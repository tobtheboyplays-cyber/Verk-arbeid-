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
import com.hearthstead.settlement.builder.BuilderStock;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * QA-BUILD-01 (batch {@code builder_load}): the Builder takes the nearest
 * block of the layer, which can lie beyond the ordered 64-unit load prefix.
 * Its material must come along on the load and must not be handed back at
 * the hut; before the fix a sack of prefix cobblestone looped FETCHING for
 * one plank forever. Every item stays accounted for: hut + sacks + ground +
 * placed blocks equals what was stocked. One Builder per hut, as the game hires.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class BuilderLoadWindowGameTests {

    /** 64 cobblestone in four 16-block rows, then one plank three rows further on, all on one layer. */
    static Blueprint cobbleRowsThenPlank(String id) {
        BuilderTestKit.Cells c = new BuilderTestKit.Cells()
            .box(0, 0, 0, 15, 0, 3, Blocks.COBBLESTONE.defaultBlockState())
            .set(8, 0, 6, Blocks.OAK_PLANKS.defaultBlockState());
        return new Blueprint(BuilderTestKit.meta(id, BlueprintMeta.Kind.DEFENSE, null, null, null),
            16, 1, 7, c.list());
    }

    private static BuildJob commit(GameTestHelper helper, BuilderTestKit.Arena arena, BlockPos site, String id) {
        BuildPlanner.Plan plan = BuildPlanner.planBlueprint(arena.level(), arena.settlement(),
            cobbleRowsThenPlank(id), helper.absolutePos(site), 0, false, null);
        helper.assertTrue(plan.validation().ok() && plan.job() != null, "plan: " + plan.validation().reasonKey());
        BuildJob job = plan.job();
        helper.assertTrue(job.size() == 65, "fixture: 65 steps, got " + job.size());
        int plank = -1;
        for (int i = 0; i < job.size(); i++) {
            if (job.state(i).is(Blocks.OAK_PLANKS)) {
                plank = i;
            }
            helper.assertTrue(job.phase(i) == job.phase(0) && job.pos(i).getY() == job.pos(0).getY(),
                "fixture: one phase, one layer");
        }
        helper.assertTrue(plank == 64, "fixture: the plank step comes after the 64 cobblestone units, got " + plank);
        helper.assertTrue(BuildJobs.commit(arena.level(), arena.settlement(), job) == null, "queued");
        return job;
    }

    private static int placed(GameTestHelper helper, BlockPos site, Block block) {
        int n = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 7; z++) {
                n += helper.getBlockState(site.offset(x, 0, z)).is(block) ? 1 : 0;
            }
        }
        return n;
    }

    private static int onGround(GameTestHelper helper, Item item) {
        int n = 0;
        for (ItemEntity e : helper.getLevel().getEntitiesOfClass(ItemEntity.class, helper.getBounds().inflate(4))) {
            n += e.getItem().is(item) ? e.getItem().getCount() : 0;
        }
        return n;
    }

    /** Huts + sacks + ground + placed, per item: nothing conjured, nothing lost. */
    private static void assertConserved(GameTestHelper helper, List<Container> huts, List<SettlerEntity> builders,
                                        List<BlockPos> sites, Item item, Block block, int stocked) {
        int stored = 0;
        for (Container hut : huts) {
            stored += BuilderTestKit.count(hut, item);
        }
        int bags = 0;
        for (SettlerEntity b : builders) {
            bags += BuilderStock.bagCount(b.bag, item);
        }
        int built = 0;
        for (BlockPos site : sites) {
            built += placed(helper, site, block);
        }
        int total = stored + bags + onGround(helper, item) + built;
        helper.assertTrue(total == stocked, item + ": huts+sacks+ground+placed = " + total + ", stocked " + stocked);
    }

    private static void assertComplete(GameTestHelper helper, BuilderTestKit.Arena arena, BuildJob job, BlockPos site) {
        BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
        helper.assertTrue(live != null && live.state == BuildJob.State.COMPLETE,
            "site complete: " + (live == null ? "gone" : live.state + " " + live.status + " " + live.statusArgs));
        helper.assertTrue(placed(helper, site, Blocks.COBBLESTONE) == 64, "64 cobblestone set");
        helper.assertTrue(placed(helper, site, Blocks.OAK_PLANKS) == 1, "the plank set");
    }

    // Empty sack; he stands by the plank, so the plank step (beyond the prefix) is chosen first.
    @GameTest(template = "empty32", timeoutTicks = 3000, batch = "builder_load")
    public void chosenStepBeyondThePrefixGetsItsMaterial(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 32, 4);
        BlockPos site = new BlockPos(8, 1, 8);
        BuildJob job = commit(helper, arena, site, "test_load_prefix");
        BuilderTestKit.stock(arena.chest(), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.OAK_PLANKS, 1));
        SettlerEntity builder = BuilderTestKit.hireBuilder(helper, arena, site.offset(8, 0, 8), "Ane");
        helper.succeedWhen(() -> {
            assertComplete(helper, arena, job, site);
            assertConserved(helper, List.of(arena.chest()), List.of(builder), List.of(site), Items.COBBLESTONE, Blocks.COBBLESTONE, 64);
            assertConserved(helper, List.of(arena.chest()), List.of(builder), List.of(site), Items.OAK_PLANKS, Blocks.OAK_PLANKS, 1);
        });
    }

    // A full sack of prefix cobblestone: at the hut he must hand back enough to take the plank.
    @GameTest(template = "empty32", timeoutTicks = 3000, batch = "builder_load")
    public void fullSackHandsBackPrefixMaterialToLoadTheChosenStep(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 32, 4);
        BlockPos site = new BlockPos(8, 1, 8);
        BuildJob job = commit(helper, arena, site, "test_load_full_sack");
        BuilderTestKit.stock(arena.chest(), new ItemStack(Items.OAK_PLANKS, 1));
        SettlerEntity builder = BuilderTestKit.hireBuilder(helper, arena, site.offset(8, 0, 8), "Brage");
        builder.bag.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        builder.bag.setChanged();
        helper.succeedWhen(() -> {
            assertComplete(helper, arena, job, site);
            assertConserved(helper, List.of(arena.chest()), List.of(builder), List.of(site), Items.COBBLESTONE, Blocks.COBBLESTONE, 64);
            assertConserved(helper, List.of(arena.chest()), List.of(builder), List.of(site), Items.OAK_PLANKS, Blocks.OAK_PLANKS, 1);
        });
    }

    // Two Builders (one per hut), two such sites; every item across both huts is accounted for.
    @GameTest(template = "empty32", timeoutTicks = 4800, batch = "builder_load")
    public void twoBuildersTwoSitesAndEveryItemIsAccountedFor(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 32, 4);
        Building hutB = GameTestFixtures.register(helper, arena.settlement(), BuildingType.BUILDERS_HUT, 12, 2);
        BlockPos chestB = new BlockPos(13, 1, 4);
        helper.setBlock(chestB, Blocks.CHEST);
        Container b = (Container) arena.level().getBlockEntity(helper.absolutePos(chestB));
        BlockPos siteA = new BlockPos(8, 1, 8);
        BlockPos siteB = new BlockPos(8, 1, 19);
        BuildJob jobA = commit(helper, arena, siteA, "test_load_two_a");
        BuildJob jobB = commit(helper, arena, siteB, "test_load_two_b");
        BuilderTestKit.stock(arena.chest(), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.OAK_PLANKS, 1));
        BuilderTestKit.stock(b, new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.OAK_PLANKS, 1));
        SettlerEntity one = BuilderTestKit.hireBuilder(helper, arena, siteA.offset(8, 0, 8), "Cato");
        SettlerEntity two = BuilderTestKit.hireBuilder(helper,
            new BuilderTestKit.Arena(arena.level(), arena.settlement(), hutB, b), siteB.offset(8, 0, 8), "Dina");
        List<Container> huts = List.of(arena.chest(), b);
        List<SettlerEntity> builders = List.of(one, two);
        List<BlockPos> sites = List.of(siteA, siteB);
        helper.succeedWhen(() -> {
            assertComplete(helper, arena, jobA, siteA);
            assertComplete(helper, arena, jobB, siteB);
            assertConserved(helper, huts, builders, sites, Items.COBBLESTONE, Blocks.COBBLESTONE, 128);
            assertConserved(helper, huts, builders, sites, Items.OAK_PLANKS, Blocks.OAK_PLANKS, 2);
        });
    }
}
