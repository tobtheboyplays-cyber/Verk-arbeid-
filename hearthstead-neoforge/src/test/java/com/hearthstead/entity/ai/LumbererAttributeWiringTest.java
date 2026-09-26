package com.hearthstead.entity.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LumbererAttributeWiringTest {

    @Test
    void strengthBandsCommitOnAuthoredContactFrames() {
        assertEquals(71, LumbererWorkGoal.chopTicksForStrength(0));
        assertEquals(71, LumbererWorkGoal.chopTicksForStrength(24));
        assertEquals(51, LumbererWorkGoal.chopTicksForStrength(25));
        assertEquals(51, LumbererWorkGoal.chopTicksForStrength(69));
        assertEquals(31, LumbererWorkGoal.chopTicksForStrength(70));
        assertEquals(31, LumbererWorkGoal.chopTicksForStrength(99));
        for (int strength = 0; strength <= 99; strength++) {
            int ticks = LumbererWorkGoal.chopTicksForStrength(strength);
            assertEquals(LumbererWorkGoal.CHOP_CONTACT_TICK,
                ticks % LumbererWorkGoal.CHOP_CYCLE_TICKS);
        }
    }

    @Test
    void staminaPauseSlowsWithoutEverBecomingAStop() {
        assertEquals(0, LumbererWorkGoal.fatiguePauseTicks(70.0D, 0));
        assertEquals(0, LumbererWorkGoal.fatiguePauseTicks(100.0D, 99));
        assertEquals(11, LumbererWorkGoal.fatiguePauseTicks(0.0D, 0));
        assertEquals(6, LumbererWorkGoal.fatiguePauseTicks(0.0D, 99));
        assertTrue(LumbererWorkGoal.fatiguePauseTicks(10.0D, 80)
            <= LumbererWorkGoal.fatiguePauseTicks(10.0D, 20));
        for (int energy = 0; energy <= 100; energy++) {
            int pause = LumbererWorkGoal.fatiguePauseTicks(energy, 0);
            assertTrue(pause >= 0 && pause <= 11,
                "pause out of bounded range at energy " + energy);
        }
    }
}
