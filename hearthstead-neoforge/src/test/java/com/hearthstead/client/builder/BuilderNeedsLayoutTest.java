package com.hearthstead.client.builder;

import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BuilderNeedsLayoutTest {
    @Test void onePageFitsCommonGuiScalesWithoutOverlap() {
        for (int[] viewport : new int[][] {{960, 540}, {640, 360}, {480, 270}, {455, 256}, {426, 240}, {320, 240}}) {
            BuilderNeedsLayout l = BuilderNeedsLayout.forViewport(viewport[0], viewport[1]);
            assertTrue(l.frame().width() <= 464 && l.frame().height() <= 256);
            assertTrue(l.frame().x() >= 0 && l.frame().right() <= viewport[0]);
            assertTrue(l.frame().y() >= 0 && l.frame().bottom() <= viewport[1]);
            List<Rect> areas = new ArrayList<>(List.of(l.site(), l.stage(), l.heading(), l.columns()));
            areas.addAll(l.rows());
            areas.addAll(List.of(l.overflow(), l.help(), l.status(), l.inventory()));
            for (int i = 0; i < areas.size(); i++) {
                assertTrue(l.frame().contains(areas.get(i)), "fits: " + areas.get(i));
                for (int j = i + 1; j < areas.size(); j++)
                    assertFalse(areas.get(i).overlaps(areas.get(j)), areas.get(i) + " overlaps " + areas.get(j));
            }
            assertTrue(l.rows().size() >= 2 && l.rows().size() <= 4);
            int[] columns = l.columnStarts();
            assertTrue(columns[0] - l.columns().x() >= 70, "item icon and name have space");
            for (int i = 0; i < columns.length - 1; i++)
                assertTrue(columns[i + 1] - columns[i] >= 25, "each numeric column remains legible");
            assertEquals(l.columns().right(), columns[5]);
        }
    }
}
