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
