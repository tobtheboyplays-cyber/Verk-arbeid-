package com.hearthstead.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HearthsteadDayLengthBoundaryTest {
    @Test
    void invalidAndOutOfRangeMultipliersCannotRunTheClockBackward() {
        assertEquals(0D, HearthsteadDayLength.holdBackFraction(Double.NaN));
        assertEquals(0D, HearthsteadDayLength.holdBackFraction(-100D));
        assertEquals(0.75D, HearthsteadDayLength.holdBackFraction(Double.POSITIVE_INFINITY));
        assertFalse(HearthsteadDayLength.holdBackThisTick(1, Double.NaN));
        assertFalse(HearthsteadDayLength.holdBackThisTick(1, 0D));
    }

    @Test
    void accumulatorRemainsAlternatingAcrossZeroGameTime() {
        for (long tick = -20; tick <= 20; tick++) {
            assertEquals(Math.floorMod(tick, 2L) == 1L,
                HearthsteadDayLength.holdBackThisTick(tick, 2D), "tick " + tick);
        }
        assertTrue(HearthsteadDayLength.isOrdinaryAdvance(true, true, true, -1, 0));
        assertFalse(HearthsteadDayLength.isOrdinaryAdvance(true, true, true, 100, 100));
    }
}
