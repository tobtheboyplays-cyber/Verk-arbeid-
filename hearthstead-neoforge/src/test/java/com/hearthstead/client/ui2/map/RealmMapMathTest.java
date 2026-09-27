package com.hearthstead.client.ui2.map;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure realm map math: interpolation, camera, hit testing and terrain colour. */
class RealmMapMathTest {
    private static final double EPS = 1.0E-4;

    // ------------------------------------------------------ interpolation

    @Test
    void firstSampleSnapsAndLaterSamplesGlideLinearlyOverOneInterval() {
        MarkerTrack t = new MarkerTrack(UUID.randomUUID());
        t.push(10, 64, 20, 1_000L, 500L);
        assertEquals(10, t.x(1_000L), EPS);
        assertEquals(20, t.z(5_000L), EPS);
        t.push(14, 64, 20, 2_000L, 500L);
        assertEquals(10, t.x(2_000L), EPS);
        assertEquals(12, t.x(2_250L), EPS);
        assertEquals(14, t.x(2_500L), EPS);
        assertEquals(14, t.x(9_000L), EPS, "rests on the last sample, never extrapolates");
        assertEquals(1, t.dirX(), EPS);
        assertEquals(0, t.dirZ(), EPS);
        assertEquals(8.0F, t.speed(2_100L), 1.0E-3F);
        assertEquals(0.0F, t.speed(2_600L), 1.0E-3F);
    }

    @Test
    void aSampleMidGlideContinuesFromTheDrawnPositionWithoutSnapBack() {
        MarkerTrack t = new MarkerTrack(UUID.randomUUID());
        t.push(0, 64, 0, 0L, 500L);
        t.push(10, 64, 0, 1_000L, 500L);
        double drawn = t.x(1_250L);
        assertEquals(5, drawn, EPS);
        t.push(12, 64, 0, 1_250L, 500L);
        assertEquals(drawn, t.x(1_250L), EPS, "continuous at the moment a new sample arrives");
        assertEquals(12, t.x(1_750L), EPS);
    }

    @Test
    void teleportsSnapInsteadOfSlidingAcrossTheMap() {
        MarkerTrack t = new MarkerTrack(UUID.randomUUID());
        t.push(0, 64, 0, 0L, 500L);
        t.push(100, 64, 100, 1_000L, 500L);
        assertEquals(100, t.x(1_000L), EPS);
        assertEquals(100, t.z(1_001L), EPS);
    }

    @Test
    void glideDurationIsBounded() {
        MarkerTrack t = new MarkerTrack(UUID.randomUUID());
        t.push(0, 64, 0, 0L, 500L);
        t.push(4, 64, 0, 100L, 99_999L);
        assertEquals(4, t.x(100L + MarkerTrack.MAX_GLIDE_MS), EPS);
    }

    // -------------------------------------------------------------- camera

    @Test
    void zoomStepsArePixelPerfectAtEveryGuiScale() {
        assertArrayEquals(new float[] {0.5F, 1, 2, 3, 4}, RealmMapCamera.zoomSteps(2, 0.6F), 1.0E-6F);
        assertArrayEquals(new float[] {2 / 3F, 1, 2, 3, 4}, RealmMapCamera.zoomSteps(3, 1.58F), 1.0E-6F);
        assertArrayEquals(new float[] {1 / 3F, 2 / 3F, 1, 2, 3, 4}, RealmMapCamera.zoomSteps(3, 0.4F), 1.0E-6F);
        assertArrayEquals(new float[] {1, 2, 3, 4}, RealmMapCamera.zoomSteps(1, 0.2F), 1.0E-6F);
        for (int scale = 1; scale <= 6; scale++) {
            for (float step : RealmMapCamera.zoomSteps(scale, 0.1F)) {
                float physical = step * scale;
                assertEquals(Math.round(physical), physical, 1.0E-4F,
                    "every step is a whole number of physical pixels per block at scale " + scale);
            }
        }
    }

    @Test
    void wheelZoomKeepsTheWorldPointUnderTheCursorFixed() {
        RealmMapCamera c = new RealmMapCamera();
        c.setSteps(new float[] {1, 2, 3, 4});
        c.jumpTo(100, 200, 1);
        double anchorDx = 40;
        double anchorDy = -20;
        double worldX = c.worldX(anchorDx, 0);
        double worldZ = c.worldZ(anchorDy, 0);
        assertTrue(c.zoomBy(1, anchorDx, anchorDy));
        c.update(0L, false);
        assertEquals(2, c.zoom(), 1.0E-6);
        assertEquals(worldX, c.worldX(anchorDx, 0), 1.0E-9);
        assertEquals(worldZ, c.worldZ(anchorDy, 0), 1.0E-9);
        assertFalse(c.zoomBy(10, 0, 0) && c.targetZoom() > 4, "clamped to the largest step");
    }

    @Test
    void cameraEasesTowardItsTargetAndDragIsImmediate() {
        RealmMapCamera c = new RealmMapCamera();
        c.jumpTo(0, 0, 1);
        c.update(0L, true);
        c.glideTo(100, 0);
        c.update(16L, true);
        assertTrue(c.centerX() > 0 && c.centerX() < 100, "moves part of the way per frame");
        for (long t = 32; t < 2_000; t += 16) c.update(t, true);
        assertEquals(100, c.centerX(), 1.0E-6);
        assertTrue(c.settled());
        c.dragBy(10, 0);
        assertEquals(90, c.centerX(), 1.0E-6, "dragging right moves the view left, 1:1 at 1x");
        assertEquals(c.targetX(), c.centerX(), 1.0E-9);
    }

    @Test
    void panningIsClampedToTheSurveyedArea() {
        RealmMapCamera c = new RealmMapCamera();
        c.setBounds(0, 0, 160, 160);
        c.jumpTo(80, 80, 1);
        c.panBy(10_000, -10_000);
        c.update(0L, false);
        assertEquals(160, c.centerX(), 1.0E-9);
        assertEquals(0, c.centerZ(), 1.0E-9);
    }

    // --------------------------------------------------------- hit testing

    @Test
    void nearestMarkerWinsWithinRadiusAndTiesGoToTheTopmost() {
        float[] xs = {10, 20, 20};
        float[] ys = {10, 10, 10};
        assertEquals(0, RealmMapHitTest.marker(xs, ys, 3, 12, 10, 6));
        assertEquals(2, RealmMapHitTest.marker(xs, ys, 3, 20, 10, 6), "last drawn is on top");
        assertEquals(-1, RealmMapHitTest.marker(xs, ys, 3, 40, 40, 6));
        assertEquals(-1, RealmMapHitTest.marker(xs, ys, 0, 10, 10, 6), "count bounds the arrays");
    }

    @Test
    void smallestFootprintUnderTheCursorWins() {
        float[] l = {0, 10};
        float[] t = {0, 10};
        float[] r = {100, 20};
        float[] b = {100, 20};
        assertEquals(1, RealmMapHitTest.rect(l, t, r, b, 2, 15, 15, 0));
        assertEquals(0, RealmMapHitTest.rect(l, t, r, b, 2, 50, 50, 0));
        assertEquals(-1, RealmMapHitTest.rect(l, t, r, b, 2, 150, 50, 0));
        assertEquals(0, RealmMapHitTest.rect(l, t, r, b, 2, 100.5, 50, 1), "slop widens thin footprints");
    }

    // -------------------------------------------------------------- colour

    @Test
    void terrainColourIsWashedLitFromTheNorthWestAndStable() {
        int grass = 0x7FB238;
        int flat = RealmMapPalette.land(grass, 0, false, 0);
        int lit = RealmMapPalette.land(grass, 3, false, 0);
        int shade = RealmMapPalette.land(grass, -3, false, 0);
        assertTrue(luminance(lit) > luminance(flat) && luminance(flat) > luminance(shade));
        assertTrue(luminance(RealmMapPalette.land(grass, 0, true, 0)) < luminance(flat), "contours are engraved");
        assertTrue(saturation(flat) < saturation(0xFF000000 | grass), "vanilla colours are washed toward linen");
        assertTrue(luminance(RealmMapPalette.water(12, 0)) < luminance(RealmMapPalette.water(1, 0)));
        for (int x = -50; x < 50; x++) {
            int g = RealmMapPalette.grain(x, x * 7);
            assertTrue(g >= -3 && g <= 3);
            assertEquals(g, RealmMapPalette.grain(x, x * 7));
        }
        assertEquals(0xFF332211, RealmMapPalette.toAbgr(0xFF112233));
    }

    private static double luminance(int argb) {
        return 0.3 * ((argb >> 16) & 0xFF) + 0.59 * ((argb >> 8) & 0xFF) + 0.11 * (argb & 0xFF);
    }

    private static double saturation(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        return max == 0 ? 0 : (max - min) / (double) max;
    }

    @Test
    void talkBadgesScaleByWholeTexels() {
        // 11 GUI px at scale 3 is 33 physical: the 32 px art at 1:1.
        org.junit.jupiter.api.Assertions.assertEquals(32, RealmMapView.badgePhysical(11.0F, 3.0D));
        // 11 GUI px at scale 2 is 22: the 16 px art at 1:1 (nearest multiple of 16).
        org.junit.jupiter.api.Assertions.assertEquals(16, RealmMapView.badgePhysical(11.0F, 2.0D));
        // Never smaller than one 16 px badge.
        org.junit.jupiter.api.Assertions.assertEquals(16, RealmMapView.badgePhysical(3.0F, 1.0D));
        for (int gui = 1; gui <= 6; gui++) {
            org.junit.jupiter.api.Assertions.assertEquals(0, RealmMapView.badgePhysical(12.0F, gui) % 16);
        }
    }
}
