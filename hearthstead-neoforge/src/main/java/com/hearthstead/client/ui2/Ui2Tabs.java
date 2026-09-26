package com.hearthstead.client.ui2;

import com.hearthstead.client.ui.HsMotion;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * UI2 text tabs with one 2px forest underline that slides between tabs.
 *
 * <p>The indicator lives on this object, which the screen keeps across
 * widget rebuilds; the {@link Tab} buttons are recreated freely. The slide is
 * an eased 160 ms tween using {@link HsMotion#easeOutCubic}; with motion
 * disabled it snaps.
 */
public final class Ui2Tabs {
    public static final int HEIGHT = 14;
    public static final int GAP = 12;
    private static final long SLIDE_MS = 160L;

    private float fromX = Float.NaN;
    private float fromW;
    private float toX;
    private float toW;
    private long start;

    /** Measured x offsets for {@code labels}, starting at {@code x}; last entry is the end. */
    public static int[] positions(Font font, Component[] labels, boolean[] external, int x) {
        int[] out = new int[labels.length + 1];
        int cursor = x;
        for (int i = 0; i < labels.length; i++) {
            out[i] = cursor;
            cursor += tabWidth(font, labels[i], external[i]) + GAP;
        }
        out[labels.length] = cursor - GAP;
        return out;
    }

    public static int tabWidth(Font font, Component label, boolean external) {
        return font.width(label) + 4 + (external ? 7 : 0);
    }

    /** Moves the indicator target; a new target starts from the current position. */
    public void target(int x, int w) {
        long now = Util.getMillis();
        if (Float.isNaN(fromX) || !HsMotion.enabled) {
            fromX = toX = x;
            fromW = toW = w;
            start = now;
            return;
        }
        if (x == Math.round(toX) && w == Math.round(toW)) return;
        float p = progress(now);
        fromX = fromX + (toX - fromX) * p;
        fromW = fromW + (toW - fromW) * p;
        toX = x;
        toW = w;
        start = now;
    }

    /** Hides the indicator (no tab selected) without losing the next slide origin. */
    public void render(GuiGraphics g, int y) {
        if (Float.isNaN(fromX)) return;
        float p = progress(Util.getMillis());
        int x = Math.round(fromX + (toX - fromX) * p);
        int w = Math.round(fromW + (toW - fromW) * p);
        g.fill(x, y, x + w, y + 2, Ui2Palette.FOREST);
    }

    private float progress(long now) {
        return HsMotion.easeOutCubic((now - start) / (float) SLIDE_MS);
    }

    /** One text tab; paints only its label (and an external-window glyph). */
    public static final class Tab extends AbstractButton {
        private final boolean selected;
        private final boolean external;
        private final Runnable onPress;
        private final HsMotion.Tween hover = new HsMotion.Tween();

        public Tab(int x, int y, int w, Component label, boolean selected, boolean external,
                   Runnable onPress) {
            super(x, y, w, HEIGHT, label);
            this.selected = selected;
            this.external = external;
            this.onPress = onPress;
        }

        public boolean selected() {
            return selected;
        }

        @Override
        public void onPress() {
            onPress.run();
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            Font font = Minecraft.getInstance().font;
            float h = hover.update(active && isHoveredOrFocused());
            int color = !active ? Ui2Palette.INK_DISABLED
                : selected ? Ui2Palette.INK
                : h > 0.5F ? Ui2Palette.INK_SOFT : Ui2Palette.INK_MUTED;
            int textY = getY() + 2;
            g.drawString(font, getMessage(), getX() + 2, textY, color, false);
            if (external) {
                Ui2Surface.externalGlyph(g, getX() + 4 + font.width(getMessage()), textY, color);
            }
            if (!selected && h > 0.01F) {
                int w = Math.round((getWidth() - 4) * h);
                g.fill(getX() + 2, getY() + HEIGHT - 2, getX() + 2 + w, getY() + HEIGHT - 1,
                    Ui2Palette.RULE_STRONG);
            }
            if (isFocused() && !isHovered()) Ui2Surface.focus(g, getX(), getY(), getWidth(), HEIGHT - 1);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
