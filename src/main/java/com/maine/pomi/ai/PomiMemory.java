package com.maine.pomi.ai;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.maine.pomi.PomiPlugin;

/**
 * What Pomi remembers about each player (recordar_dato / olvidar_dato), in
 * {@code plugins/Pomi/memoria.db}. Capped at 40 facts per player like the mod; the oldest go first.
 * Off with {@code memoria=false}. The SQLite driver is downloaded by Paper (plugin.yml "libraries").
 */
public final class PomiMemory {
	private static final int MAX_FACTS = 40;

	private final PomiPlugin plugin;
	private final Path file;
	private boolean ready;

	public PomiMemory(PomiPlugin plugin) {
		this.plugin = plugin;
		this.file = plugin.getDataFolder().toPath().resolve("memoria.db");
		try {
			Class.forName("org.sqlite.JDBC");
			Files.createDirectories(file.getParent());
			try (Connection connection = open(); Statement statement = connection.createStatement()) {
				statement.executeUpdate("CREATE TABLE IF NOT EXISTS fact (id INTEGER PRIMARY KEY AUTOINCREMENT, "
						+ "uuid TEXT NOT NULL, fact TEXT NOT NULL, added_at INTEGER NOT NULL)");
				statement.executeUpdate("CREATE INDEX IF NOT EXISTS fact_uuid ON fact(uuid)");
			}
			ready = true;
		} catch (Exception e) {
			plugin.getLogger().warning("La memoria de Pomi no está disponible: " + e.getMessage());
		}
	}

	private Connection open() throws SQLException {
		return DriverManager.getConnection("jdbc:sqlite:" + file);
	}

	public boolean enabled() {
		return ready && plugin.settings().memory();
	}

	public String remember(UUID player, String fact) {
		if (!enabled()) {
			return "Tu memoria está desactivada en este servidor.";
		}
		String text = fact == null ? "" : fact.strip();
		if (text.isEmpty() || text.length() > 200) {
			return "El dato debe ser corto (hasta 200 letras).";
		}
		try (Connection connection = open()) {
			try (PreparedStatement insert = connection.prepareStatement("INSERT INTO fact(uuid, fact, added_at) VALUES (?, ?, ?)")) {
				insert.setString(1, player.toString());
				insert.setString(2, text);
				insert.setLong(3, System.currentTimeMillis());
				insert.executeUpdate();
			}
			try (PreparedStatement trim = connection.prepareStatement("DELETE FROM fact WHERE uuid = ? AND id NOT IN "
					+ "(SELECT id FROM fact WHERE uuid = ? ORDER BY id DESC LIMIT ?)")) {
				trim.setString(1, player.toString());
				trim.setString(2, player.toString());
				trim.setInt(3, MAX_FACTS);
				trim.executeUpdate();
			}
			return "Guardado.";
		} catch (SQLException e) {
			plugin.getLogger().warning("No se pudo guardar un dato: " + e.getMessage());
			return "No se pudo guardar.";
		}
	}

	/** Forgets the facts that contain {@code text}. */
	public String forget(UUID player, String text) {
		if (!enabled()) {
			return "Tu memoria está desactivada en este servidor.";
		}
		try (Connection connection = open();
				PreparedStatement delete = connection.prepareStatement("DELETE FROM fact WHERE uuid = ? AND lower(fact) LIKE ?")) {
			delete.setString(1, player.toString());
			delete.setString(2, "%" + (text == null ? "" : text.strip().toLowerCase()) + "%");
			int gone = delete.executeUpdate();
			return gone == 0 ? "No recordabas nada así." : "Olvidaste " + gone + " dato(s).";
		} catch (SQLException e) {
			return "No se pudo olvidar.";
		}
	}

	public List<String> recall(UUID player) {
		List<String> facts = new ArrayList<>();
		if (!enabled()) {
			return facts;
		}
		try (Connection connection = open();
				PreparedStatement select = connection.prepareStatement("SELECT fact FROM fact WHERE uuid = ? ORDER BY id")) {
			select.setString(1, player.toString());
			try (ResultSet rows = select.executeQuery()) {
				while (rows.next()) {
					facts.add(rows.getString(1));
				}
			}
		} catch (SQLException e) {
			plugin.getLogger().warning("No se pudo leer la memoria: " + e.getMessage());
		}
		return facts;
	}
}
