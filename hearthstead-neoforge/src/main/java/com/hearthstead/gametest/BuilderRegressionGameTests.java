package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.settlement.builder.Blueprint;
import com.hearthstead.settlement.builder.BlueprintMeta;
import com.hearthstead.settlement.builder.BuildJob;
import com.hearthstead.settlement.builder.BuildJobs;
import com.hearthstead.settlement.builder.BuildPlanner;
import com.hearthstead.settlement.builder.BuildStatus;
import com.hearthstead.settlement.builder.BuilderMaterials;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;

/**
 * Regressions for Codex's T3b review of the Builder's exact-once paths
 * (batch {@code builder_regress}): a dismantled door refunds exactly one
 * door; a single slab never satisfies a double-slab plan; a dismantle leaves
 * a block the player changed and refunds nothing for it.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class BuilderRegressionGameTests {

    private static final BlockPos SITE = new BlockPos(9, 1, 9);

    private static BuildJob commit(GameTestHelper helper, BuilderTestKit.Arena arena, BuildPlanner.Plan plan) {
        helper.assertTrue(plan.validation().ok() && plan.job() != null, "plan: " + plan.validation().reasonKey());
        helper.assertTrue(BuildJobs.commit(arena.level(), arena.settlement(), plan.job()) == null, "queued");
        return plan.job();
    }

    private static void stockBill(BuilderTestKit.Arena arena, BuildJob job) {
        for (Map.Entry<Item, Integer> e : BuilderMaterials.total(job).entrySet()) {
            BuilderTestKit.stock(arena.chest(), new ItemStack(e.getKey(), e.getValue()));
        }
    }

    private static int onGround(GameTestHelper helper, Item item) {
        AABB box = new AABB(helper.absolutePos(BlockPos.ZERO)).expandTowards(16, 8, 16);
        int n = 0;
        for (ItemEntity e : helper.getLevel().getEntitiesOfClass(ItemEntity.class, box)) {
            n += e.getItem().is(item) ? e.getItem().getCount() : 0;
        }
        return n;
    }

    /** A cobble step with a door on it and a double slab beside it. */
    static Blueprint doorAndSlab() {
        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH);
        BuilderTestKit.Cells c = new BuilderTestKit.Cells()
            .box(0, 0, 0, 2, 0, 0, Blocks.COBBLESTONE.defaultBlockState())
            .set(0, 1, 0, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER))
            .set(0, 2, 0, door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER))
            .set(2, 1, 0, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE));
        return new Blueprint(BuilderTestKit.meta("test_door_slab", BlueprintMeta.Kind.DEFENSE, null, null, null),
            3, 3, 1, c.list());
    }

    // P1: door refunded twice on dismantle.
    @GameTest(template = "empty16", timeoutTicks = 5000, batch = "builder_regress")
    public void dismantledDoorRefundsExactlyOneDoor(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 6);
        BuildJob job = commit(helper, arena, BuildPlanner.planBlueprint(arena.level(), arena.settlement(),
            doorAndSlab(), helper.absolutePos(SITE), 0, false, null));
        stockBill(arena, job);
        helper.assertTrue(BuilderMaterials.total(job).get(Items.OAK_DOOR) == 1, "a door costs one door");
        BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Ola");
        boolean[] ordered = {false};
        helper.succeedWhen(() -> {
            BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
            helper.assertTrue(live != null && live.state == BuildJob.State.COMPLETE, "built first");
            if (!ordered[0]) {
                BuildJobs.act(arena.level(), arena.settlement(), job.id, BuildJobs.SiteAction.DISMANTLE, null);
                ordered[0] = true;
                helper.fail("dismantling");
            }
            helper.assertTrue(helper.getBlockState(SITE.above()).isAir() && helper.getBlockState(SITE.above(2)).isAir(),
                "both door halves gone");
            int doors = BuilderTestKit.count(arena.chest(), Items.OAK_DOOR) + onGround(helper, Items.OAK_DOOR);
            helper.assertTrue(doors == 1, "exactly one door back, got " + doors);
            helper.assertTrue(BuilderTestKit.count(arena.chest(), Items.OAK_SLAB) == 2, "the double slab: two slabs");
        });
    }

    // P2: a single slab must not satisfy a double-slab plan.
    @GameTest(template = "empty16", timeoutTicks = 3000, batch = "builder_regress")
    public void singleSlabNeverSatisfiesADoubleSlabPlan(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 6);
        BlockPos slab = SITE.offset(2, 1, 0);
        helper.setBlock(SITE.offset(2, 0, 0), Blocks.COBBLESTONE);
        helper.setBlock(slab, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        BuildPlanner.Plan plan = BuildPlanner.planBlueprint(arena.level(), arena.settlement(), doorAndSlab(),
            helper.absolutePos(SITE), 0, false, null);
        helper.assertTrue(plan.validation().playerBlockCount() == 1,
            "the single slab is reported in the way, not taken as done");
        BuildJob job = commit(helper, arena, plan);
        stockBill(arena, job);
        BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Per");
        helper.succeedWhen(() -> {
            BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
            helper.assertTrue(live != null && live.status == BuildStatus.BLOCKED_PLAYER_BLOCK,
                "the site waits on the player's slab: " + (live == null ? "" : live.status));
            helper.assertTrue(helper.getBlockState(slab).getValue(SlabBlock.TYPE) == SlabType.BOTTOM,
                "the player's single slab is untouched");
            helper.assertTrue(BuilderTestKit.count(arena.chest(), Items.OAK_SLAB) == 2,
                "no slab charged for an unbuilt double slab");
        });
    }

    // P1/P2: dismantle leaves a block whose material the player changed.
    @GameTest(template = "empty16", timeoutTicks = 5000, batch = "builder_regress")
    public void dismantleLeavesAPlayerChangedBlockAndRefundsNothingForIt(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 6);
        BuildJob job = commit(helper, arena, BuildPlanner.planBlueprint(arena.level(), arena.settlement(),
            doorAndSlab(), helper.absolutePos(SITE), 0, false, null));
        stockBill(arena, job);
        BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Rut");
        BlockPos slab = SITE.offset(2, 1, 0);
        boolean[] ordered = {false};
        helper.succeedWhen(() -> {
            BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
            helper.assertTrue(live != null && live.state == BuildJob.State.COMPLETE, "built first");
            if (!ordered[0]) {
                // The player cuts the double slab down to a single one.
                helper.setBlock(slab, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
                BuildJobs.act(arena.level(), arena.settlement(), job.id, BuildJobs.SiteAction.DISMANTLE, null);
                ordered[0] = true;
                helper.fail("dismantling");
            }
            helper.assertTrue(helper.getBlockState(SITE).isAir(), "the rest came down");
            helper.assertTrue(helper.getBlockState(slab).is(Blocks.OAK_SLAB), "the player's slab stays");
            helper.assertTrue(BuilderTestKit.count(arena.chest(), Items.OAK_SLAB) == 0,
                "no slab refunded for a block the player changed");
            helper.assertTrue(BuilderTestKit.count(arena.chest(), Items.COBBLESTONE) == 3
                && BuilderTestKit.count(arena.chest(), Items.OAK_DOOR) == 1, "everything else refunded once");
        });
    }

    // Codex T3b round 2, #1: a complete door already standing is kept.
    @GameTest(template = "empty16", timeoutTicks = 3000, batch = "builder_regress")
    public void standingDoorPairIsKeptNotCleared(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 6);
        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH);
        helper.setBlock(SITE, Blocks.COBBLESTONE);
        helper.setBlock(SITE.above(), door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        helper.setBlock(SITE.above(2), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        BuildPlanner.Plan plan = BuildPlanner.planBlueprint(arena.level(), arena.settlement(), doorAndSlab(),
            helper.absolutePos(SITE), 0, false, null);
        helper.assertTrue(plan.validation().playerBlockCount() == 0, "a correct door is not in the way");
        BuildJob job = commit(helper, arena, plan);
        helper.assertTrue(!BuilderMaterials.total(job).containsKey(Items.OAK_DOOR), "the standing door costs nothing");
        stockBill(arena, job);
        BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Sol");
        helper.succeedWhen(() -> {
            BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
            helper.assertTrue(live != null && live.state == BuildJob.State.COMPLETE, "completes");
            helper.assertTrue(helper.getBlockState(SITE.above()).is(Blocks.OAK_DOOR)
                && helper.getBlockState(SITE.above(2)).is(Blocks.OAK_DOOR), "both halves still stand");
            helper.assertTrue(BuilderTestKit.count(arena.chest(), Items.OAK_DOOR) + onGround(helper, Items.OAK_DOOR) == 0,
                "no door appeared from nowhere");
        });
    }

    // Codex T3b round 2, #3: a log turned the wrong way is turned, not rebuilt.
    @GameTest(template = "empty16", timeoutTicks = 3000, batch = "builder_regress")
    public void wronglyTurnedLogIsTurnedForFree(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 6);
        BuilderTestKit.Cells c = new BuilderTestKit.Cells()
            .set(0, 0, 0, Blocks.COBBLESTONE.defaultBlockState())
            .set(0, 1, 0, Blocks.OAK_LOG.defaultBlockState()
                .setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS, Direction.Axis.Y));
        Blueprint blueprint = new Blueprint(BuilderTestKit.meta("test_log", BlueprintMeta.Kind.DEFENSE, null, null, null),
            1, 2, 1, c.list());
        helper.setBlock(SITE, Blocks.COBBLESTONE);
        helper.setBlock(SITE.above(), Blocks.OAK_LOG.defaultBlockState()
            .setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS, Direction.Axis.X));
        BuildPlanner.Plan plan = BuildPlanner.planBlueprint(arena.level(), arena.settlement(), blueprint,
            helper.absolutePos(SITE), 0, false, null);
        helper.assertTrue(plan.validation().playerBlockCount() == 0 && plan.validation().clears() == 0,
            "a turned log is neither in the way nor cleared");
        BuildJob job = commit(helper, arena, plan);
        stockBill(arena, job);
        BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Tor");
        helper.succeedWhen(() -> {
            BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
            helper.assertTrue(live != null && live.state == BuildJob.State.COMPLETE, "completes");
            helper.assertTrue(helper.getBlockState(SITE.above()).getValue(
                net.minecraft.world.level.block.RotatedPillarBlock.AXIS) == Direction.Axis.Y, "turned upright");
            helper.assertTrue(BuilderTestKit.count(arena.chest(), Items.OAK_LOG) == 1
                && onGround(helper, Items.OAK_LOG) == 0, "the stocked log was not spent and nothing was salvaged");
        });
    }

    /** Three planks on the ground course, then one cobble on top: planks first, cobble later. */
    static Blueprint planksThenCobble() {
        BuilderTestKit.Cells c = new BuilderTestKit.Cells()
            .box(0, 0, 0, 2, 0, 0, Blocks.OAK_PLANKS.defaultBlockState())
            .set(0, 1, 0, Blocks.COBBLESTONE.defaultBlockState());
        return new Blueprint(BuilderTestKit.meta("test_sack", BlueprintMeta.Kind.DEFENSE, null, null, null),
            3, 2, 1, c.list());
    }

    // Codex T3b round 2, #2: a sack full of leftovers never blocks loading (at the hut).
    @GameTest(template = "empty16", timeoutTicks = 3000, batch = "builder_regress")
    public void fullSackOfLeftoversIsEmptiedAtTheHut(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 6);
        BuildJob job = commit(helper, arena, BuildPlanner.planBlueprint(arena.level(), arena.settlement(),
            planksThenCobble(), helper.absolutePos(SITE), 0, false, null));
        BuilderTestKit.stock(arena.chest(), new ItemStack(Items.OAK_PLANKS, 3));
        var builder = BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Ulv");
        builder.bag.setItem(0, new ItemStack(Items.COBBLESTONE, 24));
        helper.succeedWhen(() -> {
            BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
            helper.assertTrue(live != null && live.state == BuildJob.State.COMPLETE, "completes");
            int cobble = BuilderTestKit.count(arena.chest(), Items.COBBLESTONE)
                + BuilderTestKit.count(builder.bag, Items.COBBLESTONE) + onGround(helper, Items.COBBLESTONE);
            helper.assertTrue(cobble == 23, "24 carried, 1 built: 23 left, got " + cobble);
            helper.assertTrue(BuilderTestKit.count(arena.chest(), Items.OAK_PLANKS) == 0, "planks loaded and used");
        });
    }

    // ... and at the warehouse when the Builder fetches himself.
    @GameTest(template = "empty16", timeoutTicks = 4000, batch = "builder_regress")
    public void fullSackOfLeftoversIsEmptiedAtTheWarehouse(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 6);
        GameTestFixtures.register(helper, arena.settlement(), com.hearthstead.building.BuildingType.WAREHOUSE, 11, 2);
        helper.setBlock(new BlockPos(12, 1, 3), Blocks.CHEST);
        net.minecraft.world.Container store = (net.minecraft.world.Container) arena.level()
            .getBlockEntity(helper.absolutePos(new BlockPos(12, 1, 3)));
        BuilderTestKit.stock(store, new ItemStack(Items.OAK_PLANKS, 3));
        com.hearthstead.settlement.builder.BuildSiteSavedData.get(arena.level()).setSettings(arena.settlement().id,
            new com.hearthstead.settlement.builder.BuildSiteSavedData.Settings(
                com.hearthstead.settlement.builder.BuildSiteSavedData.Pickup.FETCH_MYSELF,
                com.hearthstead.settlement.builder.BuildSiteSavedData.Fill.MATCH));
        BuildJob job = commit(helper, arena, BuildPlanner.planBlueprint(arena.level(), arena.settlement(),
            planksThenCobble(), helper.absolutePos(SITE), 0, false, null));
        var builder = BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Vali");
        builder.bag.setItem(0, new ItemStack(Items.COBBLESTONE, 24));
        helper.succeedWhen(() -> {
            BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
            helper.assertTrue(live != null && live.state == BuildJob.State.COMPLETE, "completes: "
                + (live == null ? "" : live.status + " " + live.statusArgs));
            int cobble = BuilderTestKit.count(arena.chest(), Items.COBBLESTONE) + BuilderTestKit.count(store, Items.COBBLESTONE)
                + BuilderTestKit.count(builder.bag, Items.COBBLESTONE) + onGround(helper, Items.COBBLESTONE);
            helper.assertTrue(cobble == 23, "24 carried, 1 built: 23 left, got " + cobble);
            helper.assertTrue(BuilderTestKit.count(store, Items.OAK_PLANKS) == 0, "planks fetched and used");
        });
    }

    // ------------------------------------------ Codex T3b round 3: scaffold ---

    /** A 7-high pillar: its top is only reachable from a 5-rung ladder column. */
    static Blueprint pillar() {
        BuilderTestKit.Cells c = new BuilderTestKit.Cells()
            .box(0, 0, 0, 0, 6, 0, Blocks.COBBLESTONE.defaultBlockState());
        return new Blueprint(BuilderTestKit.meta("test_pillar_r", BlueprintMeta.Kind.DEFENSE, null, null, null),
            1, 7, 1, c.list());
    }

    private static int laddersEverywhere(GameTestHelper helper, BuilderTestKit.Arena arena,
                                         com.hearthstead.entity.SettlerEntity builder) {
        return BuilderTestKit.count(arena.chest(), Items.LADDER) + BuilderTestKit.count(builder.bag, Items.LADDER)
            + onGround(helper, Items.LADDER);
    }

    private static int laddersStanding(GameTestHelper helper) {
        int n = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int y = 0; y <= 6; y++) {
                    n += helper.getBlockState(SITE.offset(dx, y, dz)).is(Blocks.LADDER) ? 1 : 0;
                }
            }
        }
        return n;
    }

    // #1: exactly enough ladders, no surplus: the demand shrinks as rungs go up.
    @GameTest(template = "empty16", timeoutTicks = 8000, batch = "builder_regress")
    public void exactlyEnoughLaddersNeverStall(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 7);
        BuildJob job = commit(helper, arena, BuildPlanner.planBlueprint(arena.level(), arena.settlement(), pillar(),
            helper.absolutePos(SITE), 0, false, null));
        BuilderTestKit.stock(arena.chest(), new ItemStack(Items.COBBLESTONE, 7), new ItemStack(Items.LADDER, 5));
        var builder = BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Yngve");
        helper.succeedWhen(() -> {
            BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
            helper.assertTrue(live != null && live.state == BuildJob.State.COMPLETE && live.skippedCount() == 0,
                "completes with exactly 5 ladders: " + (live == null ? "" : live.status + " " + live.statusArgs));
            helper.assertTrue(laddersStanding(helper) == 0, "no rung left");
            helper.assertTrue(laddersEverywhere(helper, arena, builder) == 5, "all 5 ladders back");
        });
    }

    // #1: a sack full of junk at the load boundary still carries ladders.
    @GameTest(template = "empty16", timeoutTicks = 8000, batch = "builder_regress")
    public void fullSackStillLoadsLaddersForAColumn(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 7);
        BuildJob job = commit(helper, arena, BuildPlanner.planBlueprint(arena.level(), arena.settlement(), pillar(),
            helper.absolutePos(SITE), 0, false, null));
        BuilderTestKit.stock(arena.chest(), new ItemStack(Items.COBBLESTONE, 7), new ItemStack(Items.LADDER, 5));
        var builder = BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Zakarias");
        builder.bag.setItem(0, new ItemStack(Items.DIRT, 24));
        helper.succeedWhen(() -> {
            BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
            helper.assertTrue(live != null && live.state == BuildJob.State.COMPLETE, "completes");
            int dirt = BuilderTestKit.count(arena.chest(), Items.DIRT) + BuilderTestKit.count(builder.bag, Items.DIRT)
                + onGround(helper, Items.DIRT);
            helper.assertTrue(dirt == 24, "the junk is kept, never lost: " + dirt);
            helper.assertTrue(laddersStanding(helper) == 0 && laddersEverywhere(helper, arena, builder) == 5,
                "ladders hung, taken down and returned");
        });
    }

    // #2: stopping a site mid-scaffold still takes every rung down.
    @GameTest(template = "empty16", timeoutTicks = 8000, batch = "builder_regress")
    public void stoppingASiteMidScaffoldTakesTheLaddersDown(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 7);
        BuildJob job = commit(helper, arena, BuildPlanner.planBlueprint(arena.level(), arena.settlement(), pillar(),
            helper.absolutePos(SITE), 0, false, null));
        BuilderTestKit.stock(arena.chest(), new ItemStack(Items.COBBLESTONE, 7), new ItemStack(Items.LADDER, 6));
        var builder = BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Aase");
        boolean[] stopped = {false};
        helper.onEachTick(() -> {
            BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
            if (!stopped[0] && live != null && live.scaffold.size() >= 3) {
                BuildJobs.act(arena.level(), arena.settlement(), job.id, BuildJobs.SiteAction.CANCEL_KEEP, null);
                stopped[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(stopped[0], "stopped mid-scaffold");
            helper.assertTrue(laddersStanding(helper) == 0, "no rung stranded");
            helper.assertTrue(laddersEverywhere(helper, arena, builder) == 6, "all 6 ladders back");
        });
    }

    // #3: a rung the player broke and replaced with their own ladder is theirs.
    @GameTest(template = "empty16", timeoutTicks = 8000, batch = "builder_regress")
    public void aPlayerReplacedRungIsLeftStanding(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 7);
        BuildJob job = commit(helper, arena, BuildPlanner.planBlueprint(arena.level(), arena.settlement(), pillar(),
            helper.absolutePos(SITE), 0, false, null));
        BuilderTestKit.stock(arena.chest(), new ItemStack(Items.COBBLESTONE, 7), new ItemStack(Items.LADDER, 6));
        var builder = BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Bjarte");
        BlockPos[] replaced = {null};
        helper.onEachTick(() -> {
            BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
            if (replaced[0] == null && live != null && live.scaffold.size() >= 2) {
                BlockPos rung = live.scaffold.get(0);
                BlockState ladder = arena.level().getBlockState(rung);
                var player = helper.makeMockServerPlayerInLevel();
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                    new net.neoforged.neoforge.event.level.BlockEvent.BreakEvent(arena.level(), rung, ladder, player));
                arena.level().setBlock(rung, Blocks.AIR.defaultBlockState(), 3);   // the player takes it
                arena.level().setBlock(rung, ladder, 3);                           // and hangs their own
                replaced[0] = rung;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(replaced[0] != null, "a rung was replaced");
            BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
            helper.assertTrue(live != null && live.state == BuildJob.State.COMPLETE, "completes");
            helper.assertTrue(arena.level().getBlockState(replaced[0]).is(Blocks.LADDER), "the player's ladder stands");
            helper.assertTrue(laddersStanding(helper) == 1, "only the player's ladder remains");
            helper.assertTrue(laddersEverywhere(helper, arena, builder) == 5,
                "5 of the Builder's ladders back (the player took one rung)");
        });
    }

    // --------------------------------- Codex T3b round 4: scaffold cleanup ---

    private static BuildJob cleanupOf(BuilderTestKit.Arena arena) {
        for (BuildJob j : com.hearthstead.settlement.builder.BuildSiteSavedData.get(arena.level())
            .jobs(arena.settlement().id)) {
            if (j.kind == BuildJob.Kind.DISMANTLE && j.label.startsWith("Ladders:")) {
                return j;
            }
        }
        return null;
    }

    private static BuildJob siteWithRungs(GameTestHelper helper, BuilderTestKit.Arena arena, BlockPos... rungs) {
        BuildJob job = commit(helper, arena, BuildPlanner.planBlueprint(arena.level(), arena.settlement(), pillar(),
            helper.absolutePos(SITE), 0, false, null));
        for (BlockPos rung : rungs) {
            job.scaffold.add(rung);
        }
        return job;
    }

    // Trigger A: a rung in an unloaded chunk still goes into the cleanup.
    @GameTest(template = "empty16", timeoutTicks = 100, batch = "builder_regress")
    public void cleanupKeepsUnloadedRungs(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 7);
        BlockPos near = helper.absolutePos(SITE.offset(1, 1, 0));
        arena.level().setBlock(near, Blocks.LADDER.defaultBlockState()
            .setValue(net.minecraft.world.level.block.LadderBlock.FACING, Direction.EAST), 3);
        BlockPos far = near.offset(30_000, 0, 30_000);   // never loaded in a GameTest world
        helper.assertTrue(!arena.level().isLoaded(far), "the far rung's chunk is unloaded");
        BuildJob job = siteWithRungs(helper, arena, near, far);
        BuildJobs.act(arena.level(), arena.settlement(), job.id, BuildJobs.SiteAction.CANCEL_KEEP, null);
        BuildJob cleanup = cleanupOf(arena);
        helper.assertTrue(cleanup != null && cleanup.size() == 2, "both rungs, loaded or not, are in the cleanup");
        helper.assertTrue(job.scaffold.isEmpty(), "ownership moved to the cleanup");
        helper.succeed();
    }

    // Trigger B: a full queue never loses the ladders.
    @GameTest(template = "empty16", timeoutTicks = 100, batch = "builder_regress")
    public void cleanupIsAdmittedPastAFullQueue(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 7);
        var data = com.hearthstead.settlement.builder.BuildSiteSavedData.get(arena.level());
        BlockPos rung = helper.absolutePos(SITE.offset(1, 1, 0));
        BuildJob job = siteWithRungs(helper, arena, rung);
        for (int n = 0; data.jobs(arena.settlement().id).size() < com.hearthstead.settlement.builder.BuildSiteSavedData.MAX_JOBS; n++) {
            BuildJob.Builder b = new BuildJob.Builder();
            b.add(helper.absolutePos(new BlockPos(n % 16, 5, n / 16)), Blocks.OAK_PLANKS.defaultBlockState(),
                com.hearthstead.settlement.builder.BuildPhase.STRUCTURE, 0, null, null);
            data.add(b.build(java.util.UUID.randomUUID(), arena.settlement().id, BuildJob.Kind.BLUEPRINT, "filler",
                "filler", helper.absolutePos(SITE), 0, false, null, 0L));
        }
        helper.assertTrue(data.jobs(arena.settlement().id).size() == com.hearthstead.settlement.builder.BuildSiteSavedData.MAX_JOBS,
            "the player queue is full");
        BuildJobs.act(arena.level(), arena.settlement(), job.id, BuildJobs.SiteAction.CANCEL_KEEP, null);
        helper.assertTrue(cleanupOf(arena) != null, "the cleanup is admitted past the cap");
        helper.assertTrue(job.scaffold.isEmpty(), "and only then does the stopped site let go of its ladders");
        helper.succeed();
    }

    // Save/reload before the cleanup runs.
    @GameTest(template = "empty16", timeoutTicks = 100, batch = "builder_regress")
    public void cleanupSurvivesSaveAndReload(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 7);
        BlockPos a = helper.absolutePos(SITE.offset(1, 1, 0));
        BlockPos b = a.above();
        BuildJob job = siteWithRungs(helper, arena, a, b);
        BuildJobs.act(arena.level(), arena.settlement(), job.id, BuildJobs.SiteAction.CANCEL_KEEP, null);
        var data = com.hearthstead.settlement.builder.BuildSiteSavedData.get(arena.level());
        net.minecraft.nbt.CompoundTag saved = data.save(new net.minecraft.nbt.CompoundTag(),
            arena.level().registryAccess());
        var loaded = com.hearthstead.settlement.builder.BuildSiteSavedData.load(saved, arena.level().registryAccess());
        BuildJob copy = null;
        for (BuildJob j : loaded.jobs(arena.settlement().id)) {
            if (j.kind == BuildJob.Kind.DISMANTLE && j.label.startsWith("Ladders:")) {
                copy = j;
            }
        }
        helper.assertTrue(copy != null && copy.size() == 2 && copy.state == BuildJob.State.ACTIVE && copy.rush,
            "the ladder cleanup reloads active, rushed, with both rungs");
        helper.assertTrue(copy.hasFlag(0, BuildJob.F_SCAFFOLD) && copy.hasFlag(1, BuildJob.F_SCAFFOLD),
            "each step is a scaffold step");
        helper.succeed();
    }
}
