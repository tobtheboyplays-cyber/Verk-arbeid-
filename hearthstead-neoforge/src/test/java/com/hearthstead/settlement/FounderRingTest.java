package com.hearthstead.settlement;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** QA U5: founders appear 3-5 blocks out from the Banner and never within 3 blocks of a player. */
class FounderRingTest {

    @Test
    void ringKeepsTheBannerFree() {
        assertFalse(FounderRing.acceptable(0, 0, List.of()), "never on the Banner");
        assertFalse(FounderRing.acceptable(1, 1, List.of()), "never on its front cells");
        assertFalse(FounderRing.acceptable(2, 0, List.of()));
        assertTrue(FounderRing.acceptable(3, 0, List.of()));
        assertTrue(FounderRing.acceptable(3, 3, List.of()));
        assertFalse(FounderRing.acceptable(6, 0, List.of()), "not too far out");
    }

    @Test
    void neverOnOrNextToThePlayer() {
        List<int[]> player = List.of(new int[]{4, 0});
        assertFalse(FounderRing.acceptable(4, 0, player));
        assertFalse(FounderRing.acceptable(4, 2, player));
        assertTrue(FounderRing.acceptable(-4, 0, player));
    }

    @Test
    void everyCandidateIsOnTheRingAndThereIsRoomForFourAroundAPlayer() {
        List<int[]> cells = FounderRing.candidates(42L);
        assertTrue(cells.size() > 40);
        List<int[]> player = List.of(new int[]{2, 0});
        long free = cells.stream().filter(c -> FounderRing.acceptable(c[0], c[1], player)).count();
        assertTrue(free >= 4, "room for all four founders even with the player standing at the Banner");
        for (int[] c : cells) {
            double r = Math.hypot(c[0], c[1]);
            assertTrue(r >= 3.0 && r <= 5.5, "on the ring: " + c[0] + "," + c[1]);
        }
    }
}
