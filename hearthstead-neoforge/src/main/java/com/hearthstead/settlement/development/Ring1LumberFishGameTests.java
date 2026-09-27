package com.hearthstead.settlement.development;

import com.hearthstead.gametest.GameTestFixtures;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.FishersChairBlock;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.FisherNetBuoyEntity;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.FisherNetGoal;
import com.hearthstead.entity.ai.LumbererWhetGoal;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.work.FisherNetYield;
import com.hearthstead.settlement.work.FishingGrounds;
import com.hearthstead.settlement.work.LumberWhetting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.GrindstoneBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * RING-1 lane (26 Sep): Sharpened Axes' whet at the grindstone and Fisher's
 * Nets' float, set, yield, haul, save/load, no duplicates and cleanup.
 * Batch "ring1_lumber_fish".
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class Ring1LumberFishGameTests {
    private static final String BATCH = "ring1_lumber_fish";

    // ------------------------------------------------------------ lumber

    private record Camp(Settlement settlement, Building camp, SettlerEntity lumberer) {
    }

    private static Camp camp(GameTestHelper helper, boolean grindstone, boolean owned) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 8; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
        BlockPos hearthRel = new BlockPos(2, 1, 2);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), "Whetwick", helper.absolutePos(hearthRel));
        s.radius = 12;
        data.settlements.put(s.id, s);
        data.setDirty();
        ((HearthBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(hearthRel))).bindSettlement(s.id);
        Building camp = GameTestFixtures.register(helper, s, BuildingType.LUMBER_CAMP, 11, 11);
        helper.setBlock(new BlockPos(12, 1, 12), Blocks.CHEST);
        if (grindstone) {
            helper.setBlock(new BlockPos(14, 1, 12), Blocks.GRINDSTONE.defaultBlockState()
                .setValue(GrindstoneBlock.FACE, AttachFace.FLOOR)
                .setValue(GrindstoneBlock.FACING, Direction.WEST));
        }
        if (owned) {
            DevelopmentState state = Development.of(helper.getLevel(), s);
            state.unlock(DevelopmentNode.SETTLEMENT_CHARTER);
            state.unlock(DevelopmentNode.SHELTER);
            state.unlock(DevelopmentNode.TIMBER_RIGHTS);
            state.unlockUpgrade(PostRaidUpgrade.SHARPENED_AXES);
        }
        SettlerEntity bram = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(6, 1, 11));
        bram.setSettlerName("Bramwell");
        bram.bindTo(s.id, s.center);
        s.putRecord(bram.getUUID(), "Bramwell", Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, camp, bram).ok(), "hire lumberer");
        bram.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
        bram.setHunger(100.0F);
        bram.setEnergy(100.0F);
        return new Camp(s, camp, bram);
    }

    private static void smallOak(GameTestHelper helper, BlockPos dirtRel) {
        helper.setBlock(dirtRel, Blocks.DIRT);
        BlockPos base = dirtRel.above();
        for (int i = 0; i < 4; i++) helper.setBlock(base.above(i), Blocks.OAK_LOG);
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                helper.setBlock(base.above(4).offset(dx, 0, dz), Blocks.OAK_LEAVES);
    }

    @GameTest(template = "empty16", timeoutTicks = 3600, batch = BATCH)
    public void theLumbererWhetsAfterEightTreesThenFellsAgain(GameTestHelper helper) {
        Camp c = camp(helper, true, true);
        SettlerEntity bram = c.lumberer();
        // Same stand as LumbererWornAxeGameTests: inside the fixture Work Zone.
        BlockPos dirtRel = new BlockPos(8, 1, 8);
        smallOak(helper, dirtRel);
        BlockPos baseAbs = helper.absolutePos(dirtRel.above());
        BlockPos stoneAbs = helper.absolutePos(new BlockPos(14, 1, 12));
        for (int i = 0; i < 7; i++) {
            LumbererWhetGoal.noteTreeFelled(helper.getLevel(), c.settlement(), bram);
        }
        helper.assertFalse(LumberWhetting.due(bram.getPersistentData().getInt(LumberWhetting.TREES_TAG)),
            "seven trees are not yet a whet");
        LumbererWhetGoal.noteTreeFelled(helper.getLevel(), c.settlement(), bram);
        helper.assertTrue(bram.getPersistentData().getInt(LumberWhetting.TREES_TAG) == 8, "eighth tree counted");

        final long[] whetDone = {-1};
        final long[] felled = {-1};
        final boolean[] atStone = {false};
        helper.onEachTick(() -> {
            helper.getLevel().setDayTime(2000);
            long now = helper.getLevel().getGameTime();
            if (bram.getActivity() == SettlerActivity.WORK_WHET
                && bram.position().distanceToSqr(stoneAbs.getCenter()) < 2.6) {
                atStone[0] = true;
            }
            if (whetDone[0] < 0 && bram.getPersistentData().getLong(LumberWhetting.LAST_WHET_TAG) > 0) {
                whetDone[0] = now;
            }
            if (felled[0] < 0 && !helper.getLevel().getBlockState(baseAbs).is(Blocks.OAK_LOG)) {
                felled[0] = now;
            }
        });
        helper.succeedWhen(() -> {
            String state = " [whetDone=" + whetDone[0] + " felled=" + felled[0] + " trees="
                + bram.getPersistentData().getInt(LumberWhetting.TREES_TAG) + " where="
                + bram.getPersistentData().getString(LumberWhetting.LAST_WHET_WHERE_TAG) + " act=" + bram.getActivity()
                + " at " + helper.relativePos(bram.blockPosition()) + " route=" + bram.routeFailureNote() + "]";
            helper.assertTrue(atStone[0], "he whets standing at the grindstone" + state);
            helper.assertTrue(whetDone[0] > 0, "the whet finishes");
            helper.assertTrue(bram.getPersistentData().getInt(LumberWhetting.TREES_TAG) <= 1,
                "the whet resets the tree counter");
            helper.assertTrue(felled[0] > 0 && felled[0] > whetDone[0],
                "after the whet he goes back to felling; whet=" + whetDone[0] + " felled=" + felled[0]
                    + " act=" + bram.getActivity() + " route=" + bram.routeFailureNote());
            helper.assertTrue(bram.getPersistentData().getInt(LumberWhetting.TREES_TAG) == 1,
                "the tree after the whet counts toward the next one");
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 800, batch = BATCH)
    public void aCampWithoutAGrindstoneWhetsOnTheSpot(GameTestHelper helper) {
        Camp c = camp(helper, false, true);
        SettlerEntity bram = c.lumberer();
        bram.getPersistentData().putInt(LumberWhetting.TREES_TAG, LumberWhetting.TREES_PER_WHET);
        final boolean[] seen = {false};
        helper.onEachTick(() -> {
            helper.getLevel().setDayTime(2000);
            if (bram.getActivity() == SettlerActivity.WORK_WHET) seen[0] = true;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(seen[0], "no grindstone: he still whets (whetstone on the spot)");
            helper.assertTrue(bram.getPersistentData().getInt(LumberWhetting.TREES_TAG) == 0, "counter reset");
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 400, batch = BATCH)
    public void withoutTheNodeNoWhetIsCounted(GameTestHelper helper) {
        Camp c = camp(helper, true, false);
        SettlerEntity bram = c.lumberer();
        for (int i = 0; i < 10; i++) {
            LumbererWhetGoal.noteTreeFelled(helper.getLevel(), c.settlement(), bram);
        }
        helper.assertTrue(bram.getPersistentData().getInt(LumberWhetting.TREES_TAG) == 0,
            "trees before Sharpened Axes do not count");
        helper.runAfterDelay(200, () -> {
            helper.assertFalse(bram.getActivity() == SettlerActivity.WORK_WHET, "no whet without the node");
            helper.succeed();
        });
    }

    // ------------------------------------------------------------ fish

    private record Shore(Settlement settlement, Building fishery, SettlerEntity fisher, Container rack,
                         Container barrel) {
    }

    private static Shore shore(GameTestHelper helper, boolean owned) {
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
        var data = SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), "Netherby", helper.absolutePos(new BlockPos(16, 1, 16)));
        s.radius = 30;
        data.settlements.put(s.id, s);
        data.setDirty();
        Building fishery = GameTestFixtures.register(helper, s, BuildingType.FISHERY, 8, 8);
        helper.setBlock(new BlockPos(9, 1, 8), ModBlocks.FISH_RACK.get());
        helper.setBlock(new BlockPos(8, 1, 11), ModBlocks.FISHERS_CHAIR.get().defaultBlockState()
            .setValue(FishersChairBlock.FACING, Direction.EAST));
        for (int x = 9; x <= 16; x++) {
            for (int z = 7; z <= 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.WATER);
            }
        }
        helper.setBlock(new BlockPos(10, 1, 8), Blocks.BARREL);
        helper.setBlock(new BlockPos(9, 1, 8), ModBlocks.FISH_RACK.get());
        Container rack = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(9, 1, 8)));
        Container barrel = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(10, 1, 8)));
        helper.assertTrue(FishingGrounds.scan(helper.getLevel(), fishery.anchor).ready(),
            "fixture must provide a valid shore chair");
        if (owned) {
            DevelopmentState state = Development.of(helper.getLevel(), s);
            state.unlock(DevelopmentNode.SETTLEMENT_CHARTER);
            state.unlock(DevelopmentNode.SHELTER);
            state.unlock(DevelopmentNode.STORES_AND_ROADS);
            state.unlock(DevelopmentNode.SHORE_PROVISIONS);
            state.unlockUpgrade(PostRaidUpgrade.FISHERS_NETS);
        }
        SettlerEntity finn = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(6, 1, 11));
        finn.setSettlerName("Finn");
        finn.bindTo(s.id, s.center);
        s.putRecord(finn.getUUID(), "Finn", Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, fishery, finn).ok(), "hire fisher");
        finn.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(ModItems.FISHERS_ROD.get()));
        finn.setHunger(100.0F);
        finn.setEnergy(100.0F);
        return new Shore(s, fishery, finn, rack, barrel);
    }

    private static int buoys(GameTestHelper helper, Building fishery) {
        return helper.getLevel().getEntitiesOfClass(FisherNetBuoyEntity.class,
            new AABB(fishery.anchor).inflate(FisherNetBuoyEntity.SEARCH, 8, FisherNetBuoyEntity.SEARCH),
            b -> b.isAlive() && b.belongsTo(fishery)).size();
    }

    private static int fishIn(Container... containers) {
        int n = 0;
        for (Container c : containers) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                if (c.getItem(i).is(ItemTags.FISHES)) n += c.getItem(i).getCount();
            }
        }
        return n;
    }

    @GameTest(template = "empty32", timeoutTicks = 4000, batch = BATCH)
    public void theFisherSetsANetItFillsToFourAndHeHaulsItIn(GameTestHelper helper) {
        Shore sh = shore(helper, true);
        SettlerEntity finn = sh.fisher();
        final FisherNetBuoyEntity[] buoy = {null};
        final boolean[] backdated = {false};
        final boolean[] sawNet = {false};
        helper.onEachTick(() -> {
            helper.getLevel().setDayTime(2000);
            if (finn.getActivity() == SettlerActivity.WORK_NET) sawNet[0] = true;
            if (buoy[0] == null) {
                buoy[0] = FisherNetBuoyEntity.find(helper.getLevel(), sh.fishery());
            }
            if (buoy[0] != null && !backdated[0]) {
                // Five in-game hours pass: the net holds its cap of four, never five.
                buoy[0].backdate(5L * FisherNetYield.TICKS_PER_FISH);
                helper.assertTrue(buoy[0].accrue(helper.getLevel().getGameTime()) == FisherNetYield.CAP,
                    "five hours fill the net to four");
                backdated[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(sawNet[0], "the Fisher works the net (WORK_NET)");
            helper.assertTrue(buoy[0] != null && buoys(helper, sh.fishery()) == 1,
                "exactly one float on the fishery water");
            helper.assertTrue(finn.getPersistentData().getInt(FisherNetGoal.HAULED_TAG) >= FisherNetYield.CAP,
                "he hauls the four fish in; hauled=" + finn.getPersistentData().getInt(FisherNetGoal.HAULED_TAG)
                    + " act=" + finn.getActivity() + " route=" + finn.routeFailureNote());
            helper.assertTrue(buoy[0].storedForDisplay() < FisherNetYield.CAP, "the hauled net is set again");
            helper.assertTrue(fishIn(sh.rack(), sh.barrel()) >= FisherNetYield.CAP,
                "the net's fish reach the fishery store; stored=" + fishIn(sh.rack(), sh.barrel()));
        });
    }

    @GameTest(template = "empty32", timeoutTicks = 200, batch = BATCH)
    public void aNetSurvivesSaveAndLoadAndNeverDoubles(GameTestHelper helper) {
        Shore sh = shore(helper, true);
        var level = helper.getLevel();
        BlockPos water = helper.absolutePos(new BlockPos(12, 0, 13));
        FisherNetBuoyEntity net = ModEntities.FISHER_NET_BUOY.get().create(level);
        net.set(sh.settlement(), sh.fishery(), water, level.getGameTime());
        net.backdate(2L * FisherNetYield.TICKS_PER_FISH + 400);
        level.addFreshEntity(net);
        helper.assertTrue(net.accrue(level.getGameTime()) == 2, "two hours, two fish");

        CompoundTag saved = new CompoundTag();
        net.saveWithoutId(saved);
        FisherNetBuoyEntity loaded = ModEntities.FISHER_NET_BUOY.get().create(level);
        loaded.load(saved);
        helper.assertTrue(loaded.belongsTo(sh.fishery()) && water.equals(loaded.water()),
            "the reloaded float knows its fishery and water cell");
        helper.assertTrue(loaded.accrue(level.getGameTime()) == 2, "the reloaded net still holds two fish");
        helper.assertTrue(loaded.accrue(level.getGameTime() + 600) == 3,
            "the part-hour carried across the reload: 600 more ticks land the third fish");

        // A second float for the same fishery (a copied chunk) is removed; one stays.
        FisherNetBuoyEntity twin = ModEntities.FISHER_NET_BUOY.get().create(level);
        twin.set(sh.settlement(), sh.fishery(), water.east(), level.getGameTime());
        level.addFreshEntity(twin);
        helper.runAfterDelay(90, () -> {
            helper.assertTrue(buoys(helper, sh.fishery()) == 1, "never two floats for one fishery");
            helper.succeed();
        });
    }

    @GameTest(template = "empty32", timeoutTicks = 200, batch = BATCH)
    public void aRemovedFisheryTakesItsFloatWithIt(GameTestHelper helper) {
        Shore sh = shore(helper, true);
        var level = helper.getLevel();
        FisherNetBuoyEntity net = ModEntities.FISHER_NET_BUOY.get().create(level);
        net.set(sh.settlement(), sh.fishery(), helper.absolutePos(new BlockPos(12, 0, 13)), level.getGameTime());
        level.addFreshEntity(net);
        BlockPos waterAbs = helper.absolutePos(new BlockPos(12, 0, 13));
        helper.assertTrue(level.getFluidState(waterAbs).isSource(), "the float sits on open water");
        sh.settlement().buildings.remove(sh.fishery());
        helper.runAfterDelay(90, () -> {
            helper.assertFalse(net.isAlive(), "no fishery, no float");
            helper.assertTrue(level.getFluidState(waterAbs).isSource(), "the water was never touched");
            helper.succeed();
        });
    }
}
