package com.hearthstead.client.ui2.handbook;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure layout of one handbook page into positioned lines and boxes, in
 * content-local pixels (0,0 = top-left of the page's content area, which
 * scrolls vertically when the result is taller than the view).
 *
 * <p>Reading order is fixed so every page looks the same: a small chapter
 * line, the heading, the picture (beside the bullets when the column is wide
 * enough, above them otherwise), 2-4 bullets, key-hint chips, item icons,
 * the "Try it" tip, then the folded "More detail" text.
 *
 * <p>Body text is always wrapped, never shortened: every line this returns
 * fits its column by construction, which JUnit asserts for every shipped
 * page at GUI scales 2-4.
 */
public final class HandbookPageLayout {
    public static final int LINE = 10;
    public static final int SMALL_LINE = 9;
    public static final int CAP_H = 12;
    public static final int ITEM = 18;
    public static final int BULLET_INDENT = 9;
    public static final int BLOCK_GAP = 6;
    public static final int SIDE_GAP = 10;
    /** Below this text-column width the picture goes on top instead of beside. */
    public static final int MIN_SIDE_TEXT = 140;
    /** One crafting grid: 3x3 slots, an arrow, the output slot. */
    public static final int RECIPE_W = 3 * 18 + 8 + 12 + 8 + 18;
    public static final int RECIPE_H = 3 * 18;
    public static final int STEP_INDENT = 12;

    private HandbookPageLayout() {
    }

    public interface Measure {
        int width(String text);

        /** Splits into lines no wider than {@code width} (breaking inside a word if it must). */
        List<String> wrap(String text, int width);

        /** Width of a single-line serif small-caps heading. */
        int titleWidth(String text);
    }

    public record Chip(List<String> caps, String prefix, String action) {
    }

    /** A reference row: an icon, a name and one wrapped line. */
    public record EntryRow(String name, String text) {
    }

    public record Content(String chapterLine, String title, int imageWidth, int imageHeight,
                          HandbookBook.Placement placement, String caption, List<String> bullets,
                          List<Chip> chips, int itemCount, String tipLabel, String tip,
                          String detailsLabel, List<String> details, boolean detailsOpen,
                          String getLabel, String obtain, int recipeCount, String useLabel,
                          List<String> steps, String tipLink, List<EntryRow> entries, List<String> gates) {
        public Content(String chapterLine, String title, int imageWidth, int imageHeight,
                       HandbookBook.Placement placement, String caption, List<String> bullets,
                       List<Chip> chips, int itemCount, String tipLabel, String tip,
                       String detailsLabel, List<String> details, boolean detailsOpen,
                       String getLabel, String obtain, int recipeCount, String useLabel,
                       List<String> steps, String tipLink, List<EntryRow> entries) {
            this(chapterLine, title, imageWidth, imageHeight, placement, caption, bullets, chips, itemCount,
                tipLabel, tip, detailsLabel, details, detailsOpen, getLabel, obtain, recipeCount, useLabel,
                steps, tipLink, entries, List.of());
        }

        public Content(String chapterLine, String title, int imageWidth, int imageHeight,
                       HandbookBook.Placement placement, String caption, List<String> bullets,
                       List<Chip> chips, int itemCount, String tipLabel, String tip,
                       String detailsLabel, List<String> details, boolean detailsOpen,
                       String getLabel, String obtain, int recipeCount, String useLabel,
                       List<String> steps, String tipLink) {
            this(chapterLine, title, imageWidth, imageHeight, placement, caption, bullets, chips, itemCount,
                tipLabel, tip, detailsLabel, details, detailsOpen, getLabel, obtain, recipeCount, useLabel,
                steps, tipLink, List.of());
        }

        /** The pre-"how to craft" page shape: no obtain line, recipes, steps or link. */
        public Content(String chapterLine, String title, int imageWidth, int imageHeight,
                       HandbookBook.Placement placement, String caption, List<String> bullets,
                       List<Chip> chips, int itemCount, String tipLabel, String tip,
                       String detailsLabel, List<String> details, boolean detailsOpen) {
            this(chapterLine, title, imageWidth, imageHeight, placement, caption, bullets, chips, itemCount,
                tipLabel, tip, detailsLabel, details, detailsOpen, null, null, 0, null, List.of(), null,
                List.of());
        }

        public boolean hasImage() {
            return imageWidth > 0 && imageHeight > 0;
        }
    }

    public enum Kind {
        CHAPTER, TITLE_SERIF, TITLE, CAPTION, BULLET, CHIP_PREFIX, CAP_TEXT, CHIP_ACTION,
        TIP_LABEL, TIP, DETAILS_LABEL, DETAIL, SECTION, OBTAIN, STEP, TIP_LINK, ENTRY_NAME, ENTRY_TEXT, GATE,
        // boxes
        RULE, IMAGE, BULLET_MARK, CAP, ITEM, TIP_BOX, DETAILS_TOGGLE, RECIPE, STEP_MARK, LINK, ENTRY_ICON,
        GATE_ICON
    }

    public record Line(Kind kind, String text, int x, int y, int width) {
    }

    public record Box(Kind kind, int x, int y, int w, int h, int index) {
        public int right() {
            return x + w;
        }

        public int bottom() {
            return y + h;
        }
    }

    public record Result(int width, int height, List<Line> lines, List<Box> boxes, boolean side,
                         float imageScale) {
        public Box first(Kind kind) {
            for (Box b : boxes) if (b.kind() == kind) return b;
            return null;
        }
    }

    /**
     * Picks the picture's texel-to-physical-pixel factor: whole or half steps
     * only, so the pixel art stays crisp, and the largest one that fits.
     * Returns GUI px per texel.
     */
    public static float imageScale(int texW, int texH, int maxW, int maxH, int guiScale) {
        int s = Math.max(1, guiScale);
        for (int twice = 8; twice >= 1; twice--) {
            float physical = twice / 2.0F;
            float gui = physical / s;
            if (Math.ceil(texW * gui) <= maxW && Math.ceil(texH * gui) <= maxH) return gui;
        }
        return Math.min((float) maxW / texW, (float) maxH / texH);
    }

    public static Result layout(Content c, int width, int viewHeight, int guiScale, Measure m) {
        List<Line> lines = new ArrayList<>();
        List<Box> boxes = new ArrayList<>();
        int y = 0;
        if (c.chapterLine() != null && !c.chapterLine().isEmpty()) {
            for (String s : m.wrap(c.chapterLine(), width)) {
                lines.add(new Line(Kind.CHAPTER, s, 0, y, m.width(s)));
                y += SMALL_LINE;
            }
            y += 2;
        }
        String title = c.title() == null ? "" : c.title();
        if (m.titleWidth(title) <= width) {
            lines.add(new Line(Kind.TITLE_SERIF, title, 0, y, m.titleWidth(title)));
            y += 13;
        } else {
            for (String s : m.wrap(title, width)) {
                lines.add(new Line(Kind.TITLE, s, 0, y, m.width(s)));
                y += LINE;
            }
            y += 2;
        }
        boxes.add(new Box(Kind.RULE, 0, y, width, 1, 0));
        y += 1 + BLOCK_GAP;

        boolean side = false;
        float scale = 0;
        int imgW = 0;
        int imgH = 0;
        if (c.hasImage()) {
            boolean sideAllowed = c.placement() != HandbookBook.Placement.TOP
                && width - 64 - SIDE_GAP >= MIN_SIDE_TEXT
                && !c.bullets().isEmpty();
            if (sideAllowed) {
                int maxW = Math.min(width / 2, width - SIDE_GAP - MIN_SIDE_TEXT);
                int maxH = Math.max(56, viewHeight * 7 / 10);
                scale = imageScale(c.imageWidth(), c.imageHeight(), maxW, maxH, guiScale);
                imgW = (int) Math.ceil(c.imageWidth() * scale);
                imgH = (int) Math.ceil(c.imageHeight() * scale);
                side = imgW >= 64 || c.placement() == HandbookBook.Placement.SIDE;
            }
            if (!side) {
                int maxH = Math.max(48, viewHeight * 45 / 100);
                scale = imageScale(c.imageWidth(), c.imageHeight(), width, maxH, guiScale);
                imgW = (int) Math.ceil(c.imageWidth() * scale);
                imgH = (int) Math.ceil(c.imageHeight() * scale);
            }
        }

        if (side) {
            int top = y;
            boxes.add(new Box(Kind.IMAGE, 0, top, imgW, imgH, 0));
            int iy = top + imgH + 2;
            iy = caption(c, lines, 0, iy, imgW, false, m);
            int colX = imgW + SIDE_GAP;
            int colW = width - colX;
            int ty = bullets(c, lines, boxes, colX, top, colW, m);
            if (!c.chips().isEmpty()) ty = chips(c, lines, boxes, colX, ty + 2, colW, m);
            y = Math.max(iy, ty) + BLOCK_GAP;
        } else {
            if (c.hasImage()) {
                int ix = (width - imgW) / 2;
                boxes.add(new Box(Kind.IMAGE, ix, y, imgW, imgH, 0));
                y += imgH + 2;
                y = caption(c, lines, 0, y, width, true, m);
                y += BLOCK_GAP - 2;
            }
            y = bullets(c, lines, boxes, 0, y, width, m);
            if (!c.chips().isEmpty()) y = chips(c, lines, boxes, 0, y + 2, width, m);
            y += BLOCK_GAP;
        }

        // How to get it: an obtain line and/or the real crafting grids.
        if ((c.obtain() != null && !c.obtain().isEmpty()) || c.recipeCount() > 0) {
            y = section(c.getLabel(), lines, y, width, m);
            if (c.obtain() != null && !c.obtain().isEmpty()) {
                for (String s : m.wrap(c.obtain(), width)) {
                    lines.add(new Line(Kind.OBTAIN, s, 0, y, m.width(s)));
                    y += LINE;
                }
                y += 3;
            }
            if (c.recipeCount() > 0) {
                int perRow = Math.max(1, (width + 10) / (RECIPE_W + 10));
                for (int i = 0; i < c.recipeCount(); i++) {
                    int row = i / perRow;
                    int col = i % perRow;
                    boxes.add(new Box(Kind.RECIPE, col * (RECIPE_W + 10), y + row * (RECIPE_H + 6),
                        Math.min(RECIPE_W, width), RECIPE_H, i));
                }
                int rows = (c.recipeCount() + perRow - 1) / perRow;
                y += rows * (RECIPE_H + 6) - 6;
            }
            // "Unlocked by: <node> (Tech Tree)", one row per distinct gating node.
            for (int i = 0; i < c.gates().size(); i++) {
                y += 4;
                int top = y;
                boxes.add(new Box(Kind.GATE_ICON, 0, top, 12, 12, i));
                for (String s : m.wrap(c.gates().get(i), Math.max(1, width - 16))) {
                    lines.add(new Line(Kind.GATE, s, 16, y + 2, m.width(s)));
                    y += LINE;
                }
                y = Math.max(y, top + 12);
            }
            y += BLOCK_GAP;
        }

        // How to use it: numbered steps.
        if (!c.steps().isEmpty()) {
            y = section(c.useLabel(), lines, y, width, m);
            int textW = Math.max(1, width - STEP_INDENT);
            for (int i = 0; i < c.steps().size(); i++) {
                boxes.add(new Box(Kind.STEP_MARK, 0, y, STEP_INDENT - 2, SMALL_LINE, i));
                for (String s : m.wrap(c.steps().get(i), textW)) {
                    lines.add(new Line(Kind.STEP, s, STEP_INDENT, y, m.width(s)));
                    y += LINE;
                }
                y += 2;
            }
            y += BLOCK_GAP - 2;
        }

        // Reference rows: icon, name, one wrapped line (jobs, buildings, events, nodes, options).
        if (!c.entries().isEmpty()) {
            int textX = ITEM + 4;
            int textW = Math.max(1, width - textX);
            for (int i = 0; i < c.entries().size(); i++) {
                EntryRow e = c.entries().get(i);
                int top = y;
                boxes.add(new Box(Kind.ENTRY_ICON, 0, top, 16, 16, i));
                for (String s : m.wrap(e.name() == null ? "" : e.name(), textW)) {
                    lines.add(new Line(Kind.ENTRY_NAME, s, textX, y, m.width(s)));
                    y += SMALL_LINE;
                }
                if (e.text() != null && !e.text().isEmpty()) {
                    for (String s : m.wrap(e.text(), textW)) {
                        lines.add(new Line(Kind.ENTRY_TEXT, s, textX, y, m.width(s)));
                        y += SMALL_LINE;
                    }
                }
                y = Math.max(y, top + 16) + 4;
            }
            y += BLOCK_GAP - 4;
        }

        if (c.itemCount() > 0) {
            int perRow = Math.max(1, (width + 2) / (ITEM + 2));
            for (int i = 0; i < c.itemCount(); i++) {
                int row = i / perRow;
                int col = i % perRow;
                boxes.add(new Box(Kind.ITEM, col * (ITEM + 2), y + row * (ITEM + 2), ITEM, ITEM, i));
            }
            int rows = (c.itemCount() + perRow - 1) / perRow;
            y += rows * (ITEM + 2) - 2 + BLOCK_GAP;
        }

        if (c.tip() != null && !c.tip().isEmpty()) {
            int pad = 6;
            int innerW = Math.max(1, width - pad * 2 - 2);
            int top = y;
            int ty = top + 4;
            for (String s : m.wrap(c.tipLabel() == null ? "" : c.tipLabel(), innerW)) {
                lines.add(new Line(Kind.TIP_LABEL, s, pad + 2, ty, m.width(s)));
                ty += SMALL_LINE;
            }
            ty += 1;
            for (String s : m.wrap(c.tip(), innerW)) {
                lines.add(new Line(Kind.TIP, s, pad + 2, ty, m.width(s)));
                ty += LINE;
            }
            if (c.tipLink() != null && !c.tipLink().isEmpty()) {
                int ly = ty + 1;
                for (String s : m.wrap(c.tipLink(), innerW)) {
                    lines.add(new Line(Kind.TIP_LINK, s, pad + 2, ty + 1, m.width(s)));
                    ty += LINE;
                }
                boxes.add(new Box(Kind.LINK, pad, ly - 1, innerW + 2, ty - ly + 1, 0));
                ty += 1;
            }
            ty += 2;
            boxes.add(new Box(Kind.TIP_BOX, 0, top, width, ty - top, 0));
            y = ty + BLOCK_GAP;
        }

        if (!c.details().isEmpty()) {
            String label = c.detailsLabel() == null ? "" : c.detailsLabel();
            List<String> labelLines = m.wrap(label, Math.max(1, width - 10));
            int top = y;
            for (String s : labelLines) {
                lines.add(new Line(Kind.DETAILS_LABEL, s, 10, y + 2, m.width(s)));
                y += LINE;
            }
            boxes.add(new Box(Kind.DETAILS_TOGGLE, 0, top, width, y - top + 3, 0));
            y += 5;
            if (c.detailsOpen()) {
                for (String paragraph : c.details()) {
                    for (String part : paragraph.split("\n")) {
                        for (String s : m.wrap(part, width)) {
                            lines.add(new Line(Kind.DETAIL, s, 0, y, m.width(s)));
                            y += LINE;
                        }
                    }
                    y += 4;
                }
            }
        }
        int bottom = 0;
        for (Line l : lines) bottom = Math.max(bottom, l.y() + SMALL_LINE);
        for (Box b : boxes) bottom = Math.max(bottom, b.bottom());
        return new Result(width, bottom + 2, List.copyOf(lines), List.copyOf(boxes), side, scale);
    }

    private static int section(String label, List<Line> lines, int y, int width, Measure m) {
        for (String s : m.wrap(label == null ? "" : label, width)) {
            lines.add(new Line(Kind.SECTION, s, 0, y, m.width(s)));
            y += SMALL_LINE;
        }
        return y + 2;
    }

    private static int caption(Content c, List<Line> lines, int x, int y, int w, boolean centre, Measure m) {
        if (c.caption() == null || c.caption().isEmpty()) return y;
        for (String s : m.wrap(c.caption(), w)) {
            int sw = m.width(s);
            lines.add(new Line(Kind.CAPTION, s, centre ? x + (w - sw) / 2 : x, y, sw));
            y += SMALL_LINE;
        }
        return y;
    }

    private static int bullets(Content c, List<Line> lines, List<Box> boxes, int x, int y, int w, Measure m) {
        int textW = Math.max(1, w - BULLET_INDENT);
        int n = 0;
        for (String bullet : c.bullets()) {
            boxes.add(new Box(Kind.BULLET_MARK, x, y + 2, 5, 5, n++));
            for (String s : m.wrap(bullet, textW)) {
                lines.add(new Line(Kind.BULLET, s, x + BULLET_INDENT, y, m.width(s)));
                y += LINE;
            }
            y += 3;
        }
        return c.bullets().isEmpty() ? y : y - 3;
    }

    /** Key chips flow left to right; a chip too wide for a row wraps its action text. */
    private static int chips(Content c, List<Line> lines, List<Box> boxes, int x0, int y, int w, Measure m) {
        int cx = x0;
        int rowTop = y;
        int rowH = CAP_H;
        int index = 0;
        for (Chip chip : c.chips()) {
            int prefixW = chip.prefix() == null || chip.prefix().isEmpty() ? 0 : m.width(chip.prefix()) + 3;
            int capsW = 0;
            for (int i = 0; i < chip.caps().size(); i++) {
                capsW += m.width(chip.caps().get(i)) + 6;
                if (i > 0) capsW += m.width("+") + 2;
            }
            int actionW = m.width(chip.action());
            int full = prefixW + capsW + 4 + actionW;
            if (cx > x0 && cx + full > x0 + w) {
                cx = x0;
                rowTop += rowH + 3;
                rowH = CAP_H;
            }
            int x = cx;
            if (prefixW > 0) {
                lines.add(new Line(Kind.CHIP_PREFIX, chip.prefix(), x, rowTop + 2, prefixW - 3));
                x += prefixW;
            }
            for (int i = 0; i < chip.caps().size(); i++) {
                String cap = chip.caps().get(i);
                if (i > 0) {
                    lines.add(new Line(Kind.CHIP_PREFIX, "+", x + 1, rowTop + 2, m.width("+")));
                    x += m.width("+") + 2;
                }
                int capW = Math.min(m.width(cap) + 6, w - (x - x0));
                if (capW < m.width(cap) + 6) {
                    // The cap cannot fit next to what is already on the row: new row.
                    rowTop += rowH + 3;
                    rowH = CAP_H;
                    x = x0;
                    capW = Math.min(m.width(cap) + 6, w);
                }
                boxes.add(new Box(Kind.CAP, x, rowTop, capW, CAP_H, index));
                lines.add(new Line(Kind.CAP_TEXT, cap, x + 3, rowTop + 2, Math.min(m.width(cap), capW - 6)));
                x += capW;
            }
            int ax = x + 4;
            int availableW = x0 + w - ax;
            if (availableW < 40) {
                rowTop += rowH + 2;
                rowH = 0;
                ax = x0 + BULLET_INDENT;
                availableW = w - BULLET_INDENT;
            }
            List<String> actionLines = m.wrap(chip.action(), Math.max(1, availableW));
            int ly = rowTop + 2;
            int maxRight = ax;
            for (String s : actionLines) {
                int sw = m.width(s);
                lines.add(new Line(Kind.CHIP_ACTION, s, ax, ly, sw));
                maxRight = Math.max(maxRight, ax + sw);
                ly += LINE;
            }
            rowH = Math.max(rowH, ly - rowTop - 1);
            if (actionLines.size() > 1 || maxRight + 10 > x0 + w) {
                cx = x0;
                rowTop += rowH + 3;
                rowH = CAP_H;
            } else {
                cx = maxRight + 10;
            }
            index++;
        }
        return cx == x0 && rowTop > y ? rowTop - 3 : rowTop + rowH;
    }
}
