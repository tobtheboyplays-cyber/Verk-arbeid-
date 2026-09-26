package com.hearthstead.client.ui2;

import com.hearthstead.client.ui2.BannerSheetLayout.Rect;

/**
 * Pure geometry for the standard Bannerhold window (screen pixels). Every
 * screen that is not the Banner itself uses this one frame, so the walnut
 * board, crest, serif title, close key and parchment page sit in exactly the
 * same places on every tab and screen.
 *
 * <pre>
 *  +--walnut board (FRAME 6)-------------------------------------+
 *  | [crest] TITLE IN SERIF SMALL CAPS                      [x]  |  header (20, or 28 with subtitle)
 *  |         optional subtitle                                   |
 *  |  ---------------------------------------------------------  |  header rule (2 px)
 *  |  +-parchment page-----------------------------------------+ |
 *  |  |  content (page inset PAD 8)                            | |
 *  |  |  ...                                                   | |
 *  |  |  footer: one BUTTON_H action row                       | |
 *  |  +--------------------------------------------------------+ |
 *  +-------------------------------------------------------------+
 * </pre>
 *
 * <p>One spacing scale: {@link #S} 4, {@link #M} 8, {@link #L} 12, {@link #XL} 16.
 * Metrics shared with the Banner: frame 6, margin 6, gutter 8, 11px close key,
 * 20px primary buttons, 12px text buttons, 14px list rows and tabs.
 */
public record Ui2FrameLayout(int x, int y, int width, int height, boolean subtitle,
                             Rect crest, Rect header, Rect title, Rect close, int ruleY,
                             Rect page, Rect content) {
    public static final int S = 4;
    public static final int M = 8;
    public static final int L = 12;
    public static final int XL = 16;

    public static final int FRAME = BannerSheetLayout.FRAME;
    public static final int MARGIN = BannerSheetLayout.MARGIN;
    public static final int GUTTER = BannerSheetLayout.GUTTER;
    public static final int PAD = BannerSheetLayout.PANEL_PAD;
    public static final int HEADER_H = 20;
    public static final int HEADER_H_SUBTITLE = BannerSheetLayout.HEADER_H;
    public static final int CLOSE = 11;
    /** Filled (primary/danger/banner) button height. */
    public static final int BUTTON_H = 20;
    /** Secondary text-button height. */
    public static final int TEXT_BUTTON_H = 12;
    /** One list row. */
    public static final int ROW_H = 14;
    /** Text tabs (see {@link Ui2Tabs#HEIGHT}). */
    public static final int TABS_H = Ui2Tabs.HEIGHT;
    /** Keep this far from the viewport edge. */
    public static final int VIEWPORT_MARGIN = 8;
    public static final int MIN_WIDTH = 200;
    public static final int MIN_HEIGHT = 120;

    /** The frame centred in the viewport at its preferred size, shrunk to fit. */
    public static Ui2FrameLayout centred(int viewportW, int viewportH, int preferredW, int preferredH,
                                         boolean subtitle) {
        int w = fit(preferredW, viewportW);
        int h = fit(preferredH, viewportH);
        return at((viewportW - w) / 2, (viewportH - h) / 2, w, h, subtitle);
    }

    /** Largest size that fits {@code viewport} with the standard margin. */
    public static int fit(int preferred, int viewport) {
        return Math.max(1, Math.min(preferred, viewport - VIEWPORT_MARGIN * 2));
    }

    public static Ui2FrameLayout at(int x, int y, int w, int h, boolean subtitle) {
        int headerH = subtitle ? HEADER_H_SUBTITLE : HEADER_H;
        Rect header = new Rect(x + FRAME + MARGIN, y + FRAME + S, w - (FRAME + MARGIN) * 2, headerH);
        // The crest hangs from the top frame over the header, like the Banner's.
        int crestW = subtitle ? 26 : 22;
        Rect crest = new Rect(header.x(), y - 2, crestW, headerH + 10);
        Rect close = new Rect(header.right() - CLOSE, header.y() + 1, CLOSE, CLOSE);
        int titleX = crest.right() + GUTTER;
        Rect title = new Rect(titleX, header.y(), Math.max(1, close.x() - GUTTER - titleX), headerH);
        int ruleY = header.bottom() + 2;
        int pageTop = ruleY + S;
        Rect page = new Rect(header.x(), pageTop, header.width(), Math.max(1, y + h - FRAME - S - pageTop));
        Rect content = inset(page, PAD, PAD);
        return new Ui2FrameLayout(x, y, w, h, subtitle, crest, header, title, close, ruleY, page, content);
    }

    public static Rect inset(Rect r, int dx, int dy) {
        return new Rect(r.x() + dx, r.y() + dy, Math.max(1, r.width() - dx * 2), Math.max(1, r.height() - dy * 2));
    }

    public int right() {
        return x + width;
    }

    public int bottom() {
        return y + height;
    }

    /** The action row at the bottom of the content area. */
    public Rect footer() {
        return new Rect(content.x(), content.bottom() - BUTTON_H, content.width(), BUTTON_H);
    }

    /** Text tabs at the top of the content area. */
    public Rect tabs() {
        return new Rect(content.x(), content.y(), content.width(), TABS_H);
    }

    /** Content between the optional tabs and the optional footer, with M gaps. */
    public Rect body(boolean withTabs, boolean withFooter) {
        int top = content.y() + (withTabs ? TABS_H + M : 0);
        int bottom = content.bottom() - (withFooter ? BUTTON_H + M : 0);
        return new Rect(content.x(), top, content.width(), Math.max(1, bottom - top));
    }

    /**
     * Buttons laid out right-aligned in the footer, {@code M} apart, each at
     * least {@code minW} wide; returns their rects left to right. Buttons
     * shrink evenly if the footer is too narrow.
     */
    public Rect[] footerButtons(int count, int minW) {
        Rect f = footer();
        Rect[] out = new Rect[count];
        if (count <= 0) return out;
        int w = Math.min(minW, (f.width() - M * (count - 1)) / count);
        int x = f.right() - count * w - (count - 1) * M;
        for (int i = 0; i < count; i++) out[i] = new Rect(x + i * (w + M), f.y(), w, BUTTON_H);
        return out;
    }

    /** True when {@code r} lies wholly inside the window (for clip tests). */
    public boolean contains(Rect r) {
        return r.x() >= x && r.y() >= y && r.right() <= x + width && r.bottom() <= y + height;
    }
}
