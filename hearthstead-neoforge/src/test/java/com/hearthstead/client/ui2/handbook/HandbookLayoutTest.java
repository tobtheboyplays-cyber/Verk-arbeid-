package com.hearthstead.client.ui2.handbook;

import com.google.gson.JsonObject;
import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every shipped page, laid out in English at GUI scales 2-4 on the common
 * window sizes (only those vanilla actually allows: at least 320x240 GUI px),
 * with its detail folded and unfolded: nothing leaves its column, nothing
 * overlaps, and body text is wrapped rather than shortened.
 *
 * <p>Glyph widths are the vanilla default font's, over-estimated for wide
 * punctuation and non-ASCII (see {@link HandbookTestData#width}); the screen
 * lays out with the real font's metrics through the same algorithm.
 */
class HandbookLayoutTest {
    /** {GUI width, GUI height, GUI scale}. */
    private static final int[][] TARGETS = {
        {960, 540, 2}, {683, 384, 2}, {640, 360, 2}, {320, 240, 2},
        {640, 360, 3}, {455, 256, 3}, {426, 240, 3},
        {480, 270, 4}, {640, 360, 4}, {320, 256, 4},
    };
    /** Worst-case binding names the chips may have to print. */
    private static final String LONG_CAP = "Right Button";

    @Test
    void frameGeometryFitsEveryTarget() {
        for (int[] t : TARGETS) {
            HandbookGeometry g = HandbookGeometry.forViewport(t[0], t[1]);
            String at = t[0] + "x" + t[1] + ": ";
            assertTrue(g.width() <= t[0] && g.height() <= t[1], at + "panel inside the window");
            for (Rect r : List.of(g.header(), g.title(), g.close(), g.rail(), g.search(), g.railList(), g.page(),
                    g.content(), g.footer(), g.prev(), g.next(), g.dots())) {
                assertTrue(r.x() >= g.inner().x() && r.right() <= g.inner().right()
                    && r.y() >= g.inner().y() - 2 && r.bottom() <= g.inner().bottom(), at + "on the board: " + r);
                assertTrue(r.width() > 0 && r.height() > 0, at + "non-empty " + r);
            }
            assertTrue(!g.rail().overlaps(g.page()), at + "rail and page are separate");
            assertTrue(g.footer().y() >= g.page().bottom(), at + "footer under the page");
            assertTrue(g.prev().right() < g.dots().x() && g.dots().right() < g.next().x(), at + "footer order");
            assertTrue(g.content().width() >= 150, at + "a readable column: " + g.content().width());
            assertTrue(g.content().height() >= 100, at + "a readable page height: " + g.content().height());
            assertTrue(g.railList().height() >= 100, at + "room for the chapter list");
        }
    }

    @Test
    void everyPageFitsItsColumnAtEveryScale() {
        HandbookBook book = HandbookTestData.book();
        JsonObject en = HandbookTestData.english();
        List<String> failures = new ArrayList<>();
        int laidOut = 0;
        for (int[] t : TARGETS) {
            HandbookGeometry g = HandbookGeometry.forViewport(t[0], t[1]);
            for (HandbookBook.Page p : book.pages()) {
                for (boolean open : new boolean[] {false, true}) {
                    HandbookPageLayout.Result r = HandbookPageLayout.layout(content(book, en, p, open),
                        g.content().width(), g.content().height(), t[2], HandbookTestData.measure());
                    check(r, p.id() + " @" + t[0] + "x" + t[1] + "/" + t[2] + (open ? " open" : ""), failures);
                    laidOut++;
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.size() + " layout failures:\n"
            + String.join("\n", failures.subList(0, Math.min(40, failures.size()))));
        assertTrue(laidOut > 100, "laid out " + laidOut);
    }

    @Test
    void picturesUseWholeOrHalfPixelSteps() {
        for (int scale = 2; scale <= 4; scale++) {
            float gui = HandbookPageLayout.imageScale(256, 144, 300, 200, scale);
            float physical = gui * scale;
            assertEquals(0.0F, (physical * 2) % 1.0F, 1e-4F, "crisp step at scale " + scale + ": " + physical);
        }
        assertEquals(2.0F / 3.0F, HandbookPageLayout.imageScale(256, 144, 200, 200, 3), 1e-4F,
            "scale 3 draws 2 physical px per texel in a 200px column");
    }

    @Test
    void wideColumnsPutThePictureBesideTheBullets() {
        HandbookPageLayout.Content c = new HandbookPageLayout.Content("Chapter", "Title", 256, 144,
            HandbookBook.Placement.AUTO, "Caption", List.of("One bullet that is fairly long and wraps a bit."),
            List.of(), 0, "Try it", "Do the thing.", "More detail", List.of(), false);
        HandbookPageLayout.Result wide = HandbookPageLayout.layout(c, 500, 300, 2, HandbookTestData.measure());
        HandbookPageLayout.Result narrow = HandbookPageLayout.layout(c, 200, 180, 4, HandbookTestData.measure());
        assertTrue(wide.side(), "wide column: side by side");
        assertTrue(!narrow.side(), "narrow column: picture on top");
    }

    private static HandbookPageLayout.Content content(HandbookBook book, JsonObject en, HandbookBook.Page p,
                                                      boolean open) {
        HandbookBook.Chapter c = book.chapterOf(p);
        String chapterTitle = tr(en, c.titleKey());
        String line = chapterTitle + "  ·  " + (p.indexInChapter() + 1) + " / " + c.pages().size();
        List<HandbookPageLayout.Chip> chips = new ArrayList<>();
        for (HandbookBook.KeyChip k : p.keys()) {
            List<String> caps = new ArrayList<>();
            if (k.modifier() != null) caps.add("Left Shift");
            caps.add(LONG_CAP);
            chips.add(new HandbookPageLayout.Chip(caps, k.hold() ? "Hold" : "", tr(en, k.actionKey())));
        }
        HandbookBook.Image img = p.image();
        return new HandbookPageLayout.Content(line, p.titleKey() != null ? tr(en, p.titleKey()) : chapterTitle,
            img == null ? 0 : img.width(), img == null ? 0 : img.height(),
            img == null ? HandbookBook.Placement.AUTO : img.placement(),
            img == null || img.captionKey() == null ? null : tr(en, img.captionKey()),
            p.bullets().stream().map(k -> tr(en, k)).toList(), chips, p.items().size(),
            "Try it", p.tipKey() == null ? null : tr(en, p.tipKey()), open ? "Less detail" : "More detail",
            p.text().stream().map(k -> tr(en, k)).toList(), open,
            "How to get it", p.obtainKey() == null ? null : tr(en, p.obtainKey()), p.recipes().size(),
            "How to use", p.steps().stream().map(k -> tr(en, k)).toList(),
            p.link() == null ? null : "› " + p.link(),
            p.entries().stream().map(e -> new HandbookPageLayout.EntryRow(tr(en, e.nameKey()),
                e.textKey() == null ? null : tr(en, e.textKey()))).toList());
    }

    private static String tr(JsonObject en, String key) {
        return en.has(key) ? en.get(key).getAsString() : key;
    }

    private static void check(HandbookPageLayout.Result r, String at, List<String> failures) {
        int w = r.width();
        for (HandbookPageLayout.Line l : r.lines()) {
            int measured = l.kind() == HandbookPageLayout.Kind.TITLE_SERIF
                ? HandbookTestData.serifWidth(l.text()) : HandbookTestData.width(l.text());
            if (l.x() < 0 || l.x() + measured > w) {
                failures.add(at + ": line leaves the column (" + l.x() + "+" + measured + " > " + w + "): "
                    + l.kind() + " '" + l.text() + "'");
            }
            if (l.text().endsWith("…") || l.text().endsWith("...")) {
                failures.add(at + ": shortened text '" + l.text() + "'");
            }
        }
        for (HandbookPageLayout.Box b : r.boxes()) {
            if (b.x() < 0 || b.right() > w) failures.add(at + ": box leaves the column " + b);
            if (b.y() + b.h() > r.height() + 1) failures.add(at + ": box below the page end " + b);
        }
        // Nothing overlaps: text against text, and text against the picture and icons.
        List<int[]> rects = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (HandbookPageLayout.Line l : r.lines()) {
            if (l.kind() == HandbookPageLayout.Kind.CAP_TEXT) continue; // drawn inside its CAP box
            int measured = l.kind() == HandbookPageLayout.Kind.TITLE_SERIF
                ? HandbookTestData.serifWidth(l.text()) : HandbookTestData.width(l.text());
            rects.add(new int[] {l.x(), l.y(), l.x() + measured, l.y() + 8});
            names.add(l.kind() + " '" + l.text() + "'");
        }
        for (HandbookPageLayout.Box b : r.boxes()) {
            switch (b.kind()) {
                case IMAGE, ITEM, CAP, BULLET_MARK, RECIPE, STEP_MARK, ENTRY_ICON, GATE_ICON -> {
                    rects.add(new int[] {b.x(), b.y(), b.right(), b.bottom()});
                    names.add(b.kind().toString());
                }
                default -> {
                }
            }
        }
        for (int i = 0; i < rects.size(); i++) {
            for (int j = i + 1; j < rects.size(); j++) {
                int[] a = rects.get(i);
                int[] c = rects.get(j);
                if (a[0] < c[2] && c[0] < a[2] && a[1] < c[3] && c[1] < a[3]) {
                    failures.add(at + ": overlap " + names.get(i) + " / " + names.get(j));
                }
            }
        }
    }
}
