package com.maine.pomi.network;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.StonecuttingRecipe;

import com.maine.pomi.util.Chat;

/** Server-side answers the LLM can't work out alone: real recipes, and commands in creative. */
public final class PomiRequests {
	/** Never run from a chat with Pomi, whatever the player's permissions. */
	private static final Set<String> BLOCKED = Set.of("stop", "restart", "reload", "op", "deop", "ban", "ban-ip", "pardon",
			"kick", "whitelist", "save-off", "lp", "luckperms", "pex", "permissions", "plugins");

	private PomiRequests() {
	}

	/** Up to three recipes that make {@code itemId}, drawn as grids. */
	public static String recipes(String itemId) {
		Material item = Material.matchMaterial(itemId == null ? "" : itemId);
		if (item == null || !item.isItem()) {
			return "No conozco el item \"" + itemId + "\".";
		}
		List<String> found = new ArrayList<>();
		for (Recipe recipe : Bukkit.getRecipesFor(new ItemStack(item))) {
			String text = describe(recipe);
			if (text != null && !found.contains(text)) {
				found.add(text);
			}
			if (found.size() == 3) {
				break;
			}
		}
		if (found.isEmpty()) {
			return Chat.id(item) + " no se fabrica: se consigue de otra forma (minando, de mobs, explorando o comerciando).";
		}
		return "Recetas de " + Chat.id(item) + ":\n" + String.join("\n", found);
	}

	private static String describe(Recipe recipe) {
		String result = recipe.getResult().getAmount() + "x " + Chat.id(recipe.getResult().getType());
		if (recipe instanceof ShapedRecipe shaped) {
			Map<Character, String> legend = new LinkedHashMap<>();
			StringBuilder grid = new StringBuilder();
			for (String row : shaped.getShape()) {
				List<String> cells = new ArrayList<>();
				for (char key : row.toCharArray()) {
					RecipeChoice choice = shaped.getChoiceMap().get(key);
					if (choice == null) {
						cells.add("(vacío)");
					} else {
						String name = name(choice);
						legend.put(key, name);
						cells.add(name);
					}
				}
				grid.append(" [").append(String.join(" | ", cells)).append(']');
			}
			boolean small = shaped.getShape().length <= 2 && shaped.getShape()[0].length() <= 2;
			return "- " + result + (small ? " (cabe en el inventario 2x2)" : " (mesa de crafteo)") + ", filas de arriba a abajo:" + grid;
		}
		if (recipe instanceof ShapelessRecipe shapeless) {
			Map<String, Integer> counts = new LinkedHashMap<>();
			for (RecipeChoice choice : shapeless.getChoiceList()) {
				counts.merge(name(choice), 1, Integer::sum);
			}
			List<String> parts = new ArrayList<>();
			counts.forEach((name, count) -> parts.add(count + "x " + name));
			return "- " + result + " (sin forma" + (shapeless.getChoiceList().size() <= 4 ? ", cabe en 2x2" : ", mesa de crafteo") + "): "
					+ String.join(" + ", parts);
		}
		if (recipe instanceof CookingRecipe<?> cooking) {
			String station = cooking.getClass().getSimpleName().toLowerCase(Locale.ROOT).replace("recipe", "");
			return "- " + result + " cocinando " + name(cooking.getInputChoice()) + " (" + station + ")";
		}
		if (recipe instanceof StonecuttingRecipe cutting) {
			return "- " + result + " con cortapiedras desde " + name(cutting.getInputChoice());
		}
		return null;
	}

	private static String name(RecipeChoice choice) {
		if (choice instanceof RecipeChoice.MaterialChoice materials && materials.getChoices().size() > 1) {
			return "cualquier " + Chat.id(materials.getChoices().getFirst()) + " (o similar)";
		}
		return Chat.id(choice.getItemStack().getType());
	}

	/**
	 * Creative only: runs a command as the player, with their own permissions (never as the console),
	 * so a chat with Pomi can't do anything the player couldn't type themselves.
	 */
	public static String runCommand(Player player, String command) {
		if (player.getGameMode() != GameMode.CREATIVE) {
			return "No se puede: los comandos solo funcionan en modo creativo.";
		}
		String line = command == null ? "" : command.strip();
		while (line.startsWith("/")) {
			line = line.substring(1);
		}
		if (line.isEmpty()) {
			return "Falta el comando.";
		}
		String label = line.split("\\s+")[0].toLowerCase(Locale.ROOT);
		if (label.contains(":")) {
			label = label.substring(label.indexOf(':') + 1);
		}
		if (BLOCKED.contains(label) || label.equals("p") || label.equals("pomi")) {
			return "Ese comando no lo ejecutas desde aquí. Que lo escriba el jugador si de verdad lo quiere.";
		}
		boolean ok = player.performCommand(line);
		return ok ? "Ejecutaste /" + line + "." : "El comando /" + line + " no existe o el jugador no tiene permiso.";
	}
}
