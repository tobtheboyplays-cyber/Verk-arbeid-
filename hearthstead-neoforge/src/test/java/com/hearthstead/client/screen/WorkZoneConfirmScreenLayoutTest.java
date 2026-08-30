package com.hearthstead.client.screen;

import org.junit.jupiter.api.Test;

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
}
