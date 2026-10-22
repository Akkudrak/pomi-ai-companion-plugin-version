package com.maine.pomi.companion;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import io.papermc.paper.datacomponent.DataComponentTypes;

import com.maine.pomi.util.Chat;
import com.maine.pomi.util.MathUtil;

/**
 * The tool the player gave Pomi (her tool slot) and what it means for breaking blocks, with the
 * game's own rules: which blocks it can harvest (wooden pickaxe: stone and coal; stone: iron, copper,
 * lapis; iron: gold, diamond, redstone, emerald), how fast (Efficiency included), what drops
 * (Fortune and Silk Touch) and how it wears out (Unbreaking).
 */
public final class CompanionMining {
	/** Without a tool, nothing harder than this (an iron block). */
	public static final float MAX_HAND_HARDNESS = 5.0f;

	private static final Material[] SAMPLES = {Material.STONE, Material.COAL_ORE, Material.IRON_ORE, Material.COPPER_ORE,
			Material.LAPIS_ORE, Material.GOLD_ORE, Material.REDSTONE_ORE, Material.DIAMOND_ORE, Material.EMERALD_ORE,
			Material.OBSIDIAN};

	private CompanionMining() {
	}

	/** Pickaxes, axes, shovels, hoes, shears, swords: anything with the game's tool component. */
	public static boolean isTool(ItemStack stack) {
		return !Companion.isEmpty(stack) && stack.hasData(DataComponentTypes.TOOL);
	}

	/** Whether breaking it gives its drops: blocks that need a tool only with the right one. */
	public static boolean canHarvest(BlockData data, ItemStack tool) {
		return !data.requiresCorrectToolForDrops() || (!Companion.isEmpty(tool) && data.isPreferredTool(tool));
	}

	/** Why Pomi can't break this with what she has, or null if she can. */
	public static String whyNotWithTool(Block block, ItemStack tool) {
		BlockData data = block.getBlockData();
		float hardness = block.getType().getHardness();
		String name = Chat.id(block.getType());
		if (hardness < 0) {
			return "No puedes romper " + name + ": no se puede romper.";
		}
		if (data.requiresCorrectToolForDrops()) {
			if (canHarvest(data, tool)) {
				return null;
			}
			return Companion.isEmpty(tool)
					? "No puedes romper " + name + " a mano: necesitas que el jugador te dé una herramienta adecuada (un pico, "
							+ "por ejemplo) en tu espacio de herramienta (clic derecho en ti) o lanzándotela."
					: "Tu " + Chat.id(tool.getType()) + " no alcanza para " + name + ": necesitas una herramienta mejor.";
		}
		if (hardness > MAX_HAND_HARDNESS && (Companion.isEmpty(tool) || data.getDestroySpeed(tool, false) <= 1.0f)) {
			return "No puedes romper " + name + ": está demasiado duro sin la herramienta adecuada.";
		}
		return null;
	}

	/** Ticks to break it, like a player with this tool (or by hand), sped up by Pomi's destreza. */
	public static int breakTicks(Block block, ItemStack tool) {
		float hardness = block.getType().getHardness();
		if (hardness <= 0) {
			return 4;
		}
		BlockData data = block.getBlockData();
		float speed = Companion.isEmpty(tool) ? 1.0f : data.getDestroySpeed(tool, true);
		double perTick = speed / hardness / (canHarvest(data, tool) ? 30.0 : 100.0);
		int ticks = MathUtil.clamp((int) Math.ceil(1.0 / perTick), 4, 600);
		return MathUtil.clamp((int) Math.round(ticks / CompanionStats.POMI.dexterityFactor()), 4, 600);
	}

	/** What breaking it drops with Pomi's tool (Fortune, Silk Touch and the like included). */
	public static Collection<ItemStack> drops(Block block, Companion companion) {
		ItemStack tool = Companion.isEmpty(companion.tool) ? ItemStack.empty() : companion.tool;
		return block.getDrops(tool, companion.entity);
	}

	/**
	 * Wears the tool after breaking a block it was used for (Unbreaking applies; nothing in creative).
	 * Returns true if the tool broke.
	 */
	public static boolean wear(Player player, Companion companion, BlockData broken) {
		ItemStack tool = companion.tool;
		if (Companion.isEmpty(tool) || player.getGameMode() == GameMode.CREATIVE
				|| (broken.getDestroySpeed(tool, false) <= 1.0f && !broken.isPreferredTool(tool))) {
			return false;
		}
		ItemStack after = tool.damage(1, companion.entity);
		if (Companion.isEmpty(after)) {
			companion.tool = null;
			companion.entity.getWorld().playSound(companion.entity.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
			return true;
		}
		companion.tool = after;
		return false;
	}

	/** "stone, coal_ore, iron_ore" for the window: what the tool lets her mine beyond her hands. */
	public static String extras(ItemStack tool) {
		List<String> names = new ArrayList<>();
		for (Material sample : SAMPLES) {
			if (!Companion.isEmpty(tool) && sample.createBlockData().isPreferredTool(tool)) {
				names.add(Chat.id(sample));
			}
		}
		return names.isEmpty() ? "nada más que a mano" : String.join(", ", names);
	}
}
