package com.hearthstead.client.screen;

import com.hearthstead.client.ui.HsButton;
import com.hearthstead.client.ui.HsUi;
import com.hearthstead.client.ui.HsUiTokens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The settler's handbook: the onboarding artifact for everything the mod does
 * today, in eight short chapters — Founding, the Plaque, Jobs, Attributes,
 * the Day, Logistics, the Mayor, and Threat.
 *
 * <p>Every chapter is one to two pages, and every page is lang-driven text,
 * so translators can keep pace with design changes without a rebuild.
 *
 * <h2>Three ways to move, and none of them are decoration (D-014)</h2>
 *
 * <p>The old handbook had only prev/next arrows and no way to see the book's
 * shape or jump into it — a chapter list existed nowhere but this class's own
 * source. That is fixed with three controls that each do a distinct, real
 * thing, matching what a reader of a real book reaches for:
 *
 * <ul>
 *   <li><b>the chapter index</b> (left column) — jumps straight to any
 *       chapter's first page, and is always able to, so it is never drawn
 *       disabled;
 *   <li><b>the page list</b> (small numbered tabs under the text) — appears
 *       only on a chapter with more than one page, and jumps straight to one
 *       of them. A single-page chapter has nothing to list, so nothing is
 *       drawn there rather than one meaningless dot;
 *   <li><b>prev/next</b> — steps one page at a time through the whole book,
 *       across chapter boundaries, and is disabled exactly at the book's own
 *       covers.
 * </ul>
 *
 * <p>Drawn with the same nine-slice kit the plaque screen uses (see the
 * {@code minecraft-ui} skill) rather than the single fixed-size book texture
 * the old screen blitted — a layout that can gain a ninth chapter without a
 * new piece of art.
 */
public class HandbookScreen extends Screen {

    /** One chapter of the book: a lang id and how many pages it runs. */
    private record Chapter(String id, int pages) {
    }

    // The book's table of contents. Order here is reading order, both in the
    // index and for prev/next. Keep this in sync with the lang keys reported
    // alongside this file — every id here needs a ".title" and at least one
    // ".body" key (".body", ".body2", ... for chapters with more than one
    // page).
    private static final Chapter[] CHAPTERS = {
        new Chapter("founding", 1),
        new Chapter("plaque", 2),
        new Chapter("jobs", 1),
        new Chapter("attributes", 2),
        new Chapter("day", 1),
        new Chapter("logistics", 1),
        new Chapter("mayor", 1),
        new Chapter("threat", 2),
    };

    // Flattened once at class-load: which chapter and which page-within-it a
    // given global page number is, plus where each chapter's first page
    // lands. Ordinary arithmetic could re-derive these every frame, but a
    // fixed table is cheaper to read than to re-prove correct at each call
    // site, and this book's shape never changes at runtime.
    private static final int TOTAL_PAGES;
    private static final int[] CHAPTER_OF_PAGE;
    private static final int[] PAGE_IN_CHAPTER;
    private static final int[] CHAPTER_FIRST_PAGE;

    static {
        int total = 0;
        for (Chapter chapter : CHAPTERS) {
            total += chapter.pages();
        }
        TOTAL_PAGES = total;
        CHAPTER_OF_PAGE = new int[total];
        PAGE_IN_CHAPTER = new int[total];
        CHAPTER_FIRST_PAGE = new int[CHAPTERS.length];
        int page = 0;
        for (int c = 0; c < CHAPTERS.length; c++) {
            CHAPTER_FIRST_PAGE[c] = page;
            for (int p = 0; p < CHAPTERS[c].pages(); p++) {
                CHAPTER_OF_PAGE[page] = c;
                PAGE_IN_CHAPTER[page] = p;
                page++;
            }
        }
    }

    // -- layout: a wide window in the plaque screen's own material, a narrow
    // chapter column on the left and the reading column on the right --
    private static final int PANEL_W = 320;
    private static final int PANEL_H = 264;
    private static final int PAD = HsUiTokens.PAD;
    // 78 clipped the Norwegian sidebar entry "Grunnleggelse" (72px against
    // the 70px labelIn box, SIDE_W - 8) by 2px. 84 clears it; the 6px taken
    // from CONTENT_W still leaves the widest chapter's prose at 135px of a
    // 152px wrap budget in the worst-case (nb attributes2) -- see the
    // ui_preview specs under tools/ui/specs/.
    private static final int SIDE_W = 84;
    private static final int GAP = 6;
    private static final int CONTENT_X = PAD + SIDE_W + GAP;
    private static final int CONTENT_W = PANEL_W - CONTENT_X - PAD;

    private static final int TITLE_Y = 10;
    private static final int DIVIDER1_Y = 24;
    private static final int SIDEBAR_Y0 = 30;
    private static final int SIDEBAR_ROW_H = 14;
    private static final int SIDEBAR_STEP = 16;
    private static final int CONTENT_TITLE_Y = 30;
    private static final int CONTENT_DIVIDER_Y = 42;
    private static final int BODY_Y = 48;
    // Body text runs from BODY_Y to DIVIDER3_Y -- roughly 150px, chosen with
    // headroom over the longest chapter page (~500 characters at this
    // column's width) rather than tuned exactly to it: Norwegian runs longer
    // than English for the same sentence and its æøå draw from a taller
    // glyph sheet (see the minecraft-ui skill), so the safe margin is for
    // the translation, not just the source text this was measured against.
    private static final int DIVIDER3_Y = 200;
    private static final int PAGELIST_Y = 206;
    private static final int PAGELIST_H = 14;
    private static final int PAGELIST_DOT_W = 16;
    private static final int COUNTER_Y = 224;
    private static final int NAV_Y = 236;
    private static final int ARROW_W = 40;
    private static final int CLOSE_W = 90;

    private int page;
    private int left;
    private int top;

    public HandbookScreen() {
        super(Component.translatable("hearthstead.guide.title"));
    }

    @Override
    protected void init() {
        left = (width - PANEL_W) / 2;
        top = (height - PANEL_H) / 2;
        rebuild();
    }

    /** Rebuilds every widget for the current page. Cheap: at most thirteen buttons. */
    private void rebuild() {
        clearWidgets();

        int currentChapter = CHAPTER_OF_PAGE[page];
        for (int c = 0; c < CHAPTERS.length; c++) {
            int target = CHAPTER_FIRST_PAGE[c];
            addRenderableWidget(new NavButton(
                left + PAD, top + SIDEBAR_Y0 + c * SIDEBAR_STEP, SIDE_W, SIDEBAR_ROW_H,
                chapterTitle(c), c == currentChapter, () -> turnTo(target)));
        }

        // The page list: only a chapter with something to list gets one, so
        // a single-page chapter draws no row here at all rather than one
        // dot that can never do anything (D-014).
        Chapter chapter = CHAPTERS[currentChapter];
        if (chapter.pages() > 1) {
            int first = CHAPTER_FIRST_PAGE[currentChapter];
            int listW = chapter.pages() * PAGELIST_DOT_W + (chapter.pages() - 1) * 2;
            int startX = left + CONTENT_X + (CONTENT_W - listW) / 2;
            for (int p = 0; p < chapter.pages(); p++) {
                int target = first + p;
                addRenderableWidget(new NavButton(
                    startX + p * (PAGELIST_DOT_W + 2), top + PAGELIST_Y,
                    PAGELIST_DOT_W, PAGELIST_H,
                    Component.literal(Integer.toString(p + 1)), target == page,
                    () -> turnTo(target)));
            }
        }

        HsButton prevButton = HsButton.normal(left + PAD, top + NAV_Y, ARROW_W,
            HsUiTokens.BUTTON_H, Component.literal("<"), () -> turnTo(page - 1));
        HsButton nextButton = HsButton.normal(left + PANEL_W - PAD - ARROW_W, top + NAV_Y,
            ARROW_W, HsUiTokens.BUTTON_H, Component.literal(">"), () -> turnTo(page + 1));
        prevButton.active = page > 0;
        nextButton.active = page < TOTAL_PAGES - 1;
        addRenderableWidget(prevButton);
        addRenderableWidget(nextButton);
        addRenderableWidget(HsButton.normal(left + (PANEL_W - CLOSE_W) / 2, top + NAV_Y,
            CLOSE_W, HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.plaque.close"), this::onClose));
    }

    private void turnTo(int target) {
        if (target < 0 || target >= TOTAL_PAGES) {
            return;
        }
        page = target;
        rebuild();
    }

    private static Component chapterTitle(int chapterIndex) {
        return Component.translatable("hearthstead.guide." + CHAPTERS[chapterIndex].id() + ".title");
    }

    /** Body key for a global page: ".body" for a chapter's first page, ".body2" and up after. */
    private static Component bodyFor(int globalPage) {
        Chapter chapter = CHAPTERS[CHAPTER_OF_PAGE[globalPage]];
        int withinChapter = PAGE_IN_CHAPTER[globalPage];
        String suffix = withinChapter == 0 ? "" : Integer.toString(withinChapter + 1);
        return Component.translatable("hearthstead.guide." + chapter.id() + ".body" + suffix);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);

        HsUi.window(graphics, left, top, PANEL_W, PANEL_H);
        HsUi.centred(graphics, font, Component.translatable("hearthstead.guide.title"),
            left + PANEL_W / 2, top + TITLE_Y, HsUiTokens.TEXT_STRONG);
        HsUi.divider(graphics, left + PAD, top + DIVIDER1_Y, PANEL_W - 2 * PAD);

        int chapterIndex = CHAPTER_OF_PAGE[page];
        Chapter chapter = CHAPTERS[chapterIndex];
        HsUi.label(graphics, font, chapterTitle(chapterIndex),
            left + CONTENT_X, top + CONTENT_TITLE_Y, HsUiTokens.TEXT_STRONG);
        if (chapter.pages() > 1) {
            String sub = (PAGE_IN_CHAPTER[page] + 1) + "/" + chapter.pages();
            HsUi.right(graphics, font, Component.literal(sub),
                left + CONTENT_X + CONTENT_W, top + CONTENT_TITLE_Y, HsUiTokens.TEXT_MUTED);
        }
        HsUi.divider(graphics, left + CONTENT_X, top + CONTENT_DIVIDER_Y, CONTENT_W);
        graphics.drawWordWrap(font, bodyFor(page), left + CONTENT_X, top + BODY_Y,
            CONTENT_W, HsUiTokens.TEXT);

        HsUi.divider(graphics, left + PAD, top + DIVIDER3_Y, PANEL_W - 2 * PAD);
        String counter = (page + 1) + " / " + TOTAL_PAGES;
        HsUi.centred(graphics, font, Component.literal(counter),
            left + PANEL_W / 2, top + COUNTER_Y, HsUiTokens.TEXT_MUTED);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * A navigation entry that looks like a tab and says where you are —
     * shared by the chapter index and the page list, since both are the same
     * idea at a different grain: a labelled jump to somewhere in the book.
     */
    private static final class NavButton extends AbstractButton {
        private final boolean selected;
        private final Runnable onPress;

        private NavButton(int x, int y, int w, int h, Component label, boolean selected,
                          Runnable onPress) {
            super(x, y, w, h, label);
            this.selected = selected;
            this.onPress = onPress;
        }

        @Override
        public void onPress() {
            onPress.run();
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY,
                                    float partialTick) {
            HsUi.tab(graphics, getX(), getY(), getWidth(), getHeight(), selected);
            HsUi.labelIn(graphics, Minecraft.getInstance().font, getMessage(),
                getX() + 4, getY() + (getHeight() - HsUiTokens.TEXT_H) / 2,
                getWidth() - 8, selected ? HsUiTokens.TEXT : HsUiTokens.TEXT_MUTED);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
