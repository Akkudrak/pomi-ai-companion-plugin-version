package com.maine.pomi.ai;

import java.util.Locale;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.maine.pomi.CompanionManager;
import com.maine.pomi.PomiPlugin;
import com.maine.pomi.companion.Companion;
import com.maine.pomi.companion.CompanionBuild;
import com.maine.pomi.companion.CompanionCraft;
import com.maine.pomi.companion.CompanionPlan;
import com.maine.pomi.companion.CompanionRoutine;
import com.maine.pomi.companion.CompanionWork;
import com.maine.pomi.companion.Herobrine;
import com.maine.pomi.companion.PomiTasks;
import com.maine.pomi.network.BiomeLocator;
import com.maine.pomi.network.PomiGuide;
import com.maine.pomi.network.PomiRequests;
import com.maine.pomi.util.MathUtil;

/**
 * The tools Grok can call, with the mod's names, and running them on the server. Every result is a
 * short Spanish text for Grok to turn into Pomi's answer.
 */
final class PomiTools {
	private final PomiPlugin plugin;
	private final JsonArray schemas = new JsonArray();

	PomiTools(PomiPlugin plugin) {
		this.plugin = plugin;
		add("mover_pomi", "Mueve a Pomi: seguir (opcional distancia en bloques), quedarse, venir (aparece junto al jugador) o ir "
				+ "(a donde apunta el jugador, o unos bloques en una dirección relativa a su vista; al llegar se queda quieta).",
				params().enumOf("accion", "Qué hacer.", "seguir", "quedarse", "venir", "ir")
						.number("distancia", "Para seguir: a cuántos bloques (1.5 a 16).")
						.enumOf("direccion", "Para ir: apuntado o una dirección.", "apuntado", "adelante", "atras", "izquierda", "derecha")
						.integer("bloques", "Para ir en una dirección: cuántos bloques.").required("accion"));
		add("detener", "Deja todo lo que hace (tarea, encargo, guardia) y vuelve a seguir al jugador.", params());
		add("defender", "Guardia: pelea contra los hostiles comunes cerca del jugador hasta derrotar 5.", params());
		add("atacar_mob", "Ataca al mob que señala el jugador (o el de ese tipo, o el hostil más cercano). Lo deja con 1 de vida, "
				+ "o lo mata si matar=true (solo hostiles comunes).",
				params().bool("matar", "Acabar con él.").string("mob", "Opcional: id en inglés del mob (zombie, skeleton...)."));
		add("recoger_items", "Recoge items tirados cerca en sus 2 espacios.", params().string("item", "Opcional: id del item."));
		add("soltar_items", "Le lanza al jugador lo que trae (o solo ese item).", params().string("item", "Opcional: id del item."));
		add("devolver_herramienta", "Le regresa al jugador la herramienta de su espacio de herramienta.", params());
		add("iluminar", "Pone antorchas en los rincones oscuros alrededor del jugador (las suyas, o las craftea con madera y carbón).",
				params().integer("antorchas", "Cuántas como máximo."));
		add("recolectar", "Busca y junta un recurso cerca del jugador; no hace falta que lo señale. Úsala para 'consigue/trae/junta/busca "
				+ "madera, piedra, hierro...'. Si pide 'recursos' sin decir cuál, pregúntale cuál. Recursos: " + CompanionWork.resourceNames() + ".",
				params().string("recurso", "Nombre del recurso.").integer("cantidad", "Cuántos.").required("recurso"));
		add("cultivar", "Trabaja el cultivo (en el que están parados o el que señala el jugador).",
				params().enumOf("accion", "Qué hacer.", "todo", "cosechar", "plantar"));
		add("quitar_bloques", "Quita el bloque que señala el jugador y los del mismo tipo pegados (hasta 10). Solo si pide quitar lo que "
				+ "señala; para conseguir un recurso usa recolectar.",
				params().integer("cantidad", "Cuántos bloques."));
		add("construir", "Construye una pared o columna con los bloques que trae, donde apunta el jugador.",
				params().enumOf("forma", "Forma.", "pared", "columna").integer("largo", "Largo de la pared (1-10).")
						.integer("alto", "Alto (1-10).").bool("atravesada", "Pared atravesada frente al jugador en vez de hacia donde mira.")
						.string("item", "Opcional: id del bloque.").required("forma"));
		add("craftear", "Craftea algo que cabe en la cuadrícula 2x2 con lo que trae.",
				params().string("item", "Id del item (oak_planks, stick, torch...).").integer("veces", "Cuántas veces. Omítelo si el jugador no dijo cuántas (se hace 1, o todos si son tablones, palos o antorchas); 0 = todas las que alcancen, solo si lo pide.").required("item"));
		JsonObject planStep = params().enumOf("accion", "Paso.", "recolectar", "craftear", "lanzar").string("recurso", "Para recolectar.")
				.integer("cantidad", "Para recolectar.").string("item", "Para craftear o lanzar.").integer("veces", "Para craftear; omítelo si no dijo cuántas.").build();
		add("encargo", "Un trabajo de varios pasos en orden (máximo " + CompanionPlan.MAX_STEPS + ").",
				params().array("pasos", "Los pasos.", planStep).required("pasos"));
		JsonObject routineStep = params().enumOf("accion", "Acción.", "ir", "mirar", "golpear", "romper", "saltar", "girar", "particulas", "sonido", "esperar")
				.enumOf("direccion", "Para ir.", "adelante", "atras", "izquierda", "derecha", "jugador").integer("bloques", "Para ir.")
				.string("objetivo", "Para mirar o golpear: 'jugador' o id de mob.").integer("veces", "Repeticiones (1-3).")
				.string("nombre", "Partículas: " + CompanionRoutine.particleNames() + ".").string("id", "Sonido: id de Minecraft.")
				.number("segundos", "Para esperar.").build();
		add("hacer_acciones", "Rutina de acciones básicas para lo que ninguna otra herramienta cubre (baila, haz un truco, festeja). "
				+ "Máximo " + CompanionRoutine.MAX_STEPS + " pasos. 'romper' rompe el bloque que señala el jugador.",
				params().array("pasos", "Los pasos.", routineStep).required("pasos"));
		add("como_fabricar", "Las recetas reales de un item.", params().string("item", "Id del item.").required("item"));
		add("que_es_eso", "Qué es el bloque o mob que señala el jugador, y cómo se fabrica.", params());
		add("siguiente_paso", "Los siguientes logros que le faltan al jugador y qué le falta para cada uno.", params());
		add("buscar_bioma", "Busca el bioma más cercano (desierto, jungla, cerezos...). Da dirección y coordenadas.",
				params().string("bioma", "Nombre del bioma en español o su id.").required("bioma"));
		add("donde_mori", "Dónde murió el jugador por última vez.", params());
		add("cambiar_color", "Cambia el color de Pomi.", params().enumOf("color", "Color.", "naranja", "blanca", "verde").required("color"));
		add("herobrine", "Easter egg: hace aparecer a Herobrine lejos detrás del jugador. Solo si pregunta por Herobrine o pide un susto.", params());
		add("invocar_item", "Solo creativo: invoca items en sus espacios.",
				params().string("item", "Id del item.").integer("cantidad", "Cuántos.").required("item"));
		add("colocar_bloque", "Pone un bloque o item que trae donde apunta el jugador (en creativo se invoca solo).",
				params().string("item", "Id del item.").required("item"));
		add("ejecutar_comando", "Solo creativo: ejecuta un comando de Minecraft como el jugador (con sus permisos).",
				params().string("comando", "El comando, sin '/'.").required("comando"));
		add("recordar_dato", "Guarda un dato corto que valga la pena recordar del jugador.", params().string("dato", "El dato.").required("dato"));
		add("olvidar_dato", "Olvida los datos que contengan ese texto.", params().string("dato", "Texto a olvidar.").required("dato"));
		add("despedirse", "Pomi se va hasta que el jugador la vuelva a llamar (si se despide de ti o te dice que te vayas).", params());
	}

	JsonArray schemas() {
		return schemas;
	}

	/** Runs one tool call on the server thread. */
	String run(Player player, String name, String arguments) {
		JsonObject args;
		try {
			JsonElement parsed = JsonParser.parseString(arguments == null || arguments.isBlank() ? "{}" : arguments);
			args = parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
		} catch (RuntimeException e) {
			return "Argumentos inválidos.";
		}
		CompanionManager companions = plugin.companions();
		Companion companion = companions.active(player);
		// Tools that don't need Pomi in the world.
		switch (name) {
			case "como_fabricar":
				return PomiRequests.recipes(text(args, "item"));
			case "siguiente_paso":
				return PomiGuide.nextSteps(player);
			case "buscar_bioma":
				return BiomeLocator.locate(player, text(args, "bioma"));
			case "donde_mori":
				return PomiGuide.whereDied(player);
			case "recordar_dato":
				return plugin.memory().remember(player.getUniqueId(), text(args, "dato"));
			case "olvidar_dato":
				return plugin.memory().forget(player.getUniqueId(), text(args, "dato"));
			case "ejecutar_comando":
				return PomiRequests.runCommand(player, text(args, "comando"));
			default:
				break;
		}
		if (companion == null) {
			return "No estás en el mundo ahora (el jugador te despidió). Dile que te llame con /p.";
		}
		return switch (name) {
			case "mover_pomi" -> move(player, companion, args);
			case "detener" -> {
				String was = companions.stopAll(player, companion);
				yield was.isEmpty() ? "No estabas haciendo nada; sigues al jugador." : "Dejaste de " + was + " y vuelves a seguirlo.";
			}
			case "defender" -> PomiTasks.startGuard(player, companion);
			case "atacar_mob" -> PomiTasks.startAttack(player, companion,
					PomiTasks.findTarget(player, companion, text(args, "mob")), bool(args, "matar"));
			case "recoger_items" -> PomiTasks.startPickup(player, companion, text(args, "item"));
			case "soltar_items" -> PomiTasks.throwItems(player, companion, text(args, "item"));
			case "devolver_herramienta" -> PomiTasks.returnTool(player, companion);
			case "iluminar" -> PomiTasks.illuminate(player, companion, integer(args, "antorchas", 12));
			case "recolectar" -> {
				CompanionPlan.cancel(companion);
				yield CompanionWork.startGather(player, companion, text(args, "recurso"), integer(args, "cantidad", 16));
			}
			case "cultivar" -> CompanionWork.startFarm(player, companion, text(args, "accion"));
			case "quitar_bloques" -> {
				CompanionPlan.cancel(companion);
				yield CompanionWork.startRemove(player, companion, player.getTargetBlockExact(PomiTasks.AIM_DISTANCE), integer(args, "cantidad", 1));
			}
			case "construir" -> CompanionBuild.start(player, companion, "columna".equals(text(args, "forma")),
					integer(args, "largo", 5), integer(args, "alto", 3), bool(args, "atravesada"), text(args, "item"));
			case "craftear" -> CompanionCraft.craft(player, companion, text(args, "item"), integer(args, "veces", CompanionCraft.UNSPECIFIED));
			case "encargo" -> args.has("pasos") && args.get("pasos").isJsonArray()
					? CompanionPlan.start(player, companion, args.getAsJsonArray("pasos")) : "Faltan los pasos.";
			case "hacer_acciones" -> args.has("pasos") && args.get("pasos").isJsonArray()
					? CompanionRoutine.start(player, companion, args.getAsJsonArray("pasos")) : "Faltan los pasos.";
			case "que_es_eso" -> PomiGuide.whatIsThat(player);
			case "cambiar_color" -> PomiTasks.recolor(player, companion, text(args, "color"));
			case "herobrine" -> {
				Location at = Herobrine.summon(player, companion);
				yield "Herobrine apareció " + MathUtil.describe(player, at) + ", mirando fijamente al jugador. Dile, misteriosa, que "
						+ "voltee a ver detrás de él, sin decir exactamente dónde.";
			}
			case "invocar_item" -> PomiTasks.invoke(player, companion, text(args, "item"), integer(args, "cantidad", 1));
			case "colocar_bloque" -> PomiTasks.place(player, companion, text(args, "item"));
			case "despedirse" -> {
				// After this turn's reply, so the goodbye is said while she's still here.
				plugin.getServer().getScheduler().runTaskLater(plugin, () -> companions.dismiss(player), 20L);
				yield "Te vas en un momento; despídete en una frase. Volverás cuando el jugador escriba /p.";
			}
			default -> "Herramienta desconocida: " + name + ".";
		};
	}

	private static String move(Player player, Companion companion, JsonObject args) {
		CompanionManager companions = PomiPlugin.instance().companions();
		return switch (text(args, "accion")) {
			case "seguir" -> companions.follow(player, companion, args.has("distancia") ? args.get("distancia").getAsDouble() : 0);
			case "quedarse" -> companions.stay(player, companion);
			case "venir" -> companions.come(player, companion);
			case "ir" -> {
				String direction = text(args, "direccion");
				if (direction.isEmpty() || direction.equals("apuntado")) {
					yield companions.goTo(player, companion, null);
				}
				double degrees = switch (direction) {
					case "derecha" -> 90;
					case "atras" -> 180;
					case "izquierda" -> 270;
					default -> 0;
				};
				yield companions.goTo(player, companion,
						MathUtil.relative(player, companion.entity.getLocation(), degrees, MathUtil.clamp(integer(args, "bloques", 5), 1, 32)));
			}
			default -> "Acción de movimiento desconocida.";
		};
	}

	private static String text(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString().strip().toLowerCase(Locale.ROOT) : "";
	}

	private static int integer(JsonObject o, String key, int fallback) {
		try {
			return o.has(key) ? o.get(key).getAsInt() : fallback;
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	private static boolean bool(JsonObject o, String key) {
		try {
			return o.has(key) && o.get(key).getAsBoolean();
		} catch (RuntimeException e) {
			return false;
		}
	}

	// ---- Schemas ------------------------------------------------------------------------------

	private void add(String name, String description, Params params) {
		JsonObject function = new JsonObject();
		function.addProperty("name", name);
		function.addProperty("description", description);
		function.add("parameters", params.build());
		JsonObject tool = new JsonObject();
		tool.addProperty("type", "function");
		tool.add("function", function);
		schemas.add(tool);
	}

	private static Params params() {
		return new Params();
	}

	/** A JSON schema object, built a property at a time. */
	private static final class Params {
		private final JsonObject properties = new JsonObject();
		private final JsonArray required = new JsonArray();

		private Params prop(String name, String type, String description) {
			JsonObject p = new JsonObject();
			p.addProperty("type", type);
			p.addProperty("description", description);
			properties.add(name, p);
			return this;
		}

		Params string(String name, String description) {
			return prop(name, "string", description);
		}

		Params integer(String name, String description) {
			return prop(name, "integer", description);
		}

		Params number(String name, String description) {
			return prop(name, "number", description);
		}

		Params bool(String name, String description) {
			return prop(name, "boolean", description);
		}

		Params enumOf(String name, String description, String... values) {
			prop(name, "string", description);
			JsonArray options = new JsonArray();
			for (String value : values) {
				options.add(value);
			}
			properties.getAsJsonObject(name).add("enum", options);
			return this;
		}

		Params array(String name, String description, JsonObject items) {
			prop(name, "array", description);
			properties.getAsJsonObject(name).add("items", items);
			return this;
		}

		Params required(String... names) {
			for (String n : names) {
				required.add(n);
			}
			return this;
		}

		JsonObject build() {
			JsonObject schema = new JsonObject();
			schema.addProperty("type", "object");
			schema.add("properties", properties);
			if (!required.isEmpty()) {
				schema.add("required", required);
			}
			return schema;
		}
	}
}
