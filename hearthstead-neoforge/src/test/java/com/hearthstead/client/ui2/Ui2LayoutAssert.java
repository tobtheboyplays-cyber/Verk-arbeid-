package com.hearthstead.client.ui2;

import com.hearthstead.client.ui2.BannerSheetLayout.Rect;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Shared checks for the UI-consistency layout tests: no clipping, no overlap. */
public final class Ui2LayoutAssert {
    /**
     * GUI viewports for GUI scale 2, 3 and 4 on a 1920x1080 window
     * (960x540, 640x360, 480x270) plus scale 3 on 1280x720 (427x240),
     * the smallest window the mod supports.
     */
    public static final int[][] GUI_2_TO_4 = {{960, 540}, {640, 360}, {480, 270}, {427, 240}};

    private Ui2LayoutAssert() {
    }

    public static void inside(Rect outer, Rect inner, String what) {
        assertTrue(inner.x() >= outer.x() && inner.y() >= outer.y()
                && inner.right() <= outer.right() && inner.bottom() <= outer.bottom(),
            what + ": " + inner + " is clipped by " + outer);
    }

    public static void inViewport(int vw, int vh, Rect r, String what) {
        inside(new Rect(0, 0, vw, vh), r, what + " (viewport " + vw + "x" + vh + ")");
    }

    public static void disjoint(List<Rect> rects, String what) {
        for (int i = 0; i < rects.size(); i++) {
            for (int j = i + 1; j < rects.size(); j++) {
                if (rects.get(i).overlaps(rects.get(j))) {
                    fail(what + ": " + rects.get(i) + " overlaps " + rects.get(j));
                }
            }
        }
    }

    public static Rect rect(int x, int y, int w, int h) {
        return new Rect(x, y, w, h);
    }
}
