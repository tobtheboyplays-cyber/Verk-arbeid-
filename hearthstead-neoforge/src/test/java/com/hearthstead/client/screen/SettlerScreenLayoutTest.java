package com.hearthstead.client.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlerScreenLayoutTest {

    @Test
    void scaleThreeViewportUsesOnePageCompactOverview() {
        SettlerScreen.Layout layout = SettlerScreen.layoutFor(
            427, 240, true, 2, true, true);

        assertEquals(SettlerScreen.SettlerLayoutMode.COMPACT, layout.mode);
        assertEquals(411, layout.panelWidth);
        assertEquals(224, layout.totalHeight);
        assertTrue(layout.panelWidth <= 427);
        assertTrue(layout.totalHeight <= 240);
        assertEquals(0, SettlerScreen.maxScrollFor(layout, 240));
        assertEquals(8, layout.attributeCells.length);
        assertEquals(new SettlerScreen.UiRect(8, 52, 142, 134),
            layout.summaryFrame);
        assertEquals(new SettlerScreen.UiRect(156, 52, 247, 134),
            layout.attributesFrame);
        assertEquals(new SettlerScreen.UiRect(16, 72, 126, 43),
            layout.requestCard);
        assertEquals(new SettlerScreen.UiRect(164, 150, 231, 28),
            layout.jobImpactArea);
        assertEquals(new SettlerScreen.UiRect(8, 197, 92, 20),
            layout.footerButtons[0]);
        assertEquals(new SettlerScreen.UiRect(104, 197, 92, 20),
            layout.footerButtons[1]);
        assertEquals(new SettlerScreen.UiRect(200, 197, 92, 20),
            layout.footerButtons[2]);
        assertEquals(new SettlerScreen.UiRect(296, 197, 107, 20),
            layout.footerButtons[3]);
    }

    @Test
    void everyCompactAttributeIsVisibleAndNonOverlapping() {
        SettlerScreen.Layout layout = SettlerScreen.layoutFor(
            427, 240, false, 1, false, false);

        for (int first = 0; first < layout.attributeCells.length; first++) {
            SettlerScreen.UiRect cell = layout.attributeCells[first];
            assertTrue(cell.x() >= 0 && cell.y() >= 0);
            assertTrue(cell.width() >= 100,
                "attribute row must retain its label, NN/100 and bar");
            assertTrue(cell.height() > 0);
            assertTrue(contains(layout.attributesFrame, cell));
            for (int second = first + 1;
                 second < layout.attributeCells.length; second++) {
                assertFalse(intersects(cell, layout.attributeCells[second]),
                    "attribute cells must never overlap");
            }
        }
        int lastBottom = layout.attributeCells[7].y()
            + layout.attributeCells[7].height();
        assertTrue(lastBottom <= layout.jobImpactArea.y());
        assertTrue(layout.jobImpactArea.y() + layout.jobImpactArea.height()
            <= layout.attributesFrame.y() + layout.attributesFrame.height());
        assertTrue(layout.blessingsTop < layout.summaryFrame.y()
            + layout.summaryFrame.height());
        assertTrue(layout.dividerD < layout.footerTop);
        assertTrue(layout.footerTop + 20 <= layout.totalHeight);
    }

    @Test
    void narrowerCompactViewportKeepsBothFramesAndFooterInsidePanel() {
        SettlerScreen.Layout layout = SettlerScreen.layoutFor(
            320, 240, false, 1, false, false);

        assertEquals(304, layout.panelWidth);
        assertEquals(288, layout.contentWidth);
        assertEquals(224, layout.totalHeight);
        assertEquals(0, SettlerScreen.maxScrollFor(layout, 240));
        assertEquals(8, layout.attributeCells.length);
        assertTrue(contains(new SettlerScreen.UiRect(0, 0,
            layout.panelWidth, layout.totalHeight), layout.summaryFrame));
        assertTrue(contains(new SettlerScreen.UiRect(0, 0,
            layout.panelWidth, layout.totalHeight), layout.attributesFrame));
        assertFalse(intersects(layout.summaryFrame, layout.attributesFrame));
        for (SettlerScreen.UiRect cell : layout.attributeCells) {
            assertTrue(cell.width() >= 70,
                "320px fallback abbreviations still need NN/100 plus a readable label");
            assertTrue(contains(layout.attributesFrame, cell));
        }
        for (SettlerScreen.UiRect button : layout.footerButtons) {
            assertTrue(button.x() >= 0);
            assertTrue(button.x() + button.width() <= layout.panelWidth);
            assertTrue(button.y() + button.height() <= layout.totalHeight);
        }
    }

    @Test
    void supportedCompactViewportsNeverIntroduceInternalScroll() {
        int[][] viewports = {{427, 240}, {480, 270}, {640, 360}};
        for (int[] viewport : viewports) {
            SettlerScreen.Layout layout = SettlerScreen.layoutFor(
                viewport[0], viewport[1], true, 2, true, true);
            assertEquals(SettlerScreen.SettlerLayoutMode.COMPACT, layout.mode);
            assertEquals(8, layout.attributeCells.length);
            assertEquals(0, SettlerScreen.maxScrollFor(layout, viewport[1]));
            assertTrue(layout.totalHeight <= viewport[1]);
        }
    }

    @Test
    void wideViewportRetainsDetailedLayout() {
        SettlerScreen.Layout layout = SettlerScreen.layoutFor(
            1280, 720, true, 2, true, true);

        assertEquals(SettlerScreen.SettlerLayoutMode.WIDE, layout.mode);
        assertEquals(336, layout.panelWidth);
        assertEquals(320, layout.contentWidth);
        assertTrue(layout.totalHeight > 224);
    }

    private static boolean intersects(SettlerScreen.UiRect a,
                                      SettlerScreen.UiRect b) {
        return a.x() < b.x() + b.width() && a.x() + a.width() > b.x()
            && a.y() < b.y() + b.height() && a.y() + a.height() > b.y();
    }

    private static boolean contains(SettlerScreen.UiRect outer,
                                    SettlerScreen.UiRect inner) {
        return inner.x() >= outer.x() && inner.y() >= outer.y()
            && inner.x() + inner.width() <= outer.x() + outer.width()
            && inner.y() + inner.height() <= outer.y() + outer.height();
    }
}
