package com.hearthstead.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Owner decision 25 Sep 2026: days last 2x vanilla by default (40 real
 * minutes), configurable through time.dayLengthMultiplier. The safety rules
 * of the previous 1.5x policy are unchanged and still asserted here.
 */
class HearthsteadDayLengthTest {
    @Test
    void onlyOrdinaryOverworldDaylightIncrementsAreEligible() {
        assertTrue(HearthsteadDayLength.isOrdinaryAdvance(true, true, true, 100L, 101L));
        // Sleep skips and /time jumps are never rewound.
        assertFalse(HearthsteadDayLength.isOrdinaryAdvance(true, true, true, 100L, 23_000L));
        // A frozen clock and other dimensions stay vanilla.
        assertFalse(HearthsteadDayLength.isOrdinaryAdvance(true, true, false, 100L, 101L));
        assertFalse(HearthsteadDayLength.isOrdinaryAdvance(true, false, true, 100L, 101L));
        // Explicitly disabled.
        assertFalse(HearthsteadDayLength.isOrdinaryAdvance(false, true, true, 100L, 101L));
    }

    @Test
    void doubleLengthHoldsBackEveryOtherTickAndNeverTwiceInARow() {
        int held = 0;
        boolean previous = false;
        for (long tick = 0; tick < 1_000L; tick++) {
            boolean hold = HearthsteadDayLength.holdBackThisTick(tick, 2.0D);
            assertFalse(previous && hold, "never hold the clock back two ticks in a row at 2x");
            previous = hold;
            if (hold) held++;
        }
        assertEquals(500, held);
        assertEquals(0.0D, HearthsteadDayLength.holdBackFraction(1.0D), 1.0E-9);
        assertEquals(0.0D, HearthsteadDayLength.holdBackFraction(Double.NaN), 1.0E-9);
    }

    @Test
    @ResourceLock("java.lang.System.properties")
    void systemPropertiesStillOptOut() {
        String legacy = System.getProperty(HearthsteadDayLength.LEGACY_ENABLE_PROPERTY);
        String disable = System.getProperty(HearthsteadDayLength.DISABLE_PROPERTY);
        try {
            System.clearProperty(HearthsteadDayLength.LEGACY_ENABLE_PROPERTY);
            System.clearProperty(HearthsteadDayLength.DISABLE_PROPERTY);
            assertTrue(HearthsteadDayLength.enabled(null));
            System.setProperty(HearthsteadDayLength.LEGACY_ENABLE_PROPERTY, "false");
            assertFalse(HearthsteadDayLength.enabled(null));
            System.clearProperty(HearthsteadDayLength.LEGACY_ENABLE_PROPERTY);
            System.setProperty(HearthsteadDayLength.DISABLE_PROPERTY, "true");
            assertFalse(HearthsteadDayLength.enabled(null));
        } finally {
            restore(HearthsteadDayLength.LEGACY_ENABLE_PROPERTY, legacy);
            restore(HearthsteadDayLength.DISABLE_PROPERTY, disable);
        }
    }

    @Test
    void aFullCycleTakesFortyMinutesAtTheDefaultMultiplier() {
        for (long initialGameTime : new long[] {0L, 1L, 2L}) {
            long dayTime = 23_999L;
            for (long tick = 1; tick <= 48_000L; tick++) {
                long before = dayTime;
                dayTime++;
                if (HearthsteadDayLength.isOrdinaryAdvance(true, true, true, before, dayTime)
                    && HearthsteadDayLength.holdBackThisTick(initialGameTime + tick, 2.0D)) {
                    dayTime--;
                }
            }
            assertEquals(47_999L, dayTime, "40 minutes must advance exactly one Minecraft day");
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
