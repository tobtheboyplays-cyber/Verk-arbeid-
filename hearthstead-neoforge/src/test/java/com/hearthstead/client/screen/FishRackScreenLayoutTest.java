package com.hearthstead.client.screen;

import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import com.hearthstead.client.ui2.Ui2FrameLayout;
import com.hearthstead.client.ui2.Ui2LayoutAssert;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/** The standard frame around the catch rack: every slot well and text line lands on the page. */
class FishRackScreenLayoutTest {

    /** Slot positions exactly as {@code FishRackMenu} places them (image-relative). */
    private static List<Rect> slotWells() {
        List<Rect> wells = new ArrayList<>();
        for (int col = 0; col < 4; col++) wells.add(well(39 + 36 * col, 52));
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) wells.add(well(20 + 18 * col, 122 + 18 * row));
        }
        for (int col = 0; col < 9; col++) wells.add(well(20 + 18 * col, 180));
        return wells;
    }

    private static Rect well(int slotX, int slotY) {
        return new Rect(slotX - 1, slotY - 1, 18, 18);
    }

    @Test
    void slotsAndTextSitOnThePageAtGuiScaleTwoToFour() {
        for (int[] v : Ui2LayoutAssert.GUI_2_TO_4) {
            int left = (v[0] - FishRackScreen.IMAGE_W) / 2;
            int top = (v[1] - FishRackScreen.IMAGE_H) / 2;
            Ui2FrameLayout f = FishRackScreen.frameAt(left, top);
            String at = v[0] + "x" + v[1];
            Ui2LayoutAssert.inViewport(v[0], v[1], new Rect(f.x(), f.y(), f.width(), f.height()), at);
            List<Rect> all = new ArrayList<>();
            for (Rect r : slotWells()) all.add(new Rect(left + r.x(), top + r.y(), r.width(), r.height()));
            for (Rect r : FishRackScreen.textRects()) {
                all.add(new Rect(left + r.x(), top + r.y(), r.width(), r.height()));
            }
            for (Rect r : all) Ui2LayoutAssert.inside(f.page(), r, at);
            Ui2LayoutAssert.disjoint(all, at);
        }
    }
}
