package com.hearthstead.entity.path;

import com.hearthstead.entity.SettlerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * Settlers keep to the road.
 *
 * <p>A village where everyone cuts diagonally across the wheat looks like a
 * crowd of pathfinders; a village where they follow the path the player dug
 * with a shovel looks like a village. This makes the second one true, and it
 * costs one block lookup per candidate step.
 *
 * <p>The road is <b>{@link Blocks#DIRT_PATH}</b> — what a shovel makes out of
 * grass, and nothing else. That was the owner's choice and it is the right one
 * for a mod with no blocks of its own to teach: the player already knows how to
 * build one, it works in a world they started before installing this, and it
 * needs no recipe, no research and no explanation.
 *
 * <h2>How the preference is expressed</h2>
 *
 * <p>Vanilla's pathfinder scores a step by {@code costMalus}, so a preference
 * for roads is really a <b>penalty for everything else</b>. Every candidate
 * step that does not land on a path gets {@link #OFF_ROAD_MALUS} added, which
 * makes the pathfinder happily walk a good deal further to stay on the path
 * and still cut across when the detour is genuinely absurd. It bends the route;
 * it does not put a wall around it.
 *
 * <h2>Guards chasing something are exempt</h2>
 *
 * <p>Nobody follows the road while a raider is in the wheat. When
 * {@link SettlerEntity#prefersRoads()} is false — a guard with a target — the
 * penalty is not applied at all and they take the straight line, which is what
 * you would do.
 */
public class RoadNodeEvaluator extends WalkNodeEvaluator {

    /**
     * How much a step off the road costs, in the pathfinder's own units.
     *
     * <p>One is roughly the cost of a single ordinary step, so at 1.5 a settler
     * will accept a detour of about half again as many steps to stay on a path
     * — visible without being obsessive. Larger values start producing settlers
     * who walk three sides of a square to avoid one metre of grass.
     */
    public static final float OFF_ROAD_MALUS = 1.5F;

    @Override
    public int getNeighbors(Node[] outputArray, Node node) {
        int found = super.getNeighbors(outputArray, node);
        if (!wantsRoads()) {
            return found;
        }
        for (int i = 0; i < found; i++) {
            Node candidate = outputArray[i];
            if (candidate != null && !onRoad(candidate)) {
                candidate.costMalus += OFF_ROAD_MALUS;
            }
        }
        return found;
    }

    private boolean wantsRoads() {
        // `mob` and `currentContext` are the evaluator's own fields, set by
        // prepare() for the duration of one search.
        return mob instanceof SettlerEntity settler && settler.prefersRoads();
    }

    /** A step is on the road when the block it stands on is a dirt path. */
    private boolean onRoad(Node node) {
        if (currentContext == null) {
            return false;
        }
        return currentContext
            .getBlockState(new BlockPos(node.x, node.y - 1, node.z))
            .is(Blocks.DIRT_PATH);
    }
}
