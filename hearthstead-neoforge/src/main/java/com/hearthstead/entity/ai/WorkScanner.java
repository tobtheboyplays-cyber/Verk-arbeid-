package com.hearthstead.entity.ai;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
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

    private static int[] columns;

    private int cursor;
    private int columnCursor;
    private int boxColumnCursor;
    private int boxCursor;
    private int nearestBoxCursor;
    private BlockPos lastBoxColumnMin;
    private BlockPos lastBoxColumnMax;
    private BlockPos lastBoxMin;
    private BlockPos lastBoxMax;
    private BlockPos lastNearestBoxMin;
    private BlockPos lastNearestBoxMax;
    private BlockPos lastNearestBoxOrigin;
    private long[] nearestBoxOrder = new long[0];

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

    /**
     * Scans horizontal COLUMNS rather than a volume.
     *
     * <p>Why this exists (KF-018): the volume scan above walks
     * 97&nbsp;&times;&nbsp;97&nbsp;&times;&nbsp;9 = 84&nbsp;681 positions, so
     * at 512 a call and one call every four seconds a single sweep of the
     * settlement takes about fourteen minutes. A lumberjack in a thin wood
     * therefore stands idle for a quarter of an hour before noticing the tree
     * behind him. Worse, the vertical band is anchored to the hearth's own Y,
     * so a tree four blocks up a slope is invisible <i>forever</i>, not merely
     * late.
     *
     * <p>Anything that grows out of the ground is found much more cheaply by
     * asking each column what is on top of it. 9&nbsp;409 columns sweep in
     * about twelve calls instead of a hundred and sixty-five, and elevation
     * stops mattering because the surface is wherever the surface is.
     *
     * <p>{@code finder} receives the column at ground level and returns the
     * work position it found there, or null. Callers that need to look down a
     * trunk do it inside the finder, where the cost is paid only on the few
     * columns that have a trunk in them.
     */
    public List<BlockPos> scanColumns(BlockPos center, int radius, int budget, int maxResults,
                                      Function<BlockPos, BlockPos> finder) {
        int[] table = columnTable();
        int radiusSqr = radius * radius;
        // One physical tree can be returned by more than one surveyed column
        // (wide trunks and custom trees are the common case). Counting those
        // duplicates against maxResults made a second lumberer spend an
        // entire batch rediscovering the first worker's already-claimed tree.
        // Preserve discovery order while charging the result cap only for a
        // distinct work position.
        Set<BlockPos> results = new LinkedHashSet<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int examined = 0; examined < budget && results.size() < maxResults; examined++) {
            if (columnCursor >= table.length
                || columnDistSqr(table[columnCursor]) > radiusSqr) {
                columnCursor = 0;
                if (examined > 0) {
                    break; // full wrap this scan; try again next cooldown
                }
            }
            int packed = table[columnCursor++];
            pos.set(center.getX() + columnX(packed), center.getY(),
                center.getZ() + columnZ(packed));
            BlockPos found = finder.apply(pos);
            if (found != null) {
                results.add(found.immutable());
            }
        }
        return List.copyOf(results);
    }

    /**
     * Resumable exact rectangular column scan. It never reads outside the
     * supplied inclusive X/Z bounds and does not acquire chunks; the finder
     * remains responsible for a cheap loaded check before any height lookup.
     */
    public List<BlockPos> scanBoxColumns(BlockPos min, BlockPos max, int y,
                                         int budget, int maxResults,
                                         Function<BlockPos, BlockPos> finder) {
        if (min == null || max == null || min.getX() > max.getX()
            || min.getZ() > max.getZ() || budget <= 0 || maxResults <= 0) {
            return List.of();
        }
        BlockPos normalizedMin = new BlockPos(min.getX(), y, min.getZ());
        BlockPos normalizedMax = new BlockPos(max.getX(), y, max.getZ());
        if (!normalizedMin.equals(lastBoxColumnMin)
            || !normalizedMax.equals(lastBoxColumnMax)) {
            boxColumnCursor = 0;
            lastBoxColumnMin = normalizedMin;
            lastBoxColumnMax = normalizedMax;
        }
        int sizeX = max.getX() - min.getX() + 1;
        int sizeZ = max.getZ() - min.getZ() + 1;
        int total = sizeX * sizeZ;
        Set<BlockPos> results = new LinkedHashSet<>();
        for (int examined = 0; examined < Math.min(budget, total)
                && results.size() < maxResults; examined++) {
            if (boxColumnCursor >= total) {
                boxColumnCursor = 0;
                if (examined > 0) {
                    break;
                }
            }
            int index = boxColumnCursor++;
            int x = min.getX() + index / sizeZ;
            int z = min.getZ() + index % sizeZ;
            BlockPos found = finder.apply(new BlockPos(x, y, z));
            if (found != null) {
                results.add(found.immutable());
            }
        }
        return List.copyOf(results);
    }

    /**
     * Resumable exact inclusive 3D scan for a confirmed Work Zone. At most
     * {@code budget} positions are offered per call, even for the maximum
     * legal zone, and no position outside the bounds is constructed.
     */
    public List<BlockPos> scanBox(BlockPos min, BlockPos max, int budget,
                                  int maxResults,
                                  Predicate<BlockPos> predicate) {
        if (min == null || max == null || min.getX() > max.getX()
            || min.getY() > max.getY() || min.getZ() > max.getZ()
            || budget <= 0 || maxResults <= 0) {
            return List.of();
        }
        if (!min.equals(lastBoxMin) || !max.equals(lastBoxMax)) {
            boxCursor = 0;
            lastBoxMin = min.immutable();
            lastBoxMax = max.immutable();
        }
        int sizeY = max.getY() - min.getY() + 1;
        int sizeZ = max.getZ() - min.getZ() + 1;
        long totalLong = (long) (max.getX() - min.getX() + 1)
            * sizeY * sizeZ;
        if (totalLong <= 0L || totalLong > Integer.MAX_VALUE) {
            return List.of();
        }
        int total = (int) totalLong;
        List<BlockPos> results = new ArrayList<>();
        for (int examined = 0; examined < Math.min(budget, total)
                && results.size() < maxResults; examined++) {
            if (boxCursor >= total) {
                boxCursor = 0;
                if (examined > 0) {
                    break;
                }
            }
            int index = boxCursor++;
            int yz = sizeY * sizeZ;
            int x = min.getX() + index / yz;
            int remainder = index % yz;
            int y = min.getY() + remainder / sizeZ;
            int z = min.getZ() + remainder % sizeZ;
            BlockPos candidate = new BlockPos(x, y, z);
            if (predicate.test(candidate)) {
                results.add(candidate);
            }
        }
        return List.copyOf(results);
    }

    /**
     * Visits an exact inclusive 3D box in deterministic nearest-first order.
     *
     * <p>The order is built only when the bounds or stable origin change,
     * then resumed across calls. Unlike a result-capped predicate scan, the
     * visitor always receives the full bounded batch. That lets callers keep
     * small priority-specific queues without a dense low-priority block type
     * hiding a rarer high-priority target later in the same batch.
     *
     * <p>The origin is clamped into the box before ordering. No position
     * outside the supplied bounds is constructed or visited.
     */
    public void visitBoxNearest(BlockPos min, BlockPos max, BlockPos origin,
                                int budget, Consumer<BlockPos> visitor) {
        if (min == null || max == null || origin == null || visitor == null
            || min.getX() > max.getX() || min.getY() > max.getY()
            || min.getZ() > max.getZ() || budget <= 0) {
            return;
        }
        BlockPos boundedOrigin = new BlockPos(
            Math.max(min.getX(), Math.min(max.getX(), origin.getX())),
            Math.max(min.getY(), Math.min(max.getY(), origin.getY())),
            Math.max(min.getZ(), Math.min(max.getZ(), origin.getZ())));
        int sizeY = max.getY() - min.getY() + 1;
        int sizeZ = max.getZ() - min.getZ() + 1;
        long totalLong = (long) (max.getX() - min.getX() + 1)
            * sizeY * sizeZ;
        if (totalLong <= 0L || totalLong > Integer.MAX_VALUE) {
            return;
        }
        int total = (int) totalLong;
        if (!min.equals(lastNearestBoxMin) || !max.equals(lastNearestBoxMax)
            || !boundedOrigin.equals(lastNearestBoxOrigin)) {
            nearestBoxCursor = 0;
            lastNearestBoxMin = min.immutable();
            lastNearestBoxMax = max.immutable();
            lastNearestBoxOrigin = boundedOrigin;
            nearestBoxOrder = nearestBoxOrder(min, sizeY, sizeZ, total,
                boundedOrigin);
        }
        for (int examined = 0; examined < Math.min(budget, total); examined++) {
            if (nearestBoxCursor >= total) {
                nearestBoxCursor = 0;
                if (examined > 0) {
                    break;
                }
            }
            int index = (int) nearestBoxOrder[nearestBoxCursor++];
            int yz = sizeY * sizeZ;
            int x = min.getX() + index / yz;
            int remainder = index % yz;
            int y = min.getY() + remainder / sizeZ;
            int z = min.getZ() + remainder % sizeZ;
            visitor.accept(new BlockPos(x, y, z));
        }
    }

    private static long[] nearestBoxOrder(BlockPos min, int sizeY, int sizeZ,
                                          int total, BlockPos origin) {
        long[] order = new long[total];
        int yz = sizeY * sizeZ;
        for (int index = 0; index < total; index++) {
            int x = min.getX() + index / yz;
            int remainder = index % yz;
            int y = min.getY() + remainder / sizeZ;
            int z = min.getZ() + remainder % sizeZ;
            long dx = x - (long) origin.getX();
            long dy = y - (long) origin.getY();
            long dz = z - (long) origin.getZ();
            long distance = dx * dx + dy * dy + dz * dz;
            order[index] = (distance << 32) | (index & 0xffffffffL);
        }
        Arrays.sort(order);
        return order;
    }

    private static synchronized int[] columnTable() {
        if (columns == null) {
            int side = MAX_RADIUS * 2 + 1;
            int[] table = new int[side * side];
            int n = 0;
            for (int dx = -MAX_RADIUS; dx <= MAX_RADIUS; dx++) {
                for (int dz = -MAX_RADIUS; dz <= MAX_RADIUS; dz++) {
                    table[n++] = (dx + MAX_RADIUS) * 97 + (dz + MAX_RADIUS);
                }
            }
            Integer[] boxed = new Integer[table.length];
            for (int i = 0; i < table.length; i++) {
                boxed[i] = table[i];
            }
            Arrays.sort(boxed, (a, b) -> Integer.compare(columnDistSqr(a), columnDistSqr(b)));
            for (int i = 0; i < table.length; i++) {
                table[i] = boxed[i];
            }
            columns = table;
        }
        return columns;
    }

    private static int columnX(int packed) {
        return packed / 97 - MAX_RADIUS;
    }

    private static int columnZ(int packed) {
        return packed % 97 - MAX_RADIUS;
    }

    private static int columnDistSqr(int packed) {
        int dx = columnX(packed);
        int dz = columnZ(packed);
        return dx * dx + dz * dz;
    }
}
