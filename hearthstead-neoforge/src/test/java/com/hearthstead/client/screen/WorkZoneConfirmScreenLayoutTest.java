package com.hearthstead.client.screen;

import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import com.hearthstead.client.ui2.Ui2FrameLayout;
import com.hearthstead.client.ui2.Ui2LayoutAssert;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Geometry preflight; native visual/mouse evidence is still a release gate. */
class WorkZoneConfirmScreenLayoutTest {

    @Test
    void premiumConfirmModalFitsEveryQaGuiScaleViewport() {
        int[][] viewports = {{427, 240}, {480, 270}, {640, 360}};
        for (int[] viewport : viewports) {
            WorkZoneConfirmScreen.Layout layout =
                WorkZoneConfirmScreen.layoutFor(viewport[0], viewport[1]);
            assertTrue(layout.left() >= 0 && layout.top() >= 0);
            assertTrue(layout.left() + layout.panelWidth() <= viewport[0]);
            assertTrue(layout.top() + layout.panelHeight() <= viewport[1]);
            assertTrue(layout.panelWidth() >= 300,
                "both explicit action buttons must retain readable width");
            assertTrue(layout.panelHeight() >= 220,
                "workplace, bounds, size and textual status must remain visible");
        }
    }

    @Test
    void standardFrameContentNeverClipsOrOverlapsAtGuiScaleTwoToFour() {
        for (int[] v : Ui2LayoutAssert.GUI_2_TO_4) {
            Ui2FrameLayout f = WorkZoneConfirmScreen.layoutFor(v[0], v[1]).frame();
            String at = v[0] + "x" + v[1];
            Rect status = WorkZoneConfirmScreen.statusRect(f);
            Rect bounds = WorkZoneConfirmScreen.boundsRect(f);
            Rect size = new Rect(bounds.x(), bounds.bottom() + 4, bounds.width(), 18);
            Rect body = f.body(false, true);
            for (Rect r : List.of(status, bounds, size)) Ui2LayoutAssert.inside(body, r, at);
            Rect[] buttons = f.footerButtons(2, 132);
            Ui2LayoutAssert.disjoint(List.of(status, bounds, size, f.footer()), at);
            Ui2LayoutAssert.disjoint(List.of(buttons), at + " footer");
            assertTrue(status.height() >= 34, "status keeps two lines at " + at);
        }
    }
}
