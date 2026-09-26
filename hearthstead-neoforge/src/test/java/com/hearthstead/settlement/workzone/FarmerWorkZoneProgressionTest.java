package com.hearthstead.settlement.workzone;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Locks the Farmer plot progression so a new hire cannot run an end-game field. */
class FarmerWorkZoneProgressionTest {

    @Test
    void sideLengthGrowsByFourBlocksPerTier() {
        assertEquals(12, WorkZoneService.farmerSideLimit(1));
        assertEquals(16, WorkZoneService.farmerSideLimit(2));
        assertEquals(20, WorkZoneService.farmerSideLimit(3));
        assertEquals(24, WorkZoneService.farmerSideLimit(4));
        assertEquals(28, WorkZoneService.farmerSideLimit(5));
    }

    @Test
    void malformedTiersClampFailClosed() {
        assertEquals(12, WorkZoneService.farmerSideLimit(Integer.MIN_VALUE));
        assertEquals(28, WorkZoneService.farmerSideLimit(Integer.MAX_VALUE));
    }
}
