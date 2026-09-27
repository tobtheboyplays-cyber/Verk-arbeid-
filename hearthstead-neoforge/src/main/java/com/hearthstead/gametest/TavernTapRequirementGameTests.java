package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.AleTapBlock;
import com.hearthstead.block.PlaqueBlock;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.block.PlaqueItemData;
import com.hearthstead.building.BuildingType;
import com.hearthstead.building.PlaqueState;
import com.hearthstead.building.Requirement;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.RoomScanner;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Real room scans and plaque requirement transitions, with no synthetic valid building. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class TavernTapRequirementGameTests {
    private static final BlockPos SEED = new BlockPos(5, 1, 5);
    private static final BlockPos BARREL = new BlockPos(3, 1, 3);
    private static final BlockPos TAP = new BlockPos(4, 1, 3);

    private static void room(GameTestHelper h) {
        for (int x = 1; x <= 9; x++) for (int z = 1; z <= 9; z++)
            for (int y = 0; y <= 4; y++) {
                boolean shell = x == 1 || x == 9 || z == 1 || z == 9 || y == 0 || y == 4;
                h.setBlock(new BlockPos(x, y, z), shell ? Blocks.STONE_BRICKS : Blocks.AIR);
            }
        h.setBlock(new BlockPos(5, 1, 1), Blocks.OAK_DOOR.defaultBlockState());
        h.setBlock(new BlockPos(5, 2, 1), Blocks.OAK_DOOR.defaultBlockState()
            .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        h.setBlock(BARREL, Blocks.BARREL);
        h.setBlock(new BlockPos(7, 1, 3), Blocks.BARREL);
        h.setBlock(new BlockPos(7, 1, 4), Blocks.BARREL);
        h.setBlock(new BlockPos(7, 1, 7), Blocks.BELL);
        for (BlockPos light : new BlockPos[]{new BlockPos(2, 1, 7),
                new BlockPos(5, 1, 7), new BlockPos(7, 1, 5)}) h.setBlock(light, Blocks.TORCH);
    }

    private static RoomScanner.Result scan(GameTestHelper h) {
        RoomScanner.Result result = RoomScanner.scan(h.getLevel(), h.absolutePos(SEED));
        h.assertTrue(result != null && result.enclosed() && !result.skyLeak()
                && result.volume() <= RoomScanner.MAX_HOME_VOLUME,
            "fixture must be one enclosed roofed room, independently of the tap requirement");
        for (Requirement requirement : BuildingType.TAVERN.requirements()) {
            if (!requirement.id().equals("ale_tap")) h.assertTrue(requirement.measure(result).met(),
                "fixture must satisfy unchanged Tavern requirement " + requirement.id()
                    + ": " + requirement.measure(result));
        }
        return result;
    }

    private static Requirement.Status tapStatus(RoomScanner.Result result) {
        return BuildingType.TAVERN.requirementById("ale_tap").measure(result);
    }

    private static void connect(GameTestHelper h) {
        h.setBlock(TAP, ModBlocks.ALE_TAP.get().defaultBlockState()
            .setValue(AleTapBlock.FACING, Direction.EAST));
    }

    @GameTest(batch = "tavern_tap_requirement", template = "empty16", timeoutTicks = 40)
    public void missingOrDisconnectedTapCannotBorrowAnUnrelatedBarrel(GameTestHelper h) {
        room(h);
        Requirement.Status absent = tapStatus(scan(h));
        h.assertTrue(absent.have() == 0 && absent.needed() == 1 && !absent.met(),
            "missing tap must expose the real zero-of-one requirement");
        h.setBlock(TAP.south(), Blocks.STONE_BRICKS);
        h.setBlock(TAP, ModBlocks.ALE_TAP.get().defaultBlockState()
            .setValue(AleTapBlock.FACING, Direction.NORTH));
        h.assertTrue(tapStatus(scan(h)).have() == 0,
            "a supported tap facing away from an existing nearby barrel remains disconnected");
        h.succeed();
    }

    @GameTest(batch = "tavern_tap_requirement", template = "empty16", timeoutTicks = 40)
    public void emptyConnectedBarrelMeetsRequirementUntilItsTapOrBarrelIsRemoved(GameTestHelper h) {
        room(h);
        connect(h);
        h.assertTrue(((net.minecraft.world.Container) h.getBlockEntity(BARREL)).isEmpty(),
            "structural test barrel must contain no ALE or wheat");
        h.assertTrue(tapStatus(scan(h)).have() == 1,
            "one empty but physically connected pair satisfies the mandatory structure");
        h.setBlock(TAP, Blocks.AIR);
        h.assertTrue(tapStatus(scan(h)).have() == 0,
            "removing the tap must remove the requirement count on the next scan");
        connect(h);
        h.setBlock(BARREL, Blocks.STONE_BRICKS);
        h.assertTrue(tapStatus(scan(h)).have() == 0,
            "a wall support is not a vanilla backing barrel, even with a second barrel elsewhere");
        h.succeed();
    }

    @GameTest(batch = "tavern_tap_requirement", template = "empty16", timeoutTicks = 40)
    public void backingBarrelOutsideTheScannedRoomCannotQualify(GameTestHelper h) {
        room(h);
        BlockPos wallTap = new BlockPos(1, 1, 3);
        BlockPos outsideBarrel = new BlockPos(0, 1, 3);
        h.setBlock(outsideBarrel, Blocks.BARREL);
        h.setBlock(wallTap, ModBlocks.ALE_TAP.get().defaultBlockState()
            .setValue(AleTapBlock.FACING, Direction.EAST));
        RoomScanner.Result result = scan(h);
        h.assertTrue(result.bounds().isInside(h.absolutePos(wallTap))
                && !result.bounds().isInside(h.absolutePos(outsideBarrel))
                && tapStatus(result).have() == 0,
            "a tap in this wall cannot satisfy the room using its backing barrel outside the survey");
        h.succeed();
    }

    @GameTest(batch = "tavern_tap_requirement", template = "empty16", timeoutTicks = 100)
    public void actualTavernPlaqueRequiresPairAndInvalidatesAfterExistingGrace(GameTestHelper h) {
        room(h);
        scan(h);
        Settlement settlement = new Settlement(UUID.randomUUID(), "Tap requirement village", h.absolutePos(SEED));
        settlement.radius = 12;
        SettlementSavedData data = SettlementSavedData.get(h.getLevel());
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        BlockPos plaquePos = new BlockPos(2, 2, 0);
        h.setBlock(plaquePos, ModBlocks.PLAQUE.get().defaultBlockState()
            .setValue(PlaqueBlock.FACING, Direction.NORTH));
        PlaqueBlockEntity plaque = (PlaqueBlockEntity) h.getBlockEntity(plaquePos);
        h.assertTrue(plaque != null, "real wall-mounted plaque must exist");
        plaque.insertPlan(h.getLevel(), PlaqueItemData.stamped(
            new ItemStack(ModItems.BUILD_PLAN.get()), BuildingType.TAVERN));
        plaque.survey(h.getLevel());
        h.assertTrue(plaque.state() == PlaqueState.LINKED_INCOMPLETE
                && plaque.lastSurvey().stream().anyMatch(status -> status.requirement().id().equals("ale_tap")
                    && status.have() == 0 && !status.met()),
            "ordinary survey must identify missing connected tap and refuse valid Tavern registration");
        connect(h);
        plaque.survey(h.getLevel());
        h.assertTrue(plaque.state() == PlaqueState.LINKED_VALID,
            "adding an empty connected pair must validate the same real Tavern plaque");
        h.setBlock(TAP, Blocks.AIR);
        // Existing grace is three failed surveys. This test preserves it.
        for (int attempt = 0; attempt < 4; attempt++) plaque.survey(h.getLevel());
        h.assertTrue(plaque.state() == PlaqueState.LINKED_INCOMPLETE
                && plaque.lastSurvey().stream().anyMatch(status -> status.requirement().id().equals("ale_tap")
                    && status.have() == 0 && !status.met()),
            "removing mandatory tap must invalidate via existing survey grace, without editing any player structure");
        data.settlements.remove(settlement.id);
        data.setDirty();
        h.succeed();
    }
}
