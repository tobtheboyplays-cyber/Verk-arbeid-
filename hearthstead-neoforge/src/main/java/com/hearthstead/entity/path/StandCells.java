package com.hearthstead.entity.path;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Where a settler can stand to use one block inside a room: a workbench,
 * a lectern, a bed, a table.
 *
 * <p>Candidates are the eight cells around the target on its own floor and
 * one block above or below (a stair tread, a slab, a platform). A cell
 * qualifies when the body fits there (floor coverings like carpet included)
 * and the target is within hand reach and in plain sight from the eye. The
 * pathfinder then picks the cheapest reachable cell. Same-floor, face-on
 * cells are listed first so equal routes prefer the natural spot.
 *
 * <p>Read-only planning plus one navigation request. Never loads a chunk.
 */
public final class StandCells {
    /** Squared distance from feet to the target centre within which it can be used. */
    public static final double REACH_SQR = 6.25D;

    private static final int[][] RING = {
        {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private StandCells() {
    }

    /** Legal feet cells around {@code target}, best first. */
    public static Set<BlockPos> standCells(ServerLevel level, Entity actor, BlockPos target) {
        return standCells(level, actor, Set.of(target), REACH_SQR);
    }

    /**
     * Legal feet cells around any of {@code parts} (a bed has two), from
     * which at least one part is within {@code reachSqr} and visible.
     */
    public static Set<BlockPos> standCells(ServerLevel level, Entity actor, Set<BlockPos> parts,
                                           double reachSqr) {
        List<Candidate> found = new ArrayList<>();
        Set<BlockPos> seen = new LinkedHashSet<>();
        for (BlockPos part : parts) {
            for (int dy : new int[]{0, 1, -1}) {
                for (int[] step : RING) {
                    BlockPos feet = part.offset(step[0], dy, step[1]);
                    if (parts.contains(feet) || !seen.add(feet)) continue;
                    double lift = feetLift(level, actor, feet);
                    if (lift < 0.0D) continue;
                    Vec3 stand = new Vec3(feet.getX() + 0.5D, feet.getY() + lift, feet.getZ() + 0.5D);
                    if (!inReachFrom(level, actor, stand, parts, reachSqr)) continue;
                    int rank = (dy == 0 ? 0 : 2) + (step[0] != 0 && step[1] != 0 ? 1 : 0);
                    found.add(new Candidate(feet.immutable(), rank));
                }
            }
        }
        found.sort(Comparator.comparingInt(Candidate::rank));
        Set<BlockPos> result = new LinkedHashSet<>();
        for (Candidate candidate : found) result.add(candidate.feet());
        return result;
    }

    /** True when the actor, where it stands now, can use {@code target}. */
    public static boolean inReach(ServerLevel level, Entity actor, BlockPos target) {
        return inReachFrom(level, actor, actor.position(), Set.of(target), REACH_SQR);
    }

    /** True when the actor, where it stands now, can use any of {@code parts}. */
    public static boolean inReach(ServerLevel level, Entity actor, Set<BlockPos> parts, double reachSqr) {
        return inReachFrom(level, actor, actor.position(), parts, reachSqr);
    }

    /**
     * Starts one route to the best reachable stand cell for {@code target}.
     * Returns true when a route was accepted or the target is already in
     * reach (navigation is then stopped).
     */
    public static boolean moveNextTo(ServerLevel level, Mob mob, BlockPos target, double speed) {
        if (inReach(level, mob, target)) {
            mob.getNavigation().stop();
            return true;
        }
        Set<BlockPos> cells = standCells(level, mob, target);
        if (cells.isEmpty()) {
            return mob.getNavigation().moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, speed);
        }
        return moveToAny(level, mob, cells, speed);
    }

    /** One bounded route to any of the given feet cells, expanded indoors when needed. */
    public static boolean moveToAny(ServerLevel level, Mob mob, Set<BlockPos> cells, double speed) {
        Path installed = mob.getNavigation().getPath();
        if (installed != null && !installed.isDone() && installed.canReach()
            && cells.contains(installed.getTarget())) {
            return true;
        }
        Path path = mob.getNavigation().createPath(cells, 0);
        if ((path == null || !path.canReach()) && mob.getNavigation() instanceof RoadNavigation road) {
            Path building = road.createBuildingPath(cells, 0);
            if (building != null && building.canReach()) path = building;
        }
        return path != null && (path.canReach() || path.getNodeCount() > 1)
            && mob.getNavigation().moveTo(path, speed);
    }

    /**
     * Height of the feet above the cell's bottom when a body can stand in
     * {@code feet}, or -1. Ordinary cells stand at 0; a floor covering
     * (carpet, snow layer, bottom slab) raises the feet, and the head must
     * still clear whatever is above.
     */
    static double feetLift(ServerLevel level, Entity actor, BlockPos feet) {
        if (!level.isLoaded(feet) || !level.isLoaded(feet.above()) || !level.isLoaded(feet.below())
            || !level.isLoaded(feet.above(2))) {
            return -1.0D;
        }
        if (!level.getFluidState(feet).isEmpty()) return -1.0D;
        BlockState feetState = level.getBlockState(feet);
        VoxelShape feetShape = feetState.getCollisionShape(level, feet);
        double lift;
        if (feetShape.isEmpty()) {
            if (level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty()) {
                return -1.0D;
            }
            lift = 0.0D;
        } else {
            lift = IndoorBlocks.lowCoverTop(level, feet, feetState);
            if (lift < 0.0D) return -1.0D;
        }
        if (!level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) {
            return -1.0D;
        }
        double headTop = lift + actor.getBbHeight();
        if (headTop > 2.0D) {
            BlockPos head = feet.above(2);
            VoxelShape ceiling = level.getBlockState(head).getCollisionShape(level, head);
            if (!ceiling.isEmpty() && 2.0D + ceiling.min(Direction.Axis.Y) < headTop - 1.0E-4D) {
                return -1.0D;
            }
        }
        return lift;
    }

    private static boolean inReachFrom(ServerLevel level, Entity actor, Vec3 feet, Set<BlockPos> parts,
                                       double reachSqr) {
        Vec3 eye = feet.add(0.0D, actor.getEyeHeight(), 0.0D);
        for (BlockPos part : parts) {
            if (!level.isLoaded(part)) continue;
            Vec3 centre = Vec3.atCenterOf(part);
            if (feet.distanceToSqr(centre) > reachSqr) continue;
            BlockHitResult hit = level.clip(new ClipContext(eye, centre,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, actor));
            if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(part)) return true;
        }
        return false;
    }

    private record Candidate(BlockPos feet, int rank) {
    }
}
