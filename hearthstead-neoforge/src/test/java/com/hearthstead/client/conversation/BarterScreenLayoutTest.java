package com.hearthstead.client.conversation;

import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import com.hearthstead.client.ui2.Ui2FrameLayout;
import com.hearthstead.client.ui2.Ui2LayoutAssert;
import org.junit.jupiter.api.Test;

import java.util.List;

/** The barter table on the standard window: goods, offers, satisfaction and footer never collide. */
class BarterScreenLayoutTest {
    private static final int CELL = 18;

    @Test
    void tableFitsAtGuiScaleTwoToFour() {
        for (int[] v : Ui2LayoutAssert.GUI_2_TO_4) {
            String at = v[0] + "x" + v[1];
            Ui2FrameLayout f = BarterScreen.layoutFor(v[0], v[1]);
            Ui2LayoutAssert.inViewport(v[0], v[1], new Rect(f.x(), f.y(), f.width(), f.height()), at);
            Rect c = f.content();
            int mineY = c.y() + 11;
            Rect mine = new Rect(c.x(), mineY, 6 * CELL, 6 * CELL);
            Rect theirs = new Rect(c.right() - 6 * CELL, mineY, 6 * CELL, 6 * CELL);
            int cx = f.x() + (f.width() - 132) / 2;
            Rect offers = new Rect(cx, mineY, 132, 10 + 3 * CELL + 12);
            Rect satisfaction = new Rect(cx, mineY + 10 + 3 * CELL + 16, 132, 30);
            Rect flash = new Rect(cx, f.footer().y() - 12, 132, 9);
            List<Rect> parts = List.of(mine, theirs, offers, satisfaction, flash, f.footer());
            for (Rect r : parts) Ui2LayoutAssert.inside(c, r, at);
            Ui2LayoutAssert.disjoint(List.of(mine, offers, theirs), at + " columns");
            Ui2LayoutAssert.disjoint(List.of(offers, satisfaction, flash, f.footer()), at + " centre");
            Ui2LayoutAssert.disjoint(List.of(mine, f.footer()), at + " goods/footer");
            Ui2LayoutAssert.disjoint(List.of(f.footerButtons(3, 76)), at + " buttons");
        }
    }
}
