package com.hearthstead.client.builder;

import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import com.hearthstead.client.ui2.Ui2FrameLayout;
import com.hearthstead.client.ui2.Ui2LayoutAssert;
import org.junit.jupiter.api.Test;

import java.util.List;

/** The design-name dialog on the standard window: name box and footer never collide. */
class DesignNameScreenLayoutTest {

    @Test
    void nameBoxAndFooterFitAtGuiScaleTwoToFour() {
        for (int[] v : Ui2LayoutAssert.GUI_2_TO_4) {
            String at = v[0] + "x" + v[1];
            Ui2FrameLayout f = DesignNameScreen.layoutFor(v[0], v[1]);
            Ui2LayoutAssert.inViewport(v[0], v[1], new Rect(f.x(), f.y(), f.width(), f.height()), at);
            Rect c = f.content();
            Rect nameBox = new Rect(c.x(), c.y(), c.width(), 18);
            Rect back = new Rect(f.footer().x(), f.footer().y(), 80, 20);
            Rect save = new Rect(f.footer().right() - 100, f.footer().y(), 100, 20);
            for (Rect r : List.of(nameBox, back, save)) Ui2LayoutAssert.inside(c, r, at);
            Ui2LayoutAssert.disjoint(List.of(nameBox, back, save), at);
        }
    }
}
