package com.hearthstead.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HearthsteadDayLengthTest {
    @Test
    void correctsOneOfEveryThreeOrdinaryOverworldDaylightTicks() {
        assertTrue(HearthsteadDayLength.shouldCorrect(true, true, true,
            100L, 101L, 3L));
        assertFalse(HearthsteadDayLength.shouldCorrect(true, true, true,
            100L, 101L, 2L));
    }

    @Test
    void externalTimeJumpsAndFrozenDaylightAreNeverRewound() {
        assertFalse(HearthsteadDayLength.shouldCorrect(true, true, true,
            100L, 23_000L, 3L));
        assertFalse(HearthsteadDayLength.shouldCorrect(true, true, false,
            100L, 101L, 3L));
    }

    @Test
    void defaultDisabledAndOtherDimensionsRemainVanilla() {
        assertFalse(HearthsteadDayLength.shouldCorrect(false, true, true,
            100L, 101L, 3L));
        assertFalse(HearthsteadDayLength.shouldCorrect(true, false, true,
            100L, 101L, 3L));
    }
}
