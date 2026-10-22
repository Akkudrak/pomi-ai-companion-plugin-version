package com.maine.pomi.companion;

import java.util.UUID;

import org.bukkit.FluidCollisionMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import io.papermc.paper.datacomponent.item.ResolvableProfile;

import com.maine.pomi.CompanionManager;
import com.maine.pomi.util.MathUtil;

/**
 * Easter egg, as in the mod: Herobrine shows up far away behind the player, staring, and vanishes
 * the moment the player looks straight at him (or gets close, or after a minute). He's a Mannequin
 * (Paper exposes it in 26.2) with Steve's default skin, so no Mojang lookup is needed.
 */
public final class Herobrine {
	/** Default skins are picked by UUID hash; this one lands on classic Steve, Herobrine's body. */
	private static final UUID STEVE_SKIN = UUID.fromString("48455245-4252-494e-8000-00000000001a");
	private static final double LOOK_ANGLE_DEGREES = 9.0;
	private static final double TOO_CLOSE = 14.0;
	private static final int LIFETIME_TICKS = 1200;
	private static final int GRACE_TICKS = 20;

	private Herobrine() {
	}

	/** Spawns him (or keeps the one already there). Returns where he is. */
	public static Location summon(Player player, Companion companion) {
		if (companion.herobrine != null && companion.herobrine.isValid()) {
			return companion.herobrine.getLocation();
		}
		Location spot = pickSpot(player);
		companion.herobrine = player.getWorld().spawn(spot, Mannequin.class, mannequin -> {
			mannequin.setProfile(ResolvableProfile.resolvableProfile().uuid(STEVE_SKIN).name("Herobrine").build());
			mannequin.setDescription(null);
			mannequin.setImmovable(true);
			mannequin.setInvulnerable(true);
			mannequin.setSilent(true);
			mannequin.setPersistent(false);
			mannequin.getPersistentDataContainer().set(CompanionManager.KEY_HEROBRINE, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
			lookAt(mannequin, player);
		});
		companion.herobrineTicks = 0;
		spot.getWorld().playSound(spot, Sound.AMBIENT_CAVE, 1.0f, 0.7f);
		return spot;
	}

	/** Behind the player and to the right, too far to reach, on the ground, preferably in plain sight. */
	private static Location pickSpot(Player player) {
		World world = player.getWorld();
		Location fallback = null;
		for (double distance : new double[] {28, 24, 32, 20}) {
			for (double angle : new double[] {135, 120, 150, 105}) {
				Location column = MathUtil.relative(player, player.getLocation(), angle, distance);
				if (!world.isChunkLoaded(column.getBlockX() >> 4, column.getBlockZ() >> 4)) {
					continue;
				}
				// Underground he stands at the player's level; above ground, on the surface.
				int surface = world.getHighestBlockYAt(column.getBlockX(), column.getBlockZ(), HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
				double y = player.getY() < surface - 6 ? groundBelow(column.getBlock()) : surface;
				Location spot = new Location(world, column.getBlockX() + 0.5, y, column.getBlockZ() + 0.5);
				if (fallback == null) {
					fallback = spot;
				}
				if (canSee(player, spot.clone().add(0, 1.6, 0))) {
					return spot;
				}
			}
		}
		return fallback != null ? fallback : player.getLocation().add(0, 0, -24);
	}

	private static double groundBelow(Block column) {
		Block block = column;
		for (int i = 0; i < 12 && block.getType().isAir(); i++) {
			block = block.getRelative(BlockFace.DOWN);
		}
		for (int i = 0; i < 12 && !block.getType().isAir(); i++) {
			block = block.getRelative(BlockFace.UP);
		}
		return block.getY();
	}

	private static boolean canSee(Player player, Location target) {
		Location eye = player.getEyeLocation();
		Vector toward = target.toVector().subtract(eye.toVector());
		double distance = toward.length();
		if (distance < 0.01) {
			return true;
		}
		RayTraceResult hit = player.getWorld().rayTraceBlocks(eye, toward.normalize(), distance, FluidCollisionMode.NEVER, true);
		return hit == null || hit.getHitBlock() == null;
	}

	/** Every tick while he's around: stare at the player, vanish when looked at. */
	public static void tick(Player player, Companion companion) {
		Mannequin herobrine = companion.herobrine;
		if (herobrine == null) {
			return;
		}
		if (!herobrine.isValid() || herobrine.getWorld() != player.getWorld()) {
			remove(companion);
			return;
		}
		companion.herobrineTicks++;
		lookAt(herobrine, player);
		if (companion.herobrineTicks < GRACE_TICKS) {
			return;
		}
		Location eyes = herobrine.getEyeLocation();
		Vector toHim = eyes.toVector().subtract(player.getEyeLocation().toVector()).normalize();
		double dot = MathUtil.clamp(player.getEyeLocation().getDirection().dot(toHim), -1.0, 1.0);
		double angle = Math.toDegrees(Math.acos(dot));
		if (angle < LOOK_ANGLE_DEGREES && canSee(player, eyes)) {
			vanish(player, companion, "El jugador miró directo a Herobrine y desapareció en una nube de humo.",
					"Reacciona entre asustada y misteriosa, como si supieras más de lo que dices.");
		} else if (herobrine.getLocation().distance(player.getLocation()) < TOO_CLOSE) {
			vanish(player, companion, "El jugador intentó acercarse a Herobrine y se esfumó antes de que llegara.",
					"Reacciona misteriosa: dile que a él nadie lo alcanza.");
		} else if (companion.herobrineTicks > LIFETIME_TICKS) {
			vanish(player, companion, "Herobrine se fue sin que el jugador lo viera de frente.",
					"Insinúa, en voz baja, que sigue por ahí vigilando.");
		}
	}

	private static void vanish(Player player, Companion companion, String fact, String hint) {
		Mannequin herobrine = companion.herobrine;
		Location at = herobrine.getLocation();
		at.getWorld().spawnParticle(Particle.LARGE_SMOKE, at.clone().add(0, 1.0, 0), 40, 0.3, 0.8, 0.3, 0.02);
		at.getWorld().playSound(at, Sound.AMBIENT_CAVE, 1.0f, 0.5f);
		remove(companion);
		PomiTasks.notify(player, companion, fact, hint, "susto");
	}

	/** Gets rid of him for good (the player left or sent Pomi away). */
	public static void remove(Companion companion) {
		if (companion.herobrine != null) {
			companion.herobrine.remove();
			companion.herobrine = null;
		}
	}

	private static void lookAt(Mannequin herobrine, Player player) {
		Location from = herobrine.getEyeLocation();
		Location to = player.getEyeLocation();
		double dx = to.getX() - from.getX();
		double dz = to.getZ() - from.getZ();
		double dy = to.getY() - from.getY();
		float yaw = MathUtil.yawTowards(from, to);
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		herobrine.setRotation(yaw, pitch);
		herobrine.setBodyYaw(yaw);
	}
}
