package com.hearthstead.client.ui2;

import com.hearthstead.client.ui.HearthMaterials;
import com.hearthstead.client.ui.HsMotion;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

/**
 * UI2 surfaces and small primitives. Everything is pixel-aligned fills; the
 * only texture is a heavily veiled paper grain on the single sheet.
 *
 * <p>Composition rules: one sheet per screen, hairline rules instead of
 * boxed cards, rows instead of tiles, and state glyphs next to every state
 * colour so nothing is communicated by colour alone.
 */
public final class Ui2Surface {
    private Ui2Surface() {
    }

    /** The one parchment sheet: 2px soft shadow, 1px warm frame, 1px inner light. */
    public static void sheet(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x + 2, y + h, x + w + 2, y + h + 2, Ui2Palette.SHADOW);
        g.fill(x + w, y + 2, x + w + 2, y + h, Ui2Palette.SHADOW);
        g.fill(x, y, x + w, y + h, Ui2Palette.FRAME);
        HearthMaterials.paper(g, x + 1, y + 1, w - 2, h - 2);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, Ui2Palette.PAPER_TEXTURE_VEIL);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, Ui2Palette.FRAME_INNER);
    }

    /** Dim the world behind a modal sheet. */
    public static void scrim(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, Ui2Palette.SCRIM);
    }

    public static void rule(GuiGraphics g, int x, int y, int w) {
        g.fill(x, y, x + w, y + 1, Ui2Palette.RULE);
    }

    public static void ruleVertical(GuiGraphics g, int x, int y, int h) {
        g.fill(x, y, x + 1, y + h, Ui2Palette.RULE);
    }

    /** Upper-case caption followed by a hairline that runs to {@code x + w}. */
    public static void sectionHeader(GuiGraphics g, Font font, Component label, int x, int y, int w) {
        g.drawString(font, label, x, y, Ui2Palette.INK_MUTED, false);
        int ruleX = x + font.width(label) + 6;
        if (ruleX < x + w) rule(g, ruleX, y + 4, x + w - ruleX);
    }

    /** List row background: hover tint and a 2px forest bar when selected. */
    public static void row(GuiGraphics g, int x, int y, int w, int h, float hover, boolean selected) {
        if (selected) {
            g.fill(x, y, x + w, y + h, Ui2Palette.ROW_SELECTED);
            g.fill(x, y, x + 2, y + h, Ui2Palette.FOREST);
        } else if (hover > 0.01F) {
            int alpha = Math.round(((Ui2Palette.ROW_HOVER >>> 24) & 0xFF) * Mth.clamp(hover, 0.0F, 1.0F));
            g.fill(x, y, x + w, y + h, (alpha << 24) | (Ui2Palette.ROW_HOVER & 0xFFFFFF));
        }
    }

    /** Quiet outlined chip; returns its width. */
    public static int badge(GuiGraphics g, Font font, Component text, int x, int y, int color) {
        int w = font.width(text) + 6;
        g.fill(x, y, x + w, y + 11, Ui2Palette.PAPER_DEEP);
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + 10, x + w, y + 11, color);
        g.fill(x, y, x + 1, y + 11, color);
        g.fill(x + w - 1, y, x + w, y + 11, color);
        g.drawString(font, text, x + 3, y + 2, color, false);
        return w;
    }

    /** Thin 2px progress bar; the fill eases toward the new value with HsMotion. */
    public static void progress(GuiGraphics g, int x, int y, int w, float ratio, int color) {
        float shown = HsMotion.smoothBar(x, y, w, 2, Mth.clamp(ratio, 0.0F, 1.0F));
        g.fill(x, y, x + w, y + 2, Ui2Palette.TRACK);
        int filled = Math.round(w * shown);
        if (filled > 0) g.fill(x, y, x + filled, y + 2, color);
    }

    /** Item icon without a box, optionally scaled (16px base). */
    public static void icon(GuiGraphics g, ItemStack stack, int x, int y, int size) {
        if (size == 16) {
            g.renderItem(stack, x, y);
            return;
        }
        float scale = size / 16.0F;
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(scale, scale, 1.0F);
        g.renderItem(stack, 0, 0);
        g.pose().popPose();
    }

    /** Sunken 18px inventory well for real container slots. */
    public static void slotWell(GuiGraphics g, int x, int y) {
        g.fill(x, y, x + 18, y + 18, Ui2Palette.PAPER_DEEP);
        g.fill(x, y, x + 18, y + 1, Ui2Palette.RULE_STRONG);
        g.fill(x, y, x + 1, y + 18, Ui2Palette.RULE_STRONG);
        g.fill(x, y + 17, x + 18, y + 18, Ui2Palette.FRAME_INNER);
        g.fill(x + 17, y, x + 18, y + 18, Ui2Palette.FRAME_INNER);
    }

    /** Keyboard focus: a 1px gold dotted underline, never a glow. */
    public static void focus(GuiGraphics g, int x, int y, int w, int h) {
        for (int px = x; px < x + w; px += 2) g.fill(px, y + h, px + 1, y + h + 1, Ui2Palette.FOCUS);
        for (int px = x; px < x + w; px += 2) g.fill(px, y - 1, px + 1, y, Ui2Palette.FOCUS);
    }

    public static void scrollbar(GuiGraphics g, int x, int y, int h, float visible, float position) {
        g.fill(x, y, x + 1, y + h, Ui2Palette.RULE);
        int thumb = Math.max(8, Math.min(h, Math.round(h * visible)));
        int offset = Math.round((h - thumb) * Mth.clamp(position, 0.0F, 1.0F));
        g.fill(x - 1, y + offset, x + 2, y + offset + thumb, Ui2Palette.RULE_STRONG);
    }

    // ------------------------------------------------------------ glyphs ---
    // 5-7px pixel glyphs pair every state colour with a shape.

    /** Padlock: disabled / locked action. */
    public static void lockGlyph(GuiGraphics g, int x, int y, int color) {
        g.fill(x + 1, y, x + 4, y + 1, color);
        g.fill(x + 1, y, x + 2, y + 3, color);
        g.fill(x + 3, y, x + 4, y + 3, color);
        g.fill(x, y + 3, x + 5, y + 7, color);
    }

    /** Filled diamond: needs you / problem. */
    public static void alertGlyph(GuiGraphics g, int x, int y, int color) {
        g.fill(x + 2, y, x + 3, y + 1, color);
        g.fill(x + 1, y + 1, x + 4, y + 2, color);
        g.fill(x, y + 2, x + 5, y + 3, color);
        g.fill(x + 1, y + 3, x + 4, y + 4, color);
        g.fill(x + 2, y + 4, x + 3, y + 5, color);
    }

    /** Hollow diamond: waiting / in progress. */
    public static void pendingGlyph(GuiGraphics g, int x, int y, int color) {
        g.fill(x + 2, y, x + 3, y + 1, color);
        g.fill(x + 1, y + 1, x + 2, y + 2, color);
        g.fill(x + 3, y + 1, x + 4, y + 2, color);
        g.fill(x, y + 2, x + 1, y + 3, color);
        g.fill(x + 4, y + 2, x + 5, y + 3, color);
        g.fill(x + 1, y + 3, x + 2, y + 4, color);
        g.fill(x + 3, y + 3, x + 4, y + 4, color);
        g.fill(x + 2, y + 4, x + 3, y + 5, color);
    }

    /** Tick: settled / fine. */
    public static void checkGlyph(GuiGraphics g, int x, int y, int color) {
        g.fill(x, y + 2, x + 1, y + 3, color);
        g.fill(x + 1, y + 3, x + 2, y + 4, color);
        g.fill(x + 2, y + 4, x + 3, y + 5, color);
        g.fill(x + 3, y + 3, x + 4, y + 4, color);
        g.fill(x + 4, y + 2, x + 5, y + 3, color);
        g.fill(x + 5, y + 1, x + 6, y + 2, color);
        g.fill(x + 6, y, x + 7, y + 1, color);
    }

    /** Small north-east arrow: this destination opens another window. */
    public static void externalGlyph(GuiGraphics g, int x, int y, int color) {
        g.fill(x + 1, y, x + 4, y + 1, color);
        g.fill(x + 3, y, x + 4, y + 3, color);
        g.fill(x + 2, y + 1, x + 3, y + 2, color);
        g.fill(x + 1, y + 2, x + 2, y + 3, color);
        g.fill(x, y + 3, x + 1, y + 4, color);
    }
}
