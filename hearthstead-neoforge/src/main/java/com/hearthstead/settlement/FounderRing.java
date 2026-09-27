package com.hearthstead.settlement;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Where the four founders appear (QA U5): on a ring 3 to 5 blocks out from
 * the Banner, never within 3 blocks of a player, so nobody gets boxed in
 * against the Banner. Pure offsets; {@code SettlementManager} checks the
 * actual ground, fluids and other entities.
 */
public final class FounderRing {
    public static final double MIN_RADIUS = 3.0D;
    public static final double MAX_RADIUS = 5.0D;
    public static final double PLAYER_CLEARANCE = 3.0D;

    private FounderRing() {
    }

    /** Whether offset (dx, dz) from the Banner is on the ring and clear of every player offset. */
    public static boolean acceptable(int dx, int dz, List<int[]> playerOffsets) {
        double r = Math.hypot(dx, dz);
        if (r < MIN_RADIUS || r > MAX_RADIUS + 0.5D) {
            return false;
        }
        for (int[] p : playerOffsets) {
            if (Math.hypot(dx - p[0], dz - p[1]) < PLAYER_CLEARANCE) {
                return false;
            }
        }
        return true;
    }

    /** Every ring cell (3..5 blocks out), shuffled with {@code seed} so founders spread around. */
    public static List<int[]> candidates(long seed) {
        List<int[]> out = new ArrayList<>();
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                double r = Math.hypot(dx, dz);
                if (r >= MIN_RADIUS && r <= MAX_RADIUS + 0.5D) {
                    out.add(new int[]{dx, dz});
                }
            }
        }
        java.util.Collections.shuffle(out, new Random(seed));
        return out;
    }
}
