package com.hearthstead.client.ui2;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Geometry for the Banner screen. Nothing may overlap, everything stays
 * inside the walnut frame, and the map keeps a useful size from the 320x240
 * minimum through GUI scale 3 (426x240) to large windows.
 */
class BannerSheetLayoutTest {
    private static final int[][] VIEWPORTS = {
        {320, 240}, {426, 240}, {427, 240}, {480, 270}, {512, 290}, {640, 360}, {960, 540}, {1280, 720}
    };

    @Test
    void usesMostOfTheWindowWithinBounds() {
        BannerSheetLayout s = BannerSheetLayout.forViewport(426, 240);
        assertEquals(410, s.width());
        assertEquals(224, s.height());
        assertFalse(s.compactNav(), "labelled rail at GUI scale 3, as in the reference");
        assertFalse(s.stacked());
        BannerSheetLayout narrow = BannerSheetLayout.forViewport(320, 240);
        assertTrue(narrow.compactNav(), "icon rail at the 320 minimum");
        assertEquals(304, narrow.width());
        assertTrue(narrow.stacked(), "right column stacks under the map at 320 wide");
        BannerSheetLayout huge = BannerSheetLayout.forViewport(1920, 1080);
        assertEquals(BannerSheetLayout.MAX_WIDTH, huge.width());
        assertEquals(BannerSheetLayout.MAX_HEIGHT, huge.height());
    }

    @Test
    void regionsStayInsideTheFrameAndNeverOverlap() {
        for (int[] v : VIEWPORTS) {
            BannerSheetLayout s = BannerSheetLayout.forViewport(v[0], v[1]);
            String at = v[0] + "x" + v[1];
            BannerSheetLayout.Rect inner = s.inner();
            for (BannerSheetLayout.Rect r : List.of(s.header(), s.title(), s.counters(), s.close(),
                s.body(), s.nav(), s.centrePanel(), s.rightPanel(), s.wide(), s.widePanel())) {
                assertContains(inner, r, at);
            }
            assertContains(s.centrePanel(), s.centre(), at);
            assertContains(s.rightPanel(), s.right(), at);
            assertTrue(s.crest().y() < inner.y() && s.crest().bottom() < s.body().y(),
                "the crest hangs from the top frame and stops above the rail at " + at);
            List<BannerSheetLayout.Rect> header = List.of(s.crest(), s.title(), s.counters(), s.close());
            assertPairwiseDisjoint(header, at + " header");
            assertFalse(s.close().overlaps(s.banner()), "close key clear of the banner at " + at);
            assertFalse(s.counters().overlaps(s.banner()), "counters clear of the banner at " + at);
            assertTrue(s.headerRuleY() > s.header().bottom() && s.headerRuleY() < s.body().y());
            assertPairwiseDisjoint(List.of(s.nav(), s.centrePanel(), s.rightPanel()), at + " columns");
            assertTrue(s.centrePanel().x() - s.nav().right() >= 4, "a wood gutter between rail and page at " + at);
            for (int i = 0; i < BannerSheetLayout.COUNTERS; i++) assertContains(s.counters(), s.counter(i), at);
        }
    }

    @Test
    void railFitsEveryDestinationAndTheMapKeepsAUsefulSize() {
        for (int[] v : VIEWPORTS) {
            BannerSheetLayout s = BannerSheetLayout.forViewport(v[0], v[1]);
            String at = v[0] + "x" + v[1];
            for (int i = 0; i < BannerSheetLayout.NAV_ITEMS; i++) assertContains(s.nav(), s.navItem(i), at);
            assertTrue(s.centre().width() >= 160, "map width at " + at + ": " + s.centre().width());
            assertTrue(s.centre().height() >= (s.stacked() ? 90 : 140), "map height at " + at + ": " + s.centre().height());
            if (s.stacked()) {
                assertTrue(s.right().y() > s.centre().bottom(), "stacked column sits under the map");
                assertTrue(s.right().height() >= 50);
            } else {
                assertTrue(s.right().width() >= 100, "cards and View Settler fit at " + at);
                assertTrue(s.right().height() >= 150, "Workers & Jobs, Food and one action fit at " + at);
            }
            // The header counters must hold icon plus number even when narrow.
            assertTrue(s.counter(0).width() >= 34);
        }
    }

    @Test
    void storageSlotsFitTheWidePaneWithoutOverlap() {
        for (int[] v : VIEWPORTS) {
            BannerSheetLayout s = BannerSheetLayout.forViewport(v[0], v[1]);
            String at = v[0] + "x" + v[1];
            List<BannerSheetLayout.Rect> wells = new ArrayList<>();
            BannerSheetLayout.Rect communal = s.communalGrid();
            for (int i = 0; i < 24; i++) {
                wells.add(new BannerSheetLayout.Rect(communal.x() + i % 6 * 18 - 1, communal.y() + i / 6 * 18 - 1, 18, 18));
            }
            BannerSheetLayout.Rect player = s.playerGrid();
            for (int i = 0; i < 36; i++) {
                int x = player.x() + (i < 27 ? i % 9 : i - 27) * 18 - 1;
                int y = player.y() + (i < 27 ? i / 9 * 18 : 3 * 18 + 4) - 1;
                wells.add(new BannerSheetLayout.Rect(x, y, 18, 18));
            }
            for (BannerSheetLayout.Rect w : wells) {
                assertContains(s.widePanel(), w, at);
                assertFalse(w.overlaps(s.nav()), "slot clear of the rail at " + at);
            }
            assertPairwiseDisjoint(wells, at + " wells");
        }
    }

    private static void assertContains(BannerSheetLayout.Rect outer, BannerSheetLayout.Rect inner, String at) {
        assertTrue(inner.x() >= outer.x() && inner.y() >= outer.y()
            && inner.right() <= outer.right() && inner.bottom() <= outer.bottom(),
            outer + " contains " + inner + " at " + at);
    }

    private static void assertPairwiseDisjoint(List<BannerSheetLayout.Rect> rects, String what) {
        for (int i = 0; i < rects.size(); i++) {
            for (int j = i + 1; j < rects.size(); j++) {
                assertFalse(rects.get(i).overlaps(rects.get(j)), what + ": " + rects.get(i) + " vs " + rects.get(j));
            }
        }
    }
}
