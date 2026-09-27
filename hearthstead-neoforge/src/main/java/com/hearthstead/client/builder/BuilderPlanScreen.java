package com.hearthstead.client.builder;

import com.hearthstead.client.ui2.BannerChrome;
import com.hearthstead.client.ui2.Ui2Frame;
import com.hearthstead.client.ui2.Ui2FrameLayout;
import com.hearthstead.client.ui2.Ui2Serif;
import com.hearthstead.client.ui2.Ui2Button;
import com.hearthstead.client.ui2.Ui2NavButton;
import com.hearthstead.client.ui2.Ui2Palette;
import com.hearthstead.client.ui2.Ui2RowButton;
import com.hearthstead.client.ui2.Ui2Surface;
import com.hearthstead.client.ui2.Ui2Type;
import com.hearthstead.network.BuilderPayloads;
import com.hearthstead.settlement.builder.BuildJobs;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The Builder's Plan (BUILDER lane, plan/BUILDER.md section 4), in the Banner
 * UI kit: walnut board, parchment page, left rail. Four pages:
 * <ul>
 *   <li><b>Catalog</b>: blueprints by category, with size, bill of
 *       materials and the reason when something is locked;</li>
 *   <li><b>Defense</b>: palisade line, stone line, barricade (line tool);</li>
 *   <li><b>Upgrades</b>: every building with a next level, its missing
 *       pieces, and "Order upgrade" for what the Builder can add;</li>
 *   <li><b>Sites</b>: every construction site with progress, what it is
 *       doing right now, and exactly what is MISSING -- at the hut, in the
 *       warehouse, on the way -- plus request / pause / reorder / stop.</li>
 * </ul>
 * Everything shown comes from the server; nothing here is decorative.
 */
public final class BuilderPlanScreen extends Screen {

    public enum Page { CATALOG, DEFENSE, UPGRADES, SITES }

    private static Page lastPage = Page.CATALOG;
    private static boolean gateInLine = true;
    /** Resource Scroll: the Builder whose site should be selected on open. */
    private static UUID focusBuilder;

    /** Opens the Sites page, selecting the linked Builder's site (Resource Scroll). */
    public static void openSites(UUID builder) {
        lastPage = Page.SITES;
        focusBuilder = builder;
        BuilderPlanScreens.openCatalog();
    }

    /** Catalog style filter (MineColonies' style packs); empty = all styles. */
    private static String styleFilter = "";

    private Page page = lastPage;
    private int x0;
    private int y0;
    private int w;
    private int h;
    private int cx;
    private Ui2FrameLayout frame;
    private final Ui2Serif.Text titleText = new Ui2Serif.Text(Ui2Serif.Size.TITLE);
    private int cy;
    private int cw;
    private int ch;
    private int scroll;
    /** Scroll of the detail pane's long list (materials, requirements, missing stock). */
    private int detailScroll;
    /** Sites: the "More" disclosure (Rush, Stop, Dismantle, Allow overwrite) is open. */
    private static boolean moreOpen;
    private String selectedBlueprint;
    private UUID selectedBuilding;
    private UUID selectedSite;
    private int seenCatalog = -1;
    private int seenSites = -1;

    public BuilderPlanScreen() {
        super(Component.translatable("item.hearthstead.builders_plan"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------ layout ---

    @Override
    protected void init() {
        // Moderate centred window (owner size decision, 26 Sep): long lists
        // scroll inside the page instead of the window growing.
        w = Math.min(width - 16, 460);
        h = Math.min(height - 16, 256);
        x0 = (width - w) / 2;
        y0 = (height - h) / 2;
        // Standard window: crest, serif title, close key; the rail and the
        // page start under the header rule.
        frame = Ui2FrameLayout.at(x0, y0, w, h, false);
        cx = x0 + 106;
        cy = frame.page().y();
        cw = frame.page().right() - cx;
        ch = frame.page().bottom() - cy;
        addRenderableWidget(Ui2Frame.closeKey(frame, this::onClose));
        seenCatalog = BuilderClientState.catalogVersion();
        seenSites = BuildSitesClient.version();
        int ny = cy;
        Page[] pages = Page.values();
        ItemStack[] icons = {new ItemStack(Items.SCAFFOLDING), new ItemStack(Items.SPRUCE_FENCE),
            new ItemStack(Items.LANTERN), new ItemStack(Items.IRON_SHOVEL)};
        for (int i = 0; i < pages.length; i++) {
            Page p = pages[i];
            addRenderableWidget(new Ui2NavButton(frame.header().x(), ny + i * 26, cx - 6 - frame.header().x(), 24,
                Component.translatable("hearthstead.builder.ui.page." + p.name().toLowerCase(Locale.ROOT)),
                icons[i], page == p, false, false, i == pages.length - 1, () -> {
                    page = p;
                    lastPage = p;
                    scroll = 0;
                    detailScroll = 0;
                    rebuildWidgets();
                }));
        }
        switch (page) {
            case CATALOG -> initCatalog();
            case DEFENSE -> initDefense();
            case UPGRADES -> initUpgrades();
            case SITES -> initSites();
        }
    }

    @Override
    public void tick() {
        if (BuilderClientState.catalogVersion() != seenCatalog
            || (page == Page.SITES && BuildSitesClient.version() != seenSites)) {
            rebuildWidgets();
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        // Over the detail pane the wheel scrolls its long list; over the list, the list.
        if (mx >= cx + LIST_W + 8 && page != Page.DEFENSE) {
            detailScroll = Math.max(0, detailScroll - (int) Math.signum(sy));
            return true;
        }
        scroll = Math.max(0, scroll - (int) Math.signum(sy));
        rebuildWidgets();
        return true;
    }

    /** Width of the list column on Catalog, Upgrades and Sites. */
    private static final int LIST_W = 132;

    /**
     * A list row that also paints its own label (and, on Sites, the progress
     * bar): Ui2RowButton paints only the row state, so without this the
     * catalog, upgrade and site lists were blank rows (Codex UI audit).
     */
    private final class ListRow extends Ui2RowButton {
        private final float progress;
        private final boolean idle;

        ListRow(int x, int y, int w, int h, Component label, boolean selected, float progress, boolean idle,
                Runnable onPress) {
            super(x, y, w, h, label, selected, onPress);
            this.progress = progress;
            this.idle = idle;
            setTooltip(Tooltip.create(label));
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.renderWidget(g, mouseX, mouseY, partialTick);
            String text = getMessage().getString();
            int room = getWidth() - 8;
            String shown = font.plainSubstrByWidth(text, room);
            if (shown.length() < text.length()) {
                shown = font.plainSubstrByWidth(text, room - font.width("\u2026")) + "\u2026";
            }
            int ty = progress >= 0.0F ? getY() + 2 : getY() + (getHeight() - 8) / 2;
            g.drawString(font, shown, getX() + 4, ty, idle ? Ui2Palette.INK_MUTED : Ui2Palette.INK, false);
            if (progress >= 0.0F) {
                Ui2Surface.progress(g, getX() + 4, getY() + getHeight() - 3, getWidth() - 8, progress,
                    idle ? Ui2Palette.INK_DISABLED : Ui2Palette.FOREST);
            }
        }
    }

    /** First visible row of a long detail list, with the detail scroll clamped to it. */
    private int detailFirst(int total, int rows) {
        detailScroll = Math.max(0, Math.min(detailScroll, Math.max(0, total - rows)));
        return detailScroll;
    }

    /** Small arrows when a detail list has rows above or below the visible window. */
    private void moreHints(GuiGraphics g, int x, int top, int bottom, int first, int shown, int total) {
        if (first > 0) {
            g.drawString(font, "\u25B2", x, top, Ui2Palette.INK_MUTED, false);
        }
        if (first + shown < total) {
            String more = "\u25BC " + (total - first - shown);
            g.drawString(font, more, x + 8 - font.width(more), bottom, Ui2Palette.INK_MUTED, false);
        }
    }

    // ----------------------------------------------------------- catalog ---

    private List<BuilderPayloads.CatalogEntry> entries() {
        BuilderPayloads.Catalog catalog = BuilderClientState.catalog();
        return catalog == null ? List.of() : catalog.entries();
    }

    /** Distinct styles in catalog order ("" first = all). */
    private List<String> styles() {
        List<String> out = new ArrayList<>();
        out.add("");
        for (BuilderPayloads.CatalogEntry e : entries()) {
            if (!out.contains(e.style())) {
                out.add(e.style());
            }
        }
        return out;
    }

    /** Entries of the current style, grouped by category, Our Designs first. */
    private List<BuilderPayloads.CatalogEntry> filtered() {
        List<BuilderPayloads.CatalogEntry> list = new ArrayList<>();
        for (BuilderPayloads.CatalogEntry e : entries()) {
            if (styleFilter.isEmpty() || e.style().equals(styleFilter)) {
                list.add(e);
            }
        }
        list.sort((a, b) -> {
            boolean da = a.category().equals("our_designs");
            boolean db = b.category().equals("our_designs");
            if (da != db) {
                return da ? -1 : 1;
            }
            int c = a.category().compareTo(b.category());
            if (c != 0) {
                return c;
            }
            c = a.group().compareTo(b.group());
            return c != 0 ? c : a.id().compareTo(b.id());
        });
        return list;
    }

    /**
     * One row per building: its style presets (blueprint lane: timber /
     * stone / rustic ...) share a group and are picked with the chips on the
     * right, next to the turning preview and the bill of materials.
     */
    private List<List<BuilderPayloads.CatalogEntry>> groups(List<BuilderPayloads.CatalogEntry> list) {
        java.util.LinkedHashMap<String, List<BuilderPayloads.CatalogEntry>> byGroup = new java.util.LinkedHashMap<>();
        for (BuilderPayloads.CatalogEntry e : list) {
            byGroup.computeIfAbsent(e.group().isEmpty() ? e.id() : e.group(), k -> new ArrayList<>()).add(e);
        }
        return new ArrayList<>(byGroup.values());
    }

    private List<BuilderPayloads.CatalogEntry> groupOf(List<List<BuilderPayloads.CatalogEntry>> groups,
                                                        BuilderPayloads.CatalogEntry e) {
        for (List<BuilderPayloads.CatalogEntry> group : groups) {
            if (group.contains(e)) {
                return group;
            }
        }
        return List.of(e);
    }

    private void initCatalog() {
        List<BuilderPayloads.CatalogEntry> list = filtered();
        List<List<BuilderPayloads.CatalogEntry>> groups = groups(list);
        List<String> styles = styles();
        if (!styles.contains(styleFilter)) {
            styleFilter = "";
        }
        // Style and Town style stack full-width under the list, like the Sites
        // settings: side by side (66 + 64 px) "Style: rustic" (61 px) and
        // "Town style: off" (75 px) no longer fit the framed buttons' w - 6 label room.
        addRenderableWidget(Ui2Button.secondary(cx + 4, cy + ch - 30, LIST_W, 12,
            Component.translatable("hearthstead.builder.ui.style",
                styleFilter.isEmpty() ? Component.translatable("hearthstead.builder.ui.style.all")
                    : Component.literal(styleFilter)), () -> {
                int i = styles.indexOf(styleFilter);
                styleFilter = styles.get((i + 1) % styles.size());
                scroll = 0;
                rebuildWidgets();
            }));
        BuilderPayloads.Catalog catalog = BuilderClientState.catalog();
        Ui2Button town = Ui2Button.secondary(cx + 4, cy + ch - 16, LIST_W, 12,
            Component.translatable(BuilderClientState.matchTownStyle()
                ? "hearthstead.builder.ui.town_style_on" : "hearthstead.builder.ui.town_style_off"), () -> {
                BuilderClientState.setMatchTownStyle(!BuilderClientState.matchTownStyle());
                rebuildWidgets();
            });
        town.active = catalog != null && catalog.townStyle();
        town.setTooltip(Tooltip.create(Component.translatable(town.active
            ? "hearthstead.builder.ui.town_style.tip" : "hearthstead.builder.ui.town_style.unavailable")));
        addRenderableWidget(town);
        BuilderPayloads.CatalogEntry e = selected(list);
        int rows = Math.max(1, (ch - 40) / 14);
        int listW = LIST_W;
        for (int i = 0; i < rows && i + scroll < groups.size(); i++) {
            List<BuilderPayloads.CatalogEntry> group = groups.get(i + scroll);
            BuilderPayloads.CatalogEntry head = group.get(0);
            boolean sel = e != null && group.contains(e);
            // A building's presets share one row, named after the building type.
            Component rowName = group.size() > 1 && !head.group().isEmpty()
                ? Component.translatableWithFallback("hearthstead.building." + head.group(), nameOf(head).getString())
                : nameOf(head);
            Component label = group.size() > 1
                ? Component.translatable("hearthstead.builder.ui.presets_row", rowName, group.size())
                : rowName;
            addRenderableWidget(new ListRow(cx + 4, cy + 4 + i * 14, listW, 13, label, sel, -1.0F, false, () -> {
                selectedBlueprint = head.id();
                detailScroll = 0;
                rebuildWidgets();
            }));
        }
        if (e != null) {
            List<BuilderPayloads.CatalogEntry> group = groupOf(groups, e);
            if (group.size() > 1) {
                int chipX = cx + 144;
                for (BuilderPayloads.CatalogEntry preset : group) {
                    Component chip = Component.literal(preset.preset().isEmpty() ? preset.style() : preset.preset());
                    int chipW = Math.max(40, font.width(chip) + 12);
                    if (chipX + chipW > cx + cw - 4) {
                        if (chipX > cx + 144) {
                            break;
                        }
                        chipW = cx + cw - 4 - chipX; // a long first preset name is shortened, never dropped
                    }
                    Ui2Button b = preset.id().equals(e.id())
                        ? Ui2Button.primary(chipX, cy + 34, chipW, 14, chip, () -> { })
                        : Ui2Button.secondary(chipX, cy + 34, chipW, 14, chip, () -> {
                            selectedBlueprint = preset.id();
                            detailScroll = 0;
                            rebuildWidgets();
                        });
                    addRenderableWidget(b);
                    chipX += chipW + 3;
                }
            }
            Ui2Button place = Ui2Button.banner(cx + cw - 96, cy + ch - 24, 90, 20,
                Component.translatable("hearthstead.builder.ui.place"), () -> {
                    BuilderClientState.requestPreview(e.id());
                });
            place.active = e.lockKey().isEmpty();
            if (!e.lockKey().isEmpty()) {
                place.setTooltip(Tooltip.create(Component.translatable(e.lockKey())));
            }
            addRenderableWidget(place);
            if (e.id().startsWith("design_")) {
                Component del = Component.translatable("hearthstead.builder.ui.delete_design");
                int delW = Ui2Button.textWidth(font, del);
                Ui2Button delete = Ui2Button.dangerText(cx + cw - 104 - delW, cy + ch - 20, delW, 12, del,
                    () -> BuilderClientState.deleteDesign(e.id()));
                addRenderableWidget(delete);
            }
        }
    }

    private BuilderPayloads.CatalogEntry selected(List<BuilderPayloads.CatalogEntry> list) {
        for (BuilderPayloads.CatalogEntry e : list) {
            if (e.id().equals(selectedBlueprint)) {
                return e;
            }
        }
        if (!list.isEmpty()) {
            selectedBlueprint = list.get(0).id();
            return list.get(0);
        }
        return null;
    }

    static Component nameOf(BuilderPayloads.CatalogEntry e) {
        if (!e.name().isEmpty()) {
            return Component.literal(e.name());
        }
        String pretty = e.id().replace('_', ' ');
        return Component.literal(Character.toUpperCase(pretty.charAt(0)) + pretty.substring(1));
    }

    private void renderCatalog(GuiGraphics g) {
        List<BuilderPayloads.CatalogEntry> list = filtered();
        if (list.isEmpty()) {
            Ui2Type.caption(g, font, Component.translatable("hearthstead.builder.ui.no_blueprints"), cx + 8, cy + 8);
            return;
        }
        BuilderPayloads.CatalogEntry e = selected(new ArrayList<>(list));
        int dx = cx + 144;
        Ui2Surface.ruleVertical(g, cx + 139, cy + 4, ch - 8);
        if (e == null) {
            return;
        }
        int right = cx + cw - 6;
        int detailW = right - dx;
        Ui2Type.title(g, font, nameOf(e), dx, cy + 6, Ui2Palette.INK);
        Component meta = Component.translatable("hearthstead.builder.ui.meta",
            Component.translatableWithFallback("hearthstead.builder.category." + e.category(), e.category().replace('_', ' ')), e.style(),
            e.sizeX(), e.sizeY(), e.sizeZ(), e.blocks());
        g.drawString(font, fitted(meta.getString(), detailW), dx, cy + 22, Ui2Palette.INK_MUTED, false);
        // Notes (locked, no Builder) sit at the bottom above the footer, wrapped
        // to the pane; the preview and materials use the space above them.
        List<net.minecraft.util.FormattedCharSequence> lockLines = e.lockKey().isEmpty() ? List.of()
            : font.split(Component.translatable(e.lockKey()), detailW - 10);
        BuilderPayloads.Catalog catalog = BuilderClientState.catalog();
        List<net.minecraft.util.FormattedCharSequence> builderLines = catalog != null && !catalog.hasBuilder()
            ? font.split(Component.translatable("hearthstead.builder.ui.no_builder"), detailW - 10) : List.of();
        int notesH = (lockLines.size() + builderLines.size()) * 9;
        int notesTop = cy + ch - 28 - notesH;
        int ny = notesTop;
        for (int i = 0; i < lockLines.size(); i++, ny += 9) {
            if (i == 0) Ui2Surface.lockGlyph(g, dx, ny, Ui2Palette.DANGER);
            g.drawString(font, lockLines.get(i), dx + 9, ny, Ui2Palette.DANGER, false);
        }
        for (int i = 0; i < builderLines.size(); i++, ny += 9) {
            if (i == 0) Ui2Surface.alertGlyph(g, dx, ny, Ui2Palette.AMBER);
            g.drawString(font, builderLines.get(i), dx + 9, ny, Ui2Palette.AMBER, false);
        }
        // Preview box: the preset as real blocks, turning (styled when on).
        int by = cy + 52;
        int box = Math.max(40, Math.min(64, notesTop - 4 - by));
        Ui2Surface.ruleVertical(g, dx + box + 3, by, box);
        BuilderPayloads.Preview thumb = BuilderClientState.thumbnail(e.id());
        if (thumb != null) {
            BlueprintThumbnail.render(g, thumb, dx, by, box);
        } else {
            g.drawString(font, fitted(Component.translatable("hearthstead.builder.ui.preview_loading").getString(), box),
                dx + 2, by + box / 2 - 4, Ui2Palette.INK_MUTED, false);
        }
        // Bill of materials: from the (styled) preview when it has arrived.
        List<BuilderPayloads.ItemLine> lines = new ArrayList<>();
        if (thumb != null) {
            for (java.util.Map.Entry<net.minecraft.world.item.Item, Integer> m : BlueprintThumbnail.bill(thumb).entrySet()) {
                lines.add(new BuilderPayloads.ItemLine(m.getKey(), m.getValue()));
            }
        } else {
            lines.addAll(e.materials());
        }
        int mx = dx + box + 8;
        g.drawString(font, Component.translatable("hearthstead.builder.ui.materials"), mx, by, Ui2Palette.INK_SOFT, false);
        int ry = by + 11;
        int matRows = Math.max(1, (notesTop - 4 - ry) / 12);
        int first = detailFirst(lines.size(), matRows);
        int shown = 0;
        for (int i = first; i < lines.size() && shown < matRows; i++, shown++) {
            BuilderPayloads.ItemLine line = lines.get(i);
            Ui2Surface.icon(g, new ItemStack(line.item()), mx, ry - 1, 10);
            String text = line.count() + " " + line.item().getDescription().getString();
            g.drawString(font, fitted(text, Math.max(20, right - mx - 22)), mx + 12, ry, Ui2Palette.INK, false);
            ry += 12;
        }
        moreHints(g, right - 8, by, ry - 2, first, shown, lines.size());
    }

    /** {@code text} cut to {@code width} with an ellipsis. */
    private String fitted(String text, int width) {
        if (font.width(text) <= width) return text;
        return font.plainSubstrByWidth(text, Math.max(0, width - font.width("\u2026"))) + "\u2026";
    }

    // ----------------------------------------------------------- defense ---

    private void initDefense() {
        BuilderPayloads.Catalog catalog = BuilderClientState.catalog();
        boolean[] unlocked = {catalog != null && catalog.palisade(), catalog != null && catalog.stone(),
            catalog != null && catalog.barricade()};
        String[] kinds = {"palisade", "stone", "barricade"};
        String[] locks = {"hearthstead.builder.lock.defense_plans", "hearthstead.builder.lock.masonry",
            "hearthstead.builder.lock.builders_hut"};
        for (int i = 0; i < kinds.length; i++) {
            String kind = kinds[i];
            Ui2Button draw = Ui2Button.banner(cx + cw - 96, cy + 10 + i * 46, 88, 20,
                Component.translatable("hearthstead.builder.ui.draw"), () -> {
                    BuilderPlacement.startLine(kind, gateInLine && !"barricade".equals(kind));
                });
            draw.active = unlocked[i];
            if (!unlocked[i]) {
                draw.setTooltip(Tooltip.create(Component.translatable(locks[i])));
            }
            addRenderableWidget(draw);
        }
        addRenderableWidget(Ui2Button.secondary(cx + 8, cy + ch - 26, 150, 20,
            Component.translatable(gateInLine ? "hearthstead.builder.ui.gate_on" : "hearthstead.builder.ui.gate_off"),
            () -> {
                gateInLine = !gateInLine;
                rebuildWidgets();
            }));
    }

    private void renderDefense(GuiGraphics g) {
        String[] kinds = {"palisade", "stone", "barricade"};
        ItemStack[] icons = {new ItemStack(Items.SPRUCE_LOG), new ItemStack(Items.STONE_BRICKS),
            new ItemStack(Items.OAK_FENCE)};
        for (int i = 0; i < kinds.length; i++) {
            int ry = cy + 8 + i * 46;
            Ui2Surface.icon(g, icons[i], cx + 8, ry, 16);
            g.drawString(font, Component.translatable("hearthstead.builder.ui.line." + kinds[i]), cx + 30, ry + 2,
                Ui2Palette.INK, false);
            g.drawWordWrap(font, Component.translatable("hearthstead.builder.ui.line." + kinds[i] + ".desc"),
                cx + 30, ry + 14, cw - 140, Ui2Palette.INK_MUTED);
            Ui2Surface.rule(g, cx + 6, ry + 40, cw - 12);
        }
        List<net.minecraft.util.FormattedCharSequence> hint =
            font.split(Component.translatable("hearthstead.builder.ui.line.controls"), cw - 170);
        int hy = cy + ch - 6 - hint.size() * 9;
        for (net.minecraft.util.FormattedCharSequence line : hint) {
            g.drawString(font, line, cx + 164, hy, Ui2Palette.INK_MUTED, false);
            hy += 9;
        }
    }

    // ---------------------------------------------------------- upgrades ---

    private List<BuilderPayloads.UpgradeRow> upgrades() {
        BuilderPayloads.Catalog catalog = BuilderClientState.catalog();
        return catalog == null ? List.of() : catalog.upgrades();
    }

    private void initUpgrades() {
        List<BuilderPayloads.UpgradeRow> list = upgrades();
        int rows = Math.max(1, (ch - 8) / 14);
        for (int i = 0; i < rows && i + scroll < list.size(); i++) {
            BuilderPayloads.UpgradeRow r = list.get(i + scroll);
            addRenderableWidget(new ListRow(cx + 4, cy + 4 + i * 14, LIST_W, 13,
                Component.translatable("hearthstead.building." + r.typeId())
                    .append(" · L" + r.level() + "/" + r.maxLevel()),
                r.buildingId().equals(selectedBuilding), -1.0F, r.level() >= r.maxLevel(), () -> {
                    selectedBuilding = r.buildingId();
                    detailScroll = 0;
                    rebuildWidgets();
                }));
        }
        BuilderPayloads.UpgradeRow r = selectedUpgrade(list);
        if (r != null) {
            boolean any = false;
            for (BuilderPayloads.GapRow gap : r.gaps()) {
                any |= gap.builderCan();
            }
            Ui2Button order = Ui2Button.banner(cx + cw - 110, cy + ch - 24, 104, 20,
                Component.translatable("hearthstead.builder.ui.order_upgrade"),
                () -> BuilderClientState.validateUpgrade(r.buildingId()));
            order.active = any && r.level() < r.maxLevel();
            addRenderableWidget(order);
            if (!"builders_hut".equals(r.typeId())) {
                Component dec = Component.translatable("hearthstead.builder.ui.deconstruct");
                int downY = deconstructStacked() ? cy + ch - 40 : cy + ch - 20;
                Ui2Button down = Ui2Button.dangerText(cx + 144, downY, Ui2Button.textWidth(font, dec), 12,
                    dec, () -> BuilderClientState.deconstruct(r.buildingId(), false));
                down.setTooltip(Tooltip.create(Component.translatable("hearthstead.builder.ui.deconstruct.tip")));
                addRenderableWidget(down);
            }
        }
    }

    /**
     * In a narrow window Deconstruct would run into Order upgrade on the bottom
     * row (427x240: 144 + 72 + 6 > cw - 110 = 183), so it moves one row up and
     * the requirement list stops above it.
     */
    private boolean deconstructStacked() {
        int dw = Ui2Button.textWidth(font, Component.translatable("hearthstead.builder.ui.deconstruct"));
        return cx + 144 + dw + 6 > cx + cw - 110;
    }

    private BuilderPayloads.UpgradeRow selectedUpgrade(List<BuilderPayloads.UpgradeRow> list) {
        for (BuilderPayloads.UpgradeRow r : list) {
            if (r.buildingId().equals(selectedBuilding)) {
                return r;
            }
        }
        if (!list.isEmpty()) {
            selectedBuilding = list.get(0).buildingId();
            return list.get(0);
        }
        return null;
    }

    private void renderUpgrades(GuiGraphics g) {
        List<BuilderPayloads.UpgradeRow> list = upgrades();
        if (list.isEmpty()) {
            g.drawWordWrap(font, Component.translatable("hearthstead.builder.ui.no_upgrades"), cx + 8, cy + 8,
                cw - 16, Ui2Palette.INK_MUTED);
            return;
        }
        BuilderPayloads.UpgradeRow r = selectedUpgrade(list);
        Ui2Surface.ruleVertical(g, cx + 139, cy + 4, ch - 8);
        if (r == null) {
            return;
        }
        int dx = cx + 144;
        Ui2Type.title(g, font, Component.translatable("hearthstead.building." + r.typeId()), dx, cy + 6, Ui2Palette.INK);
        g.drawString(font, Component.translatable("hearthstead.builder.ui.level", r.level(), r.maxLevel()),
            dx, cy + 22, Ui2Palette.INK_MUTED, false);
        if (r.level() >= r.maxLevel()) {
            Ui2Surface.checkGlyph(g, dx, cy + 38, Ui2Palette.FOREST);
            g.drawString(font, Component.translatable("hearthstead.builder.ui.top_level"), dx + 9, cy + 38,
                Ui2Palette.FOREST, false);
            return;
        }
        g.drawString(font, Component.translatable("hearthstead.builder.ui.next_level"), dx, cy + 38,
            Ui2Palette.INK_SOFT, false);
        int y = cy + 52;
        List<BuilderPayloads.GapRow> gaps = r.gaps();
        int gapBottom = cy + ch - 30
            - (!"builders_hut".equals(r.typeId()) && deconstructStacked() ? 14 : 0);
        int gapRows = Math.max(1, (gapBottom - y) / 12);
        int first = detailFirst(gaps.size(), gapRows);
        int shown = 0;
        int right = cx + cw - 6;
        for (int i = first; i < gaps.size() && shown < gapRows; i++, shown++) {
            BuilderPayloads.GapRow gap = gaps.get(i);
            MutableComponent line = Component.translatable("hearthstead.requirement." + gap.id(), gap.have(), gap.needed());
            line.append(Component.translatable(gap.builderCan() ? "hearthstead.builder.ui.by_builder"
                : "hearthstead.builder.ui.by_hand"));
            Ui2Surface.alertGlyph(g, dx, y, gap.builderCan() ? Ui2Palette.AMBER : Ui2Palette.DANGER);
            g.drawString(font, font.plainSubstrByWidth(line.getString(), Math.max(20, right - dx - 20)), dx + 9, y,
                Ui2Palette.INK, false);
            y += 12;
        }
        moreHints(g, right - 8, cy + 40, y, first, shown, gaps.size());
    }

    // ------------------------------------------------------------- sites ---

    private void initSites() {
        BuilderPayloads.Catalog catalog = BuilderClientState.catalog();
        if (catalog != null) {
            int pickup = catalog.pickup();
            int fill = catalog.fill();
            // Settings live on the parchment under the site list (on the walnut
            // rail their ink was unreadable), each a labelled text toggle.
            Ui2Button pick = Ui2Button.secondary(cx + 4, cy + ch - 30, LIST_W, 12,
                Component.translatable("hearthstead.builder.ui.pickup." + pickup),
                () -> BuilderClientState.settings(pickup + 1, fill));
            pick.setTooltip(Tooltip.create(Component.translatable("hearthstead.builder.ui.settings.tip")));
            addRenderableWidget(pick);
            Ui2Button fillB = Ui2Button.secondary(cx + 4, cy + ch - 16, LIST_W, 12,
                Component.translatable("hearthstead.builder.ui.fill." + fill),
                () -> BuilderClientState.settings(pickup, fill + 1));
            fillB.setTooltip(Tooltip.create(Component.translatable("hearthstead.builder.ui.settings.tip")));
            addRenderableWidget(fillB);
        }
        List<BuilderPayloads.Site> sites = BuildSitesClient.sites();
        int rows = Math.max(1, (ch - 40) / 16);
        for (int i = 0; i < rows && i + scroll < sites.size(); i++) {
            BuilderPayloads.Site s = sites.get(i + scroll);
            addRenderableWidget(new ListRow(cx + 4, cy + 4 + i * 16, LIST_W, 15,
                Component.literal(s.label()), s.id().equals(selectedSite), s.progress(), s.order() < 0, () -> {
                    selectedSite = s.id();
                    detailScroll = 0;
                    rebuildWidgets();
                }));
        }
        BuilderPayloads.Site s = selectedSite(sites);
        siteActionTop = cy + ch - 8;
        if (s == null || s.order() < 0) {
            return;
        }
        // Actions live inside the detail pane (they used to run 64px past the
        // frame): Request on its own row, then Pause/Resume, move up/down and
        // "More", which discloses the rarer and destructive actions.
        int bx = cx + LIST_W + 12;
        int right = cx + cw - 6;
        int by = cy + ch - 20;
        addRenderableWidget(Ui2Button.banner(bx, by - 26, right - bx, 20,
            Component.translatable("hearthstead.builder.ui.request"),
            () -> BuilderClientState.siteAction(s.id(), BuildJobs.SiteAction.REQUEST_NOW.ordinal())));
        List<Ui2Button> row = new ArrayList<>();
        if (!moreOpen) {
            row.add(text(Component.translatable(s.paused() ? "hearthstead.builder.ui.resume" : "hearthstead.builder.ui.pause"),
                () -> BuilderClientState.siteAction(s.id(), (s.paused() ? BuildJobs.SiteAction.RESUME
                    : BuildJobs.SiteAction.PAUSE).ordinal()), null));
            row.add(text(Component.literal("\u25B2"),
                () -> BuilderClientState.siteAction(s.id(), BuildJobs.SiteAction.UP.ordinal()),
                Component.translatable("hearthstead.builder.ui.move_up")));
            row.add(text(Component.literal("\u25BC"),
                () -> BuilderClientState.siteAction(s.id(), BuildJobs.SiteAction.DOWN.ordinal()),
                Component.translatable("hearthstead.builder.ui.move_down")));
            row.add(text(Component.translatable("hearthstead.builder.ui.more"), () -> {
                moreOpen = true;
                rebuildWidgets();
            }, Component.translatable("hearthstead.builder.ui.more.tip")));
        } else {
            row.add(text(Component.literal("\u2039"), () -> {
                moreOpen = false;
                rebuildWidgets();
            }, Component.translatable("hearthstead.builder.ui.less")));
            row.add(text(Component.translatable("hearthstead.builder.ui.rush"),
                () -> BuilderClientState.siteAction(s.id(), BuildJobs.SiteAction.RUSH.ordinal()), null));
            Component stop = Component.translatable("hearthstead.builder.ui.stop");
            Ui2Button stopB = Ui2Button.dangerText(0, 0, Ui2Button.textWidth(font, stop), 12, stop,
                () -> BuilderClientState.siteAction(s.id(), BuildJobs.SiteAction.CANCEL_KEEP.ordinal()));
            stopB.setTooltip(Tooltip.create(Component.translatable("hearthstead.builder.ui.stop.tip")));
            row.add(stopB);
            Component dis = Component.translatable("hearthstead.builder.ui.dismantle");
            Ui2Button disB = Ui2Button.dangerText(0, 0, Ui2Button.textWidth(font, dis), 12, dis,
                () -> BuilderClientState.siteAction(s.id(), BuildJobs.SiteAction.DISMANTLE.ordinal()));
            disB.setTooltip(Tooltip.create(Component.translatable("hearthstead.builder.ui.dismantle.tip")));
            row.add(disB);
            if (s.blocked() > 0) {
                row.add(text(Component.translatable("hearthstead.builder.ui.allow_overwrite"),
                    () -> BuilderClientState.siteAction(s.id(), BuildJobs.SiteAction.ALLOW_OVERWRITE.ordinal()),
                    null));
            }
        }
        // Flow left to right; a row that runs out of room wraps upward, never past the frame.
        int x = bx;
        int y = by + 4;
        int wraps = 0;
        for (Ui2Button b : row) {
            if (x + b.getWidth() > right && x > bx) {
                x = bx;
                wraps++;
            }
            b.setX(x);
            b.setY(y - wraps * 14);
            addRenderableWidget(b);
            x += b.getWidth() + 6;
        }
        if (wraps > 0) {
            // Lift the Request row above the wrapped action rows.
            for (var child : children()) {
                if (child instanceof Ui2Button b && b.variant() == Ui2Button.Variant.BANNER && b.getY() == by - 26) {
                    b.setY(by - 26 - wraps * 14);
                }
            }
        }
        siteActionTop = by - 26 - wraps * 14;
    }

    /** Top of the Sites action block (the missing-stock table stops above it). */
    private int siteActionTop;

    private Ui2Button text(Component label, Runnable action, Component tip) {
        Ui2Button b = Ui2Button.secondary(0, 0, Ui2Button.textWidth(font, label), 12, label, action);
        if (tip != null) {
            b.setTooltip(Tooltip.create(tip));
        }
        return b;
    }

    private BuilderPayloads.Site selectedSite(List<BuilderPayloads.Site> sites) {
        if (focusBuilder != null) {
            for (BuilderPayloads.Site s : sites) {
                if (focusBuilder.equals(s.builder())) {
                    selectedSite = s.id();
                    focusBuilder = null;
                    return s;
                }
            }
        }
        for (BuilderPayloads.Site s : sites) {
            if (s.id().equals(selectedSite)) {
                return s;
            }
        }
        if (!sites.isEmpty()) {
            selectedSite = sites.get(0).id();
            return sites.get(0);
        }
        return null;
    }

    private void renderSites(GuiGraphics g, int mouseX, int mouseY) {
        List<BuilderPayloads.Site> sites = BuildSitesClient.sites();
        if (sites.isEmpty()) {
            g.drawWordWrap(font, Component.translatable("hearthstead.builder.ui.no_sites"), cx + 8, cy + 8,
                cw - 16, Ui2Palette.INK_MUTED);
            return;
        }
        // Each list row paints its own name and progress bar (ListRow).
        Ui2Surface.ruleVertical(g, cx + 139, cy + 4, ch - 8);
        BuilderPayloads.Site s = selectedSite(sites);
        if (s == null) {
            return;
        }
        int dx = cx + 144;
        int right = cx + cw - 6;
        Component percent = Component.literal(Math.round(s.progress() * 100) + "%");
        int titleRoom = Math.max(0, right - dx - font.width(percent) - 6);
        // title() scales the font: fit in unscaled font pixels, rounding down
        // so the rendered title cannot enter the percentage's reserved space.
        int titleFontRoom = (int) Math.floor(titleRoom / Ui2Type.TITLE_SCALE);
        String siteTitle = titleFontRoom < font.width("\u2026")
            ? font.plainSubstrByWidth(s.label(), titleFontRoom) : fitted(s.label(), titleFontRoom);
        Ui2Type.title(g, font, Component.literal(siteTitle), dx, cy + 6, Ui2Palette.INK);
        if (!siteTitle.equals(s.label()) && mouseX >= dx && mouseX < dx + titleRoom
            && mouseY >= cy + 6 && mouseY < cy + 6 + Math.ceil(font.lineHeight * Ui2Type.TITLE_SCALE)) {
            setTooltipForNextRenderPass(Component.literal(s.label()));
        }
        Ui2Type.right(g, font, percent, right, cy + 8, Ui2Palette.INK);
        Ui2Surface.progress(g, dx, cy + 22, right - dx, s.progress(), Ui2Palette.FOREST);
        int y = cy + 30;
        int statusColor = switch (com.hearthstead.settlement.builder.BuildStatus.byOrdinal(s.status())) {
            case WAITING_FOR, NEEDS_PLAYER, BLOCKED_PLAYER_BLOCK, NO_BUILDER, SKIPPED, UNREACHABLE -> Ui2Palette.BURGUNDY;
            case DONE -> Ui2Palette.FOREST;
            default -> Ui2Palette.INK_SOFT;
        };
        g.drawWordWrap(font, BuildSitesClient.status(s), dx, y, right - dx, statusColor);
        y += 22;
        Component missing = BuildSitesClient.missingLine(s);
        if (!missing.getString().isEmpty()) {
            g.fill(dx - 2, y - 2, right, y + 10, 0x22A13A2E);
            siteStockText(g, missing, missing, dx, y, right - dx, Ui2Palette.DANGER, false, mouseX, mouseY);
            y += 14;
            // Table: item | at hut | warehouse | on the way
            List<BuilderPayloads.Stock> stocks = s.missing();
            Component[] headings = {
                Component.translatable("hearthstead.builder.ui.col.hut"),
                Component.translatable("hearthstead.builder.ui.col.warehouse"),
                Component.translatable("hearthstead.builder.ui.col.on_the_way")
            };
            int gap = 6;
            int arrowRoom = Math.max(font.width("\u25B2"), font.width("\u25BC " + stocks.size())) + 4;
            int stockRight = right - arrowRoom;
            int itemMinimum = 14 + font.width("MMMMMM");
            int columnMinimum = font.width("0\u2026");
            int[] columnWidths = new int[3];
            for (int column = 0; column < 3; column++) {
                columnWidths[column] = Math.max(columnMinimum, font.width(headings[column]));
            }
            // Measure every row, not just the visible slice: scrolling keeps
            // all three columns at the same anchors.
            for (BuilderPayloads.Stock stock : stocks) {
                int[] counts = {stock.inHut(), stock.inWarehouse(), stock.onTheWay()};
                for (int column = 0; column < 3; column++) {
                    columnWidths[column] = Math.max(columnWidths[column], font.width(String.valueOf(counts[column])));
                }
            }
            int columnBudget = stockRight - dx - itemMinimum - gap * 3;
            boolean compact = columnBudget < columnMinimum * 3;
            if (!compact) {
                while (columnWidths[0] + columnWidths[1] + columnWidths[2] > columnBudget) {
                    int widest = 0;
                    for (int column = 1; column < 3; column++) {
                        if (columnWidths[column] > columnWidths[widest]) widest = column;
                    }
                    columnWidths[widest]--;
                }
            }
            int[] columns = new int[3];
            columns[2] = stockRight - columnWidths[2];
            columns[1] = columns[2] - gap - columnWidths[1];
            columns[0] = columns[1] - gap - columnWidths[0];
            int nameRoom = Math.max(0, (compact ? stockRight : columns[0] - gap) - dx - 14);
            if (compact) {
                Component summary = Component.empty().append(headings[0]).append(" / ")
                    .append(headings[1]).append(" / ").append(headings[2]);
                siteStockText(g, summary, summary, dx + 14, y, nameRoom,
                    Ui2Palette.INK_MUTED, false, mouseX, mouseY);
            } else {
                for (int column = 0; column < 3; column++) {
                    siteStockText(g, headings[column], headings[column], columns[column], y,
                        columnWidths[column], Ui2Palette.INK_MUTED, false, mouseX, mouseY);
                }
            }
            y += 10;
            int bottom = siteActionTop - 26;
            int stockRows = Math.max(1, (bottom - y) / 11 + 1);
            int first = detailFirst(stocks.size(), stockRows);
            int tableTop = y;
            int shown = 0;
            for (int i = first; i < stocks.size(); i++) {
                BuilderPayloads.Stock stock = stocks.get(i);
                if (y > bottom) {
                    break;
                }
                shown++;
                Ui2Surface.icon(g, new ItemStack(stock.item()), dx, y - 3, 12);
                int[] counts = {stock.inHut(), stock.inWarehouse(), stock.onTheWay()};
                MutableComponent rowTip = Component.empty().append(stock.item().getDescription());
                for (int column = 0; column < 3; column++) {
                    rowTip.append("\n").append(headings[column]).append(": " + counts[column]);
                }
                siteStockText(g, stock.item().getDescription(), rowTip, dx + 14, y, nameRoom,
                    Ui2Palette.INK, false, mouseX, mouseY);
                // Extremely narrow panes retain the item and expose the three
                // exact counts on row hover instead of crushing its name.
                if (compact && mouseX >= dx && mouseX < stockRight && mouseY >= y - 3 && mouseY < y + 9) {
                    setTooltipForNextRenderPass(rowTip);
                }
                if (!compact) {
                    for (int column = 0; column < 3; column++) {
                        Component count = Component.literal(String.valueOf(counts[column]));
                        Component tip = Component.empty().append(headings[column]).append(": ").append(count);
                        int color = column == 1 && counts[column] <= 0 ? Ui2Palette.DANGER : Ui2Palette.INK;
                        siteStockText(g, count, tip, columns[column], y, columnWidths[column],
                            color, true, mouseX, mouseY);
                    }
                }
                y += 11;
                // The extra "Nobody has this" line only when it stays inside the table:
                // past `bottom` it ran into the amber skipped/blocked line.
                if (stock.inWarehouse() <= 0 && stock.onTheWay() <= 0 && stock.shortfall() > 0 && y <= bottom) {
                    Component nobody = Component.translatable("hearthstead.builder.ui.nobody_has");
                    siteStockText(g, nobody, nobody, dx + 14, y, Math.max(0, stockRight - dx - 14),
                        Ui2Palette.DANGER, false, mouseX, mouseY);
                    y += 11;
                }
            }
            moreHints(g, right - 8, tableTop - 10, y, first, shown, stocks.size());
        }
        if (s.skipped() > 0 || s.blocked() > 0) {
            g.drawString(font, Component.translatable("hearthstead.builder.ui.flags", s.skipped(), s.blocked()),
                dx, siteActionTop - 12, Ui2Palette.AMBER, false);
        }
    }

    /** Text cells share measured bounds; tooltips retain full translated labels and exact counts. */
    private void siteStockText(GuiGraphics g, Component text, Component tip, int x, int y, int room,
                               int color, boolean alignRight, int mouseX, int mouseY) {
        if (room <= 0) return;
        String value = text.getString();
        String shown = room < font.width("\u2026") ? font.plainSubstrByWidth(value, room) : fitted(value, room);
        g.drawString(font, shown, alignRight ? x + room - font.width(shown) : x, y, color, false);
        if (mouseX >= x && mouseX < x + room && mouseY >= y - 2 && mouseY < y + 9) {
            setTooltipForNextRenderPass(tip);
        }
    }

    // ------------------------------------------------------------ render ---

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Ui2Surface.scrim(g, 0, 0, width, height);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        Ui2Frame.board(g, frame);
        Ui2Frame.crest(g, frame);
        Ui2Frame.title(g, font, frame, titleText, title.getString(), null);
        BannerChrome.parchment(g, cx, cy, cw, ch);
        switch (page) {
            case CATALOG -> renderCatalog(g);
            case DEFENSE -> renderDefense(g);
            case UPGRADES -> renderUpgrades(g);
            case SITES -> renderSites(g, mouseX, mouseY);
        }
        for (var child : renderables) {
            child.render(g, mouseX, mouseY, partialTick);
        }
    }
}
