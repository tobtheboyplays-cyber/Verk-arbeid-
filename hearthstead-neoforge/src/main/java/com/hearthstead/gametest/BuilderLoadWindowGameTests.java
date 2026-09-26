package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.builder.Blueprint;
import com.hearthstead.settlement.builder.BlueprintMeta;
import com.hearthstead.settlement.builder.BuildJob;
import com.hearthstead.settlement.builder.BuildJobs;
import com.hearthstead.settlement.builder.BuildPlanner;
import com.hearthstead.settlement.builder.BuilderStock;
import com.hearthstead.settlement.builder.BuilderSupply;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
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

    /** Four rows of 16 cobblestone: the 64-unit load prefix. */
    private static final int ROWS = 4;

    /** {@code rows} 16-block rows of cobblestone, then one plank two rows further on, all on one layer. */
    static Blueprint cobbleRowsThenPlank(String id, int rows) {
        BuilderTestKit.Cells c = new BuilderTestKit.Cells()
            .box(0, 0, 0, 15, 0, rows - 1, Blocks.COBBLESTONE.defaultBlockState())
            .set(8, 0, rows + 2, Blocks.OAK_PLANKS.defaultBlockState());
        return new Blueprint(BuilderTestKit.meta(id, BlueprintMeta.Kind.DEFENSE, null, null, null),
            16, 1, rows + 3, c.list());
    }

    private static BuildJob commit(GameTestHelper helper, BuilderTestKit.Arena arena, BlockPos site, String id) {
        return commit(helper, arena, site, id, ROWS);
    }

    private static BuildJob commit(GameTestHelper helper, BuilderTestKit.Arena arena, BlockPos site, String id, int rows) {
        BuildPlanner.Plan plan = BuildPlanner.planBlueprint(arena.level(), arena.settlement(),
            cobbleRowsThenPlank(id, rows), helper.absolutePos(site), 0, false, null);
        helper.assertTrue(plan.validation().ok() && plan.job() != null, "plan: " + plan.validation().reasonKey());
        BuildJob job = plan.job();
        int cobble = rows * 16;
        helper.assertTrue(job.size() == cobble + 1, "fixture: " + (cobble + 1) + " steps, got " + job.size());
        int plank = -1;
        for (int i = 0; i < job.size(); i++) {
            if (job.state(i).is(Blocks.OAK_PLANKS)) {
                plank = i;
            }
            helper.assertTrue(job.phase(i) == job.phase(0) && job.pos(i).getY() == job.pos(0).getY(),
                "fixture: one phase, one layer");
        }
        helper.assertTrue(plank == cobble, "fixture: the plank step comes after the " + cobble
            + " cobblestone units, got " + plank);
        helper.assertTrue(BuildJobs.commit(arena.level(), arena.settlement(), job) == null, "queued");
        return job;
    }

    private static int placed(GameTestHelper helper, BlockPos site, Block block) {
        return placed(helper, site, block, ROWS);
    }

    private static int placed(GameTestHelper helper, BlockPos site, Block block, int rows) {
        int n = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < rows + 3; z++) {
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
        assertConserved(helper, huts, builders, sites, item, block, stocked, ROWS);
    }

    private static void assertConserved(GameTestHelper helper, List<Container> huts, List<SettlerEntity> builders,
                                        List<BlockPos> sites, Item item, Block block, int stocked, int rows) {
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
            built += placed(helper, site, block, rows);
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

    // The hut chest is full, so the sack's prefix cobblestone cannot go back: he rests the plank,
    // sets the cobblestone he carries first, and then has room for it (never a FETCHING loop).
    @GameTest(template = "empty32", timeoutTicks = 3000, batch = "builder_load")
    public void fullHutChestRestsTheChosenStepUntilTheSackHasRoom(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 32, 4);
        BlockPos site = new BlockPos(8, 1, 8);
        BuildJob job = commit(helper, arena, site, "test_load_full_hut");
        ItemStack[] fill = new ItemStack[arena.chest().getContainerSize()];
        fill[0] = new ItemStack(Items.OAK_PLANKS, 1);
        for (int i = 1; i < fill.length; i++) {
            fill[i] = new ItemStack(Items.DIRT, 64);
        }
        BuilderTestKit.stock(arena.chest(), fill);
        int dirt = BuilderTestKit.count(arena.chest(), Items.DIRT);
        SettlerEntity builder = BuilderTestKit.hireBuilder(helper, arena, site.offset(8, 0, 8), "Eir");
        builder.bag.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        builder.bag.setChanged();
        helper.succeedWhen(() -> {
            assertComplete(helper, arena, job, site);
            assertConserved(helper, List.of(arena.chest()), List.of(builder), List.of(site), Items.COBBLESTONE, Blocks.COBBLESTONE, 64);
            assertConserved(helper, List.of(arena.chest()), List.of(builder), List.of(site), Items.OAK_PLANKS, Blocks.OAK_PLANKS, 1);
            helper.assertTrue(BuilderTestKit.count(arena.chest(), Items.DIRT) + BuilderStock.bagCount(builder.bag, Items.DIRT)
                + onGround(helper, Items.DIRT) == dirt, "the chest's other goods are untouched");
        });
    }

    // The chosen plank lies beyond the 128 units BuilderSupply asks for (144 cobblestone first), the
    // hut has none and a Courier is employed: the Builder asks for the plank itself and it is delivered.
    @GameTest(template = "empty32", timeoutTicks = 12000, batch = "builder_load")
    public void courierDeliversTheChosenStepBeyondTheRequestBatch(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 32, 4);
        ServerLevel level = arena.level();
        Settlement settlement = arena.settlement();
        int rows = 9;
        helper.assertTrue(rows * 16 > BuilderSupply.BATCH_UNITS, "fixture: the plank is beyond the request batch");
        BlockPos site = new BlockPos(8, 1, 2);
        BuildJob job = commit(helper, arena, site, "test_load_courier", rows);
        // Couriers keep the Hearth's food floor first (settlers find it at the settlement centre).
        BlockPos hearthRel = new BlockPos(16, 1, 16);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        var hearth = (com.hearthstead.block.HearthBlockEntity) level.getBlockEntity(helper.absolutePos(hearthRel));
        hearth.bindSettlement(settlement.id);
        hearth.getInventory().setStackInSlot(0, new ItemStack(Items.BREAD, 64));
        Building warehouse = GameTestFixtures.register(helper, settlement, BuildingType.WAREHOUSE, 25, 2);
        BlockPos storeRel = new BlockPos(26, 1, 3);
        helper.setBlock(storeRel, Blocks.CHEST);
        Container store = (Container) level.getBlockEntity(helper.absolutePos(storeRel));
        BuilderTestKit.stock(store, new ItemStack(Items.OAK_PLANKS, 1));
        BuilderTestKit.stock(arena.chest(), new ItemStack(Items.COBBLESTONE, rows * 16));
        SettlerEntity builder = BuilderTestKit.hireBuilder(helper, arena, site.offset(8, 0, rows + 4), "Frode");
        SettlerEntity courier = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(20, 1, 20));
        courier.bindTo(settlement.id, settlement.center);
        settlement.putRecord(courier.getUUID(), "Courier", Profession.NONE);
        helper.assertTrue(Employment.hire(level, settlement, warehouse, courier).ok(), "courier hired");
        List<Container> stores = List.of(arena.chest(), store);
        List<SettlerEntity> carriers = List.of(builder, courier);
        helper.succeedWhen(() -> {
            BuildJob live = BuilderTestKit.job(level, settlement, job.id);
            helper.assertTrue(live != null && live.state == BuildJob.State.COMPLETE,
                "site complete: " + (live == null ? "gone" : live.state + " " + live.status + " " + live.statusArgs));
            helper.assertTrue(placed(helper, site, Blocks.OAK_PLANKS, rows) == 1, "the delivered plank is set");
            assertConserved(helper, stores, carriers, List.of(site), Items.COBBLESTONE, Blocks.COBBLESTONE, rows * 16, rows);
            assertConserved(helper, stores, carriers, List.of(site), Items.OAK_PLANKS, Blocks.OAK_PLANKS, 1, rows);
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
        // Dina is hired once Cato holds site A, so each Builder stands by the
        // plank of his own site and both take the step beyond the prefix.
        SettlerEntity[] two = {null};
        java.util.Set<java.util.UUID> claimantsA = new java.util.HashSet<>();
        java.util.Set<java.util.UUID> claimantsB = new java.util.HashSet<>();
        helper.onEachTick(() -> {
            BuildJob a = BuilderTestKit.job(arena.level(), arena.settlement(), jobA.id);
            BuildJob bJob = BuilderTestKit.job(arena.level(), arena.settlement(), jobB.id);
            if (a != null && a.claimant != null) {
                claimantsA.add(a.claimant);
            }
            if (bJob != null && bJob.claimant != null) {
                claimantsB.add(bJob.claimant);
            }
            if (two[0] == null && a != null && one.getUUID().equals(a.claimant)) {
                two[0] = BuilderTestKit.hireBuilder(helper,
                    new BuilderTestKit.Arena(arena.level(), arena.settlement(), hutB, b), siteB.offset(8, 0, 8), "Dina");
            }
        });
        List<Container> huts = List.of(arena.chest(), b);
        List<BlockPos> sites = List.of(siteA, siteB);
        helper.succeedWhen(() -> {
            helper.assertTrue(two[0] != null, "Dina hired after Cato claimed site A");
            List<SettlerEntity> builders = List.of(one, two[0]);
            helper.assertTrue(claimantsA.equals(java.util.Set.of(one.getUUID()))
                && claimantsB.equals(java.util.Set.of(two[0].getUUID())), "each Builder kept his own site");
            assertComplete(helper, arena, jobA, siteA);
            assertComplete(helper, arena, jobB, siteB);
            assertConserved(helper, huts, builders, sites, Items.COBBLESTONE, Blocks.COBBLESTONE, 128);
            assertConserved(helper, huts, builders, sites, Items.OAK_PLANKS, Blocks.OAK_PLANKS, 2);
        });
    }
}
