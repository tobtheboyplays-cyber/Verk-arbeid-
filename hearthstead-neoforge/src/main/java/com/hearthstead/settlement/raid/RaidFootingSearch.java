package com.hearthstead.settlement.raid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Where a raider may try to form up, in the order it tries. Pure geometry:
 * no world access, so the order and bounds are unit tested
 * ({@code RaidFootingSearchTest}); {@link RaidDirector} walks these
 * candidates against real, loaded terrain.
 *
 * <p>Found on the owner's real server world (Elmfield, 26 Sep): a lake
 * wraps half the town, and the land that is there climbs 15-26 blocks above
 * the Banner. The old search tried one bearing per follower and only +-12
 * blocks of height around the Banner's own Y, so the authored five-raider
 * first raid failed on 55 of 72 approach bearings, a three-raider recurring
 * band on 16 of 72 -- and because a held plan keeps its bearing, a failing
 * raid failed on every retry. The order below keeps the warned direction
 * whenever the ground allows, and otherwise still brings the band.
 *
 * <p>Every candidate distance is strictly past the claim edge
 * ({@code claimRadius + EDGE_MARGIN} at the nearest), so nothing here can
 * place a raider inside the claim.
 */
public final class RaidFootingSearch {

    /** One column to try: a compass bearing and a distance from the centre. */
    public record Candidate(float bearing, int distance) {
    }

    /** Bearing step of the captain's all-round sweep, degrees. */
    public static final float SWEEP_STEP = 12.0F;
    /** Sweep steps each side of the warned bearing: 15 x 12 = the full circle. */
    public static final int SWEEP_STEPS = 15;
    /** Nearest ring, just past the claim edge: the last land before the claim. */
    public static final int EDGE_MARGIN = 2;
    /** Farthest ring past the band's depth, only when nothing nearer stands. */
    public static final int FAR_REACH = 40;
    /**
     * How far above or below the Banner's own height a form-up surface may
     * be. Real terrain 56-88 blocks out is often 15-30 blocks off the
     * Banner; a cliff face or a peak further off is not an approach.
     */
    public static final int SURFACE_REACH = 40;
    /** How far from its captain a follower may gather when its own ray has no ground. */
    public static final int GATHER_RADIUS = 4;
    /** A gathering follower's ground may differ this much from the captain's. */
    public static final int GATHER_STEP_HEIGHT = 3;

    private static final List<int[]> GATHER_OFFSETS = buildGatherOffsets();

    private RaidFootingSearch() {
    }

    /** Nearest ordinary band depth: the claim edge plus the band margin. */
    public static int bandMin(int claimRadius) {
        return Math.max(0, claimRadius) + RaidDirector.SPAWN_EDGE_MARGIN_MIN;
    }

    /** Farthest ordinary band depth. */
    public static int bandMax(int claimRadius) {
        return Math.max(0, claimRadius) + RaidDirector.SPAWN_EDGE_MARGIN_MAX;
    }

    /**
     * A follower keeps the warned front: its own column, then its own
     * bearing across the band's depth, then the same bearing a little
     * further out. Gathering on the captain (see {@link #gatherOffsets}) is
     * the caller's next step, once the captain's ground is known.
     */
    public static List<Candidate> follower(float bearing, int claimRadius,
                                           int direct) {
        Set<Candidate> out = new LinkedHashSet<>();
        float b = normalize(bearing);
        out.add(new Candidate(b, direct));
        addDepths(out, b, bandMin(claimRadius), bandMax(claimRadius), 2);
        addDepths(out, b, bandMax(claimRadius) + 2,
            bandMax(claimRadius) + RaidDirector.CAPTAIN_EXTRA_REACH, 2);
        return List.copyOf(out);
    }

    /**
     * The captain must find ground if any exists: the warned bearing across
     * the band and the extra reach, then every other bearing nearest-first
     * (the full circle), then the thin edge ring just outside the claim,
     * then a farther ring. Bounded: well under a thousand columns.
     */
    public static List<Candidate> captain(float bearing, int claimRadius,
                                          int direct) {
        Set<Candidate> out = new LinkedHashSet<>();
        int min = bandMin(claimRadius);
        int max = bandMax(claimRadius);
        int extended = max + RaidDirector.CAPTAIN_EXTRA_REACH;
        List<Float> bearings = sweepBearings(bearing);
        out.add(new Candidate(normalize(bearing), direct));
        for (float b : bearings) {
            addDepths(out, b, min, extended, 2);
        }
        int edge = Math.max(0, claimRadius) + EDGE_MARGIN;
        for (float b : bearings) {
            addDepths(out, b, edge, min - 1, 1);
        }
        for (float b : bearings) {
            addDepths(out, b, extended + 4, max + FAR_REACH, 4);
        }
        return List.copyOf(out);
    }

    /** The warned bearing, then alternately either side, 12 degrees apart. */
    public static List<Float> sweepBearings(float bearing) {
        List<Float> out = new ArrayList<>();
        out.add(normalize(bearing));
        for (int k = 1; k <= SWEEP_STEPS; k++) {
            out.add(normalize(bearing + k * SWEEP_STEP));
            if (k * SWEEP_STEP < 180.0F) {
                out.add(normalize(bearing - k * SWEEP_STEP));
            }
        }
        return out;
    }

    /**
     * Horizontal offsets around the captain, nearest first. A follower
     * starts at a slot derived from its index so gathering raiders spread
     * around their leader instead of stacking on one block.
     */
    public static List<int[]> gatherOffsets(int followerIndex) {
        int n = GATHER_OFFSETS.size();
        int start = Math.floorMod(followerIndex * 5, n);
        List<int[]> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int[] o = GATHER_OFFSETS.get((start + i) % n);
            out.add(new int[] {o[0], o[1]});
        }
        return out;
    }

    /** Whether a surface at {@code surfaceY} is a plausible approach height. */
    public static boolean withinReach(int surfaceY, int anchorY, int reach) {
        return Math.abs((long) surfaceY - anchorY) <= reach;
    }

    private static void addDepths(Set<Candidate> out, float bearing,
                                  int from, int to, int step) {
        for (int d = from; d <= to; d += step) {
            out.add(new Candidate(bearing, d));
        }
    }

    static float normalize(float degrees) {
        float n = degrees % 360.0F;
        if (n < 0.0F) {
            n += 360.0F;
        }
        // Round away float noise so equal bearings dedupe in the set.
        return Math.round(n * 1000.0F) / 1000.0F;
    }

    private static List<int[]> buildGatherOffsets() {
        List<int[]> offsets = new ArrayList<>();
        for (int dx = -GATHER_RADIUS; dx <= GATHER_RADIUS; dx++) {
            for (int dz = -GATHER_RADIUS; dz <= GATHER_RADIUS; dz++) {
                int sq = dx * dx + dz * dz;
                if (sq > 0 && sq <= GATHER_RADIUS * GATHER_RADIUS) {
                    offsets.add(new int[] {dx, dz});
                }
            }
        }
        offsets.sort((a, b) -> {
            int c = Integer.compare(a[0] * a[0] + a[1] * a[1],
                b[0] * b[0] + b[1] * b[1]);
            if (c != 0) {
                return c;
            }
            c = Integer.compare(a[0], b[0]);
            return c != 0 ? c : Integer.compare(a[1], b[1]);
        });
        return Collections.unmodifiableList(offsets);
    }
}
