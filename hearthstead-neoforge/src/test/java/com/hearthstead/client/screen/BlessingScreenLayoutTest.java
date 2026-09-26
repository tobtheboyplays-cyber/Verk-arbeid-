package com.hearthstead.client.screen;

import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import com.hearthstead.client.ui2.Ui2FrameLayout;
import com.hearthstead.client.ui2.Ui2LayoutAssert;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Blessing offer in the standard frame: status, three cards (with crowns) and footer never clip or overlap. */
class BlessingScreenLayoutTest {

    @Test
    void cardsStatusAndFooterFitAtGuiScaleTwoToFour() {
        for (int[] v : Ui2LayoutAssert.GUI_2_TO_4) {
            BlessingScreen.Layout l = BlessingScreen.layoutFor(v[0], v[1]);
            Ui2FrameLayout f = l.frame();
            String at = v[0] + "x" + v[1];
            Ui2LayoutAssert.inViewport(v[0], v[1], new Rect(f.x(), f.y(), f.width(), f.height()), at);

            List<Rect> blocks = new ArrayList<>();
            blocks.add(l.status());
            blocks.add(l.footer());
            // The footer rule sits S above the footer text.
            blocks.add(new Rect(l.footer().x(), l.footer().y() - Ui2FrameLayout.S, l.footer().width(), 1));
            for (int i = 0; i < 3; i++) blocks.add(l.hit(i));
            for (Rect r : blocks) Ui2LayoutAssert.inside(f.content(), r, at);
            Ui2LayoutAssert.disjoint(blocks, at);

            for (int i = 0; i < 3; i++) {
                Rect card = l.card(i);
                Ui2LayoutAssert.inside(l.hit(i), card, at + " card " + i);
                // Name, two effect lines, two quality lines and the claim row.
                assertTrue(card.height() >= 100, "card too short at " + at + ": " + card);
                assertTrue(card.width() >= 110, "card too narrow at " + at + ": " + card);
                // The 41 px seal crown (centre at face top + 3) stays inside the card column.
                assertTrue(card.width() >= 41, at);
            }
            assertEquals(l.card(0).height(), l.card(2).height());
        }
    }
}
