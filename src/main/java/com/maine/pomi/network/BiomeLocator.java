package com.maine.pomi.network;

import java.text.Normalizer;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;
import org.bukkit.util.BiomeSearchResult;

import com.maine.pomi.util.Chat;
import com.maine.pomi.util.MathUtil;

/** The nearest biome of a kind, like /locate biome, with Spanish names for the common ones. */
public final class BiomeLocator {
	private static final int RADIUS = 3200;
	private static final Map<String, String> SPANISH = Map.ofEntries(
			Map.entry("llanura", "plains"), Map.entry("pradera", "plains"), Map.entry("bosque", "forest"),
			Map.entry("bosque oscuro", "dark_forest"), Map.entry("abedul", "birch_forest"), Map.entry("desierto", "desert"),
			Map.entry("jungla", "jungle"), Map.entry("selva", "jungle"), Map.entry("sabana", "savanna"), Map.entry("taiga", "taiga"),
			Map.entry("taiga nevada", "snowy_taiga"), Map.entry("tundra", "snowy_plains"), Map.entry("nieve", "snowy_plains"),
			Map.entry("cerezos", "cherry_grove"), Map.entry("cerezo", "cherry_grove"), Map.entry("pantano", "swamp"),
			Map.entry("manglar", "mangrove_swamp"), Map.entry("badlands", "badlands"), Map.entry("mesa", "badlands"),
			Map.entry("champinones", "mushroom_fields"), Map.entry("hongos", "mushroom_fields"), Map.entry("playa", "beach"),
			Map.entry("oceano", "ocean"), Map.entry("mar", "ocean"), Map.entry("rio", "river"), Map.entry("montana", "jagged_peaks"),
			Map.entry("picos", "jagged_peaks"), Map.entry("arboleda", "grove"), Map.entry("prado", "meadow"),
			Map.entry("cueva exuberante", "lush_caves"), Map.entry("cuevas exuberantes", "lush_caves"),
			Map.entry("ciudad antigua", "deep_dark"), Map.entry("oscuridad profunda", "deep_dark"), Map.entry("palido", "pale_garden"),
			Map.entry("jardin palido", "pale_garden"));

	private BiomeLocator() {
	}

	public static String locate(Player player, String query) {
		String plain = Normalizer.normalize(Chat.lower(query), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
		String id = SPANISH.getOrDefault(plain, plain.replace(' ', '_'));
		NamespacedKey key = NamespacedKey.fromString(id.contains(":") ? id : "minecraft:" + id);
		Biome biome = key == null ? null : Registry.BIOME.get(key);
		if (biome == null) {
			return "No conoces el bioma \"" + query + "\". Prueba con su nombre en inglés (desert, jungle, cherry_grove...).";
		}
		BiomeSearchResult result = player.getWorld().locateNearestBiome(player.getLocation(), RADIUS, 32, 64, biome);
		if (result == null) {
			return "No hay " + biome.getKey().getKey() + " a menos de " + RADIUS + " bloques en esta dimensión.";
		}
		Location at = result.getLocation();
		return "El " + biome.getKey().getKey() + " más cercano está " + MathUtil.describe(player, at) + ", en X " + at.getBlockX()
				+ " Z " + at.getBlockZ() + ". Dile la dirección y las coordenadas; que vaya él.";
	}
}
