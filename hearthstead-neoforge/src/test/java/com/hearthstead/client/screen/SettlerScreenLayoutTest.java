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
    }

    @Test
    void everyCompactAttributeIsVisibleAndNonOverlapping() {
        SettlerScreen.Layout layout = SettlerScreen.layoutFor(
            427, 240, false, 1, false, false);

        for (int first = 0; first < layout.attributeCells.length; first++) {
            SettlerScreen.UiRect cell = layout.attributeCells[first];
            assertTrue(cell.x() >= 0 && cell.y() >= 0);
            assertTrue(cell.width() >= 56,
                "attribute cell must retain the 48px NN / 100 value box");
            assertTrue(cell.height() > 0);
            assertTrue(cell.x() + cell.width() <= layout.panelWidth);
            assertTrue(cell.y() + cell.height() <= layout.totalHeight);
            for (int second = first + 1;
                 second < layout.attributeCells.length; second++) {
                assertFalse(intersects(cell, layout.attributeCells[second]),
                    "attribute cells must never overlap");
            }
        }
        int lastBottom = layout.attributeCells[7].y()
            + layout.attributeCells[7].height();
        assertTrue(lastBottom < layout.employmentTop);
        assertTrue(layout.blessingsTop < layout.dividerD);
        assertTrue(layout.dividerD < layout.footerTop);
        assertTrue(layout.footerTop + 20 <= layout.totalHeight);
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
}
