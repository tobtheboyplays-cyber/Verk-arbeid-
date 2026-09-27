package com.hearthstead.client.screen;

import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import com.hearthstead.client.ui2.Ui2FrameLayout;
import com.hearthstead.client.ui2.Ui2LayoutAssert;
import com.hearthstead.menu.SettlerInventoryMenu;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The settler inventory in the standard frame: slots and page blocks on the page, nothing overlapping. */
class SettlerInventoryScreenLayoutTest {

    /** Slot wells exactly where {@link SettlerInventoryMenu} places its slots (image-relative). */
    private static List<Rect> slotWells() {
        List<Rect> wells = new ArrayList<>();
        for (int row = 0; row < SettlerInventoryMenu.BAG_ROWS; row++) {
            for (int col = 0; col < SettlerInventoryMenu.BAG_COLUMNS; col++) {
                wells.add(well(SettlerInventoryMenu.BAG_X + col * 18, SettlerInventoryMenu.BAG_Y + row * 18));
            }
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                wells.add(well(SettlerInventoryMenu.PLAYER_X + col * 18, SettlerInventoryMenu.PLAYER_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            wells.add(well(SettlerInventoryMenu.PLAYER_X + col * 18, SettlerInventoryMenu.PLAYER_Y + 58));
        }
        return wells;
    }

    private static Rect well(int slotX, int slotY) {
        return new Rect(slotX - 1, slotY - 1, 18, 18);
    }

    private static Rect shift(Rect r, int dx, int dy) {
        return new Rect(r.x() + dx, r.y() + dy, r.width(), r.height());
    }

    @Test
    void slotsAndPageBlocksSitOnThePageAtGuiScaleTwoToFour() {
        for (int[] v : Ui2LayoutAssert.GUI_2_TO_4) {
            int left = (v[0] - SettlerInventoryScreen.WIDTH) / 2;
            int top = SettlerInventoryScreen.imageTop(v[1]);
            Ui2FrameLayout f = SettlerInventoryScreen.frameAt(left, top);
            String at = v[0] + "x" + v[1];
            Ui2LayoutAssert.inViewport(v[0], v[1], new Rect(f.x(), f.y(), f.width(), f.height()), at);
            // The image hangs from the frame exactly LIFT below its top.
            assertTrue(f.y() + SettlerInventoryScreen.LIFT == top, at);

            List<Rect> all = new ArrayList<>();
            for (Rect r : slotWells()) all.add(shift(r, left, top));
            for (Rect r : SettlerInventoryScreen.pageBlocks()) all.add(shift(r, left, top));
            for (Rect r : all) Ui2LayoutAssert.inside(f.page(), r, at);
            Ui2LayoutAssert.disjoint(all, at);

            // The rail/right divider runs between the two columns.
            int divider = left + SettlerInventoryScreen.DIVIDER_X;
            for (Rect r : all) {
                assertTrue(r.right() <= divider || r.x() > divider, at + ": " + r + " crosses the divider");
            }
        }
    }
}
