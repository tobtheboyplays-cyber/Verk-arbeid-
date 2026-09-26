package com.hearthstead.client.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevelopmentScreenLayoutTest {
    @Test
    void compactBoardKeepsWholeDefaultNodeAndIndependentDetailControlsReachable() {
        for (int width : new int[] {320, 427, 640}) {
            var l = DevelopmentScreen.layoutFor(width, 240);
            assertEquals(width - 16, l.panelWidth());
            assertEquals(224, l.panelHeight());
            assertTrue(l.viewWidth() >= 8 + DevelopmentScreen.scaledNodeWidth(0.86F),
                "A complete default node must fit with its focus inset");
            assertTrue(l.inspectorWidth() >= 132,
                "Full detail text and page controls need their own column");
            assertEquals(l.panelWidth() - 24,
                l.viewWidth() + 8 + l.inspectorWidth());
            int detailTop = 58;
            int pageTop = detailTop + l.viewHeight() - 23;
            int learnTop = l.panelHeight() - 26;
            assertTrue(pageTop >= detailTop + 90,
                "At least nine wrapped detail lines must precede page navigation");
            assertTrue(pageTop + 18 <= learnTop - 4,
                "Detail pagination must not collide with the unlock action");
            assertTrue(learnTop + 20 <= l.panelHeight() - 6);
        }
    }

    @Test
    void LargerViewportAddsUsefulGraphAndDetailSpace() {
        var compact = DevelopmentScreen.layoutFor(320, 240);
        var desktop = DevelopmentScreen.layoutFor(640, 360);
        assertEquals(624, desktop.panelWidth());
        assertEquals(344, desktop.panelHeight());
        assertTrue(desktop.viewWidth() > compact.viewWidth());
        assertTrue(desktop.viewHeight() > compact.viewHeight());
        assertTrue(desktop.inspectorWidth() > compact.inspectorWidth());
    }

    @Test
    void onlyANewlyLearnedSelectionOpensTheRecipePage() {
        assertTrue(DevelopmentScreen.shouldOpenLearnedRecipePage(false, true));
        assertTrue(!DevelopmentScreen.shouldOpenLearnedRecipePage(false, false));
        assertTrue(!DevelopmentScreen.shouldOpenLearnedRecipePage(true, true),
            "routine progress refreshes preserve the page the player selected");
    }
}
