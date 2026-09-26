package com.hearthstead.client.ui2;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;

/**
 * Serif small caps for titles and section headings only (Droid Serif Bold,
 * Apache-2.0, see assets/hearthstead/font/readme-fonts.txt).
 *
 * <p>Minecraft samples TrueType glyphs with nearest filtering, so a glyph
 * is only crisp when its oversample equals the GUI scale. One font
 * definition exists per scale (1..6) and this class picks the matching one.
 * Every size shares the vanilla baseline (y + 7), so a larger initial and
 * smaller following capitals line up without offsets: true small caps.
 *
 * <p>A {@link Text} caches its styled component and width and is rebuilt
 * only when its source string or the GUI scale changes.
 */
public final class Ui2Serif {
    public enum Size {
        /** Settlement name: 14px initials over 11px capitals. */
        TITLE("cap", "mid"),
        /** Section headings: 11px initials over 9px capitals. */
        HEADING("mid", "small"),
        /** Display title: 14px serif, the text's own case (no small caps). */
        DISPLAY("cap", "cap");

        final String big;
        final String small;

        Size(String big, String small) {
            this.big = big;
            this.small = small;
        }
    }

    private Ui2Serif() {
    }

    public static int guiScale() {
        return Math.max(1, Math.min(6, (int) Math.round(Minecraft.getInstance().getWindow().getGuiScale())));
    }

    static ResourceLocation font(String kind, int scale) {
        return ResourceLocation.fromNamespaceAndPath("hearthstead", "serif_" + kind + "_" + scale);
    }

    /** Builds small caps: each word's first letter at the big size, the rest upper-cased small. */
    public static Component smallCaps(String text, Size size, int scale) {
        if (size == Size.DISPLAY) {
            return Component.literal(text).withStyle(Style.EMPTY.withFont(font(size.big, scale)));
        }
        Style big = Style.EMPTY.withFont(font(size.big, scale));
        Style small = Style.EMPTY.withFont(font(size.small, scale));
        MutableComponent out = Component.empty();
        StringBuilder run = new StringBuilder();
        boolean wordStart = true;
        boolean runBig = false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            boolean letter = Character.isLetterOrDigit(cp);
            boolean isBig = letter && wordStart;
            if (run.length() > 0 && isBig != runBig) {
                out.append(Component.literal(run.toString()).withStyle(runBig ? big : small));
                run.setLength(0);
            }
            runBig = isBig;
            run.append(new String(Character.toChars(cp)).toUpperCase(Locale.ROOT));
            wordStart = !letter && cp != '\'';
        }
        if (run.length() > 0) out.append(Component.literal(run.toString()).withStyle(runBig ? big : small));
        return out;
    }

    /** A cached small-caps line. */
    public static final class Text {
        private final Size size;
        private String source;
        private int scale;
        private Component component = Component.empty();
        private int width;
        private String fitSource;
        private int fitWidth = -1;
        private boolean truncated;

        public Text(Size size) {
            this.size = size;
        }

        /** Updates the text; cheap when nothing changed. */
        public Text set(Font font, String text) {
            String safe = text == null ? "" : text;
            int s = guiScale();
            if (!safe.equals(source) || s != scale) {
                source = safe;
                scale = s;
                component = smallCaps(safe, size, s);
                width = font.width(component);
            }
            return this;
        }

        /** Shortens the source with an ellipsis until it fits {@code maxWidth}. */
        public Text fit(Font font, String text, int maxWidth) {
            int s = guiScale();
            if (text != null && text.equals(fitSource) && maxWidth == fitWidth && s == scale) return this;
            fitSource = text;
            fitWidth = maxWidth;
            truncated = false;
            set(font, text);
            if (width <= maxWidth || text == null) return this;
            truncated = true;
            String base = text;
            while (base.length() > 1) {
                base = base.substring(0, base.length() - 1).stripTrailing();
                set(font, base + "…");
                if (width <= maxWidth) break;
            }
            return this;
        }

        public int width() {
            return width;
        }

        /** True when the last {@link #fit} shortened the text: show the full text as a tooltip. */
        public boolean truncated() {
            return truncated;
        }

        public Component component() {
            return component;
        }

        public void draw(GuiGraphics g, Font font, int x, int y, int color) {
            g.drawString(font, component, x, y, color, false);
        }
    }
}
