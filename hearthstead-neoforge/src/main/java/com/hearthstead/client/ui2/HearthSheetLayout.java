package com.hearthstead.client.ui2;

/**
 * Pure UI2 geometry for the Hearth sheet (local pixels, sheet origin 0,0).
 *
 * <p>Vertical order on Home follows the brief: header (name, day, time) ->
 * tabs -> one focal line with one action -> three figures -> "Needs you"
 * (max three rows) -> "Village" ledger. Spare height above the 224px
 * minimum is shared out as air between those sections, never as new boxes.
 */
public record HearthSheetLayout(int width, int height, int pad,
                                Rect header, Rect tabs, int tabRuleY, Rect body,
                                Rect focus, Rect figures, int figuresRuleY,
                                Rect needsHeader, Rect needs, Rect villageHeader, Rect village,
                                Rect list, Rect detail) {
    public static final int NEED_ROWS = 3;
    public static final int NEED_ROW_H = 14;
    public static final int VILLAGE_ROWS = 3;
    public static final int VILLAGE_ROW_H = 12;
    public static final int FOCUS_H = 26;
    public static final int FIGURES_H = 16;
    public static final int SECTION_HEADER_H = 11;
    /** Everything Home needs at the minimum sheet height, header through village. */
    static final int HOME_MIN_BOTTOM = 212;

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

    public static HearthSheetLayout forSheet(int width, int height) {
        int pad = width >= 400 ? 12 : 10;
        int inner = width - pad * 2;
        Rect header = new Rect(pad, 7, inner, 14);
        Rect tabs = new Rect(pad, 24, inner, Ui2Tabs.HEIGHT);
        int tabRuleY = tabs.bottom() + 1;
        int bodyTop = tabRuleY + 7;
        int bodyBottom = height - 10;
        Rect body = new Rect(pad, bodyTop, inner, Math.max(1, bodyBottom - bodyTop));

        int spare = Math.max(0, bodyBottom - HOME_MIN_BOTTOM);
        int air = Math.min(10, spare / 3);
        int y = bodyTop;
        Rect focus = new Rect(pad, y, inner, FOCUS_H);
        y = focus.bottom() + 6 + air;
        Rect figures = new Rect(pad, y, inner, FIGURES_H);
        int figuresRuleY = figures.bottom() + 5;
        y = figuresRuleY + 6 + air / 2;
        Rect needsHeader = new Rect(pad, y, inner, SECTION_HEADER_H);
        Rect needs = new Rect(pad, needsHeader.bottom(), inner, NEED_ROWS * NEED_ROW_H);
        y = needs.bottom() + 6 + air;
        Rect villageHeader = new Rect(pad, y, inner, SECTION_HEADER_H);
        Rect village = new Rect(pad, villageHeader.bottom(), inner, VILLAGE_ROWS * VILLAGE_ROW_H);

        int listWidth = inner * 11 / 20;
        Rect list = new Rect(pad, bodyTop, listWidth, body.height());
        int detailX = pad + listWidth + 13;
        Rect detail = new Rect(detailX, bodyTop, width - pad - detailX, body.height());
        return new HearthSheetLayout(width, height, pad, header, tabs, tabRuleY, body,
            focus, figures, figuresRuleY, needsHeader, needs, villageHeader, village, list, detail);
    }

    /** Rule between list and detail panes. */
    public int splitX() {
        return list.right() + 6;
    }

    public Rect figure(int index) {
        if (index < 0 || index > 2) throw new IllegalArgumentException("Hearth figure: " + index);
        int w = figures.width() / 3;
        int x = figures.x() + index * w;
        int right = index == 2 ? figures.right() : x + w - 6;
        return new Rect(x, figures.y(), right - x, figures.height());
    }

    public Rect needRow(int index) {
        if (index < 0 || index >= NEED_ROWS) throw new IllegalArgumentException("Need row: " + index);
        return new Rect(needs.x(), needs.y() + index * NEED_ROW_H, needs.width(), NEED_ROW_H);
    }

    public Rect villageRow(int index) {
        if (index < 0 || index >= VILLAGE_ROWS) throw new IllegalArgumentException("Village row: " + index);
        return new Rect(village.x(), village.y() + index * VILLAGE_ROW_H, village.width(), VILLAGE_ROW_H);
    }

    /** Focus band action, right-aligned and vertically centred. */
    public Rect focusAction(int buttonWidth) {
        int w = Math.min(buttonWidth, focus.width() / 3);
        return new Rect(focus.right() - w, focus.y() + (focus.height() - 18) / 2, w, 18);
    }

    public boolean homeFits() {
        return village.bottom() <= height - 10;
    }
}
