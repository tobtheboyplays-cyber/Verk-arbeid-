package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.HearthsteadServerConfig;
import com.hearthstead.block.PlaqueBlock;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.block.PlaqueItemData;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.builder.Blueprint;
import com.hearthstead.settlement.builder.BlueprintMeta;
import com.hearthstead.settlement.builder.BuildJob;
import com.hearthstead.settlement.builder.BuildJobs;
import com.hearthstead.settlement.builder.BuildPlanner;
import com.hearthstead.settlement.builder.BuildSiteSavedData;
import com.hearthstead.settlement.builder.BuildStatus;
import com.hearthstead.settlement.builder.BuilderMaterials;
import com.hearthstead.settlement.builder.UpgradePlanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;

/**
 * BUILDER lane core GameTests (batch {@code builder_core}): exact blocks and
 * exact materials, save/reload mid-build, never overwriting a player block,
 * a defense line, auto-registration through the plaque, an Upgrade Order
 * that raises a level, Courier delivery into the hut, and the kill switch.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class BuilderGameTests {

    private static final BlockPos SITE = new BlockPos(9, 1, 9);

    /** 3x3 cobble pad, a plank ring on it, a lantern in the middle. */
    static Blueprint pad() {
        BuilderTestKit.Cells c = new BuilderTestKit.Cells()
            .box(0, 0, 0, 2, 0, 2, Blocks.COBBLESTONE.defaultBlockState())
            .box(0, 1, 0, 2, 1, 2, Blocks.OAK_PLANKS.defaultBlockState())
            .set(1, 1, 1, Blocks.LANTERN.defaultBlockState());
        return new Blueprint(BuilderTestKit.meta("test_pad", BlueprintMeta.Kind.DEFENSE, null, null, null),
            3, 2, 3, c.list());
    }

    private static BuildJob commit(GameTestHelper helper, BuilderTestKit.Arena arena, BuildPlanner.Plan plan) {
        helper.assertTrue(plan.validation().ok(), "plan must validate: " + plan.validation().reasonKey());
        helper.assertTrue(plan.job() != null, "plan must produce a job");
        helper.assertTrue(BuildJobs.commit(arena.level(), arena.settlement(), plan.job()) == null,
            "job must enter the queue");
        return plan.job();
    }

    private static void assertPadBuilt(GameTestHelper helper) {
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                BlockPos floor = SITE.offset(x, 0, z);
                helper.assertTrue(helper.getBlockState(floor).is(Blocks.COBBLESTONE), "cobble at " + floor);
                BlockPos wall = SITE.offset(x, 1, z);
                if (x == 1 && z == 1) {
                    helper.assertTrue(helper.getBlockState(wall).is(Blocks.LANTERN), "lantern in the middle");
                } else {
                    helper.assertTrue(helper.getBlockState(wall).is(Blocks.OAK_PLANKS), "plank at " + wall);
                }
            }
        }
        // Nothing outside the footprint: a ring around the site stays air.
        for (int x = -1; x <= 3; x++) {
            for (int z = -1; z <= 3; z++) {
                if (x >= 0 && x <= 2 && z >= 0 && z <= 2) {
                    continue;
                }
                for (int y = 0; y <= 2; y++) {
                    helper.assertTrue(helper.getBlockState(SITE.offset(x, y, z)).isAir(),
                        "nothing may be built outside the blueprint at " + SITE.offset(x, y, z));
                }
            }
        }
    }

    // ------------------------------------------------------------------ 1 ---

    @GameTest(template = "empty16", timeoutTicks = 3000, batch = "builder_core")
    public void blueprintPlacesExactlyItsBlocksAndConsumesExactlyItsMaterials(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 6);
        BuilderTestKit.stock(arena.chest(), new ItemStack(Items.COBBLESTONE, 13),
            new ItemStack(Items.OAK_PLANKS, 13), new ItemStack(Items.LANTERN, 1));
        BuildJob job = commit(helper, arena, BuildPlanner.planBlueprint(arena.level(), arena.settlement(), pad(),
            helper.absolutePos(SITE), 0, false, null));
        Map<Item, Integer> bill = BuilderMaterials.total(job);
        helper.assertTrue(bill.get(Items.COBBLESTONE) == 9 && bill.get(Items.OAK_PLANKS) == 8
            && bill.get(Items.LANTERN) == 1 && bill.size() == 3, "bill must be 9 cobble, 8 planks, 1 lantern: " + bill);
        SettlerEntity builder = BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Ada");
        helper.succeedWhen(() -> {
            BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
            helper.assertTrue(live != null && live.state == BuildJob.State.COMPLETE,
                "job must complete; status " + (live == null ? "gone" : live.status + " " + live.statusArgs));
            assertPadBuilt(helper);
            helper.assertTrue(BuilderTestKit.count(arena.chest(), Items.COBBLESTONE) == 4,
                "exactly 9 cobble consumed, 4 left: " + BuilderTestKit.count(arena.chest(), Items.COBBLESTONE));
            helper.assertTrue(BuilderTestKit.count(arena.chest(), Items.OAK_PLANKS) == 5,
                "exactly 8 planks consumed, 5 left: " + BuilderTestKit.count(arena.chest(), Items.OAK_PLANKS));
            helper.assertTrue(BuilderTestKit.count(arena.chest(), Items.LANTERN) == 0, "the lantern was used");
            helper.assertTrue(builder.bag.isEmpty(), "nothing left in the Builder's sack");
            helper.assertTrue(live.doneCount() == live.size() && live.skippedCount() == 0,
                "every step done, none skipped");
        });
    }

    // ------------------------------------------------------------------ 2 ---

    @GameTest(template = "empty16", timeoutTicks = 3600, batch = "builder_core")
    public void saveReloadMidBuildResumesWithoutDoubleCharging(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 6);
        BuilderTestKit.stock(arena.chest(), new ItemStack(Items.COBBLESTONE, 9),
            new ItemStack(Items.OAK_PLANKS, 8), new ItemStack(Items.LANTERN, 1));
        BuildJob job = commit(helper, arena, BuildPlanner.planBlueprint(arena.level(), arena.settlement(), pad(),
            helper.absolutePos(SITE), 0, false, null));
        BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Bo");
        ServerLevel level = arena.level();
        boolean[] reloaded = {false};
        helper.onEachTick(() -> {
            if (reloaded[0]) {
                return;
            }
            BuildJob live = BuilderTestKit.job(level, arena.settlement(), job.id);
            if (live != null && live.doneCount() >= 6) {
                // Simulate a restart: the whole site file goes to NBT and back,
                // and the live job is replaced by the loaded copy.
                BuildSiteSavedData data = BuildSiteSavedData.get(level);
                CompoundTag saved = data.save(new CompoundTag(), level.registryAccess());
                BuildSiteSavedData loaded = BuildSiteSavedData.load(saved, level.registryAccess());
                BuildJob copy = loaded.job(arena.settlement().id, job.id);
                helper.assertTrue(copy != null && copy.doneCount() == live.doneCount()
                    && copy.size() == live.size(), "the loaded site matches the saved one");
                List<BuildJob> list = data.jobs(arena.settlement().id);
                list.set(list.indexOf(live), copy);
                reloaded[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(reloaded[0], "the reload must have happened mid-build");
            BuildJob live = BuilderTestKit.job(level, arena.settlement(), job.id);
            helper.assertTrue(live != null && live.state == BuildJob.State.COMPLETE, "job completes after reload");
            assertPadBuilt(helper);
            helper.assertTrue(BuilderTestKit.count(arena.chest(), Items.COBBLESTONE) == 0
                && BuilderTestKit.count(arena.chest(), Items.OAK_PLANKS) == 0
                && BuilderTestKit.count(arena.chest(), Items.LANTERN) == 0,
                "exactly the bill was used, nothing charged twice");
        });
    }

    // ------------------------------------------------------------------ 3 ---

    @GameTest(template = "empty16", timeoutTicks = 3600, batch = "builder_core")
    public void neverOverwritesAPlayerBlockUntilAllowed(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 6);
        BlockPos shelf = SITE.offset(2, 0, 2);
        helper.setBlock(shelf, Blocks.BOOKSHELF);
        BuilderTestKit.stock(arena.chest(), new ItemStack(Items.COBBLESTONE, 9),
            new ItemStack(Items.OAK_PLANKS, 8), new ItemStack(Items.LANTERN, 1));
        BuildPlanner.Plan plan = BuildPlanner.planBlueprint(arena.level(), arena.settlement(), pad(),
            helper.absolutePos(SITE), 0, false, null);
        helper.assertTrue(plan.validation().playerBlockCount() == 1
            && plan.validation().playerBlocks().contains(helper.absolutePos(shelf)),
            "validation names the bookshelf in the way");
        BuildJob job = commit(helper, arena, plan);
        BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Cy");
        boolean[] allowed = {false};
        helper.succeedWhen(() -> {
            BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
            helper.assertTrue(live != null, "job exists");
            if (!allowed[0]) {
                helper.assertTrue(helper.getBlockState(shelf).is(Blocks.BOOKSHELF), "the bookshelf is never touched");
                BlockPos abs = helper.absolutePos(shelf);
                helper.assertTrue(live.status == BuildStatus.BLOCKED_PLAYER_BLOCK && live.blockedCount() >= 1
                    && live.statusArgs.equals(List.of(String.valueOf(abs.getX()), String.valueOf(abs.getY()),
                        String.valueOf(abs.getZ()))),
                    "the site names exactly the blocking cell: " + live.status + " " + live.statusArgs);
                helper.assertTrue(BuilderTestKit.count(arena.chest(), Items.COBBLESTONE) == 1,
                    "the blocked cell's cobble is not charged");
                BuildJobs.act(arena.level(), arena.settlement(), job.id, BuildJobs.SiteAction.ALLOW_OVERWRITE, null);
                allowed[0] = true;
                helper.fail("now allowed: wait for the overwrite");
            }
            helper.assertTrue(live.state == BuildJob.State.COMPLETE, "completes once allowed");
            helper.assertTrue(helper.getBlockState(shelf).is(Blocks.COBBLESTONE), "the cell is built as planned");
            helper.assertTrue(BuilderTestKit.count(arena.chest(), Items.BOOKSHELF) == 1,
                "the replaced bookshelf is salvaged to the hut as itself");
            helper.assertTrue(BuilderTestKit.count(arena.chest(), Items.COBBLESTONE) == 0, "cobble fully used");
        });
    }

    // ------------------------------------------------------------------ 4 ---

    @GameTest(template = "empty16", timeoutTicks = 4800, batch = "builder_core")
    public void palisadeLineWithGateBuildsAndRegistersItsGate(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 6);
        BlockPos a = new BlockPos(3, 1, 11);
        BlockPos b = new BlockPos(12, 1, 11);
        BuildPlanner.Plan plan = BuildPlanner.planLine(arena.level(), arena.settlement(), helper.absolutePos(a),
            helper.absolutePos(b), BuildPlanner.PALISADE, true, 0, null);
        BuildJob job = commit(helper, arena, plan);
        Map<Item, Integer> bill = BuilderMaterials.total(job);
        for (Map.Entry<Item, Integer> e : bill.entrySet()) {
            BuilderTestKit.stock(arena.chest(), new ItemStack(e.getKey(), e.getValue()));
        }
        helper.assertTrue(job.gates.size() == 1, "one gate planned");
        BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Dag");
        helper.succeedWhen(() -> {
            BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
            helper.assertTrue(live != null && live.state == BuildJob.State.COMPLETE && live.skippedCount() == 0,
                "wall completes: " + (live == null ? "" : live.status + " " + live.statusArgs
                    + " skipped: " + live.skipReport()));
            for (Map.Entry<Item, Integer> e : bill.entrySet()) {
                helper.assertTrue(BuilderTestKit.count(arena.chest(), e.getKey()) == 0,
                    "exactly the bill was used: " + e.getKey());
            }
            BlockPos gate = job.gates.get(0);
            helper.assertTrue(arena.level().getBlockState(gate).is(Blocks.SPRUCE_FENCE_GATE), "gate stands");
            helper.assertTrue(BuildSiteSavedData.gates(arena.level(), arena.settlement().id).contains(gate),
                "the raid lane can find the gate");
            helper.assertTrue("palisade".equals(BuildSiteSavedData.segmentAt(arena.level(), arena.settlement().id,
                helper.absolutePos(a))), "wall blocks are registered as palisade");
        });
    }

    // ------------------------------------------------------------------ 5 ---

    /** A 5x4x5 one-room cottage that satisfies HOUSE level 1, plaque inside. */
    static Blueprint cottage() {
        BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
        BuilderTestKit.Cells c = new BuilderTestKit.Cells()
            .box(0, 0, 0, 4, 0, 4, Blocks.COBBLESTONE.defaultBlockState())
            .box(0, 1, 0, 4, 2, 4, planks)
            .box(1, 1, 1, 3, 2, 3, Blocks.AIR.defaultBlockState())
            .box(0, 3, 0, 4, 3, 4, planks);
        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH);
        c.set(2, 1, 0, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        c.set(2, 2, 0, door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        BlockState bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST);
        c.set(1, 1, 3, bed.setValue(BedBlock.PART, BedPart.FOOT));
        c.set(2, 1, 3, bed.setValue(BedBlock.PART, BedPart.HEAD));
        c.set(3, 1, 1, Blocks.LANTERN.defaultBlockState());
        c.set(3, 2, 2, ModBlocks.PLAQUE.get().defaultBlockState().setValue(PlaqueBlock.FACING, Direction.WEST));
        return new Blueprint(BuilderTestKit.meta("test_cottage", BlueprintMeta.Kind.BUILDING, "house",
            new int[]{3, 2, 2}, "west"), 5, 4, 5, c.list());
    }

    @GameTest(template = "empty16", timeoutTicks = 9000, batch = "builder_core")
    public void cottageRegistersItselfThroughItsPlaqueWhenDone(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 7);
        BuildJob job = commit(helper, arena, BuildPlanner.planBlueprint(arena.level(), arena.settlement(),
            cottage(), helper.absolutePos(SITE), 0, false, null));
        helper.assertTrue("house".equals(job.fitPlanType), "the job knows what it will register as");
        Map<Item, Integer> bill = BuilderMaterials.total(job);
        for (Map.Entry<Item, Integer> e : bill.entrySet()) {
            BuilderTestKit.stock(arena.chest(), new ItemStack(e.getKey(), e.getValue()));
        }
        BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Eir");
        BlockPos plaque = helper.absolutePos(SITE.offset(3, 2, 2));
        helper.succeedWhen(() -> {
            BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
            helper.assertTrue(live != null && live.state == BuildJob.State.COMPLETE && live.skippedCount() == 0,
                "cottage completes, nothing skipped: " + (live == null ? "" : live.status + " " + live.statusArgs
                    + " done " + live.doneCount() + "/" + live.size() + " skipped " + live.skippedCount()
                    + " [" + live.skipReport() + "]"));
            Building house = null;
            if (arena.level().getBlockEntity(plaque) instanceof PlaqueBlockEntity be) {
                Building linked = be.building(arena.level());
                if (linked != null && linked.type == BuildingType.HOUSE && linked.plaquePos.equals(plaque)) {
                    house = linked;
                }
            }
            helper.assertTrue(house != null && house.valid,
                "the finished cottage is a registered, valid HOUSE through its own plaque");
            for (Map.Entry<Item, Integer> e : bill.entrySet()) {
                helper.assertTrue(BuilderTestKit.count(arena.chest(), e.getKey()) == 0,
                    "exactly the bill was used: " + e.getKey() + " left " + BuilderTestKit.count(arena.chest(), e.getKey()));
            }
        });
    }

    // ------------------------------------------------------------------ 6 ---

    @GameTest(template = "empty16", timeoutTicks = 4000, batch = "builder_core")
    public void upgradeOrderAddsTheMissingPiecesAndTheLevelRises(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 7);
        // Hand-build the cottage instantly (a player's own house).
        Blueprint cottage = cottage();
        for (Blueprint.Cell cell : cottage.cells()) {
            if (cell.state().is(ModBlocks.PLAQUE.get())) {
                continue;
            }
            helper.setBlock(SITE.offset(cell.x(), cell.y(), cell.z()), cell.state());
        }
        BlockPos plaqueRel = SITE.offset(3, 2, 2);
        helper.setBlock(plaqueRel, ModBlocks.PLAQUE.get().defaultBlockState().setValue(PlaqueBlock.FACING, Direction.WEST));
        PlaqueBlockEntity plaque = (PlaqueBlockEntity) arena.level().getBlockEntity(helper.absolutePos(plaqueRel));
        helper.assertTrue(plaque.insertPlan(arena.level(),
            PlaqueItemData.stamped(new ItemStack(ModItems.BUILD_PLAN.get()), BuildingType.HOUSE)),
            "the player fits a house plan");
        Building house = plaque.building(arena.level());
        helper.assertTrue(house != null && house.valid && house.level == 1,
            "hand-built cottage registers at level 1, got " + (house == null ? "none" : house.level));
        helper.assertTrue(!house.nextLevelGap.isEmpty(), "level 2 has a gap");
        UpgradePlanner.Result result = UpgradePlanner.plan(arena.level(), arena.settlement(), house, null);
        helper.assertTrue(result != null && result.plan().job() != null, "an Upgrade Order can be planned");
        BuildJob job = commit(helper, arena, result.plan());
        Map<Item, Integer> bill = BuilderMaterials.total(job);
        for (Map.Entry<Item, Integer> e : bill.entrySet()) {
            BuilderTestKit.stock(arena.chest(), new ItemStack(e.getKey(), e.getValue()));
        }
        BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Frode");
        Building target = house;
        helper.succeedWhen(() -> {
            BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
            helper.assertTrue(live != null && live.state == BuildJob.State.COMPLETE,
                "upgrade completes: " + (live == null ? "" : live.status + " " + live.statusArgs));
            helper.assertTrue(target.level == 2, "the checklist level rose to 2, is " + target.level
                + " gap " + target.nextLevelGap);
            for (Map.Entry<Item, Integer> e : bill.entrySet()) {
                helper.assertTrue(BuilderTestKit.count(arena.chest(), e.getKey()) == 0, "used " + e.getKey());
            }
        });
    }

    // ------------------------------------------------------------------ 7 ---

    @GameTest(template = "empty16", timeoutTicks = 7000, batch = "builder_core")
    public void courierDeliversTheMaterialsIntoTheHut(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 6);
        ServerLevel level = arena.level();
        Settlement settlement = arena.settlement();
        // The Hearth stands at the settlement centre (settlers find it by their
        // bound hearth position); Couriers keep its ready-food floor first.
        BlockPos hearthRel = new BlockPos(8, 1, 8);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        var hearth = (com.hearthstead.block.HearthBlockEntity) level.getBlockEntity(helper.absolutePos(hearthRel));
        hearth.bindSettlement(settlement.id);
        hearth.getInventory().setStackInSlot(0, new ItemStack(Items.BREAD, 64));
        Building warehouse = GameTestFixtures.register(helper, settlement, BuildingType.WAREHOUSE, 11, 2);
        helper.setBlock(new BlockPos(12, 1, 3), Blocks.CHEST);
        Container storage = (Container) level.getBlockEntity(helper.absolutePos(new BlockPos(12, 1, 3)));
        BuilderTestKit.stock(storage, new ItemStack(Items.OAK_PLANKS, 40));
        // A 4x5 plank pad: 20 planks, none in the hut.
        BuilderTestKit.Cells c = new BuilderTestKit.Cells().box(0, 0, 0, 3, 0, 4, Blocks.OAK_PLANKS.defaultBlockState());
        Blueprint deck = new Blueprint(BuilderTestKit.meta("test_deck", BlueprintMeta.Kind.DEFENSE, null, null, null),
            4, 1, 5, c.list());
        BuildJob job = commit(helper, arena, BuildPlanner.planBlueprint(level, settlement, deck,
            helper.absolutePos(new BlockPos(10, 1, 10)), 0, false, null));
        BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Gro");
        SettlerEntity courier = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(10, 1, 6));
        courier.bindTo(settlement.id, settlement.center);
        settlement.putRecord(courier.getUUID(), "Courier", Profession.NONE);
        helper.assertTrue(Employment.hire(level, settlement, warehouse, courier).ok(), "courier hired");
        helper.succeedWhen(() -> {
            BuildJob live = BuilderTestKit.job(level, settlement, job.id);
            helper.assertTrue(live != null && live.state == BuildJob.State.COMPLETE,
                "deck completes from delivered planks: " + (live == null ? "" : live.status + " " + live.statusArgs));
            int left = BuilderTestKit.count(storage, Items.OAK_PLANKS);
            int hut = BuilderTestKit.count(arena.chest(), Items.OAK_PLANKS);
            helper.assertTrue(left + hut == 20, "exactly 20 planks left the warehouse and were built: warehouse "
                + left + " hut " + hut);
            helper.assertTrue(courier.bag.isEmpty(), "the courier carries nothing extra");
        });
    }

    // ------------------------------------------------------------ switch ---

    // Own batch: the switch is global, so it must never overlap the others.
    @GameTest(template = "empty16", timeoutTicks = 400, batch = "builder_switch")
    public void builderSwitchedOffIsInert(GameTestHelper helper) {
        boolean before = HearthsteadServerConfig.builderEnabled();
        HearthsteadServerConfig.BUILDER_ENABLED.set(false);
        try {
            BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 6);
            BuilderTestKit.stock(arena.chest(), new ItemStack(Items.COBBLESTONE, 9),
                new ItemStack(Items.OAK_PLANKS, 8), new ItemStack(Items.LANTERN, 1));
            BuildJob job = commit(helper, arena, BuildPlanner.planBlueprint(arena.level(), arena.settlement(), pad(),
                helper.absolutePos(SITE), 0, false, null));
            BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Hild");
            helper.runAfterDelay(300, () -> {
                try {
                    BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
                    helper.assertTrue(live != null && live.state == BuildJob.State.ACTIVE && live.doneCount() == 0,
                        "switched off: the site exists and nothing was done");
                    helper.assertTrue(helper.getBlockState(SITE).isAir(), "no block placed");
                    helper.assertTrue(BuilderTestKit.count(arena.chest(), Items.COBBLESTONE) == 9,
                        "no material moved");
                    helper.succeed();
                } finally {
                    HearthsteadServerConfig.BUILDER_ENABLED.set(before);
                }
            });
        } catch (RuntimeException e) {
            HearthsteadServerConfig.BUILDER_ENABLED.set(before);
            throw e;
        }
    }
}
