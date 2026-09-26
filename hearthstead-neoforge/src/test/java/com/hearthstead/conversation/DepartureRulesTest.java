package com.hearthstead.conversation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DepartureRulesTest {
    private static DepartureRules.Viewer v(double d, boolean los) {
        return new DepartureRules.Viewer(d, los);
    }

    @Test
    void nobodyVanishesInViewUpClose() {
        assertFalse(DepartureRules.unseen(List.of(v(10, true))));
        assertFalse(DepartureRules.unseen(List.of(v(10, false))), "within 24 counts as seen even behind a wall");
        assertFalse(DepartureRules.unseen(List.of(v(40, true))), "in sight within 48");
        assertTrue(DepartureRules.unseen(List.of(v(40, false))), "beyond 24 and out of sight");
        assertTrue(DepartureRules.unseen(List.of(v(49, true))), "beyond 48 always");
        assertFalse(DepartureRules.unseen(List.of(v(60, false), v(30, true))), "every player must be unable to see");
        assertTrue(DepartureRules.unseen(List.of()), "nobody online");
    }

    @Test
    void leaversMustWalkOffOrGiveUpFirst() {
        assertFalse(DepartureRules.mayDespawn(5, 100, List.of()), "no walking yet");
        assertTrue(DepartureRules.mayDespawn(20, 100, List.of()));
        assertTrue(DepartureRules.mayDespawn(3, DepartureRules.GIVE_UP_TICKS, List.of()), "stuck budget spent");
        assertFalse(DepartureRules.mayDespawn(30, DepartureRules.GIVE_UP_TICKS, List.of(v(10, true))),
            "never in view, even after the budget");
    }

    @Test
    void stuckAfterTwentySecondsWithoutProgress() {
        assertFalse(DepartureRules.stuck(0.5, 100));
        assertTrue(DepartureRules.stuck(0.5, DepartureRules.STUCK_TICKS));
        assertFalse(DepartureRules.stuck(3.0, DepartureRules.STUCK_TICKS));
    }
}
