package com.maine.pomi.companion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Frog;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.maine.pomi.util.Chat;
import com.maine.pomi.util.MathUtil;

/**
 * Short routines strung together from basic actions ("baila": hop, spin, hearts), for requests
 * nobody programmed one by one. Same steps and limits as the mod: at most {@link #MAX_STEPS} steps
 * and 15 s, hits on the player are small, spaced out and never leave them low, and other players
 * are never a target.
 */
public final class CompanionRoutine {
	public static final int MAX_STEPS = 10;
	private static final int MAX_TICKS = 15 * 20;
	private static final int STEP_TIMEOUT_TICKS = 5 * 20;
	private static final double PLAYER_HIT_DAMAGE = 2.0;
	private static final double PLAYER_SAFE_HEALTH = 6.0;
	private static final int MAX_PLAYER_HITS = 3;
	private static final int HIT_INTERVAL_TICKS = 20;
	private static final double REACH = 1.6;
	private static final double ARRIVE = 1.0;
	private static final double SEARCH_RADIUS = 16.0;
	private static final int JUMP_TICKS = 12;
	private static final int SPIN_TICKS = 20;
	private static final int LOOK_TICKS = 15;
	private static final double BREAK_REACH = 2.0;

	private static final Map<String, Particle> PARTICLES = Map.ofEntries(
			Map.entry("corazones", Particle.HEART), Map.entry("notas", Particle.NOTE), Map.entry("enojo", Particle.ANGRY_VILLAGER),
			Map.entry("felicidad", Particle.HAPPY_VILLAGER), Map.entry("chispas", Particle.ELECTRIC_SPARK), Map.entry("humo", Particle.POOF),
			Map.entry("fuego", Particle.FLAME), Map.entry("brillos", Particle.END_ROD), Map.entry("fiesta", Particle.TOTEM_OF_UNDYING),
			Map.entry("magia", Particle.WITCH));

	/** One checked step. {@code target} is "jugador", an entity type key, or "x|y|z" for romper. */
	public record Step(String action, String target, String direction, int blocks, int times, int ticks, String sound) {
	}

	private CompanionRoutine() {
	}

	public static String particleNames() {
		return String.join(", ", PARTICLES.keySet().stream().sorted().toList());
	}

	/** Checks the steps and starts the routine. Returns the result for the LLM. */
	public static String start(Player player, Companion companion, JsonArray array) {
		List<Step> steps = new ArrayList<>();
		try {
			for (JsonElement element : array) {
				if (steps.size() == MAX_STEPS) {
					break;
				}
				String error = parse(player, companion, element.getAsJsonObject(), steps);
				if (error != null) {
					return error;
				}
			}
		} catch (RuntimeException e) {
			return "Pasos inválidos.";
		}
		if (steps.isEmpty()) {
			return "No hay pasos que hacer.";
		}
		CompanionPlan.cancel(companion);
		companion.attackTarget = null;
		companion.pickupTarget = null;
		companion.routine = steps;
		companion.routineStep = 0;
		companion.routineTicks = 0;
		companion.routinePlayerHits = 0;
		companion.routineNotes.clear();
		companion.mode = Companion.Mode.ROUTINE;
		beginStep(player, companion);
		return "Empezaste la rutina (" + steps.size() + " pasos" + (steps.size() == MAX_STEPS ? ", el máximo" : "")
				+ "). Dilo con tu estilo mientras la haces; si algo no sale, llegará un [Evento].";
	}

	/** A little dance without Grok: two hops, a spin and hearts. */
	public static String dance(Player player, Companion companion) {
		JsonArray steps = new JsonArray();
		steps.add(step("saltar", "veces", 2));
		steps.add(step("girar", "veces", 1));
		steps.add(step("particulas", "nombre", "corazones"));
		steps.add(step("saltar", "veces", 1));
		return start(player, companion, steps);
	}

	/** Sings without Grok: notes and a few note-block chimes. */
	public static String sing(Player player, Companion companion) {
		JsonArray steps = new JsonArray();
		steps.add(step("mirar", "objetivo", "jugador"));
		steps.add(step("sonido", "id", "minecraft:block.note_block.harp"));
		steps.add(step("particulas", "nombre", "notas"));
		steps.add(step("sonido", "id", "minecraft:block.note_block.bell"));
		steps.add(step("saltar", "veces", 1));
		steps.add(step("sonido", "id", "minecraft:block.note_block.flute"));
		steps.add(step("particulas", "nombre", "notas"));
		return start(player, companion, steps);
	}

	/** Three hops. */
	public static String jump(Player player, Companion companion) {
		JsonArray steps = new JsonArray();
		steps.add(step("saltar", "veces", 3));
		return start(player, companion, steps);
	}

	/** Looks at the player, hops and shows happy particles. */
	public static String greet(Player player, Companion companion) {
		JsonArray steps = new JsonArray();
		steps.add(step("mirar", "objetivo", "jugador"));
		steps.add(step("saltar", "veces", 1));
		steps.add(step("particulas", "nombre", "felicidad"));
		return start(player, companion, steps);
	}

	/** A loud croak with notes. */
	public static String croak(Player player, Companion companion) {
		JsonArray steps = new JsonArray();
		steps.add(step("mirar", "objetivo", "jugador"));
		steps.add(step("sonido", "id", "minecraft:entity.frog.ambient"));
		steps.add(step("particulas", "nombre", "notas"));
		return start(player, companion, steps);
	}

	private static JsonObject step(String action, String key, Object value) {
		JsonObject o = new JsonObject();
		o.addProperty("accion", action);
		if (value instanceof Number n) {
			o.addProperty(key, n);
		} else {
			o.addProperty(key, String.valueOf(value));
		}
		return o;
	}

	private static String parse(Player player, Companion companion, JsonObject o, List<Step> steps) {
		String action = text(o, "accion");
		int times = MathUtil.clamp(integer(o, "veces", 1), 1, 3);
		switch (action) {
			case "ir" -> {
				String direction = text(o, "direccion");
				if (!List.of("adelante", "atras", "izquierda", "derecha", "jugador").contains(direction)) {
					direction = "jugador";
				}
				steps.add(new Step(action, "", direction, MathUtil.clamp(integer(o, "bloques", 3), 1, 8), 1, 0, ""));
			}
			case "mirar", "golpear" -> {
				String target = text(o, "objetivo");
				if (target.isEmpty()) {
					target = "jugador";
				}
				if (!target.equals("jugador")) {
					NamespacedKey key = NamespacedKey.fromString(target.contains(":") ? target : "minecraft:" + target);
					EntityType type = key == null ? null : Registry.ENTITY_TYPE.get(key);
					if (type == null) {
						return "No existe el mob \"" + target + "\". Usa su id en inglés (zombie, skeleton) o 'jugador'.";
					}
					if (type == EntityType.PLAYER) {
						return "Solo puedes golpear o mirar a tu jugador ('jugador'), nunca a otros jugadores.";
					}
					target = type.getKey().toString();
				}
				steps.add(new Step(action, target, "", 0, times, 0, ""));
			}
			case "romper" -> {
				RayTraceResult aim = player.rayTraceBlocks(PomiTasks.AIM_DISTANCE);
				Block block = aim == null ? null : aim.getHitBlock();
				if (block == null) {
					return "El jugador no está apuntando a ningún bloque. Pídele que apunte con la mira al que quiere romper.";
				}
				String refusal = Protection.whyNotBreak(player, companion, block, Protection.BREAK_RADIUS);
				if (refusal != null) {
					return refusal;
				}
				steps.add(new Step(action, block.getX() + "|" + block.getY() + "|" + block.getZ(), "", 0, 1, 0, ""));
			}
			case "saltar", "girar" -> steps.add(new Step(action, "", "", 0, times, 0, ""));
			case "particulas" -> {
				String name = text(o, "nombre");
				if (!PARTICLES.containsKey(name)) {
					return "Esas partículas no existen. Hay: " + particleNames() + ".";
				}
				steps.add(new Step(action, name, "", 0, times, 0, ""));
			}
			case "sonido" -> {
				NamespacedKey key = NamespacedKey.fromString(text(o, "id"));
				if (key == null || Registry.SOUNDS.get(key) == null) {
					return "Ese sonido no existe.";
				}
				steps.add(new Step(action, "", "", 0, 1, 0, key.toString()));
			}
			case "esperar" -> {
				double seconds = o.has("segundos") ? o.get("segundos").getAsDouble() : 1.0;
				steps.add(new Step(action, "", "", 0, 1, (int) Math.round(MathUtil.clamp(seconds, 0.5, 5.0) * 20), ""));
			}
			default -> {
				return "Acción desconocida: \"" + action + "\".";
			}
		}
		return null;
	}

	/** Runs one tick of the routine. @return where to head this tick, or null to stand still */
	public static Location tick(Player player, Companion companion) {
		companion.routineOwnsBody = false;
		if (++companion.routineTicks > MAX_TICKS) {
			note(companion, "La rutina duró demasiado y la cortó a la mitad.");
			finish(player, companion);
			return null;
		}
		Step step = companion.routine.get(companion.routineStep);
		companion.stepTicks++;
		if (companion.stepTicks > STEP_TIMEOUT_TICKS) {
			if (step.action().equals("ir") || step.action().equals("golpear")) {
				note(companion, step.action().equals("ir") ? "No pudo llegar a donde iba." : "No alcanzó a su objetivo.");
			}
			return next(player, companion);
		}
		Frog frog = companion.entity;
		switch (step.action()) {
			case "ir" -> {
				if (MathUtil.horizontal(frog.getLocation(), companion.stepGoal) <= ARRIVE) {
					return next(player, companion);
				}
				return companion.stepGoal;
			}
			case "mirar" -> {
				LivingEntity target = target(player, companion, step);
				if (target == null) {
					note(companion, "No encontró ningún " + step.target().replace("minecraft:", "") + " cerca para mirarlo.");
					return next(player, companion);
				}
				PomiBody.face(frog, target.getEyeLocation());
				companion.routineOwnsBody = true;
				return companion.stepTicks >= LOOK_TICKS ? next(player, companion) : null;
			}
			case "golpear" -> {
				LivingEntity target = target(player, companion, step);
				if (target == null) {
					note(companion, "No encontró ningún " + step.target().replace("minecraft:", "") + " cerca para golpearlo.");
					return next(player, companion);
				}
				if (target.getLocation().distance(frog.getLocation()) > REACH) {
					return target.getLocation();
				}
				if (companion.stepCount >= step.times()) {
					return next(player, companion);
				}
				if (companion.stepCooldown-- > 0) {
					PomiBody.face(frog, target.getEyeLocation());
					companion.routineOwnsBody = true;
					return null;
				}
				hit(player, companion, target);
				companion.stepCount++;
				companion.stepCooldown = HIT_INTERVAL_TICKS;
				return null;
			}
			case "romper" -> {
				String[] parts = step.target().split("[|]");
				Block block = frog.getWorld().getBlockAt(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
				Location center = MathUtil.center(block);
				if (MathUtil.horizontal(frog.getLocation(), center) <= BREAK_REACH && block.getY() - frog.getLocation().getBlockY() > 2) {
					note(companion, "Ese bloque estaba muy alto: solo rompe hasta 3 bloques de altura.");
					return next(player, companion);
				}
				if (frog.getLocation().distance(center) > BREAK_REACH) {
					return center;
				}
				if (companion.stepCount == 0) {
					companion.stepCount = CompanionMining.breakTicks(block, companion.tool);
					companion.stepCooldown = companion.stepCount;
				}
				companion.routineOwnsBody = true;
				PomiBody.face(frog, center);
				if (companion.stepCooldown % 8 == 0) {
					Vector toward = center.toVector().subtract(frog.getLocation().toVector());
					if (frog.isOnGround() && toward.lengthSquared() > 1.0E-4) {
						Vector push = toward.normalize().multiply(0.15);
						frog.setVelocity(new Vector(push.getX(), 0.25, push.getZ()));
					}
					frog.getWorld().playSound(center, block.getBlockData().getSoundGroup().getHitSound(), 0.8f, 1.0f);
				}
				if (companion.stepCooldown-- > 0) {
					return null;
				}
				String refusal = Protection.whyNotBreak(player, companion, block, Protection.BREAK_RADIUS);
				if (refusal == null) {
					refusal = Protection.breakBlock(player, companion, block, false, null);
				}
				if (refusal != null) {
					note(companion, refusal);
				}
				return next(player, companion);
			}
			case "saltar" -> {
				companion.routineOwnsBody = true;
				if (companion.stepCooldown-- > 0) {
					return null;
				}
				if (companion.stepCount >= step.times()) {
					PomiBody.setPose(frog, Pose.STANDING);
					return next(player, companion);
				}
				if (frog.isOnGround() || frog.isInWater()) {
					Vector velocity = frog.getVelocity();
					frog.setVelocity(new Vector(velocity.getX() * 0.2, 0.45, velocity.getZ() * 0.2));
					PomiBody.setPose(frog, Pose.LONG_JUMPING);
					companion.stepCount++;
					companion.stepCooldown = JUMP_TICKS;
				}
				return null;
			}
			case "girar" -> {
				companion.routineOwnsBody = true;
				float yaw = frog.getYaw() + 360.0f / SPIN_TICKS;
				frog.setRotation(yaw, 0.0f);
				frog.setBodyYaw(yaw);
				return companion.stepTicks >= SPIN_TICKS * step.times() ? next(player, companion) : null;
			}
			case "particulas" -> {
				frog.getWorld().spawnParticle(PARTICLES.get(step.target()), frog.getLocation().add(0, frog.getHeight() + 0.3, 0),
						7 * step.times(), 0.4, 0.3, 0.4, 0.02);
				return next(player, companion);
			}
			case "sonido" -> {
				Sound sound = Registry.SOUNDS.get(NamespacedKey.fromString(step.sound()));
				if (sound != null) {
					frog.getWorld().playSound(frog.getLocation(), sound, 1.0f, 1.3f);
				}
				return next(player, companion);
			}
			default -> {
				// esperar
				return companion.stepTicks >= step.ticks() ? next(player, companion) : null;
			}
		}
	}

	private static void hit(Player player, Companion companion, LivingEntity target) {
		PomiBody.action(companion, target);
		companion.entity.getWorld().playSound(companion.entity.getLocation(), Sound.ENTITY_FROG_TONGUE, 1.0f, 1.3f);
		if (target.equals(player)) {
			// Playful: a tap, a few per routine, and never when it would leave the player low.
			if (companion.routinePlayerHits >= MAX_PLAYER_HITS) {
				note(companion, "Ya le había dado suficientes golpecitos al jugador; los demás fueron de mentiritas.");
				return;
			}
			if (player.getHealth() - PLAYER_HIT_DAMAGE <= PLAYER_SAFE_HEALTH) {
				note(companion, "Le hizo el golpe de mentiritas porque tiene poca vida: no quiere lastimarlo de verdad.");
				return;
			}
			companion.routinePlayerHits++;
			player.damage(PLAYER_HIT_DAMAGE, companion.entity);
		} else {
			var attribute = target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
			double max = attribute == null ? target.getHealth() : attribute.getValue();
			target.setNoDamageTicks(0);
			target.damage(Math.max(1.0, max * 0.2) * CompanionStats.POMI.strengthFactor(), companion.entity);
		}
	}

	/** The player, or the nearest mob of the step's type around them. Only hostile mobs get hit. */
	private static LivingEntity target(Player player, Companion companion, Step step) {
		if (step.target().equals("jugador")) {
			return player;
		}
		if (companion.stepTarget != null && companion.stepTarget.isValid() && !companion.stepTarget.isDead()
				&& companion.stepTarget.getWorld() == player.getWorld()) {
			return companion.stepTarget;
		}
		EntityType type = Registry.ENTITY_TYPE.get(NamespacedKey.fromString(step.target()));
		LivingEntity best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Entity candidate : player.getNearbyEntities(SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS)) {
			if (!(candidate instanceof LivingEntity living) || candidate.getType() != type || candidate.equals(companion.entity)
					|| living.isDead()) {
				continue;
			}
			boolean allowed = step.action().equals("mirar") || candidate instanceof Enemy;
			double distance = candidate.getLocation().distanceSquared(player.getLocation());
			if (allowed && distance < bestDistance) {
				best = living;
				bestDistance = distance;
			}
		}
		if (best == null && step.action().equals("golpear") && type != null) {
			note(companion, "En una rutina solo golpea mobs hostiles o, si se lo pide, al jugador.");
		}
		companion.stepTarget = best;
		return best;
	}

	private static Location next(Player player, Companion companion) {
		companion.routineStep++;
		if (companion.routineStep >= companion.routine.size()) {
			finish(player, companion);
			return null;
		}
		beginStep(player, companion);
		return null;
	}

	private static void beginStep(Player player, Companion companion) {
		companion.stepTicks = 0;
		companion.stepCount = 0;
		companion.stepCooldown = 0;
		companion.stepTarget = null;
		Step step = companion.routine.get(companion.routineStep);
		if (step.action().equals("ir")) {
			companion.stepGoal = spot(player, companion, step);
		}
	}

	/** Where an "ir" step goes: next to the player, or some blocks from Pomi relative to the player's view. */
	private static Location spot(Player player, Companion companion, Step step) {
		if (step.direction().equals("jugador")) {
			return player.getLocation();
		}
		double degrees = switch (step.direction()) {
			case "derecha" -> 90;
			case "atras" -> 180;
			case "izquierda" -> 270;
			default -> 0;
		};
		return MathUtil.relative(player, companion.entity.getLocation(), degrees, step.blocks());
	}

	private static void finish(Player player, Companion companion) {
		companion.mode = Companion.Mode.FOLLOW;
		companion.routine = List.of();
		companion.routineOwnsBody = false;
		PomiBody.setPose(companion.entity, Pose.STANDING);
		if (!companion.routineNotes.isEmpty()) {
			PomiTasks.notify(player, companion, "Pomi terminó la rutina, pero: " + String.join(" ", companion.routineNotes),
					"Díselo al jugador en una frase.", null);
		}
	}

	private static void note(Companion companion, String note) {
		if (!companion.routineNotes.contains(note)) {
			companion.routineNotes.add(note);
		}
	}

	private static String text(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? Chat.lower(o.get(key).getAsString()) : "";
	}

	private static int integer(JsonObject o, String key, int fallback) {
		try {
			return o.has(key) ? o.get(key).getAsInt() : fallback;
		} catch (RuntimeException e) {
			return fallback;
		}
	}
}
