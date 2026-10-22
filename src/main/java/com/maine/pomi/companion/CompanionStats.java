package com.maine.pomi.companion;

/**
 * Pomi's stats from the mod's profile (fuerza 4, velocidad 7, destreza 6, percepción 8), each 1-10,
 * turned into multipliers the same way: 0.6 + stat * 0.08 (5 is about average).
 */
public record CompanionStats(int strength, int speed, int dexterity, int perception) {
	public static final CompanionStats POMI = new CompanionStats(4, 7, 6, 8);

	private static double factor(int stat) {
		return 0.6 + Math.max(1, Math.min(10, stat)) * 0.08;
	}

	public double strengthFactor() {
		return factor(strength);
	}

	public double speedFactor() {
		return factor(speed);
	}

	public double dexterityFactor() {
		return factor(dexterity);
	}

	public double perceptionFactor() {
		return factor(perception);
	}
}
