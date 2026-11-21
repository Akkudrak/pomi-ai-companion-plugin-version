package com.maine.pomi.network;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import com.maine.pomi.PomiPlugin;
import com.maine.pomi.companion.Companion;
import com.maine.pomi.util.Chat;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;

/** Bukkit access stays on the server thread; HTTP and audio capture never block that thread. */
public final class PomiSimpleVoiceChat implements PomiInGameVoice, VoicechatPlugin {
	private static final int MAX_BYTES = 15 * PomiEars.SAMPLE_RATE * 2;
	private final PomiPlugin plugin;
	private final PomiEars ears;
	private final PomiSpeech speech;
	private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
	private final Map<UUID, Capture> captures = new ConcurrentHashMap<>();
	// Only accessed from the Bukkit thread.
	private final Map<UUID, Speaking> speakers = new HashMap<>();
	private final Map<UUID, Long> followUp = new HashMap<>();
	private volatile VoicechatServerApi api;
	private volatile boolean closed;
	private BukkitTask task;

	private static final class Capture {
		final OpusDecoder decoder;
		final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
		long lastVoice;
		int voicedSamples;
		boolean busy;
		boolean closed;

		Capture(OpusDecoder decoder) { this.decoder = decoder; }
		synchronized void close() {
			closed = true;
			decoder.close();
			pcm.reset();
		}
	}

	private static final class Speaking {
		final ArrayDeque<String> queue = new ArrayDeque<>();
		boolean loading;
		AudioPlayer player;
		UUID entity;
	}

	public PomiSimpleVoiceChat(PomiPlugin plugin) {
		this.plugin = plugin;
		ears = new PomiEars(plugin);
		speech = new PomiSpeech();
	}

	public boolean start() {
		BukkitVoicechatService service = plugin.getServer().getServicesManager().load(BukkitVoicechatService.class);
		if (service == null) {
			workers.shutdownNow();
			plugin.getLogger().warning("Simple Voice Chat no expone su API; revisa su instalación.");
			return false;
		}
		service.registerPlugin(this);
		task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 2L);
		return true;
	}

	@Override public String getPluginId() { return "pomi"; }

	@Override public void registerEvents(EventRegistration events) {
		events.registerEvent(VoicechatServerStartedEvent.class, event -> {
			if (!closed) {
				api = event.getVoicechat();
				plugin.getLogger().info("Pomi conectada a Simple Voice Chat.");
			}
		});
		events.registerEvent(VoicechatServerStoppedEvent.class, event -> api = null);
		events.registerEvent(MicrophonePacketEvent.class, this::microphone);
	}

	private void microphone(MicrophonePacketEvent event) {
		if (closed || api == null || event.isCancelled()) return;
		VoicechatConnection sender = event.getSenderConnection();
		// Group conversations are private and are not addressed to the nearby NPC.
		if (sender == null || sender.isInGroup() || sender.isDisabled()) return;
		Capture capture = captures.get(sender.getPlayer().getUuid());
		if (capture == null) return;
		byte[] opus = event.getPacket().getOpusEncodedData();
		if (opus.length == 0) return;
		synchronized (capture) {
			if (capture.closed || capture.busy) return;
			try {
				short[] samples = capture.decoder.decode(opus);
				long energy = 0;
				for (short sample : samples) energy += (long) sample * sample;
				boolean voiced = samples.length > 0 && energy / samples.length > 150L * 150;
				if (voiced) {
					capture.lastVoice = System.currentTimeMillis();
					capture.voicedSamples += samples.length;
				}
				if (capture.lastVoice != 0 && capture.pcm.size() < MAX_BYTES) {
					byte[] pcm = VoicePcm.downsample48k(samples);
					capture.pcm.write(pcm, 0, Math.min(pcm.length, MAX_BYTES - capture.pcm.size()));
				}
			} catch (RuntimeException e) {
				capture.pcm.reset();
				capture.lastVoice = 0;
				capture.voicedSamples = 0;
				capture.decoder.resetState();
			}
		}
	}

	private boolean nearby(Player player) {
		Companion companion = plugin.companions().get(player);
		return player.hasPermission("pomi.use") && companion != null && companion.present()
				&& companion.entity.getWorld().equals(player.getWorld())
				&& companion.entity.getLocation().distanceSquared(player.getLocation())
						<= (double) plugin.settings().voiceDistance() * plugin.settings().voiceDistance();
	}

	private void tick() {
		VoicechatServerApi current = api;
		Set<UUID> eligible = new HashSet<>();
		if (current != null && plugin.settings().simpleVoiceChat()) {
			for (Player player : plugin.getServer().getOnlinePlayers()) {
				UUID id = player.getUniqueId();
				VoicechatConnection connection = current.getConnectionOf(id);
				if (connection == null || !connection.isConnected() || connection.isDisabled() || !nearby(player)) continue;
				Speaking speaking = speakers.get(id);
				if (speaking != null && speaking.player != null && !speaking.player.isStopped()) {
					Companion companion = plugin.companions().get(player);
					if (!companion.entity.getUniqueId().equals(speaking.entity)) speaking.player.stopPlaying();
				}
				boolean talking = speaking != null && speaking.player != null && !speaking.player.isStopped();
				if (!connection.isInGroup() && !talking && !plugin.settings().whisperUrl().isBlank()) {
					eligible.add(id);
					Capture capture = captures.computeIfAbsent(id, key -> new Capture(current.createDecoder()));
					flush(id, capture);
				}
				pump(player, current);
			}
		}
		captures.entrySet().removeIf(entry -> {
			if (eligible.contains(entry.getKey())) return false;
			entry.getValue().close();
			return true;
		});
		speakers.entrySet().removeIf(entry -> {
			Player player = plugin.getServer().getPlayer(entry.getKey());
			VoicechatConnection connection = current == null ? null : current.getConnectionOf(entry.getKey());
			if (connection != null && connection.isConnected() && !connection.isDisabled()
					&& plugin.settings().simpleVoiceChat() && player != null && nearby(player)) return false;
			stop(entry.getValue());
			followUp.remove(entry.getKey());
			return true;
		});
		followUp.entrySet().removeIf(entry -> !eligible.contains(entry.getKey()) || entry.getValue() < System.currentTimeMillis());
	}

	private void flush(UUID id, Capture capture) {
		byte[] pcm;
		synchronized (capture) {
			if (capture.busy || capture.lastVoice == 0 || (capture.pcm.size() < MAX_BYTES
					&& System.currentTimeMillis() - capture.lastVoice < 700)) return;
			pcm = capture.voicedSamples >= 48_000 / 5 ? capture.pcm.toByteArray() : null;
			capture.pcm.reset();
			capture.lastVoice = 0;
			capture.voicedSamples = 0;
			capture.decoder.resetState();
			if (pcm == null) return;
			capture.busy = true;
		}
		workers.submit(() -> {
			try {
				String text = ears.transcribe(pcm);
				onMain(() -> {
					if (captures.get(id) == capture && plugin.settings().simpleVoiceChat()) heard(id, text);
				});
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (Exception e) {
				plugin.getLogger().warning("Pomi: no se pudo transcribir la voz: " + e.getMessage());
			} finally {
				synchronized (capture) { capture.busy = false; }
			}
		});
	}

	private void heard(UUID id, String text) {
		Player player = plugin.getServer().getPlayer(id);
		if (player == null || !nearby(player)) return;
		VoicechatServerApi current = api;
		VoicechatConnection connection = current == null ? null : current.getConnectionOf(id);
		if (connection == null || !connection.isConnected() || connection.isInGroup() || connection.isDisabled()) return;
		int wake = PomiEars.wakeEnd(text);
		String order = PomiEars.asCommand(wake >= 0 ? text.substring(wake) : text);
		if (wake >= 0 && order.isEmpty()) {
			followUp.put(id, System.currentTimeMillis() + 7_000);
			Chat.info(player, "Te escucho; dime qué necesitas.");
		} else if (!order.isEmpty() && (wake >= 0 || followUp.getOrDefault(id, 0L) > System.currentTimeMillis())) {
			followUp.remove(id);
			Chat.info(player, "Tú, por voz: " + order);
			player.performCommand("pomi " + order);
		}
	}

	@Override public void publish(Player player, String text) {
		if (closed || api == null || !plugin.settings().simpleVoiceChat() || text == null || text.isBlank() || !nearby(player)) return;
		VoicechatConnection connection = api.getConnectionOf(player.getUniqueId());
		if (connection == null || !connection.isConnected() || connection.isDisabled()) return;
		Speaking speaking = speakers.computeIfAbsent(player.getUniqueId(), key -> new Speaking());
		if (speaking.queue.size() < 5) speaking.queue.addLast(text);
	}

	private void pump(Player owner, VoicechatServerApi current) {
		UUID id = owner.getUniqueId();
		Speaking speaking = speakers.get(id);
		if (speaking == null || speaking.loading || speaking.queue.isEmpty()
				|| (speaking.player != null && !speaking.player.isStopped())) return;
		speaking.loading = true;
		String text = speaking.queue.removeFirst();
		UUID frog = plugin.companions().get(owner).entity.getUniqueId();
		workers.submit(() -> {
			try {
				short[] audio = speech.synthesize(plugin.settings().kokoroUrl(), plugin.settings().kokoroVoice(),
						plugin.settings().kokoroSpeed(), text);
				onMain(() -> {
					Player player = plugin.getServer().getPlayer(id);
					if (api != current || speakers.get(id) != speaking || player == null || !nearby(player)
							|| !plugin.settings().simpleVoiceChat()) return;
					VoicechatConnection connection = current.getConnectionOf(id);
					if (connection == null || !connection.isConnected() || connection.isDisabled()) return;
					Companion companion = plugin.companions().get(player);
					if (!companion.entity.getUniqueId().equals(frog)) return;
					EntityAudioChannel channel = current.createEntityAudioChannel(UUID.randomUUID(), current.fromEntity(companion.entity));
					if (channel == null) return;
					channel.setDistance(plugin.settings().voiceDistance());
					// Match the existing per-player chat: only the owner hears this Pomi.
					channel.setFilter(listener -> listener.getUuid().equals(id));
					OpusEncoder encoder = current.createEncoder();
					try {
						speaking.player = current.createAudioPlayer(channel, encoder, audio);
						speaking.entity = frog;
						speaking.player.setOnStopped(encoder::close);
						speaking.player.startPlaying();
					} catch (RuntimeException e) {
						encoder.close();
						throw e;
					}
				});
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (Exception e) {
				plugin.getLogger().warning("Pomi: no se pudo generar la voz: " + e.getMessage());
			} finally {
				onMain(() -> speaking.loading = false);
			}
		});
	}

	private void onMain(Runnable action) {
		if (!closed) {
			plugin.getServer().getScheduler().runTask(plugin, () -> { if (!closed) action.run(); });
		}
	}

	@Override public String status(Player player) {
		if (!plugin.settings().simpleVoiceChat()) return "Simple Voice Chat desactivado en la configuración de Pomi.";
		if (api == null) return "Esperando que arranque Simple Voice Chat.";
		VoicechatConnection connection = api.getConnectionOf(player.getUniqueId());
		if (connection == null || !connection.isConnected()) return "Conecta Simple Voice Chat en tu cliente para hablar con Pomi.";
		if (connection.isDisabled()) return "Activa el audio de Simple Voice Chat en tu cliente.";
		if (plugin.settings().kokoroUrl().isBlank()) return "Falta kokoro-url para que Pomi responda con voz.";
		if (plugin.settings().whisperUrl().isBlank()) return "Falta whisper-url para que Pomi te escuche.";
		if (connection.isInGroup()) return "Sal del grupo de Simple Voice Chat para que Pomi te escuche por proximidad.";
		return "Simple Voice Chat listo: acércate a tu Pomi (" + plugin.settings().voiceDistance() + " bloques) y di ‘Pomi, ven’.";
	}

	private void stop(Speaking speaking) {
		if (speaking.player != null && !speaking.player.isStopped()) speaking.player.stopPlaying();
		speaking.queue.clear();
	}

	@Override public void shutdown() {
		closed = true;
		api = null;
		if (task != null) task.cancel();
		captures.values().forEach(Capture::close);
		captures.clear();
		speakers.values().forEach(this::stop);
		speakers.clear();
		followUp.clear();
		workers.shutdownNow();
	}
}
