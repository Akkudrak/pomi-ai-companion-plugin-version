package com.maine.pomi.companion;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockSupport;
import org.bukkit.entity.Frog;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import com.maine.pomi.util.Chat;
import com.maine.pomi.util.MathUtil;

/**
 * Simple building with blocks the player gave Pomi: a straight wall or a column, layer by layer
 * from the bottom. The two lowest layers she places from the ground; higher up she climbs onto
 * what she built and puts the block under her feet, the way players pillar up. Ported from the mod.
 */
public final class CompanionBuild {
	public static final int MAX_SIZE = 10;
	private static final double MAX_DISTANCE = 16.0;
	private static final int GROUND_LAYERS = 2;
	private static final double PLACE_REACH = 2.5;
	private static final int TARGET_TIMEOUT_TICKS = 160;
	private static final int BUILD_TIMEOUT_TICKS = 3600;

	private CompanionBuild() {
	}

	/**
	 * Plans a wall ({@code length} x {@code height}) or a column ({@code height}) and starts it, next
	 * to the face the player points at (or two blocks ahead of them).
	 *
	 * @param across the wall goes across the player's view (like a barrier) instead of along it
	 * @param itemId the block to use, or "" for the one she carries most of
	 * @return the result for the LLM
	 */
	public static String start(Player player, Companion companion, boolean column, int length, int height, boolean across, String itemId) {
		int width = column ? 1 : MathUtil.clamp(length, 1, MAX_SIZE);
		int tall = MathUtil.clamp(height, 1, MAX_SIZE);
		int slot = -1;
		if (itemId != null && !itemId.isBlank()) {
			Material item = Material.matchMaterial(itemId);
			for (int i = 0; i < companion.inventory.length && item != null; i++) {
				if (!Companion.isEmpty(companion.inventory[i]) && companion.inventory[i].getType() == item) {
					slot = i;
				}
			}
			if (slot < 0) {
				return "No traes " + (item == null ? itemId : Chat.id(item)) + ". El jugador te tiene que dar los bloques: que te los lance (tecla Q).";
			}
		} else {
			for (int i = 0; i < companion.inventory.length; i++) {
				if (isBuildingBlock(companion.inventory[i])
						&& (slot < 0 || companion.inventory[i].getAmount() > companion.inventory[slot].getAmount())) {
					slot = i;
				}
			}
			if (slot < 0) {
				return "No traes bloques para construir. El jugador te tiene que dar los bloques: que te los lance (tecla Q).";
			}
		}
		ItemStack material = companion.inventory[slot];
		if (!isBuildingBlock(material)) {
			return Chat.id(material.getType()) + " no sirve para construir paredes o columnas: hace falta un bloque completo "
					+ "(tierra, piedra, tablones, ladrillos...).";
		}
		Material type = material.getType();
		int needed = width * tall;
		int have = 0;
		for (ItemStack stack : companion.inventory) {
			if (!Companion.isEmpty(stack) && stack.getType() == type) {
				have += stack.getAmount();
			}
		}
		if (have < needed && player.getGameMode() != GameMode.CREATIVE) {
			return "No te alcanzan los materiales: " + (column ? "una columna de " + tall : "una pared de " + width + " x " + tall)
					+ " necesita " + needed + " de " + Chat.id(type) + " y traes " + have + ". Que te lance " + (needed - have) + " más (tecla Q).";
		}

		Block start = startBlock(player);
		BlockFace facing = player.getFacing();
		BlockFace along = across ? MathUtil.clockwise(facing) : facing;
		int from = across ? -(width - 1) / 2 : 0;
		List<Block> targets = new ArrayList<>();
		for (int y = 0; y < tall; y++) {
			for (int i = 0; i < width; i++) {
				int step = from + i;
				targets.add(start.getRelative(along.getModX() * step, y, along.getModZ() * step));
			}
		}
		for (Block block : targets) {
			if (MathUtil.center(block).distance(player.getLocation()) > MAX_DISTANCE) {
				return "Eso quedaría muy lejos del jugador (más de " + (int) MAX_DISTANCE + " bloques). Que se acerque o apunte más cerca.";
			}
		}
		if (player.getGameMode() == GameMode.ADVENTURE || player.getGameMode() == GameMode.SPECTATOR) {
			return "Aquí el jugador no puede construir (modo de juego).";
		}
		CompanionPlan.cancel(companion);
		companion.attackTarget = null;
		companion.pickupTarget = null;
		companion.mode = Companion.Mode.BUILD;
		companion.buildTargets = targets;
		companion.buildIndex = 0;
		companion.buildMaterial = type;
		companion.buildBaseY = start.getY();
		companion.buildAxis = along;
		companion.buildPlaced = 0;
		companion.workTicks = 0;
		companion.workTargetTicks = 0;
		companion.workNotes.clear();
		String shape = column ? "una columna de " + tall + " de alto" : "una pared de " + width + " de largo por " + tall + " de alto"
				+ (across ? " atravesada frente al jugador" : " hacia donde mira el jugador");
		return "Vas a construir " + shape + " con " + needed + " de " + Chat.id(type) + ", empezando a unos "
				+ Math.round(MathUtil.center(start).distance(player.getLocation())) + " bloques del jugador. Al terminar llegará un "
				+ "[Evento]; por ahora solo di que vas.";
	}

	/** The empty block next to the face the player points at, or two blocks ahead of them at their feet. */
	private static Block startBlock(Player player) {
		RayTraceResult aim = player.rayTraceBlocks(PomiTasks.AIM_DISTANCE);
		if (aim != null && aim.getHitBlock() != null && aim.getHitBlockFace() != null) {
			return aim.getHitBlock().getRelative(aim.getHitBlockFace());
		}
		return player.getLocation().getBlock().getRelative(player.getFacing(), 2);
	}

	/** A full, solid block: what walls and columns are made of. */
	private static boolean isBuildingBlock(ItemStack stack) {
		return !Companion.isEmpty(stack) && stack.getType().isBlock() && stack.getType().isSolid()
				&& stack.getType().createBlockData().isFaceSturdy(BlockFace.UP, BlockSupport.FULL);
	}

	/** @return where to head this tick, or null to stay put */
	public static Location tickBuild(Player player, Companion companion) {
		companion.workOwnsBody = false;
		Frog frog = companion.entity;
		if (++companion.workTicks > BUILD_TIMEOUT_TICKS) {
			note(companion, "Se le acabó el tiempo antes de terminar.");
			finish(player, companion);
			return null;
		}
		// Cells already taken (a tree, a rock, the player's own block) are left as they are.
		while (companion.buildIndex < companion.buildTargets.size()) {
			Block there = companion.buildTargets.get(companion.buildIndex);
			if (there.isReplaceable()) {
				break;
			}
			if (there.getType() != companion.buildMaterial) {
				note(companion, "Algunos lugares ya estaban ocupados por otros bloques y los dejó así.");
			}
			companion.buildIndex++;
			companion.workTargetTicks = 0;
		}
		if (companion.buildIndex >= companion.buildTargets.size()) {
			finish(player, companion);
			return null;
		}
		if (slot(companion) < 0) {
			note(companion, "Se le acabaron los bloques.");
			finish(player, companion);
			return null;
		}
		Block target = companion.buildTargets.get(companion.buildIndex);
		if (++companion.workTargetTicks > TARGET_TIMEOUT_TICKS) {
			note(companion, "Algunos bloques no los pudo poner (no alcanzaba, algo estorbaba o el terreno está protegido).");
			companion.buildIndex++;
			companion.workTargetTicks = 0;
			return null;
		}

		if (target.getY() - companion.buildBaseY < GROUND_LAYERS) {
			// From the ground, standing beside the wall (on the player's side).
			Location center = MathUtil.bottomCenter(target);
			double distance = MathUtil.horizontal(frog.getLocation(), center);
			boolean inReach = distance <= PLACE_REACH && distance >= 0.8 && Math.abs(frog.getY() - target.getY()) <= 2.5;
			if (!inReach) {
				return standingSpot(player, companion, target);
			}
			if (place(player, companion, target)) {
				companion.buildIndex++;
				companion.workTargetTicks = 0;
			}
			return null;
		}

		// Higher up: stand on the block below the target, jump, and put the block under her feet.
		companion.workOwnsBody = true;
		Location top = MathUtil.bottomCenter(target);
		boolean onSpot = MathUtil.horizontal(frog.getLocation(), top) < 0.35 && frog.getY() >= target.getY() - 0.05;
		if (!onSpot) {
			Block below = target.getRelative(BlockFace.DOWN);
			if (!below.getBlockData().isFaceSturdy(BlockFace.UP, BlockSupport.FULL) || !target.getRelative(BlockFace.UP).isReplaceable()) {
				note(companion, "Algunos bloques de arriba no los pudo poner: no había dónde pararse.");
				companion.buildIndex++;
				companion.workTargetTicks = 0;
				return null;
			}
			// A hop up onto what she built.
			top.setYaw(frog.getYaw());
			frog.teleport(top);
			frog.setVelocity(new Vector());
			return null;
		}
		if (frog.isOnGround() && frog.getY() < target.getY() + 0.5) {
			frog.setVelocity(new Vector(0.0, 0.42, 0.0));
			PomiBody.setPose(frog, Pose.LONG_JUMPING);
			return null;
		}
		if (frog.getY() >= target.getY() + 1.0 && place(player, companion, target)) {
			companion.buildIndex++;
			companion.workTargetTicks = 0;
			PomiBody.setPose(frog, Pose.STANDING);
		}
		return null;
	}

	/** Beside the target, on the player's side of the wall, at the base level. */
	private static Location standingSpot(Player player, Companion companion, Block target) {
		BlockFace side = MathUtil.clockwise(companion.buildAxis);
		Location center = MathUtil.bottomCenter(target);
		double toPlayerX = player.getX() - center.getX();
		double toPlayerZ = player.getZ() - center.getZ();
		if (toPlayerX * side.getModX() + toPlayerZ * side.getModZ() < 0) {
			side = side.getOppositeFace();
		}
		return new Location(center.getWorld(), center.getX() + side.getModX() * 1.5, companion.buildBaseY, center.getZ() + side.getModZ() * 1.5);
	}

	/** Places one block of the material as the player would (protections apply); false if it didn't go in. */
	private static boolean place(Player player, Companion companion, Block target) {
		int slot = slot(companion);
		if (slot < 0) {
			return false;
		}
		ItemStack stack = companion.inventory[slot];
		if (!Protection.place(player, target, target.getRelative(BlockFace.DOWN), companion.buildMaterial.createBlockData(), stack.asOne())) {
			return false;
		}
		if (player.getGameMode() != GameMode.CREATIVE) {
			stack.setAmount(stack.getAmount() - 1);
			if (stack.getAmount() <= 0) {
				companion.inventory[slot] = null;
			}
		}
		companion.buildPlaced++;
		PomiBody.face(companion.entity, MathUtil.center(target));
		return true;
	}

	private static int slot(Companion companion) {
		for (int i = 0; i < companion.inventory.length; i++) {
			if (!Companion.isEmpty(companion.inventory[i]) && companion.inventory[i].getType() == companion.buildMaterial) {
				return i;
			}
		}
		return -1;
	}

	private static void finish(Player player, Companion companion) {
		companion.mode = Companion.Mode.FOLLOW;
		companion.workOwnsBody = false;
		PomiBody.setPose(companion.entity, Pose.STANDING);
		StringBuilder fact = new StringBuilder("Pomi terminó de construir: puso ").append(companion.buildPlaced)
				.append(" de ").append(companion.buildTargets.size()).append(" bloques.");
		for (String note : companion.workNotes) {
			fact.append(' ').append(note);
		}
		PomiTasks.notify(player, companion, fact.toString(), "Celebra la obra en una frase, emocionada.", "fanfarria");
	}

	private static void note(Companion companion, String note) {
		if (!companion.workNotes.contains(note)) {
			companion.workNotes.add(note);
		}
	}
}
