package com.hearthstead.client.ui2;

import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * The standard Bannerhold window, painted from a {@link Ui2FrameLayout}:
 * the Banner's walnut board with iron corners, the burgundy crest, a serif
 * small-caps title on the wood, the header rule, the 11px wooden close key
 * and one parchment page. Screens paint their content on the page with ink
 * colours from {@link Ui2Palette} and use {@link Ui2Button}, {@link Ui2Tabs},
 * {@link Ui2RowButton} and {@link Ui2Tips} for everything interactive.
 */
public final class Ui2Frame {
    private Ui2Frame() {
    }

    /** Board, header rule, parchment page and crest. Call first in render(). */
    public static void draw(GuiGraphics g, Ui2FrameLayout l) {
        board(g, l);
        Rect p = l.page();
        BannerChrome.parchment(g, p.x(), p.y(), p.width(), p.height());
        crest(g, l);
    }

    /** Board and header rule only, for screens that paint their own page panels. */
    public static void board(GuiGraphics g, Ui2FrameLayout l) {
        BannerChrome.panel(g, l.x(), l.y(), l.width(), l.height());
        Rect h = l.header();
        g.fill(h.x(), l.ruleY(), h.right(), l.ruleY() + 1, BannerChrome.PLATE_SHADOW);
        g.fill(h.x(), l.ruleY() + 1, h.right(), l.ruleY() + 2, BannerChrome.PLATE_HIGHLIGHT);
    }

    public static void crest(GuiGraphics g, Ui2FrameLayout l) {
        Rect c = l.crest();
        BannerChrome.crest(g, c.x(), c.y(), c.width(), c.height());
    }

    /**
     * Serif small-caps title on the wood (ellipsised to the title rect), plus
     * an optional muted subtitle line when the layout reserved one. Title-fit
     * rule: never clip; shorten with an ellipsis and let
     * {@link #titleTooltip} show the full text on hover.
     */
    public static void title(GuiGraphics g, Font font, Ui2FrameLayout l, Ui2Serif.Text cache, String text,
                             Component subtitle) {
        Rect t = l.title();
        cache.fit(font, text == null || text.isEmpty() ? " " : text, t.width());
        cache.draw(g, font, t.x(), t.y() + (l.subtitle() ? 6 : 7), BannerChrome.TEXT_ON_WOOD);
        if (l.subtitle() && subtitle != null) {
            Component line = com.hearthstead.client.ui.HsUi.fitLabel(font, subtitle, t.width()).text();
            g.drawString(font, line, t.x(), t.y() + 17, BannerChrome.TEXT_ON_WOOD_MUTED, false);
        }
    }

    /**
     * Full title (and subtitle) as a tooltip while the pointer is over a
     * shortened title. Call after the widgets so it draws on top.
     */
    public static void titleTooltip(GuiGraphics g, Font font, Ui2FrameLayout l, Ui2Serif.Text cache, String text,
                                    Component subtitle, int mouseX, int mouseY) {
        Rect t = l.title();
        if (!t.contains(mouseX, mouseY)) return;
        boolean subtitleCut = l.subtitle() && subtitle != null && font.width(subtitle) > t.width();
        if (!cache.truncated() && !subtitleCut) return;
        java.util.List<Component> lines = new java.util.ArrayList<>();
        lines.add(Component.literal(text));
        if (subtitle != null && l.subtitle()) lines.add(subtitle.copy().withStyle(net.minecraft.ChatFormatting.GRAY));
        g.renderComponentTooltip(font, lines, mouseX, mouseY);
    }

    /** The standard close key ("×", tooltip "Close (Esc)") at the layout's close rect. */
    public static Ui2WoodKey closeKey(Ui2FrameLayout l, Runnable onClose) {
        return closeKey(l, Component.literal("Close (Esc)"), onClose);
    }

    public static Ui2WoodKey closeKey(Ui2FrameLayout l, Component tooltip, Runnable onClose) {
        Rect c = l.close();
        Ui2WoodKey key = new Ui2WoodKey(c.x(), c.y(), c.width(), c.height(), Component.literal("×"), onClose);
        key.setTooltip(Tooltip.create(tooltip));
        return key;
    }

    /** Dim the world behind a modal window. */
    public static void scrim(GuiGraphics g, int screenW, int screenH) {
        Ui2Surface.scrim(g, 0, 0, screenW, screenH);
    }

    /** A section heading on the page: serif small caps in soft ink, then a hairline to {@code x + w}. */
    public static void heading(GuiGraphics g, Font font, Ui2Serif.Text cache, String text, int x, int y, int w) {
        cache.set(font, text);
        cache.draw(g, font, x, y + 2, Ui2Palette.INK_SOFT);
        int rx = x + cache.width() + 5;
        if (rx < x + w) Ui2Surface.rule(g, rx, y + 6, x + w - rx);
    }

    /**
     * A quiet status block on the page: a 2px state bar, a state glyph and
     * a muted label; the caller writes the message lines inside {@code r}
     * from {@code r.x() + 12}. Never a coloured box.
     */
    public static void status(GuiGraphics g, Font font, Rect r, Component label, Tone tone) {
        g.fill(r.x(), r.y(), r.right(), r.bottom(), Ui2Palette.INSET);
        g.fill(r.x(), r.y(), r.x() + 2, r.bottom(), tone.color);
        int gx = r.x() + 5;
        int gy = r.y() + 5;
        switch (tone) {
            case GOOD -> Ui2Surface.checkGlyph(g, gx, gy, tone.color);
            case BAD -> Ui2Surface.alertGlyph(g, gx, gy, tone.color);
            default -> Ui2Surface.pendingGlyph(g, gx, gy, tone.color);
        }
        g.drawString(font, label, r.x() + 14, r.y() + 4, Ui2Palette.INK_MUTED, false);
    }

    /** State colours on parchment; each is always paired with a glyph or word. */
    public enum Tone {
        GOOD(Ui2Palette.FOREST),
        WAIT(Ui2Palette.AMBER),
        NEUTRAL(Ui2Palette.GOLD),
        BAD(Ui2Palette.DANGER);

        public final int color;

        Tone(int color) {
            this.color = color;
        }
    }
}
