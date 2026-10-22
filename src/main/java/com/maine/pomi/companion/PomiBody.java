package com.maine.pomi.companion;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Frog;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.util.Vector;

import com.maine.pomi.util.MathUtil;

/**
 * How the frog moves, ported from the mod's FrogBody: each hop is a jump towards the goal, longer
 * and higher the faster the player goes; she hits and grabs with the tongue. Her own brain is off
 * ({@code setAware(false)}, the plugin's stand-in for the mod's FrogMixin) while physics stay on, so
 * gravity, water and collisions still work.
 */
public final class PomiBody {
	public static final int ACTION_TICKS = 8;
	/** Extra speed per block beyond the stop distance, so the frog catches up. */
	private static final double CATCH_UP_FACTOR = 0.15;
	private static final double MIN_HOP_SPEED = 0.08;
	private static final double MAX_HOP_SPEED = 1.2;

	private PomiBody() {
	}

	/** Right after spawning: switch off the frog's own AI (not its physics). */
	public static void prepare(Frog frog) {
		frog.setAware(false);
	}

	/**
	 * Every tick: head for {@code goal} until within {@code stopDistance} of it (horizontally);
	 * {@code Double.MAX_VALUE} means stand still facing it.
	 */
	public static void move(Companion companion, Player player, Location goal, double stopDistance) {
		Frog frog = companion.entity;
		Location at = frog.getLocation();
		double dx = goal.getX() - at.getX();
		double dz = goal.getZ() - at.getZ();
		double distance = Math.sqrt(dx * dx + dz * dz);
		if (distance > 0.01) {
			face(frog, goal);
		}

		double gap = distance - stopDistance;
		double hopSpeed = 0.0;
		if (gap > 0.1) {
			hopSpeed = companion.mode == Companion.Mode.FOLLOW
					? MathUtil.clamp(companion.playerSpeed + gap * CATCH_UP_FACTOR, MIN_HOP_SPEED, MAX_HOP_SPEED)
					: MathUtil.clamp(0.2 + gap * 0.04, MIN_HOP_SPEED, 0.6);
		}
		hopSpeed *= CompanionStats.POMI.speedFactor();
		double dirX = distance > 0.01 ? dx / distance : 0.0;
		double dirZ = distance > 0.01 ? dz / distance : 0.0;
		Vector velocity = frog.getVelocity();

		if (frog.isInWater()) {
			// Swim towards the goal's height instead of hopping.
			double vy = MathUtil.clamp((goal.getY() - at.getY()) * 0.1, -0.1, 0.1);
			frog.setVelocity(new Vector(dirX * hopSpeed, vy, dirZ * hopSpeed));
			setPose(frog, Pose.STANDING);
		} else if (frog.isOnGround()) {
			if (frog.getPose() == Pose.LONG_JUMPING || hopSpeed == 0.0) {
				// Just landed (or nothing to do): touch the ground for a tick so the jump animation restarts.
				setPose(frog, Pose.STANDING);
				frog.setVelocity(new Vector(dirX * hopSpeed * 0.5, velocity.getY(), dirZ * hopSpeed * 0.5));
			} else {
				// Faster player -> higher, longer hops. Hop higher when a block is in the way, to climb it.
				double jump = blocked(frog, dirX, dirZ) ? 0.5 : MathUtil.clamp(0.25 + hopSpeed * 0.8, 0.3, 0.55);
				frog.setVelocity(new Vector(dirX * hopSpeed, jump, dirZ * hopSpeed));
				setPose(frog, Pose.LONG_JUMPING);
			}
		} else {
			// Mid-air: keep the horizontal pace, gravity handles the arc.
			double air = hopSpeed > 0.0 ? hopSpeed : Math.sqrt(velocity.getX() * velocity.getX() + velocity.getZ() * velocity.getZ()) * 0.5;
			frog.setVelocity(new Vector(dirX * air, velocity.getY(), dirZ * air));
		}
	}

	/** Bukkit has no collision flag for mobs: a solid block right ahead at foot level means a wall. */
	private static boolean blocked(Frog frog, double dirX, double dirZ) {
		return frog.getLocation().add(dirX * 0.7, 0.1, dirZ * 0.7).getBlock().isSolid();
	}

	public static void face(Entity entity, Location point) {
		float yaw = MathUtil.yawTowards(entity.getLocation(), point);
		entity.setRotation(yaw, 0.0f);
		if (entity instanceof Frog frog) {
			frog.setBodyYaw(yaw);
		}
	}

	/** The hit / grab animation towards {@code target}; it lasts {@link #ACTION_TICKS}. */
	public static void action(Companion companion, Entity target) {
		Frog frog = companion.entity;
		frog.setTongueTarget(target);
		setPose(frog, Pose.USING_TONGUE);
		companion.actionTicks = ACTION_TICKS;
	}

	public static void endAction(Companion companion) {
		Frog frog = companion.entity;
		if (frog != null && frog.isValid()) {
			frog.setTongueTarget(null);
			setPose(frog, Pose.STANDING);
		}
	}

	public static void setPose(Frog frog, Pose pose) {
		if (frog.getPose() != pose) {
			frog.setPose(pose);
		}
	}
}
