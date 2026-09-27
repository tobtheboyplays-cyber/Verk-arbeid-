package com.hearthstead.settlement.work;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** RING-1 lane: Fisher's Nets yield, one fish per in-game hour, at most four. */
class FisherNetYieldTest {

    @Test
    void oneFishPerInGameHour() {
        assertEquals(1000, FisherNetYield.TICKS_PER_FISH);
        FisherNetYield.State s = FisherNetYield.accrue(0, 5_000, 5_999);
        assertEquals(0, s.stored());
        assertEquals(5_000, s.since());
        s = FisherNetYield.accrue(0, 5_000, 6_000);
        assertEquals(1, s.stored());
        assertEquals(6_000, s.since());
        s = FisherNetYield.accrue(1, 6_000, 8_500);
        assertEquals(3, s.stored());
        assertEquals(8_000, s.since(), "the part-hour keeps counting");
    }

    @Test
    void capsAtFourAndBanksNoSpareTime() {
        FisherNetYield.State s = FisherNetYield.accrue(0, 0, 100_000);
        assertEquals(FisherNetYield.CAP, s.stored());
        assertEquals(100_000, s.since());
        FisherNetYield.State after = FisherNetYield.accrue(s.stored(), s.since(), 100_999);
        assertEquals(4, after.stored());
        assertEquals(4, FisherNetYield.accrue(9, 0, 1).stored(), "a corrupt count is clamped");
    }

    @Test
    void stepwiseAccrualEqualsOneBigStep() {
        int stored = 0;
        long since = 0;
        for (long now = 0; now <= 3_700; now += 37) {
            FisherNetYield.State s = FisherNetYield.accrue(stored, since, now);
            stored = s.stored();
            since = s.since();
        }
        FisherNetYield.State once = FisherNetYield.accrue(0, 0, 3_700);
        assertEquals(once.stored(), stored);
        assertEquals(once.since(), since);
    }

    @Test
    void aClockThatRunsBackwardsNeverMintsFish() {
        FisherNetYield.State s = FisherNetYield.accrue(2, 50_000, 10_000);
        assertEquals(2, s.stored());
        assertEquals(10_000, s.since());
        s = FisherNetYield.accrue(s.stored(), s.since(), 10_999);
        assertEquals(2, s.stored());
    }

    @Test
    void haulsAFullNetAnyTimeAndAnyCatchOnTheFirstRound() {
        assertTrue(FisherNetYield.shouldHaul(4, false));
        assertFalse(FisherNetYield.shouldHaul(3, false));
        assertTrue(FisherNetYield.shouldHaul(1, true));
        assertFalse(FisherNetYield.shouldHaul(0, true));
    }
}
