package com.maine.pomi.companion;

import java.util.Set;

import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Which mobs Pomi dares to take on (same lists as the mod): she only finishes off "first level"
 * hostiles, nothing tough, beefy or with enchanted gear.
 */
public final class MobDifficulty {
	public static final double MAX_KILLABLE_HEALTH = 24.0;

	private static final Set<EntityType> BOSSES = Set.of(EntityType.ENDER_DRAGON, EntityType.WITHER,
			EntityType.WARDEN, EntityType.ELDER_GUARDIAN);
	private static final Set<EntityType> TOUGH = Set.of(EntityType.ENDERMAN, EntityType.WITHER_SKELETON,
			EntityType.BLAZE, EntityType.BREEZE, EntityType.EVOKER, EntityType.VINDICATOR, EntityType.ILLUSIONER,
			EntityType.RAVAGER, EntityType.PIGLIN_BRUTE, EntityType.GUARDIAN, EntityType.SHULKER, EntityType.CREAKING,
			EntityType.WITCH, EntityType.HOGLIN, EntityType.ZOGLIN, EntityType.GHAST);

	private MobDifficulty() {
	}

	public static boolean isBoss(EntityType type) {
		return BOSSES.contains(type);
	}

	/** Why Pomi won't kill this mob (Spanish, for the LLM), or null if she will. */
	public static String whyTooTough(LivingEntity mob) {
		if (isBoss(mob.getType())) {
			return "es un jefe";
		}
		if (TOUGH.contains(mob.getType())) {
			return "es de los mobs rudos";
		}
		double maxHealth = mob.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) == null ? mob.getHealth()
				: mob.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
		if (maxHealth > MAX_KILLABLE_HEALTH) {
			return "tiene muchísima vida (" + Math.round(maxHealth) + ")";
		}
		EntityEquipment equipment = mob.getEquipment();
		if (equipment != null) {
			for (EquipmentSlot slot : EquipmentSlot.values()) {
				if (!mob.canUseEquipmentSlot(slot)) {
					continue;
				}
				ItemStack item = equipment.getItem(slot);
				if (!item.isEmpty() && !item.getEnchantments().isEmpty()) {
					return "trae equipo encantado";
				}
			}
		}
		return null;
	}
}
