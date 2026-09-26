package com.hearthstead.settlement.warehouse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Pure warehouse level math: how many containers a building manages, which
 * ones, and what an old save is grandfathered to. No Minecraft types, so the
 * whole table is covered by plain JUnit.
 *
 * <p>Rule of the system (owner, 26 Sep): a building is never broken for
 * having too many containers. Anything beyond capacity is simply "not
 * managed (warehouse full)": workers ignore it, the UI shows it, and nothing
 * blocks readiness or the raid.
 */
public final class WarehouseLevels {

    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 5;
    /** Managed containers per warehouse level, index 0 = L1. */
    private static final int[] CAPACITY = {16, 32, 64, 128, 256};
    /**
     * Cap for every non-warehouse building (taverns, lodges, towers ...).
     * This is the old global cap, so their behaviour is unchanged apart from
     * the deterministic nearest-to-plaque choice when they overflow.
     */
    public static final int WORKPLACE_CAPACITY = 64;
    /** Highest level an old save is grandfathered to (L3 = the old 64 cap). */
    public static final int GRANDFATHER_MAX_LEVEL = 3;
    /** Largest capacity any building can have; bounds metrics and payloads. */
    public static final int ABSOLUTE_MAX = 256;

    /** Player marks on a single container. */
    public static final byte MARK_NONE = 0;
    /** Always managed first (counts toward capacity). */
    public static final byte MARK_PRIORITY = 1;
    /** Never managed, even when there is room. */
    public static final byte MARK_EXCLUDED = 2;

    private WarehouseLevels() {
    }

    public static int clampLevel(int level) {
        return Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, level));
    }

    /** Managed-container capacity of a warehouse level (clamped). */
    public static int capacity(int level) {
        return CAPACITY[clampLevel(level) - 1];
    }

    /** The next level, or -1 at the top. */
    public static int nextLevel(int level) {
        int clamped = clampLevel(level);
        return clamped >= MAX_LEVEL ? -1 : clamped + 1;
    }

    /** Smallest level whose capacity covers {@code containers} (max L5). */
    public static int levelCovering(int containers) {
        for (int level = MIN_LEVEL; level <= MAX_LEVEL; level++) {
            if (capacity(level) >= containers) {
                return level;
            }
        }
        return MAX_LEVEL;
    }

    /**
     * Old saves: at least the level that covers what they already have, but
     * never more than L3. A pre-level warehouse managed up to 64 containers,
     * so L3 means nobody shrinks; nobody is handed L4/L5 for free either.
     */
    public static int grandfatherLevel(int existingContainers) {
        return Math.min(GRANDFATHER_MAX_LEVEL,
            levelCovering(Math.max(0, existingContainers)));
    }

    /** Highest level recognised without any Logistics tree node. */
    public static final int BASE_TECH_LEVEL = 2;

    /**
     * Highest level the settlement's tree recognises: L2 for free, then one
     * more per consecutive owned gate node (Racks, Great, Royal).
     */
    public static int techMaxLevel(int consecutiveGatesOwned) {
        return clampLevel(BASE_TECH_LEVEL + Math.max(0, consecutiveGatesOwned));
    }

    /**
     * Effective level. The room's checklist says what was built, the tree
     * caps what is recognised, and an old save's grandfather floor is never
     * taken away.
     *
     * @param builtLevel       checklist level met by the room (plaque scan)
     * @param techMax          {@link #techMaxLevel}
     * @param grandfatherFloor 1 for new warehouses, up to L3 for old saves
     */
    public static int effectiveLevel(int builtLevel, int techMax,
                                     int grandfatherFloor) {
        return clampLevel(Math.max(grandfatherFloor,
            Math.min(clampLevel(builtLevel), clampLevel(techMax))));
    }

    /**
     * Deterministic managed subset.
     *
     * <p>Order of preference: player PRIORITY marks, then nearest to the
     * plaque (squared distance), ties broken by the old scan order (y, then
     * x, then z). EXCLUDED marks are never managed. The result is returned in
     * scan order (y, x, z) so callers that fill "the first container with
     * room" behave exactly as before whenever nothing overflows.
     *
     * @param candidates packed positions (see {@link #pack})
     * @param anchor     packed plaque position
     * @param priority   packed positions marked priority (may be empty)
     * @param excluded   packed positions marked excluded (may be empty)
     */
    public static long[] selectManaged(Collection<Long> candidates, long anchor,
                                       int capacity, Set<Long> priority,
                                       Set<Long> excluded) {
        if (candidates == null || candidates.isEmpty() || capacity <= 0) {
            return new long[0];
        }
        int ax = unpackX(anchor);
        int ay = unpackY(anchor);
        int az = unpackZ(anchor);
        List<Long> eligible = new ArrayList<>(candidates.size());
        for (Long pos : candidates) {
            if (pos != null && (excluded == null || !excluded.contains(pos))) {
                eligible.add(pos);
            }
        }
        eligible.sort((a, b) -> {
            boolean pa = priority != null && priority.contains(a);
            boolean pb = priority != null && priority.contains(b);
            if (pa != pb) {
                return pa ? -1 : 1;
            }
            int byDistance = Long.compare(distSqr(a, ax, ay, az),
                distSqr(b, ax, ay, az));
            return byDistance != 0 ? byDistance : compareScanOrder(a, b);
        });
        int take = Math.min(capacity, eligible.size());
        long[] managed = new long[take];
        for (int i = 0; i < take; i++) {
            managed[i] = eligible.get(i);
        }
        sortScanOrder(managed);
        return managed;
    }

    /** Sorts packed positions into the historical y, x, z scan order. */
    public static void sortScanOrder(long[] positions) {
        Long[] boxed = new Long[positions.length];
        for (int i = 0; i < positions.length; i++) {
            boxed[i] = positions[i];
        }
        Arrays.sort(boxed, WarehouseLevels::compareScanOrder);
        for (int i = 0; i < positions.length; i++) {
            positions[i] = boxed[i];
        }
    }

    public static int compareScanOrder(long a, long b) {
        int c = Integer.compare(unpackY(a), unpackY(b));
        if (c != 0) {
            return c;
        }
        c = Integer.compare(unpackX(a), unpackX(b));
        return c != 0 ? c : Integer.compare(unpackZ(a), unpackZ(b));
    }

    private static long distSqr(long pos, int ax, int ay, int az) {
        long dx = (long) unpackX(pos) - ax;
        long dy = (long) unpackY(pos) - ay;
        long dz = (long) unpackZ(pos) - az;
        return dx * dx + dy * dy + dz * dz;
    }

    // Same bit layout as net.minecraft.core.BlockPos#asLong (26/12/26), so a
    // packed value here is interchangeable with BlockPos.asLong()/of(long).
    private static final int PACKED_X_LENGTH = 26;
    private static final int PACKED_Z_LENGTH = 26;
    private static final int PACKED_Y_LENGTH = 12;
    private static final long PACKED_X_MASK = (1L << PACKED_X_LENGTH) - 1L;
    private static final long PACKED_Y_MASK = (1L << PACKED_Y_LENGTH) - 1L;
    private static final long PACKED_Z_MASK = (1L << PACKED_Z_LENGTH) - 1L;
    private static final int Z_OFFSET = PACKED_Y_LENGTH;
    private static final int X_OFFSET = PACKED_Y_LENGTH + PACKED_Z_LENGTH;

    public static long pack(int x, int y, int z) {
        long packed = 0L;
        packed |= ((long) x & PACKED_X_MASK) << X_OFFSET;
        packed |= ((long) y & PACKED_Y_MASK);
        packed |= ((long) z & PACKED_Z_MASK) << Z_OFFSET;
        return packed;
    }

    public static int unpackX(long packed) {
        return (int) (packed << 64 - X_OFFSET - PACKED_X_LENGTH >> 64 - PACKED_X_LENGTH);
    }

    public static int unpackY(long packed) {
        return (int) (packed << 64 - PACKED_Y_LENGTH >> 64 - PACKED_Y_LENGTH);
    }

    public static int unpackZ(long packed) {
        return (int) (packed << 64 - Z_OFFSET - PACKED_Z_LENGTH >> 64 - PACKED_Z_LENGTH);
    }
}
