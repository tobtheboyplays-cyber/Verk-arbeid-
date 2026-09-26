package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.SettlerEntity;
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
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;

/**
 * BUILDER lane quality gates (batch {@code builder_robust}, coordinator list
 * 8-14): two storeys with stairs, fill and clear on a slope, water and
 * falling blocks, the stuck guard, dismantle/stop, two Builders on one site,
 * and a Builder dying mid-build -- each with exact item accounting.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class BuilderRobustnessGameTests {

    private static final BlockPos SITE = new BlockPos(9, 1, 9);

    private static BuildJob commit(GameTestHelper helper, BuilderTestKit.Arena arena, BuildPlanner.Plan plan) {
        helper.assertTrue(plan.validation().ok() && plan.job() != null,
            "plan must validate: " + plan.validation().reasonKey() + " " + plan.validation().reasonArgs());
        helper.assertTrue(BuildJobs.commit(arena.level(), arena.settlement(), plan.job()) == null, "queued");
        return plan.job();
    }

    private static void stockBill(Container chest, BuildJob job) {
        for (Map.Entry<Item, Integer> e : BuilderMaterials.total(job).entrySet()) {
            BuilderTestKit.stock(chest, new ItemStack(e.getKey(), e.getValue()));
        }
    }

    private static String state(BuildJob job) {
        return job == null ? "gone" : job.state + " " + job.status + " " + job.statusArgs + " done "
            + job.doneCount() + "/" + job.size() + " skipped " + job.skippedCount() + " [" + job.skipReport() + "]";
    }

    // ------------------------------------------------------------------ 8 ---

    /** 5x5 two storeys: stone ground floor, plank upper floor with a stairwell, stairs up. */
    static Blueprint twoStorey() {
        BlockState stone = Blocks.COBBLESTONE.defaultBlockState();
        BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        BuilderTestKit.Cells c = new BuilderTestKit.Cells()
            .box(0, 0, 0, 4, 0, 4, stone)
            .box(0, 1, 0, 4, 3, 4, planks).box(1, 1, 1, 3, 3, 3, air)      // ground storey walls
            .box(0, 3, 0, 4, 3, 4, planks)                                 // upper floor
            .box(0, 4, 0, 4, 6, 4, planks).box(1, 4, 1, 3, 6, 3, air)      // upper storey walls
            .box(0, 6, 0, 4, 6, 4, planks);                                // roof deck
        // Doorway (open, 2 high) on the south side, stairs up the east side.
        c.set(2, 1, 0, air).set(2, 2, 0, air);
        BlockState stair = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH);
        c.set(3, 1, 3, stair).set(3, 2, 2, stair);
        // Stairwell above both steps (headroom over the first), landing at
        // (3,3,1) left as floor: a staircase a settler can actually walk (W6).
        // W9: vanilla's step-up check sweeps the settler's full height over
        // the cell he jumps FROM, so the approach cell (2,1,3) needs the
        // stairwell over it too -- under a 2-high ceiling the first step is
        // unclimbable and the upper floor stays out of reach.
        c.set(3, 3, 3, air).set(3, 3, 2, air).set(2, 3, 3, air);
        return new Blueprint(BuilderTestKit.meta("test_two_storey", BlueprintMeta.Kind.DEFENSE, null, null, null),
            5, 7, 5, c.list());
    }

    @GameTest(template = "empty16", timeoutTicks = 12000, batch = "builder_robust")
    public void twoStoreysWithStairsCompleteFromUpperFloorStandCells(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 7);
        BuildJob job = commit(helper, arena, BuildPlanner.planBlueprint(arena.level(), arena.settlement(),
            twoStorey(), helper.absolutePos(SITE), 0, false, null));
        stockBill(arena.chest(), job);
        Map<Item, Integer> bill = BuilderMaterials.total(job);
        BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Ivar");
        helper.succeedWhen(() -> {
            BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
            helper.assertTrue(live != null && live.state == BuildJob.State.COMPLETE && live.skippedCount() == 0,
                "two storeys complete with nothing skipped: " + state(live));
            for (Map.Entry<Item, Integer> e : bill.entrySet()) {
                helper.assertTrue(BuilderTestKit.count(arena.chest(), e.getKey()) == 0, "used exactly " + e);
            }
            helper.assertTrue(helper.getBlockState(SITE.offset(2, 6, 2)).is(Blocks.OAK_PLANKS), "roof deck closed");
        });
    }

    // ------------------------------------------------------------------ 9 ---

    static Blueprint pad() {
        return BuilderGameTests.pad();
    }

    @GameTest(template = "empty16", timeoutTicks = 5000, batch = "builder_robust")
    public void slopeIsFilledAndClearedWithExactCounts(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 7);
        BlockPos site = SITE.above(3); // pad bottom at rel y4
        // Stepped ground under the pad: column x0 reaches y3 (no fill), x1 y2
        // (fill 1), x2 y0 only (fill 3). A dirt bump stands in the pad itself.
        for (int z = 0; z < 3; z++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(site.offset(0, y - 4, z), Blocks.STONE);
            }
            for (int y = 1; y <= 2; y++) {
                helper.setBlock(site.offset(1, y - 4, z), Blocks.STONE);
            }
            helper.setBlock(site.offset(0, 0, z), Blocks.DIRT);
        }
        BuildPlanner.Plan plan = BuildPlanner.planBlueprint(arena.level(), arena.settlement(), pad(),
            helper.absolutePos(site), 0, false, null);
        helper.assertTrue(plan.validation().fills() == 3 * 1 + 3 * 3,
            "fills: 1 per x1 column, 3 per x2 column = 12, got " + plan.validation().fills());
        helper.assertTrue(plan.validation().clears() == 3, "three dirt cells to clear, got " + plan.validation().clears());
        BuildJob job = commit(helper, arena, plan);
        Map<Item, Integer> bill = BuilderMaterials.total(job);
        helper.assertTrue(bill.get(Items.COBBLESTONE) == 9 + 12, "cobble for pad + fill = 21: " + bill);
        stockBill(arena.chest(), job);
        BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Jorunn");
        helper.succeedWhen(() -> {
            BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
            helper.assertTrue(live != null && live.state == BuildJob.State.COMPLETE && live.skippedCount() == 0,
                "slope job completes: " + state(live));
            for (int z = 0; z < 3; z++) {
                helper.assertTrue(helper.getBlockState(site.offset(1, -1, z)).is(Blocks.COBBLESTONE), "x1 filled");
                for (int d = 1; d <= 3; d++) {
                    helper.assertTrue(helper.getBlockState(site.offset(2, -d, z)).is(Blocks.COBBLESTONE),
                        "x2 filled to depth " + d);
                }
                helper.assertTrue(helper.getBlockState(site.offset(0, 0, z)).is(Blocks.COBBLESTONE), "bump replaced");
            }
            helper.assertTrue(BuilderTestKit.count(arena.chest(), Items.COBBLESTONE) == 0, "exactly 21 cobble used");
            helper.assertTrue(BuilderTestKit.count(arena.chest(), Items.DIRT) == 3, "the 3 dirt cells were salvaged");
        });
    }

    // ----------------------------------------------------------------- 10 ---

    @GameTest(template = "empty16", timeoutTicks = 5000, batch = "builder_robust")
    public void waterIsClearedAndSandWaitsForSupport(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 7);
        // A pit: stone walls just outside the 3x3 footprint, water inside.
        for (int x = -1; x <= 3; x++) {
            for (int z = -1; z <= 3; z++) {
                for (int y = 0; y <= 1; y++) {
                    boolean inside = x >= 0 && x <= 2 && z >= 0 && z <= 2;
                    helper.setBlock(SITE.offset(x, y, z), inside ? Blocks.WATER : Blocks.STONE);
                }
            }
        }
        // Pad with an empty centre and a block of sand floating over it.
        BuilderTestKit.Cells c = new BuilderTestKit.Cells()
            .box(0, 0, 0, 2, 0, 2, Blocks.COBBLESTONE.defaultBlockState())
            .box(0, 1, 0, 2, 1, 2, Blocks.OAK_PLANKS.defaultBlockState())
            .set(1, 0, 1, Blocks.AIR.defaultBlockState())
            .set(1, 1, 1, Blocks.AIR.defaultBlockState())
            .set(1, 2, 1, Blocks.SAND.defaultBlockState());
        Blueprint blueprint = new Blueprint(BuilderTestKit.meta("test_wet", BlueprintMeta.Kind.DEFENSE, null, null, null),
            3, 3, 3, c.list());
        BuildJob job = commit(helper, arena, BuildPlanner.planBlueprint(arena.level(), arena.settlement(), blueprint,
            helper.absolutePos(SITE), 0, false, null));
        stockBill(arena.chest(), job);
        BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Kari");
        AABB box = new AABB(helper.absolutePos(new BlockPos(0, 0, 0))).expandTowards(16, 8, 16);
        boolean[] sandFell = {false};
        helper.onEachTick(() -> {
            if (!arena.level().getEntitiesOfClass(FallingBlockEntity.class, box).isEmpty()) {
                sandFell[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertFalse(sandFell[0], "sand was never set where it would fall");
            BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
            helper.assertTrue(live != null && live.state == BuildJob.State.COMPLETE, "completes: " + state(live));
            helper.assertTrue(live.skippedCount() == 1 && live.status == BuildStatus.SKIPPED,
                "the unsupported sand is the one flagged skip: " + state(live));
            for (int x = 0; x < 3; x++) {
                for (int z = 0; z < 3; z++) {
                    for (int y = 0; y <= 1; y++) {
                        helper.assertTrue(arena.level().getFluidState(helper.absolutePos(SITE.offset(x, y, z))).isEmpty(),
                            "no water left in the footprint at " + SITE.offset(x, y, z));
                    }
                }
            }
            helper.assertTrue(helper.getBlockState(SITE.offset(1, 2, 1)).isAir(), "sand never placed");
            helper.assertTrue(BuilderTestKit.count(arena.chest(), Items.SAND) == 1, "sand never charged");
            helper.assertTrue(BuilderTestKit.count(arena.chest(), Items.COBBLESTONE) == 0
                && BuilderTestKit.count(arena.chest(), Items.OAK_PLANKS) == 0, "the rest used exactly");
        });
    }

    // ----------------------------------------------------------------- 11 ---

    @GameTest(template = "empty16", timeoutTicks = 6000, batch = "builder_robust")
    public void unreachableStepIsSkippedAndTheJobTerminates(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 7);
        // One plank floating 6 above open ground: no stand cell reaches it
        // (eye 1.62 above the ground, reach 4.5), and nothing to climb.
        BuilderTestKit.Cells c = new BuilderTestKit.Cells()
            .set(1, 6, 1, Blocks.OAK_PLANKS.defaultBlockState());
        Blueprint blueprint = new Blueprint(BuilderTestKit.meta("test_unreachable", BlueprintMeta.Kind.DEFENSE,
            null, null, null), 3, 7, 3, c.list());
        BuildJob job = commit(helper, arena, BuildPlanner.planBlueprint(arena.level(), arena.settlement(), blueprint,
            helper.absolutePos(SITE), 0, false, null));
        stockBill(arena.chest(), job);
        SettlerEntity builder = BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Leif");
        helper.succeedWhen(() -> {
            BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
            helper.assertTrue(live != null && live.state == BuildJob.State.COMPLETE,
                "the job terminates instead of looping: " + state(live));
            helper.assertTrue(live.skippedCount() == 1 && live.status == BuildStatus.SKIPPED,
                "exactly the floating plank is flagged: " + state(live));
            helper.assertTrue(helper.getBlockState(SITE.offset(1, 6, 1)).isAir(), "never placed");
            helper.assertTrue(BuilderTestKit.count(arena.chest(), Items.OAK_PLANKS) == 1 && builder.bag.isEmpty(),
                "the plank comes back to the hut, uncharged");
        });
    }

    // ----------------------------------------------------------------- 12 ---

    @GameTest(template = "empty16", timeoutTicks = 6000, batch = "builder_robust")
    public void dismantleReturnsExactlyWhatWasPlaced(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 6);
        BuildJob job = commit(helper, arena, BuildPlanner.planBlueprint(arena.level(), arena.settlement(), pad(),
            helper.absolutePos(SITE), 0, false, null));
        stockBill(arena.chest(), job);
        BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Magna");
        boolean[] ordered = {false};
        helper.succeedWhen(() -> {
            BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
            helper.assertTrue(live != null && live.state == BuildJob.State.COMPLETE, "built first: " + state(live));
            if (!ordered[0]) {
                helper.assertTrue(BuildJobs.act(arena.level(), arena.settlement(), job.id,
                    BuildJobs.SiteAction.DISMANTLE, null).equals("hearthstead.builder.site.ok"), "dismantle ordered");
                ordered[0] = true;
                helper.fail("dismantling");
            }
            for (int x = 0; x < 3; x++) {
                for (int z = 0; z < 3; z++) {
                    for (int y = 0; y <= 1; y++) {
                        helper.assertTrue(helper.getBlockState(SITE.offset(x, y, z)).isAir(), "taken down");
                    }
                }
            }
            helper.assertTrue(BuilderTestKit.count(arena.chest(), Items.COBBLESTONE) == 9
                && BuilderTestKit.count(arena.chest(), Items.OAK_PLANKS) == 8
                && BuilderTestKit.count(arena.chest(), Items.LANTERN) == 1, "exactly the placed items came back");
            AABB box = new AABB(helper.absolutePos(new BlockPos(0, 0, 0))).expandTowards(16, 8, 16);
            helper.assertTrue(arena.level().getEntitiesOfClass(ItemEntity.class, box).isEmpty(), "nothing dropped");
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 4000, batch = "builder_robust")
    public void stopKeepsWhatIsBuiltAndLosesNothing(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 6);
        BuildJob job = commit(helper, arena, BuildPlanner.planBlueprint(arena.level(), arena.settlement(), pad(),
            helper.absolutePos(SITE), 0, false, null));
        stockBill(arena.chest(), job);
        SettlerEntity builder = BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Njal");
        int[] placedAtStop = {-1};
        helper.succeedWhen(() -> {
            BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
            helper.assertTrue(live != null, "job exists");
            if (placedAtStop[0] < 0) {
                helper.assertTrue(live.doneCount() >= 5, "wait for some progress");
                BuildJobs.act(arena.level(), arena.settlement(), job.id, BuildJobs.SiteAction.CANCEL_KEEP, null);
                placedAtStop[0] = live.doneCount();
                helper.fail("stopped");
            }
            helper.assertTrue(live.state == BuildJob.State.CANCELLED, "stopped");
            helper.assertTrue(builder.bag.isEmpty(), "the sack is emptied back into the hut");
            int cobble = 0;
            int planks = 0;
            int lantern = 0;
            for (int x = 0; x < 3; x++) {
                for (int z = 0; z < 3; z++) {
                    cobble += helper.getBlockState(SITE.offset(x, 0, z)).is(Blocks.COBBLESTONE) ? 1 : 0;
                    planks += helper.getBlockState(SITE.offset(x, 1, z)).is(Blocks.OAK_PLANKS) ? 1 : 0;
                    lantern += helper.getBlockState(SITE.offset(x, 1, z)).is(Blocks.LANTERN) ? 1 : 0;
                }
            }
            helper.assertTrue(cobble + planks + lantern >= placedAtStop[0], "what was built stays");
            helper.assertTrue(cobble + BuilderTestKit.count(arena.chest(), Items.COBBLESTONE) == 9
                && planks + BuilderTestKit.count(arena.chest(), Items.OAK_PLANKS) == 8
                && lantern + BuilderTestKit.count(arena.chest(), Items.LANTERN) == 1,
                "built + back in the hut = exactly the stock; cobble " + cobble + " planks " + planks);
        });
    }

    // ----------------------------------------------------------------- 13 ---

    @GameTest(template = "empty16", timeoutTicks = 4000, batch = "builder_robust")
    public void twoBuildersNeverWorkOneSiteTwice(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 6);
        Building hutB = GameTestFixtures.register(helper, arena.settlement(), BuildingType.BUILDERS_HUT, 12, 2);
        BlockPos chestB = new BlockPos(13, 1, 4);
        helper.setBlock(chestB, Blocks.CHEST);
        Container b = (Container) arena.level().getBlockEntity(helper.absolutePos(chestB));
        BuildJob job = commit(helper, arena, BuildPlanner.planBlueprint(arena.level(), arena.settlement(), pad(),
            helper.absolutePos(SITE), 0, false, null));
        stockBill(arena.chest(), job);
        stockBill(b, job);
        SettlerEntity ada = BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Ada");
        SettlerEntity bo = BuilderTestKit.hireBuilder(helper,
            new BuilderTestKit.Arena(arena.level(), arena.settlement(), hutB, b), new BlockPos(12, 1, 7), "Bo");
        java.util.Set<java.util.UUID> claimants = new java.util.HashSet<>();
        helper.onEachTick(() -> {
            BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
            if (live != null && live.claimant != null) {
                claimants.add(live.claimant);
            }
        });
        helper.succeedWhen(() -> {
            BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
            helper.assertTrue(live != null && live.state == BuildJob.State.COMPLETE, "completes: " + state(live));
            helper.assertTrue(claimants.size() == 1, "one Builder holds the site from start to finish: " + claimants);
            int cobble = BuilderTestKit.count(arena.chest(), Items.COBBLESTONE) + BuilderTestKit.count(b, Items.COBBLESTONE);
            int planks = BuilderTestKit.count(arena.chest(), Items.OAK_PLANKS) + BuilderTestKit.count(b, Items.OAK_PLANKS);
            helper.assertTrue(cobble == 9 && planks == 8, "exactly one bill consumed across both huts: cobble "
                + cobble + " planks " + planks);
            helper.assertTrue(ada.bag.isEmpty() && bo.bag.isEmpty(), "both sacks empty");
        });
    }

    // ----------------------------------------------------------------- 14 ---

    @GameTest(template = "empty16", timeoutTicks = 6000, batch = "builder_robust")
    public void builderDiesMidBuildAndTheSiteIsReclaimedWithoutDuplication(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 6);
        Building hutB = GameTestFixtures.register(helper, arena.settlement(), BuildingType.BUILDERS_HUT, 12, 2);
        BlockPos chestB = new BlockPos(13, 1, 4);
        helper.setBlock(chestB, Blocks.CHEST);
        Container b = (Container) arena.level().getBlockEntity(helper.absolutePos(chestB));
        BuildJob job = commit(helper, arena, BuildPlanner.planBlueprint(arena.level(), arena.settlement(), pad(),
            helper.absolutePos(SITE), 0, false, null));
        stockBill(arena.chest(), job);
        stockBill(b, job);
        SettlerEntity ada = BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Ada");
        BuilderTestKit.hireBuilder(helper, new BuilderTestKit.Arena(arena.level(), arena.settlement(), hutB, b),
            new BlockPos(12, 1, 12), "Bo");
        boolean[] killed = {false};
        helper.onEachTick(() -> {
            BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
            if (!killed[0] && live != null && live.doneCount() >= 4 && ada.getUUID().equals(live.claimant)) {
                ada.kill();
                killed[0] = true;
            }
        });
        AABB box = new AABB(helper.absolutePos(new BlockPos(0, 0, 0))).expandTowards(16, 8, 16);
        helper.succeedWhen(() -> {
            helper.assertTrue(killed[0], "the first Builder died mid-build");
            BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
            helper.assertTrue(live != null && live.state == BuildJob.State.COMPLETE && live.doneCount() == live.size(),
                "the second Builder reclaims and finishes: " + state(live));
            int[] onGround = new int[3];
            for (ItemEntity item : arena.level().getEntitiesOfClass(ItemEntity.class, box)) {
                ItemStack s = item.getItem();
                onGround[0] += s.is(Items.COBBLESTONE) ? s.getCount() : 0;
                onGround[1] += s.is(Items.OAK_PLANKS) ? s.getCount() : 0;
                onGround[2] += s.is(Items.LANTERN) ? s.getCount() : 0;
            }
            int cobble = BuilderTestKit.count(arena.chest(), Items.COBBLESTONE) + BuilderTestKit.count(b, Items.COBBLESTONE)
                + onGround[0];
            int planks = BuilderTestKit.count(arena.chest(), Items.OAK_PLANKS) + BuilderTestKit.count(b, Items.OAK_PLANKS)
                + onGround[1];
            int lantern = BuilderTestKit.count(arena.chest(), Items.LANTERN) + BuilderTestKit.count(b, Items.LANTERN)
                + onGround[2];
            // Two bills were stocked, one pad stands: exactly one bill remains, split
            // between the huts and the dead Builder's dropped sack.
            helper.assertTrue(cobble == 9 && planks == 8 && lantern == 1,
                "conservation: cobble " + cobble + " planks " + planks + " lantern " + lantern);
        });
    }

    /** Unused: keeps the site data import for future checks. */
    static int sites(GameTestHelper helper, BuilderTestKit.Arena arena) {
        return BuildSiteSavedData.get(arena.level()).jobs(arena.settlement().id).size();
    }

    // ------------------------------------------------------------ scaffold ---

    /**
     * A 7-high cobble pillar: its top block is out of reach from the ground
     * (eye 1.62, reach 4.5), so the Builder must hang ladders on the pillar,
     * climb, set the top, and take every rung back down (ladders to the hut).
     */
    @GameTest(template = "empty16", timeoutTicks = 8000, batch = "builder_robust")
    public void tallWorkIsReachedFromALadderThatComesDownAfter(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 7);
        BuilderTestKit.Cells c = new BuilderTestKit.Cells()
            .box(0, 0, 0, 0, 6, 0, Blocks.COBBLESTONE.defaultBlockState());
        Blueprint pillar = new Blueprint(BuilderTestKit.meta("test_pillar", BlueprintMeta.Kind.DEFENSE, null, null, null),
            1, 7, 1, c.list());
        BuildJob job = commit(helper, arena, BuildPlanner.planBlueprint(arena.level(), arena.settlement(), pillar,
            helper.absolutePos(SITE), 0, false, null));
        BuilderTestKit.stock(arena.chest(), new ItemStack(Items.COBBLESTONE, 7), new ItemStack(Items.LADDER, 6));
        SettlerEntity builder = BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Wenche");
        boolean[] sawLadder = {false};
        AABB box = new AABB(helper.absolutePos(new BlockPos(0, 0, 0))).expandTowards(16, 8, 16);
        helper.onEachTick(() -> {
            BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
            if (live != null && !live.scaffold.isEmpty()) {
                sawLadder[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
            helper.assertTrue(live != null && live.state == BuildJob.State.COMPLETE && live.skippedCount() == 0,
                "the pillar completes, nothing skipped: " + state(live));
            helper.assertTrue(sawLadder[0], "a ladder column went up");
            helper.assertTrue(live.scaffold.isEmpty(), "every rung came down");
            helper.assertTrue(helper.getBlockState(SITE.above(6)).is(Blocks.COBBLESTONE), "the top block is set");
            int ladders = BuilderTestKit.count(arena.chest(), Items.LADDER) + BuilderTestKit.count(builder.bag, Items.LADDER);
            for (ItemEntity item : arena.level().getEntitiesOfClass(ItemEntity.class, box)) {
                ladders += item.getItem().is(Items.LADDER) ? item.getItem().getCount() : 0;
            }
            helper.assertTrue(ladders == 6, "all 6 ladders are back, got " + ladders);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int y = 0; y <= 6; y++) {
                        helper.assertTrue(!helper.getBlockState(SITE.offset(dx, y, dz)).is(Blocks.LADDER),
                            "no ladder left standing");
                    }
                }
            }
            helper.assertTrue(BuilderTestKit.count(arena.chest(), Items.COBBLESTONE) == 0, "exactly 7 cobble used");
        });
    }
}
