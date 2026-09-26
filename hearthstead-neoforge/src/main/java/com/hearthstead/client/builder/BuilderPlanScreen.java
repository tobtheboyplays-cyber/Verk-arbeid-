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
        w = Math.min(width - 16, 460);
        h = Math.min(height - 16, 280);
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
        scroll = Math.max(0, scroll - (int) Math.signum(sy));
        rebuildWidgets();
        return true;
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
        addRenderableWidget(Ui2Button.secondary(cx + 4, cy + ch - 22, 66, 18,
            Component.translatable("hearthstead.builder.ui.style",
                styleFilter.isEmpty() ? Component.translatable("hearthstead.builder.ui.style.all")
                    : Component.literal(styleFilter)), () -> {
                int i = styles.indexOf(styleFilter);
                styleFilter = styles.get((i + 1) % styles.size());
                scroll = 0;
                rebuildWidgets();
            }));
        BuilderPayloads.Catalog catalog = BuilderClientState.catalog();
        Ui2Button town = Ui2Button.secondary(cx + 72, cy + ch - 22, 64, 18,
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
        int rows = Math.max(1, (ch - 30) / 14);
        int listW = 132;
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
            addRenderableWidget(new Ui2RowButton(cx + 4, cy + 4 + i * 14, listW, 13, label, sel, () -> {
                selectedBlueprint = head.id();
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
                        break;
                    }
                    Ui2Button b = preset.id().equals(e.id())
                        ? Ui2Button.banner(chipX, cy + 34, chipW, 14, chip, () -> { })
                        : Ui2Button.secondary(chipX, cy + 34, chipW, 14, chip, () -> {
                            selectedBlueprint = preset.id();
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
                Ui2Button delete = Ui2Button.danger(cx + cw - 172, cy + ch - 24, 72, 20,
                    Component.translatable("hearthstead.builder.ui.delete_design"),
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
        Ui2Type.title(g, font, nameOf(e), dx, cy + 6, Ui2Palette.INK);
        g.drawString(font, Component.translatable("hearthstead.builder.ui.meta",
            Component.translatableWithFallback("hearthstead.builder.category." + e.category(), e.category().replace('_', ' ')), e.style(),
            e.sizeX(), e.sizeY(), e.sizeZ(), e.blocks()), dx, cy + 22, Ui2Palette.INK_MUTED, false);
        // Preview box: the preset as real blocks, turning (styled when on).
        int box = Math.min(96, ch - 110);
        int by = cy + 52;
        Ui2Surface.ruleVertical(g, dx + box + 3, by, box);
        BuilderPayloads.Preview thumb = BuilderClientState.thumbnail(e.id());
        if (thumb != null) {
            BlueprintThumbnail.render(g, thumb, dx, by, box);
        } else {
            g.drawString(font, Component.translatable("hearthstead.builder.ui.preview_loading"), dx + 4,
                by + box / 2 - 4, Ui2Palette.INK_MUTED, false);
        }
        if (!e.lockKey().isEmpty()) {
            Ui2Surface.lockGlyph(g, dx, by + box + 4, Ui2Palette.DANGER);
            g.drawString(font, Component.translatable(e.lockKey()), dx + 9, by + box + 4, Ui2Palette.DANGER, false);
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
        int right = cx + cw - 4;
        for (BuilderPayloads.ItemLine line : lines) {
            if (ry > cy + ch - 44) {
                break;
            }
            Ui2Surface.icon(g, new ItemStack(line.item()), mx, ry, 16);
            String text = line.count() + "  " + line.item().getDescription().getString();
            g.drawString(font, font.plainSubstrByWidth(text, Math.max(20, right - mx - 19)), mx + 19, ry + 4,
                Ui2Palette.INK, false);
            ry += 17;
        }
        BuilderPayloads.Catalog catalog = BuilderClientState.catalog();
        if (catalog != null && !catalog.hasBuilder()) {
            Ui2Surface.alertGlyph(g, dx, cy + ch - 38, Ui2Palette.AMBER);
            g.drawString(font, Component.translatable("hearthstead.builder.ui.no_builder"), dx + 9, cy + ch - 38,
                Ui2Palette.AMBER, false);
        }
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
        g.drawWordWrap(font, Component.translatable("hearthstead.builder.ui.line.controls"), cx + 164, cy + ch - 28,
            cw - 170, Ui2Palette.INK_MUTED);
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
            addRenderableWidget(new Ui2RowButton(cx + 4, cy + 4 + i * 14, 132, 13,
                Component.translatable("hearthstead.building." + r.typeId())
                    .append(" · L" + r.level() + "/" + r.maxLevel()),
                r.buildingId().equals(selectedBuilding), () -> {
                    selectedBuilding = r.buildingId();
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
                Ui2Button down = Ui2Button.danger(cx + 144, cy + ch - 24, 84, 20,
                    Component.translatable("hearthstead.builder.ui.deconstruct"),
                    () -> BuilderClientState.deconstruct(r.buildingId(), false));
                down.setTooltip(Tooltip.create(Component.translatable("hearthstead.builder.ui.deconstruct.tip")));
                addRenderableWidget(down);
            }
        }
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
        for (BuilderPayloads.GapRow gap : r.gaps()) {
            MutableComponent line = Component.translatable("hearthstead.requirement." + gap.id(), gap.have(), gap.needed());
            line.append(Component.translatable(gap.builderCan() ? "hearthstead.builder.ui.by_builder"
                : "hearthstead.builder.ui.by_hand"));
            Ui2Surface.alertGlyph(g, dx, y, gap.builderCan() ? Ui2Palette.AMBER : Ui2Palette.DANGER);
            g.drawString(font, line, dx + 9, y, Ui2Palette.INK, false);
            y += 12;
            if (y > cy + ch - 30) {
                break;
            }
        }
    }

    // ------------------------------------------------------------- sites ---

    private void initSites() {
        BuilderPayloads.Catalog catalog = BuilderClientState.catalog();
        if (catalog != null) {
            int pickup = catalog.pickup();
            int fill = catalog.fill();
            addRenderableWidget(Ui2Button.secondary(x0 + 8, y0 + h - 50, 92, 18,
                Component.translatable("hearthstead.builder.ui.pickup." + pickup),
                () -> BuilderClientState.settings(pickup + 1, fill)));
            addRenderableWidget(Ui2Button.secondary(x0 + 8, y0 + h - 28, 92, 18,
                Component.translatable("hearthstead.builder.ui.fill." + fill),
                () -> BuilderClientState.settings(pickup, fill + 1)));
        }
        List<BuilderPayloads.Site> sites = BuildSitesClient.sites();
        int rows = Math.max(1, (ch - 8) / 16);
        for (int i = 0; i < rows && i + scroll < sites.size(); i++) {
            BuilderPayloads.Site s = sites.get(i + scroll);
            addRenderableWidget(new Ui2RowButton(cx + 4, cy + 4 + i * 16, 132, 15,
                Component.literal(s.label()), s.id().equals(selectedSite), () -> {
                    selectedSite = s.id();
                    rebuildWidgets();
                }));
        }
        BuilderPayloads.Site s = selectedSite(sites);
        if (s == null || s.order() < 0) {
            return;
        }
        int bx = cx + 144;
        int by = cy + ch - 24;
        addRenderableWidget(Ui2Button.banner(bx, by - 24, 150, 20,
            Component.translatable("hearthstead.builder.ui.request"),
            () -> BuilderClientState.siteAction(s.id(), BuildJobs.SiteAction.REQUEST_NOW.ordinal())));
        addRenderableWidget(Ui2Button.secondary(bx, by, 58, 20,
            Component.translatable(s.paused() ? "hearthstead.builder.ui.resume" : "hearthstead.builder.ui.pause"),
            () -> BuilderClientState.siteAction(s.id(), (s.paused() ? BuildJobs.SiteAction.RESUME
                : BuildJobs.SiteAction.PAUSE).ordinal())));
        addRenderableWidget(Ui2Button.secondary(bx + 60, by, 20, 20, Component.literal("▲"),
            () -> BuilderClientState.siteAction(s.id(), BuildJobs.SiteAction.UP.ordinal())));
        addRenderableWidget(Ui2Button.secondary(bx + 82, by, 20, 20, Component.literal("▼"),
            () -> BuilderClientState.siteAction(s.id(), BuildJobs.SiteAction.DOWN.ordinal())));
        addRenderableWidget(Ui2Button.secondary(bx + 104, by, 44, 20,
            Component.translatable("hearthstead.builder.ui.rush"),
            () -> BuilderClientState.siteAction(s.id(), BuildJobs.SiteAction.RUSH.ordinal())));
        Ui2Button stop = Ui2Button.danger(bx + 150, by, 52, 20, Component.translatable("hearthstead.builder.ui.stop"),
            () -> BuilderClientState.siteAction(s.id(), BuildJobs.SiteAction.CANCEL_KEEP.ordinal()));
        stop.setTooltip(Tooltip.create(Component.translatable("hearthstead.builder.ui.stop.tip")));
        addRenderableWidget(stop);
        Ui2Button dismantle = Ui2Button.danger(bx + 204, by, 70, 20,
            Component.translatable("hearthstead.builder.ui.dismantle"),
            () -> BuilderClientState.siteAction(s.id(), BuildJobs.SiteAction.DISMANTLE.ordinal()));
        dismantle.setTooltip(Tooltip.create(Component.translatable("hearthstead.builder.ui.dismantle.tip")));
        addRenderableWidget(dismantle);
        if (s.blocked() > 0) {
            addRenderableWidget(Ui2Button.secondary(bx + 154, by - 24, 120, 20,
                Component.translatable("hearthstead.builder.ui.allow_overwrite"),
                () -> BuilderClientState.siteAction(s.id(), BuildJobs.SiteAction.ALLOW_OVERWRITE.ordinal())));
        }
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

    private void renderSites(GuiGraphics g) {
        List<BuilderPayloads.Site> sites = BuildSitesClient.sites();
        if (sites.isEmpty()) {
            g.drawWordWrap(font, Component.translatable("hearthstead.builder.ui.no_sites"), cx + 8, cy + 8,
                cw - 16, Ui2Palette.INK_MUTED);
            return;
        }
        // Progress chips beside each list row.
        int rows = Math.max(1, (ch - 8) / 16);
        for (int i = 0; i < rows && i + scroll < sites.size(); i++) {
            BuilderPayloads.Site s = sites.get(i + scroll);
            Ui2Surface.progress(g, cx + 8, cy + 16 + i * 16, 124, s.progress(),
                s.order() < 0 ? Ui2Palette.INK_DISABLED : Ui2Palette.FOREST);
        }
        Ui2Surface.ruleVertical(g, cx + 139, cy + 4, ch - 8);
        BuilderPayloads.Site s = selectedSite(sites);
        if (s == null) {
            return;
        }
        int dx = cx + 144;
        int right = cx + cw - 6;
        Ui2Type.title(g, font, Component.literal(s.label()), dx, cy + 6, Ui2Palette.INK);
        Ui2Type.right(g, font, Component.literal(Math.round(s.progress() * 100) + "%"), right, cy + 8, Ui2Palette.INK);
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
            g.drawString(font, missing, dx, y, Ui2Palette.DANGER, false);
            y += 14;
            // Table: item | at hut | warehouse | on the way
            int c1 = right - 120;
            int c2 = right - 78;
            int c3 = right - 30;
            g.drawString(font, Component.translatable("hearthstead.builder.ui.col.hut"), c1, y, Ui2Palette.INK_MUTED, false);
            g.drawString(font, Component.translatable("hearthstead.builder.ui.col.warehouse"), c2, y, Ui2Palette.INK_MUTED, false);
            g.drawString(font, Component.translatable("hearthstead.builder.ui.col.on_the_way"), c3 - 8, y, Ui2Palette.INK_MUTED, false);
            y += 10;
            for (BuilderPayloads.Stock stock : s.missing()) {
                if (y > cy + ch - 62) {
                    break;
                }
                Ui2Surface.icon(g, new ItemStack(stock.item()), dx, y - 3, 12);
                g.drawString(font, font.plainSubstrByWidth(stock.item().getDescription().getString(), c1 - dx - 18),
                    dx + 14, y, Ui2Palette.INK, false);
                g.drawString(font, String.valueOf(stock.inHut()), c1 + 6, y, Ui2Palette.INK, false);
                int whColor = stock.inWarehouse() > 0 ? Ui2Palette.INK : Ui2Palette.DANGER;
                g.drawString(font, String.valueOf(stock.inWarehouse()), c2 + 10, y, whColor, false);
                g.drawString(font, String.valueOf(stock.onTheWay()), c3, y, Ui2Palette.INK, false);
                y += 11;
                if (stock.inWarehouse() <= 0 && stock.onTheWay() <= 0 && stock.shortfall() > 0) {
                    g.drawString(font, Component.translatable("hearthstead.builder.ui.nobody_has"), dx + 14, y,
                        Ui2Palette.DANGER, false);
                    y += 11;
                }
            }
        }
        if (s.skipped() > 0 || s.blocked() > 0) {
            g.drawString(font, Component.translatable("hearthstead.builder.ui.flags", s.skipped(), s.blocked()),
                dx, cy + ch - 58, Ui2Palette.AMBER, false);
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
            case SITES -> renderSites(g);
        }
        for (var child : renderables) {
            child.render(g, mouseX, mouseY, partialTick);
        }
    }
}
