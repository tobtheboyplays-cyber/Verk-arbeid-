package com.hearthstead.settlement.workzone;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Locks the Farmer plot progression so a new hire cannot run an end-game field. */
class FarmerWorkZoneProgressionTest {

    @Test
    void sideLengthGrowsByFourBlocksPerTier() {
        assertEquals(5, WorkZoneService.farmerSideLimit(1));
        assertEquals(9, WorkZoneService.farmerSideLimit(2));
        assertEquals(13, WorkZoneService.farmerSideLimit(3));
        assertEquals(17, WorkZoneService.farmerSideLimit(4));
        assertEquals(25, WorkZoneService.farmerSideLimit(5));
    }

    @Test
    void malformedTiersClampFailClosed() {
        assertEquals(5, WorkZoneService.farmerSideLimit(Integer.MIN_VALUE));
        assertEquals(25, WorkZoneService.farmerSideLimit(Integer.MAX_VALUE));
    }
}
