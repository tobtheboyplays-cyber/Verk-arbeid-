package com.hearthstead.settlement.work;

import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.workzone.WorkZoneService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Server-side physical contact with one already-loaded world container.
 *
 * <p>This class deliberately has no knowledge of bags, routes, requests or
 * workplace ownership. It only answers whether a settler can interact with
 * one exact container now, or starts one bounded path to a legal standing
 * cell. Callers retain ownership of all inventory and route state.
 */
public final class ContainerApproach {
    /** The complete, immutable outcome vocabulary for one contact attempt. */
    public enum State {
        /** The target is loaded, a real container, in hand reach and visible. */
        CONTACT,
        /** Navigation accepted a path to one specific legal contact cell. */
        PATH_STARTED,
        /** The target remains valid but the settler has not reached it yet. */
        OUT_OF_REACH,
        /** The target is in reach but a solid block currently seals every face. */
        OCCLUDED,
        /** The target is unloaded, absent, or no longer a {@link Container}. */
        INVALID_TARGET,
        /** No collision-free feet cell exists beside or above the container. */
        NO_STANDABLE_SIDE,
        /** Legal feet cells exist, but navigation cannot reach any of them. */
        NO_REACHABLE_SIDE
    }

    /**
     * Immutable result of a contact inspection or one navigation attempt.
     * Target and approach positions are copied so callers cannot accidentally
     * mutate a route key after receiving the result.
     */
    public record Result(State state, @Nullable BlockPos target,
                         @Nullable BlockPos approach) {
        public Result {
            if (state == null) {
                throw new IllegalArgumentException("Container approach state is required");
            }
            target = target == null ? null : target.immutable();
            approach = approach == null ? null : approach.immutable();
        }

        public boolean canInteract() {
            return state == State.CONTACT;
        }

        public boolean startedPath() {
            return state == State.PATH_STARTED;
        }
    }

    /** Same entity-to-container-centre contact radius used by request authority. */
    public static final double CONTACT_DISTANCE_SQR = 6.25D;
    private static final double FACE_INSET = 0.49D;

    /**
     * Re-reads one exact target without loading a chunk. A successful result
     * is the server-authoritative precondition for any container mutation.
     */
    public static Result inspect(@Nullable ServerLevel level,
                                 @Nullable SettlerEntity settler,
                                 @Nullable BlockPos target) {
        if (!isLiveContainer(level, target) || settler == null) {
            return new Result(State.INVALID_TARGET, target, null);
        }
        if (settler.distanceToSqr(target.getX() + 0.5D,
                target.getY() + 0.5D, target.getZ() + 0.5D)
                > CONTACT_DISTANCE_SQR) {
            return new Result(State.OUT_OF_REACH, target, null);
        }
        return hasVisibleFace(level, settler.getEyePosition(), target, settler)
            ? new Result(State.CONTACT, target, settler.blockPosition())
            : new Result(State.OCCLUDED, target, null);
    }

    /**
     * Starts one bounded multi-target path to a legal container contact cell.
     * No inventory, request, target selection, cooldown or other caller state
     * is changed here.
     */
    public static Result moveToContact(@Nullable ServerLevel level,
                                       @Nullable SettlerEntity settler,
                                       @Nullable BlockPos target,
                                       double speed) {
        Result inspection = inspect(level, settler, target);
        if (inspection.canInteract()) {
            settler.getNavigation().stop();
            return inspection;
        }
        if (inspection.state() == State.INVALID_TARGET || level == null
            || settler == null || target == null) {
            return inspection;
        }

        Set<BlockPos> approaches = standableApproaches(level, settler, target);
        if (approaches.isEmpty()) {
            return new Result(State.NO_STANDABLE_SIDE, target, null);
        }
        Path path = settler.getNavigation().createPath(approaches, 0);
        if (path == null || !path.canReach() || !approaches.contains(path.getTarget())) {
            return new Result(State.NO_REACHABLE_SIDE, target, null);
        }
        BlockPos approach = path.getTarget().immutable();
        if (!settler.getNavigation().moveTo(path, speed)) {
            return new Result(State.NO_REACHABLE_SIDE, target, approach);
        }
        return new Result(State.PATH_STARTED, target, approach);
    }

    /** Bounded candidate set: four adjacent cells and the block above. */
    private static Set<BlockPos> standableApproaches(ServerLevel level,
                                                     SettlerEntity settler,
                                                     BlockPos target) {
        Set<BlockPos> result = new LinkedHashSet<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            addIfStandable(level, settler, target, target.relative(direction), result);
        }
        addIfStandable(level, settler, target, target.above(), result);
        return result;
    }

    private static void addIfStandable(ServerLevel level, SettlerEntity settler,
                                       BlockPos target, BlockPos candidate,
                                       Set<BlockPos> result) {
        if (!WorkZoneService.livePositionAvailable(level, candidate)
            || !WorkZoneService.livePositionAvailable(level, candidate.above())
            || !WorkZoneService.livePositionAvailable(level, candidate.below())) {
            return;
        }
        if (level.getBlockState(candidate).getCollisionShape(level, candidate).isEmpty()
            && level.getBlockState(candidate.above())
                .getCollisionShape(level, candidate.above()).isEmpty()
            && !level.getBlockState(candidate.below())
                .getCollisionShape(level, candidate.below()).isEmpty()
            // Do not path to the closest side merely to discover, at the
            // terminal node, that its wall hides every interactable face.
            // The eye is projected from these exact candidate feet; no
            // caller position or navigation target is mutated by this check.
            && hasVisibleFace(level, new Vec3(candidate.getX() + 0.5D,
                candidate.getY() + settler.getEyeHeight(),
                candidate.getZ() + 0.5D), target, settler)) {
            result.add(candidate.immutable());
        }
    }

    private static boolean isLiveContainer(@Nullable ServerLevel level,
                                           @Nullable BlockPos target) {
        if (!WorkZoneService.livePositionAvailable(level, target)) {
            return false;
        }
        BlockEntity blockEntity = level.getBlockEntity(target);
        return blockEntity instanceof Container;
    }

    /**
     * The centre handles ordinary chests; inset face samples preserve real
     * exposed corner contact without widening the interaction radius or
     * accepting an obstructed through-wall ray.
     */
    private static boolean hasVisibleFace(ServerLevel level, Vec3 from,
                                          BlockPos target,
                                          SettlerEntity settler) {
        Vec3 centre = Vec3.atCenterOf(target);
        return rayReachesTarget(level, from, centre, target, settler)
            || rayReachesTarget(level, from,
                centre.add(FACE_INSET, 0.0D, 0.0D), target, settler)
            || rayReachesTarget(level, from,
                centre.add(-FACE_INSET, 0.0D, 0.0D), target, settler)
            || rayReachesTarget(level, from,
                centre.add(0.0D, 0.0D, FACE_INSET), target, settler)
            || rayReachesTarget(level, from,
                centre.add(0.0D, 0.0D, -FACE_INSET), target, settler);
    }

    private static boolean rayReachesTarget(ServerLevel level, Vec3 from,
                                            Vec3 to, BlockPos target,
                                            SettlerEntity settler) {
        BlockHitResult hit = level.clip(new ClipContext(from, to,
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, settler));
        return hit.getType() == HitResult.Type.MISS || target.equals(hit.getBlockPos());
    }

    private ContainerApproach() {
    }
}
