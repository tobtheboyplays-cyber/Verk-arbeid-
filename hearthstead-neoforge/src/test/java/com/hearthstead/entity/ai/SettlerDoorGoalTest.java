package com.hearthstead.entity.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlerDoorGoalTest {

    @Test
    void centreCrossingAloneDoesNotCloseDoorOnSettlersBack() {
        assertFalse(SettlerDoorGoal.safelyClearedDoor(true, -0.01D, 0.0D));
        assertFalse(SettlerDoorGoal.safelyClearedDoor(true, -0.99D, 0.0D));
    }

    @Test
    void doorMayCloseOnlyAfterCrossingAndClearingAFullBlock() {
        assertTrue(SettlerDoorGoal.safelyClearedDoor(true, -1.01D, 0.0D));
        assertFalse(SettlerDoorGoal.safelyClearedDoor(false, -2.0D, 0.0D));
    }
}
