package com.hearthstead.finisher;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Owner rule: finishable only while OFF BALANCE and BELOW 10% health. */
class FinishWindowTrackerTest {
    private static final float MAX = 100.0F;

    @Test
    void ruleNeedsBothConditions() {
        assertFalse(FinishWindowTracker.eligible(false, 9.0F, MAX), "9% HP without a stagger: no finish");
        assertFalse(FinishWindowTracker.eligible(true, 15.0F, MAX), "staggered at 15% HP: no finish");
        assertTrue(FinishWindowTracker.eligible(true, 8.0F, MAX), "8% HP while staggered: finish");
        assertFalse(FinishWindowTracker.eligible(true, 10.0F, MAX), "exactly 10% is not BELOW 10%");
        assertTrue(FinishWindowTracker.eligible(true, 9.99F, MAX));
    }

    @Test
    void deadOrBrokenHealthIsNeverEligible() {
        assertFalse(FinishWindowTracker.eligible(true, 0.0F, MAX));
        assertFalse(FinishWindowTracker.eligible(true, Float.NaN, MAX));
        assertFalse(FinishWindowTracker.eligible(true, 5.0F, 0.0F));
    }

    @Test
    void offBalanceIsAShortSpell() {
        assertTrue(FinishWindowTracker.OFF_BALANCE_TICKS >= 30 && FinishWindowTracker.OFF_BALANCE_TICKS <= 60,
            "owner: about 1.5-3 s");
        FinishWindowTracker t = new FinishWindowTracker();
        UUID id = UUID.randomUUID();
        assertTrue(t.markOffBalance(id, 100L, 50), "a fresh spell starts");
        assertFalse(t.markOffBalance(id, 110L, 50), "a second blow only extends it");
        assertTrue(t.isOffBalance(id, 159L));
        assertFalse(t.isOffBalance(id, 160L), "balance regained");
        assertTrue(t.markOffBalance(id, 170L, 50), "a later blow starts a new spell");
    }

    @Test
    void lowHealthAloneNeverOpensTheWindow() {
        FinishWindowTracker t = new FinishWindowTracker();
        UUID id = UUID.randomUUID();
        assertEquals(FinishWindowTracker.Transition.NONE, t.evaluate(id, 10L, 5.0F, MAX));
        assertFalse(t.isOpen(id, 10L));
        assertFalse(t.reserve(id, 10L, 5.0F, MAX), "no window, no execution");
    }

    @Test
    void staggerAboveTenPercentNeverOpensTheWindow() {
        FinishWindowTracker t = new FinishWindowTracker();
        UUID id = UUID.randomUUID();
        t.markOffBalance(id, 10L, 50);
        assertEquals(FinishWindowTracker.Transition.NONE, t.evaluate(id, 11L, 15.0F, MAX));
        assertFalse(t.reserve(id, 11L, 15.0F, MAX));
    }

    @Test
    void windowOpensWhileBothHoldAndClosesWhenBalanceReturns() {
        FinishWindowTracker t = new FinishWindowTracker();
        UUID id = UUID.randomUUID();
        t.markOffBalance(id, 10L, 50);
        assertEquals(FinishWindowTracker.Transition.NONE, t.evaluate(id, 11L, 30.0F, MAX));
        // a blow drops it below 10% while it is still stumbling
        assertEquals(FinishWindowTracker.Transition.OPENED, t.evaluate(id, 20L, 8.0F, MAX));
        assertEquals(FinishWindowTracker.Transition.NONE, t.evaluate(id, 21L, 8.0F, MAX), "stays open, no flicker");
        assertTrue(t.isOpen(id, 59L));
        assertEquals(9, t.ticksLeft(id, 51L));
        assertEquals(FinishWindowTracker.Transition.CLOSED, t.evaluate(id, 60L, 8.0F, MAX), "balance regained");
        assertFalse(t.isOpen(id, 60L));
    }

    @Test
    void healingAboveTenPercentClosesTheWindow() {
        FinishWindowTracker t = new FinishWindowTracker();
        UUID id = UUID.randomUUID();
        t.markOffBalance(id, 0L, 50);
        t.evaluate(id, 1L, 5.0F, MAX);
        assertEquals(FinishWindowTracker.Transition.CLOSED, t.evaluate(id, 2L, 12.0F, MAX));
    }

    @Test
    void reserveRechecksBothConditionsOnUse() {
        FinishWindowTracker t = new FinishWindowTracker();
        UUID id = UUID.randomUUID();
        t.markOffBalance(id, 0L, 50);
        t.evaluate(id, 1L, 5.0F, MAX);
        assertFalse(t.reserve(id, 2L, 11.0F, MAX), "healed since the last tick: refused");
        assertFalse(t.reserve(id, 50L, 5.0F, MAX), "balance regained since the last tick: refused");
        assertTrue(t.reserve(id, 2L, 5.0F, MAX));
        assertFalse(t.reserve(id, 3L, 5.0F, MAX), "one execution per window");
        assertFalse(t.isOpen(id, 3L), "a reserved enemy stops glowing");
    }

    @Test
    void executedIsTerminal() {
        FinishWindowTracker t = new FinishWindowTracker();
        UUID id = UUID.randomUUID();
        t.markOffBalance(id, 0L, 50);
        t.evaluate(id, 1L, 5.0F, MAX);
        t.reserve(id, 1L, 5.0F, MAX);
        t.markExecuted(id);
        t.markOffBalance(id, 100L, 50);
        assertEquals(FinishWindowTracker.Transition.NONE, t.evaluate(id, 101L, 5.0F, MAX),
            "each enemy can be finished once");
        assertEquals(FinishWindowTracker.State.EXECUTED, t.state(id));
    }

    @Test
    void releasedReservationGoesBackToCombat() {
        FinishWindowTracker t = new FinishWindowTracker();
        UUID id = UUID.randomUUID();
        t.markOffBalance(id, 0L, 50);
        t.evaluate(id, 1L, 5.0F, MAX);
        assertTrue(t.reserve(id, 1L, 5.0F, MAX));
        t.release(id, 2L);
        assertEquals(FinishWindowTracker.State.NONE, t.state(id));
        assertEquals(FinishWindowTracker.Transition.OPENED, t.evaluate(id, 3L, 5.0F, MAX),
            "still off balance and low: the window re-opens");
    }
}
