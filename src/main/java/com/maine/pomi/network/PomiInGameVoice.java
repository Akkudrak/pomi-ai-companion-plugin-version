package com.maine.pomi.network;

import org.bukkit.entity.Player;

/** Keeps the optional Voice Chat API out of the plugin's public signatures. */
public interface PomiInGameVoice {
	void publish(Player player, String text);
	String status(Player player);
	void shutdown();
}
