package com.hearthstead.settlement.work;

import java.util.List;
import java.util.Map;

/**
 * Pure decision rules for the hunting-grounds spawner (no world access).
 *
 * <p>The Hunter's population floor (more than four of a species alive in
 * range before one may be taken) means a cap spread thinly across five
 * species would never become huntable. So the spawner "tops up" the herd that
 * is already there: it prefers the biome-eligible species with the most live
 * animals in range (the one closest to crossing the floor), and only now and
 * then ({@value #VARIETY_PERCENT}%) picks a random eligible species so a
 * lodge's grounds do not stay a single species forever.
 */
public final class HuntingGroundsPolicy {
    public static final int VARIETY_PERCENT = 25;

    private HuntingGroundsPolicy() {
    }

    /** True once {@code interval} ticks have passed since the last spawn. */
    public static boolean due(long now, long lastSpawn, int interval) {
        return lastSpawn == Long.MIN_VALUE || now - lastSpawn >= interval;
    }

    /** True while fewer than {@code cap} wild game animals live in range. */
    public static boolean belowCap(int liveGame, int cap) {
        return cap > 0 && liveGame < cap;
    }

    /**
     * True when the spawner may add one animal: below the cap, or at/over it
     * while no species is huntable yet (none above the Hunter's floor).
     *
     * <p>captain1 soak (2026-09-26): three cows, three pigs and three rabbits
     * around the Lodge are nine animals, over the cap of eight, and none of
     * them above the floor of four. The cap stopped every spawn and the floor
     * stopped every hunt: the Hunter stood "hunting grounds empty" for the
     * whole soak (3% work). Topping up past the cap only until one species
     * becomes huntable is self-limiting: each spawn raises the largest herd by
     * at most one, so at most {@code huntFloor + 1} extra animals ever appear.
     */
    public static boolean mayAdd(Map<?, Integer> liveCounts, int liveGame, int cap, int huntFloor) {
        if (cap <= 0) {
            return false;
        }
        if (liveGame < cap) {
            return true;
        }
        for (int count : liveCounts.values()) {
            if (count > huntFloor) {
                return false;
            }
        }
        return true;
    }

    /**
     * Chooses the species to add, or null when none is eligible here.
     *
     * @param eligible species the local biome naturally spawns (in biome order)
     * @param liveCounts live wild count per species in the hunting radius
     * @param varietyRoll uniform roll in [0,100)
     * @param randomIndex any non-negative int, used only for the variety pick
     */
    public static <T> T chooseSpecies(List<T> eligible, Map<T, Integer> liveCounts,
                                      int varietyRoll, int randomIndex) {
        if (eligible == null || eligible.isEmpty()) {
            return null;
        }
        if (varietyRoll < VARIETY_PERCENT) {
            return eligible.get(Math.floorMod(randomIndex, eligible.size()));
        }
        T best = eligible.get(0);
        int bestCount = liveCounts.getOrDefault(best, 0);
        for (T species : eligible) {
            int count = liveCounts.getOrDefault(species, 0);
            if (count > bestCount) {
                best = species;
                bestCount = count;
            }
        }
        return best;
    }

    /**
     * Horizontal exclusion: inside the village core (a disc around the
     * Hearth) or within {@code margin} blocks of a building's box.
     */
    public static boolean insideCore(int x, int z, int centerX, int centerZ, int coreRadius) {
        long dx = x - centerX;
        long dz = z - centerZ;
        return dx * dx + dz * dz <= (long) coreRadius * coreRadius;
    }

    public static boolean nearBox(int x, int y, int z, int minX, int minY, int minZ,
                                  int maxX, int maxY, int maxZ, int margin) {
        return x >= minX - margin && x <= maxX + margin
            && z >= minZ - margin && z <= maxZ + margin
            && y >= minY - margin && y <= maxY + margin;
    }

    /** True when no listed player position is within {@code minDistance}. */
    public static boolean outOfSight(double x, double y, double z,
                                     List<double[]> players, int minDistance) {
        double limit = (double) minDistance * minDistance;
        for (double[] p : players) {
            double dx = p[0] - x;
            double dy = p[1] - y;
            double dz = p[2] - z;
            if (dx * dx + dy * dy + dz * dz < limit) {
                return false;
            }
        }
        return true;
    }
}
