package com.hearthstead.entity.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AnimationRuntimePhaseContractTest {

    @Test
    void courierTransactionsShareTheAuthoredContactFrames() {
        assertEquals(12, CourierWorkGoal.LIFT_GRIP_TICK,
            "COURIER_LIFT grip is authored at 0.60 s / tick 12");
        assertEquals(28, CourierWorkGoal.LIFT_DURATION_TICKS,
            "COURIER_LIFT owns the body for its full 1.40 s");
        assertEquals(12, CourierWorkGoal.SET_DOWN_TICK,
            "COURIER_SET_DOWN floor contact is authored at 0.60 s / tick 12");
        assertEquals(24, CourierWorkGoal.SET_DOWN_DURATION_TICKS,
            "COURIER_SET_DOWN owns the body for its full 1.20 s");
        assertTrue(CourierWorkGoal.LIFT_GRIP_TICK
                < CourierWorkGoal.LIFT_DURATION_TICKS,
            "lift must retain recovery frames after the physical transaction");
        assertTrue(CourierWorkGoal.SET_DOWN_TICK
                < CourierWorkGoal.SET_DOWN_DURATION_TICKS,
            "set-down must retain recovery frames before sorting begins");
    }

    @Test
    void thirdAxeContactIsTheOnlyLogCommitFrame() {
        assertEquals(20, LumbererWorkGoal.CHOP_CYCLE_TICKS);
        assertEquals(11, LumbererWorkGoal.CHOP_CONTACT_TICK);
        assertEquals(3, LumbererWorkGoal.CHOP_CONTACTS_PER_LOG);
        assertEquals(51, LumbererWorkGoal.TICKS_PER_LOG,
            "contacts are ticks 11, 31 and 51; the last one fells the log");
        assertEquals(LumbererWorkGoal.CHOP_CONTACT_TICK,
            LumbererWorkGoal.TICKS_PER_LOG % LumbererWorkGoal.CHOP_CYCLE_TICKS,
            "world mutation must land on the same phase as axe/wood contact");
    }
}
