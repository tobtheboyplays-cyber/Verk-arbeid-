package com.hearthstead.client.ui2.handbook;

import com.hearthstead.client.ui2.BannerSheetLayout;
import com.hearthstead.client.ui2.BannerSheetLayout.Rect;

/**
 * Pure frame geometry for the handbook (local pixels, panel origin 0,0), in
 * the Banner screen's language: a walnut board with iron corners, a header
 * with the crest and a serif title, a wooden chapter rail on the left with a
 * search field, one parchment page on the right and a footer of wooden
 * page controls under it.
 *
 * <p>Sized from the GUI viewport so it fills most of the window at GUI
 * scales 2-4 without ever exceeding it; JUnit checks every rectangle at the
 * common targets (320x240 up to 960x540).
 */
public record HandbookGeometry(int width, int height, Rect inner, Rect header, Rect crest, Rect title,
                               Rect close, Rect rail, Rect search, Rect railList, Rect page, Rect content,
                               Rect footer, Rect prev, Rect next, Rect dots) {
    public static final int FRAME = BannerSheetLayout.FRAME;
    /** The Banner's approved footprint cap (see BannerSheetLayout.MAX_WIDTH). */
    public static final int MAX_WIDTH = BannerSheetLayout.MAX_WIDTH;
    public static final int MAX_HEIGHT = BannerSheetLayout.MAX_HEIGHT;
    public static final int MIN_WIDTH = 300;
    public static final int MIN_HEIGHT = 216;
    public static final int HEADER_H = 22;
    public static final int MARGIN = 6;
    public static final int GUTTER = 8;
    public static final int SEARCH_H = 14;
    public static final int FOOTER_H = 16;
    public static final int ARROW_W = 52;
    /** Inner padding of the parchment page. */
    public static final int PAGE_PAD_X = 8;
    public static final int PAGE_PAD_Y = 6;
    /** Width reserved on the page's right edge for the content scrollbar. */
    public static final int SCROLL_GUTTER = 6;

    public static HandbookGeometry forViewport(int viewportWidth, int viewportHeight) {
        int w = Math.min(MAX_WIDTH, Math.max(Math.min(MIN_WIDTH, viewportWidth - 4), viewportWidth - 16));
        int h = Math.min(MAX_HEIGHT, Math.max(Math.min(MIN_HEIGHT, viewportHeight - 4), viewportHeight - 16));
        return forPanel(Math.max(200, w), Math.max(160, h));
    }

    public static int railWidth(int panelWidth) {
        return panelWidth >= 560 ? 136 : panelWidth >= 440 ? 120 : panelWidth >= 400 ? 108 : 96;
    }

    public static HandbookGeometry forPanel(int w, int h) {
        Rect inner = new Rect(FRAME, FRAME, w - FRAME * 2, h - FRAME * 2);
        Rect header = new Rect(inner.x() + MARGIN, inner.y() + 2, inner.width() - MARGIN * 2, HEADER_H);
        Rect crest = new Rect(inner.x() + MARGIN, -2, 20, HEADER_H + 8);
        Rect close = new Rect(header.right() - 11, header.y() + (HEADER_H - 11) / 2, 11, 11);
        int titleX = crest.right() + GUTTER;
        Rect title = new Rect(titleX, header.y(), Math.max(20, close.x() - GUTTER - titleX), HEADER_H);
        int bodyTop = header.bottom() + 5;
        int bodyBottom = inner.bottom() - 5;
        int railW = railWidth(w);
        Rect rail = new Rect(inner.x() + MARGIN, bodyTop, railW, bodyBottom - bodyTop);
        Rect search = new Rect(rail.x(), rail.y(), rail.width(), SEARCH_H);
        Rect railList = new Rect(rail.x(), search.bottom() + 4, rail.width(),
            Math.max(1, rail.bottom() - search.bottom() - 4));
        int pageX = rail.right() + GUTTER;
        int pageRight = inner.right() - MARGIN;
        Rect footer = new Rect(pageX, bodyBottom - FOOTER_H, pageRight - pageX, FOOTER_H);
        Rect page = new Rect(pageX, bodyTop, pageRight - pageX, Math.max(1, footer.y() - 5 - bodyTop));
        Rect content = new Rect(page.x() + PAGE_PAD_X, page.y() + PAGE_PAD_Y,
            Math.max(1, page.width() - PAGE_PAD_X * 2 - SCROLL_GUTTER),
            Math.max(1, page.height() - PAGE_PAD_Y * 2));
        Rect prev = new Rect(footer.x(), footer.y(), ARROW_W, FOOTER_H);
        Rect next = new Rect(footer.right() - ARROW_W, footer.y(), ARROW_W, FOOTER_H);
        Rect dots = new Rect(prev.right() + 4, footer.y(), Math.max(1, next.x() - prev.right() - 8), FOOTER_H);
        return new HandbookGeometry(w, h, inner, header, crest, title, close, rail, search, railList, page,
            content, footer, prev, next, dots);
    }

    /** Scrollbar track for the page content, in the page's right gutter. */
    public Rect contentScrollbar() {
        return new Rect(page.right() - PAGE_PAD_X / 2 - 3, content.y(), 3, content.height());
    }
}
