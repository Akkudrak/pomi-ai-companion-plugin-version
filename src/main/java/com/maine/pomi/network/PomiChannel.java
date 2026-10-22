package com.maine.pomi.network;

import java.nio.charset.StandardCharsets;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.maine.pomi.PomiPlugin;

/**
 * Plugin messaging channel {@code pomi:main}, for a future companion desktop app (voice). The
 * server sends Pomi's lines as {@code {"type":"say","text":...}}; a client mod or app can send
 * {@code {"type":"ask","text":...}}, handled exactly like {@code /p <text>}. Vanilla clients
 * ignore the channel.
 */
public final class PomiChannel implements PluginMessageListener {
	public static final String CHANNEL = "pomi:main";
	private static final int MAX_BYTES = 30000;

	private final PomiPlugin plugin;

	public PomiChannel(PomiPlugin plugin) {
		this.plugin = plugin;
		Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
		Bukkit.getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
	}

	public void shutdown() {
		Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL);
		Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
	}

	public static void send(PomiPlugin plugin, Player player, String type, String text) {
		if (!player.getListeningPluginChannels().contains(CHANNEL)) {
			return;
		}
		String body = text == null ? "" : text;
		if (body.length() > MAX_BYTES / 4) {
			body = body.substring(0, MAX_BYTES / 4);
		}
		JsonObject message = new JsonObject();
		message.addProperty("type", type);
		message.addProperty("text", body);
		player.sendPluginMessage(plugin, CHANNEL, message.toString().getBytes(StandardCharsets.UTF_8));
	}

	@Override
	public void onPluginMessageReceived(String channel, Player player, byte[] bytes) {
		if (!CHANNEL.equals(channel) || bytes.length > MAX_BYTES || !player.hasPermission("pomi.use")) {
			return;
		}
		try {
			JsonObject message = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
			String type = message.has("type") ? message.get("type").getAsString() : "";
			String text = message.has("text") ? message.get("text").getAsString().strip() : "";
			if (type.equals("ask") && !text.isEmpty()) {
				plugin.companions().summon(player);
				plugin.brain().ask(player, text);
			}
		} catch (RuntimeException ignored) {
			// Not ours or malformed: ignore.
		}
	}
}
