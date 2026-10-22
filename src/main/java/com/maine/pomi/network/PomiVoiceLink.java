package com.maine.pomi.network;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.entity.Player;

import com.google.gson.JsonObject;
import com.maine.pomi.PomiPlugin;
import com.maine.pomi.util.Chat;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The link to "Pomi Voz", the desktop app that reads Pomi's lines out loud (vanilla clients can't
 * play new audio, so the voice lives in a separate app). The player types {@code /p voz}, gets a
 * code, and enters it in the app with the server's address; from then on the app is linked to that
 * player (the token survives restarts) and:
 * <ul>
 * <li>{@code POST /vincular} (body: the code) → the token, for the app to keep;
 * <li>{@code GET /eventos} (Bearer token) → a Server-Sent Events stream, one event per line Pomi says
 * to that player ({@code id} = the line's number, {@code data} = its text);
 * <li>{@code GET /audio/<id>} (Bearer token) → the line spoken by Kokoro: 24 kHz 16-bit mono PCM;
 * <li>{@code POST /escuchar} (Bearer token, body: 16 kHz 16-bit mono PCM of one thing the player
 * said; {@code ?tecla=1} if it was said holding the talk key, so it needs no name) → Whisper's
 * transcript and what came of it: {@code ignorado} (not for Pomi),
 * {@code escuchando} (just "Pomi": the next thing said within 7 s is the order) or {@code enviado}
 * (run as {@code /p <order>}).
 * </ul>
 * The server talks to Kokoro, so players only need to reach this port.
 */
public final class PomiVoiceLink {
	private static final String LINKS_FILE = "voz-vinculos.properties";
	private static final long CODE_MILLIS = 5 * 60 * 1000L;
	private static final int KEPT_LINES = 200;
	private static final long PING_SECONDS = 20;
	private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
	/** Longest utterance accepted: 15 s of 16 kHz 16-bit mono. */
	private static final int MAX_HEARD_BYTES = 15 * PomiEars.SAMPLE_RATE * 2;
	/** After a bare "Pomi", the next thing said within this time is the order. */
	private static final long FOLLOW_UP_MILLIS = 7_000;

	private record Code(UUID player, String name, long expires) {
	}

	private record Line(UUID player, String text) {
	}

	private final PomiPlugin plugin;
	private final SecureRandom random = new SecureRandom();
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private final Map<String, Code> codes = new ConcurrentHashMap<>();
	/** Token → player. Saved in voz-vinculos.properties. */
	private final Map<String, UUID> tokens = new ConcurrentHashMap<>();
	/** Open event streams per player; each gets every new line. */
	private final Map<UUID, Set<BlockingQueue<Long>>> streams = new ConcurrentHashMap<>();
	private final AtomicLong nextId = new AtomicLong(1);
	private final PomiEars ears;
	/** Players whose last utterance is still being transcribed (one at a time each). */
	private final Set<UUID> hearing = ConcurrentHashMap.newKeySet();
	private final Map<UUID, Long> followUpUntil = new ConcurrentHashMap<>();
	private final Map<Long, Line> lines = new LinkedHashMap<>() {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, Line> eldest) {
			return size() > KEPT_LINES;
		}
	};
	private HttpServer server;

	public PomiVoiceLink(PomiPlugin plugin) {
		this.plugin = plugin;
		this.ears = new PomiEars(plugin);
	}

	public void start() {
		if (!plugin.settings().voice()) {
			return;
		}
		loadTokens();
		int port = plugin.settings().voicePort();
		try {
			server = HttpServer.create(new InetSocketAddress(port), 0);
		} catch (IOException e) {
			plugin.getLogger().warning("No se pudo abrir el puerto " + port + " para Pomi Voz: " + e);
			return;
		}
		server.createContext("/vincular", exchange -> handle(exchange, this::link));
		server.createContext("/eventos", exchange -> handle(exchange, this::events));
		server.createContext("/audio/", exchange -> handle(exchange, this::audio));
		server.createContext("/escuchar", exchange -> handle(exchange, this::hear));
		// Every open stream holds a thread: virtual ones cost nothing while they wait.
		server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
		server.start();
		plugin.getLogger().info("Pomi Voz escucha en el puerto " + port + ".");
	}

	public void shutdown() {
		if (server != null) {
			server.stop(0);
			server = null;
		}
		streams.clear();
	}

	public boolean running() {
		return server != null;
	}

	/** "/p voz": a fresh code for the app, valid for 5 minutes. */
	public String newCode(Player player) {
		codes.values().removeIf(code -> code.player().equals(player.getUniqueId()) || code.expires() < System.currentTimeMillis());
		StringBuilder code = new StringBuilder();
		for (int i = 0; i < 6; i++) {
			code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
		}
		codes.put(code.toString(), new Code(player.getUniqueId(), player.getName(), System.currentTimeMillis() + CODE_MILLIS));
		return code.toString();
	}

	/** "/p voz quitar": unlinks every app of the player. Returns how many there were. */
	public int unlink(Player player) {
		int before = tokens.size();
		tokens.values().removeIf(player.getUniqueId()::equals);
		Set<BlockingQueue<Long>> open = streams.remove(player.getUniqueId());
		if (open != null) {
			open.forEach(queue -> queue.offer(-1L));
		}
		saveTokens();
		return before - tokens.size();
	}

	/** The address to type in the app: the one the player joined with, on the voice port. */
	public String address(Player player) {
		InetSocketAddress joined = player.getVirtualHost();
		String host = joined == null ? "" : joined.getHostString();
		return (host.isBlank() ? "<ip-del-servidor>" : host) + ":" + plugin.settings().voicePort();
	}

	/** Pomi said {@code text} to {@code player}: the linked apps read it out loud. */
	public void publish(Player player, String text) {
		Set<BlockingQueue<Long>> open = streams.get(player.getUniqueId());
		if (open == null || open.isEmpty() || text == null || text.isBlank()) {
			return;
		}
		long id = nextId.getAndIncrement();
		synchronized (lines) {
			lines.put(id, new Line(player.getUniqueId(), text.replace('\n', ' ').strip()));
		}
		open.forEach(queue -> queue.offer(id));
	}

	// ---- HTTP ---------------------------------------------------------------------------------

	private interface Handler {
		void handle(HttpExchange exchange) throws IOException, InterruptedException;
	}

	private void handle(HttpExchange exchange, Handler handler) {
		try (exchange) {
			handler.handle(exchange);
		} catch (IOException e) {
			// The app went away; nothing to do.
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (RuntimeException e) {
			plugin.getLogger().warning("Pomi Voz: " + e);
		}
	}

	private void link(HttpExchange exchange) throws IOException {
		if (!exchange.getRequestMethod().equals("POST")) {
			reply(exchange, 405, "Usa POST.");
			return;
		}
		String typed = new String(exchange.getRequestBody().readNBytes(64), StandardCharsets.UTF_8).strip().toUpperCase();
		Code code = codes.remove(typed);
		if (code == null || code.expires() < System.currentTimeMillis()) {
			reply(exchange, 403, "Código inválido o vencido. Pide otro con /p voz.");
			return;
		}
		byte[] bytes = new byte[24];
		random.nextBytes(bytes);
		String token = HexFormat.of().formatHex(bytes);
		tokens.put(token, code.player());
		saveTokens();
		JsonObject answer = new JsonObject();
		answer.addProperty("token", token);
		answer.addProperty("jugador", code.name());
		reply(exchange, 200, answer.toString());
	}

	private void events(HttpExchange exchange) throws IOException, InterruptedException {
		UUID player = authorized(exchange);
		if (player == null) {
			return;
		}
		exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
		exchange.getResponseHeaders().set("Cache-Control", "no-cache");
		exchange.sendResponseHeaders(200, 0);
		BlockingQueue<Long> queue = new LinkedBlockingQueue<>();
		streams.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet()).add(queue);
		try (OutputStream body = exchange.getResponseBody()) {
			write(body, ": conectado\n\n");
			while (server != null) {
				Long id = queue.poll(PING_SECONDS, TimeUnit.SECONDS);
				if (id == null) {
					write(body, ": ping\n\n");
				} else if (id < 0) {
					return;
				} else {
					Line line;
					synchronized (lines) {
						line = lines.get(id);
					}
					if (line != null) {
						write(body, "id: " + id + "\ndata: " + line.text() + "\n\n");
					}
				}
			}
		} finally {
			Set<BlockingQueue<Long>> open = streams.get(player);
			if (open != null) {
				open.remove(queue);
			}
		}
	}

	private void audio(HttpExchange exchange) throws IOException, InterruptedException {
		UUID player = authorized(exchange);
		if (player == null) {
			return;
		}
		Line line;
		try {
			long id = Long.parseLong(exchange.getRequestURI().getPath().substring("/audio/".length()));
			synchronized (lines) {
				line = lines.get(id);
			}
		} catch (NumberFormatException e) {
			line = null;
		}
		if (line == null || !line.player().equals(player)) {
			reply(exchange, 404, "Esa frase ya no está.");
			return;
		}
		String url = plugin.settings().kokoroUrl();
		if (url.isBlank()) {
			reply(exchange, 503, "El servidor no tiene Kokoro configurado (kokoro-url).");
			return;
		}
		JsonObject body = new JsonObject();
		body.addProperty("model", "kokoro");
		body.addProperty("input", speakable(line.text()));
		body.addProperty("voice", plugin.settings().kokoroVoice());
		body.addProperty("speed", plugin.settings().kokoroSpeed());
		body.addProperty("response_format", "pcm");
		body.addProperty("stream", false);
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(30))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
				.build();
		HttpResponse<InputStream> spoken;
		try {
			spoken = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
		} catch (IOException e) {
			plugin.getLogger().warning("Pomi Voz: Kokoro no responde en " + url + ": " + e);
			reply(exchange, 502, "Kokoro no responde.");
			return;
		}
		try (InputStream pcm = spoken.body()) {
			if (spoken.statusCode() != 200) {
				reply(exchange, 502, "Kokoro respondió " + spoken.statusCode() + ".");
				return;
			}
			exchange.getResponseHeaders().set("Content-Type", "audio/L16; rate=24000; channels=1");
			exchange.sendResponseHeaders(200, 0);
			try (OutputStream out = exchange.getResponseBody()) {
				pcm.transferTo(out);
			}
		}
	}

	private void hear(HttpExchange exchange) throws IOException, InterruptedException {
		UUID player = authorized(exchange);
		if (player == null) {
			return;
		}
		if (plugin.settings().whisperUrl().isBlank()) {
			reply(exchange, 503, "El servidor no tiene Whisper configurado (whisper-url).");
			return;
		}
		byte[] pcm = exchange.getRequestBody().readNBytes(MAX_HEARD_BYTES);
		if (!hearing.add(player)) {
			reply(exchange, 429, "Todavía estoy entendiendo lo anterior.");
			return;
		}
		String text;
		try {
			text = ears.transcribe(pcm);
		} catch (IOException e) {
			plugin.getLogger().warning("Pomi Voz: Whisper no responde en " + plugin.settings().whisperUrl() + ": " + e);
			reply(exchange, 502, "Whisper no responde.");
			return;
		} finally {
			hearing.remove(player);
		}
		String state = "ignorado";
		int wakeEnd = text.isEmpty() ? -1 : PomiEars.wakeEnd(text);
		String order = PomiEars.asCommand(wakeEnd >= 0 ? text.substring(wakeEnd) : text);
		// Said while holding the app's talk key: it's for Pomi, name or not.
		String query = exchange.getRequestURI().getQuery();
		boolean fromKey = query != null && query.contains("tecla=1");
		boolean followingUp = fromKey || followUpUntil.getOrDefault(player, 0L) > System.currentTimeMillis();
		if (wakeEnd >= 0 && order.isEmpty()) {
			// Just "Pomi": she perks up and waits for the order.
			followUpUntil.put(player, System.currentTimeMillis() + FOLLOW_UP_MILLIS);
			state = "escuchando";
		} else if (!order.isEmpty() && (wakeEnd >= 0 || followingUp)) {
			followUpUntil.remove(player);
			state = "enviado";
			plugin.getServer().getScheduler().runTask(plugin, () -> {
				Player online = plugin.getServer().getPlayer(player);
				if (online != null) {
					Chat.info(online, "Tú, por voz: " + order);
					online.performCommand("pomi " + order);
				}
			});
		}
		plugin.getLogger().info("Pomi Voz oyó" + (fromKey ? " (tecla)" : "") + ": \"" + text + "\" → " + state);
		JsonObject answer = new JsonObject();
		answer.addProperty("texto", text);
		answer.addProperty("estado", state);
		answer.addProperty("orden", state.equals("enviado") ? order : "");
		reply(exchange, 200, answer.toString());
	}

	/** The player behind the request's Bearer token, or null (and a 401 sent). */
	private UUID authorized(HttpExchange exchange) throws IOException {
		String header = exchange.getRequestHeaders().getFirst("Authorization");
		UUID player = header != null && header.startsWith("Bearer ") ? tokens.get(header.substring(7).strip()) : null;
		if (player == null) {
			reply(exchange, 401, "No vinculado. Pide un código con /p voz.");
		}
		return player;
	}

	private static void reply(HttpExchange exchange, int status, String text) throws IOException {
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", text.startsWith("{") ? "application/json; charset=utf-8" : "text/plain; charset=utf-8");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private static void write(OutputStream body, String text) throws IOException {
		body.write(text.getBytes(StandardCharsets.UTF_8));
		body.flush();
	}

	/** What Kokoro should read: no music notes or markdown it would spell out. */
	private static String speakable(String text) {
		return text.replaceAll("[♪*_~#]", "").replaceAll("\\s+", " ").strip();
	}

	// ---- Links on disk ------------------------------------------------------------------------

	private Path linksFile() {
		return plugin.getDataFolder().toPath().resolve(LINKS_FILE);
	}

	private void loadTokens() {
		Path file = linksFile();
		if (!Files.isRegularFile(file)) {
			return;
		}
		Properties saved = new Properties();
		try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			saved.load(in);
		} catch (IOException e) {
			plugin.getLogger().warning("No se pudo leer " + LINKS_FILE + ": " + e);
			return;
		}
		saved.forEach((token, player) -> {
			try {
				tokens.put(token.toString(), UUID.fromString(player.toString()));
			} catch (IllegalArgumentException e) {
				// Hand-edited line; skip it.
			}
		});
	}

	private synchronized void saveTokens() {
		Properties saved = new Properties();
		tokens.forEach((token, player) -> saved.setProperty(token, player.toString()));
		try {
			Files.createDirectories(linksFile().getParent());
			try (Writer out = Files.newBufferedWriter(linksFile(), StandardCharsets.UTF_8)) {
				saved.store(out, "Apps de Pomi Voz vinculadas: token=jugador. Borra una línea para desvincularla.");
			}
		} catch (IOException e) {
			plugin.getLogger().warning("No se pudo guardar " + LINKS_FILE + ": " + e);
		}
	}
}
