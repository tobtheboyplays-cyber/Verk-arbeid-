package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.builder.Blueprint;
import com.hearthstead.settlement.builder.BlueprintMeta;
import com.hearthstead.settlement.builder.BuildJob;
import com.hearthstead.settlement.builder.BuildJobs;
import com.hearthstead.settlement.builder.BuildPlanner;
import com.hearthstead.settlement.builder.BuildSiteSavedData;
import com.hearthstead.settlement.builder.BuildStatus;
import com.hearthstead.settlement.builder.BuilderMaterials;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * Owner's second fear, 26 Sep: "the mix between player-built and
 * builder-built buildings". The rules (plan/BUILDER-PLAYER-RULES.md): the
 * Builder never removes or changes a block a player placed unless the player
 * explicitly allows it for that site; it only adds; a player's changes to a
 * Builder building are never "fixed back"; a new plan never cuts into a
 * registered building. Batch {@code builder_mixed}.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class BuilderMixedGameTests {

    private static final BlockPos SITE = new BlockPos(8, 1, 8);

    /** 3x3 plank pad two layers high (18 blocks), a simple Builder job. */
    static Blueprint block3() {
        BuilderTestKit.Cells c = new BuilderTestKit.Cells()
            .box(0, 0, 0, 2, 1, 2, Blocks.OAK_PLANKS.defaultBlockState());
        return new Blueprint(BuilderTestKit.meta("test_block3", BlueprintMeta.Kind.DEFENSE, null, null, null),
            3, 2, 3, c.list());
    }

    /** A plan over two of the player's blocks lists them and never removes them without leave. */
    @GameTest(template = "empty16", timeoutTicks = 4000, batch = "builder_mixed")
    public void aPlanOverThePlayersBlocksNeverRemovesThem(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 5);
        helper.setBlock(SITE.offset(1, 0, 1), Blocks.GLASS);
        helper.setBlock(SITE.offset(2, 1, 2), Blocks.BOOKSHELF);
        BuildPlanner.Plan plan = BuildPlanner.planBlueprint(arena.level(), arena.settlement(), block3(),
            helper.absolutePos(SITE), 0, false, null);
        helper.assertTrue(plan.validation().ok(), "the plan is allowed (with the blocks listed)");
        helper.assertTrue(plan.validation().playerBlockCount() == 2,
            "the confirm sheet lists exactly the player's 2 blocks, got " + plan.validation().playerBlockCount());
        BuildJob job = plan.job();
        helper.assertTrue(BuildJobs.commit(arena.level(), arena.settlement(), job) == null, "queued");
        for (Map.Entry<Item, Integer> e : BuilderMaterials.total(job).entrySet()) {
            BuilderTestKit.stock(arena.chest(), new ItemStack(e.getKey(), e.getValue()));
        }
        BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Unn");
        helper.succeedWhen(() -> {
            BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
            helper.assertTrue(live != null && live.status == BuildStatus.BLOCKED_PLAYER_BLOCK,
                "the site waits on the player's decision: " + (live == null ? "gone" : live.status + " " + live.statusArgs));
            helper.assertTrue(helper.getBlockState(SITE.offset(1, 0, 1)).is(Blocks.GLASS)
                && helper.getBlockState(SITE.offset(2, 1, 2)).is(Blocks.BOOKSHELF), "the player's blocks stand");
            helper.assertTrue(BuilderTestKit.count(arena.chest(), Items.OAK_PLANKS) == 2,
                "the two planks for the player's cells were never used, "
                    + BuilderTestKit.count(arena.chest(), Items.OAK_PLANKS) + " left");
        });
    }

    /** A new plan that cuts into a registered building is refused, by name. */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "builder_mixed")
    public void aPlanIntoARegisteredBuildingIsRefused(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 5);
        Building shop = GameTestFixtures.register(helper, arena.settlement(), BuildingType.HOUSE, 8, 8);
        BuildPlanner.Plan plan = BuildPlanner.planBlueprint(arena.level(), arena.settlement(), block3(),
            helper.absolutePos(new BlockPos(9, 1, 9)), 0, false, null);
        helper.assertTrue(!plan.validation().ok() && plan.job() == null
                && "hearthstead.builder.refuse.overlaps_building".equals(plan.validation().reasonKey()),
            "refused with the building named, got " + plan.validation().reasonKey() + " " + plan.validation().reasonArgs());
        helper.assertTrue(shop.valid, "the building stands untouched");
        helper.succeed();
    }

    /**
     * The player works in the Builder's site: a SAME block set by hand is
     * counted done and its item stays in stock; a DIFFERENT block is never
     * overwritten. No loop, no double block, exact counts.
     */
    @GameTest(template = "empty16", timeoutTicks = 4000, batch = "builder_mixed")
    public void aPlayerBuildingInTheSiteIsRespectedAndCountedExactly(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 5);
        BuildPlanner.Plan plan = BuildPlanner.planBlueprint(arena.level(), arena.settlement(), block3(),
            helper.absolutePos(SITE), 0, false, null);
        BuildJob job = plan.job();
        helper.assertTrue(BuildJobs.commit(arena.level(), arena.settlement(), job) == null, "queued");
        int bill = BuilderMaterials.total(job).getOrDefault(Items.OAK_PLANKS, 0);
        helper.assertTrue(bill == 18, "bill 18 planks, got " + bill);
        BuilderTestKit.stock(arena.chest(), new ItemStack(Items.OAK_PLANKS, bill));
        // Before the Builder arrives the player sets one plank himself (upper
        // corner) and puts a stone block into another cell.
        helper.setBlock(SITE.offset(0, 1, 0), Blocks.OAK_PLANKS);
        helper.setBlock(SITE.offset(2, 1, 0), Blocks.BRICKS);
        BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Ivar");
        helper.succeedWhen(() -> {
            BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
            helper.assertTrue(live != null && live.exhausted() && live.blockedCount() == 1,
                "every cell is done except the player's stone: "
                    + (live == null ? "gone" : live.status + " done " + live.doneCount() + "/" + live.size()
                    + " blocked " + live.blockedCount()));
            helper.assertTrue(helper.getBlockState(SITE.offset(2, 1, 0)).is(Blocks.BRICKS), "the bricks stand");
            helper.assertTrue(BuilderTestKit.count(arena.chest(), Items.OAK_PLANKS) == 2,
                "16 used: the hand-set plank and the stone's cell took none, left "
                    + BuilderTestKit.count(arena.chest(), Items.OAK_PLANKS));
        });
    }

    /**
     * A Builder building the player then renovates (a wall block out, one
     * swapped for glass): nothing rebuilds it, and the plaque's re-survey
     * judges the room as it now is.
     */
    @GameTest(template = "empty16", timeoutTicks = 12000, batch = "builder_mixed")
    public void aPlayersRenovationOfABuilderHouseIsNeverUndone(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 7);
        BuildPlanner.Plan plan = BuildPlanner.planBlueprint(arena.level(), arena.settlement(),
            BuilderGameTests.cottage(), helper.absolutePos(SITE), 0, false, null);
        BuildJob job = plan.job();
        helper.assertTrue(BuildJobs.commit(arena.level(), arena.settlement(), job) == null, "queued");
        for (Map.Entry<Item, Integer> e : BuilderMaterials.total(job).entrySet()) {
            BuilderTestKit.stock(arena.chest(), new ItemStack(e.getKey(), e.getValue() + 8));
        }
        BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Brynhild");
        BlockPos plaquePos = helper.absolutePos(SITE.offset(3, 2, 2));
        BlockPos window = SITE.offset(0, 2, 2);
        BlockPos glass = SITE.offset(4, 2, 1);
        long[] renovatedAt = {-1L};
        helper.onEachTick(() -> {
            BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
            if (renovatedAt[0] < 0 && live != null && live.state == BuildJob.State.COMPLETE) {
                helper.setBlock(window, Blocks.AIR);
                helper.setBlock(glass, Blocks.GLASS);
                renovatedAt[0] = arena.level().getGameTime();
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(renovatedAt[0] > 0 && arena.level().getGameTime() - renovatedAt[0] > 1200,
                "the renovation stood for a minute of game time");
            helper.assertTrue(helper.getBlockState(window).isAir() && helper.getBlockState(glass).is(Blocks.GLASS),
                "nothing rebuilt the player's change");
            for (BuildJob other : BuildSiteSavedData.get(arena.level()).activeJobs(arena.settlement().id)) {
                helper.assertTrue(other.id.equals(job.id), "no new job appeared to undo it: " + other.label);
            }
            if (arena.level().getBlockEntity(plaquePos) instanceof PlaqueBlockEntity plaque) {
                plaque.survey(arena.level());
                Building house = plaque.building(arena.level());
                helper.assertTrue(house != null && house.type == BuildingType.HOUSE,
                    "the plaque still knows its house after the survey");
            }
        });
    }

    /** A player extension on a Builder house: one building, a bigger room, no second registration. */
    @GameTest(template = "empty16", timeoutTicks = 12000, batch = "builder_mixed")
    public void aPlayerExtensionMakesTheSameHouseBigger(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 7);
        BuildPlanner.Plan plan = BuildPlanner.planBlueprint(arena.level(), arena.settlement(),
            BuilderGameTests.cottage(), helper.absolutePos(SITE), 0, false, null);
        BuildJob job = plan.job();
        helper.assertTrue(BuildJobs.commit(arena.level(), arena.settlement(), job) == null, "queued");
        for (Map.Entry<Item, Integer> e : BuilderMaterials.total(job).entrySet()) {
            BuilderTestKit.stock(arena.chest(), new ItemStack(e.getKey(), e.getValue()));
        }
        BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Sindre");
        BlockPos plaquePos = helper.absolutePos(SITE.offset(3, 2, 2));
        int[] before = {-1};
        boolean[] extended = {false};
        Map<String, Integer> result = new HashMap<>();
        helper.onEachTick(() -> {
            BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
            if (extended[0] || live == null || live.state != BuildJob.State.COMPLETE
                || !(arena.level().getBlockEntity(plaquePos) instanceof PlaqueBlockEntity plaque)) {
                return;
            }
            Building house = plaque.building(arena.level());
            if (house == null || !house.valid) {
                return;
            }
            before[0] = house.bounds.getXSpan() * house.bounds.getZSpan();
            // Annex to the west: 3 deep, same height, then open the shared wall.
            BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
            for (int x = -3; x <= -1; x++) {
                for (int z = 0; z <= 4; z++) {
                    helper.setBlock(SITE.offset(x, 0, z), Blocks.COBBLESTONE);
                    helper.setBlock(SITE.offset(x, 3, z), planks);
                    for (int y = 1; y <= 2; y++) {
                        boolean edge = x == -3 || z == 0 || z == 4;
                        helper.setBlock(SITE.offset(x, y, z), edge ? planks : Blocks.AIR.defaultBlockState());
                    }
                }
            }
            helper.setBlock(SITE.offset(0, 1, 2), Blocks.AIR);
            helper.setBlock(SITE.offset(0, 2, 2), Blocks.AIR);
            plaque.survey(arena.level());
            Building after = plaque.building(arena.level());
            int houses = 0;
            for (Building b : arena.settlement().buildings) {
                if (b.type == BuildingType.HOUSE && b.valid) {
                    houses++;
                }
            }
            result.put("houses", houses);
            result.put("area", after == null || after.bounds == null ? -1
                : after.bounds.getXSpan() * after.bounds.getZSpan());
            result.put("same", after != null && after.id.equals(house.id) ? 1 : 0);
            extended[0] = true;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(extended[0], "the house was finished and extended");
            helper.assertTrue(result.get("same") == 1, "the plaque keeps the same building");
            helper.assertTrue(result.get("houses") == 1, "exactly one house registered, got " + result.get("houses"));
            helper.assertTrue(result.get("area") > before[0], "the room grew: " + before[0] + " -> " + result.get("area"));
        });
    }
}
