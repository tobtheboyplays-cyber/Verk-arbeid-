package com.hearthstead.client.screen;

import com.hearthstead.client.QaClientObserver;
import com.hearthstead.client.QaUiInspectable;
import com.hearthstead.client.ui.HsButton;
import com.hearthstead.client.ui.HsUi;
import com.hearthstead.client.ui.HsUiTokens;
import com.hearthstead.client.ui.HearthLayout;
import com.hearthstead.client.ui.HearthPixelSurface;
import com.hearthstead.menu.HearthMenu;
import com.hearthstead.network.HearthMayorAction;
import com.hearthstead.network.HearthMayorSnapshot;
import com.hearthstead.network.StorageRequestPayload;
import com.hearthstead.registry.ModItems;
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
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.hearthstead.client.ui.HearthMaterials;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** A Minecraft council console: chapter rail, colony overview and real supply slots.
 * Popouts retain server snapshot identity and consume clicks over covered slots.
 */
public class HearthScreen extends AbstractContainerScreen<HearthMenu>
        implements QaUiInspectable {
    private static final int HEADER_TITLE_X = 34;
    private final ItemStack headerHearthItem;
    private final ItemStack[] overviewIcons = {
        new ItemStack(Items.PLAYER_HEAD), new ItemStack(Items.WOODEN_AXE),
        new ItemStack(Items.BREAD), new ItemStack(Items.YELLOW_BED),
        new ItemStack(Items.BELL)
    };
    private static final int INK = HsUiTokens.TEXT;
    private static final int INK_SOFT = HsUiTokens.TEXT_MUTED;
    private static final int SURFACE_INK = HearthPixelSurface.INK;
    private static final int SURFACE_MUTED = HearthPixelSurface.MUTED;
    private static final Component COUNCIL_LEDGER = Component.translatable("hearthstead.gui.council_ledger");
    private static final Component HOUSING_LABEL = Component.translatable("hearthstead.gui.housing");
    private boolean suppliesOpen = false;
    private HearthSupplyCategory supplyCategory = HearthSupplyCategory.ALL;
    private final List<ItemStack> laidOutSupplies = new ArrayList<>();
    private HearthLayout councilLayout = HearthLayout.forImage(512, 274);
    private HsUi.FittedLabel councilLedgerLabel = fittedEmpty();
    private HsUi.FittedLabel storesLabel = fittedEmpty();
    private HsUi.FittedLabel inventoryLabel = fittedEmpty();
    private HsUi.FittedLabel housingLabel = fittedEmpty();
    private HsUi.FittedLabel moraleLabel = fittedEmpty();
    private HsUi.FittedLabel housingValue = fittedEmpty();
    private HsUi.FittedLabel populationValue = fittedEmpty();

    // -- Mayor roster ----------------------------------------------------
    // The old 256px scroll column hid profession and converted the one
    // player-facing attribute into five vague pips. The roster is now a
    // bounded dossier: three complete rows, an exact page counter, and one
    // explicit Appoint action per person. At 427x240 it is the approved
    // 411x224 composition; at 320px it contracts to 304px without changing
    // the information hierarchy.
    private static final int MAYOR_PANEL_MAX_W = 411;
    private static final int MAYOR_PANEL_MIN_W = 240;
    private static final int MAYOR_PAD = HsUiTokens.PAD;
    private static final int MAYOR_GAP = 6;
    private static final int MAYOR_TITLE_Y = 8;
    private static final int MAYOR_RULE_Y = 42;
    private static final int MAYOR_STATUS_Y = 6;
    private static final int MAYOR_STATUS_W = 135;
    private static final int MAYOR_STATUS_H = 32;
    private static final int MAYOR_LABEL_Y = 48;
    private static final int MAYOR_LIST_TOP = 64;
    private static final int MAYOR_MAX_ROWS = 3;
    private static final int MAYOR_CARD_H = 38;
    private static final int MAYOR_CARD_STEP = MAYOR_CARD_H + 4;
    private static final int MAYOR_CARD_X = MAYOR_PAD;
    private static final int MAYOR_AVATAR_X = MAYOR_CARD_X + 6;
    private static final int MAYOR_AVATAR_SIZE = 24;
    private static final int MAYOR_TEXT_X = MAYOR_AVATAR_X + MAYOR_AVATAR_SIZE + 6;
    private static final int MAYOR_ROW_NAME_WIDE = 104;
    private static final int MAYOR_ROW_NAME_NARROW = 96;
    private static final int MAYOR_NAV_GAP = 4;
    private static final int MAYOR_NAV_W = 60;
    private static final int MAYOR_CLOSE_W = 61;
    private static final int MAYOR_FOOTER_GAP_TOP = 6;
    private static final int MAYOR_PANEL_BOTTOM_PAD = 8;
    private static final Component MAYOR_TITLE = Component.translatable(
        "hearthstead.mayor.tab.title");
    private static final Component MAYOR_CHOICE_RULE = Component.translatable(
        "hearthstead.mayor.choice_rule");
    private static final Component MAYOR_CANDIDATES_TITLE = Component.translatable(
        "hearthstead.mayor.roster.title");
    private static final Component MAYOR_LOADING = Component.translatable(
        "hearthstead.mayor.loading");
    private static final Component MAYOR_CANDIDATES_EMPTY = Component.translatable(
        "hearthstead.mayor.candidates.empty");

    // -- Founding Journey: one event-driven vertical path, no world scans. --
    private int journeyPanelWidth = 256;
    private final int[] journeyDescriptionScroll = new int[2];
    private HsUi.FittedLabel journeyScrollHint;
    private static final Component JOURNEY_SCROLL_HELP = Component.translatable(
        "hearthstead.journey.scroll_help");
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
    private static final int RECURRING_CARD_Y = 34;
    private static final int RECURRING_CARD_H = 28;
    private static final int AFTERMATH_WITH_RECURRING_HEAD_Y = 68;
    private static final int AFTERMATH_WITH_RECURRING_HEAD_H = 28;
    private static final int AFTERMATH_WITH_RECURRING_FACTS_Y = 102;
    private static final int AFTERMATH_WITH_RECURRING_FACTS_H = 28;
    private static final int AFTERMATH_WITH_RECURRING_STATE_Y = 136;
    private static final int AFTERMATH_WITH_RECURRING_STATE_H = 36;

    // -- Explicit natural-recruit admission. This modal is intentionally
    // bounded to 224px so the whole card and its authoritative action fit in
    // the required 427x240 logical viewport at GUI scale 3. --
    private static final int RECRUIT_PANEL_W = 256;
    private Component recruitmentPriceTitle = Component.empty();
    private Component recruitmentAptitudeLine = Component.empty();
    private Component recruitmentQuoteReasonLine = Component.empty();
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
    private int requestPanelWidth = 300;
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
    private static final Component RECURRING_TITLE = Component.translatable(
        "hearthstead.raid.recurring.title");
    private static final Component JOURNEY_TITLE = Component.translatable(
        "hearthstead.journey.title");
    private static final Component STORES_LABEL = Component.translatable(
        "hearthstead.gui.stores");
    private static final Component MORALE_LABEL = Component.translatable(
        "hearthstead.gui.morale");
    private static final Component ALERT_LABEL = Component.translatable(
        "hearthstead.gui.alert");
    private static final Component SETTLEMENT_STATUS_LABEL = Component.translatable(
        "hearthstead.gui.settlement_status");
    private static final Component SETTLEMENT_STATUS_ALERT = Component.translatable(
        "hearthstead.gui.settlement_status.alert");
    private static final Component SETTLEMENT_STATUS_STABLE = Component.translatable(
        "hearthstead.gui.settlement_status.stable");
    private static final Component COMMAND_CENTER_LABEL = Component.translatable(
        "hearthstead.gui.command_center");
    private static final Component SETTLEMENT_PULSE_LABEL = Component.translatable(
        "hearthstead.gui.settlement_pulse");
    private static final Component POPULATION_LABEL = Component.translatable(
        "hearthstead.gui.tooltip.population");
    private static final Component EMPLOYED_LABEL = Component.translatable(
        "hearthstead.gui.tooltip.employed");
    private static final Component FOOD_LABEL = Component.translatable(
        "hearthstead.gui.stat.food");
    private static final Component RADIUS_LABEL = Component.translatable(
        "hearthstead.gui.tooltip.radius");

    /** Consume the release of a modal click even if its button closed the panel. */
    private boolean modalClickAwaitingRelease;
    private boolean mayorTabOpen;
    private boolean peopleTabOpen;
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
    private int mayorPage;
    private int peopleScroll;
    /** Rebuild only when client entity tracking makes the selected sheet actionable or unavailable. */
    private boolean peopleViewInspectable;
    private UUID selectedResidentId;
    private String appliedPeopleSearch = "";
    private EditBox peopleSearchBox;
    private HearthMayorSnapshot peopleRenderSnapshot;
    private Font peopleRenderFont;
    private int peopleRenderListWidth = -1;
    private int peopleRenderDetailWidth = -1;
    private List<PeopleRenderRow> cachedPeopleRows = List.of();
    private static final Component PEOPLE_SELECT = Component.literal("Select a recorded resident");
    private Component peopleCountLine = Component.empty();
    private HsUi.FittedLabel peopleRangeLine = fittedEmpty();
    private Component peopleDetailHeading = Component.empty();
    private Component peopleSelectLine = Component.empty();
    private Component peopleMoveCloserLine = Component.empty();
    private Component peopleEmptyLine = Component.empty();
    private Component peopleMayorLine = Component.empty();
    private Component peopleTravelerLine = Component.empty();
    private int selectedRequestIndex;
    /** Wrapped right-pane request facts have their own offset; the left request list remains independent. */
    private int taskDetailScroll;
    private final List<SeatTabButton> seatTabs = new ArrayList<>();
    /** Controls registered for input/narration and drawn once after the modal. */
    private final List<AbstractButton> latePanelWidgets = new ArrayList<>();
    private boolean uiOpenSoundPlayed;
    private boolean uiCloseSoundPlayed;
    private int mayorPanelLeft;
    private int mayorPanelTop;
    private int mayorPanelWidth = MAYOR_PANEL_MAX_W;
    private int mayorPanelHeight = 224;
    private int mayorVisibleRows = MAYOR_MAX_ROWS;
    private int mayorListHeight = MAYOR_MAX_ROWS * MAYOR_CARD_STEP - 4;
    private int mayorFoot = MAYOR_LIST_TOP + mayorListHeight + 4;
    private int mayorButtonY = mayorFoot + MAYOR_FOOTER_GAP_TOP;
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
    private com.hearthstead.entity.SettlerEntity recruitmentPortrait;
    private int portraitRefreshTicks;
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
    /** Page-specific fitted strings; rebuilt only for the request projection, font, or body columns. */
    private HearthMayorSnapshot.RequestView taskRenderView =
        HearthMayorSnapshot.RequestView.closed();
    private Font taskRenderFont;
    private int taskRenderListWidth = -1;
    private int taskRenderDetailWidth = -1;
    private boolean taskRenderLoading;
    private List<TaskPageRow> cachedTaskRows = List.of();
    private Component taskMetaLine = Component.empty();
    private Component taskDetailHeading = Component.empty();
    private Component taskEmptyLine = Component.empty();
    private Component requestMetaLine = Component.empty();
    private Component requestFooterLine = Component.empty();
    private HearthMayorSnapshot.ReadinessView cachedReadinessView =
        HearthMayorSnapshot.ReadinessView.closed();
    private List<Component> cachedReadinessBlockers = List.of();
    private Component readinessMetaLine = Component.empty();
    private Component readinessMetricsLine = Component.empty();
    private int readinessScroll;
    private static final int RAID_STATUS_REFRESH_INTERVAL_TICKS = 100;
    private HearthMayorSnapshot.RecurringStatusView cachedRecurringStatusView =
        HearthMayorSnapshot.RecurringStatusView.closed();
    private int raidStatusRefreshTicks;
    private Component recurringStatusLine = Component.empty();
    private Component recurringDetailLine = Component.empty();
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
    private int cachedRecruitMissingFood = Integer.MIN_VALUE;
    private int cachedRecruitTooltipWidth = -1;
    private List<FormattedCharSequence> recruitTooltip = List.of();
    private boolean cachedRecruitCandidatePresent;
    private Font cachedRecruitFont;
    private String cachedRecruitLanguage = "";
    private int cachedRecruitLineWidth = -1;
    private FormattedCharSequence recruitLine1 = FormattedCharSequence.EMPTY;
    private FormattedCharSequence recruitLine2 = FormattedCharSequence.EMPTY;
    private HearthMayorSnapshot mayorRenderSnapshot;
    private String mayorRenderLanguage = "";
    private int mayorRenderPanelWidth = -1;
    private int mayorRenderPanelHeight = -1;
    private int mayorRenderVisibleRows = -1;
    private int mayorRenderPage = -1;
    private MayorRenderModel mayorRenderModel = MayorRenderModel.empty();
    private HearthMayorSnapshot mayorStatusSnapshot;
    private String mayorStatusLanguage = "";
    private int mayorStatusPanelWidth = -1;
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
    private HearthMayorSnapshot.RecurringStatusView recurringRenderSource;
    private String recurringRenderLanguage = "";
    private int recurringRenderPanelHeight = -1;
    private RecurringStatusRenderModel recurringRenderModel =
        RecurringStatusRenderModel.empty();
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
    private int cachedStatsMorale = Integer.MIN_VALUE;
    private int cachedStatsMoraleBand = Integer.MIN_VALUE;
    private int cachedStatsAlert = Integer.MIN_VALUE;
    private int cachedStatsLayoutWidth = -1;
    private int cachedStatsHeroLabelWidth = -1;
    private int cachedStatsCardLabelWidth = -1;
    private int cachedSettlementStatusWidth = -1;
    private Font cachedStatsFont;
    private String cachedStatsLanguage = "";
    private HsUi.FittedLabel cachedPopulationStat = fittedEmpty();
    private HsUi.FittedLabel cachedEmploymentStat = fittedEmpty();
    private HsUi.FittedLabel cachedFoodStat = fittedEmpty();
    private HsUi.FittedLabel cachedRadiusStat = fittedEmpty();
    private HsUi.FittedLabel cachedMoraleValue = fittedEmpty();
    private HsUi.FittedLabel cachedMoraleBand = fittedEmpty();
    private HsUi.FittedLabel cachedPopulationCardLabel = fittedEmpty();
    private HsUi.FittedLabel cachedEmploymentCardLabel = fittedEmpty();
    private HsUi.FittedLabel cachedFoodCardLabel = fittedEmpty();
    private HsUi.FittedLabel cachedRadiusCardLabel = fittedEmpty();
    private HsUi.FittedLabel cachedSettlementStatusTitle = fittedEmpty();
    private HsUi.FittedLabel cachedSettlementStatusDetail = fittedEmpty();
    private int cachedPriorityFood = Integer.MIN_VALUE;
    private int cachedPriorityPopulation = Integer.MIN_VALUE;
    private int cachedPriorityCapacity = Integer.MIN_VALUE;
    private int cachedPriorityAlert = Integer.MIN_VALUE;
    private int cachedPriorityWidth = -1;
    private Font cachedPriorityFont;
    private String cachedPriorityLanguage = "";
    private HsUi.FittedLabel cachedPriorityTitle = fittedEmpty();
    private HsUi.FittedLabel cachedPriorityDetail = fittedEmpty();
    private boolean cachedPriorityNeedsAttention;

    public HearthScreen(HearthMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        headerHearthItem = new ItemStack(ModItems.HEARTH.get());
        imageWidth = 512;
        imageHeight = 274;
        inventoryLabelX = HearthMenu.PLAYER_INV_X + 1;
        inventoryLabelY = HearthMenu.PLAYER_INV_Y - 9;
        titleLabelY = -1000; // we draw our own header
    }

    private void switchSupplies(boolean open) {
        // A held stack remains in its current workspace until the player places it.
        if (!menu.getCarried().isEmpty()) return;
        if (open && !suppliesOpen) supplyCategory = HearthSupplyCategory.ALL;
        suppliesOpen = open;
        mayorTabOpen = peopleTabOpen = journeyTabOpen = recruitmentPanelOpen = requestPanelOpen = false;
        journeySkipConfirm = false;
        rebuildWidgets();
    }
    @Override
    protected void init() {
        councilLayout = HearthLayout.forViewport(width, height, suppliesOpen);
        imageWidth = councilLayout.width();
        imageHeight = councilLayout.height();
        super.init();
        topPos = hearthTopFor(height, imageHeight);
        // Keep the same client Slot instances, server indices and vanilla drag references.
        layoutSupplySlots();
        inventoryLabelX = councilLayout.playerX();
        inventoryLabelY = councilLayout.playerY() - 12;
        applyResponsivePopoutLayout();
        cachedSettlementName = "\u0000";
        cachedRecruitBlocker = null;
        raidStatusRefreshTicks = 0;
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

    /** Repositions only client slots; filtering never changes any stored stack or component. */
    private void layoutSupplySlots() {
        laidOutSupplies.clear();
        int visibleCommunal = 0;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (i < HearthMenu.COMMUNAL_SLOTS) laidOutSupplies.add(slot.getItem().copy());
            boolean show = suppliesOpen && (i >= HearthMenu.COMMUNAL_SLOTS
                || supplyCategory.matches(slot.getItem()));
            if (!show) {
                slot.x = -10000;
                slot.y = -10000;
                continue;
            }
            HearthLayout.Rect bounds = i < HearthMenu.COMMUNAL_SLOTS
                ? councilLayout.slot(visibleCommunal++) : councilLayout.slot(i);
            slot.x = bounds.x();
            slot.y = bounds.y();
        }
    }

    private boolean supplyContentsChanged() {
        int count = Math.min(HearthMenu.COMMUNAL_SLOTS, menu.slots.size());
        if (laidOutSupplies.size() != count) return true;
        for (int i = 0; i < count; i++) {
            if (!ItemStack.matches(laidOutSupplies.get(i), menu.slots.get(i).getItem())) return true;
        }
        return false;
    }

    private void selectSupplyCategory(HearthSupplyCategory category) {
        if (category == supplyCategory || !menu.getCarried().isEmpty()) return;
        supplyCategory = category;
        layoutSupplySlots();
        rebuildSeatWidgets();
    }

    private int supplyItemTotal(HearthSupplyCategory category) {
        int total = 0;
        for (int i = 0; i < Math.min(HearthMenu.COMMUNAL_SLOTS, menu.slots.size()); i++) {
            ItemStack stack = menu.slots.get(i).getItem();
            if (!stack.isEmpty() && category.matches(stack)) total += stack.getCount();
        }
        return total;
    }

    /** Pure geometry used by the unit preflight and the live screen. */
    static int hearthTopFor(int viewportHeight, int ledgerHeight) {
        return Math.max(0, (viewportHeight - ledgerHeight) / 2);
    }

    static boolean hearthTabsFitFor(int viewportWidth, int ledgerWidth) {
        return ledgerWidth <= viewportWidth && ledgerWidth >= 304;
    }

    /**
     * Pure Mayor geometry shared by native runtime and deterministic layout
     * tests. A 16px viewport gutter yields the approved 411x224 panel at
     * 427x240 and a truthful 304x224 contraction at 320x240.
     */
    static MayorLayout mayorLayoutFor(int viewportWidth, int viewportHeight) {
        int panelWidth = Mth.clamp(viewportWidth - 16,
            MAYOR_PANEL_MIN_W, MAYOR_PANEL_MAX_W);
        int availableHeight = Math.max(1, viewportHeight - 16);
        // panel height = 98 fixed pixels + one 42px step per visible row.
        int rows = Mth.clamp((availableHeight - 98) / MAYOR_CARD_STEP,
            1, MAYOR_MAX_ROWS);
        int listHeight = rows * MAYOR_CARD_STEP - 4;
        int foot = MAYOR_LIST_TOP + listHeight + 4;
        int buttonY = foot + MAYOR_FOOTER_GAP_TOP;
        int panelHeight = buttonY + HsUiTokens.BUTTON_H
            + MAYOR_PANEL_BOTTOM_PAD;
        return new MayorLayout(panelWidth, panelHeight, rows, listHeight,
            foot, buttonY);
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
        journeyPanelWidth = Math.min(360, Math.max(1, width - 16));
        requestPanelWidth = Math.min(411, Math.max(1, width - 16));
        journeyRenderLanguage = "";
        readinessRenderLanguage = "";
        recurringRenderLanguage = "";
        aftermathRenderLanguage = "";
        MayorLayout mayor = mayorLayoutFor(width, height);
        mayorPanelWidth = mayor.panelWidth();
        mayorPanelHeight = mayor.panelHeight();
        mayorVisibleRows = mayor.visibleRows();
        mayorListHeight = mayor.listHeight();
        mayorFoot = mayor.foot();
        mayorButtonY = mayor.buttonY();

        JourneyLayout journey = journeyLayoutFor(height);
        journeyPanelHeight = journey.panelHeight();
        journeyStepHeight = journey.stepHeight();
        journeyStepGap = journey.stepGap();
        journeyFootDividerY = journey.footDividerY();
        journeyFootY = journey.footY();
        journeyButtonY = journey.buttonY();
    }

    record MayorLayout(int panelWidth, int panelHeight, int visibleRows,
                       int listHeight, int foot, int buttonY) {
    }

    static int mayorPageCount(int candidateCount, int rowsPerPage) {
        int safeRows = Math.max(1, rowsPerPage);
        int safeCandidates = Math.max(0, candidateCount);
        return Math.max(1, (safeCandidates + safeRows - 1) / safeRows);
    }

    static int mayorClampPage(int requestedPage, int candidateCount,
                              int rowsPerPage) {
        return Mth.clamp(requestedPage, 0,
            mayorPageCount(candidateCount, rowsPerPage) - 1);
    }

    static int mayorPageStart(int page, int candidateCount,
                              int rowsPerPage) {
        int clamped = mayorClampPage(page, candidateCount, rowsPerPage);
        return clamped * Math.max(1, rowsPerPage);
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

    /** Already fitted when the People projection/search/layout changes; render only draws it. */
    private record PeopleRenderRow(UUID id, int runtimeEntityId, Component listName,
                                   Component listProfession, Component listStatus, Component detailName,
                                   Component detailProfession, Component detailStatus,
                                   Component detailHint, boolean loaded) { }

    /** Request labels fitted independently for the narrow list and detail columns. */
    private record TaskPageLine(FormattedCharSequence text, int tone) { }

    private record TaskPageRow(Component list, List<TaskPageLine> detailLines,
                               int tone) {
        private TaskPageRow {
            detailLines = List.copyOf(detailLines);
        }
    }

    private record MayorRenderRow(HsUi.FittedLabel initial,
                                  HsUi.FittedLabel name,
                                  HsUi.FittedLabel profession,
                                  HsUi.FittedLabel boon,
                                  HsUi.FittedLabel knack,
                                  HsUi.Tone tone) {
    }

    private record MayorRenderModel(HsUi.FittedLabel title,
                                    HsUi.FittedLabel choiceRule,
                                    HsUi.FittedLabel candidatesTitle,
                                    HsUi.FittedLabel loading,
                                    HsUi.FittedLabel emptyCandidates,
                                    List<MayorRenderRow> candidates,
                                    HsUi.FittedLabel page) {
        private MayorRenderModel {
            candidates = List.copyOf(candidates);
        }

        private static MayorRenderModel empty() {
            HsUi.FittedLabel empty = fittedEmpty();
            return new MayorRenderModel(empty, empty, empty, empty, empty,
                List.of(), empty);
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

    private record RecurringStatusRenderModel(int titleWidth,
                                              HsUi.FittedLabel status,
                                              HsUi.FittedLabel detail) {
        private static RecurringStatusRenderModel empty() {
            HsUi.FittedLabel empty = fittedEmpty();
            return new RecurringStatusRenderModel(0, empty, empty);
        }
    }

    private record AftermathRenderModel(int titleWidth,
                                        HsUi.FittedLabel status,
                                        HsUi.FittedLabel night,
                                        HsUi.FittedLabel captain,
                                        HsUi.FittedLabel objective,
                                        List<FormattedCharSequence> impact,
                                        HsUi.FittedLabel compactImpact,
                                        HsUi.FittedLabel threat,
                                        List<FormattedCharSequence> reward,
                                        HsUi.FittedLabel compactReward,
                                        List<FormattedCharSequence> road) {
        private AftermathRenderModel {
            impact = List.copyOf(impact);
            reward = List.copyOf(reward);
            road = List.copyOf(road);
        }

        private static AftermathRenderModel empty() {
            HsUi.FittedLabel empty = fittedEmpty();
            return new AftermathRenderModel(0, empty, empty, empty, empty,
                List.of(), empty, empty, List.of(), empty, List.of());
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
        updateRecurringStatusViewCache(fresh == null
            ? HearthMayorSnapshot.RecurringStatusView.closed()
            : fresh.recurringStatus());
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
        HearthLayout.Rect navigation = councilLayout.navigation();
        int tabX = leftPos + navigation.x();
        int tabY = topPos + navigation.y();
        int tabWidth = councilLayout.navigationStep() - 2;
        int tabHeight = councilLayout.navigationHeight();
        // Five equal Hearth destinations. People has its own bounded,
        // server-authored recorded-member projection; Mayor choice remains
        // a separate secondary action inside that truthful roster panel.
        addSeatTab(new SeatTabButton(tabX, tabY, tabWidth, tabHeight,
            Component.literal("Home"), !mayorTabOpen && !peopleTabOpen && !journeyTabOpen
                && !recruitmentPanelOpen && !requestPanelOpen && !suppliesOpen, () -> {
                QaClientObserver.markUiTransition("hearth_home_tab");
                if (suppliesOpen) { switchSupplies(false); return; }
                mayorTabOpen = peopleTabOpen = journeyTabOpen = recruitmentPanelOpen = requestPanelOpen = false;
                journeySkipConfirm = false;
                rebuildSeatWidgets();
            }));
        tabX += councilLayout.navigationStep();
        addSeatTab(new SeatTabButton(tabX, tabY, tabWidth, tabHeight,
            Component.literal("People"), peopleTabOpen, this::openPeoplePanel));
        tabX += councilLayout.navigationStep();
        SeatTabButton tasks = new SeatTabButton(tabX, tabY, tabWidth, tabHeight,
            Component.literal("Tasks"), requestPanelOpen, this::openRequestLedger);
        tasks.setTooltip(Tooltip.create(Component.translatable("hearthstead.request.ledger.open.tip")));
        addSeatTab(tasks);
        tabX += councilLayout.navigationStep();
        SeatTabButton development = new SeatTabButton(tabX, tabY, tabWidth, tabHeight,
            Component.literal("Tech Tree"), false, this::requestDevelopmentData);
        development.setTooltip(Tooltip.create(Component.translatable("hearthstead.development.open.tip")));
        addSeatTab(development);
        tabX += councilLayout.navigationStep();
        SeatTabButton stores = new SeatTabButton(tabX, tabY, tabWidth, tabHeight,
            Component.literal("Stores"), false, () -> {
                QaClientObserver.markUiTransition("hearth_stores_open");
                PacketDistributor.sendToServer(new StorageRequestPayload());
            });
        stores.setTooltip(Tooltip.create(Component.literal("View settlement-wide stored items")));
        addSeatTab(stores);

        if (!hasOpenPopout()) {
            addRenderableWidget(ModalButton.danger(leftPos + imageWidth - 52,
                topPos + 5, 46, 16, Component.literal("Close"), this::onClose));
        }

        if (!suppliesOpen && !mayorTabOpen && !peopleTabOpen && !journeyTabOpen
                && !recruitmentPanelOpen && !requestPanelOpen) {
            HearthLayout.Rect attention = councilLayout.stat(5);
            int actionWidth = Math.max(44, (attention.width() - 18) / 2);
            HsButton journey = new TransparentRowButton(leftPos + attention.x() + 6,
                topPos + attention.y() + attention.height() - 20,
                actionWidth, 16, Component.literal("Journey"), this::openJourneyPanel);
            journey.setTooltip(Tooltip.create(Component.literal(
                "View the settlement journey, raid readiness, and aftermath")));
            addRenderableWidget(journey);
            HsButton supplies = new TransparentRowButton(leftPos + attention.x() + 12 + actionWidth,
                topPos + attention.y() + attention.height() - 20,
                Math.max(44, attention.width() - 18 - actionWidth), 16,
                Component.literal("Supplies"), () -> switchSupplies(true));
            supplies.setTooltip(Tooltip.create(Component.literal(
                "Open the Hearth's physical storage and inventory slots")));
            addRenderableWidget(supplies);
        }


        if (!suppliesOpen && cachedRecruitmentCard.present() && !recruitmentPanelOpen) {
            HearthLayout.Rect recruitment = councilLayout.recruitment();
            int reviewWidth = Math.min(RECRUIT_REVIEW_W, recruitment.width() - 12);
            int reviewX = leftPos + recruitment.x()
                + (suppliesOpen ? 6 : recruitment.width() - reviewWidth - 6);
            int reviewY = topPos + recruitment.y()
                + (suppliesOpen ? 22 : recruitment.height() - 19);
            HsButton review = new TransparentRowButton(
                reviewX, reviewY,
                reviewWidth, 16,
                Component.translatable("hearthstead.recruit.card.review"), () -> {
                    QaClientObserver.markUiTransition("hearth_recruitment_popout");
                    mayorTabOpen = false;
                    peopleTabOpen = false;
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

        if (peopleTabOpen) {
            rebuildPeopleWidgets();
            return;
        }
        if (!mayorTabOpen) {
            return;
        }
        updateMayorPanelPosition();
        suppressCoveredTabs(mayorPanelLeft, mayorPanelTop,
            mayorPanelWidth, mayorPanelHeight);

        int candidateCount = mayorSnapshot == null
            ? 0 : mayorSnapshot.candidates().size();
        mayorPage = mayorClampPage(mayorPage, candidateCount,
            mayorVisibleRows);
        int pages = mayorPageCount(candidateCount, mayorVisibleRows);
        int closeWidth = Math.min(MAYOR_CLOSE_W,
            Math.max(44, mayorPanelWidth / 5));
        int navWidth = Math.min(MAYOR_NAV_W,
            Math.max(48, mayorPanelWidth / 5));
        int closeX = mayorPanelLeft + mayorPanelWidth - MAYOR_PAD - closeWidth;
        int nextX = closeX - MAYOR_NAV_GAP - navWidth;

        HsButton previous = ModalButton.normal(mayorPanelLeft + MAYOR_PAD,
            mayorPanelTop + mayorButtonY, navWidth, HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.mayor.page.previous"),
            () -> changeMayorPage(-1));
        previous.active = mayorPage > 0;
        previous.setTooltip(Tooltip.create(Component.translatable(
            "hearthstead.mayor.page.previous.tip")));
        addPanelWidget(previous);

        HsButton next = ModalButton.normal(nextX, mayorPanelTop + mayorButtonY,
            navWidth, HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.mayor.page.next"),
            () -> changeMayorPage(1));
        next.active = mayorPage + 1 < pages;
        next.setTooltip(Tooltip.create(Component.translatable(
            "hearthstead.mayor.page.next.tip")));
        addPanelWidget(next);

        HsButton close = ModalButton.danger(closeX, mayorPanelTop + mayorButtonY,
            closeWidth, HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.gui.close"), this::closePopout);
        close.setTooltip(Tooltip.create(Component.translatable(
            "hearthstead.gui.close.tip")));
        addPanelWidget(close);

        if (mayorSnapshot == null) {
            return;
        }
        List<HearthMayorSnapshot.Candidate> candidates = mayorSnapshot.candidates();
        int pageStart = mayorPageStart(mayorPage, candidates.size(),
            mayorVisibleRows);
        boolean canAppoint = !mayorSnapshot.mourning() || !mayorSnapshot.hasMayor();
        for (int row = 0; row < mayorVisibleRows
            && row + pageStart < candidates.size(); row++) {
            HearthMayorSnapshot.Candidate candidate = candidates.get(row + pageStart);
            int y = mayorPanelTop + MAYOR_LIST_TOP + row * MAYOR_CARD_STEP;
            int appointWidth = mayorAppointWidth(mayorPanelWidth);
            int appointX = mayorPanelLeft + mayorPanelWidth - MAYOR_PAD
                - appointWidth;
            HsButton appoint = ModalButton.normal(appointX, y + 4, appointWidth,
                HsUiTokens.BUTTON_H, Component.translatable("hearthstead.mayor.appoint"),
                () -> appointAction(candidate.id()));
            appoint.active = canAppoint;
            // A disabled control always says why (D-014).
            Component appointmentHint = canAppoint
                ? Component.translatable(mayorSnapshot.mourning()
                    ? "hearthstead.mayor.appoint.tip.mourning_vacancy"
                    : "hearthstead.mayor.appoint.tip", candidate.name())
                : Component.translatable("hearthstead.mayor.refused.mourning");
            appoint.setTooltip(Tooltip.create(appointmentHint.copy()
                .append("\n").append(boonName(candidate.boonKey()))
                .append("\n").append(Component.translatable("hearthstead.mayor.knack.value",
                    boonAttributeName(candidate.boonKey()), Mth.clamp(candidate.knack(), 0, 100)))));
            addPanelWidget(appoint);
        }
    }

    private void rebuildPeopleWidgets() {
        HearthLayout.Rect body = councilLayout.summary();
        int listWidth = Math.max(104, body.width() * 3 / 5);
        int rightWidth = Math.max(72, body.width() - listWidth - 6);
        ensurePeopleRenderCache(Math.max(1, listWidth - 60),
            Math.max(1, rightWidth - 12));
        // Header (0..17), search (21..36), and list (42..) never share pixels.
        peopleSearchBox = new EditBox(font, leftPos + body.x() + 5,
            topPos + body.y() + 21, Math.max(56, listWidth - 48), 16,
            Component.literal("Search residents"));
        peopleSearchBox.setMaxLength(32);
        peopleSearchBox.setValue(appliedPeopleSearch);
        peopleSearchBox.setTextColor(HearthPixelSurface.INK);
        peopleSearchBox.setTextColorUneditable(HearthPixelSurface.MUTED);
        peopleSearchBox.setHint(Component.literal("Search residents")
            .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        addRenderableWidget(peopleSearchBox);
        HsButton search = new TransparentRowButton(leftPos + body.x() + listWidth - 40,
            topPos + body.y() + 21, 35, 16, Component.literal("Find"), () -> {
                appliedPeopleSearch = peopleSearchBox.getValue().trim();
                peopleScroll = 0;
                peopleRenderSnapshot = null;
                rebuildSeatWidgets();
            });
        addRenderableWidget(search);

        int visible = peopleVisibleRows();
        peopleScroll = Mth.clamp(peopleScroll, 0,
            Math.max(0, cachedPeopleRows.size() - visible));
        int shown = Math.min(visible, Math.max(0, cachedPeopleRows.size() - peopleScroll));
        peopleRangeLine = HsUi.fitLabel(font, Component.literal(
            shown == 0 ? "0 / " + cachedPeopleRows.size()
                : (peopleScroll + 1) + "-" + (peopleScroll + shown)
                    + " / " + cachedPeopleRows.size()), 48);
        for (int row = 0; row < visible && peopleScroll + row < cachedPeopleRows.size(); row++) {
            PeopleRenderRow person = cachedPeopleRows.get(peopleScroll + row);
            int rowY = topPos + body.y() + 42 + row * 24;
            // The card text is painted by renderPeoplePage exactly once; this button is input/narration only.
            addRenderableWidget(new TransparentRowButton(leftPos + body.x() + 5, rowY,
                Math.max(50, listWidth - 10), 20, person.listName(), () -> {
                    selectedResidentId = person.id();
                    rebuildSeatWidgets();
                }));
        }
        PeopleRenderRow selected = selectedPeopleRow();
        int rightX = body.x() + listWidth + 6;
        int footerTop = topPos + body.y() + body.height() - 42;
        HsButton view = new TransparentRowButton(leftPos + rightX + 5,
            topPos + body.y() + body.height() - 22, Math.max(52, rightWidth - 10), 16,
            Component.literal("View Settler"), this::viewSelectedSettler);
        boolean canInspect = canInspectResident(selected);
        peopleViewInspectable = canInspect;
        view.active = canInspect;
        view.setTooltip(Tooltip.create(Component.literal(selected != null && !selected.loaded()
            ? "This resident is unloaded; their live sheet cannot be opened"
            : selected != null && !canInspect
                ? "Move within 8 blocks of this settler to inspect them"
                : "Open the loaded settler's server-authored inspection sheet")));
        addRenderableWidget(view);
        int peopleSecondaryWidth = Math.max(32, (rightWidth - 14) / 2);
        HsButton mayor = new TransparentRowButton(leftPos + rightX + 5, footerTop,
            peopleSecondaryWidth, 16, Component.literal("Mayor"), () -> {
                peopleTabOpen = false; mayorTabOpen = true; requestMayorData(); rebuildSeatWidgets();
            });
        addRenderableWidget(mayor);
        if (cachedRecruitmentCard.present()) {
            HsButton traveler = new TransparentRowButton(leftPos + rightX + 8 + (rightWidth - 14) / 2,
                footerTop, Math.max(32, rightWidth - 13 - peopleSecondaryWidth),
                16, Component.literal("Traveler"), () -> {
                    peopleTabOpen = false; recruitmentPanelOpen = true; rebuildSeatWidgets();
                });
            addRenderableWidget(traveler);
        }
    }

    private Component fitPageText(Component source, int width) {
        String raw = source.getString();
        if (font.width(raw) <= width) return Component.literal(raw);
        int ellipsis = font.width("...");
        return Component.literal(font.plainSubstrByWidth(raw,
            Math.max(1, width - ellipsis)) + "...");
    }

    private void ensurePeopleRenderCache(int listNameWidth, int detailWidth) {
        if (peopleRenderSnapshot == mayorSnapshot && peopleRenderFont == font
            && peopleRenderListWidth == listNameWidth
            && peopleRenderDetailWidth == detailWidth) {
            return;
        }
        List<PeopleRenderRow> rows = new ArrayList<>();
        String query = appliedPeopleSearch.toLowerCase(Locale.ROOT);
        if (mayorSnapshot != null) {
            for (HearthMayorSnapshot.Resident resident : mayorSnapshot.residents()) {
                String status = resident.loaded()
                    ? resident.statusKey().replace('_', ' ') : "unloaded";
                if (!query.isEmpty() && !resident.name().toLowerCase(Locale.ROOT).contains(query)
                    && !resident.professionId().toLowerCase(Locale.ROOT).contains(query)
                    && !status.contains(query)) {
                    continue;
                }
                rows.add(new PeopleRenderRow(resident.id(), resident.runtimeEntityId(),
                    fitPageText(Component.literal(resident.name()), listNameWidth),
                    fitPageText(Component.literal(resident.professionId()), listNameWidth),
                    fitPageText(Component.literal(status), 48),
                    fitPageText(Component.literal(resident.name()), detailWidth),
                    fitPageText(Component.literal(resident.professionId()), detailWidth),
                    fitPageText(Component.literal(status), detailWidth),
                    fitPageText(Component.literal(resident.loaded() ? "Live sheet available"
                        : "Unloaded: no live sheet"), detailWidth), resident.loaded()));
            }
        }
        cachedPeopleRows = List.copyOf(rows);
        peopleCountLine = mayorSnapshot == null ? fitPageText(Component.literal("Loading residents"),
            Math.max(1, listNameWidth - 10)) : fitPageText(Component.literal(mayorSnapshot.residentTotal()
                + " residents"), Math.max(1, listNameWidth - 10));
        peopleDetailHeading = fitPageText(Component.literal("SELECTED RESIDENT"), detailWidth);
        peopleSelectLine = fitPageText(PEOPLE_SELECT, detailWidth);
        peopleMoveCloserLine = fitPageText(Component.literal("Move closer to inspect"), detailWidth);
        peopleEmptyLine = cachedPeopleRows.isEmpty()
            ? fitPageText(Component.literal("No recorded residents match this search"), detailWidth)
            : Component.empty();
        peopleMayorLine = mayorSnapshot != null && mayorSnapshot.hasMayor()
            ? fitPageText(Component.literal("Mayor: " + mayorSnapshot.mayorName()), detailWidth)
            : fitPageText(Component.literal("Mayor: unappointed"), detailWidth);
        peopleTravelerLine = cachedRecruitmentCard.present()
            ? fitPageText(Component.literal("Traveler: " + cachedRecruitmentCard.name()), detailWidth)
            : fitPageText(Component.literal("Traveler: none"), detailWidth);
        if (selectedResidentId == null
            || cachedPeopleRows.stream().noneMatch(row -> row.id().equals(selectedResidentId))) {
            selectedResidentId = cachedPeopleRows.isEmpty() ? null : cachedPeopleRows.getFirst().id();
        }
        peopleScroll = Mth.clamp(peopleScroll, 0,
            Math.max(0, cachedPeopleRows.size() - peopleVisibleRows()));
        peopleRenderSnapshot = mayorSnapshot;
        peopleRenderFont = font;
        peopleRenderListWidth = listNameWidth;
        peopleRenderDetailWidth = detailWidth;
    }

    private int peopleVisibleRows() {
        // Search/list/header consume 42px; the two secondary actions and view control reserve 42px.
        return Math.max(2, Math.min(6, (councilLayout.summary().height() - 82) / 24));
    }

    private PeopleRenderRow selectedPeopleRow() {
        if (selectedResidentId == null) return null;
        for (PeopleRenderRow row : cachedPeopleRows) {
            if (row.id().equals(selectedResidentId)) return row;
        }
        return null;
    }

    private boolean canInspectResident(PeopleRenderRow resident) {
        if (resident == null || !resident.loaded() || resident.runtimeEntityId() < 0) return false;
        var level = net.minecraft.client.Minecraft.getInstance().level;
        var player = net.minecraft.client.Minecraft.getInstance().player;
        return level != null && level.getEntity(resident.runtimeEntityId())
            instanceof com.hearthstead.entity.SettlerEntity settler
            && resident.id().equals(settler.getUUID())
            && player != null && player.distanceToSqr(settler) <= 64.0D;
    }

    private void viewSelectedSettler() {
        PeopleRenderRow selected = selectedPeopleRow();
        if (!canInspectResident(selected)) return;
        PacketDistributor.sendToServer(mayorAction(
            HearthMayorAction.Kind.VIEW_SETTLER, selected.id(), 0));
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
        HsButton admit = ModalButton.normal(
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
        HsButton dismiss = ModalButton.danger(
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
        HearthLayout.Rect body = councilLayout.summary();
        int listWidth = Math.max(104, body.width() * 3 / 5);
        int rightWidth = Math.max(72, body.width() - listWidth - 6);
        ensureTaskPageRenderCache(Math.max(1, listWidth - 16),
            Math.max(1, rightWidth - 12));
        int rows = taskVisibleRows();
        requestScroll = Mth.clamp(requestScroll, 0,
            Math.max(0, cachedTaskRows.size() - rows));
        selectedRequestIndex = Mth.clamp(selectedRequestIndex, 0,
            Math.max(0, cachedTaskRows.size() - 1));
        taskDetailScroll = Mth.clamp(taskDetailScroll, 0, taskDetailMaxScroll());
        HsButton refresh = new TransparentRowButton(leftPos + body.x() + listWidth - 56,
            topPos + body.y() + 5, 51, 16, Component.literal("Refresh"),
            this::refreshRequestLedger);
        refresh.active = !requestLoading;
        addRenderableWidget(refresh);
        for (int row = 0; row < rows && row + requestScroll < cachedTaskRows.size(); row++) {
            int index = row + requestScroll;
            TaskPageRow request = cachedTaskRows.get(index);
            addRenderableWidget(new TransparentRowButton(leftPos + body.x() + 5,
                topPos + body.y() + 27 + row * 24, Math.max(50, listWidth - 10), 20,
                request.list(), () -> {
                    selectedRequestIndex = index;
                    taskDetailScroll = 0;
                    rebuildSeatWidgets();
                }));
        }
    }

    private void ensureTaskPageRenderCache(int listWidth, int detailWidth) {
        if (taskRenderView == cachedRequestView && taskRenderFont == font
            && taskRenderListWidth == listWidth && taskRenderDetailWidth == detailWidth
            && taskRenderLoading == requestLoading) {
            return;
        }
        List<TaskPageRow> rows = new ArrayList<>(cachedRequestRows.size());
        for (RequestRenderRow request : cachedRequestRows) {
            List<TaskPageLine> detailLines = new ArrayList<>();
            addTaskDetailLines(detailLines, request.headline(), detailWidth, ModalPixels.INK);
            addTaskDetailLines(detailLines, request.route(), detailWidth, ModalPixels.GOOD);
            addTaskDetailLines(detailLines, request.assignment(), detailWidth, ModalPixels.MUTED);
            addTaskDetailLines(detailLines, request.stop(), detailWidth,
                ModalPixels.ink(request.tone()));
            rows.add(new TaskPageRow(fitPageText(request.headline(), listWidth),
                detailLines, request.tone()));
        }
        cachedTaskRows = List.copyOf(rows);
        // Header metadata ends before the 51px Refresh button; list rows keep their full width.
        taskMetaLine = fitPageText(requestMetaLine, Math.max(1, listWidth - 94));
        taskDetailHeading = fitPageText(Component.literal("REQUEST DETAILS"), detailWidth);
        taskEmptyLine = fitPageText(requestLoading ? REQUEST_LOADING : REQUEST_EMPTY,
            detailWidth);
        taskRenderView = cachedRequestView;
        taskRenderFont = font;
        taskRenderListWidth = listWidth;
        taskRenderDetailWidth = detailWidth;
        taskRenderLoading = requestLoading;
    }

    private void addTaskDetailLines(List<TaskPageLine> destination, Component source,
                                    int width, int tone) {
        for (FormattedCharSequence line : font.split(source, Math.max(1, width))) {
            destination.add(new TaskPageLine(line, tone));
        }
    }

    private int taskVisibleRows() {
        return Math.max(2, Math.min(6, (councilLayout.summary().height() - 32) / 24));
    }

    private int taskDetailVisibleLines() {
        // Detail starts at y + 34 and uses 9px baseline steps through y + h - 6.
        return Math.max(1, (councilLayout.summary().height() - 48) / 9 + 1);
    }

    private int taskDetailMaxScroll() {
        if (cachedTaskRows.isEmpty()) return 0;
        TaskPageRow selected = cachedTaskRows.get(Mth.clamp(selectedRequestIndex, 0,
            cachedTaskRows.size() - 1));
        return Math.max(0, selected.detailLines().size() - taskDetailVisibleLines());
    }

    private boolean isOverTaskDetail(double mouseX, double mouseY) {
        HearthLayout.Rect body = councilLayout.summary();
        int listWidth = Math.max(104, body.width() * 3 / 5);
        int rightX = leftPos + body.x() + listWidth + 6;
        return mouseX >= rightX && mouseX < leftPos + body.x() + body.width()
            && mouseY >= topPos + body.y() + 24
            && mouseY < topPos + body.y() + body.height();
    }

    private void rebuildJourneyWidgets() {
        updateJourneyPanelPosition();
        suppressCoveredTabs(journeyPanelLeft, journeyPanelTop,
            journeyPanelWidth, journeyPanelHeight);
        addPanelClose(journeyPanelLeft, journeyPanelTop, journeyPanelWidth);
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
            HsButton check = ModalButton.normal(
                journeyPanelLeft + (journeyPanelWidth - JOURNEY_BUTTON_W) / 2,
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
            HsButton skip = ModalButton.danger(
                journeyPanelLeft + (journeyPanelWidth - JOURNEY_BUTTON_W) / 2,
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

        int half = (journeyPanelWidth - 2 * JOURNEY_PAD - JOURNEY_GAP) / 2;
        HsButton cancel = ModalButton.normal(journeyPanelLeft + JOURNEY_PAD,
            journeyPanelTop + journeyButtonY, half, HsUiTokens.BUTTON_H,
            Component.translatable("gui.cancel"), () -> {
                QaClientObserver.markUiTransition("hearth_journey_confirm_cancel");
                journeySkipConfirm = false;
                rebuildSeatWidgets();
            });
        HsButton confirm = ModalButton.danger(
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
        int half = (journeyPanelWidth - 2 * JOURNEY_PAD - JOURNEY_GAP) / 2;
        HsButton refresh = ModalButton.normal(journeyPanelLeft + JOURNEY_PAD,
            journeyPanelTop + journeyButtonY, half, HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.raid.readiness.refresh"),
            this::openRaidReadiness);
        HsButton declare = ModalButton.normal(
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
        HsButton close = ModalButton.danger(
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
        peopleTabOpen = false;
        journeyTabOpen = false;
        recruitmentPanelOpen = false;
        requestPanelOpen = false;
        journeySkipConfirm = false;
        raidStatusRefreshTicks = 0;
        rebuildSeatWidgets();
    }

    private void changeMayorPage(int direction) {
        int candidates = mayorSnapshot == null
            ? 0 : mayorSnapshot.candidates().size();
        int next = mayorClampPage(mayorPage + Integer.signum(direction),
            candidates, mayorVisibleRows);
        if (next == mayorPage) {
            return;
        }
        mayorPage = next;
        QaClientObserver.markUiTransition("hearth_mayor_page");
        rebuildSeatWidgets();
    }

    private static int mayorAppointWidth(int panelWidth) {
        return panelWidth >= 360 ? 61 : 54;
    }

    private static int mayorNameWidth(int panelWidth) {
        return Math.max(1, panelWidth - MAYOR_PAD - mayorAppointWidth(panelWidth)
            - MAYOR_TEXT_X - 8);
    }

    private static int mayorInfoX(int panelWidth) {
        return MAYOR_TEXT_X + (panelWidth - MAYOR_PAD - MAYOR_TEXT_X) / 2 + 4;
    }

    private static int mayorBoonWidth(int panelWidth) {
        return Math.max(1, mayorInfoX(panelWidth) - MAYOR_TEXT_X - 8);
    }

    private static int mayorKnackWidth(int panelWidth) {
        return Math.max(1, panelWidth - MAYOR_PAD
            - mayorInfoX(panelWidth));
    }

    /** Prefers the right of the window; falls back left, then clamps on-screen. */
    private void updateMayorPanelPosition() {
        int tabLeft = leftPos;
        int preferred = (leftPos + imageWidth) + MAYOR_GAP;
        if (preferred + mayorPanelWidth > width) {
            int leftSide = Math.min(leftPos, tabLeft) - MAYOR_GAP
                - mayorPanelWidth;
            preferred = leftSide >= 0 ? leftSide
                : Math.max(0, (width - mayorPanelWidth) / 2);
        }
        mayorPanelLeft = preferred;
        mayorPanelTop = Mth.clamp(
            topPos - (mayorPanelHeight - imageHeight) / 2,
            0, Math.max(0, height - mayorPanelHeight));
    }

    private void updateJourneyPanelPosition() {
        int tabLeft = leftPos;
        int preferred = (leftPos + imageWidth) + JOURNEY_GAP;
        if (preferred + journeyPanelWidth > width) {
            int leftSide = Math.min(leftPos, tabLeft) - JOURNEY_GAP - journeyPanelWidth;
            preferred = leftSide >= 0 ? leftSide : Math.max(0, (width - journeyPanelWidth) / 2);
        }
        journeyPanelLeft = preferred;
        journeyPanelTop = Mth.clamp(
            topPos - (journeyPanelHeight - imageHeight) / 2,
            0, Math.max(0, height - journeyPanelHeight));
    }

    private void updateRecruitmentPanelPosition() {
        int tabLeft = leftPos;
        int preferred = (leftPos + imageWidth) + RECRUIT_PANEL_GAP;
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
        int tabLeft = leftPos;
        int preferred = (leftPos + imageWidth) + REQUEST_PANEL_GAP;
        if (preferred + requestPanelWidth > width) {
            int leftSide = Math.min(leftPos, tabLeft) - REQUEST_PANEL_GAP
                - requestPanelWidth;
            preferred = leftSide >= 0 ? leftSide
                : Math.max(0, (width - requestPanelWidth) / 2);
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

    private void openPeoplePanel() {
        QaClientObserver.markUiTransition("hearth_people_open");
        mayorTabOpen = false;
        peopleTabOpen = true;
        journeyTabOpen = false;
        recruitmentPanelOpen = false;
        requestPanelOpen = false;
        journeySkipConfirm = false;
        PacketDistributor.sendToServer(mayorAction(
            HearthMayorAction.Kind.OPEN_PEOPLE, HearthMayorAction.NO_ID, 0));
        rebuildSeatWidgets();
    }

    private void requestDevelopmentData() {
        QaClientObserver.markUiTransition("hearth_development_open");
        PacketDistributor.sendToServer(mayorAction(
            HearthMayorAction.Kind.OPEN_DEVELOPMENT, HearthMayorAction.NO_ID, 0));
    }

    private void openJourneyPanel() {
        QaClientObserver.markUiTransition("hearth_journey_popout");
        mayorTabOpen = false;
        peopleTabOpen = false;
        journeyTabOpen = true;
        recruitmentPanelOpen = false;
        requestPanelOpen = false;
        journeySkipConfirm = false;
        raidStatusRefreshTicks = 0;
        resetReadinessView();
        PacketDistributor.sendToServer(mayorAction(
            HearthMayorAction.Kind.OPEN_JOURNEY,
            HearthMayorAction.NO_ID, 0));
        rebuildSeatWidgets();
    }

    private void openRequestLedger() {
        QaClientObserver.markUiTransition("hearth_request_ledger_open");
        mayorTabOpen = false;
        peopleTabOpen = false;
        journeyTabOpen = false;
        recruitmentPanelOpen = false;
        requestPanelOpen = true;
        requestLoading = true;
        requestLoadingTicks = 0;
        requestUnavailable = false;
        requestScroll = 0;
        taskDetailScroll = 0;
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
        recruitmentPriceTitle = Component.translatable("hearthstead.recruit.card.price_fixed",
            safe.quoteDiscountPercent());
        if (safe.firstAttribute() >= 0) {
            recruitmentAptitudeLine = Component.translatable("hearthstead.recruit.card.aptitudes",
                com.hearthstead.entity.Attribute.ALL[safe.firstAttribute()].displayName(), safe.firstValue(),
                com.hearthstead.entity.Attribute.ALL[safe.secondAttribute()].displayName(), safe.secondValue());
            recruitmentQuoteReasonLine = safe.quoteVersion() == 2
                ? Component.translatable("hearthstead.recruit.card.starting_aptitude_coins",
                    safe.aptitudePremium() * 2)
                : safe.quoteVersion() >= 1
                ? Component.translatable("hearthstead.recruit.card.starting_aptitude",
                    safe.aptitudePremium(), safe.aptitudePremium() * 2)
                : Component.translatable("hearthstead.recruit.card.legacy_quote");
        } else {
            recruitmentAptitudeLine = Component.translatable("hearthstead.recruit.card.legacy_quote");
            recruitmentQuoteReasonLine = Component.translatable("hearthstead.recruit.card.quote_fixed");
        }
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
        taskDetailScroll = 0;
        List<RequestRenderRow> rendered = new ArrayList<>(view.rows().size());
        for (HearthMayorSnapshot.RequestRow row : view.rows()) {
            rendered.add(requestRenderRow(row));
        }
        cachedRequestRows = List.copyOf(rendered);
        taskRenderView = HearthMayorSnapshot.RequestView.closed();
        requestScroll = Math.max(0, Math.min(requestScroll,
            Math.max(0, cachedRequestRows.size() - taskVisibleRows())));
        requestMetaLine = view.quarantined()
            ? Component.translatable("hearthstead.request.ledger.quarantined",
                Component.literal(view.quarantineReason()))
            : Component.translatable("hearthstead.request.ledger.summary",
                view.rows().size());
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

    /** Builds the server-measured recurring status only when a snapshot arrives. */
    private void updateRecurringStatusViewCache(
            HearthMayorSnapshot.RecurringStatusView view) {
        cachedRecurringStatusView = view == null
            ? HearthMayorSnapshot.RecurringStatusView.closed() : view;
        HearthMayorSnapshot.RecurringStatusView.Status status =
            cachedRecurringStatusView.status();
        if (status == null || status
                == HearthMayorSnapshot.RecurringStatusView.Status.NONE) {
            recurringStatusLine = Component.empty();
            recurringDetailLine = Component.empty();
            return;
        }
        recurringStatusLine = Component.translatable(
            "hearthstead.raid.recurring." + status.id());
        recurringDetailLine = switch (status) {
            case RECOVERING -> Component.translatable(
                "hearthstead.raid.recurring.cooldown",
                formatServerCooldown(cachedRecurringStatusView.cooldownRemainingTicks()));
            case WARNED, QUEUED, ACTIVE -> Component.translatable(
                "hearthstead.raid.recurring.planned_night",
                cachedRecurringStatusView.plannedNight());
            case NONE, BLOCKED -> Component.empty();
        };
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

    private boolean shouldShowRecurringStatus() {
        return cachedRecurringStatusView.status()
            != HearthMayorSnapshot.RecurringStatusView.Status.NONE;
    }

    private boolean shouldShowRaidStatus() {
        return shouldShowRecurringStatus() || shouldShowAftermath();
    }

    private boolean shouldShowAftermath() {
        JourneyPresentationMode mode = JourneyPresentationMode.tryFromWireId(
            menu.get(HearthMenu.DATA_JOURNEY_V3_MODE))
            .orElse(JourneyPresentationMode.QUARANTINED);
        return aftermathVisibleFor(cachedAftermathView.present(), mode,
            menu.get(HearthMenu.DATA_JOURNEY_V3_CURRENT));
    }

    static boolean raidStatusVisibleFor(boolean reportPresent,
                                       HearthMayorSnapshot.RecurringStatusView status,
                                       JourneyPresentationMode mode,
                                       int currentStepOrdinal) {
        return (status != null && status.status()
                != HearthMayorSnapshot.RecurringStatusView.Status.NONE)
            || aftermathVisibleFor(reportPresent, mode, currentStepOrdinal);
    }

    static boolean recurringBeforeAftermathLayoutIsNonOverlapping() {
        return RECURRING_CARD_Y + RECURRING_CARD_H
                <= AFTERMATH_WITH_RECURRING_HEAD_Y
            && AFTERMATH_WITH_RECURRING_HEAD_Y
                + AFTERMATH_WITH_RECURRING_HEAD_H
                <= AFTERMATH_WITH_RECURRING_FACTS_Y
            && AFTERMATH_WITH_RECURRING_FACTS_Y
                + AFTERMATH_WITH_RECURRING_FACTS_H
                <= AFTERMATH_WITH_RECURRING_STATE_Y
            && AFTERMATH_WITH_RECURRING_STATE_Y
                + AFTERMATH_WITH_RECURRING_STATE_H
                < journeyLayoutFor(240).footDividerY();
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

        Component urgencyOrCompletion = state == RequestState.SATISFIED
            ? Component.translatable("hearthstead.request.row.complete")
            : Component.translatable("hearthstead.request.priority."
                + priority.name().toLowerCase(java.util.Locale.ROOT));
        Component headline = Component.translatable(
            "hearthstead.request.row.headline", urgencyOrCompletion,
            Component.translatable("hearthstead.request.type." + type.id()),
            row.requestedCount(), requestItem(row.itemId()));
        Component route = row.equipmentAdapter()
            ? Component.translatable("hearthstead.request.row.route.requester",
                Component.literal(row.requesterName()),
                Component.translatable("hearthstead.profession."
                    + row.professionId()), requestBuildingName(row.targetNameKey(),
                    row.targetPos()))
            : Component.translatable("hearthstead.request.row.route.output",
                requestBuildingName(row.sourceNameKey(), row.sourcePos()),
                requestBuildingName(row.targetNameKey(), row.targetPos()));
        Component detailedRoute = row.equipmentAdapter()
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
        int physicalOwner = row.physicalOwnerWireId();
        Component age = requestAge(row.ageTicks());
        Component courier = row.hasCourier()
            ? row.courierName().isBlank()
                ? Component.translatable("hearthstead.request.courier.assigned")
                : Component.literal(row.courierName())
            : Component.translatable("hearthstead.request.courier.unassigned");
        // Every card always exposes the same five truths. Previously the
        // blocker branch hid the assigned Courier, while the healthy branch
        // hid the concrete stop field entirely.
        Component equipmentReason = row.equipmentReasonWireId() < 0 ? null
            : Component.translatable("hearthstead.equipment.reason." + switch (row.equipmentReasonWireId()) {
                case 0 -> "missing";
                case 1 -> "wrong_tool";
                case 2 -> "worn";
                default -> throw new IllegalArgumentException("unknown equipment reason");
            });
        Component assignment = row.awaitingSource()
            ? Component.translatable("hearthstead.request.row.awaiting_source", courier)
            : switch (physicalOwner) {
                case 1 -> row.hasCourier()
                    ? Component.translatable("hearthstead.request.row.courier.carrying", courier)
                    : Component.translatable("hearthstead.request.row.courier.in_bag");
                case 2 -> Component.translatable("hearthstead.request.row.courier.stored");
                case 0 -> row.hasCourier()
                    ? Component.translatable("hearthstead.request.row.courier.assigned", courier)
                    : Component.translatable("hearthstead.request.row.courier.unassigned");
                default -> row.hasCourier()
                    ? Component.translatable("hearthstead.request.row.courier.location_unknown", courier)
                    : Component.translatable("hearthstead.request.row.courier.location_needs_checking");
            };
        Component stop = state == RequestState.SATISFIED
            ? Component.translatable("hearthstead.request.row.delivery_complete")
            : Component.translatable("hearthstead.request.row.stop",
                stateName, row.awaitingSource() ? equipmentReason : Component.translatable(
                    "hearthstead.request.blocker." + blocker.id()));
        Component ageLine = Component.translatable(
            "hearthstead.request.row.age", age);
        int tone = blocker != RequestBlocker.NONE ? HsUiTokens.WARN
            : state == RequestState.SATISFIED ? HsUiTokens.GOOD
            : priority == RequestPriority.URGENT ? HsUiTokens.BAD
            : priority == RequestPriority.HIGH ? HsUiTokens.ACCENT
            : HsUiTokens.TEXT_MUTED;
        List<Component> tooltip = new ArrayList<>(List.of(headline, detailedRoute, assignment, stop, ageLine));
        if (equipmentReason != null && !row.awaitingSource()) {
            tooltip.add(equipmentReason);
        }
        return new RequestRenderRow(headline, route, assignment, stop,
            List.copyOf(tooltip), tone);
    }

    private static Component requestItem(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        Item item = id == null ? Items.AIR
            : BuiltInRegistries.ITEM.getOptional(id).orElse(Items.AIR);
        return item == Items.AIR
            ? Component.literal(itemId)
            : Component.translatable(item.getDescriptionId());
    }

    private static Component requestBuildingName(String key,
                                                 net.minecraft.core.BlockPos pos) {
        return "hearthstead.request.location.unknown".equals(key)
            ? requestLocation(key, pos) : Component.translatable(key);
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
        // Courier/server updates may populate a previously hidden slot. Reflow
        // only between interactions, never underneath an active carried/dragged stack.
        if (suppliesOpen && menu.getCarried().isEmpty() && !isDragging()
                && supplyContentsChanged()) {
            layoutSupplySlots();
        }
        if (recruitmentPanelOpen && --portraitRefreshTicks <= 0) {
            portraitRefreshTicks = 20;
            recruitmentPortrait = null;
            if (minecraft != null && minecraft.level != null && cachedRecruitmentCard.present()) {
                for (var entity : minecraft.level.entitiesForRendering()) {
                    if (entity instanceof com.hearthstead.entity.SettlerEntity settler
                            && settler.getUUID().equals(cachedRecruitmentCard.travelerId())) {
                        recruitmentPortrait = settler;
                        break;
                    }
                }
            }
        }
        if (peopleTabOpen) {
            boolean canInspect = canInspectResident(selectedPeopleRow());
            if (canInspect != peopleViewInspectable) {
                rebuildSeatWidgets();
            }
        }
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
        boolean raidStatusVisible = journeyTabOpen && !cachedReadinessView.open()
            && shouldShowRaidStatus();
        if (!raidStatusVisible) {
            raidStatusRefreshTicks = 0;
        } else if (++raidStatusRefreshTicks >= RAID_STATUS_REFRESH_INTERVAL_TICKS) {
            raidStatusRefreshTicks = 0;
            requestMayorData();
        }
    }

    static boolean requestLoadTimedOut(boolean panelOpen, boolean loading,
                                       int elapsedTicks) {
        return panelOpen && loading && elapsedTicks >= REQUEST_LOAD_TIMEOUT_TICKS;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (journeyTabOpen && !cachedReadinessView.open() && !shouldShowAftermath()
                && mouseX >= journeyPanelLeft && mouseX < journeyPanelLeft + journeyPanelWidth) {
            int localY = (int) mouseY - journeyPanelTop - JOURNEY_STEPS_TOP;
            int row = localY / (journeyStepHeight + journeyStepGap);
            if (localY >= 0 && localY % (journeyStepHeight + journeyStepGap) < journeyStepHeight
                    && row < 2 && scrollJourneyDescription(row, -(int) Math.signum(dy))) return true;
        }
        int readinessRows = readinessVisibleRows();
        if (journeyTabOpen && cachedReadinessView.open()
            && cachedReadinessBlockers.size() > readinessRows
            && mouseX >= journeyPanelLeft
            && mouseX <= journeyPanelLeft + journeyPanelWidth
            && mouseY >= journeyPanelTop
            && mouseY <= journeyPanelTop + journeyPanelHeight) {
            int before = readinessScroll;
            readinessScroll = Math.max(0, Math.min(
                cachedReadinessBlockers.size() - readinessRows,
                readinessScroll - (int) Math.signum(dy)));
            if (before != readinessScroll) {
                QaClientObserver.markUiTransition("hearth_raid_readiness_scroll");
                return true;
            }
        }
        HearthLayout.Rect body = councilLayout.summary();
        boolean overBody = mouseX >= leftPos + body.x() && mouseX <= leftPos + body.x() + body.width()
            && mouseY >= topPos + body.y() && mouseY <= topPos + body.y() + body.height();
        if (requestPanelOpen && isOverTaskDetail(mouseX, mouseY)) {
            int before = taskDetailScroll;
            taskDetailScroll = Mth.clamp(taskDetailScroll - (int) Math.signum(dy), 0,
                taskDetailMaxScroll());
            if (before != taskDetailScroll) {
                QaClientObserver.markUiTransition("hearth_request_detail_scroll");
                return true;
            }
            // The right pane owns its wheel even at either end; never scroll the left list instead.
            return true;
        }
        if (requestPanelOpen && overBody && cachedRequestRows.size() > taskVisibleRows()) {
            int before = requestScroll;
            requestScroll = Mth.clamp(requestScroll - (int) Math.signum(dy), 0,
                Math.max(0, cachedRequestRows.size() - taskVisibleRows()));
            if (before != requestScroll) {
                QaClientObserver.markUiTransition("hearth_request_ledger_scroll");
                rebuildSeatWidgets();
                return true;
            }
        }
        if (peopleTabOpen && overBody && cachedPeopleRows.size() > peopleVisibleRows()) {
            int before = peopleScroll;
            peopleScroll = Mth.clamp(peopleScroll - (int) Math.signum(dy), 0,
                Math.max(0, cachedPeopleRows.size() - peopleVisibleRows()));
            if (before != peopleScroll) {
                QaClientObserver.markUiTransition("hearth_people_scroll");
                rebuildSeatWidgets();
                return true;
            }
        }
        if (mayorTabOpen && mayorSnapshot != null) {
            int rows = mayorSnapshot.candidates().size();
            if (rows > mayorVisibleRows && mouseX >= mayorPanelLeft
                && mouseX <= mayorPanelLeft + mayorPanelWidth
                && mouseY >= mayorPanelTop
                && mouseY <= mayorPanelTop + mayorPanelHeight) {
                int before = mayorPage;
                changeMayorPage(-(int) Math.signum(dy));
                return before != mayorPage;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, dx, dy);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_PAGE_UP || keyCode == GLFW.GLFW_KEY_PAGE_DOWN) {
            int direction = keyCode == GLFW.GLFW_KEY_PAGE_UP ? -1 : 1;
            if (journeyTabOpen && !cachedReadinessView.open() && !shouldShowAftermath()) {
                scrollJourneyDescription(hasShiftDown() ? 1 : 0, direction);
                return true;
            }
            if (requestPanelOpen) {
                int before = requestScroll;
                requestScroll = Mth.clamp(requestScroll + direction * taskVisibleRows(), 0,
                    Math.max(0, cachedRequestRows.size() - taskVisibleRows()));
                if (before != requestScroll) rebuildSeatWidgets();
                return before != requestScroll || super.keyPressed(keyCode, scanCode, modifiers);
            }
            if (peopleTabOpen) {
                int before = peopleScroll;
                peopleScroll = Mth.clamp(peopleScroll + direction * peopleVisibleRows(), 0,
                    Math.max(0, cachedPeopleRows.size() - peopleVisibleRows()));
                if (before != peopleScroll) rebuildSeatWidgets();
                return before != peopleScroll || super.keyPressed(keyCode, scanCode, modifiers);
            }
            if (mayorTabOpen) {
                int before = mayorPage;
                changeMayorPage(direction);
                return before != mayorPage || super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        modalClickAwaitingRelease = hasOpenPopout();
        if (!modalClickAwaitingRelease) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        // Dispatch only real widgets while a popout is open. Its surface
        // owns covered clicks; outside it, visible chapter/review buttons
        // remain usable. Blank clicks never enter the container's local
        // quick-craft or split-stack state machine, even with a held stack.
        var listeners = isOverOpenPanel(mouseX, mouseY)
            ? latePanelWidgets : children();
        for (var listener : listeners) {
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

    private boolean hasOpenPopout() {
        return recruitmentPanelOpen || mayorTabOpen || journeyTabOpen;
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton,
                               ClickType clickType) {
        // All vanilla inventory mutations converge here: pickup, quick move,
        // number/offhand swaps, drop, creative clone, double-click and drag.
        // A popout dims the whole container, so no background slot may mutate.
        if (!suppliesOpen || hasOpenPopout() || modalClickAwaitingRelease) return;
        super.slotClicked(slot, slotId, mouseButton, clickType);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                double dragX, double dragY) {
        if (hasOpenPopout() || modalClickAwaitingRelease) return true;
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (hasOpenPopout() || modalClickAwaitingRelease) {
            modalClickAwaitingRelease = false;
            // Release the real focused widget without entering the container's
            // pickup/quick-craft release handler behind a just-closed panel.
            if (getFocused() != null) {
                getFocused().mouseReleased(mouseX, mouseY, button);
            }
            setDragging(false);
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private boolean isOverOpenPanel(double mouseX, double mouseY) {
        if (recruitmentPanelOpen) {
            return mouseX >= recruitmentPanelLeft
                && mouseX < recruitmentPanelLeft + RECRUIT_PANEL_W
                && mouseY >= recruitmentPanelTop
                && mouseY < recruitmentPanelTop + RECRUIT_PANEL_H;
        }
        if (mayorTabOpen) {
            return mouseX >= mayorPanelLeft
                && mouseX < mayorPanelLeft + mayorPanelWidth
                && mouseY >= mayorPanelTop
                && mouseY < mayorPanelTop + mayorPanelHeight;
        }
        return journeyTabOpen
            && mouseX >= journeyPanelLeft && mouseX < journeyPanelLeft + journeyPanelWidth
            && mouseY >= journeyPanelTop
            && mouseY < journeyPanelTop + journeyPanelHeight;
    }

    @Override
    public String qaUiState() {
        String view = recruitmentPanelOpen ? "recruitment"
            : mayorTabOpen ? "mayor"
            : peopleTabOpen ? "people"
            : requestPanelOpen ? "requests"
            : journeyTabOpen && cachedReadinessView.open()
                ? "raid_readiness"
            : journeyTabOpen && shouldShowAftermath()
                ? "raid_aftermath"
            : journeyTabOpen ? "journey" : "settlement";
        int panelLeft = recruitmentPanelOpen ? recruitmentPanelLeft
            : mayorTabOpen ? mayorPanelLeft
            : journeyTabOpen ? journeyPanelLeft : -1;
        int panelTop = recruitmentPanelOpen ? recruitmentPanelTop
            : mayorTabOpen ? mayorPanelTop
            : journeyTabOpen ? journeyPanelTop : -1;
        int panelWidth = recruitmentPanelOpen ? RECRUIT_PANEL_W
            : mayorTabOpen ? mayorPanelWidth
            : journeyTabOpen ? journeyPanelWidth : 0;
        int panelHeight = recruitmentPanelOpen ? RECRUIT_PANEL_H
            : mayorTabOpen ? mayorPanelHeight
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
        return "view=" + view + ",suppliesOpen=" + suppliesOpen + ",panel=" + panelLeft + ":" + panelTop
            + ":" + panelWidth + ":" + panelHeight
            + ",ledgerOverlap=" + overlapsLedger + ",hiddenTabs=" + hiddenTabs
            + ",lateWidgets=" + latePanelWidgets.size()
            + ",carriedEmpty=" + menu.getCarried().isEmpty()
            + ",mayorRows=" + mayorVisibleRows
            + ",mayorPage=" + mayorPage
            + ",mayorPages=" + mayorPageCount(mayorSnapshot == null
                ? 0 : mayorSnapshot.candidates().size(), mayorVisibleRows)
            + ",journeyCompact=" + (journeyPanelHeight < JOURNEY_PANEL_H)
            + ",journeyConfirm=" + journeySkipConfirm
            + ",recruitPresent=" + cachedRecruitmentCard.present()
            + ",recruitStatus=" + menu.get(HearthMenu.DATA_RECRUIT_TRANSACTION_STATUS)
            + ",recruitRevision=" + cachedRecruitmentCard.revision()
            + ",recruitPending=" + recruitmentAdmissionPending
            + ",requestOpen=" + cachedRequestView.open()
            + ",requestPanelOpen=" + requestPanelOpen
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
            + ",recurringStatus=" + cachedRecurringStatusView.status().id()
            + ",recurringNight=" + cachedRecurringStatusView.plannedNight()
            + ",recurringCooldown=" + cachedRecurringStatusView.cooldownRemainingTicks()
            + ",aftermathPresent=" + cachedAftermathView.present()
            + ",aftermathNight=" + cachedAftermathView.night()
            + ",aftermathVisible=" + shouldShowAftermath();
    }

    // ------------------------------------------------------------- drawing ---

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        HearthPixelSurface.table(graphics, leftPos, topPos, councilLayout);
        HearthLayout.Rect note = councilLayout.recruitment();
        if (suppliesOpen) {
            HearthPixelSurface.note(graphics, leftPos + note.x(), topPos + note.y(),
                note.width(), note.height());
        } else {
            HearthPixelSurface.priorityNote(graphics, leftPos + note.x(), topPos + note.y(),
                note.width(), note.height());
        }
        if (suppliesOpen) for (var slot : menu.slots) {
            HearthPixelSurface.slot(graphics, leftPos + slot.x - 1, topPos + slot.y - 1);
        }
        // The Mayor popout is NOT drawn here. renderBg runs first in the
        // frame, and AbstractContainerScreen draws slot items and then
        // renderLabels AFTER it -- so a panel painted here gets the
        // settlement's own labels painted straight across it. Seen live in
        // the owner's first session (video 0:24, "veldig dÃ¥rlig UI"):
        // "The seat is empty" through the stores list, "Content" through
        // the candidate cards. The panel draws at the END of render() now,
        // above everything it overlaps.
    }

    private void drawResidentPortrait(GuiGraphics graphics, PeopleRenderRow person, int x, int y, int size) {
        if (minecraft != null && minecraft.level != null) {
            var entity = minecraft.level.getEntity(person.runtimeEntityId());
            if (entity instanceof com.hearthstead.entity.SettlerEntity settler
                    && settler.getUUID().equals(person.id())) {
                var texture = minecraft.getEntityRenderDispatcher().getRenderer(settler).getTextureLocation(settler);
                graphics.blit(texture, x, y, size, size, 8.0F, 8.0F, 8, 8, 128, 64);
                graphics.blit(texture, x, y, size, size, 40.0F, 8.0F, 8, 8, 128, 64);
                return;
            }
        }
        graphics.renderItem(overviewIcons[0], x, y);
    }

    private void renderPeoplePage(GuiGraphics graphics) {
        HearthLayout.Rect body = councilLayout.summary();
        int x = body.x();
        int y = body.y();
        int w = body.width();
        int h = body.height();
        int listW = Math.max(104, w * 3 / 5);
        int rightX = x + listW + 6;
        int rightW = Math.max(72, w - listW - 6);
        ensurePeopleRenderCache(Math.max(1, listW - 80), Math.max(1, rightW - 12));
        HearthMaterials.paper(graphics, x, y, w, h);
        HearthMaterials.header(graphics, x, y, listW, 18);
        graphics.drawString(font, "PEOPLE", x + 7, y + 5, HearthPixelSurface.LIGHT_TEXT, false);
        graphics.drawString(font, peopleCountLine, x + 50, y + 5,
            HearthPixelSurface.LIGHT_TEXT, false);
        graphics.drawString(font, peopleRangeLine.text(), x + listW - 6
            - peopleRangeLine.width(), y + 5, 0xFFD2DEBE, false);
        HearthMaterials.header(graphics, rightX, y, rightW, 18);
        graphics.drawString(font, peopleDetailHeading, rightX + 6, y + 5,
            HearthPixelSurface.LIGHT_TEXT, false);
        for (int row = 0; row < peopleVisibleRows() && peopleScroll + row < cachedPeopleRows.size(); row++) {
            PeopleRenderRow person = cachedPeopleRows.get(peopleScroll + row);
            int rowY = y + 42 + row * 24;
            ModalPixels.card(graphics, x + 4, rowY, listW - 11, 20,
                person.id().equals(selectedResidentId));
            drawResidentPortrait(graphics, person, x + 7, rowY + 2, 16);
            graphics.drawString(font, person.listName(), x + 27, rowY + 3, ModalPixels.INK, false);
            graphics.drawString(font, person.listProfession(), x + 27, rowY + 12,
                ModalPixels.GOOD, false);
            graphics.drawString(font, person.listStatus(), x + listW - 52, rowY + 7,
                person.loaded() ? ModalPixels.GOOD : ModalPixels.MUTED, false);
        }
        if (cachedPeopleRows.size() > peopleVisibleRows()) {
            int total = cachedPeopleRows.size();
            int visible = peopleVisibleRows();
            ModalPixels.scrollbar(graphics, x + listW - 6, y + 42,
                visible * 24 - 4, Math.min(1.0F, (float) visible / total),
                (float) peopleScroll / Math.max(1, total - visible), false);
        }
        PeopleRenderRow selected = selectedPeopleRow();
        if (selected == null) {
            graphics.drawString(font, cachedPeopleRows.isEmpty() ? peopleEmptyLine : peopleSelectLine,
                rightX + 6, y + 34, ModalPixels.MUTED, false);
            return;
        }
        graphics.drawString(font, selected.detailName(), rightX + 6, y + 34, ModalPixels.INK, false);
        graphics.drawString(font, selected.detailProfession(), rightX + 6, y + 47, ModalPixels.GOOD, false);
        graphics.drawString(font, selected.detailStatus(), rightX + 6, y + 60,
            selected.loaded() ? ModalPixels.ACCENT : ModalPixels.MUTED, false);
        graphics.drawString(font, canInspectResident(selected)
                ? selected.detailHint() : selected.loaded() ? peopleMoveCloserLine : selected.detailHint(),
            rightX + 6, y + 73, ModalPixels.MUTED, false);
        // Detail lines end before the responsive action band at h - 42 on every supported viewport.
        int footerTop = y + h - 42;
        graphics.drawString(font, peopleMayorLine, rightX + 6, footerTop - 21,
            ModalPixels.GOOD, false);
        graphics.drawString(font, peopleTravelerLine, rightX + 6, footerTop - 10,
            ModalPixels.ACCENT, false);
    }

    private void renderTasksPage(GuiGraphics graphics) {
        HearthLayout.Rect body = councilLayout.summary();
        int x = body.x();
        int y = body.y();
        int w = body.width();
        int h = body.height();
        int listW = Math.max(104, w * 3 / 5);
        int rightX = x + listW + 6;
        int rightW = Math.max(72, w - listW - 6);
        ensureTaskPageRenderCache(Math.max(1, listW - 16), Math.max(1, rightW - 12));
        HearthMaterials.paper(graphics, x, y, w, h);
        HearthMaterials.header(graphics, x, y, listW, 24);
        graphics.drawString(font, "TASKS", x + 7, y + 8, HearthPixelSurface.LIGHT_TEXT, false);
        graphics.drawString(font, taskMetaLine, x + 52, y + 8,
            HearthPixelSurface.LIGHT_TEXT, false);
        HearthMaterials.header(graphics, rightX, y, rightW, 24);
        graphics.drawString(font, taskDetailHeading, rightX + 6, y + 8,
            HearthPixelSurface.LIGHT_TEXT, false);
        for (int row = 0; row < taskVisibleRows() && row + requestScroll < cachedTaskRows.size(); row++) {
            int index = row + requestScroll;
            TaskPageRow request = cachedTaskRows.get(index);
            int rowY = y + 27 + row * 24;
            ModalPixels.card(graphics, x + 4, rowY, listW - 8, 20, index == selectedRequestIndex);
            graphics.drawString(font, request.list(), x + 8, rowY + 6, ModalPixels.INK, false);
        }
        if (cachedTaskRows.isEmpty()) {
            graphics.drawString(font, taskEmptyLine, rightX + 6, y + 34, ModalPixels.MUTED, false);
            return;
        }
        TaskPageRow selected = cachedTaskRows.get(Mth.clamp(selectedRequestIndex, 0,
            cachedTaskRows.size() - 1));
        int visibleDetailLines = taskDetailVisibleLines();
        int maxDetailScroll = Math.max(0, selected.detailLines().size() - visibleDetailLines);
        taskDetailScroll = Mth.clamp(taskDetailScroll, 0, maxDetailScroll);
        int detailY = y + 34;
        int detailBottom = y + h - 6;
        for (int index = taskDetailScroll; index < selected.detailLines().size()
                && index < taskDetailScroll + visibleDetailLines; index++) {
            if (detailY + HsUiTokens.TEXT_H > detailBottom) break;
            TaskPageLine line = selected.detailLines().get(index);
            graphics.drawString(font, line.text(), rightX + 6, detailY, line.tone(), false);
            detailY += 9;
        }
        if (maxDetailScroll > 0) {
            ModalPixels.scrollbar(graphics, x + w - 5, y + 34,
                Math.max(8, detailBottom - (y + 34)),
                (float) visibleDetailLines / selected.detailLines().size(),
                (float) taskDetailScroll / maxDetailScroll, false);
        }
    }

    /** People is a main-body page; retained only for old render-call compatibility. */
    private void renderPeoplePanel(GuiGraphics graphics, int mouseX, int mouseY) {
        renderPeoplePage(graphics);
    }

    private void renderMayorPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        ensureMayorRenderModels();
        int pl = mayorPanelLeft;
        int pt = mayorPanelTop;
        int pw = mayorPanelWidth;
        ModalPixels.window(graphics, pl, pt, pw, mayorPanelHeight);
        ModalPixels.header(graphics, pl, pt, pw, MAYOR_RULE_Y);
        graphics.drawString(font, mayorRenderModel.title().text(),
            pl + MAYOR_PAD, pt + MAYOR_TITLE_Y,
            HearthPixelSurface.LIGHT_TEXT, false);
        if (pw >= 360) {
            graphics.drawString(font, mayorRenderModel.choiceRule().text(),
                pl + MAYOR_PAD, pt + MAYOR_TITLE_Y + 14,
                HearthPixelSurface.LIGHT_TEXT, false);
        }

        drawMayorStatus(graphics, pl, pt);

        ModalPixels.divider(graphics, pl + MAYOR_PAD, pt + MAYOR_RULE_Y,
            pw - 2 * MAYOR_PAD);
        graphics.drawString(font, mayorRenderModel.candidatesTitle().text(),
            pl + MAYOR_PAD, pt + MAYOR_LABEL_Y, ModalPixels.MUTED, false);

        List<HearthMayorSnapshot.Candidate> candidates = mayorSnapshot == null
            ? List.of() : mayorSnapshot.candidates();
        if (mayorSnapshot == null) {
            graphics.drawString(font, mayorRenderModel.loading().text(),
                pl + MAYOR_PAD, pt + MAYOR_LIST_TOP,
                ModalPixels.MUTED, false);
        } else if (candidates.isEmpty()) {
            graphics.drawString(font, mayorRenderModel.emptyCandidates().text(),
                pl + MAYOR_PAD, pt + MAYOR_LIST_TOP,
                ModalPixels.MUTED, false);
        }
        int pageStart = mayorPageStart(mayorPage, candidates.size(),
            mayorVisibleRows);
        for (int row = 0; row < mayorVisibleRows
            && row + pageStart < candidates.size(); row++) {
            int y = pt + MAYOR_LIST_TOP + row * MAYOR_CARD_STEP;
            int cardWidth = pw - 2 * MAYOR_PAD;
            boolean hovered = mouseX >= pl + MAYOR_CARD_X
                && mouseX <= pl + MAYOR_CARD_X + cardWidth
                && mouseY >= y && mouseY <= y + MAYOR_CARD_H;
            ModalPixels.card(graphics, pl + MAYOR_CARD_X, y, cardWidth,
                MAYOR_CARD_H, hovered);
            MayorRenderRow rendered = mayorRenderModel.candidates()
                .get(row + pageStart);
            ModalPixels.inset(graphics, pl + MAYOR_AVATAR_X, y + 7,
                MAYOR_AVATAR_SIZE, MAYOR_AVATAR_SIZE);
            graphics.drawString(font, rendered.initial().text(),
                pl + MAYOR_AVATAR_X
                    + (MAYOR_AVATAR_SIZE - rendered.initial().width()) / 2,
                y + 15, ModalPixels.ink(rendered.tone().colour()), false);
            graphics.drawString(font, rendered.name().text(),
                pl + MAYOR_TEXT_X, y + 3, ModalPixels.INK, false);
            graphics.drawString(font, rendered.profession().text(),
                pl + MAYOR_TEXT_X, y + 14, ModalPixels.GOOD, false);
            graphics.drawString(font, rendered.boon().text(),
                pl + MAYOR_TEXT_X, y + 26, ModalPixels.ACCENT, false);
            graphics.drawString(font, rendered.knack().text(),
                pl + mayorInfoX(pw), y + 26,
                ModalPixels.ink(rendered.tone().colour()), false);
        }

        ModalPixels.divider(graphics, pl + MAYOR_PAD, pt + mayorFoot,
            pw - 2 * MAYOR_PAD);
        int previousRight = MAYOR_PAD
            + Math.min(MAYOR_NAV_W, Math.max(48, pw / 5));
        int closeWidth = Math.min(MAYOR_CLOSE_W, Math.max(44, pw / 5));
        int navWidth = Math.min(MAYOR_NAV_W, Math.max(48, pw / 5));
        int nextX = pw - MAYOR_PAD - closeWidth - MAYOR_NAV_GAP - navWidth;
        int pageBoxLeft = previousRight + MAYOR_NAV_GAP;
        int pageBoxWidth = Math.max(1, nextX - MAYOR_NAV_GAP - pageBoxLeft);
        graphics.drawString(font, mayorRenderModel.page().text(),
            pl + pageBoxLeft
                + (pageBoxWidth - mayorRenderModel.page().width()) / 2,
            pt + mayorButtonY + 6, ModalPixels.MUTED, false);
    }

    private void renderRecruitmentPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        int pl = recruitmentPanelLeft;
        int pt = recruitmentPanelTop;
        ModalPixels.window(graphics, pl, pt, RECRUIT_PANEL_W, RECRUIT_PANEL_H);
        renderModalTitle(graphics, RECRUIT_CARD_TITLE, pl, pt + 12,
            RECRUIT_PANEL_W, RECRUIT_PANEL_PAD);
        ModalPixels.divider(graphics, pl + RECRUIT_PANEL_PAD, pt + 26,
            RECRUIT_PANEL_W - 2 * RECRUIT_PANEL_PAD);
        if (!cachedRecruitmentCard.present()) {
            return;
        }

        renderRequestLabel(graphics, recruitmentNameLine,
            pl + RECRUIT_PANEL_PAD + 40, pt + 35,
            RECRUIT_PANEL_W - 2 * RECRUIT_PANEL_PAD - 40,
            ModalPixels.INK);
        renderRequestLabel(graphics, recruitmentStageLine,
            pl + RECRUIT_PANEL_PAD + 40, pt + 49,
            RECRUIT_PANEL_W - 2 * RECRUIT_PANEL_PAD - 40,
            ModalPixels.ACCENT);

        ModalPixels.inset(graphics, pl + RECRUIT_PANEL_PAD, pt + 30, 34, 31);
        if (recruitmentPortrait != null && recruitmentPortrait.isAlive()
                && recruitmentPortrait.getUUID().equals(cachedRecruitmentCard.travelerId())) {
            com.hearthstead.client.render.SettlerRenderer.withoutPortraitLabels(recruitmentPortrait, () ->
                net.minecraft.client.gui.screens.inventory.InventoryScreen.renderEntityInInventoryFollowsMouse(
                    graphics, pl + RECRUIT_PANEL_PAD + 1, pt + 31,
                    pl + RECRUIT_PANEL_PAD + 33, pt + 60,
                    13, 0.0625F, mouseX, mouseY, recruitmentPortrait));
        } else {
            graphics.renderItem(new ItemStack(Items.LEATHER_BOOTS), pl + RECRUIT_PANEL_PAD + 9, pt + 37);
        }
        int insetX = pl + RECRUIT_PANEL_PAD;
        int insetY = pt + 64;
        int insetW = RECRUIT_PANEL_W - 2 * RECRUIT_PANEL_PAD;
        ModalPixels.inset(graphics, insetX, insetY, insetW, 119);
        renderRequestLabel(graphics, recruitmentPriceTitle,
            insetX + 6, insetY + 7, insetW - 12, ModalPixels.MUTED);
        for (int i = 0; i < recruitmentCostLines.size(); i++) {
            renderRequestLabel(graphics, recruitmentCostLines.get(i),
                insetX + 6 + (i % 2) * (insetW / 2), insetY + 19 + (i / 2) * 10, insetW / 2 - 12,
                ModalPixels.INK);
        }
        renderRequestLabel(graphics, recruitmentAptitudeLine,
            insetX + 6, insetY + 40, insetW - 12, ModalPixels.INK);
        renderRequestLabel(graphics, recruitmentQuoteReasonLine,
            insetX + 6, insetY + 52, insetW - 12, ModalPixels.MUTED);
        renderRequestLabel(graphics, recruitmentBedsLine,
            insetX + 6, insetY + 64, insetW - 12, ModalPixels.MUTED);
        renderRequestLabel(graphics, recruitmentFoodLine,
            insetX + 6, insetY + 76, insetW - 12, ModalPixels.MUTED);
        renderRequestLabel(graphics, recruitmentTimeLine,
            insetX + 6, insetY + 88, insetW - 12, ModalPixels.MUTED);
        int blockerColour = cachedRecruitmentCard.mayAdmit()
            ? ModalPixels.GOOD : ModalPixels.WARN;
        for (int i = 0; i < Math.min(2, recruitmentBlockerLines.size()); i++) {
            graphics.drawString(font, recruitmentBlockerLines.get(i),
                insetX + 6, insetY + 101 + i * 9, blockerColour, false);
        }
    }

    private void renderRequestPanel(GuiGraphics graphics, int mouseX,
                                    int mouseY) {
        int pl = requestPanelLeft;
        int pt = requestPanelTop;
        ModalPixels.window(graphics, pl, pt, requestPanelWidth, REQUEST_PANEL_H);
        renderModalTitle(graphics, REQUEST_TITLE, pl, pt + REQUEST_TITLE_Y,
            requestPanelWidth, REQUEST_PANEL_PAD + REQUEST_REFRESH_W + 4);
        ModalPixels.divider(graphics, pl + REQUEST_PANEL_PAD,
            pt + REQUEST_DIV1_Y, requestPanelWidth - 2 * REQUEST_PANEL_PAD);

        Component meta = requestLoading ? REQUEST_LOADING : requestMetaLine;
        renderRequestLabel(graphics, meta, pl + REQUEST_PANEL_PAD,
            pt + REQUEST_META_Y,
            requestPanelWidth - 2 * REQUEST_PANEL_PAD,
            cachedRequestView.quarantined() || requestUnavailable
                ? ModalPixels.WARN
                : ModalPixels.MUTED);
        ModalPixels.divider(graphics, pl + REQUEST_PANEL_PAD,
            pt + REQUEST_DIV2_Y, requestPanelWidth - 2 * REQUEST_PANEL_PAD);

        if (!requestLoading && !requestUnavailable
            && cachedRequestRows.isEmpty()) {
            renderRequestLabel(graphics, REQUEST_EMPTY,
                pl + REQUEST_PANEL_PAD, pt + REQUEST_LIST_TOP + 8,
                requestPanelWidth - 2 * REQUEST_PANEL_PAD,
                ModalPixels.MUTED);
        }
        for (int row = 0; row < REQUEST_MAX_ROWS
            && row + requestScroll < cachedRequestRows.size(); row++) {
            RequestRenderRow cached = cachedRequestRows.get(row + requestScroll);
            int y = pt + REQUEST_LIST_TOP + row * REQUEST_CARD_STEP;
            boolean hovered = mouseX >= pl + REQUEST_PANEL_PAD
                && mouseX < pl + requestPanelWidth - REQUEST_PANEL_PAD
                && mouseY >= y && mouseY < y + REQUEST_CARD_H;
            ModalPixels.card(graphics, pl + REQUEST_PANEL_PAD, y,
                requestPanelWidth - 2 * REQUEST_PANEL_PAD
                    - HsUiTokens.SCROLL_W - 3,
                REQUEST_CARD_H, hovered);
            graphics.fill(pl + REQUEST_PANEL_PAD, y,
                pl + REQUEST_PANEL_PAD + 3, y + REQUEST_CARD_H,
                ModalPixels.ink(cached.tone()));
            int textX = pl + REQUEST_PANEL_PAD + 7;
            int textW = requestPanelWidth - 2 * REQUEST_PANEL_PAD
                - HsUiTokens.SCROLL_W - 13;
            renderRequestLabel(graphics, cached.headline(), textX, y + 3,
                textW, ModalPixels.INK);
            renderRequestLabel(graphics, cached.route(), textX, y + 13,
                textW, ModalPixels.MUTED);
            renderRequestLabel(graphics, cached.assignment(), textX, y + 23,
                textW, ModalPixels.MUTED);
            renderRequestLabel(graphics, cached.stop(), textX, y + 33,
                textW, ModalPixels.ink(cached.tone()));
        }

        int total = cachedRequestRows.size();
        ModalPixels.scrollbar(graphics,
            pl + requestPanelWidth - REQUEST_PANEL_PAD - HsUiTokens.SCROLL_W,
            pt + REQUEST_LIST_TOP,
            REQUEST_MAX_ROWS * REQUEST_CARD_STEP - 4,
            total == 0 ? 1.0F
                : Math.min(1.0F, (float) REQUEST_MAX_ROWS / total),
            total <= REQUEST_MAX_ROWS ? 0.0F
                : (float) requestScroll / (total - REQUEST_MAX_ROWS), false);
        ModalPixels.divider(graphics, pl + REQUEST_PANEL_PAD,
            pt + REQUEST_FOOT_DIV_Y,
            requestPanelWidth - 2 * REQUEST_PANEL_PAD);
        renderRequestLabel(graphics,
            requestLoading ? REQUEST_LOADING : requestFooterLine,
            pl + REQUEST_PANEL_PAD, pt + REQUEST_FOOT_Y,
            requestPanelWidth - 2 * REQUEST_PANEL_PAD,
            ModalPixels.MUTED);
    }

    /** Pale request surfaces need solid ink; preserve the existing fitting budget. */
    private void renderRequestLabel(GuiGraphics graphics, Component text,
                                    int x, int y, int width, int colour) {
        Component shown = font.width(text) <= width
            ? text
            : Component.literal(font.plainSubstrByWidth(text.getString(),
                width - font.width("...")) + "...");
        graphics.drawString(font, shown, x, y, colour, false);
    }

    /**
     * Full cached row text for narrow translations. No Components or Lists
     * are allocated on the render path; the tooltip list was built with the
     * server snapshot and is only painted while an actual card is hovered.
     */
    private void renderRequestRowTooltip(GuiGraphics graphics, int mouseX,
                                         int mouseY) {
        int cardLeft = requestPanelLeft + REQUEST_PANEL_PAD;
        int cardRight = requestPanelLeft + requestPanelWidth
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
        ModalPixels.window(graphics, pl, pt, journeyPanelWidth,
            journeyPanelHeight);
        renderModalTitle(graphics, READINESS_TITLE, pl, pt + JOURNEY_TITLE_Y,
            journeyPanelWidth, JOURNEY_PAD);
        ModalPixels.divider(graphics, pl + JOURNEY_PAD, pt + JOURNEY_DIV1_Y,
            journeyPanelWidth - 2 * JOURNEY_PAD);
        graphics.drawString(font, readinessRenderModel.meta().text(),
            pl + JOURNEY_PAD, pt + READINESS_META_Y,
            cachedReadinessView.ready() ? ModalPixels.GOOD : ModalPixels.WARN,
            false);
        graphics.drawString(font, readinessRenderModel.metrics().text(),
            pl + JOURNEY_PAD, pt + READINESS_META_Y + 11,
            ModalPixels.MUTED, false);
        ModalPixels.divider(graphics, pl + JOURNEY_PAD, pt + READINESS_DIV2_Y,
            journeyPanelWidth - 2 * JOURNEY_PAD);

        int visibleRows = readinessVisibleRows();
        if (cachedReadinessBlockers.isEmpty()) {
            int cardX = pl + JOURNEY_PAD;
            int cardY = pt + READINESS_LIST_TOP;
            int cardW = journeyPanelWidth - 2 * JOURNEY_PAD;
            ModalPixels.card(graphics, cardX, cardY, cardW, 48, false);
            graphics.fill(cardX, cardY, cardX + 3, cardY + 48,
                ModalPixels.GOOD);
            HsUi.drawLines(graphics, font, readinessRenderModel.clear(),
                cardX + 9, cardY + 8, ModalPixels.INK);
        } else {
            for (int row = 0; row < visibleRows
                && row + readinessScroll < cachedReadinessBlockers.size();
                    row++) {
                int y = pt + READINESS_LIST_TOP + row * READINESS_CARD_STEP;
                int x = pl + JOURNEY_PAD;
                int w = journeyPanelWidth - 2 * JOURNEY_PAD
                    - HsUiTokens.SCROLL_W - 3;
                boolean hovered = mouseX >= x && mouseX <= x + w
                    && mouseY >= y && mouseY <= y + READINESS_CARD_H;
                ModalPixels.card(graphics, x, y, w, READINESS_CARD_H, hovered);
                graphics.fill(x, y, x + 3, y + READINESS_CARD_H,
                    ModalPixels.BAD);
                HsUi.drawLines(graphics, font,
                    readinessRenderModel.blockers().get(row + readinessScroll),
                    x + 8, y + 4, ModalPixels.INK);
            }
            int total = cachedReadinessBlockers.size();
            ModalPixels.scrollbar(graphics,
                pl + journeyPanelWidth - JOURNEY_PAD - HsUiTokens.SCROLL_W,
                pt + READINESS_LIST_TOP,
                visibleRows * READINESS_CARD_STEP - 4,
                Math.min(1.0F, (float) visibleRows / total),
                total <= visibleRows ? 0.0F
                    : (float) readinessScroll / (total - visibleRows), false);
        }

        ModalPixels.divider(graphics, pl + JOURNEY_PAD,
            pt + journeyFootDividerY, journeyPanelWidth - 2 * JOURNEY_PAD);
        graphics.drawString(font, readinessRenderModel.footer().text(),
            pl + JOURNEY_PAD, pt + journeyFootY,
            cachedReadinessView.committed() ? ModalPixels.GOOD
                : ModalPixels.MUTED, false);
    }

    private void renderAftermathPanel(GuiGraphics graphics) {
        ensureRecurringStatusRenderModel();
        ensureAftermathRenderModel();
        int pl = journeyPanelLeft;
        int pt = journeyPanelTop;
        int cardX = pl + JOURNEY_PAD;
        int cardW = journeyPanelWidth - 2 * JOURNEY_PAD;
        boolean recurring = shouldShowRecurringStatus();
        boolean aftermath = shouldShowAftermath();
        int outcome = cachedAftermathView.held()
            ? ModalPixels.GOOD : ModalPixels.BAD;
        int headY = recurring ? AFTERMATH_WITH_RECURRING_HEAD_Y
            : AFTERMATH_HEAD_Y;
        int headH = recurring ? AFTERMATH_WITH_RECURRING_HEAD_H
            : AFTERMATH_HEAD_H;
        int factsY = recurring ? AFTERMATH_WITH_RECURRING_FACTS_Y
            : AFTERMATH_FACTS_Y;
        int factsH = recurring ? AFTERMATH_WITH_RECURRING_FACTS_H
            : AFTERMATH_FACTS_H;
        int stateY = recurring ? AFTERMATH_WITH_RECURRING_STATE_Y
            : AFTERMATH_STATE_Y;
        int stateH = recurring ? AFTERMATH_WITH_RECURRING_STATE_H
            : AFTERMATH_STATE_H;

        ModalPixels.window(graphics, pl, pt, journeyPanelWidth,
            journeyPanelHeight);
        renderModalTitle(graphics, aftermath ? AFTERMATH_TITLE : RECURRING_TITLE,
            pl, pt + JOURNEY_TITLE_Y, journeyPanelWidth, JOURNEY_PAD);
        ModalPixels.divider(graphics, cardX, pt + JOURNEY_DIV1_Y, cardW);

        if (recurring) {
            ModalPixels.card(graphics, cardX, pt + RECURRING_CARD_Y, cardW,
                RECURRING_CARD_H, false);
            graphics.fill(cardX, pt + RECURRING_CARD_Y, cardX + 3,
                pt + RECURRING_CARD_Y + RECURRING_CARD_H,
                recurringStatusTone());
            graphics.drawString(font, recurringRenderModel.status().text(),
                cardX + 9, pt + RECURRING_CARD_Y + 4,
                recurringStatusTone(), false);
            graphics.drawString(font, recurringRenderModel.detail().text(),
                cardX + 9, pt + RECURRING_CARD_Y + 15,
                ModalPixels.MUTED, false);
        }
        if (!aftermath) {
            return;
        }

        ModalPixels.card(graphics, cardX, pt + headY, cardW, headH, false);
        graphics.fill(cardX, pt + headY, cardX + 3, pt + headY + headH,
            outcome);
        graphics.drawString(font, aftermathRenderModel.status().text(),
            cardX + 9, pt + headY + 6, outcome, false);
        graphics.drawString(font, aftermathRenderModel.night().text(),
            cardX + cardW - 8 - aftermathRenderModel.night().width(),
            pt + headY + 6, ModalPixels.MUTED, false);
        graphics.drawString(font, aftermathRenderModel.captain().text(),
            cardX + 9, pt + headY + (recurring ? 18 : 20),
            ModalPixels.INK, false);

        ModalPixels.card(graphics, cardX, pt + factsY, cardW, factsH, false);
        graphics.drawString(font, aftermathRenderModel.objective().text(),
            cardX + 8, pt + factsY + (recurring ? 4 : 6),
            ModalPixels.ACCENT, false);
        if (recurring) {
            graphics.drawString(font, aftermathRenderModel.compactImpact().text(),
                cardX + 8, pt + factsY + 16, ModalPixels.MUTED, false);
        } else {
            HsUi.drawLines(graphics, font, aftermathRenderModel.impact(),
                cardX + 8, pt + factsY + 21, ModalPixels.MUTED);
        }

        ModalPixels.card(graphics, cardX, pt + stateY, cardW, stateH, false);
        graphics.drawString(font, aftermathRenderModel.threat().text(),
            cardX + 8, pt + stateY + (recurring ? 4 : 6), ModalPixels.INK, false);
        if (recurring) {
            graphics.drawString(font, aftermathRenderModel.compactReward().text(),
                cardX + 8, pt + stateY + 16,
                cachedAftermathView.rewardStatus()
                        == HearthMayorSnapshot.AftermathView.RewardStatus.UNAVAILABLE
                    ? ModalPixels.BAD : ModalPixels.MUTED, false);
        } else {
            HsUi.drawLines(graphics, font, aftermathRenderModel.reward(),
                cardX + 8, pt + stateY + 20,
                cachedAftermathView.rewardStatus()
                        == HearthMayorSnapshot.AftermathView.RewardStatus.UNAVAILABLE
                    ? ModalPixels.BAD : ModalPixels.MUTED);
        }

        ModalPixels.divider(graphics, cardX, pt + journeyFootDividerY, cardW);
        HsUi.drawLines(graphics, font, aftermathRenderModel.road(),
            cardX, pt + journeyFootY, ModalPixels.MUTED);
    }

    private int recurringStatusTone() {
        return switch (cachedRecurringStatusView.status()) {
            case RECOVERING, WARNED -> ModalPixels.WARN;
            case QUEUED -> ModalPixels.ACCENT;
            case ACTIVE, BLOCKED -> ModalPixels.BAD;
            case NONE -> ModalPixels.MUTED;
        };
    }

    private int journeyDescriptionRows() {
        int textTop = journeyStepHeight < JOURNEY_STEP_H ? 16 : 21;
        return Math.max(1, (journeyStepHeight - textTop - 4) / 9);
    }

    private boolean scrollJourneyDescription(int row, int direction) {
        ensureJourneyRenderModel();
        if (row < 0 || row >= journeyRenderModel.steps().size()) return false;
        int before = journeyDescriptionScroll[row];
        journeyDescriptionScroll[row] = Mth.clamp(before + direction, 0,
            Math.max(0, journeyRenderModel.steps().get(row).description().size() - journeyDescriptionRows()));
        return before != journeyDescriptionScroll[row];
    }

    private void renderJourneyPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        if (cachedReadinessView.open()) {
            renderReadinessPanel(graphics, mouseX, mouseY);
            return;
        }
        if (shouldShowRaidStatus()) {
            renderAftermathPanel(graphics);
            return;
        }
        ensureJourneyRenderModel();
        int pl = journeyPanelLeft;
        int pt = journeyPanelTop;
        ModalPixels.window(graphics, pl, pt, journeyPanelWidth,
            journeyPanelHeight);
        renderModalTitle(graphics, JOURNEY_TITLE, pl, pt + JOURNEY_TITLE_Y,
            journeyPanelWidth, JOURNEY_PAD);
        ModalPixels.divider(graphics, pl + JOURNEY_PAD, pt + JOURNEY_DIV1_Y,
            journeyPanelWidth - 2 * JOURNEY_PAD);
        if (journeyRenderModel.chapter().width() > 0) {
            graphics.drawString(font, journeyRenderModel.chapter().text(),
                pl + JOURNEY_PAD, pt + JOURNEY_INTRO_Y,
                ModalPixels.ACCENT, false);
        }
        graphics.drawString(font, journeyRenderModel.progress().text(),
            pl + journeyPanelWidth - JOURNEY_PAD
                - journeyRenderModel.progress().width(),
            pt + JOURNEY_INTRO_Y, ModalPixels.MUTED, false);

        graphics.drawString(font, journeyScrollHint.text(), pl + JOURNEY_PAD,
            pt + JOURNEY_INTRO_Y + 15, ModalPixels.MUTED, false);
        int spineX = pl + JOURNEY_PAD + 6;
        int cardX = pl + JOURNEY_PAD + 22;
        int cardW = journeyPanelWidth - 2 * JOURNEY_PAD - 22;

        int visible = journeyRenderModel.steps().size();
        for (int row = 0; row < visible; row++) {
            JourneyRenderRow step = journeyRenderModel.steps().get(row);
            int y = pt + JOURNEY_STEPS_TOP
                + row * (journeyStepHeight + journeyStepGap);
            boolean current = row == 0;
            if (row == 0 && visible > 1) {
                graphics.fill(spineX + 2, y + 18, spineX + 4,
                    y + journeyStepHeight + journeyStepGap + 2,
                    ModalPixels.ACCENT);
            }
            graphics.fill(spineX, y + 11, spineX + 5, y + 16,
                current ? ModalPixels.GOOD : ModalPixels.MUTED);

            if (current) {
                int edge = 0xFF719267;
                graphics.fill(cardX - 1, y - 1, cardX + cardW + 1, y, edge);
                graphics.fill(cardX - 1, y + journeyStepHeight,
                    cardX + cardW + 1, y + journeyStepHeight + 1, edge);
                graphics.fill(cardX - 1, y, cardX,
                    y + journeyStepHeight, edge);
                graphics.fill(cardX + cardW, y, cardX + cardW + 1,
                    y + journeyStepHeight, edge);
            }
            ModalPixels.card(graphics, cardX, y, cardW, journeyStepHeight, current);
            int titleColour = current ? ModalPixels.ACCENT : ModalPixels.MUTED;
            graphics.drawString(font, step.title().text(), cardX + 7,
                y + (journeyStepHeight < JOURNEY_STEP_H ? 3 : 6),
                titleColour, false);
            int visibleLines = journeyDescriptionRows();
            int maxScroll = Math.max(0, step.description().size() - visibleLines);
            int firstLine = journeyDescriptionScroll[row] = Mth.clamp(journeyDescriptionScroll[row], 0, maxScroll);
            int textY = y + (journeyStepHeight < JOURNEY_STEP_H ? 16 : 21);
            for (int line = firstLine; line < Math.min(step.description().size(), firstLine + visibleLines); line++) {
                graphics.drawString(font, step.description().get(line), cardX + 7,
                    textY + (line - firstLine) * 9, ModalPixels.MUTED, false);
            }
            if (maxScroll > 0) ModalPixels.scrollbar(graphics, cardX + cardW - HsUiTokens.SCROLL_W - 2, textY,
                visibleLines * 9, (float) visibleLines / step.description().size(),
                (float) firstLine / maxScroll, false);
            graphics.drawString(font, step.state().text(),
                cardX + cardW - 7 - step.state().width(),
                y + (journeyStepHeight < JOURNEY_STEP_H ? 3 : 6),
                titleColour, false);
        }

        ModalPixels.divider(graphics, pl + JOURNEY_PAD,
            pt + journeyFootDividerY, journeyPanelWidth - 2 * JOURNEY_PAD);
        graphics.drawString(font, journeyRenderModel.footer().text(),
            pl + JOURNEY_PAD, pt + journeyFootY,
            journeyRenderModel.mode() == JourneyPresentationMode.QUARANTINED
                ? ModalPixels.BAD : ModalPixels.MUTED, false);
    }

    /**
     * The seat itself: who holds it (name, boon, tenure or settling
     * countdown), or that it is vacant, or that the settlement is in
     * mourning and the reason Appoint is disabled below.
     */
    private void drawMayorStatus(GuiGraphics graphics, int pl, int pt) {
        int x = pl + mayorPanelWidth - MAYOR_PAD - MAYOR_STATUS_W;
        int y = pt + MAYOR_STATUS_Y;
        int w = MAYOR_STATUS_W;
        ModalPixels.inset(graphics, x, y, w, MAYOR_STATUS_H);
        MayorStatusRenderModel model = mayorStatusRenderModel;
        if (model.kind() == 0) {
            return;
        }
        if (model.kind() == 1) {
            graphics.drawString(font, model.first().text(), x + 6, y + 5,
                ModalPixels.WARN, false);
            graphics.drawString(font, model.second().text(), x + 6, y + 18,
                ModalPixels.MUTED, false);
            return;
        }
        if (model.kind() == 2) {
            graphics.drawString(font, model.first().text(), x + 6, y + 5,
                ModalPixels.INK, false);
            graphics.drawString(font, model.second().text(), x + 6, y + 18,
                ModalPixels.MUTED, false);
            return;
        }
        graphics.drawString(font, model.first().text(), x + 6, y + 5,
            ModalPixels.INK, false);
        graphics.drawString(font, model.second().text(), x + 6, y + 18,
            ModalPixels.ink(model.secondTone()), false);
    }

    private static Component boonName(String key) {
        return Component.translatable("hearthstead.mayor.boon." + key);
    }

    private static Component boonDesc(String key) {
        return Component.translatable("hearthstead.mayor.boon." + key + ".desc");
    }

    /** "2d 4h", "4h", or "soon" -- ticks-to-days uses Minecraft's own 24000-tick day. */
    /** Formats one already-measured server cooldown without starting a client clock. */
    private static Component formatServerCooldown(long remainingTicks) {
        long totalSeconds = Math.max(0L, remainingTicks) / 20L;
        return Component.literal(String.format(Locale.ROOT, "%d:%02d",
            totalSeconds / 60L, totalSeconds % 60L));
    }
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
        if (!mayorRenderCacheMatches(mayorRenderSnapshot,
            mayorRenderLanguage, mayorRenderPanelWidth,
            mayorRenderPanelHeight, mayorRenderVisibleRows, mayorRenderPage,
            mayorSnapshot, language, mayorPanelWidth, mayorPanelHeight,
            mayorVisibleRows, mayorPage)) {
            List<MayorRenderRow> candidates = new ArrayList<>();
            if (mayorSnapshot != null) {
                for (HearthMayorSnapshot.Candidate candidate
                        : mayorSnapshot.candidates()) {
                    int knack = Mth.clamp(candidate.knack(), 0, 100);
                    candidates.add(new MayorRenderRow(
                        HsUi.fitLabel(font, Component.literal(
                            firstInitial(candidate.name())), MAYOR_AVATAR_SIZE),
                        HsUi.fitLabel(font, Component.literal(candidate.name()),
                            mayorNameWidth(mayorPanelWidth)),
                        HsUi.fitLabel(font, Component.translatable(
                                "hearthstead.profession."
                                    + candidate.professionId().toLowerCase(
                                        Locale.ROOT)),
                            mayorNameWidth(mayorPanelWidth)),
                        HsUi.fitLabel(font, boonName(candidate.boonKey()),
                            mayorBoonWidth(mayorPanelWidth)),
                        HsUi.fitLabel(font, Component.translatable(
                                "hearthstead.mayor.knack.value",
                                boonAttributeName(candidate.boonKey()), knack),
                            mayorKnackWidth(mayorPanelWidth)),
                        HsUi.Tone.of(knack / 100.0F)));
                }
            }
            int titleBox = Math.max(1, mayorPanelWidth - 2 * MAYOR_PAD
                - MAYOR_STATUS_W - MAYOR_PAD);
            int pages = mayorPageCount(mayorSnapshot == null
                ? 0 : mayorSnapshot.candidates().size(), mayorVisibleRows);
            mayorRenderModel = new MayorRenderModel(
                HsUi.fitLabel(font, MAYOR_TITLE, titleBox),
                HsUi.fitLabel(font, MAYOR_CHOICE_RULE, titleBox),
                HsUi.fitLabel(font, MAYOR_CANDIDATES_TITLE,
                    mayorPanelWidth - 2 * MAYOR_PAD),
                HsUi.fitLabel(font, MAYOR_LOADING,
                    mayorPanelWidth - 2 * MAYOR_PAD),
                HsUi.fitLabel(font, MAYOR_CANDIDATES_EMPTY,
                    mayorPanelWidth - 2 * MAYOR_PAD),
                candidates,
                HsUi.fitLabel(font, Component.translatable(
                        "hearthstead.mayor.page", mayorPage + 1, pages),
                    Math.max(1, mayorPanelWidth / 3)));
            mayorRenderSnapshot = mayorSnapshot;
            mayorRenderLanguage = language;
            mayorRenderPanelWidth = mayorPanelWidth;
            mayorRenderPanelHeight = mayorPanelHeight;
            mayorRenderVisibleRows = mayorVisibleRows;
            mayorRenderPage = mayorPage;
            mayorStatusSecond = Long.MIN_VALUE;
        }

        long gameTime = currentGameTime();
        long second = countdownSecond(gameTime);
        if (mayorStatusSnapshot == mayorSnapshot
            && mayorStatusLanguage.equals(language)
            && mayorStatusPanelWidth == mayorPanelWidth
            && !needsCountdownRefresh(mayorStatusSecond, gameTime)) {
            return;
        }
        mayorStatusSnapshot = mayorSnapshot;
        mayorStatusLanguage = language;
        mayorStatusPanelWidth = mayorPanelWidth;
        mayorStatusSecond = second;
        mayorStatusRenderModel = buildMayorStatusRenderModel();
    }

    /** Pure cache key: roster text is rebuilt only on actual visible input. */
    static boolean mayorRenderCacheMatches(Object cachedSnapshot,
                                            String cachedLanguage,
                                            int cachedWidth, int cachedHeight,
                                            int cachedRows, int cachedPage,
                                            Object snapshot, String language,
                                            int width, int height,
                                            int rows, int page) {
        return cachedSnapshot == snapshot
            && cachedLanguage.equals(language)
            && cachedWidth == width
            && cachedHeight == height
            && cachedRows == rows
            && cachedPage == page;
    }

    private MayorStatusRenderModel buildMayorStatusRenderModel() {
        if (mayorSnapshot == null) {
            return MayorStatusRenderModel.empty();
        }
        int box = MAYOR_STATUS_W - 12;
        HsUi.FittedLabel empty = fittedEmpty();
        long now = currentGameTime();
        if (mayorSnapshot.mourning()) {
            long remaining = Math.max(0, mayorSnapshot.mourningUntil() - now);
            return new MayorStatusRenderModel(1,
                HsUi.fitLabel(font, Component.translatable(
                    "hearthstead.mayor.mourning.active",
                    formatTicks(remaining)), box),
                HsUi.fitLabel(font, Component.translatable(
                    "hearthstead.mayor.mourning.boon_suppressed"), box),
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
            empty,
            empty,
            settling ? HsUiTokens.TEXT_MUTED : HsUiTokens.ACCENT);
    }

    static String firstInitial(String name) {
        if (name == null || name.isBlank()) {
            return "?";
        }
        String stripped = name.strip();
        int end = stripped.offsetByCodePoints(0, 1);
        return stripped.substring(0, end).toUpperCase(Locale.ROOT);
    }

    private static Component boonAttributeName(String boonKey) {
        String attribute = switch (boonKey == null ? "" : boonKey) {
            case "hard_hands" -> "strength";
            case "long_days" -> "stamina";
            case "good_counsel" -> "wits";
            case "careful_work" -> "dexterity";
            case "open_hearth" -> "spirit";
            case "clear_sight" -> "perception";
            case "steady_purpose" -> "focus";
            case "common_voice" -> "presence";
            default -> "";
        };
        return attribute.isEmpty()
            ? Component.translatable("hearthstead.mayor.knack.unknown")
            : Component.translatable("hearthstead.attribute." + attribute);
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
        int contentWidth = journeyPanelWidth - 2 * JOURNEY_PAD;
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

    /** Recurring raid labels are rebuilt only from a received server snapshot. */
    private void ensureRecurringStatusRenderModel() {
        String language = currentLanguage();
        if (recurringRenderSource == cachedRecurringStatusView
            && recurringRenderLanguage.equals(language)
            && recurringRenderPanelHeight == journeyPanelHeight) {
            return;
        }
        int cardWidth = journeyPanelWidth - 2 * JOURNEY_PAD;
        recurringRenderModel = new RecurringStatusRenderModel(
            font.width(RECURRING_TITLE),
            HsUi.fitLabel(font, recurringStatusLine, cardWidth - 16),
            HsUi.fitLabel(font, recurringDetailLine, cardWidth - 16));
        recurringRenderSource = cachedRecurringStatusView;
        recurringRenderLanguage = language;
        recurringRenderPanelHeight = journeyPanelHeight;
    }

    /** Immutable aftermath copy shares the Journey panel's render cache. */
    private void ensureAftermathRenderModel() {
        String language = currentLanguage();
        if (aftermathRenderSource == cachedAftermathView
            && aftermathRenderLanguage.equals(language)
            && aftermathRenderPanelHeight == journeyPanelHeight) {
            return;
        }
        int cardWidth = journeyPanelWidth - 2 * JOURNEY_PAD;
        Component road = Component.translatable(
            "hearthstead.raid.aftermath.road.label", aftermathRoadLine);
        aftermathRenderModel = new AftermathRenderModel(
            font.width(AFTERMATH_TITLE),
            HsUi.fitLabel(font, aftermathStatusLine, cardWidth - 90),
            HsUi.fitLabel(font, aftermathNightLine, Integer.MAX_VALUE),
            HsUi.fitLabel(font, aftermathCaptainLine, cardWidth - 17),
            HsUi.fitLabel(font, aftermathObjectiveLine, cardWidth - 16),
            HsUi.fitLines(font, aftermathImpactLine, cardWidth - 16),
            HsUi.fitLabel(font, aftermathImpactLine, cardWidth - 16),
            HsUi.fitLabel(font, aftermathThreatLine, cardWidth - 16),
            HsUi.fitLines(font, aftermathRewardLine, cardWidth - 16),
            HsUi.fitLabel(font, aftermathRewardLine, cardWidth - 16),
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
                journeyPanelWidth - 2 * JOURNEY_PAD - 64);
        }
        HsUi.FittedLabel progress = HsUi.fitLabel(font,
            Component.translatable("journey.hearthstead.progress", completed,
                JourneyDefinition.CURRENT.orderedSteps().size()), Integer.MAX_VALUE);
        int cardWidth = journeyPanelWidth - 2 * JOURNEY_PAD - 22;
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
                    cardWidth - 20),
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
        if (journeyRenderCurrent != currentOrdinal) {
            journeyDescriptionScroll[0] = journeyDescriptionScroll[1] = 0;
        }
        journeyScrollHint = HsUi.fitLabel(font, JOURNEY_SCROLL_HELP, journeyPanelWidth - 2 * JOURNEY_PAD);
        journeyRenderModel = new JourneyRenderModel(
            font.width(JOURNEY_TITLE), chapter, progress, rows,
            HsUi.fitLabel(font, footer,
                journeyPanelWidth - 2 * JOURNEY_PAD), mode);
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
                                       int morale, int alert,
                                       int heroLabelWidth, int metricLabelWidth,
                                       int settlementStatusWidth) {
        String language = currentLanguage();
        boolean languageChanged = !cachedStatsLanguage.equals(language);
        boolean fontChanged = cachedStatsFont != font;
        boolean layoutChanged = cachedStatsLayoutWidth != imageWidth;
        boolean populationChanged = population != cachedStatsPopulation;
        if (populationChanged
            || capacity != cachedStatsCapacity || fontChanged || languageChanged) {
            cachedStatsPopulation = population;
            cachedStatsCapacity = capacity;
            populationValue = HsUi.fitLabel(font, Component.literal(String.valueOf(population)), Integer.MAX_VALUE);
            housingValue = HsUi.fitLabel(font, Component.literal(population + " / " + capacity), Integer.MAX_VALUE);
            cachedPopulationStat = HsUi.fitLabel(font,
                Component.literal(population + " / " + capacity), Integer.MAX_VALUE);
        }
        if (employed != cachedStatsEmployed
            || populationChanged || fontChanged || languageChanged) {
            cachedStatsEmployed = employed;
            cachedEmploymentStat = HsUi.fitLabel(font,
                Component.literal(employed + " / " + population), Integer.MAX_VALUE);
        }
        if (food != cachedStatsFood || fontChanged || languageChanged) {
            cachedStatsFood = food;
            cachedFoodStat = HsUi.fitLabel(font, Component.literal(String.valueOf(food)),
                Integer.MAX_VALUE);
        }
        if (radius != cachedStatsRadius || fontChanged || languageChanged) {
            cachedStatsRadius = radius;
            cachedRadiusStat = HsUi.fitLabel(font,
                Component.literal(radius + " m"), Integer.MAX_VALUE);
        }
        if (morale != cachedStatsMorale || fontChanged || languageChanged) {
            cachedStatsMorale = morale;
            cachedMoraleValue = HsUi.fitLabel(font,
                Component.literal(morale + " / 100"), Integer.MAX_VALUE);
        }
        int band = morale < 25 ? 0 : morale < 50 ? 1 : morale < 75 ? 2 : 3;
        if (band != cachedStatsMoraleBand || languageChanged || fontChanged || layoutChanged) {
            cachedStatsMoraleBand = band;
            cachedMoraleBand = HsUi.fitLabel(font, moraleBand(morale), councilLayout.summary().width());
        }
        if (metricLabelWidth > 0 && (languageChanged || fontChanged || layoutChanged
            || cachedStatsHeroLabelWidth != heroLabelWidth
            || cachedStatsCardLabelWidth != metricLabelWidth)) {
            cachedPopulationCardLabel = HsUi.fitLabel(font, POPULATION_LABEL, heroLabelWidth);
            cachedEmploymentCardLabel = HsUi.fitLabel(font, EMPLOYED_LABEL,
                metricLabelWidth);
            cachedFoodCardLabel = HsUi.fitLabel(font, FOOD_LABEL, metricLabelWidth);
            cachedRadiusCardLabel = HsUi.fitLabel(font, RADIUS_LABEL, metricLabelWidth);
            housingLabel = HsUi.fitLabel(font, HOUSING_LABEL, metricLabelWidth);
            moraleLabel = HsUi.fitLabel(font, MORALE_LABEL, metricLabelWidth);
            councilLedgerLabel = HsUi.fitLabel(font, COUNCIL_LEDGER, imageWidth - 210);
            storesLabel = HsUi.fitLabel(font, STORES_LABEL, 108);
            inventoryLabel = HsUi.fitLabel(font, playerInventoryTitle, 162);
            cachedStatsHeroLabelWidth = heroLabelWidth;
            cachedStatsCardLabelWidth = metricLabelWidth;
        }
        if (settlementStatusWidth > 0 && !statusRenderCacheMatches(cachedStatsAlert,
            cachedStatsFont, cachedStatsLanguage, cachedSettlementStatusWidth, alert, font,
            language, settlementStatusWidth)) {
            cachedSettlementStatusTitle = HsUi.fitLabel(font,
                alert == 1 ? ALERT_LABEL : SETTLEMENT_STATUS_LABEL, settlementStatusWidth);
            cachedSettlementStatusDetail = HsUi.fitLabel(font,
                alert == 1 ? SETTLEMENT_STATUS_ALERT : SETTLEMENT_STATUS_STABLE,
                settlementStatusWidth);
            cachedSettlementStatusWidth = settlementStatusWidth;
        }
        boolean needsAttention = food <= 0 || population > capacity || alert == 1;
        if (cachedPriorityFood != food || cachedPriorityPopulation != population
            || cachedPriorityCapacity != capacity || cachedPriorityAlert != alert
            || cachedPriorityWidth != settlementStatusWidth || cachedPriorityFont != font
            || !cachedPriorityLanguage.equals(language)) {
            Component priorityTitle = food <= 0
                ? Component.literal("Food stores empty")
                : population > capacity ? Component.literal("More beds needed")
                : alert == 1 ? SETTLEMENT_STATUS_ALERT : SETTLEMENT_STATUS_LABEL;
            Component priorityDetail = food <= 0 && population > capacity
                ? Component.literal("Housing " + population + " / " + capacity)
                : food <= 0 ? Component.literal("Produce food before recruiting")
                : population > capacity ? Component.literal("Housing " + population + " / " + capacity)
                : alert == 1 ? SETTLEMENT_STATUS_ALERT : SETTLEMENT_STATUS_STABLE;
            cachedPriorityTitle = HsUi.fitLabel(font, priorityTitle, settlementStatusWidth);
            cachedPriorityDetail = HsUi.fitLabel(font, priorityDetail, settlementStatusWidth);
            cachedPriorityNeedsAttention = needsAttention;
            cachedPriorityFood = food;
            cachedPriorityPopulation = population;
            cachedPriorityCapacity = capacity;
            cachedPriorityAlert = alert;
            cachedPriorityWidth = settlementStatusWidth;
            cachedPriorityFont = font;
            cachedPriorityLanguage = language;
        }
        cachedStatsLanguage = language;
        cachedStatsFont = font;
        cachedStatsLayoutWidth = imageWidth;
        cachedStatsAlert = alert;
    }

    /** Pure cache key for the status card and its fitted detail line. */
    static boolean statusRenderCacheMatches(int cachedAlert, Object cachedFont,
                                            String cachedLanguage, int cachedWidth,
                                            int alert, Object font, String language,
                                            int width) {
        return cachedAlert == alert
            && cachedFont == font
            && cachedWidth == width
            && cachedLanguage.equals(language);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
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
            // Reserve the right-side Close action; the title never slides beneath it.
            int maxHeaderWidth = councilLayout.compact()
                ? imageWidth - 64 - HEADER_TITLE_X : 190 - HEADER_TITLE_X;
            HsUi.FittedLabel header = HsUi.fitLabel(font, fullHeader, maxHeaderWidth);
            cachedSettlementHeader = header.text();
            cachedSettlementHeaderWidth = header.width();
        }
        int pop = menu.get(HearthMenu.DATA_POPULATION);
        int cap = menu.get(HearthMenu.DATA_CAPACITY);
        int employed = menu.get(HearthMenu.DATA_EMPLOYED);
        int food = menu.get(HearthMenu.DATA_FOOD);
        int radius = menu.get(HearthMenu.DATA_RADIUS);
        int morale = Mth.clamp(menu.get(HearthMenu.DATA_MORALE), 0, 100);
        int alert = menu.get(HearthMenu.DATA_ALERT);
        int metricLabelWidth = suppliesOpen ? councilLayout.stat(1).width() - 40
            : councilLayout.minimumHomeStatLabelWidth();
        updateStatRenderCache(pop, cap, employed, food, radius, morale, alert,
            Math.max(24, metricLabelWidth), Math.max(24, metricLabelWidth),
            councilLayout.stat(5).width() - (suppliesOpen ? 0 : 10));
        // The normal item model shares the inventory ember texture; no extra animation state.
        graphics.fill(8, 6, 28, 26, 0xFF596050);
        graphics.fill(9, 7, 27, 25, 0xFF414D3F);
        graphics.renderItem(headerHearthItem, 10, 8);
        graphics.drawString(font, cachedSettlementHeader, HEADER_TITLE_X, 12,
            HearthPixelSurface.LIGHT_TEXT, true);
        if (!councilLayout.compact()) {
            graphics.drawString(font, councilLedgerLabel.text(),
                imageWidth - 12 - councilLedgerLabel.width(), 12, 0xFFD2DEBE, true);
        }
        if (suppliesOpen) graphics.drawString(font, storesLabel.text(), councilLayout.communalX(),
            councilLayout.communalY() - 10, SURFACE_INK, false);
        if (suppliesOpen) graphics.drawString(font, inventoryLabel.text(),
            inventoryLabelX, inventoryLabelY, SURFACE_INK, false);
        if (peopleTabOpen) {
            renderPeoplePage(graphics);
            return;
        }
        if (requestPanelOpen) {
            renderTasksPage(graphics);
            return;
        }

        if (suppliesOpen) {
            drawSupplySummary(graphics);
            return;
        }

        drawCouncilStat(graphics, 0, cachedPopulationCardLabel, populationValue);
        drawCouncilStat(graphics, 1, cachedEmploymentCardLabel, cachedEmploymentStat);
        drawCouncilStat(graphics, 2, cachedFoodCardLabel, cachedFoodStat);
        drawCouncilStat(graphics, 3, housingLabel, housingValue);
        drawCouncilStat(graphics, 4, moraleLabel, cachedMoraleValue);
        HearthLayout.Rect safety = councilLayout.stat(5);
        int statusX = safety.x() + (suppliesOpen ? 0 : 5);
        int statusY = safety.y() + (suppliesOpen ? 0 : 4);
        if (!suppliesOpen) {
            graphics.drawString(font, "NEEDS ATTENTION", statusX, statusY,
                cachedPriorityNeedsAttention ? 0xFF913D2D : SURFACE_MUTED, false);
            graphics.drawString(font, cachedPriorityTitle.text(), statusX, statusY + 12,
                cachedPriorityNeedsAttention ? 0xFF913D2D : SURFACE_INK, false);
            graphics.drawString(font, cachedPriorityDetail.text(), statusX, statusY + 22,
                cachedPriorityNeedsAttention ? 0xFF913D2D : SURFACE_MUTED, false);
            drawOverviewMeter(graphics, 1, employed, pop, 0xFF4D7048);
            drawOverviewMeter(graphics, 3, pop, cap,
                pop > cap ? 0xFF913D2D : pop == cap && cap > 0 ? 0xFF4D7048 : 0xFF9A722D);
            drawOverviewMeter(graphics, 4, morale, 100,
                morale < 25 ? 0xFF913D2D : morale < 50 ? 0xFF9A722D : 0xFF4D7048);
        }
        HearthLayout.Rect note = councilLayout.recruitment();
        int recruit = menu.get(HearthMenu.DATA_RECRUIT);
        RecruitmentPolicy.Blocker blocker = RecruitmentPolicy.Blocker.fromWireId(
            menu.get(HearthMenu.DATA_RECRUIT_BLOCKER));
        updateRecruitLines(blocker, pop, cap, morale, recruit);
        if (suppliesOpen) {
            HsUi.taskPaper(graphics, note.x(), note.y(), note.width(), note.height(),
                blocker != RecruitmentPolicy.Blocker.NONE, false);
            graphics.drawString(font, recruitLine1, note.x() + 5, note.y() + 2,
                SURFACE_INK, false);
            graphics.drawString(font, recruitLine2, note.x() + 5, note.y() + 11,
                SURFACE_MUTED, false);
        } else {
            graphics.drawString(font, "RECRUITMENT", note.x() + 8, note.y() + 4,
                HearthPixelSurface.LIGHT_TEXT, false);
            // The fitted first line is the section title; keep it out of the body.
            graphics.drawString(font, recruitLine2, note.x() + 8, note.y() + 19,
                SURFACE_INK, false);
        }
        RecruitmentPolicy.Stage stage = RecruitmentPolicy.Stage.fromWireId(
            menu.get(HearthMenu.DATA_RECRUIT_STAGE));
        if (recruitmentProgressVisible(stage, blocker)) {
            int progressWidth = note.width() - 10;
            graphics.fill(note.x() + 5, note.y() + note.height() - 2,
                note.x() + 5 + Mth.clamp(recruit, 0, 100) * progressWidth / 100,
                note.y() + note.height(), 0xFF4D7048);
        }
    }

    private void drawSupplySummary(GuiGraphics graphics) {
        HearthLayout.Rect summary = councilLayout.summary();
        int allItems = supplyItemTotal(HearthSupplyCategory.ALL);
        int selectedItems = supplyItemTotal(supplyCategory);
        graphics.drawString(font, "HEARTH SUPPLIES", summary.x(), summary.y(), SURFACE_INK, false);
        graphics.drawString(font, HsUi.fitLabel(font, Component.literal("Categories are in Stores"), summary.width()).text(),
            summary.x(), summary.y() + 12, SURFACE_MUTED, false);
        graphics.drawString(font, HsUi.fitLabel(font, Component.literal(selectedItems + " / " + allItems + " items"), summary.width()).text(),
            summary.x(), summary.y() + 22, SURFACE_INK, false);
    }

    private void drawCouncilStat(GuiGraphics graphics, int index,
                                 HsUi.FittedLabel label, HsUi.FittedLabel value) {
        HearthLayout.Rect bounds = councilLayout.stat(index);
        int labelX = suppliesOpen ? bounds.x() : bounds.x() + (bounds.width() - label.width()) / 2;
        int labelY = bounds.y() + (suppliesOpen ? 0 : 4);
        graphics.drawString(font, label.text(), labelX, labelY, SURFACE_MUTED, false);
        if (!suppliesOpen) {
            int color = index == 2 && cachedStatsFood == 0 ? 0xFF913D2D
                : index == 3 && cachedStatsPopulation > cachedStatsCapacity ? 0xFF913D2D
                : SURFACE_INK;
            float valueScale = Math.min(1.35F, (bounds.width() - 8.0F) / Math.max(1, value.width()));
            graphics.pose().pushPose();
            graphics.pose().translate(bounds.x() + (bounds.width() - value.width() * valueScale) / 2.0F,
                bounds.y() + bounds.height() - 17, 0);
            graphics.pose().scale(valueScale, valueScale, 1);
            graphics.drawString(font, value.text(), 0, 0, color, false);
            graphics.pose().popPose();
            drawOverviewIcon(graphics, index);
        } else {
            graphics.drawString(font, value.text(),
                bounds.x() + bounds.width() - value.width(), bounds.y(), SURFACE_INK, false);
        }
    }

    private void drawOverviewIcon(GuiGraphics graphics, int index) {
        HearthLayout.Rect icon = councilLayout.statIcon(index);
        if (icon.width() <= 0) return;
        float scale = icon.width() / 16.0F;
        graphics.pose().pushPose();
        graphics.pose().translate(icon.x(), icon.y(), 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.renderItem(overviewIcons[index], 0, 0);
        graphics.pose().popPose();
    }

    private void drawOverviewMeter(GuiGraphics graphics, int index, int value, int maximum, int color) {
        HearthLayout.Rect bounds = councilLayout.statMeter(index);
        if (bounds.width() <= 0 || bounds.height() <= 0) return;
        int filled = maximum <= 0 ? 0
            : (int) (bounds.width() * Mth.clamp((double) value / maximum, 0.0D, 1.0D));
        graphics.fill(bounds.x(), bounds.y(), bounds.x() + bounds.width(),
            bounds.y() + bounds.height(), 0xFFABB5A0);
        if (filled > 0) graphics.fill(bounds.x(), bounds.y(), bounds.x() + filled,
            bounds.y() + bounds.height(), color);
    }

    private void updateRecruitLines(RecruitmentPolicy.Blocker blocker,
                                    int population, int capacity, int morale,
                                    int recruit) {
        RecruitmentPolicy.Stage stage = RecruitmentPolicy.Stage.fromWireId(
            menu.get(HearthMenu.DATA_RECRUIT_STAGE));
        int readyFood = menu.get(HearthMenu.DATA_READY_AFTER_PRICE);
        int requiredFood = menu.get(HearthMenu.DATA_REQUIRED_RESERVE);
        int missingFood = menu.get(HearthMenu.DATA_MISSING_RESERVE);
        int tooltipWidth = Math.min(260, Math.max(80, width - 24));
        boolean candidatePresent = cachedRecruitmentCard.present();
        String language = currentLanguage();
        int lineWidth = councilLayout.recruitment().width() - 10
            - (candidatePresent && !suppliesOpen ? RECRUIT_REVIEW_W + 8 : 0);
        if (blocker == cachedRecruitBlocker
            && stage == cachedRecruitStage
            && population == cachedRecruitPopulation
            && capacity == cachedRecruitCapacity
            && morale == cachedRecruitMorale
            && recruit == cachedRecruitProgress
            && readyFood == cachedRecruitReadyFood
            && requiredFood == cachedRecruitRequiredFood
            && missingFood == cachedRecruitMissingFood
            && tooltipWidth == cachedRecruitTooltipWidth
            && candidatePresent == cachedRecruitCandidatePresent
            && cachedRecruitFont == font
            && cachedRecruitLanguage.equals(language)
            && cachedRecruitLineWidth == lineWidth) {
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
        cachedRecruitMissingFood = missingFood;
        cachedRecruitTooltipWidth = tooltipWidth;
        cachedRecruitCandidatePresent = candidatePresent;
        cachedRecruitFont = font;
        cachedRecruitLanguage = language;
        cachedRecruitLineWidth = lineWidth;
        // Two complete, independently fitted lines; full facts remain in the hover.
        recruitLine1 = HsUi.fitLabel(font, Component.translatable(
            "hearthstead.gui.recruit_summary.title"), lineWidth).text().getVisualOrderText();
        boolean emptySettlementRecovery = emptySettlementRecoveryVisible(population,
            blocker, candidatePresent);
        String summary = emptySettlementRecovery ? "empty_settlement" : switch (blocker) {
            case NONE -> stage == RecruitmentPolicy.Stage.WAITING_ADMISSION ? "waiting"
                : stage == RecruitmentPolicy.Stage.TRAVELING ? "traveling"
                : recruit > 0 ? "attracting" : "ready";
            case NO_HEARTH -> "hearth";
            case NO_TAVERN -> "tavern";
            case NO_BED -> "bed";
            case LOW_MORALE -> "morale";
            case CANNOT_PAY -> "price";
            case INSUFFICIENT_READY_FOOD -> "reserve";
            case INVALID_STATE -> "invalid";
        };
        recruitLine2 = HsUi.fitLabel(font, Component.translatable(
            "hearthstead.gui.recruit_summary." + summary), lineWidth).text().getVisualOrderText();
        recruitTooltip = buildRecruitTooltip(stage, blocker, tooltipWidth,
            emptySettlementRecovery);
    }

    static boolean emptySettlementRecoveryVisible(int population,
                                                  RecruitmentPolicy.Blocker blocker,
                                                  boolean candidatePresent) {
        return population == 0 && blocker == RecruitmentPolicy.Blocker.NO_TAVERN
            && !candidatePresent;
    }

    /** Complete stage, blocker, reserve and progress copy, wrapped only when inputs change. */
    private List<FormattedCharSequence> buildRecruitTooltip(RecruitmentPolicy.Stage stage,
            RecruitmentPolicy.Blocker blocker, int tooltipWidth,
            boolean emptySettlementRecovery) {
        List<Component> lines = new ArrayList<>(5);
        if (emptySettlementRecovery) {
            lines.add(Component.translatable(
                "hearthstead.gui.tooltip.recruit.empty_settlement.route"));
            lines.add(Component.translatable(
                "hearthstead.gui.tooltip.recruit.empty_settlement.loss")
                .withStyle(net.minecraft.ChatFormatting.RED));
            List<FormattedCharSequence> wrapped = new ArrayList<>();
            for (Component line : lines) wrapped.addAll(font.split(line, tooltipWidth));
            return List.copyOf(wrapped);
        }
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
        List<FormattedCharSequence> wrapped = new ArrayList<>();
        for (Component line : lines) wrapped.addAll(font.split(line, tooltipWidth));
        return List.copyOf(wrapped);
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

    // Floating carried-item decorations reach Z232 + Z200 in vanilla.
    // Keep the entire modal and its tooltips above that same depth plane.
    private static final int MODAL_Z = 500;
    private final HsUi.FittedLabelCache modalTitleCache = new HsUi.FittedLabelCache();

    private void renderModalTitle(GuiGraphics graphics, Component title, int x, int y,
                                  int width, int leftReserve) {
        int available = Math.max(1, width - leftReserve - 60);
        HsUi.FittedLabel fitted = modalTitleCache.fit(font, title, available,
            minecraft.getLanguageManager().getSelected());
        graphics.drawString(font, fitted.text(), x + leftReserve
            + (available - fitted.width()) / 2, y, HearthPixelSurface.LIGHT_TEXT, false);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (hasOpenPopout()) {
            // Finish container batches before establishing the modal plane.
            graphics.flush();
            clearTooltipForNextRenderPass();
            graphics.pose().pushPose();
            try {
                graphics.pose().translate(0.0F, 0.0F, MODAL_Z);
                int panelLeft = recruitmentPanelOpen ? recruitmentPanelLeft
                    : mayorTabOpen || peopleTabOpen ? mayorPanelLeft
                    : requestPanelOpen ? requestPanelLeft : journeyPanelLeft;
                int panelWidth = recruitmentPanelOpen ? RECRUIT_PANEL_W
                    : mayorTabOpen || peopleTabOpen ? mayorPanelWidth
                    : requestPanelOpen ? requestPanelWidth : journeyPanelWidth;
                if (panelLeft < leftPos + imageWidth
                    && panelLeft + panelWidth > leftPos) {
                    graphics.fill(leftPos, topPos, leftPos + imageWidth,
                        topPos + imageHeight, 0xB0101010);
                }
                if (recruitmentPanelOpen) {
                    renderRecruitmentPanel(graphics, mouseX, mouseY);
                } else if (mayorTabOpen) {
                    renderMayorPanel(graphics, mouseX, mouseY);
                } else if (peopleTabOpen) {
                    renderPeoplePanel(graphics, mouseX, mouseY);
                } else if (requestPanelOpen) {
                    renderRequestPanel(graphics, mouseX, mouseY);
                } else {
                    renderJourneyPanel(graphics, mouseX, mouseY);
                }
                AbstractButton tooltipOwner = null;
                for (AbstractButton widget : latePanelWidgets) {
                    widget.render(graphics, mouseX, mouseY, partialTick);
                    if (widget.visible && widget.getTooltip() != null
                        && (widget.isMouseOver(mouseX, mouseY) || widget.isFocused())
                        && (tooltipOwner == null || widget.isMouseOver(mouseX, mouseY))) {
                        tooltipOwner = widget;
                    }
                }
                // Screen's deferred tooltip pass runs after this pose is popped.
                // Paint the cached widget tooltip here so it shares modal depth.
                if (tooltipOwner != null) {
                    graphics.renderTooltip(font,
                        tooltipOwner.getTooltip().toCharSequence(minecraft), mouseX, mouseY);
                } else if (requestPanelOpen) {
                    renderRequestRowTooltip(graphics, mouseX, mouseY);
                }
            } finally {
                clearTooltipForNextRenderPass();
                try {
                    graphics.flush();
                } finally {
                    graphics.pose().popPose();
                }
            }
            return;
        }
        graphics.flush();
        renderMainPageActionChrome(graphics, mouseX, mouseY);
        graphics.flush();
        renderTooltip(graphics, mouseX, mouseY);
        renderStatTooltips(graphics, mouseX, mouseY);
    }

    private void renderMainPageActionChrome(GuiGraphics graphics, int mouseX, int mouseY) {
        drawMainAction(graphics, leftPos + imageWidth - 52, topPos + 5, 46, 16,
            "Close", true, true, mouseX, mouseY);
        HearthLayout.Rect body = councilLayout.summary();
        if (peopleTabOpen) {
            int listWidth = Math.max(104, body.width() * 3 / 5);
            int rightWidth = Math.max(72, body.width() - listWidth - 6);
            int rightX = body.x() + listWidth + 6;
            drawMainAction(graphics, leftPos + body.x() + listWidth - 40,
                topPos + body.y() + 21, 35, 16, "Find", true, false, mouseX, mouseY);
            int footerTop = topPos + body.y() + body.height() - 42;
            int secondaryWidth = Math.max(32, (rightWidth - 14) / 2);
            drawMainAction(graphics, leftPos + rightX + 5, footerTop,
                secondaryWidth, 16, "Mayor", true, false, mouseX, mouseY);
            if (cachedRecruitmentCard.present()) {
                drawMainAction(graphics, leftPos + rightX + 8 + (rightWidth - 14) / 2,
                    footerTop, Math.max(32, rightWidth - 13 - secondaryWidth), 16,
                    "Traveler", true, false, mouseX, mouseY);
            }
            drawMainAction(graphics, leftPos + rightX + 5,
                topPos + body.y() + body.height() - 22, Math.max(52, rightWidth - 10), 16,
                "View Settler", peopleViewInspectable, false, mouseX, mouseY);
            return;
        }
        if (requestPanelOpen) {
            int listWidth = Math.max(104, body.width() * 3 / 5);
            drawMainAction(graphics, leftPos + body.x() + listWidth - 56,
                topPos + body.y() + 5, 51, 16, "Refresh", !requestLoading,
                false, mouseX, mouseY);
            return;
        }
        if (!suppliesOpen && !mayorTabOpen && !journeyTabOpen && !recruitmentPanelOpen) {
            HearthLayout.Rect attention = councilLayout.stat(5);
            int actionWidth = Math.max(44, (attention.width() - 18) / 2);
            drawMainAction(graphics, leftPos + attention.x() + 6,
                topPos + attention.y() + attention.height() - 20,
                actionWidth, 16, "Journey", true, false, mouseX, mouseY);
            drawMainAction(graphics, leftPos + attention.x() + 12 + actionWidth,
                topPos + attention.y() + attention.height() - 20,
                Math.max(44, attention.width() - 18 - actionWidth), 16,
                "Supplies", true, false, mouseX, mouseY);
            if (cachedRecruitmentCard.present()) {
                HearthLayout.Rect recruitment = councilLayout.recruitment();
                int reviewWidth = Math.min(RECRUIT_REVIEW_W, recruitment.width() - 12);
                drawMainAction(graphics, leftPos + recruitment.x() + recruitment.width()
                    - reviewWidth - 6, topPos + recruitment.y() + recruitment.height() - 19,
                    reviewWidth, 16, "Review", true, false, mouseX, mouseY);
            }
        }
    }

    private void drawMainAction(GuiGraphics graphics, int x, int y, int width, int height,
                                String label, boolean active, boolean danger,
                                int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        HearthMaterials.button(graphics, x, y, width, height, true, danger, hovered, active);
        int labelWidth = font.width(label);
        graphics.drawString(font, label, x + Math.max(3, (width - labelWidth) / 2),
            y + (height - HsUiTokens.TEXT_H) / 2,
            active ? HearthPixelSurface.LIGHT_TEXT : 0xFFADB6A3, false);
    }

    private void renderStatTooltips(GuiGraphics graphics, int mouseX, int mouseY) {
        int localX = mouseX - leftPos;
        int localY = mouseY - topPos;
        for (int index = 0; index < 6; index++) {
            if (!councilLayout.stat(index).contains(localX, localY)) {
                continue;
            }
            String key = switch (index) {
                case 0 -> "population";
                case 3 -> "housing";
                case 1 -> "employed";
                case 2 -> "food";
                case 4 -> "morale";
                default -> null;
            };
            if (key == null) {
                graphics.renderComponentTooltip(font, List.of(
                    menu.get(HearthMenu.DATA_ALERT) == 1 ? SETTLEMENT_STATUS_ALERT : SETTLEMENT_STATUS_STABLE),
                    mouseX, mouseY);
            } else {
                List<Component> lines = new ArrayList<>(3);
                lines.add(Component.translatable("hearthstead.gui.tooltip." + key));
                lines.add(Component.translatable("hearthstead.gui.tooltip." + key + ".desc")
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
                if (index == 0) {
                    lines.add(Component.translatable("hearthstead.gui.tooltip.radius")
                        .append(": ").append(cachedRadiusStat.text()));
                }
                graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
            }
            return;
        }
        if (!councilLayout.recruitment().contains(localX, localY)) {
            return;
        }

        graphics.renderTooltip(font, recruitTooltip, mouseX, mouseY);
    }

    /** Opaque, pixel-aligned materials local to the modal layer. */
    private static final class ModalPixels {
        private static final int INK = HearthPixelSurface.INK;
        private static final int MUTED = HearthPixelSurface.MUTED;
        private static final int ACCENT = 0xFF70502B;
        private static final int GOOD = 0xFF35643C;
        private static final int WARN = 0xFF86521F;
        private static final int BAD = 0xFF963E31;

        private static int ink(int tone) {
            if (tone == HsUiTokens.GOOD) return GOOD;
            if (tone == HsUiTokens.WARN) return WARN;
            if (tone == HsUiTokens.BAD) return BAD;
            if (tone == HsUiTokens.ACCENT) return ACCENT;
            return INK;
        }

        private static void window(GuiGraphics g, int x, int y, int w, int h) {
            HearthMaterials.frame(g, x, y, w, h);
            header(g, x, y, w, 26);
        }

        private static void header(GuiGraphics g, int x, int y, int w, int h) {
            HearthMaterials.header(g, x + 3, y + 3, w - 6, h - 3);
            g.fill(x + 8, y + h, x + w - 8, y + h + 1, HearthPixelSurface.COPPER);
        }

        private static void card(GuiGraphics g, int x, int y, int w, int h, boolean hover) {
            HearthMaterials.paper(g, x, y, w, h);
            if (hover) g.fill(x, y, x + w, y + h, 0x30648B45);
            g.fill(x, y + h - 1, x + w, y + h, 0xFF9AA68E);
        }

        private static void inset(GuiGraphics g, int x, int y, int w, int h) {
            HearthMaterials.paper(g, x, y, w, h);
            g.fill(x, y, x + w, y + 1, 0xFF98A38E);
            g.fill(x, y, x + 1, y + h, 0xFF98A38E);
            g.fill(x, y + h - 1, x + w, y + h, 0xFFF0F2E8);
            g.fill(x + w - 1, y, x + w, y + h, 0xFFF0F2E8);
        }

        private static void divider(GuiGraphics g, int x, int y, int w) {
            g.fill(x, y, x + w, y + 1, 0xFF9AA68E);
        }

        private static void scrollbar(GuiGraphics g, int x, int y, int h,
                                       float visible, float position, boolean hovered) {
            g.fill(x, y, x + HsUiTokens.SCROLL_W, y + h, 0xFFA9B3A0);
            int thumb = Math.max(8, Math.min(h, Math.round(h * visible)));
            int offset = Math.round((h - thumb) * position);
            g.fill(x + 1, y + offset, x + HsUiTokens.SCROLL_W - 1, y + offset + thumb,
                hovered ? 0xFF49644C : 0xFF66775B);
        }
    }

    private static final class ModalButton extends HsButton {
        private final boolean danger;
        private final HsUi.FittedLabelCache labelCache = new HsUi.FittedLabelCache();
        private boolean pressed;

        private ModalButton(int x, int y, int w, int h, Component label,
                            Runnable action, boolean danger) {
            super(x, y, w, h, label, danger ? Kind.DANGER : Kind.NORMAL, action);
            this.danger = danger;
        }

        public static ModalButton normal(int x, int y, int w, int h,
                                         Component label, Runnable action) {
            return new ModalButton(x, y, w, h, label, action, false);
        }

        public static ModalButton danger(int x, int y, int w, int h,
                                         Component label, Runnable action) {
            return new ModalButton(x, y, w, h, label, action, true);
        }

        @Override
        public void onClick(double x, double y) {
            pressed = true;
            super.onClick(x, y);
        }

        @Override
        public void onRelease(double x, double y) {
            pressed = false;
            super.onRelease(x, y);
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            HearthMaterials.button(g, getX(), getY(), getWidth(), getHeight(),
                true, danger, active && isHoveredOrFocused(), active);
            if (active && pressed) g.fill(getX() + 1, getY() + 1,
                getX() + getWidth() - 1, getY() + getHeight() - 1, 0x22000000);
            var mc = net.minecraft.client.Minecraft.getInstance();
            int inner = Math.max(1, getWidth() - 8);
            HsUi.FittedLabel label = labelCache.fit(mc.font, getMessage(), inner,
                mc.getLanguageManager().getSelected());
            g.drawString(mc.font, label.text(), getX() + 4 + (inner - Math.min(inner, label.width())) / 2,
                getY() + (getHeight() - HsUiTokens.TEXT_H) / 2 + 1,
                active ? HearthPixelSurface.LIGHT_TEXT : 0xFFADB6A3, false);
        }
    }

    /** Input and narration for a hand-painted page row; it intentionally draws no second label. */
    private static final class TransparentRowButton extends HsButton {
        private TransparentRowButton(int x, int y, int width, int height, Component label,
                                     Runnable onPress) {
            super(x, y, width, height, label, HsButton.Kind.NORMAL, onPress);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY,
                                    float partialTick) {
            // The containing page paints the card and fitted text exactly once.
        }
    }

    /** Same HsButton action/narration contract, with the council's pixel-only surface. */
    private static final class PixelActionButton extends HsButton {
        private final HsUi.FittedLabelCache pixelLabel = new HsUi.FittedLabelCache();

        private PixelActionButton(int x, int y, int width, int height, Component label,
                                  Runnable onPress) {
            super(x, y, width, height, label, HsButton.Kind.NORMAL, onPress);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY,
                                    float partialTick) {
            HearthPixelSurface.actionButton(graphics, getX(), getY(), getWidth(), getHeight(),
                false, active && isHoveredOrFocused());
            if (!active) {
                graphics.fill(getX() + 1, getY() + 1, getX() + getWidth() - 1,
                    getY() + getHeight() - 1, 0xAA5A6054);
            }
            var minecraft = net.minecraft.client.Minecraft.getInstance();
            var font = minecraft.font;
            HsUi.FittedLabel label = pixelLabel.fit(font, getMessage(), getWidth() - 12,
                minecraft.getLanguageManager().getSelected());
            graphics.drawString(font, label.text(), getX() + (getWidth() - label.width()) / 2,
                getY() + (getHeight() - HsUiTokens.TEXT_H) / 2,
                active ? HearthPixelSurface.LIGHT_TEXT : 0xFFADB6A3, true);
        }
    }

    /** A chapter on the vertical council rail; narration retains the complete label. */
    private static final class SeatTabButton extends AbstractButton {
        private final boolean selected;
        private final Runnable onPress;
        private final HsUi.FittedLabelCache fittedLabel = new HsUi.FittedLabelCache();

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
            HearthPixelSurface.tabButton(graphics, getX(), getY(), getWidth(), getHeight(),
                selected, active && isHoveredOrFocused());
            var minecraft = net.minecraft.client.Minecraft.getInstance();
            var font = minecraft.font;
            HsUi.FittedLabel label = fittedLabel.fit(font, getMessage(), getWidth() - 8,
                minecraft.getLanguageManager().getSelected());
            int text = !active ? 0xFF7C766B
                : selected ? HearthPixelSurface.LIGHT_TEXT : HearthPixelSurface.INK;
            int labelX = getX() + 4;
            int labelY = getY() + (getHeight() - HsUiTokens.TEXT_H) / 2;
            if (active && !selected) {
                graphics.drawString(font, label.text(), labelX, labelY, text, false);
            } else {
                HsUi.label(graphics, font, label.text(), labelX, labelY, text);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
