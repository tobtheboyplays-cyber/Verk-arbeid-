package com.hearthstead.ambient;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BarkLimiterTest {
    private static final double X = 100.0D;
    private static final double Z = -40.0D;

    @Test
    void oneSettlerSpeaksAtMostOncePerSettlerGap() {
        BarkLimiter limiter = new BarkLimiter();
        UUID settler = UUID.randomUUID();
        assertTrue(limiter.tryAcquire(settler, BarkContext.RAIN, X, Z, 1000L));
        assertFalse(limiter.tryAcquire(settler, BarkContext.HUNGRY, X + 30, Z + 30, 1001L),
            "a different context and area still waits for the settler gap");
        assertFalse(limiter.tryAcquire(settler, BarkContext.HUNGRY, X + 30, Z + 30,
            1000L + BarkLimiter.SETTLER_GAP - 1));
        assertTrue(limiter.tryAcquire(settler, BarkContext.HUNGRY, X + 30, Z + 30,
            1000L + BarkLimiter.SETTLER_GAP));
    }

    @Test
    void anEverydayContextIsNotRepeatedSoon() {
        BarkLimiter limiter = new BarkLimiter();
        UUID settler = UUID.randomUUID();
        assertTrue(limiter.tryAcquire(settler, BarkContext.RAIN, X, Z, 0L));
        assertFalse(limiter.tryAcquire(settler, BarkContext.RAIN, X, Z, BarkLimiter.SETTLER_GAP * 2));
        assertTrue(limiter.tryAcquire(settler, BarkContext.RAIN, X, Z, BarkLimiter.CONTEXT_GAP));
    }

    @Test
    void aCrowdInOneAreaDoesNotTalkOverItself() {
        BarkLimiter limiter = new BarkLimiter();
        int said = 0;
        // Twenty settlers, all wanting to talk every tick for one minute.
        for (long t = 0; t < 1200L; t++) {
            for (int i = 0; i < 20; i++) {
                UUID settler = new UUID(7L, i);
                if (limiter.tryAcquire(settler, BarkContext.JOB, X + i % 5, Z + i / 5, t)) {
                    said++;
                }
            }
        }
        long cap = 1200L / BarkLimiter.AREA_GAP + 1;
        assertTrue(said <= cap, "at most one everyday line per area gap, got " + said);
        assertTrue(said >= 1);
    }

    @Test
    void separateAreasAreIndependent() {
        BarkLimiter limiter = new BarkLimiter();
        assertTrue(limiter.tryAcquire(UUID.randomUUID(), BarkContext.JOB, 0, 0, 50L));
        assertFalse(limiter.tryAcquire(UUID.randomUUID(), BarkContext.JOB, 3, 3, 51L), "same cell");
        assertTrue(limiter.tryAcquire(UUID.randomUUID(), BarkContext.JOB, 200, 200, 51L), "far cell");
    }

    @Test
    void eventLinesAndGreetingsUseShorterAreaGaps() {
        BarkLimiter limiter = new BarkLimiter();
        assertTrue(limiter.tryAcquire(UUID.randomUUID(), BarkContext.JOB, X, Z, 0L));
        assertFalse(limiter.tryAcquire(UUID.randomUUID(), BarkContext.GREET, X, Z, BarkLimiter.GREET_AREA_GAP - 1));
        assertTrue(limiter.tryAcquire(UUID.randomUUID(), BarkContext.GREET, X, Z, BarkLimiter.GREET_AREA_GAP));
        assertTrue(limiter.tryAcquire(UUID.randomUUID(), BarkContext.RAID_WON, X, Z,
            BarkLimiter.GREET_AREA_GAP + BarkLimiter.EVENT_AREA_GAP));
    }

    @Test
    void aChorusIgnoresTheAreaButNotTheSettler() {
        BarkLimiter limiter = new BarkLimiter();
        UUID a = UUID.randomUUID();
        assertTrue(limiter.tryAcquireChorus(a, BarkContext.RAID_WON, 10L));
        assertTrue(limiter.tryAcquireChorus(UUID.randomUUID(), BarkContext.RAID_WON, 10L));
        assertTrue(limiter.tryAcquireChorus(UUID.randomUUID(), BarkContext.RAID_WON, 10L));
        assertFalse(limiter.tryAcquireChorus(a, BarkContext.RAID_WON, 11L));
    }

    @Test
    void bodyCuesHaveTheirOwnGap() {
        BarkLimiter limiter = new BarkLimiter();
        UUID settler = UUID.randomUUID();
        assertTrue(limiter.tryCue(settler, 0L));
        assertFalse(limiter.tryCue(settler, BarkLimiter.CUE_GAP - 1));
        assertTrue(limiter.tryCue(settler, BarkLimiter.CUE_GAP));
    }

    @Test
    void aClockThatWentBackwardsNeverBlocksForever() {
        BarkLimiter limiter = new BarkLimiter();
        UUID settler = UUID.randomUUID();
        assertTrue(limiter.tryAcquire(settler, BarkContext.RAIN, X, Z, 50_000L));
        assertTrue(limiter.tryAcquire(settler, BarkContext.RAIN, X, Z, 10L), "time reset");
    }

    @Test
    void greetingsOncePerPlayerPerDay() {
        GreetingLedger ledger = new GreetingLedger();
        UUID settler = UUID.randomUUID();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        assertTrue(ledger.shouldGreet(settler, alice, 3L));
        ledger.mark(settler, alice, 3L);
        assertFalse(ledger.shouldGreet(settler, alice, 3L));
        assertTrue(ledger.shouldGreet(settler, bob, 3L), "another player is still greeted");
        assertTrue(ledger.shouldGreet(UUID.randomUUID(), alice, 3L), "another settler still greets");
        assertTrue(ledger.shouldGreet(settler, alice, 4L), "a new day, a new greeting");
    }
}
