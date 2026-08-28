package com.hearthstead.entity.ai;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/**
 * Budgeted, resumable block scanning around a settlement center.
 *
 * A shared offset table (sorted by horizontal distance, max radius 48,
 * vertical band -4..+4) is walked with a per-goal cursor: each scan call
 * inspects at most {@code budget} positions and remembers where it stopped,
 * so no goal ever streams the whole settlement volume in one tick.
 */
public class WorkScanner {
    private static final int MAX_RADIUS = 48;
    private static final int Y_BAND = 4;
    private static int[] offsets;

    private int cursor;

    private static synchronized int[] offsetTable() {
        if (offsets == null) {
            int side = MAX_RADIUS * 2 + 1;
            int height = Y_BAND * 2 + 1;
            int[] table = new int[side * side * height];
            int n = 0;
            for (int dx = -MAX_RADIUS; dx <= MAX_RADIUS; dx++) {
                for (int dz = -MAX_RADIUS; dz <= MAX_RADIUS; dz++) {
                    for (int dy = -Y_BAND; dy <= Y_BAND; dy++) {
                        table[n++] = pack(dx, dy, dz);
                    }
                }
            }
            Integer[] boxed = new Integer[table.length];
            for (int i = 0; i < table.length; i++) {
                boxed[i] = table[i];
            }
            Arrays.sort(boxed, (a, b) -> {
                int da = horizontalDistSqr(a);
                int db = horizontalDistSqr(b);
                if (da != db) {
                    return Integer.compare(da, db);
                }
                return Integer.compare(Math.abs(unpackY(a)), Math.abs(unpackY(b)));
            });
            for (int i = 0; i < table.length; i++) {
                table[i] = boxed[i];
            }
            offsets = table;
        }
        return offsets;
    }

    private static int pack(int dx, int dy, int dz) {
        return ((dx + MAX_RADIUS) * 97 + (dz + MAX_RADIUS)) * 9 + (dy + Y_BAND);
    }

    private static int unpackX(int packed) {
        return packed / 9 / 97 - MAX_RADIUS;
    }

    private static int unpackZ(int packed) {
        return packed / 9 % 97 - MAX_RADIUS;
    }

    private static int unpackY(int packed) {
        return packed % 9 - Y_BAND;
    }

    private static int horizontalDistSqr(int packed) {
        int dx = unpackX(packed);
        int dz = unpackZ(packed);
        return dx * dx + dz * dz;
    }

    /**
     * Scans up to {@code budget} positions inside {@code radius} around
     * {@code center}, resuming from the previous cursor. Collects at most
     * {@code maxResults} matches. Wraps around the table when exhausted.
     */
    public List<BlockPos> scan(BlockPos center, int radius, int budget, int maxResults,
                               Predicate<BlockPos> predicate) {
        int[] table = offsetTable();
        int radiusSqr = radius * radius;
        List<BlockPos> results = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int examined = 0; examined < budget && results.size() < maxResults; examined++) {
            if (cursor >= table.length || horizontalDistSqr(table[cursor]) > radiusSqr) {
                cursor = 0;
                if (examined > 0) {
                    break; // full wrap this scan; try again next cooldown
                }
            }
            int packed = table[cursor++];
            pos.set(center.getX() + unpackX(packed), center.getY() + unpackY(packed),
                center.getZ() + unpackZ(packed));
            if (predicate.test(pos)) {
                results.add(pos.immutable());
            }
        }
        return results;
    }
}
