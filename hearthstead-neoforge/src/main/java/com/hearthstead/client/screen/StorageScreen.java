package com.hearthstead.client.screen;

import com.hearthstead.client.QaUiInspectable;
import com.hearthstead.client.ui.HsMotion;
import com.hearthstead.client.ui.HsUi;
import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import com.hearthstead.client.ui2.Ui2Button;
import com.hearthstead.client.ui2.Ui2Frame;
import com.hearthstead.client.ui2.Ui2FrameLayout;
import com.hearthstead.client.ui2.Ui2Palette;
import com.hearthstead.client.ui2.Ui2Serif;
import com.hearthstead.client.ui2.Ui2Surface;
import com.hearthstead.client.ui2.Ui2Tabs;
import com.hearthstead.client.ui2.Ui2Tips;
import com.hearthstead.network.StorageIndexPayload;
import com.hearthstead.settlement.work.GoodsQuality;
import com.hearthstead.network.StorageRequestPayload;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Read-only stock overview. Every item and total comes from the latest server snapshot. */
public class StorageScreen extends Screen implements QaUiInspectable {
    static final int PREF_W = 464; // size check: Banner footprint (was 520)
    static final int PREF_H = 256; // size check: Banner footprint (was 336)
    static final int INFO_H = 10;
    static final int SEARCH_H = 16;
    static final int ROW_H = 22;
    /** Frames at least this wide keep a factual selected-stack pane beside the stock list. */
    static final int DETAIL_MIN_W = 390;
    /** Detail pane: item well and three facts, then location lines from this offset. */
    static final int DETAIL_FACTS_H = 32;
    static final int DETAIL_LINE_H = 10;
    private static final Component FIND_HINT = Component.literal("Find item");

    private HearthSupplyCategory category = HearthSupplyCategory.ALL;
    private final Ui2Tabs categoryIndicator = new Ui2Tabs();
    private final List<Ui2Tabs.Tab> categoryTabs = new ArrayList<>();
    private int categoryIndicatorY;
    private boolean categoryTabsDirty;
    private final Ui2Serif.Text titleText = new Ui2Serif.Text(Ui2Serif.Size.TITLE);
    private Layout layout;
    private Rect[] footer;
    private int panelHeight;
    private int page;
    private int selectedRow = -1;
    private Ui2Button previous;
    private Ui2Button next;
    private Component pageLabel = Component.empty();
    private int pageLabelWidth;

    private StorageIndexPayload data;
    private StorageIndexPayload renderedData;
    private Font renderedFont;
    private String renderedLanguage = "";
    private int renderedWidth = -1;
    private int renderedHeight = -1;
    private List<net.minecraft.util.FormattedCharSequence> stateLines = List.of();
    private int panelWidth;
    private int left;
    private int top;
    private boolean uiSoundActive;
    private HsUi.FittedLabel summary;
    private HsUi.FittedLabel coverage;
    private HsUi.FittedLabel listed;
    private Component stateCaption = Component.empty();
    private Component headerName = Component.empty();
    private Component headerTotals = Component.empty();
    private Component headerLoaded = Component.empty();
    private Component baseStateMessage = Component.empty();
    private List<Row> allRows = List.of();
    private List<Row> rows = List.of();
    private String filter = "";
    private EditBox findBox;
    private HsUi.FittedLabel findHint;
    private DetailCopy detailCopy;
    private List<Component> headerTooltip = List.of();
    private List<Component> stateTooltip = List.of();

    public StorageScreen(StorageIndexPayload data) {
        super(Component.translatable("hearthstead.storage.title"));
        this.data = data;
    }

    public void update(StorageIndexPayload snapshot) {
        this.data = snapshot;
    }

    @Override
    protected void init() {
        HearthSupplyCategory[] choices = HearthSupplyCategory.values();
        int[] tabWidths = new int[choices.length];
        for (int i = 0; i < choices.length; i++) {
            tabWidths[i] = Ui2Tabs.tabWidth(font, Component.literal(choices[i].displayName()), false);
        }
        Ui2FrameLayout frame = frameFor(width, height);
        int tabRows = tabRows(categoryTabRects(frame.content().x(), 0, frame.content().width(), tabWidths));
        layout = layoutFor(width, height, tabRows);
        frame = layout.frame();
        panelWidth = frame.width();
        panelHeight = frame.height();
        left = frame.x();
        top = frame.y();
        page = Math.min(page, Math.max(0, (rows.size() - 1) / pageSize()));
        clearWidgets();
        categoryTabs.clear();
        buildCategoryTabs(tabWidths);

        Component refreshLabel = Component.translatable("hearthstead.storage.refresh");
        Component previousLabel = Component.translatable("hearthstead.mayor.page.previous");
        Component nextLabel = Component.translatable("hearthstead.mayor.page.next");
        int pageSlot = font.width(Component.translatable("hearthstead.mayor.page", 99, 99));
        // Previous/Next are disabled on the first/last page; the padlock takes 8 px of the
        // label box, so they get that room up front instead of reading "Previ..." / "N...".
        footer = footerSlots(frame.footer(), Ui2Button.textWidth(font, refreshLabel),
            Ui2Button.textWidth(font, previousLabel) + 8, pageSlot, Ui2Button.textWidth(font, nextLabel) + 8);
        Ui2Button refresh = addRenderableWidget(button(footer[FOOTER_REFRESH], refreshLabel,
            () -> PacketDistributor.sendToServer(new StorageRequestPayload())));
        Ui2Tips.tip(refresh, Component.translatable("hearthstead.storage.refresh.tip"));
        previous = addRenderableWidget(button(footer[FOOTER_PREVIOUS], previousLabel, () -> turnPage(-1)));
        next = addRenderableWidget(button(footer[FOOTER_NEXT], nextLabel, () -> turnPage(1)));
        // The old footer "Close" button is the wood close key; Esc still closes.
        addRenderableWidget(Ui2Frame.closeKey(frame, this::onClose));

        Rect search = layout.search();
        findBox = addRenderableWidget(new EditBox(font, search.x() + 4, search.y() + 4,
            search.width() - 8, SEARCH_H - 4, FIND_HINT));
        findBox.setBordered(false);
        findBox.setTextColor(Ui2Palette.INK);
        findBox.setTextColorUneditable(Ui2Palette.INK_MUTED);
        findBox.setMaxLength(64);
        findBox.setValue(filter);
        findBox.setResponder(value -> {
            if (!filter.equals(value)) {
                filter = value;
                applyFilter();
            }
        });
        invalidateDetails();
        updatePaging();
        if (!uiSoundActive) {
            uiSoundActive = true;
            HsUi.playOpenSound();
        }
    }

    private static Ui2Button button(Rect r, Component label, Runnable action) {
        return Ui2Button.secondary(r.x(), r.y(), r.width(), r.height(), label, action);
    }

    /** Category filters are text tabs with one sliding underline; they wrap to a second row when narrow. */
    private void buildCategoryTabs(int[] tabWidths) {
        HearthSupplyCategory[] choices = HearthSupplyCategory.values();
        Rect c = layout.categories();
        Rect[] rects = categoryTabRects(c.x(), c.y(), c.width(), tabWidths);
        for (int i = 0; i < choices.length; i++) {
            HearthSupplyCategory choice = choices[i];
            Rect r = rects[i];
            Ui2Tabs.Tab tab = new Ui2Tabs.Tab(r.x(), r.y(), r.width(),
                Component.literal(choice.displayName()), choice == category, false, () -> selectCategory(choice));
            Ui2Tips.nav(tab, Component.literal("Show " + choice.displayName() + " across settlement stores"));
            categoryTabs.add(addRenderableWidget(tab));
            if (choice == category) {
                categoryIndicator.target(r.x() + 2, r.width() - 4);
                categoryIndicatorY = r.bottom();
            }
        }
    }

    private void selectCategory(HearthSupplyCategory choice) {
        category = choice;
        applyFilter();
        // Tabs carry their selected state, so they are rebuilt next tick (never mid-click).
        categoryTabsDirty = true;
    }

    @Override
    public void tick() {
        super.tick();
        if (!categoryTabsDirty || layout == null) return;
        categoryTabsDirty = false;
        boolean hadFocus = getFocused() instanceof Ui2Tabs.Tab;
        categoryTabs.forEach(this::removeWidget);
        categoryTabs.clear();
        HearthSupplyCategory[] choices = HearthSupplyCategory.values();
        int[] tabWidths = new int[choices.length];
        for (int i = 0; i < choices.length; i++) {
            tabWidths[i] = Ui2Tabs.tabWidth(font, Component.literal(choices[i].displayName()), false);
        }
        buildCategoryTabs(tabWidths);
        if (hadFocus) {
            for (Ui2Tabs.Tab tab : categoryTabs) if (tab.selected()) setFocused(tab);
        }
    }

    @Override
    public void removed() {
        if (uiSoundActive) {
            uiSoundActive = false;
            HsUi.playCloseSound();
        }
        super.removed();
    }

    private boolean hasDetailPane() { return layout != null && layout.detail() != null; }
    private int listWidth() { return layout.rows().width(); }
    private int visibleRows() { return layout == null ? 1 : layout.visibleRows(); }
    private int pageSize() { return visibleRows(); }
    private void updatePaging() {
        int pages = Math.max(1, (rows.size() + pageSize() - 1) / pageSize());
        page = Math.max(0, Math.min(page, pages - 1));
        pageLabel = Component.translatable("hearthstead.mayor.page", page + 1, pages);
        pageLabelWidth = font.width(pageLabel);
        if (previous != null) {
            Ui2Tips.enable(previous, page > 0, Component.translatable("hearthstead.storage.page.previous.tip"),
                Component.translatable("hearthstead.guide.nav.first"));
        }
        if (next != null) {
            Ui2Tips.enable(next, page + 1 < pages, Component.translatable("hearthstead.storage.page.next.tip"),
                Component.translatable("hearthstead.guide.nav.last"));
        }
    }
    private void turnPage(int direction) {
        page += direction;
        updatePaging();
        selectedRow = rows.isEmpty() ? -1 : Math.min(rows.size() - 1, page * pageSize());
        invalidateDetails();
    }

    /** The 128-row server snapshot is filtered locally; typing never changes stock data. */
    private void applyFilter() {
        String needle = filter.strip().toLowerCase(Locale.ROOT);
        rows = allRows.stream()
            .filter(row -> category.matches(row.stack()))
            .filter(row -> needle.isEmpty() || row.searchKey().contains(needle))
            .toList();
        page = 0;
        selectedRow = rows.isEmpty() ? -1 : 0;
        invalidateDetails();
        updatePaging();
        refreshFilterCopy();
    }

    /** Rebuilds only the copy whose truth depends on the current Find text. */
    private void refreshFilterCopy() {
        if (font == null || layout == null) return;
        int textWidth = layout.list().width() - 18;
        Component message = (!filter.isBlank() || category != HearthSupplyCategory.ALL)
                && !allRows.isEmpty() && rows.isEmpty()
            ? Component.literal("No stored items match this category and search.") : baseStateMessage;
        List<net.minecraft.util.FormattedCharSequence> wrapped = font.split(message, Math.max(1, textWidth));
        stateLines = List.copyOf(wrapped.subList(0,
            Math.min(wrapped.size(), Math.max(1, (layout.list().height() - 20) / 9))));
        stateTooltip = List.of(message);
        Component listing = data == null || data.distinctTypes() < 0 ? Component.empty()
            : Component.literal("Showing " + rows.size() + " filtered / " + allRows.size()
                + " known / " + data.distinctTypes() + " total");
        // The full line (~224 px) is cut at GUI 4 (footer slot ~112 px at 427x240 with framed
        // buttons); the footer keeps the short form, the info tooltip keeps the full one.
        // The total kinds already lead the info line above.
        Component footerListing = data == null || data.distinctTypes() < 0 ? Component.empty()
            : Component.literal(rows.size() + " of " + allRows.size() + " shown");
        listed = HsUi.fitLabel(font, footerListing, footer == null ? 1 : footer[FOOTER_LISTED].width());
        headerTooltip = List.of(headerName, headerTotals, headerLoaded, listing);
    }

    private void invalidateDetails() {
        detailCopy = null;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (layout != null && layout.list().contains(x, y) && dy != 0) {
            turnPage(dy > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(x, y, dx, dy);
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (findBox != null && findBox.isFocused()) return super.keyPressed(key, scanCode, modifiers);
        if (!rows.isEmpty() && (key == 264 || key == 265 || key == 266 || key == 267)) {
            int delta = key == 264 ? 1 : key == 265 ? -1 : key == 266 ? -pageSize() : pageSize();
            selectedRow = Math.max(0, Math.min(rows.size() - 1,
                selectedRow < 0 ? page * pageSize() : selectedRow + delta));
            page = selectedRow / pageSize();
            invalidateDetails();
            updatePaging();
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override
    protected void updateNarrationState(net.minecraft.client.gui.narration.NarrationElementOutput output) {
        super.updateNarrationState(output);
        if (rows.isEmpty()) {
            for (Component message : stateTooltip) output.add(
                net.minecraft.client.gui.narration.NarratedElementType.HINT, message);
        }
        if (selectedRow >= 0 && selectedRow < rows.size()) {
            Row row = rows.get(selectedRow);
            output.add(net.minecraft.client.gui.narration.NarratedElementType.HINT,
                row.stack().getHoverName().copy().append(" × " + row.stack().getCount()));
        }
    }

    /** Translation, totals and fitting are paid only when their source changes. */
    private void refreshLabels() {
        String language = minecraft.getLanguageManager().getSelected();
        if (renderedData == data && renderedFont == font
            && renderedWidth == panelWidth && renderedHeight == panelHeight
            && renderedLanguage.equals(language)) return;
        int textWidth = layout.info().width();
        findHint = HsUi.fitLabel(font, FIND_HINT, layout.search().width() - 8);
        stateCaption = HsUi.fitLabel(font, Component.translatable("hearthstead.storage.state"),
            layout.list().width() - 18).text();
        Component name = data == null ? Component.empty() : Component.literal(data.settlementName());
        Component totals = data == null || data.distinctTypes() < 0 ? Component.empty()
            : Component.translatable("hearthstead.storage.summary", data.distinctTypes(), data.totalItems());
        Component message = data == null ? Component.translatable("hearthstead.settler.loading")
            : data.distinctTypes() == -1 ? Component.translatable("hearthstead.storage.no_warehouse")
            : data.settlementName().isEmpty() ? Component.translatable("hearthstead.storage.no_settlement")
            : data.top().isEmpty() ? Component.translatable("hearthstead.storage.empty") : Component.empty();
        Component loaded = data == null || data.distinctTypes() < 0 ? Component.empty()
            : Component.literal("Loaded stores: " + data.loadedWarehouseCount() + " / "
                + data.warehouseCount() + " · containers: " + data.loadedContainers());
        // Totals on the left, store coverage right-aligned in what is left of the line.
        summary = HsUi.fitLabel(font, totals, textWidth);
        coverage = HsUi.fitLabel(font, loaded, Math.max(1, textWidth - summary.width() - Ui2FrameLayout.L));
        headerName = name;
        headerTotals = totals;
        headerLoaded = loaded;
        baseStateMessage = message;
        ArrayList<Row> projected = new ArrayList<>();
        if (data != null && data.distinctTypes() >= 0 && !data.settlementName().isEmpty()) {
            int labelWidth = listWidth() - 32;
            for (StorageIndexPayload.StockRow stock : data.stocks()) {
                ItemStack stack = stock.stack();
                String searchKey = (stack.getHoverName().getString() + " "
                    + BuiltInRegistries.ITEM.getKey(stack.getItem())).toLowerCase(Locale.ROOT);
                HsUi.FittedLabel count = HsUi.fitLabel(font, Component.literal("× " + stock.total()), labelWidth);
                int qualityWidth = Math.max(1, labelWidth - count.width() - 6);
                projected.add(new Row(stack,
                    HsUi.fitLabel(font, stack.getHoverName(), labelWidth), count,
                    HsUi.fitLabel(font, Component.literal(qualityName(stack)), qualityWidth),
                    GoodsQuality.of(stack), stock.total(), stock.locationCount(), stock.locations(), searchKey));
            }
        }
        allRows = List.copyOf(projected);
        applyFilter();
        renderedData = data;
        renderedFont = font;
        renderedWidth = panelWidth;
        renderedHeight = panelHeight;
        renderedLanguage = language;
    }

    // Motion only: first-open intro (4 px slide + fade); created once, survives re-init.
    private HsMotion.ScreenIntro hsIntro;
    private boolean hsIntroRendering;

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
        renderTransparentBackground(g);
        refreshLabels();
        Ui2FrameLayout frame = layout.frame();
        Ui2Frame.draw(g, frame);
        Ui2Frame.title(g, font, frame, titleText, title.getString(), headerName);

        Rect info = layout.info();
        g.drawString(font, summary.text(), info.x(), info.y() + 1, Ui2Palette.INK_SOFT, false);
        g.drawString(font, coverage.text(), info.right() - coverage.width(), info.y() + 1,
            Ui2Palette.INK_MUTED, false);

        // Find: an inset strip with a hairline that turns gold while typing.
        Rect search = layout.search();
        boolean typing = findBox != null && findBox.isFocused();
        g.fill(search.x(), search.y(), search.right(), search.bottom(), Ui2Palette.INSET);
        g.fill(search.x(), search.bottom() - 1, search.right(), search.bottom(),
            typing ? Ui2Palette.FOCUS : Ui2Palette.RULE_STRONG);
        // EditBox retains its message for narration and still owns typed text and the caret.
        // This is the single deliberate, shadow-free empty placeholder on parchment.
        if (findBox != null && findBox.getValue().isEmpty() && !typing && findHint != null) {
            g.drawString(font, findHint.text(), search.x() + 4, search.y() + 4, Ui2Palette.INK_MUTED, false);
        }
        for (Rect row : tabRowRules(layout.categories())) Ui2Surface.rule(g, row.x(), row.y(), row.width());

        ItemStack hovered = ItemStack.EMPTY;
        if (rows.isEmpty()) {
            Rect status = layout.list();
            Ui2Frame.status(g, font, status, stateCaption, stateTone());
            HsUi.drawLines(g, font, stateLines, status.x() + 14, status.y() + 17, Ui2Palette.INK_SOFT);
        } else {
            Rect list = layout.rows();
            int first = page * pageSize();
            for (int i = first; i < Math.min(rows.size(), first + pageSize()); i++) {
                Row row = rows.get(i);
                int x = list.x();
                int y = list.y() + (i - first) * ROW_H;
                boolean hover = mouseX >= x && mouseX < list.right() && mouseY >= y && mouseY < y + ROW_H;
                // Aggregate stacks are selected for inspection only; no transfer is offered here.
                Ui2Surface.row(g, x, y, list.width(), ROW_H - 1, hover ? 1.0F : 0.0F, selectedRow == i);
                Ui2Surface.rule(g, x, y + ROW_H - 1, list.width());
                g.renderItem(row.stack(), x + 5, y + 2);
                g.drawString(font, row.name().text(), x + 26, y + 2, Ui2Palette.INK, false);
                g.drawString(font, row.quality().text(), x + 26, y + 12, qualityColour(row.qualityTier()), false);
                g.drawString(font, row.count().text(), list.right() - 4 - row.count().width(), y + 12,
                    Ui2Palette.INK_SOFT, false);
                if (hover) hovered = row.stack();
            }
            drawSelectedStack(g);
        }
        Ui2Surface.rule(g, frame.footer().x(), frame.footer().y() - Ui2FrameLayout.S, frame.footer().width());
        Rect listedSlot = footer[FOOTER_LISTED];
        g.drawString(font, listed.text(), listedSlot.x(), listedSlot.y() + 2, Ui2Palette.INK_MUTED, false);
        Rect pageSlot = footer[FOOTER_PAGE];
        g.drawString(font, pageLabel, pageSlot.x() + (pageSlot.width() - pageLabelWidth) / 2, pageSlot.y() + 2,
            Ui2Palette.INK_MUTED, false);
        HsUi.widgets(this, g, mouseX, mouseY, partialTick);
        categoryIndicator.render(g, categoryIndicatorY);
        if (!hovered.isEmpty()) {
            g.renderTooltip(font, hovered, mouseX, mouseY);
        } else if (info.contains(mouseX, mouseY) && data != null) {
            g.renderComponentTooltip(font, headerTooltip, mouseX, mouseY);
        } else if (rows.isEmpty() && layout.list().contains(mouseX, mouseY)) {
            g.renderComponentTooltip(font, stateTooltip, mouseX, mouseY);
        }
    }

    /** Bar and glyph mirror the state text only. */
    private Ui2Frame.Tone stateTone() {
        if (data == null) return Ui2Frame.Tone.WAIT;
        if (data.distinctTypes() == -1 || data.settlementName().isEmpty()) return Ui2Frame.Tone.BAD;
        return Ui2Frame.Tone.NEUTRAL;
    }

    /**
     * The server supplies exact loaded warehouse-plaque locations and their
     * component-aware totals. Detail labels are cached per selected row and
     * pane geometry so rendering does not rebuild Components or fitted text.
     */
    private void drawSelectedStack(GuiGraphics g) {
        DetailCopy detail = detailForSelected();
        if (detail == null) return;
        Rect pane = layout.detail();
        int x = pane.x();
        int y = pane.y();
        Ui2Surface.ruleVertical(g, x - Ui2FrameLayout.S, y, pane.height());
        Ui2Surface.slotWell(g, x, y);
        g.renderItem(detail.row().stack(), x + 1, y + 1);
        g.drawString(font, detail.name().text(), x + 24, y, Ui2Palette.INK, false);
        g.drawString(font, detail.count().text(), x + 24, y + 10, Ui2Palette.INK_SOFT, false);
        g.drawString(font, detail.quality().text(), x + 24, y + 20,
            qualityColour(detail.row().qualityTier()), false);
        int locationY = y + DETAIL_FACTS_H;
        for (int index = 0; index < detail.locations().size(); index++) {
            g.drawString(font, detail.locations().get(index).text(), x,
                locationY + index * DETAIL_LINE_H, Ui2Palette.INK_MUTED, false);
        }
        int remainingY = locationY + detail.locations().size() * DETAIL_LINE_H;
        if (detail.remaining() != null && remainingY + 9 <= pane.bottom()) {
            g.drawString(font, detail.remaining().text(), x, remainingY, Ui2Palette.INK_MUTED, false);
        }
    }

    private DetailCopy detailForSelected() {
        if (!hasDetailPane() || selectedRow < 0 || selectedRow >= rows.size()) return null;
        Row row = rows.get(selectedRow);
        int width = layout.detail().width();
        int slots = Math.max(1, (layout.detail().height() - DETAIL_FACTS_H) / DETAIL_LINE_H);
        if (detailCopy != null && detailCopy.row() == row && detailCopy.width() == width
                && detailCopy.locationSlots() == slots) return detailCopy;
        int shown = 0;
        ArrayList<HsUi.FittedLabel> locations = new ArrayList<>();
        for (int index = 0; index < Math.min(row.locations().size(), row.locationCount()); index++) {
            StorageIndexPayload.LocationRow location = row.locations().get(index);
            int required = 3; // Warehouse, coordinates, then its authoritative count.
            boolean moreAfter = index + 1 < row.locationCount();
            if (locations.size() + required + (moreAfter ? 1 : 0) > slots) break;
            locations.add(HsUi.fitLabel(font, Component.literal("Warehouse"), width - 4));
            locations.add(HsUi.fitLabel(font, Component.literal(location.warehousePlaque().getX() + ", "
                + location.warehousePlaque().getY() + ", " + location.warehousePlaque().getZ()), width - 4));
            locations.add(HsUi.fitLabel(font, Component.literal("Stored: " + location.count()), width - 4));
            shown++;
        }
        HsUi.FittedLabel remaining = shown < row.locationCount()
            ? HsUi.fitLabel(font, Component.literal("+ " + (row.locationCount() - shown) + " locations"),
                width - 4) : null;
        HsUi.FittedLabel name = HsUi.fitLabel(font, row.stack().getHoverName(), width - 28);
        HsUi.FittedLabel count = HsUi.fitLabel(font, Component.literal("× " + row.total()), width - 28);
        HsUi.FittedLabel quality = HsUi.fitLabel(font, row.quality().text(), width - 28);
        detailCopy = new DetailCopy(row, width, slots, name, count, quality, List.copyOf(locations), remaining);
        return detailCopy;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && !rows.isEmpty() && layout != null && layout.rows().contains(mouseX, mouseY)) {
            int index = page * pageSize() + (int) ((mouseY - layout.rows().y()) / ROW_H);
            if (index >= 0 && index < rows.size()) {
                selectedRow = index;
                invalidateDetails();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private static String qualityName(ItemStack stack) {
        return switch (GoodsQuality.of(stack)) {
            case GoodsQuality.FINE -> "Fine quality";
            case GoodsQuality.SUPERIOR -> "Superior quality";
            case GoodsQuality.EXCEPTIONAL -> "Exceptional quality";
            case GoodsQuality.MASTERWORK -> "Masterwork quality";
            case GoodsQuality.LEGENDARY -> "Legendary quality";
            default -> "Basic quality";
        };
    }

    /** Palette ink per tier; the tier is always written out too, never colour alone. */
    private static int qualityColour(int quality) {
        return switch (quality) {
            case GoodsQuality.FINE -> Ui2Palette.FOREST;
            case GoodsQuality.SUPERIOR -> Ui2Palette.STATUS_BLUE;
            case GoodsQuality.EXCEPTIONAL -> Ui2Palette.GOLD;
            case GoodsQuality.MASTERWORK -> Ui2Palette.BURGUNDY_HIGHLIGHT;
            case GoodsQuality.LEGENDARY -> Ui2Palette.BURGUNDY;
            default -> Ui2Palette.INK_MUTED;
        };
    }

    private record Row(ItemStack stack, HsUi.FittedLabel name, HsUi.FittedLabel count,
                       HsUi.FittedLabel quality, int qualityTier, int total,
                       int locationCount, List<StorageIndexPayload.LocationRow> locations,
                       String searchKey) { }

    private record DetailCopy(Row row, int width, int locationSlots,
                              HsUi.FittedLabel name, HsUi.FittedLabel count,
                              HsUi.FittedLabel quality, List<HsUi.FittedLabel> locations,
                              HsUi.FittedLabel remaining) { }

    /** Observe only the snapshot whose labels were actually rendered. */
    @Override
    public String qaUiState() {
        if (data == null || renderedData != data || panelWidth <= 0) {
            return "loading";
        }
        return "readOnly=true,rows=" + rows.size()
            + ",knownRows=" + allRows.size()
            + ",filter=" + (!filter.isBlank())
            + ",distinctTypes=" + data.distinctTypes()
            + ",totalItems=" + data.totalItems()
            + ",hasSettlement=" + !data.settlementName().isEmpty()
            + ",hasWarehouse=" + (!data.settlementName().isEmpty()
                && data.distinctTypes() >= 0)
            + ",page=" + page + ",pageSize=" + pageSize()
            + ",panel=" + left + ":" + top + ":" + panelWidth + ":" + panelHeight;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // ------------------------------------------------------------------ geometry ---

    static final int FOOTER_REFRESH = 0;
    static final int FOOTER_LISTED = 1;
    static final int FOOTER_PREVIOUS = 2;
    static final int FOOTER_PAGE = 3;
    static final int FOOTER_NEXT = 4;

    static Ui2FrameLayout frameFor(int viewportW, int viewportH) {
        return Ui2FrameLayout.centred(viewportW, viewportH, PREF_W, PREF_H, true);
    }

    /**
     * The page, top to bottom: totals line, Find strip, category tabs (one or
     * more rows), then the stock rows with the selected-stack pane on the
     * right when the frame is wide enough. The footer holds the text actions.
     */
    static Layout layoutFor(int viewportW, int viewportH, int categoryRows) {
        Ui2FrameLayout f = frameFor(viewportW, viewportH);
        Rect body = f.body(false, true);
        Rect info = new Rect(body.x(), body.y(), body.width(), INFO_H);
        Rect search = new Rect(body.x(), info.bottom() + Ui2FrameLayout.S, body.width(), SEARCH_H);
        int tabRows = Math.max(1, categoryRows);
        int tabsH = tabRows * Ui2FrameLayout.TABS_H + (tabRows - 1) * Ui2FrameLayout.S;
        Rect categories = new Rect(body.x(), search.bottom() + Ui2FrameLayout.M, body.width(), tabsH);
        int listTop = categories.bottom() + Ui2FrameLayout.M;
        int visible = Math.max(1, (body.bottom() - listTop) / ROW_H);
        Rect list = new Rect(body.x(), listTop, body.width(), visible * ROW_H);
        boolean detail = f.width() >= DETAIL_MIN_W;
        int rowsW = detail ? Math.max(160, (body.width() - Ui2FrameLayout.M) * 3 / 5) : body.width();
        Rect rows = new Rect(body.x(), listTop, rowsW, list.height());
        Rect pane = null;
        if (detail) {
            int px = rows.right() + Ui2FrameLayout.M;
            pane = new Rect(px, listTop, Math.max(1, body.right() - px), list.height());
        }
        return new Layout(f, info, search, categories, list, visible, rows, pane);
    }

    record Layout(Ui2FrameLayout frame, Rect info, Rect search, Rect categories, Rect list, int visibleRows,
                  Rect rows, Rect detail) {
    }

    /** Tab rects left to right, {@link Ui2Tabs#GAP} apart, wrapping to a new row when {@code maxW} runs out. */
    static Rect[] categoryTabRects(int x, int y, int maxW, int[] widths) {
        Rect[] out = new Rect[widths.length];
        int cx = x;
        int cy = y;
        for (int i = 0; i < widths.length; i++) {
            int w = Math.min(widths[i], maxW);
            if (cx > x && cx + w > x + maxW) {
                cx = x;
                cy += Ui2FrameLayout.TABS_H + Ui2FrameLayout.S;
            }
            out[i] = new Rect(cx, cy, w, Ui2FrameLayout.TABS_H);
            cx += w + Ui2Tabs.GAP;
        }
        return out;
    }

    static int tabRows(Rect[] tabs) {
        int rows = 0;
        int lastY = Integer.MIN_VALUE;
        for (Rect r : tabs) {
            if (r.y() != lastY) {
                rows++;
                lastY = r.y();
            }
        }
        return Math.max(1, rows);
    }

    /** One hairline under each row of tabs; the sliding underline rides on it. */
    static List<Rect> tabRowRules(Rect categories) {
        List<Rect> out = new ArrayList<>();
        for (int y = categories.y(); y < categories.bottom(); y += Ui2FrameLayout.TABS_H + Ui2FrameLayout.S) {
            out.add(new Rect(categories.x(), y + Ui2FrameLayout.TABS_H + 1, categories.width(), 1));
        }
        return out;
    }

    /**
     * Footer slots: Refresh on the left, the "Showing ..." line after it,
     * then Previous, the page label and Next on the right. Text buttons are
     * {@link Ui2FrameLayout#TEXT_BUTTON_H} high, centred in the footer row.
     */
    static Rect[] footerSlots(Rect footer, int refreshW, int previousW, int pageW, int nextW) {
        int y = footer.y() + (Ui2FrameLayout.BUTTON_H - Ui2FrameLayout.TEXT_BUTTON_H) / 2;
        int h = Ui2FrameLayout.TEXT_BUTTON_H;
        Rect refresh = new Rect(footer.x(), y, refreshW, h);
        Rect next = new Rect(footer.right() - nextW, y, nextW, h);
        Rect page = new Rect(next.x() - Ui2FrameLayout.M - pageW, y, pageW, h);
        Rect previous = new Rect(page.x() - Ui2FrameLayout.M - previousW, y, previousW, h);
        int listedX = refresh.right() + Ui2FrameLayout.L;
        Rect listed = new Rect(listedX, y, Math.max(1, previous.x() - Ui2FrameLayout.L - listedX), h);
        return new Rect[] {refresh, listed, previous, page, next};
    }
}
