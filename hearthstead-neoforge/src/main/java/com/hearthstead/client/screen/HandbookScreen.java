package com.hearthstead.client.screen;

import com.hearthstead.client.QaClientObserver;
import com.hearthstead.client.QaUiInspectable;
import com.hearthstead.client.ui.HsMotion;
import com.hearthstead.client.ui.HsUi;
import com.hearthstead.client.ui2.BannerChrome;
import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import com.hearthstead.client.ui2.Ui2Hud;
import com.hearthstead.client.ui2.Ui2Palette;
import com.hearthstead.client.ui2.Ui2Tips;
import com.hearthstead.client.ui2.Ui2Serif;
import com.hearthstead.client.ui2.Ui2Surface;
import com.hearthstead.client.ui2.Ui2WoodKey;
import com.hearthstead.client.ui2.handbook.HandbookBook;
import com.hearthstead.client.ui2.handbook.HandbookBook.Chapter;
import com.hearthstead.client.ui2.handbook.HandbookBook.Page;
import com.hearthstead.client.ui2.handbook.HandbookGeometry;
import com.hearthstead.client.ui2.handbook.HandbookKeys;
import com.hearthstead.client.ui2.handbook.HandbookLoader;
import com.hearthstead.client.ui2.handbook.HandbookPageLayout;
import com.hearthstead.client.ui2.handbook.HandbookPageLayout.Box;
import com.hearthstead.client.ui2.handbook.HandbookPageLayout.Kind;
import com.hearthstead.client.ui2.handbook.HandbookPageLayout.Line;
import com.hearthstead.client.ui2.handbook.HandbookPlateButton;
import com.hearthstead.client.ui2.handbook.HandbookRecipes;
import com.hearthstead.client.ui2.handbook.HandbookSearch;
import com.hearthstead.client.ui2.handbook.HandbookState;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Settler's Handbook, rebuilt as a picture book in the Banner screen's
 * materials: walnut board, parchment page, serif small-caps headings and a
 * burgundy "you are here".
 *
 * <p>All content is data ({@code assets/hearthstead/handbook/}, parsed by
 * {@link HandbookBook}); this class owns only presentation. Each page shows,
 * in a fixed order, a picture, a heading, 2-4 short bullets, key chips that
 * print the player's REAL bindings, item icons from the registry, a "Try it"
 * tip and a folded "More detail" section holding the long-form text.
 *
 * <p>Navigation: the chapter rail on the left (grouped, scrollable, with
 * search across every page), Back/Next plates that step through the whole
 * book, and a dot per page of the current chapter. The book re-opens on the
 * last page read ({@link HandbookState}); other screens open it at a page
 * with {@link #openPage} or {@link #openForJourney}.
 *
 * <p>Geometry is pure ({@link HandbookGeometry}, {@link HandbookPageLayout})
 * and JUnit-checked at GUI scales 2-4: body text wraps and scrolls, it is
 * never shortened.
 */
public class HandbookScreen extends Screen implements QaUiInspectable {
    private static final Component BOOK_TITLE = Component.translatable("hearthstead.guide.title");
    private static final int RAIL_ROW_H = 18;
    private static final int RAIL_GROUP_H = 13;
    private static final int RAIL_SCROLL_W = 4;
    /** Idle label ink on a wooden plate, the same value Ui2NavButton uses. */
    public static final int NAV_IDLE_INK = BannerChrome.TEXT_ON_WOOD_IDLE;
    private RailEntry tipRow;
    private long tipSince;

    private final HandbookBook book;
    private final String requestedPage;
    private HandbookGeometry geo;
    private int left;
    private int top;
    private Page page;
    private int scroll;
    private int maxScroll;
    private int railScroll;
    private int railMaxScroll;
    private HandbookPageLayout.Result layout;
    private final List<ItemStack> pageItems = new ArrayList<>();
    private final List<HandbookRecipes.Grid> pageRecipes = new ArrayList<>();
    private final Set<String> openDetails = new HashSet<>();
    private EditBox search;
    private String query = "";
    private final List<Page> results = new ArrayList<>();
    private final List<RailEntry> railEntries = new ArrayList<>();
    private final Ui2Serif.Text titleText = new Ui2Serif.Text(Ui2Serif.Size.TITLE);
    private final Ui2Serif.Text pageTitle = new Ui2Serif.Text(Ui2Serif.Size.HEADING);
    private final Ui2Serif.Text groupText = new Ui2Serif.Text(Ui2Serif.Size.HEADING);
    private boolean uiSoundActive;
    private HsMotion.ScreenIntro hsIntro;
    private boolean hsIntroRendering;

    /** One row of the rail: a group caption, a chapter, or a search hit. */
    private record RailEntry(int y, int h, HandbookBook.Group group, Chapter chapter, Page hit) {
    }

    public HandbookScreen() {
        this(null);
    }

    public HandbookScreen(String pageId) {
        super(BOOK_TITLE);
        this.book = HandbookLoader.load();
        this.requestedPage = pageId;
    }

    /** Opens the book at a page id ({@code chapter.slug}); unknown ids fall back to the last page read. */
    public static void openPage(String pageId) {
        net.minecraft.client.Minecraft.getInstance().setScreen(new HandbookScreen(pageId));
    }

    /** Opens the page that explains a Journey step, or the book as last read. */
    public static void openForJourney(String stepId) {
        HandbookScreen screen = new HandbookScreen(null);
        Page p = screen.book.pageForJourney(stepId);
        if (p != null) screen.page = p;
        net.minecraft.client.Minecraft.getInstance().setScreen(screen);
    }

    /** True when some page explains this Journey step (the "?" button is only drawn then). */
    public static boolean hasJourneyPage(HandbookBook book, String stepId) {
        return book.pageForJourney(stepId) != null;
    }

    @Override
    protected void init() {
        geo = HandbookGeometry.forViewport(width, height);
        left = (width - geo.width()) / 2;
        top = (height - geo.height()) / 2;
        if (page == null) {
            page = book.page(requestedPage);
            if (page == null) page = book.page(HandbookState.lastPage());
            if (page == null && !book.pages().isEmpty()) page = book.pages().get(0);
        }
        if (!uiSoundActive) {
            uiSoundActive = true;
            HsUi.playOpenSound();
        }
        rebuild();
        revealCurrentChapterInRail();
    }

    @Override
    public void removed() {
        if (uiSoundActive) {
            uiSoundActive = false;
            HsUi.playCloseSound();
        }
        if (page != null) HandbookState.setLastPage(page.id());
        super.removed();
    }

    // ---------------------------------------------------------------- build

    private void rebuild() {
        clearWidgets();
        Rect s = abs(geo.search());
        String keep = search == null ? query : search.getValue();
        boolean focused = search != null && search.isFocused();
        // 14px for the glyph on the left, 6px clear of the box edge on the right (QA nit a).
        search = new EditBox(font, s.x() + 14, s.y() + 3, s.width() - 20, s.height() - 4,
            Component.translatable("hearthstead.guide.ui.search"));
        search.setBordered(false);
        search.setTextColor(BannerChrome.TEXT_ON_WOOD);
        search.setMaxLength(40);
        search.setHint(Component.translatable("hearthstead.guide.ui.search").withStyle(
            Style.EMPTY.withColor(BannerChrome.TEXT_ON_WOOD_MUTED)));
        search.setValue(keep);
        search.setResponder(this::onSearch);
        addRenderableWidget(search);
        if (focused) setFocused(search);

        Rect c = abs(geo.close());
        addRenderableWidget(new Ui2WoodKey(c.x(), c.y(), c.width(), c.height(), Component.literal("×"), this::onClose));
        Rect p = abs(geo.prev());
        HandbookPlateButton prev = new HandbookPlateButton(p.x(), p.y(), p.width(), p.height(),
            Component.translatable("hearthstead.guide.ui.back"), false, () -> step(-1));
        Rect n = abs(geo.next());
        HandbookPlateButton next = new HandbookPlateButton(n.x(), n.y(), n.width(), n.height(),
            Component.translatable("hearthstead.guide.ui.next"), true, () -> step(1));
        Ui2Tips.enable(prev, page != null && page.globalIndex() > 0, null,
            Component.translatable("hearthstead.guide.nav.first"));
        Ui2Tips.enable(next, page != null && page.globalIndex() < book.pages().size() - 1, null,
            Component.translatable("hearthstead.guide.nav.last"));
        addRenderableWidget(prev);
        addRenderableWidget(next);
        computeResults();
        buildRail();
        relayout();
    }

    private void relayout() {
        pageItems.clear();
        pageRecipes.clear();
        if (page == null) {
            layout = null;
            maxScroll = 0;
            return;
        }
        Chapter chapter = book.chapterOf(page);
        String chapterTitle = tr(chapter.titleKey());
        String chapterLine = chapter.pages().size() > 1
            ? chapterTitle + "  ·  " + (page.indexInChapter() + 1) + " / " + chapter.pages().size()
            : chapterTitle;
        String title = page.titleKey() != null ? tr(page.titleKey()) : chapterTitle;
        HandbookBook.Image img = page.image();
        boolean hasImg = img != null && HandbookLoader.textureExists(img.texture());
        for (String id : page.items()) {
            ResourceLocation loc = ResourceLocation.tryParse(id);
            if (loc == null) continue;
            var item = BuiltInRegistries.ITEM.get(loc);
            if (item != Items.AIR) pageItems.add(new ItemStack(item));
        }
        List<HandbookPageLayout.Chip> chips = new ArrayList<>();
        for (HandbookBook.KeyChip k : page.keys()) chips.add(HandbookKeys.chip(k));
        for (String recipeId : page.recipes()) pageRecipes.add(HandbookRecipes.grid(recipeId));
        Page linked = book.page(page.link());
        String link = linked == null ? null : "› " + (linked.titleKey() != null ? tr(linked.titleKey())
            : tr(book.chapterOf(linked).titleKey()));
        boolean open = openDetails.contains(page.id());
        HandbookPageLayout.Content content = new HandbookPageLayout.Content(chapterLine, title,
            hasImg ? img.width() : 0, hasImg ? img.height() : 0,
            hasImg ? img.placement() : HandbookBook.Placement.AUTO,
            hasImg && img.captionKey() != null ? tr(img.captionKey()) : null,
            trAll(page.bullets()), chips, pageItems.size(),
            tr("hearthstead.guide.ui.try_it"), page.tipKey() == null ? null : tr(page.tipKey()),
            tr(open ? "hearthstead.guide.ui.less" : "hearthstead.guide.ui.more"),
            trAll(page.text()), open,
            tr("hearthstead.guide.ui.get"), page.obtainKey() == null ? null : tr(page.obtainKey()),
            pageRecipes.size(), tr("hearthstead.guide.ui.use"), trAll(page.steps()), link, entryRows(),
            gateLines());
        layout = HandbookPageLayout.layout(content, geo.content().width(), geo.content().height(),
            Ui2Serif.guiScale(), measure());
        maxScroll = Math.max(0, layout.height() - geo.content().height());
        scroll = Math.max(0, Math.min(scroll, maxScroll));
    }

    private final List<String> pageGates = new ArrayList<>();

    /** One "Unlocked by" line per distinct Tech Tree node gating this page's recipes. */
    private List<String> gateLines() {
        pageGates.clear();
        List<String> out = new ArrayList<>();
        for (HandbookRecipes.Grid grid : pageRecipes) {
            if (grid.gateNode() == null || pageGates.contains(grid.gateNode())) continue;
            pageGates.add(grid.gateNode());
            var def = com.hearthstead.settlement.techtree.TechTreeData.get().node(grid.gateNode());
            Component name = def != null ? def.displayName() : Component.literal(grid.gateNode());
            boolean learned = com.hearthstead.client.techtree.TechKnowledgeClient.isLearned(grid.gateNode());
            out.add(Component.translatable(learned ? "hearthstead.guide.ui.unlocked_by_done"
                : "hearthstead.guide.ui.unlocked_by", name).getString());
        }
        return out;
    }

    private final List<ItemStack> entryIcons = new ArrayList<>();

    private List<HandbookPageLayout.EntryRow> entryRows() {
        entryIcons.clear();
        List<HandbookPageLayout.EntryRow> rows = new ArrayList<>();
        for (HandbookBook.Entry e : page.entries()) {
            rows.add(new HandbookPageLayout.EntryRow(tr(e.nameKey()), e.textKey() == null ? null : tr(e.textKey())));
            entryIcons.add(stackFor(e.icon()));
        }
        return rows;
    }

    private static ItemStack stackFor(String id) {
        ResourceLocation loc = id == null ? null : ResourceLocation.tryParse(id);
        var item = loc == null ? Items.AIR : BuiltInRegistries.ITEM.get(loc);
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    private HandbookPageLayout.Measure measure() {
        Font f = font;
        return new HandbookPageLayout.Measure() {
            @Override
            public int width(String text) {
                return f.width(text);
            }

            @Override
            public List<String> wrap(String text, int w) {
                List<String> out = new ArrayList<>();
                for (var line : f.getSplitter().splitLines(text, w, Style.EMPTY)) out.add(line.getString());
                if (out.isEmpty()) out.add("");
                return out;
            }

            @Override
            public int titleWidth(String text) {
                return f.width(Ui2Serif.smallCaps(text, Ui2Serif.Size.HEADING, Ui2Serif.guiScale()));
            }
        };
    }

    private void buildRail() {
        railEntries.clear();
        int y = 0;
        if (!query.isBlank()) {
            for (Page hit : results) {
                railEntries.add(new RailEntry(y, RAIL_ROW_H, null, book.chapterOf(hit), hit));
                y += RAIL_ROW_H;
            }
        } else {
            for (HandbookBook.Group group : book.groups()) {
                boolean any = false;
                for (Chapter c : book.chapters()) {
                    if (group.id() != null && group.id().equals(c.groupId())) {
                        if (!any && group.titleKey() != null) {
                            railEntries.add(new RailEntry(y, RAIL_GROUP_H, group, null, null));
                            y += RAIL_GROUP_H;
                        }
                        any = true;
                        railEntries.add(new RailEntry(y, RAIL_ROW_H, null, c, null));
                        y += RAIL_ROW_H;
                    }
                }
            }
        }
        railMaxScroll = Math.max(0, y - geo.railList().height());
        railScroll = Math.max(0, Math.min(railScroll, railMaxScroll));
    }

    private void revealCurrentChapterInRail() {
        if (page == null || !query.isBlank()) return;
        for (RailEntry e : railEntries) {
            if (e.chapter() != null && e.chapter().index() == page.chapterIndex()) {
                int viewH = geo.railList().height();
                if (e.y() < railScroll) railScroll = Math.max(0, e.y() - RAIL_GROUP_H);
                else if (e.y() + e.h() > railScroll + viewH) railScroll = e.y() + e.h() - viewH;
                railScroll = Math.max(0, Math.min(railScroll, railMaxScroll));
                return;
            }
        }
    }

    // --------------------------------------------------------------- search

    private void onSearch(String value) {
        if (value.equals(query)) return;
        query = value;
        railScroll = 0;
        computeResults();
        buildRail();
        QaClientObserver.markUiTransition("handbook_search");
    }

    private void computeResults() {
        results.clear();
        String q = query.toLowerCase(Locale.ROOT).trim();
        if (q.isEmpty()) return;
        String[] terms = q.split("\\s+");
        // Ranked (QA nit d): page titles first, then headings, then bullets/steps, then detail.
        List<String> titles = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        List<String> details = new ArrayList<>();
        for (Page p : book.pages()) {
            titles.add((p.titleKey() != null ? tr(p.titleKey()) : "").toLowerCase(Locale.ROOT));
            headings.add(tr(book.chapterOf(p).titleKey()).toLowerCase(Locale.ROOT));
            StringBuilder body = new StringBuilder();
            for (String k : p.bullets()) body.append(tr(k)).append(' ');
            for (String k : p.steps()) body.append(tr(k)).append(' ');
            if (p.tipKey() != null) body.append(tr(p.tipKey())).append(' ');
            if (p.obtainKey() != null) body.append(tr(p.obtainKey()));
            bodies.add(body.toString().toLowerCase(Locale.ROOT));
            StringBuilder detail = new StringBuilder();
            for (String k : p.text()) detail.append(tr(k)).append(' ');
            details.add(detail.toString().toLowerCase(Locale.ROOT));
        }
        results.addAll(HandbookSearch.rank(book.pages(), terms, titles, headings, bodies, details, 60));
    }

    // ----------------------------------------------------------- navigation

    private void turnTo(Page target) {
        if (target == null || target == page) return;
        page = target;
        scroll = 0;
        QaClientObserver.markUiTransition("handbook_page");
        HandbookState.setLastPage(page.id());
        rebuild();
        revealCurrentChapterInRail();
    }

    private void step(int delta) {
        if (page == null) return;
        int i = page.globalIndex() + delta;
        if (i >= 0 && i < book.pages().size()) turnTo(book.pages().get(i));
    }

    private void stepChapter(int delta) {
        if (page == null) return;
        int c = page.chapterIndex() + delta;
        if (c >= 0 && c < book.chapters().size()) turnTo(book.chapter(c).first());
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (button != 0) return false;
        Rect list = abs(geo.railList());
        if (list.contains(mx, my)) {
            RailEntry e = railAt(my);
            if (e != null && e.group() == null) {
                playClick();
                turnTo(e.hit() != null ? e.hit() : e.chapter().first());
                return true;
            }
        }
        int dot = dotAt(mx, my);
        if (dot >= 0) {
            playClick();
            turnTo(book.chapterOf(page).pages().get(dot));
            return true;
        }
        Rect content = abs(geo.content());
        if (layout != null && content.contains(mx, my)) {
            Box toggle = layout.first(Kind.DETAILS_TOGGLE);
            int ly = (int) my - content.y() + scroll;
            int lx = (int) mx - content.x();
            Box linkBox = layout.first(Kind.LINK);
            if (linkBox != null && lx >= linkBox.x() && lx < linkBox.right() && ly >= linkBox.y()
                && ly < linkBox.bottom() && book.page(page.link()) != null) {
                playClick();
                turnTo(book.page(page.link()));
                return true;
            }
            if (toggle != null && lx >= toggle.x() && lx < toggle.right() && ly >= toggle.y() && ly < toggle.bottom()) {
                playClick();
                if (!openDetails.remove(page.id())) openDetails.add(page.id());
                relayout();
                QaClientObserver.markUiTransition("handbook_details");
                return true;
            }
        }
        return false;
    }

    private RailEntry railAt(double my) {
        Rect list = abs(geo.railList());
        int ly = (int) my - list.y() + railScroll;
        for (RailEntry e : railEntries) if (ly >= e.y() && ly < e.y() + e.h()) return e;
        return null;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (abs(geo.rail()).contains(mx, my) && railMaxScroll > 0) {
            railScroll = Math.max(0, Math.min(railMaxScroll, railScroll - (int) Math.signum(dy) * RAIL_ROW_H));
            return true;
        }
        if (abs(geo.page()).contains(mx, my) && maxScroll > 0) {
            scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(dy) * 20));
            QaClientObserver.markUiTransition("handbook_body_scroll");
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        boolean typing = search != null && search.isFocused();
        if (key == GLFW.GLFW_KEY_F && (mods & GLFW.GLFW_MOD_CONTROL) != 0) {
            setFocused(search);
            return true;
        }
        if (typing && key == GLFW.GLFW_KEY_ENTER && !results.isEmpty()) {
            turnTo(results.get(0));
            return true;
        }
        if (!typing) {
            switch (key) {
                case GLFW.GLFW_KEY_RIGHT -> {
                    step(1);
                    return true;
                }
                case GLFW.GLFW_KEY_LEFT -> {
                    step(-1);
                    return true;
                }
                case GLFW.GLFW_KEY_DOWN -> {
                    scroll = Math.min(maxScroll, scroll + 10);
                    return true;
                }
                case GLFW.GLFW_KEY_UP -> {
                    scroll = Math.max(0, scroll - 10);
                    return true;
                }
                case GLFW.GLFW_KEY_PAGE_DOWN -> {
                    stepChapter(1);
                    return true;
                }
                case GLFW.GLFW_KEY_PAGE_UP -> {
                    stepChapter(-1);
                    return true;
                }
                default -> {
                }
            }
        }
        return super.keyPressed(key, scan, mods);
    }

    // --------------------------------------------------------------- render

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (hsIntro == null) hsIntro = new HsMotion.ScreenIntro();
        if (!hsIntroRendering && !hsIntro.done()) {
            hsIntroRendering = true;
            try {
                hsIntro.render(g, 4.0F, () -> render(g, mouseX, mouseY, partialTick));
            } finally {
                hsIntroRendering = false;
            }
            return;
        }
        renderBackground(g, mouseX, mouseY, partialTick);
        BannerChrome.panel(g, left, top, geo.width(), geo.height());
        Rect crest = abs(geo.crest());
        BannerChrome.crest(g, crest.x(), crest.y(), crest.width(), crest.height());
        Rect title = abs(geo.title());
        titleText.fit(font, BOOK_TITLE.getString(), title.width());
        titleText.draw(g, font, title.x(), title.y() + (title.height() - 10) / 2, BannerChrome.TEXT_ON_WOOD);
        Rect header = abs(geo.header());
        g.fill(header.x(), header.bottom() + 1, header.right(), header.bottom() + 2, BannerChrome.PLATE_SHADOW);
        g.fill(header.x(), header.bottom() + 2, header.right(), header.bottom() + 3, BannerChrome.PLATE_HIGHLIGHT);

        Rect s = abs(geo.search());
        BannerChrome.counterBox(g, s.x(), s.y(), s.width(), s.height());
        searchGlyph(g, s.x() + 4, s.y() + 3, BannerChrome.TEXT_ON_WOOD_MUTED);

        Component hover = renderRail(g, mouseX, mouseY);
        Rect pageRect = abs(geo.page());
        BannerChrome.parchment(g, pageRect.x(), pageRect.y(), pageRect.width(), pageRect.height());
        ItemStack hoveredItem = renderPage(g, mouseX, mouseY);
        Component dotHover = renderDots(g, mouseX, mouseY);

        // Widgets only: Screen.render would paint the blurred background again over the board.
        HsUi.widgets(this, g, mouseX, mouseY, partialTick);

        if (hoveredItem != null) {
            g.renderTooltip(font, hoveredItem, mouseX, mouseY);
        } else if (hover != null) {
            g.renderTooltip(font, hover, mouseX, mouseY);
        } else if (dotHover != null) {
            g.renderTooltip(font, dotHover, mouseX, mouseY);
        }
    }

    private Component renderRail(GuiGraphics g, int mouseX, int mouseY) {
        Rect list = abs(geo.railList());
        boolean scrolls = railMaxScroll > 0;
        int rowW = list.width() - (scrolls ? RAIL_SCROLL_W + 2 : 0);
        Component tooltip = null;
        g.enableScissor(list.x(), list.y(), list.right(), list.bottom());
        if (!query.isBlank() && railEntries.isEmpty()) {
            g.drawString(font, Component.translatable("hearthstead.guide.ui.no_results"), list.x() + 3, list.y() + 3,
                BannerChrome.TEXT_ON_WOOD_MUTED, false);
        }
        for (RailEntry e : railEntries) {
            int y = list.y() + e.y() - railScroll;
            if (y + e.h() < list.y() || y > list.bottom()) continue;
            if (e.group() != null) {
                groupText.fit(font, tr(e.group().titleKey()), rowW - 4);
                groupText.draw(g, font, list.x() + 2, y + 3, BannerChrome.GOLD_EDGE);
                int rx = list.x() + 2 + groupText.width() + 4;
                if (rx < list.x() + rowW) g.fill(rx, y + 7, list.x() + rowW, y + 8, Ui2Hud.fade(BannerChrome.GOLD_EDGE, 0.4F));
                continue;
            }
            boolean selected = e.hit() != null ? e.hit() == page
                : page != null && e.chapter().index() == page.chapterIndex();
            boolean hovered = mouseX >= list.x() && mouseX < list.x() + rowW && mouseY >= y && mouseY < y + e.h()
                && list.contains(mouseX, mouseY);
            BannerChrome.navPlate(g, list.x(), y, rowW, e.h() - 2, selected, hovered ? 1.0F : 0.0F);
            ItemStack icon = iconFor(e.chapter());
            g.pose().pushPose();
            g.pose().translate(list.x() + 2, y, 0);
            g.pose().scale(1.0F, 1.0F, 1.0F);
            g.renderItem(icon, 0, 0);
            g.pose().popPose();
            String label = e.hit() != null
                ? (e.hit().titleKey() != null ? tr(e.hit().titleKey()) : tr(e.chapter().titleKey()))
                : tr(e.chapter().titleKey());
            int room = rowW - 22;
            HsUi.FittedLabel fitted = HsUi.fitLabel(font, Component.literal(label), room);
            g.pose().pushPose();
            g.pose().translate(0, 0, 200);
            int ink = selected || hovered ? BannerChrome.TEXT_ON_WOOD : NAV_IDLE_INK;
            g.drawString(font, fitted.text(), list.x() + 20, y + (e.h() - 2 - 8) / 2 + 1, ink, false);
            g.pose().popPose();
            if (hovered && tipRow != e) {
                tipRow = e;
                tipSince = System.currentTimeMillis();
            }
            boolean tipReady = System.currentTimeMillis() - tipSince >= Ui2Tips.NAV_DELAY.toMillis();
            if (hovered && tipReady && (fitted.width() < font.width(label) || e.hit() != null)) {
                tooltip = e.hit() != null
                    ? Component.literal(label + "  —  " + tr(e.chapter().titleKey()))
                    : Component.literal(label);
            }
        }
        g.disableScissor();
        if (scrolls) {
            int h = list.height();
            int trackX = list.right() - RAIL_SCROLL_W;
            g.fill(trackX + 1, list.y(), trackX + 3, list.bottom(), BannerChrome.INSET_DARK);
            int thumb = Math.max(10, h * h / (h + railMaxScroll));
            int off = Math.round((h - thumb) * (railScroll / (float) railMaxScroll));
            g.fill(trackX, list.y() + off, trackX + RAIL_SCROLL_W, list.y() + off + thumb, BannerChrome.PLATE_HIGHLIGHT);
            g.fill(trackX, list.y() + off, trackX + RAIL_SCROLL_W, list.y() + off + 1, BannerChrome.GOLD_EDGE);
        }
        return tooltip;
    }

    private ItemStack renderPage(GuiGraphics g, int mouseX, int mouseY) {
        if (layout == null) {
            Rect c = abs(geo.content());
            g.drawString(font, Component.translatable("hearthstead.guide.ui.empty"), c.x(), c.y(), Ui2Palette.INK_MUTED, false);
            return null;
        }
        Rect c = abs(geo.content());
        int ox = c.x();
        int oy = c.y() - scroll;
        ItemStack hovered = null;
        g.enableScissor(c.x() - 2, c.y() - 1, c.right() + 2, c.bottom() + 1);
        for (Box b : layout.boxes()) {
            int x = ox + b.x();
            int y = oy + b.y();
            switch (b.kind()) {
                case RULE -> g.fill(x, y, x + b.w(), y + 1, Ui2Palette.RULE_STRONG);
                case IMAGE -> drawImage(g, x, y, b);
                case BULLET_MARK -> Ui2Surface.alertGlyph(g, x, y, Ui2Palette.BURGUNDY);
                case CAP -> keyCap(g, x, y, b.w(), b.h());
                case ITEM -> {
                    Ui2Surface.slotWell(g, x, y);
                    if (b.index() < pageItems.size()) {
                        g.renderItem(pageItems.get(b.index()), x + 1, y + 1);
                        if (mouseX >= x && mouseX < x + b.w() && mouseY >= y && mouseY < y + b.h()
                            && c.contains(mouseX, mouseY)) {
                            hovered = pageItems.get(b.index());
                        }
                    }
                }
                case TIP_BOX -> {
                    g.fill(x, y, x + b.w(), y + b.h(), Ui2Hud.fade(Ui2Palette.AMBER, 0.15F));
                    g.fill(x, y, x + 2, y + b.h(), Ui2Palette.BURGUNDY);
                    g.fill(x + 2, y, x + b.w(), y + 1, Ui2Hud.fade(Ui2Palette.AMBER, 0.25F));
                    g.fill(x + 2, y + b.h() - 1, x + b.w(), y + b.h(), Ui2Hud.fade(Ui2Palette.AMBER, 0.25F));
                }
                case RECIPE -> {
                    ItemStack over = drawRecipe(g, x, y, b, mouseX, mouseY, c.contains(mouseX, mouseY));
                    if (over != null) hovered = over;
                }
                case GATE_ICON -> {
                    if (b.index() < pageGates.size()) drawNodeIcon(g, pageGates.get(b.index()), x, y, 12);
                }
                case ENTRY_ICON -> {
                    if (b.index() < entryIcons.size() && !entryIcons.get(b.index()).isEmpty()) {
                        g.renderItem(entryIcons.get(b.index()), x, y);
                    }
                }
                case STEP_MARK -> {
                    String n = (b.index() + 1) + ".";
                    g.drawString(font, n, x, y, Ui2Palette.BURGUNDY, false);
                }
                case LINK -> {
                    boolean over = mouseX >= x && mouseX < x + b.w() && mouseY >= y && mouseY < y + b.h()
                        && c.contains(mouseX, mouseY);
                    if (over) g.fill(x, y, x + b.w(), y + b.h(), Ui2Palette.ROW_HOVER);
                }
                case DETAILS_TOGGLE -> {
                    boolean over = mouseX >= x && mouseX < x + b.w() && mouseY >= y && mouseY < y + b.h()
                        && c.contains(mouseX, mouseY);
                    if (over) g.fill(x, y, x + b.w(), y + b.h(), Ui2Palette.ROW_HOVER);
                    g.fill(x, y, x + b.w(), y + 1, Ui2Palette.RULE);
                    triangle(g, x + 2, y + 5, openDetails.contains(page.id()), Ui2Palette.INK_SOFT);
                }
                default -> {
                }
            }
        }
        for (Line l : layout.lines()) {
            int x = ox + l.x();
            int y = oy + l.y();
            if (y > c.bottom() || y + 10 < c.y()) continue;
            switch (l.kind()) {
                case CHAPTER -> g.drawString(font, l.text(), x, y, Ui2Palette.INK_MUTED, false);
                case TITLE_SERIF -> {
                    pageTitle.set(font, l.text());
                    pageTitle.draw(g, font, x, y, Ui2Palette.INK);
                }
                case TITLE -> g.drawString(font, l.text(), x, y, Ui2Palette.INK, false);
                case CAPTION -> g.drawString(font, l.text(), x, y, Ui2Palette.INK_MUTED, false);
                case BULLET, DETAIL, TIP -> g.drawString(font, l.text(), x, y, Ui2Palette.INK, false);
                case CHIP_PREFIX -> g.drawString(font, l.text(), x, y, Ui2Palette.INK_SOFT, false);
                case CAP_TEXT -> g.drawString(font, l.text(), x, y, BannerChrome.TEXT_ON_WOOD, false);
                case CHIP_ACTION -> g.drawString(font, l.text(), x, y, Ui2Palette.INK, false);
                case TIP_LABEL -> g.drawString(font, l.text().toUpperCase(Locale.ROOT), x, y, Ui2Palette.BURGUNDY, false);
                case DETAILS_LABEL -> g.drawString(font, l.text(), x, y, Ui2Palette.INK_SOFT, false);
                case SECTION -> g.drawString(font, l.text().toUpperCase(Locale.ROOT), x, y, Ui2Palette.GOLD, false);
                case OBTAIN, STEP -> g.drawString(font, l.text(), x, y, Ui2Palette.INK, false);
                case ENTRY_NAME -> g.drawString(font, l.text(), x, y, Ui2Palette.INK, false);
                case GATE -> g.drawString(font, l.text(), x, y, Ui2Palette.INK_SOFT, false);
                case ENTRY_TEXT -> g.drawString(font, l.text(), x, y, Ui2Palette.INK_SOFT, false);
                case TIP_LINK -> g.drawString(font, l.text(), x, y, Ui2Palette.BURGUNDY, false);
                default -> {
                }
            }
        }
        g.disableScissor();
        if (maxScroll > 0) {
            Rect bar = abs(geo.contentScrollbar());
            float visible = c.height() / (float) layout.height();
            Ui2Surface.scrollbar(g, bar.x() + 1, bar.y(), bar.height(), visible, scroll / (float) maxScroll);
            if (scroll < maxScroll) {
                // Fade at the bottom edge: there is more below.
                for (int i = 0; i < 6; i++) {
                    int a = (int) (0x70 * (i + 1) / 6.0F);
                    g.fill(c.x(), c.bottom() - 6 + i, c.right(), c.bottom() - 5 + i, (a << 24) | 0xE7DDC8);
                }
            }
        }
        return hovered;
    }

    private void drawImage(GuiGraphics g, int x, int y, Box b) {
        HandbookBook.Image img = page.image();
        ResourceLocation loc = ResourceLocation.tryParse(img.texture());
        if (loc == null) return;
        g.fill(x - 2, y - 2, x + b.w() + 2, y + b.h() + 2, BannerChrome.PLATE_SHADOW);
        g.fill(x - 1, y - 1, x + b.w() + 1, y + b.h() + 1, BannerChrome.GOLD_EDGE);
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        float scale = layout.imageScale();
        g.pose().scale(scale, scale, 1.0F);
        g.blit(loc, 0, 0, 0.0F, 0.0F, img.width(), img.height(), img.width(), img.height());
        g.pose().popPose();
    }

    private Component renderDots(GuiGraphics g, int mouseX, int mouseY) {
        if (page == null) return null;
        Chapter chapter = book.chapterOf(page);
        int n = chapter.pages().size();
        Rect d = abs(geo.dots());
        Component tooltip = null;
        int[] xs = dotXs(n, d);
        for (int i = 0; i < n; i++) {
            int x = xs[i];
            int y = d.y() + (d.height() - 7) / 2;
            boolean current = i == page.indexInChapter();
            boolean over = mouseX >= x - 2 && mouseX < x + 9 && mouseY >= d.y() && mouseY < d.bottom();
            g.fill(x, y, x + 7, y + 7, BannerChrome.PLATE_SHADOW);
            g.fill(x + 1, y + 1, x + 6, y + 6, current ? Ui2Palette.BURGUNDY : over ? BannerChrome.PLATE_HOVER
                : BannerChrome.PLATE);
            if (current) BannerChrome.outline(g, x, y, 7, 7, BannerChrome.GOLD_EDGE);
            if (over) {
                Page p = chapter.pages().get(i);
                tooltip = Component.literal(p.titleKey() != null ? tr(p.titleKey()) : tr(chapter.titleKey()));
            }
        }
        // One counter only (QA nit b): the chapter's "4 / 18" sits in the page header;
        // the whole-book position lives in the dot tooltip.
        if (tooltip != null) {
            tooltip = tooltip.copy().append(Component.literal("\n")).append(Component.translatable(
                "hearthstead.guide.ui.book_position", page.globalIndex() + 1, book.pages().size())
                .withStyle(net.minecraft.ChatFormatting.GRAY));
        }
        return tooltip;
    }

    private int[] dotXs(int n, Rect d) {
        int step = 11;
        int total = n * step - 4;
        int start = d.x() + Math.max(0, (d.width() - total) / 2);
        int[] xs = new int[n];
        for (int i = 0; i < n; i++) xs[i] = start + i * step;
        return xs;
    }

    private int dotAt(double mx, double my) {
        if (page == null) return -1;
        Rect d = abs(geo.dots());
        if (my < d.y() || my >= d.bottom()) return -1;
        int n = book.chapterOf(page).pages().size();
        if (n <= 1) return -1;
        int[] xs = dotXs(n, d);
        for (int i = 0; i < n; i++) if (mx >= xs[i] - 2 && mx < xs[i] + 9) return i;
        return -1;
    }

    private static void playClick() {
        // Sound pass: a parchment page flip; falls back to vanilla if the asset is missing.
        com.hearthstead.client.sound.HsSound.ui("ui.page_turn", net.minecraft.sounds.SoundEvents.BOOK_PAGE_TURN,
            0.9F, 0.95F + (float) Math.random() * 0.1F);
    }

    /** One crafting grid: 3x3 wells, an arrow and the output. Ingredient options cycle each second. */
    private ItemStack drawRecipe(GuiGraphics g, int x, int y, Box b, int mouseX, int mouseY, boolean inView) {
        HandbookRecipes.Grid grid = b.index() < pageRecipes.size() ? pageRecipes.get(b.index()) : null;
        if (grid == null || grid.missing()) {
            g.drawString(font, Component.translatable("hearthstead.guide.ui.no_recipe"), x, y + 4,
                Ui2Palette.INK_MUTED, false);
            return null;
        }
        // The most common option shows first and stays put; options cycle only while the
        // pointer is over the grid (QA nit c: a new player must not read "crimson stems").
        boolean gridHover = inView && mouseX >= x && mouseX < x + b.w() && mouseY >= y && mouseY < y + b.h();
        long tick = gridHover ? System.currentTimeMillis() / 1000L : 0L;
        ItemStack hovered = null;
        for (int i = 0; i < 9; i++) {
            int sx = x + (i % 3) * 18;
            int sy = y + (i / 3) * 18;
            Ui2Surface.slotWell(g, sx, sy);
            List<ItemStack> options = grid.cells().get(i);
            if (options.isEmpty()) continue;
            ItemStack stack = options.get((int) (tick % options.size()));
            g.renderItem(stack, sx + 1, sy + 1);
            if (inView && mouseX >= sx && mouseX < sx + 18 && mouseY >= sy && mouseY < sy + 18) hovered = stack;
        }
        int ax = x + 3 * 18 + 8;
        int ay = y + 18 + 5;
        g.fill(ax, ay + 3, ax + 9, ay + 5, Ui2Palette.INK_SOFT);
        for (int i = 0; i < 4; i++) g.fill(ax + 8 + i, ay + i, ax + 9 + i, ay + 8 - i, Ui2Palette.INK_SOFT);
        int ox = x + 3 * 18 + 8 + 12 + 8;
        int oy = y + 18;
        Ui2Surface.slotWell(g, ox, oy);
        g.renderItem(grid.output(), ox + 1, oy + 1);
        g.renderItemDecorations(font, grid.output(), ox + 1, oy + 1);
        if (grid.locked()) {
            // Not learned yet: veil the result and put the padlock on it (the tooltip names the node).
            g.pose().pushPose();
            g.pose().translate(0, 0, 250);
            g.fill(ox + 1, oy + 1, ox + 17, oy + 17, 0x99E7DDC8);
            Ui2Surface.lockGlyph(g, ox + 12, oy + 10, Ui2Palette.INK);
            g.pose().popPose();
        }
        if (inView && mouseX >= ox && mouseX < ox + 18 && mouseY >= oy && mouseY < oy + 18) hovered = grid.output();
        return hovered;
    }

    /** A Tech Tree node's medallion (or its icon item) at {@code size} px. */
    private static void drawNodeIcon(GuiGraphics g, String nodeId, int x, int y, int size) {
        ResourceLocation custom = com.hearthstead.client.techtree.TechTreeIcons.custom(nodeId);
        if (custom != null) {
            com.hearthstead.client.techtree.TechTreeIcons.draw(g, custom, x, y, size);
            return;
        }
        ItemStack icon = stackFor(com.hearthstead.settlement.techtree.TechTreeData.get().iconItem(nodeId));
        if (icon.isEmpty()) {
            Ui2Surface.lockGlyph(g, x + 3, y + 2, Ui2Palette.INK_SOFT);
            return;
        }
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(size / 16.0F, size / 16.0F, 1.0F);
        g.renderItem(icon, 0, 0);
        g.pose().popPose();
    }

    private static void keyCap(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y + 1, x + w, y + h, BannerChrome.PLATE_SHADOW);
        g.fill(x, y, x + w, y + h - 1, BannerChrome.INSET_DARK);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 2, BannerChrome.PLATE);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, BannerChrome.PLATE_HIGHLIGHT);
    }

    private static void triangle(GuiGraphics g, int x, int y, boolean open, int color) {
        if (open) {
            for (int i = 0; i < 3; i++) g.fill(x + i, y - 1 + i, x + 5 - i, y + i, color);
        } else {
            for (int i = 0; i < 3; i++) g.fill(x + i, y - 3 + i, x + i + 1, y + 2 - i, color);
        }
    }

    private static void searchGlyph(GuiGraphics g, int x, int y, int color) {
        g.fill(x + 1, y, x + 5, y + 1, color);
        g.fill(x, y + 1, x + 1, y + 5, color);
        g.fill(x + 5, y + 1, x + 6, y + 5, color);
        g.fill(x + 1, y + 5, x + 5, y + 6, color);
        g.fill(x + 5, y + 5, x + 6, y + 6, color);
        g.fill(x + 6, y + 6, x + 8, y + 8, color);
    }

    private ItemStack iconFor(Chapter chapter) {
        ResourceLocation loc = chapter.icon() == null ? null : ResourceLocation.tryParse(chapter.icon());
        var item = loc == null ? Items.BOOK : BuiltInRegistries.ITEM.get(loc);
        return new ItemStack(item == Items.AIR ? Items.BOOK : item);
    }

    // ---------------------------------------------------------------- utils

    private Rect abs(Rect r) {
        return new Rect(left + r.x(), top + r.y(), r.width(), r.height());
    }

    private static String tr(String key) {
        return key == null ? "" : Component.translatable(key).getString();
    }

    private static List<String> trAll(List<String> keys) {
        List<String> out = new ArrayList<>(keys.size());
        for (String k : keys) out.add(tr(k));
        return out;
    }

    public HandbookBook book() {
        return book;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public String qaUiState() {
        return "page=" + (page == null ? "none" : page.id())
            + ",index=" + (page == null ? 0 : page.globalIndex() + 1) + "/" + book.pages().size()
            + ",chapters=" + book.chapters().size()
            + ",scroll=" + scroll + "/" + maxScroll
            + ",rail=" + railScroll + "/" + railMaxScroll
            + ",query=" + query.replace(',', ' ')
            + ",results=" + results.size()
            + ",layout=" + (layout == null ? "none" : (layout.side() ? "side" : "top") + "@" + layout.imageScale())
            + ",details=" + (page != null && openDetails.contains(page.id()))
            + ",panel=" + left + ":" + top + ":" + geo.width() + ":" + geo.height()
            + ",problems=" + book.problems().size();
    }
}
