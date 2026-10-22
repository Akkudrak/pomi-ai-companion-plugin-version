package com.maine.pomi.listener;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import com.maine.pomi.CompanionManager;
import com.maine.pomi.PomiPlugin;
import com.maine.pomi.companion.Companion;
import com.maine.pomi.companion.PomiMenu;
import com.maine.pomi.companion.PomiTasks;

/** Paper events Pomi cares about. */
public final class PomiListener implements Listener {
	private final PomiPlugin plugin;

	public PomiListener(PomiPlugin plugin) {
		this.plugin = plugin;
	}

	@EventHandler
	public void onJoin(PlayerJoinEvent event) {
		plugin.companions().onJoin(event.getPlayer());
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		plugin.companions().onQuit(event.getPlayer());
		plugin.brain().forget(event.getPlayer());
	}

	/** Kills by the player (melee or arrows: Bukkit's killer is the shooter). */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onDeath(EntityDeathEvent event) {
		LivingEntity victim = event.getEntity();
		Player killer = victim.getKiller();
		if (killer != null) {
			Companion companion = plugin.companions().active(killer);
			if (companion != null) {
				PomiTasks.onPlayerKill(killer, companion, victim);
			}
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onAdvancement(PlayerAdvancementDoneEvent event) {
		Companion companion = plugin.companions().active(event.getPlayer());
		if (companion != null) {
			PomiTasks.onAdvancement(event.getPlayer(), companion, event.getAdvancement());
		}
	}

	/**
	 * No feeding, breeding or leashing Pomi (or Herobrine): only her logic moves her. Her owner's
	 * right-click opens her window.
	 */
	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
	public void onInteract(PlayerInteractEntityEvent event) {
		Entity entity = event.getRightClicked();
		if (CompanionManager.isHerobrine(entity)) {
			event.setCancelled(true);
			return;
		}
		if (!CompanionManager.isCompanion(entity)) {
			return;
		}
		event.setCancelled(true);
		if (event.getHand() != EquipmentSlot.HAND) {
			return;
		}
		Companion companion = plugin.companions().active(event.getPlayer());
		if (companion != null && entity.equals(companion.entity)) {
			PomiMenu.open(event.getPlayer(), companion);
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onLeash(PlayerLeashEntityEvent event) {
		if (CompanionManager.isCompanion(event.getEntity())) {
			event.setCancelled(true);
		}
	}

	/** Invulnerable, even to players in creative. */
	@EventHandler(ignoreCancelled = true)
	public void onDamage(EntityDamageEvent event) {
		if (CompanionManager.isCompanion(event.getEntity()) || CompanionManager.isHerobrine(event.getEntity())) {
			event.setCancelled(true);
		}
	}

	/** Mobs don't go after Pomi (she follows her own rules; nothing should chase her into lava). */
	@EventHandler(ignoreCancelled = true)
	public void onTarget(EntityTargetEvent event) {
		if (CompanionManager.isCompanion(event.getTarget())) {
			event.setCancelled(true);
		}
	}

	/** She changes dimension with the player (respawned next to them), never through a portal by herself. */
	@EventHandler(ignoreCancelled = true)
	public void onPortal(EntityPortalEvent event) {
		if (CompanionManager.isCompanion(event.getEntity()) || CompanionManager.isHerobrine(event.getEntity())) {
			event.setCancelled(true);
		}
	}

	@EventHandler
	public void onMenuClick(InventoryClickEvent event) {
		if (event.getView().getTopInventory().getHolder() instanceof PomiMenu menu) {
			menu.click(event);
		}
	}

	@EventHandler
	public void onMenuDrag(InventoryDragEvent event) {
		if (event.getView().getTopInventory().getHolder() instanceof PomiMenu) {
			int size = event.getView().getTopInventory().getSize();
			if (event.getRawSlots().stream().anyMatch(slot -> slot < size)) {
				event.setCancelled(true);
			}
		}
	}

	@EventHandler
	public void onMenuClose(InventoryCloseEvent event) {
		if (event.getView().getTopInventory().getHolder() instanceof PomiMenu menu) {
			menu.companion().menuOpen = false;
		}
	}
}
