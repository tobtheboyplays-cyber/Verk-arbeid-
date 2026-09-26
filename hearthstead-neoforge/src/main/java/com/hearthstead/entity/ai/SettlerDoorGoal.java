package com.hearthstead.entity.ai;

import com.hearthstead.entity.SettlerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;

import com.hearthstead.entity.path.IndoorBlocks;
import com.hearthstead.entity.path.SettlerDoorSteward;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.TrapDoorBlock;

import javax.annotation.Nullable;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Opens a wooden door, a fence gate or a ladder hatch before a settler
 * collides with it, then closes it after the settler has crossed.
 *
 * <p>Doors are closed behind the settler whatever state they were in (a
 * settlement's doors stay shut, as in MineColonies). Gates and hatches are
 * closed only when this goal opened them, so a gate a player propped open
 * stays open. Iron doors and iron trapdoors are never operated. Every
 * passage opened here is also registered with {@link SettlerDoorSteward},
 * which closes it if this goal is interrupted before it can.
 *
 * <p>Vanilla's OpenDoorGoal refuses to start until horizontalCollision is
 * already true. In the small Hearthstead workplaces that condition is
 * unreliable: navigation can stop a fraction before the closed door, so the
 * goal that could open it never starts. This goal reads only the next few
 * nodes of the already-bounded path and opens their nearby door proactively.
 * It does not scan a room and it never opens an unrelated distant door.
 */
public final class SettlerDoorGoal extends Goal {
    private static final double ACTIVATE_REACH_SQR = 9.0D;
    // Container staging can finish on the exterior threshold before its work
    // goal's bounded repath interval elapses. Five seconds keeps the proven
    // door open through that handoff without leaving it permanently open.
    private static final int MAX_OPEN_TICKS = 100;
    // A completed outbound route may immediately issue a reverse route. Keep
    // the already-open door through that small AI handoff, then close it.
    private static final int POST_PASS_OPEN_TICKS = 4;
    private static final int MAX_TERMINAL_FORWARD_PROBE = 3;

    private final SettlerEntity settler;
    @Nullable
    private BlockPos doorPos;
    private int openTicks;
    private double approachX;
    private double approachZ;
    private boolean crossedPlane;
    private boolean passed;
    private boolean openedByUs;
    private boolean touchedHatch;

    public SettlerDoorGoal(SettlerEntity settler) {
        this.settler = settler;
    }

    @Override
    public boolean canUse() {
        if (!(settler.getNavigation() instanceof GroundPathNavigation nav)
            || !nav.canOpenDoors()) {
            return false;
        }
        BlockPos requested = settler.requestedDoorPassage().orElse(null);
        if (requested != null) {
            doorPos = nearbyDoor(requested);
            if (doorPos != null) {
                return true;
            }
            // A destroyed, replaced or no-longer-near door cannot retain
            // authority. The work route may issue a fresh proven request.
            settler.clearDoorPassageRequest();
        }
        Path path = nav.getPath();
        if (path == null) {
            return false;
        }
        // A closed door can make navigation finish on the final walkable
        // node immediately in front of it. Refusing a completed partial path
        // here strands the settler precisely when this goal is needed most.
        // Keep the ordinary bounded look-ahead for active routes, and use one
        // target-directed, obstruction-checked ray for terminal recovery.
        if (terminalRecoveryEligible(path.isDone(), path.canReach())) {
            doorPos = terminalDoor(path);
        } else if (!path.isDone()) {
            doorPos = nextDoor(path);
        } else {
            // A successfully completed route has reached its target; probing
            // beyond it could only operate an unrelated nearby door.
            doorPos = null;
        }
        return doorPos != null;
    }

    /** Package-visible pure seam for the terminal-recovery authority gate. */
    static boolean terminalRecoveryEligible(boolean pathDone,
                                              boolean pathCanReach) {
        return pathDone && !pathCanReach;
    }

    @Override
    public boolean canContinueToUse() {
        return doorPos != null && openTicks > 0
            && woodenDoorLower(doorPos) != null;
    }

    @Override
    public void start() {
        if (doorPos == null) {
            return;
        }
        settler.clearDoorPassageRequest();
        openTicks = MAX_OPEN_TICKS;
        crossedPlane = false;
        passed = false;
        approachX = doorPos.getX() + 0.5D - settler.getX();
        approachZ = doorPos.getZ() + 0.5D - settler.getZ();
        // Opening changes the door's collision shape after the courier's
        // current path was calculated.  Refresh that path exactly once on
        // the closed -> open transition so the same route can cross the now
        // passable doorway in either direction.  Recomputing from tick()
        // would cause needless path churn while several settlers use a door.
        touchedHatch = false;
        openedByUs = setOpen(true);
        if (openedByUs) {
            settler.getNavigation().recomputePath();
        }
    }

    @Override
    public void tick() {
        if (doorPos == null) {
            return;
        }
        openTicks--;
        setOpen(true);
        double remainingX = doorPos.getX() + 0.5D - settler.getX();
        double remainingZ = doorPos.getZ() + 0.5D - settler.getZ();
        crossedPlane |= approachX * remainingX + approachZ * remainingZ < 0.0D;
        // The old centre-plane check closed the door as soon as the mob's
        // centre crossed the threshold.  At that instant roughly half of a
        // settler's collision box can still occupy the doorway, so closing
        // the door catches the back half and strands the route on the sill.
        // Keep it open until the centre is a full block beyond the door: that
        // clears the whole 0.6-block body with margin in every orientation.
        if (isHatch(doorPos)) {
            AABB cell = new AABB(doorPos).inflate(0.3D, 0.05D, 0.3D);
            boolean inside = settler.getBoundingBox().intersects(cell);
            touchedHatch |= inside;
            passed = touchedHatch && !inside;
        } else {
            passed = safelyClearedDoor(crossedPlane, remainingX, remainingZ);
        }
        if (settler.level() instanceof ServerLevel serverLevel && IndoorBlocks.isOpen(
                settler.level().getBlockState(doorPos))) {
            SettlerDoorSteward.touched(serverLevel, doorPos);
        }
        if (passed) {
            openTicks = Math.min(openTicks, POST_PASS_OPEN_TICKS);
        }
    }

    @Override
    public void stop() {
        settler.clearDoorPassageRequest();
        // Never swing a door shut on this settler's own body: a door closed
        // around it pushes it back into the room. The steward closes it
        // once the doorway is empty.
        if (doorPos != null && (openedByUs || isDoor(doorPos))
                && !IndoorBlocks.closingWouldHit(settler.level(), doorPos)
                && noOtherSettlerIsUsingDoor(doorPos) && setOpen(false)
                && settler.level() instanceof ServerLevel serverLevel) {
            SettlerDoorSteward.closed(serverLevel, doorPos);
        }
        doorPos = null;
        crossedPlane = false;
        passed = false;
        openedByUs = false;
        touchedHatch = false;
        openTicks = 0;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    /** Package-visible pure seam for the collision-clearance regression. */
    static boolean safelyClearedDoor(boolean crossedDoorPlane,
                                     double remainingX, double remainingZ) {
        return crossedDoorPlane
            && remainingX * remainingX + remainingZ * remainingZ > 1.0D;
    }

    @Nullable
    private BlockPos nextDoor(Path path) {
        int first = Math.max(0, path.getNextNodeIndex() - 1);
        int last = Math.min(path.getNodeCount(), path.getNextNodeIndex() + 4);
        for (int i = first; i < last; i++) {
            Node node = path.getNode(i);
            BlockPos nodePos = new BlockPos(node.x, node.y, node.z);
            BlockPos found = nearbyDoor(nodePos);
            if (found != null) {
                return found;
            }
        }
        BlockPos at = settler.blockPosition();
        BlockPos found = nearbyDoor(at);
        return found != null ? found : nearbyDoor(at.above());
    }

    /**
     * Finds only a closed wooden door at, or directly between, the terminal
     * node and its selected target. A partial path may stop two cells before
     * the collision which made it partial, but recovery must not turn into a
     * room scan: the target direction must be unambiguous, every earlier cell
     * must remain physically traversable, and the door must be the first
     * obstruction. {@link #nearbyDoor(BlockPos)} independently retains the
     * three-block activation bound.
     */
    @Nullable
    private BlockPos terminalDoor(Path path) {
        if (path.getNodeCount() <= 0) {
            return null;
        }
        BlockPos found = forwardClosedDoor(settler.level(), path);
        if (found != null && nearbyDoor(found) != null) {
            return found;
        }
        return null;
    }

    /**
     * Whether an otherwise partial path has one precise, recoverable reason:
     * a closed wooden door directly between its terminal and selected target.
     *
     * <p>This is the side-effect-free half of the hand-off used by physical
     * container routing. It deliberately ignores nearby side doors, walls and
     * gaps, and does not widen the pathfinder's target set; a caller may start
     * the existing partial path, after which this goal opens the same door and
     * asks vanilla navigation to recompute through it.
     */
    public static boolean canRecoverPartialPathThroughClosedDoor(
            @Nullable Level level, @Nullable Path path) {
        return level != null && path != null && !path.canReach()
            && forwardClosedDoor(level, path) != null;
    }

    @Nullable
    private static BlockPos forwardClosedDoor(Level level, Path path) {
        if (path.getNodeCount() <= 0) {
            return null;
        }
        Node terminalNode = path.getNode(path.getNodeCount() - 1);
        BlockPos terminal = new BlockPos(terminalNode.x, terminalNode.y,
            terminalNode.z);
        BlockPos found = closedWoodenDoorAt(level, terminal);
        if (found != null) {
            return found;
        }
        BlockPos previous = null;
        for (int index = path.getNodeCount() - 2; index >= 0; index--) {
            Node node = path.getNode(index);
            BlockPos candidate = new BlockPos(node.x, node.y, node.z);
            if (candidate.getX() != terminal.getX()
                || candidate.getZ() != terminal.getZ()) {
                previous = candidate;
                break;
            }
        }
        BlockPos heading = terminalContinuationStep(previous, terminal,
            path.getTarget());
        return firstDoorBeforeObstruction(terminal, heading,
            MAX_TERMINAL_FORWARD_PROBE,
            candidate -> closedWoodenDoorAt(level, candidate),
            candidate -> traversableStandingCell(level, candidate));
    }

    /**
     * Selects a safe continuation from a partial path's terminal node.
     * Ordinarily the target's dominant axis is authoritative. At an exact
     * diagonal tie that axis is unknowable, but the final cardinal path edge
     * still provides one unambiguous continuation when it also points toward
     * the selected target. This covers common diagonal room layouts without
     * widening recovery into a side-door scan.
     */
    @Nullable
    static BlockPos terminalContinuationStep(@Nullable BlockPos previous,
                                             BlockPos terminal,
                                             BlockPos target) {
        BlockPos dominant = dominantCardinalStep(terminal, target);
        if (dominant != null) {
            return dominant;
        }
        if (previous == null) {
            return null;
        }
        int stepX = terminal.getX() - previous.getX();
        int stepZ = terminal.getZ() - previous.getZ();
        if (Math.abs(stepX) + Math.abs(stepZ) != 1) {
            return null;
        }
        int targetX = target.getX() - terminal.getX();
        int targetZ = target.getZ() - terminal.getZ();
        if (stepX * targetX + stepZ * targetZ <= 0) {
            return null;
        }
        return new BlockPos(stepX, 0, stepZ);
    }

    /**
     * Returns the one target-directed cardinal continuation. Equal-axis
     * diagonals are ambiguous without another path node, so recovery fails
     * closed instead of guessing which neighbouring door owns the route.
     */
    @Nullable
    static BlockPos dominantCardinalStep(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        int absX = Math.abs(dx);
        int absZ = Math.abs(dz);
        if (absX == absZ) {
            return null;
        }
        return absX > absZ
            ? new BlockPos(Integer.signum(dx), 0, 0)
            : new BlockPos(0, 0, Integer.signum(dz));
    }

    /**
     * Pure bounded ray: a door is recoverable only when it is the first
     * non-traversable cell. A wall, gap or unloaded cell ends the probe before
     * any door beyond it can be selected or opened.
     */
    @Nullable
    static BlockPos firstDoorBeforeObstruction(
            BlockPos origin, @Nullable BlockPos cardinalStep, int maxSteps,
            Function<BlockPos, BlockPos> closedDoorAt,
            Predicate<BlockPos> traversable) {
        if (origin == null || cardinalStep == null || maxSteps <= 0
            || closedDoorAt == null || traversable == null
            || cardinalStep.getY() != 0
            || Math.abs(cardinalStep.getX())
                + Math.abs(cardinalStep.getZ()) != 1) {
            return null;
        }
        for (int step = 1; step <= maxSteps; step++) {
            BlockPos candidate = origin.offset(cardinalStep.getX() * step,
                0, cardinalStep.getZ() * step);
            BlockPos door = closedDoorAt.apply(candidate);
            if (door != null) {
                return door.immutable();
            }
            if (!traversable.test(candidate)) {
                return null;
            }
        }
        return null;
    }

    private static boolean traversableStandingCell(Level level,
                                                    BlockPos feet) {
        BlockPos head = feet.above();
        BlockPos floor = feet.below();
        if (!level.isLoaded(feet) || !level.isLoaded(head)
            || !level.isLoaded(floor)) {
            return false;
        }
        var floorShape = level.getBlockState(floor)
            .getCollisionShape(level, floor);
        return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
            && level.getBlockState(head).getCollisionShape(level, head).isEmpty()
            && !floorShape.isEmpty()
            // Paths, farmland and slabs are legal support, while a fence/wall
            // projecting into the feet cell is not an open continuation.
            && floorShape.max(net.minecraft.core.Direction.Axis.Y) <= 1.0D;
    }

    @Nullable
    private BlockPos nearbyDoor(BlockPos candidate) {
        BlockPos lower = woodenDoorLower(candidate);
        if (lower == null) {
            lower = woodenDoorLower(candidate.above());
        }
        if (lower == null
            || settler.distanceToSqr(lower.getX() + 0.5D,
                settler.getY() + 0.5D, lower.getZ() + 0.5D)
                > ACTIVATE_REACH_SQR) {
            return null;
        }
        return lower.immutable();
    }

    @Nullable
    private BlockPos woodenDoorLower(BlockPos candidate) {
        return woodenDoorLower(settler.level(), candidate);
    }

    @Nullable
    private static BlockPos closedWoodenDoorAt(Level level,
                                               BlockPos candidate) {
        if (!level.isLoaded(candidate) || !level.isLoaded(candidate.above())) {
            return null;
        }
        BlockPos lower = woodenDoorLower(level, candidate);
        if (lower == null) {
            lower = woodenDoorLower(level, candidate.above());
        }
        if (lower == null) {
            return null;
        }
        BlockState state = level.getBlockState(lower);
        return state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)
                && !IndoorBlocks.isOpen(state)
            ? lower.immutable() : null;
    }

    /**
     * The state-holding position of a hand-openable passage at
     * {@code candidate}: a wooden door (lower half), a fence gate, or a
     * wooden hatch over a ladder. Iron doors and hatches return null.
     */
    @Nullable
    private static BlockPos woodenDoorLower(Level level, BlockPos candidate) {
        if (!level.isLoaded(candidate)) {
            return null;
        }
        BlockPos found = IndoorBlocks.openablePassage(level, candidate);
        if (found == null || !level.isLoaded(found)) {
            return null;
        }
        BlockState state = level.getBlockState(found);
        if (state.getBlock() instanceof DoorBlock
            && state.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
            return null;
        }
        return found;
    }

    private boolean isHatch(BlockPos pos) {
        return settler.level().getBlockState(pos).getBlock() instanceof TrapDoorBlock;
    }

    private boolean isDoor(BlockPos pos) {
        return settler.level().getBlockState(pos).getBlock() instanceof DoorBlock;
    }

    /**
     * Applies the requested door state.
     *
     * @return {@code true} only when the world's collision state changed
     */
    private boolean setOpen(boolean open) {
        if (doorPos == null) {
            return false;
        }
        if (!(settler.level() instanceof ServerLevel level)) {
            return false;
        }
        boolean changed = SettlerDoorSteward.setOpen(level, doorPos, open, settler);
        if (changed && open) {
            SettlerDoorSteward.touched(level, doorPos);
        }
        return changed;
    }

    private boolean noOtherSettlerIsUsingDoor(BlockPos door) {
        AABB doorway = new AABB(door).inflate(1.25D, 1.0D, 1.25D);
        return settler.level().getEntitiesOfClass(SettlerEntity.class, doorway,
            other -> other != settler && other.isAlive()).isEmpty();
    }
}
