package com.hearthstead.client.screen;

import com.hearthstead.client.QaClientObserver;
import com.hearthstead.client.QaUiInspectable;
import com.hearthstead.client.ui.HsButton;
import com.hearthstead.client.ui.HsUi;
import com.hearthstead.client.ui.HsUiTokens;
import com.hearthstead.network.DevelopmentActionPayload;
import com.hearthstead.network.DevelopmentSnapshotPayload;
import com.hearthstead.settlement.development.BuildingDescription;
import com.hearthstead.settlement.development.Development;
import com.hearthstead.settlement.development.DevelopmentNode;
import com.hearthstead.settlement.development.DevelopmentObjective;
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
    // Match the wide overview used by the approved art-direction preview when
    // the player's GUI scale leaves room for it. Smaller displays still fall
    // back to the same clipped, pannable viewport in init().
    private static final int PANEL_W = 1824;
    private static final int PANEL_H = 490;
    private static final int PAD = 10;
    private static final int VIEW_X = 12;
    private static final int VIEW_Y = 52;
    private static final int FOOTER_RESERVED = 38;
    private static final int INSPECTOR_GAP = 8;
    private static final int INSPECTOR_MIN_W = 132;
    private static final int INSPECTOR_MAX_W = 300;
    private static final int INSPECTOR_LINE_H = 10;
    private static final int NODE_W = 146;
    private static final int NODE_H = 54;
    // Fonts remain at native GUI scale while cards zoom. The two overview
    // levels deliberately collapse cards to name + state; full quest and cost
    // detail remains available in the bounded keyboard/mouse inspector.
    private static final float[] ZOOMS = {
        0.30F, 0.42F, 0.56F, 0.72F, 0.86F, 1.0F, 1.18F
    };
    // Keep the established 86% opening scale after adding the wider overview.
    private static final int DEFAULT_ZOOM_INDEX = 4;
    private static final int DEFAULT_PAN_X = -10;
    private static final int DEFAULT_PAN_Y = -40;
    private static final int CONTROL_GAP = 4;
    private static final int REFRESH_W = 48;
    private static final int CENTER_W = 44;
    private static final int ZOOM_W = 24;
    private static final int CLOSE_W = 40;
    private static final int HEADER_CONTROLS_W = REFRESH_W + CENTER_W
        + 2 * ZOOM_W + CLOSE_W + 4 * CONTROL_GAP;
    private static final Component SUBTITLE = Component.translatable(
        "hearthstead.development.subtitle");
    private static final Component DEFAULT_FOOTER = Component.translatable(
        "hearthstead.development.footer.pan_zoom");

    static final DevelopmentNode[][] EDGES = {
        {DevelopmentNode.SETTLEMENT_CHARTER, DevelopmentNode.SHELTER},
        {DevelopmentNode.SHELTER, DevelopmentNode.TIMBER_RIGHTS},
        {DevelopmentNode.TIMBER_RIGHTS, DevelopmentNode.STORES_AND_ROADS},
        {DevelopmentNode.STORES_AND_ROADS, DevelopmentNode.CULTIVATED_GROUND},
        {DevelopmentNode.CULTIVATED_GROUND, DevelopmentNode.HOME},
        {DevelopmentNode.HOME, DevelopmentNode.HOSPITALITY},
        {DevelopmentNode.HOSPITALITY, DevelopmentNode.FIRST_WATCH},
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
    private final List<TechNodeButton> nodeButtons = new ArrayList<>();
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
    private CachedNode inspectedNode;
    private List<InspectorLine> inspectorLines = List.of();
    private int panX = DEFAULT_PAN_X;
    private int panY = DEFAULT_PAN_Y;
    private int zoomIndex = DEFAULT_ZOOM_INDEX;
    private boolean draggingMap;
    private boolean uiOpenSoundPlayed;
    private boolean uiCloseSoundPlayed;
    private double lastDragX;
    private double lastDragY;
    private String nodeTextEpoch = "";

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
        playAuthoritativeFeedback(fresh);
        snapshot = fresh;
        rebuildCache();
        refreshNodeControls();
    }

    @Override
    protected void init() {
        // GUI scale 4 can leave only 320x180 logical pixels. Keep the map a
        // real viewport instead of pushing a fixed 440x274 panel off-screen.
        panelWidth = Math.min(PANEL_W, Math.max(1, width - 16));
        panelHeight = Math.min(PANEL_H, Math.max(1, height - 16));
        viewHeight = Math.max(1, panelHeight - VIEW_Y - FOOTER_RESERVED);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        int contentWidth = Math.max(1, panelWidth - 2 * VIEW_X);
        if (contentWidth >= 220) {
            inspectorWidth = Math.min(INSPECTOR_MAX_W,
                Math.max(INSPECTOR_MIN_W, contentWidth / 4));
            viewWidth = Math.max(108,
                contentWidth - inspectorWidth - INSPECTOR_GAP);
            inspectorWidth = Math.max(1,
                contentWidth - viewWidth - INSPECTOR_GAP);
        } else {
            viewWidth = contentWidth;
            inspectorWidth = 0;
        }
        inspectorLeft = left + VIEW_X + viewWidth + INSPECTOR_GAP;
        inspectorTop = top + VIEW_Y;
        inspectorHeight = viewHeight;
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
                view == null ? List.of() : view.quests());
        }
        cached = next;
        if (cached.length > 0) {
            inspectedNode = inspected == null
                ? cached[0] : cached[inspected.ordinal()];
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
            panelWidth - HEADER_CONTROLS_W - 3 * PAD);
        subtitleLine = clipLine(font, SUBTITLE, panelWidth - 2 * PAD);
        footerLine = clipLine(font, footer, panelWidth - 2 * PAD);
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
        if (snapshot == null) {
            return;
        }
        int right = left + panelWidth - PAD;
        right -= CLOSE_W;
        HsButton close = HsButton.normal(right, top + 8, CLOSE_W,
            HsUiTokens.BUTTON_H, Component.translatable("hearthstead.gui.close"),
            this::onClose);
        close.setTooltip(Tooltip.create(Component.translatable(
            "hearthstead.gui.close.tip")));
        addRenderableWidget(close);

        right -= CONTROL_GAP + ZOOM_W;
        HsButton zoomIn = HsButton.normal(right, top + 8, ZOOM_W,
            HsUiTokens.BUTTON_H, Component.literal("+"), () -> zoom(1));
        zoomIn.setTooltip(Tooltip.create(Component.translatable(
            "hearthstead.development.zoom_in")));
        addRenderableWidget(zoomIn);

        right -= CONTROL_GAP + ZOOM_W;
        HsButton zoomOut = HsButton.normal(right, top + 8, ZOOM_W,
            HsUiTokens.BUTTON_H, Component.literal("−"), () -> zoom(-1));
        zoomOut.setTooltip(Tooltip.create(Component.translatable(
            "hearthstead.development.zoom_out")));
        addRenderableWidget(zoomOut);

        right -= CONTROL_GAP + CENTER_W;
        HsButton centre = HsButton.normal(right, top + 8, CENTER_W,
            HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.development.center"),
            this::resetView);
        centre.setTooltip(Tooltip.create(Component.translatable(
            "hearthstead.development.center.tip")));
        addRenderableWidget(centre);

        right -= CONTROL_GAP + REFRESH_W;
        HsButton refresh = HsButton.normal(right, top + 8,
            REFRESH_W, HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.development.refresh"),
            this::refresh);
        refresh.setTooltip(Tooltip.create(Component.translatable(
            "hearthstead.development.refresh.tip")));
        addRenderableWidget(refresh);

        for (CachedNode node : cached) {
            TechNodeButton button = new TechNodeButton(node,
                () -> unlock(node.node));
            nodeButtons.add(button);
            addRenderableWidget(button);
        }
        relayoutNodeControls();
    }

    /**
     * A server refresh changes node state, not screen structure. Rebind the
     * existing controls instead of clearing and allocating the whole widget
     * tree on every authoritative snapshot.
     */
    private void refreshNodeControls() {
        if (nodeButtons.size() != cached.length) {
            rebuildControls();
            return;
        }
        for (int i = 0; i < cached.length; i++) {
            nodeButtons.get(i).updateNode(cached[i]);
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
        int viewLeft = left + VIEW_X;
        int viewTop = top + VIEW_Y;
        int viewRight = viewLeft + viewWidth;
        int viewBottom = viewTop + viewHeight;
        String textEpoch = currentTextEpoch();
        nodeTextEpoch = textEpoch;
        for (TechNodeButton button : nodeButtons) {
            int x = screenX(worldX(button.node.node), zoom);
            int y = screenY(worldY(button.node.node), zoom);
            button.relayout(x, y, geometry, viewLeft, viewTop, viewRight,
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

    private void refresh() {
        PacketDistributor.sendToServer(new DevelopmentActionPayload(snapshot.hearthPos(),
            snapshot.settlementId(), snapshot.mayorId(), DevelopmentActionPayload.View.TECH,
            DevelopmentActionPayload.Kind.REFRESH, -1, snapshot.revision()));
    }

    private void zoom(int direction) {
        int next = Math.max(0, Math.min(ZOOMS.length - 1, zoomIndex + direction));
        if (next != zoomIndex) {
            QaClientObserver.markUiTransition("development_zoom");
            zoomIndex = next;
            clampPan();
            relayoutNodeControls();
        }
    }

    private void resetView() {
        QaClientObserver.markUiTransition("development_center");
        panX = DEFAULT_PAN_X;
        panY = DEFAULT_PAN_Y;
        zoomIndex = DEFAULT_ZOOM_INDEX;
        relayoutNodeControls();
    }

    private float zoom() {
        return ZOOMS[zoomIndex];
    }

    /**
     * A far overview is a different presentation, not a detailed card forced
     * into too little world space. At 30%, trunk columns are only 54px apart
     * and doctrine lanes only 24px apart; the former 72x36 floor therefore
     * made both kinds of node overlap. The compact state chip leaves the full
     * name, quest, cost and reward in the existing inspector/narration path.
     */
    static NodeGeometry nodeGeometry(float zoom) {
        if (zoom < 0.56F) {
            return new NodeGeometry(Math.max(44, Math.round(NODE_W * zoom)),
                Math.max(18, Math.round(NODE_H * zoom)), NodeVisualMode.OVERVIEW);
        }
        return new NodeGeometry(Math.max(72, Math.round(NODE_W * zoom)),
            Math.max(36, Math.round(NODE_H * zoom)), NodeVisualMode.DETAILED);
    }

    static int scaledNodeWidth(float zoom) {
        return nodeGeometry(zoom).width();
    }

    static int scaledNodeHeight(float zoom) {
        return nodeGeometry(zoom).height();
    }

    enum NodeVisualMode {
        DETAILED,
        OVERVIEW
    }

    record NodeGeometry(int width, int height, NodeVisualMode mode) {
    }

    private int screenX(int worldX, float zoom) {
        return left + VIEW_X + Math.round((worldX - panX) * zoom);
    }

    private int screenY(int worldY, float zoom) {
        return top + VIEW_Y + Math.round((worldY - panY) * zoom);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Locale changes do not necessarily recreate this Screen. Re-fit the
        // fixed widget tree once here; ordinary frames keep the cached lines.
        if (!nodeTextEpoch.equals(currentTextEpoch())) {
            rebuildDisplayLines();
            relayoutNodeControls();
        }
        renderBackground(graphics, mouseX, mouseY, partialTick);
        HsUi.window(graphics, left, top, panelWidth, panelHeight);
        graphics.drawString(font, titleLine, left + PAD, top + 12,
            HsUiTokens.TEXT_STRONG, true);
        graphics.drawString(font, subtitleLine, left + PAD, top + 33,
            HsUiTokens.TEXT_MUTED, true);
        HsUi.divider(graphics, left + PAD, top + VIEW_Y - 6,
            panelWidth - 2 * PAD);

        HsUi.inset(graphics, left + VIEW_X, top + VIEW_Y, viewWidth, viewHeight);
        graphics.enableScissor(left + VIEW_X, top + VIEW_Y,
            left + VIEW_X + viewWidth, top + VIEW_Y + viewHeight);
        drawEdges(graphics);
        graphics.disableScissor();
        updateInspectedNode(mouseX, mouseY);
        renderInspector(graphics);
        // Every node clips itself to the viewport. Rendering the widget layer
        // after the edge scissor keeps the zoom/close controls and deferred
        // tooltips outside that clip rectangle.
        HsUi.widgets(this, graphics, mouseX, mouseY, partialTick);

        HsUi.divider(graphics, left + PAD, top + panelHeight - 33,
            panelWidth - 2 * PAD);
        graphics.drawString(font, footerLine, left + PAD, top + panelHeight - 25,
            HsUiTokens.ACCENT, true);
    }

    private void updateInspectedNode(int mouseX, int mouseY) {
        // Mouse intent wins over a previously keyboard-focused card.
        for (TechNodeButton button : nodeButtons) {
            if (button.isHoveredAt(mouseX, mouseY)) {
                setInspectedNode(button.node);
                return;
            }
        }
        for (TechNodeButton button : nodeButtons) {
            if (button.isFocused()) {
                setInspectedNode(button.node);
                return;
            }
        }
    }

    private void setInspectedNode(CachedNode node) {
        if (node != inspectedNode) {
            QaClientObserver.markUiTransition("development_inspector");
            inspectedNode = node;
            rebuildInspectorLines();
        }
    }

    /** Wraps only when selection, locale, snapshot or panel width changes. */
    private void rebuildInspectorLines() {
        if (font == null || inspectorWidth <= 0 || inspectorHeight <= 0
            || inspectedNode == null) {
            inspectorLines = List.of();
            return;
        }
        int textWidth = Math.max(1, inspectorWidth - 16);
        List<InspectorLine> rebuilt = new ArrayList<>();
        for (InspectorSection section : inspectedNode.details) {
            for (FormattedCharSequence line : font.split(section.text, textWidth)) {
                rebuilt.add(new InspectorLine(line, section.colour));
            }
        }
        int maxLines = Math.max(1, (inspectorHeight - 14) / INSPECTOR_LINE_H);
        if (rebuilt.size() > maxLines) {
            rebuilt.subList(Math.max(0, maxLines - 1), rebuilt.size()).clear();
            rebuilt.add(new InspectorLine(Component.translatable(
                "hearthstead.development.inspector.more").getVisualOrderText(),
                HsUiTokens.TEXT_MUTED));
        }
        inspectorLines = List.copyOf(rebuilt);
    }

    private void renderInspector(GuiGraphics graphics) {
        if (inspectorWidth <= 0) {
            return;
        }
        HsUi.inset(graphics, inspectorLeft, inspectorTop,
            inspectorWidth, inspectorHeight);
        if (inspectorLines.isEmpty()) {
            HsUi.labelIn(graphics, font, Component.translatable(
                "hearthstead.development.inspector.empty"),
                inspectorLeft + 8, inspectorTop + 8, inspectorWidth - 16,
                HsUiTokens.TEXT_MUTED);
            return;
        }
        int y = inspectorTop + 7;
        for (InspectorLine line : inspectorLines) {
            graphics.drawString(font, line.text, inspectorLeft + 8, y,
                line.colour, true);
            y += INSPECTOR_LINE_H;
        }
    }

    private void drawEdges(GuiGraphics graphics) {
        float zoom = zoom();
        for (DevelopmentNode[] edge : EDGES) {
            int fromX = screenX(worldX(edge[0]), zoom)
                + scaledNodeWidth(zoom);
            int fromY = screenY(worldY(edge[0]), zoom)
                + scaledNodeHeight(zoom) / 2;
            int toX = screenX(worldX(edge[1]), zoom);
            int toY = screenY(worldY(edge[1]), zoom)
                + scaledNodeHeight(zoom) / 2;
            int midX = fromX + (toX - fromX) / 2;
            int colour = edgeColour(edge[0], edge[1]);
            lineH(graphics, fromX, midX, fromY, colour);
            lineV(graphics, midX, fromY, toY, colour);
            lineH(graphics, midX, toX, toY, colour);
        }
    }

    private int edgeColour(DevelopmentNode from, DevelopmentNode to) {
        CachedNode source = cached[from.ordinal()];
        CachedNode target = cached[to.ordinal()];
        boolean learned = source.learned && target.learned;
        return learned ? HsUiTokens.GOOD
            : target.available ? HsUiTokens.ACCENT : 0xFF827A6C;
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
        if (button == 0 && overViewport(mouseX, mouseY)) {
            QaClientObserver.markUiTransition("development_pan_drag");
            draggingMap = true;
            lastDragX = mouseX;
            lastDragY = mouseY;
            return true;
        }
        return false;
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

    private void clampPan() {
        panX = Math.max(-40, Math.min(1840, panX));
        panY = Math.max(-40, Math.min(260, panY));
    }

    @Override
    public String qaUiState() {
        int visibleNodes = 0;
        for (TechNodeButton button : nodeButtons) {
            if (button.visible) {
                visibleNodes++;
            }
        }
        String inspected = inspectedNode == null
            ? "none" : inspectedNode.node.id();
        return "inspected=" + inspected + ",inspector=" + inspectorLeft + ":"
            + inspectorTop + ":" + inspectorWidth + ":" + inspectorHeight
            + ",inspectorLines=" + inspectorLines.size() + ",viewport="
            + (left + VIEW_X) + ":" + (top + VIEW_Y) + ":" + viewWidth + ":"
            + viewHeight + ",nodes=" + visibleNodes + "/" + nodeButtons.size()
            + ",pan=" + panX + ":" + panY + ",zoom=" + zoomIndex
            + ",dragging=" + draggingMap;
    }

    private boolean overViewport(double x, double y) {
        return x >= left + VIEW_X && x < left + VIEW_X + viewWidth
            && y >= top + VIEW_Y && y < top + VIEW_Y + viewHeight;
    }

    static int worldX(DevelopmentNode node) {
        return switch (node) {
            case SETTLEMENT_CHARTER -> 0;
            case SHELTER -> 180;
            case TIMBER_RIGHTS -> 360;
            case STORES_AND_ROADS -> 540;
            case CULTIVATED_GROUND -> 720;
            case HOME -> 900;
            case HOSPITALITY -> 1080;
            case FIRST_WATCH -> 1260;
            case ARM_THE_WATCH -> 1440;
            case FIRST_RAID_AFTERMATH -> 1620;
            case SHIELD_DOCTRINE, GUILD_DOCTRINE, HEARTH_DOCTRINE -> 1810;
            default -> 2000;
        };
    }

    static int worldY(DevelopmentNode node) {
        return switch (node) {
            case SHIELD_DOCTRINE -> 0;
            case GUILD_DOCTRINE -> 80;
            case HEARTH_DOCTRINE -> 160;
            case FORTIFICATION -> -40;
            case BORDER_WARDENS -> 24;
            case LAND_AND_HARVEST -> 88;
            case CRAFT_AND_INDUSTRY -> 152;
            case HALL_AND_LEARNING -> 216;
            default -> 80;
        };
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record InspectorSection(Component text, int colour) {
    }

    private record InspectorLine(FormattedCharSequence text, int colour) {
    }

    private record CachedNode(DevelopmentNode node, Development.NodeStatus status,
                              Component name, Component description,
                              Component questText, Component stateText, Component costText,
                              Component tooltip, List<InspectorSection> details, ItemStack icon,
                              boolean available, boolean learned, int outline) {
        static CachedNode create(DevelopmentNode node, Development.NodeStatus status,
                                 String reasonKey,
                                 List<DevelopmentSnapshotPayload.QuestView> quests) {
            Component name = node.displayName();
            Component description = node.description();
            Component cost = costLine(node);
            MutableComponent quest = compactQuestLine(node, quests);
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
            details.add(new InspectorSection(name, HsUiTokens.TEXT_STRONG));
            details.add(new InspectorSection(state, outline(status)));
            details.add(new InspectorSection(description, HsUiTokens.TEXT_MUTED));
            for (DevelopmentSnapshotPayload.QuestView view : quests) {
                DevelopmentObjective objective = DevelopmentObjective.byWireId(
                    view.objectiveWireId());
                if (objective != null) {
                    Component progress = objective.progressText(
                        view.progress(), view.target());
                    tooltip.append("\n").append(progress);
                    details.add(new InspectorSection(progress, HsUiTokens.ACCENT));
                }
            }
            tooltip.append("\n").append(cost).append("\n").append(reason);
            details.add(new InspectorSection(cost,
                available ? HsUiTokens.GOOD : HsUiTokens.TEXT_MUTED));
            details.add(new InspectorSection(reason,
                available ? HsUiTokens.GOOD : HsUiTokens.WARN));
            if (node.doctrine()) {
                tooltip.append("\n").append(node.tradeoff());
                details.add(new InspectorSection(node.tradeoff(), HsUiTokens.WARN));
            }
            for (var building : node.knowledge().buildPlans()) {
                Component plan = BuildingDescription.shortDescription(building);
                tooltip.append("\n").append(plan);
                details.add(new InspectorSection(plan, HsUiTokens.TEXT_MUTED));
            }
            ItemStack icon = node.buildings().isEmpty()
                ? new ItemStack(node.costs().isEmpty()
                    ? net.minecraft.world.item.Items.BOOK : node.costs().get(0).item())
                : new ItemStack(node.buildings().get(0).emblem());
            return new CachedNode(node, status, name, description, quest, state, cost,
                tooltip, List.copyOf(details), icon, available, learned, outline(status));
        }

        private static MutableComponent compactQuestLine(DevelopmentNode node,
                List<DevelopmentSnapshotPayload.QuestView> quests) {
            if (quests.isEmpty()) {
                return Component.translatable(node == DevelopmentNode.SETTLEMENT_CHARTER
                    ? "hearthstead.development.quest.free_tutorial"
                    : "hearthstead.development.quest.planned");
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

        private static Component costLine(DevelopmentNode node) {
            if (node.costs().isEmpty()) {
                return Component.translatable("hearthstead.development.cost.none");
            }
            MutableComponent line = Component.translatable("hearthstead.development.cost.prefix");
            for (int i = 0; i < node.costs().size(); i++) {
                DevelopmentNode.Cost cost = node.costs().get(i);
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
                case ACTIVE, AVAILABLE -> HsUiTokens.ACCENT;
                case OWNED -> HsUiTokens.GOOD;
                case DORMANT -> 0xFF7793B8;
                case FUTURE -> 0xFF8E789C;
                case QUARANTINED -> HsUiTokens.BAD;
                case LOCKED -> 0xFF5B554B;
            };
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
        private CachedNode node;
        private final Runnable onPress;
        private NodeVisualMode visualMode = NodeVisualMode.DETAILED;
        private boolean textDirty = true;
        private Font fittedFont;
        private String fittedTextEpoch = "";
        private Component nameLine = Component.empty();
        private Component questLine = Component.empty();
        private Component costLine = Component.empty();
        private Component stateLine = Component.empty();
        private int overviewStateWidth;

        TechNodeButton(CachedNode node, Runnable onPress) {
            super(0, 0, 1, 1, node.name);
            this.node = node;
            this.onPress = onPress;
            // Locked nodes remain focusable so keyboard-only players can read
            // the same prerequisite/quest/cost inspector as mouse users. The
            // guarded onPress below still prevents a useless network action.
            this.active = true;
        }

        void updateNode(CachedNode fresh) {
            node = fresh;
            setMessage(fresh.name);
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
                nameLine = clipLine(font, node.name, drawWidth - 30);
                questLine = clipLine(font, node.questText, drawWidth - 12);
                costLine = clipLine(font, node.costText, drawWidth - 12);
                stateLine = clipLine(font, node.stateText,
                    visualMode == NodeVisualMode.OVERVIEW ? drawWidth - 6 : drawWidth - 12);
                overviewStateWidth = font == null ? 0 : font.width(stateLine);
                textDirty = false;
            }
        }

        @Override
        public void onPress() {
            if (node.available) {
                onPress.run();
            }
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY,
                                    float partialTick) {
            graphics.enableScissor(clipLeft, clipTop, clipRight, clipBottom);
            HsUi.card(graphics, drawX, drawY, drawWidth, drawHeight,
                isHoveredOrFocused());
            graphics.fill(drawX, drawY, drawX + drawWidth, drawY + 2,
                node.outline);
            graphics.fill(drawX, drawY + drawHeight - 2,
                drawX + drawWidth, drawY + drawHeight, node.outline);
            var font = net.minecraft.client.Minecraft.getInstance().font;
            if (visualMode == NodeVisualMode.OVERVIEW) {
                graphics.drawString(font, stateLine,
                    drawX + Math.max(3, (drawWidth - overviewStateWidth) / 2),
                    drawY + Math.max(3, (drawHeight - HsUiTokens.TEXT_H) / 2),
                    node.outline, true);
                graphics.disableScissor();
                return;
            }
            graphics.renderItem(node.icon, drawX + 5, drawY + 5);
            graphics.drawString(font, nameLine, drawX + 25, drawY + 5,
                node.learned ? HsUiTokens.GOOD : HsUiTokens.TEXT_STRONG, true);
            if (drawHeight >= 48) {
                graphics.drawString(font, questLine, drawX + 6, drawY + 21,
                    HsUiTokens.TEXT_MUTED, true);
                graphics.drawString(font, costLine, drawX + 6, drawY + 31,
                    node.available ? HsUiTokens.GOOD : HsUiTokens.TEXT_MUTED,
                    true);
            }
            graphics.drawString(font, stateLine, drawX + 6,
                drawY + drawHeight - 12, node.outline, true);
            graphics.disableScissor();
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
            output.add(NarratedElementType.HINT, node.tooltip);
        }
    }
}
