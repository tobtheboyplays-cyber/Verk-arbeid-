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
    void headerFooterAndInspectorStayApartAtGuiScaleTwoToFour() {
        // Mirrors DevelopmentScreen's private constants: PAD 10, VIEW_X 12, VIEW_Y 64,
        // control row Refresh 56 + Fit 30 + 2 x zoom 20 + 3 gaps of 4 at top+41..61,
        // Close 40 and the Learn / "Available" column 116 at the right.
        for (int[] v : com.hearthstead.client.ui2.Ui2LayoutAssert.GUI_2_TO_4) {
            String at = v[0] + "x" + v[1];
            var l = DevelopmentScreen.layoutFor(v[0], v[1]);
            assertTrue(l.panelWidth() + 16 <= v[0] && l.panelHeight() + 16 <= v[1], at);
            int controlsRight = 10 + 56 + 30 + 2 * 20 + 3 * 4;
            int coinsLeft = l.panelWidth() - 10 - 116;
            assertTrue(controlsRight + 8 <= coinsLeft, at + " zoom row clears the coin line");
            // Board and inspector share the content width and stay in the panel.
            assertTrue(l.inspectorWidth() >= 132, at);
            assertEquals(l.panelWidth() - 24, l.viewWidth() + 8 + l.inspectorWidth(), at);
            // Board bottom (64 + viewHeight) clears the footer divider (panelHeight - 33).
            assertTrue(64 + l.viewHeight() <= l.panelHeight() - 33, at);
            // Footer text column ends before the Learn button.
            assertTrue(10 + (l.panelWidth() - 2 * 10 - 124) < l.panelWidth() - 10 - 116, at);
        }
    }

    @Test
    void onlyANewlyLearnedSelectionOpensTheRecipePage() {
        assertTrue(DevelopmentScreen.shouldOpenLearnedRecipePage(false, true));
        assertTrue(!DevelopmentScreen.shouldOpenLearnedRecipePage(false, false));
        assertTrue(!DevelopmentScreen.shouldOpenLearnedRecipePage(true, true),
            "routine progress refreshes preserve the page the player selected");
    }
}
