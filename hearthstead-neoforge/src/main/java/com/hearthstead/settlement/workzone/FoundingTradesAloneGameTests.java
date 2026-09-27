package com.hearthstead.settlement.workzone;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.FishersChairBlock;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.gametest.GameTestFixtures;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.development.Development;
import com.hearthstead.settlement.development.DevelopmentNode;
import com.hearthstead.settlement.development.TechCraftGate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Set;
import java.util.UUID;

/**
 * Tech tree Option 2: the five founding trades each need only the Banner.
 * A village that learns ONLY one of them must get its plan, its emblem, a
 * hired worker and (Lumber Camp, Farm) a Work Zone target and the Scepter,
 * with nothing silently assuming Timber Rights or the Warehouse.
 * Batch "founding_trades_alone".
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class FoundingTradesAloneGameTests {
    private static final String BATCH = "founding_trades_alone";
    private static final ResourceLocation SCEPTER = Hearthstead.id("work_scepter");

    private record Village(Settlement settlement, HearthBlockEntity hearth) {
    }

    private static Village village(GameTestHelper helper, String name, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
        BlockPos hearthRel = new BlockPos(1, 1, 1);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        BlockPos abs = helper.absolutePos(hearthRel);
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getLevel().getBlockEntity(abs);
        Settlement s = new Settlement(UUID.randomUUID(), name, abs);
        s.radius = size + 4;
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(s.id, s);
        data.setDirty();
        hearth.bindSettlement(s.id);
        Development.revisionOf(helper.getLevel(), s); // initialize before any building
        return new Village(s, hearth);
    }

    /** Buys exactly this one founding node, paid in full from the Banner. */
    private static void learnOnly(GameTestHelper helper, Village v, DevelopmentNode node) {
        for (DevelopmentNode.Cost cost : node.costs()) {
            ItemStack stack = new ItemStack(cost.item(), cost.count());
            for (int slot = 0; slot < v.hearth().getInventory().getSlots() && !stack.isEmpty(); slot++) {
                if (v.hearth().getInventory().getStackInSlot(slot).isEmpty()) {
                    v.hearth().getInventory().setStackInSlot(slot, stack);
                    stack = ItemStack.EMPTY;
                }
            }
        }
        Development.Result result = Development.purchaseNode(helper.getLevel(), v.settlement(), v.hearth(),
            node, Development.revisionOf(helper.getLevel(), v.settlement()));
        helper.assertTrue(result == Development.Result.APPLIED,
            node.id() + " must be learnable with only the Banner: " + result);
        Set<String> learned = Development.of(helper.getLevel(), v.settlement()).unlockedIds();
        for (DevelopmentNode other : new DevelopmentNode[] {DevelopmentNode.TIMBER_RIGHTS,
                DevelopmentNode.STORES_AND_ROADS, DevelopmentNode.CULTIVATED_GROUND,
                DevelopmentNode.SHORE_PROVISIONS, DevelopmentNode.BORDER_WARDENS,
                DevelopmentNode.TRADING_POST, DevelopmentNode.HOME, DevelopmentNode.HOSPITALITY}) {
            if (other != node) {
                helper.assertFalse(learned.contains(other.id()), "fixture: only " + node.id() + " is learned, not " + other.id());
            }
        }
    }

    private static SettlerEntity hire(GameTestHelper helper, Village v, Building at, Profession expected,
                                      BlockPos spawn) {
        helper.assertTrue(Development.isBuildingUnlocked(helper.getLevel(), v.settlement(), at.type),
            at.type.id() + " plan must be unlocked by its founding trade alone");
        helper.assertTrue(Development.isEmblemUnlocked(helper.getLevel(), v.settlement(), expected),
            expected + " emblem must be unlocked by its founding trade alone");
        SettlerEntity worker = helper.spawn(ModEntities.SETTLER.get(), spawn);
        worker.setSettlerName("Solo " + expected);
        worker.bindTo(v.settlement().id, v.settlement().center);
        v.settlement().putRecord(worker.getUUID(), "Solo " + expected, Profession.NONE);
        worker.setHunger(100.0F);
        worker.setEnergy(100.0F);
        helper.assertTrue(Employment.hire(helper.getLevel(), v.settlement(), at, worker).ok()
                && worker.getProfession() == expected,
            "a " + expected + " can be hired at the " + at.type.id());
        return worker;
    }

    private static void scepterCraftable(GameTestHelper helper, Village v, boolean expected, String why) {
        var player = helper.makeMockServerPlayerInLevel();
        player.setPos(v.settlement().center.getX() + .5, v.settlement().center.getY() + 1, v.settlement().center.getZ() + .5);
        boolean allowed = TechCraftGate.allowed(helper.getLevel(), player, SCEPTER, ModItems.WORK_SCEPTER.get());
        // With crafting gates switched off in config every recipe is open.
        helper.assertTrue(allowed == (expected || !com.hearthstead.settlement.techtree.TechTreeConfig.gateCrafting()),
            why);
    }

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 100)
    public void aLumberOnlyVillageHiresALumbererAndDrawsItsZone(GameTestHelper helper) {
        Village v = village(helper, "Solo Lumber", 16);
        // Product rule kept (lead, 26 Sep): Timber Rights needs Foundation
        // Ready, a valid Banner and at least three settlers.
        for (int i = 0; i < 3; i++) {
            v.settlement().putRecord(UUID.randomUUID(), "Founder " + i, Profession.NONE);
        }
        learnOnly(helper, v, DevelopmentNode.TIMBER_RIGHTS);
        Building camp = GameTestFixtures.register(helper, v.settlement(), BuildingType.LUMBER_CAMP, 8, 8);
        hire(helper, v, camp, Profession.LUMBERER, new BlockPos(6, 1, 6));
        helper.assertTrue(WorkZoneService.validateTarget(helper.getLevel(), v.settlement(), camp,
                WorkZone.Type.LUMBER) == WorkZoneService.Result.APPLIED,
            "the Lumber Camp's Work Zone target is open");
        scepterCraftable(helper, v, true, "a Lumber-only village can make the Work Scepter");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 100)
    public void aFieldsOnlyVillageHiresAFarmerAndDrawsItsZone(GameTestHelper helper) {
        Village v = village(helper, "Solo Fields", 16);
        learnOnly(helper, v, DevelopmentNode.CULTIVATED_GROUND);
        Building farm = GameTestFixtures.register(helper, v.settlement(), BuildingType.FARMHOUSE, 8, 8);
        hire(helper, v, farm, Profession.FARMER, new BlockPos(6, 1, 6));
        helper.assertTrue(WorkZoneService.validateTarget(helper.getLevel(), v.settlement(), farm,
                WorkZone.Type.FARM) == WorkZoneService.Result.APPLIED,
            "the Farm's Work Zone target is open without Timber Rights (Option 2 regression)");
        scepterCraftable(helper, v, true, "a Fields-only village can make the Work Scepter for its farm zone");
        helper.succeed();
    }

    @GameTest(template = "empty32", batch = BATCH, timeoutTicks = 1200)
    public void aFisheryOnlyVillageHiresAFisherWhoFishes(GameTestHelper helper) {
        Village v = village(helper, "Solo Fishery", 24);
        learnOnly(helper, v, DevelopmentNode.SHORE_PROVISIONS);
        Building fishery = GameTestFixtures.register(helper, v.settlement(), BuildingType.FISHERY, 8, 8);
        helper.setBlock(new BlockPos(9, 1, 8), ModBlocks.FISH_RACK.get());
        helper.setBlock(new BlockPos(10, 1, 8), Blocks.BARREL);
        helper.setBlock(new BlockPos(8, 1, 11), ModBlocks.FISHERS_CHAIR.get().defaultBlockState()
            .setValue(FishersChairBlock.FACING, Direction.EAST));
        for (int x = 9; x <= 15; x++) {
            for (int z = 9; z <= 15; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.WATER);
            }
        }
        SettlerEntity fisher = hire(helper, v, fishery, Profession.FISHER, new BlockPos(6, 1, 11));
        fisher.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(ModItems.FISHERS_ROD.get()));
        scepterCraftable(helper, v, false, "a Fishery-only village has no Work Zone and no Scepter");
        helper.onEachTick(() -> helper.getLevel().setDayTime(2000));
        helper.succeedWhen(() -> helper.assertTrue(fisher.getActivity() == SettlerActivity.WORK_FISH,
            "the Fisher of a Fishery-only village fishes; act=" + fisher.getActivity()
                + " route=" + fisher.routeFailureNote()));
    }

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 100)
    public void aHuntersOnlyVillageHiresAHunter(GameTestHelper helper) {
        Village v = village(helper, "Solo Hunt", 16);
        learnOnly(helper, v, DevelopmentNode.BORDER_WARDENS);
        Building lodge = GameTestFixtures.register(helper, v.settlement(), BuildingType.HUNTERS_LODGE, 8, 8);
        hire(helper, v, lodge, Profession.HUNTER, new BlockPos(6, 1, 6));
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 100)
    public void aTradingPostOnlyVillageHiresATrader(GameTestHelper helper) {
        Village v = village(helper, "Solo Trade", 16);
        learnOnly(helper, v, DevelopmentNode.TRADING_POST);
        Building post = GameTestFixtures.register(helper, v.settlement(), BuildingType.TRADING_POST, 8, 8);
        hire(helper, v, post, Profession.TRADER, new BlockPos(6, 1, 6));
        helper.succeed();
    }
}
