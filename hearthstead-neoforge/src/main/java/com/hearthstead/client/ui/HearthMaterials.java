package com.hearthstead.client.ui;

import com.hearthstead.Hearthstead;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * Samples the installed 1254px four-material atlas. Public methods preserve
 * caller geometry; they only replace the old flat fills with carved wood,
 * calm paper, moss action surfaces, and a red destructive surface.
 */
public final class HearthMaterials {
    private static final ResourceLocation ATLAS = ResourceLocation.fromNamespaceAndPath(
        Hearthstead.MODID, "textures/gui/materials/premium_atlas.png");
    private static final int ATLAS_SIZE = 1254;
    private static final int QUADRANT = 627;
    private static final int PAPER_OVERLAY = 0x60F3EBD6;

    private HearthMaterials() {
    }

    public static void frame(GuiGraphics g, int x, int y, int w, int h) {
        sample(g, x, y, w, h, 0, 0);
        g.fill(x, y, x + w, y + 1, 0xFF20170F);
        g.fill(x, y + h - 1, x + w, y + h, 0xFF20170F);
        g.fill(x, y, x + 1, y + h, 0xFF20170F);
        g.fill(x + w - 1, y, x + w, y + h, 0xFF20170F);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, 0xFF9A7045);
        paper(g, x + 5, y + 5, Math.max(0, w - 10), Math.max(0, h - 10));
        // Fixed five-pixel caps keep the wood corners legible at every GUI scale.
        for (int capX : new int[] {x, x + Math.max(0, w - 5)}) {
            for (int capY : new int[] {y, y + Math.max(0, h - 5)}) {
                sample(g, capX, capY, 5, 5, 0, 0);
                g.fill(capX + 1, capY + 1, capX + 4, capY + 2, 0xFFA97C4C);
                g.fill(capX + 3, capY + 2, capX + 4, capY + 4, 0xFF392514);
            }
        }
    }

    public static void paper(GuiGraphics g, int x, int y, int w, int h) {
        sample(g, x, y, w, h, QUADRANT, 0);
        g.fill(x, y, x + w, y + h, PAPER_OVERLAY);
    }

    public static void panel(GuiGraphics g, int x, int y, int w, int h) {
        paper(g, x, y, w, h);
        g.fill(x, y, x + w, y + 1, 0xFFAB9D80);
        g.fill(x, y, x + 1, y + h, 0xFFAB9D80);
        g.fill(x, y + h - 1, x + w, y + h, 0xFFD6C8AC);
        g.fill(x + w - 1, y, x + w, y + h, 0xFFD6C8AC);
    }

    public static void header(GuiGraphics g, int x, int y, int w, int h) {
        sample(g, x, y, w, h, 0, QUADRANT);
        g.fill(x, y, x + w, y + h, 0xCC304825);
        g.fill(x, y, x + w, y + 2, 0x667E9E70);
    }

    public static void button(GuiGraphics g, int x, int y, int w, int h,
                              boolean primary, boolean danger, boolean hovered,
                              boolean active) {
        if (!active || (!primary && !danger)) {
            panel(g, x, y, w, h);
            if (!active) g.fill(x, y, x + w, y + h, 0x885A6054);
            return;
        }
        sample(g, x, y, w, h, danger ? QUADRANT : 0, QUADRANT);
        g.fill(x, y, x + w, y + h, danger ? 0x99501C1A : 0xBB38582A);
        if (hovered) g.fill(x, y, x + w, y + h, danger ? 0x33975A49 : 0x335C7A58);
        g.fill(x, y, x + w, y + 1, primary ? 0xFF9DBD8D : 0xFFB3956B);
        g.fill(x, y + h - 2, x + w, y + h, danger ? 0xFF482723 : 0xFF29442F);
    }

    /** Compatibility overload for existing local PixelButton surfaces. */
    public static void button(GuiGraphics g, int x, int y, int w, int h,
                              boolean primary, boolean hovered) {
        button(g, x, y, w, h, primary, false, hovered, true);
    }

    public static void slot(GuiGraphics g, int x, int y) {
        paper(g, x, y, 18, 18);
        g.fill(x, y, x + 18, y + 1, 0xFF594432);
        g.fill(x, y, x + 1, y + 18, 0xFF594432);
        g.fill(x + 17, y + 1, x + 18, y + 18, 0xFFFFF7E5);
        g.fill(x + 1, y + 17, x + 18, y + 18, 0xFFFFF7E5);
    }

    private static void sample(GuiGraphics g, int x, int y, int w, int h, int u, int v) {
        if (w <= 0 || h <= 0) return;
        // Repeat complete quadrants at an isotropic logical scale; wide windows
        // must never sample the adjacent material or stretch its pixel grain.
        final int tile = 128;
        for (int dy = 0; dy < h; dy += tile) {
            for (int dx = 0; dx < w; dx += tile) {
                int dw = Math.min(tile, w - dx);
                int dh = Math.min(tile, h - dy);
                g.blit(ATLAS, x + dx, y + dy, dw, dh, (float) u, (float) v,
                    Math.round(dw * (float) QUADRANT / tile),
                    Math.round(dh * (float) QUADRANT / tile), ATLAS_SIZE, ATLAS_SIZE);
            }
        }
    }
}
