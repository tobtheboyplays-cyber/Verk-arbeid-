package com.hearthstead.entity.combat.role;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Pure spell choice for the Rune Mage (plan/BATTLE-ROLES.md §4). The first
 * rule that fits wins: Ward protects, Frost controls, Firebolt finishes.
 * The goal measures the battlefield; this class only decides.
 */
public final class RuneMageBrain {
    /** Ward only when at least this many allies are close and fighting. */
    public static final int WARD_MIN_ALLIES = 2;
    public static final float WARD_HURT_FRACTION = 0.6F;
    public static final int FROST_MIN_ENEMIES = 2;
    public static final int FIREBOLT_MIN_ENEMIES = 2;
    /** Keep this far from any enemy; retreat when closer. */
    public static final double KEEP_AWAY = 5.0D;
    public static final double PREFERRED_MIN = 8.0D;
    public static final double PREFERRED_MAX = 14.0D;

    private RuneMageBrain() {
    }

    /**
     * @param alliesInWard       allied fighters within the ward radius that are in combat
     * @param lowestAllyFraction lowest health fraction among those allies (1 = unhurt)
     * @param allyHeavyIncoming  an enemy heavy is about to land on one of those allies
     * @param bestFrostCount     most enemies inside one frost-rune circle within range
     * @param chargerClosing     an enemy is charging our line
     * @param bestFireCount      most enemies inside one firebolt blast within range
     * @param anyEnemyInRange    a single valid firebolt target exists
     */
    public record View(int alliesInWard, float lowestAllyFraction, boolean allyHeavyIncoming,
                       int bestFrostCount, boolean chargerClosing,
                       int bestFireCount, boolean anyEnemyInRange) {
    }

    @Nullable
    public static RuneSpell choose(View v, RuneCharges charges, long now) {
        if (v.alliesInWard() >= WARD_MIN_ALLIES
            && (v.lowestAllyFraction() <= WARD_HURT_FRACTION || v.allyHeavyIncoming())
            && charges.canCast(RuneSpell.WARD, now)) {
            return RuneSpell.WARD;
        }
        if ((v.bestFrostCount() >= FROST_MIN_ENEMIES || v.chargerClosing())
            && charges.canCast(RuneSpell.FROST_RUNE, now)) {
            return RuneSpell.FROST_RUNE;
        }
        if (charges.canCast(RuneSpell.FIREBOLT, now)
            && (v.bestFireCount() >= FIREBOLT_MIN_ENEMIES
                || v.anyEnemyInRange() && charges.charges() >= RuneCharges.MAX_CHARGES)) {
            return RuneSpell.FIREBOLT;
        }
        return null;
    }

    /** A point and how many enemies a circle of {@code radius} there would catch. */
    public record Aim(double x, double y, double z, int count) {
    }

    /**
     * Best circle centre among the enemy positions themselves (O(n^2), n is a
     * raid's worth). Pure: positions are {x, y, z} triples.
     */
    @Nullable
    public static Aim bestCluster(List<double[]> enemies, double radius) {
        Aim best = null;
        double r2 = radius * radius;
        for (double[] c : enemies) {
            int n = 0;
            for (double[] o : enemies) {
                double dx = o[0] - c[0];
                double dz = o[2] - c[2];
                if (dx * dx + dz * dz <= r2 && Math.abs(o[1] - c[1]) <= 2.5D) {
                    n++;
                }
            }
            if (best == null || n > best.count()) {
                best = new Aim(c[0], c[1], c[2], n);
            }
        }
        return best;
    }

    /**
     * Mage cap: 1, or 2 once the tech tree's High Runes is learned (tech tree
     * v3: the second mage is that node's reward, so settlement size no
     * longer grants it). {@code settlers} is kept for callers.
     */
    public static int mageCap(int settlers, boolean highRunes) {
        return highRunes ? 2 : 1;
    }
}
