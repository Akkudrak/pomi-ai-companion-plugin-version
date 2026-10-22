package com.maine.pomi.companion;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.maine.pomi.util.Chat;

/**
 * A job of several steps done in order ("consigue madera, haz tablones, palos y botones"):
 * gathering takes a while, so the next step waits until it finishes; crafting and tossing are
 * instant. The player hears one summary at the end. Ported from the mod.
 */
public final class CompanionPlan {
	public static final int MAX_STEPS = 8;

	/** "recolectar" (resource, amount), "craftear" (item id, times; 0 = all she can) or "lanzar" (item id or ""). */
	public record Step(String action, String what, int amount) {
	}

	private CompanionPlan() {
	}

	/** Checks the steps and starts the first. Returns the result for the LLM. */
	public static String start(Player player, Companion companion, JsonArray array) {
		List<Step> steps = new ArrayList<>();
		try {
			for (JsonElement element : array) {
				if (steps.size() == MAX_STEPS) {
					break;
				}
				JsonObject o = element.getAsJsonObject();
				String action = text(o, "accion");
				switch (action) {
					case "recolectar" -> {
						String resource = text(o, "recurso");
						if (!CompanionWork.knows(resource)) {
							return "No sabes juntar \"" + resource + "\" (solo " + CompanionWork.resourceNames() + ").";
						}
						steps.add(new Step(action, CompanionWork.normalize(resource), number(o, "cantidad", 10)));
					}
					case "craftear" -> {
						String item = text(o, "item");
						if (Material.matchMaterial(item) == null) {
							return "No conozco el item \"" + item + "\" de un paso de craftear.";
						}
						steps.add(new Step(action, item, number(o, "veces", CompanionCraft.UNSPECIFIED)));
					}
					case "lanzar" -> {
						String item = text(o, "item");
						steps.add(new Step(action, Material.matchMaterial(item) != null ? item : "", 0));
					}
					default -> {
						return "Paso desconocido: \"" + action + "\". Usa recolectar, craftear o lanzar.";
					}
				}
			}
		} catch (RuntimeException e) {
			return "Pasos inválidos.";
		}
		if (steps.isEmpty()) {
			return "No hay pasos que hacer.";
		}
		companion.plan = steps;
		companion.planIndex = 0;
		companion.planReport.clear();
		companion.planActive = true;
		companion.planWaiting = false;
		List<String> outline = new ArrayList<>();
		for (Step step : steps) {
			outline.add(switch (step.action()) {
				case "recolectar" -> "juntar " + step.amount() + " de " + step.what();
				case "craftear" -> "craftear " + step.what().replace("minecraft:", "");
				default -> "lanzarle " + (step.what().isEmpty() ? "todo" : step.what().replace("minecraft:", ""));
			});
		}
		advance(player, companion);
		return "Empezaste el encargo: " + String.join(", luego ", outline) + ". Lo haces en orden y al terminar llegará un "
				+ "[Evento] con lo que salió; por ahora solo di que vas.";
	}

	/** Runs steps until one has to wait (gathering) or the plan is over. */
	static void advance(Player player, Companion companion) {
		while (companion.planActive && companion.planIndex < companion.plan.size()) {
			Step step = companion.plan.get(companion.planIndex++);
			switch (step.action()) {
				case "recolectar" -> {
					String started = CompanionWork.startGather(player, companion, step.what(), step.amount());
					if (companion.mode == Companion.Mode.GATHER) {
						companion.planWaiting = true;
						return;
					}
					companion.planReport.add(started);
				}
				case "craftear" -> companion.planReport.add(CompanionCraft.craft(player, companion, step.what(), step.amount()));
				default -> companion.planReport.add(PomiTasks.throwItems(player, companion, step.what()));
			}
		}
		if (companion.planActive) {
			finish(player, companion);
		}
	}

	/** A gathering step finished; on with the rest. */
	static void gatherDone(Player player, Companion companion, String summary) {
		companion.planWaiting = false;
		companion.planReport.add(summary);
		advance(player, companion);
	}

	static boolean waitingOnGather(Companion companion) {
		return companion.planActive && companion.planWaiting;
	}

	/** The player asked for something else: the rest of the plan is dropped. */
	public static void cancel(Companion companion) {
		companion.planActive = false;
		companion.planWaiting = false;
	}

	private static void finish(Player player, Companion companion) {
		companion.planActive = false;
		companion.planWaiting = false;
		StringBuilder fact = new StringBuilder("Pomi terminó el encargo:");
		for (int i = 0; i < companion.planReport.size(); i++) {
			fact.append("\n").append(i + 1).append(". ").append(companion.planReport.get(i));
		}
		fact.append("\nTrae: ").append(PomiTasks.describeInventory(companion)).append('.');
		PomiTasks.notify(player, companion, fact.toString(),
				"Cuéntale en dos o tres frases qué lograste y qué no; si quiere lo que traes, que te pida lanzárselo.", "fanfarria");
	}

	private static String text(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? Chat.lower(o.get(key).getAsString()) : "";
	}

	private static int number(JsonObject o, String key, int fallback) {
		try {
			return o.has(key) ? o.get(key).getAsInt() : fallback;
		} catch (RuntimeException e) {
			return fallback;
		}
	}
}
