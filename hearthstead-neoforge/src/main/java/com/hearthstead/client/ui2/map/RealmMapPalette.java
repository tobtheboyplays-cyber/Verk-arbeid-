package com.hearthstead.client.ui2.map;

/**
 * Colours and the terrain colour recipe for the realm map.
 *
 * <p>The look is an inked survey on linen: vanilla map colours are
 * desaturated and washed toward parchment, lit from the north-west by a
 * gentle hillshade, with a faint engraved contour every
 * {@link #CONTOUR_STEP} blocks of height and a two-level paper grain.
 * Water is a flat blue-grey wash that deepens with depth. Unknown ground is
 * blank parchment. Every function is pure ARGB math so it is unit tested.
 */
public final class RealmMapPalette {
    public static final int CONTOUR_STEP = 8;

    public static final int PARCHMENT = 0xFFEADFC5;
    public static final int UNKNOWN = 0xFFE3D7BC;
    public static final int WATER_SHALLOW = 0xFFA3B8B2;
    public static final int WATER_DEEP = 0xFF6D8C92;
    public static final int ROAD = 0xFFCDB182;

    // Map chrome and overlays.
    public static final int MAP_FRAME = 0xFF4A3526;
    public static final int MAP_INNER_SHADE = 0x33201408;
    public static final int OUTSIDE_VEIL = 0x3CE6DAC0;
    public static final int CLAIM_INK = 0xFF7A2E2A;
    public static final int CLAIM_INK_SOFT = 0x667A2E2A;
    public static final int CLAIM_DASH = 0xFFF7F1E3;
    public static final int CLAIM_DASH_SHADOW = 0x90201408;
    public static final int BADGE = 0xF0271D15;
    public static final int BADGE_RIM = 0xFFD8C7A4;
    public static final int BUILDING_INK = 0xE0342A20;
    public static final int BUILDING_WASH = 0x30F5EEDC;
    public static final int BUILDING_INVALID = 0xFF8C3A31;
    public static final int MARKER_OUTLINE = 0xFF2A2119;
    public static final int MARKER_HALO = 0xB0F3ECDD;
    public static final int SELECT_RING = 0xFF8C2F2A;
    public static final int HOVER_RING = 0xFF2E261C;
    public static final int LABEL_PAPER = 0xF2F6F0E2;
    public static final int LABEL_EDGE = 0xFFB9A682;

    // Status accents (always drawn with a glyph as well).
    public static final int STATUS_WORKING = 0xFF3E6243;
    public static final int STATUS_WALKING = 0xFF4A6A86;
    public static final int STATUS_IDLE = 0xFF82745D;
    public static final int STATUS_SLEEPING = 0xFFA87B2E;
    public static final int STATUS_ALERT = 0xFF8C3A31;

    private RealmMapPalette() {
    }

    /** Paper grain in [-3, 3], stable per block. */
    public static int grain(int x, int z) {
        int h = x * 374761393 + z * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return ((h >>> 8) % 7) - 3;
    }

    /**
     * Land colour for a vanilla map colour {@code rgb} with hillshade
     * {@code slope} (height minus the mean of the north and west neighbours;
     * positive faces the north-west light) and an optional contour line.
     */
    public static int land(int rgb, int slope, boolean contour, int grain) {
        float r = (rgb >> 16) & 0xFF;
        float g = (rgb >> 8) & 0xFF;
        float b = rgb & 0xFF;
        float lum = 0.30F * r + 0.59F * g + 0.11F * b;
        float sat = 0.60F;
        r = lum + (r - lum) * sat;
        g = lum + (g - lum) * sat;
        b = lum + (b - lum) * sat;
        float wash = 0.30F;
        r = r * (1 - wash) + ((PARCHMENT >> 16) & 0xFF) * wash;
        g = g * (1 - wash) + ((PARCHMENT >> 8) & 0xFF) * wash;
        b = b * (1 - wash) + (PARCHMENT & 0xFF) * wash;
        float shade = 1.0F + Math.max(-4, Math.min(4, slope)) * 0.055F;
        if (contour) shade *= 0.88F;
        // A touch of warmth keeps greens from turning cold on linen.
        r = r * shade * 1.02F + grain;
        g = g * shade + grain;
        b = b * shade * 0.95F + grain;
        return pack(r, g, b);
    }

    /** Water wash, deepening over the first twelve blocks of depth. */
    public static int water(int depth, int grain) {
        float t = Math.max(0, Math.min(12, depth - 1)) / 12.0F;
        return pack(mix(WATER_SHALLOW >> 16, WATER_DEEP >> 16, t) + grain * 0.5F,
            mix(WATER_SHALLOW >> 8, WATER_DEEP >> 8, t) + grain * 0.5F,
            mix(WATER_SHALLOW, WATER_DEEP, t) + grain * 0.5F);
    }

    /** A worn footpath: warm tan, still shaded so hills read through it. */
    public static int road(int slope, int grain) {
        float shade = 1.0F + Math.max(-3, Math.min(3, slope)) * 0.04F;
        return pack(((ROAD >> 16) & 0xFF) * shade + grain, ((ROAD >> 8) & 0xFF) * shade + grain,
            (ROAD & 0xFF) * shade + grain);
    }

    public static int unknown(int grain) {
        return pack(((UNKNOWN >> 16) & 0xFF) + grain, ((UNKNOWN >> 8) & 0xFF) + grain,
            (UNKNOWN & 0xFF) + grain);
    }

    /** Converts ARGB to the ABGR int layout {@code NativeImage#setPixelRGBA} stores. */
    public static int toAbgr(int argb) {
        return (argb & 0xFF00FF00) | ((argb & 0xFF) << 16) | ((argb >> 16) & 0xFF);
    }

    public static int withAlpha(int argb, float alpha) {
        int a = Math.round(((argb >>> 24) & 0xFF) * Math.max(0.0F, Math.min(1.0F, alpha)));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    public static int darker(int argb, float factor) {
        return (argb & 0xFF000000) | pack(((argb >> 16) & 0xFF) * factor, ((argb >> 8) & 0xFF) * factor,
            (argb & 0xFF) * factor) & 0xFFFFFF;
    }

    private static float mix(int a, int b, float t) {
        return (a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t;
    }

    private static int pack(float r, float g, float b) {
        return 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(float v) {
        int i = Math.round(v);
        return i < 0 ? 0 : Math.min(255, i);
    }
}
