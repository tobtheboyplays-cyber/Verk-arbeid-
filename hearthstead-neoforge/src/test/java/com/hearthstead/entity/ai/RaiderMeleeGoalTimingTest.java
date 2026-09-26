package com.hearthstead.entity.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RaiderMeleeGoalTimingTest {

    @Test
    void bruteClubUsesTheAuthoredEighteenTickContactBeforeRecovery() {
        // 2026-09-26 owner retunes ("much heavier blows", then "fair Brutes"):
        // 7/15 -> 14/28 -> 18/34.
        assertEquals(18, RaiderMeleeGoal.BRUTE_CLUB_CONTACT_TICK,
            "BRUTE_CLUB_STRIKE contact is t=0.90s on the 20Hz server");
        assertTrue(RaiderMeleeGoal.BRUTE_CLUB_CONTACT_TICK < 34,
            "the 1.70s clip must retain recovery after its contact");
    }
}
