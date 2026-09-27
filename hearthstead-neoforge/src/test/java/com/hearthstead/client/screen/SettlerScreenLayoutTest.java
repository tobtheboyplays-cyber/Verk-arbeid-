package com.hearthstead.client.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure geometry of the one-page settler sheet (owner, 26 Sep: no tabs, no
 * scrolling) at GUI scales 2-4 and on small windows: everything inside the
 * panel and the viewport, nothing overlapping, and at the approved 464x256
 * footprint the whole page (Blessings row included) fits without scrolling.
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
                l.header, l.title, l.close, l.body, l.portrait, l.needs,
                l.page, l.footer, l.counters[0], l.counters[1]}) {
                assertTrue(contains(panel, r), r + " outside the panel" + at);
            }
            assertTrue(contains(l.body, l.page) && contains(l.body, l.footer), "page and footer on the body" + at);
            assertTrue(contains(l.page, l.portrait), "portrait on the page" + at);
            assertTrue(contains(l.page, l.needs), "needs on the page" + at);
            assertFalse(l.portrait.overlaps(l.needs), "portrait covers the needs" + at);
            assertTrue(l.page.bottom() <= l.footer.y(), "page above the footer" + at);
            assertFalse(l.title.overlaps(l.counters[0]), "title under the counters" + at);
            assertFalse(l.counters[1].overlaps(l.close), "counter under the close key" + at);
            assertFalse(l.counters[0].overlaps(l.counters[1]), "counters overlap" + at);
            assertTrue(l.header.bottom() < l.body.y(), "header above the body" + at);
        }
    }

    @Test
    void theApprovedFootprintShowsTheWholePageWithoutScrolling() {
        // 1920x1080 at GUI 3 is a 640x360 viewport: the sheet stops at the Banner's cap.
        SettlerScreen.Layout l = SettlerScreen.layoutFor(640, 360);
        assertEquals(464, l.panelWidth);
        assertEquals(256, l.totalHeight);
        assertTrue(l.page.height() >= SettlerScreen.ONE_PAGE_H,
            "every row, the Blessings too, fits: page " + l.page.height() + " < " + SettlerScreen.ONE_PAGE_H);
        int colW = SettlerScreen.columnWidth(l.page.width());
        assertTrue(colW >= 190, "two readable columns: " + colW);
        assertTrue(l.needs.bottom() <= l.page.y() + SettlerScreen.L_WORK, "needs above the Work line");
    }

    @Test
    void biggerWindowsKeepTheSameCappedSheet() {
        SettlerScreen.Layout big = SettlerScreen.layoutFor(960, 540);
        SettlerScreen.Layout gui3 = SettlerScreen.layoutFor(640, 360);
        assertEquals(gui3.panelWidth, big.panelWidth);
        assertEquals(gui3.totalHeight, big.totalHeight);
        assertEquals(gui3.page, big.page);
    }

    @Test
    void smallestSheetStillHasAPortraitAndTheCoreRows() {
        SettlerScreen.Layout l = SettlerScreen.layoutFor(320, 240);
        assertTrue(l.portrait.width() >= 30 && l.portrait.height() >= 40, "a visible portrait");
        assertTrue(l.page.height() >= SettlerScreen.L_HOME + 16, "general knowledge rows fit");
    }

    private static boolean contains(SettlerScreen.UiRect outer, SettlerScreen.UiRect inner) {
        return inner.x() >= outer.x() && inner.y() >= outer.y()
            && inner.x() + inner.width() <= outer.x() + outer.width()
            && inner.y() + inner.height() <= outer.y() + outer.height();
    }
}
