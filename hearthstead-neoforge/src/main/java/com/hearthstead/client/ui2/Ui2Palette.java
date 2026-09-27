package com.hearthstead.client.ui2;

/**
 * UI2 colour tokens: one light linen sheet, warm ink, a single forest accent,
 * aged gold as the quiet secondary and burgundy only for danger.
 *
 * <p>Rules: a view uses FOREST for at most one primary action and for the
 * selected-row bar; GOLD marks secondary facts (progress, office holders);
 * DANGER appears only next to a glyph or word that also says "problem".
 */
public final class Ui2Palette {
    // Sheet
    public static final int PAPER = 0xFFEFE7D6;
    public static final int PAPER_DEEP = 0xFFE7DDC8;
    public static final int PAPER_TEXTURE_VEIL = 0xD8EFE7D6;
    public static final int FRAME = 0xFF8B6F4E;
    public static final int FRAME_INNER = 0xFFF8F2E4;
    public static final int SHADOW = 0x38201408;
    public static final int SCRIM = 0x8C1C150E;

    // Rules
    public static final int RULE = 0xFFD2C4A6;
    public static final int RULE_STRONG = 0xFFB9A682;

    // Ink
    public static final int INK = 0xFF2E261C;
    public static final int INK_SOFT = 0xFF5B4F3F;
    public static final int INK_MUTED = 0xFF82745D;
    public static final int INK_DISABLED = 0xFFA39781;
    public static final int ON_ACCENT = 0xFFF5F0E3;

    // One functional accent
    public static final int FOREST = 0xFF3E6243;
    public static final int FOREST_DARK = 0xFF2B4630;
    public static final int FOREST_HIGHLIGHT = 0xFF5F8163;
    public static final int FOREST_HOVER = 0xFF466D4B;

    // Secondary
    public static final int GOLD = 0xFF9C7B3C;
    public static final int GOLD_SOFT = 0xFFCDB57E;

    // Danger
    public static final int DANGER = 0xFF8C3A31;
    public static final int DANGER_DARK = 0xFF692A23;
    public static final int DANGER_HIGHLIGHT = 0xFFA65A50;

    // Banner screen: burgundy marks the selected destination and the one
    // primary action per view; it never means "danger" there (danger keeps
    // DANGER plus a glyph).
    public static final int BURGUNDY = 0xFF7A2E2A;
    public static final int BURGUNDY_DARK = 0xFF571E1B;
    public static final int BURGUNDY_HIGHLIGHT = 0xFF9A4A43;
    public static final int ON_BURGUNDY = 0xFFF4E9D8;
    // Outer frame only: dark walnut and iron.
    public static final int WALNUT = 0xFF4B3323;
    public static final int WALNUT_DARK = 0xFF24170F;
    public static final int WALNUT_LIGHT = 0xFF6E4B32;
    public static final int WALNUT_GRAIN = 0xFF3E2A1C;
    public static final int IRON = 0xFF3A3836;
    public static final int IRON_LIGHT = 0xFF7C776F;
    public static final int IRON_DARK = 0xFF1E1D1C;
    // Status accents; each is always paired with a glyph or word.
    public static final int STATUS_BLUE = 0xFF4A6A86;
    public static final int AMBER = 0xFFA87B2E;
    public static final int INSET = 0xFFE6DBC4;

    // States
    public static final int DISABLED_FILL = 0xFFDCD2BE;
    public static final int DISABLED_BORDER = 0xFFC4B79C;
    public static final int ROW_HOVER = 0x16603F1C;
    public static final int ROW_SELECTED = 0x1E3E6243;
    public static final int TRACK = 0xFFDCD0B6;
    public static final int FOCUS = 0xFF9C7B3C;

    private Ui2Palette() {
    }
}
