package com.hearthstead.client.screen;

import com.hearthstead.client.QaClientObserver;
import com.hearthstead.client.QaUiInspectable;
import com.hearthstead.client.ui.HsMotion;
import com.hearthstead.client.ui.HsButton;
import com.hearthstead.client.ui.HearthPixelSurface;
import com.hearthstead.client.ui.HsUi;
import com.hearthstead.client.ui.HearthMaterials;
import com.hearthstead.client.ui.HsUiTokens;
import com.hearthstead.network.DevelopmentActionPayload;
import com.hearthstead.network.DevelopmentSnapshotPayload;
import com.hearthstead.settlement.development.BuildingDescription;
import com.hearthstead.settlement.development.Development;
import com.hearthstead.settlement.development.DevelopmentNode;
import com.hearthstead.settlement.development.DevelopmentObjective;
import com.hearthstead.settlement.development.PostRaidUpgrade;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hearth-owned spatial Development map.
 *
 * <p>Layout, labels, tooltips, item stacks and server-state projections are
 * cached when a snapshot arrives. A steady frame walks fixed arrays and does
 * not rebuild lists, Components or ItemStacks. Panning only translates the
 * existing node controls and their hit boxes; zoom may reflow their cached
 * labels, but never recreates the widget tree.
 */
public final class DevelopmentScreen extends Screen implements QaUiInspectable {
    // Keep a real dependency viewport alongside complete, paged phase details.
    // The initial view frames the next unfinished phase, including at GUI scale 4.
    private static final int PANEL_W = 1824;
    private static final int PANEL_H = 520;
    private static final int PAD = 10;
    private static final int VIEW_X = 12;
    private static final int VIEW_Y = 64;
    // Tree content (edges, plaques, hit boxes) clips one pixel inside the
    // viewport inset so a partly visible plaque never paints over its rule.
    private static final int VIEW_CLIP_INSET = 1;
    private static final int FOOTER_RESERVED = 42;
    private static final int INSPECTOR_GAP = 8;
    private static final int INSPECTOR_MIN_W = 132;
    private static final int INSPECTOR_MAX_W = 300;
    // Native Minecraft glyphs are nine pixels high. Compact inspector pages use
    // that honest line height so one complete short objective never loses its
    // final wrapped word below the page controls.
    private static final int INSPECTOR_LINE_H = 9;
    private static final int INSPECTOR_NAV_RESERVED = 64;
    // Unlock pages reserve their lower panel for the price and current requirements.
    private static final int UNLOCK_ROW_H = 24;
    private static final int UNLOCK_SUMMARY_H = 72;
    private static final int NODE_W = 150;
    private static final int NODE_H = 74;
    // Fonts remain at native GUI scale while cards zoom. The opening overview
    // is a recognisable item card with one compact name; full quest, cost and
    // requirement detail remains in the bounded keyboard/mouse inspector.
    private static final float[] ZOOMS = {
        0.30F, 0.42F, 0.56F, 0.72F, 0.86F, 1.0F, 1.18F
    };
    // A 42% opening view keeps the next chain legible at GUI scale 4. The
    // 30% level remains available when the player deliberately zooms farther out.
    private static final int DEFAULT_ZOOM_INDEX = 1;
    private static final int DEFAULT_PAN_X = -10;
    private static final int DEFAULT_PAN_Y = -40;
    private static final int CONTROL_GAP = 4;
    private static final int REFRESH_W = 56;
    private static final int CENTER_W = 30;
    private static final int ZOOM_W = 20;
    private static final int CLOSE_W = 40;
    private static final int HEADER_CONTROLS_W = REFRESH_W + CENTER_W
        + 2 * ZOOM_W + CLOSE_W + 4 * CONTROL_GAP;
    private static final Component SUBTITLE = Component.translatable(
        "hearthstead.development.subtitle");
    private static final Component COMPACT_HELP = Component.literal(
        "Select a phase | drag to pan | Ctrl+wheel zooms");
    private static final Component DEFAULT_FOOTER = Component.translatable(
        "hearthstead.development.footer.pan_zoom.compact");
    private static final Component UNLOCKS_HEADER = Component.translatable(
        "hearthstead.development.inspector.unlocks_from_research");
    /** Compact unlock heading; the full localized instruction is its tooltip. */
    private static final Component UNLOCKS_SHORT_HEADER = Component.literal("UNLOCKS");
    private static final Component CRAFT_PLAN_HEADER = Component.translatable(
        "hearthstead.development.inspector.craft_this_plan");
    private static final Component DIRECTION_WATCH = Component.translatable(
        "hearthstead.development.direction.watch");
    private static final Component DIRECTION_HEARTH = Component.translatable(
        "hearthstead.development.direction.hearth");
    private static final Component DIRECTION_CRAFT = Component.translatable(
        "hearthstead.development.direction.craft");
    private static final Component DIRECTION_LOGISTICS = Component.translatable(
        "hearthstead.development.direction.logistics");
    // Hub map: worldX/worldY are card CENTRES; the Charter / First Fire pair
    // sits at the origin and the four directions radiate out (negative y is up).
    private static final int HUB_MIN_X = -1000;
    private static final int HUB_MAX_X = 1000;
    private static final int HUB_MIN_Y = -1010;
    private static final int HUB_MAX_Y = 1010;
    /** The First Raid milestone ring; everything outside it is post-raid. */
    private static final int RAID_RING_RADIUS = 560;
    /** Zooms below this show the whole hub as a map of plain state chips. */
    private static final float MAP_ZOOM_LIMIT = 0.29F;

    static final DevelopmentNode[][] EDGES = {
        {DevelopmentNode.SETTLEMENT_CHARTER, DevelopmentNode.SHELTER},
        {DevelopmentNode.SHELTER, DevelopmentNode.TIMBER_RIGHTS},
        {DevelopmentNode.TIMBER_RIGHTS, DevelopmentNode.STORES_AND_ROADS},
        {DevelopmentNode.STORES_AND_ROADS, DevelopmentNode.CULTIVATED_GROUND},
        {DevelopmentNode.STORES_AND_ROADS, DevelopmentNode.SHORE_PROVISIONS},
        {DevelopmentNode.STORES_AND_ROADS, DevelopmentNode.FIRST_WATCH},
        {DevelopmentNode.SHELTER, DevelopmentNode.HOME},
        {DevelopmentNode.HOME, DevelopmentNode.HOSPITALITY},
        {DevelopmentNode.FIRST_WATCH, DevelopmentNode.ARM_THE_WATCH},
        {DevelopmentNode.ARM_THE_WATCH, DevelopmentNode.FIRST_RAID_AFTERMATH},
        {DevelopmentNode.FIRST_RAID_AFTERMATH, DevelopmentNode.SHIELD_DOCTRINE},
        {DevelopmentNode.FIRST_RAID_AFTERMATH, DevelopmentNode.GUILD_DOCTRINE},
        {DevelopmentNode.FIRST_RAID_AFTERMATH, DevelopmentNode.HEARTH_DOCTRINE},
        {DevelopmentNode.SHIELD_DOCTRINE, DevelopmentNode.FORTIFICATION},
        {DevelopmentNode.SHIELD_DOCTRINE, DevelopmentNode.BORDER_WARDENS},
        {DevelopmentNode.GUILD_DOCTRINE, DevelopmentNode.LAND_AND_HARVEST},
        {DevelopmentNode.GUILD_DOCTRINE, DevelopmentNode.CRAFT_AND_INDUSTRY},
        {DevelopmentNode.HEARTH_DOCTRINE, DevelopmentNode.HALL_AND_LEARNING}
    };

    private DevelopmentSnapshotPayload snapshot;
    private CachedNode[] cached = new CachedNode[0];
    private CachedUpgrade[] cachedUpgrades = new CachedUpgrade[0];
    private final List<TechNodeButton> nodeButtons = new ArrayList<>();
    private final List<TechNodeButton> upgradeButtons = new ArrayList<>();
    /** Every map card, centre outward then clockwise from north (Tab / PgUp / PgDn order). */
    private final List<TechNodeButton> focusOrder = new ArrayList<>();
    private CachedUpgrade inspectedUpgrade;
    /** Continuous zoom used by "Fit" when the whole hub is smaller than ZOOMS[0]. */
    private float fitZoom = 0.30F;
    private Component footer = DEFAULT_FOOTER;
    private Component titleLine;
    private Component subtitleLine = SUBTITLE;
    private Component footerLine = DEFAULT_FOOTER;
    private int left;
    private int top;
    private int panelWidth = PANEL_W;
    private int panelHeight = PANEL_H;
    private int viewWidth = PANEL_W - 24;
    private int viewHeight = 192;
    private int inspectorLeft;
    private int inspectorTop;
    private int inspectorWidth;
    private int inspectorHeight;
    private int inspectorPage;
    private int inspectorPageSize = 1;
    private Component inspectorPageLine = Component.empty();
    private Component inspectorHeadingLine = Component.empty();
    private Component inspectorBadgeLine = Component.empty();
    private Component unlocksHeaderLine = UNLOCKS_HEADER;
    private boolean unlocksHeaderShortened;
    private List<FormattedCharSequence> inspectorCostLines = List.of();
    /** Full text for a shortened inspector label, drawn after the panel clip. */
    private Component inspectorTextHover;
    private Component availableCoinsLine = Component.empty();
    private PixelButton inspectorPrevious;
    private PixelButton inspectorNext;
    private PixelButton learnButton;
    private boolean viewInitialized;
    private CachedNode inspectedNode;
    private List<InspectorLine> inspectorLines = List.of();
    private List<PlanRecipe> inspectorRecipes = List.of();
    private List<UnlockView> inspectorUnlocks = List.of();
    private int selectedUnlockIndex;
    private ItemStack recipeHover = ItemStack.EMPTY;
    /** A real recipe resolved from the synced client RecipeManager. */
    private record PlanRecipe(String buildingType, ItemStack output,
                              List<Ingredient> cells, boolean shaped) {}
    /** Server-owned knowledge kept with enough identity for an inspectable UI. */
    private record UnlockView(ItemStack icon, Component name, Component benefit,
                              String buildingType) {
        boolean isPlan() {
            return buildingType != null;
        }
    }
    private int panX = DEFAULT_PAN_X;
    private int panY = DEFAULT_PAN_Y;
    private int zoomIndex = DEFAULT_ZOOM_INDEX;
    private boolean draggingMap;
    private boolean uiOpenSoundPlayed;
    private boolean uiCloseSoundPlayed;
    private double lastDragX;
    private double lastDragY;
    private String nodeTextEpoch = "";
    private int progressRefreshTicks;

    public DevelopmentScreen(DevelopmentSnapshotPayload snapshot) {
        super(Component.translatable("hearthstead.development.title"));
        this.snapshot = snapshot;
        this.titleLine = title;
        rebuildCache();
    }

    public boolean accepts(DevelopmentSnapshotPayload fresh) {
        return fresh != null
            && fresh.view() == DevelopmentActionPayload.View.TECH
            && fresh.settlementId().equals(snapshot.settlementId())
            && fresh.hearthPos().equals(snapshot.hearthPos());
    }

    public void update(DevelopmentSnapshotPayload fresh) {
        if (!accepts(fresh)) {
            return;
        }
        boolean selectedWasLearned = inspectedNode != null && inspectedNode.learned;
        playAuthoritativeFeedback(fresh);
        snapshot = fresh;
        rebuildCache();
        if (shouldOpenLearnedRecipePage(selectedWasLearned,
            inspectedNode != null && inspectedNode.learned)) {
            // Recipe pages are added only after the server accepts learning.
            // Land on the first newly useful page instead of preserving a
            // shifted requirement/details page from the pre-learned state.
            inspectorPage = 0;
            selectedUnlockIndex = firstPlanUnlockIndex();
            updateInspectorControls();
        }
        refreshNodeControls();
    }

    @Override
    protected void init() {
        BoardLayout layout = layoutFor(width, height);
        panelWidth = layout.panelWidth();
        panelHeight = layout.panelHeight();
        viewWidth = layout.viewWidth();
        viewHeight = layout.viewHeight();
        inspectorWidth = layout.inspectorWidth();
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        inspectorLeft = left + VIEW_X + viewWidth + INSPECTOR_GAP;
        inspectorTop = top + VIEW_Y;
        inspectorHeight = viewHeight;
        if (!viewInitialized) {
            frameProgression();
            viewInitialized = true;
        }
        rebuildDisplayLines();
        rebuildControls();
        if (!uiOpenSoundPlayed) {
            uiOpenSoundPlayed = true;
            HsUi.playOpenSound();
        }
    }

    @Override
    public void removed() {
        if (uiOpenSoundPlayed && !uiCloseSoundPlayed) {
            uiCloseSoundPlayed = true;
            HsUi.playCloseSound();
        }
        super.removed();
    }

    private static void playAuthoritativeFeedback(
            DevelopmentSnapshotPayload fresh) {
        fresh.feedback().ifPresent(feedback -> {
            boolean accepted = feedback.getContents()
                instanceof TranslatableContents translated
                && Development.Result.APPLIED.translationKey().equals(
                    translated.getKey());
            QaClientObserver.markUiTransition(accepted
                ? "development_result_ok" : "development_result_error");
            if (accepted) {
                HsUi.playConfirmSound();
            } else {
                HsUi.playErrorSound();
            }
        });
    }

    private void rebuildCache() {
        DevelopmentNode inspected = inspectedNode == null
            ? null : inspectedNode.node;
        Map<Integer, DevelopmentSnapshotPayload.NodeView> views = new HashMap<>();
        if (snapshot != null) {
            for (DevelopmentSnapshotPayload.NodeView view : snapshot.nodes()) {
                views.put(view.nodeWireId(), view);
            }
        }
        DevelopmentNode[] nodes = DevelopmentNode.PRESENTATION_ORDER;
        CachedNode[] next = new CachedNode[nodes.length];
        for (int i = 0; i < nodes.length; i++) {
            DevelopmentNode node = nodes[i];
            DevelopmentSnapshotPayload.NodeView view = views.get(node.wireId());
            Development.NodeStatus status = view == null
                ? Development.NodeStatus.QUARANTINED
                : Development.NodeStatus.fromWireId(view.statusWireId());
            String reasonKey = view == null
                ? "hearthstead.development.blocked.quarantined" : view.reasonKey();
            next[i] = CachedNode.create(node, status, reasonKey,
                view == null ? List.of() : view.quests(), views,
                snapshot == null ? -1 : snapshot.availableCoins());
        }
        cached = next;
        PostRaidUpgrade inspectedBonus = inspectedUpgrade == null
            ? null : inspectedUpgrade.upgrade;
        Map<Integer, DevelopmentSnapshotPayload.UpgradeView> upgradeViews = new HashMap<>();
        if (snapshot != null) {
            for (DevelopmentSnapshotPayload.UpgradeView view : snapshot.upgrades()) {
                upgradeViews.put(view.upgradeWireId(), view);
            }
        }
        PostRaidUpgrade[] upgrades = PostRaidUpgrade.values();
        CachedUpgrade[] nextUpgrades = new CachedUpgrade[upgrades.length];
        for (int i = 0; i < upgrades.length; i++) {
            nextUpgrades[i] = CachedUpgrade.create(upgrades[i],
                upgradeViews.get(upgrades[i].wireId()), views, upgradeViews,
                snapshot == null ? -1 : snapshot.availableCoins());
        }
        cachedUpgrades = nextUpgrades;
        if (inspectedBonus != null) {
            inspectedUpgrade = cachedUpgrades[inspectedBonus.ordinal()];
            inspectedNode = null;
        } else if (cached.length > 0) {
            inspectedUpgrade = null;
            inspectedNode = inspected == null
                ? nextUnfinishedNode() : cached[inspected.ordinal()];
        } else {
            inspectedNode = null;
        }
        footer = snapshot != null && snapshot.feedback().isPresent()
            ? snapshot.feedback().get() : DEFAULT_FOOTER;
        if (font != null) {
            rebuildDisplayLines();
            rebuildInspectorLines();
        }
    }

    /** Ellipsise only when layout/snapshot changes, never once per frame. */
    private void rebuildDisplayLines() {
        titleLine = clipLine(font, title,
            panelWidth - CLOSE_W - 3 * PAD);
        // The complete localized guidance remains available as a hover tooltip;
        // keep the visible compact line whole at the smallest supported board.
        subtitleLine = clipLine(font, COMPACT_HELP, panelWidth - 2 * PAD);
        footerLine = clipLine(font, footer, panelWidth - 2 * PAD - 124);
        availableCoinsLine = snapshot != null && snapshot.availableCoins() >= 0
            ? clipLine(font, Component.literal("Available: " + snapshot.availableCoins() + " Coins"), 116)
            : Component.empty();
        rebuildInspectorLines();
    }

    private static Component clipLine(Font font, Component text, int width) {
        if (font == null || text == null || width <= 0) {
            return Component.empty();
        }
        if (font.width(text) <= width) {
            return text;
        }
        int contentWidth = Math.max(0, width - font.width("..."));
        return Component.literal(font.plainSubstrByWidth(text.getString(), contentWidth)
            + "...");
    }

    private void rebuildControls() {
        clearWidgets();
        nodeButtons.clear();
        upgradeButtons.clear();
        focusOrder.clear();
        if (snapshot == null) {
            return;
        }
        int right = left + panelWidth - PAD;
        right -= CLOSE_W;
        HsButton close = PixelButton.danger(right, top + 8, CLOSE_W,
            HsUiTokens.BUTTON_H, Component.translatable("hearthstead.gui.close"),
            this::onClose);
        close.setTooltip(Tooltip.create(Component.translatable(
            "hearthstead.gui.close.tip")));
        addRenderableWidget(close);

        right = left + PAD + REFRESH_W + CENTER_W + 2 * ZOOM_W + 3 * CONTROL_GAP;
        right -= ZOOM_W;
        HsButton zoomIn = PixelButton.normal(right, top + 41, ZOOM_W,
            HsUiTokens.BUTTON_H, Component.literal("+"), () -> zoom(1));
        zoomIn.setTooltip(Tooltip.create(Component.translatable(
            "hearthstead.development.zoom_in")));
        addRenderableWidget(zoomIn);

        right -= CONTROL_GAP + ZOOM_W;
        HsButton zoomOut = PixelButton.normal(right, top + 41, ZOOM_W,
            HsUiTokens.BUTTON_H, Component.literal("-"), () -> zoom(-1));
        zoomOut.setTooltip(Tooltip.create(Component.translatable(
            "hearthstead.development.zoom_out")));
        addRenderableWidget(zoomOut);

        right -= CONTROL_GAP + CENTER_W;
        HsButton centre = PixelButton.normal(right, top + 41, CENTER_W,
            HsUiTokens.BUTTON_H, Component.literal("Fit"), this::resetView);
        centre.setTooltip(Tooltip.create(Component.translatable(
            "hearthstead.development.center.tip")));
        addRenderableWidget(centre);

        right -= CONTROL_GAP + REFRESH_W;
        HsButton refresh = PixelButton.normal(right, top + 41,
            REFRESH_W, HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.development.refresh"), this::refresh);
        refresh.setTooltip(Tooltip.create(Component.translatable(
            "hearthstead.development.refresh.tip")));
        addRenderableWidget(refresh);

        inspectorPrevious = PixelButton.normal(inspectorLeft + 6,
            inspectorTop + inspectorHeight - 23, 24, 18,
            Component.literal("<"), () -> changeInspectorPage(-1));
        inspectorNext = PixelButton.normal(inspectorLeft + inspectorWidth - 30,
            inspectorTop + inspectorHeight - 23, 24, 18,
            Component.literal(">"), () -> changeInspectorPage(1));
        inspectorPrevious.setTooltip(Tooltip.create(Component.translatable("hearthstead.mayor.page.previous")));
        inspectorNext.setTooltip(Tooltip.create(Component.translatable("hearthstead.mayor.page.next")));
        addRenderableWidget(inspectorPrevious);
        addRenderableWidget(inspectorNext);
        learnButton = PixelButton.action(left + panelWidth - PAD - 116,
            top + panelHeight - 26, 116, HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.development.action.learn"), () -> {
                if (inspectedUpgrade != null) {
                    if (inspectedUpgrade.available) buyUpgrade(inspectedUpgrade.upgrade);
                } else if (inspectedNode != null && inspectedNode.available) {
                    unlock(inspectedNode.node);
                }
            });
        addRenderableWidget(learnButton);
        updateInspectorControls();

        for (CachedNode node : cached) {
            TechNodeButton button = new TechNodeButton(node, false,
                () -> setInspectedNode(cached[node.node.ordinal()]));
            nodeButtons.add(button);
            focusOrder.add(button);
        }
        for (CachedUpgrade upgrade : cachedUpgrades) {
            TechNodeButton button = new TechNodeButton(upgrade, true,
                () -> setInspectedUpgrade(cachedUpgrades[upgrade.upgrade.ordinal()]));
            upgradeButtons.add(button);
            focusOrder.add(button);
        }
        // Keyboard order follows the hub: the Charter / First Fire centre
        // first, then ring by ring, clockwise from the northern Watch arm.
        focusOrder.sort(java.util.Comparator
            .comparingInt((TechNodeButton b) -> hubRing(b.node))
            .thenComparingDouble(b -> hubAngle(b.node)));
        for (TechNodeButton button : focusOrder) {
            addRenderableWidget(button);
        }
        relayoutNodeControls();
    }

    private static int hubRing(Inspectable entry) {
        double distance = Math.hypot(entryX(entry), entryY(entry));
        return (int) Math.round(distance / 100.0D);
    }

    private static double hubAngle(Inspectable entry) {
        double angle = Math.atan2(entryX(entry), -entryY(entry));
        return angle < 0 ? angle + 2 * Math.PI : angle;
    }

    private static int entryX(Inspectable entry) {
        return entry instanceof CachedUpgrade upgrade
            ? upgradeX(upgrade.upgrade) : worldX(((CachedNode) entry).node);
    }

    private static int entryY(Inspectable entry) {
        return entry instanceof CachedUpgrade upgrade
            ? upgradeY(upgrade.upgrade) : worldY(((CachedNode) entry).node);
    }

    /**
     * A server refresh changes node state, not screen structure. Rebind the
     * existing controls instead of clearing and allocating the whole widget
     * tree on every authoritative snapshot.
     */
    private void refreshNodeControls() {
        if (nodeButtons.size() != cached.length
            || upgradeButtons.size() != cachedUpgrades.length) {
            rebuildControls();
            return;
        }
        for (int i = 0; i < cached.length; i++) {
            nodeButtons.get(i).updateNode(cached[i]);
        }
        for (int i = 0; i < cachedUpgrades.length; i++) {
            upgradeButtons.get(i).updateNode(cachedUpgrades[i]);
        }
        relayoutNodeControls();
    }

    /**
     * Moves the fixed node controls with the map without clearing or
     * allocating the screen widget tree. This runs during drag/scroll and is
     * deliberately O(node count) with only integer geometry updates.
     */
    private void relayoutNodeControls() {
        float zoom = zoom();
        NodeGeometry geometry = nodeGeometry(zoom);
        NodeGeometry bonusGeometry = upgradeGeometry(zoom);
        // Same rectangle as the render scissor, so hover/click hit boxes never
        // extend past what is visibly drawn.
        int viewLeft = viewClipLeft();
        int viewTop = viewClipTop();
        int viewRight = viewClipRight();
        int viewBottom = viewClipBottom();
        String textEpoch = currentTextEpoch();
        nodeTextEpoch = textEpoch;
        for (TechNodeButton button : focusOrder) {
            NodeGeometry g = button.upgrade ? bonusGeometry : geometry;
            // Hub coordinates are card centres, so every zoom keeps cards centred on their spot.
            int x = screenX(entryX(button.node), zoom) - g.width() / 2;
            int y = screenY(entryY(button.node), zoom) - g.height() / 2;
            button.relayout(x, y, g, viewLeft, viewTop, viewRight,
                viewBottom, font, textEpoch);
        }
    }

    private String currentTextEpoch() {
        return minecraft == null ? "" : minecraft.getLanguageManager().getSelected();
    }

    private void unlock(DevelopmentNode node) {
        PacketDistributor.sendToServer(new DevelopmentActionPayload(snapshot.hearthPos(),
            snapshot.settlementId(), snapshot.mayorId(), DevelopmentActionPayload.View.TECH,
            DevelopmentActionPayload.Kind.UNLOCK_NODE, node.wireId(), snapshot.revision()));
    }

    /** The server re-validates prerequisites, milestone gate, revision and the atomic payment. */
    private void buyUpgrade(PostRaidUpgrade upgrade) {
        PacketDistributor.sendToServer(new DevelopmentActionPayload(snapshot.hearthPos(),
            snapshot.settlementId(), snapshot.mayorId(), DevelopmentActionPayload.View.TECH,
            DevelopmentActionPayload.Kind.BUY_UPGRADE, upgrade.wireId(), snapshot.revision()));
    }

    @Override
    public void tick() {
        super.tick();
        // Receive fresh server counters and wallet while this screen is open.
        // Five seconds keeps multiplayer progress current without per-tick scans.
        if (snapshot != null && ++progressRefreshTicks >= 100) {
            progressRefreshTicks = 0;
            refresh();
        }
    }

    private void refresh() {
        PacketDistributor.sendToServer(new DevelopmentActionPayload(snapshot.hearthPos(),
            snapshot.settlementId(), snapshot.mayorId(), DevelopmentActionPayload.View.TECH,
            DevelopmentActionPayload.Kind.REFRESH, -1, snapshot.revision()));
    }

    private void zoom(int direction) {
        int next;
        if (zoomIndex < 0) {
            next = direction > 0 ? firstZoomAbove(fitZoom) : -1;
        } else if (zoomIndex == 0 && direction < 0 && hubFitZoom() < ZOOMS[0]) {
            next = -1;
        } else {
            next = Math.max(0, Math.min(ZOOMS.length - 1, zoomIndex + direction));
        }
        if (next != zoomIndex) {
            QaClientObserver.markUiTransition("development_zoom");
            if (next < 0) {
                fitHub();
            } else {
                // Keep the map point under the viewport centre in place.
                float before = zoom();
                int centreX = panX + Math.round(viewWidth / (2 * before));
                int centreY = panY + Math.round(viewHeight / (2 * before));
                zoomIndex = next;
                centerOn(centreX, centreY);
            }
            relayoutNodeControls();
        }
    }

    private static int firstZoomAbove(float zoom) {
        for (int i = 0; i < ZOOMS.length; i++) {
            if (ZOOMS[i] > zoom + 0.001F) return i;
        }
        return ZOOMS.length - 1;
    }

    /** "Fit" and Home frame the whole hub, every direction and ring at once. */
    private void resetView() {
        QaClientObserver.markUiTransition("development_center");
        fitHub();
        relayoutNodeControls();
    }

    private float hubFitZoom() {
        float fitX = Math.max(1, viewWidth - 8) / (float) (HUB_MAX_X - HUB_MIN_X);
        float fitY = Math.max(1, viewHeight - 8) / (float) (HUB_MAX_Y - HUB_MIN_Y);
        return Math.max(0.02F, Math.min(fitX, fitY));
    }

    private void fitHub() {
        float fit = hubFitZoom();
        zoomIndex = -1;
        for (int i = ZOOMS.length - 1; i >= 0; i--) {
            if (ZOOMS[i] <= fit) {
                zoomIndex = i;
                break;
            }
        }
        fitZoom = Math.min(fit, ZOOMS[0]);
        centerOn((HUB_MIN_X + HUB_MAX_X) / 2, (HUB_MIN_Y + HUB_MAX_Y) / 2);
    }

    /** Opens legible (42%) on the next unfinished step of the hub. */
    private void frameProgression() {
        zoomIndex = DEFAULT_ZOOM_INDEX;
        Inspectable entry = inspected();
        if (entry == null) {
            centerOn(0, 0);
        } else {
            centerOn(entryX(entry), entryY(entry));
        }
    }

    private void centerOn(int worldCentreX, int worldCentreY) {
        float z = zoom();
        panX = worldCentreX - Math.round(viewWidth / (2 * z));
        panY = worldCentreY - Math.round(viewHeight / (2 * z));
        clampPan();
    }

    private float zoom() {
        return zoomIndex < 0 ? fitZoom : ZOOMS[zoomIndex];
    }

    /**
     * The 30% map stays a compact label chip. At the normal 42% opening
     * view, a 16px item icon and one unbroken short name make every node
     * recognisable without forcing the inspector text into the map. Doubling
     * the branch lanes in {@link #worldY(DevelopmentNode)} leaves real air
     * between these 68x34 overview cards.
     */
    static NodeGeometry nodeGeometry(float zoom) {
        if (zoom < MAP_ZOOM_LIMIT) {
            // Whole-hub "Fit" view: plain state chips, no text.
            return new NodeGeometry(Math.max(4, Math.round(160 * zoom)),
                Math.max(3, Math.round(64 * zoom)), NodeVisualMode.MAP);
        }
        if (zoom < 0.38F) {
            return new NodeGeometry(Math.max(48, Math.round(160 * zoom)),
                Math.max(20, Math.round(64 * zoom)), NodeVisualMode.OVERVIEW);
        }
        if (zoom < 0.56F) {
            return new NodeGeometry(Math.max(68, Math.round(162 * zoom)),
                Math.max(34, Math.round(80 * zoom)), NodeVisualMode.OVERVIEW);
        }
        return new NodeGeometry(Math.max(72, Math.round(NODE_W * zoom)),
            Math.max(36, Math.round(NODE_H * zoom)), NodeVisualMode.DETAILED);
    }

    /**
     * Bonus cards are the node card at a smaller size (about 110 x 44 world
     * units). The overview shows only the item icon; the name joins at 56%.
     */
    static NodeGeometry upgradeGeometry(float zoom) {
        if (zoom < MAP_ZOOM_LIMIT) {
            return new NodeGeometry(Math.max(3, Math.round(110 * zoom)),
                Math.max(2, Math.round(44 * zoom)), NodeVisualMode.MAP);
        }
        if (zoom < 0.56F) {
            return new NodeGeometry(Math.max(26, Math.round(110 * zoom)), 20,
                NodeVisualMode.OVERVIEW);
        }
        return new NodeGeometry(Math.max(62, Math.round(110 * zoom)),
            Math.max(22, Math.round(44 * zoom)), NodeVisualMode.DETAILED);
    }

    static int scaledNodeWidth(float zoom) {
        return nodeGeometry(zoom).width();
    }

    static int scaledNodeHeight(float zoom) {
        return nodeGeometry(zoom).height();
    }

    enum NodeVisualMode {
        DETAILED,
        OVERVIEW,
        MAP
    }

    record NodeGeometry(int width, int height, NodeVisualMode mode) {
    }

    private int screenX(int worldX, float zoom) {
        return left + VIEW_X + Math.round((worldX - panX) * zoom);
    }

    private int screenY(int worldY, float zoom) {
        return top + VIEW_Y + Math.round((worldY - panY) * zoom);
    }

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
        // Locale changes do not necessarily recreate this Screen. Re-fit the
        // fixed widget tree once here; ordinary frames keep the cached lines.
        if (!nodeTextEpoch.equals(currentTextEpoch())) {
            rebuildDisplayLines();
            relayoutNodeControls();
        }
        renderBackground(graphics, mouseX, mouseY, partialTick);
        PixelBoard.window(graphics, left, top, panelWidth, panelHeight);
        graphics.drawString(font, titleLine, left + PAD, top + 10,
            HsUiTokens.TEXT_STRONG, true);
        graphics.drawString(font, subtitleLine, left + PAD, top + 27,
            HearthPixelSurface.MUTED, false);
        if (mouseX >= left + PAD && mouseX < left + panelWidth - PAD
            && mouseY >= top + 24 && mouseY < top + VIEW_Y - 6) {
            renderWrappedTooltip(graphics, SUBTITLE, mouseX, mouseY);
        }

        PixelBoard.divider(graphics, left + PAD, top + VIEW_Y - 6,
            panelWidth - 2 * PAD);

        PixelBoard.inset(graphics, left + VIEW_X, top + VIEW_Y, viewWidth, viewHeight);
        graphics.enableScissor(viewClipLeft(), viewClipTop(), viewClipRight(), viewClipBottom());
        drawHubGuides(graphics);
        drawEdges(graphics);
        graphics.disableScissor();
        updateInspectedNode(mouseX, mouseY);
        renderInspector(graphics, mouseX, mouseY);
        // Every node clips itself to the viewport. Rendering the widget layer
        // after the edge scissor keeps the zoom/close controls and deferred
        // tooltips outside that clip rectangle.
        HsUi.widgets(this, graphics, mouseX, mouseY, partialTick);
        if (!recipeHover.isEmpty()) {
            graphics.renderTooltip(font, recipeHover, mouseX, mouseY);
        } else if (inspectorTextHover != null) {
            renderWrappedTooltip(graphics, inspectorTextHover, mouseX, mouseY);
        }

        PixelBoard.divider(graphics, left + PAD, top + panelHeight - 33,
            panelWidth - 2 * PAD);
        graphics.drawString(font, footerLine, left + PAD, top + panelHeight - 25,
            HearthPixelSurface.MUTED, false);
        if (mouseX >= left + PAD && mouseX < left + panelWidth - 134
            && mouseY >= top + panelHeight - 30 && mouseY < top + panelHeight - 6) {
            renderWrappedTooltip(graphics, footer, mouseX, mouseY);
        }
    }

    /**
     * Single-Component tooltips are never wrapped by vanilla, so the long
     * help line ran past the right edge. Pre-split to a width that always
     * fits on one side of the cursor; vanilla's positioner then flips/clamps.
     */
    private void renderWrappedTooltip(GuiGraphics graphics, Component text, int mouseX, int mouseY) {
        graphics.renderTooltip(font, font.split(text, tooltipWrapWidth(width)), mouseX, mouseY);
    }

    /** Max tooltip text width: fits beside the cursor on either half of the screen. */
    static int tooltipWrapWidth(int screenWidth) {
        return Math.max(40, Math.min(240, screenWidth / 2 - 16));
    }

    /**
     * Learn/Buy label from the same price the server charges: the Coin line
     * ({@code coinCost()}) plus physical goods lines, never their sum.
     */
    static String actionLabel(boolean upgrade, boolean done, int coins, int goodsLines) {
        if (done) return upgrade ? "Owned" : "Learned";
        String verb = upgrade ? "Buy - " : "Learn - ";
        if (coins <= 0) return verb + (goodsLines > 0 ? "Goods" : "Free");
        return verb + coins + (coins == 1 ? " Coin" : " Coins") + (goodsLines > 0 ? " +" : "");
    }

    private void updateInspectedNode(int mouseX, int mouseY) {
        // Mouse intent wins over a previously keyboard-focused card.
        for (TechNodeButton button : focusOrder) {
            if (button.isHoveredAt(mouseX, mouseY)) {
                setInspected(button.node);
                return;
            }
        }
        for (TechNodeButton button : focusOrder) {
            if (button.isFocused()) {
                setInspected(button.node);
                return;
            }
        }
    }

    private Inspectable inspected() {
        return inspectedUpgrade != null ? inspectedUpgrade : inspectedNode;
    }

    private void setInspected(Inspectable entry) {
        if (entry instanceof CachedUpgrade upgrade) {
            setInspectedUpgrade(upgrade);
        } else {
            setInspectedNode((CachedNode) entry);
        }
    }

    private void setInspectedNode(CachedNode node) {
        selectEntry(node, null);
    }

    private void setInspectedUpgrade(CachedUpgrade upgrade) {
        selectEntry(null, upgrade);
    }

    private void selectEntry(CachedNode node, CachedUpgrade upgrade) {
        if (node != inspectedNode || upgrade != inspectedUpgrade) {
            QaClientObserver.markUiTransition("development_inspector");
            inspectedNode = node;
            inspectedUpgrade = upgrade;
            inspectorPage = 0;
            selectedUnlockIndex = 0;
            rebuildInspectorLines();
        }
    }

    /** Wraps only when selection, locale, snapshot or panel width changes. */
    private void rebuildInspectorLines() {
        inspectorRecipes = List.of();
        inspectorUnlocks = List.of();
        Inspectable entry = inspected();
        if (font == null || inspectorWidth <= 0 || inspectorHeight <= 0
            || entry == null) {
            inspectorHeadingLine = Component.empty();
            inspectorLines = List.of();
            inspectorBadgeLine = Component.empty();
            inspectorCostLines = List.of();
            return;
        }
        List<UnlockView> unlocks = new ArrayList<>();
        // Bonuses unlock no plans or emblems; their inspector is details only.
        if (inspectedNode != null) {
            for (var building : inspectedNode.node.knowledge().buildPlans()) {
                ItemStack plan = com.hearthstead.block.PlaqueItemData.stamped(
                    new ItemStack(com.hearthstead.registry.ModItems.BUILD_PLAN.get()), building);
                unlocks.add(new UnlockView(plan, building.displayName(),
                    BuildingDescription.shortDescription(building), building.id()));
            }
            for (var profession : inspectedNode.node.knowledge().jobEmblems()) {
                ItemStack emblem = com.hearthstead.item.JobEmblemItem.stackFor(profession);
                if (!emblem.isEmpty()) {
                    unlocks.add(new UnlockView(emblem, profession.displayName(),
                        Component.translatable("hearthstead.development.detail.emblem",
                            profession.displayName()), null));
                }
            }
        }
        inspectorUnlocks = List.copyOf(unlocks);
        selectedUnlockIndex = Math.max(0, Math.min(selectedUnlockIndex,
            Math.max(0, inspectorUnlocks.size() - 1)));
        inspectorHeadingLine = clipLine(font, entry.name(),
            Math.max(1, inspectorWidth - 34));
        int textWidth = Math.max(1, inspectorWidth - 16);
        inspectorBadgeLine = clipLine(font, entry.stateBadge(),
            Math.max(1, inspectorWidth - 34));
        unlocksHeaderShortened = font.width(UNLOCKS_HEADER) > textWidth;
        unlocksHeaderLine = unlocksHeaderShortened
            ? clipLine(font, UNLOCKS_SHORT_HEADER, textWidth) : UNLOCKS_HEADER;
        inspectorCostLines = wrapCost(font, entry.costText(), textWidth);
        List<InspectorLine> rebuilt = new ArrayList<>();
        for (InspectorSection section : entry.details()) {
            for (FormattedCharSequence line : font.split(section.text, textWidth)) {
                rebuilt.add(new InspectorLine(line, section.colour, -1, 0));
            }
            if (section.target > 0) {
                rebuilt.add(new InspectorLine(FormattedCharSequence.EMPTY, section.colour,
                    section.progress, section.target));
            }
        }
        inspectorLines = List.copyOf(rebuilt);
        if (inspectedNode != null && inspectedNode.learned
            && minecraft != null && minecraft.level != null) {
            List<PlanRecipe> plans = new ArrayList<>();
            for (var holder : minecraft.level.getRecipeManager().getRecipes()) {
                if (!(holder.value() instanceof CraftingRecipe recipe)) continue;
                ItemStack output = recipe.getResultItem(minecraft.level.registryAccess());
                if (!output.is(com.hearthstead.registry.ModItems.BUILD_PLAN.get())) continue;
                String type = output.get(com.hearthstead.registry.ModComponents.BUILDING_TYPE.get());
                if (inspectedNode.node.knowledge().buildPlans().stream()
                        .noneMatch(building -> building.id().equals(type))) continue;
                List<Ingredient> cells = new ArrayList<>(java.util.Collections.nCopies(9, Ingredient.EMPTY));
                var ingredients = recipe.getIngredients();
                if (recipe instanceof ShapedRecipe shaped) {
                    for (int row = 0; row < shaped.getHeight(); row++) {
                        for (int col = 0; col < shaped.getWidth(); col++) {
                            cells.set(row * 3 + col, ingredients.get(row * shaped.getWidth() + col));
                        }
                    }
                } else {
                    for (int slot = 0; slot < Math.min(9, ingredients.size()); slot++) cells.set(slot, ingredients.get(slot));
                }
                plans.add(new PlanRecipe(type, output.copy(), List.copyOf(cells),
                    recipe instanceof ShapedRecipe));
            }
            plans.sort(java.util.Comparator.comparing(plan -> plan.output.getHoverName().getString()));
            inspectorRecipes = List.copyOf(plans);
        }
        inspectorPageSize = Math.max(1, (inspectorHeight - INSPECTOR_NAV_RESERVED)
            / INSPECTOR_LINE_H);
        updateInspectorControls();
    }

    /**
     * Wraps a "Cost: a + b + c" line at its " + " joins where possible so each
     * ingredient stays whole; any single over-long ingredient is word-wrapped.
     */
    private static List<FormattedCharSequence> wrapCost(Font font, Component cost, int width) {
        String[] parts = cost.getString().split(" \\+ ");
        List<FormattedCharSequence> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            String candidate = current.length() == 0 ? part : current + " + " + part;
            if (current.length() > 0 && font.width(candidate) > width) {
                lines.addAll(font.split(Component.literal(current.toString()), width));
                current.setLength(0);
                current.append("+ ").append(part);
            } else {
                current.setLength(0);
                current.append(candidate);
            }
        }
        if (current.length() > 0) {
            lines.addAll(font.split(Component.literal(current.toString()), width));
        }
        return List.copyOf(lines);
    }

    private int viewClipLeft() {
        return left + VIEW_X + VIEW_CLIP_INSET;
    }

    private int viewClipTop() {
        return top + VIEW_Y + VIEW_CLIP_INSET;
    }

    private int viewClipRight() {
        return left + VIEW_X + viewWidth - VIEW_CLIP_INSET;
    }

    private int viewClipBottom() {
        return top + VIEW_Y + viewHeight - VIEW_CLIP_INSET;
    }

    private int inspectorPageCount() {
        return inspectorRecipes.size() + unlockPageCount() + Math.max(1, (inspectorLines.size() + inspectorPageSize - 1) / inspectorPageSize);
    }

    private int unlockRowsPerPage() {
        return Math.max(1, (inspectorHeight - 56 - UNLOCK_SUMMARY_H - 26) / UNLOCK_ROW_H);
    }

    private int unlockPageCount() {
        return (inspectorUnlocks.size() + unlockRowsPerPage() - 1) / unlockRowsPerPage();
    }

    private void changeInspectorPage(int direction) {
        inspectorPage = Math.max(0, Math.min(inspectorPageCount() - 1, inspectorPage + direction));
        updateInspectorControls();
    }

    private int firstPlanUnlockIndex() {
        for (int index = 0; index < inspectorUnlocks.size(); index++) {
            if (inspectorUnlocks.get(index).isPlan()) {
                return index;
            }
        }
        return 0;
    }

    static boolean shouldOpenLearnedRecipePage(boolean wasLearned,
                                               boolean isLearned) {
        return !wasLearned && isLearned;
    }

    private int recipePageFor(String buildingType) {
        for (int index = 0; index < inspectorRecipes.size(); index++) {
            if (inspectorRecipes.get(index).buildingType.equals(buildingType)) {
                return index;
            }
        }
        return -1;
    }

    private void selectUnlock(int index) {
        if (index < 0 || index >= inspectorUnlocks.size()) {
            return;
        }
        selectedUnlockIndex = index;
        UnlockView selected = inspectorUnlocks.get(index);
        if (inspectedNode != null && inspectedNode.learned && selected.isPlan()) {
            int recipePage = recipePageFor(selected.buildingType);
            if (recipePage >= 0) {
                inspectorPage = recipePage;
            }
        }
        updateInspectorControls();
    }

    private void updateInspectorControls() {
        inspectorPage = Math.max(0, Math.min(inspectorPageCount() - 1, inspectorPage));
        inspectorPageLine = Component.literal((inspectorPage + 1) + "/" + inspectorPageCount());
        if (inspectorPrevious != null) {
            inspectorPrevious.active = inspectorPage > 0;
            inspectorNext.active = inspectorPage + 1 < inspectorPageCount();
            inspectorPrevious.visible = inspectorNext.visible = inspectorWidth > 0;
        }
        if (learnButton != null) {
            Inspectable entry = inspected();
            learnButton.active = entry != null && entry.available();
            if (inspectedUpgrade != null) {
                learnButton.setMessage(Component.literal(actionLabel(true,
                    inspectedUpgrade.learned, inspectedUpgrade.upgrade.coinCost(),
                    inspectedUpgrade.upgrade.materialCosts().size())));
            } else if (inspectedNode != null) {
                // Coins and goods are separate lines of node.costs(); summing
                // every count turned "2 Coins + 6 goods" into "8 Coins".
                learnButton.setMessage(Component.literal(actionLabel(false,
                    inspectedNode.learned, inspectedNode.node.coinCost(),
                    inspectedNode.node.materialCosts().size())));
            }
            // A disabled action always names why: the server's refusal reason.
            learnButton.setTooltip(entry == null ? null : Tooltip.create(entry.available()
                ? entry.costText() : entry.reason()));
        }
    }

    private void renderInspector(GuiGraphics graphics, int mouseX, int mouseY) {
        recipeHover = ItemStack.EMPTY;
        inspectorTextHover = null;
        Inspectable entry = inspected();
        if (inspectorWidth <= 0 || entry == null) return;
        HsUi.taskPaper(graphics, inspectorLeft, inspectorTop, inspectorWidth, inspectorHeight,
            !entry.available() && !entry.learned(), false);
        // Every detail line is pre-fitted; the clip is a last guard so nothing
        // in the detail panel can paint past its paper at any GUI size.
        graphics.enableScissor(inspectorLeft, inspectorTop,
            inspectorLeft + inspectorWidth, inspectorTop + inspectorHeight);
        graphics.renderItem(entry.icon(), inspectorLeft + 8, inspectorTop + 8);
        graphics.drawString(font, inspectorHeadingLine, inspectorLeft + 28, inspectorTop + 8,
            HearthPixelSurface.INK, false);
        graphics.drawString(font, inspectorBadgeLine, inspectorLeft + 28, inspectorTop + 19,
            PixelBoard.ink(entry.outline()), false);
        PixelBoard.divider(graphics, inspectorLeft + 6, inspectorTop + 32, inspectorWidth - 12);
        if (inspectorPage < inspectorRecipes.size()) {
            renderPlanRecipe(graphics, inspectorRecipes.get(inspectorPage), mouseX, mouseY);
        }
        int unlockPage = inspectorPage - inspectorRecipes.size();
        if (unlockPage >= 0 && unlockPage < unlockPageCount()) {
            renderUnlockItems(graphics, unlockPage, mouseX, mouseY);
        }
        int first = (inspectorPage - inspectorRecipes.size() - unlockPageCount()) * inspectorPageSize;
        int last = Math.min(inspectorLines.size(), first + inspectorPageSize);
        int y = inspectorTop + 39;
        for (int i = Math.max(0, first); first >= 0 && i < last; i++) {
            InspectorLine line = inspectorLines.get(i);
            if (line.target > 0) {
                int barX = inspectorLeft + 8;
                int barWidth = Math.max(1, inspectorWidth - 16);
                int filled = Math.round((barWidth - 2) * Math.min(1.0F,
                    Math.max(0, line.progress) / (float)line.target));
                graphics.fill(barX, y + 1, barX + barWidth, y + 7, 0xFF655B45);
                graphics.fill(barX + 1, y + 2, barX + barWidth - 1, y + 6, 0xFFD7CFB8);
                graphics.fill(barX + 1, y + 2, barX + 1 + filled, y + 6,
                    line.progress >= line.target ? 0xFF4C852F : 0xFFC1942E);
            } else {
                graphics.drawString(font, line.text, inspectorLeft + 8, y,
                    PixelBoard.ink(line.colour), false);
            }
            y += INSPECTOR_LINE_H;
        }
        graphics.drawString(font, inspectorPageLine, inspectorLeft + 35,
            inspectorTop + inspectorHeight - 18, HearthPixelSurface.MUTED, false);
        graphics.disableScissor();
        graphics.drawString(font, availableCoinsLine, left + panelWidth - PAD - 116,
            top + 43, HearthPixelSurface.INK, false);
    }

    private void renderUnlockItems(GuiGraphics graphics, int page, int mouseX, int mouseY) {
        int x = inspectorLeft + 8;
        int y = inspectorTop + 40;
        int bottom = inspectorTop + inspectorHeight - 26;
        graphics.drawString(font, unlocksHeaderLine, x, y, HearthPixelSurface.INK, false);
        if (unlocksHeaderShortened && mouseX >= x && mouseX < inspectorLeft + inspectorWidth - 8
                && mouseY >= y && mouseY < y + INSPECTOR_LINE_H) {
            inspectorTextHover = UNLOCKS_HEADER;
        }
        int first = page * unlockRowsPerPage();
        int end = Math.min(inspectorUnlocks.size(), first + unlockRowsPerPage());
        y += 16;
        for (int i = first; i < end; i++, y += UNLOCK_ROW_H) {
            UnlockView unlock = inspectorUnlocks.get(i);
            boolean selected = i == selectedUnlockIndex;
            graphics.fill(x, y, inspectorLeft + inspectorWidth - 8, y + UNLOCK_ROW_H - 2,
                selected ? 0x66845A31 : 0x447E6A43);
            if (selected) {
                graphics.fill(x, y, x + 2, y + UNLOCK_ROW_H - 2, HearthPixelSurface.COPPER);
            }
            renderRecipeSlot(graphics, unlock.icon, x + 3, y + 3, mouseX, mouseY);
            graphics.drawString(font, clipLine(font, unlock.name, inspectorWidth - 46),
                x + 26, y + 3, HearthPixelSurface.INK, false);
            graphics.drawString(font, clipLine(font, unlock.benefit, inspectorWidth - 46),
                x + 26, y + 13, HearthPixelSurface.MUTED, false);
            if (mouseX >= x && mouseX < inspectorLeft + inspectorWidth - 8
                    && mouseY >= y && mouseY < y + UNLOCK_ROW_H - 2) recipeHover = unlock.icon;
        }
        // Keep the decision facts visible with the unlock, rather than hiding
        // price and prerequisites on a later inspector page.
        int summaryY = y + 3;
        for (FormattedCharSequence costLine : inspectorCostLines) {
            if (summaryY >= bottom) break;
            graphics.drawString(font, costLine, x, summaryY, HearthPixelSurface.INK, false);
            summaryY += INSPECTOR_LINE_H;
        }
        if (summaryY < bottom) {
            graphics.drawString(font, availableCoinsLine, x, summaryY,
                HearthPixelSurface.INK, false);
            summaryY += INSPECTOR_LINE_H;
        }
        int summaryCount = Math.min(inspectorLines.size(), Math.max(0, (bottom - summaryY) / INSPECTOR_LINE_H));
        for (int i = 0; i < summaryCount; i++) {
            InspectorLine line = inspectorLines.get(i);
            if (line.target > 0) {
                int barWidth = Math.max(1, inspectorWidth - 16);
                int filled = Math.round((barWidth - 2) * Math.min(1.0F,
                    Math.max(0, line.progress) / (float) line.target));
                graphics.fill(x, summaryY + 1, x + barWidth, summaryY + 7, 0xFF655B45);
                graphics.fill(x + 1, summaryY + 2, x + barWidth - 1, summaryY + 6, 0xFFD7CFB8);
                graphics.fill(x + 1, summaryY + 2, x + 1 + filled, summaryY + 6,
                    line.progress >= line.target ? 0xFF4C852F : 0xFFC1942E);
            } else {
                graphics.drawString(font, line.text, x, summaryY, PixelBoard.ink(line.colour), false);
            }
            summaryY += INSPECTOR_LINE_H;
        }
    }
    private void renderPlanRecipe(GuiGraphics graphics, PlanRecipe recipe, int mouseX, int mouseY) {
        int availableHeight = inspectorHeight - 66;
        if (availableHeight <= 0) return;
        // The natural recipe footprint is 98 x 94 with its explicit craft
        // heading. At compact GUI sizes
        // shrink the complete diagram above the pager; never overlap controls.
        float scale = Math.min(1F, Math.min((inspectorWidth - 16) / 98F,
            availableHeight / 94F));
        int originX = inspectorLeft + 8;
        int originY = inspectorTop + 40;
        int localMouseX = (int)Math.floor((mouseX - originX) / scale);
        int localMouseY = (int)Math.floor((mouseY - originY) / scale);
        graphics.enableScissor(inspectorLeft + 6, originY,
            inspectorLeft + inspectorWidth - 6, inspectorTop + inspectorHeight - 26);
        graphics.pose().pushPose();
        graphics.pose().translate(originX, originY, 0);
        graphics.pose().scale(scale, scale, 1F);
        int x = 0;
        int y = 0;
        graphics.drawString(font, CRAFT_PLAN_HEADER, x, y, HearthPixelSurface.INK, false);
        graphics.drawString(font, clipLine(font, recipe.output.getHoverName(),
                (int)((inspectorWidth - 16) / scale)),
            x, y + 11, HearthPixelSurface.INK, false);
        graphics.drawString(font, recipe.shaped ? "Crafting Table" : "Any order (3 x 3)",
            x, y + 22, HearthPixelSurface.MUTED, false);
        y += 38;
        long cycle = minecraft.level == null ? 0 : minecraft.level.getGameTime() / 30;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack[] alternatives = recipe.cells.get(slot).getItems();
            ItemStack stack = alternatives.length == 0 ? ItemStack.EMPTY
                : alternatives[(int)(cycle % alternatives.length)];
            renderRecipeSlot(graphics, stack, x + (slot % 3) * 19, y + (slot / 3) * 19, localMouseX, localMouseY);
        }
        int resultX = x + 80;
        int resultY = y + 19;
        graphics.drawString(font, "→", x + 62, y + 24, HearthPixelSurface.INK, false);
        renderRecipeSlot(graphics, recipe.output, resultX, resultY, localMouseX, localMouseY);
        graphics.pose().popPose();
        graphics.disableScissor();
    }

    private void renderRecipeSlot(GuiGraphics graphics, ItemStack stack, int x, int y, int mouseX, int mouseY) {
        graphics.fill(x, y, x + 18, y + 18, 0xFF76684F);
        graphics.fill(x + 1, y + 1, x + 17, y + 17, 0xFFD8C9A9);
        if (stack.isEmpty()) return;
        graphics.renderItem(stack, x + 1, y + 1);
        graphics.renderItemDecorations(font, stack, x + 1, y + 1);
        if (mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18) recipeHover = stack;
    }

    private CachedNode nextUnfinishedNode() {
        for (CachedNode node : cached) if (node.available && !node.learned) return node;
        for (CachedNode node : cached) if (!node.learned) return node;
        return cached[0];
    }

    private void centerInspectedNode() {
        Inspectable entry = inspected();
        if (entry == null) return;
        if (zoomIndex < 0) zoomIndex = DEFAULT_ZOOM_INDEX;
        centerOn(entryX(entry), entryY(entry));
    }

    static BoardLayout layoutFor(int viewportWidth, int viewportHeight) {
        int pw = Math.min(PANEL_W, Math.max(1, viewportWidth - 16));
        int ph = Math.min(PANEL_H, Math.max(1, viewportHeight - 16));
        int content = Math.max(1, pw - 2 * VIEW_X);
        int inspector = content >= 220
            ? Math.min(INSPECTOR_MAX_W, Math.max(INSPECTOR_MIN_W, content / 3)) : 0;
        int view = inspector > 0 ? content - inspector - INSPECTOR_GAP : content;
        return new BoardLayout(pw, ph, view, Math.max(1, ph - VIEW_Y - FOOTER_RESERVED), inspector);
    }

    record BoardLayout(int panelWidth, int panelHeight, int viewWidth,
                       int viewHeight, int inspectorWidth) {
    }

    /** First Raid milestone ring plus the four direction headers. */
    private void drawHubGuides(GuiGraphics graphics) {
        float z = zoom();
        int cx = screenX(0, z);
        int cy = screenY(0, z);
        float radius = RAID_RING_RADIUS * z;
        int dot = z < MAP_ZOOM_LIMIT ? 1 : 2;
        int dots = Math.max(24, Math.round((float) (2 * Math.PI * radius / 7.0F)));
        for (int i = 0; i < dots; i++) {
            double angle = 2 * Math.PI * i / dots;
            int x = cx + (int) Math.round(Math.sin(angle) * radius);
            int y = cy - (int) Math.round(Math.cos(angle) * radius);
            graphics.fill(x, y, x + dot, y + dot, 0xFFB9AF95);
        }
        drawDirectionHeader(graphics, DIRECTION_WATCH, 0, HUB_MIN_Y + 10, 0);
        drawDirectionHeader(graphics, DIRECTION_HEARTH, 0, HUB_MAX_Y - 10, 0);
        drawDirectionHeader(graphics, DIRECTION_CRAFT, HUB_MIN_X, 0, -1);
        drawDirectionHeader(graphics, DIRECTION_LOGISTICS, HUB_MAX_X - 40, 0, 1);
    }

    /** Same ruled heading as the original lane labels; align -1 right, 0 centre, 1 left. */
    private void drawDirectionHeader(GuiGraphics graphics, Component label,
                                     int worldX, int worldY, int align) {
        int width = font.width(label);
        int x = screenX(worldX, zoom());
        int y = screenY(worldY, zoom());
        x = align < 0 ? x - width : align == 0 ? x - width / 2 : x;
        if (zoomIndex < 0) {
            // Whole-hub view: keep every heading readable inside the viewport.
            x = Math.max(viewClipLeft() + 2, Math.min(viewClipRight() - 2 - width, x));
            y = Math.max(viewClipTop() + 8, Math.min(viewClipBottom() - 6, y));
        }
        graphics.fill(x, y + 4, x + width, y + 5, 0xFFB9AF95);
        graphics.drawString(font, label, x, y - 6, 0xFF746A56, false);
    }

    private void drawEdges(GuiGraphics graphics) {
        for (DevelopmentNode[] edge : EDGES) {
            CachedNode source = cached[edge[0].ordinal()];
            CachedNode target = cached[edge[1].ordinal()];
            int colour = edgeColour(source, target);
            if (edge[0] == DevelopmentNode.FIRST_RAID_AFTERMATH
                && !northArm(worldX(edge[1]), worldY(edge[1]))) {
                // Post-raid steps in other directions pass through the raid ring
                // instead of a line across the whole hub.
                drawRaidGate(graphics, worldX(edge[1]), worldY(edge[1]), colour);
                continue;
            }
            int ax = worldX(edge[0]);
            int ay = worldY(edge[0]);
            int bx = worldX(edge[1]);
            int by = worldY(edge[1]);
            if (edge[0] == DevelopmentNode.TIMBER_RIGHTS
                && edge[1] == DevelopmentNode.STORES_AND_ROADS) {
                // Lumber feeds the Warehouse across the hub: route above the centre.
                drawRoute(graphics, colour, ax, ay, ax, -150, bx, -150, bx, by);
            } else if (edge[0] == DevelopmentNode.STORES_AND_ROADS
                && (edge[1] == DevelopmentNode.CULTIVATED_GROUND
                    || edge[1] == DevelopmentNode.SHORE_PROVISIONS)) {
                // Warehouse-first food loops: route below the centre to the west arm.
                drawRoute(graphics, colour, ax, ay, ax, 170, -340, 170, -340, by, bx, by);
            } else {
                drawElbow(graphics, colour, ax, ay, bx, by, false);
            }
        }
        for (CachedUpgrade target : cachedUpgrades) {
            PostRaidUpgrade upgrade = target.upgrade;
            int bx = upgradeX(upgrade);
            int by = upgradeY(upgrade);
            PostRaidUpgrade chain = upgrade.requiresUpgrade();
            if (chain != null) {
                drawElbow(graphics, edgeColour(cachedUpgrades[chain.ordinal()], target),
                    upgradeX(chain), upgradeY(chain), bx, by, true);
            }
            CachedNode source = cached[upgrade.requires().ordinal()];
            int colour = edgeColour(source, target);
            if (upgrade.requires() == DevelopmentNode.FIRST_RAID_AFTERMATH
                && !northArm(bx, by)) {
                if (chain == null || !chain.postRaid()) {
                    drawRaidGate(graphics, bx, by, colour);
                }
                continue;
            }
            drawElbow(graphics, colour, worldX(upgrade.requires()),
                worldY(upgrade.requires()), bx, by, true);
        }
    }

    private static boolean northArm(int x, int y) {
        return y < 0 && Math.abs(x) <= Math.abs(y);
    }

    /** A short stub from a post-raid card to the raid ring, marked where it crosses. */
    private void drawRaidGate(GuiGraphics graphics, int x, int y, int colour) {
        int ringX;
        int ringY;
        if (Math.abs(x) >= Math.abs(y)) {
            ringY = y;
            ringX = (int) Math.round(Math.signum(x) * Math.sqrt(Math.max(0,
                RAID_RING_RADIUS * RAID_RING_RADIUS - y * y)));
        } else {
            ringX = x;
            ringY = (int) Math.round(Math.signum(y) * Math.sqrt(Math.max(0,
                RAID_RING_RADIUS * RAID_RING_RADIUS - x * x)));
        }
        drawRoute(graphics, colour, x, y, ringX, ringY);
        float z = zoom();
        int sx = screenX(ringX, z) + 1;
        int sy = screenY(ringY, z) + 1;
        int r = z < MAP_ZOOM_LIMIT ? 1 : 3;
        graphics.fill(sx - r, sy - 1, sx + r, sy + 1, colour);
        graphics.fill(sx - 1, sy - r, sx + 1, sy + r, colour);
    }

    /**
     * Orthogonal elbow between two card centres; the cards are drawn on top,
     * so only the visible run between them shows (same 2px rule as before).
     */
    private void drawElbow(GuiGraphics graphics, int colour, int ax, int ay,
                           int bx, int by, boolean verticalFirst) {
        if (ax == bx || ay == by) {
            drawRoute(graphics, colour, ax, ay, bx, by);
        } else if (!verticalFirst && Math.abs(bx - ax) > Math.abs(by - ay)) {
            int midX = (ax + bx) / 2;
            drawRoute(graphics, colour, ax, ay, midX, ay, midX, by, bx, by);
        } else {
            int midY = (ay + by) / 2;
            drawRoute(graphics, colour, ax, ay, ax, midY, bx, midY, bx, by);
        }
    }

    /** Axis-aligned polyline through world points (x0, y0, x1, y1, ...). */
    private void drawRoute(GuiGraphics graphics, int colour, int... points) {
        float z = zoom();
        for (int i = 0; i + 3 < points.length; i += 2) {
            int x1 = screenX(points[i], z);
            int y1 = screenY(points[i + 1], z);
            int x2 = screenX(points[i + 2], z);
            int y2 = screenY(points[i + 3], z);
            if (y1 == y2) {
                lineH(graphics, x1, x2, y1, colour);
            } else if (x1 == x2) {
                lineV(graphics, x1, y1, y2, colour);
            } else {
                lineH(graphics, x1, x2, y1, colour);
                lineV(graphics, x2, y1, y2, colour);
            }
        }
    }

    private static int edgeColour(Inspectable source, Inspectable target) {
        boolean learned = source.learned() && target.learned();
        return learned ? PixelBoard.GOOD
            : target.available() ? PixelBoard.ACCENT : 0xFF8D9784;
    }

    private static void lineH(GuiGraphics graphics, int x1, int x2, int y, int colour) {
        graphics.fill(Math.min(x1, x2), y, Math.max(x1, x2) + 1, y + 2, colour);
    }

    private static void lineV(GuiGraphics graphics, int x, int y1, int y2, int colour) {
        graphics.fill(x, Math.min(y1, y2), x + 2, Math.max(y1, y2) + 1, colour);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button == 0) {
            int unlock = unlockIndexAt(mouseX, mouseY);
            if (unlock >= 0) {
                selectUnlock(unlock);
                return true;
            }
        }
        if (button == 0 && overViewport(mouseX, mouseY)) {
            QaClientObserver.markUiTransition("development_pan_drag");
            draggingMap = true;
            lastDragX = mouseX;
            lastDragY = mouseY;
            return true;
        }
        return false;
    }

    private int unlockIndexAt(double mouseX, double mouseY) {
        int unlockPage = inspectorPage - inspectorRecipes.size();
        if (unlockPage < 0 || unlockPage >= unlockPageCount()) {
            return -1;
        }
        int first = unlockPage * unlockRowsPerPage();
        int row = (int) ((mouseY - (inspectorTop + 56)) / UNLOCK_ROW_H);
        int index = first + row;
        return row >= 0 && row < unlockRowsPerPage() && index < inspectorUnlocks.size()
            && mouseX >= inspectorLeft + 8 && mouseX < inspectorLeft + inspectorWidth - 8
            && mouseY < inspectorTop + 56 + unlockRowsPerPage() * UNLOCK_ROW_H ? index : -1;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                double dragX, double dragY) {
        if (draggingMap && button == 0) {
            float zoom = zoom();
            panX -= Math.round((float) (mouseX - lastDragX) / zoom);
            panY -= Math.round((float) (mouseY - lastDragY) / zoom);
            lastDragX = mouseX;
            lastDragY = mouseY;
            clampPan();
            relayoutNodeControls();
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingMap = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (mouseX >= inspectorLeft && mouseX < inspectorLeft + inspectorWidth
            && mouseY >= inspectorTop && mouseY < inspectorTop + inspectorHeight) {
            if (dy != 0) changeInspectorPage(dy > 0 ? -1 : 1);
            return true;
        }
        if (overViewport(mouseX, mouseY)) {
            if (hasControlDown()) {
                zoom(dy > 0 ? 1 : -1);
            } else {
                QaClientObserver.markUiTransition("development_pan_wheel");
                panX += (int) Math.round(-dy * 72.0D / zoom());
                clampPan();
                relayoutNodeControls();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, dx, dy);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 266 || keyCode == 267) { // Page Up / Page Down: inspect each phase
            if (hasControlDown()) {
                changeInspectorPage(keyCode == 266 ? -1 : 1);
            } else if (!focusOrder.isEmpty()) {
                // Steps through the hub centre outward, in the same order as Tab.
                Inspectable entry = inspected();
                int current = 0;
                for (int i = 0; i < focusOrder.size(); i++) {
                    if (focusOrder.get(i).node == entry) current = i;
                }
                int next = Math.max(0, Math.min(focusOrder.size() - 1,
                    current + (keyCode == 266 ? -1 : 1)));
                TechNodeButton target = focusOrder.get(next);
                setInspected(target.node);
                centerInspectedNode();
                relayoutNodeControls();
                setFocused(target);
            }
            return true;
        }
        if (keyCode == 82) { // R
            refresh();
            return true;
        }
        if (keyCode == 268) { // Home
            resetView();
            return true;
        }
        if (keyCode == 334 || keyCode == 61) { // numpad+/+
            zoom(1);
            return true;
        }
        if (keyCode == 333 || keyCode == 45) { // numpad-/-
            zoom(-1);
            return true;
        }
        int step = Math.round(48.0F / zoom());
        boolean moved = switch (keyCode) {
            case 263 -> { panX -= step; yield true; } // left
            case 262 -> { panX += step; yield true; } // right
            case 265 -> { panY -= step; yield true; } // up
            case 264 -> { panY += step; yield true; } // down
            default -> false;
        };
        if (moved) {
            QaClientObserver.markUiTransition("development_pan_key");
            clampPan();
            relayoutNodeControls();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** The viewport centre may travel anywhere inside the hub, never past it. */
    private void clampPan() {
        float z = zoom();
        int halfW = Math.round(viewWidth / (2 * z));
        int halfH = Math.round(viewHeight / (2 * z));
        int centreX = Math.max(HUB_MIN_X, Math.min(HUB_MAX_X, panX + halfW));
        int centreY = Math.max(HUB_MIN_Y, Math.min(HUB_MAX_Y, panY + halfH));
        panX = centreX - halfW;
        panY = centreY - halfH;
    }

    @Override
    public String qaUiState() {
        int visibleNodes = 0;
        for (TechNodeButton button : focusOrder) {
            if (button.visible) {
                visibleNodes++;
            }
        }
        String inspected = inspectedUpgrade != null
            ? "upgrade:" + inspectedUpgrade.upgrade.id()
            : inspectedNode == null ? "none" : inspectedNode.node.id();
        return "inspected=" + inspected + ",inspector=" + inspectorLeft + ":"
            + inspectorTop + ":" + inspectorWidth + ":" + inspectorHeight
            + ",inspectorLines=" + inspectorLines.size()
            + ",inspectorPage=" + (inspectorPage + 1) + "/" + inspectorPageCount()
            + ",viewport="
            + (left + VIEW_X) + ":" + (top + VIEW_Y) + ":" + viewWidth + ":"
            + viewHeight + ",nodes=" + visibleNodes + "/" + focusOrder.size()
            + ",pan=" + panX + ":" + panY + ",zoom=" + zoomIndex
            + ",dragging=" + draggingMap;
    }

    private boolean overViewport(double x, double y) {
        return x >= left + VIEW_X && x < left + VIEW_X + viewWidth
            && y >= top + VIEW_Y && y < top + VIEW_Y + viewHeight;
    }

    /*
     * Hub layout (card centres, world units, negative y is up).
     * Centre: Charter above First Fire. N = Watch & Defense, S = Hearth &
     * Household, W = Craft & Trade, E = Logistics. Ring 1 at ~240, ring 2 at
     * ~400-440, the First Raid milestone on the dotted ring at 560, and the
     * post-raid outer ring from ~680 out. FUTURE placeholders are parked on
     * the far outer ring. Spacing keeps every card apart at 30% and 42%.
     */
    static int worldX(DevelopmentNode node) {
        return switch (node) {
            case SETTLEMENT_CHARTER, SHELTER -> 0;
            case FIRST_WATCH, ARM_THE_WATCH, FIRST_RAID_AFTERMATH, SHIELD_DOCTRINE -> 0;
            case BORDER_WARDENS -> -100;
            case FORTIFICATION -> 100;
            case HOME, HOSPITALITY, HEARTH_DOCTRINE, HALL_AND_LEARNING -> 0;
            case TIMBER_RIGHTS -> -240;
            case CULTIVATED_GROUND, SHORE_PROVISIONS -> -440;
            case GUILD_DOCTRINE -> -720;
            case LAND_AND_HARVEST, CRAFT_AND_INDUSTRY -> -900;
            case STORES_AND_ROADS -> 240;
        };
    }

    static int worldY(DevelopmentNode node) {
        return switch (node) {
            case SETTLEMENT_CHARTER -> -55;
            case SHELTER -> 55;
            case FIRST_WATCH -> -240;
            case ARM_THE_WATCH -> -400;
            case FIRST_RAID_AFTERMATH -> -RAID_RING_RADIUS;
            case SHIELD_DOCTRINE -> -720;
            case BORDER_WARDENS, FORTIFICATION -> -880;
            case HOME -> 240;
            case HOSPITALITY -> 400;
            case HEARTH_DOCTRINE -> 720;
            case HALL_AND_LEARNING -> 880;
            case TIMBER_RIGHTS, STORES_AND_ROADS, GUILD_DOCTRINE -> 0;
            case CULTIVATED_GROUND, LAND_AND_HARVEST -> -60;
            case SHORE_PROVISIONS, CRAFT_AND_INDUSTRY -> 60;
        };
    }

    /** Bonus cards sit beside the job or house they improve. */
    static int upgradeX(PostRaidUpgrade upgrade) {
        return switch (upgrade) {
            case GUARD_DRILL, ARCHER_LONGBOW_DRILL -> -170;
            case GUARD_ARMS_IRON, WARM_HEARTH, STURDY_BEDS, FEATHER_QUILTS -> 170;
            case SHARPENED_AXES -> -240;
            case FISHERS_NETS -> -440;
            case STOUT_STRAPS -> 420;
            case COURIER_SATCHEL, WORKER_PACKS -> 680;
            case HAND_CART, LEATHER_PACK -> 860;
            case FRAME_PACK -> 1040;
            case PAVED_ROADS, SWIFT_COURIERS -> 420;
            case WAREHOUSE_RACKS -> 600;
            case GREAT_STOREHOUSE -> 780;
            case ROYAL_STOREHOUSE -> 960;
            // Builder lane: beside the Watch side of the map.
            case DEFENSE_PLANS -> -340;
            case MASONRY -> -520;
        };
    }

    static int upgradeY(PostRaidUpgrade upgrade) {
        return switch (upgrade) {
            case GUARD_DRILL -> -240;
            case GUARD_ARMS_IRON, ARCHER_LONGBOW_DRILL -> -720;
            case WARM_HEARTH -> 240;
            case STURDY_BEDS -> 400;
            case FEATHER_QUILTS -> 720;
            case SHARPENED_AXES -> 110;
            case FISHERS_NETS -> 180;
            case STOUT_STRAPS, COURIER_SATCHEL, HAND_CART -> 0;
            case WORKER_PACKS -> 120;
            case LEATHER_PACK, FRAME_PACK -> -120;
            case PAVED_ROADS -> 150;
            case SWIFT_COURIERS -> -150;
            case WAREHOUSE_RACKS, GREAT_STOREHOUSE, ROYAL_STOREHOUSE -> 260;
            case DEFENSE_PLANS, MASONRY -> -400;
        };
    }

    /** A recognisable item for each bonus card (display only). */
    private static net.minecraft.world.item.Item upgradeIcon(PostRaidUpgrade upgrade) {
        return switch (upgrade) {
            case COURIER_SATCHEL -> net.minecraft.world.item.Items.LEATHER;
            case HAND_CART -> net.minecraft.world.item.Items.MINECART;
            case WORKER_PACKS -> net.minecraft.world.item.Items.BARREL;
            case GUARD_ARMS_IRON -> net.minecraft.world.item.Items.IRON_CHESTPLATE;
            case ARCHER_LONGBOW_DRILL -> net.minecraft.world.item.Items.BOW;
            case WARM_HEARTH -> net.minecraft.world.item.Items.CAMPFIRE;
            case STURDY_BEDS -> net.minecraft.world.item.Items.RED_BED;
            case FEATHER_QUILTS -> net.minecraft.world.item.Items.FEATHER;
            case SHARPENED_AXES -> net.minecraft.world.item.Items.IRON_AXE;
            case FISHERS_NETS -> net.minecraft.world.item.Items.FISHING_ROD;
            case STOUT_STRAPS -> net.minecraft.world.item.Items.LEAD;
            case GUARD_DRILL -> net.minecraft.world.item.Items.IRON_SWORD;
            case LEATHER_PACK -> net.minecraft.world.item.Items.BUNDLE;
            case FRAME_PACK -> net.minecraft.world.item.Items.CHEST;
            case PAVED_ROADS -> net.minecraft.world.item.Items.DIRT_PATH;
            case SWIFT_COURIERS -> net.minecraft.world.item.Items.LEATHER_BOOTS;
            case WAREHOUSE_RACKS -> net.minecraft.world.item.Items.BARREL;
            case GREAT_STOREHOUSE -> net.minecraft.world.item.Items.CHEST;
            case ROYAL_STOREHOUSE -> net.minecraft.world.item.Items.ENDER_CHEST;
            case DEFENSE_PLANS -> net.minecraft.world.item.Items.OAK_FENCE;
            case MASONRY -> net.minecraft.world.item.Items.STONE_BRICKS;
        };
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record InspectorSection(Component text, int colour, int progress, int target) {
        InspectorSection(Component text, int colour) { this(text, colour, -1, 0); }
    }

    private record InspectorLine(FormattedCharSequence text, int colour, int progress, int target) {
    }

    /** What a map card and the inspector need, for a phase node or a bonus. */
    private interface Inspectable {
        Component name();
        Component overviewName();
        Component stateBadge();
        Component costText();
        Component tooltip();
        /** Why the Learn/Buy action is unavailable (or the action name when it is). */
        Component reason();
        List<InspectorSection> details();
        ItemStack icon();
        boolean available();
        boolean learned();
        int outline();
    }

    private record CachedNode(DevelopmentNode node, Development.NodeStatus status,
                              Component name, Component overviewName, Component description,
                              Component questText, Component stateText, Component stateBadge,
                               Component costText, Component tooltip, List<InspectorSection> details,
                               ItemStack icon,
                              boolean available, boolean learned, int outline,
                              Component reason) implements Inspectable {
        static CachedNode create(DevelopmentNode node, Development.NodeStatus status,
                                 String reasonKey,
                                 List<DevelopmentSnapshotPayload.QuestView> quests,
                                 Map<Integer, DevelopmentSnapshotPayload.NodeView> views,
                                 int availableCoins) {
            Component name = node.displayName();
            Component overviewName = overviewLabel(node);
            Component description = node.description();
            Component cost = costLine(node.costs());
            MutableComponent quest = compactQuestLine(node, status, quests);
            Component state = Component.translatable("hearthstead.development.status."
                + status.name().toLowerCase(java.util.Locale.ROOT));
            boolean available = reasonKey == null || reasonKey.isEmpty();
            boolean learned = status == Development.NodeStatus.ACTIVE
                || status == Development.NodeStatus.OWNED
                || status == Development.NodeStatus.DORMANT;
            Component reason = available
                ? Component.translatable("hearthstead.development.action.learn")
                : Component.translatable(reasonKey);
            MutableComponent tooltip = name.copy().append("\n").append(description);
            List<InspectorSection> details = new ArrayList<>();
            List<InspectorSection> prerequisiteDetails = new ArrayList<>();
            for (String prerequisite : node.prerequisites()) {
                DevelopmentNode required = DevelopmentNode.byId(prerequisite);
                if (required != null) {
                    Component dependency = Component.translatable(
                        "hearthstead.development.detail.prerequisite", required.displayName());
                    tooltip.append("\n").append(dependency);
                    var requiredView = views.get(required.wireId());
                    var requiredStatus = requiredView == null ? Development.NodeStatus.QUARANTINED
                        : Development.NodeStatus.fromWireId(requiredView.statusWireId());
                    boolean met = requiredStatus == Development.NodeStatus.OWNED
                        || requiredStatus == Development.NodeStatus.ACTIVE
                        || requiredStatus == Development.NodeStatus.DORMANT;
                    prerequisiteDetails.add(new InspectorSection(Component.literal(met ? "Done: " : "Need: ")
                        .append(dependency), met ? HsUiTokens.GOOD : HsUiTokens.WARN));
                }
            }
            List<InspectorSection> questDetails = new ArrayList<>();
            for (DevelopmentSnapshotPayload.QuestView view : quests) {
                DevelopmentObjective objective = DevelopmentObjective.byWireId(
                    view.objectiveWireId());
                if (objective != null) {
                    Component progress = objective.progressText(
                        view.progress(), view.target());
                    boolean complete = view.target() > 0 && view.progress() >= view.target();
                    questDetails.add(new InspectorSection(progress,
                        complete ? HsUiTokens.GOOD : HsUiTokens.WARN,
                        Math.max(0, view.progress()), Math.max(1, view.target())));
                    if (objective == DevelopmentObjective.LUMBER_LOGS_STORED
                        || objective == DevelopmentObjective.FARM_CROPS_STORED
                        || objective == DevelopmentObjective.PRODUCTIVE_GOODS_MOVED) {
                        questDetails.add(new InspectorSection(Component.literal("Progress is kept when goods are used."),
                            HsUiTokens.TEXT_MUTED));
                    }
                }
            }
            boolean repeatsSpecificPrerequisite = "hearthstead.development.blocked.prerequisite".equals(reasonKey)
                && !prerequisiteDetails.isEmpty();
            boolean repeatsSpecificQuest = "hearthstead.development.blocked.quest".equals(reasonKey)
                && !questDetails.isEmpty();
            details.add(new InspectorSection(Component.literal("REQUIREMENTS"), HsUiTokens.ACCENT));
            if (!available && !repeatsSpecificPrerequisite && !repeatsSpecificQuest) {
                details.add(new InspectorSection(reason, HsUiTokens.WARN));
            } else if (available && node.prerequisites().isEmpty() && quests.isEmpty()) {
                details.add(new InspectorSection(Component.literal("Ready now"), HsUiTokens.GOOD));
            }
            details.addAll(prerequisiteDetails);
            details.addAll(questDetails);
            details.add(new InspectorSection(Component.literal("COST"), HsUiTokens.ACCENT));
            if (node.costs().isEmpty()) {
                details.add(new InspectorSection(cost, HsUiTokens.ACCENT));
            }
            // One row per charged line (Coins first, then each good), exactly
            // the list Development.learn pays from.
            for (DevelopmentNode.Cost row : node.costs()) {
                details.add(new InspectorSection(Component.translatable(
                    "hearthstead.development.cost.line", row.count(), row.displayName()), 0));
            }
            int coinCost = node.coinCost();
            if (coinCost > 0) {
                if (availableCoins >= 0) {
                    boolean funded = availableCoins >= coinCost;
                    Component wallet = Component.literal("Coins: " + availableCoins + " / " + coinCost
                        + (funded ? " - Ready" : " - Need " + (coinCost - availableCoins)));
                    details.add(new InspectorSection(wallet, funded ? HsUiTokens.GOOD : HsUiTokens.WARN,
                        availableCoins, coinCost));
                } else {
                    details.add(new InspectorSection(Component.literal("Coins: awaiting server"), HsUiTokens.TEXT_MUTED));
                }
                details.add(new InspectorSection(Component.literal("Paid only when you learn this."), HsUiTokens.TEXT_MUTED));
            }
            for (var building : node.knowledge().buildPlans()) {
                Component plan = BuildingDescription.shortDescription(building);
                tooltip.append("\n").append(plan);
            }
            for (var profession : node.knowledge().jobEmblems()) {
                Component emblem = Component.translatable("hearthstead.development.detail.emblem",
                    profession.displayName());
                tooltip.append("\n").append(emblem);
            }
            details.add(new InspectorSection(Component.literal("ABOUT"), HsUiTokens.ACCENT));
            details.add(new InspectorSection(description, HsUiTokens.TEXT_MUTED));
            tooltip.append("\n").append(cost).append("\n").append(reason);

            if (node.doctrine()) {
                tooltip.append("\n").append(node.tradeoff());
                details.add(new InspectorSection(Component.literal("DOCTRINE"), HsUiTokens.ACCENT));
                details.add(new InspectorSection(node.tradeoff(), HsUiTokens.WARN));
            }
            ItemStack icon = node.buildings().isEmpty()
                ? new ItemStack(node.costs().isEmpty()
                    ? net.minecraft.world.item.Items.BOOK : node.costs().get(0).item())
                : new ItemStack(node.buildings().get(0).emblem());
            Component badge = Component.literal(learned ? "Learned" : available ? "Ready"
                : status == Development.NodeStatus.QUARANTINED ? "Blocked"
                : "Needs unlocks");
            return new CachedNode(node, status, name, overviewName, description, quest, state, badge, cost,
                tooltip, List.copyOf(details), icon, available, learned, outline(status), reason);
        }

        private static Component overviewLabel(DevelopmentNode node) {
            return Component.literal(switch (node) {
                case SETTLEMENT_CHARTER -> "Charter";
                case SHELTER -> "First Fire";
                case TIMBER_RIGHTS -> "Lumber";
                case STORES_AND_ROADS -> "Warehouse";
                case CULTIVATED_GROUND -> "Fields";
                case SHORE_PROVISIONS -> "Fishery";
                case HOME -> "Houses";
                case HOSPITALITY -> "Tavern";
                case FIRST_WATCH -> "Barracks";
                case ARM_THE_WATCH -> "Archers";
                case FIRST_RAID_AFTERMATH -> "First Raid";
                case SHIELD_DOCTRINE -> "Shield Drill";
                case GUILD_DOCTRINE -> "Sawmill";
                case HEARTH_DOCTRINE -> "Scholar";
                case FORTIFICATION -> "Fortify";
                case BORDER_WARDENS -> "Wardens";
                case LAND_AND_HARVEST -> "Harvest";
                case CRAFT_AND_INDUSTRY -> "Industry";
                case HALL_AND_LEARNING -> "Learning";
            });
        }

        private static MutableComponent compactQuestLine(DevelopmentNode node,
                Development.NodeStatus status,
                List<DevelopmentSnapshotPayload.QuestView> quests) {
            if (quests.isEmpty()) {
                // Only a FUTURE node is "planned"; a learnable node without a
                // quest (the specializations) just costs its price.
                return Component.translatable(node == DevelopmentNode.SETTLEMENT_CHARTER
                    ? "hearthstead.development.quest.free_tutorial"
                    : status == Development.NodeStatus.FUTURE
                        ? "hearthstead.development.quest.planned"
                        : "hearthstead.development.quest.none");
            }
            StringBuilder progress = new StringBuilder();
            for (DevelopmentSnapshotPayload.QuestView view : quests) {
                if (!progress.isEmpty()) {
                    progress.append(" + ");
                }
                DevelopmentObjective objective = DevelopmentObjective.byWireId(
                    view.objectiveWireId());
                if (objective == DevelopmentObjective.ALL_HOUSED_TICKS) {
                    progress.append(Math.max(0, view.progress()) / 1_200)
                        .append('/').append(Math.max(1, view.target()) / 1_200)
                        .append("m");
                } else {
                    progress.append(Math.max(0, view.progress())).append('/')
                        .append(Math.max(1, view.target()));
                }
            }
            return Component.translatable("hearthstead.development.quest.compact",
                progress.toString());
        }

        private static Component costLine(List<DevelopmentNode.Cost> costs) {
            if (costs.isEmpty()) {
                return Component.translatable("hearthstead.development.cost.none");
            }
            MutableComponent line = Component.translatable("hearthstead.development.cost.prefix");
            for (int i = 0; i < costs.size(); i++) {
                DevelopmentNode.Cost cost = costs.get(i);
                if (i > 0) {
                    line.append(Component.literal(" + "));
                }
                line.append(Component.translatable("hearthstead.development.cost.line",
                    cost.count(), cost.displayName()));
            }
            return line;
        }

        private static int outline(Development.NodeStatus status) {
            return switch (status) {
                case ACTIVE, AVAILABLE -> HsUiTokens.GOOD;
                case OWNED -> HsUiTokens.GOOD;
                case DORMANT -> 0xFF7793B8;
                case FUTURE -> HsUiTokens.WARN;
                case QUARANTINED -> HsUiTokens.BAD;
                case LOCKED -> HsUiTokens.WARN;
            };
        }
    }

    /**
     * One bonus ({@link PostRaidUpgrade}) projected from the snapshot's
     * upgrade rows. Built only when a snapshot arrives, like {@link CachedNode}.
     */
    private record CachedUpgrade(PostRaidUpgrade upgrade, Development.NodeStatus status,
                                 Component name, Component overviewName, Component stateBadge,
                                 Component costText, Component tooltip, Component reason,
                                 List<InspectorSection> details, ItemStack icon,
                                 boolean available, boolean learned, int outline)
            implements Inspectable {
        static CachedUpgrade create(PostRaidUpgrade upgrade,
                                    DevelopmentSnapshotPayload.UpgradeView view,
                                    Map<Integer, DevelopmentSnapshotPayload.NodeView> nodeViews,
                                    Map<Integer, DevelopmentSnapshotPayload.UpgradeView> upgradeViews,
                                    int availableCoins) {
            Development.NodeStatus status = view == null ? Development.NodeStatus.LOCKED
                : Development.NodeStatus.fromWireId(view.statusWireId());
            String reasonKey = view == null
                ? "hearthstead.development.blocked.quarantined" : view.reasonKey();
            boolean learned = status == Development.NodeStatus.OWNED;
            boolean available = !learned && status == Development.NodeStatus.AVAILABLE
                && reasonKey.isEmpty();
            Component name = upgrade.displayName();
            Component description = upgrade.description();
            Component cost = CachedNode.costLine(upgrade.costs());
            Component reason = learned ? Component.literal("Already owned")
                : available ? Component.literal("Buy this bonus")
                : reasonKey.isEmpty() ? Component.literal("Not available yet")
                : Component.translatable(reasonKey);

            List<InspectorSection> details = new ArrayList<>();
            details.add(new InspectorSection(Component.literal("EFFECT"), HsUiTokens.ACCENT));
            details.add(new InspectorSection(description, 0));
            details.add(new InspectorSection(Component.literal("COST"), HsUiTokens.ACCENT));
            for (DevelopmentNode.Cost row : upgrade.costs()) {
                details.add(new InspectorSection(Component.translatable(
                    "hearthstead.development.cost.line", row.count(), row.displayName()), 0));
            }
            int coinCost = upgrade.coinCost();
            if (!learned && coinCost > 0) {
                if (availableCoins >= 0) {
                    boolean funded = availableCoins >= coinCost;
                    details.add(new InspectorSection(Component.literal("Coins: " + availableCoins
                        + " / " + coinCost + (funded ? " - Ready" : " - Need "
                        + (coinCost - availableCoins))),
                        funded ? HsUiTokens.GOOD : HsUiTokens.WARN, availableCoins, coinCost));
                } else {
                    details.add(new InspectorSection(Component.literal("Coins: awaiting server"),
                        HsUiTokens.TEXT_MUTED));
                }
                details.add(new InspectorSection(Component.literal(
                    "Coins and goods are paid together, only when you buy."), HsUiTokens.TEXT_MUTED));
            }

            details.add(new InspectorSection(Component.literal("REQUIREMENTS"), HsUiTokens.ACCENT));
            DevelopmentNode required = upgrade.requires();
            var requiredView = nodeViews.get(required.wireId());
            var requiredStatus = requiredView == null ? Development.NodeStatus.QUARANTINED
                : Development.NodeStatus.fromWireId(requiredView.statusWireId());
            boolean nodeMet = requiredStatus == Development.NodeStatus.OWNED
                || requiredStatus == Development.NodeStatus.ACTIVE
                || requiredStatus == Development.NodeStatus.DORMANT;
            details.add(new InspectorSection(Component.literal(nodeMet ? "Done: " : "Need: ")
                .append(Component.translatable("hearthstead.development.detail.prerequisite",
                    required.displayName())), nodeMet ? HsUiTokens.GOOD : HsUiTokens.WARN));
            PostRaidUpgrade chain = upgrade.requiresUpgrade();
            if (chain != null) {
                var chainView = upgradeViews.get(chain.wireId());
                boolean chainMet = chainView != null && Development.NodeStatus.fromWireId(
                    chainView.statusWireId()) == Development.NodeStatus.OWNED;
                details.add(new InspectorSection(Component.literal(chainMet ? "Done: " : "Need: ")
                    .append(chain.displayName()), chainMet ? HsUiTokens.GOOD : HsUiTokens.WARN));
            }
            DevelopmentObjective gate = upgrade.gateObjective();
            if (gate != null) {
                // The snapshot carries no live counter for bonuses; a bonus the
                // server offers (or that only lacks payment) has met its gate.
                boolean gateMet = learned || available
                    || "hearthstead.development.blocked.materials".equals(reasonKey);
                details.add(new InspectorSection(Component.literal(gateMet ? "Done: " : "Milestone: ")
                    .append(gate.progressText(gateMet ? upgrade.gateTarget() : 0,
                        upgrade.gateTarget())), gateMet ? HsUiTokens.GOOD : HsUiTokens.WARN));
            }
            if (!learned && !available) {
                details.add(new InspectorSection(reason, HsUiTokens.WARN));
            } else if (available) {
                details.add(new InspectorSection(Component.literal("Ready now"), HsUiTokens.GOOD));
            }

            MutableComponent tooltip = name.copy().append("\n").append(description)
                .append("\n").append(cost).append("\n").append(reason);
            Component badge = Component.literal(learned ? "Owned" : available ? "Ready"
                : status == Development.NodeStatus.QUARANTINED ? "Blocked" : "Locked");
            return new CachedUpgrade(upgrade, status, name, name, badge, cost, tooltip, reason,
                List.copyOf(details), new ItemStack(upgradeIcon(upgrade)), available, learned,
                CachedNode.outline(status));
        }
    }

    private static final class TechNodeButton extends AbstractButton {
        private int drawX;
        private int drawY;
        private int drawWidth;
        private int drawHeight;
        private int clipLeft;
        private int clipTop;
        private int clipRight;
        private int clipBottom;
        private Inspectable node;
        /** Bonus cards: smaller, icon-only in the overview. */
        private final boolean upgrade;
        private final Runnable onPress;
        private NodeVisualMode visualMode = NodeVisualMode.DETAILED;
        private boolean textDirty = true;
        private Font fittedFont;
        private String fittedTextEpoch = "";
        private List<FormattedCharSequence> nameLines = List.of();
        private FormattedCharSequence overviewLine = Component.empty().getVisualOrderText();
        private boolean overviewIcon;

        TechNodeButton(Inspectable node, boolean upgrade, Runnable onPress) {
            super(0, 0, 1, 1, node.name());
            this.node = node;
            this.upgrade = upgrade;
            this.onPress = onPress;
            // Locked nodes remain focusable so keyboard-only players can read
            // the same prerequisite/quest/cost inspector as mouse users. The
            // Selection never spends Coins; only the explicit Learn action can buy.
            this.active = true;
            setTooltip(null); // Node facts stay in the persistent right inspector.
        }

        void updateNode(Inspectable fresh) {
            node = fresh;
            setMessage(fresh.name());
            setTooltip(null); // Node facts stay in the persistent right inspector.
            textDirty = true;
        }

        boolean isHoveredAt(int mouseX, int mouseY) {
            return visible && mouseX >= getX() && mouseX < getX() + getWidth()
                && mouseY >= getY() && mouseY < getY() + getHeight();
        }

        void relayout(int drawX, int drawY, NodeGeometry geometry,
                      int viewLeft, int viewTop, int viewRight, int viewBottom,
                      Font font, String textEpoch) {
            int drawWidth = geometry.width();
            int drawHeight = geometry.height();
            boolean widthChanged = this.drawWidth != drawWidth;
            boolean modeChanged = visualMode != geometry.mode();
            boolean textEpochChanged = !fittedTextEpoch.equals(textEpoch)
                || fittedFont != font;
            this.drawX = drawX;
            this.drawY = drawY;
            this.drawWidth = drawWidth;
            this.drawHeight = drawHeight;
            this.visualMode = geometry.mode();
            this.clipLeft = viewLeft;
            this.clipTop = viewTop;
            this.clipRight = viewRight;
            this.clipBottom = viewBottom;

            int x = Math.max(drawX, viewLeft);
            int y = Math.max(drawY, viewTop);
            int right = Math.min(drawX + drawWidth, viewRight);
            int bottom = Math.min(drawY + drawHeight, viewBottom);
            visible = right > x && bottom > y;
            setX(x);
            setY(y);
            setWidth(Math.max(1, right - x));
            setHeight(Math.max(1, bottom - y));

            // Text is only re-fit for a changed geometry, node snapshot,
            // locale/font epoch or visual mode; panning remains geometry-only.
            if (widthChanged || modeChanged || textDirty || textEpochChanged) {
                fittedFont = font;
                fittedTextEpoch = textEpoch;
                overviewIcon = visualMode == NodeVisualMode.OVERVIEW
                    && (upgrade || drawHeight >= 30);
                int nameWidth = Math.max(1, drawWidth - (visualMode == NodeVisualMode.OVERVIEW
                    ? 6 : upgrade ? 26 : 30));
                List<FormattedCharSequence> wrapped = font == null ? List.of() : font.split(node.name(), nameWidth);
                nameLines = List.copyOf(wrapped.subList(0, Math.min(2, wrapped.size())));
                overviewLine = font == null ? Component.empty().getVisualOrderText()
                    : clipLine(font, node.overviewName(), nameWidth).getVisualOrderText();
                textDirty = false;
            }
        }

        @Override
        public void onPress() {
            onPress.run();
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY,
                                    float partialTick) {
            graphics.enableScissor(clipLeft, clipTop, clipRight, clipBottom);
            if (visualMode == NodeVisualMode.MAP) {
                // Whole-hub map: the card's own state colours as a plain chip.
                graphics.fill(drawX, drawY, drawX + drawWidth, drawY + drawHeight,
                    node.learned() ? 0xFF355B3B : node.available() ? 0xFF80601F : 0xFF55554C);
                graphics.fill(drawX, drawY, drawX + drawWidth, drawY + 1,
                    node.learned() ? 0xFFB4D589 : node.available() ? 0xFFFFD36B : 0xFFB7B1A7);
                if (isHoveredOrFocused()) {
                    graphics.fill(drawX - 1, drawY - 1, drawX + drawWidth + 1, drawY, 0xCCFFF3C2);
                    graphics.fill(drawX - 1, drawY + drawHeight, drawX + drawWidth + 1,
                        drawY + drawHeight + 1, 0xCCFFF3C2);
                }
                graphics.disableScissor();
                return;
            }
            PixelBoard.nodeCard(graphics, drawX, drawY, drawWidth, drawHeight,
                isHoveredOrFocused(), node.learned(), node.available());
            var font = net.minecraft.client.Minecraft.getInstance().font;
            int cardText = HearthPixelSurface.LIGHT_TEXT;
            if (visualMode == NodeVisualMode.OVERVIEW && upgrade) {
                graphics.renderItem(node.icon(), drawX + (drawWidth - 16) / 2,
                    drawY + (drawHeight - 16) / 2);
                graphics.disableScissor();
                return;
            }
            if (visualMode == NodeVisualMode.OVERVIEW) {
                if (overviewIcon) {
                    graphics.renderItem(node.icon(), drawX + (drawWidth - 16) / 2, drawY + 3);
                    if (node.learned()) {
                        HsUi.requirementMark(graphics, drawX + drawWidth - 13, drawY + 3, true);
                    } else if (!node.available()) {
                        int lockX = drawX + drawWidth - 11;
                        int lockY = drawY + 4;
                        graphics.fill(lockX + 1, lockY, lockX + 6, lockY + 1, cardText);
                        graphics.fill(lockX + 1, lockY, lockX + 2, lockY + 4, cardText);
                        graphics.fill(lockX + 5, lockY, lockX + 6, lockY + 4, cardText);
                        graphics.fill(lockX, lockY + 3, lockX + 7, lockY + 9, cardText);
                        graphics.fill(lockX + 3, lockY + 5, lockX + 4, lockY + 8, 0xFF55554C);
                    }
                    graphics.drawString(font, overviewLine,
                        drawX + (drawWidth - font.width(overviewLine)) / 2,
                        drawY + 23, cardText, false);
                } else {
                    graphics.drawString(font, overviewLine,
                        drawX + Math.max(3, (drawWidth - font.width(overviewLine)) / 2),
                        drawY + (drawHeight - HsUiTokens.TEXT_H) / 2 + 1, cardText, false);
                }
                graphics.disableScissor();
                return;
            }
            if (upgrade && drawHeight < 40) {
                // Compact bonus card: icon plus one fitted name line.
                graphics.renderItem(node.icon(), drawX + 4, drawY + (drawHeight - 16) / 2);
                graphics.drawString(font, overviewLine, drawX + 22,
                    drawY + (drawHeight - HsUiTokens.TEXT_H) / 2 + 1, cardText, false);
                graphics.disableScissor();
                return;
            }
            int textX = upgrade ? 22 : 25;
            graphics.renderItem(node.icon(), drawX + (upgrade ? 4 : 5), drawY + 5);
            for (int i = 0; i < nameLines.size() && i < 2; i++) {
                graphics.drawString(font, nameLines.get(i), drawX + textX, drawY + 5 + i * 9,
                    cardText, false);
            }
            graphics.drawString(font, node.stateBadge(), drawX + 6,
                drawY + drawHeight - 12, cardText, false);
            graphics.disableScissor();
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
            output.add(NarratedElementType.HINT, node.tooltip());
        }
    }
    /** Opaque, pixel-aligned materials for the development board. */
    private static final class PixelBoard {
        private static final int INK = HearthPixelSurface.INK;
        private static final int MUTED = HearthPixelSurface.MUTED;
        private static final int ACCENT = 0xFF70502B;
        private static final int GOOD = 0xFF35643C;
        private static final int WARN = 0xFF86521F;
        private static final int BAD = 0xFF963E31;
        // Mirrors the established Hearth notice-board material without changing the shared surface.
        private static final int OUTLINE = 0xFF20251F;
        private static final int TIMBER = 0xFF594432;
        private static final int TIMBER_LIGHT = 0xFFB3956B;
        private static final int TIMBER_SHADE = 0xFF2D241B;
        private static final int TIMBER_GROOVE = 0xFF3D2D20;
        private static final int TIMBER_GRAIN = 0xFF755238;
        private static final int PARCHMENT = 0xFFE5DEC9;
        private static final int PARCHMENT_LIGHT = 0xFFFFF5DD;
        private static final int PARCHMENT_SHADE = 0xFFAB9D80;
        private static final int TAB_PARCHMENT = 0xFFD7C4A0;
        private static final int TAB_LIGHT = 0xFFFFEED0;
        private static final int TAB_SHADE = 0xFF8E7654;
        private static final int MOSS = 0xFF49644C;
        private static final int MOSS_HOVER = 0xFF5C7A58;
        private static final int MOSS_PRESSED = 0xFF36513A;

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
            HearthMaterials.header(g, x + 5, y + 4, w - 10, h - 4);
        }

        private static void card(GuiGraphics g, int x, int y, int w, int h, boolean hover) {
            HearthMaterials.panel(g, x, y, w, h);
            if (hover) g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0x2276A848);
        }

        private static void nodeCard(GuiGraphics g, int x, int y, int w, int h,
                                     boolean hover, boolean learned, boolean available) {
            int face = learned ? 0xFF355B3B : available ? 0xFF80601F : 0xFF55554C;
            int edge = learned ? 0xFFB4D589 : available ? 0xFFFFD36B : 0xFFB7B1A7;
            HearthMaterials.header(g, x, y, w, h);
            if (!learned) g.fill(x, y, x + w, y + h, available ? 0xCC80601F : 0xDD55554C);
            g.fill(x, y, x + w, y + 2, edge);
            g.fill(x, y + h - 2, x + w, y + h, 0xFF2D2B26);
            if (hover) {
                g.fill(x + 2, y + 2, x + w - 2, y + 3, 0xCCFFF3C2);
            }
        }

        private static void inset(GuiGraphics g, int x, int y, int w, int h) {
            HearthMaterials.panel(g, x, y, w, h);
        }

        private static void divider(GuiGraphics g, int x, int y, int w) {
            g.fill(x, y, x + w, y + 1, PARCHMENT_SHADE);
        }

        private static void button(GuiGraphics g, int x, int y, int w, int h,
                                   boolean action, boolean pressed, boolean hoveredOrFocused) {
            HearthMaterials.button(g, x, y, w, h, action, false, hoveredOrFocused, true);
            if (pressed) g.fill(x, y, x + w, y + h, 0x22000000);
        }

        private static void raised(GuiGraphics g, int x, int y, int w, int h,
                                   int face, int light, int shade) {
            g.fill(x, y, x + w, y + h, OUTLINE);
            g.fill(x + 1, y + 1, x + w - 1, y + h - 1, face);
            g.fill(x + 1, y + 1, x + w - 1, y + 2, light);
            g.fill(x + 1, y + 1, x + 2, y + h - 1, light);
            g.fill(x + 2, y + h - 2, x + w - 1, y + h - 1, shade);
            g.fill(x + w - 2, y + 2, x + w - 1, y + h - 1, shade);
        }

        private static void scrollbar(GuiGraphics g, int x, int y, int h,
                                       float visible, float position, boolean hovered) {
            g.fill(x, y, x + HsUiTokens.SCROLL_W, y + h, 0xFFC0B89F);
            int thumb = Math.max(8, Math.min(h, Math.round(h * visible)));
            int offset = Math.round((h - thumb) * position);
            g.fill(x + 1, y + offset, x + HsUiTokens.SCROLL_W - 1, y + offset + thumb,
                hovered ? MOSS_HOVER : MOSS);
        }
    }

    private static final class PixelButton extends HsButton {
        private final boolean danger;
        private final boolean action;
        private final HsUi.FittedLabelCache labelCache = new HsUi.FittedLabelCache();
        private boolean pressed;

        private PixelButton(int x, int y, int w, int h, Component label,
                            Runnable action, boolean danger, boolean actionSurface) {
            super(x, y, w, h, label, danger ? Kind.DANGER : Kind.NORMAL, action);
            this.danger = danger;
            this.action = actionSurface;
        }

        public static PixelButton normal(int x, int y, int w, int h,
                                         Component label, Runnable action) {
            return new PixelButton(x, y, w, h, label, action, false, false);
        }

        public static PixelButton action(int x, int y, int w, int h,
                                         Component label, Runnable action) {
            return new PixelButton(x, y, w, h, label, action, false, true);
        }

        public static PixelButton danger(int x, int y, int w, int h,
                                         Component label, Runnable action) {
            return new PixelButton(x, y, w, h, label, action, true, false);
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
            HsMotion.blendHover(g, hoverProgress(active && isHoveredOrFocused()), h ->
                PixelBoard.button(g, getX(), getY(), getWidth(), getHeight(), action,
                    active && pressed, h));
            if (!active) {
                g.fill(getX() + 1, getY() + 1, getX() + getWidth() - 1,
                    getY() + getHeight() - 1, 0x885A6054);
            } else if (danger) {
                HearthMaterials.button(g, getX(), getY(), getWidth(), getHeight(), true, true, isHoveredOrFocused(), true);
            }
            if (active && isFocused()) HsUi.keyboardFocus(g, getX(), getY(), getWidth(), getHeight());
            var mc = net.minecraft.client.Minecraft.getInstance();
            int inner = Math.max(1, getWidth() - 8);
            HsUi.FittedLabel label = labelCache.fit(mc.font, getMessage(), inner,
                mc.getLanguageManager().getSelected());
            g.drawString(mc.font, label.text(), getX() + 4 + (inner - Math.min(inner, label.width())) / 2,
                getY() + (getHeight() - HsUiTokens.TEXT_H) / 2 + 1 + labelPressOffset(),
                active ? (action || danger ? HearthPixelSurface.LIGHT_TEXT : HearthPixelSurface.INK)
                    : (action ? 0xFFADB6A3 : 0xFF6C6F66), false);
        }
    }


}
