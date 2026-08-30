package com.hearthstead.client.screen;

import com.hearthstead.settlement.journey.JourneyDefinition;
import com.hearthstead.settlement.journey.JourneyIds;
import com.hearthstead.settlement.journey.JourneyPresentationMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HearthAftermathLayoutTest {

    @Test
    void reportAppearsOnlyAtFj620OrAfterExplicitCompletion() {
        int aftermathOrdinal = JourneyDefinition.CURRENT
            .step(JourneyIds.FJ_620_REVIEW_AFTERMATH).orElseThrow().ordinal();
        int migratedWatchOrdinal = JourneyDefinition.CURRENT
            .step(JourneyIds.FJ_554_SECOND_TRAVELER_ARRIVES)
            .orElseThrow().ordinal();

        assertFalse(HearthScreen.aftermathVisibleFor(false,
            JourneyPresentationMode.ACTIVE, aftermathOrdinal));
        assertFalse(HearthScreen.aftermathVisibleFor(true,
            JourneyPresentationMode.ACTIVE, migratedWatchOrdinal),
            "an old raid report must not hide the remaining v3 Watch path");
        assertTrue(HearthScreen.aftermathVisibleFor(true,
            JourneyPresentationMode.ACTIVE, aftermathOrdinal),
            "the report must be visible while FJ-620 is the current review objective");
        assertTrue(HearthScreen.aftermathVisibleFor(true,
            JourneyPresentationMode.COMPLETE, -1),
            "the immutable report remains reviewable after Journey completion");
        assertFalse(HearthScreen.aftermathVisibleFor(true,
            JourneyPresentationMode.QUARANTINED, aftermathOrdinal));
    }
}
