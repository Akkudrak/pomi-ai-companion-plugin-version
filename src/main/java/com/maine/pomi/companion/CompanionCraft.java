package com.maine.pomi.companion;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

import com.maine.pomi.util.Chat;

/**
 * Simple crafting with what Pomi carries: only recipes that fit the 2x2 grid (she has no crafting
 * table), like logs into planks, planks into sticks, coal and a stick into torches. A craft only
 * happens when the result fits in her two slots once the ingredients are used up. Ported from the
 * mod's CompanionCraft onto Bukkit's recipe list.
 */
public final class CompanionCraft {
	private static final int MAX_CRAFTS = 64;
	/** "veces" not given: 1 of a finished item, all she can of a material (planks, sticks, torches). */
	public static final int UNSPECIFIED = -1;

	/** A small recipe: one ingredient per grid cell, and what one craft gives. */
	private record Option(List<RecipeChoice> ingredients, ItemStack result) {
	}

	private CompanionCraft() {
	}

	/** Crafts {@code itemId} up to {@code times} times ({@code times} <= 0: as many as possible). Returns the result for the LLM. */
	public static String craft(Player player, Companion companion, String itemId, int times) {
		Material item = Material.matchMaterial(itemId == null ? "" : itemId);
		if (item == null || !item.isItem() || item.isAir()) {
			return "No conozco el item \"" + itemId + "\".";
		}
		List<Option> options = options(stack -> stack.getType() == item);
		// "Tablones" without saying which wood: any planks her logs can make.
		if (Tag.PLANKS.isTagged(item) && options.stream().noneMatch(o -> canCraft(companion.inventory, o))) {
			options = options(stack -> Tag.PLANKS.isTagged(stack.getType()));
		}
		if (options.isEmpty()) {
			return Chat.id(item) + " no se puede hacer en la cuadrícula chica de 2x2 (necesita mesa de crafteo, horno, o no "
					+ "se craftea). Dile al jugador que lo haga él; puedes explicarle la receta con como_fabricar.";
		}
		if (times == UNSPECIFIED) {
			times = Tag.PLANKS.isTagged(item) || item == Material.STICK || item == Material.TORCH ? 0 : 1;
		}
		int limit = times <= 0 ? MAX_CRAFTS : Math.min(times, MAX_CRAFTS);
		Map<String, Integer> made = new LinkedHashMap<>();
		List<String> extras = new ArrayList<>();
		int crafts = 0;
		while (crafts < limit) {
			Option done = null;
			for (Option option : options) {
				if (craftOnce(companion, option)) {
					done = option;
					break;
				}
			}
			if (done == null) {
				// Missing an ingredient she can make from what she carries (sticks from logs): make that first.
				if (prepare(companion, options.getFirst(), 1, extras) > 0) {
					continue;
				}
				break;
			}
			crafts++;
			made.merge(Chat.id(done.result().getType()), done.result().getAmount(), Integer::sum);
		}
		if (crafts == 0) {
			Option first = options.getFirst();
			return "No pudiste hacer " + Chat.id(item) + ": " + (hasIngredients(companion.inventory, first.ingredients())
					? "no te cabría el resultado en tus dos espacios. Pide que te dejen soltar algo primero."
					: "te faltan ingredientes. Se necesita: " + describe(first.ingredients()) + ". Traes: "
							+ PomiTasks.describeInventory(companion) + ". El jugador te los puede lanzar (tecla Q).");
		}
		companion.entity.getWorld().playSound(companion.entity.getLocation(), Sound.ENTITY_VILLAGER_WORK_TOOLSMITH, 0.8f, 1.2f);
		List<String> parts = new ArrayList<>();
		made.forEach((what, count) -> parts.add(count + "x " + what));
		return (extras.isEmpty() ? "" : String.join(" ", extras) + " ") + "Crafteaste " + String.join(" y ", parts)
				+ (crafts < limit && times > 0 ? " (hasta donde alcanzó)" : "") + ". Ahora traes: "
				+ PomiTasks.describeInventory(companion) + ".";
	}

	/**
	 * Crafts up to {@code wanted} torches (4 per coal) from the coal or charcoal and the wood she
	 * carries: sticks first, then pairs of planks (4 sticks), then logs (8 sticks), working out the
	 * whole chain at once so the slots don't need room for what's in between. The torches are NOT put
	 * in the slots (the caller places them); spare sticks go in the slots or to the player.
	 *
	 * @return how many torches she made
	 */
	public static int craftTorches(Player player, Companion companion, int wanted) {
		ItemStack[] slots = companion.inventory;
		int coal = count(slots, stack -> Tag.ITEMS_COALS.isTagged(stack.getType()));
		int sticks = count(slots, stack -> stack.getType() == Material.STICK);
		int planks = count(slots, stack -> Tag.PLANKS.isTagged(stack.getType()));
		int logs = count(slots, stack -> Tag.LOGS.isTagged(stack.getType()));
		int crafts = Math.min(Math.min((wanted + 3) / 4, coal), sticks + planks / 2 * 4 + logs * 8);
		if (crafts <= 0) {
			return 0;
		}
		int missing = crafts;
		int useSticks = Math.min(missing, sticks);
		missing -= useSticks;
		int usePairs = Math.min((missing + 3) / 4, planks / 2);
		missing -= usePairs * 4;
		int useLogs = missing > 0 ? (missing + 7) / 8 : 0;
		missing -= useLogs * 8;
		take(slots, stack -> Tag.ITEMS_COALS.isTagged(stack.getType()), crafts);
		take(slots, stack -> stack.getType() == Material.STICK, useSticks);
		take(slots, stack -> Tag.PLANKS.isTagged(stack.getType()), usePairs * 2);
		take(slots, stack -> Tag.LOGS.isTagged(stack.getType()), useLogs);
		if (missing < 0) {
			ItemStack spare = PomiTasks.insert(companion, new ItemStack(Material.STICK, -missing));
			if (!Companion.isEmpty(spare)) {
				PomiTasks.toss(player, companion, spare);
			}
		}
		companion.entity.getWorld().playSound(companion.entity.getLocation(), Sound.ENTITY_VILLAGER_WORK_TOOLSMITH, 0.8f, 1.2f);
		return crafts * 4;
	}

	private static int count(ItemStack[] slots, Predicate<ItemStack> what) {
		int total = 0;
		for (ItemStack stack : slots) {
			if (!Companion.isEmpty(stack) && what.test(stack)) {
				total += stack.getAmount();
			}
		}
		return total;
	}

	private static void take(ItemStack[] slots, Predicate<ItemStack> what, int amount) {
		for (int i = 0; i < slots.length && amount > 0; i++) {
			if (!Companion.isEmpty(slots[i]) && what.test(slots[i])) {
				int taken = Math.min(amount, slots[i].getAmount());
				slots[i].setAmount(slots[i].getAmount() - taken);
				amount -= taken;
				if (slots[i].getAmount() <= 0) {
					slots[i] = null;
				}
			}
		}
	}

	/** Makes the target's missing ingredients from what she carries (planks from logs, then sticks). Returns how many crafts. */
	private static int prepare(Companion companion, Option target, int depth, List<String> extras) {
		int total = 0;
		for (RecipeChoice ingredient : target.ingredients()) {
			if (hasIngredients(companion.inventory, List.of(ingredient))) {
				continue;
			}
			for (Option source : options(ingredient::test)) {
				if (source.result().isSimilar(target.result())) {
					continue;
				}
				if (depth > 0) {
					total += prepare(companion, source, depth - 1, extras);
				}
				int crafted = 0;
				while (crafted < MAX_CRAFTS && craftOnce(companion, source)) {
					crafted++;
				}
				if (crafted > 0) {
					total += crafted;
					extras.add("Primero hiciste " + crafted * source.result().getAmount() + "x " + Chat.id(source.result().getType()) + ".");
					break;
				}
			}
		}
		return total;
	}

	/** Crafting recipes that fit a 2x2 grid and give something {@code wanted} accepts. */
	private static List<Option> options(Predicate<ItemStack> wanted) {
		List<Option> found = new ArrayList<>();
		Iterator<Recipe> recipes = Bukkit.recipeIterator();
		while (recipes.hasNext()) {
			Recipe recipe = recipes.next();
			ItemStack result = recipe.getResult();
			if (Companion.isEmpty(result) || !wanted.test(result)) {
				continue;
			}
			List<RecipeChoice> ingredients = new ArrayList<>();
			if (recipe instanceof ShapedRecipe shaped) {
				String[] shape = shaped.getShape();
				boolean small = shape.length <= 2;
				for (String row : shape) {
					small &= row.length() <= 2;
				}
				if (!small) {
					continue;
				}
				for (String row : shape) {
					for (char key : row.toCharArray()) {
						RecipeChoice choice = shaped.getChoiceMap().get(key);
						if (choice != null) {
							ingredients.add(choice);
						}
					}
				}
			} else if (recipe instanceof ShapelessRecipe shapeless) {
				if (shapeless.getChoiceList().size() > 4) {
					continue;
				}
				ingredients.addAll(shapeless.getChoiceList());
			} else {
				continue;
			}
			if (!ingredients.isEmpty()) {
				found.add(new Option(ingredients, result.clone()));
			}
		}
		return found;
	}

	/** Crafts once if the ingredients are there and the result fits afterwards. */
	private static boolean craftOnce(Companion companion, Option option) {
		ItemStack[] slots = copy(companion.inventory);
		if (!consume(slots, option.ingredients()) || !fit(slots, option.result().clone())) {
			return false;
		}
		System.arraycopy(slots, 0, companion.inventory, 0, slots.length);
		return true;
	}

	private static boolean canCraft(ItemStack[] inventory, Option option) {
		ItemStack[] slots = copy(inventory);
		return consume(slots, option.ingredients()) && fit(slots, option.result().clone());
	}

	private static boolean hasIngredients(ItemStack[] inventory, List<RecipeChoice> ingredients) {
		return consume(copy(inventory), ingredients);
	}

	/** Takes one item per ingredient from the slots; false if something is missing. */
	private static boolean consume(ItemStack[] slots, List<RecipeChoice> ingredients) {
		for (RecipeChoice ingredient : ingredients) {
			boolean found = false;
			for (int i = 0; i < slots.length; i++) {
				if (!Companion.isEmpty(slots[i]) && ingredient.test(slots[i])) {
					slots[i].setAmount(slots[i].getAmount() - 1);
					if (slots[i].getAmount() <= 0) {
						slots[i] = null;
					}
					found = true;
					break;
				}
			}
			if (!found) {
				return false;
			}
		}
		return true;
	}

	/** Puts the whole result in the slots (stacking first); false if it doesn't all fit. */
	private static boolean fit(ItemStack[] slots, ItemStack result) {
		return Companion.isEmpty(PomiTasks.insert(slots, result));
	}

	private static ItemStack[] copy(ItemStack[] inventory) {
		ItemStack[] slots = new ItemStack[inventory.length];
		for (int i = 0; i < inventory.length; i++) {
			slots[i] = Companion.isEmpty(inventory[i]) ? null : inventory[i].clone();
		}
		return slots;
	}

	/** "4x oak_planks", "1x coal y 1x stick". */
	private static String describe(List<RecipeChoice> ingredients) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (RecipeChoice ingredient : ingredients) {
			counts.merge(Chat.id(ingredient.getItemStack().getType()), 1, Integer::sum);
		}
		List<String> parts = new ArrayList<>();
		counts.forEach((what, count) -> parts.add(count + "x " + what));
		return String.join(" y ", parts);
	}
}
