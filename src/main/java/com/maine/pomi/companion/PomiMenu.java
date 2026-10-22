package com.maine.pomi.companion;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import com.maine.pomi.PomiPlugin;
import com.maine.pomi.util.Chat;

/**
 * Pomi's window (right-click on her): her two slots, her tool slot, what she's doing and a stop
 * button. Every click in it is cancelled and applied to her own state by hand, so the companion is
 * the only copy of her items (nothing to sync, nothing to duplicate or lose); the window is then
 * redrawn from her state.
 */
public final class PomiMenu implements InventoryHolder {
	private static final int SLOT_A = 0;
	private static final int SLOT_B = 1;
	private static final int SLOT_INFO = 4;
	private static final int SLOT_STOP = 6;
	private static final int SLOT_TOOL = 8;

	private final Companion companion;
	private final Inventory inventory;

	private PomiMenu(Companion companion) {
		this.companion = companion;
		this.inventory = Bukkit.createInventory(this, 9, Component.text(companion.name()));
		refresh();
	}

	public static void open(Player player, Companion companion) {
		PomiMenu menu = new PomiMenu(companion);
		companion.menuOpen = true;
		player.openInventory(menu.inventory);
	}

	public Companion companion() {
		return companion;
	}

	@Override
	public @NotNull Inventory getInventory() {
		return inventory;
	}

	/** Redraws the window from Pomi's state. */
	public void refresh() {
		ItemStack filler = button(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
		for (int i = 0; i < 9; i++) {
			inventory.setItem(i, filler);
		}
		inventory.setItem(SLOT_A, companion.inventory[0]);
		inventory.setItem(SLOT_B, companion.inventory[1]);
		inventory.setItem(SLOT_TOOL, Companion.isEmpty(companion.tool)
				? button(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "Herramienta (vacío)", List.of("Pon aquí un pico, pala o hacha."))
				: companion.tool);
		inventory.setItem(SLOT_INFO, button(Material.LILY_PAD, companion.name(), List.of(
				"Haciendo: " + doing(companion),
				"Te sigue a " + Math.round(companion.followDistance * 10) / 10.0 + " bloques",
				"Color: " + (companion.choice == null ? "?" : companion.choice.colourName()),
				"Con su herramienta rompe: " + CompanionMining.extras(companion.tool))));
		inventory.setItem(SLOT_STOP, button(Material.BARRIER, "Detener", List.of("Deja lo que está haciendo y te sigue.")));
	}

	/** Redraws the open window of this companion's owner, if any. */
	public static void refreshOpen(Player player) {
		if (player.getOpenInventory().getTopInventory().getHolder() instanceof PomiMenu menu) {
			menu.refresh();
		}
	}

	private static ItemStack button(Material type, String name, List<String> lore) {
		ItemStack stack = new ItemStack(type);
		ItemMeta meta = stack.getItemMeta();
		meta.displayName(Component.text(name, NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
		meta.lore(lore.stream().map(line -> (Component) Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)).toList());
		stack.setItemMeta(meta);
		return stack;
	}

	/** What she's doing, in a few words. */
	public static String doing(Companion companion) {
		if (companion.planActive) {
			return "un encargo de varios pasos";
		}
		return switch (companion.mode) {
			case FOLLOW -> "seguirte";
			case STAY -> "esperar aquí";
			case GO_TO -> "ir a un lugar";
			case ATTACK -> "pelear con un mob";
			case PICKUP -> "recoger items";
			case GUARD -> "cuidarte";
			case GATHER -> "juntar " + companion.workResource;
			case FARM -> "trabajar el cultivo";
			case REMOVE -> "quitar bloques";
			case BUILD -> "construir";
			case ROUTINE -> "una rutina";
		};
	}

	/** Handles a click while this window is open. */
	public void click(InventoryClickEvent event) {
		event.setCancelled(true);
		Player player = (Player) event.getWhoClicked();
		InventoryAction action = event.getAction();
		if (action == InventoryAction.COLLECT_TO_CURSOR || !companion.present()) {
			return;
		}
		boolean top = event.getRawSlot() >= 0 && event.getRawSlot() < inventory.getSize();
		if (!top) {
			if (event.isShiftClick() && event.getCurrentItem() != null && !event.getCurrentItem().isEmpty()) {
				// Shift-click from the player's inventory: into her tool slot or her two slots.
				ItemStack moving = event.getCurrentItem();
				ItemStack rest;
				if (CompanionMining.isTool(moving) && Companion.isEmpty(companion.tool)) {
					companion.tool = moving.asOne();
					rest = moving.getAmount() > 1 ? moving.asQuantity(moving.getAmount() - 1) : null;
				} else {
					rest = PomiTasks.insert(companion, moving.clone());
				}
				event.setCurrentItem(rest);
			} else if (!event.isShiftClick()) {
				// Normal clicks in the player's own inventory work as usual.
				event.setCancelled(false);
				return;
			}
		} else {
			int slot = event.getRawSlot();
			if (slot == SLOT_STOP) {
				PomiPlugin.instance().companions().stopAll(player, companion);
				Chat.info(player, companion.name() + " dejó lo que hacía y te sigue.");
			} else if (slot == SLOT_A || slot == SLOT_B) {
				clickSlot(player, event, slot);
			} else if (slot == SLOT_TOOL) {
				clickTool(player, event);
			}
		}
		refresh();
		Bukkit.getScheduler().runTask(PomiPlugin.instance(), player::updateInventory);
	}

	/** Takes from, puts into, merges with or swaps one of her two slots, like a normal slot. */
	private void clickSlot(Player player, InventoryClickEvent event, int slot) {
		ItemStack held = companion.inventory[slot];
		ItemStack cursor = event.getView().getCursor();
		if (event.isShiftClick()) {
			if (!Companion.isEmpty(held)) {
				player.getInventory().addItem(held).values().forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
				companion.inventory[slot] = null;
			}
			return;
		}
		if (Companion.isEmpty(cursor)) {
			if (!Companion.isEmpty(held)) {
				event.getView().setCursor(held);
				companion.inventory[slot] = null;
			}
		} else if (Companion.isEmpty(held)) {
			companion.inventory[slot] = cursor.clone();
			event.getView().setCursor(null);
		} else if (held.isSimilar(cursor) && held.getAmount() < held.getMaxStackSize()) {
			int moved = Math.min(cursor.getAmount(), held.getMaxStackSize() - held.getAmount());
			held.setAmount(held.getAmount() + moved);
			event.getView().setCursor(cursor.getAmount() > moved ? cursor.asQuantity(cursor.getAmount() - moved) : null);
		} else {
			companion.inventory[slot] = cursor.clone();
			event.getView().setCursor(held);
		}
	}

	/** Her tool slot only takes tools (one). */
	private void clickTool(Player player, InventoryClickEvent event) {
		ItemStack cursor = event.getView().getCursor();
		if (event.isShiftClick()) {
			if (!Companion.isEmpty(companion.tool)) {
				player.getInventory().addItem(companion.tool).values()
						.forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
				companion.tool = null;
			}
			return;
		}
		if (Companion.isEmpty(cursor)) {
			if (!Companion.isEmpty(companion.tool)) {
				event.getView().setCursor(companion.tool);
				companion.tool = null;
			}
		} else if (CompanionMining.isTool(cursor) && cursor.getAmount() == 1) {
			ItemStack previous = companion.tool;
			companion.tool = cursor.clone();
			event.getView().setCursor(previous);
		}
	}
}
