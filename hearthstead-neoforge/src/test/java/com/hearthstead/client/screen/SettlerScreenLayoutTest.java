package com.hearthstead.client.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure geometry of the Banner-style settler sheet at GUI scales 2-4 and on
 * small windows: everything inside the panel and the viewport, nothing
 * overlapping, and enough room for the portrait, needs, tabs, page and footer.
 */
class SettlerScreenLayoutTest {

    private static final int[][] VIEWPORTS = {
        {320, 240}, {427, 240}, {480, 270}, {640, 360}, {683, 384}, {960, 540}, {1920, 1080}
    };

    @Test
    void sheetFitsEverySupportedViewport() {
        for (int[] v : VIEWPORTS) {
            SettlerScreen.Layout l = SettlerScreen.layoutFor(v[0], v[1]);
            assertTrue(l.panelWidth <= v[0], "panel wider than viewport " + v[0]);
            assertTrue(l.totalHeight <= v[1], "panel taller than viewport " + v[1]);
            assertTrue(l.panelWidth >= SettlerScreen.MIN_W || l.panelWidth == v[0]);
            assertTrue(l.panelWidth <= SettlerScreen.MAX_W);
            assertTrue(l.totalHeight <= SettlerScreen.MAX_H);
        }
    }

    @Test
    void regionsStayInsideThePanelAndApart() {
        for (int[] v : VIEWPORTS) {
            SettlerScreen.Layout l = SettlerScreen.layoutFor(v[0], v[1]);
            SettlerScreen.UiRect panel = new SettlerScreen.UiRect(0, 0, l.panelWidth, l.totalHeight);
            String at = " at " + v[0] + "x" + v[1];
            for (SettlerScreen.UiRect r : new SettlerScreen.UiRect[]{
                l.header, l.title, l.close, l.body, l.left, l.right, l.portrait, l.needs,
                l.tabs, l.page, l.footer, l.counters[0], l.counters[1]}) {
                assertTrue(contains(panel, r), r + " outside the panel" + at);
            }
            assertFalse(l.left.overlaps(l.right), "portrait and page columns overlap" + at);
            assertTrue(contains(l.left, l.portrait), "portrait outside its column" + at);
            assertTrue(contains(l.left, l.needs), "needs outside the portrait column" + at);
            assertFalse(l.portrait.overlaps(l.needs), "portrait covers the needs" + at);
            assertTrue(contains(l.right, l.tabs) && contains(l.right, l.page) && contains(l.right, l.footer),
                "tabs, page and footer stay on the page parchment" + at);
            assertTrue(l.tabs.bottom() < l.page.y(), "tabs above the page" + at);
            assertTrue(l.page.bottom() <= l.footer.y(), "page above the footer" + at);
            assertFalse(l.title.overlaps(l.counters[0]), "title under the counters" + at);
            assertFalse(l.counters[1].overlaps(l.close), "counter under the close key" + at);
            assertFalse(l.counters[0].overlaps(l.counters[1]), "counters overlap" + at);
            assertTrue(l.header.bottom() < l.body.y(), "header above the body" + at);
        }
    }

    @Test
    void smallestSheetStillHasAReadablePortraitAndPage() {
        SettlerScreen.Layout l = SettlerScreen.layoutFor(320, 240);
        assertEquals(304, l.panelWidth);
        assertEquals(224, l.totalHeight);
        assertTrue(l.portrait.width() >= 70, "portrait wide enough for a settler");
        assertTrue(l.portrait.height() >= 80, "portrait tall enough for a settler");
        assertTrue(l.page.width() >= 160, "page wide enough for two attribute columns");
        assertTrue(l.page.height() >= 100, "page tall enough for a section before scrolling");
        assertTrue(l.narrowCounters);
        assertFalse(l.widePage, "narrow pages flow in one column");
    }

    @Test
    void largeSheetUsesTwoColumnPagesAndAFullPortrait() {
        SettlerScreen.Layout l = SettlerScreen.layoutFor(960, 540);
        assertEquals(SettlerScreen.MAX_W, l.panelWidth);
        assertEquals(SettlerScreen.MAX_H, l.totalHeight);
        assertTrue(l.widePage);
        assertFalse(l.narrowCounters);
        assertTrue(l.portrait.height() >= 200, "the settler is the sheet's focal point");
        assertEquals(150, l.left.width());
    }

    @Test
    void guiScaleThreeAtFullHdIsTheReviewedShape() {
        SettlerScreen.Layout l = SettlerScreen.layoutFor(640, 360);
        assertEquals(624, l.panelWidth);
        assertEquals(344, l.totalHeight);
        assertTrue(l.widePage);
        assertTrue(l.page.height() >= 200);
    }

    private static boolean contains(SettlerScreen.UiRect outer, SettlerScreen.UiRect inner) {
        return inner.x() >= outer.x() && inner.y() >= outer.y()
            && inner.x() + inner.width() <= outer.x() + outer.width()
            && inner.y() + inner.height() <= outer.y() + outer.height();
    }
}
