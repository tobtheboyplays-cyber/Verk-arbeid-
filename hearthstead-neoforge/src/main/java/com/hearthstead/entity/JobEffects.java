package com.hearthstead.entity;

/**
 * Pure, bounded calculators for the first attribute-driven job effects.
 *
 * <p>This class is deliberately free of entity or world mutation. A formula
 * being present and tested here means {@code CALCULATOR_READY}, not that an AI
 * goal already consumes it. Runtime call sites must be wired and verified
 * separately before UI copy may call an effect live.
 */
public final class JobEffects {

    private static final double BASE_WORKING_DRAIN = 0.09D;
    private static final double BASE_SLEEP_RECOVERY = 1.5D;
    private static final double BASE_REST_RECOVERY = 1.2D;

    /** Fatigue begins below 70 energy and reaches one at zero energy. */
    public static double fatigue(double energy) {
        double boundedEnergy = boundedFinite(energy, 0.0D, 100.0D, 0.0D);
        return clamp((70.0D - boundedEnergy) / 70.0D, 0.0D, 1.0D);
    }

    /** Lowest continuous work pace at zero energy for this Stamina. */
    public static double minimumPace(int stamina) {
        return 0.65D + 0.0015D * attribute(stamina);
    }

    /**
     * Continuous pace: nobody hits a hidden daily stop; low energy makes work
     * progressively slower, bounded by {@link #minimumPace(int)}.
     */
    public static double workPace(double energy, int stamina) {
        double minimum = minimumPace(stamina);
        return 1.0D - fatigue(energy) * (1.0D - minimum);
    }

    /** Energy drained by one working interval at the supplied intensity. */
    public static double workingDrain(int stamina, double intensity,
                                      double mayorMultiplier) {
        double safeIntensity = boundedFinite(intensity, 0.0D, 4.0D, 0.0D);
        double safeMayor = boundedFinite(mayorMultiplier, 0.0D, 4.0D, 1.0D);
        return BASE_WORKING_DRAIN * safeIntensity
            * (1.0D - 0.0025D * attribute(stamina)) * safeMayor;
    }

    public static double sleepRecovery(int stamina) {
        return BASE_SLEEP_RECOVERY * (1.0D + 0.002D * attribute(stamina));
    }

    public static double restRecovery(int stamina) {
        return BASE_REST_RECOVERY * (1.0D + 0.002D * attribute(stamina));
    }

    /** Item-count budget for one physical carry trip. */
    public static int carryItems(int strength, double traitCarry) {
        double safeTrait = boundedFinite(traitCarry, 0.0D, 4.0D, 1.0D);
        int calculated = (int) Math.floor(8.0D * safeTrait)
            + attribute(strength) / 10;
        return Math.max(0, Math.min(20, calculated));
    }

    /** Weight/mass budget for one physical carry trip. */
    public static int carryMass(int strength, double traitCarry) {
        double safeTrait = boundedFinite(traitCarry, 0.0D, 4.0D, 1.0D);
        int calculated = (int) Math.floor(16.0D * safeTrait)
            + attribute(strength) / 10;
        return Math.max(0, Math.min(30, calculated));
    }

    /** Number of visible axe contacts required by a Lumberer. */
    public static int lumberContacts(int strength) {
        int value = attribute(strength);
        if (value >= 70) {
            return 2;
        }
        if (value >= 25) {
            return 3;
        }
        return 4;
    }

    private static int attribute(int raw) {
        return Math.max(0, Math.min(99, raw));
    }

    private static double boundedFinite(double value, double minimum,
                                        double maximum, double fallback) {
        return Double.isFinite(value)
            ? clamp(value, minimum, maximum) : fallback;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private JobEffects() {
    }
}
