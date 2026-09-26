package com.hearthstead.client.ui2.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlerFigureTest {
    private static final int TORSO = 0xFF5B7A50;

    private static SettlerFigure build(int t, boolean close, int view, boolean left, int motion, int frame, int tool) {
        SettlerFigure f = new SettlerFigure();
        f.build(t, close, view, left, motion, frame, 0, 0, 0, tool, null, TORSO, true, false);
        return f;
    }

    @Test
    void skinCellsAverageOpaqueTexelsOnly() {
        // Left half red, right half transparent.
        FigureSkin.Pixels px = (x, y) -> x < 2 ? 0xFFFF0000 : 0x00000000;
        int[] cells = FigureSkin.sampleRegion(px, 0, 0, 4, 4, 2, 2);
        assertEquals(0xFFFF0000, cells[0]);
        assertEquals(0, cells[1]);
        FigureSkin.Pixels mixed = (x, y) -> x == 0 ? 0xFF000000 : 0xFF0000FE;
        assertEquals(0xFF00007F, FigureSkin.sampleRegion(mixed, 0, 0, 2, 1, 1, 1)[0]);
    }

    @Test
    void plantedFootSlidesBackOneTexelPerFrame() {
        int t = 3;
        int previous = Integer.MIN_VALUE;
        for (int frame = 0; frame <= 3; frame++) {
            SettlerFigure f = build(t, true, SettlerFigure.SIDE, false, SettlerFigure.WALK, frame, SettlerFigure.TOOL_NONE);
            int foot = nearFootX(f);
            if (previous != Integer.MIN_VALUE) assertEquals(-t, foot - previous, "frame " + frame);
            previous = foot;
        }
    }

    /** Left edge of the near (last-drawn) grounded leg cell. */
    private static int nearFootX(SettlerFigure f) {
        int x = Integer.MIN_VALUE;
        for (int i = 0; i < f.count; i++) {
            if (f.y1[i] == 0 && f.x1[i] - f.x0[i] == 2 * 3) x = f.x0[i];
        }
        assertNotEquals(Integer.MIN_VALUE, x);
        return x;
    }

    @Test
    void cadenceMatchesGroundSpeed() {
        float zoom = 3.0F;
        double gui = 3.0D;
        int t = 3;
        float speed = 3.0F;
        float cps = SettlerFigure.cyclesPerSecond(speed, t, zoom, gui, false);
        double halfCycleBlocks = SettlerFigure.WALK_STRIDE * t / (zoom * gui);
        assertEquals(speed, cps * 2.0D * halfCycleBlocks, 1.0E-4);
        assertTrue(SettlerFigure.cyclesPerSecond(40.0F, t, zoom, gui, true) <= 2.6F);
    }

    @Test
    void facingLeftMirrorsTheFigure() {
        SettlerFigure right = build(3, true, SettlerFigure.SIDE, false, SettlerFigure.WALK, 2, SettlerFigure.AXE);
        SettlerFigure left = build(3, true, SettlerFigure.SIDE, true, SettlerFigure.WALK, 2, SettlerFigure.AXE);
        assertEquals(right.count, left.count);
        for (int i = 0; i < right.count; i++) {
            assertEquals(-right.x1[i], left.x0[i]);
            assertEquals(right.y0[i], left.y0[i]);
        }
        assertTrue(left.headMirror != right.headMirror);
    }

    @Test
    void figuresStayWithinTheirFootprint() {
        int t = 3;
        for (int view = 0; view < 3; view++) {
            for (int motion = 0; motion <= SettlerFigure.SLEEP; motion++) {
                for (int frame = 0; frame < 6; frame++) {
                    for (int tool = 0; tool < SettlerFigure.TOOLS.length; tool++) {
                        SettlerFigure f = build(t, true, view, false, motion, frame, tool);
                        for (int i = 0; i < f.count; i++) {
                            assertTrue(f.x0[i] >= -8 * t && f.x1[i] <= 9 * t, "x " + view + "/" + motion + "/" + tool);
                            assertTrue(f.y0[i] >= -17 * t && f.y1[i] <= 3 * t, "y " + view + "/" + motion + "/" + tool);
                        }
                        assertEquals(8 * t, f.headSize);
                    }
                }
            }
        }
    }

    @Test
    void midMarchAlternatesLegsOnly() {
        SettlerFigure a = build(2, false, SettlerFigure.FRONT, false, SettlerFigure.WALK, 0, SettlerFigure.TOOL_NONE);
        SettlerFigure b = build(2, false, SettlerFigure.FRONT, false, SettlerFigure.WALK, 1, SettlerFigure.TOOL_NONE);
        assertNotEquals(signature(a), signature(b));
        assertEquals(a.headY, b.headY);
    }

    private static long signature(SettlerFigure f) {
        long h = 17;
        for (int i = 0; i < f.count; i++) h = h * 31 + f.x0[i] * 7L + f.y0[i] * 13L + f.x1[i] * 17L + f.y1[i] * 19L;
        return h;
    }

    @Test
    void tradesCarryTheirTools() {
        assertEquals(SettlerFigure.HOE, SettlerFigure.toolFor("farmer"));
        assertEquals(SettlerFigure.AXE, SettlerFigure.toolFor("lumberer"));
        assertEquals(SettlerFigure.PICK, SettlerFigure.toolFor("miner"));
        assertEquals(SettlerFigure.SWORD, SettlerFigure.toolFor("guard"));
        assertEquals(SettlerFigure.BOW, SettlerFigure.toolFor("archer"));
        assertEquals(SettlerFigure.HAMMER, SettlerFigure.toolFor("builder"));
        assertEquals(SettlerFigure.BASKET, SettlerFigure.toolFor("courier"));
        assertEquals(SettlerFigure.TOOL_NONE, SettlerFigure.toolFor("none"));
        SettlerFigure bare = build(2, false, SettlerFigure.FRONT, false, SettlerFigure.IDLE, 0, SettlerFigure.TOOL_NONE);
        SettlerFigure armed = build(2, false, SettlerFigure.FRONT, false, SettlerFigure.IDLE, 0, SettlerFigure.AXE);
        assertEquals(bare.count + 6, armed.count);
    }

    @Test
    void workGestureMovesTheToolOverTheHead() {
        SettlerFigure f = new SettlerFigure();
        int[] tops = new int[3];
        for (int w = 0; w < 3; w++) {
            f.build(3, true, SettlerFigure.FRONT, false, SettlerFigure.WORK, 0, w, 0, 0, SettlerFigure.AXE, null, TORSO,
                true, false);
            int top = 0;
            boolean over = false;
            for (int i = 0; i < f.count; i++) {
                if ((f.flags[i] & SettlerFigure.OVER) != 0) {
                    over = true;
                    top = Math.min(top, f.y0[i]);
                }
            }
            assertTrue(over);
            tops[w] = top;
        }
        assertTrue(tops[0] < tops[2], "raised frame sits higher than the strike");
    }
}
