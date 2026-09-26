package com.hearthstead.client.screen;

import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import com.hearthstead.client.ui2.Ui2FrameLayout;
import com.hearthstead.client.ui2.Ui2LayoutAssert;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BannerOrderScreenLayoutTest {

    @Test
    void commandCrossStatusAndFooterFitWithoutOverlapAtGuiScaleTwoToFour() {
        for (int[] v : Ui2LayoutAssert.GUI_2_TO_4) {
            String at = v[0] + "x" + v[1];
            BannerOrderScreen.Layout l = BannerOrderScreen.layoutFor(v[0], v[1]);
            Ui2FrameLayout f = l.frame();
            Ui2LayoutAssert.inViewport(v[0], v[1], new Rect(f.x(), f.y(), f.width(), f.height()), at);
            Rect body = f.body(false, true);
            List<Rect> parts = List.of(l.move(), l.follow(), l.centre(), l.attack(), l.hold(), l.status());
            for (Rect r : parts) Ui2LayoutAssert.inside(body, r, at);
            Ui2LayoutAssert.inside(f.footer(), l.takeover(), at + " takeover");
            Ui2LayoutAssert.disjoint(List.of(l.move(), l.follow(), l.centre(), l.attack(), l.hold(), l.status(),
                f.footer()), at);
            assertTrue(l.move().width() >= 80, "command labels stay readable at " + at);
            assertTrue(!l.takeover().overlaps(f.close()), "takeover clear of close key at " + at);
        }
    }
}
