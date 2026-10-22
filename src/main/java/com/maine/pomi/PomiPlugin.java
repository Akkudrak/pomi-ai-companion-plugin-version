package com.maine.pomi;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import com.maine.pomi.ai.PomiBrain;
import com.maine.pomi.ai.PomiMemory;
import com.maine.pomi.command.PomiCommand;
import com.maine.pomi.config.PomiConfig;
import com.maine.pomi.listener.PomiListener;
import com.maine.pomi.network.PomiChannel;
import com.maine.pomi.network.PomiVoiceLink;

/**
 * Pomi as a Paper plugin: the frog companion lives on the server (body, jobs, inventory) and talks
 * through Grok from the server too, so players need no client mod. Players start without her and
 * call her with {@code /p}; {@code /p adios} sends her away until they call again.
 */
public final class PomiPlugin extends JavaPlugin {
	private static PomiPlugin instance;

	private PomiConfig settings;
	private CompanionManager companions;
	private PomiMemory memory;
	private PomiBrain brain;
	private PomiChannel channel;
	private PomiVoiceLink voice;

	/** The running plugin, for the companion code that has no other way to reach it. */
	public static PomiPlugin instance() {
		return instance;
	}

	@Override
	public void onEnable() {
		instance = this;
		settings = new PomiConfig(this);
		memory = new PomiMemory(this);
		companions = new CompanionManager(this);
		brain = new PomiBrain(this);
		channel = new PomiChannel(this);
		voice = new PomiVoiceLink(this);
		voice.start();

		Bukkit.getPluginManager().registerEvents(new PomiListener(this), this);
		PluginCommand command = getCommand("pomi");
		if (command != null) {
			PomiCommand executor = new PomiCommand(this);
			command.setExecutor(executor);
			command.setTabCompleter(executor);
		}
		Bukkit.getScheduler().runTaskTimer(this, companions::tick, 1L, 1L);

		if (settings.apiKey().isBlank()) {
			getLogger().warning("No hay clave de xAI (xai-api-key o XAI_API_KEY): Pomi obedecerá comandos pero no platicará.");
		}
	}

	@Override
	public void onDisable() {
		if (companions != null) {
			companions.shutdown();
		}
		if (channel != null) {
			channel.shutdown();
		}
		if (voice != null) {
			voice.shutdown();
		}
		instance = null;
	}

	/** Pomi's own settings (not Bukkit's {@code getConfig()}, which is YAML). */
	public PomiConfig settings() {
		return settings;
	}

	public CompanionManager companions() {
		return companions;
	}

	public PomiMemory memory() {
		return memory;
	}

	public PomiBrain brain() {
		return brain;
	}

	public PomiVoiceLink voice() {
		return voice;
	}
}
