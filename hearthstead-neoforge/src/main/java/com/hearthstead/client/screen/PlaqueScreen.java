package com.hearthstead.client.screen;

import com.hearthstead.building.BuildingType;
import com.hearthstead.client.QaClientObserver;
import com.hearthstead.client.QaUiInspectable;
import com.hearthstead.client.ui.HsButton;
import com.hearthstead.client.ui.HsUi;
import com.hearthstead.client.ui.HsUiTokens;
import com.hearthstead.network.PlaqueAction;
import com.hearthstead.network.PlaqueSnapshot;
import com.hearthstead.settlement.state.BlessingId;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
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
 */
public class PlaqueScreen extends Screen implements QaUiInspectable {

    // 256 clipped the hire screen's cost sentence -- "The Carpenter's Shop
    // would have no worker" measured 224px against the 212px COST_BOX it
    // derives (256px is the point of the screen per the class doc, so it
    // wraps and truncating it is the wrong fix). 272 carries COST_BOX to
    // 228px, and as a side effect also clears NAME_BOX past the crowded-
    // settlement "Gislebert the Younger" fallback (111px) with margin.
    private static final int PANEL_W = 272;
    private static final BlessingId[] BLESSING_IDS = BlessingId.values();
    private static final int PAD = 8;
    private static final int SCROLL_W = HsUiTokens.SCROLL_W;
    private static final int CARD_X = PAD;
    private static final int CARD_W = PANEL_W - 2 * PAD - SCROLL_W - 2;
    private static final int CARD_H = 38;
    private static final int CARD_STEP = CARD_H + 4;
    private static final int BTN_W = 64;
    private static final int BTN_X = CARD_X + CARD_W - BTN_W - 8;
    // The footer's own "Survey again" / close pair sit far apart (the close
    // button is pinned to BTN_X, on the card column's right edge) with no
    // shared-width need, so the refresh button gets its own, wider constant:
    // "Survey again" measures 66px and "Mål opp på nytt" 77px against a
    // BTN_W (64) box, both over its 56px label budget.
    private static final int REFRESH_BTN_W = 92;
    private static final int TEXT_X = CARD_X + 10;
    private static final int LIST_TOP = 52;
    /** Three rows normally; a short GUI uses the same list with fewer visible rows. */
    private static final int MAX_ROWS = 3;
    private static final int PANEL_FIXED_H = LIST_TOP - 4 + 6
        + 20 + HsUiTokens.BUTTON_H + 10;

    /** The cost line gets the full card width; it is the point of the screen. */
    private static final int NAME_BOX = BTN_X - TEXT_X - 40;
    private static final int POST_BOX = BTN_X - TEXT_X - 6;
    private static final int COST_BOX = CARD_W - 20;

    // Requirements tab only: a local, wider text column that leaves room for
    // a 16x16 representative item icon. Kept separate from TEXT_X/POST_BOX so
    // the Hire tab's already-measured cost-sentence budget (COST_BOX) is
    // untouched by this.
    private static final int REQ_ICON_X = CARD_X + 6;
    private static final int REQ_TEXT_X = REQ_ICON_X + 16 + 6;
    private static final int REQ_BOX = BTN_X - REQ_TEXT_X - 6;

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
    private int visibleRows = MAX_ROWS;
    private int listHeight = MAX_ROWS * CARD_STEP - 4;
    private int footerTop = LIST_TOP + listHeight + 6;
    private int panelHeight = footerTop + 20 + HsUiTokens.BUTTON_H + 10;
    /** The header emblem -- rebuilt only when the building type changes. */
    private ItemStack emblem = ItemStack.EMPTY;
    /** Rebuilt only on the existing open/action snapshot path, never per frame. */
    private Component blessingStatusLine = Component.empty();
    private boolean hasBlessings;
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
        rebuildEmblem();
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

    /** The building's own item stands in for a coat of arms beside its name. */
    private void rebuildEmblem() {
        BuildingType type = snapshot == null ? null : BuildingType.byId(snapshot.buildingType());
        emblem = type == null ? ItemStack.EMPTY : new ItemStack(type.emblem());
    }

    @Override
    protected void init() {
        left = (width - PANEL_W) / 2;
        // 230px/3 rows at ordinary scales; 146px/1 row in a 180px-tall
        // guiScale-4 viewport. The existing list scrollbar remains the only
        // scroll authority, so controls and hitboxes never detach from art.
        visibleRows = Math.max(1, Math.min(MAX_ROWS,
            (height - HsUiTokens.GRID * 2 - PANEL_FIXED_H) / CARD_STEP));
        listHeight = visibleRows * CARD_STEP - 4;
        footerTop = LIST_TOP + listHeight + 6;
        panelHeight = footerTop + 20 + HsUiTokens.BUTTON_H + 10;
        top = (height - panelHeight) / 2;
        renderView = null;
        rebuildEmblem();
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
        int tabW = (PANEL_W - 20 - (tabs.length - 1) * 4) / tabs.length;
        boolean staffLabels = !usesHousingAssignment();
        for (int i = 0; i < tabs.length; i++) {
            Tab which = tabs[i];
            addRenderableWidget(new TabButton(
                left + 10 + i * (tabW + 4), top + 26, tabW, 16,
                which.label(staffLabels), which == tab, () -> {
                    QaClientObserver.markUiTransition("plaque_tab_" + which.key);
                    tab = which;
                    scroll = 0;
                    rebuild();
                }));
        }

        int rows = rowCount();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows - visibleRows)));

        if (tab == Tab.PEOPLE) {
            buildPeople();
        } else if (tab == Tab.HIRE && usesHousingAssignment()) {
            buildHire();
        }

        addRenderableWidget(HsButton.normal(left + 10, top + footerTop + 20, REFRESH_BTN_W,
            HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.plaque.refresh"),
            () -> act(PlaqueAction.Kind.REFRESH, new UUID(0, 0))));
        addRenderableWidget(HsButton.normal(left + BTN_X, top + footerTop + 20, BTN_W,
            HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.plaque.close"), this::onClose));
    }

    private void buildPeople() {
        // Workplace staff is intentionally read-only. Housing keeps its
        // existing resident eviction control; no work action is authored here.
        if (!usesHousingAssignment()) {
            return;
        }
        List<PlaqueSnapshot.Occupant> people = snapshot.occupants();
        for (int row = 0; row < visibleRows && row + scroll < people.size(); row++) {
            PlaqueSnapshot.Occupant occupant = people.get(row + scroll);
            int y = top + LIST_TOP + row * CARD_STEP;
            HsButton dismiss = HsButton.danger(left + BTN_X, y + 4, BTN_W,
                HsUiTokens.BUTTON_H,
                Component.translatable("hearthstead.employ.dismiss"),
                () -> act(PlaqueAction.Kind.EVICT, occupant.id()));
            dismiss.active = snapshot.mayManage();
            dismiss.setTooltip(Tooltip.create(Component.translatable(
                "hearthstead.plaque.evict.tip", occupant.name())));
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
            int y = top + LIST_TOP + row * CARD_STEP;
            boolean eligible = candidate.blockedReason().isEmpty();
            HsButton hire = HsButton.normal(left + BTN_X, y + 4, BTN_W,
                HsUiTokens.BUTTON_H,
                Component.translatable("hearthstead.employ.hire"),
                () -> act(PlaqueAction.Kind.ASSIGN, candidate.id()));
            hire.active = eligible && snapshot.mayManage();
            // A disabled control always says why (D-014).
            hire.setTooltip(Tooltip.create(eligible
                ? Component.translatable("hearthstead.employ.hire.tip",
                    candidate.name())
                : Component.translatable(candidate.blockedReason())));
            addRenderableWidget(hire);
        }
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
                kind, target, snapshot.revision()));
        }
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
            + PANEL_W + ":" + panelHeight;
    }

    // ------------------------------------------------------------- drawing ---

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        if (snapshot == null) {
            HsUi.widgets(this, graphics, mouseX, mouseY, partialTick);
            return;
        }
        PlaqueRenderView view = renderView();
        HsUi.window(graphics, left, top, PANEL_W, panelHeight);
        if (!emblem.isEmpty()) {
            // The building's own item beside its name -- a small coat of arms,
            // not a functional slot (it never gets a tooltip or a hover state).
            graphics.renderItem(emblem, left + 10, top + 6);
        }
        HsUi.label(graphics, font, view.title().text(),
            left + PANEL_W / 2 - view.title().width() / 2, top + 6,
            HsUiTokens.TEXT_STRONG);
        // The second header line uses space that was previously blank, so it
        // adds no height at either the normal three-row size or the compact
        // guiScale-4 one-row size.
        HsUi.label(graphics, font, view.blessing().text(), left + 32, top + 16,
            hasBlessings ? HsUiTokens.ACCENT : HsUiTokens.TEXT_MUTED);
        // A double rule instead of one -- the same ink, just given a second
        // hairline of breathing room, the way a title page is ruled off from
        // its body. Both fit between the tabs (ending at top+42) and
        // LIST_TOP (52) with 2px clear on every side.
        HsUi.divider(graphics, left + 10, top + 44, PANEL_W - 20);
        HsUi.divider(graphics, left + 10, top + 48, PANEL_W - 20);

        switch (tab) {
            case REQUIREMENTS -> drawRequirements(graphics, view);
            case PEOPLE -> drawPeople(graphics, mouseX, mouseY, view);
            case HIRE -> drawHire(graphics, mouseX, mouseY, view);
        }

        int rows = rowCount();
        HsUi.scrollbar(graphics, left + PANEL_W - PAD - SCROLL_W, top + LIST_TOP,
            listHeight, rows == 0 ? 1.0F
                : Math.min(1.0F, (float) visibleRows / rows),
            rows <= visibleRows ? 0.0F : (float) scroll / (rows - visibleRows), false);

        HsUi.divider(graphics, left + 10, top + footerTop, PANEL_W - 20);
        HsUi.label(graphics, font, view.footer(tab).text(), left + 12,
            top + footerTop + 7, HsUiTokens.ACCENT);
        HsUi.widgets(this, graphics, mouseX, mouseY, partialTick);
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
            int y = top + LIST_TOP + row * CARD_STEP;
            boolean met = line.have() >= line.needed();
            HsUi.card(graphics, left + CARD_X, y, CARD_W, CARD_H, false);

            ItemStack icon = REQUIREMENT_ICONS.get(line.id());
            if (icon != null) {
                graphics.renderItem(icon, left + REQ_ICON_X, y + 11);
            }
            // The lang strings carry their own counts ("Storage %s/%s") --
            // the same keys the physical sheet formats -- so the row passes
            // the real numbers instead of showing literal placeholders
            // (found by the UI pass: an argless call rendered "%s/%s").
            // One source of truth; the separate count chip went with it.
            HsUi.label(graphics, font, view.requirements().get(row + scroll).text(),
                left + REQ_TEXT_X, y + 14,
                met ? HsUiTokens.TEXT_STRONG : HsUiTokens.WARN);

            HsUi.pips(graphics, left + BTN_X, y + 10,
                line.needed() == 0 ? 5
                    : Math.min(5, line.have() * 5 / Math.max(1, line.needed())),
                5, met ? HsUi.Tone.GOOD : HsUi.Tone.WARN);
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
        int y = top + LIST_TOP;
        HsUi.card(graphics, left + CARD_X, y, CARD_W, CARD_H, false);
        boolean hasReason = view.noRoomReason() != null;
        HsUi.label(graphics, font, view.noRoomState().text(), left + TEXT_X,
            y + (hasReason ? 9 : 15), HsUiTokens.WARN);
        if (hasReason) {
            HsUi.label(graphics, font, view.noRoomReason().text(), left + TEXT_X,
                y + 22, HsUiTokens.TEXT_MUTED);
        }
    }

    private void drawPeople(GuiGraphics graphics, int mouseX, int mouseY,
                            PlaqueRenderView view) {
        List<PlaqueSnapshot.Occupant> people = snapshot.occupants();
        boolean workplace = isWorkplace();
        int rows = rowCount();
        for (int row = 0; row < visibleRows && row + scroll < rows; row++) {
            int visibleIndex = row + scroll;
            int y = top + LIST_TOP + row * CARD_STEP;
            if (workplace && visibleIndex == 0) {
                drawStaffInstruction(graphics, y, view);
                continue;
            }
            int occupantIndex = workplace ? visibleIndex - 1 : visibleIndex;
            if (occupantIndex < 0 || occupantIndex >= people.size()) {
                continue;
            }
            PlaqueSnapshot.Occupant occupant = people.get(occupantIndex);
            boolean hovered = hovering(mouseX, mouseY, y);
            HsUi.card(graphics, left + CARD_X, y, CARD_W, CARD_H, hovered);
            cardFrame(graphics, y);
            OccupantRenderView rowView = view.occupants().get(occupantIndex);
            HsUi.label(graphics, font, rowView.name().text(), left + TEXT_X,
                y + 5, HsUiTokens.TEXT_STRONG);
            HsUi.label(graphics, font, rowView.profession().text(), left + TEXT_X,
                y + 17, HsUiTokens.TEXT_MUTED);
            float morale = occupant.morale() / 100.0F;
            HsUi.bar(graphics, left + TEXT_X, y + 29,
                workplace ? Math.min(160, COST_BOX) : 80, 6, morale,
                HsUi.Tone.of(morale));
        }
    }

    /** One wrapped rule card, always first on a workplace's Staff page. */
    private void drawStaffInstruction(GuiGraphics graphics, int y,
                                      PlaqueRenderView view) {
        HsUi.card(graphics, left + CARD_X, y, CARD_W, CARD_H, false);
        cardFrame(graphics, y);
        List<FormattedCharSequence> lines = view.staffInstructions();
        int shown = Math.min(3, lines.size());
        int textTop = y + Math.max(5, (CARD_H - shown * 10) / 2);
        for (int line = 0; line < shown; line++) {
            graphics.drawString(font, lines.get(line), left + TEXT_X,
                textTop + line * 10, line == 0
                    ? HsUiTokens.ACCENT : HsUiTokens.TEXT_STRONG, true);
        }
    }

    private void drawHire(GuiGraphics graphics, int mouseX, int mouseY,
                          PlaqueRenderView view) {
        if (!usesHousingAssignment()) {
            return;
        }
        List<PlaqueSnapshot.Candidate> people = snapshot.candidates();
        for (int row = 0; row < visibleRows && row + scroll < people.size(); row++) {
            PlaqueSnapshot.Candidate candidate = people.get(row + scroll);
            int y = top + LIST_TOP + row * CARD_STEP;
            boolean hovered = hovering(mouseX, mouseY, y);
            HsUi.card(graphics, left + CARD_X, y, CARD_W, CARD_H, hovered);
            cardFrame(graphics, y);
            CandidateRenderView rowView = view.candidates().get(row + scroll);
            HsUi.label(graphics, font, rowView.name().text(), left + TEXT_X,
                y + 5, HsUiTokens.TEXT_STRONG);
            HsUi.pips(graphics, left + BTN_X - 36, y + 6, candidate.fitness(), 5,
                HsUi.Tone.ACCENT);
            HsUi.label(graphics, font, rowView.post().text(), left + TEXT_X,
                y + 17, HsUiTokens.TEXT_MUTED);
            // The sentence that says what taking them costs, on its own row at
            // full card width, amber when a building would be left empty.
            HsUi.label(graphics, font, rowView.cost().text(), left + TEXT_X,
                y + 28, rowView.emptiesPost() ? HsUiTokens.WARN
                    : HsUiTokens.TEXT_MUTED);
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
        List<HsUi.FittedLabel> requirements = new ArrayList<>(
            snapshot.requirements().size());
        for (PlaqueSnapshot.RequirementLine line : snapshot.requirements()) {
            requirements.add(HsUi.fitLabel(currentFont,
                Component.translatable("hearthstead.requirement." + line.id(),
                    line.have(), line.needed()), REQ_BOX));
        }

        boolean workplace = isWorkplace();
        int occupantNameWidth = workplace ? COST_BOX : NAME_BOX;
        int occupantPostWidth = workplace ? COST_BOX : POST_BOX;
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
            footers.add(HsUi.fitLabel(currentFont, footer(which), PANEL_W - 24));
        }
        HsUi.FittedLabel reason = snapshot.scanReason()
            .map(component -> HsUi.fitLabel(currentFont, component, COST_BOX))
            .orElse(null);
        return new PlaqueRenderView(snapshot, snapshot.revision(), currentFont, language,
            HsUi.fitLabel(currentFont, title(), PANEL_W - 64),
            HsUi.fitLabel(currentFont, blessingStatusLine, PANEL_W - 42),
            List.copyOf(footers), List.copyOf(requirements),
            HsUi.fitLabel(currentFont,
                Component.translatable("hearthstead.plaque.state." + snapshot.state()),
                COST_BOX), reason, List.copyOf(occupants), List.copyOf(candidates),
            HsUi.fitLines(currentFont,
                Component.translatable("hearthstead.plaque.staff.instructions"),
                COST_BOX));
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
                                    HsUi.FittedLabel title,
                                    HsUi.FittedLabel blessing,
                                    List<HsUi.FittedLabel> footers,
                                    List<HsUi.FittedLabel> requirements,
                                    HsUi.FittedLabel noRoomState,
                                    HsUi.FittedLabel noRoomReason,
                                    List<OccupantRenderView> occupants,
                                    List<CandidateRenderView> candidates,
                                    List<FormattedCharSequence> staffInstructions) {
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

    /**
     * A thin inset sits inside the card with a 4px margin on every side --
     * matching the nine-slice border width itself, so the card's own edge
     * reads as a deliberate mat around a recessed inner panel rather than a
     * flat rectangle. Drawn under the row's text and buttons (both come
     * after this in render order), using only the existing inset sprite.
     */
    private void cardFrame(GuiGraphics graphics, int cardTop) {
        HsUi.inset(graphics, left + CARD_X + 4, cardTop + 4, CARD_W - 8, CARD_H - 8);
    }

    private boolean hovering(int mouseX, int mouseY, int cardTop) {
        return mouseX >= left + CARD_X && mouseX <= left + CARD_X + CARD_W
            && mouseY >= cardTop && mouseY <= cardTop + CARD_H;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** A tab is a button that looks like a tab and says where you are. */
    private static final class TabButton extends AbstractButton {
        private final boolean selected;
        private final Runnable onPress;
        private final HsUi.FittedLabelCache fittedLabel = new HsUi.FittedLabelCache();

        private TabButton(int x, int y, int w, int h, Component label,
                          boolean selected, Runnable onPress) {
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
            if (selected) {
                // A quiet engraved highlight, not a new sprite: one
                // low-alpha accent hairline just inside the top edge.
                graphics.fill(getX() + 3, getY() + 1, getX() + getWidth() - 3, getY() + 2,
                    0x40000000 | (HsUiTokens.ACCENT & 0x00FFFFFF));
            }
            var minecraft = net.minecraft.client.Minecraft.getInstance();
            var font = minecraft.font;
            HsUi.FittedLabel label = fittedLabel.fit(font, getMessage(), getWidth() - 8,
                minecraft.getLanguageManager().getSelected());
            HsUi.label(graphics, font, label.text(),
                getX() + 4, getY() + (getHeight() - HsUiTokens.TEXT_H) / 2,
                selected ? HsUiTokens.TEXT : HsUiTokens.TEXT_MUTED);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
