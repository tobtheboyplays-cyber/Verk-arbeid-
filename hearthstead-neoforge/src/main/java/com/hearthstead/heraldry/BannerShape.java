package com.hearthstead.heraldry;

import java.util.List;
import java.util.Locale;

/**
 * The silhouette of a settlement Banner's cloth. Stored apart from the
 * vanilla pattern layers (it never changes what a pattern means), saved by
 * its string id and sent by its ordinal.
 *
 * <p>Geometry is in cloth pixels on vanilla's 20 x 40 banner flag: x from
 * -10 (left edge) to 10, y from 0 (top, under the bar) to 40 (bottom). Every
 * shape lies inside that rectangle, so no shape can clip anything the plain
 * cloth does not. Each shape is a list of convex quads (a triangle repeats a
 * corner) plus its outline, which the renderer uses for the cloth's thin
 * edges.
 */
public enum BannerShape {
    STRAIGHT("straight",
        List.of(quad(-10, 0, 10, 0, 10, 40, -10, 40)),
        outline(-10, 0, 10, 0, 10, 40, -10, 40)),
    SWALLOWTAIL("swallowtail",
        List.of(quad(-10, 0, 10, 0, 10, 27, -10, 27),
            quad(-10, 27, 0, 27, -10, 40, -10, 40),
            quad(0, 27, 10, 27, 10, 40, 10, 40)),
        outline(-10, 0, 10, 0, 10, 40, 0, 27, -10, 40)),
    POINTED("pointed",
        List.of(quad(-10, 0, 10, 0, 10, 30, -10, 30),
            quad(-10, 30, 10, 30, 0, 40, 0, 40)),
        outline(-10, 0, 10, 0, 10, 30, 0, 40, -10, 30)),
    TONGUED("tongued",
        List.of(quad(-10, 0, 10, 0, 10, 32, -10, 32),
            quad(-10, 32, -10 / 3F, 32, -20 / 3F, 40, -10, 34),
            quad(-10 / 3F, 32, 10 / 3F, 32, 0, 40, 0, 40),
            quad(10 / 3F, 32, 10, 32, 10, 34, 20 / 3F, 40)),
        outline(-10, 0, 10, 0, 10, 34, 20 / 3F, 40, 10 / 3F, 32, 0, 40, -10 / 3F, 32, -20 / 3F, 40, -10, 34)),
    PENNANT("pennant",
        List.of(quad(-10, 0, 10, 0, 1.5F, 40, -1.5F, 40)),
        outline(-10, 0, 10, 0, 1.5F, 40, -1.5F, 40));

    /** Cloth size in pixels (vanilla banner flag). */
    public static final float LEFT = -10F;
    public static final float RIGHT = 10F;
    public static final float TOP = 0F;
    public static final float BOTTOM = 40F;

    private static final BannerShape[] VALUES = values();

    private final String id;
    private final List<float[]> quads;
    private final float[] outline;

    BannerShape(String id, List<float[]> quads, float[] outline) {
        this.id = id;
        this.quads = quads.stream().map(BannerShape::oriented).toList();
        this.outline = outline;
    }

    /** Stable save id ("swallowtail"). */
    public String id() {
        return id;
    }

    /** Language key of the player-facing name. */
    public String translationKey() {
        return "hearthstead.heraldry.shape." + id;
    }

    /**
     * Convex quads as {x0,y0, x1,y1, x2,y2, x3,y3}, wound the way vanilla
     * winds the flag's front face (top-right, top-left, bottom-left,
     * bottom-right: negative area with y pointing down).
     */
    public List<float[]> quads() {
        return quads;
    }

    /** Closed outline as {x0,y0, x1,y1, ...}. */
    public float[] outline() {
        return outline.clone();
    }

    /** Parses a saved id; anything unknown or missing is the plain cloth. */
    public static BannerShape byId(String id) {
        if (id == null) {
            return STRAIGHT;
        }
        String key = id.trim().toLowerCase(Locale.ROOT);
        for (BannerShape shape : VALUES) {
            if (shape.id.equals(key)) {
                return shape;
            }
        }
        return STRAIGHT;
    }

    /** Network ordinal decode; null when out of range (the server refuses it). */
    public static BannerShape byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : null;
    }

    /** Signed area (y down) of a quad; negative is the front-face winding. */
    public static float signedArea(float[] q) {
        float sum = 0;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            sum += q[i * 2] * q[j * 2 + 1] - q[j * 2] * q[i * 2 + 1];
        }
        return sum / 2F;
    }

    private static float[] quad(float... xy) {
        if (xy.length != 8) {
            throw new IllegalArgumentException("a quad has four corners");
        }
        return xy;
    }

    private static float[] outline(float... xy) {
        return xy;
    }

    /** Reverses a quad wound the other way so every face culls like vanilla's. */
    private static float[] oriented(float[] q) {
        if (signedArea(q) <= 0) {
            return q;
        }
        return new float[] {q[6], q[7], q[4], q[5], q[2], q[3], q[0], q[1]};
    }
}
