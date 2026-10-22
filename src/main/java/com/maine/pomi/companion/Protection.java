package com.maine.pomi.companion;

import java.util.List;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import com.maine.pomi.PomiPlugin;
import com.maine.pomi.util.Chat;
import com.maine.pomi.util.MathUtil;

/**
 * What Pomi may break or place, and doing it "as the player": the break and place go through
 * Bukkit's BlockBreakEvent / BlockPlaceEvent with the player as the actor, so spawn protection and
 * land-claim plugins (WorldGuard, GriefPrevention...) can refuse it, and loggers record it.
 */
public final class Protection {
	/** Pomi only breaks blocks this close to the player. */
	public static final double BREAK_RADIUS = 30.0;

	/** On a server, blocks that are part of the landscape, not of someone's build (the mod's list). */
	private static final List<Tag<Material>> NATURAL = List.of(Tag.DIRT, Tag.SAND, Tag.BASE_STONE_OVERWORLD,
			Tag.BASE_STONE_NETHER, Tag.LOGS, Tag.LEAVES, Tag.COAL_ORES, Tag.IRON_ORES, Tag.COPPER_ORES, Tag.GOLD_ORES,
			Tag.REDSTONE_ORES, Tag.LAPIS_ORES, Tag.DIAMOND_ORES, Tag.EMERALD_ORES, Tag.FLOWERS, Tag.REPLACEABLE, Tag.ICE,
			Tag.TERRACOTTA);

	private Protection() {
	}

	/**
	 * Why Pomi may not break this block, or null if she may: only near the player, never containers or
	 * blocks with data, nothing her tool can't handle, not in adventure mode, and (by default) only
	 * natural blocks so nobody's build gets touched. Land protection is checked when she actually
	 * breaks it ({@link #breakBlock}).
	 */
	public static String whyNotBreak(Player player, Companion companion, Block block, double maxDistance) {
		if (block.getType().isAir()) {
			return "Ahí no hay ningún bloque que romper.";
		}
		if (MathUtil.center(block).distance(player.getLocation()) > maxDistance) {
			return "Ese bloque está muy lejos: solo rompes bloques a menos de " + (int) maxDistance + " del jugador.";
		}
		String name = Chat.id(block.getType());
		if (block.getState(false) instanceof TileState) {
			return "No rompes " + name + ": nunca rompes cofres, hornos, spawners ni bloques que guardan cosas.";
		}
		String tooHard = CompanionMining.whyNotWithTool(block, companion.tool);
		if (tooHard != null) {
			return tooHard;
		}
		if (player.getGameMode() == GameMode.ADVENTURE || player.getGameMode() == GameMode.SPECTATOR) {
			return "No puedes romper " + name + ": en este modo de juego el jugador no puede romper bloques.";
		}
		if (PomiPlugin.instance().settings().naturalBlocksOnly() && !isNatural(block.getType())) {
			return "No rompes " + name + ": en el servidor solo rompes bloques naturales (tierra, piedra, troncos...), "
					+ "para no tocar construcciones de nadie.";
		}
		return null;
	}

	public static boolean isNatural(Material type) {
		for (Tag<Material> tag : NATURAL) {
			if (tag.isTagged(type)) {
				return true;
			}
		}
		return type == Material.GRAVEL || type == Material.CLAY || type == Material.NETHER_QUARTZ_ORE
				|| type == Material.SNOW || type == Material.SNOW_BLOCK;
	}

	/** Asks the protection plugins whether the player could break it; false if one said no. */
	public static boolean mayBreak(Player player, Block block) {
		BlockBreakEvent event = new BlockBreakEvent(block, player);
		event.setDropItems(false);
		event.setExpToDrop(0);
		return event.callEvent();
	}

	/**
	 * Breaks it as the player would, with Pomi's tool deciding the drops. {@code keep} puts the drops
	 * in her slots instead of on the ground (gathering); in creative nothing drops. Returns null when
	 * broken, or why not.
	 */
	public static String breakBlock(Player player, Companion companion, Block block, boolean keep, List<ItemStack> kept) {
		if (!mayBreak(player, block)) {
			return "No puedes romper " + Chat.id(block.getType()) + ": este terreno está protegido.";
		}
		BlockData data = block.getBlockData();
		List<ItemStack> drops = player.getGameMode() == GameMode.CREATIVE ? List.of() : List.copyOf(CompanionMining.drops(block, companion));
		Location center = MathUtil.center(block);
		block.setType(Material.AIR);
		block.getWorld().spawnParticle(Particle.BLOCK, center, 24, 0.3, 0.3, 0.3, 0.0, data);
		block.getWorld().playSound(center, data.getSoundGroup().getBreakSound(), 1.0f, 0.9f);
		for (ItemStack drop : drops) {
			if (keep && kept != null) {
				kept.add(drop);
			} else {
				block.getWorld().dropItemNaturally(center, drop);
			}
		}
		CompanionMining.wear(player, companion, data);
		return null;
	}

	/**
	 * Places {@code data} at {@code target} as the player would (protection plugins may refuse).
	 * Returns false if it didn't go in.
	 */
	public static boolean place(Player player, Block target, Block against, BlockData data, ItemStack item) {
		if (!target.isReplaceable() || !data.isSupported(target)) {
			return false;
		}
		BlockState replaced = target.getState();
		target.setBlockData(data, true);
		BlockPlaceEvent event = new BlockPlaceEvent(target, replaced, against, item, player, true, EquipmentSlot.HAND);
		if (!event.callEvent() || !event.canBuild()) {
			replaced.update(true, false);
			return false;
		}
		target.getWorld().playSound(MathUtil.center(target), data.getSoundGroup().getPlaceSound(), 1.0f, 1.0f);
		return true;
	}
}
