package com.hearthstead.settlement.work;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** RING-1 lane: Sharpened Axes whet counter and stroke timing. */
class LumberWhettingTest {

    @Test
    void eightOwnedTreesMakeAWhetDue() {
        int trees = 0;
        for (int i = 1; i < LumberWhetting.TREES_PER_WHET; i++) {
            trees = LumberWhetting.afterTree(trees, true);
            assertFalse(LumberWhetting.due(trees), "not due after " + i + " trees");
        }
        trees = LumberWhetting.afterTree(trees, true);
        assertEquals(8, trees);
        assertTrue(LumberWhetting.due(trees));
    }

    @Test
    void treesBeforeTheNodeDoNotCount() {
        assertEquals(0, LumberWhetting.afterTree(0, false));
        assertEquals(3, LumberWhetting.afterTree(3, false));
    }

    @Test
    void anOverdueCounterStaysCappedUntilTheWhet() {
        int trees = 8;
        for (int i = 0; i < 20; i++) {
            trees = LumberWhetting.afterTree(trees, true);
        }
        assertEquals(LumberWhetting.TREES_PER_WHET, trees);
        assertEquals(1, LumberWhetting.afterTree(-5, true), "a corrupt negative counter heals");
    }

    @Test
    void fourUnevenStrokesInsideTheClip() {
        int strokes = 0;
        int last = -1;
        int minGap = Integer.MAX_VALUE;
        int maxGap = 0;
        for (int t = 0; t <= LumberWhetting.WHET_TICKS; t++) {
            if (LumberWhetting.strokeAt(t)) {
                assertTrue(t > 0 && t < LumberWhetting.WHET_TICKS);
                if (last >= 0) {
                    minGap = Math.min(minGap, t - last);
                    maxGap = Math.max(maxGap, t - last);
                }
                last = t;
                strokes++;
            }
        }
        assertEquals(4, strokes);
        assertEquals(LumberWhetting.strokeCount(), strokes);
        assertTrue(maxGap > minGap, "strokes are not a metronome");
        assertEquals(70, LumberWhetting.WHET_TICKS, "WHET_AXE clip is 3.5 s");
    }
}
