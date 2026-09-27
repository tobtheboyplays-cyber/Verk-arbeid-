package com.hearthstead.entity.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LumbererRecoveryBudgetTest {

    @Test
    void recoveryWakesBeforeOwnedDropLeaseCanExpire() {
        assertTrue(LumbererWorkGoal.recoveryRetryTicksForQa()
                <= GroundCollectionSession.OWNERSHIP_LEASE_TICKS
                    - 2 * GroundCollectionSession.OWNERSHIP_REFRESH_TICKS,
            "retry must leave two heartbeat windows on the finite item lease");
        assertTrue(LumbererWorkGoal.recoveryRetryTicksForQa() > 0,
            "recovery must remain a real bounded backoff, not a tick storm");
    }

    @Test
    void approachChangesCannotResetTheAbsoluteItemRouteBudget() {
        assertFalse(LumbererWorkGoal.itemRouteBudgetExhausted(23));
        assertTrue(LumbererWorkGoal.itemRouteBudgetExhausted(24));
        assertTrue(LumbererWorkGoal.itemRouteBudgetExhausted(25));
    }

    @Test
    void campDetourCountsRealMovementEvenWhenItHeadsAwayFromTheChest() {
        assertTrue(LumbererWorkGoal.campRouteMadeProgress(-0.30, 0.0, 0.0),
            "a healthy wall detour must reset the stuck budget regardless of direction");
        assertTrue(LumbererWorkGoal.campRouteMadeProgress(0.0, 0.0, 0.30),
            "movement around either side of a workplace must count");
        assertFalse(LumbererWorkGoal.campRouteMadeProgress(0.10, 0.0, 0.10),
            "tiny path jitter must not hide a genuinely stalled route");
        assertFalse(LumbererWorkGoal.campRouteMadeProgress(0.0, 0.0, 0.0),
            "a stationary worker must still spend the bounded recovery budget");
    }
}
