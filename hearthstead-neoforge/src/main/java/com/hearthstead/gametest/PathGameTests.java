package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.path.PathWear;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Path;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Settlers keep to the road, and the road appears where they walk.
 *
 * <p>Owner's ask, 2026-08-25: <i>"Vil også at villagerne skal følge stier etter
 * beste evne. Vakter slipper selvfølgelig om det er mobs i nærheten."</i>
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class PathGameTests {

    private static void meadow(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.GRASS_BLOCK);
                for (int y = 1; y <= 3; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }

    private static SettlerEntity settler(GameTestHelper helper, BlockPos rel) {
        Settlement s = new Settlement(UUID.randomUUID(), "Testholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), rel);
        settler.setSettlerName("Gjenger");
        settler.bindTo(s.id, s.center);
        return settler;
    }

    /**
     * The point of the whole feature: a settler accepts a longer walk to stay
     * on the path a player dug with a shovel.
     *
     * <p>The road here is a deliberate detour — two steps aside, across, and
     * two steps back, against a straight line over grass. If the preference
     * were merely cosmetic the pathfinder would cut across and this fails.
     */
    @GameTest(template = "empty16", timeoutTicks = 300)
    public void settlersTakeTheLongWayRoundToStayOnTheRoad(GameTestHelper helper) {
        meadow(helper, 16);
        // A road from (2,8) north to row 6, along row 6, and back down at x=13.
        for (int z = 6; z <= 8; z++) {
            helper.setBlock(new BlockPos(2, 0, z), Blocks.DIRT_PATH);
            helper.setBlock(new BlockPos(13, 0, z), Blocks.DIRT_PATH);
        }
        for (int x = 2; x <= 13; x++) {
            helper.setBlock(new BlockPos(x, 0, 6), Blocks.DIRT_PATH);
        }

        SettlerEntity walker = settler(helper, new BlockPos(2, 1, 8));
        BlockPos target = helper.absolutePos(new BlockPos(13, 1, 8));

        // Asked for on a later tick, not at tick zero: a settler spawned this
        // instant is not yet standing on anything, and navigation for a mob
        // that is still falling returns null.
        helper.succeedWhen(() -> {
            Path path = walker.getNavigation().createPath(target, 1);
            helper.assertTrue(path != null, "a walkable meadow must yield a path");
            int onRoad = 0;
            int total = path.getNodeCount();
            for (int i = 0; i < total; i++) {
                var node = path.getNode(i);
                if (helper.getLevel().getBlockState(
                    new BlockPos(node.x, node.y - 1, node.z)).is(Blocks.DIRT_PATH)) {
                    onRoad++;
                }
            }
            helper.assertTrue(total > 12,
                "the straight line is about 11 steps; taking the road must cost "
                    + "more than that, got " + total);
            helper.assertTrue(onRoad * 2 > total,
                "most of the walk must be on the road, got " + onRoad
                    + " of " + total);
        });
    }

    /** Nobody follows the road with a raider in the wheat. */
    @GameTest(template = "empty16", timeoutTicks = 200)
    public void nobodyFollowsTheRoadWhileFightingOrFleeing(GameTestHelper helper) {
        meadow(helper, 16);
        SettlerEntity guard = settler(helper, new BlockPos(4, 1, 4));
        guard.assignProfession(Profession.GUARD);

        helper.assertTrue(guard.prefersRoads(),
            "a guard on an ordinary patrol keeps to the road like everyone else");

        guard.setActivity(SettlerActivity.COMBAT);
        helper.assertFalse(guard.prefersRoads(),
            "a guard in combat takes the straight line");

        guard.setActivity(SettlerActivity.FLEEING);
        helper.assertFalse(guard.prefersRoads(),
            "and so does anyone running for their life");

        guard.setActivity(SettlerActivity.IDLE);
        helper.assertTrue(guard.prefersRoads(), "and goes back to the road after");
        helper.succeed();
    }

    /**
     * The village draws its own map: grass walked often enough becomes a path,
     * with nobody building anything.
     */
    @GameTest(template = "empty16", timeoutTicks = 200)
    public void feetWearGrassIntoAPath(GameTestHelper helper) {
        meadow(helper, 16);
        PathWear.forget(helper.getLevel());
        SettlerEntity walker = settler(helper, new BlockPos(5, 1, 5));
        BlockPos under = new BlockPos(5, 0, 5);

        for (int i = 0; i < PathWear.FOOTFALLS_TO_WEAR - 1; i++) {
            PathWear.step(helper.getLevel(), walker);
        }
        helper.assertBlockPresent(Blocks.GRASS_BLOCK, under);

        boolean worn = PathWear.step(helper.getLevel(), walker);

        helper.assertTrue(worn, "the last footfall must wear the grass through");
        helper.assertBlockPresent(Blocks.DIRT_PATH, under);
        PathWear.forget(helper.getLevel());
        helper.succeed();
    }

    /**
     * The guard rails on a system that edits the world by itself: grass only,
     * outdoors only. A floor a player laid must never be worn away.
     */
    @GameTest(template = "empty16", timeoutTicks = 200)
    public void wearTouchesNothingButOpenGrass(GameTestHelper helper) {
        meadow(helper, 16);
        PathWear.forget(helper.getLevel());

        // A player's stone floor: walked on forever, never worn.
        helper.setBlock(new BlockPos(5, 0, 5), Blocks.STONE_BRICKS);
        SettlerEntity onStone = settler(helper, new BlockPos(5, 1, 5));
        for (int i = 0; i < PathWear.FOOTFALLS_TO_WEAR * 2; i++) {
            PathWear.step(helper.getLevel(), onStone);
        }
        helper.assertBlockPresent(Blocks.STONE_BRICKS, new BlockPos(5, 0, 5));

        // Grass under a roof is somebody's floor too.
        helper.setBlock(new BlockPos(9, 3, 9), Blocks.STONE_BRICKS);
        SettlerEntity indoors = settler(helper, new BlockPos(9, 1, 9));
        for (int i = 0; i < PathWear.FOOTFALLS_TO_WEAR * 2; i++) {
            PathWear.step(helper.getLevel(), indoors);
        }
        helper.assertBlockPresent(Blocks.GRASS_BLOCK, new BlockPos(9, 0, 9));

        PathWear.forget(helper.getLevel());
        helper.succeed();
    }
}
