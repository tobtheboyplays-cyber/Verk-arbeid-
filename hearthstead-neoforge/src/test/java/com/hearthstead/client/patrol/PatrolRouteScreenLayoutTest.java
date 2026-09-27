package com.hearthstead.client.patrol;

import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import com.hearthstead.client.ui2.Ui2FrameLayout;
import com.hearthstead.client.ui2.Ui2LayoutAssert;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The route editor sits on the standard window. GUI 2/3/4 on 1080p must not
 * clip; the editor is dense, so 427x240 (GUI 3 on 720p) is not required.
 */
class PatrolRouteScreenLayoutTest {
    private static final int[][] VIEWPORTS = {{960, 540}, {640, 360}, {480, 270}};

    @Test
    void editorBodyLandsOnTheContentAreaAndNothingClips() {
        for (int[] v : VIEWPORTS) {
            String at = v[0] + "x" + v[1];
            Ui2FrameLayout f = PatrolRouteScreen.frameFor(v[0], v[1]);
            int left = PatrolRouteScreen.contentLeft(f);
            int top = PatrolRouteScreen.contentTop(f);
            int w = PatrolRouteScreen.sheetWidth();
            int h = PatrolRouteScreen.sheetHeight();
            Ui2LayoutAssert.inViewport(v[0], v[1], new Rect(f.x(), f.y(), f.width(), f.height()), at);
            // Old sheet body: x from left + 10 to left + w - 10, y from top + 24 to top + h - 8.
            Rect body = new Rect(left + 10, top + 24, w - 20, h - 32);
            assertEquals(f.content(), body, "body = content at " + at);
            Rect list = new Rect(left + 10, top + 26, 112, 8 * 16);
            Rect delete = new Rect(left + w - 10 - 74, top + h - 24, 74, 16);
            Rect hint = new Rect(left + 10, top + h - 19, w - 20 - 74 - 6, 9);
            // Guard picker: four rows from top + 128, page keys just below them.
            Rect guardRows = new Rect(left + 132, top + 128, w - 10 - 132, 4 * 16);
            Rect pageKeys = new Rect(left + 132, top + 128 + 4 * 16 + 1, 64, 12);
            Ui2LayoutAssert.inside(f.content(), pageKeys, at + " page keys");
            Ui2LayoutAssert.disjoint(List.of(guardRows, pageKeys, hint, delete), at + " picker");
            for (Rect r : List.of(delete, hint)) Ui2LayoutAssert.inside(f.content(), r, at);
            Ui2LayoutAssert.inside(f.page(), list, at);
            Ui2LayoutAssert.disjoint(List.of(delete, hint), at);
            Ui2LayoutAssert.disjoint(List.of(f.crest(), f.title(), f.close()), at + " header");
        }
    }
}
