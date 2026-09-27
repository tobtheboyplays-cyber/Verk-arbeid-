package com.hearthstead.settlement;

import com.hearthstead.entity.path.StairHeadroom;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoomScannerStairCoverTest {
    private static final BlockPos APPROACH = new BlockPos(2, 1, 2);

    private static StairHeadroom.Cell blocker(BlockPos covered, VoxelShape shape) {
        return StairHeadroom.blocker(3, 1, 2, 1, 0, (x, y, z) -> {
            BlockPos cell = new BlockPos(x, y, z);
            return RoomScanner.clearForBody(cell.equals(covered) ? shape : Shapes.empty(), cell, APPROACH);
        });
    }

    @Test void bottomSlabAboveApproachWarns() {
        assertEquals(new StairHeadroom.Cell(2, 3, 2),
            blocker(APPROACH.above(2), Shapes.box(0, 0, 0, 1, 0.5, 1)));
    }

    @Test void floorCarpetIsStillAllowed() {
        assertNull(blocker(APPROACH, Shapes.box(0, 0, 0, 1, 1.0 / 16, 1)));
    }

    @Test void closedBottomTrapdoorAboveTreadWarns() {
        assertEquals(new StairHeadroom.Cell(3, 2, 2),
            blocker(new BlockPos(3, 2, 2), Shapes.box(0, 0, 0, 1, 3.0 / 16, 1)));
    }

    @Test void openEdgePanelRemainsClear() {
        assertNull(blocker(APPROACH.above(2), Shapes.box(0, 0, 0, 3.0 / 16, 1, 1)));
    }
}
