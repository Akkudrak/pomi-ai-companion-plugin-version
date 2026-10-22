package com.maine.pomi.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import com.maine.pomi.CompanionManager;
import com.maine.pomi.PomiPlugin;
import com.maine.pomi.companion.Companion;
import com.maine.pomi.companion.CompanionBuild;
import com.maine.pomi.companion.CompanionCraft;
import com.maine.pomi.companion.CompanionPlan;
import com.maine.pomi.companion.CompanionRoutine;
import com.maine.pomi.companion.CompanionWork;
import com.maine.pomi.companion.Herobrine;
import com.maine.pomi.companion.PomiChoice;
import com.maine.pomi.companion.PomiLines;
import com.maine.pomi.companion.PomiMenu;
import com.maine.pomi.companion.PomiTasks;
import com.maine.pomi.network.BiomeLocator;
import com.maine.pomi.network.PomiGuide;
import com.maine.pomi.network.PomiRequests;
import com.maine.pomi.network.PomiVoiceLink;
import com.maine.pomi.util.Chat;
import com.maine.pomi.util.MathUtil;

/**
 * {@code /p} (or {@code /pomi}). Alone it calls Pomi; {@code /p adios} sends her away until called
 * again. Known words are direct orders that work without Grok; anything else is said to Pomi.
 */
public final class PomiCommand implements CommandExecutor, TabCompleter {
	private static final Set<String> FAREWELLS = Set.of("adios", "adiós", "bye", "chao", "chau", "vete", "largate", "lárgate",
			"despedir", "hastaluego");
	private static final List<String> ORDERS = List.of("ayuda", "adios", "seguir", "quieta", "ven", "ve", "detener", "guardia",
			"atacar", "recoger", "soltar", "devolver", "iluminar", "recolectar", "cultivar", "quitar", "construir", "craftear",
			"receta", "baila", "canta", "salta", "saluda", "croa", "dame", "toma", "color", "bioma", "muerte", "siguiente", "estado", "herobrine", "olvidar", "voz", "modelo", "recargar");

	/** "consigue madera", "tráeme 10 de hierro": gathering in plain words, without Grok. */
	private static final Set<String> GATHER_VERBS = Set.of("consigue", "consigueme", "consígueme", "junta", "juntame", "júntame",
			"trae", "traeme", "tráeme", "busca", "buscame", "búscame", "recolecta", "saca", "sacame", "sácame", "corta", "tala", "mina");
	/** Amounts said out loud ("consigue diez de madera"): Whisper writes small numbers as words. */
	private static final Map<String, Integer> NUMBER_WORDS = Map.ofEntries(Map.entry("un", 1), Map.entry("uno", 1),
			Map.entry("una", 1), Map.entry("dos", 2), Map.entry("tres", 3), Map.entry("cuatro", 4), Map.entry("cinco", 5),
			Map.entry("seis", 6), Map.entry("siete", 7), Map.entry("ocho", 8), Map.entry("nueve", 9), Map.entry("diez", 10),
			Map.entry("doce", 12), Map.entry("quince", 15), Map.entry("veinte", 20), Map.entry("treinta", 30),
			Map.entry("cuarenta", 40), Map.entry("cincuenta", 50));
	/** Words that mean "some resource" without saying which. */
	private static final Set<String> ANY_RESOURCE = Set.of("recursos", "recurso", "materiales", "material", "cosas");

	private final PomiPlugin plugin;

	public PomiCommand(PomiPlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!(sender instanceof Player player)) {
			sender.sendMessage("Pomi solo acompaña a jugadores.");
			return true;
		}
		CompanionManager companions = plugin.companions();
		if (args.length == 0) {
			if (!companions.summon(player)) {
				Chat.info(player, "Pomi ya está contigo. Escribe /p ayuda para ver lo que sabe hacer.");
			}
			return true;
		}
		String first = args[0].toLowerCase(Locale.ROOT);
		if (FAREWELLS.contains(first)) {
			if (companions.dismiss(player)) {
				Chat.say(player, "Bueno, me voy a mi charco... llámame con /p cuando me extrañes. ¡Croac!");
			} else {
				Chat.info(player, "Pomi no está contigo. Llámala con /p.");
			}
			return true;
		}
		switch (first) {
			case "ayuda", "help", "?" -> {
				help(player);
				return true;
			}
			case "modelo", "recargar" -> {
				admin(player, first, args);
				return true;
			}
			case "voz" -> {
				voice(player, args);
				return true;
			}
			case "olvidar" -> {
				plugin.brain().forget(player);
				Chat.info(player, "Pomi olvidó la plática de ahora (lo que guardó con recordar_dato sigue).");
				return true;
			}
			case "bioma" -> {
				Chat.say(player, args.length > 1 ? BiomeLocator.locate(player, join(args, 1)) : "Uso: /p bioma <nombre>");
				return true;
			}
			case "muerte" -> {
				Chat.say(player, PomiGuide.whereDied(player));
				return true;
			}
			case "siguiente" -> {
				Chat.say(player, PomiGuide.nextSteps(player));
				return true;
			}
			case "receta" -> {
				Chat.say(player, args.length > 1 ? PomiRequests.recipes(args[1]) : "Uso: /p receta <item>");
				return true;
			}
			default -> {
			}
		}

		// Everything else needs Pomi here: talking or an order calls her first.
		companions.summon(player);
		Companion companion = companions.active(player);
		if (companion == null) {
			Chat.error(player, "Pomi no pudo aparecer aquí.");
			return true;
		}
		if (GATHER_VERBS.contains(first)) {
			String gathered = gather(player, companion, first, args);
			if (gathered != null) {
				Chat.say(player, gathered);
				return true;
			}
		}
		if (!ORDERS.contains(first) && !isAlias(first)) {
			plugin.brain().ask(player, String.join(" ", args));
			return true;
		}
		String result = order(player, companion, first, args);
		if (result != null) {
			Chat.say(player, result);
		}
		return true;
	}

	private static boolean isAlias(String word) {
		return switch (word) {
			case "sigueme", "quedate", "stop", "defiendeme", "cubreme", "info", "hola", "bailar", "cantar", "brinca", "croac" -> true;
			default -> false;
		};
	}

	/** A direct order. The texts are Pomi's own lines, short. Returns what to show, or null. */
	private String order(Player player, Companion companion, String word, String[] args) {
		CompanionManager companions = plugin.companions();
		return switch (word) {
			case "seguir", "sigueme" -> {
				companions.follow(player, companion, args.length > 1 ? number(args[1], 0) : 0);
				yield "Te sigo a " + Math.round(companion.followDistance * 10) / 10.0 + " bloques.";
			}
			case "quieta", "quedate" -> {
				companions.stay(player, companion);
				yield "Me quedo aquí. Ni se te ocurra olvidarme.";
			}
			case "ven" -> {
				companions.come(player, companion);
				yield "¡Ya llegué!";
			}
			case "ve" -> plain(companions.goTo(player, companion, null));
			case "detener", "stop" -> {
				String was = companions.stopAll(player, companion);
				yield was.isEmpty() ? "No estaba haciendo nada, pero bueno, aquí sigo." : "Ya dejé de " + was + ".";
			}
			case "guardia", "defiendeme", "cubreme" -> {
				PomiTasks.startGuard(player, companion);
				yield "¡A ver quién se atreve! Te cubro.";
			}
			case "atacar" -> {
				boolean kill = args.length > 1 && args[args.length - 1].equalsIgnoreCase("matar");
				String mob = args.length > 1 && !args[1].equalsIgnoreCase("matar") ? args[1] : "";
				yield plain(PomiTasks.startAttack(player, companion, PomiTasks.findTarget(player, companion, mob), kill));
			}
			case "recoger" -> plain(PomiTasks.startPickup(player, companion, args.length > 1 ? args[1] : ""));
			case "soltar" ->plain(PomiTasks.throwItems(player, companion, args.length > 1 ? args[1] : ""));
			case "devolver" -> plain(PomiTasks.returnTool(player, companion));
			case "iluminar" ->plain(PomiTasks.illuminate(player, companion, args.length > 1 ? (int) number(args[1], 12) : 12));
			case "recolectar" -> {
				if (args.length < 2) {
					yield "Uso: /p recolectar <recurso> [cantidad]. Recursos: " + CompanionWork.resourceNames() + ".";
				}
				CompanionPlan.cancel(companion);
				yield plain(CompanionWork.startGather(player, companion, args[1], args.length > 2 ? (int) number(args[2], 16) : 16));
			}
			case "cultivar" ->plain(CompanionWork.startFarm(player, companion, args.length > 1 ? args[1] : "todo"));
			case "quitar" -> {
				CompanionPlan.cancel(companion);
				yield plain(CompanionWork.startRemove(player, companion, player.getTargetBlockExact(PomiTasks.AIM_DISTANCE),
						args.length > 1 ? (int) number(args[1], 1) : 1));
			}
			case "construir" -> build(player, companion, args);
			case "craftear" ->args.length < 2 ? "Uso: /p craftear <item> [veces]"
					: plain(CompanionCraft.craft(player, companion, args[1], args.length > 2 ? (int) number(args[2], 1) : 1));
			case "baila", "bailar" -> act(CompanionRoutine.dance(player, companion), PomiLines.DANCE);
			case "canta", "cantar" -> act(CompanionRoutine.sing(player, companion), PomiLines.SING);
			case "salta", "brinca" -> act(CompanionRoutine.jump(player, companion), PomiLines.JUMP);
			case "saluda", "hola" -> act(CompanionRoutine.greet(player, companion), PomiLines.GREET);
			case "croa", "croac" -> act(CompanionRoutine.croak(player, companion), PomiLines.CROAK);
			case "dame" -> PomiTasks.throwItems(player, companion, "").startsWith("Le lanzaste") ? PomiLines.GIVE.pick()
					: PomiLines.GIVE_EMPTY.pick();
			case "toma" -> PomiTasks.takeFromHand(player, companion);
			case "color" -> args.length < 2 ? "Uso: /p color <" + String.join("|", PomiChoice.COLOURS.keySet()) + ">"
					: plain(PomiTasks.recolor(player, companion, args[1]));
			case "estado", "info" -> {
				PomiMenu.open(player, companion);
				yield null;
			}
			case "herobrine" -> {
				Herobrine.summon(player, companion);
				yield "...¿escuchaste eso? Mejor no voltees.";
			}
			default -> null;
		};
	}

	/** /p construir pared <largo> <alto> [atravesada] [item] | /p construir columna <alto> [item]. */
	private static String build(Player player, Companion companion, String[] args) {
		if (args.length < 3) {
			return "Uso: /p construir pared <largo> <alto> [atravesada] [item]  o  /p construir columna <alto> [item]";
		}
		boolean column = args[1].equalsIgnoreCase("columna");
		if (column) {
			return plain(CompanionBuild.start(player, companion, true, 1, (int) number(args[2], 3), false, args.length > 3 ? args[3] : ""));
		}
		int length = (int) number(args[2], 5);
		int height = args.length > 3 ? (int) number(args[3], 3) : 3;
		boolean across = false;
		String item = "";
		for (int i = 4; i < args.length; i++) {
			if (args[i].equalsIgnoreCase("atravesada")) {
				across = true;
			} else {
				item = args[i];
			}
		}
		return plain(CompanionBuild.start(player, companion, false, length, height, across, item));
	}

	/** "/p voz": a code for the Pomi Voz app. "/p voz quitar": unlinks the player's apps. */
	private void voice(Player player, String[] args) {
		PomiVoiceLink link = plugin.voice();
		if (link == null || !link.running()) {
			Chat.error(player, "La voz de Pomi no está activa en este servidor.");
			return;
		}
		if (args.length > 1 && args[1].equalsIgnoreCase("quitar")) {
			int removed = link.unlink(player);
			Chat.info(player, removed == 0 ? "No tenías ninguna app de voz vinculada." : "Listo, desvinculé " + removed + " app(s) de voz.");
			return;
		}
		String code = link.newCode(player);
		Chat.info(player, "Abre la app Pomi Voz y escribe:");
		Chat.info(player, "  Servidor: " + link.address(player));
		Chat.info(player, "  Código: " + code + "  (vale 5 minutos)");
	}

	private void admin(Player player, String word, String[] args) {
		if (!player.hasPermission("pomi.admin")) {
			Chat.error(player, "Eso solo lo puede hacer un administrador.");
			return;
		}
		if (word.equals("recargar")) {
			plugin.settings().reload();
			Chat.info(player, "Configuración recargada. Modelo: " + plugin.settings().model() + ".");
		} else if (args.length > 1) {
			plugin.settings().set("model", args[1]);
			Chat.info(player, "Modelo cambiado a " + args[1] + ".");
		} else {
			Chat.info(player, "Modelo actual: " + plugin.settings().model() + ". Cámbialo con /p modelo <id>.");
		}
	}

	/**
	 * "consigue madera", "trae 10 de hierro", "tala": the resource and the amount from the words.
	 * Null when no known resource is named, so Grok gets the sentence.
	 */
	private static String gather(Player player, Companion companion, String verb, String[] args) {
		String resource = null;
		int amount = 16;
		boolean vague = false;
		for (int i = 1; i < args.length; i++) {
			String word = args[i].toLowerCase(Locale.ROOT);
			if (word.matches("\\d+")) {
				amount = (int) number(word, 16);
			} else if (NUMBER_WORDS.containsKey(word)) {
				amount = NUMBER_WORDS.get(word);
			} else if (resource == null && CompanionWork.knows(word)) {
				resource = word;
			} else if (resource == null && word.endsWith("s") && CompanionWork.knows(word.substring(0, word.length() - 1))) {
				resource = word.substring(0, word.length() - 1);
			} else if (ANY_RESOURCE.contains(word)) {
				vague = true;
			}
		}
		if (resource == null && (verb.equals("corta") || verb.equals("tala"))) {
			resource = "madera";
		}
		if (resource == null) {
			return vague || args.length == 1 ? "¿Qué junto? Dime cuál: " + CompanionWork.resourceNames() + "." : null;
		}
		CompanionPlan.cancel(companion);
		return plain(CompanionWork.startGather(player, companion, resource, amount));
	}

	/** A one-word routine: a canned line if it started, otherwise why not. */
	private static String act(String result, PomiLines.Pool lines) {
		return result.startsWith("Empezaste") ? lines.pick() : plain(result);
	}

	/**
	 * The task results are written for Grok ("di que vas", "Dile que..."); for a direct order the
	 * player sees the first sentence, which is the fact.
	 */
	private static String plain(String forLlm) {
		if (forLlm == null) {
			return null;
		}
		String[] cuts = {" Al terminar llegará", " Dilo", " Díselo", " Dile", " Responde", " Presume", " Celebra", " Contesta", " Pide al", " Pídele"};
		String text = forLlm;
		for (String cut : cuts) {
			int at = text.indexOf(cut);
			if (at > 0) {
				text = text.substring(0, at);
			}
		}
		return text.strip();
	}

	private static double number(String text, double fallback) {
		try {
			return Double.parseDouble(text.replace(',', '.'));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static String join(String[] args, int from) {
		StringBuilder text = new StringBuilder();
		for (int i = from; i < args.length; i++) {
			text.append(i > from ? " " : "").append(args[i]);
		}
		return text.toString();
	}

	private static void help(Player player) {
		Chat.info(player, "/p — llama a Pomi · /p adios — se va hasta que la llames");
		Chat.info(player, "/p <lo que sea> — platica con ella (Grok) y te entiende órdenes");
		Chat.info(player, "Órdenes: seguir [dist], quieta, ven, ve (a donde apuntas), detener, guardia, atacar [mob] [matar]");
		Chat.info(player, "recoger [item], soltar [item], devolver, iluminar [n], recolectar <recurso> [n], cultivar [cosechar|plantar]");
		Chat.info(player, "quitar [n], construir pared <largo> <alto> | columna <alto>, craftear <item> [n], color <"
				+ String.join("|", PomiChoice.COLOURS.keySet()) + ">");
		Chat.info(player, "Sin Grok: baila, canta, salta, saluda, croa, dame (te da lo que trae), toma (agarra lo de tu mano)");
		Chat.info(player, "voz — vincula la app Pomi Voz para oírla · voz quitar — la desvincula");
		Chat.info(player, "Info: receta <item>, bioma <nombre>, muerte, siguiente, estado · clic derecho en ella abre su ventana");
		Chat.info(player, "Dale cosas lanzándoselas con Q. Recursos: " + CompanionWork.resourceNames() + ".");
		Chat.info(player, "Hasta " + MathUtil.clamp(CompanionBuild.MAX_SIZE, 1, 10) + " bloques por lado al construir.");
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		List<String> options = new ArrayList<>();
		if (args.length == 1) {
			options.addAll(ORDERS);
		} else if (args.length == 2) {
			switch (args[0].toLowerCase(Locale.ROOT)) {
				case "recolectar" -> options.addAll(List.of(CompanionWork.resourceNames().split(", ")));
				case "cultivar" -> options.addAll(List.of("todo", "cosechar", "plantar"));
				case "construir" -> options.addAll(List.of("pared", "columna"));
				case "color" -> options.addAll(PomiChoice.COLOURS.keySet());
				case "craftear" -> options.addAll(List.of("oak_planks", "stick", "torch", "crafting_table"));
				case "voz" -> options.add("quitar");
				default -> {
				}
			}
		}
		String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
		options.removeIf(option -> !option.startsWith(last));
		return options;
	}
}
