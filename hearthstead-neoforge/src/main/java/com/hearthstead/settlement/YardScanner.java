package com.hearthstead.settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The WORK YARD survey (owner, 26 Sep: "the buildings must be unique to the
 * job -- they don't even need a roof, like a lumberjack camp").
 *
 * <p>An open-air trade -- a woodcutter's camp, a masons' yard, an open forge,
 * a mine headframe -- is a bounded LOT, not a roofed room. The lot is found
 * the way a player reads it: stand where the plaque looks, and walk at foot
 * level until something stops you (a fence, a wall, the side of a hut). That
 * 2-D fill is the yard. A fence gate or a door in its boundary is its way in.
 *
 * <p>It returns an ordinary {@link RoomScanner.Result}, so every existing
 * requirement measures a yard exactly like a room:
 * <ul>
 *   <li>{@code volume} = the yard's area in cells (floor_space);</li>
 *   <li>{@code doors} = distinct gates and doors on the boundary that open
 *   onto standable ground (a walled-up gate is not an entrance);</li>
 *   <li>{@code blockCounts}, {@code lights}, {@code beds} are tallied in every
 *   yard and boundary column from {@value #DEPTH} below to {@value #HEAD}
 *   above foot level, so a shaft's ladders, a crane's load and the lanterns
 *   hung in a lean-to all count;</li>
 *   <li>{@code enclosed} = the fill stayed bounded (within {@value #MAX_EXTENT}
 *   of the seed and at most {@value #MAX_AREA} cells) AND at least
 *   {@value #MIN_COVERED} yard cells are covered (the tool shelter); a yard
 *   is never "open to the sky" by definition, so {@code skyLeak} is false.
 *   Water or lava at foot level is an unsafe edge, never a boundary: a lot
 *   ringed by a moat or lava is unbounded.</li>
 * </ul>
 *
 * <p>Only {@link com.hearthstead.building.BuildingType.ValidationMode#YARD_OR_ROOM}
 * types are surveyed this way, and only after every room candidate failed:
 * a building that already registers as a room keeps registering as one.
 * Every scan is bounded like RoomScanner's.
 */
public final class YardScanner {

    public static final int MAX_AREA = RoomScanner.MAX_HOME_VOLUME;
    public static final int MAX_EXTENT = 24;
    /** Columns are read this far below foot level (a mine shaft's ladders). */
    public static final int DEPTH = 8;
    /** ...and this far above it (lanterns on posts, a crane's hanging load). */
    public static final int HEAD = 4;
    /** A covered cell has a roof block at most this far above its foot level. */
    public static final int ROOF_REACH = 5;
    /** The tool shelter: a yard needs at least this many covered cells. */
    public static final int MIN_COVERED = 4;

    /**
     * One yard survey. {@code result} is what the plaque measures;
     * {@code unsafeEdge} is true when the fill ran into water or lava (a
     * fluid is never a boundary); {@code entrances} counts only gates and
     * doors that open onto standable ground.
     */
    public record Yard(RoomScanner.Result result, int area, int covered, boolean bounded,
                       boolean unsafeEdge, int entrances) {
    }

    private YardScanner() {
    }

    @Nullable
    public static Yard scan(ServerLevel level, BlockPos seed) {
        BlockPos foot = footOf(level, seed);
        if (foot == null) {
            return null;
        }
        int fy = foot.getY();
        Set<Long> area = new HashSet<>();
        Set<Long> boundary = new HashSet<>();
        Set<BlockPos> gates = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        area.add(key(foot.getX(), foot.getZ()));
        queue.add(foot);
        boolean bounded = true;
        boolean unsafeEdge = false;
        BlockPos leak = null;
        int minX = foot.getX(), maxX = foot.getX(), minZ = foot.getZ(), maxZ = foot.getZ();
        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos next = current.relative(dir);
                long k = key(next.getX(), next.getZ());
                if (area.contains(k) || boundary.contains(k)) {
                    continue;
                }
                if (Math.abs(next.getX() - foot.getX()) > MAX_EXTENT
                    || Math.abs(next.getZ() - foot.getZ()) > MAX_EXTENT
                    || !level.hasChunkAt(next)) {
                    bounded = false;
                    if (leak == null || foot.distSqr(next) < foot.distSqr(leak)) {
                        leak = next.immutable();
                    }
                    continue;
                }
                BlockState state = level.getBlockState(next);
                if (!state.getFluidState().isEmpty()) {
                    // Water or lava is an unsafe edge, never a wall: a lot
                    // "bounded" by a moat or a lava lake is not a work yard.
                    bounded = false;
                    unsafeEdge = true;
                    if (leak == null || foot.distSqr(next) < foot.distSqr(leak)) {
                        leak = next.immutable();
                    }
                    continue;
                }
                if (state.getBlock() instanceof DoorBlock || state.getBlock() instanceof FenceGateBlock) {
                    // A way in only counts when it opens onto somewhere a
                    // settler can stand: a gate walled up from outside is
                    // part of the fence, not an entrance.
                    BlockPos lower = state.getBlock() instanceof DoorBlock
                        && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? next.below() : next.immutable();
                    BlockPos beyond = lower.relative(dir);
                    if (passable(level, beyond, level.getBlockState(beyond))
                        && passable(level, beyond.above(), level.getBlockState(beyond.above()))) {
                        gates.add(lower);
                    }
                    boundary.add(k);
                    continue;
                }
                if (!passable(level, next, state)) {
                    boundary.add(k);
                    continue;
                }
                area.add(k);
                minX = Math.min(minX, next.getX());
                maxX = Math.max(maxX, next.getX());
                minZ = Math.min(minZ, next.getZ());
                maxZ = Math.max(maxZ, next.getZ());
                if (area.size() > MAX_AREA) {
                    bounded = false;
                    leak = next.immutable();
                    queue.clear();
                    break;
                }
                queue.add(next);
            }
        }

        Map<Block, Integer> counts = new HashMap<>();
        Map<Block, Integer> floor = new HashMap<>();
        List<BlockPos> beds = new ArrayList<>();
        int lights = 0;
        int covered = 0;
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        Set<Long> columns = new HashSet<>(area);
        columns.addAll(boundary);
        for (long column : columns) {
            int x = (int) (column >> 32);
            int z = (int) column;
            for (int y = fy - DEPTH; y <= fy + HEAD; y++) {
                probe.set(x, y, z);
                BlockState s = level.getBlockState(probe);
                if (s.isAir()) {
                    continue;
                }
                counts.merge(s.getBlock(), 1, Integer::sum);
                if (s.getLightEmission() > 7) {
                    lights++;
                }
                if (s.getBlock() instanceof BedBlock && s.getValue(BedBlock.PART) == BedPart.HEAD) {
                    beds.add(probe.immutable());
                }
            }
            if (area.contains(column)) {
                probe.set(x, fy - 1, z);
                floor.merge(level.getBlockState(probe).getBlock(), 1, Integer::sum);
                for (int y = fy + 2; y <= fy + ROOF_REACH; y++) {
                    probe.set(x, y, z);
                    BlockState s = level.getBlockState(probe);
                    if (!(s.getBlock() instanceof LadderBlock) && !s.getCollisionShape(level, probe).isEmpty()) {
                        covered++;
                        break;
                    }
                }
            }
        }
        boolean enclosed = bounded && covered >= MIN_COVERED;
        BoundingBox bounds = new BoundingBox(minX - 1, fy - DEPTH, minZ - 1, maxX + 1, fy + HEAD, maxZ + 1);
        RoomScanner.Result result = new RoomScanner.Result(bounds, area.size(), List.copyOf(beds),
            gates.size(), lights, 0, enclosed, false, Map.copyOf(counts),
            bounded ? null : leak, null, 0, Map.copyOf(floor));
        return new Yard(result, area.size(), covered, bounded, unsafeEdge, gates.size());
    }

    /** The plaque looks at head height; the yard is walked at foot level. */
    @Nullable
    static BlockPos footOf(ServerLevel level, BlockPos seed) {
        BlockPos pos = seed;
        if (!passable(level, pos, level.getBlockState(pos))) {
            return null;
        }
        for (int i = 0; i < 4; i++) {
            BlockPos below = pos.below();
            BlockState s = level.getBlockState(below);
            if (passable(level, below, s) && !(s.getBlock() instanceof DoorBlock)) {
                pos = below;
            } else {
                break;
            }
        }
        return pos.immutable();
    }

    /** Same rule as RoomScanner: no collision and no fluid, beds/carpets/ladders pass. */
    static boolean passable(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof DoorBlock) {
            return false;
        }
        if (state.getBlock() instanceof BedBlock
            || state.getBlock() instanceof CarpetBlock
            || state.getBlock() instanceof LadderBlock && state.getFluidState().isEmpty()) {
            return true;
        }
        return state.getFluidState().isEmpty() && state.getCollisionShape(level, pos).isEmpty();
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
