package com.hearthstead.settlement.guard.patrol;

/**
 * Route colours: one per route slot, readable on the parchment map and as
 * in-world marker tints. Muted heraldic tinctures, never neon (UI brief).
 */
public final class PatrolPalette {
    /** ARGB, map ink. */
    private static final int[] INK = {
        0xFFB0412E, // gules
        0xFF2F5E8C, // azure
        0xFF3F7A3A, // vert
        0xFF8A4F9E, // purpure
        0xFFC08A1E, // or (darkened)
        0xFF2D7F7C  // teal
    };
    public static final int COUNT = INK.length;

    public static int ink(int color) {
        return INK[Math.floorMod(color, COUNT)];
    }

    public static float red(int color) {
        return ((ink(color) >> 16) & 0xFF) / 255.0F;
    }

    public static float green(int color) {
        return ((ink(color) >> 8) & 0xFF) / 255.0F;
    }

    public static float blue(int color) {
        return (ink(color) & 0xFF) / 255.0F;
    }

    private PatrolPalette() {
    }
}
