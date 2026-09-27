package com.hearthstead.settlement.development;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.gametest.BuilderTestKit;
import com.hearthstead.gametest.GameTestFixtures;
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
import com.hearthstead.settlement.builder.BuildStatus;
import com.hearthstead.settlement.builder.BuilderSupply;
import com.hearthstead.settlement.builder.VillageSupply;
import com.hearthstead.settlement.request.CraftingOrderBook;
import com.hearthstead.settlement.request.CraftingOrderService;
import com.hearthstead.settlement.techtree.TechCosts;
import com.hearthstead.settlement.techtree.TechTreeData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Owner-approved B4: population gate and truthful Builder supply promises. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class OvernightB4GameTests {
    @GameTest(template = "empty16", batch = "overnight_b4", timeoutTicks = 100)
    public void hospitalityCanBeLearnedWithThreeLivingResidentsAndNoBeds(GameTestHelper helper) {
        var arena = BuilderTestKit.arena(helper, 16, 4);
        Settlement s = arena.settlement();
        DevelopmentState state = Development.of(arena.level(), s);
        state.unlock(DevelopmentNode.SETTLEMENT_CHARTER);
        state.unlock(DevelopmentNode.SHELTER);
        state.unlock(DevelopmentNode.HOME);
        BlockPos banner = new BlockPos(8, 1, 8);
        helper.setBlock(banner, ModBlocks.HEARTH.get());
        HearthBlockEntity hearth = (HearthBlockEntity) arena.level().getBlockEntity(helper.absolutePos(banner));
        hearth.bindSettlement(s.id);
        resident(helper, s, new BlockPos(6, 1, 6), "Ada");
        resident(helper, s, new BlockPos(7, 1, 6), "Bo");
        int slot = 0;
        for (var cost : TechCosts.costs(TechTreeData.get().node("hospitality"))) {
            hearth.getInventory().setStackInSlot(slot++, new ItemStack(cost.item(), cost.count()));
        }
        var before = hearth.getInventory().serializeNBT(arena.level().registryAccess());
        int revision = state.revision();
        helper.assertTrue(TechTree.learn(arena.level(), s, hearth, "hospitality", revision, null)
                == TechTree.Result.GATE, "two residents cannot satisfy the population gate");
        helper.assertTrue(before.equals(hearth.getInventory().serializeNBT(arena.level().registryAccess()))
                && state.revision() == revision, "a refused purchase spends nothing and does not change revision");
        resident(helper, s, new BlockPos(8, 1, 6), "Cy");
        helper.assertTrue(s.population() == 3 && s.validBedCount() == 0, "three living residents, no housing");
        helper.assertTrue(DevelopmentQuests.complete(arena.level(), s, hearth, state,
                DevelopmentNode.HOSPITALITY), "the legacy Hospitality gate also accepts unhoused residents");
        helper.assertTrue(DevelopmentQuests.upgradeGateProgress(arena.level(), s, state,
                DevelopmentObjective.HOUSED_SETTLERS) == 0, "housing upgrades still require real beds");
        var result = TechTree.learn(arena.level(), s, hearth, "hospitality", revision, null);
        helper.assertTrue(result == TechTree.Result.LEARNED, "Hospitality can be learned: " + result);
        helper.assertTrue(DevelopmentState.readNbt(state.writeNbt()).unlocked(DevelopmentNode.HOSPITALITY),
                "the learned Hospitality node survives reload");
        var after = hearth.getInventory().serializeNBT(arena.level().registryAccess());
        helper.assertTrue(TechTree.learn(arena.level(), s, hearth, "hospitality", revision, null)
                == TechTree.Result.STALE
                && after.equals(hearth.getInventory().serializeNBT(arena.level().registryAccess())),
            "replaying the paid research request cannot spend again");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "overnight_b4", timeoutTicks = 100)
    public void glassNeedsAnUnlockedStaffedSmelterAndExistingStockStillCounts(GameTestHelper helper) {
        var arena = BuilderTestKit.arena(helper, 16, 4);
        var level = arena.level();
        var s = arena.settlement();
        DevelopmentState state = Development.of(level, s); // initialize before the synthetic locked workshop
        Building smelter = GameTestFixtures.register(helper, s, BuildingType.SMELTER, 10, 2);
        SettlerEntity worker = resident(helper, s, new BlockPos(10, 1, 1), "Smelter");
        helper.assertTrue(Employment.hire(level, s, smelter, worker).ok(), "fixture worker is hired");
        helper.assertTrue(!VillageSupply.canMake(level, s, Items.GLASS), "a staffed but locked Smelter promises no glass");
        var denied = CraftingOrderService.requestCraft(level, s, arena.hut(), Items.GLASS, 2,
            CraftingOrderBook.Source.MATERIAL).order();
        helper.assertTrue(denied != null && denied.status() == CraftingOrderBook.Status.NEEDS_PLAYER
                && BuilderSupply.onOrder(level, s, arena.hut(), Items.GLASS) == 0,
            "a locked recipe becomes a player need, never incoming glass");
        TechTreeTestGrants.grant(state, "craft_and_industry");
        helper.assertTrue(VillageSupply.canMake(level, s, Items.GLASS), "unlocked staffed Smelter can supply glass");
        smelter.workers.clear();
        helper.assertTrue(!VillageSupply.canMake(level, s, Items.GLASS), "unmanned Smelter promises no glass");
        Building warehouse = GameTestFixtures.register(helper, s, BuildingType.WAREHOUSE, 10, 10);
        helper.setBlock(new BlockPos(11, 1, 11), Blocks.CHEST);
        var stock = (net.minecraft.world.Container) level.getBlockEntity(helper.absolutePos(new BlockPos(11, 1, 11)));
        stock.setItem(0, new ItemStack(Items.GLASS, 1));
        helper.assertTrue(VillageSupply.canMake(level, s, Items.GLASS), "already owned glass remains available without a producer");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "overnight_b4", timeoutTicks = 100)
    public void staleWorkshopDemandDoesNotWaitOrDestroyPhysicalCraftReceipts(GameTestHelper helper) {
        var arena = BuilderTestKit.arena(helper, 16, 4);
        var level = arena.level();
        var s = arena.settlement();
        DevelopmentState state = Development.of(level, s);
        TechTreeTestGrants.grant(state, "craft_and_industry");
        Building smelter = GameTestFixtures.register(helper, s, BuildingType.SMELTER, 10, 2);
        SettlerEntity worker = resident(helper, s, new BlockPos(10, 1, 1), "Smelter");
        helper.assertTrue(Employment.hire(level, s, smelter, worker).ok(), "fixture worker is hired");
        var order = CraftingOrderService.requestCraft(level, s, arena.hut(), Items.GLASS, 4,
            CraftingOrderBook.Source.MATERIAL).order();
        helper.assertTrue(order != null && order.status() == CraftingOrderBook.Status.OPEN
                && BuilderSupply.onOrder(level, s, arena.hut(), Items.GLASS) == 4,
            "an actual staffed unlocked workshop creates incoming demand");
        helper.setBlock(new BlockPos(11, 1, 3), Blocks.CHEST);
        var stock = (net.minecraft.world.Container) level.getBlockEntity(helper.absolutePos(new BlockPos(11, 1, 3)));
        stock.setItem(0, new ItemStack(Items.GLASS, 1));
        var recipe = com.hearthstead.building.Production.of(BuildingType.SMELTER).stream()
            .filter(r -> r.output() == Items.GLASS).findFirst().orElseThrow();
        CraftingOrderService.noteCrafted(level, s, smelter, recipe);
        int made = order.made();
        helper.assertTrue(made > 0, "a real placed output is recorded once");
        smelter.workers.clear();
        helper.assertTrue(BuilderSupply.onOrder(level, s, arena.hut(), Items.GLASS) == 0,
            "an unmanned stale workshop is not promised incoming stock");
        helper.assertTrue(order.made() == made && !order.status().terminal()
                && stock.getItem(0).getCount() == 1,
            "reading supply does not cancel receipts or consume physical output");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "overnight_b4", timeoutTicks = 800)
    public void builderReportsNeedsGlassWithoutAWorkshop(GameTestHelper helper) {
        builderNeedsGlass(helper, false);
    }

    @GameTest(template = "empty16", batch = "overnight_b4", timeoutTicks = 800)
    public void builderReportsNeedsGlassWithALockedStaffedWorkshop(GameTestHelper helper) {
        builderNeedsGlass(helper, true);
    }

    private static void builderNeedsGlass(GameTestHelper helper, boolean lockedWorkshop) {
        var arena = BuilderTestKit.arena(helper, 16, 5);
        var level = arena.level();
        var s = arena.settlement();
        Development.of(level, s);
        if (lockedWorkshop) {
            Building smelter = GameTestFixtures.register(helper, s, BuildingType.SMELTER, 10, 2);
            SettlerEntity worker = resident(helper, s, new BlockPos(10, 1, 1), "Locked smelter");
            helper.assertTrue(Employment.hire(level, s, smelter, worker).ok(), "locked fixture workshop is staffed");
            helper.assertTrue(!Development.isBuildingUnlocked(level, s, BuildingType.SMELTER),
                "the staffed workshop really is locked");
        }
        BuilderTestKit.Cells cells = new BuilderTestKit.Cells().set(0, 0, 0, Blocks.GLASS.defaultBlockState());
        Blueprint blueprint = new Blueprint(BuilderTestKit.meta("b4_glass", BlueprintMeta.Kind.DEFENSE,
            null, null, null), 1, 1, 1, cells.list());
        var plan = BuildPlanner.planBlueprint(level, s, blueprint,
            helper.absolutePos(new BlockPos(10, 1, 10)), 0, false, null);
        helper.assertTrue(plan.job() != null, "glass plan is valid: " + plan.validation().reasonKey());
        BuildJob job = plan.job();
        helper.assertTrue(BuildJobs.commit(level, s, job) == null, "glass job committed");
        BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 4), "Builder");
        CraftingOrderService.scan(level, s);
        helper.assertTrue(CraftingOrderService.orders(level, s).stream().anyMatch(o ->
                arena.hut().id.equals(o.requesterId()) && o.itemId().equals(net.minecraft.resources.ResourceLocation.parse("minecraft:glass"))
                && o.status() == CraftingOrderBook.Status.NEEDS_PLAYER),
            "unavailable glass has an explicit player need");
        helper.succeedWhen(() -> {
            BuildJob live = BuilderTestKit.job(level, s, job.id);
            helper.assertTrue(live != null && live.status == BuildStatus.NEEDS_PLAYER
                    && live.statusArgs.contains("item:minecraft:glass"),
                "Builder reports needs Glass: " + (live == null ? "missing" : live.status + " " + live.statusArgs));
            helper.assertTrue(helper.getBlockState(new BlockPos(10, 1, 10)).isAir(),
                "no glass appears without physical material");
        });
    }

    private static SettlerEntity resident(GameTestHelper helper, Settlement s, BlockPos position, String name) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), position);
        settler.bindTo(s.id, s.center);
        settler.setSettlerName(name);
        settler.setNoAi(true);
        s.putRecord(settler.getUUID(), name, Profession.NONE);
        return settler;
    }
}
