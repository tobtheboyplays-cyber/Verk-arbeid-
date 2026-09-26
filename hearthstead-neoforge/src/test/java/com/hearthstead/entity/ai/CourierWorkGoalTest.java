package com.hearthstead.entity.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CourierWorkGoalTest {

    @Test
    void measurableProgressResetsRoutePatience() {
        assertTrue(CourierWorkGoal.madePhysicalPathProgress(0.0401D));
        assertTrue(CourierWorkGoal.madePhysicalPathProgress(1.0D));
    }

    @Test
    void jitterAndNoMovementDoNotPretendToBeProgress() {
        assertFalse(CourierWorkGoal.madePhysicalPathProgress(0.0D));
        assertFalse(CourierWorkGoal.madePhysicalPathProgress(0.04D));
        assertFalse(CourierWorkGoal.madePhysicalPathProgress(0.0004D));
    }
}
