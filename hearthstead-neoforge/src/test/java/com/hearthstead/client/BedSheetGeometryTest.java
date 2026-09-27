package com.hearthstead.client;

import static org.junit.jupiter.api.Assertions.*;

import com.hearthstead.network.BedMarkersPayload;
import java.util.HashSet;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class BedSheetGeometryTest {
    private static final float EPS = 1e-6F;

    @Test void stateMapsToOwnerColours() {
        assertEquals(BedSheetGeometry.YELLOW_OCHRE, BedSheetGeometry.tint(BedMarkersPayload.FREE));
        assertEquals(BedSheetGeometry.MEADOW_GREEN, BedSheetGeometry.tint(BedMarkersPayload.ASSIGNED));
        assertEquals(BedSheetGeometry.BRICK_RED, BedSheetGeometry.tint(BedMarkersPayload.INVALID));
        assertThrows(IllegalArgumentException.class, () -> BedSheetGeometry.tint((byte) 3));
        var colours = new HashSet<Integer>();
        for (byte s = BedMarkersPayload.INVALID; s <= BedMarkersPayload.ASSIGNED; s++) colours.add(BedSheetGeometry.tint(s));
        assertEquals(3, colours.size(), "three distinct sheet colours");
    }

    @Test void paletteReadsAsYellowGreenRed() {
        int y = BedSheetGeometry.YELLOW_OCHRE, g = BedSheetGeometry.MEADOW_GREEN, r = BedSheetGeometry.BRICK_RED;
        assertTrue(red(y) > blue(y) + 80 && green(y) > blue(y) + 60, "yellow ochre");
        assertTrue(green(g) > red(g) && green(g) > blue(g), "meadow green");
        assertTrue(red(r) > green(r) + 80 && red(r) > blue(r) + 80, "brick red");
        assertEquals(BedSheetGeometry.shade(0x646464, 0.5F), 0x323232);
    }

    @Test void sheetSitsJustAboveTheBlanketWithoutZFighting() {
        float lift = BedSheetGeometry.SHEET_Y - BedSheetGeometry.BLANKET_TOP;
        assertTrue(lift >= 0.01F && lift <= 0.02F, "lift " + lift);
        assertTrue(BedSheetGeometry.FOLD_BOTTOM < BedSheetGeometry.BLANKET_TOP);
        assertTrue(BedSheetGeometry.FOLD_BOTTOM > 3F / 16F, "fold stays above the legs");
        assertTrue(BedSheetGeometry.OUTSET > 0F && BedSheetGeometry.OUTSET <= 0.02F);
    }

    @Test void footHalfIsFullyCoveredInEveryDirection() {
        for (Direction f : Direction.Plane.HORIZONTAL) {
            float[] r = rect(f, false);
            assertEquals(1F + BedSheetGeometry.OUTSET * (acrossIsX(f) ? 2 : 1), r[2] - r[0], EPS, f + " x span");
            assertEquals(1F + BedSheetGeometry.OUTSET * (acrossIsX(f) ? 1 : 2), r[3] - r[1], EPS, f + " z span");
            // Foot end hangs over the side opposite the head, never toward the head.
            boolean positive = f.getAxisDirection() == Direction.AxisDirection.POSITIVE;
            assertEquals(positive ? 1F : 0F, along(f, r, positive), EPS, f + " head edge");
            assertEquals(positive ? -BedSheetGeometry.OUTSET : 1F + BedSheetGeometry.OUTSET,
                along(f, r, !positive), EPS, f + " foot end fold");
        }
    }

    @Test void headHalfStopsAtThePillow() {
        for (Direction f : Direction.Plane.HORIZONTAL) {
            float[] r = rect(f, true);
            float min = acrossIsX(f) ? r[1] : r[0], max = acrossIsX(f) ? r[3] : r[2];
            assertEquals(BedSheetGeometry.HEAD_BLANKET, max - min, EPS, f + " reaches 9/16");
            // The pillow end is the FACING side of the head block and must stay uncovered.
            boolean positive = f.getAxisDirection() == Direction.AxisDirection.POSITIVE;
            if (positive) { assertEquals(0F, min, EPS); assertTrue(max <= 1F - 7F / 16F + EPS); }
            else { assertEquals(1F, max, EPS); assertTrue(min >= 7F / 16F - EPS); }
        }
    }

    @Test void halvesMeetWithoutGapInWorldSpace() {
        for (Direction f : Direction.Plane.HORIZONTAL) {
            float[] head = rect(f, true), foot = rect(f, false);
            // Foot block sits one step opposite FACING; shift its rect into the head block frame.
            float dx = -f.getStepX(), dz = -f.getStepZ();
            foot[0] += dx; foot[2] += dx; foot[1] += dz; foot[3] += dz;
            if (acrossIsX(f)) {
                assertEquals(head[0], foot[0], EPS); assertEquals(head[2], foot[2], EPS);
                assertTrue(Math.abs(head[1] - foot[3]) < EPS || Math.abs(head[3] - foot[1]) < EPS, f + " seam");
            } else {
                assertEquals(head[1], foot[1], EPS); assertEquals(head[3], foot[3], EPS);
                assertTrue(Math.abs(head[0] - foot[2]) < EPS || Math.abs(head[2] - foot[0]) < EPS, f + " seam");
            }
        }
    }

    @Test void foldsHangOnTheLongSidesAndTheFootEnd() {
        for (Direction f : Direction.Plane.HORIZONTAL) {
            assertEquals(2, BedSheetGeometry.foldCount(true));
            assertEquals(3, BedSheetGeometry.foldCount(false));
            for (int i = 0; i < 2; i++) {
                Direction side = BedSheetGeometry.foldSide(f, true, i);
                assertNotEquals(f.getAxis(), side.getAxis(), "long side");
            }
            assertEquals(f.getOpposite(), BedSheetGeometry.foldSide(f, false, 2));
            assertThrows(IllegalArgumentException.class, () -> BedSheetGeometry.foldSide(f, true, 2));
        }
        assertThrows(IllegalArgumentException.class, () -> BedSheetGeometry.sheetRect(Direction.UP, true, new float[4]));
    }

    @Test void handbookDescribesTheSheetColours() throws Exception {
        var stream = BedSheetGeometryTest.class.getClassLoader().getResourceAsStream("assets/hearthstead/lang/en_us.json");
        assertNotNull(stream);
        com.google.gson.JsonObject english;
        try (var reader = new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8)) {
            english = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
        }
        String sentence = "Beds show their status on the sheet: yellow = free, green = taken, red = not in a valid room.";
        assertEquals(sentence, english.get("hearthstead.guide.plaque.p2.b4").getAsString());
        assertTrue(english.get("hearthstead.guide.plaque.body2").getAsString().endsWith(sentence));
    }

    private static float[] rect(Direction f, boolean head) {
        float[] r = new float[4];
        BedSheetGeometry.sheetRect(f, head, r);
        assertTrue(r[0] < r[2] && r[1] < r[3], "ordered rect");
        return r;
    }
    private static boolean acrossIsX(Direction f) { return f.getAxis() == Direction.Axis.Z; }
    private static float along(Direction f, float[] r, boolean max) {
        return acrossIsX(f) ? (max ? r[3] : r[1]) : (max ? r[2] : r[0]);
    }
    private static int red(int c) { return (c >> 16) & 0xFF; }
    private static int green(int c) { return (c >> 8) & 0xFF; }
    private static int blue(int c) { return c & 0xFF; }
}
