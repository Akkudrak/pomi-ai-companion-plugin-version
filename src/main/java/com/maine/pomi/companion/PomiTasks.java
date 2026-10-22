package com.maine.pomi.companion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Frog;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.PigZombie;
import org.bukkit.entity.Piglin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import io.papermc.paper.advancement.AdvancementDisplay;

import com.maine.pomi.CompanionManager;
import com.maine.pomi.PomiPlugin;
import com.maine.pomi.util.Chat;
import com.maine.pomi.util.MathUtil;

/**
 * Things Pomi does besides hopping along: weakening a mob, guarding, collecting dropped items into
 * her two slots and tossing them back, lighting up caves, and cheering the player's kills and
 * advancements. Ported from the mod's CompanionTasks; results go to {@link #notify} so Pomi comments.
 */
public final class PomiTasks {
	private static final double MAX_TASK_DISTANCE = 72.0;
	private static final int TASK_TIMEOUT_TICKS = 600;
	private static final int ATTACK_INTERVAL_TICKS = 16;
	private static final double REACH = 1.6;
	private static final double GUARD_RADIUS = 10.0;
	private static final int GUARD_KILLS = 5;
	private static final int GUARD_CALM_TICKS = 300;
	private static final int GUARD_TARGET_TICKS = 160;
	private static final int HORDE_SIZE = 4;
	private static final int HORDE_COMMENT_TICKS = 600;
	private static final double CATCH_REACH = 1.2;
	private static final int CATCH_COMMENT_TICKS = 100;
	private static final int FULL_WARNING_TICKS = 200;
	private static final double MAX_THROW_DISTANCE = 10.0;
	private static final double PICKUP_SEARCH_RADIUS = 16.0;
	private static final int LIGHT_RADIUS = 12;
	private static final int DARK = 7;
	private static final int TORCH_SPACING = 6;
	private static final int KILL_COMMENT_COOLDOWN_TICKS = 120;
	private static final int ADVANCEMENT_COMMENT_COOLDOWN_TICKS = 200;
	/** How far the player can point at a mob or block for Pomi. */
	public static final int AIM_DISTANCE = 32;
	private static final Set<EntityType> EDIBLE = Set.of(EntityType.COW, EntityType.MOOSHROOM, EntityType.PIG,
			EntityType.CHICKEN, EntityType.SHEEP, EntityType.RABBIT, EntityType.COD, EntityType.SALMON, EntityType.HOGLIN);

	private PomiTasks() {
	}

	private static long now() {
		return Bukkit.getCurrentTick();
	}

	// ---- Attack -------------------------------------------------------------------------------

	/** Mobs Pomi may beat up: no villagers, pets, bosses, players or companions. */
	public static boolean canAttack(Entity entity) {
		if (!(entity instanceof Mob mob) || !mob.isValid() || mob.isDead()) {
			return false;
		}
		if (mob instanceof AbstractVillager || (mob instanceof Tameable tame && tame.isTamed()) || CompanionManager.isCompanion(mob)) {
			return false;
		}
		return !MobDifficulty.isBoss(mob.getType());
	}

	/**
	 * The mob the player means: one of type {@code mobId} if given, else the one they point at, else
	 * the nearest hostile (those after the player first).
	 */
	public static LivingEntity findTarget(Player player, Companion companion, String mobId) {
		NamespacedKey key = mobId == null || mobId.isBlank() ? null
				: NamespacedKey.fromString(Chat.lower(mobId).contains(":") ? Chat.lower(mobId) : "minecraft:" + Chat.lower(mobId));
		EntityType type = key == null ? null : Registry.ENTITY_TYPE.get(key);
		if (type != null) {
			LivingEntity best = null;
			for (Entity entity : player.getNearbyEntities(24, 12, 24)) {
				if (entity.getType() == type && canAttack(entity)
						&& (best == null || entity.getLocation().distanceSquared(player.getLocation()) < best.getLocation().distanceSquared(player.getLocation()))) {
					best = (LivingEntity) entity;
				}
			}
			return best;
		}
		Entity aimed = player.getTargetEntity(AIM_DISTANCE);
		if (aimed instanceof LivingEntity living && canAttack(aimed)) {
			return living;
		}
		List<LivingEntity> threats = threats(player, companion, 16.0, false);
		return threats.isEmpty() ? null : threats.getFirst();
	}

	/**
	 * @param kill finish it off (hostile mobs only); otherwise leave it at 1 health for the player
	 * @return what happened, for the LLM
	 */
	public static String startAttack(Player player, Companion companion, LivingEntity target, boolean kill) {
		if (target == null) {
			return "No encontraste a qué mob atacar: el jugador no apunta a ninguno y no hay hostiles cerca.";
		}
		if (!canAttack(target) || target.getLocation().distance(companion.entity.getLocation()) > MAX_TASK_DISTANCE) {
			return "No puedes atacar a ese mob: no se puede atacar o está muy lejos.";
		}
		String tooTough = MobDifficulty.whyTooTough(target);
		if (kill && tooTough != null) {
			return "Te pidieron acabar con el " + name(target) + " pero " + tooTough + ", así que no lo atacas. Di algo "
					+ "gracioso como que ese mob te tiene miedo, así que lo dejarás vivir, pero que él le dé duro.";
		}
		companion.attackKill = kill && target instanceof Enemy;
		companion.attackStopHealth = companion.attackKill ? 0.0f : 1.0f;
		if (!companion.attackKill && target.getHealth() <= companion.attackStopHealth) {
			return "No le pegaste al " + name(target) + " porque ya estaba muy débil (" + health(target) + "). Que lo remate él.";
		}
		CompanionPlan.cancel(companion);
		companion.pickupTarget = null;
		companion.attackTarget = target;
		companion.mode = Companion.Mode.ATTACK;
		companion.taskTicks = 0;
		companion.attackCooldown = 0;
		return "Vas por el " + name(target) + (companion.attackKill ? " para acabar con él." : " para dejarlo con 1 de vida.")
				+ " Al terminar llegará un [Evento]; por ahora solo di que vas.";
	}

	/** @return where to hop this tick, or null to stand still (hitting, or the attack ended) */
	public static Location tickAttack(Player player, Companion companion) {
		LivingEntity target = companion.attackTarget;
		Frog frog = companion.entity;
		if (target == null || !target.isValid() || target.isDead() || target.getWorld() != frog.getWorld()) {
			if (companion.attackKill && target != null && target.isDead()) {
				finishAttack(player, companion, "Pomi acabó con el " + name(target) + " sin ayuda.",
						"Celébralo con mucha emoción (puedes bromear sobre el mob, no sobre el jugador).");
			} else {
				finishAttack(player, companion, "El " + (target == null ? "mob" : name(target)) + " murió o desapareció antes "
						+ "de que Pomi terminara de pegarle.", null);
			}
			return null;
		}
		if (target.getLocation().distance(frog.getLocation()) > MAX_TASK_DISTANCE) {
			finishAttack(player, companion, "Se le escapó el " + name(target) + " a Pomi; le dejó " + health(target) + ".", null);
			return null;
		}
		if (++companion.taskTicks > TASK_TIMEOUT_TICKS) {
			finishAttack(player, companion, "Pomi se cansó de perseguir al " + name(target) + "; le dejó " + health(target) + ".",
					"Dile al jugador que lo termine él.");
			return null;
		}
		if (companion.attackCooldown > 0) {
			companion.attackCooldown--;
		}
		boolean inReach = MathUtil.horizontal(frog.getLocation(), target.getLocation()) <= REACH + target.getWidth() / 2
				&& Math.abs(frog.getY() - target.getY()) < 2.0;
		if (!inReach) {
			return target.getLocation();
		}
		if (companion.attackCooldown == 0) {
			hit(player, companion, target);
		}
		return null;
	}

	private static void hit(Player player, Companion companion, LivingEntity target) {
		double damage = Math.max(1.0, maxHealth(target) * 0.2) * CompanionStats.POMI.strengthFactor();
		if (!companion.attackKill) {
			damage = Math.min(damage, target.getHealth() - companion.attackStopHealth);
		}
		if (damage > 0.0) {
			target.setNoDamageTicks(0);
			target.damage(damage, companion.entity);
		}
		PomiBody.action(companion, target);
		companion.entity.getWorld().playSound(companion.entity.getLocation(), Sound.ENTITY_FROG_TONGUE, 1.0f, 1.3f);
		companion.attackCooldown = ATTACK_INTERVAL_TICKS;

		if (companion.mode == Companion.Mode.ATTACK && !companion.attackKill && target.getHealth() <= companion.attackStopHealth + 0.01) {
			finishAttack(player, companion, "Pomi dejó al " + name(target) + " con " + health(target) + ", a un solo golpe.",
					"Dile al jugador que lo remate, que tú ya te cansaste (algo como 'te lo dejé a un tiro, termínalo ya').");
		}
	}

	private static void finishAttack(Player player, Companion companion, String fact, String hint) {
		companion.lastWeakened = companion.attackTarget;
		companion.attackTarget = null;
		companion.mode = Companion.Mode.FOLLOW;
		notify(player, companion, fact, hint, null);
	}

	// ---- Guard ("cúbreme") --------------------------------------------------------------------

	public static String startGuard(Player player, Companion companion) {
		CompanionPlan.cancel(companion);
		companion.mode = Companion.Mode.GUARD;
		companion.attackTarget = null;
		companion.guardKills = 0;
		companion.guardQuietTicks = 0;
		companion.taskTicks = 0;
		companion.attackCooldown = 0;
		return "Estás en guardia: peleas contra los hostiles comunes a menos de " + (int) GUARD_RADIUS + " bloques del jugador "
				+ "hasta derrotar " + GUARD_KILLS + " o hasta que se calme la zona. Contesta con una frase de batalla.";
	}

	/** @return where to go this tick, or null to stay with the player */
	public static Location tickGuard(Player player, Companion companion) {
		Frog frog = companion.entity;
		LivingEntity target = companion.attackTarget;
		boolean lost = target == null || !target.isValid() || target.isDead() || target.getWorld() != frog.getWorld()
				|| target.getLocation().distance(player.getLocation()) > GUARD_RADIUS + 6;
		if (lost) {
			if (target != null && target.isDead()) {
				companion.guardKills++;
				if (companion.guardKills >= GUARD_KILLS) {
					companion.mode = Companion.Mode.FOLLOW;
					companion.attackTarget = null;
					notify(player, companion, "Pomi terminó la guardia: acabó con " + GUARD_KILLS + " enemigos.",
							"Presúmelo en una frase; si quiere más, que te lo vuelva a pedir.", "fanfarria");
					return null;
				}
			}
			List<LivingEntity> threats = threats(player, companion, GUARD_RADIUS, true);
			target = threats.isEmpty() ? null : threats.getFirst();
			companion.attackTarget = target;
			companion.taskTicks = 0;
			if (threats.size() >= HORDE_SIZE && now() - companion.lastHordeComment > HORDE_COMMENT_TICKS) {
				companion.lastHordeComment = now();
				notify(player, companion, "¡Una horda de " + threats.size() + " mobs viene encima!",
						"Grita algo valiente (o asustado pero decidido) mientras peleas.", null);
			}
		}
		if (target == null) {
			if (++companion.guardQuietTicks > GUARD_CALM_TICKS) {
				companion.mode = Companion.Mode.FOLLOW;
				notify(player, companion, "Pomi terminó la guardia: ya no hay mobs hostiles cerca"
						+ (companion.guardKills > 0 ? " y acabó con " + companion.guardKills + " en total" : "") + ".",
						"Dilo con orgullo en una frase.", null);
			}
			return null;
		}
		companion.guardQuietTicks = 0;
		if (++companion.taskTicks > GUARD_TARGET_TICKS) {
			companion.attackTarget = null;
			return null;
		}
		if (MathUtil.horizontal(frog.getLocation(), target.getLocation()) > REACH || Math.abs(frog.getY() - target.getY()) > 2.0) {
			return target.getLocation();
		}
		if (companion.attackCooldown > 0) {
			companion.attackCooldown--;
			return null;
		}
		companion.attackKill = true;
		hit(player, companion, target);
		return null;
	}

	/**
	 * Hostile mobs near the player worth fighting: those after the player (or Pomi) first, then the
	 * closest. Neutral mobs minding their business and the tough ones are left alone.
	 */
	private static List<LivingEntity> threats(Player player, Companion companion, double radius, boolean onlyEasy) {
		List<LivingEntity> found = new ArrayList<>();
		for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
			if (!(entity instanceof Mob mob) || !(mob instanceof Enemy) || !canAttack(mob)
					|| mob.getLocation().distance(player.getLocation()) > radius) {
				continue;
			}
			boolean angry = mob.getTarget() != null && (mob.getTarget().equals(player) || mob.getTarget().equals(companion.entity));
			if ((mob instanceof Enderman || mob instanceof PigZombie || mob instanceof Piglin) && !angry) {
				continue;
			}
			if (onlyEasy && MobDifficulty.whyTooTough(mob) != null) {
				continue;
			}
			found.add(mob);
		}
		found.sort(Comparator.comparingInt((LivingEntity mob) -> ((Mob) mob).getTarget() != null
						&& ((Mob) mob).getTarget().equals(player) ? 0 : 1)
				.thenComparingDouble(mob -> mob.getLocation().distanceSquared(player.getLocation())));
		return found;
	}

	// ---- Pickup -------------------------------------------------------------------------------

	/** Collects dropped items near the player ({@code itemId} "" = anything). Returns the result for the LLM. */
	public static String startPickup(Player player, Companion companion, String itemId) {
		Material wanted = itemId == null || itemId.isBlank() ? null : Material.matchMaterial(itemId);
		if (itemId != null && !itemId.isBlank() && wanted == null) {
			return "No conozco el item \"" + itemId + "\".";
		}
		companion.pickupItemId = wanted == null ? "" : wanted.getKey().toString();
		companion.pickupTarget = nextItem(companion);
		if (companion.pickupTarget == null) {
			return "No hay items tirados cerca que puedas recoger" + (isFull(companion) ? " (además tus dos espacios están llenos)" : "")
					+ ". Si el jugador quiere darte algo, que te lo lance (tecla Q).";
		}
		CompanionPlan.cancel(companion);
		companion.attackTarget = null;
		companion.pickedUp.clear();
		companion.mode = Companion.Mode.PICKUP;
		companion.taskTicks = 0;
		return "Vas a recoger lo que hay tirado cerca. Al terminar llegará un [Evento]; por ahora solo di que vas.";
	}

	/** @return where to hop this tick, or null to stand still (collecting, or the pickup ended) */
	public static Location tickPickup(Player player, Companion companion) {
		Frog frog = companion.entity;
		if (++companion.taskTicks > TASK_TIMEOUT_TICKS) {
			finishPickup(player, companion, "Se cansó de buscar items.");
			return null;
		}
		Item item = companion.pickupTarget;
		if (item == null || !item.isValid() || item.getWorld() != frog.getWorld() || !canAccept(companion, item.getItemStack())) {
			item = nextItem(companion);
			companion.pickupTarget = item;
			if (item == null) {
				finishPickup(player, companion, null);
				return null;
			}
		}
		boolean inReach = MathUtil.horizontal(frog.getLocation(), item.getLocation()) <= 1.2 && Math.abs(frog.getY() - item.getY()) < 1.5;
		if (!inReach) {
			return item.getLocation();
		}
		ItemStack stack = item.getItemStack().clone();
		int before = stack.getAmount();
		ItemStack rest = insert(companion, stack);
		int taken = before - amount(rest);
		if (taken > 0) {
			companion.pickedUp.merge(Chat.id(stack.getType()), taken, Integer::sum);
			PomiBody.action(companion, item);
			frog.getWorld().playSound(frog.getLocation(), Sound.ENTITY_FROG_EAT, 1.0f, 1.3f);
		}
		if (Companion.isEmpty(rest)) {
			item.remove();
		} else {
			item.setItemStack(rest);
		}
		companion.pickupTarget = null;
		if (isFull(companion)) {
			finishPickup(player, companion, "Ya tiene los dos espacios llenos.");
		}
		return null;
	}

	private static Item nextItem(Companion companion) {
		Frog frog = companion.entity;
		Item best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Entity entity : frog.getNearbyEntities(PICKUP_SEARCH_RADIUS, 4.0, PICKUP_SEARCH_RADIUS)) {
			if (!(entity instanceof Item item) || !item.isValid()) {
				continue;
			}
			String id = item.getItemStack().getType().getKey().toString();
			if ((companion.pickupItemId.isEmpty() || companion.pickupItemId.equals(id)) && canAccept(companion, item.getItemStack())) {
				double distance = item.getLocation().distanceSquared(frog.getLocation());
				if (distance < bestDistance) {
					bestDistance = distance;
					best = item;
				}
			}
		}
		return best;
	}

	private static void finishPickup(Player player, Companion companion, String reason) {
		companion.pickupTarget = null;
		companion.mode = Companion.Mode.FOLLOW;
		StringBuilder fact = new StringBuilder();
		if (companion.pickedUp.isEmpty()) {
			fact.append("Pomi no encontró nada que recoger.");
		} else {
			List<String> parts = new ArrayList<>();
			companion.pickedUp.forEach((name, count) -> parts.add(count + "x " + name));
			fact.append("Pomi recogió ").append(String.join(", ", parts)).append('.');
		}
		if (reason != null) {
			fact.append(' ').append(reason);
		}
		companion.pickedUp.clear();
		notify(player, companion, fact.toString(), null, null);
	}

	// ---- Drop, toss and catch -----------------------------------------------------------------

	/** Tosses the matching slots ("" = all) to the player's feet. Returns what happened. */
	public static String throwItems(Player player, Companion companion, String itemId) {
		Material wanted = itemId == null || itemId.isBlank() ? null : Material.matchMaterial(itemId);
		List<String> dropped = new ArrayList<>();
		for (int i = 0; i < companion.inventory.length; i++) {
			ItemStack stack = companion.inventory[i];
			if (Companion.isEmpty(stack) || (wanted != null && stack.getType() != wanted)) {
				continue;
			}
			toss(player, companion, stack);
			dropped.add(Chat.stack(stack));
			companion.inventory[i] = null;
		}
		if (dropped.isEmpty()) {
			return "Te pidieron soltar " + (wanted == null ? "algo" : Chat.id(wanted)) + " pero no lo traías.";
		}
		companion.entity.getWorld().playSound(companion.entity.getLocation(), Sound.ENTITY_FROG_TONGUE, 1.0f, 1.3f);
		return "Le lanzaste " + String.join(", ", dropped) + " al jugador.";
	}

	/**
	 * "/p toma": Pomi takes what the player holds in their main hand (a tool goes to her free tool
	 * slot). Returns her line.
	 */
	public static String takeFromHand(Player player, Companion companion) {
		ItemStack held = player.getInventory().getItemInMainHand();
		if (Companion.isEmpty(held)) {
			return PomiLines.TAKE_EMPTY.pick();
		}
		if (CompanionMining.isTool(held) && Companion.isEmpty(companion.tool)) {
			companion.tool = held.asOne();
			held.setAmount(held.getAmount() - 1);
		} else if (canAccept(companion, held)) {
			ItemStack rest = insert(companion, held.clone());
			player.getInventory().setItemInMainHand(rest);
		} else {
			return PomiLines.TAKE_FULL.pick();
		}
		PomiBody.action(companion, player);
		companion.entity.getWorld().playSound(companion.entity.getLocation(), Sound.ENTITY_FROG_EAT, 1.0f, 1.3f);
		return PomiLines.TAKE.pick();
	}

	/** Tosses {@code stack} in an arc that lands at the player's feet. */
	public static void toss(Player player, Companion companion, ItemStack stack) {
		Frog frog = companion.entity;
		Location from = frog.getLocation().add(0, 0.5, 0);
		Vector toward = player.getLocation().toVector().subtract(frog.getLocation().toVector());
		double distance = Math.min(Math.sqrt(toward.getX() * toward.getX() + toward.getZ() * toward.getZ()), MAX_THROW_DISTANCE);
		Vector flat = distance < 0.5 ? new Vector() : new Vector(toward.getX(), 0, toward.getZ()).normalize();
		double up = MathUtil.clamp(0.3 + Math.max(toward.getY(), 0) * 0.1 + distance * 0.015, 0.25, 0.9);
		// Count the ticks until it comes down to the player's height (items fall 0.04/tick and keep 98%
		// of their speed), then pick the horizontal speed that covers the distance in that time.
		double y = 0.5;
		double vy = up;
		int ticks = 0;
		while (ticks < 100) {
			vy -= 0.04;
			y += vy;
			vy *= 0.98;
			ticks++;
			if (vy < 0 && y <= toward.getY()) {
				break;
			}
		}
		double speed = distance * 0.02 / (1 - Math.pow(0.98, ticks));
		Vector velocity = new Vector(flat.getX() * speed, up, flat.getZ() * speed);
		frog.getWorld().dropItem(from, stack.clone(), item -> {
			item.setVelocity(velocity);
			item.setPickupDelay(10);
			item.setThrower(frog.getUniqueId());
		});
	}

	/** Hands back the tool, tossed to the player's feet. Returns the result for the LLM. */
	public static String returnTool(Player player, Companion companion) {
		if (Companion.isEmpty(companion.tool)) {
			return "No traes ninguna herramienta en tu espacio de herramienta: no hay nada que devolver. Díselo.";
		}
		ItemStack tool = companion.tool;
		companion.tool = null;
		if (companion.workBlock != null || companion.planActive) {
			PomiPlugin.instance().companions().stopAll(player, companion);
		}
		toss(player, companion, tool);
		companion.entity.getWorld().playSound(companion.entity.getLocation(), Sound.ENTITY_FROG_TONGUE, 1.0f, 1.0f);
		return "Le devolviste tu " + Chat.id(tool.getType()) + " al jugador: se la lanzaste a sus pies. Ahora "
				+ "ya no puedes romper piedra ni minerales. Responde con UNA frase corta de decepción, un poco dramática.";
	}

	/**
	 * Catches what the player throws at Pomi (Q near her): their own thrown items that land within
	 * reach go into her slots (a tool goes to her tool slot if it's free).
	 */
	public static void catchThrown(Player player, Companion companion) {
		Frog frog = companion.entity;
		List<String> caught = new ArrayList<>();
		boolean full = false;
		for (Entity entity : frog.getNearbyEntities(CATCH_REACH, CATCH_REACH, CATCH_REACH)) {
			if (!(entity instanceof Item item) || !item.isValid() || !player.getUniqueId().equals(item.getThrower())) {
				continue;
			}
			ItemStack stack = item.getItemStack();
			if (CompanionMining.isTool(stack) && Companion.isEmpty(companion.tool)) {
				companion.tool = stack.asOne();
				if (stack.getAmount() <= 1) {
					item.remove();
				} else {
					item.setItemStack(stack.asQuantity(stack.getAmount() - 1));
				}
				caught.add("1x " + Chat.id(stack.getType()) + " (se la puso de herramienta)");
				continue;
			}
			if (!canAccept(companion, stack)) {
				full = true;
				continue;
			}
			int before = stack.getAmount();
			ItemStack rest = insert(companion, stack.clone());
			int taken = before - amount(rest);
			if (Companion.isEmpty(rest)) {
				item.remove();
			} else {
				item.setItemStack(rest);
				full = true;
			}
			caught.add(taken + "x " + Chat.id(stack.getType()));
		}
		long now = now();
		if (!caught.isEmpty()) {
			PomiBody.action(companion, player);
			frog.getWorld().playSound(frog.getLocation(), Sound.ENTITY_FROG_EAT, 1.0f, 1.3f);
			boolean comment = now - companion.lastCatchComment > CATCH_COMMENT_TICKS;
			if (comment) {
				companion.lastCatchComment = now;
				notify(player, companion, "Pomi atrapó al vuelo " + String.join(", ", caught) + ". Trae: " + describeInventory(companion) + ".",
						"Reacciona en una frase corta.", null);
			}
		} else if (full && now - companion.lastFullWarning > FULL_WARNING_TICKS) {
			companion.lastFullWarning = now;
			notify(player, companion, "A Pomi ya no le cabe nada: sus dos espacios están llenos (" + describeInventory(companion) + ").",
					"Dile que primero te pida soltar o lanzarle algo.", null);
		}
	}

	/** Gives the carried items back (leaving, or sent away), so nothing is lost. */
	public static void returnItems(Player player, Companion companion) {
		List<ItemStack> back = new ArrayList<>();
		if (!Companion.isEmpty(companion.tool)) {
			back.add(companion.tool);
			companion.tool = null;
		}
		for (int i = 0; i < companion.inventory.length; i++) {
			if (!Companion.isEmpty(companion.inventory[i])) {
				back.add(companion.inventory[i]);
				companion.inventory[i] = null;
			}
		}
		for (ItemStack stack : back) {
			player.getInventory().addItem(stack).values()
					.forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
		}
	}

	// ---- Inventory helpers --------------------------------------------------------------------

	public static int amount(ItemStack stack) {
		return Companion.isEmpty(stack) ? 0 : stack.getAmount();
	}

	public static boolean canAccept(Companion companion, ItemStack stack) {
		if (Companion.isEmpty(stack)) {
			return false;
		}
		for (ItemStack slot : companion.inventory) {
			if (Companion.isEmpty(slot) || (slot.isSimilar(stack) && slot.getAmount() < slot.getMaxStackSize())) {
				return true;
			}
		}
		return false;
	}

	public static boolean isFull(Companion companion) {
		for (ItemStack slot : companion.inventory) {
			if (Companion.isEmpty(slot) || slot.getAmount() < slot.getMaxStackSize()) {
				return false;
			}
		}
		return true;
	}

	/** Puts as much of {@code stack} as fits into the slots (stacking first) and returns what's left (null if all fit). */
	public static ItemStack insert(Companion companion, ItemStack stack) {
		return insert(companion.inventory, stack);
	}

	public static ItemStack insert(ItemStack[] slots, ItemStack stack) {
		if (Companion.isEmpty(stack)) {
			return null;
		}
		int left = stack.getAmount();
		for (ItemStack slot : slots) {
			if (left > 0 && !Companion.isEmpty(slot) && slot.isSimilar(stack)) {
				int moved = Math.min(left, slot.getMaxStackSize() - slot.getAmount());
				slot.setAmount(slot.getAmount() + moved);
				left -= moved;
			}
		}
		for (int i = 0; i < slots.length && left > 0; i++) {
			if (Companion.isEmpty(slots[i])) {
				int moved = Math.min(left, stack.getMaxStackSize());
				slots[i] = stack.asQuantity(moved);
				left -= moved;
			}
		}
		return left <= 0 ? null : stack.asQuantity(left);
	}

	public static String describeInventory(Companion companion) {
		List<String> parts = new ArrayList<>();
		for (ItemStack stack : companion.inventory) {
			if (!Companion.isEmpty(stack)) {
				parts.add(Chat.stack(stack));
			}
		}
		String slots = parts.isEmpty() ? "nada" : String.join(", ", parts);
		return slots + (Companion.isEmpty(companion.tool) ? "" : "; herramienta: " + Chat.id(companion.tool.getType()));
	}

	private static int slotWith(Companion companion, Material type) {
		for (int i = 0; i < companion.inventory.length; i++) {
			if (!Companion.isEmpty(companion.inventory[i]) && companion.inventory[i].getType() == type) {
				return i;
			}
		}
		return -1;
	}

	/** Creative: if neither slot can take {@code stack}, hand the first slot's items to the player. */
	private static void makeRoom(Player player, Companion companion, ItemStack stack) {
		if (!canAccept(companion, stack) && !Companion.isEmpty(companion.inventory[0])) {
			player.getInventory().addItem(companion.inventory[0]).values()
					.forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
			companion.inventory[0] = null;
		}
	}

	// ---- Invoke and place ---------------------------------------------------------------------

	/** Creative only: conjures items straight into Pomi's slots. */
	public static String invoke(Player player, Companion companion, String itemId, int count) {
		if (player.getGameMode() != GameMode.CREATIVE) {
			return "No se puede: invocar items solo funciona en modo creativo.";
		}
		Material type = Material.matchMaterial(itemId == null ? "" : itemId);
		if (type == null || !type.isItem() || type.isAir()) {
			return "No existe el item " + itemId + ".";
		}
		ItemStack stack = new ItemStack(type, MathUtil.clamp(count, 1, type.getMaxStackSize()));
		makeRoom(player, companion, stack);
		ItemStack rest = insert(companion, stack.clone());
		int got = stack.getAmount() - amount(rest);
		return got == 0 ? "No cupo: tus dos espacios están llenos."
				: "Invocaste " + got + "x " + Chat.id(type) + " en tu inventario. Traes: " + describeInventory(companion) + ".";
	}

	/**
	 * Puts one {@code itemId} against the face of the block the player is aiming at: as a block if it
	 * is one (a torch goes on the wall or the floor depending on the face), otherwise dropped there.
	 * Uses what Pomi carries; in creative it conjures the item if needed.
	 */
	public static String place(Player player, Companion companion, String itemId) {
		Material type = Material.matchMaterial(itemId == null ? "" : itemId);
		if (type == null || type.isAir()) {
			return "No existe el item " + itemId + ".";
		}
		RayTraceResult aim = player.rayTraceBlocks(AIM_DISTANCE);
		if (aim == null || aim.getHitBlock() == null || aim.getHitBlockFace() == null) {
			return "El jugador no está apuntando a ningún bloque. Pídele que apunte con la mira a donde lo quiere.";
		}
		Block against = aim.getHitBlock();
		BlockFace face = aim.getHitBlockFace();
		Block target = against.getRelative(face);
		boolean creative = player.getGameMode() == GameMode.CREATIVE;
		int slot = slotWith(companion, type);
		if (slot < 0 && !creative) {
			return "No traes " + Chat.id(type) + ". El jugador puede lanzártelo (tecla Q) para que lo uses.";
		}
		ItemStack one = new ItemStack(type);
		boolean done;
		if (type.isBlock()) {
			BlockData data = blockFor(type, face);
			done = data != null && Protection.place(player, target, against, data, one);
		} else {
			player.getWorld().dropItem(MathUtil.center(target), one, item -> item.setPickupDelay(10));
			done = true;
		}
		if (!done) {
			return "No se pudo poner " + Chat.id(type) + " ahí (está ocupado, no se sostiene o el terreno está protegido).";
		}
		if (!creative) {
			ItemStack carried = companion.inventory[slot];
			carried.setAmount(carried.getAmount() - 1);
			if (carried.getAmount() <= 0) {
				companion.inventory[slot] = null;
			}
		}
		PomiBody.face(companion.entity, MathUtil.center(target));
		companion.entity.getWorld().playSound(companion.entity.getLocation(), Sound.ENTITY_FROG_TONGUE, 1.0f, 1.3f);
		return "Pusiste " + Chat.id(type) + " donde apunta el jugador" + (creative ? "" : "; te quedan: " + describeInventory(companion)) + ".";
	}

	/** The block state to place for {@code type} on {@code face}: wall torches on walls, nothing under a ceiling. */
	private static BlockData blockFor(Material type, BlockFace face) {
		Material wall = switch (type) {
			case TORCH -> Material.WALL_TORCH;
			case SOUL_TORCH -> Material.SOUL_WALL_TORCH;
			case REDSTONE_TORCH -> Material.REDSTONE_WALL_TORCH;
			case COPPER_TORCH -> Material.COPPER_WALL_TORCH;
			default -> null;
		};
		if (wall == null) {
			return type.createBlockData();
		}
		if (face == BlockFace.UP) {
			return type.createBlockData();
		}
		if (face == BlockFace.DOWN) {
			return null;
		}
		BlockData data = wall.createBlockData();
		if (data instanceof Directional directional) {
			directional.setFacing(face);
		}
		return data;
	}

	// ---- Light --------------------------------------------------------------------------------

	/**
	 * Places torches on the darkest floor spots around the player, spread out so each one counts.
	 * Free in creative; in survival only Pomi's own torches, crafted from the wood and coal she carries
	 * if she's short. Returns the result for the LLM.
	 */
	public static String illuminate(Player player, Companion companion, int wanted) {
		Block center = player.getLocation().getBlock();
		BlockData torch = Material.TORCH.createBlockData();
		List<Block> spots = new ArrayList<>();
		for (int x = -LIGHT_RADIUS; x <= LIGHT_RADIUS; x++) {
			for (int z = -LIGHT_RADIUS; z <= LIGHT_RADIUS; z++) {
				for (int y = -6; y <= 6; y++) {
					Block block = center.getRelative(x, y, z);
					// Underground only: sky light means outdoors.
					if (block.getType().isAir() && block.getLightFromBlocks() <= DARK && block.getLightFromSky() <= DARK
							&& torch.isSupported(block)) {
						spots.add(block);
					}
				}
			}
		}
		if (spots.isEmpty()) {
			return "Te pidieron iluminar, pero alrededor ya no hay rincones oscuros donde puedan salir monstruos. Díselo.";
		}
		spots.sort(Comparator.comparingInt((Block block) -> block.getLightFromBlocks())
				.thenComparingDouble(block -> block.getLocation().distanceSquared(center.getLocation())));

		wanted = MathUtil.clamp(wanted <= 0 ? 12 : wanted, 1, 64);
		boolean creative = player.getGameMode() == GameMode.CREATIVE;
		int needed = Math.min(wanted, spots.size());
		int owned = creative ? Integer.MAX_VALUE : countTorches(companion);
		int available = owned;
		int made = 0;
		if (!creative && available < needed) {
			made = CompanionCraft.craftTorches(player, companion, needed - available);
			available += made;
			if (available == 0) {
				return "Te pidieron iluminar pero no tienes antorchas, ni madera y carbón para hacerlas (traes: "
						+ describeInventory(companion) + "). Dile que te dé antorchas, o madera y carbón, lanzándotelos con la tecla Q.";
			}
		}
		List<Block> placed = new ArrayList<>();
		for (Block spot : spots) {
			if (placed.size() >= Math.min(needed, available)) {
				break;
			}
			// Light only updates next tick, so spacing stands in for "already lit".
			if (placed.stream().anyMatch(p -> p.getLocation().distanceSquared(spot.getLocation()) < TORCH_SPACING * TORCH_SPACING)) {
				continue;
			}
			if (Protection.place(player, spot, spot.getRelative(BlockFace.DOWN), torch, new ItemStack(Material.TORCH))) {
				placed.add(spot);
			}
		}
		if (!creative) {
			// Her own torches go first; crafted ones she didn't need stay with her (or go to the player).
			takeTorches(companion, Math.min(placed.size(), owned));
			int spare = made - Math.max(0, placed.size() - owned);
			if (spare > 0) {
				ItemStack rest = insert(companion, new ItemStack(Material.TORCH, spare));
				if (!Companion.isEmpty(rest)) {
					toss(player, companion, rest);
				}
			}
		}
		companion.entity.getWorld().playSound(companion.entity.getLocation(), Sound.ENTITY_FROG_TONGUE, 1.0f, 1.3f);
		if (placed.isEmpty()) {
			return "No pudiste poner ninguna antorcha (el terreno está protegido o no había dónde). Díselo.";
		}
		String paid = creative ? "gratis porque está en creativo" : "con antorchas de tu inventario";
		String crafted = made > 0 ? " Antes crafteaste " + made + " antorchas con la madera y el carbón que traías." : "";
		String short_ = placed.size() < needed && !creative ? " Se acabaron las antorchas antes de terminar." : "";
		return "Pusiste " + placed.size() + " antorchas en los rincones más oscuros alrededor del jugador, " + paid + "." + crafted
				+ short_ + " Celebra que ya no saldrán monstruos ahí.";
	}

	private static int countTorches(Companion companion) {
		int count = 0;
		for (ItemStack stack : companion.inventory) {
			if (!Companion.isEmpty(stack) && stack.getType() == Material.TORCH) {
				count += stack.getAmount();
			}
		}
		return count;
	}

	private static void takeTorches(Companion companion, int amount) {
		for (int i = 0; i < companion.inventory.length && amount > 0; i++) {
			ItemStack stack = companion.inventory[i];
			if (!Companion.isEmpty(stack) && stack.getType() == Material.TORCH) {
				int taken = Math.min(amount, stack.getAmount());
				stack.setAmount(stack.getAmount() - taken);
				amount -= taken;
				if (stack.getAmount() <= 0) {
					companion.inventory[i] = null;
				}
			}
		}
	}

	// ---- Colour -------------------------------------------------------------------------------

	/** Repaints Pomi ("naranja", "blanca" or "verde"); the new colour is saved. Returns the result for the LLM. */
	public static String recolor(Player player, Companion companion, String colour) {
		Frog.Variant variant = PomiChoice.COLOURS.get(Chat.lower(colour));
		if (variant == null) {
			return "Ese color no existe para una rana: solo hay naranja, blanca y verde. Dilo con humor.";
		}
		companion.choice = companion.choice.withVariant(variant);
		CompanionManager.saveChoice(player, companion.choice);
		companion.entity.setVariant(variant);
		companion.entity.getWorld().playSound(companion.entity.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.0f);
		return "Listo, ahora eres " + Chat.lower(colour) + ".";
	}

	// ---- Comments -----------------------------------------------------------------------------

	/**
	 * The player completed an advancement with a toast (recipe unlocks have no display). A burst of
	 * them gets one comment; challenges always get their own.
	 */
	public static void onAdvancement(Player player, Companion companion, Advancement advancement) {
		AdvancementDisplay display = advancement.getDisplay();
		if (display == null || !display.doesAnnounceToChat()) {
			return;
		}
		boolean challenge = display.frame() == AdvancementDisplay.Frame.CHALLENGE;
		companion.advancementsSinceComment++;
		if (!challenge && now() - companion.lastAdvancementComment < ADVANCEMENT_COMMENT_COOLDOWN_TICKS) {
			return;
		}
		int count = companion.advancementsSinceComment;
		companion.advancementsSinceComment = 0;
		companion.lastAdvancementComment = now();
		String description = Chat.plain(display.description());
		StringBuilder fact = new StringBuilder("¡Logro \"" + Chat.plain(display.title()) + "\"");
		if (!description.isBlank()) {
			fact.append(" (").append(description).append(")");
		}
		fact.append("!");
		if (count > 1) {
			fact.append(" Ya van ").append(count).append(" logros en un ratito.");
		}
		String hint;
		String sound;
		if (challenge) {
			hint = "Es un DESAFÍO, de los logros más difíciles del juego: celébralo en grande.";
			sound = "fanfarria";
		} else if (display.frame() == AdvancementDisplay.Frame.GOAL) {
			hint = "Es una meta importante: felicítalo con ganas.";
			sound = "fanfarria";
		} else {
			hint = "Coméntalo con algo gracioso y relacionado con ese logro.";
			sound = "ding";
		}
		notify(player, companion, fact.toString(), hint, sound);
	}

	/** The player killed something: Pomi congratulates them, in a way that depends on the mob. */
	public static void onPlayerKill(Player player, Companion companion, LivingEntity victim) {
		if (victim instanceof Player || CompanionManager.isCompanion(victim)) {
			return;
		}
		boolean helped = victim.equals(companion.lastWeakened);
		if (helped) {
			companion.lastWeakened = null;
		}
		if (!helped && now() - companion.lastKillComment < KILL_COMMENT_COOLDOWN_TICKS) {
			return;
		}
		companion.lastKillComment = now();
		// Canned lines, never Grok: kills are too frequent to spend tokens on.
		String line = PomiLines.kill(victim.getType().getKey().getKey()) + (helped ? " Y yo lo dejé blandito, ¿eh?" : "");
		String sound = victim instanceof Enemy ? "fanfarria" : EDIBLE.contains(victim.getType()) ? "comer" : "hmm";
		PomiPlugin.instance().brain().line(player, companion, line, sound);
	}

	// ---- Shared -------------------------------------------------------------------------------

	/**
	 * Something Pomi should react to. {@code fact} is readable as is (it's what the player sees when
	 * Grok is off); {@code hint} only guides Grok's comment. {@code sound} is one of Pomi's cues.
	 */
	public static void notify(Player player, Companion companion, String fact, String hint, String sound) {
		PomiPlugin.instance().brain().event(player, companion, fact, hint, sound);
	}

	public static String name(LivingEntity entity) {
		return entity.getType().getKey().getKey().replace('_', ' ');
	}

	private static double maxHealth(LivingEntity entity) {
		var attribute = entity.getAttribute(Attribute.MAX_HEALTH);
		return attribute == null ? entity.getHealth() : attribute.getValue();
	}

	private static String health(LivingEntity entity) {
		return String.format(Locale.ROOT, "%.0f de %.0f de vida", Math.ceil(entity.getHealth()), maxHealth(entity));
	}

	public static World world(Companion companion) {
		return companion.entity.getWorld();
	}
}
