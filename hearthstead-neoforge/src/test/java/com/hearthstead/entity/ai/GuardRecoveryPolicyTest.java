package com.hearthstead.entity.ai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuardRecoveryPolicyTest {
    @Test void recoveryHasSeparateEntryAndReturnThresholds() {
        assertTrue(GuardRecoveryPolicy.shouldRecover(6, 20, false));
        assertFalse(GuardRecoveryPolicy.shouldRecover(6.1F, 20, false));
        assertTrue(GuardRecoveryPolicy.shouldRecover(12.9F, 20, true));
        assertFalse(GuardRecoveryPolicy.shouldRecover(13, 20, true));
        assertFalse(GuardRecoveryPolicy.shouldRecover(0, 20, true));
    }

    @Test void onlyNutritiousConsumedMealsCanFundBoundedHealth() {
        assertEquals(0, GuardRecoveryPolicy.mealHeal(5, 20, 0));
        assertEquals(1, GuardRecoveryPolicy.mealHeal(5, 20, 1));
        assertEquals(4, GuardRecoveryPolicy.mealHeal(5, 20, 20));
        assertEquals(1, GuardRecoveryPolicy.mealHeal(12, 20, 5));
        assertEquals(0, GuardRecoveryPolicy.mealHeal(13, 20, 5));
        assertEquals(0, GuardRecoveryPolicy.mealHeal(Float.NaN, 20, 5));
        assertEquals(0, GuardRecoveryPolicy.mealHeal(0, 20, 5));
    }
}
