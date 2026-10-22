package com.maine.pomi.util;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

/** Geometry shared by the companion code. Yaw follows Minecraft: forward is (-sin(yaw), cos(yaw)). */
public final class MathUtil {
	private MathUtil() {
	}

	public static double horizontal(Location a, Location b) {
		double dx = a.getX() - b.getX();
		double dz = a.getZ() - b.getZ();
		return Math.sqrt(dx * dx + dz * dz);
	}

	public static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	public static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	/** The center of a block. */
	public static Location center(Block block) {
		return block.getLocation().add(0.5, 0.5, 0.5);
	}

	/** The middle of a block's floor. */
	public static Location bottomCenter(Block block) {
		return block.getLocation().add(0.5, 0.0, 0.5);
	}

	/** {@code distance} blocks behind the player, at their height. */
	public static Location behind(Player player, double distance) {
		double yaw = Math.toRadians(player.getYaw());
		return new Location(player.getWorld(), player.getX() + Math.sin(yaw) * distance, player.getY(),
				player.getZ() - Math.cos(yaw) * distance);
	}

	/** {@code distance} blocks from {@code from}, at {@code degrees} clockwise from where the player looks. */
	public static Location relative(Player player, Location from, double degrees, double distance) {
		double yaw = Math.toRadians(player.getYaw() + degrees);
		return new Location(from.getWorld(), from.getX() - Math.sin(yaw) * distance, from.getY(),
				from.getZ() + Math.cos(yaw) * distance);
	}

	/** Yaw that faces from {@code from} towards {@code to}. */
	public static float yawTowards(Location from, Location to) {
		double dx = to.getX() - from.getX();
		double dz = to.getZ() - from.getZ();
		return (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
	}

	public static BlockFace clockwise(BlockFace face) {
		return switch (face) {
			case NORTH -> BlockFace.EAST;
			case EAST -> BlockFace.SOUTH;
			case SOUTH -> BlockFace.WEST;
			default -> BlockFace.NORTH;
		};
	}

	/**
	 * Where {@code target} is from the player's point of view, in the mod's words: "enfrente a tu
	 * derecha, a unos 17 bloques, y 9 más abajo".
	 */
	public static String describe(Player player, Location target) {
		Location eye = player.getLocation();
		double dx = target.getX() - eye.getX();
		double dz = target.getZ() - eye.getZ();
		double distance = Math.sqrt(dx * dx + dz * dz);
		double toTarget = Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
		double angle = ((toTarget - player.getYaw()) % 360 + 540) % 360 - 180;
		String side;
		if (Math.abs(angle) <= 22.5) {
			side = "enfrente";
		} else if (Math.abs(angle) >= 157.5) {
			side = "atrás";
		} else if (Math.abs(angle) < 67.5) {
			side = angle > 0 ? "enfrente a tu derecha" : "enfrente a tu izquierda";
		} else if (Math.abs(angle) <= 112.5) {
			side = angle > 0 ? "a tu derecha" : "a tu izquierda";
		} else {
			side = angle > 0 ? "atrás a tu derecha" : "atrás a tu izquierda";
		}
		StringBuilder text = new StringBuilder(side).append(", a unos ").append(Math.round(distance)).append(" bloques");
		long dy = Math.round(target.getY() - eye.getY());
		if (Math.abs(dy) >= 3) {
			text.append(", y ").append(Math.abs(dy)).append(dy > 0 ? " más arriba" : " más abajo");
		}
		return text.toString();
	}
}
