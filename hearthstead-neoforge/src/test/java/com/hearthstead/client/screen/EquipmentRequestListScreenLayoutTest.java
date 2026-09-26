package com.hearthstead.client.screen;

import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import com.hearthstead.client.ui2.Ui2FrameLayout;
import com.hearthstead.client.ui2.Ui2LayoutAssert;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EquipmentRequestListScreenLayoutTest {

    @Test
    void stateStripTicketsAndFooterFitTheStandardFrameAtGuiScaleTwoToFour() {
        for (int[] v : Ui2LayoutAssert.GUI_2_TO_4) {
            String at = v[0] + "x" + v[1];
            EquipmentRequestListScreen.Layout l = EquipmentRequestListScreen.layoutFor(v[0], v[1]);
            Ui2FrameLayout f = l.frame();
            Ui2LayoutAssert.inViewport(v[0], v[1], new Rect(f.x(), f.y(), f.width(), f.height()), at);
            List<Rect> blocks = new ArrayList<>();
            blocks.add(l.status());
            for (int i = 0; i < l.visibleRows(); i++) {
                Rect row = l.row(i);
                Ui2LayoutAssert.inside(l.list(), row, at + " row " + i);
                Ui2LayoutAssert.inside(row, l.well(i), at + " well " + i);
                // Position label under the well stays in the row.
                assertTrue(l.well(i).bottom() + 3 + 9 <= row.bottom(), "position label fits at " + at);
                assertTrue(l.textX() + l.rowTextWidth() <= l.handleX(), "text clears the drag handle at " + at);
                assertTrue(l.handleX() + 5 <= row.right(), "handle inside the row at " + at);
                blocks.add(row);
            }
            blocks.add(f.footer());
            for (Rect r : blocks) Ui2LayoutAssert.inside(f.content(), r, at);
            Ui2LayoutAssert.disjoint(blocks, at);
            Ui2LayoutAssert.inside(f.footer(), l.refresh(), at + " refresh");
            Ui2LayoutAssert.inside(f.footer(), l.back(), at + " back");
            Ui2LayoutAssert.disjoint(List.of(l.refresh(), l.back()), at + " footer");
            assertTrue(l.visibleRows() >= 2, "two tickets visible at " + at);
            assertTrue(l.rowTextWidth() >= 200, "ticket lines stay readable at " + at);
        }
    }
}
