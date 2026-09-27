package com.hearthstead.settlement.warehouse;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WarehouseLevelsTest {

    @Test
    void capacityTableDoublesFromSixteenToTwoFiftySix() {
        assertEquals(16, WarehouseLevels.capacity(1));
        assertEquals(32, WarehouseLevels.capacity(2));
        assertEquals(64, WarehouseLevels.capacity(3));
        assertEquals(128, WarehouseLevels.capacity(4));
        assertEquals(256, WarehouseLevels.capacity(5));
        assertEquals(16, WarehouseLevels.capacity(0), "clamped low");
        assertEquals(256, WarehouseLevels.capacity(99), "clamped high");
        assertEquals(WarehouseLevels.ABSOLUTE_MAX, WarehouseLevels.capacity(5));
        assertEquals(2, WarehouseLevels.nextLevel(1));
        assertEquals(-1, WarehouseLevels.nextLevel(5));
    }

    @Test
    void levelCoveringPicksSmallestSufficientLevel() {
        assertEquals(1, WarehouseLevels.levelCovering(0));
        assertEquals(1, WarehouseLevels.levelCovering(16));
        assertEquals(2, WarehouseLevels.levelCovering(17));
        assertEquals(3, WarehouseLevels.levelCovering(64));
        assertEquals(4, WarehouseLevels.levelCovering(65));
        assertEquals(5, WarehouseLevels.levelCovering(10_000));
    }

    @Test
    void grandfatherCoversWhatExistsButNeverAboveL3() {
        assertEquals(1, WarehouseLevels.grandfatherLevel(0));
        assertEquals(1, WarehouseLevels.grandfatherLevel(10));
        assertEquals(2, WarehouseLevels.grandfatherLevel(20));
        assertEquals(3, WarehouseLevels.grandfatherLevel(40));
        assertEquals(3, WarehouseLevels.grandfatherLevel(64));
        // The old cap was 64: a bigger old warehouse keeps exactly that.
        assertEquals(3, WarehouseLevels.grandfatherLevel(65));
        assertEquals(3, WarehouseLevels.grandfatherLevel(500));
        assertEquals(64, WarehouseLevels.capacity(WarehouseLevels.grandfatherLevel(500)));
    }

    @Test
    void effectiveLevelIsBuiltCappedByTreeButNeverBelowFloor() {
        assertEquals(2, WarehouseLevels.techMaxLevel(0));
        assertEquals(3, WarehouseLevels.techMaxLevel(1));
        assertEquals(5, WarehouseLevels.techMaxLevel(3));
        assertEquals(5, WarehouseLevels.techMaxLevel(9));
        // Built L5, tree only knows L2.
        assertEquals(2, WarehouseLevels.effectiveLevel(5, 2, 1));
        // Built L1, tree allows L5: the room decides.
        assertEquals(1, WarehouseLevels.effectiveLevel(1, 5, 1));
        // Grandfathered L3 survives a bare room and a bare tree.
        assertEquals(3, WarehouseLevels.effectiveLevel(1, 2, 3));
        // Built beyond the floor with the tree: grows past the floor.
        assertEquals(4, WarehouseLevels.effectiveLevel(4, 4, 3));
    }

    @Test
    void packMatchesBlockPosLayout() {
        int[][] samples = {{0, 0, 0}, {-1, -64, -1}, {12345, 319, -54321},
            {-30_000_000, 100, 29_999_999}};
        for (int[] p : samples) {
            long packed = WarehouseLevels.pack(p[0], p[1], p[2]);
            assertEquals(new BlockPos(p[0], p[1], p[2]).asLong(), packed);
            assertEquals(p[0], WarehouseLevels.unpackX(packed));
            assertEquals(p[1], WarehouseLevels.unpackY(packed));
            assertEquals(p[2], WarehouseLevels.unpackZ(packed));
        }
    }

    @Test
    void selectsNearestToPlaqueAndReturnsScanOrder() {
        long plaque = WarehouseLevels.pack(0, 0, 0);
        List<Long> candidates = new ArrayList<>();
        for (int x = 1; x <= 10; x++) {
            candidates.add(WarehouseLevels.pack(x, 0, 0));
        }
        long[] managed = WarehouseLevels.selectManaged(candidates, plaque, 3,
            Set.of(), Set.of());
        assertArrayEquals(new long[]{WarehouseLevels.pack(1, 0, 0),
            WarehouseLevels.pack(2, 0, 0), WarehouseLevels.pack(3, 0, 0)}, managed);
    }

    @Test
    void distanceTiesBreakInOldScanOrderDeterministically() {
        long plaque = WarehouseLevels.pack(0, 0, 0);
        // Four positions all at distance 1 from the plaque.
        List<Long> candidates = List.of(WarehouseLevels.pack(0, 0, 1),
            WarehouseLevels.pack(1, 0, 0), WarehouseLevels.pack(0, 0, -1),
            WarehouseLevels.pack(-1, 0, 0));
        long[] first = WarehouseLevels.selectManaged(candidates, plaque, 2,
            Set.of(), Set.of());
        List<Long> reversed = new ArrayList<>(candidates);
        java.util.Collections.reverse(reversed);
        long[] second = WarehouseLevels.selectManaged(reversed, plaque, 2,
            Set.of(), Set.of());
        assertArrayEquals(first, second, "input order must not matter");
        // y equal, so lowest x first: (-1,0,0), then x=0 lowest z: (0,0,-1).
        assertArrayEquals(new long[]{WarehouseLevels.pack(-1, 0, 0),
            WarehouseLevels.pack(0, 0, -1)}, first);
    }

    @Test
    void priorityMarksWinAndExcludedNeverManaged() {
        long plaque = WarehouseLevels.pack(0, 0, 0);
        List<Long> candidates = new ArrayList<>();
        for (int x = 1; x <= 6; x++) {
            candidates.add(WarehouseLevels.pack(x, 0, 0));
        }
        long far = WarehouseLevels.pack(6, 0, 0);
        long near = WarehouseLevels.pack(1, 0, 0);
        long[] managed = WarehouseLevels.selectManaged(candidates, plaque, 2,
            Set.of(far), Set.of(near));
        assertArrayEquals(new long[]{WarehouseLevels.pack(2, 0, 0), far}, managed);
    }

    @Test
    void neverMoreThanCapacityAndNeverFailsOnOverflow() {
        long plaque = WarehouseLevels.pack(0, 64, 0);
        List<Long> candidates = new ArrayList<>();
        for (int x = 0; x < 30; x++) {
            for (int z = 0; z < 30; z++) {
                candidates.add(WarehouseLevels.pack(x, 64, z));
            }
        }
        for (int level = 1; level <= 5; level++) {
            long[] managed = WarehouseLevels.selectManaged(candidates, plaque,
                WarehouseLevels.capacity(level), Set.of(), Set.of());
            assertEquals(WarehouseLevels.capacity(level), managed.length);
            for (int i = 1; i < managed.length; i++) {
                assertTrue(WarehouseLevels.compareScanOrder(managed[i - 1], managed[i]) < 0,
                    "scan order");
            }
        }
        assertEquals(0, WarehouseLevels.selectManaged(List.of(), plaque, 16,
            Set.of(), Set.of()).length);
    }
}
