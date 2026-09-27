package com.hearthstead.client.screen;

import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import com.hearthstead.client.ui2.Ui2FrameLayout;
import com.hearthstead.client.ui2.Ui2LayoutAssert;
import com.hearthstead.client.ui2.handbook.HandbookTestData;
import com.hearthstead.settlement.state.GuardOrder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Geometry preflight for the standard-frame guard command board. */
class GuardOrderScreenLayoutTest {

    @Test
    void commandBoardNeverClipsOrOverlapsAtGuiScaleTwoToFour() {
        for (int[] v : Ui2LayoutAssert.GUI_2_TO_4) {
            GuardOrderScreen.Layout l = GuardOrderScreen.layoutFor(v[0], v[1]);
            Ui2FrameLayout f = l.frame();
            String at = v[0] + "x" + v[1];
            Ui2LayoutAssert.inViewport(v[0], v[1], new Rect(f.x(), f.y(), f.width(), f.height()), at);

            List<Rect> blocks = new ArrayList<>();
            blocks.add(l.status());
            blocks.addAll(List.of(l.orders()));
            blocks.add(l.patrolHeading());
            blocks.add(l.traversal());
            blocks.addAll(List.of(l.patrol()));
            blocks.add(l.points());
            blocks.add(l.towerHeading());
            blocks.add(l.tower());
            for (Rect r : blocks) Ui2LayoutAssert.inside(f.content(), r, at);
            Ui2LayoutAssert.disjoint(blocks, at);

            List<Rect> points = new ArrayList<>();
            for (int i = 0; i < GuardOrder.MAX_PATROL_POINTS; i++) {
                Rect p = l.point(i);
                Ui2LayoutAssert.inside(l.points(), p, at + " point " + i);
                points.add(p);
            }
            Ui2LayoutAssert.disjoint(points, at + " points");

            // Actions keep a readable column and the text-button height.
            for (Rect r : List.of(l.orders()[0], l.patrol()[2], l.traversal(), l.tower())) {
                assertTrue(r.width() >= 100, "action column too narrow at " + at + ": " + r);
                assertEquals(Ui2FrameLayout.TEXT_BUTTON_H, r.height());
            }
            assertTrue(l.status().height() >= 24, "status keeps order + feedback at " + at);

            // A locked framed text button keeps a 6 px rim and an 8 px lock (label + 10 + 8):
            // the widest English action must still fit its column unshortened.
            for (String label : List.of("Hold Here", "Defend Banner", "Stop Order", "Add My Position",
                    "Undo Point", "Start Patrol", "Route: Ping-Pong", "Take Tower Post")) {
                assertTrue(HandbookTestData.width(label) + 18 <= l.orders()[0].width(),
                    label + " does not fit its action column at " + at);
            }
        }
    }

    @Test
    void onlyTheSmallestViewportIsCompact() {
        assertTrue(!GuardOrderScreen.layoutFor(960, 540).compact());
        assertTrue(!GuardOrderScreen.layoutFor(640, 360).compact());
        assertTrue(!GuardOrderScreen.layoutFor(480, 270).compact());
        assertTrue(GuardOrderScreen.layoutFor(427, 240).compact());
    }
}
