package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.HearthsteadServerConfig;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.block.PlaqueItemData;
import com.hearthstead.building.BuildingType;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.builder.Blueprint;
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
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;

/**
 * SCENARIO lane (26 Sep): the Builder's Sites-tab branches with no test
 * yet (batches {@code scenario_builder_*}): missing materials, pause and
 * resume, reordering the queue, deconstructing a registered building (and
 * the own-hut refusal), and the kill switch turned back on.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class ScenarioBuilderGameTests {
    private static final BlockPos SITE = new BlockPos(9, 1, 9);
    private static final BlockPos SITE_B = new BlockPos(9, 1, 3);

    private static BuildJob commit(GameTestHelper helper, BuilderTestKit.Arena arena, Blueprint print, BlockPos at) {
        BuildPlanner.Plan plan = BuildPlanner.planBlueprint(arena.level(), arena.settlement(), print,
            helper.absolutePos(at), 0, false, null);
        helper.assertTrue(plan.validation().ok() && plan.job() != null, "plan validates: " + plan.validation().reasonKey());
        helper.assertTrue(BuildJobs.commit(arena.level(), arena.settlement(), plan.job()) == null, "job queued");
        return plan.job();
    }

    private static BuildJob live(BuilderTestKit.Arena arena, BuildJob job) {
        return BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
    }

    // ---------------------------------------------------- missing materials --

    @GameTest(template = "empty16", timeoutTicks = 3000, batch = "scenario_builder_missing")
    public void aShortBillWaitsNamesTheItemChargesNothingExtraThenFinishes(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 6);
        // One plank short of the pad's 8.
        BuilderTestKit.stock(arena.chest(), new ItemStack(Items.COBBLESTONE, 9),
            new ItemStack(Items.OAK_PLANKS, 7), new ItemStack(Items.LANTERN, 1));
        BuildJob job = commit(helper, arena, BuilderGameTests.pad(), SITE);
        BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Short");
        boolean[] topped = {false};
        helper.succeedWhen(() -> {
            BuildJob j = live(arena, job);
            helper.assertTrue(j != null, "the site exists");
            if (!topped[0]) {
                helper.assertTrue(j.status == BuildStatus.WAITING_FOR || j.status == BuildStatus.NEEDS_PLAYER,
                    "short one plank: the site waits (status " + j.status + " " + j.statusArgs + ", done "
                        + j.doneCount() + "/" + j.size() + ")");
                helper.assertTrue(String.join(",", j.statusArgs).contains("oak_planks"),
                    "and names the missing item: " + j.statusArgs);
                helper.assertTrue(j.state == BuildJob.State.ACTIVE, "it neither completes nor gives up");
                BuilderTestKit.stock(arena.chest(), new ItemStack(Items.OAK_PLANKS, 1));
                topped[0] = true;
                helper.fail("topped up: wait for completion");
            }
            helper.assertTrue(j.state == BuildJob.State.COMPLETE && j.skippedCount() == 0,
                "with the last plank it completes: " + j.status);
            helper.assertTrue(BuilderTestKit.count(arena.chest(), Items.OAK_PLANKS) == 0
                    && BuilderTestKit.count(arena.chest(), Items.COBBLESTONE) == 0,
                "exactly the bill was used");
        });
    }

    // ------------------------------------------------------ pause / resume --

    @GameTest(template = "empty16", timeoutTicks = 3000, batch = "scenario_builder_pause")
    public void aPausedSiteWaitsAndResumesToCompletion(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 6);
        BuilderTestKit.stock(arena.chest(), new ItemStack(Items.COBBLESTONE, 9),
            new ItemStack(Items.OAK_PLANKS, 8), new ItemStack(Items.LANTERN, 1));
        BuildJob job = commit(helper, arena, BuilderGameTests.pad(), SITE);
        helper.assertTrue(BuildJobs.act(arena.level(), arena.settlement(), job.id, BuildJobs.SiteAction.PAUSE, null)
            .endsWith(".ok"), "pause accepted");
        BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Pausa");
        GameTestTicks.at(helper, 300, () -> {
            BuildJob j = live(arena, job);
            helper.assertTrue(j != null, "the paused site still exists");
            helper.assertTrue(j.doneCount() == 0 && j.status == BuildStatus.PAUSED,
                "paused: nothing built in 300 ticks (" + j.status + ", done " + j.doneCount() + ")");
            helper.assertTrue(BuilderTestKit.count(arena.chest(), Items.COBBLESTONE) == 9, "and nothing taken");
            BuildJobs.act(arena.level(), arena.settlement(), job.id, BuildJobs.SiteAction.RESUME, null);
        });
        helper.succeedWhen(() -> {
            BuildJob j = live(arena, job);
            helper.assertTrue(j != null && helper.getTick() > 300 && j.state == BuildJob.State.COMPLETE,
                "resumed, the site completes: " + j.status);
        });
    }

    // ----------------------------------------------------------- queue order --

    @GameTest(template = "empty16", timeoutTicks = 4000, batch = "scenario_builder_order")
    public void movingASiteUpBuildsItFirst(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 6);
        BuilderTestKit.stock(arena.chest(), new ItemStack(Items.COBBLESTONE, 18),
            new ItemStack(Items.OAK_PLANKS, 16), new ItemStack(Items.LANTERN, 2));
        BuildJob first = commit(helper, arena, BuilderGameTests.pad(), SITE);
        BuildJob second = commit(helper, arena, BuilderGameTests.pad(), SITE_B);
        BuildJobs.act(arena.level(), arena.settlement(), second.id, BuildJobs.SiteAction.UP, null);
        List<BuildJob> queue = BuildSiteSavedData.get(arena.level()).activeJobs(arena.settlement().id);
        helper.assertTrue(live(arena, second).order < live(arena, first).order
                && queue.indexOf(live(arena, second)) < queue.indexOf(live(arena, first)),
            "UP moves the second site ahead of the first (order " + live(arena, second).order + " vs "
                + live(arena, first).order + ")");
        BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Ordna");
        boolean[] secondDoneFirst = {false};
        helper.onEachTick(() -> {
            BuildJob a = live(arena, first);
            BuildJob b = live(arena, second);
            if (b != null && b.state == BuildJob.State.COMPLETE && a != null && a.doneCount() == 0) {
                secondDoneFirst[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(live(arena, first) != null && live(arena, second) != null
                && live(arena, first).state == BuildJob.State.COMPLETE
                && live(arena, second).state == BuildJob.State.COMPLETE, "both sites complete");
            helper.assertTrue(secondDoneFirst[0], "the moved-up site was finished before the other was started");
        });
    }

    // ----------------------------------------------------------- deconstruct --

    @GameTest(template = "empty16", timeoutTicks = 6000, batch = "scenario_builder_deconstruct")
    public void deconstructingAHouseReturnsItsMaterialsAndDissolvesIt(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 7);
        Blueprint cottage = BuilderGameTests.cottage();
        BlockPos origin = helper.absolutePos(SITE);
        for (Blueprint.Cell cell : cottage.cells()) {
            arena.level().setBlock(origin.offset(cell.x(), cell.y(), cell.z()), cell.state(), Block.UPDATE_CLIENTS);
        }
        BlockPos plaquePos = origin.offset(3, 2, 2);
        PlaqueBlockEntity plaque = (PlaqueBlockEntity) arena.level().getBlockEntity(plaquePos);
        helper.assertTrue(plaque.insertPlan(arena.level(),
            PlaqueItemData.stamped(new ItemStack(ModItems.BUILD_PLAN.get()), BuildingType.HOUSE)), "plan fitted");
        Building house = plaque.building(arena.level());
        helper.assertTrue(house != null && house.valid && house.type == BuildingType.HOUSE, "fixture: a registered house");

        BuildPlanner.Plan own = BuildPlanner.planDeconstruct(arena.level(), arena.settlement(), arena.hut(), null);
        helper.assertTrue(own.job() == null && "hearthstead.builder.refuse.own_hut".equals(own.validation().reasonKey()),
            "the Builder never deconstructs his own hut: " + own.validation().reasonKey());

        BuildPlanner.Plan plan = BuildPlanner.planDeconstruct(arena.level(), arena.settlement(), house, null);
        helper.assertTrue(plan.validation().ok() && plan.job() != null, "the house can be deconstructed");
        Map<Item, Integer> refund = plan.validation().materials();
        helper.assertTrue(!refund.isEmpty(), "with a refund list");
        helper.assertTrue(BuildJobs.commit(arena.level(), arena.settlement(), plan.job()) == null, "queued");
        BuildJob job = plan.job();
        BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Riv");
        helper.succeedWhen(() -> {
            BuildJob j = live(arena, job);
            helper.assertTrue(j != null && j.state == BuildJob.State.COMPLETE,
                "the teardown completes: " + (j == null ? "gone" : j.status + " " + j.doneCount() + "/" + j.size()));
            helper.assertTrue(j.skippedCount() == 0, "nothing skipped: " + j.skipReport());
            for (int i = 0; i < j.size(); i++) {
                BlockPos at = j.pos(i);
                helper.assertTrue(arena.level().getBlockState(at).isAir(),
                    "every planned teardown cell is cleared, " + at + " holds " + arena.level().getBlockState(at));
            }
            helper.assertTrue(arena.settlement().buildings.stream().noneMatch(b -> b.id.equals(house.id)),
                "the house is dissolved");
            List<net.minecraft.world.Container> hut = com.hearthstead.settlement.builder.BuilderStock.hutContainers(
                arena.level(), arena.hut());
            for (Map.Entry<Item, Integer> e : refund.entrySet()) {
                int got = com.hearthstead.settlement.builder.BuilderStock.count(hut, e.getKey());
                helper.assertTrue(got >= e.getValue(), "the refund returns " + e.getValue() + " " + e.getKey()
                    + " to the hut, got " + got);
            }
        });
    }

    // ----------------------------------------------------- switch back on --

    @GameTest(template = "empty16", timeoutTicks = 3000, batch = "scenario_builder_switch_on")
    public void aSiteHeldByTheSwitchResumesWhenTurnedBackOn(GameTestHelper helper) {
        HearthsteadServerConfig.BUILDER_ENABLED.set(false);
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 6);
        BuilderTestKit.stock(arena.chest(), new ItemStack(Items.COBBLESTONE, 9),
            new ItemStack(Items.OAK_PLANKS, 8), new ItemStack(Items.LANTERN, 1));
        BuildJob job = commit(helper, arena, BuilderGameTests.pad(), SITE);
        BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Paa");
        GameTestTicks.at(helper, 200, () -> {
            HearthsteadServerConfig.BUILDER_ENABLED.set(true);
            helper.assertTrue(live(arena, job) != null && live(arena, job).doneCount() == 0,
                "switched off: nothing built");
        });
        helper.succeedWhen(() -> {
            BuildJob j = live(arena, job);
            helper.assertTrue(j != null && helper.getTick() > 200 && j.state == BuildJob.State.COMPLETE,
                "switched back on, the kept site completes: " + j.status);
            helper.assertTrue(BuilderTestKit.count(arena.chest(), Items.OAK_PLANKS) == 0
                && BuilderMaterials.total(j).get(Items.OAK_PLANKS) == 8, "exactly the bill was used");
        });
    }
}
