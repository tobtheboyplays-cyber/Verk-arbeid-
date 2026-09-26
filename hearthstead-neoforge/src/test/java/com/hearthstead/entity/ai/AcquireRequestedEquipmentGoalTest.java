package com.hearthstead.entity.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcquireRequestedEquipmentGoalTest {

    @Test
    void measurableBodyProgressPreservesAHealthyRoute() {
        assertTrue(AcquireRequestedEquipmentGoal
            .madePhysicalPathProgress(0.0401D));
        assertTrue(AcquireRequestedEquipmentGoal
            .madePhysicalPathProgress(1.0D));
    }

    @Test
    void jitterDoesNotPretendToBeRouteProgress() {
        assertFalse(AcquireRequestedEquipmentGoal
            .madePhysicalPathProgress(0.0D));
        assertFalse(AcquireRequestedEquipmentGoal
            .madePhysicalPathProgress(0.04D));
        assertFalse(AcquireRequestedEquipmentGoal
            .madePhysicalPathProgress(0.0004D));
    }

    @Test
    void eitherStallOrAbsoluteBudgetReleasesMoveOwnership() {
        assertFalse(AcquireRequestedEquipmentGoal
            .routeBudgetExhausted(79, 7));
        assertTrue(AcquireRequestedEquipmentGoal
            .routeBudgetExhausted(80, 0));
        assertTrue(AcquireRequestedEquipmentGoal
            .routeBudgetExhausted(1, 8));
    }

    @Test
    void exactFailedTargetYieldsOnlyUntilItsOwnRetryDeadline() {
        assertFalse(AcquireRequestedEquipmentGoal.targetAvailable(99L, 100L));
        assertTrue(AcquireRequestedEquipmentGoal.targetAvailable(100L, 100L));
        assertTrue(AcquireRequestedEquipmentGoal.targetAvailable(1L, null));
    }
}
