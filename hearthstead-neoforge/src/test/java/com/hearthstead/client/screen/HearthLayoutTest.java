package com.hearthstead.client.screen;

import com.hearthstead.client.ui.HearthLayout;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Geometry proves active UI surfaces, not texture quality or a native client render. */
class HearthLayoutTest {
    @Test
    void everyRealSlotFitsAndNeverOverlapsAnotherSlotOrNavigation() {
        for (int width : new int[] {320, 427, 480, 512, 640, 960}) {
            for (int height : new int[] {240, 270, 290, 360}) {
                HearthLayout layout = HearthLayout.forViewport(width, height);
                assertTrue(layout.width() <= width);
                assertTrue(layout.height() <= height);
                for (int index = 0; index < 60; index++) {
                    HearthLayout.Rect slot = layout.slot(index);
                    assertTrue(slot.x() >= 1 && slot.y() >= 1);
                    assertTrue(slot.x() + 17 <= layout.width());
                    assertTrue(slot.y() + 17 <= layout.height());
                    assertFalse(overlaps(slot, layout.recruitment()));
                    assertFalse(overlaps(slot, layout.summary()));
                    for (int tab = 0; tab < 5; tab++) {
                        assertFalse(overlaps(slot, layout.navigationEntry(tab)));
                    }
                    for (int previous = 0; previous < index; previous++) {
                        assertFalse(overlaps(slot, layout.slot(previous)));
                    }
                }
            }
        }
    }

    @Test
    void compactModeKeepsFiveNamedDestinationsAndSeparateLowerActions() {
        HearthLayout compact = HearthLayout.forViewport(320, 240);
        assertTrue(compact.compact());
        assertEquals(304, compact.width());
        assertEquals(224, compact.height());
        assertFalse(overlaps(compact.navigationEntry(4), compact.summary()));
        assertFalse(overlaps(compact.stat(5), compact.recruitment()));
        for (int tab = 0; tab < 5; tab++) {
            HearthLayout.Rect chapter = compact.navigationEntry(tab);
            assertTrue(chapter.x() >= 0 && chapter.y() >= 0);
            assertTrue(chapter.x() + chapter.width() <= compact.width());
            assertTrue(chapter.y() + chapter.height() <= compact.height());
            assertFalse(overlaps(chapter, compact.summary()));
        }
        assertThrows(IllegalArgumentException.class, () -> compact.navigationEntry(5));
    }

    @Test
    void slotOrderRetainsSixByFourStoreThenPlayerRowsThenHotbar() {
        HearthLayout desktop = HearthLayout.forImage(512, 274);
        assertFalse(desktop.compact());
        assertEquals(desktop.slot(0).x() + 5 * 18, desktop.slot(5).x());
        assertEquals(desktop.slot(0).y() + 3 * 18, desktop.slot(23).y());
        assertEquals(desktop.playerX(), desktop.slot(24).x());
        assertEquals(desktop.playerY() + 2 * 18, desktop.slot(50).y());
        assertEquals(desktop.hotbarY(), desktop.slot(51).y());
        assertEquals(desktop.playerX() + 8 * 18, desktop.slot(59).x());
        assertThrows(IllegalArgumentException.class, () -> desktop.slot(60));
    }

    // UI2 rebuild (2026-09-25): Home no longer uses the five stat cards,
    // attention card or recruitment note of this record. Their geometry tests
    // moved to com.hearthstead.client.ui2.HearthSheetLayoutTest (focal line,
    // figures, Needs you, Village, list/detail) with the same intent: no
    // overlap and everything fits at GUI scale 3 (426x240) and 320x240.
    // This record still owns the real Supplies slot geometry tested above.

    private static void assertContains(HearthLayout.Rect outer, HearthLayout.Rect inner) {
        assertTrue(inner.x() >= outer.x() && inner.y() >= outer.y()
            && inner.x() + inner.width() <= outer.x() + outer.width()
            && inner.y() + inner.height() <= outer.y() + outer.height());
    }

    private static boolean overlaps(HearthLayout.Rect a, HearthLayout.Rect b) {
        return a.x() < b.x() + b.width() && a.x() + a.width() > b.x()
            && a.y() < b.y() + b.height() && a.y() + a.height() > b.y();
    }
}
