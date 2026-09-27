package com.hearthstead.client.screen;

import com.hearthstead.building.BuildingType;
import com.hearthstead.client.QaClientObserver;
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
import com.hearthstead.entity.Profession;
import com.hearthstead.item.JobEmblemItem;
import com.hearthstead.network.PlaqueAction;
import com.hearthstead.network.PlaqueSnapshot;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.state.BlessingId;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The building plaque's screen: what this building is, what it still needs,
 * and who currently belongs to it.
 *
 * <p>Drawn entirely from the server's snapshot. The screen holds no opinion
 * about capacity, eligibility or cost — it renders what it was sent and sends
 * back the revision it drew, so a click on a view the world has already moved
 * past is refused rather than applied.
 *
 * <p>Homes retain their resident and move-in controls. A workplace deliberately
 * has no candidate/hire controls: its Staff page is a read-only operational
 * overview. The player gives a Job Emblem to the settler, and Hearthstead then
 * selects an active compatible workplace. Keeping that rule visible on the
 * plaque prevents two competing hiring loops from teaching opposite habits.
 *
 * <p>Chrome is the standard Bannerhold window ({@link Ui2Frame}): the
 * building's name as the serif title, its blessings as the subtitle, text
 * tabs, hairline-ruled rows, the burgundy Check Room action and the wood
 * close key.
 */
public class PlaqueScreen extends Screen implements QaUiInspectable {

    // 272 once carried the hire tab's cost sentence ("The Carpenter's Shop
    // would have no worker", 224px) in a 228px COST_BOX. The standard frame
    // spends 20px a side on wood and page padding, so 296 keeps that
    // sentence (and "Gislebert the Younger" in NAME_BOX) with margin.
    static final int PANEL_W = 296;
    private static final BlessingId[] BLESSING_IDS = BlessingId.values();
    static final int SCROLL_W = 4;
    static final int CARD_H = 38;
    static final int CARD_STEP = CARD_H + 4;
    /** Three rows normally; a short GUI uses the same list with fewer visible rows. */
    static final int MAX_ROWS = 3;
    /** Card column width at the preferred frame width. */
    static final int LIST_W = PANEL_W - 2 * (Ui2FrameLayout.FRAME + Ui2FrameLayout.MARGIN)
        - 2 * Ui2FrameLayout.PAD - SCROLL_W - 2;
    /** Tabs, list gap, note line and its gaps, and the footer action row. */
    static final int INNER_FIXED_H = Ui2FrameLayout.TABS_H + Ui2FrameLayout.M
        + Ui2FrameLayout.M + 10 + Ui2FrameLayout.M + Ui2FrameLayout.BUTTON_H;
    /** Everything but the list: wood, header with subtitle, page padding and the inner fixed rows. */
    static final int PANEL_FIXED_H = Ui2FrameLayout.FRAME + Ui2FrameLayout.S + Ui2FrameLayout.HEADER_H_SUBTITLE
        + 2 + Ui2FrameLayout.S + Ui2FrameLayout.PAD + INNER_FIXED_H
        + Ui2FrameLayout.PAD + Ui2FrameLayout.S + Ui2FrameLayout.FRAME;
    static final int REFRESH_W = 112;

    // Offsets from the card's left edge.
    static final int TEXT_OFF = 6;
    static final int BTN_W = 64;
    static final int BTN_OFF = LIST_W - 4 - BTN_W;
    static final int FIRE_W = 48;
    static final int SUMMON_W = 56;
    static final int ACTION_GAP = 4;
    static final int FIRE_OFF = LIST_W - 4 - FIRE_W;
    static final int SUMMON_OFF = FIRE_OFF - ACTION_GAP - SUMMON_W;
    static final int STAFF_ICON_OFF = 6;
    static final int STAFF_TEXT_OFF = STAFF_ICON_OFF + 16 + 6;
    static final int STAFF_TEXT_W = LIST_W - 6 - STAFF_TEXT_OFF;
    /** Fitness pips: five 5px squares, 8px apart, left of the action column. */
    static final int PIPS_W = 4 * 8 + 5;
    static final int PIPS_OFF = BTN_OFF - 4 - PIPS_W;

    /** The cost line gets the full card width; it is the point of the screen. */
    static final int NAME_BOX = PIPS_OFF - TEXT_OFF - 4;
    static final int POST_BOX = BTN_OFF - TEXT_OFF - 6;
    static final int COST_BOX = LIST_W - 2 * TEXT_OFF;
    /** Workplace staff text stays left of Summon/Fire. */
    static final int STAFF_ROW_BOX = SUMMON_OFF - TEXT_OFF - 6;

    // Requirements tab only: a wider text column that leaves room for a 16x16
    // representative item icon and the met/unmet glyph on the right.
    static final int REQ_ICON_OFF = 6;
    static final int REQ_TEXT_OFF = REQ_ICON_OFF + 16 + 6;
    static final int REQ_MARK_OFF = LIST_W - 12;
    static final int REQ_BOX = REQ_MARK_OFF - REQ_TEXT_OFF - 6;

    // Extracted (not invented) from BuildingType's own Requirement.blocks(...)
    // declarations -- the first block listed for each requirement id, wherever
    // it appears, is always the same block, so this is that same association
    // read onto the client rather than a new one. floor_space has no natural
    // single block and is left without an icon. Stacks, not bare Items, and
    // built once rather than allocated per row per frame -- the same idiom
    // PlaqueRenderer's own EMBLEMS cache already uses.
    private static final Map<String, ItemStack> REQUIREMENT_ICONS = Map.ofEntries(
        icon("anvil", Items.ANVIL), icon("beds", Items.RED_BED),
        icon("bell", Items.BELL), icon("bookshelf", Items.BOOKSHELF),
        icon("brewing_stand", Items.BREWING_STAND), icon("cauldron", Items.CAULDRON),
        icon("composter", Items.COMPOSTER), icon("doors", Items.OAK_DOOR),
        icon("dressed_stone", Items.STONE_BRICKS), icon("fletching", Items.FLETCHING_TABLE),
        icon("forge", Items.BLAST_FURNACE), icon("grindstone", Items.GRINDSTONE),
        icon("hay", Items.HAY_BLOCK), icon("hearth_fire", Items.CAMPFIRE),
        icon("ladder", Items.LADDER), icon("lectern", Items.LECTERN),
        icon("lights", Items.TORCH), icon("loom", Items.LOOM),
        icon("oven", Items.FURNACE), icon("sawbench", Items.STONECUTTER),
        icon("smithing_table", Items.SMITHING_TABLE), icon("smoker", Items.SMOKER),
        icon("stall", Items.BARREL), icon("storage", Items.CHEST),
        icon("water", Items.WATER_BUCKET), icon("workbench", Items.CRAFTING_TABLE));

    private static Map.Entry<String, ItemStack> icon(String id, Item item) {
        return Map.entry(id, new ItemStack(item));
    }

    private enum Tab {
        REQUIREMENTS("requirements"),
        PEOPLE("people"),
        HIRE("hire");

        private final String key;

        Tab(String key) {
            this.key = key;
        }

        Component label(boolean staff) {
            return Component.translatable("hearthstead.plaque.tab."
                + (this == PEOPLE && staff ? "staff" : key));
        }
    }

    private PlaqueSnapshot snapshot;
    private Tab tab = Tab.REQUIREMENTS;
    private int scroll;
    private int left;
    private int top;
    private Layout layout = layoutFor(PANEL_W + 16, PANEL_FIXED_H + MAX_ROWS * CARD_STEP + 16);
    private int visibleRows = MAX_ROWS;
    private int panelHeight = layout.frame().height();
    private final Ui2Serif.Text titleText = new Ui2Serif.Text(Ui2Serif.Size.TITLE);
    /** One sliding underline for the tabs; the Tab buttons are recreated on every rebuild. */
    private final Ui2Tabs tabIndicator = new Ui2Tabs();
    private int tabIndicatorY;
    /** Rebuilt only on the existing open/action snapshot path, never per frame. */
    private Component blessingStatusLine = Component.empty();
    private boolean hasBlessings;
    private List<List<Component>> requirementTooltips = List.of();
    private List<List<Component>> occupantTooltips = List.of();
    private List<List<Component>> candidateTooltips = List.of();
    private List<List<Component>> footerTooltips = List.of();
    private List<Component> headerTooltip = List.of();
    private List<Component> roomTooltip = List.of();

    /** Immutable text/layout projection for steady-state rendering. */
    private PlaqueRenderView renderView;
    private boolean uiOpenSoundPlayed;
    private boolean uiCloseSoundPlayed;

    public PlaqueScreen(PlaqueSnapshot snapshot) {
        super(Component.translatable("hearthstead.plaque.title"));
        this.snapshot = snapshot;
        rebuildBlessingStatus();
    }

    /** A fresh snapshot from the server replaces what is on screen. */
    public void update(PlaqueSnapshot fresh) {
        if (!acceptsSnapshot(fresh)) {
            return;
        }
        this.snapshot = fresh;
        this.renderView = null;
        rebuildBlessingStatus();
        rebuild();
    }

    /**
     * Exact physical-screen guard used by update-only network deliveries.
     *
     * <p>The building id is deliberately not part of the client-side match:
     * an authorized REFRESH may link an unlinked plaque (or replace its stale
     * saved link) inside this same server-authored session. The next snapshot
     * is the authority for that id transition; every action still echoes the
     * latest id and the server independently validates plaque, session and
     * building identity before it mutates anything.
     */
    public boolean acceptsSnapshot(PlaqueSnapshot fresh) {
        return snapshot != null && fresh != null
            && snapshot.pos().equals(fresh.pos())
            && snapshot.sessionId().equals(fresh.sessionId());
    }

    /** Eagerly release the server's one bounded viewer entry on any close. */
    @Override
    public void removed() {
        if (uiOpenSoundPlayed && !uiCloseSoundPlayed) {
            uiCloseSoundPlayed = true;
            HsUi.playCloseSound();
        }
        if (snapshot != null && minecraft != null && minecraft.getConnection() != null) {
            PacketDistributor.sendToServer(new PlaqueAction(snapshot.pos(),
                snapshot.buildingId(), snapshot.sessionId(),
                PlaqueAction.Kind.CLOSE, PlaqueAction.NO_BUILDING,
                snapshot.revision()));
        }
        super.removed();
    }

    @Override
    protected void init() {
        // Three rows at ordinary scales; fewer in a short viewport. The list
        // scrollbar remains the only scroll authority, so controls and
        // hitboxes never detach from the art.
        layout = layoutFor(width, height);
        visibleRows = layout.visibleRows();
        left = layout.frame().x();
        top = layout.frame().y();
        panelHeight = layout.frame().height();
        renderView = null;
        rebuild();
        if (!uiOpenSoundPlayed) {
            uiOpenSoundPlayed = true;
            HsUi.playOpenSound();
        }
    }

    // ------------------------------------------------------------ widgets ---

    private void rebuild() {
        clearWidgets();
        if (snapshot == null) {
            return;
        }
        Tab[] tabs = availableTabs();
        boolean currentTabAvailable = false;
        for (Tab candidate : tabs) {
            currentTabAvailable |= candidate == tab;
        }
        if (!currentTabAvailable) {
            tab = Tab.REQUIREMENTS;
            scroll = 0;
        }
        boolean staffLabels = !usesHousingAssignment();
        Component[] labels = new Component[tabs.length];
        boolean[] external = new boolean[tabs.length];
        for (int i = 0; i < tabs.length; i++) labels[i] = tabs[i].label(staffLabels);
        Rect tabRow = layout.tabs();
        int[] xs = Ui2Tabs.positions(font, labels, external, tabRow.x());
        for (int i = 0; i < tabs.length; i++) {
            Tab which = tabs[i];
            int w = Ui2Tabs.tabWidth(font, labels[i], false);
            addRenderableWidget(new Ui2Tabs.Tab(xs[i], tabRow.y(), w, labels[i], which == tab, false, () -> {
                QaClientObserver.markUiTransition("plaque_tab_" + which.key);
                tab = which;
                scroll = 0;
                rebuild();
            }));
            if (which == tab) {
                tabIndicator.target(xs[i] + 2, w - 4);
                tabIndicatorY = tabRow.bottom();
            }
        }

        int rows = rowCount();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows - visibleRows)));

        if (tab == Tab.PEOPLE) {
            buildPeople();
        } else if (tab == Tab.HIRE && usesHousingAssignment()) {
            buildHire();
        }

        // The plaque's one primary action; the old Close button is the wood close key (Esc still closes).
        Rect refresh = layout.refresh();
        Ui2Button checkRoom = Ui2Button.banner(refresh.x(), refresh.y(), refresh.width(), refresh.height(),
            Component.translatable("hearthstead.plaque.refresh"),
            () -> act(PlaqueAction.Kind.REFRESH, new UUID(0, 0)));
        Ui2Tips.tip(checkRoom, Component.translatable("hearthstead.plaque.refresh.tip"));
        addRenderableWidget(checkRoom);
        addRenderableWidget(Ui2Frame.closeKey(layout.frame(), this::onClose));
    }

    private void buildPeople() {
        List<PlaqueSnapshot.Occupant> people = snapshot.occupants();
        boolean workplace = isWorkplace();
        int rows = workplace ? people.size() + 1 : people.size();
        for (int row = 0; row < visibleRows && row + scroll < rows; row++) {
            int visibleIndex = row + scroll;
            if (workplace && visibleIndex == 0) {
                continue; // the role/emblem instruction has no worker action
            }
            int personIndex = workplace ? visibleIndex - 1 : visibleIndex;
            if (personIndex < 0 || personIndex >= people.size()) {
                continue;
            }
            PlaqueSnapshot.Occupant occupant = people.get(personIndex);
            if (workplace) {
                boolean may = snapshot.mayManage() && occupant.worker();
                Component why = !snapshot.mayManage()
                    ? Component.translatable("hearthstead.plaque.read_only")
                    : Component.translatable("hearthstead.plaque.summon.not_employed");
                Rect s = layout.summon(row);
                Ui2Button summon = Ui2Button.secondary(s.x(), s.y(), s.width(), s.height(),
                    Component.translatable("hearthstead.plaque.summon"),
                    () -> act(PlaqueAction.Kind.SUMMON, occupant.id()));
                gate(summon, may, Component.translatable("hearthstead.plaque.summon.tip", occupant.name()), why);
                addRenderableWidget(summon);
                Rect f = layout.fire(row);
                Ui2Button fire = Ui2Button.dangerText(f.x(), f.y(), f.width(), f.height(),
                    Component.translatable("hearthstead.plaque.fire"),
                    () -> act(PlaqueAction.Kind.FIRE, occupant.id()));
                gate(fire, may, Component.translatable("hearthstead.plaque.fire.tip", occupant.name()),
                    !snapshot.mayManage() ? why
                        : Component.translatable("hearthstead.plaque.fire.not_employed"));
                addRenderableWidget(fire);
                continue;
            }
            Rect d = layout.dismiss(row);
            Ui2Button dismiss = Ui2Button.dangerText(d.x(), d.y(), d.width(), d.height(),
                Component.translatable("hearthstead.employ.dismiss"),
                () -> act(PlaqueAction.Kind.EVICT, occupant.id()));
            gate(dismiss, snapshot.mayManage(),
                Component.translatable("hearthstead.plaque.evict.tip", occupant.name()),
                Component.translatable("hearthstead.plaque.read_only"));
            addRenderableWidget(dismiss);
        }
    }

    private void buildHire() {
        if (!usesHousingAssignment()) {
            return;
        }
        List<PlaqueSnapshot.Candidate> people = snapshot.candidates();
        for (int row = 0; row < visibleRows && row + scroll < people.size(); row++) {
            PlaqueSnapshot.Candidate candidate = people.get(row + scroll);
            boolean eligible = candidate.blockedReason().isEmpty();
            Rect h = layout.hire(row);
            Ui2Button hire = Ui2Button.secondary(h.x(), h.y(), h.width(), h.height(),
                Component.translatable("hearthstead.employ.hire"),
                () -> act(PlaqueAction.Kind.ASSIGN, candidate.id()));
            // A disabled control always says why (D-014).
            gate(hire, eligible && snapshot.mayManage(),
                Component.translatable("hearthstead.employ.hire.tip", candidate.name()),
                eligible ? Component.translatable("hearthstead.plaque.read_only")
                    : Component.translatable(candidate.blockedReason()));
            addRenderableWidget(hire);
        }
    }

    /** Keeps the action's own tooltip; a disabled action adds the grey reason under it (padlock drawn by the kit). */
    private static void gate(AbstractWidget widget, boolean enabled, Component tip, Component reason) {
        widget.active = enabled;
        Ui2Tips.tip(widget, enabled ? tip : Ui2Tips.why(tip, reason));
    }

    private int rowCount() {
        return switch (tab) {
            case PEOPLE -> snapshot.occupants().size() + (isWorkplace() ? 1 : 0);
            case HIRE -> usesHousingAssignment() ? snapshot.candidates().size() : 0;
            case REQUIREMENTS -> snapshot.requirements().size();
        };
    }

    /** Homes expose move-in candidates; every other plaque is inspection-only. */
    private Tab[] availableTabs() {
        return usesHousingAssignment()
            ? Tab.values()
            : new Tab[] {Tab.REQUIREMENTS, Tab.PEOPLE};
    }

    private BuildingType buildingType() {
        return BuildingType.byId(snapshot.buildingType());
    }

    private boolean usesHousingAssignment() {
        return buildingType().housesResidents();
    }

    private boolean isWorkplace() {
        BuildingType type = buildingType();
        return type.employsWorkers() && !type.housesResidents();
    }

    private void act(PlaqueAction.Kind kind, UUID target) {
        if (snapshot != null) {
            PacketDistributor.sendToServer(new PlaqueAction(
                snapshot.pos(), snapshot.buildingId(), snapshot.sessionId(),
                kind, target, snapshot.revision(),
                kind == PlaqueAction.Kind.FIRE ? employmentRevision(target) : 0L));
        }
    }

    private long employmentRevision(UUID target) {
        if (snapshot == null || target == null) return 0L;
        for (PlaqueSnapshot.Occupant occupant : snapshot.occupants()) {
            if (target.equals(occupant.id())) return occupant.employmentRevision();
        }
        return 0L;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        int rows = rowCount();
        if (rows > visibleRows) {
            int before = scroll;
            scroll = Math.max(0,
                Math.min(rows - visibleRows, scroll - (int) Math.signum(dy)));
            if (before != scroll) {
                QaClientObserver.markUiTransition("plaque_scroll");
                rebuild();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, dx, dy);
    }

    @Override
    public String qaUiState() {
        String type = snapshot == null ? "none" : snapshot.buildingType();
        int rows = snapshot == null ? 0 : rowCount();
        return "tab=" + tab.key + ",scroll=" + scroll + "/"
            + Math.max(0, rows - visibleRows) + ",rows=" + visibleRows
            + ",building=" + type + ",panel=" + left + ":" + top + ":"
            + layout.frame().width() + ":" + panelHeight;
    }

    // ------------------------------------------------------------- drawing ---

    // Motion only: first-open intro (4 px slide + fade); created once, survives re-init.
    private HsMotion.ScreenIntro hsIntro;
    private boolean hsIntroRendering;

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (hsIntro == null) hsIntro = new HsMotion.ScreenIntro();
        if (!hsIntroRendering && !hsIntro.done()) {
            hsIntroRendering = true;
            try {
                hsIntro.render(graphics, 4.0F, () -> render(graphics, mouseX, mouseY, partialTick));
            } finally {
                hsIntroRendering = false;
            }
            return;
        }
        renderTransparentBackground(graphics);
        if (snapshot == null) {
            HsUi.widgets(this, graphics, mouseX, mouseY, partialTick);
            return;
        }
        PlaqueRenderView view = renderView();
        Ui2FrameLayout frame = layout.frame();
        Ui2Frame.draw(graphics, frame);
        // The building's name in the serif title; its blessings (or "No Blessings") as the subtitle.
        Ui2Frame.title(graphics, font, frame, titleText, view.title().getString(), blessingStatusLine);
        if (buildingType() == BuildingType.FISHERY) {
            // The Fishery ledger keeps its restrained wave along the foot of the page.
            Rect page = frame.page();
            int y = page.bottom() - 4;
            for (int x = page.x() + 8; x + 12 <= page.right() - 8; x += 12) {
                graphics.fill(x, y, x + 6, y + 1, Ui2Palette.STATUS_BLUE);
                graphics.fill(x + 6, y - 1, x + 12, y, Ui2Palette.STATUS_BLUE);
            }
        }
        // One hairline under the tabs; the sliding underline rides on it.
        Rect tabRow = layout.tabs();
        Ui2Surface.rule(graphics, tabRow.x(), tabRow.bottom() + 1, tabRow.width());

        switch (tab) {
            case REQUIREMENTS -> drawRequirements(graphics, view);
            case PEOPLE -> drawPeople(graphics, mouseX, mouseY, view);
            case HIRE -> drawHire(graphics, mouseX, mouseY, view);
        }

        int rows = rowCount();
        if (rows > visibleRows) {
            Rect bar = layout.scrollbar();
            Ui2Surface.scrollbar(graphics, bar.x() + 1, bar.y(), bar.height(),
                Math.min(1.0F, (float) visibleRows / rows), (float) scroll / (rows - visibleRows));
        }

        Rect note = layout.note();
        Ui2Surface.rule(graphics, note.x(), note.y() - Ui2FrameLayout.S, note.width());
        graphics.drawString(font, view.footer(tab).text(), note.x(), note.y() + 1, Ui2Palette.INK_SOFT, false);
        HsUi.widgets(this, graphics, mouseX, mouseY, partialTick);
        tabIndicator.render(graphics, tabIndicatorY);
        renderInformationTooltip(graphics, mouseX, mouseY);
    }

    private Component title() {
        BuildingType type = BuildingType.byId(snapshot.buildingType());
        return type == null ? Component.translatable("hearthstead.plaque.title")
            : type.displayName();
    }

    /** Names plus Roman numerals; zero ranks are omitted, never colour-coded blanks. */
    private void rebuildBlessingStatus() {
        MutableComponent ranks = Component.empty();
        int activeRanks = 0;
        for (BlessingId blessing : BLESSING_IDS) {
            activeRanks += snapshot.blessingRank(blessing) > 0 ? 1 : 0;
        }
        boolean any = false;
        for (BlessingId blessing : BLESSING_IDS) {
            int rank = snapshot.blessingRank(blessing);
            if (rank <= 0) {
                continue;
            }
            if (any) {
                ranks.append(Component.literal(" • "));
            }
            ranks.append(Component.translatable("hearthstead.blessing."
                + blessing.id() + (activeRanks == BLESSING_IDS.length
                    ? ".short" : ".name")));
            ranks.append(Component.literal(" " + roman(rank)));
            any = true;
        }
        hasBlessings = any;
        blessingStatusLine = any ? Component.translatable("hearthstead.blessing.status", ranks)
            : Component.translatable("hearthstead.blessing.status.none");
        renderView = null;
    }

    private static String roman(int rank) {
        return switch (rank) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            default -> "—";
        };
    }

    /**
     * The footer says the one thing worth saying about this tab. On the hire
     * tab that is the suggestion — and it is a <i>suggestion</i>: the settlement
     * never moves anybody on its own (D-013). The server has already sorted the
     * candidates, so the first one is the recommendation.
     */
    private Component footer(Tab which) {
        if (which == Tab.HIRE) {
            List<PlaqueSnapshot.Candidate> people = snapshot.candidates();
            return people.isEmpty()
                ? Component.translatable("hearthstead.employ.no_candidates")
                : Component.translatable("hearthstead.employ.suggested",
                    people.get(0).name());
        }
        if (which == Tab.PEOPLE) {
            return usesHousingAssignment()
                ? Component.translatable("hearthstead.plaque.people_count",
                    snapshot.occupants().size(), snapshot.capacity())
                : Component.translatable("hearthstead.plaque.workers",
                    snapshot.occupants().size(), snapshot.capacity());
        }
        // The requirements tab's one thing worth saying is what the building
        // GIVES. The screen listed costs and never the payoff -- the owner's
        // masterplan called it out and byggherre-dom #4 confirmed the gap:
        // zero lines anywhere told a player what hiring here gets them. For
        // the four buildings nothing in the game reacts to yet, this same
        // line carries the honest 'not yet operational' instead -- a plaque
        // that goes green and does nothing must say so, not let the player
        // discover it by waiting.
        return Component.translatable(
            "hearthstead.building.benefit." + snapshot.buildingType());
    }

    private void drawRequirements(GuiGraphics graphics, PlaqueRenderView view) {
        List<PlaqueSnapshot.RequirementLine> lines = snapshot.requirements();
        if (lines.isEmpty()) {
            drawNoRoomCard(graphics, view);
            return;
        }
        for (int row = 0; row < visibleRows && row + scroll < lines.size(); row++) {
            PlaqueSnapshot.RequirementLine line = lines.get(row + scroll);
            Rect card = layout.card(row);
            boolean met = line.have() >= line.needed();
            // A 2px state bar and a glyph on the right; the counts are always written.
            graphics.fill(card.x(), card.y(), card.x() + 2, card.bottom(),
                met ? Ui2Palette.FOREST : Ui2Palette.AMBER);
            ruleUnder(graphics, row, lines.size());

            ItemStack icon = REQUIREMENT_ICONS.get(line.id());
            if (icon != null) {
                graphics.renderItem(icon, card.x() + REQ_ICON_OFF, card.y() + 11);
            }
            // The lang strings carry their own counts ("Storage %s/%s") --
            // the same keys the physical sheet formats -- so the row passes
            // the real numbers instead of showing literal placeholders
            // (found by the UI pass: an argless call rendered "%s/%s").
            // One source of truth; the separate count chip went with it.
            graphics.drawString(font, view.requirements().get(row + scroll).text(),
                card.x() + REQ_TEXT_OFF, card.y() + 15,
                met ? Ui2Palette.INK : Ui2Palette.AMBER, false);

            int markY = card.y() + (CARD_H - 7) / 2;
            if (met) {
                Ui2Surface.checkGlyph(graphics, card.x() + REQ_MARK_OFF, markY, Ui2Palette.FOREST);
            } else {
                Ui2Surface.pendingGlyph(graphics, card.x() + REQ_MARK_OFF + 1, markY + 1, Ui2Palette.AMBER);
            }
        }
    }

    /** Hairline between cards, never after the last one shown. */
    private void ruleUnder(GuiGraphics graphics, int row, int rows) {
        if (row + 1 < visibleRows && row + scroll + 1 < rows) {
            Rect card = layout.card(row);
            Ui2Surface.rule(graphics, card.x(), card.bottom() + 1, card.width());
        }
    }

    /**
     * The room itself did not scan — not enclosed, open to the sky, too big,
     * or nothing found near the plaque at all — so there is no
     * per-requirement checklist to draw. Before this the tab simply had
     * nothing here; "No room found" (5:27, the owner's underground
     * warehouse) explained neither what nor where. Now it names the
     * plaque's state and, whenever the server computed one, exactly why —
     * {@link PlaqueSnapshot#scanReason()}, the same sentence the chat
     * message and the physical sheet's fallback line now carry too.
     */
    private void drawNoRoomCard(GuiGraphics graphics, PlaqueRenderView view) {
        Rect card = layout.card(0);
        Ui2Frame.status(graphics, font, card, view.noRoomState().text(), Ui2Frame.Tone.WAIT);
        if (view.noRoomReason() != null) {
            graphics.drawString(font, view.noRoomReason().text(), card.x() + 14, card.y() + 17,
                Ui2Palette.INK_SOFT, false);
        }
    }

    private void drawPeople(GuiGraphics graphics, int mouseX, int mouseY,
                            PlaqueRenderView view) {
        List<PlaqueSnapshot.Occupant> people = snapshot.occupants();
        boolean workplace = isWorkplace();
        int rows = rowCount();
        for (int row = 0; row < visibleRows && row + scroll < rows; row++) {
            int visibleIndex = row + scroll;
            Rect card = layout.card(row);
            ruleUnder(graphics, row, rows);
            if (workplace && visibleIndex == 0) {
                drawStaffInstruction(graphics, card, view);
                continue;
            }
            int occupantIndex = workplace ? visibleIndex - 1 : visibleIndex;
            if (occupantIndex < 0 || occupantIndex >= people.size()) {
                continue;
            }
            PlaqueSnapshot.Occupant occupant = people.get(occupantIndex);
            Ui2Surface.row(graphics, card.x(), card.y(), card.width(), card.height(),
                hovering(mouseX, mouseY, row) ? 1.0F : 0.0F, false);
            OccupantRenderView rowView = view.occupants().get(occupantIndex);
            int x = card.x() + TEXT_OFF;
            graphics.drawString(font, rowView.name().text(), x, card.y() + 5, Ui2Palette.INK, false);
            graphics.drawString(font, rowView.profession().text(), x, card.y() + 17, Ui2Palette.INK_SOFT, false);
            float morale = occupant.morale() / 100.0F;
            HsUi.Tone tone = HsUi.Tone.of(morale);
            int barW = workplace ? Math.min(160, STAFF_ROW_BOX) : 80;
            int colour = tone == HsUi.Tone.GOOD ? Ui2Palette.FOREST
                : tone == HsUi.Tone.WARN ? Ui2Palette.AMBER : Ui2Palette.DANGER;
            Ui2Surface.progress(graphics, x, card.y() + 30, barW, morale, colour);
            if (tone == HsUi.Tone.BAD) {
                // Low morale is never colour alone.
                Ui2Surface.alertGlyph(graphics, x + barW + 4, card.y() + 29, Ui2Palette.DANGER);
            }
        }
    }

    /** One role-and-action card, always first on a workplace's Staff page. */
    private void drawStaffInstruction(GuiGraphics graphics, Rect card, PlaqueRenderView view) {
        if (!view.staffEmblem().isEmpty()) {
            graphics.renderItem(view.staffEmblem(), card.x() + STAFF_ICON_OFF, card.y() + 11);
        }
        // Three short lines share the existing row; no extra scroll space or action.
        int x = card.x() + STAFF_TEXT_OFF;
        graphics.drawString(font, view.staffRequiredRole().text(), x, card.y() + 4, Ui2Palette.GOLD, false);
        graphics.drawString(font, view.staffObtain().text(), x, card.y() + 15, Ui2Palette.INK_MUTED, false);
        graphics.drawString(font, view.staffChoice().text(), x, card.y() + 26, Ui2Palette.INK, false);
    }

    private void drawHire(GuiGraphics graphics, int mouseX, int mouseY,
                          PlaqueRenderView view) {
        if (!usesHousingAssignment()) {
            return;
        }
        List<PlaqueSnapshot.Candidate> people = snapshot.candidates();
        for (int row = 0; row < visibleRows && row + scroll < people.size(); row++) {
            PlaqueSnapshot.Candidate candidate = people.get(row + scroll);
            Rect card = layout.card(row);
            Ui2Surface.row(graphics, card.x(), card.y(), card.width(), card.height(),
                hovering(mouseX, mouseY, row) ? 1.0F : 0.0F, false);
            ruleUnder(graphics, row, people.size());
            CandidateRenderView rowView = view.candidates().get(row + scroll);
            int x = card.x() + TEXT_OFF;
            graphics.drawString(font, rowView.name().text(), x, card.y() + 4, Ui2Palette.INK, false);
            pips(graphics, card.x() + PIPS_OFF, card.y() + 6, candidate.fitness(), 5);
            graphics.drawString(font, rowView.post().text(), x, card.y() + 15, Ui2Palette.INK_SOFT, false);
            // The sentence that says what taking them costs, on its own row at
            // full card width, amber when a building would be left empty.
            graphics.drawString(font, rowView.cost().text(), x, card.y() + 27,
                rowView.emptiesPost() ? Ui2Palette.AMBER : Ui2Palette.INK_MUTED, false);
        }
    }

    /** Fitness as five small squares: gold for each point, a track for the rest. */
    private static void pips(GuiGraphics g, int x, int y, int filled, int of) {
        for (int i = 0; i < of; i++) {
            g.fill(x + i * 8, y, x + i * 8 + 5, y + 5, i < filled ? Ui2Palette.GOLD : Ui2Palette.TRACK);
        }
    }

    private Component currentPost(PlaqueSnapshot.Candidate candidate) {
        return candidate.costArg().isEmpty()
            ? Component.translatable("hearthstead.employ.unemployed")
            : Component.translatable(candidate.costArg());
    }

    private Component costSentence(PlaqueSnapshot.Candidate candidate) {
        return candidate.costArg().isEmpty()
            ? Component.translatable(candidate.costKey())
            : Component.translatable(candidate.costKey(),
                Component.translatable(candidate.costArg()));
    }

    /**
     * Creates all translated, measured and wrapped row text once per server
     * snapshot/font/language epoch. Scrolling only selects another cached row;
     * ordinary frames no longer allocate Components or split staff text.
     */
    private PlaqueRenderView renderView() {
        Font currentFont = font;
        String language = minecraft.getLanguageManager().getSelected();
        if (renderView == null || !renderViewInputsMatch(renderView.snapshot(),
            renderView.revision(), renderView.font(), renderView.language(),
            snapshot, snapshot.revision(), currentFont, language)) {
            renderView = buildRenderView(currentFont, language);
        }
        return renderView;
    }

    private PlaqueRenderView buildRenderView(Font currentFont, String language) {
        rebuildInformationTooltips();
        List<HsUi.FittedLabel> requirements = new ArrayList<>(
            snapshot.requirements().size());
        for (PlaqueSnapshot.RequirementLine line : snapshot.requirements()) {
            requirements.add(HsUi.fitLabel(currentFont,
                Component.translatable("hearthstead.requirement." + line.id(),
                    line.have(), line.needed()), REQ_BOX));
        }

        boolean workplace = isWorkplace();
        int occupantNameWidth = workplace ? STAFF_ROW_BOX : NAME_BOX;
        int occupantPostWidth = workplace ? STAFF_ROW_BOX : POST_BOX;
        List<OccupantRenderView> occupants = new ArrayList<>(snapshot.occupants().size());
        for (PlaqueSnapshot.Occupant occupant : snapshot.occupants()) {
            occupants.add(new OccupantRenderView(
                HsUi.fitLabel(currentFont, Component.literal(occupant.name()),
                    occupantNameWidth),
                HsUi.fitLabel(currentFont, Component.translatable(
                    "hearthstead.profession."
                        + occupant.profession().toLowerCase(java.util.Locale.ROOT)),
                    occupantPostWidth)));
        }

        List<CandidateRenderView> candidates = new ArrayList<>(
            snapshot.candidates().size());
        for (PlaqueSnapshot.Candidate candidate : snapshot.candidates()) {
            candidates.add(new CandidateRenderView(
                HsUi.fitLabel(currentFont, Component.literal(candidate.name()), NAME_BOX),
                HsUi.fitLabel(currentFont, currentPost(candidate), POST_BOX),
                HsUi.fitLabel(currentFont, costSentence(candidate), COST_BOX),
                candidate.costKey().equals("hearthstead.employ.cost.leaves_empty")));
        }

        List<HsUi.FittedLabel> footers = new ArrayList<>(Tab.values().length);
        for (Tab which : Tab.values()) {
            footers.add(HsUi.fitLabel(currentFont, footer(which), LIST_W + SCROLL_W + 2));
        }
        HsUi.FittedLabel reason = snapshot.scanReason()
            .map(component -> HsUi.fitLabel(currentFont, component, LIST_W - 18))
            .orElse(null);
        Profession workplaceProfession = Employment.tradeOf(buildingType());
        ItemStack staffEmblem = JobEmblemItem.stackFor(workplaceProfession);
        Component staffRole = Component.translatable("hearthstead.plaque.staff.required",
            workplaceProfession.displayName());
        Component staffObtain = Component.translatable(staffEmblem.isEmpty()
            ? "hearthstead.plaque.staff.unavailable"
            : buildingType() == BuildingType.TAVERN
                ? "hearthstead.plaque.staff.tavern.obtain" : "hearthstead.plaque.staff.obtain");
        Component staffChoice = staffEmblem.isEmpty() ? Component.empty()
            : Component.translatable("hearthstead.plaque.staff.assign");
        int tipW = PANEL_W - 20;
        ArrayList<FormattedCharSequence> staffTooltip = new ArrayList<>();
        staffTooltip.addAll(currentFont.split(staffRole, tipW));
        if (!staffEmblem.isEmpty()) {
            staffTooltip.addAll(currentFont.split(staffEmblem.getHoverName(), tipW));
        }
        staffTooltip.addAll(currentFont.split(Component.translatable(staffEmblem.isEmpty()
            ? "hearthstead.plaque.staff.unavailable" : "hearthstead.plaque.staff.instructions"),
            tipW));
        if (buildingType() == BuildingType.TAVERN) {
            staffTooltip.addAll(currentFont.split(Component.translatable(
                "hearthstead.plaque.staff.tavern.supplies"), tipW));
            staffTooltip.addAll(currentFont.split(Component.translatable(
                "hearthstead.plaque.staff.tavern.seating"), tipW));
        }
        return new PlaqueRenderView(snapshot, snapshot.revision(), currentFont, language,
            title(), List.copyOf(footers), List.copyOf(requirements),
            HsUi.fitLabel(currentFont,
                Component.translatable("hearthstead.plaque.state." + snapshot.state()),
                LIST_W - 18), reason, List.copyOf(occupants), List.copyOf(candidates),
            HsUi.fitLabel(currentFont, staffRole, STAFF_TEXT_W),
            HsUi.fitLabel(currentFont, staffObtain, STAFF_TEXT_W),
            HsUi.fitLabel(currentFont, staffChoice, STAFF_TEXT_W),
            staffEmblem, List.copyOf(staffTooltip));
    }

    private void rebuildInformationTooltips() {
        ArrayList<List<Component>> requirements = new ArrayList<>();
        for (PlaqueSnapshot.RequirementLine line : snapshot.requirements()) {
            requirements.add(List.of(Component.translatable("hearthstead.requirement." + line.id(),
                line.have(), line.needed())));
        }
        ArrayList<List<Component>> occupants = new ArrayList<>();
        for (PlaqueSnapshot.Occupant person : snapshot.occupants()) {
            occupants.add(List.of(Component.literal(person.name()),
                Component.translatable("hearthstead.profession."
                    + person.profession().toLowerCase(java.util.Locale.ROOT))));
        }
        ArrayList<List<Component>> candidates = new ArrayList<>();
        for (PlaqueSnapshot.Candidate person : snapshot.candidates()) {
            ArrayList<Component> lines = new ArrayList<>();
            lines.add(Component.literal(person.name()));
            lines.add(currentPost(person));
            lines.add(costSentence(person));
            if (!person.blockedReason().isEmpty()) lines.add(Component.translatable(person.blockedReason()));
            candidates.add(List.copyOf(lines));
        }
        ArrayList<List<Component>> footers = new ArrayList<>();
        for (Tab which : Tab.values()) footers.add(List.of(footer(which)));
        ArrayList<Component> room = new ArrayList<>();
        room.add(Component.translatable("hearthstead.plaque.state." + snapshot.state()));
        snapshot.scanReason().ifPresent(room::add);
        requirementTooltips = List.copyOf(requirements);
        occupantTooltips = List.copyOf(occupants);
        candidateTooltips = List.copyOf(candidates);
        footerTooltips = List.copyOf(footers);
        headerTooltip = List.of(title(), blessingStatusLine);
        roomTooltip = List.copyOf(room);
    }

    private void renderInformationTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        Rect titleRect = layout.frame().title();
        if (titleRect.contains(mouseX, mouseY)) {
            graphics.renderComponentTooltip(font, headerTooltip, mouseX, mouseY);
            return;
        }
        if (layout.note().contains(mouseX, mouseY)) {
            graphics.renderComponentTooltip(font, footerTooltips.get(tab.ordinal()), mouseX, mouseY);
            return;
        }
        int row = -1;
        for (int r = 0; r < visibleRows; r++) {
            if (layout.card(r).contains(mouseX, mouseY)) row = r;
        }
        if (row < 0) return;
        int x = mouseX - layout.list().x();
        int index = row + scroll;
        List<Component> tooltip = null;
        if (tab == Tab.REQUIREMENTS) {
            tooltip = requirementTooltips.isEmpty() ? roomTooltip
                : index < requirementTooltips.size() ? requirementTooltips.get(index) : null;
        } else if (tab == Tab.PEOPLE) {
            if (isWorkplace() && index == 0) {
                graphics.renderTooltip(font, renderView().staffTooltip(), mouseX, mouseY);
                return;
            } else {
                int person = index - (isWorkplace() ? 1 : 0);
                int actionsX = isWorkplace() ? SUMMON_OFF : BTN_OFF;
                if (x < actionsX && person >= 0 && person < occupantTooltips.size()) {
                    tooltip = occupantTooltips.get(person);
                }
            }
        } else if (x < BTN_OFF && index < candidateTooltips.size()) {
            tooltip = candidateTooltips.get(index);
        }
        if (tooltip != null) graphics.renderComponentTooltip(font, tooltip, mouseX, mouseY);
    }

    /** Package-visible cache key for focused allocation/invalidation tests. */
    static boolean renderViewInputsMatch(Object cachedSnapshot, int cachedRevision,
                                         Object cachedFont, String cachedLanguage,
                                         Object snapshot, int revision,
                                         Object font, String language) {
        return cachedSnapshot == snapshot
            && cachedRevision == revision
            && cachedFont == font
            && Objects.equals(cachedLanguage, language);
    }

    private record PlaqueRenderView(PlaqueSnapshot snapshot, int revision,
                                    Font font, String language,
                                    Component title,
                                    List<HsUi.FittedLabel> footers,
                                    List<HsUi.FittedLabel> requirements,
                                    HsUi.FittedLabel noRoomState,
                                    HsUi.FittedLabel noRoomReason,
                                    List<OccupantRenderView> occupants,
                                    List<CandidateRenderView> candidates,
                                    HsUi.FittedLabel staffRequiredRole,
                                    HsUi.FittedLabel staffObtain,
                                    HsUi.FittedLabel staffChoice,
                                    ItemStack staffEmblem,
                                    List<FormattedCharSequence> staffTooltip) {
        HsUi.FittedLabel footer(Tab tab) {
            return footers.get(tab.ordinal());
        }
    }

    private record OccupantRenderView(HsUi.FittedLabel name,
                                      HsUi.FittedLabel profession) {
    }

    private record CandidateRenderView(HsUi.FittedLabel name,
                                       HsUi.FittedLabel post,
                                       HsUi.FittedLabel cost,
                                       boolean emptiesPost) {
    }

    private boolean hovering(int mouseX, int mouseY, int row) {
        Rect card = layout.card(row);
        return mouseX >= card.x() && mouseX <= card.right()
            && mouseY >= card.y() && mouseY <= card.bottom();
    }

    /** The server answers nothing once the block is gone; close instead of showing dead buttons. */
    @Override
    public void tick() {
        super.tick();
        if (minecraft == null || minecraft.level == null || snapshot == null) return;
        if (minecraft.level.isLoaded(snapshot.pos())
            && !(minecraft.level.getBlockState(snapshot.pos()).getBlock() instanceof com.hearthstead.block.PlaqueBlock)) {
            onClose();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ geometry ---

    /** Rows that fit the viewport (1..3), and the standard frame sized to hold exactly them. */
    static Layout layoutFor(int viewportW, int viewportH) {
        int rows = Math.max(1, Math.min(MAX_ROWS,
            (viewportH - 2 * Ui2FrameLayout.VIEWPORT_MARGIN - PANEL_FIXED_H + 4) / CARD_STEP));
        int listH = rows * CARD_STEP - 4;
        Ui2FrameLayout f = Ui2FrameLayout.centred(viewportW, viewportH, PANEL_W, PANEL_FIXED_H + listH, true);
        Rect c = f.content();
        Rect tabs = new Rect(c.x(), c.y(), c.width(), Ui2FrameLayout.TABS_H);
        int listTop = tabs.bottom() + Ui2FrameLayout.M;
        Rect list = new Rect(c.x(), listTop, c.width() - SCROLL_W - 2, listH);
        Rect scrollbar = new Rect(c.right() - 3, listTop, 3, listH);
        Rect note = new Rect(c.x(), list.bottom() + Ui2FrameLayout.M, c.width(), 10);
        Rect refresh = f.footerButtons(1, REFRESH_W)[0];
        return new Layout(f, rows, tabs, list, scrollbar, note, refresh);
    }

    /** Absolute screen geometry of the plaque window. */
    record Layout(Ui2FrameLayout frame, int visibleRows, Rect tabs, Rect list, Rect scrollbar, Rect note,
                  Rect refresh) {
        Rect card(int row) {
            return new Rect(list.x(), list.y() + row * CARD_STEP, list.width(), CARD_H);
        }

        /** Home occupant: Dismiss, a danger button centred on the card. */
        Rect dismiss(int row) {
            Rect c = card(row);
            return new Rect(c.x() + BTN_OFF, c.y() + (CARD_H - Ui2FrameLayout.TEXT_BUTTON_H) / 2, BTN_W,
                Ui2FrameLayout.TEXT_BUTTON_H);
        }

        /** Workplace worker: Fire, a red text button at the right (same weight as Summon). */
        Rect fire(int row) {
            Rect c = card(row);
            return new Rect(c.x() + FIRE_OFF, c.y() + (CARD_H - Ui2FrameLayout.TEXT_BUTTON_H) / 2, FIRE_W,
                Ui2FrameLayout.TEXT_BUTTON_H);
        }

        /** Workplace worker: Summon, a text button left of Fire. */
        Rect summon(int row) {
            Rect c = card(row);
            return new Rect(c.x() + SUMMON_OFF, c.y() + (CARD_H - Ui2FrameLayout.TEXT_BUTTON_H) / 2, SUMMON_W,
                Ui2FrameLayout.TEXT_BUTTON_H);
        }

        /** Move-in candidate: Hire, a text button on the name line (the cost line runs full width below). */
        Rect hire(int row) {
            Rect c = card(row);
            return new Rect(c.x() + BTN_OFF, c.y() + 3, BTN_W, Ui2FrameLayout.TEXT_BUTTON_H);
        }
    }
}
