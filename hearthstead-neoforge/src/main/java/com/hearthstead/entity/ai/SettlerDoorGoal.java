package com.hearthstead.entity.ai;

import com.hearthstead.entity.SettlerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;

/**
 * Opens a wooden door before a settler collides with it, then closes it after
 * the settler has crossed the doorway.
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
    private static final int MAX_OPEN_TICKS = 60;

    private final SettlerEntity settler;
    @Nullable
    private BlockPos doorPos;
    private int openTicks;
    private double approachX;
    private double approachZ;
    private boolean passed;

    public SettlerDoorGoal(SettlerEntity settler) {
        this.settler = settler;
    }

    @Override
    public boolean canUse() {
        if (!(settler.getNavigation() instanceof GroundPathNavigation nav)
            || !nav.canOpenDoors()) {
            return false;
        }
        Path path = nav.getPath();
        if (path == null || path.isDone()) {
            return false;
        }
        doorPos = nextDoor(path);
        return doorPos != null;
    }

    @Override
    public boolean canContinueToUse() {
        return doorPos != null && !passed && openTicks > 0
            && woodenDoorLower(doorPos) != null;
    }

    @Override
    public void start() {
        if (doorPos == null) {
            return;
        }
        openTicks = MAX_OPEN_TICKS;
        passed = false;
        approachX = doorPos.getX() + 0.5D - settler.getX();
        approachZ = doorPos.getZ() + 0.5D - settler.getZ();
        // Opening changes the door's collision shape after the courier's
        // current path was calculated.  Refresh that path exactly once on
        // the closed -> open transition so the same route can cross the now
        // passable doorway in either direction.  Recomputing from tick()
        // would cause needless path churn while several settlers use a door.
        if (setOpen(true)) {
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
        passed = approachX * remainingX + approachZ * remainingZ < 0.0D;
    }

    @Override
    public void stop() {
        if (doorPos != null && noOtherSettlerIsUsingDoor(doorPos)) {
            setOpen(false);
        }
        doorPos = null;
        passed = false;
        openTicks = 0;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
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
        BlockState state = settler.level().getBlockState(candidate);
        if (!(state.getBlock() instanceof DoorBlock)
            || !DoorBlock.isWoodenDoor(settler.level(), candidate)) {
            return null;
        }
        return state.hasProperty(DoorBlock.HALF)
                && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER
            ? candidate.below() : candidate;
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
        BlockState state = settler.level().getBlockState(doorPos);
        if (state.getBlock() instanceof DoorBlock door
            && state.getValue(DoorBlock.OPEN) != open) {
            door.setOpen(settler, settler.level(), state, doorPos, open);
            return true;
        }
        return false;
    }

    private boolean noOtherSettlerIsUsingDoor(BlockPos door) {
        AABB doorway = new AABB(door).inflate(1.25D, 1.0D, 1.25D);
        return settler.level().getEntitiesOfClass(SettlerEntity.class, doorway,
            other -> other != settler && other.isAlive()).isEmpty();
    }
}
