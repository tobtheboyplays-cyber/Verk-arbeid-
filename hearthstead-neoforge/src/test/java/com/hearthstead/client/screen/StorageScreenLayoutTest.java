package com.hearthstead.client.screen;

import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import com.hearthstead.client.ui2.Ui2FrameLayout;
import com.hearthstead.client.ui2.Ui2LayoutAssert;
import com.hearthstead.client.ui2.handbook.HandbookTestData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Standard-frame geometry for the Stores window: nothing clipped, nothing overlapping at GUI 2-4. */
class StorageScreenLayoutTest {
    /** Generous vanilla-font widths (6 px a glyph + tab padding) for All ... Other. */
    private static final int[] CATEGORY_WIDTHS = tabWidths("All", "Food", "Tools", "Wood", "Farming",
        "Building Materials", "Coins", "Other");

    private static int[] tabWidths(String... labels) {
        int[] out = new int[labels.length];
        for (int i = 0; i < labels.length; i++) out[i] = labels[i].length() * 6 + 4;
        return out;
    }

    @Test
    void pageBlocksStackInsideTheBodyWithoutOverlapAtGuiScaleTwoToFour() {
        for (int[] v : Ui2LayoutAssert.GUI_2_TO_4) {
            String at = v[0] + "x" + v[1];
            Ui2FrameLayout frame = StorageScreen.frameFor(v[0], v[1]);
            Rect[] tabs = StorageScreen.categoryTabRects(frame.content().x(), frame.content().y(),
                frame.content().width(), CATEGORY_WIDTHS);
            StorageScreen.Layout l = StorageScreen.layoutFor(v[0], v[1], StorageScreen.tabRows(tabs));
            Ui2FrameLayout f = l.frame();
            Ui2LayoutAssert.inViewport(v[0], v[1], new Rect(f.x(), f.y(), f.width(), f.height()), at);
            Rect body = f.body(false, true);
            for (Rect r : List.of(l.info(), l.search(), l.categories(), l.list(), l.rows())) {
                Ui2LayoutAssert.inside(body, r, at);
            }
            Ui2LayoutAssert.disjoint(List.of(l.info(), l.search(), l.categories(), l.list(), f.footer()), at);
            assertTrue(l.visibleRows() >= 2, "at least two stock rows at " + at);
            assertEquals(l.visibleRows() * StorageScreen.ROW_H, l.list().height());
            if (l.detail() != null) {
                Ui2LayoutAssert.inside(body, l.detail(), at + " detail");
                Ui2LayoutAssert.disjoint(List.of(l.rows(), l.detail()), at + " rows/detail");
                assertTrue(l.detail().width() >= 90, "detail pane stays readable at " + at);
                assertTrue(l.detail().height() >= StorageScreen.DETAIL_FACTS_H + StorageScreen.DETAIL_LINE_H,
                    "detail pane fits the facts and one location line at " + at);
            }
            // Tab rules sit inside the categories block, above the list.
            for (Rect rule : StorageScreen.tabRowRules(l.categories())) {
                assertTrue(rule.y() < l.list().y(), "tab rule above the list at " + at);
            }
        }
    }

    @Test
    void categoryTabsWrapInsideTheirBlockAndNeverOverlap() {
        for (int[] v : Ui2LayoutAssert.GUI_2_TO_4) {
            String at = v[0] + "x" + v[1];
            Ui2FrameLayout frame = StorageScreen.frameFor(v[0], v[1]);
            Rect[] probe = StorageScreen.categoryTabRects(frame.content().x(), 0, frame.content().width(),
                CATEGORY_WIDTHS);
            StorageScreen.Layout l = StorageScreen.layoutFor(v[0], v[1], StorageScreen.tabRows(probe));
            Rect c = l.categories();
            Rect[] tabs = StorageScreen.categoryTabRects(c.x(), c.y(), c.width(), CATEGORY_WIDTHS);
            for (Rect tab : tabs) Ui2LayoutAssert.inside(c, tab, at + " tab");
            Ui2LayoutAssert.disjoint(List.of(tabs), at + " tabs");
        }
    }

    @Test
    void footerSlotsStayInTheFooterAndApart() {
        for (int[] v : Ui2LayoutAssert.GUI_2_TO_4) {
            String at = v[0] + "x" + v[1];
            Ui2FrameLayout f = StorageScreen.frameFor(v[0], v[1]);
            // "Refresh", "Previous", "Page 99 / 99", "Next" at vanilla widths; framed buttons
            // are label + 10, and Previous/Next keep 8 px for the disabled padlock.
            Rect[] slots = StorageScreen.footerSlots(f.footer(), 42 + 10, 46 + 10 + 8, 66, 22 + 10 + 8);
            List<Rect> all = new ArrayList<>(List.of(slots));
            for (Rect r : all) Ui2LayoutAssert.inside(f.footer(), r, at + " footer");
            Ui2LayoutAssert.disjoint(all, at + " footer");
            assertTrue(slots[StorageScreen.FOOTER_LISTED].width() >= 100,
                "the Showing line keeps room at " + at);
            // The footer's short listing (three-digit counts) is never cut with an ellipsis.
            assertTrue(HandbookTestData.width("128 of 128 shown") <= slots[StorageScreen.FOOTER_LISTED].width(),
                "the Showing line fits uncut at " + at);
            assertNotNull(f.close());
            Ui2LayoutAssert.disjoint(List.of(f.close(), f.page()), at + " close key");
        }
    }
}
