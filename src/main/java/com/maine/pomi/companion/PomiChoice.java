package com.maine.pomi.companion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Frog;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

/**
 * The player's Pomi, saved on them (PersistentDataContainer): her name and colour. Only the frog is
 * kept from the mod's five companions; the colour is picked at random the first time she's called.
 */
public record PomiChoice(String name, String variant) {
	private static final Gson GSON = new Gson();
	/** The mod's colour names for the three frog variants. */
	public static final Map<String, Frog.Variant> COLOURS = Map.of(
			"naranja", Frog.Variant.TEMPERATE,
			"blanca", Frog.Variant.WARM,
			"verde", Frog.Variant.COLD);

	public static PomiChoice random() {
		List<Frog.Variant> all = new ArrayList<>();
		Registry.FROG_VARIANT.forEach(all::add);
		Frog.Variant pick = all.isEmpty() ? Frog.Variant.TEMPERATE : all.get(ThreadLocalRandom.current().nextInt(all.size()));
		return new PomiChoice("Pomi", pick.getKey().toString());
	}

	public PomiChoice withVariant(Frog.Variant frogVariant) {
		return new PomiChoice(name, frogVariant.getKey().toString());
	}

	public Frog.Variant frogVariant() {
		NamespacedKey key = variant == null ? null : NamespacedKey.fromString(variant);
		Frog.Variant found = key == null ? null : Registry.FROG_VARIANT.get(key);
		return found == null ? Frog.Variant.TEMPERATE : found;
	}

	/** "naranja", "blanca" or "verde". */
	public String colourName() {
		Frog.Variant current = frogVariant();
		for (Map.Entry<String, Frog.Variant> entry : COLOURS.entrySet()) {
			if (entry.getValue().equals(current)) {
				return entry.getKey();
			}
		}
		return "naranja";
	}

	public String toJson() {
		return GSON.toJson(this);
	}

	public static PomiChoice fromJson(String json) {
		if (json == null || json.isBlank()) {
			return null;
		}
		try {
			PomiChoice choice = GSON.fromJson(json, PomiChoice.class);
			return choice == null || choice.name() == null || choice.name().isBlank() ? null : choice;
		} catch (JsonParseException e) {
			return null;
		}
	}
}
