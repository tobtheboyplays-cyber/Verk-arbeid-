package com.hearthstead.settlement.guard;

import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Pure formation geometry shared by the client preview (ground dots) and the
 * server slot assignment, so what the player sees is exactly what the server
 * orders. No level access here: callers snap Y and judge standability.
 *
 * <p>Facing is quantised to eight octants so every slot is a distinct block:
 * octant 0 = south (+Z, Minecraft yaw 0), then clockwise seen from above in
 * yaw order: 1 = south-west, 2 = west, 3 = north-west, 4 = north,
 * 5 = north-east, 6 = east, 7 = south-east.
 */
public final class FormationMath {
    public static final int MIN_WIDTH = 1;
    public static final int MAX_WIDTH = 16;
    public static final int MAX_SOLDIERS = 48;
    /** Knights stand shoulder to shoulder; archers keep a loose line. */
    public static final int KNIGHT_SPACING = 1;
    public static final int ARCHER_SPACING = 2;
    public static final int KNIGHT_RANK_GAP = 2;
    public static final int ARCHER_RANK_GAP = 2;
    public static final int DEFAULT_KNIGHT_WIDTH = 6;
    public static final int DEFAULT_ARCHER_WIDTH = 8;

    private static final int[][] FORWARD = {
        {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}, {1, 0}, {1, 1}
    };

    /** Quantises a Minecraft yaw (degrees) to one of eight octants. */
    public static int octant(float yawDegrees) {
        double wrapped = ((yawDegrees % 360.0) + 360.0) % 360.0;
        return (int) Math.round(wrapped / 45.0) & 7;
    }

    public static boolean validOctant(int octant) {
        return octant >= 0 && octant < 8;
    }

    public static int forwardX(int octant) { return FORWARD[octant & 7][0]; }
    public static int forwardZ(int octant) { return FORWARD[octant & 7][1]; }
    /** The commander's right hand when looking along the octant. */
    public static int rightX(int octant) { return -FORWARD[octant & 7][1]; }
    public static int rightZ(int octant) { return FORWARD[octant & 7][0]; }

    public static int clampWidth(int width, int soldiers) {
        int upper = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, Math.max(1, soldiers)));
        return Math.max(MIN_WIDTH, Math.min(upper, width));
    }

    public static int ranks(int soldiers, int width) {
        if (soldiers <= 0) return 0;
        int w = clampWidth(width, soldiers);
        return (soldiers + w - 1) / w;
    }

    public static int defaultWidth(boolean archers, int soldiers) {
        return clampWidth(archers ? DEFAULT_ARCHER_WIDTH : DEFAULT_KNIGHT_WIDTH, soldiers);
    }

    /** Role default: spearmen form a two-deep pike line, others their style's width. */
    public static int defaultWidth(FieldOrderRules.Group role, int soldiers) {
        if (role == FieldOrderRules.Group.SPEARMEN) return clampWidth((soldiers + 1) / 2, soldiers);
        return defaultWidth(role.ranged(), soldiers);
    }

    /** Blocks between neighbours in a rank. */
    public static int spacing(FieldOrderRules.Group role) {
        return role.ranged() || role.support() ? ARCHER_SPACING : KNIGHT_SPACING;
    }

    /** Blocks between ranks; the pike line's back rank stands right behind the front. */
    public static int rankGap(FieldOrderRules.Group role) {
        if (role == FieldOrderRules.Group.SPEARMEN) return 1;
        return role.ranged() || role.support() ? ARCHER_RANK_GAP : KNIGHT_RANK_GAP;
    }

    /**
     * Depth of each role behind the aimed point when the whole army gets one
     * order: roles stack front to back in {@link FieldOrderRules.Group#ROLES}
     * order (knights, spearmen, longswordsmen, archers, mages, healers), each
     * block as deep as its own ranks plus one free row. Roles with no soldier
     * in earshot take no space. Identical on client (preview) and server.
     */
    public static java.util.Map<FieldOrderRules.Group, Integer> layerDepths(
            java.util.Map<FieldOrderRules.Group, Integer> counts) {
        java.util.Map<FieldOrderRules.Group, Integer> depths = new java.util.EnumMap<>(FieldOrderRules.Group.class);
        int depth = 0;
        for (FieldOrderRules.Group role : FieldOrderRules.Group.ROLES) {
            int count = counts.getOrDefault(role, 0);
            if (count <= 0) continue;
            depths.put(role, depth);
            int width = defaultWidth(role, count);
            depth += ranks(count, width) * rankGap(role) + 1;
        }
        return depths;
    }

    /** {@code depth} octant steps behind {@code center} (away from the facing). */
    public static BlockPos behind(BlockPos center, int octant, int depth) {
        return center.offset(-forwardX(octant) * depth, 0, -forwardZ(octant) * depth);
    }

    /** One slot in formation-local terms plus its world column. */
    public record Slot(int rank, int lateral, BlockPos pos) {
    }

    /**
     * Line (or block, with more ranks) centred on {@code center}. Rank 0 is
     * the front line through the aimed point; later ranks stand behind it,
     * away from the facing direction. Y is left at {@code center.getY()}.
     * Returned in column-major order (lateral, then rank) which is the order
     * {@link #assign} pairs soldiers with.
     */
    public static List<Slot> line(BlockPos center, int octant, int soldiers,
                                  int width, int spacing, int rankGap) {
        List<Slot> slots = new ArrayList<>();
        if (center == null || soldiers <= 0) return slots;
        int count = Math.min(soldiers, MAX_SOLDIERS);
        int w = clampWidth(width, count);
        int rx = rightX(octant), rz = rightZ(octant);
        int fx = forwardX(octant), fz = forwardZ(octant);
        int placed = 0;
        for (int rank = 0; placed < count; rank++) {
            int inRow = Math.min(w, count - placed);
            int firstLateral = -((inRow - 1) / 2);
            for (int c = 0; c < inRow; c++) {
                int lateral = firstLateral + c;
                int x = center.getX() + rx * lateral * spacing - fx * rank * rankGap;
                int z = center.getZ() + rz * lateral * spacing - fz * rank * rankGap;
                slots.add(new Slot(rank, lateral, new BlockPos(x, center.getY(), z)));
                placed++;
            }
        }
        slots.sort(Comparator.comparingInt(Slot::lateral).thenComparingInt(Slot::rank));
        return slots;
    }

    /**
     * Bodyguard ring for "follow me": knights on a tight arc in front and to
     * the sides of the commander, archers on a wider arc behind.
     */
    public static List<Slot> escort(BlockPos commander, int octant, int soldiers,
                                    boolean archers) {
        List<Slot> slots = new ArrayList<>();
        if (commander == null || soldiers <= 0) return slots;
        int count = Math.min(soldiers, MAX_SOLDIERS);
        double baseYaw = octant * 45.0;
        double radius = archers ? 6.0 : 2.5;
        // Knights: arc from -120 to +120 degrees around facing (front-heavy).
        // Archers: arc from 120 to 240 degrees (behind the commander).
        double from = archers ? 135.0 : -110.0;
        double to = archers ? 225.0 : 110.0;
        Set<BlockPos> used = new HashSet<>();
        used.add(commander);
        for (int i = 0; i < count; i++) {
            int ring = i / 8;
            int inRing = Math.min(8, count - ring * 8);
            int index = i % 8;
            double t = inRing == 1 ? 0.5 : index / (double) (inRing - 1);
            double angle = Math.toRadians(baseYaw + from + (to - from) * t);
            double r = radius + ring * 1.5;
            int x = commander.getX() + (int) Math.round(-Math.sin(angle) * r);
            int z = commander.getZ() + (int) Math.round(Math.cos(angle) * r);
            BlockPos pos = new BlockPos(x, commander.getY(), z);
            int bump = 0;
            while (!used.add(pos) && bump < 8) {
                bump++;
                pos = pos.offset(rightX(octant), 0, rightZ(octant));
            }
            slots.add(new Slot(ring, i, pos));
        }
        return slots;
    }

    /**
     * Breadth-first search over a raised surface (wall walk, tower floor) from
     * the aimed cell: returns up to {@code count} distinct standable cells,
     * nearest first, never farther than {@code radius} blocks horizontally and
     * never more than one block up or down per step. Cells not standable are
     * never returned, so a tower that is full yields fewer slots than asked.
     */
    public static List<BlockPos> surface(BlockPos start, int count, int radius,
                                         Predicate<BlockPos> standable) {
        List<BlockPos> result = new ArrayList<>();
        if (start == null || count <= 0 || standable == null) return result;
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        queue.add(start);
        seen.add(start);
        int budget = 512;
        while (!queue.isEmpty() && result.size() < count && budget-- > 0) {
            BlockPos cell = queue.poll();
            if (!standable.test(cell)) {
                if (cell.equals(start)) {
                    // Aimed at a non-standable cell: still search around it.
                } else {
                    continue;
                }
            } else {
                result.add(cell);
            }
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                for (int dy = 0; dy >= -1; dy--) {
                    // Same level first, then one step down; one step up last.
                    BlockPos next = cell.offset(d[0], dy, d[1]);
                    if (Math.abs(next.getX() - start.getX()) > radius
                        || Math.abs(next.getZ() - start.getZ()) > radius
                        || Math.abs(next.getY() - start.getY()) > 2
                        || !seen.add(next)) continue;
                    queue.add(next);
                }
                BlockPos up = cell.offset(d[0], 1, d[1]);
                if (Math.abs(up.getX() - start.getX()) <= radius
                    && Math.abs(up.getZ() - start.getZ()) <= radius
                    && Math.abs(up.getY() - start.getY()) <= 2 && seen.add(up)) {
                    queue.add(up);
                }
            }
        }
        return result;
    }

    /** A soldier's identity and current block, for slot pairing. */
    public record Soldier(UUID id, BlockPos pos) {
    }

    /**
     * Pairs soldiers with slots so that lines do not cross: soldiers ordered by
     * their position across the formation (projection on the right vector,
     * ties by distance to the front, then UUID) take slots in the order
     * {@link #line} returns them. Extra soldiers (more soldiers than slots)
     * are left unassigned and appear as {@code null} slot indices.
     *
     * @return for each soldier in the input order, the index of its slot, or -1
     */
    public static int[] assign(List<Soldier> soldiers, List<BlockPos> slots,
                               BlockPos center, int octant) {
        int[] result = new int[soldiers.size()];
        java.util.Arrays.fill(result, -1);
        if (soldiers.isEmpty() || slots.isEmpty()) return result;
        int rx = rightX(octant), rz = rightZ(octant);
        int fx = forwardX(octant), fz = forwardZ(octant);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < soldiers.size(); i++) order.add(i);
        order.sort(Comparator
            .<Integer>comparingInt(i -> lateralOf(soldiers.get(i).pos(), center, rx, rz))
            .thenComparingInt(i -> -forwardOf(soldiers.get(i).pos(), center, fx, fz))
            .thenComparing(i -> soldiers.get(i).id()));
        // Only the nearest soldiers fill a smaller slot list: keep the
        // closest N by distance, then pair those in lateral order.
        if (soldiers.size() > slots.size()) {
            List<Integer> nearest = new ArrayList<>(order);
            nearest.sort(Comparator.<Integer>comparingDouble(
                i -> soldiers.get(i).pos().distSqr(center))
                .thenComparing(i -> soldiers.get(i).id()));
            Set<Integer> keep = new HashSet<>(nearest.subList(0, slots.size()));
            order.removeIf(i -> !keep.contains(i));
        }
        for (int k = 0; k < order.size(); k++) {
            result[order.get(k)] = k;
        }
        return result;
    }

    private static int lateralOf(BlockPos pos, BlockPos center, int rx, int rz) {
        return (pos.getX() - center.getX()) * rx + (pos.getZ() - center.getZ()) * rz;
    }

    private static int forwardOf(BlockPos pos, BlockPos center, int fx, int fz) {
        return (pos.getX() - center.getX()) * fx + (pos.getZ() - center.getZ()) * fz;
    }

    private FormationMath() {
    }
}
