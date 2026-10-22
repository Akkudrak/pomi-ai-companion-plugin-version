package com.maine.pomi.companion;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Frog;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Per-player state of the companion. It outlives the frog (respawned after a dimension change or a
 * chunk unload), so the colour, follow distance and carried items survive those. Pomi only exists
 * in the world while {@link #summoned}.
 */
public final class Companion {
	public static final double DEFAULT_FOLLOW_DISTANCE = 2.5;
	public static final double MIN_FOLLOW_DISTANCE = 1.5;
	public static final double MAX_FOLLOW_DISTANCE = 16.0;
	public static final int INVENTORY_SLOTS = 2;

	public enum Mode {
		FOLLOW, STAY, GO_TO, ATTACK, PICKUP, GUARD, GATHER, FARM, REMOVE, BUILD, ROUTINE
	}

	public final UUID owner;
	/** Name and colour, saved on the player; null until the first time they call her. */
	public PomiChoice choice;
	/** Called with /p and not sent away: only then does the frog exist. */
	public boolean summoned;
	public Frog entity;

	public Location lastPos;
	public double playerSpeed;
	public Mode mode = Mode.FOLLOW;
	public double followDistance = DEFAULT_FOLLOW_DISTANCE;

	/** GO_TO destination and progress, to give up when stuck. */
	public Location target;
	public double bestDistance;
	public int stuckTicks;

	/** Ticks left of the tongue animation; the frog doesn't move meanwhile. */
	public int actionTicks;
	public int taskTicks;
	public int attackCooldown;
	public LivingEntity attackTarget;
	public float attackStopHealth;
	public boolean attackKill;
	public int guardKills;
	public int guardQuietTicks;
	public long lastHordeComment = Long.MIN_VALUE / 2;

	public Item pickupTarget;
	public String pickupItemId = "";
	public final Map<String, Integer> pickedUp = new LinkedHashMap<>();

	/** Two slots (null = empty) and the tool slot. */
	public final ItemStack[] inventory = new ItemStack[INVENTORY_SLOTS];
	public ItemStack tool;
	public long lastCatchComment = Long.MIN_VALUE / 2;
	public long lastFullWarning = Long.MIN_VALUE / 2;
	/** The owner has Pomi's window open. */
	public boolean menuOpen;

	/** Gathering, removing and farming (see CompanionWork). */
	public String workResource = "";
	public int workGoal;
	public int workTimeout;
	public int workDone;
	public int workBroken;
	public int workTicks;
	public int workTargetTicks;
	public int workStrikeTotal;
	public int workStrikeLeft;
	public Block workBlock;
	public final Set<Block> workSkipped = new HashSet<>();
	public List<Block> workQueue = new ArrayList<>();
	public final List<String> workNotes = new ArrayList<>();
	/** The job turns the body itself this tick, so the usual movement is skipped. */
	public boolean workOwnsBody;
	public String farmAction = "todo";
	public List<Block> farmCells = List.of();
	public int farmIndex;
	public int farmHarvested;
	public int farmReplanted;
	public int farmPlanted;
	public boolean farmDropped;

	/** Building (see CompanionBuild). */
	public List<Block> buildTargets = List.of();
	public int buildIndex;
	public Material buildMaterial;
	public int buildBaseY;
	public BlockFace buildAxis = BlockFace.NORTH;
	public int buildPlaced;

	/** A job of several steps (see CompanionPlan). */
	public List<CompanionPlan.Step> plan = List.of();
	public int planIndex;
	public final List<String> planReport = new ArrayList<>();
	public boolean planActive;
	public boolean planWaiting;

	/** A routine of basic actions (see CompanionRoutine). */
	public List<CompanionRoutine.Step> routine = List.of();
	public int routineStep;
	public int routineTicks;
	public int routinePlayerHits;
	public final List<String> routineNotes = new ArrayList<>();
	public boolean routineOwnsBody;
	public int stepTicks;
	public int stepCount;
	public int stepCooldown;
	public Location stepGoal;
	public LivingEntity stepTarget;

	/** Herobrine, while he's out there staring (see Herobrine). */
	public Mannequin herobrine;
	public int herobrineTicks;

	/** Comment pacing for kills and advancements. */
	public LivingEntity lastWeakened;
	public long lastKillComment = Long.MIN_VALUE / 2;
	public long lastAdvancementComment = Long.MIN_VALUE / 2;
	public int advancementsSinceComment;

	public Companion(Player player) {
		this.owner = player.getUniqueId();
		this.lastPos = player.getLocation();
	}

	/** Whether the frog is in the world right now. */
	public boolean present() {
		return summoned && entity != null && entity.isValid();
	}

	public String name() {
		return choice == null ? "Pomi" : choice.name();
	}

	public static boolean isEmpty(ItemStack stack) {
		return stack == null || stack.isEmpty();
	}
}
