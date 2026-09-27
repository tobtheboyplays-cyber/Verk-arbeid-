package com.hearthstead.client.ui2;

import com.hearthstead.client.ui.HearthLayout;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Geometry for the UI2 Hearth sheet. Intent carried over from the old Home
 * card tests: nothing overlaps and every region fits at GUI scale 3
 * (426x240 logical, sheet 410x224) and at the 320x240 minimum.
 */
class HearthSheetLayoutTest {
    private static final int[][] VIEWPORTS = {
        {320, 240}, {426, 240}, {427, 240}, {480, 270}, {512, 290}, {640, 360}, {960, 540}
    };

    private static HearthSheetLayout sheetFor(int viewportWidth, int viewportHeight) {
        HearthLayout container = HearthLayout.forViewport(viewportWidth, viewportHeight, false);
        return HearthSheetLayout.forSheet(container.width(), container.height());
    }

    @Test
    void guiScaleThreeSheetIsTheExpectedSize() {
        HearthSheetLayout sheet = sheetFor(426, 240);
        assertEquals(410, sheet.width());
        assertEquals(224, sheet.height());
        assertTrue(sheet.homeFits());
    }

    @Test
    void homeRegionsStackInBriefOrderWithoutOverlap() {
        for (int[] viewport : VIEWPORTS) {
            HearthSheetLayout s = sheetFor(viewport[0], viewport[1]);
            String at = viewport[0] + "x" + viewport[1];
            assertTrue(s.homeFits(), "Home fits at " + at);
            List<HearthSheetLayout.Rect> order = List.of(s.header(), s.tabs(), s.focus(), s.figures(),
                s.needsHeader(), s.needs(), s.villageHeader(), s.village());
            for (int i = 0; i < order.size(); i++) {
                HearthSheetLayout.Rect r = order.get(i);
                assertInside(s, r, at);
                if (i > 0) {
                    assertTrue(r.y() >= order.get(i - 1).bottom(),
                        "region " + i + " starts below region " + (i - 1) + " at " + at);
                }
            }
            assertTrue(s.tabRuleY() >= s.tabs().bottom() && s.tabRuleY() < s.body().y());
            assertTrue(s.figuresRuleY() > s.figures().bottom() && s.figuresRuleY() < s.needsHeader().y());
        }
    }

    @Test
    void figuresNeedsAndVillageRowsAreDistinctAndLegible() {
        for (int[] viewport : VIEWPORTS) {
            HearthSheetLayout s = sheetFor(viewport[0], viewport[1]);
            for (int i = 0; i < 3; i++) {
                HearthSheetLayout.Rect figure = s.figure(i);
                assertContains(s.figures(), figure);
                assertTrue(figure.width() >= 80, "icon + value + caption need room");
                for (int j = 0; j < i; j++) assertFalse(figure.overlaps(s.figure(j)));
            }
            for (int i = 0; i < HearthSheetLayout.NEED_ROWS; i++) {
                HearthSheetLayout.Rect row = s.needRow(i);
                assertContains(s.needs(), row);
                assertTrue(row.height() >= 12, "need rows hold 12px text buttons");
            }
            for (int i = 0; i < HearthSheetLayout.VILLAGE_ROWS; i++) {
                assertContains(s.village(), s.villageRow(i));
            }
            HearthSheetLayout.Rect action = s.focusAction(90);
            assertContains(s.focus(), action);
            assertTrue(action.width() <= s.focus().width() / 3, "focal text keeps two thirds of the line");
        }
    }

    @Test
    void listAndDetailPanesSplitTheBodyWithAGutter() {
        for (int[] viewport : VIEWPORTS) {
            HearthSheetLayout s = sheetFor(viewport[0], viewport[1]);
            assertContains(s.body(), s.list());
            assertContains(s.body(), s.detail());
            assertFalse(s.list().overlaps(s.detail()));
            assertTrue(s.splitX() > s.list().right() && s.splitX() < s.detail().x());
            assertTrue(s.detail().width() >= 110, "detail pane fits View Settler and a name");
            // People: search + caption (30px) and at least two 20px rows; detail offices + action need 54px.
            assertTrue(s.list().height() >= 30 + 2 * 20);
            assertTrue(s.detail().height() >= 54 + 50);
        }
    }

    @Test
    void suppliesSlotsNeverTouchTheSheetHeaderOrTabs() {
        for (int[] viewport : VIEWPORTS) {
            HearthLayout container = HearthLayout.forViewport(viewport[0], viewport[1], true);
            HearthSheetLayout s = HearthSheetLayout.forSheet(container.width(), container.height());
            for (int index = 0; index < 60; index++) {
                HearthLayout.Rect slot = container.slot(index);
                HearthSheetLayout.Rect well = new HearthSheetLayout.Rect(slot.x() - 1, slot.y() - 1, 18, 18);
                assertFalse(well.overlaps(s.header()));
                assertFalse(well.overlaps(s.tabs()));
                assertTrue(well.y() > s.tabRuleY(), "slot wells sit below the tab rule");
                assertTrue(well.bottom() <= s.height() - 1 && well.right() <= s.width() - 1,
                    "slot wells stay inside the 1px frame");
            }
        }
    }

    private static void assertInside(HearthSheetLayout s, HearthSheetLayout.Rect r, String at) {
        assertTrue(r.x() >= 1 && r.y() >= 1 && r.right() <= s.width() - 1 && r.bottom() <= s.height() - 1,
            "inside the sheet frame at " + at + ": " + r);
    }

    private static void assertContains(HearthSheetLayout.Rect outer, HearthSheetLayout.Rect inner) {
        assertTrue(inner.x() >= outer.x() && inner.y() >= outer.y()
            && inner.right() <= outer.right() && inner.bottom() <= outer.bottom(),
            outer + " contains " + inner);
    }
}
