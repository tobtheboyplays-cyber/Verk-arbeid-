package com.hearthstead.entity.path;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StairHeadroomTest {

    /** A world of air with some solid cells. */
    private static final class Grid implements StairHeadroom.Clearance {
        private final Set<StairHeadroom.Cell> solid = new HashSet<>();

        Grid solid(int x, int y, int z) {
            solid.add(new StairHeadroom.Cell(x, y, z));
            return this;
        }

        @Override
        public boolean clear(int x, int y, int z) {
            return !solid.contains(new StairHeadroom.Cell(x, y, z));
        }
    }

    // The blueprint staircase: stairs facing east at (3,1)->(4,2)->(5,3)->(6,4),
    // upper floor at y=4. Climbing onto (4,2) means standing above (3,1).

    @Test
    void threeClearAboveTheApproachFits() {
        Grid grid = new Grid().solid(3, 5, 2);
        assertNull(StairHeadroom.blocker(4, 2, 2, 1, 0, grid));
    }

    @Test
    void floorLeftOverThePreviousTreadBlocksTheStep() {
        Grid grid = new Grid().solid(3, 4, 2);
        assertEquals(new StairHeadroom.Cell(3, 4, 2), StairHeadroom.blocker(4, 2, 2, 1, 0, grid));
        assertEquals(new StairHeadroom.Cell(3, 1, 2), StairHeadroom.stepBelowApproach(4, 2, 2, 1, 0));
    }

    @Test
    void lanternOverTheFirstApproachBlocksTheFirstStep() {
        Grid grid = new Grid().solid(2, 3, 2);
        assertEquals(new StairHeadroom.Cell(2, 3, 2), StairHeadroom.blocker(3, 1, 2, 1, 0, grid));
    }

    @Test
    void theStairNeedsTwoClearAboveItsOwnTread() {
        assertEquals(new StairHeadroom.Cell(4, 4, 2),
            StairHeadroom.blocker(4, 2, 2, 1, 0, new Grid().solid(4, 4, 2)));
        assertNull(StairHeadroom.blocker(4, 2, 2, 1, 0, new Grid().solid(4, 5, 2)));
    }

    @Test
    void facingDecidesTheApproachSide() {
        // A stair facing north is climbed from the south.
        Grid grid = new Grid().solid(0, 3, 1);
        assertEquals(new StairHeadroom.Cell(0, 3, 1), StairHeadroom.blocker(0, 1, 0, 0, -1, grid));
        assertNull(StairHeadroom.blocker(0, 1, 0, 0, 1, grid));
    }

    @Test
    void ceilingThreeAboveTheApproachFloorIsFine() {
        // Ground floor, stair at y=1: the approach needs y=1..3 clear; a ceiling at y=4 is fine.
        assertNull(StairHeadroom.blocker(3, 1, 2, 1, 0, new Grid().solid(2, 4, 2)));
    }
}
