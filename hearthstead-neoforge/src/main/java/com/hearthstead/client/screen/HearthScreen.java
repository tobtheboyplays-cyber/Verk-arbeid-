package com.hearthstead.client.screen;

import com.hearthstead.Hearthstead;
import com.hearthstead.client.ui.HsButton;
import com.hearthstead.client.ui.HsUi;
import com.hearthstead.client.ui.HsUiTokens;
import com.hearthstead.menu.HearthMenu;
import com.hearthstead.network.HearthMayorAction;
import com.hearthstead.network.HearthMayorSnapshot;
import com.hearthstead.settlement.Mayor;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The settlement ledger: stats column on parchment, communal stores grid,
 * player inventory below. Texture is 256x256; screen is 220x222.
 *
 * <h2>The Mayor tab</h2>
 *
 * <p>Two small tabs stick out above the window's top-left corner, like a
 * creative-inventory tab: "Settlement" (the default view above) and
 * "Mayor". They live outside the parchment image on purpose -- the
 * communal storage grid and player inventory are real, always-interactive
 * slots owned by {@link HearthMenu}, and a tab that hid or covered them
 * without disabling them would be a control that lies about what clicking
 * it does. So the Mayor tab opens a second panel beside the window instead
 * of replacing anything inside it: nothing about the existing slots moves.
 *
 * <p>The panel is drawn entirely from a {@link HearthMayorSnapshot} the
 * server sends back for a {@link HearthMayorAction}, exactly
 * {@code PlaqueScreen}'s discipline: this screen holds no opinion about who
 * is eligible or what a candidate would bring, and every Appoint click sends
 * back the revision it was drawn from so a press made against a seat that
 * has since changed hands is refused rather than applied blind.
 */
public class HearthScreen extends AbstractContainerScreen<HearthMenu> {
    // ---------------------------------------------------------- geometry ---
    // Measured, not guessed (minecraft-ui skill, §2). Every number here is a
    // vanilla metric, a 4px-grid multiple, or a real width from
    // tools/mcfont.py against BOTH languages.
    //
    // The height budget is the binding constraint and it is not negotiable:
    // at guiScale 3 on a 1280x720 window the entire viewport is 240px tall.
    // The parchment screen was 222 and hung its tabs ABOVE the frame at
    // topPos - TAB_H, which at that scale is y = -10 -- the owner's own
    // screenshot has both tabs sliced off by the top of the window and the
    // "Communal Stores" label sitting across the title bar. The tabs are
    // inside the frame now, and everything below sums to PANEL_H = 234,
    // which clears 240 with six pixels to spare.
    private static final int PANEL_W = 256;
    private static final int PAD = HsUiTokens.PAD;
    private static final int CONTENT_X = PAD;
    private static final int CONTENT_W = PANEL_W - 2 * PAD;

    private static final int TITLE_Y = 6;
    // "Settlement" is 51px, "Ordfører" 47px; 72 clears both inside labelIn's
    // box (TAB_W - 8 = 64) with room left for a longer translation.
    private static final int TAB_W = 72;
    private static final int TAB_H = 15;
    private static final int TAB_Y = 17;
    private static final int TAB_GAP = 4;
    private static final int HEAD_DIV_Y = 35;

    // One band, two columns: the settlement's vitals on the left, the stores
    // grid on the right. The grid's origin is HearthMenu.COMMUNAL_X/Y -- the
    // MENU owns where a slot is; this screen only paints the socket under it,
    // so the two can never drift apart.
    private static final int BAND_Y = 42;
    private static final int BAND_H = 82;
    private static final int VITALS_X = CONTENT_X;
    private static final int VITALS_W = HearthMenu.COMMUNAL_X - CONTENT_X - 8;

    private static final int STAT_ROWS = 4;
    private static final int STAT_ROW_H = 16;
    private static final int STAT_ICON = 12;
    private static final int STAT_VALUE_X = STAT_ICON + 5;

    private static final int MORALE_Y = BAND_Y + STAT_ROWS * STAT_ROW_H + 2;
    private static final int MORALE_BAR_W = 64;
    private static final int MORALE_BAR_H = 8;

    // The status strip. Its longest sentence measures 368px in Norwegian
    // ("Neste settler trenger en taverna — vandrere har ingen steder å ta
    // inn"), which is exactly why the old screen's single unwrapped line ran
    // a hundred and fifty pixels past the frame and out over the world. No
    // panel width this mod is allowed (skill §2 caps at ~320) fits that on
    // one line, so two lines are reserved unconditionally and the text WRAPS.
    // Ellipsising it instead would read as a different, shorter sentence.
    private static final int STATUS_Y = BAND_Y + BAND_H + 4;
    private static final int STATUS_LINES = 2;

    /** Bottom of the hotbar row plus the frame's own bottom padding. */
    private static final int PANEL_H =
        HearthMenu.PLAYER_INV_Y + 58 + HsUiTokens.SLOT + 7;

    private static final ResourceLocation ICON_POPULATION = Hearthstead.id("icon/population");
    private static final ResourceLocation ICON_WORKFORCE = Hearthstead.id("icon/workforce");
    private static final ResourceLocation ICON_FOOD = Hearthstead.id("icon/food");
    private static final ResourceLocation ICON_RADIUS = Hearthstead.id("icon/radius");
    private static final ResourceLocation ICON_MORALE = Hearthstead.id("icon/morale");
    private static final ResourceLocation[] STAT_ICONS = {
        ICON_POPULATION, ICON_WORKFORCE, ICON_FOOD, ICON_RADIUS
    };

    // Error text on a dark ground is crimson[4], never the BAD token: BAD
    // measures 2.3:1 against the coal field and is fills-and-pips only. Both
    // blink phases clear the 3:1 floor the design language sets for a
    // blinking element (4.5:1 and 3.3:1, computed against FIELD #1A1A1A).
    private static final int CRIMSON_TEXT = 0xFFD9584A;
    private static final int BLINK_BRIGHT = CRIMSON_TEXT;
    private static final int BLINK_DIM = 0xFFB8483C;

    /** The morale bar's row index in the hover/tooltip mapping. */
    private static final int MORALE_ROW = STAT_ROWS;

    // -- the Mayor tab's popout panel, laid out exactly like PlaqueScreen's
    //    card list (same PAD/SCROLL_W/CARD proportions) plus a status block
    //    up top for the seat itself. --
    private static final int MAYOR_PANEL_W = 256;
    private static final int MAYOR_PAD = HsUiTokens.PAD;
    private static final int MAYOR_GAP = 6;
    private static final int MAYOR_TITLE_Y = 12;
    private static final int MAYOR_DIV1_Y = 26;
    private static final int MAYOR_STATUS_Y = 32;
    private static final int MAYOR_STATUS_H = 48;
    private static final int MAYOR_DIV2_Y = MAYOR_STATUS_Y + MAYOR_STATUS_H + 6;
    private static final int MAYOR_LABEL_Y = MAYOR_DIV2_Y + 8;
    private static final int MAYOR_LIST_TOP = MAYOR_LABEL_Y + 12;
    /** Most candidate rows the panel will ever show; fewer on a short viewport. */
    private static final int MAYOR_ROWS_MAX = 3;
    private static final int MAYOR_CARD_H = 38;
    private static final int MAYOR_CARD_STEP = MAYOR_CARD_H + 4;
    /** Everything in the panel that is not the candidate list. */
    private static final int MAYOR_CHROME_H = MAYOR_LIST_TOP + 6 + 8;
    // The footer sometimes wraps to two lines -- "Appointing someone new
    // stands Gislebert the Younger down" measured 299px against a 240px box
    // (59px over) in English, 249px (9px over) in Norwegian, both from the
    // rare "crowded settlement" long-name fallback. Two lines clears both
    // with room to spare; reserved unconditionally like the rest of this
    // panel's fixed shape.
    private static final int MAYOR_FOOTER_H = HsUiTokens.TEXT_H + 9;
    /** "Tilbake" is 34px, "Back" 23; 52 clears both inside a button. */
    private static final int MAYOR_BACK_W = 52;
    /** The footer row holds the sentence and the Back control side by side. */
    private static final int MAYOR_FOOTER_ROW_H =
        Math.max(MAYOR_FOOTER_H, HsUiTokens.BUTTON_H);


    private static final int MAYOR_CARD_X = MAYOR_PAD;
    private static final int MAYOR_CARD_W =
        MAYOR_PANEL_W - 2 * MAYOR_PAD - HsUiTokens.SCROLL_W - 2;
    private static final int MAYOR_BTN_W = 64;
    private static final int MAYOR_BTN_X = MAYOR_CARD_X + MAYOR_CARD_W - MAYOR_BTN_W - 8;
    private static final int MAYOR_TEXT_X = MAYOR_CARD_X + 10;
    // Measured against "Gislebert the Younger" (111px, the crowded-settlement
    // long-name fallback) -- 110 clipped it by 1px. 114 clears it and still
    // leaves a 2px gap before the pips column at MAYOR_BTN_X - 34.
    private static final int MAYOR_NAME_BOX = MAYOR_BTN_X - MAYOR_TEXT_X - 36;
    private static final int MAYOR_LINE_BOX = MAYOR_BTN_X - MAYOR_TEXT_X - 6;

    private boolean mayorTabOpen;
    private HearthMayorSnapshot mayorSnapshot;
    private int mayorScroll;
    private int mayorPanelLeft;
    private int mayorPanelTop;
    /** The seat panel's own buttons; drawn by the modal pass, not by super. */
    private final List<HsButton> mayorButtons = new ArrayList<>();
    /** True when the panel has to cover the window rather than sit beside it. */
    private boolean mayorModal;

    // The seat panel's height depends on the viewport, for the same reason the
    // hearth's own does: at guiScale 3 on a 720p window there are 240px in
    // total, and a three-row panel is 267 -- it drew its candidate list fine
    // and then ran its divider and footer straight off the bottom of the
    // screen (seen live, 2026-08-29, v4-mayor-modal.png). The list is the only
    // part that can give, so it is the part that does; the rows that no longer
    // fit are still reachable, because the list already scrolls.
    private int mayorRows = MAYOR_ROWS_MAX;
    private int mayorListH;
    private int mayorFoot;
    private int mayorPanelH;

    private void updateMayorMetrics() {
        int available = height - MAYOR_CHROME_H - MAYOR_FOOTER_ROW_H - 8 - 4;
        mayorRows = Mth.clamp(available / MAYOR_CARD_STEP, 1, MAYOR_ROWS_MAX);
        mayorListH = mayorRows * MAYOR_CARD_STEP - 4;
        mayorFoot = MAYOR_LIST_TOP + mayorListH + 6;
        mayorPanelH = mayorFoot + 8 + MAYOR_FOOTER_ROW_H + 8;
    }

    // ------------------------------------------------- per-frame discipline --
    // Everything below exists so that render() ALLOCATES NOTHING. That is not
    // premature tidiness; it was measured. With this screen open on a stocked
    // settlement the client was allocating 947 KB per frame -- about 57 MB a
    // second at 60fps, which is a young-generation collection every breath and
    // reads to a player as "the UI stutters". A Component is not free: every
    // Component.translatable(...) in a draw method is a fresh object, and
    // every "a + " / " + b" is three more, sixty times a second, to say a
    // number that changed once a minute.
    //
    // So the screen keeps the last values it drew and rebuilds a Component
    // only when the value behind it actually changes. The container data is
    // nine ints; packing them into one long makes "did anything change?" a
    // single comparison instead of nine.
    private long dataStamp = Long.MIN_VALUE;
    private final Component[] statValues = new Component[STAT_ROWS];
    private Component moraleBand = CommonComponents.EMPTY;
    private int moraleColour = HsUiTokens.TEXT;
    private float moraleRatio;
    private boolean alerting;
    /** Wrapped once per change: Font#split allocates a list and a line each call. */
    private List<FormattedCharSequence> statusLines = List.of();

    /** Static labels, built once rather than per frame. */
    private Component headerLine = CommonComponents.EMPTY;
    private Component storesLabel = CommonComponents.EMPTY;

    /** Which vitals row the mouse is over (0-3, 4 = morale), or -1. */
    private int hoveredStat = -1;
    private List<Component> hoveredTooltip;
    private int tooltipFor = -2;

    public HearthScreen(HearthMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        imageWidth = PANEL_W;
        imageHeight = PANEL_H;
        // Both vanilla labels are drawn by this screen instead, in places the
        // layout chose: the settlement name is centred in the title band, and
        // the row above the player inventory carries the settlement's status
        // line rather than the word "Inventory" -- which is a row of pixels
        // spent saying something the player can already see.
        titleLabelY = -1000;
        inventoryLabelY = -1000;
    }

    @Override
    protected void init() {
        super.init();
        String name = menu.getSettlementName();
        headerLine = name == null || name.isEmpty()
            ? Component.translatable("container.hearthstead.hearth")
            : Component.literal(name);
        storesLabel = Component.translatable("hearthstead.gui.stores");
        // A resize re-runs init(); force the next frame to rebuild the value
        // Components so a re-wrapped status line is never stale.
        dataStamp = Long.MIN_VALUE;
        tooltipFor = -2;
        updateMayorMetrics();
        rebuildSeatWidgets();
    }

    /**
     * Rebuilds the drawn Components, and only when the numbers behind them
     * moved. Called at the top of every frame; nine int reads and one long
     * compare is what a steady frame costs.
     */
    private void refreshIfChanged() {
        int pop = menu.get(HearthMenu.DATA_POPULATION);
        int cap = menu.get(HearthMenu.DATA_CAPACITY);
        int employed = menu.get(HearthMenu.DATA_EMPLOYED);
        int food = menu.get(HearthMenu.DATA_FOOD);
        int morale = Mth.clamp(menu.get(HearthMenu.DATA_MORALE), 0, 100);
        int radius = menu.get(HearthMenu.DATA_RADIUS);
        int alert = menu.get(HearthMenu.DATA_ALERT);
        int recruit = menu.get(HearthMenu.DATA_RECRUIT);
        int tavern = menu.get(HearthMenu.DATA_TAVERN);

        long stamp = (long) (pop & 0x3FF) | (long) (cap & 0x3FF) << 10
            | (long) (employed & 0x3FF) << 20 | (long) (food & 0xFFF) << 30
            | (long) (morale & 0x7F) << 42 | (long) (radius & 0x1FF) << 49
            | (long) (alert & 1) << 58 | (long) (recruit & 0x7F) << 59
            | (long) (tavern & 1) << 56;
        if (stamp == dataStamp) {
            return;
        }
        dataStamp = stamp;

        statValues[0] = Component.literal(pop + " / " + cap);
        statValues[1] = Component.literal(employed + " / " + pop);
        statValues[2] = Component.literal(Integer.toString(food));
        statValues[3] = Component.translatable("hearthstead.gui.radius_m", radius);

        moraleRatio = morale / 100.0F;
        moraleBand = moraleBand(morale);
        moraleColour = moraleColour(morale);
        alerting = alert == 1;

        statusLines = font.split(statusText(alert, tavern, recruit, pop, cap, food, morale),
            CONTENT_W);
        // The shape must not depend on the data (skill §11): a status that
        // wrapped to three lines would push the player inventory down into
        // the frame. Two lines are reserved, so two lines are what is drawn.
        if (statusLines.size() > STATUS_LINES) {
            statusLines = statusLines.subList(0, STATUS_LINES);
        }
        tooltipFor = -2;
    }

    /**
     * The one sentence under the vitals: what the settlement is doing about
     * growing, or that it is under threat. Priority order is unchanged from
     * the parchment screen -- threat, then the tavern gate, then progress,
     * then the first unmet condition -- because that order is the honest one
     * and PLAN_TAVERN_GATE krav 1 depends on the gate outranking progress.
     */
    private Component statusText(int alert, int tavern, int recruit,
                                 int pop, int cap, int food, int morale) {
        if (alert == 1) {
            return Component.translatable("hearthstead.gui.alert");
        }
        if (tavern == 0) {
            return Component.translatable("hearthstead.gui.recruit_blocked.tavern");
        }
        if (recruit > 0) {
            return Component.translatable("hearthstead.gui.recruit_progress");
        }
        if (pop >= cap) {
            return Component.translatable("hearthstead.gui.recruit_blocked.beds", pop, cap);
        }
        if (food < 8) {
            return Component.translatable("hearthstead.gui.recruit_blocked.food", food, 8);
        }
        if (morale < 60) {
            return Component.translatable("hearthstead.gui.recruit_blocked.morale", morale, 60);
        }
        return Component.translatable("hearthstead.gui.recruit_ready");
    }

    /**
     * A fresh Mayor snapshot arrived. Called from the client payload handler
     * whenever this screen is the one open -- see {@code ClientHooks}.
     */
    public void updateMayor(HearthMayorSnapshot fresh) {
        this.mayorSnapshot = fresh;
        rebuildSeatWidgets();
    }

    // ------------------------------------------------------------ widgets ---

    private void rebuildSeatWidgets() {
        clearWidgets();
        mayorButtons.clear();
        // Inside the frame, not above it. Hung above (the old topPos - TAB_H)
        // they left the screen entirely at guiScale 3, where topPos is 3.
        addRenderableWidget(new SeatTabButton(leftPos + CONTENT_X, topPos + TAB_Y, TAB_W, TAB_H,
            Component.translatable("hearthstead.gui.tab.settlement"), !mayorTabOpen, () -> {
                mayorTabOpen = false;
                rebuildSeatWidgets();
            }));
        addRenderableWidget(new SeatTabButton(leftPos + CONTENT_X + TAB_W + TAB_GAP,
            topPos + TAB_Y,
            TAB_W, TAB_H, Component.translatable("hearthstead.gui.tab.mayor"), mayorTabOpen, () -> {
                mayorTabOpen = true;
                requestMayorData();
                rebuildSeatWidgets();
            }));

        if (!mayorTabOpen) {
            return;
        }
        updateMayorPanelPosition();
        if (mayorSnapshot == null) {
            addMayorBackButton();
            return;
        }
        List<HearthMayorSnapshot.Candidate> candidates = mayorSnapshot.candidates();
        int rows = candidates.size();
        mayorScroll = Math.max(0, Math.min(mayorScroll, Math.max(0, rows - mayorRows)));
        boolean canAppoint = !mayorSnapshot.mourning();
        for (int row = 0; row < mayorRows && row + mayorScroll < rows; row++) {
            HearthMayorSnapshot.Candidate candidate = candidates.get(row + mayorScroll);
            int y = mayorPanelTop + MAYOR_LIST_TOP + row * MAYOR_CARD_STEP;
            HsButton appoint = HsButton.normal(mayorPanelLeft + MAYOR_BTN_X, y + 4, MAYOR_BTN_W,
                HsUiTokens.BUTTON_H, Component.translatable("hearthstead.mayor.appoint"),
                () -> appointAction(candidate.id()));
            appoint.active = canAppoint;
            // A disabled control always says why (D-014).
            appoint.setTooltip(Tooltip.create(canAppoint
                ? Component.translatable("hearthstead.mayor.appoint.tip", candidate.name())
                : Component.translatable("hearthstead.mayor.refused.mourning")));
            // addWidget, NOT addRenderableWidget: clickable and narrated, but
            // drawn by renderMayorPanel inside the modal's own raised pass.
            // A renderable is drawn by AbstractContainerScreen#render BEFORE
            // the slots and labels, which is under the panel these belong to.
            addWidget(appoint);
            mayorButtons.add(appoint);
        }
        addMayorBackButton();
    }

    /**
     * The modal's way out.
     *
     * <p>When the seat panel has to cover the window (guiScale 3 and below,
     * where 426px cannot hold a 256px window and a 256px panel side by side)
     * it also covers the tab strip that opened it, and Escape -- which closes
     * the whole screen -- became the only exit. A modal that can only be left
     * by leaving everything is a dead end, so it carries its own way back.
     */
    private void addMayorBackButton() {
        HsButton back = HsButton.normal(
            mayorPanelLeft + MAYOR_PANEL_W - MAYOR_PAD - MAYOR_BACK_W,
            mayorPanelTop + mayorPanelH - MAYOR_PAD - HsUiTokens.BUTTON_H,
            MAYOR_BACK_W, HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.gui.back"), () -> {
                mayorTabOpen = false;
                rebuildSeatWidgets();
            });
        addWidget(back);
        mayorButtons.add(back);
    }

    /**
     * Beside the window when the viewport is genuinely wide enough for both,
     * centred as a modal when it is not.
     *
     * <p>The old rule clamped the panel onto the window whenever it did not
     * fit beside it, which at guiScale 3 is always: a 426px viewport cannot
     * hold a 256px window and a 256px panel side by side. The result drew the
     * seat panel across the stores grid and the hearth's own labels, and ran
     * its footer off the bottom of the screen. A panel that overlaps the
     * window it belongs to IS a modal, so it is drawn as one -- centred, over
     * a full-screen scrim, above everything (see render()).
     */
    private void updateMayorPanelPosition() {
        updateMayorMetrics();
        int beside = leftPos + imageWidth + MAYOR_GAP;
        if (beside + MAYOR_PANEL_W <= width) {
            mayorPanelLeft = beside;
            mayorPanelTop = Mth.clamp(topPos - (mayorPanelH - imageHeight) / 2,
                0, Math.max(0, height - mayorPanelH));
            mayorModal = false;
            return;
        }
        int leftSide = leftPos - MAYOR_GAP - MAYOR_PANEL_W;
        if (leftSide >= 0) {
            mayorPanelLeft = leftSide;
            mayorPanelTop = Mth.clamp(topPos - (mayorPanelH - imageHeight) / 2,
                0, Math.max(0, height - mayorPanelH));
            mayorModal = false;
            return;
        }
        mayorPanelLeft = (width - MAYOR_PANEL_W) / 2;
        mayorPanelTop = Math.max(0, (height - mayorPanelH) / 2);
        mayorModal = true;
    }

    private void requestMayorData() {
        PacketDistributor.sendToServer(
            new HearthMayorAction(HearthMayorAction.Kind.REFRESH, new UUID(0, 0), 0));
    }

    private void appointAction(UUID id) {
        if (mayorSnapshot != null) {
            PacketDistributor.sendToServer(
                new HearthMayorAction(HearthMayorAction.Kind.APPOINT, id, mayorSnapshot.revision()));
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (mayorTabOpen && mayorSnapshot != null) {
            int rows = mayorSnapshot.candidates().size();
            if (rows > mayorRows && mouseX >= mayorPanelLeft
                && mouseX <= mayorPanelLeft + MAYOR_PANEL_W
                && mouseY >= mayorPanelTop && mouseY <= mayorPanelTop + mayorPanelH) {
                int before = mayorScroll;
                mayorScroll = Math.max(0, Math.min(rows - mayorRows, mayorScroll - (int) Math.signum(dy)));
                if (before != mayorScroll) {
                    rebuildSeatWidgets();
                    return true;
                }
            }
        }
        return super.mouseScrolled(mouseX, mouseY, dx, dy);
    }

    // ------------------------------------------------------------- drawing ---

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        HsUi.window(graphics, leftPos, topPos, PANEL_W, PANEL_H);
        // Sockets under the real slots. Drawn from the MENU's own origins so
        // a socket can never end up somewhere a slot is not; HsUi.SLOT is
        // 18x18 and is blitted at exactly that size, which is the one case
        // vanilla's nine-slice takes as a single quad.
        HsUi.inset(graphics, leftPos + HearthMenu.COMMUNAL_X - 1,
            topPos + HearthMenu.COMMUNAL_Y - 1, 6 * HsUiTokens.SLOT + 2,
            4 * HsUiTokens.SLOT + 2);
        // Three draw calls, not sixty: the grids are fixed by the menu, so
        // they are baked (HsUi.slotGrid) from the same socket art.
        HsUi.slotGrid(graphics, HsUi.SLOTS_6X4,
            leftPos + HearthMenu.COMMUNAL_X, topPos + HearthMenu.COMMUNAL_Y, 6, 4);
        HsUi.slotGrid(graphics, HsUi.SLOTS_9X3,
            leftPos + HearthMenu.PLAYER_INV_X, topPos + HearthMenu.PLAYER_INV_Y, 9, 3);
        HsUi.slotGrid(graphics, HsUi.SLOTS_9X1,
            leftPos + HearthMenu.PLAYER_INV_X, topPos + HearthMenu.PLAYER_INV_Y + 58, 9, 1);
        // The Mayor popout is NOT drawn here. renderBg runs first in the
        // frame, and AbstractContainerScreen draws slot items and then
        // renderLabels AFTER it -- so a panel painted here gets the
        // settlement's own labels painted straight across it. Seen live in
        // the owner's first session (video 0:24, "veldig dårlig UI"):
        // "The seat is empty" through the stores list, "Content" through
        // the candidate cards. The panel draws at the END of render() now,
        // above everything it overlaps.
    }

    private void renderMayorPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        int pl = mayorPanelLeft;
        int pt = mayorPanelTop;
        HsUi.window(graphics, pl, pt, MAYOR_PANEL_W, mayorPanelH);
        HsUi.centred(graphics, font, Component.translatable("hearthstead.mayor.tab.title"),
            pl + MAYOR_PANEL_W / 2, pt + MAYOR_TITLE_Y, HsUiTokens.TEXT_STRONG);
        HsUi.divider(graphics, pl + MAYOR_PAD, pt + MAYOR_DIV1_Y, MAYOR_PANEL_W - 2 * MAYOR_PAD);

        drawMayorStatus(graphics, pl, pt);

        HsUi.divider(graphics, pl + MAYOR_PAD, pt + MAYOR_DIV2_Y, MAYOR_PANEL_W - 2 * MAYOR_PAD);
        HsUi.labelIn(graphics, font, Component.translatable("hearthstead.mayor.candidates.title"),
            pl + MAYOR_PAD, pt + MAYOR_LABEL_Y, MAYOR_PANEL_W - 2 * MAYOR_PAD, HsUiTokens.TEXT_MUTED);

        if (mayorSnapshot == null) {
            HsUi.labelIn(graphics, font, Component.translatable("hearthstead.mayor.loading"),
                pl + MAYOR_PAD, pt + MAYOR_LIST_TOP, MAYOR_PANEL_W - 2 * MAYOR_PAD,
                HsUiTokens.TEXT_MUTED);
            return;
        }

        List<HearthMayorSnapshot.Candidate> candidates = mayorSnapshot.candidates();
        if (candidates.isEmpty()) {
            HsUi.labelIn(graphics, font, Component.translatable("hearthstead.mayor.candidates.empty"),
                pl + MAYOR_PAD, pt + MAYOR_LIST_TOP, MAYOR_PANEL_W - 2 * MAYOR_PAD,
                HsUiTokens.TEXT_MUTED);
        }
        for (int row = 0; row < mayorRows && row + mayorScroll < candidates.size(); row++) {
            HearthMayorSnapshot.Candidate candidate = candidates.get(row + mayorScroll);
            int y = pt + MAYOR_LIST_TOP + row * MAYOR_CARD_STEP;
            boolean hovered = mouseX >= pl + MAYOR_CARD_X && mouseX <= pl + MAYOR_CARD_X + MAYOR_CARD_W
                && mouseY >= y && mouseY <= y + MAYOR_CARD_H;
            HsUi.card(graphics, pl + MAYOR_CARD_X, y, MAYOR_CARD_W, MAYOR_CARD_H, hovered);
            HsUi.labelIn(graphics, font, Component.literal(candidate.name()),
                pl + MAYOR_TEXT_X, y + 6, MAYOR_NAME_BOX, HsUiTokens.TEXT_STRONG);
            HsUi.labelIn(graphics, font, Component.translatable("hearthstead.mayor.would_bring",
                    boonName(candidate.boonKey())),
                pl + MAYOR_TEXT_X, y + 20, MAYOR_LINE_BOX, HsUiTokens.TEXT_MUTED);
            HsUi.pips(graphics, pl + MAYOR_BTN_X - 34, y + 9,
                Math.min(5, candidate.knack() * 5 / 100), 5, HsUi.Tone.ACCENT);
        }

        int rows = candidates.size();
        HsUi.scrollbar(graphics, pl + MAYOR_PANEL_W - MAYOR_PAD - HsUiTokens.SCROLL_W,
            pt + MAYOR_LIST_TOP, mayorListH,
            rows == 0 ? 1.0F : Math.min(1.0F, (float) mayorRows / rows),
            rows <= mayorRows ? 0.0F : (float) mayorScroll / (rows - mayorRows), false);

        HsUi.divider(graphics, pl + MAYOR_PAD, pt + mayorFoot, MAYOR_PANEL_W - 2 * MAYOR_PAD);
        // Word-wrapped, not labelIn -- the "stands X down" sentence can carry
        // the current mayor's full (possibly long) name, and ellipsising a
        // name mid-sentence here reads as a different, shorter sentence
        // rather than a merely-truncated one. See MAYOR_FOOTER_H.
        graphics.drawWordWrap(font, mayorFooter(), pl + MAYOR_PAD, pt + mayorFoot + 7,
            MAYOR_PANEL_W - 2 * MAYOR_PAD - MAYOR_BACK_W - MAYOR_GAP, HsUiTokens.ACCENT);
    }

    /**
     * The seat itself: who holds it (name, boon, tenure or settling
     * countdown), or that it is vacant, or that the settlement is in
     * mourning and the reason Appoint is disabled below.
     */
    private void drawMayorStatus(GuiGraphics graphics, int pl, int pt) {
        int x = pl + MAYOR_PAD;
        int y = pt + MAYOR_STATUS_Y;
        int w = MAYOR_PANEL_W - 2 * MAYOR_PAD;
        HsUi.inset(graphics, x, y, w, MAYOR_STATUS_H);
        if (mayorSnapshot == null) {
            return;
        }
        long now = currentGameTime();
        if (mayorSnapshot.mourning()) {
            long remaining = Math.max(0, mayorSnapshot.mourningUntil() - now);
            HsUi.labelIn(graphics, font, Component.translatable("hearthstead.mayor.mourning.active",
                formatTicks(remaining)), x + 6, y + 8, w - 12, HsUiTokens.WARN);
            HsUi.labelIn(graphics, font, Component.translatable("hearthstead.mayor.refused.mourning"),
                x + 6, y + 22, w - 12, HsUiTokens.TEXT_MUTED);
            return;
        }
        if (!mayorSnapshot.hasMayor()) {
            HsUi.labelIn(graphics, font, Component.translatable("hearthstead.mayor.vacant"),
                x + 6, y + 8, w - 12, HsUiTokens.TEXT_STRONG);
            HsUi.labelIn(graphics, font, Component.translatable("hearthstead.mayor.vacant.hint"),
                x + 6, y + 22, w - 12, HsUiTokens.TEXT_MUTED);
            return;
        }

        HsUi.labelIn(graphics, font, Component.literal(mayorSnapshot.mayorName()),
            x + 6, y + 4, w - 12, HsUiTokens.TEXT_STRONG);
        long settlingRemaining = Math.max(0, (mayorSnapshot.mayorSince() + Mayor.SETTLING_TICKS) - now);
        boolean settling = settlingRemaining > 0;
        Component boonLine = settling
            ? Component.translatable("hearthstead.mayor.brings_pending", boonName(mayorSnapshot.boonKey()))
            : Component.translatable("hearthstead.mayor.brings_now", boonName(mayorSnapshot.boonKey()));
        HsUi.labelIn(graphics, font, boonLine, x + 6, y + 15, w - 12,
            settling ? HsUiTokens.TEXT_MUTED : HsUiTokens.ACCENT);
        HsUi.labelIn(graphics, font, boonDesc(mayorSnapshot.boonKey()), x + 6, y + 26, w - 12,
            HsUiTokens.TEXT_MUTED);
        Component tenureLine = settling
            ? Component.translatable("hearthstead.mayor.current.settling", formatTicks(settlingRemaining))
            : Component.translatable("hearthstead.mayor.current.tenure",
                formatTicks(Math.max(0, now - mayorSnapshot.mayorSince())));
        HsUi.labelIn(graphics, font, tenureLine, x + 6, y + 37, w - 12, HsUiTokens.TEXT_MUTED);
    }

    private Component mayorFooter() {
        if (mayorSnapshot == null) {
            return Component.translatable("hearthstead.mayor.loading");
        }
        if (mayorSnapshot.mourning()) {
            return Component.translatable("hearthstead.mayor.refused.mourning");
        }
        if (mayorSnapshot.candidates().isEmpty()) {
            return Component.translatable("hearthstead.mayor.candidates.empty");
        }
        return mayorSnapshot.hasMayor()
            ? Component.translatable("hearthstead.mayor.footer.swap", mayorSnapshot.mayorName())
            : Component.translatable("hearthstead.mayor.vacant.hint");
    }

    private static Component boonName(String key) {
        return Component.translatable("hearthstead.mayor.boon." + key);
    }

    private static Component boonDesc(String key) {
        return Component.translatable("hearthstead.mayor.boon." + key + ".desc");
    }

    /** "2d 4h", "4h", or "soon" -- ticks-to-days uses Minecraft's own 24000-tick day. */
    private static Component formatTicks(long ticks) {
        if (ticks <= 0) {
            return Component.translatable("hearthstead.mayor.time.soon");
        }
        long days = ticks / 24000L;
        long hours = (ticks % 24000L) / 1000L;
        if (days > 0) {
            return Component.translatable("hearthstead.mayor.time.days_hours", days, hours);
        }
        if (hours > 0) {
            return Component.translatable("hearthstead.mayor.time.hours", hours);
        }
        return Component.translatable("hearthstead.mayor.time.soon");
    }

    private long currentGameTime() {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        return level != null ? level.getGameTime() : 0L;
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // Inside renderLabels the pose is ALREADY translated by leftPos/topPos
        // (AbstractContainerScreen pushes it), so every coordinate here is
        // panel-local. Adding leftPos again is the classic way to draw a
        // screen twice as far right as intended.
        HsUi.divider(graphics, CONTENT_X, HEAD_DIV_Y, CONTENT_W);
        // Sprites first, then ALL the text in one managed batch.
        //
        // GuiGraphics#drawString ends in flushIfUnmanaged(), which -- outside
        // a managed block -- flushes the whole buffer source on EVERY call:
        // endBatch, sortQuads, and a fresh DirectFloatBuffer per string.
        // Profiling caught it directly (JFR: drawString -> flushIfUnmanaged
        // -> MeshData.sortQuads -> DirectByteBuffer.asFloatBuffer). A screen
        // that draws a dozen strings pays that a dozen times a frame for no
        // reason; drawManaged pays it once. Blits are unaffected either way,
        // since blitSprite goes to BufferUploader directly -- which is also
        // why they are issued BEFORE the batch, so the text lands on top.
        graphics.drawManaged(() -> {
            HsUi.centred(graphics, font, headerLine, PANEL_W / 2, TITLE_Y,
                HsUiTokens.TEXT_STRONG);
            HsUi.label(graphics, font, storesLabel, HearthMenu.COMMUNAL_X, BAND_Y,
                HsUiTokens.TEXT_MUTED);
            drawStatus(graphics);
        });
        drawVitals(graphics);
    }

    /**
     * The left column: four counted facts and the morale bar.
     *
     * <p>Every row is icon then value, and the icon carries the label in its
     * tooltip -- progressive disclosure, the same stat-row recipe the design
     * language already uses. The numbers are exact counts in {@code a / b}
     * form because that is the control an exact count deserves; morale is a
     * 0-100 continuous value, so it gets a bar with its band named beside it.
     */
    private void drawVitals(GuiGraphics graphics) {
        for (int row = 0; row < STAT_ROWS; row++) {
            graphics.blitSprite(STAT_ICONS[row], VITALS_X, BAND_Y + row * STAT_ROW_H,
                STAT_ICON, STAT_ICON);
        }
        graphics.blitSprite(ICON_MORALE, VITALS_X, MORALE_Y - 2, STAT_ICON, STAT_ICON);
        HsUi.bar(graphics, VITALS_X + STAT_VALUE_X, MORALE_Y, MORALE_BAR_W, MORALE_BAR_H,
            moraleRatio, HsUi.Tone.of(moraleRatio));
        // Same reasoning as renderLabels: every value string in one batch.
        graphics.drawManaged(() -> {
            for (int row = 0; row < STAT_ROWS; row++) {
                HsUi.labelIn(graphics, font, statValues[row], VITALS_X + STAT_VALUE_X,
                    BAND_Y + row * STAT_ROW_H + 2, VITALS_W - STAT_VALUE_X,
                    hoveredStat == row ? HsUiTokens.TEXT_STRONG : HsUiTokens.TEXT);
            }
            HsUi.labelIn(graphics, font, moraleBand,
                VITALS_X + STAT_VALUE_X, MORALE_Y + MORALE_BAR_H + 3,
                VITALS_W - STAT_VALUE_X, moraleColour);
        });
    }

    /**
     * The status strip, in the row a container screen normally spends on the
     * word "Inventory". Wrapped into height reserved unconditionally, so the
     * panel's shape never depends on which sentence is true.
     */
    private void drawStatus(GuiGraphics graphics) {
        // One blinking element, 400ms, and it is the thing that loses the
        // settlement -- the skill's budget is exactly one, and this is it.
        int colour = alerting
            ? ((System.currentTimeMillis() / 400) % 2 == 0
                ? BLINK_BRIGHT : BLINK_DIM)
            : HsUiTokens.TEXT_MUTED;
        for (int i = 0; i < statusLines.size(); i++) {
            graphics.drawString(font, statusLines.get(i), CONTENT_X,
                STATUS_Y + i * HsUiTokens.LINE_GAP, colour, true);
        }
    }

    /**
     * The band's own colour, on the DARK ground this screen now uses.
     *
     * <p>Not the parchment set: those were ink values chosen against
     * {@code #EFE0BD}. The token {@code BAD} is deliberately not used here
     * either -- it measures 2.3:1 as text on the coal field, which the design
     * language forbids outright; {@code crimson[4]} is the same meaning at
     * 4.5:1. Tone colour is only ever spent on the value itself, never on the
     * label beside it.
     */
    private static int moraleColour(int morale) {
        if (morale < 25) {
            return CRIMSON_TEXT;
        }
        if (morale < 50) {
            return HsUiTokens.WARN;
        }
        if (morale < 75) {
            return HsUiTokens.TEXT;
        }
        return HsUiTokens.GOOD;
    }

    private static Component moraleBand(int morale) {
        String key = morale < 25 ? "miserable" : morale < 50 ? "uneasy"
            : morale < 75 ? "content" : "joyful";
        return Component.translatable("hearthstead.morale." + key);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Once per frame, before anything is drawn: rebuild the Components
        // whose numbers moved, and none of the ones that did not.
        refreshIfChanged();
        hoveredStat = statRowAt(mouseX - leftPos, mouseY - topPos);

        super.render(graphics, mouseX, mouseY, partialTick);
        if (mayorTabOpen) {
            // Everything AbstractContainerScreen draws after its widgets --
            // the slot items and renderLabels -- has already been issued by
            // the super call above. Flushing here, then drawing the panel in
            // its own pushed pose translated forward in z, is what puts the
            // panel above them instead of underneath: without it the hearth's
            // own labels and item stacks painted straight through the seat
            // panel (seen live, 2026-08-29, v3-mayor-tab.png).
            graphics.flush();
            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, 0.0F, 300.0F);
            if (mayorModal) {
                // It covers the window, so it is a modal and says so: a
                // full-screen scrim, not a patch over the window alone, which
                // left the rest of the screen reading as still-live.
                graphics.fill(0, 0, width, height, 0xC0101010);
            }
            renderMayorPanel(graphics, mouseX, mouseY);
            for (HsButton button : mayorButtons) {
                button.render(graphics, mouseX, mouseY, partialTick);
            }
            graphics.flush();
            graphics.pose().popPose();
        }
        // No slot/stat tooltips from under the panel: the slot is covered,
        // so a tooltip for it would name something the player cannot see.
        // A modal covers everything, so nothing under it may claim the cursor.
        boolean overPanel = mayorTabOpen && (mayorModal
            || (mouseX >= mayorPanelLeft && mouseX < mayorPanelLeft + MAYOR_PANEL_W
                && mouseY >= mayorPanelTop && mouseY < mayorPanelTop + mayorPanelH));
        if (!overPanel) {
            renderTooltip(graphics, mouseX, mouseY);
            renderStatTooltip(graphics, mouseX, mouseY);
        }
    }

    /**
     * Which vitals row a panel-local point is on: 0-3 for the counted stats,
     * {@link #MORALE_ROW} for the morale bar, -1 for none.
     *
     * <p>Hit rows are the full width of the vitals column, not just the icon:
     * a 12px glyph is a 4px target at guiScale 1 and the design language
     * calls anything under 10px a miss.
     */
    private int statRowAt(int localX, int localY) {
        if (localX < VITALS_X || localX >= VITALS_X + VITALS_W) {
            return -1;
        }
        if (localY >= BAND_Y && localY < BAND_Y + STAT_ROWS * STAT_ROW_H) {
            return (localY - BAND_Y) / STAT_ROW_H;
        }
        if (localY >= MORALE_Y - 2 && localY < MORALE_Y + MORALE_BAR_H + 12) {
            return MORALE_ROW;
        }
        return -1;
    }

    /**
     * The label for a stat the icon alone cannot carry, plus what it means.
     *
     * <p>Built only when the hovered row CHANGES, not every frame the mouse
     * rests on it. Two Components and an ArrayList sixty times a second, to
     * say the same two lines, is exactly the kind of per-frame garbage this
     * screen was rewritten to stop producing.
     */
    private void renderStatTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (hoveredStat < 0) {
            return;
        }
        if (hoveredStat != tooltipFor) {
            tooltipFor = hoveredStat;
            String key = switch (hoveredStat) {
                case 0 -> "population";
                case 1 -> "employed";
                case 2 -> "food";
                case 3 -> "radius";
                default -> "morale";
            };
            hoveredTooltip = List.of(
                Component.translatable("hearthstead.gui.tooltip." + key),
                Component.translatable("hearthstead.gui.tooltip." + key + ".desc")
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
        }
        graphics.renderComponentTooltip(font, hoveredTooltip, mouseX, mouseY);
    }

    /** A folder tab sticking above the window's edge, like vanilla's creative tabs. */
    private static final class SeatTabButton extends AbstractButton {
        private final boolean selected;
        private final Runnable onPress;

        private SeatTabButton(int x, int y, int w, int h, Component label,
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
            var font = net.minecraft.client.Minecraft.getInstance().font;
            HsUi.labelIn(graphics, font, getMessage(),
                getX() + 4, getY() + (getHeight() - HsUiTokens.TEXT_H) / 2,
                getWidth() - 8,
                selected ? HsUiTokens.TEXT : HsUiTokens.TEXT_MUTED);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
