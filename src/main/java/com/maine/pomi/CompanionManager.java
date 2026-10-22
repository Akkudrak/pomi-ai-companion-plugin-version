package com.maine.pomi;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Frog;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import net.kyori.adventure.text.Component;

import com.maine.pomi.companion.Companion;
import com.maine.pomi.companion.CompanionBuild;
import com.maine.pomi.companion.CompanionPlan;
import com.maine.pomi.companion.CompanionRoutine;
import com.maine.pomi.companion.CompanionWork;
import com.maine.pomi.companion.Herobrine;
import com.maine.pomi.companion.PomiBody;
import com.maine.pomi.companion.PomiChoice;
import com.maine.pomi.companion.PomiMenu;
import com.maine.pomi.companion.PomiTasks;
import com.maine.pomi.util.MathUtil;

/**
 * Every player's Pomi: summoning and sending her away, the per-tick brain (where to go for the
 * current order) ported from the mod's FrogCompanions, and the orders themselves. Players start
 * without her; she only exists while {@link Companion#summoned}.
 */
public final class CompanionManager {
	/** Marks the frog (with its owner's UUID) so it's recognised and never treated as a normal frog. */
	public static final NamespacedKey KEY_OWNER = new NamespacedKey("pomi", "owner");
	public static final NamespacedKey KEY_HEROBRINE = new NamespacedKey("pomi", "herobrine");
	/** The player's Pomi (name and colour), saved on the player. */
	private static final NamespacedKey KEY_CHOICE = new NamespacedKey("pomi", "choice");

	private static final double TELEPORT_DISTANCE = 24.0;
	private static final double ARRIVE_DISTANCE = 0.8;
	private static final int STUCK_TICKS = 100;
	private static final double MAX_ORDER_DISTANCE = 64.0;

	private final PomiPlugin plugin;
	private final Map<UUID, Companion> companions = new HashMap<>();

	CompanionManager(PomiPlugin plugin) {
		this.plugin = plugin;
		// After a /reload the players are already online.
		for (Player player : Bukkit.getOnlinePlayers()) {
			onJoin(player);
		}
	}

	public static boolean isCompanion(Entity entity) {
		return entity != null && entity.getPersistentDataContainer().has(KEY_OWNER, PersistentDataType.STRING);
	}

	public static boolean isHerobrine(Entity entity) {
		return entity != null && entity.getPersistentDataContainer().has(KEY_HEROBRINE, PersistentDataType.BYTE);
	}

	public static PomiChoice loadChoice(Player player) {
		return PomiChoice.fromJson(player.getPersistentDataContainer().get(KEY_CHOICE, PersistentDataType.STRING));
	}

	public static void saveChoice(Player player, PomiChoice choice) {
		player.getPersistentDataContainer().set(KEY_CHOICE, PersistentDataType.STRING, choice.toJson());
	}

	public Companion get(Player player) {
		return companions.get(player.getUniqueId());
	}

	/** The player's Pomi if she's in the world right now, else null. */
	public Companion active(Player player) {
		Companion companion = get(player);
		return companion != null && companion.present() ? companion : null;
	}

	// ---- Lifecycle ----------------------------------------------------------------------------

	/** Joining: only the state; Pomi doesn't appear until the player calls her. */
	public void onJoin(Player player) {
		Companion companion = new Companion(player);
		companion.choice = loadChoice(player);
		companions.put(player.getUniqueId(), companion);
	}

	public void onQuit(Player player) {
		Companion companion = companions.remove(player.getUniqueId());
		if (companion != null) {
			despawn(player, companion);
		}
	}

	public void shutdown() {
		for (Map.Entry<UUID, Companion> entry : companions.entrySet()) {
			Player player = Bukkit.getPlayer(entry.getKey());
			if (player != null) {
				if (entry.getValue().menuOpen) {
					player.closeInventory();
				}
				despawn(player, entry.getValue());
			}
		}
		companions.clear();
	}

	/**
	 * Calls Pomi. The first time ever she gets a random colour, saved on the player.
	 *
	 * @return false if she was already here
	 */
	public boolean summon(Player player) {
		Companion companion = companions.computeIfAbsent(player.getUniqueId(), id -> new Companion(player));
		if (companion.present()) {
			return false;
		}
		boolean first = companion.choice == null;
		if (first) {
			companion.choice = PomiChoice.random();
			saveChoice(player, companion.choice);
		}
		companion.summoned = true;
		companion.mode = Companion.Mode.FOLLOW;
		spawn(player, companion);
		plugin.brain().event(player, companion,
				first ? "¡Hola! Soy Pomi, tu nueva ranita compañera (soy " + companion.choice.colourName() + ")." : "¡Aquí estoy!",
				first ? "El jugador te acaba de llamar por primera vez. Preséntate: di tu nombre y algo de tu personalidad, en una o dos frases."
						: "El jugador te volvió a llamar y acabas de aparecer a su lado. Salúdalo en una frase corta.",
				first ? "fanfarria" : null);
		return true;
	}

	/**
	 * Sends Pomi away until called again: she drops her jobs, gives back what she carries and vanishes.
	 *
	 * @return false if she wasn't here
	 */
	public boolean dismiss(Player player) {
		Companion companion = get(player);
		if (companion == null || !companion.summoned) {
			return false;
		}
		despawn(player, companion);
		return true;
	}

	private void despawn(Player player, Companion companion) {
		if (companion.menuOpen) {
			player.closeInventory();
		}
		if (companion.present()) {
			stopAll(player, companion);
		}
		PomiTasks.returnItems(player, companion);
		Herobrine.remove(companion);
		if (companion.entity != null) {
			Location at = companion.entity.getLocation();
			at.getWorld().spawnParticle(Particle.POOF, at.add(0, 0.4, 0), 10, 0.2, 0.2, 0.2, 0.02);
			companion.entity.remove();
			companion.entity = null;
		}
		companion.summoned = false;
	}

	private void spawn(Player player, Companion companion) {
		Location spot = MathUtil.behind(player, companion.followDistance);
		if (!spot.getBlock().isPassable() || !spot.clone().add(0, 0.5, 0).getBlock().isPassable()) {
			spot = player.getLocation();
		}
		spot.setYaw(player.getYaw());
		spot.setPitch(0);
		companion.entity = player.getWorld().spawn(spot, Frog.class, frog -> {
			frog.setVariant(companion.choice.frogVariant());
			frog.customName(Component.text(companion.name()));
			frog.setCustomNameVisible(true);
			// Not saved with the chunk: she belongs to the player, not to the world (no copies after a crash).
			frog.setPersistent(false);
			frog.setRemoveWhenFarAway(false);
			frog.setInvulnerable(true);
			frog.getPersistentDataContainer().set(KEY_OWNER, PersistentDataType.STRING, player.getUniqueId().toString());
			PomiBody.prepare(frog);
		});
		companion.attackTarget = null;
		companion.pickupTarget = null;
		companion.actionTicks = 0;
		player.getWorld().playSound(spot, Sound.ENTITY_FROG_AMBIENT, 1.0f, 1.3f);
	}

	// ---- Tick ---------------------------------------------------------------------------------

	void tick() {
		for (Map.Entry<UUID, Companion> entry : companions.entrySet()) {
			Player player = Bukkit.getPlayer(entry.getKey());
			if (player == null) {
				continue;
			}
			Companion companion = entry.getValue();
			Herobrine.tick(player, companion);
			tickCompanion(player, companion);
			if (companion.menuOpen && Bukkit.getCurrentTick() % 10 == 0) {
				PomiMenu.refreshOpen(player);
			}
		}
	}

	private void tickCompanion(Player player, Companion companion) {
		Location playerPos = player.getLocation();
		// Measure the player's speed from position changes, smoothed; big jumps are teleports.
		double moved = companion.lastPos.getWorld() == playerPos.getWorld() ? MathUtil.horizontal(playerPos, companion.lastPos) : 0.0;
		if (moved > 2.0) {
			moved = 0.0;
		}
		companion.playerSpeed = companion.playerSpeed * 0.7 + moved * 0.3;
		companion.lastPos = playerPos;

		if (!companion.summoned || companion.choice == null || player.getGameMode() == GameMode.SPECTATOR || player.isDead()) {
			return;
		}
		Frog frog = companion.entity;
		if (frog == null || !frog.isValid() || frog.getWorld() != player.getWorld()) {
			// Unloaded with its chunk, or the player changed dimension: back next to them.
			if (frog != null) {
				frog.remove();
			}
			stopAll(player, companion);
			spawn(player, companion);
			return;
		}
		if (frog.getTicksLived() % 4 == 0) {
			PomiTasks.catchThrown(player, companion);
		}
		if (companion.actionTicks > 0) {
			if (--companion.actionTicks == 0) {
				PomiBody.endAction(companion);
			}
			Vector velocity = frog.getVelocity();
			frog.setVelocity(new Vector(0.0, velocity.getY(), 0.0));
			return;
		}

		Location goal = null;
		double stopDistance = 0.0;
		boolean busy = companion.mode == Companion.Mode.ATTACK || companion.mode == Companion.Mode.PICKUP;
		switch (companion.mode) {
			case ROUTINE -> {
				Location routineGoal = CompanionRoutine.tick(player, companion);
				if (companion.routineOwnsBody) {
					return;
				}
				goal = routineGoal != null ? routineGoal : playerPos;
				stopDistance = routineGoal != null ? 1.0 : Double.MAX_VALUE;
			}
			case GATHER, FARM, REMOVE, BUILD -> {
				Location workGoal = switch (companion.mode) {
					case GATHER -> CompanionWork.tickGather(player, companion);
					case FARM -> CompanionWork.tickFarm(player, companion);
					case BUILD -> CompanionBuild.tickBuild(player, companion);
					default -> CompanionWork.tickRemove(player, companion);
				};
				if (companion.workOwnsBody) {
					return;
				}
				goal = workGoal != null ? workGoal : frog.getLocation();
				stopDistance = workGoal != null ? 0.4 : Double.MAX_VALUE;
			}
			case GUARD -> {
				goal = PomiTasks.tickGuard(player, companion);
				if (goal != null) {
					stopDistance = 1.0;
				} else {
					if (tooFar(player, companion)) {
						teleportBehind(player, companion);
						return;
					}
					goal = playerPos;
					stopDistance = companion.followDistance;
				}
			}
			case ATTACK -> {
				goal = PomiTasks.tickAttack(player, companion);
				stopDistance = 1.0;
			}
			case PICKUP -> {
				goal = PomiTasks.tickPickup(player, companion);
				stopDistance = 0.3;
			}
			default -> {
			}
		}
		if (goal != null) {
			// Heading for the mob, item, block or spot.
		} else if (busy) {
			goal = frog.getLocation();
			stopDistance = Double.MAX_VALUE;
		} else if (companion.mode == Companion.Mode.FOLLOW) {
			if (tooFar(player, companion)) {
				teleportBehind(player, companion);
				return;
			}
			goal = playerPos;
			stopDistance = companion.followDistance;
		} else if (companion.mode == Companion.Mode.GO_TO) {
			goal = new Location(frog.getWorld(), companion.target.getX(), frog.getY(), companion.target.getZ());
			stopDistance = ARRIVE_DISTANCE;
			double remaining = MathUtil.horizontal(goal, frog.getLocation());
			if (remaining < companion.bestDistance - 0.5) {
				companion.bestDistance = remaining;
				companion.stuckTicks = 0;
			} else if (++companion.stuckTicks > STUCK_TICKS) {
				// Blocked by a wall or a cliff: wait here instead of trying forever.
				companion.mode = Companion.Mode.STAY;
			}
			if (remaining <= ARRIVE_DISTANCE) {
				companion.mode = Companion.Mode.STAY;
			}
		} else {
			// Staying: don't move, but keep looking at the player.
			goal = playerPos;
			stopDistance = Double.MAX_VALUE;
		}
		PomiBody.move(companion, player, goal, stopDistance);
	}

	private static boolean tooFar(Player player, Companion companion) {
		Location frog = companion.entity.getLocation();
		return MathUtil.horizontal(player.getLocation(), frog) > TELEPORT_DISTANCE + companion.followDistance
				|| Math.abs(player.getY() - frog.getY()) > TELEPORT_DISTANCE;
	}

	/** Puts Pomi right behind the player (or on them, if that spot is inside a wall). */
	public void teleportBehind(Player player, Companion companion) {
		Location spot = MathUtil.behind(player, companion.followDistance);
		if (!spot.getBlock().isPassable() || !spot.clone().add(0, 0.5, 0).getBlock().isPassable()) {
			spot = player.getLocation();
		}
		spot.setYaw(player.getYaw());
		spot.setPitch(0);
		companion.entity.teleport(spot);
		companion.entity.setVelocity(new Vector());
		PomiBody.setPose(companion.entity, Pose.STANDING);
	}

	// ---- Orders -------------------------------------------------------------------------------

	/** Something new from the player replaces what she was doing (and the rest of a multi-step job). */
	private static void interrupt(Companion companion) {
		CompanionPlan.cancel(companion);
		companion.attackTarget = null;
		companion.pickupTarget = null;
	}

	public String follow(Player player, Companion companion, double distance) {
		interrupt(companion);
		companion.mode = Companion.Mode.FOLLOW;
		if (distance > 0) {
			companion.followDistance = MathUtil.clamp(distance, Companion.MIN_FOLLOW_DISTANCE, Companion.MAX_FOLLOW_DISTANCE);
			return "Ahora lo sigues a " + Math.round(companion.followDistance * 10) / 10.0 + " bloques.";
		}
		return "Lo sigues.";
	}

	public String stay(Player player, Companion companion) {
		if (companion.mode == Companion.Mode.GO_TO) {
			// A trip already ends standing still; "go there, then stay" must not cancel the trip.
			return "Al llegar te quedas ahí.";
		}
		interrupt(companion);
		companion.mode = Companion.Mode.STAY;
		Vector velocity = companion.entity.getVelocity();
		companion.entity.setVelocity(new Vector(0, velocity.getY(), 0));
		return "Te quedas aquí esperando.";
	}

	public String come(Player player, Companion companion) {
		interrupt(companion);
		companion.mode = Companion.Mode.FOLLOW;
		Location from = companion.entity.getLocation();
		from.getWorld().spawnParticle(Particle.POOF, from.add(0, 0.4, 0), 8, 0.2, 0.2, 0.2, 0.02);
		teleportBehind(player, companion);
		Location to = companion.entity.getLocation();
		to.getWorld().spawnParticle(Particle.POOF, to.add(0, 0.4, 0), 8, 0.2, 0.2, 0.2, 0.02);
		return "Apareciste junto al jugador.";
	}

	/** Goes to a spot; {@code target} null means where the player points. */
	public String goTo(Player player, Companion companion, Location target) {
		if (target == null) {
			var aim = player.rayTraceBlocks(PomiTasks.AIM_DISTANCE * 2);
			if (aim == null || aim.getHitBlock() == null) {
				return "El jugador no apunta a ningún lugar. Que apunte con la mira a donde quiere que vayas.";
			}
			target = aim.getHitBlock().getLocation().add(0.5, 1, 0.5);
		}
		if (MathUtil.horizontal(target, companion.entity.getLocation()) > MAX_ORDER_DISTANCE) {
			return "Eso está muy lejos (más de " + (int) MAX_ORDER_DISTANCE + " bloques).";
		}
		interrupt(companion);
		companion.mode = Companion.Mode.GO_TO;
		companion.target = target;
		companion.bestDistance = Double.MAX_VALUE;
		companion.stuckTicks = 0;
		return "Vas para allá; al llegar te quedas quieta.";
	}

	/**
	 * Stops everything (the current task, the rest of a multi-step job, the guard) and goes back to
	 * following. Returns what she was doing, or "" if nothing.
	 */
	public String stopAll(Player player, Companion companion) {
		String was = companion.mode == Companion.Mode.FOLLOW && !companion.planActive ? "" : PomiMenu.doing(companion);
		interrupt(companion);
		companion.workOwnsBody = false;
		companion.routineOwnsBody = false;
		companion.workBlock = null;
		companion.workQueue.clear();
		companion.routine = java.util.List.of();
		if (companion.entity != null && companion.entity.isValid()) {
			PomiBody.endAction(companion);
			PomiBody.setPose(companion.entity, Pose.STANDING);
		}
		companion.actionTicks = 0;
		companion.mode = Companion.Mode.FOLLOW;
		return was;
	}
}
