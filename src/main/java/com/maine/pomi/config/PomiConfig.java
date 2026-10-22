package com.maine.pomi.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * {@code plugins/Pomi/config.properties}: copied from the jar's {@code pomi.properties} the first
 * time (comments included), with the jar's values as defaults for any key the file lacks.
 */
public final class PomiConfig {
	private static final String DEFAULTS = "pomi.properties";

	private final JavaPlugin plugin;
	private final Path file;
	private final Properties props = new Properties();

	public PomiConfig(JavaPlugin plugin) {
		this.plugin = plugin;
		this.file = plugin.getDataFolder().toPath().resolve("config.properties");
		reload();
	}

	public void reload() {
		Properties loaded = new Properties();
		try (InputStream in = plugin.getResource(DEFAULTS)) {
			if (in != null) {
				loaded.load(new InputStreamReader(in, StandardCharsets.UTF_8));
			}
		} catch (IOException e) {
			plugin.getLogger().warning("No se pudieron leer los valores por defecto: " + e.getMessage());
		}
		try {
			Files.createDirectories(file.getParent());
			if (Files.notExists(file)) {
				try (InputStream in = plugin.getResource(DEFAULTS)) {
					if (in != null) {
						Files.copy(in, file);
					}
				}
			}
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				loaded.load(reader);
			}
		} catch (IOException e) {
			plugin.getLogger().warning("No se pudo leer " + file + ": " + e.getMessage());
		}
		props.clear();
		props.putAll(loaded);
	}

	/** Changes one key in the file, keeping its comments and the other lines as they are. */
	public void set(String key, String value) {
		props.setProperty(key, value);
		try {
			List<String> lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8)) : new ArrayList<>();
			boolean replaced = false;
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i).strip();
				if (line.startsWith(key + "=") || line.startsWith(key + " =")) {
					lines.set(i, key + "=" + value);
					replaced = true;
				}
			}
			if (!replaced) {
				lines.add(key + "=" + value);
			}
			Files.write(file, lines, StandardCharsets.UTF_8);
		} catch (IOException e) {
			plugin.getLogger().warning("No se pudo guardar " + file + ": " + e.getMessage());
		}
	}

	public String get(String key, String fallback) {
		String value = props.getProperty(key);
		return value == null || value.isBlank() ? fallback : value.strip();
	}

	private boolean flag(String key, boolean fallback) {
		String value = props.getProperty(key);
		return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value.strip());
	}

	private int number(String key, int fallback) {
		try {
			return Integer.parseInt(props.getProperty(key, "").strip());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/** The xAI key from the file, or else from the server's XAI_API_KEY environment variable ("" if none). */
	public String apiKey() {
		String key = get("xai-api-key", "");
		if (key.isEmpty()) {
			String env = System.getenv("XAI_API_KEY");
			key = env == null ? "" : env.strip();
		}
		return key;
	}

	public String model() {
		return get("model", "grok-4.20-0309-non-reasoning");
	}

	public String personality() {
		return get("personalidad", "");
	}

	public boolean memory() {
		return flag("memoria", true);
	}

	public boolean aiComments() {
		return flag("comentarios-ia", true);
	}

	public long messageCooldownMillis() {
		return Math.max(0, number("espera-mensajes", 2)) * 1000L;
	}

	public boolean naturalBlocksOnly() {
		return flag("solo-bloques-naturales", true);
	}

	/** The link for the Pomi Voz desktop app (see PomiVoiceLink). */
	public boolean voice() {
		return flag("voz", true);
	}

	public int voicePort() {
		return number("voz-puerto", 8765);
	}

	/** Kokoro's OpenAI-compatible speech endpoint; the server calls it for the app. */
	public String kokoroUrl() {
		return get("kokoro-url", "");
	}

	public String kokoroVoice() {
		return get("kokoro-voz", "ef_dora");
	}

	/** Whisper's OpenAI-compatible transcription endpoint (speaches), for the app's voice orders. */
	public String whisperUrl() {
		return get("whisper-url", "");
	}

	public String whisperModel() {
		return get("whisper-modelo", "Systran/faster-whisper-small");
	}

	public double kokoroSpeed() {
		try {
			return Double.parseDouble(get("kokoro-velocidad", "0.95"));
		} catch (NumberFormatException e) {
			return 0.95;
		}
	}
}
