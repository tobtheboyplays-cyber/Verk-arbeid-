package com.hearthstead.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TownFactsTest {
    @Test
    void aBareTownIsNotDefended() {
        TownFacts dayZero = new TownFacts(0, 0, 30, 0, 4);
        assertFalse(dayZero.defended(), "no guards, no walls: nothing to threaten with");
        assertFalse(new TownFacts(1, 0, 0, 0, 4).defended(), "one fighter is not an army");
        assertTrue(new TownFacts(2, 0, 0, 0, 6).defended());
        assertTrue(new TownFacts(0, 1, 0, 0, 6).defended(), "a watchtower or barracks counts");
    }

    @Test
    void threatsNeedRealDefendersToHelp() {
        assertEquals(-10, new TownFacts(0, 0, 0, 0, 4).bonus("intimidation"), "an empty threat hurts the odds");
        assertEquals(6 + 4 + 2, new TownFacts(2, 1, 0, 1, 8).bonus("intimidation"));
        assertEquals(12 + 8 + 6, new TownFacts(9, 5, 0, 9, 40).bonus("intimidation"), "capped");
        assertEquals(2, new TownFacts(0, 0, 40, 0, 4).bonus("trade"));
        assertEquals(0, new TownFacts(0, 0, 0, 0, 4).bonus("charisma"));
    }
}
