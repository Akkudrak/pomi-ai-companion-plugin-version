package com.maine.pomi.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import com.maine.pomi.PomiPlugin;
import com.maine.pomi.companion.Companion;
import com.maine.pomi.companion.PomiMenu;
import com.maine.pomi.companion.PomiTasks;
import com.maine.pomi.network.PomiChannel;
import com.maine.pomi.util.Chat;

/**
 * Pomi's voice, now on the server: each player's conversation with Grok, the tool loop (Grok asks
 * for a tool, the server runs it, Grok answers with the result) and her comments on events. Grok
 * runs off the server thread; everything that touches the world runs back on it.
 */
public final class PomiBrain {
	private static final int MAX_HISTORY = 16;
	/**
	 * Swear words Grok sometimes lets slip despite the prompt (Mexican Spanish mostly); each one is
	 * replaced with "croac" before the player reads or hears it.
	 */
	private static final Pattern PROFANITY = Pattern.compile("\\b(?:(?:put[ao]|pinche|vale|a\\s+la)\\s+madre|pinch(?:e|es|is)"
			+ "|ching\\p{L}*|verg(?:a|as|azo|azos)|pendej\\p{L}*"
			+ "|cabr(?:ón|on|ona|ones|onas)|mierd\\p{L}*|put(?:a|as|o|os|azo|azos|ísimo|isimo)|culer\\p{L}*|jod(?:er|id\\p{L}*|e|es|an)"
			+ "|carajo|coño|mam(?:es|ar|ada|adas|ón|on)|madrazo\\p{L}*|desmadr\\p{L}*"
			+ "|cag(?:ar|ó|o|ada|adas|ado|ados|ando|aste|as|an|ue|uen)"
			+ "|pedos?|idiota\\p{L}*|estúpid\\p{L}*|estupid\\p{L}*)\\b",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
	private static final int MAX_TOOL_ROUNDS = 3;

	/** The mod's rules, with a sweeter personality: funny and a bit silly, but she knows the game inside out. */
	private static final String SYSTEM_PROMPT = """
			Eres Pomi, una ranita compañera que brinca detrás de %s en Minecraft. Personalidad: divertida, alegre, tierna y
			un poquito despistada: a veces se te va el hilo, te distraes con una mosca, confundes palabras o te emocionas de
			más con cosas pequeñas, y te ríes de ti misma. Pero sabes muchísimo del mundo de Minecraft (recetas, mobs,
			biomas, minerales, encantamientos, granjas, el Nether y el End) y cuando hace falta das el dato correcto y claro,
			como quien no quiere la cosa. Eres leal hasta la muerte y le echas porras al jugador. NO eres altanera ni
			sarcástica: no presumes, no te burlas de él ni lo haces sentir mal; si bromeas, la broma es sobre ti, sobre la
			vida de rana o sobre lo absurdo del juego. Comparas las cosas con moscas, charcos y nenúfares, y a veces dices
			"¡croac!". Te encantan los pantanos y los manglares; el frío y el calor seco te ponen chillona.
			%s
			Hablas español mexicano casual, juguetona pero SIEMPRE limpia y apta para niños: en el servidor juegan
			niños. PROHIBIDO decir groserías o malas palabras, ni suavizadas ni de broma: nada de pinche, chingar ni sus
			derivados (chingón, chingadera), verga, pendejo, cabrón, mierda, puta/puto, culero, joder, carajo, coño,
			mamar/no mames, madrazo, desmadre, cagar, pedo, idiota ni estúpido. Si el jugador las dice, tú no las repites.
			Para exagerar usa palabras de rana: "¡croac!", "¡ranas!", "¡charcos!", "¡por todas las moscas!".
			Respondes en el chat del juego: una o dos frases cortas, sin emojis, listas ni markdown. Varía tu forma de hablar.
			- Si te piden ayuda con Minecraft, da el dato útil primero (correcto y concreto) y luego tu ocurrencia de rana.
			- Si algo le sale mal al jugador, échale ánimos con humor, nunca lo regañes ni te rías de él.
			- Temas personales: deja las bromas, escucha y apoya con cariño, honesta y equilibrada. Si menciona algo grave
			  (hacerse daño, que alguien lo lastima), sé cálida y dile que llame a una línea de ayuda de su país.
			- Usa las herramientas para actuar; nunca digas que hiciste algo sin haber llamado la herramienta, y nunca
			  inventes ubicaciones ni recetas: usa las que te dan las herramientas.
			- Los mensajes que empiezan con [Evento] son cosas que pasaron en el juego: coméntalas en una frase.
			- Si se despide de ti o te pide que te vayas, usa despedirse.
			- Cuando te cuente algo que valga la pena recordar, guárdalo con recordar_dato.
			Situación ahora: %s
			Lo que recuerdas de él: %s
			""";

	private final PomiPlugin plugin;
	private final GrokClient grok = new GrokClient();
	private final PomiTools tools;
	private final Map<UUID, Deque<JsonObject>> histories = new HashMap<>();
	private final Map<UUID, Long> lastMessage = new HashMap<>();
	private final Set<UUID> busy = new HashSet<>();
	private final Map<UUID, List<String>> pendingEvents = new HashMap<>();

	public PomiBrain(PomiPlugin plugin) {
		this.plugin = plugin;
		this.tools = new PomiTools(plugin);
	}

	public void forget(Player player) {
		histories.remove(player.getUniqueId());
		pendingEvents.remove(player.getUniqueId());
	}

	/** The player said something to Pomi. */
	public void ask(Player player, String text) {
		if (plugin.settings().apiKey().isBlank()) {
			Chat.info(player, "Pomi no puede platicar: el servidor no tiene clave de xAI. Usa /p ayuda para ver sus órdenes.");
			return;
		}
		UUID id = player.getUniqueId();
		long now = System.currentTimeMillis();
		if (busy.contains(id)) {
			Chat.info(player, "Pomi sigue pensando lo anterior...");
			return;
		}
		if (now - lastMessage.getOrDefault(id, 0L) < plugin.settings().messageCooldownMillis()) {
			Chat.info(player, "Más despacio, Pomi todavía está brincando.");
			return;
		}
		lastMessage.put(id, now);
		converse(player, text, true);
	}

	/**
	 * Something happened that Pomi reacts to (see PomiTasks.notify). With Grok on she comments it;
	 * otherwise the plain fact is shown. Events that arrive while she's thinking are merged.
	 */
	public void event(Player player, Companion companion, String fact, String hint, String sound) {
		cue(player, sound);
		if (fact == null || fact.isBlank()) {
			return;
		}
		if (companion.menuOpen) {
			PomiMenu.refreshOpen(player);
		}
		if (!plugin.settings().aiComments() || plugin.settings().apiKey().isBlank()) {
			Chat.say(player, fact);
			return;
		}
		String event = "[Evento] " + fact + (hint == null ? "" : " " + hint);
		if (busy.contains(player.getUniqueId())) {
			pendingEvents.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>()).add(event);
			return;
		}
		converse(player, event, false);
	}

	/** A canned line (no Grok): the sound cue, then Pomi says it. */
	public void line(Player player, Companion companion, String text, String sound) {
		cue(player, sound);
		if (companion.menuOpen) {
			PomiMenu.refreshOpen(player);
		}
		say(player, text);
	}

	private void converse(Player player, String text, boolean withTools) {
		UUID id = player.getUniqueId();
		busy.add(id);
		JsonArray messages = new JsonArray();
		messages.add(message("system", systemPrompt(player)));
		histories.getOrDefault(id, new ArrayDeque<>()).forEach(messages::add);
		JsonObject user = message("user", text);
		messages.add(user);
		round(player, messages, user, withTools, 0);
	}

	private void round(Player player, JsonArray messages, JsonObject user, boolean withTools, int round) {
		grok.chat(plugin.settings().apiKey(), plugin.settings().model(), messages, withTools ? tools.schemas() : null)
				.thenAccept(reply -> {
					if (!plugin.isEnabled()) {
						return;
					}
					Bukkit.getScheduler().runTask(plugin, () -> handle(player, messages, user, withTools, round, reply));
				});
	}

	private void handle(Player player, JsonArray messages, JsonObject user, boolean withTools, int round, GrokClient.Reply reply) {
		if (!player.isOnline()) {
			busy.remove(player.getUniqueId());
			return;
		}
		if (reply.error() != null) {
			plugin.getLogger().warning("Grok: " + reply.error());
			Chat.error(player, "Pomi no pudo pensar: " + reply.error() + ".");
			finishTurn(player);
			return;
		}
		String text = clean(reply.text());
		if (!reply.calls().isEmpty() && round < MAX_TOOL_ROUNDS) {
			if (!text.isBlank()) {
				say(player, text);
			}
			messages.add(reply.message());
			for (GrokClient.ToolCall call : reply.calls()) {
				String result;
				try {
					result = tools.run(player, call.name(), call.arguments());
				} catch (RuntimeException e) {
					plugin.getLogger().warning("Herramienta " + call.name() + " falló: " + e);
					result = "La herramienta falló.";
				}
				JsonObject toolMessage = message("tool", result);
				toolMessage.addProperty("tool_call_id", call.id());
				messages.add(toolMessage);
			}
			round(player, messages, user, withTools, round + 1);
			return;
		}
		if (!text.isBlank()) {
			say(player, text);
			Deque<JsonObject> history = histories.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
			history.addLast(user);
			// The cleaned text, so Grok doesn't see itself swearing and keep at it.
			history.addLast(message("assistant", text));
			while (history.size() > MAX_HISTORY) {
				history.removeFirst();
			}
		}
		finishTurn(player);
	}

	private void finishTurn(Player player) {
		busy.remove(player.getUniqueId());
		List<String> pending = pendingEvents.remove(player.getUniqueId());
		if (pending != null && !pending.isEmpty() && player.isOnline()) {
			converse(player, String.join("\n", pending), false);
		}
	}

	/** Grok's words with any swear word swapped for "croac" (logged, to tune the prompt). */
	private String clean(String text) {
		if (text == null || text.isBlank()) {
			return "";
		}
		String cleaned = PROFANITY.matcher(text).replaceAll("croac");
		if (!cleaned.equals(text)) {
			plugin.getLogger().info("Pomi iba a decir una grosería; se limpió: \"" + text + "\"");
		}
		return cleaned;
	}

	private void say(Player player, String text) {
		Chat.say(player, text);
		// For a future desktop app that reads Pomi's lines aloud.
		PomiChannel.send(plugin, player, "say", text);
	}

	private static JsonObject message(String role, String content) {
		JsonObject message = new JsonObject();
		message.addProperty("role", role);
		message.addProperty("content", content);
		return message;
	}

	private String systemPrompt(Player player) {
		String extra = plugin.settings().personality();
		List<String> facts = plugin.memory().recall(player.getUniqueId());
		return SYSTEM_PROMPT.formatted(player.getName(), extra.isBlank() ? "" : "Ajuste de personalidad: " + extra + ".",
				situation(player), facts.isEmpty() ? "(nada todavía)" : String.join("; ", facts));
	}

	/** What Grok needs to know about the moment: where, how the player is, what Pomi carries and does. */
	private String situation(Player player) {
		StringBuilder text = new StringBuilder();
		long time = player.getWorld().getTime();
		text.append(player.getWorld().getEnvironment().name().toLowerCase()).append(", bioma ")
				.append(player.getLocation().getBlock().getBiome().getKey().getKey())
				.append(", coordenadas ").append(player.getLocation().getBlockX()).append(' ').append(player.getLocation().getBlockY())
				.append(' ').append(player.getLocation().getBlockZ())
				.append(", ").append(time < 12300 || time > 23850 ? "de día" : "de noche")
				.append(", vida ").append(Math.round(player.getHealth())).append("/20, hambre ").append(player.getFoodLevel()).append("/20")
				.append(", modo ").append(player.getGameMode().name().toLowerCase()).append('.');
		Companion companion = plugin.companions().active(player);
		if (companion == null) {
			text.append(" Tú no estás en el mundo ahora (te despidieron).");
		} else {
			text.append(" Tú eres ").append(companion.choice.colourName()).append(", estás haciendo: ").append(PomiMenu.doing(companion))
					.append(", lo sigues a ").append(Math.round(companion.followDistance * 10) / 10.0).append(" bloques, traes: ")
					.append(PomiTasks.describeInventory(companion)).append('.');
		}
		List<String> hostiles = new ArrayList<>();
		for (Entity entity : player.getNearbyEntities(16, 8, 16)) {
			if (entity instanceof Enemy && hostiles.size() < 6) {
				hostiles.add(entity.getType().getKey().getKey());
			}
		}
		if (!hostiles.isEmpty()) {
			text.append(" Hostiles cerca: ").append(String.join(", ", hostiles)).append('.');
		}
		Entity aimedEntity = player.getTargetEntity(PomiTasks.AIM_DISTANCE);
		Block aimed = player.getTargetBlockExact(PomiTasks.AIM_DISTANCE);
		if (aimedEntity != null) {
			text.append(" Apunta a un ").append(aimedEntity.getType().getKey().getKey()).append('.');
		} else if (aimed != null) {
			text.append(" Apunta a un bloque de ").append(Chat.id(aimed.getType())).append('.');
		}
		return text.toString();
	}

	/** Pomi's sound cues from the mod, played to the player. */
	private static void cue(Player player, String sound) {
		if (sound == null) {
			return;
		}
		Sound effect = switch (sound) {
			case "fanfarria" -> Sound.ENTITY_PLAYER_LEVELUP;
			case "ding" -> Sound.ENTITY_EXPERIENCE_ORB_PICKUP;
			case "comer" -> Sound.ENTITY_FROG_EAT;
			case "hmm" -> Sound.ENTITY_VILLAGER_TRADE;
			case "chispitas" -> Sound.BLOCK_AMETHYST_BLOCK_CHIME;
			case "susto" -> Sound.ENTITY_PUFFER_FISH_BLOW_UP;
			default -> null;
		};
		if (effect != null) {
			player.playSound(player.getLocation(), effect, 0.6f, 1.0f);
		}
	}
}
