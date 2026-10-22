package com.maine.pomi.companion;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Frog;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import com.maine.pomi.util.Chat;
import com.maine.pomi.util.MathUtil;

/**
 * Jobs that take a while, ported from the mod: gathering basic resources (by hand, or stone and ores
 * with the right pickaxe in her tool slot), removing the blocks the player points at, and tending a
 * field (harvest, replant, sow) from its center outwards.
 */
public final class CompanionWork {
	/** Most of one resource per request: all her two slots hold. */
	public static final int MAX_GATHER = 128;
	public static final int MAX_REMOVE = 10;
	private static final int GATHER_RADIUS = (int) Protection.BREAK_RADIUS;
	/** What she reaches from where she stands: up to 2 blocks above her feet and 2 below. */
	private static final int VERTICAL_REACH = 2;
	/** How far up and down from the player she looks for the resource (she walks there; the 30-block rule still holds). */
	private static final int SEARCH_HEIGHT = 8;
	/** Beyond the gathering radius she only looks, to tell the player which way to go. */
	private static final int SCOUT_RADIUS = 64;
	private static final double BLOCK_REACH = 1.7;
	private static final double ITEM_REACH = 1.2;
	private static final int TARGET_TIMEOUT_TICKS = 160;
	private static final int GATHER_TIMEOUT_TICKS = 2400;
	private static final int GATHER_TICKS_PER_ITEM = 100;
	private static final int GATHER_MAX_TICKS = 12000;
	private static final int FARM_TIMEOUT_TICKS = 3600;
	private static final int MAX_GRASS = 60;
	private static final int FIELD_HALF = 5;
	private static final int MAX_FIELD_SCAN = 2000;
	private static final int FARM_ACTION_TICKS = 6;

	/** Ripe crops and what replants them. */
	private static final Map<Material, Material> CROP_SEEDS = Map.of(Material.WHEAT, Material.WHEAT_SEEDS,
			Material.CARROTS, Material.CARROT, Material.POTATOES, Material.POTATO, Material.BEETROOTS, Material.BEETROOT_SEEDS);
	/** What she can sow on farmland, and the crop it grows. */
	private static final Map<Material, Material> SEED_CROPS = Map.of(Material.WHEAT_SEEDS, Material.WHEAT,
			Material.CARROT, Material.CARROTS, Material.POTATO, Material.POTATOES, Material.BEETROOT_SEEDS, Material.BEETROOTS,
			Material.MELON_SEEDS, Material.MELON_STEM, Material.PUMPKIN_SEEDS, Material.PUMPKIN_STEM,
			Material.TORCHFLOWER_SEEDS, Material.TORCHFLOWER_CROP);

	/**
	 * A resource: the blocks it comes from (null for eggs), which drops count, and for what needs a
	 * tool, a sample block her tool must be able to harvest (null = by hand).
	 */
	private record Resource(String name, Predicate<Material> blocks, Predicate<ItemStack> counts, Material needsTool, String tool) {
		Resource(String name, Predicate<Material> blocks, Predicate<ItemStack> counts) {
			this(name, blocks, counts, null, "");
		}
	}

	private static final Map<String, Resource> RESOURCES = new LinkedHashMap<>();
	private static final Map<String, String> ALIASES = Map.ofEntries(Map.entry("dirt", "tierra"), Map.entry("gravel", "grava"),
			Map.entry("clay", "arcilla"), Map.entry("sand", "arena"), Map.entry("wood", "madera"), Map.entry("log", "madera"),
			Map.entry("logs", "madera"), Map.entry("troncos", "madera"), Map.entry("tronco", "madera"), Map.entry("seeds", "semillas"),
			Map.entry("eggs", "huevos"), Map.entry("stone", "piedra"), Map.entry("coal", "carbon"), Map.entry("copper", "cobre"),
			Map.entry("iron", "hierro"), Map.entry("gold", "oro"), Map.entry("diamond", "diamante"), Map.entry("diamantes", "diamante"),
			Map.entry("emerald", "esmeralda"), Map.entry("lapislazuli", "lapis"));

	static {
		add(new Resource("tierra", m -> m == Material.DIRT || m == Material.GRASS_BLOCK || m == Material.COARSE_DIRT
				|| m == Material.ROOTED_DIRT || m == Material.PODZOL || m == Material.MYCELIUM, stack -> true));
		add(new Resource("grava", m -> m == Material.GRAVEL, stack -> true));
		add(new Resource("arcilla", m -> m == Material.CLAY, stack -> true));
		add(new Resource("arena", Tag.SAND::isTagged, stack -> true));
		add(new Resource("madera", Tag.LOGS::isTagged, stack -> true));
		add(new Resource("semillas", m -> m == Material.SHORT_GRASS || m == Material.TALL_GRASS || m == Material.FERN,
				stack -> stack.getType() == Material.WHEAT_SEEDS));
		add(new Resource("huevos", null, CompanionWork::isEgg));
		add(new Resource("piedra", Tag.BASE_STONE_OVERWORLD::isTagged, stack -> true, Material.STONE, "un pico de madera o mejor"));
		add(new Resource("carbon", Tag.COAL_ORES::isTagged, stack -> true, Material.COAL_ORE, "un pico de madera o mejor"));
		add(new Resource("cobre", Tag.COPPER_ORES::isTagged, stack -> true, Material.COPPER_ORE, "un pico de piedra o mejor"));
		add(new Resource("hierro", Tag.IRON_ORES::isTagged, stack -> true, Material.IRON_ORE, "un pico de piedra o mejor"));
		add(new Resource("lapis", Tag.LAPIS_ORES::isTagged, stack -> true, Material.LAPIS_ORE, "un pico de piedra o mejor"));
		add(new Resource("oro", Tag.GOLD_ORES::isTagged, stack -> true, Material.GOLD_ORE, "un pico de hierro o mejor"));
		add(new Resource("redstone", Tag.REDSTONE_ORES::isTagged, stack -> true, Material.REDSTONE_ORE, "un pico de hierro o mejor"));
		add(new Resource("diamante", Tag.DIAMOND_ORES::isTagged, stack -> true, Material.DIAMOND_ORE, "un pico de hierro o mejor"));
		add(new Resource("esmeralda", Tag.EMERALD_ORES::isTagged, stack -> true, Material.EMERALD_ORE, "un pico de hierro o mejor"));
	}

	private static void add(Resource resource) {
		RESOURCES.put(resource.name(), resource);
	}

	private CompanionWork() {
	}

	/** "Carbón", "IRON", "troncos" → the resource's name, or the text as given. */
	public static String normalize(String resource) {
		String plain = Normalizer.normalize(Chat.lower(resource), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
		return ALIASES.getOrDefault(plain, plain);
	}

	public static boolean knows(String resource) {
		return RESOURCES.containsKey(normalize(resource));
	}

	public static String resourceNames() {
		return String.join(", ", RESOURCES.keySet());
	}

	private static boolean isEgg(ItemStack stack) {
		Material type = stack.getType();
		return type == Material.EGG || type == Material.BLUE_EGG || type == Material.BROWN_EGG;
	}

	// ---- Gathering ----------------------------------------------------------------------------

	/** Starts gathering. Returns the result for the LLM. */
	public static String startGather(Player player, Companion companion, String resourceName, int amount) {
		String key = normalize(resourceName);
		Resource resource = RESOURCES.get(key);
		if (resource == null) {
			return "No sabes juntar \"" + resourceName + "\". Puedes juntar: " + resourceNames() + ".";
		}
		if (resource.needsTool() != null && !CompanionMining.canHarvest(resource.needsTool().createBlockData(), companion.tool)) {
			return "Para juntar " + key + " necesitas " + resource.tool() + (Companion.isEmpty(companion.tool) ? ""
					: " (tu " + Chat.id(companion.tool.getType()) + " no alcanza)") + ". El jugador te lo puede poner en tu espacio "
					+ "de herramienta (clic derecho en ti) o lanzártelo.";
		}
		companion.attackTarget = null;
		companion.pickupTarget = null;
		companion.mode = Companion.Mode.GATHER;
		companion.workResource = key;
		companion.workGoal = MathUtil.clamp(amount <= 0 ? MAX_GATHER : amount, 1, MAX_GATHER);
		companion.workTimeout = Math.min(GATHER_TIMEOUT_TICKS + companion.workGoal * GATHER_TICKS_PER_ITEM, GATHER_MAX_TICKS);
		companion.workDone = 0;
		companion.workBroken = 0;
		companion.workTicks = 0;
		companion.workSkipped.clear();
		companion.workNotes.clear();
		clearTarget(companion);
		return "Vas a juntar " + companion.workGoal + " de " + key + " a menos de " + GATHER_RADIUS + " bloques del jugador (solo "
				+ "lo que alcanzas: hasta 3 bloques de alto). Al terminar llegará un [Evento]; por ahora solo di que vas.";
	}

	/** @return where to head this tick, or null to stay put */
	public static Location tickGather(Player player, Companion companion) {
		companion.workOwnsBody = false;
		Resource resource = RESOURCES.get(companion.workResource);
		if (resource == null || ++companion.workTicks > companion.workTimeout) {
			companion.workNotes.add("Se le acabó el tiempo.");
			finishGather(player, companion);
			return null;
		}
		if (companion.workDone >= companion.workGoal) {
			finishGather(player, companion);
			return null;
		}
		if (resource.blocks() == null) {
			return tickEgg(player, companion);
		}
		if (resource.name().equals("semillas") && companion.workBroken >= MAX_GRASS) {
			companion.workNotes.add("El pasto casi no está soltando semillas.");
			finishGather(player, companion);
			return null;
		}
		if (companion.workBlock == null) {
			companion.workBlock = findBlock(player, companion, resource);
			companion.workTargetTicks = 0;
			if (companion.workBlock == null) {
				String farther = scout(player, resource);
				companion.workNotes.add((companion.workDone == 0 ? "No encontró " : "Ya no encontró más ") + resource.name()
						+ " a su alcance a menos de " + GATHER_RADIUS + " bloques del jugador."
						+ (farther == null ? "" : " Vio " + resource.name() + " " + farther + "; si el jugador se acerca, puede seguir.")
						+ (resource.needsTool() != null ? " Los minerales y la piedra tienen que estar a la vista (pegados al aire) y "
								+ "a 3 bloques de alto como máximo: no excava túneles." : ""));
				finishGather(player, companion);
				return null;
			}
		}
		Block block = companion.workBlock;
		if (!resource.blocks().test(block.getType())) {
			// Someone else broke it, or it fell (gravel, sand): look again.
			clearTarget(companion);
			return null;
		}
		Frog frog = companion.entity;
		Location center = MathUtil.center(block);
		boolean inReach = MathUtil.horizontal(frog.getLocation(), center) <= BLOCK_REACH
				&& Math.abs(block.getY() - frog.getLocation().getBlockY()) <= VERTICAL_REACH;
		if (!inReach) {
			if (++companion.workTargetTicks > TARGET_TIMEOUT_TICKS) {
				companion.workSkipped.add(block);
				clearTarget(companion);
				return null;
			}
			return center;
		}
		companion.workOwnsBody = true;
		if (companion.workStrikeTotal == 0) {
			companion.workStrikeTotal = CompanionMining.breakTicks(block, companion.tool);
			companion.workStrikeLeft = companion.workStrikeTotal;
		}
		strike(player, companion, block);
		if (companion.workStrikeLeft-- > 0) {
			return null;
		}
		String refusal = Protection.whyNotBreak(player, companion, block, Protection.BREAK_RADIUS);
		List<ItemStack> drops = new ArrayList<>();
		if (refusal == null) {
			refusal = Protection.breakBlock(player, companion, block, true, drops);
		}
		if (refusal != null) {
			note(companion, refusal);
			companion.workSkipped.add(block);
			clearTarget(companion);
			return null;
		}
		companion.workBroken++;
		boolean full = keep(companion, drops, resource, block);
		clearTarget(companion);
		if (full) {
			companion.workNotes.add("Se le llenaron los dos espacios; lo que no cupo quedó tirado ahí.");
			finishGather(player, companion);
		}
		return null;
	}

	/** Eggs the chickens laid: hop to the nearest one and pick it up. */
	private static Location tickEgg(Player player, Companion companion) {
		Frog frog = companion.entity;
		Item egg = companion.pickupTarget;
		if (egg == null || !egg.isValid() || !isEgg(egg.getItemStack())) {
			egg = null;
			double best = Double.MAX_VALUE;
			for (Entity entity : player.getNearbyEntities(Protection.BREAK_RADIUS, 8, Protection.BREAK_RADIUS)) {
				if (entity instanceof Item item && item.isValid() && isEgg(item.getItemStack())
						&& !companion.workSkipped.contains(item.getLocation().getBlock()) && PomiTasks.canAccept(companion, item.getItemStack())) {
					double distance = item.getLocation().distanceSquared(frog.getLocation());
					if (distance < best) {
						best = distance;
						egg = item;
					}
				}
			}
			companion.pickupTarget = egg;
			companion.workTargetTicks = 0;
			if (egg == null) {
				companion.workNotes.add(companion.workDone == 0
						? "No hay huevos tirados cerca: las gallinas los ponen cada 5 a 10 minutos."
						: "Ya no hay más huevos tirados cerca.");
				finishGather(player, companion);
				return null;
			}
		}
		if (egg.getLocation().distance(frog.getLocation()) > ITEM_REACH) {
			if (++companion.workTargetTicks > TARGET_TIMEOUT_TICKS) {
				companion.workSkipped.add(egg.getLocation().getBlock());
				companion.pickupTarget = null;
				return null;
			}
			return egg.getLocation();
		}
		ItemStack stack = egg.getItemStack().clone();
		int before = stack.getAmount();
		ItemStack rest = PomiTasks.insert(companion, stack);
		int taken = before - PomiTasks.amount(rest);
		companion.workDone += taken;
		if (Companion.isEmpty(rest)) {
			egg.remove();
		} else {
			egg.setItemStack(rest);
		}
		companion.pickupTarget = null;
		PomiBody.action(companion, egg);
		frog.getWorld().playSound(frog.getLocation(), Sound.ENTITY_FROG_EAT, 1.0f, 1.3f);
		if (taken == 0) {
			companion.workNotes.add("Se le llenaron los dos espacios.");
			finishGather(player, companion);
		}
		return null;
	}

	/**
	 * The nearest block of the resource near the player that Pomi can reach: exposed to the air, no
	 * more than {@link #VERTICAL_REACH} above or below her, from a tree when it's wood, and allowed.
	 */
	private static Block findBlock(Player player, Companion companion, Resource resource) {
		Block around = player.getLocation().getBlock();
		List<Block> candidates = new ArrayList<>();
		for (int x = -GATHER_RADIUS; x <= GATHER_RADIUS; x++) {
			for (int z = -GATHER_RADIUS; z <= GATHER_RADIUS; z++) {
				for (int y = -SEARCH_HEIGHT; y <= SEARCH_HEIGHT; y++) {
					Block block = around.getRelative(x, y, z);
					if (!resource.blocks().test(block.getType()) || companion.workSkipped.contains(block) || !exposed(block)
							|| !reachableFromGround(block, resource)) {
						continue;
					}
					candidates.add(block);
				}
			}
		}
		Location from = companion.entity.getLocation();
		candidates.sort(Comparator.comparingDouble(block -> MathUtil.center(block).distanceSquared(from)));
		for (Block block : candidates) {
			if (resource.name().equals("madera") && !isTree(block)) {
				companion.workSkipped.add(block);
				continue;
			}
			if (Protection.whyNotBreak(player, companion, block, Protection.BREAK_RADIUS) != null) {
				companion.workSkipped.add(block);
				continue;
			}
			return block;
		}
		return null;
	}

	/**
	 * Whether she can reach {@code block} standing on the ground under it: at most
	 * {@link #VERTICAL_REACH} above where she'd stand (the bottom of a trunk, not its top).
	 */
	private static boolean reachableFromGround(Block block, Resource resource) {
		Block below = block.getRelative(BlockFace.DOWN);
		for (int i = 0; i < SEARCH_HEIGHT && (below.isPassable() || resource.blocks().test(below.getType())); i++) {
			below = below.getRelative(BlockFace.DOWN);
		}
		return block.getY() - (below.getY() + 1) <= VERTICAL_REACH;
	}

	/**
	 * Looks past the gathering radius (only in loaded chunks) for the nearest block of the resource,
	 * so she can tell the player where to go: "a unos 45 bloques al norte", or null if there's none.
	 */
	private static String scout(Player player, Resource resource) {
		if (resource.blocks() == null) {
			return null;
		}
		Location at = player.getLocation();
		World world = player.getWorld();
		int cx = at.getBlockX();
		int cy = at.getBlockY();
		int cz = at.getBlockZ();
		Block best = null;
		double bestDistance = Double.MAX_VALUE;
		for (int x = cx - SCOUT_RADIUS; x <= cx + SCOUT_RADIUS; x++) {
			for (int z = cz - SCOUT_RADIUS; z <= cz + SCOUT_RADIUS; z++) {
				double flat = (double) (x - cx) * (x - cx) + (double) (z - cz) * (z - cz);
				if (flat <= (double) GATHER_RADIUS * GATHER_RADIUS || flat >= bestDistance || !world.isChunkLoaded(x >> 4, z >> 4)) {
					continue;
				}
				for (int y = cy - SEARCH_HEIGHT; y <= cy + SEARCH_HEIGHT; y++) {
					Block block = world.getBlockAt(x, y, z);
					if (resource.blocks().test(block.getType()) && exposed(block)
							&& (!resource.name().equals("madera") || isTree(block))) {
						best = block;
						bestDistance = flat;
						break;
					}
				}
			}
		}
		if (best == null) {
			return null;
		}
		return "a unos " + Math.round(Math.sqrt(bestDistance)) + " bloques al " + direction(best.getX() - cx, best.getZ() - cz);
	}

	private static String direction(int dx, int dz) {
		String[] names = {"sur", "suroeste", "oeste", "noroeste", "norte", "noreste", "este", "sureste"};
		double angle = Math.toDegrees(Math.atan2(-dx, dz));
		return names[(int) Math.floorMod(Math.round(angle / 45.0), 8)];
	}

	private static boolean exposed(Block block) {
		for (BlockFace face : new BlockFace[] {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
			Block next = block.getRelative(face);
			if (next.getType().isAir() || next.isReplaceable()) {
				return true;
			}
		}
		return false;
	}

	/** A log with leaves around and above it: a tree, not a house. */
	private static boolean isTree(Block log) {
		for (int x = -3; x <= 3; x++) {
			for (int z = -3; z <= 3; z++) {
				for (int y = 0; y <= 8; y++) {
					if (Tag.LEAVES.isTagged(log.getRelative(x, y, z).getType())) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** Puts the drops in her slots, counting the ones the job is after; what doesn't fit is left there. True if something didn't fit. */
	private static boolean keep(Companion companion, List<ItemStack> drops, Resource resource, Block block) {
		boolean full = false;
		for (ItemStack drop : drops) {
			int before = drop.getAmount();
			ItemStack rest = PomiTasks.insert(companion, drop.clone());
			if (resource.counts().test(drop)) {
				companion.workDone += before - PomiTasks.amount(rest);
			}
			if (!Companion.isEmpty(rest)) {
				block.getWorld().dropItemNaturally(MathUtil.center(block), rest);
				full = true;
			}
		}
		return full;
	}

	private static void finishGather(Player player, Companion companion) {
		companion.mode = Companion.Mode.FOLLOW;
		companion.workOwnsBody = false;
		clearTarget(companion);
		companion.pickupTarget = null;
		StringBuilder fact = new StringBuilder(companion.workDone == 0
				? "Pomi no consiguió nada de " + companion.workResource + "."
				: "Pomi terminó de juntar " + companion.workResource + ": consiguió " + Math.min(companion.workDone, companion.workGoal)
						+ " de " + companion.workGoal + ".");
		for (String note : companion.workNotes) {
			fact.append(' ').append(note);
		}
		if (CompanionPlan.waitingOnGather(companion)) {
			// Part of a bigger job: the player hears about it in the plan's summary.
			CompanionPlan.gatherDone(player, companion, fact.toString());
			return;
		}
		fact.append(" Trae: ").append(PomiTasks.describeInventory(companion)).append('.');
		PomiTasks.notify(player, companion, fact.toString(), "Díselo en una frase; si quiere lo que traes, que te pida lanzárselo.", "fanfarria");
	}

	// ---- Removing pointed-at blocks -----------------------------------------------------------

	/**
	 * Removes the block the player points at and, when asked for more, the same-kind blocks touching
	 * it, up to {@code amount}. Returns the result for the LLM.
	 */
	public static String startRemove(Player player, Companion companion, Block aimed, int amount) {
		if (aimed == null) {
			return "El jugador no está señalando ningún bloque. Pídele que apunte con la mira al que quiere quitar.";
		}
		String refusal = Protection.whyNotBreak(player, companion, aimed, Protection.BREAK_RADIUS);
		if (refusal != null) {
			return refusal;
		}
		Material kind = aimed.getType();
		int wanted = MathUtil.clamp(amount, 1, MAX_REMOVE);
		List<Block> queue = new ArrayList<>();
		Set<Block> seen = new HashSet<>();
		Deque<Block> frontier = new ArrayDeque<>();
		frontier.add(aimed);
		seen.add(aimed);
		while (!frontier.isEmpty() && queue.size() < wanted) {
			Block block = frontier.poll();
			if (!block.equals(aimed) && Protection.whyNotBreak(player, companion, block, Protection.BREAK_RADIUS) != null) {
				continue;
			}
			queue.add(block);
			for (BlockFace face : new BlockFace[] {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
				Block next = block.getRelative(face);
				if (seen.add(next) && next.getType() == kind) {
					frontier.add(next);
				}
			}
		}
		companion.attackTarget = null;
		companion.pickupTarget = null;
		companion.mode = Companion.Mode.REMOVE;
		companion.workQueue = queue;
		companion.workGoal = queue.size();
		companion.workBroken = 0;
		companion.workTicks = 0;
		companion.workNotes.clear();
		clearTarget(companion);
		String name = Chat.id(kind);
		return "Vas a quitar " + (queue.size() == 1 ? "el bloque de " + name + " que señala el jugador"
				: queue.size() + " bloques de " + name + " empezando por el que señala") + (queue.size() < wanted
				? " (no hay más de ese tipo pegados que puedas quitar)" : "")
				+ ". Lo que suelten cae al suelo para que él lo recoja. Al terminar llegará un [Evento]; por ahora solo di que vas.";
	}

	/** @return where to head this tick, or null to stay put */
	public static Location tickRemove(Player player, Companion companion) {
		companion.workOwnsBody = false;
		if (++companion.workTicks > GATHER_TIMEOUT_TICKS) {
			companion.workNotes.add("Se le acabó el tiempo.");
			finishRemove(player, companion);
			return null;
		}
		while (companion.workBlock == null) {
			if (companion.workQueue.isEmpty()) {
				finishRemove(player, companion);
				return null;
			}
			Block next = companion.workQueue.removeFirst();
			if (!next.getType().isAir()) {
				companion.workBlock = next;
				companion.workTargetTicks = 0;
			}
		}
		Block block = companion.workBlock;
		if (block.getType().isAir()) {
			clearTarget(companion);
			return null;
		}
		Frog frog = companion.entity;
		Location center = MathUtil.center(block);
		int frogY = frog.getLocation().getBlockY();
		boolean inReach = MathUtil.horizontal(frog.getLocation(), center) <= BLOCK_REACH
				&& block.getY() - frogY <= VERTICAL_REACH && frogY - block.getY() <= VERTICAL_REACH;
		if (!inReach) {
			if (++companion.workTargetTicks > TARGET_TIMEOUT_TICKS) {
				note(companion, "Algunos bloques le quedaron muy altos o no pudo llegar a ellos.");
				clearTarget(companion);
				return null;
			}
			return center;
		}
		companion.workOwnsBody = true;
		if (companion.workStrikeTotal == 0) {
			companion.workStrikeTotal = CompanionMining.breakTicks(block, companion.tool);
			companion.workStrikeLeft = companion.workStrikeTotal;
		}
		strike(player, companion, block);
		if (companion.workStrikeLeft-- > 0) {
			return null;
		}
		String refusal = Protection.whyNotBreak(player, companion, block, Protection.BREAK_RADIUS);
		if (refusal == null) {
			refusal = Protection.breakBlock(player, companion, block, false, null);
		}
		if (refusal != null) {
			note(companion, refusal);
		} else {
			companion.workBroken++;
		}
		clearTarget(companion);
		return null;
	}

	private static void finishRemove(Player player, Companion companion) {
		companion.mode = Companion.Mode.FOLLOW;
		companion.workOwnsBody = false;
		clearTarget(companion);
		StringBuilder fact = new StringBuilder("Pomi terminó de quitar bloques: quitó ").append(companion.workBroken)
				.append(" de ").append(companion.workGoal).append('.');
		for (String note : companion.workNotes) {
			fact.append(' ').append(note);
		}
		PomiTasks.notify(player, companion, fact.toString(), "Díselo en una frase.", null);
	}

	private static void note(Companion companion, String note) {
		if (!companion.workNotes.contains(note)) {
			companion.workNotes.add(note);
		}
	}

	// ---- Farming ------------------------------------------------------------------------------

	/**
	 * Starts tending a field: the one under Pomi, under the player, or the one the player points at.
	 * Harvests ripe crops and replants them, and sows empty farmland with seeds she carries
	 * ({@code action}: "cosechar", "plantar" or "todo"). Returns the result for the LLM.
	 */
	public static String startFarm(Player player, Companion companion, String action) {
		Block start = null;
		List<Block> probes = new ArrayList<>();
		Block frogFeet = companion.entity.getLocation().getBlock();
		Block playerFeet = player.getLocation().getBlock();
		probes.add(frogFeet.getRelative(BlockFace.DOWN));
		probes.add(frogFeet);
		probes.add(playerFeet.getRelative(BlockFace.DOWN));
		probes.add(playerFeet);
		Block aimed = player.getTargetBlockExact(PomiTasks.AIM_DISTANCE);
		if (aimed != null) {
			probes.add(aimed);
			probes.add(aimed.getRelative(BlockFace.DOWN));
		}
		for (Block probe : probes) {
			if (probe.getType() == Material.FARMLAND) {
				start = probe;
				break;
			}
		}
		if (start == null) {
			return "No encuentras ningún cultivo: ni tú ni el jugador están parados en tierra arada, y él no apunta a una. "
					+ "Que se pare en el cultivo o lo señale y te lo vuelva a pedir.";
		}
		if (player.getGameMode() == GameMode.ADVENTURE || player.getGameMode() == GameMode.SPECTATOR) {
			return "No puedes trabajar este cultivo: en este modo de juego el jugador no puede romper ni poner bloques.";
		}
		// The whole field: farmland connected to it, on the same level.
		Set<Block> field = new HashSet<>();
		Deque<Block> queue = new ArrayDeque<>();
		queue.add(start);
		field.add(start);
		BlockFace[] around = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
		while (!queue.isEmpty() && field.size() < MAX_FIELD_SCAN) {
			Block block = queue.poll();
			for (BlockFace face : around) {
				Block next = block.getRelative(face);
				if (!field.contains(next) && next.getType() == Material.FARMLAND) {
					field.add(next);
					queue.add(next);
				}
			}
		}
		// Its center, so the work spreads out from the middle and covers the best 10x10.
		double sumX = 0;
		double sumZ = 0;
		for (Block block : field) {
			sumX += block.getX();
			sumZ += block.getZ();
		}
		int centerX = (int) Math.round(sumX / field.size());
		int centerZ = (int) Math.round(sumZ / field.size());
		List<Block> cells = new ArrayList<>();
		for (Block block : field) {
			int dx = block.getX() - centerX;
			int dz = block.getZ() - centerZ;
			if (dx >= -FIELD_HALF && dx < FIELD_HALF && dz >= -FIELD_HALF && dz < FIELD_HALF) {
				cells.add(block);
			}
		}
		// A spiral from the center: ring by ring, going around each one.
		cells.sort(Comparator.comparingInt((Block b) -> Math.max(Math.abs(b.getX() - centerX), Math.abs(b.getZ() - centerZ)))
				.thenComparingDouble(b -> Math.atan2(b.getZ() - centerZ, b.getX() - centerX)));

		CompanionPlan.cancel(companion);
		companion.attackTarget = null;
		companion.pickupTarget = null;
		companion.mode = Companion.Mode.FARM;
		String act = Chat.lower(action);
		companion.farmAction = act.equals("cosechar") || act.equals("plantar") ? act : "todo";
		companion.farmCells = cells;
		companion.farmIndex = 0;
		companion.farmHarvested = 0;
		companion.farmReplanted = 0;
		companion.farmPlanted = 0;
		companion.workTicks = 0;
		companion.workTargetTicks = 0;
		companion.workStrikeLeft = 0;
		companion.workNotes.clear();
		companion.farmDropped = false;
		int ripe = 0;
		int empty = 0;
		for (Block cell : cells) {
			Block crop = cell.getRelative(BlockFace.UP);
			if (isRipe(crop)) {
				ripe++;
			} else if (crop.getType().isAir()) {
				empty++;
			}
		}
		return "Encontraste el cultivo: " + field.size() + " bloques de tierra arada" + (field.size() > cells.size()
				? " (trabajas los " + cells.size() + " del centro, máximo 10x10)" : "") + ", con " + ripe + " listos para cosechar y "
				+ empty + " vacíos. Empiezas desde el centro hacia afuera. Al terminar llegará un [Evento]; por ahora solo di que vas.";
	}

	/** @return where to head this tick, or null to stay put */
	public static Location tickFarm(Player player, Companion companion) {
		companion.workOwnsBody = false;
		if (++companion.workTicks > FARM_TIMEOUT_TICKS) {
			companion.workNotes.add("Se le acabó el tiempo antes de terminar todo el cultivo.");
			finishFarm(player, companion);
			return null;
		}
		while (companion.farmIndex < companion.farmCells.size() && work(companion, companion.farmCells.get(companion.farmIndex)) == null) {
			companion.farmIndex++;
			companion.workTargetTicks = 0;
			companion.workStrikeLeft = 0;
		}
		if (companion.farmIndex >= companion.farmCells.size()) {
			finishFarm(player, companion);
			return null;
		}
		Block crop = companion.farmCells.get(companion.farmIndex).getRelative(BlockFace.UP);
		Location spot = MathUtil.bottomCenter(crop);
		if (MathUtil.horizontal(companion.entity.getLocation(), spot) > 0.9) {
			if (++companion.workTargetTicks > TARGET_TIMEOUT_TICKS) {
				companion.farmIndex++;
				companion.workTargetTicks = 0;
			}
			return spot;
		}
		companion.workOwnsBody = true;
		if (companion.workStrikeLeft == 0) {
			companion.workStrikeLeft = Math.max(2, (int) Math.round(FARM_ACTION_TICKS / CompanionStats.POMI.dexterityFactor()));
		}
		if (--companion.workStrikeLeft > 0) {
			return null;
		}
		String job = work(companion, companion.farmCells.get(companion.farmIndex));
		if ("cosechar".equals(job)) {
			harvest(player, companion, crop);
		} else if ("plantar".equals(job)) {
			plant(player, companion, crop);
		}
		companion.farmIndex++;
		companion.workTargetTicks = 0;
		return null;
	}

	private static boolean isRipe(Block crop) {
		return CROP_SEEDS.containsKey(crop.getType()) && crop.getBlockData() instanceof Ageable age && age.getAge() >= age.getMaximumAge();
	}

	/** What there is to do on this farmland: "cosechar", "plantar" or null. */
	private static String work(Companion companion, Block farmland) {
		if (farmland.getType() != Material.FARMLAND) {
			return null;
		}
		Block crop = farmland.getRelative(BlockFace.UP);
		String action = companion.farmAction;
		if (!action.equals("plantar") && isRipe(crop)) {
			return "cosechar";
		}
		if (!action.equals("cosechar") && crop.getType().isAir() && seedSlot(companion) >= 0) {
			return "plantar";
		}
		return null;
	}

	/** Picks a ripe crop and plants it again with one of its own seeds. */
	private static void harvest(Player player, Companion companion, Block crop) {
		Material type = crop.getType();
		if (!new BlockBreakEvent(crop, player).callEvent()) {
			note(companion, "Parte del cultivo está protegido y no lo tocó.");
			return;
		}
		List<ItemStack> drops = new ArrayList<>(crop.getDrops(ItemStack.empty(), companion.entity));
		BlockData before = crop.getBlockData();
		crop.setType(Material.AIR);
		crop.getWorld().playSound(MathUtil.center(crop), before.getSoundGroup().getBreakSound(), 0.8f, 1.0f);
		companion.farmHarvested++;
		Material seed = CROP_SEEDS.get(type);
		for (int i = 0; i < drops.size(); i++) {
			ItemStack drop = drops.get(i);
			if (drop.getType() == seed && drop.getAmount() > 0) {
				drops.set(i, drop.getAmount() > 1 ? drop.asQuantity(drop.getAmount() - 1) : null);
				crop.setType(type);
				companion.farmReplanted++;
				break;
			}
		}
		for (ItemStack drop : drops) {
			if (Companion.isEmpty(drop)) {
				continue;
			}
			ItemStack rest = PomiTasks.insert(companion, drop);
			if (!Companion.isEmpty(rest)) {
				crop.getWorld().dropItemNaturally(MathUtil.center(crop), rest);
				companion.farmDropped = true;
			}
		}
		PomiBody.action(companion, companion.entity);
	}

	/** Sows empty farmland with a seed from her slots. */
	private static void plant(Player player, Companion companion, Block crop) {
		int slot = seedSlot(companion);
		if (slot < 0) {
			return;
		}
		ItemStack seeds = companion.inventory[slot];
		Material cropType = SEED_CROPS.get(seeds.getType());
		if (!Protection.place(player, crop, crop.getRelative(BlockFace.DOWN), cropType.createBlockData(), seeds.asOne())) {
			note(companion, "En parte del cultivo no pudo sembrar (protegido u ocupado).");
			return;
		}
		if (player.getGameMode() != GameMode.CREATIVE) {
			seeds.setAmount(seeds.getAmount() - 1);
			if (seeds.getAmount() <= 0) {
				companion.inventory[slot] = null;
			}
		}
		companion.farmPlanted++;
	}

	/** The slot holding something plantable on farmland, or -1. */
	private static int seedSlot(Companion companion) {
		for (int i = 0; i < companion.inventory.length; i++) {
			if (!Companion.isEmpty(companion.inventory[i]) && SEED_CROPS.containsKey(companion.inventory[i].getType())) {
				return i;
			}
		}
		return -1;
	}

	private static void finishFarm(Player player, Companion companion) {
		companion.mode = Companion.Mode.FOLLOW;
		companion.workOwnsBody = false;
		StringBuilder fact = new StringBuilder("Pomi terminó con el cultivo: ");
		if (companion.farmHarvested == 0 && companion.farmPlanted == 0) {
			fact.append("no había nada listo para cosechar");
			fact.append(companion.farmAction.equals("cosechar") ? "." : " ni semillas para plantar (que le lance semillas, zanahorias o papas).");
		} else {
			List<String> parts = new ArrayList<>();
			if (companion.farmHarvested > 0) {
				parts.add("cosechó " + companion.farmHarvested + " y replantó " + companion.farmReplanted);
			}
			if (companion.farmPlanted > 0) {
				parts.add("sembró " + companion.farmPlanted + " lugares vacíos");
			}
			fact.append(String.join(", ", parts)).append('.');
		}
		if (companion.farmDropped) {
			fact.append(" No le cupo todo en sus dos espacios: lo demás quedó tirado en el cultivo.");
		}
		for (String note : companion.workNotes) {
			fact.append(' ').append(note);
		}
		fact.append(" Trae: ").append(PomiTasks.describeInventory(companion)).append('.');
		PomiTasks.notify(player, companion, fact.toString(), "Díselo en una frase.", "fanfarria");
	}

	// ---- Shared -------------------------------------------------------------------------------

	/** Paws at a block: faces it, a little lunge and the hit sound now and then, and the usual cracks. */
	private static void strike(Player player, Companion companion, Block block) {
		Frog frog = companion.entity;
		Location center = MathUtil.center(block);
		PomiBody.face(frog, center);
		if (companion.workStrikeLeft % 8 == 0) {
			Vector toward = center.toVector().subtract(frog.getLocation().toVector());
			if (frog.isOnGround() && toward.lengthSquared() > 1.0E-4) {
				Vector push = toward.normalize().multiply(0.12);
				frog.setVelocity(new Vector(push.getX(), 0.2, push.getZ()));
			}
			frog.getWorld().playSound(center, block.getBlockData().getSoundGroup().getHitSound(), 0.7f, 1.0f);
		}
		float progress = Math.max(0.0f, Math.min(0.99f, 1.0f - (float) companion.workStrikeLeft / (companion.workStrikeTotal + 1)));
		for (Player viewer : block.getWorld().getPlayers()) {
			if (viewer.getLocation().distanceSquared(center) < 48 * 48) {
				viewer.sendBlockDamage(block.getLocation(), progress, frog);
			}
		}
	}

	private static void clearTarget(Companion companion) {
		companion.workBlock = null;
		companion.workStrikeTotal = 0;
		companion.workStrikeLeft = 0;
		companion.workTargetTicks = 0;
	}
}
