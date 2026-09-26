package com.hearthstead.block;

import com.hearthstead.Hearthstead;
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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Exact terminal evidence contracts for physical Plaque building links. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class PlaqueAuthorityTelemetryGameTests {

    @GameTest(template = "empty16", timeoutTicks = 120,
        batch = "authority_plaque_link_terminal_only")
    public void newAndMateriallyRevalidatedLinksEmitButDuplicateSurveyIsSilent(
            GameTestHelper helper) {
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(), "Linkproof",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 20;
        data.settlements.put(settlement.id, settlement);

        BlockPos hutOrigin = new BlockPos(6, 0, 6);
        buildHut(helper, hutOrigin);
        BlockPos plaqueRelative = hutOrigin.offset(1, 2, -1);
        helper.setBlock(plaqueRelative, ModBlocks.PLAQUE.get().defaultBlockState()
            .setValue(PlaqueBlock.FACING, Direction.NORTH));
        if (!(helper.getLevel().getBlockEntity(helper.absolutePos(plaqueRelative))
            instanceof PlaqueBlockEntity plaque)) {
            helper.fail("fixture: Plaque block entity missing");
            return;
        }

        ItemStack plan = PlaqueItemData.stamped(
            new ItemStack(ModItems.BUILD_PLAN.get()), BuildingType.HOUSE);
        helper.assertTrue(plaque.insertPlan(helper.getLevel(), plan),
            "the physical plan insertion must commit the first survey");
        Building building = plaque.building(helper.getLevel());
        helper.assertTrue(building != null && building.valid
                && settlement.buildings.size() == 1,
            "the first survey must persist exactly one valid building");
        helper.assertTrue(plaque.buildingLinkTelemetryCountForTest() == 1,
            "the terminal new-link commit must emit exactly one evidence row");

        int stableRevision = plaque.revision();
        int stableEvidence = plaque.buildingLinkTelemetryCountForTest();
        plaque.survey(helper.getLevel());
        helper.assertTrue(plaque.revision() == stableRevision
                && plaque.buildingLinkTelemetryCountForTest() == stableEvidence,
            "an identical duplicate re-survey must emit no evidence row");

        int lightCountBefore = building.lightSources;
        BlockPos extraTorch = hutOrigin.offset(3, 1, 3);
        helper.setBlock(extraTorch, Blocks.TORCH);
        plaque.survey(helper.getLevel());
        helper.assertTrue(building.lightSources == lightCountBefore + 1
                && plaque.revision() == stableRevision + 1,
            "the changed physical room must commit one revalidation revision");
        helper.assertTrue(plaque.buildingLinkTelemetryCountForTest()
                == stableEvidence + 1,
            "the terminal material revalidation must emit exactly one row");

        int revalidatedRevision = plaque.revision();
        int revalidatedEvidence = plaque.buildingLinkTelemetryCountForTest();
        plaque.survey(helper.getLevel());
        helper.assertTrue(plaque.revision() == revalidatedRevision
                && plaque.buildingLinkTelemetryCountForTest()
                    == revalidatedEvidence,
            "the duplicate survey after revalidation must remain silent");

        CompoundTag graceState = plaque.saveWithoutMetadata(
            helper.getLevel().registryAccess());
        graceState.putString("State", PlaqueState.LINKED_INCOMPLETE.id());
        plaque.loadAdditional(graceState, helper.getLevel().registryAccess());
        int graceRevision = plaque.revision();
        int graceEvidence = plaque.buildingLinkTelemetryCountForTest();
        plaque.survey(helper.getLevel());
        helper.assertTrue(plaque.state() == PlaqueState.LINKED_VALID
                && plaque.revision() == graceRevision + 1
                && plaque.buildingLinkTelemetryCountForTest()
                    == graceEvidence + 1,
            "a persisted grace-held Plaque returning to valid must emit one "
                + "revalidation row even when building geometry is unchanged");

        int beforeCorruptSurvey = plaque.buildingLinkTelemetryCountForTest();
        Building duplicate = new Building(building.id, building.type,
            building.plaquePos, building.anchor, building.bounds);
        duplicate.valid = true;
        settlement.buildings.add(duplicate);
        plaque.survey(helper.getLevel());
        helper.assertTrue(plaque.state() == PlaqueState.ORPHANED
                && settlement.buildings.size() == 2
                && plaque.buildingLinkTelemetryCountForTest()
                    == beforeCorruptSurvey,
            "duplicate building authority must fail closed without minting a "
                + "replacement link or terminal evidence");
        helper.succeed();
    }

    private static void buildHut(GameTestHelper helper, BlockPos origin) {
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) {
                boolean wall = x == 0 || z == 0 || x == 4 || z == 4;
                for (int y = 1; y <= 3; y++) {
                    if (wall) {
                        helper.setBlock(origin.offset(x, y, z),
                            Blocks.STONE_BRICKS);
                    }
                }
                helper.setBlock(origin.offset(x, 4, z), Blocks.STONE_BRICKS);
                helper.setBlock(origin.offset(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
        helper.setBlock(origin.offset(2, 1, 0),
            Blocks.OAK_DOOR.defaultBlockState());
        helper.setBlock(origin.offset(2, 2, 0),
            Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        helper.setBlock(origin.offset(2, 1, 2),
            Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, Direction.NORTH)
                .setValue(BedBlock.PART, BedPart.FOOT));
        helper.setBlock(origin.offset(2, 1, 3),
            Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, Direction.NORTH)
                .setValue(BedBlock.PART, BedPart.HEAD));
        helper.setBlock(origin.offset(1, 2, 1), Blocks.TORCH);
    }

    public PlaqueAuthorityTelemetryGameTests() {
    }
}
