package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.workzone.WorkZone;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * The farmer's bootstrap (farmer audit 2026-08-25). Every pre-existing
 * farmer test seeds its arena with a mature crop first, which is exactly
 * the hole the audit found: with no crop anywhere, the old goal had no
 * plant path at all -- the only setBlock-plant was the replant on a
 * just-harvested tile, and tilling was gated on a crop that could only
 * ever come from a harvest. These tests start from the empty state the
 * player actually starts from: a valid farmhouse, seeds in its chest, and
 * bare ground.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class FarmerBootstrapGameTests {

    /** Same arena idiom as EffortGameTests: guaranteed-flat floor, cleared
     *  air, 2-high perimeter wall (structure templates only reserve bounds;
     *  their contents cannot be trusted). */
    private static void buildArena(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                boolean rim = x == 0 || z == 0 || x == size - 1 || z == size - 1;
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z),
                        rim && y <= 2 ? Blocks.STONE_BRICKS.defaultBlockState()
                                      : Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    /** Registered with SettlementSavedData so settler.settlement() resolves
     *  through the manager. Radius 6, small on purpose. */
    private static Settlement settlement(GameTestHelper helper) {
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), "Testholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        s.radius = 6;
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

    private static Building farmhouse(GameTestHelper helper, Settlement s, int x, int z) {
        // Delegates to the one place that places the plaque a building
        // needs to survive BuildingManager's sweep -- see GameTestFixtures
        // (KF-021 / FLAKE-2, 2026-08-26).
        return GameTestFixtures.register(helper, s, BuildingType.FARMHOUSE, x, z);
    }

    private static SettlerEntity farmer(GameTestHelper helper, Settlement s,
                                        Building farmhouse, int x, int z) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(x, 1, z));
        settler.setSettlerName("Astrid");
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), "Astrid", Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, farmhouse, settler).ok(),
            "setup: the farmhouse must take its first farmer");
        return settler;
    }

    private static Container chestAt(GameTestHelper helper, int x, int z) {
        helper.setBlock(new BlockPos(x, 1, z), Blocks.CHEST);
        return (Container) helper.getLevel()
            .getBlockEntity(helper.absolutePos(new BlockPos(x, 1, z)));
    }

    private static int countIn(Container container, net.minecraft.world.item.Item item) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int bagSeeds(SettlerEntity settler) {
        int total = 0;
        for (int i = 0; i < settler.bag.getContainerSize(); i++) {
            ItemStack stack = settler.bag.getItem(i);
            if (stack.is(Items.WHEAT_SEEDS)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int bagCount(SettlerEntity settler,
                                net.minecraft.world.item.Item item) {
        int total = 0;
        for (int i = 0; i < settler.bag.getContainerSize(); i++) {
            ItemStack stack = settler.bag.getItem(i);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    // ---------------------------------------------------- the bootstrap ---

    /**
     * The headline: a fully valid farmhouse, seeds in the building's own
     * chest, and NO crop anywhere in the world. The farmer must fetch
     * seeds, till the tended plot without any pre-existing crop anchor,
     * and give a fresh tile its first planting -- through WORK_PLANT, the
     * first-planting clip, not the replant's WORK_SOW. Items are conserved
     * exactly: every seed is in the chest, in the bag, or standing in the
     * plot as a crop.
     */
    @GameTest(template = "empty16", timeoutTicks = 1600, batch = "farmer_bootstrap_day")
    public void farmerBootstrapsABrandNewPlotFromChestSeeds(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 16);
        Settlement s = settlement(helper);
        Building house = farmhouse(helper, s, 8, 8);
        // The fresh farmer's 3x3 tended plot spans anchor +-1: rel 7..9.
        // Dirt there, and NOTHING planted anywhere -- the exact state every
        // older farmer test papered over with a pre-placed mature crop.
        for (int x = 7; x <= 9; x++) {
            for (int z = 7; z <= 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.DIRT);
            }
        }
        // The farmhouse's own chest: inside the building bounds (anchor +0..3),
        // outside the plot, holding the starter seeds a player would stock.
        Container chest = chestAt(helper, 10, 10);
        chest.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 16));
        chest.setItem(1, new ItemStack(Items.IRON_HOE));
        SettlerEntity astrid = farmer(helper, s, house, 8, 8);
        // FLAKE-1 (2026-08-26): DEXTERITY is rolled from the entity's own
        // unseeded RandomSource, so it differs every run. This test's "3x3
        // plot spans anchor +-1: rel 7..9" comment above is only true below
        // the tended-plot formula's first widening threshold (20 DEXTERITY;
        // see FarmerWorkGoal#tendedHalfSide) -- a fresh roll is capped under
        // that today (SettlerAttributes.START_CAP=15), but this test is
        // about the bootstrap, not about what regime a fresh roll happens to
        // land in, so it pins the one number it actually depends on instead
        // of inheriting that invariant implicitly from a constant it does
        // not otherwise reference.
        astrid.attributes().pinForTest(com.hearthstead.entity.Attribute.DEXTERITY, 10);

        final boolean[] sawFirstPlantClip = {false};
        helper.succeedWhen(() -> {
            if (astrid.getActivity() == SettlerActivity.WORK_PLANT) {
                sawFirstPlantClip[0] = true;
            }
            boolean tilled = false;
            int crops = 0;
            for (int x = 7; x <= 9; x++) {
                for (int z = 7; z <= 9; z++) {
                    if (helper.getBlockState(new BlockPos(x, 0, z)).is(Blocks.FARMLAND)) {
                        tilled = true;
                    }
                    if (helper.getBlockState(new BlockPos(x, 1, z))
                        .getBlock() instanceof CropBlock) {
                        crops++;
                    }
                }
            }
            String diag = " [act=" + astrid.getActivity() + " pos=" + astrid.blockPosition()
                + " bagSeeds=" + bagSeeds(astrid)
                + " chestSeeds=" + countIn(chest, Items.WHEAT_SEEDS) + "]";
            helper.assertTrue(tilled,
                "a farmer with chest seeds must till the bare tended plot" + diag);
            helper.assertTrue(crops >= 1,
                "the freshly tilled plot must receive its FIRST planting -- a crop "
                    + "must stand where there was never one before" + diag);
            helper.assertTrue(sawFirstPlantClip[0],
                "first planting must run through WORK_PLANT (FARM_PLANT clip), "
                    + "not the replant's WORK_SOW" + diag);
            // Chest truth: 16 seeds went in; every one is accounted for.
            int accounted = countIn(chest, Items.WHEAT_SEEDS) + bagSeeds(astrid) + crops;
            helper.assertTrue(accounted == 16,
                "seeds must be conserved exactly (chest + bag + planted == 16), got "
                    + accounted + diag);
        });
    }

    // ------------------------------------------------------ the reserve ---

    /**
     * A full harvest-replant-deposit cycle must leave future seed in the
     * exact linked Farmhouse storage. Loose, untagged bag seeds are ordinary
     * output under the strict worker provenance contract; banking them and
     * withdrawing one exact action-tagged planting input is the authority
     * boundary this regression now pins.
     */
    @GameTest(template = "empty16", timeoutTicks = 1600, batch = "farmer_bootstrap_day")
    public void depositHoldsBackTheSeedReserve(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 16);
        Settlement s = settlement(helper);
        Building house = farmhouse(helper, s, 8, 8);
        Container storage = chestAt(helper, 10, 10);
        storage.setItem(0, new ItemStack(Items.IRON_HOE));
        // ONE mature crop inside the plot, on real farmland, so the cycle is
        // deterministic: harvest, replant (one seed spent), then a deposit
        // trip with wheat in the bag. Moisture 7 keeps watering out of it.
        BlockPos cropRel = new BlockPos(9, 1, 8);
        helper.setBlock(new BlockPos(9, 0, 8), Blocks.FARMLAND.defaultBlockState()
            .setValue(FarmBlock.MOISTURE, 7));
        helper.setBlock(cropRel, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        SettlerEntity astrid = farmer(helper, s, house, 7, 7);
        // Four loose seeds are deliberately ordinary physical stock. The
        // Farmer must bank them, then withdraw only the exact planting input.
        astrid.bag.addItem(new ItemStack(Items.WHEAT_SEEDS, 4));

        helper.succeedWhen(() -> {
            boolean hasWheat = countIn(storage, Items.WHEAT) > 0;
            int storedSeeds = countIn(storage, Items.WHEAT_SEEDS);
            BlockState replanted = helper.getBlockState(cropRel);
            String diag = " [act=" + astrid.getActivity() + " pos=" + astrid.blockPosition()
                + " bagSeeds=" + bagSeeds(astrid) + " storedSeeds=" + storedSeeds
                + " crop=" + replanted + "]";
            helper.assertTrue(hasWheat,
                "the harvest must reach the farmhouse storage (the deposit cycle must complete)"
                    + diag);
            helper.assertTrue(replanted.is(Blocks.WHEAT)
                    && replanted.getValue(CropBlock.AGE) < 7,
                "the harvested tile must be replanted" + diag);
            helper.assertTrue(storedSeeds >= 1,
                "the exact Farmhouse must retain a future seed reserve after "
                    + "banking loose output and consuming one planting input" + diag);
        });
    }

    /**
     * Regression for the old whole-settlement volume scan. The Hearth is in
     * one corner while the farmhouse and its ripe outer field tile are far
     * away. A field-scoped survey must notice the crop promptly; the former
     * 84,681-offset cursor could leave this visible worker idle for tens of
     * seconds even though the crop stood inside their own tended plot.
     */
    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "farmer_bootstrap_day")
    public void remoteFarmhouseCropIsNoticedPromptly(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 16);

        BlockPos hearthRel = new BlockPos(1, 1, 1);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        BlockPos hearthAbs = helper.absolutePos(hearthRel);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(),
            "Langaker", hearthAbs);
        settlement.radius = 20;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        if (helper.getLevel().getBlockEntity(hearthAbs)
            instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(settlement.id);
        }

        Building house = farmhouse(helper, settlement, 8, 8);
        Container storage = chestAt(helper, 10, 10);
        storage.setItem(0, new ItemStack(Items.IRON_HOE));
        BlockPos cropRel = new BlockPos(13, 1, 13);
        helper.setBlock(cropRel.below(), Blocks.FARMLAND.defaultBlockState()
            .setValue(FarmBlock.MOISTURE, 7));
        helper.setBlock(cropRel, Blocks.WHEAT.defaultBlockState()
            .setValue(CropBlock.AGE, 7));

        SettlerEntity astrid = farmer(helper, settlement, house, 9, 9);
        astrid.attributes().pinForTest(
            com.hearthstead.entity.Attribute.DEXTERITY, 100);

        helper.succeedWhen(() -> {
            BlockState crop = helper.getBlockState(cropRel);
            helper.assertTrue(astrid.getActivity() == SettlerActivity.WORK_HARVEST
                    || !crop.is(Blocks.WHEAT)
                    || crop.getValue(CropBlock.AGE) < 7,
                "a farmer must notice a ripe crop in their remote tended field "
                    + "within ten seconds (activity=" + astrid.getActivity()
                    + ", pos=" + astrid.blockPosition()
                    + ", route=" + astrid.routeFailureNote() + ")");
        });
    }

    /**
     * A Farm Zone's height is real authority/headroom, but it must never make
     * a distant crop wait behind hundreds of empty vertical voxels.  The crop
     * at (14, 1, 14) is behind more than the old 512 nearest-first 3D samples
     * of this 16x16x64 confirmed zone; the new floor/crop-column cursor must
     * still reach it promptly and harvest it through the ordinary transaction.
     */
    @GameTest(template = "empty16", timeoutTicks = 20,
        batch = "farmer_bootstrap_day")
    public void tallConfirmedFarmFindsFarFloorCropWithoutIdleCooldown(
            GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 16);
        Settlement s = settlement(helper);
        s.radius = 20;
        Building house = farmhouse(helper, s, 8, 8);
        Container storage = chestAt(helper, 10, 10);
        storage.setItem(0, new ItemStack(Items.IRON_HOE));

        WorkZone tall = WorkZone.between(s.id, house.id, WorkZone.Type.FARM,
            helper.getLevel().dimension().location(),
            helper.absolutePos(new BlockPos(0, 0, 0)),
            helper.absolutePos(new BlockPos(15, 63, 15)), 2);
        helper.assertTrue(house.commitWorkZone(1, tall),
            "fixture: the tall confirmed Farm Zone must replace the default zone");

        BlockPos cropRel = new BlockPos(14, 1, 14);
        helper.setBlock(cropRel.below(), Blocks.FARMLAND.defaultBlockState()
            .setValue(FarmBlock.MOISTURE, 7));
        helper.setBlock(cropRel, Blocks.WHEAT.defaultBlockState()
            .setValue(CropBlock.AGE, 7));

        // Start beside the crop so this deadline isolates field discovery,
        // rather than making normal path length part of a scan regression.
        // The crop remains distant from the farmhouse scan origin.
        SettlerEntity astrid = farmer(helper, s, house, 14, 14);
        astrid.attributes().pinForTest(
            com.hearthstead.entity.Attribute.DEXTERITY, 100);

        helper.succeedWhen(() -> {
            BlockState crop = helper.getBlockState(cropRel);
            helper.assertTrue(astrid.getActivity() == SettlerActivity.WORK_HARVEST
                    || !crop.is(Blocks.WHEAT)
                    || crop.getValue(CropBlock.AGE) < 7,
                "the far crop in a tall confirmed zone must be selected without "
                    + "per-batch idle cooldown (activity=" + astrid.getActivity()
                    + ", stop=" + astrid.logisticsStopReason()
                    + ", route=" + astrid.routeFailureNote() + ")");
        });
    }

    /**
     * A full farmhouse may bounce a partial load back into the bag. That load
     * can be smaller than the normal eight-item departure threshold, but it is
     * still real produce and must remain a recoverable logistics obligation.
     * The retry is deliberately delayed: a full chest must not make the goal
     * rescan and reinsert every server tick.
     */
    @GameTest(template = "empty16", timeoutTicks = 320,
        batch = "farmer_storage_recovery")
    public void partialProduceRecoversAfterFullStorageClears(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 16);
        Settlement s = settlement(helper);
        Building house = farmhouse(helper, s, 8, 8);
        Container storage = chestAt(helper, 10, 10);
        for (int slot = 0; slot < storage.getContainerSize(); slot++) {
            storage.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }

        SettlerEntity astrid = farmer(helper, s, house, 10, 9);
        astrid.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_HOE));
        astrid.bag.addItem(new ItemStack(Items.WHEAT, 3));

        final String[] firstBlockedTrace = {null};
        helper.runAtTickTime(20, () -> {
            firstBlockedTrace[0] = astrid.routeFailureNote();
            helper.assertTrue(firstBlockedTrace[0].startsWith(
                    "farmhouse_storage_full@"),
                "a full farmhouse must publish its exact stop reason, got "
                    + firstBlockedTrace[0]);
        });
        helper.runAtTickTime(80, () -> {
            helper.assertTrue(firstBlockedTrace[0] != null
                    && firstBlockedTrace[0].equals(astrid.routeFailureNote()),
                "the full-storage retry must be bounded instead of rewriting "
                    + "the same failure every tick (first=" + firstBlockedTrace[0]
                    + ", now=" + astrid.routeFailureNote() + ")");
            storage.setItem(0, ItemStack.EMPTY);
            storage.setChanged();
        });

        helper.succeedWhen(() -> {
            helper.assertTrue(countIn(storage, Items.WHEAT) == 3
                    && bagCount(astrid, Items.WHEAT) == 0,
                "the sub-threshold remainder must retry after capacity returns "
                    + "without duplication or loss (chest="
                    + countIn(storage, Items.WHEAT) + ", bag="
                    + bagCount(astrid, Items.WHEAT) + ", route="
                    + astrid.routeFailureNote() + ")");
        });
    }
}
