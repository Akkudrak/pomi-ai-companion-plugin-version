package com.maine.pomi.network;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import io.papermc.paper.advancement.AdvancementDisplay;

import com.maine.pomi.companion.PomiTasks;
import com.maine.pomi.util.Chat;
import com.maine.pomi.util.MathUtil;

/** "What's next?", "where did I die?" and "what is that?", answered from the server's real data. */
public final class PomiGuide {
	private PomiGuide() {
	}

	/**
	 * The next advancements the player can go for: not done, with their parent done (the frontier of
	 * their advancement tree), story ones first, and what's still missing for each.
	 */
	public static String nextSteps(Player player) {
		List<String> story = new ArrayList<>();
		List<String> other = new ArrayList<>();
		Iterator<Advancement> all = Bukkit.advancementIterator();
		while (all.hasNext()) {
			Advancement advancement = all.next();
			AdvancementDisplay display = advancement.getDisplay();
			if (display == null || advancement.getKey().getKey().startsWith("recipes/")) {
				continue;
			}
			AdvancementProgress progress = player.getAdvancementProgress(advancement);
			Advancement parent = advancement.getParent();
			if (progress.isDone() || (parent != null && !player.getAdvancementProgress(parent).isDone())) {
				continue;
			}
			List<String> missing = new ArrayList<>(progress.getRemainingCriteria());
			String line = Chat.plain(display.title()) + ": " + Chat.plain(display.description())
					+ (missing.size() > 0 && missing.size() <= 6 ? " (le falta: " + String.join(", ", missing) + ")" : "");
			(advancement.getKey().getKey().startsWith("story/") ? story : other).add(line);
		}
		List<String> pick = new ArrayList<>(story);
		pick.addAll(other);
		if (pick.isEmpty()) {
			return "Ya completó todos los logros que hay a su alcance. ¡Impresionante!";
		}
		return "Siguientes logros (los de historia primero):\n- " + String.join("\n- ", pick.subList(0, Math.min(5, pick.size())));
	}

	public static String whereDied(Player player) {
		Location death = player.getLastDeathLocation();
		if (death == null) {
			return "No hay registro de que el jugador haya muerto.";
		}
		if (death.getWorld() != player.getWorld()) {
			return "Murió en otra dimensión (" + death.getWorld().getName() + "), en " + death.getBlockX() + " " + death.getBlockY() + " "
					+ death.getBlockZ() + ".";
		}
		return "Murió " + MathUtil.describe(player, death) + ", en las coordenadas " + death.getBlockX() + " " + death.getBlockY() + " "
				+ death.getBlockZ() + ". Sus cosas desaparecen 5 minutos después de morir.";
	}

	/** The block or mob the player points at, and how it's made if it's crafted. */
	public static String whatIsThat(Player player) {
		Entity entity = player.getTargetEntity(PomiTasks.AIM_DISTANCE);
		if (entity != null) {
			return "Señala un " + entity.getType().getKey().getKey() + ", " + MathUtil.describe(player, entity.getLocation()) + ".";
		}
		Block block = player.getTargetBlockExact(PomiTasks.AIM_DISTANCE);
		if (block == null) {
			return "No está señalando nada (o está muy lejos).";
		}
		return "Señala un bloque de " + Chat.id(block.getType()) + ". " + PomiRequests.recipes(block.getType().getKey().toString());
	}
}
