package com.hearthstead.client.screen;

import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import com.hearthstead.client.ui2.Ui2FrameLayout;
import com.hearthstead.client.ui2.Ui2LayoutAssert;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EmblemShopScreenLayoutTest {

    @Test
    void rowsBuyPlatesAndFooterFitTheStandardFrameAtGuiScaleTwoToFour() {
        for (int[] v : Ui2LayoutAssert.GUI_2_TO_4) {
            String at = v[0] + "x" + v[1];
            EmblemShopScreen.ScreenLayout l = EmblemShopScreen.layoutFor(v[0], v[1]);
            Ui2FrameLayout f = l.frame();
            Ui2LayoutAssert.inViewport(v[0], v[1], new Rect(f.x(), f.y(), f.width(), f.height()), at);
            Rect content = f.content();
            List<Rect> blocks = new ArrayList<>();
            blocks.add(l.info());
            for (int i = 0; i < l.visibleRows(); i++) {
                Rect row = l.row(i);
                Ui2LayoutAssert.inside(l.list(), row, at + " row " + i);
                Ui2LayoutAssert.inside(row, l.buy(i), at + " buy " + i);
                Ui2LayoutAssert.inside(row, l.well(i), at + " icon " + i);
                assertTrue(l.textX(i) + l.rowTextWidth() <= l.buy(i).x(), "row text clears Buy at " + at);
                blocks.add(row);
            }
            blocks.add(l.footer());
            for (Rect r : blocks) Ui2LayoutAssert.inside(content, r, at);
            Ui2LayoutAssert.disjoint(blocks, at);
            Ui2LayoutAssert.disjoint(List.of(l.feedback(), l.pager(), l.inspect()), at + " footer");
            for (Rect r : List.of(l.feedback(), l.pager(), l.inspect())) Ui2LayoutAssert.inside(l.footer(), r, at);
            assertTrue(l.visibleRows() >= 2, "at least two emblems visible at " + at);
            assertTrue(l.rowTextWidth() >= 150, "cost and reason lines stay readable at " + at);
            assertTrue(l.footerTextWidth() >= 120, "feedback keeps a useful column at " + at);
        }
    }
}
