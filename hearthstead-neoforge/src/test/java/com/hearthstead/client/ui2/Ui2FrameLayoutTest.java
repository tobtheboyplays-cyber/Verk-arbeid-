package com.hearthstead.client.ui2;

import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The standard window keeps the Banner's metrics and never clips at GUI scale 2-4. */
class Ui2FrameLayoutTest {

    @Test
    void sharesTheBannerMetrics() {
        Ui2FrameLayout l = Ui2FrameLayout.centred(640, 360, 400, 260, false);
        assertEquals(Ui2FrameLayout.FRAME + Ui2FrameLayout.MARGIN, l.header().x() - l.x());
        assertEquals(Ui2FrameLayout.CLOSE, l.close().width());
        assertEquals(l.header().right(), l.close().right(), "close key flush with the header's right edge");
        assertEquals(Ui2FrameLayout.PAD, l.content().x() - l.page().x());
        assertEquals(l.header().bottom() + 2, l.ruleY());
        Ui2FrameLayout sub = Ui2FrameLayout.centred(640, 360, 400, 260, true);
        assertEquals(BannerSheetLayout.HEADER_H, sub.header().height(), "subtitle header = Banner header");
    }

    @Test
    void nothingClipsOrOverlapsAtGuiScaleTwoToFour() {
        for (int[] v : Ui2LayoutAssert.GUI_2_TO_4) {
            for (boolean subtitle : new boolean[] {false, true}) {
                for (int[] pref : new int[][] {{260, 180}, {360, 240}, {460, 320}, {600, 400}}) {
                    Ui2FrameLayout l = Ui2FrameLayout.centred(v[0], v[1], pref[0], pref[1], subtitle);
                    String at = v[0] + "x" + v[1] + " pref " + pref[0] + "x" + pref[1] + " sub=" + subtitle;
                    Rect window = new Rect(l.x(), l.y(), l.width(), l.height());
                    Ui2LayoutAssert.inViewport(v[0], v[1], window, at);
                    for (Rect r : List.of(l.header(), l.title(), l.close(), l.page(), l.content(), l.footer())) {
                        Ui2LayoutAssert.inside(window, r, at);
                    }
                    Ui2LayoutAssert.inside(l.page(), l.content(), at);
                    Ui2LayoutAssert.inside(l.content(), l.footer(), at);
                    Ui2LayoutAssert.disjoint(List.of(l.crest(), l.title(), l.close()), at + " header");
                    assertTrue(l.crest().bottom() < l.page().y(), "crest stops above the page at " + at);
                    assertTrue(l.title().width() >= 60, "title keeps room at " + at);
                    Rect[] buttons = l.footerButtons(2, 96);
                    Ui2LayoutAssert.disjoint(List.of(buttons), at + " footer");
                    for (Rect b : buttons) Ui2LayoutAssert.inside(l.footer(), b, at);
                    Rect body = l.body(true, true);
                    Ui2LayoutAssert.disjoint(List.of(l.tabs(), body, l.footer()), at + " body");
                }
            }
        }
    }
}
