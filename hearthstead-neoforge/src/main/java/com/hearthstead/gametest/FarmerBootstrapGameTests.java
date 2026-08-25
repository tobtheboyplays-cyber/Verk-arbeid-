package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
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
        BlockPos anchor = helper.absolutePos(new BlockPos(x, 1, z));
        Building building = new Building(UUID.randomUUID(), BuildingType.FARMHOUSE,
            helper.absolutePos(new BlockPos(x, 2, z)), anchor,
            BoundingBox.fromCorners(anchor, anchor.offset(3, 2, 3)));
        building.valid = true;
        s.buildings.add(building);
        return building;
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
    @GameTest(template = "empty16", timeoutTicks = 1600, batch = "day")
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
        SettlerEntity astrid = farmer(helper, s, house, 8, 8);

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
     * The deposit no longer strips the working stock: after a full
     * harvest-replant-deposit cycle, the bag still holds seed for the crop
     * that is growing (the reserve keeps two for wheat, whose drop RNG
     * rolls zero seeds ~25% of the time). Before the audit fix, the
     * deposit emptied the ENTIRE bag -- seeds included -- so this exact
     * cycle ended with zero seeds and a tile that could die permanently.
     */
    @GameTest(template = "empty16", timeoutTicks = 1600, batch = "day")
    public void depositHoldsBackTheSeedReserve(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 16);
        BlockPos hearthRel = new BlockPos(8, 1, 8);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        BlockPos hearthAbs = helper.absolutePos(hearthRel);
        Settlement s = settlement(helper); // center == the hearth position
        if (helper.getLevel().getBlockEntity(hearthAbs) instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(s.id);
        }
        Building house = farmhouse(helper, s, 8, 8);
        // ONE mature crop inside the plot, on real farmland, so the cycle is
        // deterministic: harvest, replant (one seed spent), then a deposit
        // trip with wheat in the bag. Moisture 7 keeps watering out of it.
        BlockPos cropRel = new BlockPos(9, 1, 8);
        helper.setBlock(new BlockPos(9, 0, 8), Blocks.FARMLAND.defaultBlockState()
            .setValue(FarmBlock.MOISTURE, 7));
        helper.setBlock(cropRel, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        SettlerEntity astrid = farmer(helper, s, house, 7, 7);
        // Bag stocked as if from an earlier bootstrap withdrawal: 4 seeds
        // guarantees the replant AND leaves the reserve unambiguous -- at
        // deposit time there are at least 3 seeds aboard, so anything less
        // than the kept reserve afterwards is the old dump-everything bug.
        astrid.bag.addItem(new ItemStack(Items.WHEAT_SEEDS, 4));

        helper.succeedWhen(() -> {
            HearthBlockEntity hearth =
                helper.getLevel().getBlockEntity(hearthAbs) instanceof HearthBlockEntity be
                    ? be : null;
            helper.assertTrue(hearth != null, "hearth block entity missing");
            boolean hasWheat = false;
            for (int i = 0; i < hearth.getInventory().getSlots(); i++) {
                if (hearth.getInventory().getStackInSlot(i).is(Items.WHEAT)) {
                    hasWheat = true;
                    break;
                }
            }
            BlockState replanted = helper.getBlockState(cropRel);
            String diag = " [act=" + astrid.getActivity() + " pos=" + astrid.blockPosition()
                + " bagSeeds=" + bagSeeds(astrid) + " crop=" + replanted + "]";
            helper.assertTrue(hasWheat,
                "the harvest must reach the hearth (the deposit cycle must complete)"
                    + diag);
            helper.assertTrue(replanted.is(Blocks.WHEAT)
                    && replanted.getValue(CropBlock.AGE) < 7,
                "the harvested tile must be replanted" + diag);
            helper.assertTrue(bagSeeds(astrid) >= 1,
                "the deposit must hold back the seed reserve for the growing crop "
                    + "instead of dumping the whole bag" + diag);
        });
    }
}
