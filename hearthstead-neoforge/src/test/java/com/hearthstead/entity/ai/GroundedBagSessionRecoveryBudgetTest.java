package com.hearthstead.entity.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundedBagSessionRecoveryBudgetTest {

    @Test
    void unreachableFieldDropIsReleasedOnlyAfterTheBoundedApproachBudget() {
        for (int attempt = 1; attempt <= GroundedBagSession.MAX_ITEM_APPROACH_ATTEMPTS; attempt++) {
            assertFalse(GroundedBagSession.itemApproachBudgetExhausted(attempt),
                "attempt " + attempt + " must still path toward the drop");
        }
        assertTrue(GroundedBagSession.itemApproachBudgetExhausted(
                GroundedBagSession.MAX_ITEM_APPROACH_ATTEMPTS + 1),
            "an exhausted budget releases the drop instead of looping BLOCKED forever");
    }
}
