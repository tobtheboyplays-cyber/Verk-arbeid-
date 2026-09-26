package com.hearthstead.client.ui2;

/**
 * Pure geometry for the Banner screen (local pixels, panel origin 0,0).
 *
 * <p>One large panel: a dark walnut frame with iron corners around a
 * parchment page. A header (crest, serif settlement name, day line, the
 * Food / Coins / Settlers counters, a small close key and the hanging
 * banner), then three columns: a left navigation rail, the centre page (the
 * live map on Overview) and a context column on the right.
 *
 * <p>It uses most of the window. Below {@link #LABELED_NAV_MIN} px wide the
 * rail collapses to icons; below {@link #STACK_MIN} px the right column moves
 * under the centre page. Every rectangle is unit tested at 320x240, 426x240,
 * 480x270 and larger viewports.
 */
public record BannerSheetLayout(int width, int height, boolean compactNav, boolean stacked,
                                Rect inner, Rect header, Rect crest, Rect title, Rect counters,
                                Rect close, Rect banner, int headerRuleY, Rect body, Rect nav,
                                Rect centrePanel, Rect rightPanel, Rect centre, Rect right, Rect wide) {
    /** Walnut frame thickness, outer dark line to inner dark line. */
    public static final int FRAME = 6;
    public static final int MAX_WIDTH = 720;
    public static final int MAX_HEIGHT = 400;
    public static final int MIN_WIDTH = 304;
    public static final int MIN_HEIGHT = 224;
    public static final int LABELED_NAV_MIN = 400;
    public static final int STACK_MIN = 380;
    public static final int NAV_ITEM_H = 24;
    public static final int NAV_ITEMS = 6;
    public static final int NAV_W_COMPACT = 22;
    public static final int NAV_W_LABELED = 76;
    public static final int HEADER_H = 28;
    public static final int COUNTERS = 3;
    public static final int COUNTER_W = 50;
    public static final int COUNTER_W_NARROW = 40;
    public static final int STACK_H = 58;
    public static final int GUTTER = 8;
    /** Inset of the rail, pages and header from the frame's inner edge. */
    public static final int MARGIN = 6;
    /** Parchment mat around the live map and around right-column content. */
    public static final int MAT = 5;
    public static final int PANEL_PAD = 8;

    public record Rect(int x, int y, int width, int height) {
        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }

        public boolean contains(double px, double py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }

        public boolean overlaps(Rect o) {
            return x < o.x + o.width && x + width > o.x && y < o.y + o.height && y + height > o.y;
        }
    }

    /** Panel size for a GUI viewport: most of the window, within sane bounds. */
    public static BannerSheetLayout forViewport(int viewportWidth, int viewportHeight) {
        int w = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, viewportWidth - 16));
        int h = Math.max(MIN_HEIGHT, Math.min(MAX_HEIGHT, viewportHeight - 16));
        return forSheet(w, h);
    }

    public static BannerSheetLayout forSheet(int width, int height) {
        boolean compactNav = width < LABELED_NAV_MIN;
        boolean stacked = width < STACK_MIN;
        Rect inner = new Rect(FRAME, FRAME, width - FRAME * 2, height - FRAME * 2);
        // One spacing scale: 4 inside rows, 8 between blocks and around panels.
        Rect header = new Rect(inner.x() + MARGIN, inner.y() + 4, inner.width() - MARGIN * 2, HEADER_H);
        // The crest banner hangs from the top frame at the left, over the header.
        Rect crest = new Rect(inner.x() + MARGIN, -2, 26, HEADER_H + 10);
        // The settlement banner hangs from the frame at the far right, clear of everything else.
        Rect banner = new Rect(width - FRAME - 24, -3, 16, 40);
        Rect close = new Rect(banner.x() - GUTTER - 11, header.y() + 1, 11, 11);
        int counterW = width >= 460 ? COUNTER_W + 8 : COUNTER_W_NARROW;
        int countersW = counterW * COUNTERS;
        Rect counters = new Rect(close.x() - GUTTER - countersW, header.y() + 3, countersW, 22);
        int titleX = crest.right() + GUTTER;
        Rect title = new Rect(titleX, header.y() + 1, Math.max(40, counters.x() - GUTTER - titleX), 26);
        int headerRuleY = header.bottom() + 2;
        int bodyTop = headerRuleY + 4;
        int bodyBottom = inner.bottom() - 4;
        Rect body = new Rect(inner.x() + MARGIN, bodyTop, inner.width() - MARGIN * 2,
            Math.max(1, bodyBottom - bodyTop));
        int navW = compactNav ? NAV_W_COMPACT : NAV_W_LABELED;
        Rect nav = new Rect(body.x(), bodyTop, navW, body.height());
        int centreX = nav.right() + GUTTER;
        int rightEdge = body.right();
        Rect centrePanel;
        Rect rightPanel;
        if (stacked) {
            int centreH = body.height() - STACK_H - GUTTER;
            centrePanel = new Rect(centreX, bodyTop, rightEdge - centreX, centreH);
            rightPanel = new Rect(centreX, centrePanel.bottom() + GUTTER, rightEdge - centreX, STACK_H);
        } else {
            int rightW = width >= 600 ? 160 : width >= 440 ? 128 : 116;
            rightPanel = new Rect(rightEdge - rightW, bodyTop, rightW, body.height());
            centrePanel = new Rect(centreX, bodyTop, rightPanel.x() - GUTTER - centreX, body.height());
        }
        Rect centre = inset(centrePanel, MAT, MAT);
        Rect right = inset(rightPanel, PANEL_PAD, stacked ? 4 : PANEL_PAD);
        Rect wide = inset(new Rect(centreX, bodyTop, rightEdge - centreX, body.height()), MAT, 1);
        return new BannerSheetLayout(width, height, compactNav, stacked, inner, header, crest, title,
            counters, close, banner, headerRuleY, body, nav, centrePanel, rightPanel, centre, right, wide);
    }

    static Rect inset(Rect r, int dx, int dy) {
        return new Rect(r.x() + dx, r.y() + dy, Math.max(1, r.width() - dx * 2), Math.max(1, r.height() - dy * 2));
    }

    public Rect navItem(int index) {
        if (index < 0 || index >= NAV_ITEMS) throw new IllegalArgumentException("Nav item: " + index);
        return new Rect(nav.x(), nav.y() + index * NAV_ITEM_H, nav.width(), NAV_ITEM_H);
    }

    public Rect counter(int index) {
        if (index < 0 || index >= COUNTERS) throw new IllegalArgumentException("Counter: " + index);
        int w = counters.width() / COUNTERS;
        return new Rect(counters.x() + index * w, counters.y(), w - 4, counters.height());
    }

    public boolean narrowCounters() {
        return counters.width() < COUNTER_W * COUNTERS;
    }

    /** The whole centre+right area as one panel (Storage page). */
    public Rect widePanel() {
        return new Rect(wide.x() - MAT, wide.y() - 1, wide.width() + MAT * 2, wide.height() + 2);
    }

    /** Rows of the Storage page's communal grid and player inventory, side by side or stacked. */
    public boolean storageSideBySide() {
        return wide.width() >= 6 * 18 + 12 + 9 * 18;
    }

    /** Top-left of the 6x4 communal slot grid (slot origin, not the well). */
    public Rect communalGrid() {
        int y = wide.y() + 11;
        return new Rect(wide.x() + 1, y, 6 * 18, 4 * 18);
    }

    /** Top-left of the 9x3 player grid; the hotbar sits 4px under it. */
    public Rect playerGrid() {
        Rect communal = communalGrid();
        if (storageSideBySide()) {
            return new Rect(wide.right() - 9 * 18, communal.y(), 9 * 18, 4 * 18 + 4);
        }
        return new Rect(wide.x() + 1, communal.bottom() + 9, 9 * 18, 4 * 18 + 4);
    }
}
