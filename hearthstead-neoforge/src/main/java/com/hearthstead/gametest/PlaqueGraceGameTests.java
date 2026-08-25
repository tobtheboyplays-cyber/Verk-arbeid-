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
    @GameTest(template = "empty16", timeoutTicks = 400)
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
    @GameTest(template = "empty16", timeoutTicks = 400)
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
}
