package com.hearthstead.entity.path;

import com.hearthstead.entity.SettlerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/**
 * The village wears its own paths.
 *
 * <p>Where settlers walk the same line often enough, the grass gives up and
 * becomes a dirt path — the same block a shovel makes. Nobody decides to build
 * it and nobody is animated laying it: it is erosion, and it appears exactly
 * where the traffic actually is.
 *
 * <p>Two things make this worth the code. It is the only part of the
 * settlement that <b>draws itself</b>, so a village that has stood for a month
 * looks lived in without the player placing a block; and because
 * {@link RoadNodeEvaluator} then prefers those paths, the routes the village
 * uses most become the routes it uses more — a village that gets tidier by
 * being lived in. Neither reference does anything like it.
 *
 * <h2>What it may never do</h2>
 *
 * <p>It is deliberately hemmed in, because a system that edits the world on its
 * own is one bad rule away from vandalising the player's build:
 *
 * <ul>
 *   <li><b>Grass, and only grass.</b> Never a placed block, never farmland,
 *       never a floor. One block type in, one block type out.
 *   <li><b>Outdoors only</b> — it requires open sky above, which also happens
 *       to be what a dirt path needs to survive.
 *   <li><b>Bounded memory.</b> Footfalls are counted in a capped table that is
 *       pruned rather than allowed to grow with the size of the world, and it
 *       is transient: worth nothing to persist, and cheaper to re-earn.
 * </ul>
 *
 * <p>It does not break "settlers never construct". A building is a room a
 * player made and a plaque approved; this is a footprint.
 */
public final class PathWear {

    /**
     * Footfalls on one block before the grass gives up.
     *
     * <p>Low enough that a route several settlers share shows a track within
     * an in-game week or two, high enough that one settler wandering past does
     * not scar the meadow.
     */
    public static final int FOOTFALLS_TO_WEAR = 25;

    /** Hard ceiling on tracked blocks, so this can never grow without bound. */
    private static final int MAX_TRACKED = 4096;

    /** How far up to look for a roof before calling a block outdoors. */
    private static final int ROOF_SCAN = 8;

    private static final Map<ResourceKey<Level>, Map<Long, Integer>> WEAR =
        new HashMap<>();

    /**
     * Counts one footfall where this settler is standing, and wears the block
     * through if it has been walked enough.
     *
     * <p>Call once per block ENTERED, never per tick: standing still must not
     * dig a hole under a settler who is asleep.
     *
     * @return true if a block was worn through on this step
     */
    public static boolean step(ServerLevel level, SettlerEntity settler) {
        if (!settler.isBound()) {
            return false;
        }
        BlockPos under = settler.blockPosition().below();
        BlockState state = level.getBlockState(under);
        if (!state.is(Blocks.GRASS_BLOCK)) {
            return false;
        }
        if (roofed(level, under)) {
            return false;
        }
        Map<Long, Integer> table = WEAR.computeIfAbsent(
            level.dimension(), key -> new HashMap<>());
        long key = under.asLong();
        int count = table.merge(key, 1, Integer::sum);
        if (count < FOOTFALLS_TO_WEAR) {
            prune(table);
            return false;
        }
        table.remove(key);
        level.setBlockAndUpdate(under, Blocks.DIRT_PATH.defaultBlockState());
        return true;
    }

    /**
     * Whether something stands over this block.
     *
     * <p>Deliberately a short explicit scan rather than {@code canSeeSky}: the
     * sky check reads a heightmap that is not necessarily settled the tick
     * after a block is placed, which made a roofed test floor wear through
     * anyway. Looking up a few blocks is a handful of lookups on the only path
     * that reaches here — a settler stepping onto open grass — and it is true
     * the instant the roof exists.
     *
     * <p>It also means no track is worn under a tree, which is the right
     * answer for the same reason: that is a canopy, not a thoroughfare.
     */
    private static boolean roofed(ServerLevel level, BlockPos under) {
        for (int dy = 1; dy <= ROOF_SCAN; dy++) {
            if (level.getBlockState(under.above(dy)).blocksMotion()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Keeps the table small.
     *
     * <p>Drops the once-only entries first — a settler crossing a meadow on an
     * errand should not hold memory against a route that is walked daily — and
     * only clears wholesale if that was not enough. Losing counts costs a
     * little patience, never correctness.
     */
    private static void prune(Map<Long, Integer> table) {
        if (table.size() <= MAX_TRACKED) {
            return;
        }
        table.values().removeIf(count -> count <= 1);
        if (table.size() > MAX_TRACKED) {
            table.clear();
        }
    }

    /** For tests and for a world unloading. */
    public static void forget(ServerLevel level) {
        WEAR.remove(level.dimension());
    }

    /** How worn a block is, 0 when untouched. Diagnostic only. */
    public static int wearAt(ServerLevel level, BlockPos pos) {
        Map<Long, Integer> table = WEAR.get(level.dimension());
        return table == null ? 0 : table.getOrDefault(pos.asLong(), 0);
    }

    private PathWear() {
    }
}
