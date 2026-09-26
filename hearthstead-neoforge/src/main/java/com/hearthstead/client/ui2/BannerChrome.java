package com.hearthstead.client.ui2;

import com.hearthstead.client.ui.HearthMaterials;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The Banner screen's materials, all pixel fills: a dark walnut board with
 * iron corner brackets, parchment panels set into it (the map mat and the
 * right column), dark inset counter boxes, wooden nav plates and the two
 * burgundy banners -- the crest at the top left and the settlement banner
 * hanging at the right edge. Grain is deterministic; nothing is random per
 * frame and nothing allocates.
 */
public final class BannerChrome {
    public static final int TEXT_ON_WOOD = 0xFFF1E5CB;
    public static final int TEXT_ON_WOOD_MUTED = 0xFFBFAE8F;
    /** Idle label on a wooden nav plate (between muted and full text on wood). */
    public static final int TEXT_ON_WOOD_IDLE = 0xFFDCCDB0;
    public static final int PLATE = 0xFF3A2819;
    public static final int PLATE_HIGHLIGHT = 0xFF5B412D;
    public static final int PLATE_SHADOW = 0xFF1B110A;
    public static final int PLATE_HOVER = 0xFF46311F;
    public static final int GOLD_EDGE = 0xFFD9B880;
    public static final int INSET_DARK = 0xFF24170E;
    public static final int GRAIN_LIGHT = 0xFF56392A;
    /** Linen of the crest's device (tree, roundel). */
    public static final int LINEN = 0xFFEBDDC0;

    private BannerChrome() {
    }

    /** The whole board: shadow, walnut with grain, outer lip and iron corners. */
    public static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x + 3, y + h, x + w + 3, y + h + 3, Ui2Palette.SHADOW);
        g.fill(x + w, y + 3, x + w + 3, y + h, Ui2Palette.SHADOW);
        g.fill(x, y, x + w, y + h, Ui2Palette.WALNUT_DARK);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, Ui2Palette.WALNUT);
        grain(g, x + 1, y + 1, w - 2, h - 2);
        // Raised outer rail: light top/left, dark bottom/right, then an inner groove.
        int f = BannerSheetLayout.FRAME;
        g.fill(x + 1, y + 1, x + w - 1, y + 2, Ui2Palette.WALNUT_LIGHT);
        g.fill(x + 1, y + 1, x + 2, y + h - 1, Ui2Palette.WALNUT_LIGHT);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, PLATE_SHADOW);
        g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, PLATE_SHADOW);
        outline(g, x + f - 1, y + f - 1, w - (f - 1) * 2, h - (f - 1) * 2, PLATE_SHADOW);
        outline(g, x + f, y + f, w - f * 2, h - f * 2, 0x40F1E5CB);
        bracket(g, x, y, false, false);
        bracket(g, x + w, y, true, false);
        bracket(g, x, y + h, false, true);
        bracket(g, x + w, y + h, true, true);
    }

    /** Horizontal grain streaks, deterministic by position. */
    private static void grain(GuiGraphics g, int x, int y, int w, int h) {
        for (int row = 1; row < h - 1; row += 3) {
            int seed = row * 7919;
            int px = x + 2 + (seed >>> 3) % 17;
            while (px < x + w - 4) {
                seed = seed * 1103515245 + 12345;
                int len = 10 + ((seed >>> 16) & 31);
                int color = ((seed >>> 8) & 3) == 0 ? GRAIN_LIGHT : Ui2Palette.WALNUT_GRAIN;
                g.fill(px, y + row, Math.min(px + len, x + w - 2), y + row + 1, color);
                px += len + 6 + ((seed >>> 20) & 15);
            }
        }
    }

    private static void bracket(GuiGraphics g, int cx, int cy, boolean flipX, boolean flipY) {
        int arm = 14;
        int t = 5;
        plate(g, flipX ? cx - arm : cx, flipY ? cy - t : cy, arm, t);
        plate(g, flipX ? cx - t : cx, flipY ? cy - arm : cy, t, arm);
        rivet(g, flipX ? cx - arm + 2 : cx + arm - 4, flipY ? cy - 3 : cy + 2);
        rivet(g, flipX ? cx - 3 : cx + 2, flipY ? cy - arm + 2 : cy + arm - 4);
    }

    private static void plate(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, Ui2Palette.IRON_DARK);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, Ui2Palette.IRON);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, Ui2Palette.IRON_LIGHT);
        g.fill(x + 1, y + 1, x + 2, y + h - 1, Ui2Palette.IRON_LIGHT);
    }

    private static void rivet(GuiGraphics g, int x, int y) {
        g.fill(x, y, x + 2, y + 2, Ui2Palette.IRON_DARK);
        g.fill(x, y, x + 1, y + 1, Ui2Palette.IRON_LIGHT);
    }

    public static void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    /** A parchment panel set into the wood: dark lip, grain veil, light top edge, soft inner shade. */
    public static void parchment(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, PLATE_SHADOW);
        HearthMaterials.paper(g, x, y, w, h);
        g.fill(x, y, x + w, y + h, Ui2Palette.PAPER_TEXTURE_VEIL);
        g.fill(x, y, x + w, y + 1, Ui2Palette.FRAME_INNER);
        g.fill(x, y, x + 1, y + h, Ui2Palette.FRAME_INNER);
        g.fill(x, y + h - 1, x + w, y + h, 0x30201408);
        g.fill(x + w - 1, y, x + w, y + h, 0x30201408);
    }

    /** One dark inset box behind a header counter. */
    public static void counterBox(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, INSET_DARK);
        g.fill(x, y, x + w, y + 1, PLATE_SHADOW);
        g.fill(x, y, x + 1, y + h, PLATE_SHADOW);
        g.fill(x, y + h - 1, x + w, y + h, PLATE_HIGHLIGHT);
        g.fill(x + w - 1, y, x + w, y + h, PLATE_HIGHLIGHT);
    }

    /** A wooden nav plate; selected plates are burgundy with a light gold border. */
    public static void navPlate(GuiGraphics g, int x, int y, int w, int h, boolean selected, float hover) {
        if (selected) {
            g.fill(x, y, x + w, y + h, PLATE_SHADOW);
            outline(g, x + 1, y + 1, w - 2, h - 2, GOLD_EDGE);
            g.fill(x + 2, y + 2, x + w - 2, y + h - 2, Ui2Palette.BURGUNDY);
            g.fill(x + 2, y + 2, x + w - 2, y + 3, Ui2Palette.BURGUNDY_HIGHLIGHT);
            return;
        }
        g.fill(x, y, x + w, y + h, PLATE_SHADOW);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, hover > 0.5F ? PLATE_HOVER : PLATE);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, PLATE_HIGHLIGHT);
        g.fill(x + 1, y + 1, x + 2, y + h - 1, PLATE_HIGHLIGHT);
    }

    /** Burgundy banner with a gold hem, a gold tree device and a swallow-tail. */
    private static void bannerCloth(GuiGraphics g, int x, int top, int w, int bottom, int notch, int treeScale) {
        cloth(g, x, top, w, bottom, notch);
        tree(g, x + w / 2, top + 6 + treeScale, treeScale, LINEN);
    }

    /** The burgundy swallow-tail cloth with its gold hem, no device. */
    private static void cloth(GuiGraphics g, int x, int top, int w, int bottom, int notch) {
        g.fill(x + 2, top + 2, x + w + 2, bottom - notch + 2, 0x40100804);
        g.fill(x, top, x + w, bottom - notch, Ui2Palette.BURGUNDY_DARK);
        g.fill(x + 1, top, x + w - 1, bottom - notch, Ui2Palette.BURGUNDY);
        g.fill(x + 1, top, x + 2, bottom - notch, Ui2Palette.BURGUNDY_HIGHLIGHT);
        int half = w / 2;
        for (int i = 0; i < notch; i++) {
            int row = bottom - notch + i;
            int cut = i + 1;
            g.fill(x, row, x + half - cut, row + 1, Ui2Palette.BURGUNDY_DARK);
            g.fill(x + 1, row, x + half - cut, row + 1, Ui2Palette.BURGUNDY);
            g.fill(x + half + cut, row, x + w, row + 1, Ui2Palette.BURGUNDY_DARK);
            g.fill(x + half + cut, row, x + w - 1, row + 1, Ui2Palette.BURGUNDY);
        }
        int gold = Ui2Palette.GOLD_SOFT;
        g.fill(x + 2, top + 2, x + w - 2, top + 3, gold);
        g.fill(x + 2, top + 2, x + 3, bottom - notch - 1, gold);
        g.fill(x + w - 3, top + 2, x + w - 2, bottom - notch - 1, gold);
    }

    /** A small pixel tree device centred on {@code cx}; scale 1 or 2. */
    public static void tree(GuiGraphics g, int cx, int ty, int s, int color) {
        g.fill(cx - s, ty, cx + s, ty + s, color);
        g.fill(cx - 2 * s, ty + s, cx + 2 * s, ty + 2 * s, color);
        g.fill(cx - 3 * s, ty + 2 * s, cx + 3 * s, ty + 4 * s, color);
        g.fill(cx - 2 * s, ty + 4 * s, cx + 2 * s, ty + 5 * s, color);
        g.fill(cx - s / 2 - (s == 1 ? 1 : 0), ty + 5 * s, cx + s / 2 + 1, ty + 8 * s, color);
        g.fill(cx - 3 * s, ty + 8 * s, cx + 3 * s, ty + 8 * s + Math.max(1, s / 2), color);
    }

    /** The crest: a larger banner hanging from the top frame at the left. */
    public static void crest(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x - 3, y + 2, x + w + 3, y + 5, Ui2Palette.IRON_DARK);
        g.fill(x - 2, y + 3, x + w + 2, y + 4, Ui2Palette.IRON_LIGHT);
        bannerCloth(g, x, y + 5, w, y + h, 7, w >= 24 ? 2 : 1);
    }

    /**
     * The crest without the tree: iron rod and cloth only, for a caller that
     * paints its own device (the settler sheet puts the job icon there).
     * Returns the cloth's top edge.
     */
    public static int crestCloth(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x - 3, y + 2, x + w + 3, y + 5, Ui2Palette.IRON_DARK);
        g.fill(x - 2, y + 3, x + w + 2, y + 4, Ui2Palette.IRON_LIGHT);
        cloth(g, x, y + 5, w, y + h, 7);
        return y + 5;
    }

    /** The settlement banner hanging from an iron rod on the right edge. */
    public static void hangingBanner(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x - 3, y + 1, x + w + 3, y + 4, Ui2Palette.IRON_DARK);
        g.fill(x - 2, y + 2, x + w + 2, y + 3, Ui2Palette.IRON_LIGHT);
        g.fill(x - 4, y, x - 2, y + 5, Ui2Palette.IRON_DARK);
        g.fill(x + w + 2, y, x + w + 4, y + 5, Ui2Palette.IRON_DARK);
        bannerCloth(g, x, y + 4, w, y + h, 6, 1);
    }
}
