package com.maine.pomi.util;

import java.util.Locale;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.maine.pomi.PomiPlugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Pomi's lines in chat, and plain names for items and blocks. */
public final class Chat {
	private static final Component PREFIX = Component.text("[Pomi] ", NamedTextColor.GREEN);

	private Chat() {
	}

	/** What Pomi says; a linked Pomi Voz app reads it out loud. */
	public static void say(Player player, String text) {
		player.sendMessage(PREFIX.append(Component.text(text, NamedTextColor.WHITE)));
		PomiPlugin plugin = PomiPlugin.instance();
		if (plugin != null && plugin.voice() != null) {
			plugin.voice().publish(player, text);
		}
	}

	/** Status lines that aren't Pomi talking (help, "thinking", settings). */
	public static void info(Player player, String text) {
		player.sendMessage(PREFIX.append(Component.text(text, NamedTextColor.GRAY)));
	}

	public static void error(Player player, String text) {
		player.sendMessage(PREFIX.append(Component.text(text, NamedTextColor.RED)));
	}

	public static String plain(Component component) {
		return component == null ? "" : PlainTextComponentSerializer.plainText().serialize(component);
	}

	/** "oak_planks" for the LLM and the logs; Grok translates the ids itself. */
	public static String id(Material material) {
		return material.getKey().getKey();
	}

	/** "3x oak_planks". */
	public static String stack(ItemStack stack) {
		return stack.getAmount() + "x " + id(stack.getType());
	}

	public static String lower(String text) {
		return text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
	}
}
