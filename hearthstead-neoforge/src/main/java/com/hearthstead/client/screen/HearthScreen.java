package com.hearthstead.client.screen;

import com.hearthstead.client.QaClientObserver;
import com.hearthstead.client.QaUiInspectable;
import com.hearthstead.client.ui.HsMotion;
import com.hearthstead.client.ui.HsButton;
import com.hearthstead.client.ui.HsUi;
import com.hearthstead.client.ui.HsUiTokens;
import com.hearthstead.client.ui.HearthPixelSurface;
import com.hearthstead.client.ui2.BannerChrome;
import com.hearthstead.client.ui2.BannerSheetLayout;
import com.hearthstead.client.ui2.Ui2Button;
import com.hearthstead.client.ui2.Ui2FrameLayout;
import com.hearthstead.client.ui2.Ui2Palette;
import com.hearthstead.client.ui2.Ui2RowButton;
import com.hearthstead.client.ui2.Ui2NavButton;
import com.hearthstead.client.ui2.Ui2Serif;
import com.hearthstead.client.ui2.Ui2Surface;
import com.hearthstead.client.ui2.Ui2WoodKey;
import com.hearthstead.client.ui2.map.MarkerTrack;
import com.hearthstead.client.ui2.map.RealmIcons;
import com.hearthstead.client.ui2.map.RealmMapClient;
import com.hearthstead.client.ui2.map.RealmMapView;
import com.hearthstead.client.ui2.Ui2Type;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.menu.HearthMenu;
import com.hearthstead.network.HearthMayorAction;
import com.hearthstead.network.HearthMayorSnapshot;
import com.hearthstead.network.RealmMapLayoutPayload;
import com.hearthstead.network.RealmMapMarkersPayload;
import com.hearthstead.network.RealmMapRequestPayload;
import com.hearthstead.network.RealmMapStatus;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    /** Live menu count for which a roster refresh was last requested. */
    private int populationRefreshRequestedFor = Integer.MIN_VALUE;
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
    private final List<AbstractButton> seatTabs = new ArrayList<>();
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

    // -- UI2 sheet (client/ui2). Geometry is pure and unit-tested; the tab
    // indicator object survives widget rebuilds so its underline can slide. --
    private BannerSheetLayout sheet = BannerSheetLayout.forSheet(410, 224);

    // -- Banner screen pages and the live realm map (client/ui2/map). The map
    // is display only; the subscription below is the one request it makes. --
    private boolean buildingsPageOpen;
    private final RealmMapView mapView = new RealmMapView(this::onMapSelection);
    private boolean mapSubscribed;
    private UUID mapFocusSent = RealmMapRequestPayload.NO_FOCUS;
    /** Profession filter from Workers & Jobs (-1 = none); highlights the map and filters Settlers. */
    private int jobFilter = -1;
    private UUID selectedBuildingId;
    private int buildingsScroll;
    private int widgetLayoutVersion = Integer.MIN_VALUE;
    private boolean cardInspectable;
    private final Ui2Serif.Text titleText = new Ui2Serif.Text(Ui2Serif.Size.TITLE);
    private final Map<String, Ui2Serif.Text> headings = new HashMap<>();
    private record JobRow(int professionId, Component label, Component count, ItemStack icon, int filled) { }
    private List<JobRow> jobRows = List.of();
    private int jobRowsVersion = Integer.MIN_VALUE;
    private String jobRowsLanguage = "";
    private int cachedCoins = Integer.MIN_VALUE;
    private Component coinsValue = Component.literal("\u2014");
    private Component subtitleLine = Component.empty();
    private int subtitleKey = Integer.MIN_VALUE;
    private HsUi.FittedLabel needLine = fittedEmpty();
    private String needLineSource = "";
    private SettlerCardModel settlerCard;
    private BuildingCardModel buildingCard;
    private final ItemStack coinIcon = new ItemStack(com.hearthstead.registry.ModItems.GOLD_COIN.get());
    private final ItemStack breadIcon = new ItemStack(Items.BREAD);
    private final ItemStack[] navIcons = {
        new ItemStack(Items.FILLED_MAP), new ItemStack(Items.PLAYER_HEAD), new ItemStack(Items.BRICKS),
        new ItemStack(Items.CHEST), new ItemStack(Items.WRITABLE_BOOK), new ItemStack(Items.LECTERN)
    };
    private static final Component[] NAV_LABELS = {
        Component.literal("Overview"), Component.literal("Settlers"), Component.literal("Buildings"),
        Component.literal("Storage"), Component.literal("Journey"), Component.literal("Tech Tree")
    };
    private record Need(String key, Component text, Component action, Runnable run, boolean danger) { }
    private List<Need> needs = List.of();
    private String needsSignature = "";
    private List<HsUi.FittedLabel> needLabels = List.of();
    private int headerDayKey = Integer.MIN_VALUE;
    private Component headerDayLine = Component.empty();
    private HsUi.FittedLabel headerTitle = fittedEmpty();
    private String headerTitleSource = "\u0000";
    private HsMotion.ScreenIntro pageFade;
    private int lastPageKind = -1;
    private static final Component FIGURE_PEOPLE = Component.literal("people");
    private static final Component FIGURE_FOOD = Component.literal("food");
    private static final Component NEEDS_HEADER = Ui2Type.label("Needs you");
    private static final Component VILLAGE_HEADER = Ui2Type.label("Village");
    private static final Component CALM_TITLE = Component.literal("All is calm");
    private static final Component NOTHING_ELSE = Component.literal("Nothing else needs you.");
    private final ItemStack moraleIcon = new ItemStack(Items.CAKE);

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
        if (heldStackBlocks()) return;
        boolean opening = storageOpenRequestsLedger(open, suppliesOpen);
        if (opening) supplyCategory = HearthSupplyCategory.ALL;
        suppliesOpen = open;
        if (opening) requestLedgerForStorage();
        mayorTabOpen = peopleTabOpen = journeyTabOpen = recruitmentPanelOpen = requestPanelOpen = false;
        buildingsPageOpen = false;
        journeySkipConfirm = false;
        rebuildWidgets();
    }
    @Override
    protected void init() {
        sheet = BannerSheetLayout.forViewport(width, height);
        imageWidth = sheet.width();
        imageHeight = sheet.height();
        headerTitleSource = "\u0000";
        super.init();
        topPos = hearthTopFor(height, imageHeight);
        // Keep the same client Slot instances, server indices and vanilla drag references.
        layoutSupplySlots();
        inventoryLabelX = sheet.playerGrid().x();
        inventoryLabelY = sheet.playerGrid().y() - 10;
        applyResponsivePopoutLayout();
        cachedSettlementName = "\u0000";
        cachedRecruitBlocker = null;
        raidStatusRefreshTicks = 0;
        rebuildSeatWidgets();
        // The live map feed follows this exact open menu; re-sent on every
        // init so a resize or reopen never leaves a stale subscription.
        subscribeMap();
        if (pendingMapFocus != null) {
            UUID focus = pendingMapFocus;
            pendingMapFocus = null;
            if (!suppliesOpen) {
                clearPages();
                mapView.selectSettler(focus, true);
                rebuildSeatWidgets();
            }
        }
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
            int[] at = i < HearthMenu.COMMUNAL_SLOTS
                ? storageSlot(visibleCommunal++) : storageSlot(i);
            slot.x = at[0];
            slot.y = at[1];
        }
    }

    /** Local slot origin for menu index {@code index} on the Storage page (6x4 stores, then 9x3 + hotbar). */
    private int[] storageSlot(int index) {
        if (index < HearthMenu.COMMUNAL_SLOTS) {
            BannerSheetLayout.Rect grid = sheet.communalGrid();
            return new int[] {grid.x() + index % 6 * 18, grid.y() + index / 6 * 18};
        }
        BannerSheetLayout.Rect grid = sheet.playerGrid();
        int player = index - HearthMenu.COMMUNAL_SLOTS;
        return player < 27
            ? new int[] {grid.x() + player % 9 * 18, grid.y() + player / 9 * 18}
            : new int[] {grid.x() + (player - 27) * 18, grid.y() + 3 * 18 + 4};
    }

    private boolean supplyContentsChanged() {
        int count = Math.min(HearthMenu.COMMUNAL_SLOTS, menu.slots.size());
        if (laidOutSupplies.size() != count) return true;
        for (int i = 0; i < count; i++) {
            if (!ItemStack.matches(laidOutSupplies.get(i), menu.slots.get(i).getItem())) return true;
        }
        return false;
    }

    /** A held stack keeps the player on Storage; say so instead of silently ignoring the click. */
    private boolean heldStackBlocks() {
        if (menu.getCarried().isEmpty()) return false;
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal("Put down the item you are holding first"), true);
        }
        return true;
    }

    private void selectSupplyCategory(HearthSupplyCategory category) {
        if (category == supplyCategory || heldStackBlocks()) return;
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
        unsubscribeMap();
        mapView.close();
        RealmMapClient.reset();
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
        widgetLayoutVersion = RealmMapClient.layoutVersion();
        // Left rail: Overview, Settlers, Buildings and Storage are
        // pages of this panel; Tasks is the request ledger page; Tech opens the
        // tech tree window (north-east arrow). Stores (the settlement-wide
        // index) opens from the Storage page.
        boolean overview = overviewOpen();
        boolean[] selected = {overview && !journeyTabOpen, peopleTabOpen, buildingsPageOpen, suppliesOpen,
            journeyTabOpen, false};
        Runnable[] actions = {
            this::showOverview,
            () -> { if (leaveSupplies()) openPeoplePanel(); },
            () -> { if (leaveSupplies()) openBuildingsPage(); },
            () -> {
                QaClientObserver.markUiTransition("hearth_supplies_tab");
                switchSupplies(true);
            },
            this::openJourneyPanel,
            this::requestDevelopmentData
        };
        Component[] tips = {
            Component.literal("The live map, who works where, and what needs you"),
            Component.literal("Every recorded resident"),
            Component.literal("Every building the plaques have declared"),
            Component.literal("The Banner's physical storage and your inventory slots"),
            Component.literal("Your next step, raid readiness and the latest raid report"),
            Component.translatable("hearthstead.development.open.tip")
        };
        for (int i = 0; i < NAV_LABELS.length; i++) {
            BannerSheetLayout.Rect r = sheet.navItem(i);
            Ui2NavButton nav = new Ui2NavButton(leftPos + r.x(), topPos + r.y(), r.width(), r.height(),
                NAV_LABELS[i], navIcons[i], selected[i], sheet.compactNav(), i == NAV_LABELS.length - 1,
                i == NAV_LABELS.length - 1, actions[i]);
            nav.setTooltip(Tooltip.create(sheet.compactNav()
                ? NAV_LABELS[i].copy().append("\n").append(tips[i].copy().withStyle(
                    net.minecraft.ChatFormatting.GRAY))
                : tips[i]));
            // Delayed so passing over the rail never covers the page with a tooltip.
            nav.setTooltipDelay(java.time.Duration.ofMillis(650));
            addSeatTab(nav);
        }

        if (!hasOpenPopout()) {
            BannerSheetLayout.Rect c = sheet.close();
            HsButton close = new com.hearthstead.client.ui2.Ui2WoodKey(leftPos + c.x(), topPos + c.y(), c.width(), c.height(),
                Component.literal("\u00d7"), this::onClose);
            close.setTooltip(Tooltip.create(Component.literal("Close (Esc)")));
            addRenderableWidget(close);
            if (overview) {
                rebuildOverviewWidgets();
            } else if (peopleTabOpen) {
                rebuildPeopleWidgets();
            } else if (buildingsPageOpen) {
                rebuildBuildingsWidgets();
            } else if (suppliesOpen) {
                rebuildStorageWidgets();
            }
        }

        if (recruitmentPanelOpen) {
            rebuildRecruitmentWidgets();
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
        int nextX = mayorPanelLeft + mayorPanelWidth - MAYOR_PAD - navWidth;

        HsButton previous = Ui2Button.secondary(mayorPanelLeft + MAYOR_PAD,
            mayorPanelTop + mayorButtonY, navWidth, HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.mayor.page.previous"),
            () -> changeMayorPage(-1));
        previous.active = mayorPage > 0;
        previous.setTooltip(Tooltip.create(Component.translatable(
            "hearthstead.mayor.page.previous.tip")));
        addPanelWidget(previous);

        HsButton next = Ui2Button.secondary(nextX, mayorPanelTop + mayorButtonY,
            navWidth, HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.mayor.page.next"),
            () -> changeMayorPage(1));
        next.active = mayorPage + 1 < pages;
        next.setTooltip(Tooltip.create(Component.translatable(
            "hearthstead.mayor.page.next.tip")));
        addPanelWidget(next);

        addPanelClose(mayorPanelLeft, mayorPanelTop, mayorPanelWidth);

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
            HsButton appoint = Ui2Button.banner(appointX, y + 4, appointWidth,
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

    /** Leaves the Supplies page before another in-sheet page; a held stack keeps you there. */
    private boolean leaveSupplies() {
        if (!suppliesOpen) return true;
        if (heldStackBlocks()) return false;
        suppliesOpen = false;
        rebuildWidgets();
        return true;
    }

    /**
     * Server-authored facts ranked into at most a handful of sentences, each
     * with exactly one action. Pure formatting: no client-side game rules.
     */
    private List<Need> computeNeeds() {
        List<Need> out = new ArrayList<>(6);
        int pop = displayedPopulation(menu.get(HearthMenu.DATA_POPULATION),
            mayorSnapshot == null ? -1 : mayorSnapshot.residentTotal());
        int cap = menu.get(HearthMenu.DATA_CAPACITY);
        int food = menu.get(HearthMenu.DATA_FOOD);
        int alert = menu.get(HearthMenu.DATA_ALERT);
        if (alert == 1) {
            out.add(new Need("alert", SETTLEMENT_STATUS_ALERT, Component.literal("See the raid"),
                this::openJourneyPanel, true));
        }
        if (food <= 0) {
            out.add(new Need("food", Component.literal("Food stores are empty"),
                Component.literal("Stock food"), () -> switchSupplies(true), true));
        }
        if (cachedRecruitmentCard.present()) {
            out.add(new Need("traveler", recruitmentStageLine.getString().isEmpty()
                ? Component.literal(cachedRecruitmentCard.name() + " waits to join")
                : Component.literal(cachedRecruitmentCard.name() + ": ").append(recruitmentStageLine), Component.translatable("hearthstead.recruit.card.review"), () -> {
                    QaClientObserver.markUiTransition("hearth_recruitment_popout");
                    mayorTabOpen = peopleTabOpen = journeyTabOpen = requestPanelOpen = false;
                    recruitmentPanelOpen = true;
                    journeySkipConfirm = false;
                    rebuildSeatWidgets();
                }, false));
        }
        if (pop > cap) {
            out.add(new Need("beds", Component.literal("Beds " + cap + " / " + pop),
                Component.literal("Build beds"), this::requestDevelopmentData, true));
        }
        RecruitmentPolicy.Blocker blocker = RecruitmentPolicy.Blocker.fromWireId(
            menu.get(HearthMenu.DATA_RECRUIT_BLOCKER));
        int morale = menu.get(HearthMenu.DATA_MORALE);
        boolean emptyRecovery = emptySettlementRecoveryVisible(pop, blocker, cachedRecruitmentCard.present());
        if (emptyRecovery) {
            out.add(new Need("recruit:empty", Component.translatable(
                "hearthstead.gui.recruit_summary.empty_settlement"), Component.literal("Next step"),
                this::openJourneyPanel, true));
        } else if (blocker == RecruitmentPolicy.Blocker.NO_TAVERN
                || (blocker == RecruitmentPolicy.Blocker.NO_BED && pop <= cap)) {
            out.add(new Need("recruit:" + blocker.name(), recruitStatus(blocker, pop, cap, morale),
                Component.literal(blocker == RecruitmentPolicy.Blocker.NO_TAVERN ? "Build a tavern" : "Build beds"),
                this::requestDevelopmentData, false));
        } else if (blocker == RecruitmentPolicy.Blocker.LOW_MORALE) {
            out.add(new Need("recruit:morale", recruitStatus(blocker, pop, cap, morale),
                Component.literal("See settlers"), this::openPeoplePanel, false));
        } else if ((blocker == RecruitmentPolicy.Blocker.INSUFFICIENT_READY_FOOD
                || blocker == RecruitmentPolicy.Blocker.CANNOT_PAY) && food > 0) {
            out.add(new Need("recruit:food", recruitStatus(blocker, pop, cap, morale),
                Component.literal("Stock food"), () -> switchSupplies(true), false));
        }
        if (mayorSnapshot != null && !mayorSnapshot.hasMayor() && mayorSnapshot.residentTotal() > 0) {
            out.add(new Need("mayor", Component.literal("No mayor appointed"), Component.literal("Appoint mayor"),
                () -> {
                    QaClientObserver.markUiTransition("hearth_mayor_popout");
                    peopleTabOpen = false;
                    mayorTabOpen = true;
                    requestMayorData();
                    rebuildSeatWidgets();
                }, false));
        }
        return List.copyOf(out);
    }

    private static String needsSignatureOf(List<Need> list) {
        StringBuilder key = new StringBuilder();
        for (Need need : list) {
            key.append(need.key()).append('|').append(need.text().getString()).append(';');
        }
        return key.toString();
    }

    // ------------------------------------------------------------ pages ---

    /** Overview is the default page: none of the other in-panel pages is open. */
    private boolean overviewOpen() {
        return !peopleTabOpen && !requestPanelOpen && !suppliesOpen && !buildingsPageOpen;
    }

    /** A settler another screen asked the next Banner screen to show on its map (client only, display only). */
    private static UUID pendingMapFocus;

    /** Called when the client leaves a world so a queued focus never leaks into the next one. */
    public static void clearPendingMapFocus() {
        pendingMapFocus = null;
    }

    /**
     * Asks the next Banner screen that opens to start on Overview with this
     * settler selected on the live map. Consumed once; it opens nothing by
     * itself (the Banner menu still needs the player at the Banner).
     */
    public static void requestMapFocus(UUID settler) {
        pendingMapFocus = settler;
    }

    private void clearPages() {
        peopleTabOpen = requestPanelOpen = buildingsPageOpen = false;
    }

    private void showOverview() {
        QaClientObserver.markUiTransition("hearth_home_tab");
        if (suppliesOpen) {
            switchSupplies(false);
            return;
        }
        mayorTabOpen = journeyTabOpen = recruitmentPanelOpen = false;
        clearPages();
        journeySkipConfirm = false;
        rebuildSeatWidgets();
    }

    private void openBuildingsPage() {
        QaClientObserver.markUiTransition("hearth_buildings_open");
        mayorTabOpen = journeyTabOpen = recruitmentPanelOpen = false;
        clearPages();
        buildingsPageOpen = true;
        journeySkipConfirm = false;
        rebuildSeatWidgets();
    }

    /** Opens Overview with a settler selected and the camera gliding to them. */
    private void showSettlerOnMap(UUID id) {
        if (!leaveSupplies()) return;
        mayorTabOpen = journeyTabOpen = recruitmentPanelOpen = false;
        clearPages();
        mapView.selectSettler(id, true);
        rebuildSeatWidgets();
    }

    private void showBuildingOnMap(UUID id) {
        if (!leaveSupplies()) return;
        mayorTabOpen = journeyTabOpen = recruitmentPanelOpen = false;
        clearPages();
        mapView.selectBuilding(id, true);
        rebuildSeatWidgets();
    }

    // -------------------------------------------------------- live map ---

    private RealmMapRequestPayload mapRequest(RealmMapRequestPayload.Kind kind, UUID focus) {
        return new RealmMapRequestPayload(menu.getHearthPos(), menu.getSettlementId(),
            menu.getContainerId(), kind, focus);
    }

    private void subscribeMap() {
        if (!mapSubscribed) {
            // A different settlement's leftovers must never show here.
            if (!menu.getSettlementId().equals(RealmMapClient.settlementId())) RealmMapClient.reset();
        }
        mapSubscribed = true;
        mapFocusSent = desiredFocus();
        PacketDistributor.sendToServer(mapRequest(RealmMapRequestPayload.Kind.SUBSCRIBE, mapFocusSent));
    }

    private void unsubscribeMap() {
        if (!mapSubscribed) return;
        mapSubscribed = false;
        PacketDistributor.sendToServer(mapRequest(RealmMapRequestPayload.Kind.UNSUBSCRIBE,
            RealmMapRequestPayload.NO_FOCUS));
    }

    /** The settler whose level and carried bag the visible card needs. */
    private UUID desiredFocus() {
        UUID id = null;
        if (overviewOpen()) id = mapView.selectedSettler();
        else if (peopleTabOpen) id = selectedResidentId;
        return id == null ? RealmMapRequestPayload.NO_FOCUS : id;
    }

    private void maintainMap() {
        if (!mapSubscribed) return;
        UUID focus = desiredFocus();
        if (!focus.equals(mapFocusSent)) {
            mapFocusSent = focus;
            PacketDistributor.sendToServer(mapRequest(RealmMapRequestPayload.Kind.FOCUS, focus));
        }
        boolean mapPage = overviewOpen() || buildingsPageOpen;
        if (mapPage && !hasOpenPopout() && menu.getCarried().isEmpty()
            && RealmMapClient.layoutVersion() != widgetLayoutVersion) {
            // Roster or buildings changed: rows and cards own real buttons.
            rebuildSeatWidgets();
        }
        UUID selected = mapView.selectedSettler();
        if (mapPage && selected != null && !hasOpenPopout()) {
            boolean inspectable = canInspectSettler(selected);
            if (inspectable != cardInspectable) rebuildSeatWidgets();
        }
    }

    /** Called by the map when a click, key or clear changed its selection. */
    private void onMapSelection() {
        QaClientObserver.markUiTransition("hearth_map_select");
        rebuildSeatWidgets();
    }

    private boolean canInspectSettler(UUID id) {
        MarkerTrack track = RealmMapClient.track(id);
        if (track == null || track.entityId < 0 || minecraft == null || minecraft.level == null
            || minecraft.player == null) {
            return false;
        }
        return minecraft.level.getEntity(track.entityId) instanceof com.hearthstead.entity.SettlerEntity settler
            && id.equals(settler.getUUID()) && minecraft.player.distanceToSqr(settler) <= 64.0D;
    }

    private void viewSettler(UUID id) {
        if (id == null || !canInspectSettler(id)) return;
        PacketDistributor.sendToServer(mayorAction(HearthMayorAction.Kind.VIEW_SETTLER, id, 0));
    }

    private RealmMapLayoutPayload mapLayout() {
        return RealmMapClient.hasData(menu.getSettlementId()) ? RealmMapClient.layout() : null;
    }

    // -------------------------------------------------- right column ---

    static final int RC_HEADING_H = 14;
    static final int JOB_ROW_H = 13;

    /** Rows of Workers & Jobs that fit above Supplies and the primary action. */
    private int jobRowsVisible() {
        // Calm column: at most four rows (three trades and "+N more" when there are more).
        int budget = sheet.right().height() - RC_HEADING_H - 2 - 84;
        return Math.max(0, Math.min(Math.min(4, jobRows.size()), budget / JOB_ROW_H));
    }

    private int jobRowY(int index) {
        return sheet.right().y() + RC_HEADING_H + index * JOB_ROW_H;
    }

    private void ensureJobRows() {
        int version = RealmMapClient.layoutVersion();
        String language = currentLanguage();
        if (version == jobRowsVersion && language.equals(jobRowsLanguage)) return;
        jobRowsVersion = version;
        jobRowsLanguage = language;
        RealmMapLayoutPayload layout = mapLayout();
        if (layout == null) {
            jobRows = List.of();
            return;
        }
        int[] counts = new int[Profession.BY_ID.length];
        for (RealmMapLayoutPayload.RosterEntry r : layout.roster()) {
            counts[Mth.clamp(r.professionId(), 0, counts.length - 1)]++;
        }
        // Capacity: staff slots of valid buildings that teach each trade.
        int[] capacity = new int[Profession.BY_ID.length];
        for (RealmMapLayoutPayload.BuildingEntry b : layout.buildings()) {
            com.hearthstead.building.BuildingType type = com.hearthstead.building.BuildingType.byId(b.typeId());
            if (type == null || !b.valid()) continue;
            Profession trade = com.hearthstead.settlement.Employment.tradeOf(type);
            if (trade != Profession.NONE) capacity[trade.id()] += b.workerCapacity();
        }
        List<JobRow> rows = new ArrayList<>();
        boolean english = language.startsWith("en");
        for (Profession p : Profession.BY_ID) {
            int n = counts[p.id()];
            if (n <= 0) continue;
            Component name = p == Profession.NONE ? Component.literal("Unassigned")
                : english && n > 1 && p != Profession.MAYOR
                    ? Component.literal(p.displayName().getString() + "s") : p.displayName();
            String count = capacity[p.id()] > 0 ? n + "/" + capacity[p.id()] : String.valueOf(n);
            rows.add(new JobRow(p.id(), name, Component.literal(count), RealmIcons.profession(p), n));
        }
        rows.sort((a, b) -> {
            int byCount = Integer.compare(b.filled(), a.filled());
            return byCount != 0 ? byCount : a.label().getString().compareTo(b.label().getString());
        });
        jobRows = List.copyOf(rows);
        if (jobFilter >= 0 && rows.stream().noneMatch(r -> r.professionId() == jobFilter)) {
            jobFilter = -1;
            mapView.setHighlightProfession(-1);
        }
    }

    private void toggleJobFilter(int professionId) {
        jobFilter = jobFilter == professionId ? -1 : professionId;
        mapView.setHighlightProfession(jobFilter);
        peopleRenderSnapshot = null;
        QaClientObserver.markUiTransition("hearth_job_filter");
        rebuildSeatWidgets();
    }

    /** Overview right column: a selection card, or Workers & Jobs, Supplies and one primary action. */
    private void rebuildOverviewWidgets() {
        needs = computeNeeds();
        needsSignature = needsSignatureOf(needs);
        ensureJobRows();
        BannerSheetLayout.Rect r = sheet.right();
        UUID settler = mapView.selectedSettler();
        UUID building = mapView.selectedBuilding();
        if (settler != null && RealmMapClient.roster(settler) != null) {
            addSettlerCardWidgets(r, settler);
            return;
        }
        if (building != null && RealmMapView.building(building) != null) {
            addBuildingCardWidgets(r, building, false);
            return;
        }
        if (settler != null || building != null) mapView.clearSelection();
        if (!sheet.stacked()) {
            int rows = jobRowsVisible();
            boolean more = rows < jobRows.size();
            for (int i = 0; i < rows; i++) {
                if (more && i == rows - 1) {
                    HsButton all = new Ui2RowButton(leftPos + r.x(), topPos + jobRowY(i), r.width(), JOB_ROW_H,
                        Component.literal("All settlers"), false, this::openPeoplePanel);
                    all.setTooltip(Tooltip.create(Component.literal("Every resident, on the Settlers page")));
                    addRenderableWidget(all);
                    break;
                }
                JobRow row = jobRows.get(i);
                HsButton button = new Ui2RowButton(leftPos + r.x(), topPos + jobRowY(i), r.width(), JOB_ROW_H,
                    row.label(), jobFilter == row.professionId(), () -> toggleJobFilter(row.professionId()));
                button.setTooltip(Tooltip.create(Component.literal(jobFilter == row.professionId()
                    ? "Show everyone again" : "Highlight them on the map and filter Settlers")));
                addRenderableWidget(button);
            }
        }
        if (!needs.isEmpty()) {
            Need focal = needs.get(0);
            int w = sheet.stacked() ? Math.min(r.width() / 2, Ui2Button.filledWidth(font, focal.action()) + 16)
                : r.width();
            Ui2Button primary = Ui2Button.banner(leftPos + r.right() - w, topPos + r.bottom() - 20, w, 20,
                focal.action(), focal.run()).withIcon(needIcon(focal.key()));
            if (focal.key().equals("beds")) {
                primary.setTooltip(Tooltip.create(Component.literal(menu.get(HearthMenu.DATA_CAPACITY)
                    + " places to sleep for " + displayedPopulation(menu.get(HearthMenu.DATA_POPULATION),
                    mayorSnapshot == null ? -1 : mayorSnapshot.residentTotal())
                    + " settlers. Learn and build houses in the tech tree.")));
            } else if (focal.key().equals("recruit:NO_TAVERN")) {
                primary.setTooltip(Tooltip.create(Component.literal(
                    "Travelers only come to a tavern. Opens the Tech Tree, where you learn and place one.")));
            } else if (focal.key().equals("recruit:NO_BED")) {
                primary.setTooltip(Tooltip.create(Component.literal(
                    "A new settler needs a free bed. Opens the Tech Tree, where you learn houses.")));
            }
            addRenderableWidget(primary);
        }
    }

    /** An item that says what the primary action is about. */
    private static ItemStack needIcon(String key) {
        if (key.startsWith("food") || key.equals("recruit:food")) return new ItemStack(Items.BREAD);
        if (key.equals("traveler")) return new ItemStack(Items.PLAYER_HEAD);
        if (key.equals("beds") || key.startsWith("recruit:NO_BED")) return new ItemStack(Items.RED_BED);
        if (key.equals("mayor")) return new ItemStack(Items.GOLDEN_HELMET);
        if (key.equals("alert")) return new ItemStack(Items.BELL);
        if (key.startsWith("recruit:NO_TAVERN")) return new ItemStack(Items.BARREL);
        if (key.equals("recruit:morale")) return new ItemStack(Items.CAKE);
        return new ItemStack(Items.FILLED_MAP);
    }

    private void addSettlerCardWidgets(BannerSheetLayout.Rect r, UUID id) {
        HsButton clear = Ui2Button.secondary(leftPos + r.right() - 10, topPos + r.y() - 1, 10, 11,
            Component.literal("×"), () -> {
                mapView.clearSelection();
                rebuildSeatWidgets();
            });
        clear.setTooltip(Tooltip.create(Component.literal("Clear the selection (Esc)")));
        addRenderableWidget(clear);
        boolean following = mapView.following();
        Component followLabel = Component.literal(following ? "Following" : "Follow");
        int fw = Ui2Button.textWidth(font, followLabel);
        int viewW = sheet.stacked() ? Math.min(92, r.width() / 2) : r.width();
        int followY = sheet.stacked() ? r.bottom() - 16 : r.bottom() - 34;
        int followX = sheet.stacked() ? r.right() - viewW - 8 - fw : r.x();
        HsButton follow = Ui2Button.secondary(leftPos + followX, topPos + followY, fw, 12, followLabel, () -> {
            mapView.toggleFollow();
            rebuildSeatWidgets();
        });
        follow.setTooltip(Tooltip.create(Component.literal(following
            ? "Stop following (F or drag the map)" : "Keep the map centred on them (F)")));
        follow.visible = overviewOpen();
        addRenderableWidget(follow);
        if (!sheet.stacked() && RealmMapClient.track(id) != null) {
            Component center = Component.literal("Center");
            int cw = Ui2Button.textWidth(font, center);
            HsButton centre = Ui2Button.secondary(leftPos + r.right() - cw, topPos + followY, cw, 12, center,
                () -> mapView.selectSettler(id, true));
            centre.setTooltip(Tooltip.create(Component.literal("Glide the map to them")));
            centre.visible = overviewOpen();
            addRenderableWidget(centre);
        }
        if (!sheet.stacked() && settlerHasWorkplace(id)) {
            RealmMapLayoutPayload.BuildingEntry work = mapLayout().buildings().get(RealmMapClient.roster(id).workBuilding());
            Component label = HsUi.fitLabel(font, Component.literal("At ").append(RealmMapView.buildingName(work)),
                r.width() - 8).text();
            int ww = Ui2Button.textWidth(font, label);
            HsButton jump = Ui2Button.secondary(leftPos + r.x() - 3, topPos + settlerWorkplaceY(r) - 2, ww, 12, label,
                () -> {
                    if (overviewOpen()) {
                        mapView.selectBuilding(work.id(), true);
                        rebuildSeatWidgets();
                    } else {
                        showBuildingOnMap(work.id());
                    }
                });
            jump.setTooltip(Tooltip.create(Component.literal("Show their workplace on the map")));
            addRenderableWidget(jump);
        }
        if (sheet.stacked()) {
            Component summonLabel = summonLabel(id);
            int sw = Ui2Button.textWidth(font, summonLabel);
            addRenderableWidget(summonButton(leftPos + Math.max(r.x(), followX - sw - 4), topPos + followY, sw, 12, id));
        } else {
            addRenderableWidget(summonButton(leftPos + r.x(), topPos + r.bottom() - 50, r.width(), 13, id));
        }
        cardInspectable = canInspectSettler(id);
        HsButton view = Ui2Button.banner(leftPos + r.right() - viewW, topPos + r.bottom() - 20, viewW, 20,
            Component.literal("View Settler"), () -> viewSettler(id));
        view.active = cardInspectable;
        view.setTooltip(Tooltip.create(Component.literal(cardInspectable
            ? "Open their full settler sheet"
            : RealmMapClient.track(id) == null ? "They are away: their chunk is not loaded"
            : "Walk within 8 blocks of them to open their sheet")));
        addRenderableWidget(view);
    }

    // ------------------------------------------------------------ summon ---

    private int summonSignature = Integer.MIN_VALUE;
    private long summonCheckMs;

    private static Component summonLabel(UUID id) {
        Boolean arrived = com.hearthstead.client.command.SummonClient.enRoute().get(id);
        return Component.literal(arrived == null ? "Summon to me" : arrived ? "Waiting with you" : "On the way to you");
    }

    /**
     * Summon, for every settler: asks the server (the command lane's
     * request) to send them to this player. Disabled with the reason when
     * the client already knows it cannot work; everything else the server
     * answers itself. While they walk, the map draws a dashed line to you.
     */
    private HsButton summonButton(int x, int y, int w, int h, UUID id) {
        MarkerTrack track = RealmMapClient.track(id);
        SettlerEntity entity = null;
        if (track != null && track.entityId >= 0 && minecraft != null && minecraft.level != null
            && minecraft.level.getEntity(track.entityId) instanceof SettlerEntity settler && settler.getUUID().equals(id)) {
            entity = settler;
        }
        Boolean arrived = com.hearthstead.client.command.SummonClient.enRoute().get(id);
        java.util.Optional<Component> reason = arrived == null
            ? com.hearthstead.client.command.SummonClient.unavailableReason(entity) : java.util.Optional.empty();
        int entityId = entity == null ? -1 : entity.getId();
        HsButton button = Ui2Button.secondary(x, y, w, h, summonLabel(id), () -> {
            com.hearthstead.client.command.SummonClient.request(entityId, id);
            summonCheckMs = 0L;
        });
        button.active = arrived == null && reason.isEmpty();
        Component tip = arrived != null
            ? Component.literal(arrived ? "They wait at your side for a minute, then go back to work"
                : "Walking to you -- the dashed line on the map shows the way")
            : reason.orElse(Component.literal("Call them to you, wherever you are in the realm"));
        button.setTooltip(Tooltip.create(tip));
        return button;
    }

    /** Rebuilds the card when this player's summons change (checked a few times a second, not per frame). */
    private void maintainSummons() {
        long now = net.minecraft.Util.getMillis();
        if (now - summonCheckMs < 250L) return;
        summonCheckMs = now;
        int signature = com.hearthstead.client.command.SummonClient.enRoute().hashCode();
        if (signature == summonSignature) return;
        boolean first = summonSignature == Integer.MIN_VALUE;
        summonSignature = signature;
        if (!first) rebuildSeatWidgets();
    }

    private void addBuildingCardWidgets(BannerSheetLayout.Rect r, UUID id, boolean showOnMap) {
        HsButton clear = Ui2Button.secondary(leftPos + r.right() - 10, topPos + r.y() - 1, 10, 11,
            Component.literal("×"), () -> {
                if (buildingsPageOpen) {
                    selectedBuildingId = null;
                } else {
                    mapView.clearSelection();
                }
                rebuildSeatWidgets();
            });
        clear.setTooltip(Tooltip.create(Component.literal("Clear the selection (Esc)")));
        addRenderableWidget(clear);
        RealmMapLayoutPayload layout = mapLayout();
        RealmMapLayoutPayload.BuildingEntry b = RealmMapView.building(id);
        if (layout == null || b == null) return;
        int index = layout.buildings().indexOf(b);
        int y = r.y() + BUILDING_WORKERS_TOP + (b.valid() ? 0 : 9);
        int maxY = r.bottom() - (showOnMap ? 22 : 2);
        for (RealmMapLayoutPayload.RosterEntry worker : layout.roster()) {
            if (worker.workBuilding() != index) continue;
            if (y + 12 > maxY || sheet.stacked()) break;
            HsButton row = new Ui2RowButton(leftPos + r.x(), topPos + y, r.width(), 12,
                Component.literal(worker.name()), false, () -> showSettlerOnMap(worker.id()));
            row.setTooltip(Tooltip.create(Component.literal("Select " + worker.name() + " on the map")));
            addRenderableWidget(row);
            y += 12;
        }
        if (showOnMap) {
            Ui2Button show = Ui2Button.banner(leftPos + r.x(), topPos + r.bottom() - 20, r.width(), 20,
                Component.literal("Show on map"), () -> showBuildingOnMap(id)).withIcon(new ItemStack(Items.FILLED_MAP));
            addRenderableWidget(show);
        }
    }

    static final int BUILDING_WORKERS_TOP = 56;
    static final int BUILDING_ROW_H = 20;
    static final int TABLE_TOP = 13;

    private int buildingRowsVisible() {
        return Math.max(1, (sheet.centre().height() - TABLE_TOP - 2) / BUILDING_ROW_H);
    }

    private void rebuildBuildingsWidgets() {
        RealmMapLayoutPayload layout = mapLayout();
        BannerSheetLayout.Rect c = sheet.centre();
        if (layout == null) return;
        List<RealmMapLayoutPayload.BuildingEntry> buildings = layout.buildings();
        int visible = buildingRowsVisible();
        buildingsScroll = Mth.clamp(buildingsScroll, 0, Math.max(0, buildings.size() - visible));
        if (selectedBuildingId == null && !buildings.isEmpty()) selectedBuildingId = buildings.get(0).id();
        for (int row = 0; row < visible && buildingsScroll + row < buildings.size(); row++) {
            RealmMapLayoutPayload.BuildingEntry b = buildings.get(buildingsScroll + row);
            HsButton button = new Ui2RowButton(leftPos + c.x() + 1, topPos + c.y() + TABLE_TOP + row * BUILDING_ROW_H,
                c.width() - 6, BUILDING_ROW_H, RealmMapView.buildingName(b), b.id().equals(selectedBuildingId), () -> {
                    selectedBuildingId = b.id();
                    rebuildSeatWidgets();
                });
            addRenderableWidget(button);
        }
        if (selectedBuildingId != null && RealmMapView.building(selectedBuildingId) != null) {
            addBuildingCardWidgets(sheet.right(), selectedBuildingId, true);
        }
    }

    private void rebuildStorageWidgets() {
        BannerSheetLayout.Rect wide = sheet.wide();
        Component stores = Component.literal("Stores");
        int sw = Ui2Button.textWidth(font, stores) + 8;
        HsButton open = Ui2Button.secondary(leftPos + wide.right() - sw, topPos + wide.y() - 1, sw, 11, stores, () -> {
            QaClientObserver.markUiTransition("hearth_stores_open");
            PacketDistributor.sendToServer(new StorageRequestPayload());
        });
        open.setTooltip(Tooltip.create(Component.literal("View settlement-wide stored items (opens a window)")));
        addRenderableWidget(open);
        Component filter = Component.literal(supplyCategory.displayName());
        int fw = Ui2Button.textWidth(font, filter);
        HsButton cycle = Ui2Button.secondary(leftPos + sheet.communalGrid().right() - fw,
            topPos + wide.y() - 1, fw, 11, filter, () -> {
                HearthSupplyCategory[] all = HearthSupplyCategory.values();
                selectSupplyCategory(all[(supplyCategory.ordinal() + 1) % all.length]);
            });
        cycle.setTooltip(Tooltip.create(Component.literal("Showing " + supplyCategory.displayName()
            + " — click for the next category")));
        addRenderableWidget(cycle);
    }

    // -------------------------------------------------------- card models ---

    /** Everything a settler card draws, fitted once per change (never per frame). */
    private record SettlerCardModel(UUID id, int status, int activity, int profession, Object focus,
                                    int layoutVersion, int width, Component name, Component job,
                                    Component statusWord, int statusColor, Component activityLine,
                                    Component workplace, Component fed, Component rested, float fedRatio,
                                    float restedRatio, List<ItemStack> bag, List<Component> bagCounts,
                                    boolean hasFocus, boolean away) { }

    private SettlerCardModel settlerCardModel(UUID id, int width) {
        // Patrol routes (PATROL ROUTES lane) change the activity line, so they key the cache too.
        int cardVersion = RealmMapClient.layoutVersion() * 31 + com.hearthstead.client.patrol.PatrolClient.version();
        MarkerTrack t = RealmMapClient.track(id);
        RealmMapMarkersPayload.Focus focus = RealmMapClient.focus();
        boolean hasFocus = focus.present() && focus.id().equals(id);
        int status = t == null ? -1 : t.statusId;
        int activity = t == null ? -1 : t.activityId;
        int profession = t == null ? -1 : t.professionId;
        SettlerCardModel m = settlerCard;
        if (m != null && m.id().equals(id) && m.status() == status && m.activity() == activity
            && m.profession() == profession && m.focus() == (hasFocus ? focus : null)
            && m.layoutVersion() == cardVersion && m.width() == width) {
            return m;
        }
        RealmMapLayoutPayload.RosterEntry entry = RealmMapClient.roster(id);
        RealmMapLayoutPayload layout = mapLayout();
        Profession p = Profession.byId(t != null ? t.professionId : entry == null ? 0 : entry.professionId());
        String level = hasFocus ? " · Lv " + focus.level() : "";
        Component name = HsUi.fitLabel(font, Component.literal(entry == null ? "Settler" : entry.name()),
            width - 36).text();
        Component job = HsUi.fitLabel(font, (p == Profession.NONE ? Component.literal("Unassigned")
            : p.displayName()).copy().append(level), width - 30).text();
        RealmMapStatus st = RealmMapStatus.byWireId(Math.max(0, status));
        Component word = Component.literal(t == null ? "Away" : statusWord(st));
        int color = t == null ? Ui2Palette.INK_MUTED : statusColor(st);
        Component activityLine = t == null ? Component.literal("Their chunk is not loaded")
            : HsUi.fitLabel(font, SettlerActivity.byId(activity).displayName(), width - 10).text();
        com.hearthstead.network.PatrolSnapshotPayload.Route patrolRoute = t == null ? null
            : com.hearthstead.client.patrol.PatrolClient.walking(RealmMapClient.settlementId(), id);
        if (patrolRoute != null && activity == SettlerActivity.PATROLLING.id()) {
            activityLine = HsUi.fitLabel(font, Component.literal("Patrolling: " + patrolRoute.name() + " route"),
                width - 10).text();
        }
        Component workplace;
        if (entry != null && layout != null && entry.workBuilding() >= 0
            && entry.workBuilding() < layout.buildings().size()) {
            workplace = HsUi.fitLabel(font, Component.literal("At ").append(
                RealmMapView.buildingName(layout.buildings().get(entry.workBuilding()))), width).text();
        } else {
            workplace = Component.literal(p.employed() ? "No workplace" : "No work assigned");
        }
        List<ItemStack> bag = new ArrayList<>();
        List<Component> counts = new ArrayList<>();
        if (hasFocus) {
            for (RealmMapMarkersPayload.BagSlot slot : focus.bag()) {
                var item = BuiltInRegistries.ITEM.byId(slot.itemId());
                bag.add(new ItemStack(item, Math.max(1, Math.min(99, slot.count()))));
                counts.add(Component.literal(slot.count() > 1 ? String.valueOf(slot.count()) : ""));
            }
        }
        m = new SettlerCardModel(id, status, activity, profession, hasFocus ? focus : null,
            cardVersion, width, name, job, word, color, activityLine, workplace,
            Component.literal(hasFocus ? focus.hunger() + "%" : "…"),
            Component.literal(hasFocus ? focus.energy() + "%" : "…"),
            hasFocus ? focus.hunger() / 100.0F : 0.0F, hasFocus ? focus.energy() / 100.0F : 0.0F,
            List.copyOf(bag), List.copyOf(counts), hasFocus, t == null);
        settlerCard = m;
        return m;
    }

    static String statusWord(RealmMapStatus status) {
        return switch (status) {
            case WORKING -> "Working";
            case WALKING -> "Walking";
            case IDLE -> "Idle";
            case SLEEPING -> "Resting";
            case FLEEING -> "Fleeing";
            case STUCK -> "Stuck";
            case FIGHTING -> "Fighting";
        };
    }

    static int statusColor(RealmMapStatus status) {
        return switch (status) {
            case WORKING -> Ui2Palette.FOREST;
            case WALKING -> Ui2Palette.STATUS_BLUE;
            case IDLE -> Ui2Palette.INK_MUTED;
            case SLEEPING -> Ui2Palette.AMBER;
            case FLEEING, STUCK, FIGHTING -> Ui2Palette.DANGER;
        };
    }

    /** Status glyph: shape carries the meaning, colour only repeats it. */
    static void statusGlyph(GuiGraphics g, RealmMapStatus status, int x, int y) {
        int c = statusColor(status);
        switch (status) {
            case WORKING -> {
                g.fill(x + 1, y, x + 4, y + 5, c);
                g.fill(x, y + 1, x + 5, y + 4, c);
            }
            case WALKING -> {
                g.fill(x, y + 2, x + 5, y + 3, c);
                g.fill(x + 3, y + 1, x + 4, y + 4, c);
                g.fill(x + 2, y, x + 3, y + 5, c);
            }
            case IDLE -> {
                g.fill(x + 1, y, x + 4, y + 1, c);
                g.fill(x + 1, y + 4, x + 4, y + 5, c);
                g.fill(x, y + 1, x + 1, y + 4, c);
                g.fill(x + 4, y + 1, x + 5, y + 4, c);
            }
            case SLEEPING -> {
                g.fill(x, y, x + 5, y + 1, c);
                g.fill(x + 3, y + 1, x + 4, y + 2, c);
                g.fill(x + 2, y + 2, x + 3, y + 3, c);
                g.fill(x + 1, y + 3, x + 2, y + 4, c);
                g.fill(x, y + 4, x + 5, y + 5, c);
            }
            case STUCK -> Ui2Surface.alertGlyph(g, x, y, c);
            case FLEEING -> {
                g.fill(x + 2, y, x + 3, y + 3, c);
                g.fill(x + 2, y + 4, x + 3, y + 5, c);
            }
            case FIGHTING -> {
                for (int k = 0; k < 5; k++) {
                    g.fill(x + k, y + k, x + k + 1, y + k + 1, c);
                    g.fill(x + 4 - k, y + k, x + 5 - k, y + k + 1, c);
                }
            }
        }
    }

    private record BuildingCardModel(UUID id, int layoutVersion, int width, Component name, Component level,
                                     boolean valid, Component status, Component workers, List<Component> workerNames,
                                     ItemStack emblem) { }

    private BuildingCardModel buildingCardModel(UUID id, int width) {
        BuildingCardModel m = buildingCard;
        if (m != null && m.id().equals(id) && m.layoutVersion() == RealmMapClient.layoutVersion()
            && m.width() == width) {
            return m;
        }
        RealmMapLayoutPayload layout = mapLayout();
        RealmMapLayoutPayload.BuildingEntry b = RealmMapView.building(id);
        if (layout == null || b == null) return null;
        int index = layout.buildings().indexOf(b);
        int count = RealmMapView.workersOf(layout, index);
        List<Component> names = new ArrayList<>();
        for (RealmMapLayoutPayload.RosterEntry r : layout.roster()) {
            if (r.workBuilding() == index) names.add(HsUi.fitLabel(font, Component.literal(r.name()), width - 20).text());
        }
        m = new BuildingCardModel(id, RealmMapClient.layoutVersion(), width,
            HsUi.fitLabel(font, RealmMapView.buildingName(b), width - 34).text(),
            Component.literal("Level " + b.level()), b.valid(),
            Component.literal(b.valid() ? "In good order" : "Not a valid room — check its plaque"),
            Component.literal(b.workerCapacity() > 0 ? count + " / " + b.workerCapacity() : String.valueOf(count)),
            List.copyOf(names), RealmIcons.building(b.typeId()));
        buildingCard = m;
        return m;
    }

    private void rebuildPeopleWidgets() {
        BannerSheetLayout.Rect list = sheet.centre();
        BannerSheetLayout.Rect detail = sheet.right();
        ensurePeopleRenderCache(Math.max(1, peopleRoleX() - list.x() - 26), Math.max(1, detail.width() - 4));
        // Search field (list.y..+14), count caption (+18) and rows (+30..) never share pixels.
        // A server refresh rebuilds every widget; keep what the player is typing.
        String searchDraft = peopleSearchBox == null ? appliedPeopleSearch : peopleSearchBox.getValue();
        boolean searchFocused = peopleSearchBox != null && peopleSearchBox.isFocused();
        Component findLabel = Component.literal("Find");
        int findW = Ui2Button.textWidth(font, findLabel);
        peopleSearchBox = new EditBox(font, leftPos + list.x() + 5, topPos + list.y() + 3,
            Math.max(40, list.width() - findW - 20), 10, Component.literal("Search residents"));
        peopleSearchBox.setBordered(false);
        peopleSearchBox.setMaxLength(32);
        peopleSearchBox.setValue(searchDraft);
        peopleSearchBox.setTextColor(Ui2Palette.INK);
        peopleSearchBox.setTextColorUneditable(Ui2Palette.INK_MUTED);
        peopleSearchBox.setHint(Component.literal("Search residents")
            .withStyle(net.minecraft.ChatFormatting.GRAY));
        addRenderableWidget(peopleSearchBox);
        if (searchFocused) setFocused(peopleSearchBox);
        HsButton search = Ui2Button.secondary(leftPos + list.right() - findW - 3, topPos + list.y() + 1,
            findW, 11, findLabel, () -> {
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
            int rowY = topPos + list.y() + PEOPLE_ROWS_TOP + row * PEOPLE_ROW_H;
            // The row text is painted by renderPeoplePage exactly once; this button is state/input/narration.
            addRenderableWidget(new Ui2RowButton(leftPos + list.x() + 1, rowY,
                list.width() - 6, PEOPLE_ROW_H, person.listName(),
                person.id().equals(selectedResidentId), () -> {
                    selectedResidentId = person.id();
                    rebuildSeatWidgets();
                }));
        }
        PeopleRenderRow selected = selectedPeopleRow();
        int viewW = sheet.stacked() ? Math.min(90, detail.width() / 2) : detail.width();
        HsButton view = Ui2Button.banner(leftPos + detail.right() - viewW, topPos + detail.bottom() - 18,
            viewW, 18, Component.literal("View Settler"), this::viewSelectedSettler);
        boolean canInspect = canInspectResident(selected);
        peopleViewInspectable = canInspect;
        view.active = canInspect;
        view.setTooltip(Tooltip.create(Component.literal(selected == null
            ? "No settler selected"
            : !selected.loaded()
            ? "This resident is unloaded; their live sheet cannot be opened"
            : selected != null && !canInspect
                ? "Move within 8 blocks of this settler to inspect them"
                : "Open the loaded settler's server-authored inspection sheet")));
        addRenderableWidget(view);
        if (jobFilter >= 0) {
            Component chip = Component.literal("Only " + Profession.byId(jobFilter).displayName().getString()
                + "  \u00d7");
            int cw = Ui2Button.textWidth(font, chip);
            HsButton clearFilter = Ui2Button.secondary(leftPos + list.right() - cw - 4,
                topPos + list.y() + PEOPLE_ROWS_TOP - 12, cw, 11, chip, () -> toggleJobFilter(jobFilter));
            clearFilter.setTooltip(Tooltip.create(Component.literal("Show every resident again")));
            addRenderableWidget(clearFilter);
            peopleRangeLine = fittedEmpty();
        }
        if (selected != null && RealmMapClient.track(selected.id()) != null && !sheet.stacked()) {
            Component mapLabel = Component.literal("Show on map");
            int mw = Ui2Button.textWidth(font, mapLabel);
            HsButton onMap = Ui2Button.secondary(leftPos + detail.x(), topPos + detail.bottom() - 70, mw, 12,
                mapLabel, () -> showSettlerOnMap(selected.id()));
            addRenderableWidget(onMap);
        }
        if (selected != null && !sheet.stacked()) {
            Component summonLabel = summonLabel(selected.id());
            int sw = Ui2Button.textWidth(font, summonLabel);
            addRenderableWidget(summonButton(leftPos + detail.x(), topPos + detail.bottom() - 86, sw, 12,
                selected.id()));
        }
        if (sheet.stacked()) {
            addStackedOfficeButtons(detail, viewW);
            return;
        }
        Component mayorLabel = Component.literal("Change");
        int mayorW = Ui2Button.textWidth(font, mayorLabel);
        HsButton mayor = Ui2Button.secondary(leftPos + detail.right() - mayorW,
            topPos + detail.bottom() - 47, mayorW, 12, mayorLabel, () -> {
                peopleTabOpen = false; mayorTabOpen = true; requestMayorData(); rebuildSeatWidgets();
            });
        mayor.setTooltip(Tooltip.create(Component.literal("Choose who leads the settlement as Mayor")));
        addRenderableWidget(mayor);
        if (cachedRecruitmentCard.present()) {
            Component travelerLabel = Component.literal("Review");
            int travelerW = Ui2Button.textWidth(font, travelerLabel);
            HsButton traveler = Ui2Button.secondary(leftPos + detail.right() - travelerW,
                topPos + detail.bottom() - 34, travelerW, 12, travelerLabel, () -> {
                    peopleTabOpen = false; recruitmentPanelOpen = true; rebuildSeatWidgets();
                });
            traveler.setTooltip(Tooltip.create(Component.literal(cachedRecruitmentCard.name() + " waits at the tavern")));
            addRenderableWidget(traveler);
        }
    }

    /** Stacked layout: "Mayor" and "Traveler" share the bottom strip left of View Settler. */
    private void addStackedOfficeButtons(BannerSheetLayout.Rect detail, int viewW) {
        int x = detail.x();
        int limit = detail.right() - viewW - 6;
        int y = detail.bottom() - 15;
        Component mayorLabel = Component.literal("Mayor");
        int mayorW = Ui2Button.textWidth(font, mayorLabel);
        if (x + mayorW <= limit) {
            HsButton mayor = Ui2Button.secondary(leftPos + x, topPos + y, mayorW, 12, mayorLabel, () -> {
                peopleTabOpen = false; mayorTabOpen = true; requestMayorData(); rebuildSeatWidgets();
            });
            mayor.setTooltip(Tooltip.create(Component.literal("Choose who leads the settlement as Mayor")));
            addRenderableWidget(mayor);
            x += mayorW + 4;
        }
        if (cachedRecruitmentCard.present()) {
            Component travelerLabel = Component.literal("Review");
            int travelerW = Ui2Button.textWidth(font, travelerLabel);
            if (x + travelerW <= limit) {
                HsButton traveler = Ui2Button.secondary(leftPos + x, topPos + y, travelerW, 12, travelerLabel, () -> {
                    peopleTabOpen = false; recruitmentPanelOpen = true; rebuildSeatWidgets();
                });
                traveler.setTooltip(Tooltip.create(Component.literal(cachedRecruitmentCard.name()
                    + " waits at the tavern -- review them")));
                addRenderableWidget(traveler);
            }
        }
    }

    private static final int PEOPLE_ROWS_TOP = 30;
    private static final int PEOPLE_ROW_H = 20;
    private static final int TASK_ROWS_TOP = 18;
    private static final int TASK_ROW_H = 16;
    private static final int TASK_DETAIL_TOP = 18;
    private static final int TASK_DETAIL_STEP = 10;

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
                if (jobFilter >= 0 && !Profession.byId(jobFilter).name().equals(resident.professionId())) {
                    continue;
                }
                if (!query.isEmpty() && !resident.name().toLowerCase(Locale.ROOT).contains(query)
                    && !resident.professionId().toLowerCase(Locale.ROOT).contains(query)
                    && !status.contains(query)) {
                    continue;
                }
                rows.add(new PeopleRenderRow(resident.id(), resident.runtimeEntityId(),
                    fitPageText(Component.literal(resident.name()), listNameWidth),
                    fitPageText(residentJob(resident.professionId()), listNameWidth),
                    fitPageText(Component.literal(status), 48),
                    fitPageText(Component.literal(resident.name()), detailWidth),
                    fitPageText(residentJob(resident.professionId()), detailWidth),
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
        peopleMoveCloserLine = fitPageText(Component.literal("Walk closer to open"), detailWidth);
        peopleEmptyLine = cachedPeopleRows.isEmpty()
            ? fitPageText(Component.literal("No residents match"), detailWidth)
            : Component.empty();
        int officeW = Math.max(1, detailWidth - font.width("Change") - 10);
        peopleMayorLine = mayorSnapshot != null && mayorSnapshot.hasMayor()
            ? fitPageText(Component.literal(mayorSnapshot.mayorName() + ", mayor"), officeW)
            : fitPageText(Component.literal("No mayor"), officeW);
        peopleTravelerLine = cachedRecruitmentCard.present()
            ? fitPageText(Component.literal(cachedRecruitmentCard.name() + " waits"), officeW)
            : fitPageText(Component.literal("No traveler"), officeW);
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

    /** The resident's trade as a display name (the snapshot carries the enum name). */
    private static Component residentJob(String professionId) {
        for (Profession p : Profession.BY_ID) {
            if (p.name().equals(professionId) || p.key().equals(professionId)) {
                return p == Profession.NONE ? Component.literal("Unassigned") : p.displayName();
            }
        }
        return Component.literal(professionId);
    }

    private int peopleVisibleRows() {
        // Search (14px) and count caption sit above PEOPLE_ROWS_TOP; rows fill the list pane.
        return Math.max(1, Math.min(12, (sheet.centre().height() - PEOPLE_ROWS_TOP - 2) / PEOPLE_ROW_H));
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
        HsButton admit = Ui2Button.banner(
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
        HsButton dismiss = Ui2Button.danger(
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
            : cachedRecruitmentCard.mayDismiss()
                ? Component.translatable("hearthstead.recruit.card.dismiss.tip", cachedRecruitmentCard.name())
                : recruitmentStageLine == null || recruitmentStageLine.getString().isBlank()
                    ? Component.literal("Not possible right now")
                    : Component.literal("Not possible right now: ").append(recruitmentStageLine)));
        addPanelWidget(admit);
        addPanelWidget(dismiss);
    }

    private void rebuildRequestWidgets() {
        BannerSheetLayout.Rect list = sheet.centre();
        BannerSheetLayout.Rect detail = sheet.right();
        ensureTaskPageRenderCache(Math.max(1, list.width() - 18), Math.max(1, detail.width() - 6));
        int rows = taskVisibleRows();
        requestScroll = Mth.clamp(requestScroll, 0,
            Math.max(0, cachedTaskRows.size() - rows));
        selectedRequestIndex = Mth.clamp(selectedRequestIndex, 0,
            Math.max(0, cachedTaskRows.size() - 1));
        taskDetailScroll = Mth.clamp(taskDetailScroll, 0, taskDetailMaxScroll());
        Component refreshLabel = Component.literal("Refresh");
        int refreshW = Ui2Button.textWidth(font, refreshLabel);
        HsButton refresh = Ui2Button.secondary(leftPos + list.right() - refreshW - 3, topPos + list.y() + 1,
            refreshW, 11, refreshLabel, this::refreshRequestLedger);
        refresh.active = !requestLoading;
        addRenderableWidget(refresh);
        for (int row = 0; row < rows && row + requestScroll < cachedTaskRows.size(); row++) {
            int index = row + requestScroll;
            TaskPageRow request = cachedTaskRows.get(index);
            addRenderableWidget(new Ui2RowButton(leftPos + list.x() + 1,
                topPos + list.y() + TASK_ROWS_TOP + row * TASK_ROW_H, list.width() - 6, TASK_ROW_H,
                request.list(), index == selectedRequestIndex, () -> {
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
        taskMetaLine = fitPageText(requestMetaLine, Math.max(1, listWidth - 52));
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
        return Math.max(1, Math.min(14, (sheet.centre().height() - TASK_ROWS_TOP - 2) / TASK_ROW_H));
    }

    private int taskDetailVisibleLines() {
        // Detail lines start below the section header and step 10px to the pane bottom.
        return Math.max(1, (sheet.right().height() - TASK_DETAIL_TOP) / TASK_DETAIL_STEP);
    }

    private int taskDetailMaxScroll() {
        if (cachedTaskRows.isEmpty()) return 0;
        TaskPageRow selected = cachedTaskRows.get(Mth.clamp(selectedRequestIndex, 0,
            cachedTaskRows.size() - 1));
        return Math.max(0, selected.detailLines().size() - taskDetailVisibleLines());
    }

    private boolean isOverTaskDetail(double mouseX, double mouseY) {
        BannerSheetLayout.Rect detail = sheet.right();
        return detail.contains(mouseX - leftPos, mouseY - topPos);
    }

    private void rebuildJourneyWidgets() {
        updateJourneyPanelPosition();
        suppressCoveredTabs(journeyPanelLeft, journeyPanelTop,
            journeyPanelWidth, journeyPanelHeight);
        addPanelClose(journeyPanelLeft, journeyPanelTop, journeyPanelWidth);
        addJourneyHandbookLink();
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
            HsButton check = Ui2Button.banner(
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
            HsButton skip = Ui2Button.danger(
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
        HsButton cancel = Ui2Button.secondary(journeyPanelLeft + JOURNEY_PAD,
            journeyPanelTop + journeyButtonY, half, HsUiTokens.BUTTON_H,
            Component.translatable("gui.cancel"), () -> {
                QaClientObserver.markUiTransition("hearth_journey_confirm_cancel");
                journeySkipConfirm = false;
                rebuildSeatWidgets();
            });
        HsButton confirm = Ui2Button.danger(
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
        HsButton refresh = Ui2Button.secondary(journeyPanelLeft + JOURNEY_PAD,
            journeyPanelTop + journeyButtonY, half, HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.raid.readiness.refresh"),
            this::openRaidReadiness);
        HsButton declare = Ui2Button.banner(
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

    /**
     * HANDBOOK lane: a "?" beside Close opens the handbook at the page that
     * explains the current Journey step (Start here for the first steps).
     */
    private void addJourneyHandbookLink() {
        int current = menu.get(HearthMenu.DATA_JOURNEY_V3_CURRENT);
        if (current < 0 || current >= JourneyDefinition.CURRENT.orderedSteps().size()) {
            return;
        }
        String step = JourneyDefinition.CURRENT.stepAt(current).id().getPath();
        HsButton help = new Ui2WoodKey(
            journeyPanelLeft + journeyPanelWidth - MODAL_CLOSE_INSET - 11 - 4 - 11,
            journeyPanelTop + MODAL_CLOSE_Y, 11, 11, Component.literal("?"), () -> {
                QaClientObserver.markUiTransition("hearth_journey_handbook");
                if (minecraft != null && minecraft.player != null) {
                    minecraft.player.closeContainer();
                }
                HandbookScreen.openForJourney(step);
            });
        help.setTooltip(Tooltip.create(Component.translatable("hearthstead.guide.ui.learn_more")));
        addPanelWidget(help);
    }

    /** Popouts close with the same wood key as every window, top right on the header. */
    private void addPanelClose(int panelLeft, int panelTop, int panelWidth) {
        HsButton close = new Ui2WoodKey(panelLeft + panelWidth - MODAL_CLOSE_INSET - 11,
            panelTop + MODAL_CLOSE_Y, 11, 11, Component.literal("\u00d7"), this::closePopout);
        close.setTooltip(Tooltip.create(Component.literal("Back to the Banner (Esc)")));
        addPanelWidget(close);
    }

    /** Popout close key: frame + margin from the right edge, centred on the 20px wood header. */
    private static final int MODAL_CLOSE_INSET = 12;
    private static final int MODAL_CLOSE_Y = 11;

    private void addPanelWidget(AbstractButton widget) {
        latePanelWidgets.add(widget);
        // addWidget keeps keyboard, narration and focus semantics without
        // placing the control in super.render's early renderable pass. The
        // late modal pass below is therefore the one and only visual draw.
        addWidget(widget);
    }

    private void addSeatTab(AbstractButton tab) {
        seatTabs.add(tab);
        addRenderableWidget(tab);
    }

    private void suppressCoveredTabs(int panelLeft, int panelTop,
                                     int panelWidth, int panelHeight) {
        int panelRight = panelLeft + panelWidth;
        int panelBottom = panelTop + panelHeight;
        for (AbstractButton tab : seatTabs) {
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
        buildingsPageOpen = false;
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

    /** Refreshes the same exact Hearth-owned view without blanking useful rows. */
    /**
     * The Tasks page is gone; open requests and deliveries now sit under the
     * stores on the Storage page. Opening Storage asks the server for the
     * same bounded request ledger view the Tasks page used (which is also
     * what completes the Journey step "Check the Open Requests").
     */
    private void requestLedgerForStorage() {
        QaClientObserver.markUiTransition("hearth_request_ledger_refresh");
        PacketDistributor.sendToServer(storageLedgerRequest(menu.getHearthPos(), menu.getSettlementId(),
            menu.getContainerId()));
    }

    /** Storage asks for the ledger when it is opened (not on every rebuild while it stays open). */
    static boolean storageOpenRequestsLedger(boolean open, boolean alreadyOpen) {
        return open && !alreadyOpen;
    }

    /** The exact action Storage sends: the tuple HearthNetwork.openRequestLedger accepts (no target, revision 0). */
    static HearthMayorAction storageLedgerRequest(net.minecraft.core.BlockPos hearthPos, UUID settlementId,
                                                  int containerId) {
        return new HearthMayorAction(hearthPos, settlementId, containerId,
            HearthMayorAction.Kind.OPEN_REQUEST_LEDGER, HearthMayorAction.NO_ID, 0);
    }

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
            case DEPARTING -> Component.translatable(
                "hearthstead.recruit.card.stage.departing");
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
            // Attributes lane (plan/ATTRIBUTES.md): name the jobs that lean on
            // these two strengths, so the card says why this newcomer fits.
            List<com.hearthstead.entity.Profession> suited = com.hearthstead.entity.AttributeFit.suitedJobs(
                com.hearthstead.entity.Attribute.ALL[safe.firstAttribute()],
                com.hearthstead.entity.Attribute.ALL[safe.secondAttribute()], 2);
            if (!suited.isEmpty()) {
                List<String> jobs = new ArrayList<>(suited.size());
                for (com.hearthstead.entity.Profession p : suited) jobs.add(p.displayName().getString());
                recruitmentAptitudeLine = recruitmentAptitudeLine.copy().append(Component.translatable(
                    "hearthstead.recruit.card.suits", String.join(", ", jobs)));
            }
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
        maintainMap();
        maintainSummons();
        // Overview's primary action is the top real need; rebuild it only when
        // the ranked needs actually change, never under a held stack.
        if (overviewOpen() && !hasOpenPopout()
                && menu.getCarried().isEmpty()
                && !needsSignatureOf(computeNeeds()).equals(needsSignature)) {
            rebuildSeatWidgets();
        }
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
        if (!hasOpenPopout() && overviewOpen() && mapView.mouseScrolled(mouseX, mouseY, dy)) {
            return true;
        }
        if (!hasOpenPopout() && buildingsPageOpen
            && sheet.centre().contains(mouseX - leftPos, mouseY - topPos)) {
            RealmMapLayoutPayload layout = mapLayout();
            int total = layout == null ? 0 : layout.buildings().size();
            int before = buildingsScroll;
            buildingsScroll = Mth.clamp(buildingsScroll - (int) Math.signum(dy), 0,
                Math.max(0, total - buildingRowsVisible()));
            if (before != buildingsScroll) rebuildSeatWidgets();
            return true;
        }
        if (journeyTabOpen && !cachedReadinessView.open() && !shouldShowAftermath()
                && mouseX >= journeyPanelLeft && mouseX < journeyPanelLeft + journeyPanelWidth) {
            int localY = (int) mouseY - journeyPanelTop;
            for (int row = 0; row < Math.min(2, journeyRenderModel.steps().size()); row++) {
                int top = journeyRowTop(row);
                if (localY >= top && localY < top + journeyRowHeight(row)
                        && scrollJourneyDescription(row, -(int) Math.signum(dy))) return true;
            }
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
        boolean overBody = sheet.body().contains(mouseX - leftPos, mouseY - topPos);
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
        // While typing a resident search, letters belong to the box: the
        // container screen must not treat E as "close inventory" or 1-9/Q as
        // slot actions. Enter runs the search; Escape still closes.
        if (peopleTabOpen && peopleSearchBox != null && peopleSearchBox.isFocused()
            && keyCode != GLFW.GLFW_KEY_ESCAPE) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                appliedPeopleSearch = peopleSearchBox.getValue().trim();
                peopleScroll = 0;
                peopleRenderSnapshot = null;
                rebuildSeatWidgets();
                return true;
            }
            return peopleSearchBox.keyPressed(keyCode, scanCode, modifiers)
                || peopleSearchBox.canConsumeInput();
        }
        // A popout (Mayor, Journey, Traveler) closes back to the Banner on Esc or the inventory key,
        // instead of closing the whole screen.
        if (hasOpenPopout() && (keyCode == GLFW.GLFW_KEY_ESCAPE
            || minecraft != null && minecraft.options.keyInventory.matches(keyCode, scanCode))) {
            closePopout();
            return true;
        }
        // Map keys: arrows pan, +/- zoom, C centres on the Banner, F follows,
        // Esc first clears a selection before it closes the screen.
        if (!hasOpenPopout() && overviewOpen() && mapView.keyPressed(keyCode)) {
            return true;
        }
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
            if (overviewOpen() && mapView.isMouseOver(mouseX, mouseY)
                && mapView.mouseClicked(mouseX, mouseY, button)) {
                setFocused(null);
                return true;
            }
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
        if (mapView.dragging()) return mapView.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        if (hasOpenPopout() || modalClickAwaitingRelease) return true;
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (mapView.dragging()) return mapView.mouseReleased(mouseX, mouseY, button);
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
        for (AbstractButton tab : seatTabs) {
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
            + ",aftermathVisible=" + shouldShowAftermath()
            + ",ui=ui2,needs=" + needs.size()
            + ",page=" + (suppliesOpen ? "supplies" : peopleTabOpen ? "people"
                : requestPanelOpen ? "tasks" : buildingsPageOpen ? "buildings" : "home")
            + ",layout=" + (sheet.compactNav() ? "icons" : "labels") + (sheet.stacked() ? "-stacked" : "")
            + ",map=" + (RealmMapClient.hasData(menu.getSettlementId()) ? "live" : "waiting")
            + ",mapMarkers=" + RealmMapClient.tracks().length
            + ",mapSelected=" + (mapView.selectedSettler() != null ? "settler"
                : mapView.selectedBuilding() != null ? "building" : "none")
            + ",mapFollow=" + mapView.following()
            + ",mapZoom=" + mapView.zoomLevel()
            + ",jobFilter=" + jobFilter;
    }

    // ------------------------------------------------------------- drawing ---

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        // Walnut board with iron corners; parchment only where content lives.
        BannerChrome.panel(graphics, x, y, imageWidth, imageHeight);
        BannerSheetLayout.Rect header = sheet.header();
        int ruleY = y + sheet.headerRuleY();
        graphics.fill(x + header.x(), ruleY, x + header.right(), ruleY + 1, BannerChrome.PLATE_SHADOW);
        graphics.fill(x + header.x(), ruleY + 1, x + header.right(), ruleY + 2, BannerChrome.PLATE_HIGHLIGHT);
        for (int i = 0; i < BannerSheetLayout.COUNTERS; i++) {
            BannerSheetLayout.Rect c = sheet.counter(i);
            BannerChrome.counterBox(graphics, x + c.x(), y + c.y(), c.width(), c.height());
        }
        boolean popout = hasOpenPopout();
        if (suppliesOpen) {
            BannerSheetLayout.Rect wide = sheet.widePanel();
            BannerChrome.parchment(graphics, x + wide.x(), y + wide.y(), wide.width(), wide.height());
        } else {
            BannerSheetLayout.Rect cp = sheet.centrePanel();
            BannerSheetLayout.Rect rp = sheet.rightPanel();
            BannerChrome.parchment(graphics, x + cp.x(), y + cp.y(), cp.width(), cp.height());
            BannerChrome.parchment(graphics, x + rp.x(), y + rp.y(), rp.width(), rp.height());
        }
        BannerSheetLayout.Rect centre = sheet.centre();
        if (overviewOpen()) {
            // The live realm map is the Overview's centrepiece.
            mapView.setTitle(menu.getSettlementName());
            mapView.render(graphics, font, x + centre.x(), y + centre.y(), centre.width(), centre.height(),
                popout ? -10000 : mouseX, popout ? -10000 : mouseY, menu.getSettlementId(), partialTick);
        } else if (!suppliesOpen) {
            graphics.fill(x + centre.x(), y + centre.y(), x + centre.right(),
                y + centre.y() + TABLE_TOP - 2, Ui2Palette.INSET);
            Ui2Surface.rule(graphics, x + centre.x(), y + centre.y() + TABLE_TOP - 2, centre.width());
        }
        if (suppliesOpen) for (var slot : menu.slots) {
            if (slot.x < 0) continue;
            Ui2Surface.slotWell(graphics, x + slot.x - 1, y + slot.y - 1);
        }
        BannerSheetLayout.Rect crest = sheet.crest();
        BannerChrome.crest(graphics, x + crest.x(), y + crest.y(), crest.width(), crest.height());
        BannerSheetLayout.Rect banner = sheet.banner();
        BannerChrome.hangingBanner(graphics, x + banner.x(), y + banner.y(), banner.width(), banner.height());
        // Popouts are NOT drawn here. renderBg runs first in the frame, and
        // AbstractContainerScreen draws slot items and then renderLabels AFTER
        // it, so a panel painted here would get the page labels painted across
        // it. Popouts draw at the END of render(), above everything they cover.
    }

    private void drawResidentPortrait(GuiGraphics graphics, PeopleRenderRow person, int x, int y, int size) {
        RealmMapLayoutPayload.RosterEntry entry = RealmMapClient.roster(person.id());
        graphics.fill(x - 1, y - 1, x + size + 1, y + size + 1, Ui2Palette.RULE_STRONG);
        RealmIcons.face(graphics, person.id(), person.runtimeEntityId(),
            entry == null ? -1 : entry.appearanceSeed(), entry == null ? 0 : entry.professionId(), x, y, size);
    }

    /** Section heading in serif small caps, then a hairline to {@code x + w}. */
    private void heading(GuiGraphics g, String text, int x, int y, int w) {
        Ui2Serif.Text t = headings.computeIfAbsent(text, k -> new Ui2Serif.Text(Ui2Serif.Size.HEADING));
        t.set(font, text);
        t.draw(g, font, x, y + 2, Ui2Palette.INK_SOFT);
        int rx = x + t.width() + 5;
        if (rx < x + w) Ui2Surface.rule(g, rx, y + 6, x + w - rx);
    }

    /** Small table caption row inside the inset centre panel. */
    private void tableCaption(GuiGraphics g, String text, int x, int y) {
        g.drawString(font, text, x, y, Ui2Palette.INK_MUTED, false);
    }

    private int peopleRoleX() {
        return sheet.centre().x() + sheet.centre().width() * 42 / 100;
    }

    private int peopleStatusX() {
        return sheet.centre().x() + sheet.centre().width() * 75 / 100;
    }

    private void renderPeoplePage(GuiGraphics graphics) {
        BannerSheetLayout.Rect list = sheet.centre();
        BannerSheetLayout.Rect detail = sheet.right();
        ensurePeopleRenderCache(Math.max(1, peopleRoleX() - list.x() - 26), Math.max(1, detail.width() - 4));
        // Search strip sits on the inset caption band; the table header below it.
        int fieldW = list.width() - font.width("Find") - 14;
        graphics.fill(list.x() + 2, list.y() + 2, list.x() + fieldW, list.y() + 12, Ui2Palette.PAPER_DEEP);
        graphics.fill(list.x() + 2, list.y() + 11, list.x() + fieldW, list.y() + 12, Ui2Palette.RULE_STRONG);
        int headY = list.y() + PEOPLE_ROWS_TOP - 11;
        tableCaption(graphics, "NAME", list.x() + 24, headY);
        tableCaption(graphics, "ROLE", peopleRoleX(), headY);
        tableCaption(graphics, "STATUS", peopleStatusX(), headY);
        Ui2Surface.rule(graphics, list.x() + 2, list.y() + PEOPLE_ROWS_TOP - 1, list.width() - 4);
        int visible = peopleVisibleRows();
        double gs = minecraft == null ? 2 : minecraft.getWindow().getGuiScale();
        int texel = RealmIcons.itemScaleFor(11, gs);
        for (int row = 0; row < visible && peopleScroll + row < cachedPeopleRows.size(); row++) {
            PeopleRenderRow person = cachedPeopleRows.get(peopleScroll + row);
            int rowY = list.y() + PEOPLE_ROWS_TOP + row * PEOPLE_ROW_H;
            drawResidentPortrait(graphics, person, list.x() + 5, rowY + 2, 16);
            graphics.drawString(font, person.listName(), list.x() + 25, rowY + 6, Ui2Palette.INK, false);
            RealmMapLayoutPayload.RosterEntry entry = RealmMapClient.roster(person.id());
            if (entry != null) {
                RealmIcons.itemCentered(graphics, RealmIcons.profession(entry.professionId()), peopleRoleX() + 5,
                    rowY + 10, texel, gs, 0);
            }
            graphics.drawString(font, person.listProfession(), peopleRoleX() + 12, rowY + 6,
                Ui2Palette.INK_SOFT, false);
            MarkerTrack track = RealmMapClient.track(person.id());
            int sx = peopleStatusX();
            if (track != null) {
                RealmMapStatus status = RealmMapStatus.byWireId(track.statusId);
                statusGlyph(graphics, status, sx, rowY + 7);
                graphics.drawString(font, statusWord(status), sx + 8, rowY + 6, statusColor(status), false);
            } else {
                graphics.drawString(font, person.loaded() ? "…" : "Away", sx + 8, rowY + 6,
                    Ui2Palette.INK_DISABLED, false);
            }
        }
        if (cachedPeopleRows.size() > visible) {
            int total = cachedPeopleRows.size();
            Ui2Surface.scrollbar(graphics, list.right() - 3, list.y() + PEOPLE_ROWS_TOP,
                visible * PEOPLE_ROW_H, Math.min(1.0F, (float) visible / total),
                (float) peopleScroll / Math.max(1, total - visible));
        }
        if (cachedPeopleRows.isEmpty()) {
            graphics.drawString(font, peopleEmptyLine, list.x() + 8, list.y() + PEOPLE_ROWS_TOP + 6,
                Ui2Palette.INK_MUTED, false);
        }
        PeopleRenderRow selected = selectedPeopleRow();
        int x = detail.x();
        int y = detail.y();
        if (selected == null) {
            graphics.drawString(font, cachedPeopleRows.isEmpty() ? peopleEmptyLine : peopleSelectLine,
                x, y + 4, Ui2Palette.INK_MUTED, false);
        } else {
            drawResidentPortrait(graphics, selected, x + 1, y + 1, 20);
            graphics.drawString(font, selected.detailName(), x + 26, y + 2, Ui2Palette.INK, false);
            graphics.drawString(font, selected.detailProfession(), x + 26, y + 12, Ui2Palette.GOLD, false);
            if (!sheet.stacked()) {
                Ui2Surface.rule(graphics, x, y + 26, detail.width());
                MarkerTrack track = RealmMapClient.track(selected.id());
                if (track != null) {
                    RealmMapStatus status = RealmMapStatus.byWireId(track.statusId);
                    statusGlyph(graphics, status, x + 1, y + 33);
                    graphics.drawString(font, statusWord(status), x + 9, y + 32, statusColor(status), false);
                } else {
                    graphics.drawString(font, selected.detailStatus(), x, y + 32,
                        selected.loaded() ? Ui2Palette.INK : Ui2Palette.INK_MUTED, false);
                }
                boolean inspectable = canInspectResident(selected);
                if (!inspectable) Ui2Surface.lockGlyph(graphics, x, y + 57, Ui2Palette.INK_MUTED);
                graphics.drawString(font, inspectable ? selected.detailHint()
                        : selected.loaded() ? peopleMoveCloserLine : selected.detailHint(),
                    inspectable ? x : x + 8, y + 57, Ui2Palette.INK_MUTED, false);
                // Settlement offices sit above the one primary action, as a quiet ledger.
                int officesY = detail.bottom() - 54;
                Ui2Surface.rule(graphics, x, officesY, detail.width());
                graphics.drawString(font, peopleMayorLine, x, detail.bottom() - 45, Ui2Palette.INK_SOFT, false);
                graphics.drawString(font, peopleTravelerLine, x, detail.bottom() - 32, Ui2Palette.INK_SOFT, false);
            }
        }
    }

    private void renderTasksPage(GuiGraphics graphics) {
        BannerSheetLayout.Rect list = sheet.centre();
        BannerSheetLayout.Rect detail = sheet.right();
        ensureTaskPageRenderCache(Math.max(1, list.width() - 18), Math.max(1, detail.width() - 6));
        graphics.drawString(font, taskMetaLine, list.x() + 4, list.y() + 2, Ui2Palette.INK_MUTED, false);
        int visible = taskVisibleRows();
        for (int row = 0; row < visible && row + requestScroll < cachedTaskRows.size(); row++) {
            int index = row + requestScroll;
            TaskPageRow request = cachedTaskRows.get(index);
            int rowY = list.y() + TASK_ROWS_TOP + row * TASK_ROW_H;
            int tone = request.tone();
            if (tone == HsUiTokens.BAD) {
                Ui2Surface.alertGlyph(graphics, list.x() + 5, rowY + 5, Ui2Palette.DANGER);
            } else if (tone == HsUiTokens.GOOD) {
                Ui2Surface.checkGlyph(graphics, list.x() + 4, rowY + 5, Ui2Palette.FOREST);
            } else {
                Ui2Surface.pendingGlyph(graphics, list.x() + 5, rowY + 5, Ui2Palette.GOLD);
            }
            graphics.drawString(font, request.list(), list.x() + 14, rowY + 4, Ui2Palette.INK, false);
        }
        if (cachedTaskRows.size() > visible) {
            Ui2Surface.scrollbar(graphics, list.right() - 3, list.y() + TASK_ROWS_TOP,
                visible * TASK_ROW_H, (float) visible / cachedTaskRows.size(),
                (float) requestScroll / Math.max(1, cachedTaskRows.size() - visible));
        }
        heading(graphics, "Request details", detail.x(), detail.y(), detail.width());
        if (cachedTaskRows.isEmpty()) {
            graphics.drawString(font, taskEmptyLine, detail.x(), detail.y() + TASK_DETAIL_TOP,
                Ui2Palette.INK_MUTED, false);
            return;
        }
        TaskPageRow selected = cachedTaskRows.get(Mth.clamp(selectedRequestIndex, 0,
            cachedTaskRows.size() - 1));
        int visibleDetailLines = taskDetailVisibleLines();
        int maxDetailScroll = Math.max(0, selected.detailLines().size() - visibleDetailLines);
        taskDetailScroll = Mth.clamp(taskDetailScroll, 0, maxDetailScroll);
        int detailY = detail.y() + TASK_DETAIL_TOP;
        for (int index = taskDetailScroll; index < selected.detailLines().size()
                && index < taskDetailScroll + visibleDetailLines; index++) {
            if (detailY + HsUiTokens.TEXT_H > detail.bottom()) break;
            TaskPageLine line = selected.detailLines().get(index);
            graphics.drawString(font, line.text(), detail.x(), detailY, line.tone(), false);
            detailY += TASK_DETAIL_STEP;
        }
        if (maxDetailScroll > 0) {
            Ui2Surface.scrollbar(graphics, detail.right() - 1, detail.y() + TASK_DETAIL_TOP,
                Math.max(8, detail.bottom() - (detail.y() + TASK_DETAIL_TOP)),
                (float) visibleDetailLines / selected.detailLines().size(),
                (float) taskDetailScroll / maxDetailScroll);
        }
    }

    /** Honest one-word state for a building from its record and its workers' live markers. */
    private record BuildingState(String word, int color, int chip) { }

    private static final BuildingState STATE_INVALID = new BuildingState("Invalid", Ui2Palette.DANGER, 0x30A0463C);
    private static final BuildingState STATE_WORKING = new BuildingState("Working", Ui2Palette.FOREST, 0x3A5F8163);
    private static final BuildingState STATE_IDLE = new BuildingState("Idle", Ui2Palette.INK_SOFT, 0x30846F52);
    private static final BuildingState STATE_UNSTAFFED = new BuildingState("No staff", Ui2Palette.AMBER, 0x30A87B2E);
    private static final BuildingState STATE_HOME = new BuildingState("Home", Ui2Palette.INK_SOFT, 0x20846F52);

    /** Staff is only meaningful where a trade is taught; dwellings read as homes. */
    private static boolean staffed(RealmMapLayoutPayload.BuildingEntry b) {
        com.hearthstead.building.BuildingType type = com.hearthstead.building.BuildingType.byId(b.typeId());
        return b.workerCapacity() > 0 && (type == null
            || com.hearthstead.settlement.Employment.tradeOf(type) != Profession.NONE);
    }

    private static BuildingState buildingState(RealmMapLayoutPayload layout, int index) {
        RealmMapLayoutPayload.BuildingEntry b = layout.buildings().get(index);
        if (!b.valid()) return STATE_INVALID;
        if (!staffed(b)) return STATE_HOME;
        boolean anyWorker = false;
        for (RealmMapLayoutPayload.RosterEntry r : layout.roster()) {
            if (r.workBuilding() != index) continue;
            anyWorker = true;
            MarkerTrack t = RealmMapClient.track(r.id());
            if (t != null && RealmMapStatus.byWireId(t.statusId) == RealmMapStatus.WORKING) return STATE_WORKING;
        }
        return anyWorker ? STATE_IDLE : STATE_UNSTAFFED;
    }

    private static void chip(GuiGraphics g, Font font, BuildingState state, int x, int y, int w) {
        g.fill(x, y, x + w, y + 11, state.chip());
        BannerChrome.outline(g, x, y, w, 11, (state.color() & 0x00FFFFFF) | 0x80000000);
        g.drawString(font, state.word(), x + (w - font.width(state.word())) / 2, y + 2, state.color(), false);
    }

    /** A tiny standing-person glyph for staff counts. */
    private static void personGlyph(GuiGraphics g, int x, int y, int color) {
        g.fill(x + 1, y, x + 3, y + 2, color);
        g.fill(x, y + 3, x + 4, y + 6, color);
        g.fill(x + 1, y + 6, x + 3, y + 8, color);
    }

    private void renderBuildingsPage(GuiGraphics graphics) {
        BannerSheetLayout.Rect c = sheet.centre();
        RealmMapLayoutPayload layout = mapLayout();
        int chipW = 44;
        int chipX = c.right() - 6 - chipW;
        int staffX = chipX - 34;
        int headY = c.y() + 2;
        tableCaption(graphics, "BUILDING", c.x() + 24, headY);
        tableCaption(graphics, "STAFF", staffX, headY);
        tableCaption(graphics, "STATE", chipX + 4, headY);
        if (layout == null) {
            graphics.drawString(font, "Surveying…", c.x() + 8, c.y() + TABLE_TOP + 6, Ui2Palette.INK_MUTED, false);
        } else {
            List<RealmMapLayoutPayload.BuildingEntry> buildings = layout.buildings();
            if (buildings.isEmpty()) {
                graphics.drawString(font, "No buildings yet.", c.x() + 8, c.y() + TABLE_TOP + 6,
                    Ui2Palette.INK_MUTED, false);
                graphics.drawString(font, "Hang a plaque in a room to declare one.", c.x() + 8,
                    c.y() + TABLE_TOP + 18, Ui2Palette.INK_MUTED, false);
            }
            int visible = buildingRowsVisible();
            for (int row = 0; row < visible && buildingsScroll + row < buildings.size(); row++) {
                int index = buildingsScroll + row;
                RealmMapLayoutPayload.BuildingEntry b = buildings.get(index);
                int rowY = c.y() + TABLE_TOP + row * BUILDING_ROW_H;
                boolean selected = b.id().equals(selectedBuildingId);
                if (selected) {
                    graphics.fill(c.x() + 1, rowY, c.right() - 5, rowY + BUILDING_ROW_H - 1, Ui2Palette.BURGUNDY_DARK);
                    graphics.fill(c.x() + 2, rowY + 1, c.right() - 6, rowY + BUILDING_ROW_H - 2, Ui2Palette.BURGUNDY);
                } else if (row > 0) {
                    Ui2Surface.rule(graphics, c.x() + 4, rowY, c.width() - 10);
                }
                int ink = selected ? Ui2Palette.ON_BURGUNDY : Ui2Palette.INK;
                int soft = selected ? 0xFFD9BFA8 : Ui2Palette.INK_MUTED;
                graphics.renderItem(RealmIcons.building(b.typeId()), c.x() + 4, rowY + 2);
                graphics.drawString(font, buildingRowName(b, staffX - c.x() - 28), c.x() + 24, rowY + 2, ink, false);
                graphics.drawString(font, levelLine(b.level()), c.x() + 24, rowY + 11, soft, false);
                int workers = RealmMapView.workersOf(layout, index);
                if (staffed(b)) {
                    personGlyph(graphics, staffX, rowY + 6, soft);
                    graphics.drawString(font, staffLine(workers, b.workerCapacity()), staffX + 7, rowY + 6, ink, false);
                }
                if (selected) graphics.fill(chipX, rowY + 4, chipX + chipW, rowY + 15, Ui2Palette.PAPER);
                chip(graphics, font, buildingState(layout, index), chipX, rowY + 4, chipW);
            }
            if (buildings.size() > visible) {
                Ui2Surface.scrollbar(graphics, c.right() - 2, c.y() + TABLE_TOP, visible * BUILDING_ROW_H,
                    (float) visible / buildings.size(),
                    (float) buildingsScroll / Math.max(1, buildings.size() - visible));
            }
        }
        if (selectedBuildingId != null) {
            drawBuildingCard(graphics, sheet.right(), selectedBuildingId);
        }
    }

    private final Map<UUID, Component> buildingRowNames = new HashMap<>();
    private int buildingRowNamesVersion = Integer.MIN_VALUE;

    private Component buildingRowName(RealmMapLayoutPayload.BuildingEntry b, int width) {
        if (buildingRowNamesVersion != RealmMapClient.layoutVersion()) {
            buildingRowNames.clear();
            buildingRowNamesVersion = RealmMapClient.layoutVersion();
        }
        return buildingRowNames.computeIfAbsent(b.id(), id -> HsUi.fitLabel(font,
            RealmMapView.buildingName(b), width).text());
    }

    private Component levelLine(int level) {
        return numberLines.computeIfAbsent(-3_000_000 - level, k -> Component.literal("Level " + level));
    }

    private Component staffLine(int filled, int capacity) {
        return numberLines.computeIfAbsent(-4_000_000 - filled * 100 - capacity,
            k -> Component.literal(filled + "/" + capacity));
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
        ModalPixels.window(graphics, pl, pt, pw, mayorPanelHeight, MAYOR_RULE_Y);
        mayorTitleText.fit(font, MAYOR_TITLE.getString(), mayorTitleBox(pw));
        mayorTitleText.draw(graphics, font, pl + MAYOR_HEADER_X, pt + MAYOR_TITLE_Y + 5,
            BannerChrome.TEXT_ON_WOOD);
        drawMayorStatus(graphics, pl, pt);

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
        int navWidth = Math.min(MAYOR_NAV_W, Math.max(48, pw / 5));
        int nextX = pw - MAYOR_PAD - navWidth;
        int pageBoxLeft = previousRight + MAYOR_NAV_GAP;
        int pageBoxWidth = Math.max(1, nextX - MAYOR_NAV_GAP - pageBoxLeft);
        graphics.drawString(font, mayorRenderModel.page().text(),
            pl + pageBoxLeft
                + (pageBoxWidth - mayorRenderModel.page().width()) / 2,
            pt + mayorButtonY + 6, ModalPixels.MUTED, false);
        // The header lines are fitted to the wood; hovering it shows every line in full.
        popoutHeaderTip(mayorHeaderTooltip, pl, pt, pw - MODAL_CLOSE_INSET - 11 - 4, MAYOR_RULE_Y);
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

    /**
     * The current step gets most of the card space (its full text is what the
     * player needs); the next step is a compact preview. Same total height
     * as the old two equal cards, so the panel geometry is unchanged.
     */
    private int journeyRowHeight(int row) {
        int total = journeyStepHeight * 2 + journeyStepGap;
        int next = Math.min(journeyStepHeight, 30);
        boolean single = journeyRenderModel.steps().size() < 2;
        if (single) return total;
        return row == 0 ? total - journeyStepGap - next : next;
    }

    private int journeyRowTop(int row) {
        return JOURNEY_STEPS_TOP + (row == 0 ? 0 : journeyRowHeight(0) + journeyStepGap);
    }

    private int journeyDescriptionRows(int row) {
        int textTop = 17;
        return Math.max(1, (journeyRowHeight(row) - textTop - 3) / 9);
    }

    private boolean scrollJourneyDescription(int row, int direction) {
        ensureJourneyRenderModel();
        if (row < 0 || row >= journeyRenderModel.steps().size()) return false;
        int before = journeyDescriptionScroll[row];
        journeyDescriptionScroll[row] = Mth.clamp(before + direction, 0,
            Math.max(0, journeyRenderModel.steps().get(row).description().size() - journeyDescriptionRows(row)));
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

        int spineX = pl + JOURNEY_PAD + 6;
        int cardX = pl + JOURNEY_PAD + 22;
        int cardW = journeyPanelWidth - 2 * JOURNEY_PAD - 22;

        int visible = journeyRenderModel.steps().size();
        for (int row = 0; row < visible; row++) {
            JourneyRenderRow step = journeyRenderModel.steps().get(row);
            int y = pt + journeyRowTop(row);
            int stepH = journeyRowHeight(row);
            boolean current = row == 0;
            if (row == 0 && visible > 1) {
                graphics.fill(spineX + 2, y + 18, spineX + 4,
                    y + stepH + journeyStepGap + 2,
                    ModalPixels.ACCENT);
            }
            graphics.fill(spineX, y + 11, spineX + 5, y + 16,
                current ? ModalPixels.GOOD : ModalPixels.MUTED);

            if (current) {
                int edge = 0xFF719267;
                graphics.fill(cardX - 1, y - 1, cardX + cardW + 1, y, edge);
                graphics.fill(cardX - 1, y + stepH, cardX + cardW + 1, y + stepH + 1, edge);
                graphics.fill(cardX - 1, y, cardX, y + stepH, edge);
                graphics.fill(cardX + cardW, y, cardX + cardW + 1, y + stepH, edge);
            }
            ModalPixels.card(graphics, cardX, y, cardW, stepH, current);
            int titleColour = current ? ModalPixels.ACCENT : ModalPixels.MUTED;
            graphics.drawString(font, step.title().text(), cardX + 7, y + 5, titleColour, false);
            int visibleLines = journeyDescriptionRows(row);
            int maxScroll = Math.max(0, step.description().size() - visibleLines);
            int firstLine = journeyDescriptionScroll[row] = Mth.clamp(journeyDescriptionScroll[row], 0, maxScroll);
            int textY = y + 17;
            for (int line = firstLine; line < Math.min(step.description().size(), firstLine + visibleLines); line++) {
                graphics.drawString(font, step.description().get(line), cardX + 7,
                    textY + (line - firstLine) * 9, current ? ModalPixels.INK : ModalPixels.MUTED, false);
            }
            if (maxScroll > 0) ModalPixels.scrollbar(graphics, cardX + cardW - HsUiTokens.SCROLL_W - 2, textY,
                visibleLines * 9, (float) visibleLines / step.description().size(),
                (float) firstLine / maxScroll, false);
            graphics.drawString(font, step.state().text(),
                cardX + cardW - 7 - step.state().width(), y + 5, titleColour, false);
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
    /** Room the popout close key takes at the header's right edge (key + gap). */
    private static final int MODAL_KEY_ROOM = 15;
    private final Ui2Serif.Text mayorTitleText = new Ui2Serif.Text(Ui2Serif.Size.TITLE);

    /** Header text inset from the panel edge (frame + margin), like every window title. */
    private static final int MAYOR_HEADER_X = 12;
    /** The serif title never takes more than this, so the status line keeps its room. */
    private static final int MAYOR_TITLE_MAX = 120;
    private List<Component> mayorHeaderTooltip = List.of();

    static int mayorTitleBox(int panelWidth) {
        return Math.max(1, Math.min(MAYOR_TITLE_MAX, panelWidth - MAYOR_HEADER_X * 2 - MODAL_KEY_ROOM));
    }

    /**
     * Header geometry (panel-local x): row 1 = serif title, then status line 1
     * right-aligned up to the close key; row 2 = status line 2 across the
     * header. Returns {line1Left, line1Right, line2Left, line2Right}.
     */
    static int[] mayorHeaderLines(int panelWidth, int titleWidth) {
        int line1Left = MAYOR_HEADER_X + Math.min(titleWidth, mayorTitleBox(panelWidth)) + Ui2FrameLayout.L;
        int line1Right = panelWidth - MODAL_CLOSE_INSET - 11 - Ui2FrameLayout.M;
        return new int[] {line1Left, Math.max(line1Left + 1, line1Right), MAYOR_HEADER_X,
            panelWidth - MAYOR_HEADER_X};
    }

    private void drawMayorStatus(GuiGraphics graphics, int pl, int pt) {
        MayorStatusRenderModel model = mayorStatusRenderModel;
        int[] lines = mayorHeaderLines(mayorPanelWidth, mayorTitleText.width());
        int row1 = pt + MAYOR_TITLE_Y + 5;
        int row2 = pt + MAYOR_TITLE_Y + 20;
        if (model.kind() == 0) {
            // Nothing to report yet: the choice rule fills the second row.
            graphics.drawString(font, mayorRenderModel.choiceRule().text(), pl + lines[2], row2,
                BannerChrome.TEXT_ON_WOOD_MUTED, false);
            return;
        }
        int first = model.kind() == 1 ? BannerChrome.GOLD_EDGE : BannerChrome.TEXT_ON_WOOD;
        graphics.drawString(font, model.first().text(),
            pl + lines[1] - model.first().width(), row1, first, false);
        int second = model.kind() == 3 && model.secondTone() == HsUiTokens.ACCENT
            ? BannerChrome.GOLD_EDGE : BannerChrome.TEXT_ON_WOOD_MUTED;
        graphics.drawString(font, model.second().text(), pl + lines[2], row2, second, false);
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
            int titleBox = mayorTitleBox(mayorPanelWidth);
            int pages = mayorPageCount(mayorSnapshot == null
                ? 0 : mayorSnapshot.candidates().size(), mayorVisibleRows);
            mayorRenderModel = new MayorRenderModel(
                HsUi.fitLabel(font, MAYOR_TITLE, titleBox),
                HsUi.fitLabel(font, MAYOR_CHOICE_RULE, mayorPanelWidth - MAYOR_HEADER_X * 2),
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
        mayorTitleText.fit(font, MAYOR_TITLE.getString(), mayorTitleBox(mayorPanelWidth));
        int[] lines = mayorHeaderLines(mayorPanelWidth, mayorTitleText.width());
        int box = lines[1] - lines[0];
        int box2 = lines[3] - lines[2];
        HsUi.FittedLabel empty = fittedEmpty();
        long now = currentGameTime();
        if (mayorSnapshot.mourning()) {
            long remaining = Math.max(0, mayorSnapshot.mourningUntil() - now);
            Component first = Component.translatable("hearthstead.mayor.mourning.active", formatTicks(remaining));
            Component second = Component.translatable("hearthstead.mayor.mourning.boon_suppressed");
            mayorHeaderTooltip = headerTooltip(first, second);
            return new MayorStatusRenderModel(1, HsUi.fitLabel(font, first, box),
                HsUi.fitLabel(font, second, box2), empty, empty, HsUiTokens.TEXT_MUTED);
        }
        if (!mayorSnapshot.hasMayor()) {
            Component first = Component.translatable("hearthstead.mayor.vacant");
            Component second = Component.translatable("hearthstead.mayor.vacant.hint");
            mayorHeaderTooltip = headerTooltip(first, second);
            return new MayorStatusRenderModel(2, HsUi.fitLabel(font, first, box),
                HsUi.fitLabel(font, second, box2), empty, empty, HsUiTokens.TEXT_MUTED);
        }
        long settlingRemaining = Math.max(0,
            mayorSnapshot.mayorSince() + Mayor.SETTLING_TICKS - now);
        boolean settling = settlingRemaining > 0;
        Component boonLine = Component.translatable(settling
                ? "hearthstead.mayor.brings_pending"
                : "hearthstead.mayor.brings_now",
            boonName(mayorSnapshot.boonKey()));
        mayorHeaderTooltip = headerTooltip(Component.literal(mayorSnapshot.mayorName()), boonLine);
        return new MayorStatusRenderModel(3,
            HsUi.fitLabel(font, Component.literal(mayorSnapshot.mayorName()), box),
            HsUi.fitLabel(font, boonLine, box2),
            empty,
            empty,
            settling ? HsUiTokens.TEXT_MUTED : HsUiTokens.ACCENT);
    }

    /** Title, the choice rule and both status lines, unshortened. */
    private static List<Component> headerTooltip(Component first, Component second) {
        return List.of(MAYOR_TITLE, MAYOR_CHOICE_RULE.copy().withStyle(net.minecraft.ChatFormatting.GRAY),
            first, second.copy().withStyle(net.minecraft.ChatFormatting.GRAY));
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
            cachedMoraleBand = HsUi.fitLabel(font, moraleBand(morale), sheet.right().width());
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
        int livePop = menu.get(HearthMenu.DATA_POPULATION);
        int pop = displayedPopulation(livePop,
            mayorSnapshot == null ? -1 : mayorSnapshot.residentTotal());
        if (mayorSnapshot != null && livePop != mayorSnapshot.residentTotal()
            && livePop != populationRefreshRequestedFor) {
            // The live count moved since the roster snapshot: refetch it once
            // so the header and Settlers converge on the same server-authored number.
            populationRefreshRequestedFor = livePop;
            PacketDistributor.sendToServer(mayorAction(
                HearthMayorAction.Kind.OPEN_PEOPLE, HearthMayorAction.NO_ID, 0));
        }
        int cap = menu.get(HearthMenu.DATA_CAPACITY);
        int employed = menu.get(HearthMenu.DATA_EMPLOYED);
        int food = menu.get(HearthMenu.DATA_FOOD);
        int radius = menu.get(HearthMenu.DATA_RADIUS);
        int morale = Mth.clamp(menu.get(HearthMenu.DATA_MORALE), 0, 100);
        int alert = menu.get(HearthMenu.DATA_ALERT);
        updateStatRenderCache(pop, cap, employed, food, radius, morale, alert,
            Math.max(24, sheet.right().width() - 20), Math.max(24, sheet.right().width() - 20),
            Math.max(24, sheet.right().width() - 12));
        int recruit = menu.get(HearthMenu.DATA_RECRUIT);
        RecruitmentPolicy.Blocker blocker = RecruitmentPolicy.Blocker.fromWireId(
            menu.get(HearthMenu.DATA_RECRUIT_BLOCKER));
        updateRecruitLines(blocker, pop, cap, morale, recruit);
        RecruitmentPolicy.Stage stage = RecruitmentPolicy.Stage.fromWireId(
            menu.get(HearthMenu.DATA_RECRUIT_STAGE));
        float recruitRatio = recruitmentProgressVisible(stage, blocker)
            ? Mth.clamp(recruit, 0, 100) / 100.0F : -1.0F;

        drawSheetHeader(graphics, pop, cap);

        int pageKind = suppliesOpen ? 3 : peopleTabOpen ? 1 : requestPanelOpen ? 2
            : buildingsPageOpen ? 4 : 0;
        if (pageKind != lastPageKind) {
            // Motion only: 120 ms fade of the page body on a page switch (never on first open).
            if (lastPageKind != -1) pageFade = new HsMotion.ScreenIntro(120L);
            lastPageKind = pageKind;
        }
        Runnable page = () -> {
            if (peopleTabOpen) {
                renderPeoplePage(graphics);
            } else if (requestPanelOpen) {
                renderTasksPage(graphics);
            } else if (suppliesOpen) {
                drawSupplySummary(graphics, recruitRatio);
            } else if (buildingsPageOpen) {
                renderBuildingsPage(graphics);
            } else {
                drawOverviewColumn(graphics, pop, food, recruitRatio);
            }
        };
        if (pageFade != null && !pageFade.done()) {
            HsMotion.withAlpha(graphics, pageFade.progress(), page);
        } else {
            pageFade = null;
            page.run();
        }
    }

    private static final Component SUPPLIES_HINT = Component.literal("Sorting by category lives in Stores");

    /** Crest, serif settlement name, "Bannerhold . Day N . time", and the three counters. */
    private void drawSheetHeader(GuiGraphics graphics, int pop, int cap) {
        long dayTime = minecraft != null && minecraft.level != null ? minecraft.level.getDayTime() : 0L;
        int day = (int) Math.min(Integer.MAX_VALUE - 1, Math.max(0L, dayTime) / 24000L) + 1;
        int tod = (int) (Math.floorMod(dayTime, 24000L));
        int phase = tod < 6000 ? 0 : tod < 11000 ? 1 : tod < 13000 ? 2 : tod < 23000 ? 3 : 4;
        int dayKey = day * 8 + phase;
        if (dayKey != subtitleKey) {
            subtitleKey = dayKey;
            String[] names = {"Morning", "Afternoon", "Evening", "Night", "Dawn"};
            subtitleLine = Component.literal("Bannerhold  ·  Day " + day + "  ·  " + names[phase]);
        }
        BannerSheetLayout.Rect title = sheet.title();
        String name = menu.getSettlementName();
        titleText.fit(font, name == null || name.isEmpty() ? "Settlement" : name, title.width());
        titleText.draw(graphics, font, title.x(), title.y() + 5, BannerChrome.TEXT_ON_WOOD);
        graphics.drawString(font, subtitleLine, title.x(), title.y() + 16, BannerChrome.TEXT_ON_WOOD_MUTED, false);

        int coins = RealmMapClient.hasData(menu.getSettlementId()) ? RealmMapClient.coins() : -1;
        if (coins != cachedCoins) {
            cachedCoins = coins;
            coinsValue = Component.literal(coins < 0 ? "—" : String.valueOf(coins));
        }
        double gs = minecraft == null ? 2 : minecraft.getWindow().getGuiScale();
        int texel = RealmIcons.itemScaleFor(12, gs);
        boolean narrow = sheet.narrowCounters();
        ItemStack[] icons = {breadIcon, coinIcon, overviewIcons[0]};
        for (int i = 0; i < BannerSheetLayout.COUNTERS; i++) {
            BannerSheetLayout.Rect r = sheet.counter(i);
            Component value = i == 0 ? cachedFoodStat.text() : i == 1 ? coinsValue : populationValue.text();
            RealmIcons.itemCentered(graphics, icons[i], r.x() + 10, r.y() + r.height() / 2.0F, texel, gs, 0);
            if (narrow) {
                graphics.drawString(font, value, r.x() + 18, r.y() + 8, BannerChrome.TEXT_ON_WOOD, false);
            } else {
                graphics.drawString(font, COUNTER_LABELS[i], r.x() + 19, r.y() + 3, BannerChrome.TEXT_ON_WOOD_MUTED,
                    false);
                graphics.drawString(font, value, r.x() + 19, r.y() + 12, BannerChrome.TEXT_ON_WOOD, false);
            }
        }
    }

    private static final Component[] COUNTER_LABELS = {
        Component.literal("Food"), Component.literal("Coins"), Component.literal("Settlers")
    };

    /** Overview right column: selection card, else Workers & Jobs, Supplies and the one primary action. */
    private void drawOverviewColumn(GuiGraphics graphics, int pop, int food, float recruitRatio) {
        BannerSheetLayout.Rect r = sheet.right();
        UUID settler = mapView.selectedSettler();
        if (settler != null && RealmMapClient.roster(settler) != null) {
            drawSettlerCard(graphics, r, settler);
            return;
        }
        UUID building = mapView.selectedBuilding();
        if (building != null && RealmMapView.building(building) != null) {
            drawBuildingCard(graphics, r, building);
            return;
        }
        double gs = minecraft == null ? 2 : minecraft.getWindow().getGuiScale();
        int texel = RealmIcons.itemScaleFor(12, gs);
        int suppliesY;
        if (!sheet.stacked()) {
            heading(graphics, "Workers & Jobs", r.x(), r.y(), r.width());
            ensureJobRows();
            int rows = jobRowsVisible();
            boolean more = rows < jobRows.size();
            if (jobRows.isEmpty()) {
                graphics.drawString(font, RealmMapClient.hasData(menu.getSettlementId())
                    ? "No settlers recorded yet" : "Surveying the realm…", r.x(), jobRowY(0) + 4,
                    Ui2Palette.INK_MUTED, false);
            }
            for (int i = 0; i < rows; i++) {
                int y = jobRowY(i);
                if (i > 0) Ui2Surface.rule(graphics, r.x() + 16, y, r.width() - 16);
                if (more && i == rows - 1) {
                    graphics.drawString(font, moreJobsLine(jobRows.size() - rows + 1), r.x() + 17, y + 4,
                        Ui2Palette.INK_SOFT, false);
                    chevron(graphics, r.right() - 5, y + 7, Ui2Palette.INK_MUTED);
                    break;
                }
                JobRow row = jobRows.get(i);
                boolean dim = jobFilter >= 0 && jobFilter != row.professionId();
                int ink = dim ? Ui2Palette.INK_MUTED : Ui2Palette.INK;
                RealmIcons.itemCentered(graphics, row.icon(), r.x() + 7, y + 7.0F, texel, gs, 0);
                graphics.drawString(font, row.label(), r.x() + 17, y + 4, ink, false);
                graphics.drawString(font, row.count(), r.right() - 10 - font.width(row.count()), y + 4, ink, false);
                chevron(graphics, r.right() - 5, y + 7, Ui2Palette.INK_MUTED);
            }
            suppliesY = r.bottom() - 80;
            heading(graphics, "Food stores", r.x(), suppliesY, r.width());
            suppliesY += RC_HEADING_H;
        } else {
            suppliesY = r.y() + 1;
        }
        // One line (food, and the reserve recruitment needs) plus one bar.
        RealmIcons.itemCentered(graphics, breadIcon, r.x() + 6, suppliesY + 4.5F, texel, gs, 0);
        int required = menu.get(HearthMenu.DATA_REQUIRED_RESERVE);
        graphics.drawString(font, foodLine(food), r.x() + 15, suppliesY + 1, Ui2Palette.INK, false);
        if (!sheet.stacked()) {
            int readyNow = Math.max(0, menu.get(HearthMenu.DATA_READY_AFTER_PRICE));
            boolean shortfall = required > 0 && readyNow < required;
            // No per-settler estimate: daily use is not known honestly, so only the real reserve gap shows.
            Component right = shortfall ? reserveLine(0, required) : null;
            // Only when it fits beside the food count: copy is dropped, never clipped.
            if (right != null && 15 + font.width(foodLine(food)) + 8 + font.width(right) <= r.width()) {
                graphics.drawString(font, right, r.right() - font.width(right), suppliesY + 1, Ui2Palette.INK_MUTED,
                    false);
            }
            if (shortfall) {
                int ready = readyNow;
                Ui2Surface.progress(graphics, r.x(), suppliesY + 12, r.width(),
                    Math.min(1.0F, ready / (float) required), ready >= required ? Ui2Palette.FOREST : Ui2Palette.GOLD);
            }
        }
        // The one thing that needs you, above its burgundy action; calm otherwise.
        int needY = sheet.stacked() ? r.y() + 14 : r.bottom() - 42;
        int textW = sheet.stacked() ? r.width() / 2 - 8 : r.width() - 10;
        if (!needs.isEmpty()) {
            Need focal = needs.get(0);
            if (focal != needLineFor || textW != needLineWidth) {
                needLineFor = focal;
                needLineWidth = textW;
                needLine = HsUi.fitLabel(font, focal.text(), textW);
                // The message has priority: up to two wrapped lines, the second fitted if it runs on.
                List<FormattedCharSequence> split = font.split(focal.text(), textW);
                needLines = split.size() <= 2 ? List.copyOf(split) : List.of(split.get(0),
                    HsUi.fitLabel(font, Component.literal(remainderAfterFirstLine(focal.text(), split.get(0))),
                        textW).text().getVisualOrderText());
            }
            Ui2Surface.alertGlyph(graphics, r.x() + 1, needY + 2, focal.danger() ? Ui2Palette.DANGER : Ui2Palette.GOLD);
            int maxLines = sheet.stacked() ? 1 : needLines.size();
            for (int i = 0; i < maxLines; i++) {
                graphics.drawString(font, sheet.stacked() ? needLine.text().getVisualOrderText() : needLines.get(i),
                    r.x() + 9, needY + 1 + i * 9, focal.danger() ? Ui2Palette.DANGER : Ui2Palette.INK, false);
            }
        } else {
            int calmY = sheet.stacked() ? needY : r.bottom() - 12;
            Ui2Surface.checkGlyph(graphics, r.x(), calmY + 2, Ui2Palette.FOREST);
            graphics.drawString(font, CALM_TITLE, r.x() + 10, calmY + 1, Ui2Palette.FOREST, false);
        }
    }

    /** A small right-pointing chevron centred vertically on {@code cy}. */
    private static void chevron(GuiGraphics g, int x, int cy, int color) {
        g.fill(x - 2, cy - 3, x - 1, cy - 2, color);
        g.fill(x - 1, cy - 2, x, cy - 1, color);
        g.fill(x, cy - 1, x + 1, cy + 1, color);
        g.fill(x - 1, cy + 1, x, cy + 2, color);
        g.fill(x - 2, cy + 2, x - 1, cy + 3, color);
    }

    private final Map<Integer, Component> numberLines = new HashMap<>();
    private Need needLineFor;
    private List<FormattedCharSequence> needLines = List.of();

    /** Plain text left after the first wrapped line (for fitting the second line). */
    private String remainderAfterFirstLine(Component text, FormattedCharSequence first) {
        StringBuilder b = new StringBuilder();
        first.accept((index, style, cp) -> {
            b.appendCodePoint(cp);
            return true;
        });
        String all = text.getString();
        return all.length() > b.length() ? all.substring(b.length()).stripLeading() : "";
    }
    private int needLineWidth = -1;

    private Component foodLine(int food) {
        if (numberLines.size() > 256) numberLines.clear();
        return numberLines.computeIfAbsent(food, f -> Component.literal(f + " food"));
    }

    private Component moreJobsLine(int more) {
        return numberLines.computeIfAbsent(-1000 - more, k -> Component.literal("+" + more + " more"));
    }

    private Component reserveLine(int ready, int required) {
        return numberLines.computeIfAbsent(1_000_000 + ready * 1000 + Math.min(999, required),
            k -> Component.literal("need " + required));
    }

    /** Selected settler: portrait, name, trade and level, what they are doing, what they carry. */
    private void drawSettlerCard(GuiGraphics g, BannerSheetLayout.Rect r, UUID id) {
        SettlerCardModel m = settlerCardModel(id, r.width());
        RealmMapLayoutPayload.RosterEntry entry = RealmMapClient.roster(id);
        MarkerTrack track = RealmMapClient.track(id);
        int x = r.x();
        int y = r.y();
        boolean roomy = r.height() >= 196 && !sheet.stacked();
        int textX;
        if (roomy) {
            centeredHeading(g, "Selected settler", x, y, r.width());
            int p = Math.min(48, r.height() - 150);
            int px = x + (r.width() - p) / 2;
            int py = y + 14;
            portraitFrame(g, px, py, p);
            RealmIcons.face(g, id, track == null ? -1 : track.entityId, entry == null ? -1 : entry.appearanceSeed(),
                entry == null ? 0 : entry.professionId(), px, py, p);
            y = py + p + 4;
            cardName.fit(font, entry == null ? "Settler" : entry.name(), r.width());
            cardName.draw(g, font, x + (r.width() - cardName.width()) / 2, y + 2, Ui2Palette.INK);
            g.drawString(font, m.job(), x + (r.width() - font.width(m.job())) / 2, y + 14, Ui2Palette.INK_SOFT, false);
            y += 26;
            textX = x;
        } else {
            portraitFrame(g, x, y, 24);
            RealmIcons.face(g, id, track == null ? -1 : track.entityId, entry == null ? -1 : entry.appearanceSeed(),
                entry == null ? 0 : entry.professionId(), x, y, 24);
            cardNameSmall.fit(font, entry == null ? "Settler" : entry.name(), r.width() - 42);
            cardNameSmall.draw(g, font, x + 29, y + 3, Ui2Palette.INK);
            g.drawString(font, m.job(), x + 29, y + 15, Ui2Palette.INK_SOFT, false);
            y += 28;
            textX = x;
        }
        if (sheet.stacked()) {
            if (track != null) {
                statusGlyph(g, RealmMapStatus.byWireId(track.statusId), x + 1, y + 1);
                g.drawString(font, m.statusWord(), x + 9, y, m.statusColor(), false);
            }
            return;
        }
        ornamentRule(g, x, y, r.width());
        y += 5;
        g.drawString(font, "CURRENT ACTION", textX, y, Ui2Palette.INK_MUTED, false);
        y += 10;
        if (track != null) {
            statusGlyph(g, RealmMapStatus.byWireId(track.statusId), textX + 1, y + 1);
        } else {
            Ui2Surface.lockGlyph(g, textX + 1, y, Ui2Palette.INK_MUTED);
        }
        g.drawString(font, m.statusWord(), textX + 9, y, m.statusColor(), false);
        g.drawString(font, m.activityLine(), textX, y + 10, Ui2Palette.INK, false);
        if (!settlerHasWorkplace(id)) {
            g.drawString(font, m.workplace(), textX, y + 20, Ui2Palette.INK_MUTED, false);
        }
        y += 32;
        int bottomLimit = r.bottom() - 52;
        if (y + 22 <= bottomLimit) {
            g.drawString(font, "CARRIED ITEMS", textX, y, Ui2Palette.INK_MUTED, false);
            y += 10;
            if (!m.hasFocus()) {
                g.drawString(font, "…", textX, y + 2, Ui2Palette.INK_MUTED, false);
            } else if (m.bag().isEmpty()) {
                g.drawString(font, "Nothing", textX, y + 2, Ui2Palette.INK_SOFT, false);
            } else {
                double gs = minecraft == null ? 2 : minecraft.getWindow().getGuiScale();
                int texel = RealmIcons.itemScaleFor(12, gs);
                float size = RealmIcons.itemGuiSize(texel, gs);
                int step = Math.max(12, (int) Math.ceil(size) + 3);
                int perRow = Math.max(1, r.width() / step);
                for (int i = 0; i < m.bag().size(); i++) {
                    float ix = textX + (i % perRow) * step + size / 2.0F;
                    float iy = y + (i / perRow) * step + size / 2.0F;
                    if (iy + size / 2 > bottomLimit) break;
                    RealmIcons.itemCentered(g, m.bag().get(i), ix, iy, texel, gs, 0);
                    Component count = m.bagCounts().get(i);
                    if (!count.getString().isEmpty()) {
                        g.pose().pushPose();
                        g.pose().translate(0, 0, 200);
                        g.drawString(font, count, (int) (ix + size / 2.0F) - font.width(count) + 1,
                            (int) (iy + size / 2.0F) - 6, 0xFFFFFFFF, true);
                        g.pose().popPose();
                    }
                }
                y += ((m.bag().size() - 1) / perRow) * step;
            }
            y += 16;
        }
        if (y + 20 <= bottomLimit) {
            drawNeedBar(g, "Fed", m.fed(), m.fedRatio(), m.hasFocus(), textX, y, r.width());
            drawNeedBar(g, "Rested", m.rested(), m.restedRatio(), m.hasFocus(), textX, y + 10, r.width());
        }
    }

    private final Ui2Serif.Text cardName = new Ui2Serif.Text(Ui2Serif.Size.TITLE);

    /** Y of the workplace line in the settler card (shared by the drawing and its jump link). */
    private int settlerWorkplaceY(BannerSheetLayout.Rect r) {
        boolean roomy = r.height() >= 196 && !sheet.stacked();
        if (roomy) return r.y() + 44 + Math.min(48, r.height() - 150) + 35;
        return r.y() + 28 + 5 + 10 + 20;
    }

    private boolean settlerHasWorkplace(UUID id) {
        RealmMapLayoutPayload layout = mapLayout();
        RealmMapLayoutPayload.RosterEntry entry = RealmMapClient.roster(id);
        return layout != null && entry != null && entry.workBuilding() >= 0
            && entry.workBuilding() < layout.buildings().size();
    }
    /** Narrow cards use heading-size capitals so names are not cut. */
    private final Ui2Serif.Text cardNameSmall = new Ui2Serif.Text(Ui2Serif.Size.HEADING);

    private static void portraitFrame(GuiGraphics g, int x, int y, int size) {
        g.fill(x - 2, y - 2, x + size + 2, y + size + 2, BannerChrome.PLATE_HIGHLIGHT);
        g.fill(x - 1, y - 1, x + size + 1, y + size + 1, Ui2Palette.RULE);
    }

    /** Hairline with a small diamond in the middle, as between card sections. */
    private static void ornamentRule(GuiGraphics g, int x, int y, int w) {
        int mid = x + w / 2;
        Ui2Surface.rule(g, x, y, w / 2 - 5);
        Ui2Surface.rule(g, mid + 5, y, x + w - mid - 5);
        Ui2Surface.pendingGlyph(g, mid - 2, y - 2, Ui2Palette.RULE_STRONG);
    }

    private void centeredHeading(GuiGraphics g, String text, int x, int y, int w) {
        Ui2Serif.Text t = headings.computeIfAbsent(text, k -> new Ui2Serif.Text(Ui2Serif.Size.HEADING));
        t.set(font, text);
        int tx = x + (w - t.width()) / 2;
        t.draw(g, font, tx, y + 2, Ui2Palette.INK_SOFT);
        if (tx - 8 > x) {
            Ui2Surface.pendingGlyph(g, tx - 9, y + 3, Ui2Palette.RULE_STRONG);
            Ui2Surface.pendingGlyph(g, tx + t.width() + 4, y + 3, Ui2Palette.RULE_STRONG);
        }
    }

    private void drawNeedBar(GuiGraphics g, String label, Component value, float ratio, boolean known,
                             int x, int y, int w) {
        g.drawString(font, label, x, y, Ui2Palette.INK_MUTED, false);
        int barX = x + 36;
        int barW = w - 36 - 26;
        int color = ratio < 0.25F ? Ui2Palette.DANGER : ratio < 0.5F ? Ui2Palette.AMBER : Ui2Palette.FOREST;
        if (known) {
            Ui2Surface.progress(g, barX, y + 3, barW, ratio, color);
        } else {
            g.fill(barX, y + 3, barX + barW, y + 5, Ui2Palette.TRACK);
        }
        g.drawString(font, value, x + w - font.width(value), y, known && ratio < 0.25F ? Ui2Palette.DANGER
            : Ui2Palette.INK_SOFT, false);
    }

    /** Selected building: emblem, serif name, level, state chip and its workers (rows are real buttons). */
    private void drawBuildingCard(GuiGraphics g, BannerSheetLayout.Rect r, UUID id) {
        BuildingCardModel m = buildingCardModel(id, r.width());
        RealmMapLayoutPayload layout = mapLayout();
        RealmMapLayoutPayload.BuildingEntry entry = RealmMapView.building(id);
        if (m == null || layout == null || entry == null) return;
        int x = r.x();
        int y = r.y();
        g.renderItem(m.emblem(), x, y + 1);
        Ui2Serif.Text nameText = r.width() >= 140 ? cardName : cardNameSmall;
        nameText.fit(font, m.name().getString(), r.width() - 32);
        nameText.draw(g, font, x + 20, y + 2, Ui2Palette.INK);
        g.drawString(font, m.level(), x + 20, y + 13, Ui2Palette.INK_SOFT, false);
        if (sheet.stacked()) return;
        ornamentRule(g, x, y + 26, r.width());
        int index = layout.buildings().indexOf(entry);
        BuildingState state = buildingState(layout, index);
        g.drawString(font, "STATUS", x, y + 32, Ui2Palette.INK_MUTED, false);
        chip(g, font, state, x + r.width() - 52, y + 30, 52);
        if (!m.valid()) {
            g.drawString(font, HsUi.fitLabel(font, m.status(), r.width()).text(), x, y + 43, Ui2Palette.DANGER, false);
        }
        g.drawString(font, "WORKERS", x, y + 45 + (m.valid() ? 0 : 9), Ui2Palette.INK_MUTED, false);
        g.drawString(font, m.workers(), x + r.width() - font.width(m.workers()), y + 45 + (m.valid() ? 0 : 9),
            Ui2Palette.INK, false);
        int rowY = y + BUILDING_WORKERS_TOP + (m.valid() ? 0 : 9);
        int maxY = r.bottom() - (buildingsPageOpen ? 24 : 2);
        if (m.workerNames().isEmpty()) {
            g.drawString(font, entry.workerCapacity() > 0 ? "Nobody works here yet" : "Not a workplace", x,
                rowY + 2, Ui2Palette.INK_MUTED, false);
        }
        for (RealmMapLayoutPayload.RosterEntry worker : layout.roster()) {
            if (worker.workBuilding() != index) continue;
            if (rowY + 12 > maxY) break;
            MarkerTrack track = RealmMapClient.track(worker.id());
            if (track != null) {
                statusGlyph(g, RealmMapStatus.byWireId(track.statusId), x + 1, rowY + 3);
            } else {
                g.fill(x + 2, rowY + 5, x + 4, rowY + 7, Ui2Palette.INK_DISABLED);
            }
            g.drawString(font, workerName(worker, r.width() - 12), x + 9, rowY + 2, Ui2Palette.INK_SOFT, false);
            rowY += 12;
        }
    }

    private Component workerName(RealmMapLayoutPayload.RosterEntry worker, int width) {
        if (buildingRowNamesVersion != RealmMapClient.layoutVersion()) {
            buildingRowNames.clear();
            buildingRowNamesVersion = RealmMapClient.layoutVersion();
        }
        return buildingRowNames.computeIfAbsent(worker.id(),
            k -> HsUi.fitLabel(font, Component.literal(worker.name()), width).text());
    }

    private void drawSupplySummary(GuiGraphics graphics, float recruitRatio) {
        BannerSheetLayout.Rect communal = sheet.communalGrid();
        BannerSheetLayout.Rect player = sheet.playerGrid();
        BannerSheetLayout.Rect wide = sheet.wide();
        heading(graphics, "Banner stores", communal.x() - 1, communal.y() - 11,
            communal.width() - font.width(supplyCategory.displayName()) - 10);
        heading(graphics, "Your inventory", player.x() - 1, player.y() - 11,
            sheet.storageSideBySide() ? player.width() - font.width("Stores") - 14 : player.width());
        if (sheet.storageSideBySide()) {
            int x = communal.x() - 1;
            int y = communal.bottom() + 6;
            int w = communal.width();
            int allItems = supplyItemTotal(HearthSupplyCategory.ALL);
            int selectedItems = supplyItemTotal(supplyCategory);
            graphics.drawString(font, HsUi.fitLabel(font, allItems == 0
                ? Component.literal("The Banner's stores are empty")
                : Component.literal(selectedItems + " of " + allItems + " items shown"), w).text(), x, y,
                Ui2Palette.INK_SOFT, false);
            if (y + 40 < wide.bottom()) {
                heading(graphics, "Recruiting", x, y + 14, w);
                graphics.drawString(font, recruitLine2, x, y + 28, Ui2Palette.INK_SOFT, false);
                if (recruitRatio >= 0.0F) {
                    Ui2Surface.progress(graphics, x, y + 40, w, recruitRatio, Ui2Palette.GOLD);
                }
            }
            // The free space under the player's inventory holds the open requests.
            drawStorageRequests(graphics, player.x() - 1, player.bottom() + 8, player.width(), wide.bottom() - 2);
        } else {
            drawStorageRequests(graphics, player.x() - 1, player.bottom() + 6, player.width(), wide.bottom() - 2);
        }
    }

    /** Open requests and deliveries (the old Tasks ledger), compact: one line each, details on hover. */
    private void drawStorageRequests(GuiGraphics graphics, int x, int y, int w, int bottom) {
        storageRequestRowsShown = 0;
        storageRequestTop = y + 14;
        if (y + 24 > bottom) return;
        List<RequestRenderRow> rows = cachedRequestRows;
        heading(graphics, rows.isEmpty() ? "Open requests" : "Open requests  " + rows.size(), x, y, w);
        int rowY = y + 14;
        if (cachedRequestView.quarantined()) {
            // A critical ledger conflict blocks the raid declaration; it must be visible here.
            graphics.drawString(font, HsUi.fitLabel(font, Component.translatable("hearthstead.request.ledger.quarantined",
                Component.literal(cachedRequestView.quarantineReason())), w).text(), x, rowY, Ui2Palette.DANGER, false);
            rowY += 10;
            storageRequestTop = rowY;
        }
        if (rows.isEmpty()) {
            graphics.drawString(font, "Nothing is waiting", x, rowY, Ui2Palette.INK_MUTED, false);
            return;
        }
        for (int i = 0; i < rows.size() && rowY + 9 <= bottom; i++) {
            RequestRenderRow row = rows.get(i);
            boolean last = i > 0 && i < rows.size() - 1 && rowY + 19 > bottom;
            Component line = last ? Component.literal("+" + (rows.size() - i) + " more") : row.headline();
            graphics.drawString(font, HsUi.fitLabel(font, line, w).text(), x, rowY,
                last ? Ui2Palette.INK_MUTED : ModalPixels.ink(row.tone()), false);
            storageRequestRowsShown = i + 1;
            if (last) break;
            rowY += 10;
        }
        storageRequestX = x;
        storageRequestW = w;
    }

    private int storageRequestRowsShown;
    private int storageRequestTop;
    private int storageRequestX;
    private int storageRequestW;

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
        int lineWidth = Math.max(24, sheet.communalGrid().width());
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
            case ATTRACTION, TAVERN_VISIT -> "hearthstead.gui.tooltip.recruit.stage.attraction";
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

    private final Map<String, Ui2Serif.Text> modalTitles = new HashMap<>();

    private void renderModalTitle(GuiGraphics graphics, Component title, int x, int y,
                                  int width, int leftReserve) {
        int available = Math.max(1, width - leftReserve - 60);
        Ui2Serif.Text serif = modalTitles.computeIfAbsent(title.getString(),
            k -> new Ui2Serif.Text(Ui2Serif.Size.TITLE));
        serif.fit(font, title.getString(), available);
        int tx = x + leftReserve + (available - serif.width()) / 2;
        serif.draw(graphics, font, tx, y + 1, BannerChrome.TEXT_ON_WOOD);
        // Title-fit rule: a shortened title shows its full text on hover.
        if (serif.truncated()) popoutHeaderTip(List.of(title), tx, y - 4, serif.width(), 16);
    }

    private List<Component> popoutHeaderTip = List.of();
    private int popoutHeaderTipX0;
    private int popoutHeaderTipY0;
    private int popoutHeaderTipX1;
    private int popoutHeaderTipY1;

    /** Registers this frame's popout header tooltip; the modal tooltip pass paints it. */
    private void popoutHeaderTip(List<Component> lines, int x, int y, int w, int h) {
        popoutHeaderTip = lines;
        popoutHeaderTipX0 = x;
        popoutHeaderTipY0 = y;
        popoutHeaderTipX1 = x + w;
        popoutHeaderTipY1 = y + h;
    }

    // Motion only: first-open intro (container: fade only so slots stay aligned with hit-testing); created once, survives re-init.
    private HsMotion.ScreenIntro hsIntro;
    private boolean hsIntroRendering;
    private HsMotion.ScreenIntro hsTabFade;
    private int hsLastTabKind;

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (hsIntro == null) hsIntro = new HsMotion.ScreenIntro();
        if (!hsIntroRendering && !hsIntro.done()) {
            hsIntroRendering = true;
            try {
                hsIntro.render(graphics, 0.0F, () -> render(graphics, mouseX, mouseY, partialTick));
            } finally {
                hsIntroRendering = false;
            }
            return;
        }
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
                        topPos + imageHeight, Ui2Palette.SCRIM);
                }
                int hsTabKind = recruitmentPanelOpen ? 1 : mayorTabOpen ? 2
                    : peopleTabOpen ? 3 : requestPanelOpen ? 4 : 5;
                if (hsTabKind != hsLastTabKind) {
                    // Motion only: 100 ms fade of the tab body on a tab switch.
                    if (hsLastTabKind != 0) hsTabFade = new HsMotion.ScreenIntro(100L);
                    hsLastTabKind = hsTabKind;
                }
                popoutHeaderTip = List.of();
                Runnable hsTabBody = () -> {
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
                };
                if (hsTabFade != null && !hsTabFade.done()) {
                    hsTabFade.render(graphics, 0.0F, hsTabBody);
                } else {
                    hsTabFade = null;
                    hsTabBody.run();
                }
                AbstractButton tooltipOwner = null;
                for (AbstractButton widget : latePanelWidgets) {
                    widget.render(graphics, mouseX, mouseY, partialTick);
                    boolean over = widget.visible && mouseX >= widget.getX() && mouseY >= widget.getY()
                        && mouseX < widget.getX() + widget.getWidth() && mouseY < widget.getY() + widget.getHeight();
                    if (widget.visible && widget.getTooltip() != null
                        && (over || widget.isFocused())
                        && (tooltipOwner == null || over)) {
                        tooltipOwner = widget;
                    }
                }
                // Screen's deferred tooltip pass runs after this pose is popped.
                // Paint the cached widget tooltip here so it shares modal depth.
                if (tooltipOwner != null) {
                    graphics.renderTooltip(font,
                        tooltipOwner.getTooltip().toCharSequence(minecraft), mouseX, mouseY);
                } else if (!popoutHeaderTip.isEmpty() && mouseX >= popoutHeaderTipX0 && mouseX < popoutHeaderTipX1
                    && mouseY >= popoutHeaderTipY0 && mouseY < popoutHeaderTipY1) {
                    graphics.renderComponentTooltip(font, popoutHeaderTip, mouseX, mouseY);
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
        graphics.flush();
        renderTooltip(graphics, mouseX, mouseY);
        renderStatTooltips(graphics, mouseX, mouseY);
        if (suppliesOpen && storageRequestRowsShown > 0) {
            int lx = mouseX - leftPos;
            int ly = mouseY - topPos;
            if (lx >= storageRequestX && lx < storageRequestX + storageRequestW && ly >= storageRequestTop) {
                int row = (ly - storageRequestTop) / 10;
                if (row < storageRequestRowsShown && row < cachedRequestRows.size()
                    && !cachedRequestRows.get(row).tooltip().isEmpty()) {
                    graphics.renderComponentTooltip(font, cachedRequestRows.get(row).tooltip(), mouseX, mouseY);
                }
            }
        }
        if (overviewOpen()) {
            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, 0.0F, 400.0F);
            mapView.renderTooltip(graphics, font, mouseX, mouseY);
            graphics.pose().popPose();
        }
    }

    /**
     * Stat-card and recruitment-card hover areas belong to the Home tab only.
     * People and Tasks render over the same body rect, so without this gate
     * the Home tooltips kept appearing on those tabs.
     */
    static boolean homeHoverTooltipsActive(boolean peopleTabOpen, boolean requestPanelOpen,
                                           boolean mayorTabOpen, boolean journeyTabOpen,
                                           boolean recruitmentPanelOpen) {
        return !peopleTabOpen && !requestPanelOpen && !mayorTabOpen
            && !journeyTabOpen && !recruitmentPanelOpen;
    }

    /**
     * The Home "Population" figure and the People roster must agree. The
     * roster is the server snapshot ({@code residentTotal}); the menu slot
     * is the live count and is only used until a snapshot exists.
     */
    static int displayedPopulation(int livePopulation, int snapshotResidentTotal) {
        return snapshotResidentTotal >= 0 ? snapshotResidentTotal : Math.max(0, livePopulation);
    }

    /** Header counters explain themselves on hover. */
    private void renderStatTooltips(GuiGraphics graphics, int mouseX, int mouseY) {
        double localX = mouseX - leftPos;
        double localY = mouseY - topPos;
        for (int index = 0; index < BannerSheetLayout.COUNTERS; index++) {
            if (!sheet.counter(index).contains(localX, localY)) continue;
            List<Component> lines = new ArrayList<>(3);
            if (index == 0) {
                lines.add(Component.translatable("hearthstead.gui.tooltip.food"));
                lines.add(Component.literal(menu.get(HearthMenu.DATA_FOOD) + " edible items in the Banner stores")
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
            } else if (index == 1) {
                lines.add(Component.literal("Coins"));
                lines.add(Component.literal(RealmMapClient.coins() < 0 ? "Counting\u2026"
                    : "Physical coins in the Banner and loaded warehouses")
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
            } else {
                lines.add(Component.translatable("hearthstead.gui.tooltip.population"));
                lines.add(Component.literal(populationValue.text().getString() + " settlers \u00b7 "
                    + menu.get(HearthMenu.DATA_CAPACITY) + " places \u00b7 " + menu.get(HearthMenu.DATA_EMPLOYED)
                    + " employed").withStyle(net.minecraft.ChatFormatting.GRAY));
                lines.add(Component.translatable("hearthstead.gui.tooltip.radius")
                    .append(": ").append(cachedRadiusStat.text()).withStyle(net.minecraft.ChatFormatting.GRAY));
            }
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
            return;
        }
    }

    /**
     * The Banner's popouts (Mayor, Journey, Recruit, requests, readiness,
     * aftermath) on the standard window: walnut board, wood header with the
     * serif title, parchment page, hairline rules and ui2 ink.
     */
    private static final class ModalPixels {
        private static final int INK = Ui2Palette.INK;
        private static final int MUTED = Ui2Palette.INK_MUTED;
        private static final int ACCENT = Ui2Palette.GOLD;
        private static final int GOOD = Ui2Palette.FOREST;
        private static final int WARN = Ui2Palette.AMBER;
        private static final int BAD = Ui2Palette.DANGER;
        /** Default wood header: the page starts under the 26px title band. */
        private static final int HEADER = 26;

        private static int ink(int tone) {
            if (tone == HsUiTokens.GOOD) return GOOD;
            if (tone == HsUiTokens.WARN) return WARN;
            if (tone == HsUiTokens.BAD) return BAD;
            if (tone == HsUiTokens.ACCENT) return ACCENT;
            return INK;
        }

        private static void window(GuiGraphics g, int x, int y, int w, int h) {
            window(g, x, y, w, h, HEADER);
        }

        /** Board, then parchment from {@code header} down; the page's top line is the header rule. */
        private static void window(GuiGraphics g, int x, int y, int w, int h, int header) {
            BannerChrome.panel(g, x, y, w, h);
            int f = BannerSheetLayout.FRAME;
            g.fill(x + f, y + header - 1, x + w - f, y + header, BannerChrome.PLATE_SHADOW);
            BannerChrome.parchment(g, x + f, y + header, w - f * 2, h - header - f);
        }

        /** Row card: hover tint and a hairline under it, never a box. */
        private static void card(GuiGraphics g, int x, int y, int w, int h, boolean hover) {
            Ui2Surface.row(g, x, y, w, h, hover ? 1.0F : 0.0F, false);
            Ui2Surface.rule(g, x, y + h - 1, w);
        }

        /** Quiet inset well (portraits, the Mayor status note). */
        private static void inset(GuiGraphics g, int x, int y, int w, int h) {
            g.fill(x, y, x + w, y + h, Ui2Palette.INSET);
            g.fill(x, y, x + w, y + 1, Ui2Palette.RULE_STRONG);
            g.fill(x, y, x + 1, y + h, Ui2Palette.RULE_STRONG);
        }

        private static void divider(GuiGraphics g, int x, int y, int w) {
            Ui2Surface.rule(g, x, y, w);
        }

        private static void scrollbar(GuiGraphics g, int x, int y, int h,
                                       float visible, float position, boolean hovered) {
            Ui2Surface.scrollbar(g, x + HsUiTokens.SCROLL_W / 2, y, h, visible, position);
        }
    }
}
