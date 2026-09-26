package com.hearthstead.client.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchScreenRenderCacheTest {

    @Test
    void renderViewReusesOnlyAnUnchangedSnapshotFontLanguageAndLayout() {
        Object snapshot = new Object();
        Object font = new Object();

        assertTrue(ResearchScreen.renderViewInputsMatch(snapshot, 7, font, "en_us", 320,
            snapshot, 7, font, "en_us", 320));
        assertFalse(ResearchScreen.renderViewInputsMatch(snapshot, 7, font, "en_us", 320,
            new Object(), 7, font, "en_us", 320));
        assertFalse(ResearchScreen.renderViewInputsMatch(snapshot, 7, font, "en_us", 320,
            snapshot, 8, font, "en_us", 320));
        assertFalse(ResearchScreen.renderViewInputsMatch(snapshot, 7, font, "en_us", 320,
            snapshot, 7, new Object(), "en_us", 320));
        assertFalse(ResearchScreen.renderViewInputsMatch(snapshot, 7, font, "en_us", 320,
            snapshot, 7, font, "nb_no", 320));
        assertFalse(ResearchScreen.renderViewInputsMatch(snapshot, 7, font, "en_us", 320,
            snapshot, 7, font, "en_us", 426));
    }

    @Test
    void scrollOnlySelectsDifferentCardsFromTheExistingView() {
        assertEquals(0, ResearchScreen.projectOrdinalForRow(0, 0));
        assertEquals(2, ResearchScreen.projectOrdinalForRow(0, 2));
        assertEquals(3, ResearchScreen.projectOrdinalForRow(2, 1));
        assertEquals(4, ResearchScreen.projectOrdinalForRow(2, 2));
    }
}
