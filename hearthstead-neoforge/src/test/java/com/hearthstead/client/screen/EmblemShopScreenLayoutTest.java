package com.hearthstead.client.screen;

import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import com.hearthstead.client.ui2.Ui2FrameLayout;
import com.hearthstead.client.ui2.Ui2LayoutAssert;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Professions and Emblems: list, detail and controls fit at GUI scale 2, 3 and 4. */
class EmblemShopScreenLayoutTest {

    @Test
    void listDetailAndControlsFitTheStandardFrameAtGuiScaleTwoToFour() {
        for (int[] v : Ui2LayoutAssert.GUI_2_TO_4) {
            String at = v[0] + "x" + v[1];
            EmblemShopScreen.ScreenLayout l = EmblemShopScreen.layoutFor(v[0], v[1]);
            Ui2FrameLayout f = l.frame();
            Ui2LayoutAssert.inViewport(v[0], v[1], new Rect(f.x(), f.y(), f.width(), f.height()), at);
            Rect content = f.content();
            for (int i = 0; i < l.visibleRows(); i++) {
                Ui2LayoutAssert.inside(l.list(), l.row(i), at + " row " + i);
            }
            List<Rect> blocks = new ArrayList<>(List.of(l.info(), l.list(), l.detail(), l.bottomRow()));
            for (Rect r : blocks) Ui2LayoutAssert.inside(content, r, at);
            Ui2LayoutAssert.disjoint(List.of(l.list(), l.detail()), at + " columns");
            Ui2LayoutAssert.disjoint(List.of(l.list(), l.bottomRow()), at + " list/footer");
            Ui2LayoutAssert.disjoint(List.of(l.detail(), l.bottomRow()), at + " detail/footer");
            Ui2LayoutAssert.disjoint(List.of(l.feedback(), l.buy(), l.close()), at + " footer");
            for (Rect r : List.of(l.feedback(), l.buy(), l.close())) Ui2LayoutAssert.inside(l.bottomRow(), r, at);
            for (Rect r : List.of(l.minus(), l.quantityBox(), l.plus(), l.reason())) {
                Ui2LayoutAssert.inside(l.detail(), r, at + " detail control");
            }
            Ui2LayoutAssert.disjoint(List.of(l.minus(), l.quantityBox(), l.plus(), l.reason()), at + " qty");
            // A disabled framed "-"/"+" keeps its 6px label beside the 8px padlock (inset 6).
            assertTrue(l.minus().width() - 6 - 8 >= 6, "disabled minus label fits at " + at);
            assertTrue(l.plus().width() - 6 - 8 >= 6, "disabled plus label fits at " + at);
            assertTrue(l.plus().right() + 6 + 110 <= l.detail().right(), "total line has room at " + at);
            assertTrue(l.visibleRows() >= 4, "at least four professions visible at " + at);
            assertTrue(l.detailTextLines() >= 6, "both requirement blocks stay visible at " + at);
            assertTrue(l.detail().width() >= 150, "detail column stays readable at " + at);
            assertTrue(l.feedback().width() >= 110, "feedback keeps a useful column at " + at);
        }
    }

    @Test
    void openScreenRefreshIsBounded() {
        // GUILD-UI01: the open shop re-asks the server for funds/availability,
        // about once a second, and never faster than every 5 ticks.
        assertTrue(EmblemShopScreen.POLL_TICKS >= 10 && EmblemShopScreen.POLL_TICKS <= 20,
            "periodic refresh every 10-20 ticks");
        assertTrue(EmblemShopScreen.MIN_POLL_TICKS >= 5
            && EmblemShopScreen.MIN_POLL_TICKS < EmblemShopScreen.POLL_TICKS,
            "inventory-change refresh is rate limited");
    }
}
