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
        return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
    }
}
