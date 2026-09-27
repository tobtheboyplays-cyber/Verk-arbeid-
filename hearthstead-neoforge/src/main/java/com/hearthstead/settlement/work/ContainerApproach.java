package com.hearthstead.settlement.work;

import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.SettlerDoorGoal;
import com.hearthstead.settlement.workzone.WorkZoneService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.HashSet;
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
        /** Navigation or the bounded connected-door handoff was accepted. */
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
    private static final int DOOR_SEARCH_RADIUS = 8;
    private static final int DOOR_SEARCH_HEIGHT = 2;
    private static final int MAX_DOOR_SEARCH_CELLS = 640;
    private static final double DOOR_STAGE_REACHED_SQR = 2.25D;
    /** Highest collision top (carpet, bottom slab, low snow) that is its own floor. */
    private static final double LOW_FLOOR_MAX_Y = 0.5D;

    /**
     * One closed doorway discovered from the container side. {@code inside}
     * is connected to a legal visible container contact cell without crossing
     * a solid block; {@code outside} is the opposite standing cell.
     */
    private record DoorPassage(BlockPos door, BlockPos inside,
                               BlockPos outside) {
        private DoorPassage {
            door = door.immutable();
            inside = inside.immutable();
            outside = outside.immutable();
        }
    }

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
     * Server-authoritative physical contact for a non-settler actor. The
     * caller still owns target ownership and every inventory mutation.
     * This reads one already-loaded container and uses the same face rays as
     * settler contact; it never requests a chunk or plans a route.
     */
    public static boolean hasPhysicalContact(@Nullable ServerLevel level,
                                             @Nullable Entity actor,
                                             @Nullable BlockPos target) {
        if (!isLiveContainer(level, target) || actor == null) {
            return false;
        }
        if (actor.distanceToSqr(target.getX() + 0.5D,
                target.getY() + 0.5D, target.getZ() + 0.5D)
                > CONTACT_DISTANCE_SQR) {
            return false;
        }
        return hasVisibleFace(level, actor.getEyePosition(), target, actor);
    }

    /**
     * Starts one full, bounded path to a visible contact cell for a mob.
     * Unlike settler routing this deliberately has no door handoff or
     * partial-path acceptance: raiders use a partial route only as their
     * existing breach fallback, never as permission to touch a container.
     */
    public static Result moveMobToContact(@Nullable ServerLevel level,
                                          @Nullable Mob mob,
                                          @Nullable BlockPos target,
                                          double speed) {
        if (!isLiveContainer(level, target) || mob == null) {
            return new Result(State.INVALID_TARGET, target, null);
        }
        if (hasPhysicalContact(level, mob, target)) {
            mob.getNavigation().stop();
            return new Result(State.CONTACT, target, mob.blockPosition());
        }
        Set<BlockPos> approaches = standableApproaches(level, mob, target);
        if (approaches.isEmpty()) {
            return new Result(State.NO_STANDABLE_SIDE, target, null);
        }
        Path path = mob.getNavigation().createPath(approaches, 0);
        if (path != null && path.canReach()
            && approaches.contains(path.getTarget())
            && mob.getNavigation().moveTo(path, speed)) {
            return new Result(State.PATH_STARTED, target, path.getTarget());
        }
        return new Result(State.NO_REACHABLE_SIDE, target, null);
    }

    /** Read-only conservative meal offer: one exact-target path, no movement or door recovery. */
    public static boolean canPlanContact(ServerLevel level, SettlerEntity settler, BlockPos target) {
        Result inspection = inspect(level, settler, target);
        if (inspection.canInteract()) return true;
        if (inspection.state() == State.INVALID_TARGET || level == null || settler == null || target == null) return false;
        Set<BlockPos> approaches = standableApproaches(level, settler, target);
        if (approaches.isEmpty()) return false;
        // Vanilla createPath changes navigation target/stuck metadata. Keep
        // those probe-local rather than changing a working host's navigator.
        Path path = new com.hearthstead.entity.path.RoadNavigation(settler, level).createPath(approaches, 0);
        return path != null && path.canReach() && approaches.contains(path.getTarget());
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
        Path direct = settler.getNavigation().createPath(approaches, 0);
        if (direct != null && approaches.contains(direct.getTarget())
            && (direct.canReach()
                || SettlerDoorGoal.canRecoverPartialPathThroughClosedDoor(level, direct))
            && settler.getNavigation().moveTo(direct, speed)) {
            return new Result(State.PATH_STARTED, target, direct.getTarget());
        }

        // An offset entrance is not necessarily on the partial path's final
        // straight ray. The old direct-only rule therefore chose the closest
        // wall beside a corner chest and never discovered the real door a few
        // blocks around that wall. Find a doorway from the CONTAINER side by
        // a bounded standable-cell flood, then stage on its reachable outside
        // cell. Once adjacent, the normal target path contains that exact door
        // and SettlerDoorGoal owns opening/closing it. No inventory changes and
        // no arbitrary room-wide door opening happen in this helper.
        Result staged = moveThroughConnectedDoor(level, settler, target,
            approaches, speed);
        if (staged != null) {
            return staged;
        }
        // A useful doorway detour may initially move away from the chest.
        // Prefer it to a generic partial prefix, or each bounded repath can
        // replace that detour with the same closer dead end behind a wall.
        // Open terrain still keeps its original useful-partial fallback.
        if (direct != null && meaningfulPartialPath(level, settler, direct, approaches)
            && settler.getNavigation().moveTo(direct, speed)) {
            return new Result(State.PATH_STARTED, target,
                direct.getEndNode().asBlockPos());
        }
        return new Result(State.NO_REACHABLE_SIDE, target, null);
    }

    /** Movement only over caller-validated feet; never grants interaction authority. */
    @Nullable
    static BlockPos startPathToStandTargets(ServerLevel level, SettlerEntity settler,
                                            Set<BlockPos> approaches, double speed) {
        Path path = settler.getNavigation().createPath(approaches, 0);
        boolean partialProgress = meaningfulPartialPath(level, settler, path, approaches);
        if (path != null && approaches.contains(path.getTarget())
            && (path.canReach()
                || SettlerDoorGoal.canRecoverPartialPathThroughClosedDoor(level, path)
                || partialProgress)) {
            BlockPos approach = partialProgress ? path.getEndNode().asBlockPos()
                : path.getTarget().immutable();
            if (settler.getNavigation().moveTo(path, speed)) {
                return approach;
            }
        }
        return null;
    }

    /**
     * Bounded candidate set: four adjacent cells, the four diagonals, the block above, and the
     * four side cells one level below (the floor beside a stacked chest).
     */
    private static Set<BlockPos> standableApproaches(ServerLevel level, Entity actor,
                                                     BlockPos target) {
        Set<BlockPos> result = new LinkedHashSet<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            addIfStandable(level, actor, target, target.relative(direction), result);
        }
        // A chest in a room corner with furniture in front of both open
        // faces is still used across the corner, from the diagonal floor
        // cell, as long as a face is in reach and plain sight. Listed after
        // the face-on cells so an equally short face-on route still wins.
        for (int[] diagonal : new int[][]{{1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
            addIfStandable(level, actor, target,
                target.offset(diagonal[0], 0, diagonal[1]), result);
        }
        addIfStandable(level, actor, target, target.above(), result);
        // The upper chest of a two-high stack has no floor beside it at its
        // own height; its feet cells are the lower chest's side cells, from
        // which inspect() already accepts contact. Same bounded, loaded-only
        // checks; contact authority is still decided solely by inspect().
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            addIfStandable(level, actor, target,
                target.relative(direction).below(), result);
        }
        return result;
    }

    private static void addIfStandable(ServerLevel level, Entity actor,
                                       BlockPos target, BlockPos candidate,
                                       Set<BlockPos> result) {
        double feet = contactFeetOffset(level, actor, candidate);
        if (feet >= 0.0D
            // Do not path to the closest side merely to discover, at the
            // terminal node, that its wall hides every interactable face.
            // The eye is projected from these exact candidate feet; no
            // caller position or navigation target is mutated by this check.
            && hasVisibleFace(level, new Vec3(candidate.getX() + 0.5D,
                candidate.getY() + feet + actor.getEyeHeight(),
                candidate.getZ() + 0.5D), target, actor)) {
            result.add(candidate.immutable());
        }
    }

    @Nullable
    private static Result moveThroughConnectedDoor(ServerLevel level,
                                                    SettlerEntity settler,
                                                    BlockPos target,
                                                    Set<BlockPos> approaches,
                                                    double speed) {
        for (DoorPassage passage : connectedClosedDoorPassages(level, target,
                approaches)) {
            if (settler.distanceToSqr(passage.outside().getX() + 0.5D,
                    passage.outside().getY(), passage.outside().getZ() + 0.5D)
                    <= DOOR_STAGE_REACHED_SQR) {
                Path throughDoor = settler.getNavigation().createPath(
                    Set.of(passage.inside()), 0);
                if (throughDoor != null
                    && (throughDoor.canReach()
                        || SettlerDoorGoal.canRecoverPartialPathThroughClosedDoor(
                            level, throughDoor))
                    && settler.getNavigation().moveTo(throughDoor, speed)) {
                    return new Result(State.PATH_STARTED, target,
                        passage.inside());
                }
                // A one-block rise at the threshold can make vanilla return
                // no useful partial path even though this worker has reached
                // the only exterior cell of a door proven from the container
                // side. Hand that exact door to SettlerDoorGoal; it rechecks
                // proximity/type, owns opening/closing, and the caller's next
                // bounded repath crosses once collision has changed.
                settler.requestDoorPassage(passage.door());
                return new Result(State.PATH_STARTED, target,
                    passage.outside());
            }

            Set<BlockPos> outsideTarget = Set.of(passage.outside());
            Path toOutside = settler.getNavigation().createPath(outsideTarget, 0);
            boolean partialProgress = meaningfulPartialPath(level, settler,
                toOutside, outsideTarget);
            if (toOutside != null && outsideTarget.contains(toOutside.getTarget())
                && (toOutside.canReach() || partialProgress)
                && settler.getNavigation().moveTo(toOutside, speed)) {
                return new Result(State.PATH_STARTED, target,
                    partialProgress ? toOutside.getEndNode().asBlockPos()
                        : passage.outside());
            }
        }
        return null;
    }

    /**
     * A bounded vanilla search may exhaust its visited-node budget before it
     * reaches a perfectly usable target, particularly with road preference.
     * A partial route is movement only: it never proves container contact.
     * Keep its exact requested target and require a real loaded standing node
     * at least one block closer, and at least one block from the worker now.
     * The latter prevents resubmitting an already consumed prefix forever.
     * One block admits short useful prefixes without accepting sub-block
     * jitter. Closed-door recovery remains an independent acceptance path,
     * including doorway detours which initially move away from the target.
     */
    private static boolean meaningfulPartialPath(ServerLevel level,
                                                 SettlerEntity settler,
                                                 @Nullable Path path,
                                                 Set<BlockPos> requestedTargets) {
        if (path == null || path.canReach() || path.getNodeCount() < 2
            || !requestedTargets.contains(path.getTarget())) {
            return false;
        }
        BlockPos end = path.getEndNode().asBlockPos();
        if (!isStandable(level, end)) {
            return false;
        }
        Vec3 endpoint = Vec3.atBottomCenterOf(end);
        Vec3 destination = Vec3.atBottomCenterOf(path.getTarget());
        Vec3 current = settler.position();
        return current.distanceToSqr(endpoint) >= 1.0D
            && current.distanceTo(destination) - endpoint.distanceTo(destination)
                >= 1.0D;
    }

    /**
     * Discovers only doors connected to the container's own visible contact
     * space. This is deliberately bounded and runs only after ordinary path
     * creation failed, never per frame or during healthy movement.
     */
    private static Set<DoorPassage> connectedClosedDoorPassages(
            ServerLevel level, BlockPos target, Set<BlockPos> approaches) {
        ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        Set<DoorPassage> passages = new LinkedHashSet<>();
        for (BlockPos approach : approaches) {
            if (insideDoorSearch(target, approach) && visited.add(approach)) {
                frontier.addLast(approach);
            }
        }

        while (!frontier.isEmpty() && visited.size() <= MAX_DOOR_SEARCH_CELLS) {
            BlockPos inside = frontier.removeFirst();
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos adjacent = inside.relative(direction);
                BlockPos door = closedWoodenDoorLower(level, adjacent);
                if (door != null) {
                    BlockPos outside = standableDoorSide(level,
                        door.relative(direction));
                    if (outside != null && insideDoorSearch(target, outside)) {
                        passages.add(new DoorPassage(door, inside, outside));
                    }
                    continue;
                }
                if (insideDoorSearch(target, adjacent)
                    && isStandable(level, adjacent)
                    && visited.add(adjacent)) {
                    frontier.addLast(adjacent.immutable());
                }
            }
        }
        return passages;
    }

    /**
     * Door thresholds commonly sit one block above the surrounding terrain.
     * Vanilla navigation can step that height, so the staging cell must accept
     * the same Y, one below, or one above the door floor instead of silently
     * requiring a perfectly level building apron.
     */
    @Nullable
    static BlockPos standableDoorSide(ServerLevel level,
                                              BlockPos doorSide) {
        if (isStandable(level, doorSide)) {
            return doorSide.immutable();
        }
        if (isStandable(level, doorSide.below())) {
            return doorSide.below().immutable();
        }
        if (isStandable(level, doorSide.above())) {
            return doorSide.above().immutable();
        }
        return null;
    }

    private static boolean insideDoorSearch(BlockPos target, BlockPos candidate) {
        return Math.abs(candidate.getX() - target.getX()) <= DOOR_SEARCH_RADIUS
            && Math.abs(candidate.getZ() - target.getZ()) <= DOOR_SEARCH_RADIUS
            && Math.abs(candidate.getY() - target.getY()) <= DOOR_SEARCH_HEIGHT;
    }

    static boolean isStandable(ServerLevel level, BlockPos candidate) {
        if (!WorkZoneService.livePositionAvailable(level, candidate)
            || !WorkZoneService.livePositionAvailable(level, candidate.above())
            || !WorkZoneService.livePositionAvailable(level, candidate.below())) {
            return false;
        }
        return level.getBlockState(candidate)
                .getCollisionShape(level, candidate).isEmpty()
            && level.getBlockState(candidate.above())
                .getCollisionShape(level, candidate.above()).isEmpty()
            && !level.getBlockState(candidate.below())
                .getCollisionShape(level, candidate.below()).isEmpty();
    }

    /**
     * Feet height inside {@code candidate} for container contact candidates,
     * or {@code -1} when the cell is not standable. Ordinary {@link
     * #isStandable} cells stand at 0. A low block whose collision top is at
     * most {@link #LOW_FLOOR_MAX_Y} (carpet, bottom slab, snow layers) is its
     * own support, so carpeted warehouses keep their chest-side cells. The
     * raised feet must still leave body room above; nothing is loaded.
     */
    private static double contactFeetOffset(ServerLevel level, Entity actor,
                                            BlockPos candidate) {
        if (isStandable(level, candidate)) {
            return 0.0D;
        }
        if (!WorkZoneService.livePositionAvailable(level, candidate)
            || !WorkZoneService.livePositionAvailable(level, candidate.above())) {
            return -1.0D;
        }
        VoxelShape floor = level.getBlockState(candidate)
            .getCollisionShape(level, candidate);
        if (floor.isEmpty() || floor.max(Direction.Axis.Y) > LOW_FLOOR_MAX_Y
            || !level.getBlockState(candidate.above())
                .getCollisionShape(level, candidate.above()).isEmpty()) {
            return -1.0D;
        }
        double top = floor.max(Direction.Axis.Y);
        // Standing on a slab pushes the head into the second cell above.
        double intrusion = top + actor.getBbHeight() - 2.0D;
        if (intrusion > 0.0D) {
            BlockPos head = candidate.above(2);
            if (!WorkZoneService.livePositionAvailable(level, head)) {
                return -1.0D;
            }
            VoxelShape ceiling = level.getBlockState(head)
                .getCollisionShape(level, head);
            if (!ceiling.isEmpty() && ceiling.min(Direction.Axis.Y) < intrusion) {
                return -1.0D;
            }
        }
        return top;
    }

    @Nullable
    private static BlockPos closedWoodenDoorLower(ServerLevel level,
                                                   BlockPos candidate) {
        if (!level.isLoaded(candidate)) {
            return null;
        }
        BlockState state = level.getBlockState(candidate);
        if (!(state.getBlock() instanceof DoorBlock)
            || !DoorBlock.isWoodenDoor(level, candidate)) {
            return null;
        }
        BlockPos lower = state.hasProperty(DoorBlock.HALF)
                && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER
            ? candidate.below() : candidate;
        BlockState lowerState = level.getBlockState(lower);
        return lowerState.hasProperty(DoorBlock.OPEN)
                && !lowerState.getValue(DoorBlock.OPEN)
            ? lower.immutable() : null;
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
                                          Entity actor) {
        Vec3 centre = Vec3.atCenterOf(target);
        return rayReachesTarget(level, from, centre, target, actor)
            || rayReachesTarget(level, from,
                centre.add(FACE_INSET, 0.0D, 0.0D), target, actor)
            || rayReachesTarget(level, from,
                centre.add(-FACE_INSET, 0.0D, 0.0D), target, actor)
            || rayReachesTarget(level, from,
                centre.add(0.0D, 0.0D, FACE_INSET), target, actor)
            || rayReachesTarget(level, from,
                centre.add(0.0D, 0.0D, -FACE_INSET), target, actor);
    }

    private static boolean rayReachesTarget(ServerLevel level, Vec3 from,
                                            Vec3 to, BlockPos target,
                                            Entity actor) {
        BlockHitResult hit = level.clip(new ClipContext(from, to,
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, actor));
        return hit.getType() == HitResult.Type.MISS || target.equals(hit.getBlockPos());
    }

    private ContainerApproach() {
    }
}
