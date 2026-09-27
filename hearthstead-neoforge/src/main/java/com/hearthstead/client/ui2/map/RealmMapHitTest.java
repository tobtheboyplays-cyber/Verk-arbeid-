package com.hearthstead.client.ui2.map;

/**
 * Pure hit testing for the realm map, on screen-space arrays the renderer
 * fills each frame (no allocation). Settlers win over buildings; among
 * settlers the nearest centre wins and a tie goes to the one drawn last
 * (on top); among buildings the smallest footprint under the cursor wins,
 * so a workshop inside a bigger yard stays clickable.
 */
public final class RealmMapHitTest {
    private RealmMapHitTest() {
    }

    /** Index of the marker whose centre is within {@code radius} px of the point, or -1. */
    public static int marker(float[] xs, float[] ys, int count, double px, double py, double radius) {
        int best = -1;
        double bestD = radius * radius;
        for (int i = 0; i < count; i++) {
            double dx = xs[i] - px;
            double dy = ys[i] - py;
            double d = dx * dx + dy * dy;
            if (d <= bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /**
     * Index of the smallest screen rectangle containing the point, or -1.
     * Rectangles are given as parallel arrays of left, top, right, bottom
     * (right/bottom exclusive).
     */
    public static int rect(float[] left, float[] top, float[] right, float[] bottom, int count,
                           double px, double py, float slop) {
        int best = -1;
        double bestArea = Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            if (px < left[i] - slop || px >= right[i] + slop || py < top[i] - slop || py >= bottom[i] + slop) {
                continue;
            }
            double area = (double) (right[i] - left[i]) * (bottom[i] - top[i]);
            if (area < bestArea) {
                bestArea = area;
                best = i;
            }
        }
        return best;
    }

    /** True when the point is inside the axis-aligned box (exclusive right/bottom). */
    public static boolean inside(double px, double py, double x, double y, double w, double h) {
        return px >= x && px < x + w && py >= y && py < y + h;
    }
}
