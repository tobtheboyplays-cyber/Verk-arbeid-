package com.hearthstead.client.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HearthHomeTabRulesTest {
    @Test
    void homeStatTooltipsOnlyOnHomeTab() {
        assertTrue(HearthScreen.homeHoverTooltipsActive(false, false, false, false, false));
        assertFalse(HearthScreen.homeHoverTooltipsActive(true, false, false, false, false),
            "People tab must not show Home stat tooltips");
        assertFalse(HearthScreen.homeHoverTooltipsActive(false, true, false, false, false),
            "Tasks tab must not show Home stat tooltips");
        assertFalse(HearthScreen.homeHoverTooltipsActive(false, false, true, false, false));
        assertFalse(HearthScreen.homeHoverTooltipsActive(false, false, false, true, false));
        assertFalse(HearthScreen.homeHoverTooltipsActive(false, false, false, false, true));
    }

    @Test
    void homePopulationMatchesPeopleRosterWhenSnapshotPresent() {
        // Live slot lags/leads the roster: Home shows the roster count.
        assertEquals(4, HearthScreen.displayedPopulation(3, 4));
        assertEquals(3, HearthScreen.displayedPopulation(4, 3));
        assertEquals(0, HearthScreen.displayedPopulation(2, 0));
        // No snapshot yet: fall back to the live menu count.
        assertEquals(3, HearthScreen.displayedPopulation(3, -1));
        assertEquals(0, HearthScreen.displayedPopulation(-5, -1));
    }
}
