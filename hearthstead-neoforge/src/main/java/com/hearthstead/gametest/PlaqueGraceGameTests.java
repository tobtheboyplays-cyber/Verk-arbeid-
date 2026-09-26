package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * The survey grace period: one bad reading must never fire the staff.
 *
 * <p>Pinned from a live failure (20260825T183505Z): placing bakery blocks
 * nudged a re-survey of the neighbouring warehouse mid-edit, one transient
 * failed scan unlinked it, and its courier was silently unemployed — the
 * very next hire command legally took her for the bakery. The grace window
 * ({@code PlaqueBlockEntity.GRACE_SURVEYS}) shields a standing building
 * through a renovation; only SUSTAINED brokenness dissolves it, and breaking
 * the plaque itself stays immediate (D-005, verified live the same evening).
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class PlaqueGraceGameTests {

    private static void floor(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
    }

    private static Settlement settlement(GameTestHelper helper) {
        com.hearthstead.settlement.SettlementSavedData data =
            com.hearthstead.settlement.SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), "Testholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        // Radius 6, same reasoning as EmploymentGameTests: a generous test
        // settlement answers for its neighbour's hearth.
        s.radius = 6;
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

    private static SettlerEntity settler(GameTestHelper helper, Settlement s,
                                         String name, int x, int z) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(x, 1, z));
        settler.setSettlerName(name);
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), name, Profession.NONE);
        return settler;
    }

    /**
     * A 7x7 farmhouse shell at {@code o}: stone walls y1..3, roof y4, door in
     * the south wall, torch, composter and chest inside (5x5 interior gives
     * floorSpace 16+ once furniture is counted out).
     */
    private static void buildFarmRoom(GameTestHelper helper, BlockPos o) {
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                boolean wall = x == 0 || z == 0 || x == 6 || z == 6;
                for (int y = 1; y <= 3; y++) {
                    if (wall) {
                        helper.setBlock(o.offset(x, y, z), Blocks.STONE_BRICKS);
                    }
                }
                helper.setBlock(o.offset(x, 4, z), Blocks.STONE_BRICKS);
                helper.setBlock(o.offset(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
        helper.setBlock(o.offset(3, 1, 0), Blocks.OAK_DOOR.defaultBlockState());
        helper.setBlock(o.offset(3, 2, 0), Blocks.OAK_DOOR.defaultBlockState()
            .setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
        helper.setBlock(o.offset(1, 1, 1), Blocks.COMPOSTER);
        helper.setBlock(o.offset(5, 1, 1), Blocks.CHEST);
        helper.setBlock(o.offset(1, 2, 5), Blocks.TORCH);
    }

    /** Hangs a fitted farmhouse plaque on the outside south wall. */
    private static BlockPos hangPlaque(GameTestHelper helper, BlockPos o) {
        BlockPos plaqueRel = o.offset(1, 2, -1);
        helper.setBlock(plaqueRel, com.hearthstead.registry.ModBlocks.PLAQUE.get()
            .defaultBlockState()
            .setValue(com.hearthstead.block.PlaqueBlock.FACING, Direction.NORTH));
        BlockPos abs = helper.absolutePos(plaqueRel);
        if (helper.getLevel().getBlockEntity(abs)
            instanceof PlaqueBlockEntity plaque) {
            plaque.insertPlan(helper.getLevel(),
                com.hearthstead.block.PlaqueItemData.stamped(
                    new ItemStack(com.hearthstead.registry.ModItems.BUILD_PLAN.get()),
                    BuildingType.FARMHOUSE));
        }
        return plaqueRel;
    }

    private static PlaqueBlockEntity plaqueAt(GameTestHelper helper, BlockPos rel) {
        var be = helper.getLevel().getBlockEntity(helper.absolutePos(rel));
        helper.assertTrue(be instanceof PlaqueBlockEntity,
            "the plaque block entity should exist");
        return (PlaqueBlockEntity) be;
    }

    /**
     * The renovation: rip out the composter, survey twice — the worker keeps
     * their job and the building stands (visibly incomplete); put it back and
     * everything reads healthy again. This test FAILS on the pre-grace code,
     * where the first bad survey cleared the roster.
     */
    @GameTest(batch = "plaque_grace", template = "empty16", timeoutTicks = 400)
    public void aRenovationKeepsTheStaff(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        BlockPos o = new BlockPos(4, 0, 4);
        buildFarmRoom(helper, o);
        BlockPos plaqueRel = hangPlaque(helper, o);

        helper.runAfterDelay(20, () -> {
            PlaqueBlockEntity plaque = plaqueAt(helper, plaqueRel);
            plaque.survey(helper.getLevel());
            helper.assertTrue(plaque.state().hasBuilding(),
                "the farm room must register before the renovation starts");
            Building building = plaque.building(helper.getLevel());
            helper.assertTrue(building != null, "a registered plaque has a building");

            SettlerEntity worker = settler(helper, s, "Greta", 8, 2);
            helper.assertTrue(
                Employment.hire(helper.getLevel(), s, building, worker).ok(),
                "the farmhouse must be able to take a farmer");

            // The renovation: the composter comes out for a moment.
            helper.setBlock(o.offset(1, 1, 1), Blocks.AIR);
            plaque.survey(helper.getLevel());
            plaque.survey(helper.getLevel());

            helper.assertTrue(
                Employment.employerOf(s, worker.getUUID()) != null,
                "two bad surveys inside the grace window must NOT fire the farmer");
            helper.assertTrue(building.valid,
                "the building rides out the renovation");
            helper.assertTrue(!plaque.state().hasBuilding(),
                "but the sheet must SHOW the trouble while it lasts");

            // The composter goes back in; the next survey heals everything.
            helper.setBlock(o.offset(1, 1, 1), Blocks.COMPOSTER);
            plaque.survey(helper.getLevel());
            helper.assertTrue(plaque.state().hasBuilding(),
                "a repaired room reads healthy again");
            helper.assertTrue(
                Employment.employerOf(s, worker.getUUID()) != null,
                "and the farmer never noticed");
            helper.succeed();
        });
    }

    /**
     * Grace is a window, not amnesty: leave the room broken past
     * GRACE_SURVEYS consecutive readings and the building genuinely
     * dissolves its links — workers freed, validity gone.
     */
    @GameTest(batch = "plaque_grace", template = "empty16", timeoutTicks = 400)
    public void aSustainedRuinDoesFireTheStaff(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        BlockPos o = new BlockPos(4, 0, 4);
        buildFarmRoom(helper, o);
        BlockPos plaqueRel = hangPlaque(helper, o);

        helper.runAfterDelay(20, () -> {
            PlaqueBlockEntity plaque = plaqueAt(helper, plaqueRel);
            plaque.survey(helper.getLevel());
            helper.assertTrue(plaque.state().hasBuilding(), "the room registers first");
            Building building = plaque.building(helper.getLevel());
            helper.assertTrue(building != null, "a registered plaque has a building");
            SettlerEntity worker = settler(helper, s, "Hakon", 8, 2);
            helper.assertTrue(
                Employment.hire(helper.getLevel(), s, building, worker).ok(),
                "the farmhouse must be able to take a farmer");

            helper.setBlock(o.offset(1, 1, 1), Blocks.AIR);
            // One past the grace window: 3 forgiven, the 4th is real.
            for (int i = 0; i < 4; i++) {
                plaque.survey(helper.getLevel());
            }
            helper.assertTrue(
                Employment.employerOf(s, worker.getUUID()) == null,
                "sustained ruin must genuinely free the worker");
            helper.assertTrue(!building.valid,
                "and the building no longer stands");
            helper.succeed();
        });
    }

    /** Raid damage must suspend a real Warehouse, survive saving, and recover
     * through its existing Courier using the surviving store's real materials. */
    @GameTest(batch = "courier_raid_recovery", template = "empty16", timeoutTicks = 1600)
    public void warehouseCourierRepairsRecordedDamageWithoutLosingEmployment(GameTestHelper helper) {
        workplaceRepairsWithoutLosingEmployment(helper, BuildingType.WAREHOUSE, Profession.COURIER);
    }

    @GameTest(batch = "farmer_raid_recovery", template = "empty16", timeoutTicks = 1600)
    public void farmerRepairsOwnRaidDamageWithoutLosingEmployment(GameTestHelper helper) {
        workplaceRepairsWithoutLosingEmployment(helper, BuildingType.FARMHOUSE, Profession.FARMER);
    }

    private void workplaceRepairsWithoutLosingEmployment(GameTestHelper helper,
            BuildingType workplaceType, Profession profession) {
        helper.getLevel().setDayTime(3000);
        floor(helper, 16);
        // Clear generated terrain explicitly; template air does not establish
        // the walkable height of a real room in every GameTest placement.
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            for (int y = 1; y <= 4; y++) helper.setBlock(new BlockPos(x,y,z), Blocks.AIR);
        }
        Settlement original = settlement(helper);
        original.radius = 12;
        for (int x = 3; x <= 11; x++) {
            for (int z = 3; z <= 11; z++) {
                if (x == 3 || x == 11 || z == 3 || z == 11) {
                    for (int y = 1; y <= 3; y++) {
                        helper.setBlock(new BlockPos(x, y, z), Blocks.STONE_BRICKS);
                    }
                }
                helper.setBlock(new BlockPos(x, 4, z), Blocks.STONE_BRICKS);
            }
        }
        helper.setBlock(new BlockPos(7, 1, 3), Blocks.OAK_DOOR.defaultBlockState());
        helper.setBlock(new BlockPos(7, 2, 3), Blocks.OAK_DOOR.defaultBlockState()
            .setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
        for (int x : new int[]{4, 10}) {
            for (int z : new int[]{4, 10}) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.BARREL);
            }
            helper.setBlock(new BlockPos(x, 2, 10), Blocks.TORCH);
        }
        helper.setBlock(new BlockPos(6, 1, 6), Blocks.COMPOSTER);
        BlockPos plaqueRel = new BlockPos(4, 2, 2);
        helper.setBlock(plaqueRel, com.hearthstead.registry.ModBlocks.PLAQUE.get()
            .defaultBlockState().setValue(com.hearthstead.block.PlaqueBlock.FACING, Direction.NORTH));
        PlaqueBlockEntity plaque = plaqueAt(helper, plaqueRel);
        plaque.insertPlan(helper.getLevel(), com.hearthstead.block.PlaqueItemData.stamped(
            new ItemStack(com.hearthstead.registry.ModItems.BUILD_PLAN.get()), workplaceType));
        helper.runAfterDelay(20, () -> {
            plaque.survey(helper.getLevel());
            Building warehouse = plaque.building(helper.getLevel());
            helper.assertTrue(warehouse != null && warehouse.valid,
                "the complete physical workplace must register before raid damage: " + plaque.state());
            SettlerEntity worker = settler(helper, original, "Ansgar", 7, 7);
            worker.setNoAi(true);
            worker.setHunger(100.0F);
            helper.assertTrue(Employment.hire(helper.getLevel(), original, warehouse, worker).ok(),
                "the worker must be hired through the real employment service");
            var stock = (net.minecraft.world.Container) helper.getLevel()
                .getBlockEntity(helper.absolutePos(new BlockPos(10, 1, 4)));
            stock.setItem(0, new ItemStack(net.minecraft.world.item.Items.STONE_BRICKS, 1));
            stock.setChanged();
            BlockPos wound = new BlockPos(11, 1, 7);
            BlockPos woundAbs = helper.absolutePos(wound);
            com.hearthstead.settlement.raid.RaidDirector.recordScar(helper.getLevel(), original.id,
                woundAbs, helper.getBlockState(wound));
            helper.setBlock(wound, Blocks.AIR);
            helper.assertTrue(Employment.retainsWorkersForRaidRepair(
                    helper.getLevel(), original, warehouse),
                "the recorded scar must belong to this assigned workplace; bounds=" + warehouse.bounds
                    + " wound=" + woundAbs + " workers=" + warehouse.workers);
            for (int i = 0; i < 4; i++) plaque.survey(helper.getLevel());
            helper.assertTrue(!warehouse.valid && warehouse.workers.contains(worker.getUUID())
                    && worker.getProfession() == profession,
                "recorded raid damage must disable the workplace without firing its worker; valid="
                    + warehouse.valid + " workers=" + warehouse.workers + " profession="
                    + worker.getProfession() + " plaque=" + plaque.state()
                    + " owner=" + original.id + " bounds=" + warehouse.bounds
                    + " wound=" + woundAbs + " block=" + helper.getBlockState(wound));
            Settlement restored = Settlement.readNbt(original.writeNbt());
            var saved = com.hearthstead.settlement.SettlementSavedData.get(helper.getLevel());
            saved.settlements.put(restored.id, restored);
            saved.setDirty();
            Building restoredworkplace = Employment.employerOf(restored, worker.getUUID());
            helper.assertTrue(restoredworkplace != null && !restoredworkplace.valid
                    && restoredworkplace.workers.contains(worker.getUUID()),
                "the suspended job must survive a settlement save/load");
            worker.setNoAi(false);
            boolean[] sawRepair = {false};
            helper.succeedWhen(() -> {
                sawRepair[0] |= worker.getActivity() == com.hearthstead.entity.SettlerActivity.WORK_CHISEL;
                helper.assertTrue(helper.getBlockState(wound).is(Blocks.STONE_BRICKS),
                    "the employed worker must autonomously restore the recorded wall; activity="
                        + worker.getActivity() + " profession=" + worker.getProfession());
                helper.assertTrue(sawRepair[0] && stock.getItem(0).isEmpty(),
                    "repair must animate and consume the one surviving workplace brick, with no Hearth subsidy");
                helper.assertTrue(!com.hearthstead.settlement.raid.RaidDirector
                        .hasScarAt(helper.getLevel(), restored.id, woundAbs),
                    "completed repair must clear the persisted scar");
                plaque.survey(helper.getLevel());
                helper.assertTrue(restoredworkplace.valid
                        && Employment.employerOf(restored, worker.getUUID()) == restoredworkplace
                        && worker.getProfession() == profession,
                    "a real successful survey must restore the same worker assignment without another hire");
                // Ordinary destruction is still ordinary destruction. A scar
                // outside this building must not keep an unrelated ruin employed.
                com.hearthstead.settlement.raid.RaidDirector.recordScar(helper.getLevel(), restored.id,
                    helper.absolutePos(new BlockPos(14, 1, 14)), Blocks.STONE_BRICKS.defaultBlockState());
                helper.setBlock(wound, Blocks.AIR);
                for (int i = 0; i < 4; i++) plaque.survey(helper.getLevel());
                helper.assertTrue(!restoredworkplace.valid
                        && Employment.employerOf(restored, worker.getUUID()) == null
                        && worker.getProfession() == Profession.NONE,
                    "unrecorded sustained destruction must still release the worker");
            });
        });
    }

}
