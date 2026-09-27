package com.hearthstead.entity.path;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathFinder;

/**
 * Ordinary ground navigation, with {@link RoadNodeEvaluator} in it.
 *
 * <p>Everything else about how a settler walks — doors, water, fall damage —
 * stays vanilla on purpose. The only change is which steps the search prefers.
 */
public class RoadNavigation extends GroundPathNavigation {
    private boolean waitingForDoorPassage;

    @Override
    protected void followThePath() {
        if (path != null && !path.isDone()
                && !DoorPassageReservations.permit(level, mob, path)) {
            // PathNavigation may submit its normal move target after this
            // hook returns, so tick() clears that target after super.tick().
            // The route itself remains intact for the waiting actor.
            waitingForDoorPassage = true;
            return;
        }
        if (path != null && !path.isDone() && mob.onClimbable()) {
            var next = path.getNextNodePos();
            var feet = mob.blockPosition();
            if (next.getX() == feet.getX() && next.getZ() == feet.getZ()
                    && level.getBlockState(next).is(net.minecraft.world.level.block.Blocks.LADDER)) {
                // Ground navigation accepts a one-block Y error. On a ladder
                // that skips the rung before the body physically reaches it.
                if (Math.abs(mob.getY() - next.getY()) < .12) path.advance();
                doStuckDetection(getTempMobPos());
                return;
            }
        }
        super.followThePath();
    }

    @Override
    protected boolean canUpdatePath() {
        return mob.onClimbable() || super.canUpdatePath();
    }

    @Override
    public void tick() {
        waitingForDoorPassage = false;
        DoorPassageReservations.maintain(level, mob);
        super.tick();
        if (waitingForDoorPassage) {
            // Hold at the actor's current physical position only after
            // vanilla has finished choosing its next path-node target.
            // No block, collision shape, position or inventory is changed.
            mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0.0D);
            return;
        }
        if (path == null || path.isDone()) return;
        var next = path.getNextNodePos();
        var feet = mob.blockPosition();
        // Only real, continuous ladders may supply vertical motion. Never
        // propel a grounded worker towards an arbitrary elevated path node.
        if (!level.hasChunkAt(feet) || !level.hasChunkAt(next)
                || !level.getBlockState(feet).is(net.minecraft.world.level.block.Blocks.LADDER)
                || !level.getBlockState(next).is(net.minecraft.world.level.block.Blocks.LADDER)
                || feet.getX() != next.getX() || feet.getZ() != next.getZ()
                || Math.abs(next.getY() - feet.getY()) > 1) return;
        double dy = next.getY() - mob.getY();
        if (Math.abs(dy) < .12) return;
        double vertical = Math.copySign(.12, dy);
        if (!level.noCollision(mob, mob.getBoundingBox().move(0, vertical, 0))) return;
        mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0);
        mob.setDeltaMovement((next.getX() + .5 - mob.getX()) * .15,
            vertical, (next.getZ() + .5 - mob.getZ()) * .15);
        mob.resetFallDistance();
    }

    public RoadNavigation(Mob mob, Level level) {
        super(mob, level);
    }

    @Override
    protected PathFinder createPathFinder(int maxVisitedNodes) {
        this.nodeEvaluator = new RoadNodeEvaluator();
        this.nodeEvaluator.setCanPassDoors(true);
        // Closed wooden doors must count as walkable or every path ENDS at
        // the door line and OpenDoorGoal never sees a door node to open.
        // Proven live (20260825T183505Z): a courier froze at the warehouse
        // door and two settlers froze at their own house doors at bedtime,
        // all at the same 0.31-block standoff, while the one whose path
        // happened to need no door slept fine. canPassDoors alone only
        // permits OPEN doors.
        this.nodeEvaluator.setCanOpenDoors(true);
        return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
    }
}

