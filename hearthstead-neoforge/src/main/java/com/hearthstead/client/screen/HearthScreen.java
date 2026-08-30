package com.hearthstead.client.screen;

import com.hearthstead.client.QaClientObserver;
import com.hearthstead.client.QaUiInspectable;
import com.hearthstead.client.ui.HsButton;
import com.hearthstead.client.ui.HsUi;
import com.hearthstead.client.ui.HsUiTokens;
import com.hearthstead.menu.HearthMenu;
import com.hearthstead.network.HearthMayorAction;
import com.hearthstead.network.HearthMayorSnapshot;
import com.hearthstead.settlement.Mayor;
import com.hearthstead.settlement.RecruitmentPolicy;
import com.hearthstead.settlement.RecruitmentTransaction;
import com.hearthstead.settlement.journey.JourneyDefinition;
import com.hearthstead.settlement.journey.JourneyIds;
import com.hearthstead.settlement.journey.JourneyOutcome;
import com.hearthstead.settlement.journey.JourneyPresentationMode;
import com.hearthstead.settlement.journey.JourneyStep;
import com.hearthstead.settlement.request.RequestBlocker;
import com.hearthstead.settlement.request.RequestPriority;
import com.hearthstead.settlement.request.RequestState;
import com.hearthstead.settlement.request.RequestType;
import com.hearthstead.settlement.raid.FirstRaidReadinessService;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Hearthstead's settlement command center. The physical communal store and
 * player inventory remain real container slots; the surrounding surface is
 * the shared dark-oak/iron UI rather than the legacy parchment ledger.
 *
 * <h2>The Mayor and Founding Journey tabs</h2>
 *
 * <p>Four small tabs sit above the window: "Settlement", "Mayor", "Journey"
 * and the Hearth-owned "Development" map. They live outside the parchment
 * image on purpose -- the
 * communal storage grid and player inventory are real, always-interactive
 * slots owned by {@link HearthMenu}, and a tab that hid or covered them
 * without disabling them would be a control that lies about what clicking
 * it does. So Mayor and Journey open a second panel beside the window
 * instead of replacing anything inside it: nothing about the existing slots
 * moves.
 *
 * <p>The panel is drawn entirely from a {@link HearthMayorSnapshot} the
 * server sends back for a {@link HearthMayorAction}, exactly
 * {@code PlaqueScreen}'s discipline: this screen holds no opinion about who
 * is eligible or what a candidate would bring, and every Appoint click sends
 * back the revision it was drawn from so a press made against a seat that
 * has since changed hands is refused rather than applied blind.
 */
public class HearthScreen extends AbstractContainerScreen<HearthMenu>
        implements QaUiInspectable {
    private static final int INK = HsUiTokens.TEXT;
    private static final int INK_SOFT = HsUiTokens.TEXT_MUTED;

    // Stat rows (icon + value) in the left column.
    private static final int STAT_X = 14;
    private static final int STAT_Y = 34;
    private static final int STAT_ROW_H = 16;
    private static final int STAT_W = 84;

    // Morale bar geometry.
    private static final int BAR_X = 14;
    private static final int BAR_Y = 101;
    private static final int BAR_W = 80;
    private static final int BAR_H = 8;

    // One bounded settlement-status card between the communal grid and the
    // player inventory. Long translated blocker text wraps to two cached
    // lines instead of painting across slots.
    private static final int RECRUIT_X = 14;
    private static final int RECRUIT_Y = 110;
    private static final int RECRUIT_W = 192;
    private static final int RECRUIT_H = 21;

    // -- Five folder tabs centred above the Hearth window. --
    // Widths follow their English labels instead of forcing every tab through
    // one narrow box. Development therefore remains readable at normal GUI
    // scale without wasting the same width on Mayor.
    // Vanilla-font preflight, including the 4px inset on both sides:
    // Settlement 51px, Norwegian Ordfører 47px, Journey 42px and
    // Development 61px. The old 44px Mayor tab exposed only 36px and
    // guaranteed an ellipsis in nb_no.
    private static final int TAB_SETTLEMENT_W = 64;
    private static final int TAB_MAYOR_W = 44;
    private static final int TAB_JOURNEY_W = 52;
    private static final int TAB_REQUESTS_W = 56;
    private static final int TAB_DEVELOPMENT_W = 72;
    private static final int TAB_H = 20;
    private static final int TAB_GAP = 4;
    private static final int TAB_STRIP_W = TAB_SETTLEMENT_W + TAB_MAYOR_W
        + TAB_JOURNEY_W + TAB_REQUESTS_W + TAB_DEVELOPMENT_W + 4 * TAB_GAP;

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
    private static final int MAYOR_MAX_ROWS = 3;
    private static final int MAYOR_CARD_H = 38;
    private static final int MAYOR_CARD_STEP = MAYOR_CARD_H + 4;
    // The footer sometimes wraps to two lines -- "Appointing someone new
    // stands Gislebert the Younger down" measured 299px against a 240px box
    // (59px over) in English, 249px (9px over) in Norwegian, both from the
    // rare "crowded settlement" long-name fallback. Two lines clears both
    // with room to spare; reserved unconditionally like the rest of this
    // panel's fixed shape.
    private static final int MAYOR_FOOTER_H = HsUiTokens.TEXT_H + 9;
    private static final int MAYOR_PANEL_H = MAYOR_LIST_TOP
        + MAYOR_MAX_ROWS * MAYOR_CARD_STEP - 4 + 6
        + 8 + MAYOR_FOOTER_H + 8;

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
    private static final Component MAYOR_TITLE = Component.translatable(
        "hearthstead.mayor.tab.title");
    private static final Component MAYOR_CANDIDATES_TITLE = Component.translatable(
        "hearthstead.mayor.candidates.title");
    private static final Component MAYOR_LOADING = Component.translatable(
        "hearthstead.mayor.loading");
    private static final Component MAYOR_CANDIDATES_EMPTY = Component.translatable(
        "hearthstead.mayor.candidates.empty");

    // -- Founding Journey: one event-driven vertical path, no world scans. --
    private static final int JOURNEY_PANEL_W = 256;
    private static final int JOURNEY_PANEL_H = 285;
    private static final int JOURNEY_PAD = HsUiTokens.PAD;
    private static final int JOURNEY_GAP = 6;
    private static final int JOURNEY_TITLE_Y = 12;
    private static final int JOURNEY_DIV1_Y = 26;
    private static final int JOURNEY_INTRO_Y = 34;
    private static final int JOURNEY_STEPS_TOP = 66;
    private static final int JOURNEY_STEP_H = 58;
    private static final int JOURNEY_STEP_GAP = 8;
    private static final int JOURNEY_BUTTON_W = 106;
    private static final int AFTERMATH_STEP_ORDINAL = JourneyDefinition.CURRENT
        .step(JourneyIds.FJ_620_REVIEW_AFTERMATH).orElseThrow().ordinal();
    private static final int AFTERMATH_HEAD_Y = 34;
    private static final int AFTERMATH_HEAD_H = 38;
    private static final int AFTERMATH_FACTS_Y = 78;
    private static final int AFTERMATH_FACTS_H = 46;
    private static final int AFTERMATH_STATE_Y = 130;
    private static final int AFTERMATH_STATE_H = 46;

    // -- Explicit natural-recruit admission. This modal is intentionally
    // bounded to 224px so the whole card and its authoritative action fit in
    // the required 427x240 logical viewport at GUI scale 3. --
    private static final int RECRUIT_PANEL_W = 256;
    private static final int RECRUIT_PANEL_H = 224;
    private static final int RECRUIT_PANEL_PAD = HsUiTokens.PAD;
    private static final int RECRUIT_PANEL_GAP = 6;
    private static final int RECRUIT_REVIEW_W = 62;
    private static final int RECRUIT_ACTION_W = 104;
    private static final Component RECRUIT_CARD_TITLE = Component.translatable(
        "hearthstead.recruit.card.title");
    private static final Component RECRUIT_CARD_PRICE = Component.translatable(
        "hearthstead.recruit.card.price");

    // -- Read-only Request Ledger. Three four-line, cached cards keep every
    // required queue fact visible without rebuilding text in render(). The
    // full cached lines are also exposed on hover when a translation clips.
    // bounded at the 427x240 / GUI-scale-3 acceptance viewport. --
    private static final int REQUEST_PANEL_W = 300;
    private static final int REQUEST_PANEL_H = 238;
    private static final int REQUEST_PANEL_PAD = HsUiTokens.PAD;
    private static final int REQUEST_PANEL_GAP = 6;
    private static final int REQUEST_TITLE_Y = 12;
    private static final int REQUEST_DIV1_Y = 26;
    private static final int REQUEST_META_Y = 34;
    private static final int REQUEST_DIV2_Y = 52;
    private static final int REQUEST_LIST_TOP = 59;
    private static final int REQUEST_MAX_ROWS = 3;
    private static final int REQUEST_CARD_H = 42;
    private static final int REQUEST_CARD_STEP = 46;
    private static final int REQUEST_FOOT_DIV_Y = 211;
    private static final int REQUEST_FOOT_Y = 219;
    private static final int REQUEST_REFRESH_W = 62;
    private static final int REQUEST_LOAD_TIMEOUT_TICKS = 100;
    private static final Component REQUEST_TITLE = Component.translatable(
        "hearthstead.request.ledger.title");
    private static final Component REQUEST_LOADING = Component.translatable(
        "hearthstead.request.ledger.loading");
    private static final Component REQUEST_EMPTY = Component.translatable(
        "hearthstead.request.ledger.empty");
    private static final Component REQUEST_UNAVAILABLE = Component.translatable(
        "hearthstead.request.ledger.unavailable");
    private static final Component REQUEST_RETRY = Component.translatable(
        "hearthstead.request.ledger.retry");

    // -- First-raid declaration reuses the Journey panel. All expensive
    // translation/list work is cached when the server snapshot arrives. --
    private static final int READINESS_META_Y = 34;
    private static final int READINESS_DIV2_Y = 52;
    private static final int READINESS_LIST_TOP = 59;
    private static final int READINESS_MAX_ROWS = 5;
    private static final int READINESS_CARD_H = 30;
    private static final int READINESS_CARD_STEP = 34;
    private static final Component READINESS_TITLE = Component.translatable(
        "hearthstead.raid.readiness.title");
    private static final Component READINESS_LOADING = Component.translatable(
        "hearthstead.raid.readiness.loading");
    private static final Component AFTERMATH_TITLE = Component.translatable(
        "hearthstead.raid.aftermath.title");
    private static final Component JOURNEY_TITLE = Component.translatable(
        "hearthstead.journey.title");
    private static final Component STORES_LABEL = Component.translatable(
        "hearthstead.gui.stores");
    private static final Component MORALE_LABEL = Component.translatable(
        "hearthstead.gui.morale");
    private static final Component ALERT_LABEL = Component.translatable(
        "hearthstead.gui.alert");
    private static final Component COMMAND_CENTER_LABEL = Component.translatable(
        "hearthstead.gui.command_center");
    private static final Component SETTLEMENT_PULSE_LABEL = Component.translatable(
        "hearthstead.gui.settlement_pulse");
    private static final Component POPULATION_LABEL = Component.translatable(
        "hearthstead.gui.tooltip.population");
    private static final Component EMPLOYED_LABEL = Component.translatable(
        "hearthstead.gui.tooltip.employed");
    private static final Component FOOD_LABEL = Component.translatable(
        "hearthstead.gui.tooltip.food");
    private static final Component RADIUS_LABEL = Component.translatable(
        "hearthstead.gui.tooltip.radius");

    private boolean mayorTabOpen;
    private boolean journeyTabOpen;
    private boolean recruitmentPanelOpen;
    private boolean requestPanelOpen;
    private boolean requestLoading;
    private boolean readinessLoading;
    private boolean readinessCommitPending;
    private boolean recruitmentAdmissionPending;
    private boolean journeySkipConfirm;
    private boolean journeySkipPending;
    private int journeySentRevision = -1;
    private int observedJourneyPhase = Integer.MIN_VALUE;
    private int observedJourneyRevision = Integer.MIN_VALUE;
    private int observedJourneyCanSkip = Integer.MIN_VALUE;
    private HearthMayorSnapshot mayorSnapshot;
    private int mayorScroll;
    private final List<SeatTabButton> seatTabs = new ArrayList<>();
    /** Controls registered for input/narration and drawn once after the modal. */
    private final List<AbstractButton> latePanelWidgets = new ArrayList<>();
    private boolean uiOpenSoundPlayed;
    private boolean uiCloseSoundPlayed;
    private int mayorPanelLeft;
    private int mayorPanelTop;
    private int mayorPanelHeight = MAYOR_PANEL_H;
    private int mayorVisibleRows = MAYOR_MAX_ROWS;
    private int mayorListHeight = MAYOR_MAX_ROWS * MAYOR_CARD_STEP - 4;
    private int mayorFoot = MAYOR_LIST_TOP + mayorListHeight + 6;
    private int journeyPanelLeft;
    private int journeyPanelTop;
    private int journeyPanelHeight = JOURNEY_PANEL_H;
    private int journeyStepHeight = JOURNEY_STEP_H;
    private int journeyStepGap = JOURNEY_STEP_GAP;
    private int journeyFootDividerY = 197;
    private int journeyFootY = 205;
    private int journeyButtonY = 222;
    private int recruitmentPanelLeft;
    private int recruitmentPanelTop;
    private int requestPanelLeft;
    private int requestPanelTop;
    private int requestScroll;
    private int requestLoadingTicks;
    private boolean requestUnavailable;
    private int observedRecruitmentRevision = Integer.MIN_VALUE;
    private int observedRecruitmentStatus = Integer.MIN_VALUE;
    private long cachedRecruitmentSecond = Long.MIN_VALUE;
    private HearthMayorSnapshot.RecruitmentCard cachedRecruitmentCard =
        HearthMayorSnapshot.RecruitmentCard.empty();
    private Component recruitmentNameLine = Component.empty();
    private Component recruitmentStageLine = Component.empty();
    private Component recruitmentBedsLine = Component.empty();
    private Component recruitmentFoodLine = Component.empty();
    private Component recruitmentTimeLine = Component.empty();
    private Component recruitmentBlockerComponent = Component.empty();
    private List<Component> recruitmentCostLines = List.of();
    private List<FormattedCharSequence> recruitmentBlockerLines = List.of();
    private HearthMayorSnapshot.RequestView cachedRequestView =
        HearthMayorSnapshot.RequestView.closed();
    private List<RequestRenderRow> cachedRequestRows = List.of();
    private Component requestMetaLine = Component.empty();
    private Component requestFooterLine = Component.empty();
    private HearthMayorSnapshot.ReadinessView cachedReadinessView =
        HearthMayorSnapshot.ReadinessView.closed();
    private List<Component> cachedReadinessBlockers = List.of();
    private Component readinessMetaLine = Component.empty();
    private Component readinessMetricsLine = Component.empty();
    private int readinessScroll;
    private HearthMayorSnapshot.AftermathView cachedAftermathView =
        HearthMayorSnapshot.AftermathView.closed();
    private Component aftermathStatusLine = Component.empty();
    private Component aftermathNightLine = Component.empty();
    private Component aftermathCaptainLine = Component.empty();
    private Component aftermathObjectiveLine = Component.empty();
    private Component aftermathImpactLine = Component.empty();
    private Component aftermathThreatLine = Component.empty();
    private Component aftermathRewardLine = Component.empty();
    private Component aftermathRoadLine = Component.empty();
    private String cachedSettlementName = "\u0000";
    private String cachedSettlementLanguage = "";
    private Component cachedSettlementHeader = Component.empty();
    private int cachedSettlementHeaderWidth;
    private RecruitmentPolicy.Blocker cachedRecruitBlocker;
    private RecruitmentPolicy.Stage cachedRecruitStage;
    private int cachedRecruitPopulation = Integer.MIN_VALUE;
    private int cachedRecruitCapacity = Integer.MIN_VALUE;
    private int cachedRecruitMorale = Integer.MIN_VALUE;
    private int cachedRecruitProgress = Integer.MIN_VALUE;
    private int cachedRecruitReadyFood = Integer.MIN_VALUE;
    private int cachedRecruitRequiredFood = Integer.MIN_VALUE;
    private boolean cachedRecruitCandidatePresent;
    private FormattedCharSequence recruitLine1 = FormattedCharSequence.EMPTY;
    private FormattedCharSequence recruitLine2 = FormattedCharSequence.EMPTY;
    private HearthMayorSnapshot mayorRenderSnapshot;
    private String mayorRenderLanguage = "";
    private int mayorRenderPanelHeight = -1;
    private int mayorRenderVisibleRows = -1;
    private MayorRenderModel mayorRenderModel = MayorRenderModel.empty();
    private HearthMayorSnapshot mayorStatusSnapshot;
    private String mayorStatusLanguage = "";
    private long mayorStatusSecond = Long.MIN_VALUE;
    private MayorStatusRenderModel mayorStatusRenderModel =
        MayorStatusRenderModel.empty();
    private HearthMayorSnapshot.ReadinessView readinessRenderSource;
    private String readinessRenderLanguage = "";
    private int readinessRenderPanelHeight = -1;
    private boolean readinessRenderLoading;
    private boolean readinessRenderPending;
    private ReadinessRenderModel readinessRenderModel =
        ReadinessRenderModel.empty();
    private HearthMayorSnapshot.AftermathView aftermathRenderSource;
    private String aftermathRenderLanguage = "";
    private int aftermathRenderPanelHeight = -1;
    private AftermathRenderModel aftermathRenderModel =
        AftermathRenderModel.empty();
    private String journeyRenderLanguage = "";
    private int journeyRenderMode = Integer.MIN_VALUE;
    private int journeyRenderCompleted = Integer.MIN_VALUE;
    private int journeyRenderChapter = Integer.MIN_VALUE;
    private int journeyRenderCurrent = Integer.MIN_VALUE;
    private int journeyRenderOutcome = Integer.MIN_VALUE;
    private int journeyRenderPanelHeight = -1;
    private int journeyRenderStepHeight = -1;
    private boolean journeyRenderReadinessLoading;
    private boolean journeyRenderSkipPending;
    private boolean journeyRenderSkipConfirm;
    private JourneyRenderModel journeyRenderModel = JourneyRenderModel.empty();
    private int cachedStatsPopulation = Integer.MIN_VALUE;
    private int cachedStatsCapacity = Integer.MIN_VALUE;
    private int cachedStatsEmployed = Integer.MIN_VALUE;
    private int cachedStatsFood = Integer.MIN_VALUE;
    private int cachedStatsRadius = Integer.MIN_VALUE;
    private int cachedStatsMoraleBand = Integer.MIN_VALUE;
    private String cachedStatsLanguage = "";
    private String cachedPopulationStat = "";
    private String cachedEmploymentStat = "";
    private String cachedFoodStat = "";
    private String cachedRadiusStat = "";
    private HsUi.FittedLabel cachedMoraleBand = fittedEmpty();

    public HearthScreen(HearthMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        imageWidth = 512;
        imageHeight = 274;
        inventoryLabelX = HearthMenu.PLAYER_INV_X + 1;
        inventoryLabelY = HearthMenu.PLAYER_INV_Y - 9;
        titleLabelY = -1000; // we draw our own header
    }

    @Override
    protected void init() {
        // 512x274 is the desktop Command Center contract. The same hierarchy
        // contracts to the available logical viewport instead of forcing the
        // old 220px ledger or painting popouts beyond the screen.
        imageWidth = Math.min(512, Math.max(320, width - 16));
        imageHeight = Math.min(274,
            Math.max(218, height - TAB_H - 2));
        super.init();
        // The 222px ledger technically fits a 240px logical viewport, but
        // its 14px folder tabs did not: vanilla centred the ledger at y=9,
        // leaving the tabs at y=-5. Keep two real pixels above the tabs and
        // two below the ledger at the 1280x720 / GUI-scale-3 target.
        topPos = hearthTopFor(height, imageHeight);
        applyResponsivePopoutLayout();
        cachedSettlementName = "\u0000";
        cachedRecruitBlocker = null;
        rebuildSeatWidgets();
        // Recruitment is a Hearth-wide surface, not a Mayor-tab inference.
        // Ask the server for the bounded candidate card as soon as this exact
        // container opens; its identity is revalidated server-side.
        requestMayorData();
        if (!uiOpenSoundPlayed) {
            uiOpenSoundPlayed = true;
            HsUi.playOpenSound();
        }
    }

    /** Pure geometry used by the unit preflight and the live screen. */
    static int hearthTopFor(int viewportHeight, int ledgerHeight) {
        int centred = (viewportHeight - ledgerHeight) / 2;
        int minimum = TAB_H;
        int maximum = viewportHeight - ledgerHeight - 2;
        return maximum >= minimum
            ? Mth.clamp(centred, minimum, maximum)
            : Math.max(0, centred);
    }

    static boolean hearthTabsFitFor(int viewportWidth, int ledgerWidth) {
        int ledgerLeft = (viewportWidth - ledgerWidth) / 2;
        int tabLeft = ledgerLeft + (ledgerWidth - TAB_STRIP_W) / 2;
        return tabLeft >= 0 && tabLeft + TAB_STRIP_W <= viewportWidth;
    }

    /**
     * Mayor keeps the complete status and footer at every target profile;
     * only the already-scrollable candidate window loses a row at 240px.
     */
    static MayorLayout mayorLayoutFor(int viewportHeight) {
        int available = Math.max(1, viewportHeight - 2);
        int fixed = MAYOR_LIST_TOP - 4 + 6 + 8 + MAYOR_FOOTER_H + 8;
        int rows = Mth.clamp((available - fixed) / MAYOR_CARD_STEP,
            1, MAYOR_MAX_ROWS);
        int listHeight = rows * MAYOR_CARD_STEP - 4;
        int foot = MAYOR_LIST_TOP + listHeight + 6;
        int panelHeight = foot + 8 + MAYOR_FOOTER_H + 8;
        return new MayorLayout(panelHeight, rows, listHeight, foot);
    }

    /** The bounded Journey projection always renders current plus next only. */
    static JourneyLayout journeyLayoutFor(int viewportHeight) {
        int panelHeight = Math.min(JOURNEY_PANEL_H,
            Math.max(1, viewportHeight - 2));
        boolean compact = panelHeight < JOURNEY_PANEL_H;
        int stepHeight = compact ? 45 : JOURNEY_STEP_H;
        int stepGap = compact ? 4 : JOURNEY_STEP_GAP;
        return new JourneyLayout(panelHeight, stepHeight, stepGap,
            panelHeight - 53, panelHeight - 45, panelHeight - 28);
    }

    private void applyResponsivePopoutLayout() {
        MayorLayout mayor = mayorLayoutFor(height);
        mayorPanelHeight = mayor.panelHeight();
        mayorVisibleRows = mayor.visibleRows();
        mayorListHeight = mayor.listHeight();
        mayorFoot = mayor.foot();

        JourneyLayout journey = journeyLayoutFor(height);
        journeyPanelHeight = journey.panelHeight();
        journeyStepHeight = journey.stepHeight();
        journeyStepGap = journey.stepGap();
        journeyFootDividerY = journey.footDividerY();
        journeyFootY = journey.footY();
        journeyButtonY = journey.buttonY();
    }

    record MayorLayout(int panelHeight, int visibleRows,
                       int listHeight, int foot) {
    }

    record JourneyLayout(int panelHeight, int stepHeight, int stepGap,
                          int footDividerY, int footY, int buttonY) {
    }

    private record RequestRenderRow(Component headline, Component route,
                                    Component assignment, Component stop,
                                    List<Component> tooltip, int tone) {
        private RequestRenderRow {
            tooltip = List.copyOf(tooltip);
        }
    }

    private record MayorRenderRow(HsUi.FittedLabel name,
                                  HsUi.FittedLabel boon) {
    }

    private record MayorRenderModel(int titleWidth,
                                    HsUi.FittedLabel candidatesTitle,
                                    HsUi.FittedLabel loading,
                                    HsUi.FittedLabel emptyCandidates,
                                    List<MayorRenderRow> candidates,
                                    List<FormattedCharSequence> footer) {
        private MayorRenderModel {
            candidates = List.copyOf(candidates);
            footer = List.copyOf(footer);
        }

        private static MayorRenderModel empty() {
            HsUi.FittedLabel empty = fittedEmpty();
            return new MayorRenderModel(0, empty, empty, empty,
                List.of(), List.of());
        }
    }

    private record MayorStatusRenderModel(int kind,
                                          HsUi.FittedLabel first,
                                          HsUi.FittedLabel second,
                                          HsUi.FittedLabel third,
                                          HsUi.FittedLabel fourth,
                                          int secondTone) {
        private static MayorStatusRenderModel empty() {
            HsUi.FittedLabel empty = fittedEmpty();
            return new MayorStatusRenderModel(0, empty, empty, empty,
                empty, HsUiTokens.TEXT_MUTED);
        }
    }

    private record ReadinessRenderModel(int titleWidth,
                                        HsUi.FittedLabel meta,
                                        HsUi.FittedLabel metrics,
                                        List<List<FormattedCharSequence>> blockers,
                                        List<FormattedCharSequence> clear,
                                        HsUi.FittedLabel footer) {
        private ReadinessRenderModel {
            blockers = blockers.stream().map(List::copyOf).toList();
            clear = List.copyOf(clear);
        }

        private static ReadinessRenderModel empty() {
            HsUi.FittedLabel empty = fittedEmpty();
            return new ReadinessRenderModel(0, empty, empty, List.of(),
                List.of(), empty);
        }
    }

    private record AftermathRenderModel(int titleWidth,
                                        HsUi.FittedLabel status,
                                        HsUi.FittedLabel night,
                                        HsUi.FittedLabel captain,
                                        HsUi.FittedLabel objective,
                                        List<FormattedCharSequence> impact,
                                        HsUi.FittedLabel threat,
                                        List<FormattedCharSequence> reward,
                                        List<FormattedCharSequence> road) {
        private AftermathRenderModel {
            impact = List.copyOf(impact);
            reward = List.copyOf(reward);
            road = List.copyOf(road);
        }

        private static AftermathRenderModel empty() {
            HsUi.FittedLabel empty = fittedEmpty();
            return new AftermathRenderModel(0, empty, empty, empty, empty,
                List.of(), empty, List.of(), List.of());
        }
    }

    private record JourneyRenderRow(HsUi.FittedLabel title,
                                    List<FormattedCharSequence> description,
                                    HsUi.FittedLabel state) {
        private JourneyRenderRow {
            description = List.copyOf(description);
        }
    }

    private record JourneyRenderModel(int titleWidth,
                                      HsUi.FittedLabel chapter,
                                      HsUi.FittedLabel progress,
                                      List<JourneyRenderRow> steps,
                                      HsUi.FittedLabel footer,
                                      JourneyPresentationMode mode) {
        private JourneyRenderModel {
            steps = List.copyOf(steps);
        }

        private static JourneyRenderModel empty() {
            HsUi.FittedLabel empty = fittedEmpty();
            return new JourneyRenderModel(0, empty, empty, List.of(), empty,
                JourneyPresentationMode.QUARANTINED);
        }
    }

    private static HsUi.FittedLabel fittedEmpty() {
        return new HsUi.FittedLabel(Component.empty(), 0);
    }

    @Override
    public void removed() {
        if (uiOpenSoundPlayed && !uiCloseSoundPlayed) {
            uiCloseSoundPlayed = true;
            HsUi.playCloseSound();
        }
        super.removed();
    }

    /**
     * A fresh Mayor snapshot arrived. Called from the client payload handler
     * whenever this screen is the one open -- see {@code ClientHooks}.
     */
    public void updateMayor(HearthMayorSnapshot fresh) {
        this.mayorSnapshot = fresh;
        recruitmentAdmissionPending = false;
        updateRecruitmentCardCache(fresh == null
            ? HearthMayorSnapshot.RecruitmentCard.empty()
            : fresh.recruitment(), true);
        if (recruitmentPanelOpen && !cachedRecruitmentCard.present()) {
            recruitmentPanelOpen = false;
        }
        if (fresh != null && fresh.requests().matches(menu.getSettlementId(),
                menu.getContainerId())
            && updateRequestViewCache(fresh.requests())) {
            requestLoading = false;
            requestLoadingTicks = 0;
            requestUnavailable = false;
        }
        if (fresh != null && fresh.readiness().open()
            && fresh.readiness().acceptsAfter(cachedReadinessView)) {
            updateReadinessViewCache(fresh.readiness());
            readinessLoading = false;
            readinessCommitPending = false;
        }
        updateAftermathViewCache(fresh == null
            ? HearthMayorSnapshot.AftermathView.closed()
            : fresh.aftermath());
        rebuildSeatWidgets();
    }

    // ------------------------------------------------------------ widgets ---

    private void rebuildSeatWidgets() {
        clearWidgets();
        seatTabs.clear();
        latePanelWidgets.clear();
        int tabLeft = leftPos + (imageWidth - TAB_STRIP_W) / 2;
        int tabX = tabLeft;
        addSeatTab(new SeatTabButton(tabX, topPos - TAB_H,
            TAB_SETTLEMENT_W, TAB_H,
            Component.translatable("hearthstead.gui.tab.settlement"),
            !mayorTabOpen && !journeyTabOpen && !recruitmentPanelOpen
                && !requestPanelOpen, () -> {
                QaClientObserver.markUiTransition("hearth_settlement_tab");
                mayorTabOpen = false;
                journeyTabOpen = false;
                recruitmentPanelOpen = false;
                requestPanelOpen = false;
                journeySkipConfirm = false;
                rebuildSeatWidgets();
            }));
        tabX += TAB_SETTLEMENT_W + TAB_GAP;
        addSeatTab(new SeatTabButton(tabX, topPos - TAB_H,
            TAB_MAYOR_W, TAB_H,
            Component.translatable("hearthstead.gui.tab.mayor"), mayorTabOpen, () -> {
                QaClientObserver.markUiTransition("hearth_mayor_popout");
                mayorTabOpen = true;
                journeyTabOpen = false;
                recruitmentPanelOpen = false;
                requestPanelOpen = false;
                journeySkipConfirm = false;
                requestMayorData();
                rebuildSeatWidgets();
            }));
        tabX += TAB_MAYOR_W + TAB_GAP;
        addSeatTab(new SeatTabButton(
            tabX, topPos - TAB_H, TAB_JOURNEY_W, TAB_H,
            Component.translatable("hearthstead.gui.tab.journey"),
            journeyTabOpen, () -> {
                QaClientObserver.markUiTransition("hearth_journey_popout");
                mayorTabOpen = false;
                journeyTabOpen = true;
                recruitmentPanelOpen = false;
                requestPanelOpen = false;
                journeySkipConfirm = false;
                resetReadinessView();
                PacketDistributor.sendToServer(mayorAction(
                    HearthMayorAction.Kind.OPEN_JOURNEY,
                    HearthMayorAction.NO_ID, 0));
                rebuildSeatWidgets();
            }));
        tabX += TAB_JOURNEY_W + TAB_GAP;
        SeatTabButton requests = new SeatTabButton(
            tabX, topPos - TAB_H, TAB_REQUESTS_W, TAB_H,
            Component.translatable("hearthstead.gui.tab.requests"),
            requestPanelOpen, this::openRequestLedger);
        requests.setTooltip(Tooltip.create(Component.translatable(
            "hearthstead.request.ledger.open.tip")));
        addSeatTab(requests);
        tabX += TAB_REQUESTS_W + TAB_GAP;
        SeatTabButton development = new SeatTabButton(
            tabX, topPos - TAB_H, TAB_DEVELOPMENT_W, TAB_H,
            Component.translatable("hearthstead.gui.tab.development"),
            false, this::requestDevelopmentData);
        development.setTooltip(Tooltip.create(Component.translatable(
            "hearthstead.development.open.tip")));
        addSeatTab(development);

        if (cachedRecruitmentCard.present() && !recruitmentPanelOpen) {
            HsButton review = HsButton.normal(
                leftPos + RECRUIT_X + RECRUIT_W - RECRUIT_REVIEW_W - 3,
                topPos + RECRUIT_Y + 2, RECRUIT_REVIEW_W,
                HsUiTokens.BUTTON_H,
                Component.translatable("hearthstead.recruit.card.review"), () -> {
                    QaClientObserver.markUiTransition("hearth_recruitment_popout");
                    mayorTabOpen = false;
                    journeyTabOpen = false;
                    recruitmentPanelOpen = true;
                    requestPanelOpen = false;
                    journeySkipConfirm = false;
                    rebuildSeatWidgets();
                });
            review.setTooltip(Tooltip.create(Component.translatable(
                "hearthstead.recruit.card.review.tip",
                cachedRecruitmentCard.name())));
            addRenderableWidget(review);
        }

        if (recruitmentPanelOpen) {
            rebuildRecruitmentWidgets();
            return;
        }

        if (requestPanelOpen) {
            rebuildRequestWidgets();
            return;
        }

        if (journeyTabOpen) {
            rebuildJourneyWidgets();
            return;
        }

        if (!mayorTabOpen) {
            return;
        }
        updateMayorPanelPosition();
        suppressCoveredTabs(mayorPanelLeft, mayorPanelTop,
            MAYOR_PANEL_W, mayorPanelHeight);
        addPanelClose(mayorPanelLeft, mayorPanelTop, MAYOR_PANEL_W);
        if (mayorSnapshot == null) {
            return;
        }
        List<HearthMayorSnapshot.Candidate> candidates = mayorSnapshot.candidates();
        int rows = candidates.size();
        mayorScroll = Math.max(0, Math.min(mayorScroll,
            Math.max(0, rows - mayorVisibleRows)));
        boolean canAppoint = !mayorSnapshot.mourning();
        for (int row = 0; row < mayorVisibleRows
            && row + mayorScroll < rows; row++) {
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
            addPanelWidget(appoint);
        }
    }

    private void rebuildRecruitmentWidgets() {
        updateRecruitmentPanelPosition();
        suppressCoveredTabs(recruitmentPanelLeft, recruitmentPanelTop,
            RECRUIT_PANEL_W, RECRUIT_PANEL_H);
        addPanelClose(recruitmentPanelLeft, recruitmentPanelTop,
            RECRUIT_PANEL_W);
        if (!cachedRecruitmentCard.present()) {
            return;
        }
        HsButton admit = HsButton.normal(
            recruitmentPanelLeft + RECRUIT_PANEL_PAD,
            recruitmentPanelTop + RECRUIT_PANEL_H - 30,
            RECRUIT_ACTION_W, HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.recruit.card.admit"),
            this::admitTravelerAction);
        admit.active = cachedRecruitmentCard.mayAdmit()
            && !recruitmentAdmissionPending;
        admit.setTooltip(Tooltip.create(recruitmentAdmissionPending
            ? Component.translatable("hearthstead.recruit.card.pending")
            : cachedRecruitmentCard.mayAdmit()
                ? Component.translatable("hearthstead.recruit.card.admit.tip",
                    cachedRecruitmentCard.name())
                : recruitmentBlockerTooltip()));
        HsButton dismiss = HsButton.danger(
            recruitmentPanelLeft + RECRUIT_PANEL_W - RECRUIT_PANEL_PAD
                - RECRUIT_ACTION_W,
            recruitmentPanelTop + RECRUIT_PANEL_H - 30,
            RECRUIT_ACTION_W, HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.recruit.card.dismiss"),
            this::dismissTravelerAction);
        dismiss.active = cachedRecruitmentCard.mayDismiss()
            && !recruitmentAdmissionPending;
        dismiss.setTooltip(Tooltip.create(recruitmentAdmissionPending
            ? Component.translatable("hearthstead.recruit.card.pending")
            : Component.translatable("hearthstead.recruit.card.dismiss.tip",
                cachedRecruitmentCard.name())));
        addPanelWidget(admit);
        addPanelWidget(dismiss);
    }

    private void rebuildRequestWidgets() {
        updateRequestPanelPosition();
        suppressCoveredTabs(requestPanelLeft, requestPanelTop,
            REQUEST_PANEL_W, REQUEST_PANEL_H);
        addPanelClose(requestPanelLeft, requestPanelTop, REQUEST_PANEL_W);
        HsButton refresh = HsButton.normal(
            requestPanelLeft + REQUEST_PANEL_PAD, requestPanelTop + 4,
            REQUEST_REFRESH_W, HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.request.ledger.refresh"),
            this::refreshRequestLedger);
        refresh.active = !requestLoading;
        refresh.setTooltip(Tooltip.create(Component.translatable(
            requestLoading ? "hearthstead.request.ledger.loading"
                : "hearthstead.request.ledger.refresh.tip")));
        addPanelWidget(refresh);
        requestScroll = Math.max(0, Math.min(requestScroll,
            Math.max(0, cachedRequestRows.size() - REQUEST_MAX_ROWS)));
    }

    private void rebuildJourneyWidgets() {
        updateJourneyPanelPosition();
        suppressCoveredTabs(journeyPanelLeft, journeyPanelTop,
            JOURNEY_PANEL_W, journeyPanelHeight);
        addPanelClose(journeyPanelLeft, journeyPanelTop, JOURNEY_PANEL_W);
        if (cachedReadinessView.open()) {
            rebuildReadinessWidgets();
            return;
        }
        if (shouldShowAftermath()) {
            return; // immutable report has no client-authored action
        }
        if (readinessLoading) {
            return;
        }
        if (canOpenRaidReadiness()) {
            HsButton check = HsButton.normal(
                journeyPanelLeft + (JOURNEY_PANEL_W - JOURNEY_BUTTON_W) / 2,
                journeyPanelTop + journeyButtonY, JOURNEY_BUTTON_W,
                HsUiTokens.BUTTON_H,
                Component.translatable("hearthstead.raid.readiness.check"),
                this::openRaidReadiness);
            check.setTooltip(Tooltip.create(Component.translatable(
                "hearthstead.raid.readiness.check.tip")));
            addPanelWidget(check);
            return;
        }
        if (menu.get(HearthMenu.DATA_JOURNEY_V3_CAN_SKIP) != 1
            || journeySkipPending) {
            return;
        }
        if (!journeySkipConfirm) {
            HsButton skip = HsButton.danger(
                journeyPanelLeft + (JOURNEY_PANEL_W - JOURNEY_BUTTON_W) / 2,
                journeyPanelTop + journeyButtonY, JOURNEY_BUTTON_W,
                HsUiTokens.BUTTON_H,
                Component.translatable("hearthstead.journey.skip"), () -> {
                    QaClientObserver.markUiTransition("hearth_journey_confirm_open");
                    journeySkipConfirm = true;
                    rebuildSeatWidgets();
                });
            skip.setTooltip(Tooltip.create(
                Component.translatable("hearthstead.journey.skip.tip")));
            addPanelWidget(skip);
            return;
        }

        int half = (JOURNEY_PANEL_W - 2 * JOURNEY_PAD - JOURNEY_GAP) / 2;
        HsButton cancel = HsButton.normal(journeyPanelLeft + JOURNEY_PAD,
            journeyPanelTop + journeyButtonY, half, HsUiTokens.BUTTON_H,
            Component.translatable("gui.cancel"), () -> {
                QaClientObserver.markUiTransition("hearth_journey_confirm_cancel");
                journeySkipConfirm = false;
                rebuildSeatWidgets();
            });
        HsButton confirm = HsButton.danger(
            journeyPanelLeft + JOURNEY_PAD + half + JOURNEY_GAP,
            journeyPanelTop + journeyButtonY, half, HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.journey.skip.confirm"),
            this::confirmJourneySkip);
        confirm.setTooltip(Tooltip.create(
            Component.translatable("hearthstead.journey.skip.confirm.tip")));
        addPanelWidget(cancel);
        addPanelWidget(confirm);
    }

    private void rebuildReadinessWidgets() {
        int visibleRows = readinessVisibleRows();
        readinessScroll = Math.max(0, Math.min(readinessScroll,
            Math.max(0, cachedReadinessBlockers.size() - visibleRows)));
        if (cachedReadinessView.committed()) {
            return;
        }
        int half = (JOURNEY_PANEL_W - 2 * JOURNEY_PAD - JOURNEY_GAP) / 2;
        HsButton refresh = HsButton.normal(journeyPanelLeft + JOURNEY_PAD,
            journeyPanelTop + journeyButtonY, half, HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.raid.readiness.refresh"),
            this::openRaidReadiness);
        HsButton declare = HsButton.normal(
            journeyPanelLeft + JOURNEY_PAD + half + JOURNEY_GAP,
            journeyPanelTop + journeyButtonY, half, HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.raid.readiness.declare"),
            this::confirmRaidReadiness);
        refresh.active = !readinessLoading && !readinessCommitPending;
        declare.active = cachedReadinessView.ready()
            && !readinessLoading && !readinessCommitPending;
        refresh.setTooltip(Tooltip.create(Component.translatable(
            "hearthstead.raid.readiness.refresh.tip")));
        declare.setTooltip(Tooltip.create(Component.translatable(
            declare.active ? "hearthstead.raid.readiness.declare.tip"
                : "hearthstead.raid.readiness.declare.blocked")));
        addPanelWidget(refresh);
        addPanelWidget(declare);
    }

    private void addPanelClose(int panelLeft, int panelTop, int panelWidth) {
        HsButton close = HsButton.normal(
            panelLeft + panelWidth - MAYOR_PAD - 44, panelTop + 4,
            44, HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.gui.close"), this::closePopout);
        close.setTooltip(Tooltip.create(Component.translatable(
            "hearthstead.gui.close.tip")));
        addPanelWidget(close);
    }

    private void addPanelWidget(AbstractButton widget) {
        latePanelWidgets.add(widget);
        // addWidget keeps keyboard, narration and focus semantics without
        // placing the control in super.render's early renderable pass. The
        // late modal pass below is therefore the one and only visual draw.
        addWidget(widget);
    }

    private void addSeatTab(SeatTabButton tab) {
        seatTabs.add(tab);
        addRenderableWidget(tab);
    }

    private void suppressCoveredTabs(int panelLeft, int panelTop,
                                     int panelWidth, int panelHeight) {
        int panelRight = panelLeft + panelWidth;
        int panelBottom = panelTop + panelHeight;
        for (SeatTabButton tab : seatTabs) {
            boolean covered = tab.getX() < panelRight
                && tab.getX() + tab.getWidth() > panelLeft
                && tab.getY() < panelBottom
                && tab.getY() + tab.getHeight() > panelTop;
            tab.visible = !covered;
            tab.active = !covered;
        }
    }

    private void closePopout() {
        QaClientObserver.markUiTransition("hearth_popout_close");
        mayorTabOpen = false;
        journeyTabOpen = false;
        recruitmentPanelOpen = false;
        requestPanelOpen = false;
        journeySkipConfirm = false;
        rebuildSeatWidgets();
    }

    /** Prefers the right of the window; falls back left, then clamps on-screen. */
    private void updateMayorPanelPosition() {
        int tabLeft = leftPos + (imageWidth - TAB_STRIP_W) / 2;
        int preferred = Math.max(leftPos + imageWidth,
            tabLeft + TAB_STRIP_W) + MAYOR_GAP;
        if (preferred + MAYOR_PANEL_W > width) {
            int leftSide = Math.min(leftPos, tabLeft) - MAYOR_GAP - MAYOR_PANEL_W;
            preferred = leftSide >= 0 ? leftSide : Math.max(0, width - MAYOR_PANEL_W);
        }
        mayorPanelLeft = preferred;
        mayorPanelTop = Mth.clamp(
            topPos - (mayorPanelHeight - imageHeight) / 2,
            0, Math.max(0, height - mayorPanelHeight));
    }

    private void updateJourneyPanelPosition() {
        int tabLeft = leftPos + (imageWidth - TAB_STRIP_W) / 2;
        int preferred = Math.max(leftPos + imageWidth,
            tabLeft + TAB_STRIP_W) + JOURNEY_GAP;
        if (preferred + JOURNEY_PANEL_W > width) {
            int leftSide = Math.min(leftPos, tabLeft) - JOURNEY_GAP - JOURNEY_PANEL_W;
            preferred = leftSide >= 0 ? leftSide : Math.max(0, width - JOURNEY_PANEL_W);
        }
        journeyPanelLeft = preferred;
        journeyPanelTop = Mth.clamp(
            topPos - (journeyPanelHeight - imageHeight) / 2,
            0, Math.max(0, height - journeyPanelHeight));
    }

    private void updateRecruitmentPanelPosition() {
        int tabLeft = leftPos + (imageWidth - TAB_STRIP_W) / 2;
        int preferred = Math.max(leftPos + imageWidth,
            tabLeft + TAB_STRIP_W) + RECRUIT_PANEL_GAP;
        if (preferred + RECRUIT_PANEL_W > width) {
            int leftSide = Math.min(leftPos, tabLeft) - RECRUIT_PANEL_GAP
                - RECRUIT_PANEL_W;
            preferred = leftSide >= 0 ? leftSide
                : Math.max(0, width - RECRUIT_PANEL_W);
        }
        recruitmentPanelLeft = preferred;
        recruitmentPanelTop = Mth.clamp(
            topPos - (RECRUIT_PANEL_H - imageHeight) / 2,
            0, Math.max(0, height - RECRUIT_PANEL_H));
    }

    private void updateRequestPanelPosition() {
        int tabLeft = leftPos + (imageWidth - TAB_STRIP_W) / 2;
        int preferred = Math.max(leftPos + imageWidth,
            tabLeft + TAB_STRIP_W) + REQUEST_PANEL_GAP;
        if (preferred + REQUEST_PANEL_W > width) {
            int leftSide = Math.min(leftPos, tabLeft) - REQUEST_PANEL_GAP
                - REQUEST_PANEL_W;
            preferred = leftSide >= 0 ? leftSide
                : Math.max(0, width - REQUEST_PANEL_W);
        }
        requestPanelLeft = preferred;
        requestPanelTop = Mth.clamp(
            topPos - (REQUEST_PANEL_H - imageHeight) / 2,
            0, Math.max(0, height - REQUEST_PANEL_H));
    }

    private void requestMayorData() {
        PacketDistributor.sendToServer(
            mayorAction(HearthMayorAction.Kind.REFRESH, HearthMayorAction.NO_ID, 0));
    }

    private void requestDevelopmentData() {
        QaClientObserver.markUiTransition("hearth_development_open");
        PacketDistributor.sendToServer(mayorAction(
            HearthMayorAction.Kind.OPEN_DEVELOPMENT, HearthMayorAction.NO_ID, 0));
    }

    private void openRequestLedger() {
        QaClientObserver.markUiTransition("hearth_request_ledger_open");
        mayorTabOpen = false;
        journeyTabOpen = false;
        recruitmentPanelOpen = false;
        requestPanelOpen = true;
        requestLoading = true;
        requestLoadingTicks = 0;
        requestUnavailable = false;
        requestScroll = 0;
        // Keep the latest accepted rows and revision floor while this exact
        // Hearth screen remains open. The loading label makes their age
        // explicit, and a delayed response from an earlier open can no longer
        // roll the cache back or resolve the current refresh dishonestly.
        PacketDistributor.sendToServer(mayorAction(
            HearthMayorAction.Kind.OPEN_REQUEST_LEDGER,
            HearthMayorAction.NO_ID, 0));
        rebuildSeatWidgets();
    }

    /** Refreshes the same exact Hearth-owned view without blanking useful rows. */
    private void refreshRequestLedger() {
        if (!requestPanelOpen || requestLoading) {
            return;
        }
        QaClientObserver.markUiTransition("hearth_request_ledger_refresh");
        requestLoading = true;
        requestLoadingTicks = 0;
        requestUnavailable = false;
        PacketDistributor.sendToServer(mayorAction(
            HearthMayorAction.Kind.OPEN_REQUEST_LEDGER,
            HearthMayorAction.NO_ID, 0));
        rebuildSeatWidgets();
    }

    private void openRaidReadiness() {
        QaClientObserver.markUiTransition("hearth_raid_readiness_check");
        readinessLoading = true;
        readinessCommitPending = false;
        PacketDistributor.sendToServer(mayorAction(
            HearthMayorAction.Kind.OPEN_RAID_READINESS,
            HearthMayorAction.NO_ID, 0));
        rebuildSeatWidgets();
    }

    private void confirmRaidReadiness() {
        HearthMayorSnapshot.ReadinessView view = cachedReadinessView;
        if (!view.open() || !view.ready() || view.committed()
            || HearthMayorAction.NO_ID.equals(view.sessionId())
            || readinessLoading || readinessCommitPending) {
            return;
        }
        QaClientObserver.markUiTransition("hearth_raid_readiness_declare");
        readinessCommitPending = true;
        PacketDistributor.sendToServer(mayorAction(
            HearthMayorAction.Kind.CONFIRM_RAID_READINESS,
            view.sessionId(), view.actionRevision()));
        rebuildSeatWidgets();
    }

    private void resetReadinessView() {
        cachedReadinessView = HearthMayorSnapshot.ReadinessView.closed();
        cachedReadinessBlockers = List.of();
        readinessMetaLine = Component.empty();
        readinessMetricsLine = Component.empty();
        readinessScroll = 0;
        readinessLoading = false;
        readinessCommitPending = false;
    }

    private boolean canOpenRaidReadiness() {
        JourneyPresentationMode mode = JourneyPresentationMode.tryFromWireId(
            menu.get(HearthMenu.DATA_JOURNEY_V3_MODE))
            .orElse(JourneyPresentationMode.QUARANTINED);
        if (mode == JourneyPresentationMode.SKIPPED) {
            return true;
        }
        int current = menu.get(HearthMenu.DATA_JOURNEY_V3_CURRENT);
        return mode == JourneyPresentationMode.ACTIVE && current >= 0
            && current < JourneyDefinition.CURRENT.orderedSteps().size()
            && JourneyIds.FJ_560_DECLARE_RAID_READY.equals(
                JourneyDefinition.CURRENT.stepAt(current).id());
    }

    private int readinessVisibleRows() {
        return readinessRowsFor(journeyPanelHeight);
    }

    static int readinessRowsFor(int panelHeight) {
        return panelHeight < 250 ? 3
            : panelHeight < JOURNEY_PANEL_H ? 4 : READINESS_MAX_ROWS;
    }

    private void appointAction(UUID id) {
        if (mayorSnapshot != null) {
            PacketDistributor.sendToServer(
                mayorAction(HearthMayorAction.Kind.APPOINT, id,
                    mayorSnapshot.revision()));
        }
    }

    private void admitTravelerAction() {
        HearthMayorSnapshot.RecruitmentCard card = cachedRecruitmentCard;
        if (!card.present() || !card.mayAdmit()
            || recruitmentAdmissionPending) {
            return;
        }
        QaClientObserver.markUiTransition("hearth_recruitment_admit_submit");
        recruitmentAdmissionPending = true;
        PacketDistributor.sendToServer(mayorAction(
            HearthMayorAction.Kind.ADMIT_TRAVELER, card.travelerId(),
            card.revision()));
        rebuildSeatWidgets();
    }

    private void dismissTravelerAction() {
        HearthMayorSnapshot.RecruitmentCard card = cachedRecruitmentCard;
        if (!card.present() || !card.mayDismiss()
            || recruitmentAdmissionPending) {
            return;
        }
        QaClientObserver.markUiTransition("hearth_recruitment_dismiss_submit");
        recruitmentAdmissionPending = true;
        PacketDistributor.sendToServer(mayorAction(
            HearthMayorAction.Kind.REJECT_TRAVELER, card.travelerId(),
            card.revision()));
        rebuildSeatWidgets();
    }

    private void updateRecruitmentCardCache(
            HearthMayorSnapshot.RecruitmentCard card, boolean force) {
        HearthMayorSnapshot.RecruitmentCard safe = card == null
            ? HearthMayorSnapshot.RecruitmentCard.empty() : card;
        if (!force && safe.equals(cachedRecruitmentCard)) {
            updateRecruitmentTimeCache(safe);
            return;
        }
        cachedRecruitmentCard = safe;
        cachedRecruitmentSecond = Long.MIN_VALUE;
        if (!safe.present()) {
            recruitmentNameLine = Component.empty();
            recruitmentStageLine = Component.empty();
            recruitmentBedsLine = Component.empty();
            recruitmentFoodLine = Component.empty();
            recruitmentTimeLine = Component.empty();
            recruitmentBlockerComponent = Component.empty();
            recruitmentCostLines = List.of();
            recruitmentBlockerLines = List.of();
            return;
        }

        recruitmentNameLine = Component.literal(safe.name());
        RecruitmentTransaction.Status status =
            RecruitmentTransaction.Status.fromWireId(safe.statusWireId());
        recruitmentStageLine = switch (status) {
            case TRAVELING -> Component.translatable(
                "hearthstead.recruit.card.stage.traveling");
            case WAITING_ADMISSION -> Component.translatable(
                "hearthstead.recruit.card.stage.waiting");
            case ATTRACTING, QUALIFYING, READY_TO_SPAWN, ADMITTED, LEFT,
                 QUARANTINED, UNKNOWN -> Component.translatable(
                "hearthstead.recruit.card.stage.unavailable");
        };
        recruitmentBedsLine = Component.translatable(
            "hearthstead.recruit.card.beds", safe.freeBeds());
        recruitmentFoodLine = Component.translatable(
            "hearthstead.recruit.card.food", safe.readyFood(),
            safe.requiredFood());
        List<Component> costs = new ArrayList<>(safe.costLines().size());
        for (HearthMayorSnapshot.CostLine cost : safe.costLines()) {
            costs.add(Component.translatable("hearthstead.recruit.card.cost.line",
                cost.count(), Component.translatable(cost.translationKey())));
        }
        recruitmentCostLines = List.copyOf(costs);
        recruitmentBlockerComponent = recruitmentBlocker(safe);
        recruitmentBlockerLines = List.copyOf(font.split(
            recruitmentBlockerComponent, RECRUIT_PANEL_W
                - 2 * RECRUIT_PANEL_PAD - 12));
        updateRecruitmentTimeCache(safe);
    }

    private void updateRecruitmentTimeCache(
            HearthMayorSnapshot.RecruitmentCard card) {
        long second = currentGameTime() / 20L;
        if (second == cachedRecruitmentSecond) {
            return;
        }
        cachedRecruitmentSecond = second;
        RecruitmentTransaction.Status status =
            RecruitmentTransaction.Status.fromWireId(card.statusWireId());
        if (!card.present()) {
            recruitmentTimeLine = Component.empty();
        } else if (status != RecruitmentTransaction.Status.WAITING_ADMISSION
            || card.patienceUntil() <= 0L) {
            recruitmentTimeLine = Component.translatable(
                "hearthstead.recruit.card.time.after_arrival");
        } else {
            long remaining = Math.max(0L,
                card.patienceUntil() - currentGameTime());
            recruitmentTimeLine = Component.translatable(
                "hearthstead.recruit.card.time.remaining",
                formatTicks(remaining));
        }
    }

    /** Builds every label once per received snapshot, never in render(). */
    private boolean updateRequestViewCache(
            HearthMayorSnapshot.RequestView view) {
        if (view == null || !view.acceptsAfter(cachedRequestView)) {
            return false; // closed/delayed/revision-regressing projection
        }
        cachedRequestView = view;
        List<RequestRenderRow> rendered = new ArrayList<>(view.rows().size());
        for (HearthMayorSnapshot.RequestRow row : view.rows()) {
            rendered.add(requestRenderRow(row));
        }
        cachedRequestRows = List.copyOf(rendered);
        requestScroll = Math.max(0, Math.min(requestScroll,
            Math.max(0, cachedRequestRows.size() - REQUEST_MAX_ROWS)));
        requestMetaLine = view.quarantined()
            ? Component.translatable("hearthstead.request.ledger.quarantined",
                Component.literal(view.quarantineReason()))
            : Component.translatable("hearthstead.request.ledger.summary",
                view.rows().size(), view.typedRevision(),
                view.equipmentRevision());
        requestFooterLine = Component.translatable(view.truncated()
                ? "hearthstead.request.ledger.truncated"
                : "hearthstead.request.ledger.complete",
            view.rows().size());
        return true;
    }

    /** Builds the complete readiness checklist only when a packet arrives. */
    private void updateReadinessViewCache(
            HearthMayorSnapshot.ReadinessView view) {
        cachedReadinessView = view;
        List<Component> blockers = new ArrayList<>(
            view.blockerWireIds().size());
        for (int wireId : view.blockerWireIds()) {
            FirstRaidReadinessService.Blocker blocker =
                FirstRaidReadinessService.Blocker.fromWireId(wireId);
            if (blocker != null) {
                blockers.add(Component.translatable(
                    "hearthstead.raid.readiness.blocker." + blocker.id()));
            }
        }
        cachedReadinessBlockers = List.copyOf(blockers);
        readinessScroll = Math.max(0, Math.min(readinessScroll,
            Math.max(0, cachedReadinessBlockers.size()
                - readinessVisibleRows())));
        readinessMetaLine = view.committed()
            ? Component.translatable(
                "hearthstead.raid.readiness.status.committed")
            : view.ready()
                ? Component.translatable(
                    "hearthstead.raid.readiness.status.ready")
                : Component.translatable(
                    "hearthstead.raid.readiness.status.blocked",
                    view.blockerWireIds().size());
        readinessMetricsLine = Component.translatable(
            "hearthstead.raid.readiness.metrics",
            view.inspectedSettlers(), view.housingCapacity(),
            view.availableReadyMeals(), view.requiredReadyMeals(),
            view.requestActiveRows(), view.requestBlockedRows());
    }

    /** Builds the complete fixed aftermath copy only when a snapshot arrives. */
    private void updateAftermathViewCache(
            HearthMayorSnapshot.AftermathView view) {
        cachedAftermathView = view == null
            ? HearthMayorSnapshot.AftermathView.closed() : view;
        if (!cachedAftermathView.present()) {
            aftermathStatusLine = Component.empty();
            aftermathNightLine = Component.empty();
            aftermathCaptainLine = Component.empty();
            aftermathObjectiveLine = Component.empty();
            aftermathImpactLine = Component.empty();
            aftermathThreatLine = Component.empty();
            aftermathRewardLine = Component.empty();
            aftermathRoadLine = Component.empty();
            return;
        }
        aftermathStatusLine = Component.translatable(
            cachedAftermathView.held()
                ? "hearthstead.raid.aftermath.held"
                : "hearthstead.raid.aftermath.lost");
        aftermathNightLine = Component.translatable(
            "hearthstead.raid.aftermath.night", cachedAftermathView.night());
        aftermathCaptainLine = Component.translatable(
            "hearthstead.raid.aftermath.captain",
            Component.literal(cachedAftermathView.captainName()));
        aftermathObjectiveLine = Component.translatable(
            "hearthstead.raid.aftermath.objective",
            Component.translatable("hearthstead.raid.objective."
                + cachedAftermathView.objectiveId()));
        aftermathImpactLine = Component.translatable(
            "hearthstead.raid.aftermath.impact",
            cachedAftermathView.itemsStolen(),
            cachedAftermathView.settlersHurt());
        aftermathThreatLine = Component.translatable(
            "hearthstead.raid.aftermath.threat",
            Component.translatable("hearthstead.raid.stage."
                + cachedAftermathView.threatStageId()));

        HearthMayorSnapshot.AftermathView.RewardStatus reward =
            cachedAftermathView.rewardStatus();
        aftermathRewardLine = reward == null ? Component.empty()
            : reward == HearthMayorSnapshot.AftermathView.RewardStatus.OFFER_PENDING
                ? Component.translatable(
                    "hearthstead.raid.aftermath.reward.pending",
                    cachedAftermathView.offerSerial())
                : Component.translatable(
                    "hearthstead.raid.aftermath.reward." + reward.id());
        HearthMayorSnapshot.AftermathView.RoadAhead road =
            cachedAftermathView.roadAhead();
        aftermathRoadLine = road == null ? Component.empty()
            : Component.translatable("hearthstead.raid.aftermath.road."
                + road.id());
    }

    private boolean shouldShowAftermath() {
        JourneyPresentationMode mode = JourneyPresentationMode.tryFromWireId(
            menu.get(HearthMenu.DATA_JOURNEY_V3_MODE))
            .orElse(JourneyPresentationMode.QUARANTINED);
        return aftermathVisibleFor(cachedAftermathView.present(), mode,
            menu.get(HearthMenu.DATA_JOURNEY_V3_CURRENT));
    }

    static boolean aftermathVisibleFor(boolean reportPresent,
                                       JourneyPresentationMode mode,
                                       int currentStepOrdinal) {
        if (!reportPresent || mode == null) {
            return false;
        }
        // FJ-620 is deliberately ordinal 44 in the append-only v3 graph,
        // while the added Watch path lives at ordinals 45..55 and then joins
        // back into FJ-560. A completed-count threshold therefore confuses a
        // partially migrated Watch path with a finished raid and replaces the
        // remaining objectives with the immutable report. Show the report
        // only while FJ-620 itself is the active objective, or after the
        // server has explicitly transitioned the Journey to COMPLETE.
        return mode == JourneyPresentationMode.COMPLETE
            || mode == JourneyPresentationMode.ACTIVE
                && currentStepOrdinal == AFTERMATH_STEP_ORDINAL;
    }

    private static RequestRenderRow requestRenderRow(
            HearthMayorSnapshot.RequestRow row) {
        RequestType type = RequestType.fromWireId(row.typeWireId())
            .orElse(RequestType.OUTPUT_PICKUP);
        RequestState state = RequestState.fromWireId(row.stateWireId())
            .orElse(RequestState.BLOCKED);
        RequestPriority priority = RequestPriority.fromWireId(
            row.priorityWireId()).orElse(RequestPriority.NORMAL);
        RequestBlocker blocker = RequestBlocker.fromWireId(
            row.blockerWireId()).orElse(RequestBlocker.MALFORMED);

        Component headline = Component.translatable(
            "hearthstead.request.row.headline",
            Component.translatable("hearthstead.request.priority."
                + priority.name().toLowerCase(java.util.Locale.ROOT)),
            Component.translatable("hearthstead.request.type." + type.id()),
            row.requestedCount(), requestItem(row.itemId()));
        Component route = row.equipmentAdapter()
            ? Component.translatable("hearthstead.request.row.route.requester",
                Component.literal(row.requesterName()),
                Component.translatable("hearthstead.profession."
                    + row.professionId()), requestLocation(row.targetNameKey(),
                    row.targetPos()))
            : Component.translatable("hearthstead.request.row.route.output",
                requestLocation(row.sourceNameKey(), row.sourcePos()),
                requestLocation(row.targetNameKey(), row.targetPos()));

        Component stateName = Component.translatable(
            "hearthstead.request.state."
                + state.name().toLowerCase(java.util.Locale.ROOT));
        Component ownerName = Component.translatable(
            "hearthstead.request.owner." + switch (row.physicalOwnerWireId()) {
                case 0 -> "source";
                case 1 -> "courier_bag";
                case 2 -> "target";
                default -> "unknown";
            });
        Component age = requestAge(row.ageTicks());
        Component courier = row.hasCourier()
            ? row.courierName().isBlank()
                ? Component.translatable("hearthstead.request.courier.assigned")
                : Component.literal(row.courierName())
            : Component.translatable("hearthstead.request.courier.unassigned");
        // Every card always exposes the same five truths. Previously the
        // blocker branch hid the assigned Courier, while the healthy branch
        // hid the concrete stop field entirely.
        Component assignment = Component.translatable(
            "hearthstead.request.row.assignment", courier, ownerName);
        Component stop = Component.translatable("hearthstead.request.row.stop",
            stateName, Component.translatable(
                "hearthstead.request.blocker." + blocker.id()));
        Component ageLine = Component.translatable(
            "hearthstead.request.row.age", age);
        int tone = blocker != RequestBlocker.NONE ? HsUiTokens.WARN
            : state == RequestState.SATISFIED ? HsUiTokens.GOOD
            : priority == RequestPriority.URGENT ? HsUiTokens.BAD
            : priority == RequestPriority.HIGH ? HsUiTokens.ACCENT
            : HsUiTokens.TEXT_MUTED;
        return new RequestRenderRow(headline, route, assignment, stop,
            List.of(headline, route, assignment, stop, ageLine), tone);
    }

    private static Component requestItem(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        Item item = id == null ? Items.AIR
            : BuiltInRegistries.ITEM.getOptional(id).orElse(Items.AIR);
        return item == Items.AIR
            ? Component.literal(itemId)
            : Component.translatable(item.getDescriptionId());
    }

    private static Component requestLocation(String key,
                                             net.minecraft.core.BlockPos pos) {
        if ("hearthstead.request.location.unknown".equals(key)) {
            return Component.translatable(key);
        }
        return Component.translatable("hearthstead.request.location.at",
            Component.translatable(key), pos.getX(), pos.getY(), pos.getZ());
    }

    private static Component requestAge(long ticks) {
        if (ticks < 0L) {
            return Component.translatable("hearthstead.request.age.unknown");
        }
        long seconds = ticks / 20L;
        if (seconds < 60L) {
            return Component.translatable("hearthstead.request.age.seconds",
                seconds);
        }
        long minutes = seconds / 60L;
        if (minutes < 60L) {
            return Component.translatable("hearthstead.request.age.minutes",
                minutes);
        }
        return Component.translatable("hearthstead.request.age.hours",
            minutes / 60L);
    }

    /** Formats only the server-authored blocker id and its supplied counts. */
    private static Component recruitmentBlocker(
            HearthMayorSnapshot.RecruitmentCard card) {
        return switch (RecruitmentPolicy.Blocker.fromWireId(
            card.blockerWireId())) {
            case NONE -> card.mayAdmit()
                ? Component.translatable("hearthstead.recruit.card.ready")
                : Component.translatable("hearthstead.recruit.card.traveling");
            case NO_HEARTH -> Component.translatable(
                "hearthstead.gui.recruit_blocked.hearth");
            case NO_TAVERN -> Component.translatable(
                "hearthstead.gui.recruit_blocked.tavern");
            case NO_BED -> Component.translatable(
                "hearthstead.recruit.card.blocked.beds", card.freeBeds());
            case LOW_MORALE -> Component.translatable(
                "hearthstead.recruit.card.blocked.morale");
            case CANNOT_PAY -> Component.translatable(
                "hearthstead.gui.recruit_blocked.price");
            case INSUFFICIENT_READY_FOOD -> Component.translatable(
                "hearthstead.gui.recruit_blocked.reserve",
                card.readyFood(), card.requiredFood());
            case INVALID_STATE -> Component.translatable(
                "hearthstead.gui.recruit_blocked.invalid");
        };
    }

    private Component recruitmentBlockerTooltip() {
        return recruitmentBlockerComponent.getString().isEmpty()
            ? Component.translatable("hearthstead.gui.recruit_blocked.invalid")
            : recruitmentBlockerComponent;
    }

    private HearthMayorAction mayorAction(HearthMayorAction.Kind kind,
                                          UUID target, int revision) {
        return new HearthMayorAction(menu.getHearthPos(), menu.getSettlementId(),
            menu.getContainerId(), kind, target, revision);
    }

    private void confirmJourneySkip() {
        QaClientObserver.markUiTransition("hearth_journey_skip_submit");
        int revision = menu.get(HearthMenu.DATA_JOURNEY_V3_REVISION);
        PacketDistributor.sendToServer(mayorAction(
            HearthMayorAction.Kind.SKIP_JOURNEY, HearthMayorAction.NO_ID,
            revision));
        journeySentRevision = revision;
        journeySkipPending = true;
        journeySkipConfirm = false;
        rebuildSeatWidgets();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (requestPanelOpen && requestLoading) {
            requestLoadingTicks = Math.min(Integer.MAX_VALUE,
                requestLoadingTicks + 1);
            if (requestLoadTimedOut(requestPanelOpen, requestLoading,
                    requestLoadingTicks)) {
                requestLoading = false;
                requestUnavailable = true;
                requestMetaLine = REQUEST_UNAVAILABLE;
                requestFooterLine = REQUEST_RETRY;
                QaClientObserver.markUiTransition(
                    "hearth_request_ledger_unavailable");
                rebuildSeatWidgets();
            }
        }
        int phase = menu.get(HearthMenu.DATA_JOURNEY_V3_MODE);
        int revision = menu.get(HearthMenu.DATA_JOURNEY_V3_REVISION);
        int canSkip = menu.get(HearthMenu.DATA_JOURNEY_V3_CAN_SKIP);
        boolean journeyDataChanged = phase != observedJourneyPhase
            || revision != observedJourneyRevision
            || canSkip != observedJourneyCanSkip;
        observedJourneyPhase = phase;
        observedJourneyRevision = revision;
        observedJourneyCanSkip = canSkip;
        boolean pendingResolved = false;
        if (journeySkipPending
            && (revision != journeySentRevision || canSkip != 1)) {
            journeySkipPending = false;
            journeySentRevision = -1;
            pendingResolved = true;
        }
        if ((journeyTabOpen && journeyDataChanged) || pendingResolved) {
            rebuildSeatWidgets();
        }

        int recruitmentRevision = menu.get(HearthMenu.DATA_RECRUIT_REVISION);
        int recruitmentStatus = menu.get(
            HearthMenu.DATA_RECRUIT_TRANSACTION_STATUS);
        boolean recruitmentChanged = recruitmentRevision
            != observedRecruitmentRevision
            || recruitmentStatus != observedRecruitmentStatus;
        observedRecruitmentRevision = recruitmentRevision;
        observedRecruitmentStatus = recruitmentStatus;
        if (recruitmentChanged) {
            requestMayorData();
        }
        if (recruitmentPanelOpen && cachedRecruitmentCard.present()) {
            updateRecruitmentTimeCache(cachedRecruitmentCard);
        }
    }

    static boolean requestLoadTimedOut(boolean panelOpen, boolean loading,
                                       int elapsedTicks) {
        return panelOpen && loading && elapsedTicks >= REQUEST_LOAD_TIMEOUT_TICKS;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        int readinessRows = readinessVisibleRows();
        if (journeyTabOpen && cachedReadinessView.open()
            && cachedReadinessBlockers.size() > readinessRows
            && mouseX >= journeyPanelLeft
            && mouseX <= journeyPanelLeft + JOURNEY_PANEL_W
            && mouseY >= journeyPanelTop
            && mouseY <= journeyPanelTop + journeyPanelHeight) {
            int before = readinessScroll;
            readinessScroll = Math.max(0, Math.min(
                cachedReadinessBlockers.size() - readinessRows,
                readinessScroll - (int) Math.signum(dy)));
            if (before != readinessScroll) {
                QaClientObserver.markUiTransition(
                    "hearth_raid_readiness_scroll");
                return true;
            }
        }
        if (requestPanelOpen && cachedRequestRows.size() > REQUEST_MAX_ROWS
            && mouseX >= requestPanelLeft
            && mouseX <= requestPanelLeft + REQUEST_PANEL_W
            && mouseY >= requestPanelTop
            && mouseY <= requestPanelTop + REQUEST_PANEL_H) {
            int before = requestScroll;
            requestScroll = Math.max(0, Math.min(
                cachedRequestRows.size() - REQUEST_MAX_ROWS,
                requestScroll - (int) Math.signum(dy)));
            if (before != requestScroll) {
                QaClientObserver.markUiTransition("hearth_request_ledger_scroll");
                return true;
            }
        }
        if (mayorTabOpen && mayorSnapshot != null) {
            int rows = mayorSnapshot.candidates().size();
            if (rows > mayorVisibleRows && mouseX >= mayorPanelLeft
                && mouseX <= mayorPanelLeft + MAYOR_PANEL_W
                && mouseY >= mayorPanelTop
                && mouseY <= mayorPanelTop + mayorPanelHeight) {
                int before = mayorScroll;
                mayorScroll = Math.max(0, Math.min(rows - mayorVisibleRows,
                    mayorScroll - (int) Math.signum(dy)));
                if (before != mayorScroll) {
                    QaClientObserver.markUiTransition("hearth_mayor_scroll");
                    rebuildSeatWidgets();
                    return true;
                }
            }
        }
        return super.mouseScrolled(mouseX, mouseY, dx, dy);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!isOverOpenPanel(mouseX, mouseY)) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        // The popout can overlap the container at narrow GUI widths. Route
        // clicks to its real widgets, but consume blank panel clicks before
        // AbstractContainerScreen can pick up, quick-move or throw an item
        // from a slot hidden underneath the modal surface.
        for (AbstractButton listener : latePanelWidgets) {
            if (listener.mouseClicked(mouseX, mouseY, button)) {
                setFocused(listener);
                if (button == 0) {
                    setDragging(true);
                }
                return true;
            }
        }
        return true;
    }

    private boolean isOverOpenPanel(double mouseX, double mouseY) {
        if (recruitmentPanelOpen) {
            return mouseX >= recruitmentPanelLeft
                && mouseX < recruitmentPanelLeft + RECRUIT_PANEL_W
                && mouseY >= recruitmentPanelTop
                && mouseY < recruitmentPanelTop + RECRUIT_PANEL_H;
        }
        if (mayorTabOpen) {
            return mouseX >= mayorPanelLeft && mouseX < mayorPanelLeft + MAYOR_PANEL_W
                && mouseY >= mayorPanelTop
                && mouseY < mayorPanelTop + mayorPanelHeight;
        }
        if (requestPanelOpen) {
            return mouseX >= requestPanelLeft
                && mouseX < requestPanelLeft + REQUEST_PANEL_W
                && mouseY >= requestPanelTop
                && mouseY < requestPanelTop + REQUEST_PANEL_H;
        }
        return journeyTabOpen
            && mouseX >= journeyPanelLeft && mouseX < journeyPanelLeft + JOURNEY_PANEL_W
            && mouseY >= journeyPanelTop
            && mouseY < journeyPanelTop + journeyPanelHeight;
    }

    @Override
    public String qaUiState() {
        String view = recruitmentPanelOpen ? "recruitment"
            : mayorTabOpen ? "mayor"
            : requestPanelOpen ? "requests"
            : journeyTabOpen && cachedReadinessView.open()
                ? "raid_readiness"
            : journeyTabOpen && shouldShowAftermath()
                ? "raid_aftermath"
            : journeyTabOpen ? "journey" : "settlement";
        int panelLeft = recruitmentPanelOpen ? recruitmentPanelLeft
            : mayorTabOpen ? mayorPanelLeft
            : requestPanelOpen ? requestPanelLeft
            : journeyTabOpen ? journeyPanelLeft : -1;
        int panelTop = recruitmentPanelOpen ? recruitmentPanelTop
            : mayorTabOpen ? mayorPanelTop
            : requestPanelOpen ? requestPanelTop
            : journeyTabOpen ? journeyPanelTop : -1;
        int panelWidth = recruitmentPanelOpen ? RECRUIT_PANEL_W
            : mayorTabOpen ? MAYOR_PANEL_W
            : requestPanelOpen ? REQUEST_PANEL_W
            : journeyTabOpen ? JOURNEY_PANEL_W : 0;
        int panelHeight = recruitmentPanelOpen ? RECRUIT_PANEL_H
            : mayorTabOpen ? mayorPanelHeight
            : requestPanelOpen ? REQUEST_PANEL_H
            : journeyTabOpen ? journeyPanelHeight : 0;
        int hiddenTabs = 0;
        for (SeatTabButton tab : seatTabs) {
            if (!tab.visible) {
                hiddenTabs++;
            }
        }
        boolean overlapsLedger = panelWidth > 0
            && panelLeft < leftPos + imageWidth
            && panelLeft + panelWidth > leftPos
            && panelTop < topPos + imageHeight
            && panelTop + panelHeight > topPos;
        return "view=" + view + ",panel=" + panelLeft + ":" + panelTop
            + ":" + panelWidth + ":" + panelHeight
            + ",ledgerOverlap=" + overlapsLedger + ",hiddenTabs=" + hiddenTabs
            + ",lateWidgets=" + latePanelWidgets.size()
            + ",mayorRows=" + mayorVisibleRows
            + ",journeyCompact=" + (journeyPanelHeight < JOURNEY_PANEL_H)
            + ",journeyConfirm=" + journeySkipConfirm
            + ",recruitPresent=" + cachedRecruitmentCard.present()
            + ",recruitRevision=" + cachedRecruitmentCard.revision()
            + ",recruitPending=" + recruitmentAdmissionPending
            + ",requestOpen=" + cachedRequestView.open()
            + ",requestRows=" + cachedRequestRows.size()
            + ",requestScroll=" + requestScroll
            + ",requestLoading=" + requestLoading
            + ",readinessOpen=" + cachedReadinessView.open()
            + ",readinessReady=" + cachedReadinessView.ready()
            + ",readinessCommitted=" + cachedReadinessView.committed()
            + ",readinessBlockers=" + cachedReadinessBlockers.size()
            + ",readinessScroll=" + readinessScroll
            + ",readinessLoading=" + readinessLoading
            + ",readinessPending=" + readinessCommitPending
            + ",aftermathPresent=" + cachedAftermathView.present()
            + ",aftermathNight=" + cachedAftermathView.night()
            + ",aftermathVisible=" + shouldShowAftermath();
    }

    // ------------------------------------------------------------- drawing ---

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        HsUi.window(graphics, leftPos, topPos, imageWidth, imageHeight);
        HsUi.divider(graphics, leftPos + 8, topPos + 26, imageWidth - 16);

        int storageWidth = Math.min(224, imageWidth - 16);
        HsUi.inset(graphics, leftPos + 8, topPos + 32,
            storageWidth, imageHeight - 40);
        if (imageWidth >= 400) {
            HsUi.inset(graphics, leftPos + 240, topPos + 32,
                imageWidth - 248, imageHeight - 40);
        }

        // The parchment texture used to provide slot borders. The new shared
        // material draws one truthful border for every real menu slot.
        for (var slot : menu.slots) {
            HsUi.slot(graphics, leftPos + slot.x - 1, topPos + slot.y - 1);
        }
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
        ensureMayorRenderModels();
        int pl = mayorPanelLeft;
        int pt = mayorPanelTop;
        HsUi.modalWindow(graphics, pl, pt, MAYOR_PANEL_W, mayorPanelHeight);
        graphics.drawString(font, MAYOR_TITLE,
            pl + (MAYOR_PANEL_W - mayorRenderModel.titleWidth()) / 2,
            pt + MAYOR_TITLE_Y, HsUiTokens.TEXT_STRONG, true);
        HsUi.divider(graphics, pl + MAYOR_PAD, pt + MAYOR_DIV1_Y, MAYOR_PANEL_W - 2 * MAYOR_PAD);

        drawMayorStatus(graphics, pl, pt);

        HsUi.divider(graphics, pl + MAYOR_PAD, pt + MAYOR_DIV2_Y, MAYOR_PANEL_W - 2 * MAYOR_PAD);
        graphics.drawString(font, mayorRenderModel.candidatesTitle().text(),
            pl + MAYOR_PAD, pt + MAYOR_LABEL_Y, HsUiTokens.TEXT_MUTED, true);

        if (mayorSnapshot == null) {
            graphics.drawString(font, mayorRenderModel.loading().text(),
                pl + MAYOR_PAD, pt + MAYOR_LIST_TOP,
                HsUiTokens.TEXT_MUTED, true);
            return;
        }

        List<HearthMayorSnapshot.Candidate> candidates = mayorSnapshot.candidates();
        if (candidates.isEmpty()) {
            graphics.drawString(font, mayorRenderModel.emptyCandidates().text(),
                pl + MAYOR_PAD, pt + MAYOR_LIST_TOP,
                HsUiTokens.TEXT_MUTED, true);
        }
        for (int row = 0; row < mayorVisibleRows
            && row + mayorScroll < candidates.size(); row++) {
            HearthMayorSnapshot.Candidate candidate = candidates.get(row + mayorScroll);
            int y = pt + MAYOR_LIST_TOP + row * MAYOR_CARD_STEP;
            boolean hovered = mouseX >= pl + MAYOR_CARD_X && mouseX <= pl + MAYOR_CARD_X + MAYOR_CARD_W
                && mouseY >= y && mouseY <= y + MAYOR_CARD_H;
            HsUi.card(graphics, pl + MAYOR_CARD_X, y, MAYOR_CARD_W, MAYOR_CARD_H, hovered);
            MayorRenderRow rendered = mayorRenderModel.candidates()
                .get(row + mayorScroll);
            graphics.drawString(font, rendered.name().text(),
                pl + MAYOR_TEXT_X, y + 6, HsUiTokens.TEXT_STRONG, true);
            graphics.drawString(font, rendered.boon().text(),
                pl + MAYOR_TEXT_X, y + 20, HsUiTokens.TEXT_MUTED, true);
            HsUi.pips(graphics, pl + MAYOR_BTN_X - 34, y + 9,
                Math.min(5, candidate.knack() * 5 / 100), 5, HsUi.Tone.ACCENT);
        }

        int rows = candidates.size();
        HsUi.scrollbar(graphics, pl + MAYOR_PANEL_W - MAYOR_PAD - HsUiTokens.SCROLL_W,
            pt + MAYOR_LIST_TOP, mayorListHeight,
            rows == 0 ? 1.0F
                : Math.min(1.0F, (float) mayorVisibleRows / rows),
            rows <= mayorVisibleRows ? 0.0F
                : (float) mayorScroll / (rows - mayorVisibleRows), false);

        HsUi.divider(graphics, pl + MAYOR_PAD, pt + mayorFoot,
            MAYOR_PANEL_W - 2 * MAYOR_PAD);
        // Word-wrapped, not labelIn -- the "stands X down" sentence can carry
        // the current mayor's full (possibly long) name, and ellipsising a
        // name mid-sentence here reads as a different, shorter sentence
        // rather than a merely-truncated one. See MAYOR_FOOTER_H.
        HsUi.drawLines(graphics, font, mayorRenderModel.footer(),
            pl + MAYOR_PAD, pt + mayorFoot + 7, HsUiTokens.ACCENT);
    }

    private void renderRecruitmentPanel(GuiGraphics graphics) {
        int pl = recruitmentPanelLeft;
        int pt = recruitmentPanelTop;
        HsUi.modalWindow(graphics, pl, pt, RECRUIT_PANEL_W, RECRUIT_PANEL_H);
        HsUi.centred(graphics, font, RECRUIT_CARD_TITLE,
            pl + RECRUIT_PANEL_W / 2, pt + 12, HsUiTokens.TEXT_STRONG);
        HsUi.divider(graphics, pl + RECRUIT_PANEL_PAD, pt + 26,
            RECRUIT_PANEL_W - 2 * RECRUIT_PANEL_PAD);
        if (!cachedRecruitmentCard.present()) {
            return;
        }

        HsUi.labelIn(graphics, font, recruitmentNameLine,
            pl + RECRUIT_PANEL_PAD, pt + 35,
            RECRUIT_PANEL_W - 2 * RECRUIT_PANEL_PAD,
            HsUiTokens.TEXT_STRONG);
        HsUi.labelIn(graphics, font, recruitmentStageLine,
            pl + RECRUIT_PANEL_PAD, pt + 49,
            RECRUIT_PANEL_W - 2 * RECRUIT_PANEL_PAD,
            HsUiTokens.ACCENT);

        int insetX = pl + RECRUIT_PANEL_PAD;
        int insetY = pt + 64;
        int insetW = RECRUIT_PANEL_W - 2 * RECRUIT_PANEL_PAD;
        HsUi.inset(graphics, insetX, insetY, insetW, 119);
        HsUi.labelIn(graphics, font, RECRUIT_CARD_PRICE,
            insetX + 6, insetY + 7, insetW - 12, HsUiTokens.TEXT_MUTED);
        for (int i = 0; i < recruitmentCostLines.size(); i++) {
            HsUi.labelIn(graphics, font, recruitmentCostLines.get(i),
                insetX + 12, insetY + 19 + i * 10, insetW - 18,
                HsUiTokens.TEXT);
        }
        HsUi.labelIn(graphics, font, recruitmentBedsLine,
            insetX + 6, insetY + 64, insetW - 12, HsUiTokens.TEXT_MUTED);
        HsUi.labelIn(graphics, font, recruitmentFoodLine,
            insetX + 6, insetY + 76, insetW - 12, HsUiTokens.TEXT_MUTED);
        HsUi.labelIn(graphics, font, recruitmentTimeLine,
            insetX + 6, insetY + 88, insetW - 12, HsUiTokens.TEXT_MUTED);
        int blockerColour = cachedRecruitmentCard.mayAdmit()
            ? HsUiTokens.GOOD : HsUiTokens.WARN;
        for (int i = 0; i < Math.min(2, recruitmentBlockerLines.size()); i++) {
            graphics.drawString(font, recruitmentBlockerLines.get(i),
                insetX + 6, insetY + 101 + i * 9, blockerColour, false);
        }
    }

    private void renderRequestPanel(GuiGraphics graphics, int mouseX,
                                    int mouseY) {
        int pl = requestPanelLeft;
        int pt = requestPanelTop;
        HsUi.modalWindow(graphics, pl, pt, REQUEST_PANEL_W, REQUEST_PANEL_H);
        HsUi.centred(graphics, font, REQUEST_TITLE,
            pl + REQUEST_PANEL_W / 2, pt + REQUEST_TITLE_Y,
            HsUiTokens.TEXT_STRONG);
        HsUi.divider(graphics, pl + REQUEST_PANEL_PAD,
            pt + REQUEST_DIV1_Y, REQUEST_PANEL_W - 2 * REQUEST_PANEL_PAD);

        Component meta = requestLoading ? REQUEST_LOADING : requestMetaLine;
        HsUi.labelIn(graphics, font, meta, pl + REQUEST_PANEL_PAD,
            pt + REQUEST_META_Y,
            REQUEST_PANEL_W - 2 * REQUEST_PANEL_PAD,
            cachedRequestView.quarantined() || requestUnavailable
                ? HsUiTokens.WARN
                : HsUiTokens.TEXT_MUTED);
        HsUi.divider(graphics, pl + REQUEST_PANEL_PAD,
            pt + REQUEST_DIV2_Y, REQUEST_PANEL_W - 2 * REQUEST_PANEL_PAD);

        if (!requestLoading && !requestUnavailable
            && cachedRequestRows.isEmpty()) {
            HsUi.labelIn(graphics, font, REQUEST_EMPTY,
                pl + REQUEST_PANEL_PAD, pt + REQUEST_LIST_TOP + 8,
                REQUEST_PANEL_W - 2 * REQUEST_PANEL_PAD,
                HsUiTokens.TEXT_MUTED);
        }
        for (int row = 0; row < REQUEST_MAX_ROWS
            && row + requestScroll < cachedRequestRows.size(); row++) {
            RequestRenderRow cached = cachedRequestRows.get(row + requestScroll);
            int y = pt + REQUEST_LIST_TOP + row * REQUEST_CARD_STEP;
            boolean hovered = mouseX >= pl + REQUEST_PANEL_PAD
                && mouseX < pl + REQUEST_PANEL_W - REQUEST_PANEL_PAD
                && mouseY >= y && mouseY < y + REQUEST_CARD_H;
            HsUi.card(graphics, pl + REQUEST_PANEL_PAD, y,
                REQUEST_PANEL_W - 2 * REQUEST_PANEL_PAD
                    - HsUiTokens.SCROLL_W - 3,
                REQUEST_CARD_H, hovered);
            graphics.fill(pl + REQUEST_PANEL_PAD, y,
                pl + REQUEST_PANEL_PAD + 3, y + REQUEST_CARD_H,
                cached.tone());
            int textX = pl + REQUEST_PANEL_PAD + 7;
            int textW = REQUEST_PANEL_W - 2 * REQUEST_PANEL_PAD
                - HsUiTokens.SCROLL_W - 13;
            HsUi.labelIn(graphics, font, cached.headline(), textX, y + 3,
                textW, HsUiTokens.TEXT_STRONG);
            HsUi.labelIn(graphics, font, cached.route(), textX, y + 13,
                textW, HsUiTokens.TEXT_MUTED);
            HsUi.labelIn(graphics, font, cached.assignment(), textX, y + 23,
                textW, HsUiTokens.TEXT_MUTED);
            HsUi.labelIn(graphics, font, cached.stop(), textX, y + 33,
                textW, cached.tone());
        }

        int total = cachedRequestRows.size();
        HsUi.scrollbar(graphics,
            pl + REQUEST_PANEL_W - REQUEST_PANEL_PAD - HsUiTokens.SCROLL_W,
            pt + REQUEST_LIST_TOP,
            REQUEST_MAX_ROWS * REQUEST_CARD_STEP - 4,
            total == 0 ? 1.0F
                : Math.min(1.0F, (float) REQUEST_MAX_ROWS / total),
            total <= REQUEST_MAX_ROWS ? 0.0F
                : (float) requestScroll / (total - REQUEST_MAX_ROWS), false);
        HsUi.divider(graphics, pl + REQUEST_PANEL_PAD,
            pt + REQUEST_FOOT_DIV_Y,
            REQUEST_PANEL_W - 2 * REQUEST_PANEL_PAD);
        HsUi.labelIn(graphics, font,
            requestLoading ? REQUEST_LOADING : requestFooterLine,
            pl + REQUEST_PANEL_PAD, pt + REQUEST_FOOT_Y,
            REQUEST_PANEL_W - 2 * REQUEST_PANEL_PAD,
            HsUiTokens.TEXT_MUTED);
    }

    /**
     * Full cached row text for narrow translations. No Components or Lists
     * are allocated on the render path; the tooltip list was built with the
     * server snapshot and is only painted while an actual card is hovered.
     */
    private void renderRequestRowTooltip(GuiGraphics graphics, int mouseX,
                                         int mouseY) {
        int cardLeft = requestPanelLeft + REQUEST_PANEL_PAD;
        int cardRight = requestPanelLeft + REQUEST_PANEL_W
            - REQUEST_PANEL_PAD - HsUiTokens.SCROLL_W - 3;
        if (mouseX < cardLeft || mouseX >= cardRight
            || mouseY < requestPanelTop + REQUEST_LIST_TOP) {
            return;
        }
        int localY = mouseY - (requestPanelTop + REQUEST_LIST_TOP);
        int visibleRow = localY / REQUEST_CARD_STEP;
        if (visibleRow < 0 || visibleRow >= REQUEST_MAX_ROWS
            || localY % REQUEST_CARD_STEP >= REQUEST_CARD_H) {
            return;
        }
        int index = requestScroll + visibleRow;
        if (index < 0 || index >= cachedRequestRows.size()) {
            return;
        }
        graphics.renderComponentTooltip(font,
            cachedRequestRows.get(index).tooltip(), mouseX, mouseY);
    }

    private void renderReadinessPanel(GuiGraphics graphics, int mouseX,
                                      int mouseY) {
        ensureReadinessRenderModel();
        int pl = journeyPanelLeft;
        int pt = journeyPanelTop;
        HsUi.modalWindow(graphics, pl, pt, JOURNEY_PANEL_W,
            journeyPanelHeight);
        graphics.drawString(font, READINESS_TITLE,
            pl + (JOURNEY_PANEL_W - readinessRenderModel.titleWidth()) / 2,
            pt + JOURNEY_TITLE_Y, HsUiTokens.TEXT_STRONG, true);
        HsUi.divider(graphics, pl + JOURNEY_PAD, pt + JOURNEY_DIV1_Y,
            JOURNEY_PANEL_W - 2 * JOURNEY_PAD);
        graphics.drawString(font, readinessRenderModel.meta().text(),
            pl + JOURNEY_PAD, pt + READINESS_META_Y,
            cachedReadinessView.ready() ? HsUiTokens.GOOD : HsUiTokens.WARN,
            true);
        graphics.drawString(font, readinessRenderModel.metrics().text(),
            pl + JOURNEY_PAD, pt + READINESS_META_Y + 11,
            HsUiTokens.TEXT_MUTED, true);
        HsUi.divider(graphics, pl + JOURNEY_PAD, pt + READINESS_DIV2_Y,
            JOURNEY_PANEL_W - 2 * JOURNEY_PAD);

        int visibleRows = readinessVisibleRows();
        if (cachedReadinessBlockers.isEmpty()) {
            int cardX = pl + JOURNEY_PAD;
            int cardY = pt + READINESS_LIST_TOP;
            int cardW = JOURNEY_PANEL_W - 2 * JOURNEY_PAD;
            HsUi.card(graphics, cardX, cardY, cardW, 48, false);
            graphics.fill(cardX, cardY, cardX + 3, cardY + 48,
                HsUiTokens.GOOD);
            HsUi.drawLines(graphics, font, readinessRenderModel.clear(),
                cardX + 9, cardY + 8, HsUiTokens.TEXT);
        } else {
            for (int row = 0; row < visibleRows
                && row + readinessScroll < cachedReadinessBlockers.size();
                    row++) {
                int y = pt + READINESS_LIST_TOP + row * READINESS_CARD_STEP;
                int x = pl + JOURNEY_PAD;
                int w = JOURNEY_PANEL_W - 2 * JOURNEY_PAD
                    - HsUiTokens.SCROLL_W - 3;
                boolean hovered = mouseX >= x && mouseX <= x + w
                    && mouseY >= y && mouseY <= y + READINESS_CARD_H;
                HsUi.card(graphics, x, y, w, READINESS_CARD_H, hovered);
                graphics.fill(x, y, x + 3, y + READINESS_CARD_H,
                    HsUiTokens.BAD);
                HsUi.drawLines(graphics, font,
                    readinessRenderModel.blockers().get(row + readinessScroll),
                    x + 8, y + 4, HsUiTokens.TEXT);
            }
            int total = cachedReadinessBlockers.size();
            HsUi.scrollbar(graphics,
                pl + JOURNEY_PANEL_W - JOURNEY_PAD - HsUiTokens.SCROLL_W,
                pt + READINESS_LIST_TOP,
                visibleRows * READINESS_CARD_STEP - 4,
                Math.min(1.0F, (float) visibleRows / total),
                total <= visibleRows ? 0.0F
                    : (float) readinessScroll / (total - visibleRows), false);
        }

        HsUi.divider(graphics, pl + JOURNEY_PAD,
            pt + journeyFootDividerY, JOURNEY_PANEL_W - 2 * JOURNEY_PAD);
        graphics.drawString(font, readinessRenderModel.footer().text(),
            pl + JOURNEY_PAD, pt + journeyFootY,
            cachedReadinessView.committed() ? HsUiTokens.GOOD
                : HsUiTokens.TEXT_MUTED, true);
    }

    private void renderAftermathPanel(GuiGraphics graphics) {
        ensureAftermathRenderModel();
        int pl = journeyPanelLeft;
        int pt = journeyPanelTop;
        int cardX = pl + JOURNEY_PAD;
        int cardW = JOURNEY_PANEL_W - 2 * JOURNEY_PAD;
        int outcome = cachedAftermathView.held()
            ? HsUiTokens.GOOD : HsUiTokens.BAD;

        HsUi.modalWindow(graphics, pl, pt, JOURNEY_PANEL_W,
            journeyPanelHeight);
        graphics.drawString(font, AFTERMATH_TITLE,
            pl + (JOURNEY_PANEL_W - aftermathRenderModel.titleWidth()) / 2,
            pt + JOURNEY_TITLE_Y, HsUiTokens.TEXT_STRONG, true);
        HsUi.divider(graphics, cardX, pt + JOURNEY_DIV1_Y, cardW);

        HsUi.card(graphics, cardX, pt + AFTERMATH_HEAD_Y, cardW,
            AFTERMATH_HEAD_H, false);
        graphics.fill(cardX, pt + AFTERMATH_HEAD_Y, cardX + 3,
            pt + AFTERMATH_HEAD_Y + AFTERMATH_HEAD_H, outcome);
        graphics.drawString(font, aftermathRenderModel.status().text(),
            cardX + 9, pt + AFTERMATH_HEAD_Y + 6, outcome, true);
        graphics.drawString(font, aftermathRenderModel.night().text(),
            cardX + cardW - 8 - aftermathRenderModel.night().width(),
            pt + AFTERMATH_HEAD_Y + 6, HsUiTokens.TEXT_MUTED, true);
        graphics.drawString(font, aftermathRenderModel.captain().text(),
            cardX + 9, pt + AFTERMATH_HEAD_Y + 21,
            HsUiTokens.TEXT_STRONG, true);

        HsUi.card(graphics, cardX, pt + AFTERMATH_FACTS_Y, cardW,
            AFTERMATH_FACTS_H, false);
        graphics.drawString(font, aftermathRenderModel.objective().text(),
            cardX + 8, pt + AFTERMATH_FACTS_Y + 7,
            HsUiTokens.ACCENT, true);
        HsUi.drawLines(graphics, font, aftermathRenderModel.impact(),
            cardX + 8, pt + AFTERMATH_FACTS_Y + 23,
            HsUiTokens.TEXT_MUTED);

        HsUi.card(graphics, cardX, pt + AFTERMATH_STATE_Y, cardW,
            AFTERMATH_STATE_H, false);
        graphics.drawString(font, aftermathRenderModel.threat().text(),
            cardX + 8, pt + AFTERMATH_STATE_Y + 7,
            HsUiTokens.TEXT, true);
        HsUi.drawLines(graphics, font, aftermathRenderModel.reward(),
            cardX + 8, pt + AFTERMATH_STATE_Y + 23,
            cachedAftermathView.rewardStatus()
                    == HearthMayorSnapshot.AftermathView.RewardStatus.UNAVAILABLE
                ? HsUiTokens.BAD : HsUiTokens.TEXT_MUTED);

        HsUi.divider(graphics, cardX, pt + journeyFootDividerY, cardW);
        HsUi.drawLines(graphics, font, aftermathRenderModel.road(),
            cardX, pt + journeyFootY, HsUiTokens.TEXT_MUTED);
    }

    private void renderJourneyPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        if (cachedReadinessView.open()) {
            renderReadinessPanel(graphics, mouseX, mouseY);
            return;
        }
        if (shouldShowAftermath()) {
            renderAftermathPanel(graphics);
            return;
        }
        ensureJourneyRenderModel();
        int pl = journeyPanelLeft;
        int pt = journeyPanelTop;
        HsUi.modalWindow(graphics, pl, pt, JOURNEY_PANEL_W,
            journeyPanelHeight);
        graphics.drawString(font, JOURNEY_TITLE,
            pl + (JOURNEY_PANEL_W - journeyRenderModel.titleWidth()) / 2,
            pt + JOURNEY_TITLE_Y, HsUiTokens.TEXT_STRONG, true);
        HsUi.divider(graphics, pl + JOURNEY_PAD, pt + JOURNEY_DIV1_Y,
            JOURNEY_PANEL_W - 2 * JOURNEY_PAD);
        if (journeyRenderModel.chapter().width() > 0) {
            graphics.drawString(font, journeyRenderModel.chapter().text(),
                pl + JOURNEY_PAD, pt + JOURNEY_INTRO_Y,
                HsUiTokens.ACCENT, true);
        }
        graphics.drawString(font, journeyRenderModel.progress().text(),
            pl + JOURNEY_PANEL_W - JOURNEY_PAD
                - journeyRenderModel.progress().width(),
            pt + JOURNEY_INTRO_Y, HsUiTokens.TEXT_MUTED, true);

        int spineX = pl + JOURNEY_PAD + 6;
        int cardX = pl + JOURNEY_PAD + 22;
        int cardW = JOURNEY_PANEL_W - 2 * JOURNEY_PAD - 22;

        int visible = journeyRenderModel.steps().size();
        for (int row = 0; row < visible; row++) {
            JourneyRenderRow step = journeyRenderModel.steps().get(row);
            int y = pt + JOURNEY_STEPS_TOP
                + row * (journeyStepHeight + journeyStepGap);
            boolean current = row == 0;
            if (row == 0 && visible > 1) {
                graphics.fill(spineX + 2, y + 18, spineX + 4,
                    y + journeyStepHeight + journeyStepGap + 2,
                    HsUiTokens.ACCENT);
            }
            HsUi.pips(graphics, spineX, y + 11, current ? 1 : 0, 1,
                HsUi.Tone.ACCENT);

            if (current) {
                int edge = 0x804ECCA3;
                graphics.fill(cardX - 1, y - 1, cardX + cardW + 1, y, edge);
                graphics.fill(cardX - 1, y + journeyStepHeight,
                    cardX + cardW + 1, y + journeyStepHeight + 1, edge);
                graphics.fill(cardX - 1, y, cardX,
                    y + journeyStepHeight, edge);
                graphics.fill(cardX + cardW, y, cardX + cardW + 1,
                    y + journeyStepHeight, edge);
            }
            HsUi.card(graphics, cardX, y, cardW, journeyStepHeight, current);
            int titleColour = current ? HsUiTokens.ACCENT : HsUiTokens.TEXT_MUTED;
            graphics.drawString(font, step.title().text(), cardX + 7,
                y + (journeyStepHeight < JOURNEY_STEP_H ? 3 : 6),
                titleColour, true);
            HsUi.drawLines(graphics, font, step.description(),
                cardX + 7, y + (journeyStepHeight < JOURNEY_STEP_H ? 16 : 21),
                HsUiTokens.TEXT_MUTED);
            graphics.drawString(font, step.state().text(),
                cardX + cardW - 7 - step.state().width(),
                y + (journeyStepHeight < JOURNEY_STEP_H ? 3 : 6),
                titleColour, true);
        }

        HsUi.divider(graphics, pl + JOURNEY_PAD,
            pt + journeyFootDividerY, JOURNEY_PANEL_W - 2 * JOURNEY_PAD);
        graphics.drawString(font, journeyRenderModel.footer().text(),
            pl + JOURNEY_PAD, pt + journeyFootY,
            journeyRenderModel.mode() == JourneyPresentationMode.QUARANTINED
                ? HsUiTokens.BAD : HsUiTokens.TEXT_MUTED, true);
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
        MayorStatusRenderModel model = mayorStatusRenderModel;
        if (model.kind() == 0) {
            return;
        }
        if (model.kind() == 1) {
            graphics.drawString(font, model.first().text(), x + 6, y + 8,
                HsUiTokens.WARN, true);
            graphics.drawString(font, model.second().text(), x + 6, y + 22,
                HsUiTokens.TEXT_MUTED, true);
            return;
        }
        if (model.kind() == 2) {
            graphics.drawString(font, model.first().text(), x + 6, y + 8,
                HsUiTokens.TEXT_STRONG, true);
            graphics.drawString(font, model.second().text(), x + 6, y + 22,
                HsUiTokens.TEXT_MUTED, true);
            return;
        }
        graphics.drawString(font, model.first().text(), x + 6, y + 4,
            HsUiTokens.TEXT_STRONG, true);
        graphics.drawString(font, model.second().text(), x + 6, y + 15,
            model.secondTone(), true);
        graphics.drawString(font, model.third().text(), x + 6, y + 26,
            HsUiTokens.TEXT_MUTED, true);
        graphics.drawString(font, model.fourth().text(), x + 6, y + 37,
            HsUiTokens.GOOD, true);
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

    static long countdownSecond(long gameTime) {
        return Math.max(0L, gameTime) / 20L;
    }

    static boolean needsCountdownRefresh(long cachedSecond, long gameTime) {
        return cachedSecond != countdownSecond(gameTime);
    }

    /** Both ordinary attraction and Call-to-Arms qualification expose progress. */
    static boolean recruitmentProgressVisible(RecruitmentPolicy.Stage stage,
                                               RecruitmentPolicy.Blocker blocker) {
        return blocker == RecruitmentPolicy.Blocker.NONE
            && (stage == RecruitmentPolicy.Stage.ATTRACTION
                || stage == RecruitmentPolicy.Stage.QUALIFYING);
    }

    private String currentLanguage() {
        return minecraft == null ? ""
            : minecraft.getLanguageManager().getSelected();
    }

    /** Stable Mayor copy is measured once per snapshot/locale/layout. */
    private void ensureMayorRenderModels() {
        String language = currentLanguage();
        if (mayorRenderSnapshot != mayorSnapshot
            || !mayorRenderLanguage.equals(language)
            || mayorRenderPanelHeight != mayorPanelHeight
            || mayorRenderVisibleRows != mayorVisibleRows) {
            List<MayorRenderRow> candidates = new ArrayList<>();
            if (mayorSnapshot != null) {
                for (HearthMayorSnapshot.Candidate candidate
                        : mayorSnapshot.candidates()) {
                    candidates.add(new MayorRenderRow(
                        HsUi.fitLabel(font, Component.literal(candidate.name()),
                            MAYOR_NAME_BOX),
                        HsUi.fitLabel(font, Component.translatable(
                                "hearthstead.mayor.would_bring",
                                boonName(candidate.boonKey())),
                            MAYOR_LINE_BOX)));
                }
            }
            mayorRenderModel = new MayorRenderModel(
                font.width(MAYOR_TITLE),
                HsUi.fitLabel(font, MAYOR_CANDIDATES_TITLE,
                    MAYOR_PANEL_W - 2 * MAYOR_PAD),
                HsUi.fitLabel(font, MAYOR_LOADING,
                    MAYOR_PANEL_W - 2 * MAYOR_PAD),
                HsUi.fitLabel(font, MAYOR_CANDIDATES_EMPTY,
                    MAYOR_PANEL_W - 2 * MAYOR_PAD),
                candidates,
                HsUi.fitLines(font, mayorFooter(),
                    MAYOR_PANEL_W - 2 * MAYOR_PAD));
            mayorRenderSnapshot = mayorSnapshot;
            mayorRenderLanguage = language;
            mayorRenderPanelHeight = mayorPanelHeight;
            mayorRenderVisibleRows = mayorVisibleRows;
            mayorStatusSecond = Long.MIN_VALUE;
        }

        long gameTime = currentGameTime();
        long second = countdownSecond(gameTime);
        if (mayorStatusSnapshot == mayorSnapshot
            && mayorStatusLanguage.equals(language)
            && !needsCountdownRefresh(mayorStatusSecond, gameTime)) {
            return;
        }
        mayorStatusSnapshot = mayorSnapshot;
        mayorStatusLanguage = language;
        mayorStatusSecond = second;
        mayorStatusRenderModel = buildMayorStatusRenderModel();
    }

    private MayorStatusRenderModel buildMayorStatusRenderModel() {
        if (mayorSnapshot == null) {
            return MayorStatusRenderModel.empty();
        }
        int box = MAYOR_PANEL_W - 2 * MAYOR_PAD - 12;
        HsUi.FittedLabel empty = fittedEmpty();
        long now = currentGameTime();
        if (mayorSnapshot.mourning()) {
            long remaining = Math.max(0, mayorSnapshot.mourningUntil() - now);
            return new MayorStatusRenderModel(1,
                HsUi.fitLabel(font, Component.translatable(
                    "hearthstead.mayor.mourning.active",
                    formatTicks(remaining)), box),
                HsUi.fitLabel(font, Component.translatable(
                    "hearthstead.mayor.refused.mourning"), box),
                empty, empty, HsUiTokens.TEXT_MUTED);
        }
        if (!mayorSnapshot.hasMayor()) {
            return new MayorStatusRenderModel(2,
                HsUi.fitLabel(font, Component.translatable(
                    "hearthstead.mayor.vacant"), box),
                HsUi.fitLabel(font, Component.translatable(
                    "hearthstead.mayor.vacant.hint"), box),
                empty, empty, HsUiTokens.TEXT_MUTED);
        }
        long settlingRemaining = Math.max(0,
            mayorSnapshot.mayorSince() + Mayor.SETTLING_TICKS - now);
        boolean settling = settlingRemaining > 0;
        Component boonLine = Component.translatable(settling
                ? "hearthstead.mayor.brings_pending"
                : "hearthstead.mayor.brings_now",
            boonName(mayorSnapshot.boonKey()));
        return new MayorStatusRenderModel(3,
            HsUi.fitLabel(font, Component.literal(mayorSnapshot.mayorName()), box),
            HsUi.fitLabel(font, boonLine, box),
            HsUi.fitLabel(font, boonDesc(mayorSnapshot.boonKey()), box),
            HsUi.fitLabel(font, Component.translatable(
                "hearthstead.mayor.emblems.hint"), box),
            settling ? HsUiTokens.TEXT_MUTED : HsUiTokens.ACCENT);
    }

    /** Readiness lists are split once per authoritative generation. */
    private void ensureReadinessRenderModel() {
        String language = currentLanguage();
        if (readinessRenderSource == cachedReadinessView
            && readinessRenderLanguage.equals(language)
            && readinessRenderPanelHeight == journeyPanelHeight
            && readinessRenderLoading == readinessLoading
            && readinessRenderPending == readinessCommitPending) {
            return;
        }
        int contentWidth = JOURNEY_PANEL_W - 2 * JOURNEY_PAD;
        int cardWidth = contentWidth;
        List<List<FormattedCharSequence>> blockers = new ArrayList<>(
            cachedReadinessBlockers.size());
        for (Component blocker : cachedReadinessBlockers) {
            blockers.add(HsUi.fitLines(font, blocker,
                cardWidth - HsUiTokens.SCROLL_W - 17));
        }
        Component clear = Component.translatable(cachedReadinessView.committed()
            ? "hearthstead.raid.readiness.receipt"
            : "hearthstead.raid.readiness.all_clear");
        Component footer = cachedReadinessView.committed()
            ? Component.translatable(
                "hearthstead.raid.readiness.footer.committed")
            : readinessCommitPending
                ? Component.translatable(
                    "hearthstead.raid.readiness.footer.committing")
                : Component.translatable("hearthstead.raid.readiness.footer");
        readinessRenderModel = new ReadinessRenderModel(
            font.width(READINESS_TITLE),
            HsUi.fitLabel(font, readinessLoading
                    ? READINESS_LOADING : readinessMetaLine,
                contentWidth),
            HsUi.fitLabel(font, readinessMetricsLine, contentWidth),
            blockers,
            HsUi.fitLines(font, clear, cardWidth - 17),
            HsUi.fitLabel(font, footer, contentWidth));
        readinessRenderSource = cachedReadinessView;
        readinessRenderLanguage = language;
        readinessRenderPanelHeight = journeyPanelHeight;
        readinessRenderLoading = readinessLoading;
        readinessRenderPending = readinessCommitPending;
    }

    /** Immutable aftermath copy shares the Journey panel's render cache. */
    private void ensureAftermathRenderModel() {
        String language = currentLanguage();
        if (aftermathRenderSource == cachedAftermathView
            && aftermathRenderLanguage.equals(language)
            && aftermathRenderPanelHeight == journeyPanelHeight) {
            return;
        }
        int cardWidth = JOURNEY_PANEL_W - 2 * JOURNEY_PAD;
        Component road = Component.translatable(
            "hearthstead.raid.aftermath.road.label", aftermathRoadLine);
        aftermathRenderModel = new AftermathRenderModel(
            font.width(AFTERMATH_TITLE),
            HsUi.fitLabel(font, aftermathStatusLine, cardWidth - 90),
            HsUi.fitLabel(font, aftermathNightLine, Integer.MAX_VALUE),
            HsUi.fitLabel(font, aftermathCaptainLine, cardWidth - 17),
            HsUi.fitLabel(font, aftermathObjectiveLine, cardWidth - 16),
            HsUi.fitLines(font, aftermathImpactLine, cardWidth - 16),
            HsUi.fitLabel(font, aftermathThreatLine, cardWidth - 16),
            HsUi.fitLines(font, aftermathRewardLine, cardWidth - 16),
            HsUi.fitLines(font, road, cardWidth));
        aftermathRenderSource = cachedAftermathView;
        aftermathRenderLanguage = language;
        aftermathRenderPanelHeight = journeyPanelHeight;
    }

    /** Current + next Journey rows are rebuilt only when synced values move. */
    private void ensureJourneyRenderModel() {
        String language = currentLanguage();
        int modeWire = menu.get(HearthMenu.DATA_JOURNEY_V3_MODE);
        int completed = Mth.clamp(menu.get(HearthMenu.DATA_JOURNEY_V3_COMPLETED),
            0, JourneyDefinition.CURRENT.orderedSteps().size());
        int chapterIndex = menu.get(HearthMenu.DATA_JOURNEY_V3_CHAPTER);
        int currentOrdinal = menu.get(HearthMenu.DATA_JOURNEY_V3_CURRENT);
        int outcomeWire = menu.get(HearthMenu.DATA_JOURNEY_V3_OUTCOME);
        if (journeyRenderLanguage.equals(language)
            && journeyRenderMode == modeWire
            && journeyRenderCompleted == completed
            && journeyRenderChapter == chapterIndex
            && journeyRenderCurrent == currentOrdinal
            && journeyRenderOutcome == outcomeWire
            && journeyRenderPanelHeight == journeyPanelHeight
            && journeyRenderStepHeight == journeyStepHeight
            && journeyRenderReadinessLoading == readinessLoading
            && journeyRenderSkipPending == journeySkipPending
            && journeyRenderSkipConfirm == journeySkipConfirm) {
            return;
        }

        JourneyPresentationMode mode = JourneyPresentationMode
            .tryFromWireId(modeWire).orElse(JourneyPresentationMode.QUARANTINED);
        HsUi.FittedLabel chapter = fittedEmpty();
        if (chapterIndex >= 0
            && chapterIndex < JourneyDefinition.CURRENT.chapters().size()) {
            ResourceLocation chapterId = JourneyDefinition.CURRENT.chapters()
                .get(chapterIndex);
            String path = chapterId.getPath();
            String leaf = path.substring(path.lastIndexOf('/') + 1);
            chapter = HsUi.fitLabel(font, Component.translatable(
                    "journey.hearthstead.chapter." + leaf + ".title"),
                JOURNEY_PANEL_W - 2 * JOURNEY_PAD - 64);
        }
        HsUi.FittedLabel progress = HsUi.fitLabel(font,
            Component.translatable("journey.hearthstead.progress", completed,
                JourneyDefinition.CURRENT.orderedSteps().size()), Integer.MAX_VALUE);
        int cardWidth = JOURNEY_PANEL_W - 2 * JOURNEY_PAD - 22;
        JourneyStep currentStep = mode == JourneyPresentationMode.ACTIVE
            && currentOrdinal >= 0
            && currentOrdinal < JourneyDefinition.CURRENT.orderedSteps().size()
            ? JourneyDefinition.CURRENT.stepAt(currentOrdinal) : null;
        List<JourneyStep> visibleSteps = new ArrayList<>(2);
        if (currentStep != null) {
            visibleSteps.add(currentStep);
            JourneyDefinition.CURRENT.nextAfter(currentStep.id())
                .ifPresent(visibleSteps::add);
        }
        List<JourneyRenderRow> rows = new ArrayList<>(visibleSteps.size());
        for (int row = 0; row < visibleSteps.size(); row++) {
            JourneyStep step = visibleSteps.get(row);
            rows.add(new JourneyRenderRow(
                HsUi.fitLabel(font, Component.translatable(step.titleKey()),
                    cardWidth - 68),
                HsUi.fitLines(font,
                    Component.translatable(step.descriptionKey()),
                    cardWidth - 14),
                HsUi.fitLabel(font, Component.translatable(row == 0
                        ? "hearthstead.journey.state.current"
                        : "hearthstead.journey.state.next"),
                    Integer.MAX_VALUE)));
        }
        Component footer;
        if (readinessLoading) {
            footer = READINESS_LOADING;
        } else if (journeySkipPending) {
            footer = Component.translatable("hearthstead.journey.skip.pending");
        } else if (journeySkipConfirm) {
            footer = Component.translatable("hearthstead.journey.skip.warning");
        } else {
            JourneyOutcome outcome = JourneyOutcome.tryFromWireId(outcomeWire)
                .orElse(JourneyOutcome.NONE);
            footer = switch (mode) {
                case COMPLETE -> Component.translatable(
                    "journey.hearthstead.complete." + outcome.id());
                case SKIPPED -> Component.translatable(
                    "hearthstead.journey.skipped");
                case QUARANTINED -> Component.translatable(
                    "hearthstead.journey.unavailable");
                case ACTIVE -> Component.translatable(
                    "journey.hearthstead.active_bounded");
            };
        }
        journeyRenderModel = new JourneyRenderModel(
            font.width(JOURNEY_TITLE), chapter, progress, rows,
            HsUi.fitLabel(font, footer,
                JOURNEY_PANEL_W - 2 * JOURNEY_PAD), mode);
        journeyRenderLanguage = language;
        journeyRenderMode = modeWire;
        journeyRenderCompleted = completed;
        journeyRenderChapter = chapterIndex;
        journeyRenderCurrent = currentOrdinal;
        journeyRenderOutcome = outcomeWire;
        journeyRenderPanelHeight = journeyPanelHeight;
        journeyRenderStepHeight = journeyStepHeight;
        journeyRenderReadinessLoading = readinessLoading;
        journeyRenderSkipPending = journeySkipPending;
        journeyRenderSkipConfirm = journeySkipConfirm;
    }

    private void updateStatRenderCache(int population, int capacity,
                                       int employed, int food, int radius,
                                       int morale) {
        String language = currentLanguage();
        boolean languageChanged = !cachedStatsLanguage.equals(language);
        cachedStatsLanguage = language;
        boolean populationChanged = population != cachedStatsPopulation;
        if (populationChanged
            || capacity != cachedStatsCapacity) {
            cachedStatsPopulation = population;
            cachedStatsCapacity = capacity;
            cachedPopulationStat = population + " / " + capacity;
        }
        if (employed != cachedStatsEmployed
            || populationChanged) {
            cachedStatsEmployed = employed;
            cachedEmploymentStat = employed + " / " + population;
        }
        if (food != cachedStatsFood) {
            cachedStatsFood = food;
            cachedFoodStat = String.valueOf(food);
        }
        if (radius != cachedStatsRadius) {
            cachedStatsRadius = radius;
            cachedRadiusStat = radius + " m";
        }
        int band = morale < 25 ? 0 : morale < 50 ? 1 : morale < 75 ? 2 : 3;
        if (band != cachedStatsMoraleBand || languageChanged) {
            cachedStatsMoraleBand = band;
            cachedMoraleBand = HsUi.fitLabel(font, moraleBand(morale), STAT_W);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // Header: settlement name, centered in the title band.
        String name = menu.getSettlementName();
        String stableName = name == null ? "" : name;
        String language = currentLanguage();
        if (!stableName.equals(cachedSettlementName)
            || !language.equals(cachedSettlementLanguage)) {
            cachedSettlementName = stableName;
            cachedSettlementLanguage = language;
            Component fullHeader = stableName.isEmpty()
                ? Component.translatable("container.hearthstead.hearth")
                : Component.literal(stableName);
            int maxHeaderWidth = imageWidth - 24;
            cachedSettlementHeader = font.width(fullHeader) <= maxHeaderWidth
                ? fullHeader
                : Component.literal(font.plainSubstrByWidth(
                    fullHeader.getString(), maxHeaderWidth - font.width("..."))
                    + "...");
            cachedSettlementHeaderWidth = font.width(cachedSettlementHeader);
        }
        graphics.drawString(font, cachedSettlementHeader,
            12, 8, HsUiTokens.TEXT_STRONG, true);
        HsUi.right(graphics, font, COMMAND_CENTER_LABEL,
            imageWidth - 12, 8, HsUiTokens.ACCENT);

        graphics.drawString(font, STORES_LABEL,
            HearthMenu.COMMUNAL_X + 1, 20, HsUiTokens.TEXT_STRONG, false);
        graphics.drawString(font, playerInventoryTitle,
            inventoryLabelX, inventoryLabelY, HsUiTokens.TEXT_STRONG, false);

        // Stat rows.
        int pop = menu.get(HearthMenu.DATA_POPULATION);
        int cap = menu.get(HearthMenu.DATA_CAPACITY);
        int employed = menu.get(HearthMenu.DATA_EMPLOYED);
        int food = menu.get(HearthMenu.DATA_FOOD);
        int radius = menu.get(HearthMenu.DATA_RADIUS);
        int morale = Mth.clamp(menu.get(HearthMenu.DATA_MORALE), 0, 100);
        updateStatRenderCache(pop, cap, employed, food, radius, morale);
        int fillColor = moraleColor(morale);
        if (imageWidth >= 400) {
            int pulseX = 248;
            int pulseW = imageWidth - pulseX - 16;
            int columnGap = 4;
            int columnW = (pulseW - columnGap) / 2;
            graphics.drawString(font, SETTLEMENT_PULSE_LABEL, pulseX, 40,
                HsUiTokens.TEXT_STRONG, true);
            drawStatCard(graphics, pulseX, 53, columnW, POPULATION_LABEL,
                cachedPopulationStat, HsUi.Tone.ACCENT);
            drawStatCard(graphics, pulseX + columnW + columnGap, 53, columnW,
                EMPLOYED_LABEL, cachedEmploymentStat, HsUi.Tone.GOOD);
            drawStatCard(graphics, pulseX, 83, columnW, FOOD_LABEL,
                cachedFoodStat, food > 0 ? HsUi.Tone.GOOD : HsUi.Tone.BAD);
            drawStatCard(graphics, pulseX + columnW + columnGap, 83, columnW,
                RADIUS_LABEL, cachedRadiusStat, HsUi.Tone.ACCENT);
            HsUi.card(graphics, pulseX, 113, pulseW, 33, false);
            graphics.drawString(font, MORALE_LABEL, pulseX + 6, 119,
                HsUiTokens.TEXT, false);
            HsUi.right(graphics, font, Component.literal(morale + " / 100"),
                pulseX + pulseW - 6, 119, fillColor);
            HsUi.bar(graphics, pulseX + 6, 133, pulseW - 12, 6,
                morale / 100.0F, HsUi.Tone.of(morale / 100.0F));
            HsUi.card(graphics, pulseX, 152, pulseW, 42, false);
            graphics.drawString(font,
                menu.get(HearthMenu.DATA_ALERT) == 1 ? ALERT_LABEL
                    : Component.translatable("hearthstead.gui.settlement_status"),
                pulseX + 6, 158,
                menu.get(HearthMenu.DATA_ALERT) == 1
                    ? HsUiTokens.BAD : HsUiTokens.GOOD, false);
            HsUi.labelIn(graphics, font,
                menu.get(HearthMenu.DATA_ALERT) == 1
                    ? Component.translatable("hearthstead.gui.settlement_status.alert")
                    : Component.translatable("hearthstead.gui.settlement_status.stable"),
                pulseX + 6, 174, pulseW - 12, HsUiTokens.TEXT_MUTED);
        } else {
            drawStat(graphics, 0, POPULATION_LABEL, cachedPopulationStat);
            drawStat(graphics, 1, EMPLOYED_LABEL, cachedEmploymentStat);
            drawStat(graphics, 2, FOOD_LABEL, cachedFoodStat);
            drawStat(graphics, 3, RADIUS_LABEL, cachedRadiusStat);
            graphics.drawString(font, MORALE_LABEL,
                STAT_X, BAR_Y - 10, HsUiTokens.TEXT, false);
            HsUi.bar(graphics, BAR_X, BAR_Y, BAR_W, BAR_H,
                morale / 100.0F, HsUi.Tone.of(morale / 100.0F));
            graphics.drawString(font, cachedMoraleBand.text(),
                STAT_X + STAT_W - cachedMoraleBand.width(),
                BAR_Y - 10, fillColor, false);
        }

        // Alert/recruitment has a dedicated bounded card across the full
        // ledger. The wrapped lines are cached until server data changes.
        HsUi.card(graphics, RECRUIT_X, RECRUIT_Y, RECRUIT_W,
            RECRUIT_H, false);
        graphics.fill(RECRUIT_X, RECRUIT_Y, RECRUIT_X + 2,
            RECRUIT_Y + RECRUIT_H, fillColor);
        if (menu.get(HearthMenu.DATA_ALERT) == 1) {
            // A stable urgent colour is easier to read and cheaper to render
            // than the old 400 ms flash. The flash also made the nearby stat
            // icons appear to pulse whenever the alert card was visible.
            graphics.drawString(font, ALERT_LABEL,
                RECRUIT_X + 6, RECRUIT_Y + 6, 0xFFA03030, false);
        } else {
            int recruit = menu.get(HearthMenu.DATA_RECRUIT);
            RecruitmentPolicy.Blocker blocker = RecruitmentPolicy.Blocker.fromWireId(
                menu.get(HearthMenu.DATA_RECRUIT_BLOCKER));
            updateRecruitLines(blocker, pop, cap, morale, recruit);
            graphics.drawString(font, recruitLine1, RECRUIT_X + 6,
                RECRUIT_Y + 2, INK_SOFT, false);
            graphics.drawString(font, recruitLine2, RECRUIT_X + 6,
                RECRUIT_Y + 11, INK_SOFT, false);
            RecruitmentPolicy.Stage stage = RecruitmentPolicy.Stage.fromWireId(
                menu.get(HearthMenu.DATA_RECRUIT_STAGE));
            if (recruitmentProgressVisible(stage, blocker)) {
                int progressX = RECRUIT_X + 6;
                int progressW = RECRUIT_W - 12;
                graphics.fill(progressX, RECRUIT_Y + RECRUIT_H - 4,
                    progressX + progressW, RECRUIT_Y + RECRUIT_H - 1,
                    0xFF54432F);
                graphics.fill(progressX, RECRUIT_Y + RECRUIT_H - 4,
                    progressX + recruit * progressW / 100,
                    RECRUIT_Y + RECRUIT_H - 1, 0xFFC9A83C);
            }
        }
    }

    private void updateRecruitLines(RecruitmentPolicy.Blocker blocker,
                                    int population, int capacity, int morale,
                                    int recruit) {
        RecruitmentPolicy.Stage stage = RecruitmentPolicy.Stage.fromWireId(
            menu.get(HearthMenu.DATA_RECRUIT_STAGE));
        int readyFood = menu.get(HearthMenu.DATA_READY_AFTER_PRICE);
        int requiredFood = menu.get(HearthMenu.DATA_REQUIRED_RESERVE);
        boolean candidatePresent = cachedRecruitmentCard.present();
        if (blocker == cachedRecruitBlocker
            && stage == cachedRecruitStage
            && population == cachedRecruitPopulation
            && capacity == cachedRecruitCapacity
            && morale == cachedRecruitMorale
            && recruit == cachedRecruitProgress
            && readyFood == cachedRecruitReadyFood
            && requiredFood == cachedRecruitRequiredFood
            && candidatePresent == cachedRecruitCandidatePresent) {
            return;
        }
        cachedRecruitBlocker = blocker;
        cachedRecruitStage = stage;
        cachedRecruitPopulation = population;
        cachedRecruitCapacity = capacity;
        cachedRecruitMorale = morale;
        cachedRecruitProgress = recruit;
        cachedRecruitReadyFood = readyFood;
        cachedRecruitRequiredFood = requiredFood;
        cachedRecruitCandidatePresent = candidatePresent;
        List<FormattedCharSequence> lines = font.split(
            recruitStatus(blocker, population, capacity, morale),
            RECRUIT_W - 12 - (candidatePresent ? RECRUIT_REVIEW_W + 4 : 0));
        recruitLine1 = lines.isEmpty()
            ? FormattedCharSequence.EMPTY : lines.get(0);
        recruitLine2 = lines.size() < 2
            ? FormattedCharSequence.EMPTY : lines.get(1);
    }

    /** Formats only the server-selected blocker; no client-side gate logic. */
    private Component recruitStatus(RecruitmentPolicy.Blocker blocker,
                                    int population, int capacity, int morale) {
        RecruitmentPolicy.Stage stage = RecruitmentPolicy.Stage.fromWireId(
            menu.get(HearthMenu.DATA_RECRUIT_STAGE));
        return switch (blocker) {
            case NONE -> stage == RecruitmentPolicy.Stage.WAITING_ADMISSION
                ? Component.translatable("hearthstead.gui.recruit_waiting.ready")
                : menu.get(HearthMenu.DATA_RECRUIT) > 0
                    ? Component.translatable("hearthstead.gui.recruit_progress")
                    : Component.translatable("hearthstead.gui.recruit_ready");
            case NO_HEARTH -> Component.translatable(
                "hearthstead.gui.recruit_blocked.hearth");
            case NO_TAVERN -> Component.translatable(
                "hearthstead.gui.recruit_blocked.tavern");
            case NO_BED -> Component.translatable(
                "hearthstead.gui.recruit_blocked.beds", population, capacity);
            case LOW_MORALE -> Component.translatable(
                "hearthstead.gui.recruit_blocked.morale", morale, 60);
            case CANNOT_PAY -> Component.translatable(
                "hearthstead.gui.recruit_blocked.price");
            case INSUFFICIENT_READY_FOOD -> Component.translatable(
                "hearthstead.gui.recruit_blocked.reserve",
                menu.get(HearthMenu.DATA_READY_AFTER_PRICE),
                menu.get(HearthMenu.DATA_REQUIRED_RESERVE));
            case INVALID_STATE -> Component.translatable(
                "hearthstead.gui.recruit_blocked.invalid");
        };
    }

    /** Compact fallback used when the command center contracts below 400px. */
    private void drawStat(GuiGraphics graphics, int row, Component label,
                          String value) {
        int y = STAT_Y + row * STAT_ROW_H;
        graphics.drawString(font, label, STAT_X, y, HsUiTokens.TEXT_MUTED, false);
        HsUi.right(graphics, font, Component.literal(value),
            STAT_X + STAT_W, y, HsUiTokens.TEXT_STRONG);
    }

    private void drawStatCard(GuiGraphics graphics, int x, int y, int width,
                              Component label, String value, HsUi.Tone tone) {
        HsUi.card(graphics, x, y, width, 26, false);
        HsUi.labelIn(graphics, font, label, x + 6, y + 4,
            width - 12, HsUiTokens.TEXT_MUTED);
        HsUi.right(graphics, font, Component.literal(value),
            x + width - 6, y + 14, tone.colour());
    }

    private static int moraleColor(int morale) {
        if (morale < 25) {
            return 0xFFA03535;
        }
        if (morale < 50) {
            return 0xFFC07A35;
        }
        if (morale < 75) {
            return 0xFFC9A83C;
        }
        return 0xFF5B8A4A;
    }

    private static Component moraleBand(int morale) {
        String key = morale < 25 ? "miserable" : morale < 50 ? "uneasy"
            : morale < 75 ? "content" : "joyful";
        return Component.translatable("hearthstead.morale." + key);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (recruitmentPanelOpen || mayorTabOpen || requestPanelOpen
            || journeyTabOpen) {
            // At small GUI widths there is no room beside the window and
            // panel positioning clamps the popout ONTO it. Dim what it
            // covers first, so the overlap reads as a modal layer above the
            // window rather than two screens fighting for the same pixels.
            int panelLeft = recruitmentPanelOpen ? recruitmentPanelLeft
                : mayorTabOpen ? mayorPanelLeft
                : requestPanelOpen ? requestPanelLeft : journeyPanelLeft;
            int panelWidth = recruitmentPanelOpen ? RECRUIT_PANEL_W
                : mayorTabOpen ? MAYOR_PANEL_W
                : requestPanelOpen ? REQUEST_PANEL_W : JOURNEY_PANEL_W;
            if (panelLeft < leftPos + imageWidth
                && panelLeft + panelWidth > leftPos) {
                graphics.fill(leftPos, topPos, leftPos + imageWidth,
                    topPos + imageHeight, 0xB0101010);
            }
            if (recruitmentPanelOpen) {
                renderRecruitmentPanel(graphics);
            } else if (mayorTabOpen) {
                renderMayorPanel(graphics, mouseX, mouseY);
            } else if (requestPanelOpen) {
                renderRequestPanel(graphics, mouseX, mouseY);
            } else {
                renderJourneyPanel(graphics, mouseX, mouseY);
            }
            // Panel controls are input/narration children but deliberately not
            // early renderables; draw them exactly once over the modal.
            for (AbstractButton widget : latePanelWidgets) {
                widget.render(graphics, mouseX, mouseY, partialTick);
            }
            if (requestPanelOpen) {
                renderRequestRowTooltip(graphics, mouseX, mouseY);
            }
        }
        // No slot/stat tooltips from under the panel: the slot is covered,
        // so a tooltip for it would name something the player cannot see.
        if (!isOverOpenPanel(mouseX, mouseY)) {
            renderTooltip(graphics, mouseX, mouseY);
            renderStatTooltips(graphics, mouseX, mouseY);
        }
    }

    private void renderStatTooltips(GuiGraphics graphics, int mouseX, int mouseY) {
        int localX = mouseX - leftPos;
        int localY = mouseY - topPos;
        if (imageWidth >= 400) {
            int pulseX = 248;
            int pulseW = imageWidth - pulseX - 16;
            int columnW = (pulseW - 4) / 2;
            if (localY >= 53 && localY < 109
                && localX >= pulseX && localX < pulseX + pulseW) {
                int row = localY < 79 ? 0 : 1;
                int column = localX < pulseX + columnW + 2 ? 0 : 1;
                String key = switch (row * 2 + column) {
                    case 0 -> "population";
                    case 1 -> "employed";
                    case 2 -> "food";
                    default -> "radius";
                };
                graphics.renderComponentTooltip(font, List.of(
                    Component.translatable("hearthstead.gui.tooltip." + key),
                    Component.translatable("hearthstead.gui.tooltip." + key + ".desc")
                        .withStyle(net.minecraft.ChatFormatting.GRAY)), mouseX, mouseY);
                return;
            }
            if (localX >= pulseX && localX < pulseX + pulseW
                && localY >= 113 && localY < 146) {
                graphics.renderComponentTooltip(font, List.of(
                    Component.translatable("hearthstead.gui.tooltip.morale"),
                    Component.translatable("hearthstead.gui.tooltip.morale.desc")
                        .withStyle(net.minecraft.ChatFormatting.GRAY)), mouseX, mouseY);
                return;
            }
        }
        if (localX >= STAT_X && localX < STAT_X + STAT_W) {
            int row = (localY - STAT_Y) / STAT_ROW_H;
            if (localY >= STAT_Y && row >= 0 && row < 4
                && localY < STAT_Y + 4 * STAT_ROW_H) {
                List<Component> lines = new ArrayList<>(2);
                String key = switch (row) {
                    case 0 -> "population";
                    case 1 -> "employed";
                    case 2 -> "food";
                    default -> "radius";
                };
                lines.add(Component.translatable("hearthstead.gui.tooltip." + key));
                lines.add(Component.translatable("hearthstead.gui.tooltip." + key + ".desc")
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
                graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
                return;
            } else if (localY >= BAR_Y - 10 && localY < BAR_Y + BAR_H + 2) {
                List<Component> lines = new ArrayList<>(2);
                lines.add(Component.translatable("hearthstead.gui.tooltip.morale"));
                lines.add(Component.translatable("hearthstead.gui.tooltip.morale.desc")
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
                graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
                return;
            }
        }
        if (menu.get(HearthMenu.DATA_ALERT) == 1
            || localX < RECRUIT_X || localX >= RECRUIT_X + RECRUIT_W
            || localY < RECRUIT_Y || localY >= RECRUIT_Y + RECRUIT_H) {
            return;
        }

        List<Component> lines = new ArrayList<>(5);
        RecruitmentPolicy.Stage stage = RecruitmentPolicy.Stage.fromWireId(
            menu.get(HearthMenu.DATA_RECRUIT_STAGE));
        RecruitmentPolicy.Blocker blocker = RecruitmentPolicy.Blocker.fromWireId(
            menu.get(HearthMenu.DATA_RECRUIT_BLOCKER));
        String stageKey = switch (stage) {
            case ATTRACTION -> "hearthstead.gui.tooltip.recruit.stage.attraction";
            case QUALIFYING -> "hearthstead.gui.tooltip.recruit.stage.qualifying";
            case TRAVELING -> "hearthstead.gui.tooltip.recruit.stage.traveling";
            case WAITING_ADMISSION -> "hearthstead.gui.tooltip.recruit.stage.waiting";
            case INVALID -> "hearthstead.gui.tooltip.recruit.stage.invalid";
        };
        lines.add(Component.translatable(stageKey));
        lines.add(recruitStatus(blocker,
            menu.get(HearthMenu.DATA_POPULATION),
            menu.get(HearthMenu.DATA_CAPACITY),
            menu.get(HearthMenu.DATA_MORALE)).copy()
            .withStyle(net.minecraft.ChatFormatting.GRAY));
        lines.add(Component.translatable("hearthstead.gui.tooltip.recruit.reserve",
            menu.get(HearthMenu.DATA_READY_AFTER_PRICE),
            menu.get(HearthMenu.DATA_REQUIRED_RESERVE))
            .withStyle(net.minecraft.ChatFormatting.GRAY));
        int missing = menu.get(HearthMenu.DATA_MISSING_RESERVE);
        if (missing > 0) {
            lines.add(Component.translatable(
                "hearthstead.gui.tooltip.recruit.missing", missing)
                .withStyle(net.minecraft.ChatFormatting.RED));
        }
        if (recruitmentProgressVisible(stage, blocker)) {
            lines.add(Component.translatable("hearthstead.gui.tooltip.recruit",
                menu.get(HearthMenu.DATA_RECRUIT)));
        }
        graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
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
