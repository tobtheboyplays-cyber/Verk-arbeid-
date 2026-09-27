package com.hearthstead.client.ui2;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.Locale;

/**
 * UI2 type scale on the Minecraft font. Only three steps exist:
 * TITLE (1.25x, short headings only), BODY (1x ink) and CAPTION (1x muted).
 * Section labels are upper-case captions; nothing is drawn below 1x.
 */
public final class Ui2Type {
    public static final float TITLE_SCALE = 1.25F;
    public static final int LINE = 9;
    public static final int TITLE_LINE = 11;

    private Ui2Type() {
    }

    public static int titleWidth(Font font, Component text) {
        return Math.round(font.width(text) * TITLE_SCALE);
    }

    public static void title(GuiGraphics g, Font font, Component text, int x, int y, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(TITLE_SCALE, TITLE_SCALE, 1.0F);
        g.drawString(font, text, 0, 0, color, false);
        g.pose().popPose();
    }

    public static void body(GuiGraphics g, Font font, Component text, int x, int y) {
        g.drawString(font, text, x, y, Ui2Palette.INK, false);
    }

    public static void body(GuiGraphics g, Font font, FormattedCharSequence text, int x, int y, int color) {
        g.drawString(font, text, x, y, color, false);
    }

    public static void caption(GuiGraphics g, Font font, Component text, int x, int y) {
        g.drawString(font, text, x, y, Ui2Palette.INK_MUTED, false);
    }

    public static void right(GuiGraphics g, Font font, Component text, int rightX, int y, int color) {
        g.drawString(font, text, rightX - font.width(text), y, color, false);
    }

    /** Upper-case caption used for section labels; the caller fits it first. */
    public static Component label(String text) {
        return Component.literal(text.toUpperCase(Locale.ROOT));
    }
}
