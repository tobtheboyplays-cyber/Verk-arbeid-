package com.hearthstead.client.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaqueScreenRenderCacheTest {

    @Test
    void renderViewReusesOnlyTheSameSnapshotRevisionFontAndLanguage() {
        Object snapshot = new Object();
        Object font = new Object();

        assertTrue(PlaqueScreen.renderViewInputsMatch(snapshot, 9, font, "en_us",
            snapshot, 9, font, "en_us"));
        assertFalse(PlaqueScreen.renderViewInputsMatch(snapshot, 9, font, "en_us",
            new Object(), 9, font, "en_us"));
        assertFalse(PlaqueScreen.renderViewInputsMatch(snapshot, 9, font, "en_us",
            snapshot, 10, font, "en_us"));
        assertFalse(PlaqueScreen.renderViewInputsMatch(snapshot, 9, font, "en_us",
            snapshot, 9, new Object(), "en_us"));
        assertFalse(PlaqueScreen.renderViewInputsMatch(snapshot, 9, font, "en_us",
            snapshot, 9, font, "nb_no"));
    }
}
