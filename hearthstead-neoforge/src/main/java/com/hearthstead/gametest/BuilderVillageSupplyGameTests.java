package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.builder.Blueprint;
import com.hearthstead.settlement.builder.BlueprintMeta;
import com.hearthstead.settlement.builder.BuildJob;
import com.hearthstead.settlement.builder.BuildJobs;
import com.hearthstead.settlement.builder.BuildPlanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Owner, 26 Sep: "the Builder must build every building from blocks he can
 * get from someone in the village, with no human input". The Builder needs
 * oak stairs nobody has; the village's Carpenter makes them from planks
 * through a crafting order, a Courier moves them, and the Builder sets them
 * -- the player does nothing. Batch {@code builder_supply}.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class BuilderVillageSupplyGameTests {

    @GameTest(template = "empty32", timeoutTicks = 12000, batch = "builder_supply")
    public void theBuilderGetsMissingStairsFromTheCarpenterWithoutAPlayer(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 32, 5);
        var level = arena.level();
        var s = arena.settlement();
        // The village: a Carpenter with planks, a Warehouse with a Courier.
        Building carpentry = GameTestFixtures.register(helper, s, BuildingType.CARPENTER, 20, 4);
        // B4 (27 Sep): only an unlocked workshop takes crafting orders, so the fixture learns
        // the Carpenter's real tech claimant, exactly as a player must.
        com.hearthstead.settlement.development.TechTreeTestGrants.grantClaimants(
            com.hearthstead.settlement.development.Development.of(level, s), null, BuildingType.CARPENTER);
        helper.setBlock(new BlockPos(21, 1, 5), Blocks.CHEST);
        Container shop = (Container) level.getBlockEntity(helper.absolutePos(new BlockPos(21, 1, 5)));
        BuilderTestKit.stock(shop, new ItemStack(Items.OAK_PLANKS, 24));
        SettlerEntity carpenter = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(20, 1, 3));
        carpenter.bindTo(s.id, s.center);
        s.putRecord(carpenter.getUUID(), "Snekker", Profession.NONE);
        helper.assertTrue(Employment.hire(level, s, carpentry, carpenter).ok(), "a Carpenter is hired");
        Building warehouse = GameTestFixtures.register(helper, s, BuildingType.WAREHOUSE, 20, 20);
        helper.setBlock(new BlockPos(21, 1, 21), Blocks.CHEST);
        SettlerEntity courier = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(20, 1, 19));
        courier.bindTo(s.id, s.center);
        s.putRecord(courier.getUUID(), "Bud", Profession.NONE);
        helper.assertTrue(Employment.hire(level, s, warehouse, courier).ok(), "a Courier is hired");
        // Two oak stairs to set; nobody in the village has a single one.
        BlockState stair = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH);
        BuilderTestKit.Cells c = new BuilderTestKit.Cells().set(0, 0, 0, stair).set(1, 0, 0, stair);
        Blueprint steps = new Blueprint(BuilderTestKit.meta("test_two_stairs", BlueprintMeta.Kind.DEFENSE, null, null, null),
            2, 1, 1, c.list());
        BuildPlanner.Plan plan = BuildPlanner.planBlueprint(level, s, steps,
            helper.absolutePos(new BlockPos(10, 1, 12)), 0, false, null);
        helper.assertTrue(plan.job() != null, "the stairs plan: " + plan.validation().reasonKey());
        BuildJob job = plan.job();
        helper.assertTrue(BuildJobs.commit(level, s, job) == null, "queued");
        BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Tora");
        level.setDayTime(3000);
        helper.onEachTick(() -> {
            if (level.getGameTime() % 40 == 0) {
                com.hearthstead.settlement.request.CraftingOrderService.scan(level, s);
            }
        });
        helper.succeedWhen(() -> {
            BuildJob live = BuilderTestKit.job(level, s, job.id);
            helper.assertTrue(live != null && live.state == BuildJob.State.COMPLETE,
                "the stairs are set with village-made stairs: " + (live == null ? "gone"
                    : live.status + " " + live.statusArgs + " done " + live.doneCount() + "/" + live.size()));
            helper.assertTrue(helper.getBlockState(new BlockPos(10, 1, 12)).is(Blocks.OAK_STAIRS)
                && helper.getBlockState(new BlockPos(11, 1, 12)).is(Blocks.OAK_STAIRS), "both stairs stand");
            helper.assertTrue(BuilderTestKit.count(shop, Items.OAK_PLANKS) < 24, "the Carpenter used planks for them");
        });
    }

    /**
     * A fresh Hamlet: no Carpenter, no Warehouse -- only planks and cobble in
     * the Builder's hut. The house has oak stairs, a door and fences; the
     * Builder makes them himself at the hut ("Builder lager det selv") and
     * never asks the player.
     */
    @GameTest(template = "empty16", timeoutTicks = 12000, batch = "builder_supply")
    public void aHamletBuilderMakesStairsDoorAndFenceHimself(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 6);
        var level = arena.level();
        var s = arena.settlement();
        BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
        BuilderTestKit.Cells c = new BuilderTestKit.Cells()
            .box(0, 0, 0, 4, 0, 4, Blocks.COBBLESTONE.defaultBlockState())
            .box(0, 1, 0, 4, 2, 4, planks)
            .box(1, 1, 1, 3, 2, 3, Blocks.AIR.defaultBlockState());
        BlockState door = Blocks.OAK_DOOR.defaultBlockState()
            .setValue(net.minecraft.world.level.block.DoorBlock.FACING, Direction.SOUTH);
        c.set(2, 1, 0, door.setValue(net.minecraft.world.level.block.DoorBlock.HALF,
            net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER));
        c.set(2, 2, 0, door.setValue(net.minecraft.world.level.block.DoorBlock.HALF,
            net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
        c.set(1, 1, 3, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
        c.set(3, 1, 3, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
        c.set(1, 1, 1, Blocks.OAK_FENCE.defaultBlockState());
        c.set(3, 1, 1, Blocks.OAK_FENCE.defaultBlockState());
        Blueprint hut = new Blueprint(BuilderTestKit.meta("test_hamlet_house", BlueprintMeta.Kind.DEFENSE, null, null, null),
            5, 3, 5, c.list());
        BuildPlanner.Plan plan = BuildPlanner.planBlueprint(level, s, hut,
            helper.absolutePos(new BlockPos(8, 1, 8)), 0, false, null);
        helper.assertTrue(plan.job() != null, "the house plans: " + plan.validation().reasonKey());
        BuildJob job = plan.job();
        helper.assertTrue(BuildJobs.commit(level, s, job) == null, "queued");
        var bill = com.hearthstead.settlement.builder.BuilderMaterials.total(job);
        int wallPlanks = bill.getOrDefault(Items.OAK_PLANKS, 0);
        // Only raw village stock: planks (walls + enough to make the shapes) and cobble.
        BuilderTestKit.stock(arena.chest(), new ItemStack(Items.OAK_PLANKS, wallPlanks + 24),
            new ItemStack(Items.COBBLESTONE, bill.getOrDefault(Items.COBBLESTONE, 0)));
        BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Hallveig");
        level.setDayTime(3000);
        boolean[] askedPlayer = {false};
        helper.onEachTick(() -> {
            BuildJob live = BuilderTestKit.job(level, s, job.id);
            if (live != null && live.status == com.hearthstead.settlement.builder.BuildStatus.NEEDS_PLAYER) {
                askedPlayer[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            BuildJob live = BuilderTestKit.job(level, s, job.id);
            helper.assertTrue(live != null && live.state == BuildJob.State.COMPLETE && live.skippedCount() == 0,
                "the hamlet house completes: " + (live == null ? "gone"
                    : live.status + " " + live.statusArgs + " done " + live.doneCount() + "/" + live.size()));
            helper.assertTrue(!askedPlayer[0], "the Builder never asked the player for anything");
            helper.assertTrue(helper.getBlockState(new BlockPos(9, 2, 11)).is(Blocks.OAK_STAIRS)
                && helper.getBlockState(new BlockPos(10, 2, 8)).is(Blocks.OAK_DOOR)
                && helper.getBlockState(new BlockPos(9, 2, 9)).is(Blocks.OAK_FENCE), "stairs, door and fence stand");
        });
    }
}
