package com.hearthstead.client.ui2;

import net.minecraft.client.gui.GuiGraphics;

/**
 * In-world HUD style (command chips, downed/revive, finisher prompt,
 * pickup notices, hint toasts): a translucent dark walnut plate with a
 * sunken edge and a faint gold top line, light "text on wood" ink and gold
 * for keys and numbers. Same materials as the Banner screen's counters.
 */
public final class Ui2Hud {
    public static final int PLATE = 0xD824170E;
    public static final int EDGE_DARK = 0xE01B110A;
    public static final int EDGE_LIGHT = 0xC05B412D;
    public static final int TEXT = BannerChrome.TEXT_ON_WOOD;
    public static final int MUTED = BannerChrome.TEXT_ON_WOOD_MUTED;
    public static final int KEY = BannerChrome.GOLD_EDGE;
    /** Warnings and danger on the dark plate (always with a word or glyph). */
    public static final int WARN = 0xFFE08A7A;
    public static final int GOOD = 0xFFA9C99B;

    private Ui2Hud() {
    }

    public static void plate(GuiGraphics g, int x, int y, int w, int h) {
        plate(g, x, y, w, h, 1.0F);
    }

    /** The plate at {@code alpha} (0..1) for fading toasts. */
    public static void plate(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        g.fill(x, y, x + w, y + h, fade(PLATE, alpha));
        g.fill(x, y, x + w, y + 1, fade(EDGE_DARK, alpha));
        g.fill(x, y, x + 1, y + h, fade(EDGE_DARK, alpha));
        g.fill(x, y + h - 1, x + w, y + h, fade(EDGE_LIGHT, alpha));
        g.fill(x + w - 1, y, x + w, y + h, fade(EDGE_LIGHT, alpha));
        g.fill(x + 1, y + 1, x + w - 1, y + 2, fade(Ui2Palette.GOLD & 0x40FFFFFF, alpha));
    }

    /** Multiplies the colour's alpha by {@code alpha}. */
    public static int fade(int argb, float alpha) {
        int a = Math.round(((argb >>> 24) & 0xFF) * Math.max(0.0F, Math.min(1.0F, alpha)));
        return (a << 24) | (argb & 0xFFFFFF);
    }
}
