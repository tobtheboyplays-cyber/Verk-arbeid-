package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.PlaqueBlock;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.block.PlaqueItemData;
import com.hearthstead.building.BuildingType;
import com.hearthstead.building.PlaqueState;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * SCENARIO lane (26 Sep): the three battle-role halls have NO blueprint, so
 * a player builds them by hand. Each is built here from survival blocks to
 * exactly its checklist and must survey to a valid level-1 building through
 * a real plaque (batch {@code scenario_hall_rooms}). Without these a
 * Spearman, Longswordsman or Rune Mage could never be hired.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class ScenarioHallRoomGameTests {
    private static final BlockPos O = new BlockPos(2, 0, 2);
    private static final int SIZE = 8;

    private static Settlement room(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            for (int y = 1; y <= 6; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        Settlement s = new Settlement(UUID.randomUUID(), "Hallholm", helper.absolutePos(new BlockPos(8, 1, 13)));
        s.radius = 12;
        SettlementSavedData.get(helper.getLevel()).settlements.put(s.id, s);
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                boolean wall = x == 0 || z == 0 || x == SIZE - 1 || z == SIZE - 1;
                for (int y = 1; y <= 3; y++) if (wall) helper.setBlock(O.offset(x, y, z), Blocks.STONE_BRICKS);
                helper.setBlock(O.offset(x, 4, z), Blocks.STONE_BRICKS);
                helper.setBlock(O.offset(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
        int mid = SIZE / 2;
        helper.setBlock(O.offset(mid, 1, 0), Blocks.OAK_DOOR.defaultBlockState());
        helper.setBlock(O.offset(mid, 2, 0), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        return s;
    }

    private static void bed(GameTestHelper helper, int x, int z) {
        helper.setBlock(O.offset(x, 1, z), Blocks.RED_BED.defaultBlockState()
            .setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.FOOT));
        helper.setBlock(O.offset(x, 1, z - 1), Blocks.RED_BED.defaultBlockState()
            .setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.HEAD));
    }

    private static void place(GameTestHelper helper, int x, int z, net.minecraft.world.level.block.Block block) {
        helper.setBlock(O.offset(x, 1, z), block);
    }

    private static void surveyValid(GameTestHelper helper, Settlement s, BuildingType type) {
        BlockPos plaqueRel = O.offset(SIZE / 2 + 1, 2, -1);
        helper.setBlock(plaqueRel, ModBlocks.PLAQUE.get().defaultBlockState().setValue(PlaqueBlock.FACING, Direction.NORTH));
        PlaqueBlockEntity plaque = (PlaqueBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(plaqueRel));
        helper.assertTrue(plaque != null, "setup: a real plaque");
        helper.assertTrue(plaque.insertPlan(helper.getLevel(),
            PlaqueItemData.stamped(new ItemStack(ModItems.BUILD_PLAN.get()), type)), "the plan fits");
        helper.succeedWhen(() -> {
            plaque.survey(helper.getLevel());
            Building b = plaque.building(helper.getLevel());
            helper.assertTrue(plaque.state() == PlaqueState.LINKED_VALID && b != null && b.valid && b.type == type
                    && b.level >= 1,
                "a hand-built " + type.id() + " meeting its checklist is a valid level-1 building [state "
                    + plaque.state() + ", reason " + (plaque.lastScanReason() == null ? "-" : plaque.lastScanReason().getString()) + ", survey "
                    + plaque.lastSurvey() + "]");
            SettlementSavedData.get(helper.getLevel()).settlements.remove(s.id);
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 200, batch = "scenario_hall_rooms")
    public void aHandBuiltPikeYardIsValid(GameTestHelper helper) {
        Settlement s = room(helper);
        bed(helper, 1, 6);
        bed(helper, 3, 6);
        place(helper, 5, 6, Blocks.HAY_BLOCK);
        place(helper, 6, 6, Blocks.HAY_BLOCK);
        place(helper, 6, 1, Blocks.CHEST);
        place(helper, 1, 1, Blocks.LANTERN);
        place(helper, 2, 1, Blocks.LANTERN);
        surveyValid(helper, s, BuildingType.PIKE_YARD);
    }

    @GameTest(template = "empty16", timeoutTicks = 200, batch = "scenario_hall_rooms")
    public void aHandBuiltSwordHallIsValid(GameTestHelper helper) {
        Settlement s = room(helper);
        bed(helper, 1, 6);
        bed(helper, 3, 6);
        place(helper, 5, 6, Blocks.ANVIL);
        place(helper, 6, 6, Blocks.GRINDSTONE);
        place(helper, 6, 1, Blocks.CHEST);
        place(helper, 1, 1, Blocks.LANTERN);
        place(helper, 2, 1, Blocks.LANTERN);
        surveyValid(helper, s, BuildingType.SWORD_HALL);
    }

    @GameTest(template = "empty16", timeoutTicks = 200, batch = "scenario_hall_rooms")
    public void aHandBuiltRuneHallIsValid(GameTestHelper helper) {
        Settlement s = room(helper);
        place(helper, 3, 4, Blocks.ENCHANTING_TABLE);
        for (int x = 1; x <= 4; x++) place(helper, x, 6, Blocks.BOOKSHELF);
        place(helper, 6, 3, Blocks.AMETHYST_BLOCK);
        place(helper, 6, 4, Blocks.AMETHYST_BLOCK);
        place(helper, 6, 5, Blocks.AMETHYST_BLOCK);
        place(helper, 6, 6, Blocks.AMETHYST_BLOCK);
        place(helper, 1, 1, Blocks.LANTERN);
        place(helper, 2, 1, Blocks.LANTERN);
        place(helper, 6, 1, Blocks.LANTERN);
        surveyValid(helper, s, BuildingType.RUNE_HALL);
    }
}
