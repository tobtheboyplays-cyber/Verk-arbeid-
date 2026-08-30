package com.hearthstead.client.screen;

import com.hearthstead.client.QaClientObserver;
import com.hearthstead.client.QaUiInspectable;
import com.hearthstead.client.ui.HsButton;
import com.hearthstead.client.ui.HsUi;
import com.hearthstead.client.ui.HsUiTokens;
import com.hearthstead.network.GuardOrderActionPayload;
import com.hearthstead.network.GuardOrderSnapshotPayload;
import com.hearthstead.network.SettlerActionPayload;
import com.hearthstead.network.SettlerSnapshotPayload;
import com.hearthstead.settlement.state.GuardOrder;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.UUID;

/**
 * Compact command board for one exact inspected Guard/Archer session.
 * Every coordinate and outcome displayed here came back from the server.
 */
public final class GuardOrderScreen extends Screen implements QaUiInspectable {
    private static final int PANEL_W = 380;
    private static final int PANEL_H = 316;
    private static final int PAD = 10;
    private static final int BUTTON_H = HsUiTokens.BUTTON_H;
    private static final int SMALL_W = 108;
    private static final long RESPONSE_TIMEOUT_MS = 5_000L;
    private static final Component SUBTITLE = Component.translatable(
        "hearthstead.guard.command.subtitle");
    private static final Component PATROL_TITLE = Component.translatable(
        "hearthstead.guard.command.patrol_title");
    private static final Component TOWER_TITLE = Component.translatable(
        "hearthstead.guard.command.tower_title");
    private static final Component LOADING = Component.translatable(
        "hearthstead.guard.command.loading");
    private static final Component HELP = Component.translatable(
        "hearthstead.guard.command.help");
    private static final Component WAITING = Component.translatable(
        "hearthstead.guard.command.waiting");
    private static final Component NO_REPLY = Component.translatable(
        "hearthstead.guard.command.no_reply");
    private static final Component NO_POINTS = Component.translatable(
        "hearthstead.guard.command.no_points");

    private final SettlerScreen parent;
    private final int guardEntityId;
    private final UUID guardId;
    private final UUID sessionId;
    private GuardOrderSnapshotPayload snapshot;
    private boolean waiting;
    private boolean failed;
    private boolean requested;
    private long requestStartedMillis;
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;
    private boolean compact;
    private HsButton holdButton;
    private HsButton defendButton;
    private HsButton stopButton;
    private HsButton addPointButton;
    private HsButton removePointButton;
    private HsButton startPatrolButton;
    private HsButton towerButton;
    private HsButton traversalButton;
    private Component cachedStatus = LOADING;
    private Component cachedFeedback = HELP;
    private Component cachedPrimary = LOADING;
    private Component cachedPatrolCount = Component.empty();
    private List<Component> cachedPointLabels = List.of();

    public GuardOrderScreen(SettlerScreen parent, int guardEntityId,
                            UUID guardId, UUID sessionId) {
        super(Component.translatable("hearthstead.guard.command.title"));
        this.parent = parent;
        this.guardEntityId = guardEntityId;
        this.guardId = guardId;
        this.sessionId = sessionId;
    }

    public boolean accepts(GuardOrderSnapshotPayload fresh) {
        return fresh != null && fresh.guardEntityId() == guardEntityId
            && fresh.guardId().equals(guardId)
            && fresh.sessionId().equals(sessionId);
    }

    public boolean acceptsParentSnapshot(SettlerSnapshotPayload fresh) {
        return parent.acceptsSnapshot(fresh);
    }

    public void updateParentSnapshot(SettlerSnapshotPayload fresh) {
        if (parent.acceptsSnapshot(fresh)) {
            parent.update(fresh);
        }
    }

    public void update(GuardOrderSnapshotPayload fresh) {
        if (!accepts(fresh)) {
            return;
        }
        snapshot = fresh;
        waiting = false;
        failed = false;
        rebuildTextCache();
        if (fresh.outcome() == GuardOrderSnapshotPayload.Outcome.APPLIED) {
            QaClientObserver.markUiTransition("guard_orders_applied");
            HsUi.playConfirmSound();
        } else if (fresh.outcome()
                == GuardOrderSnapshotPayload.Outcome.REFUSED) {
            QaClientObserver.markUiTransition("guard_orders_refused");
            HsUi.playErrorSound();
        }
        if (minecraft != null && minecraft.screen == this) {
            rebuildButtons();
        }
    }

    @Override
    protected void init() {
        panelWidth = Math.min(PANEL_W, Math.max(1, width - 16));
        panelHeight = Math.min(PANEL_H, Math.max(1, height - 16));
        compact = panelWidth < PANEL_W || panelHeight < PANEL_H;
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        rebuildTextCache();
        rebuildButtons();
        if (!requested) {
            requested = true;
            QaClientObserver.markUiTransition("guard_orders_open");
            HsUi.playOpenSound();
            send(GuardOrderActionPayload.Kind.REFRESH);
        }
    }

    private void rebuildButtons() {
        clearWidgets();
        int buttonWidth = compact
            ? Math.max(64, (panelWidth - PAD * 2 - 16) / 3) : SMALL_W;
        int buttonGap = (panelWidth - PAD * 2 - buttonWidth * 3) / 2;
        int commandY = top + (compact ? 57 : 70);
        holdButton = button(left + PAD, commandY, buttonWidth,
            "hearthstead.guard.command.hold", GuardOrderActionPayload.Kind.HOLD_HERE);
        holdButton.setTooltip(Tooltip.create(Component.translatable(
            "hearthstead.guard.command.hold_tip")));
        defendButton = button(left + PAD + buttonWidth + buttonGap,
            commandY, buttonWidth,
            "hearthstead.guard.command.defend",
            GuardOrderActionPayload.Kind.DEFEND_HEARTH);
        stopButton = button(left + panelWidth - PAD - buttonWidth,
            commandY, buttonWidth,
            "hearthstead.guard.command.stop", GuardOrderActionPayload.Kind.CLEAR_ORDER);

        int patrolY = top + (compact ? 101 : 132);
        addPointButton = button(left + PAD, patrolY, buttonWidth,
            "hearthstead.guard.command.add_point",
            GuardOrderActionPayload.Kind.ADD_PATROL_POINT);
        removePointButton = button(left + PAD + buttonWidth + buttonGap,
            patrolY, buttonWidth, "hearthstead.guard.command.undo_point",
            GuardOrderActionPayload.Kind.REMOVE_PATROL_POINT);
        startPatrolButton = button(left + panelWidth - PAD - buttonWidth,
            patrolY, buttonWidth, "hearthstead.guard.command.start_patrol",
            GuardOrderActionPayload.Kind.START_PATROL);

        int towerY = compact ? top + panelHeight - 32 : top + 244;
        int towerGap = 8;
        int towerWidth = (panelWidth - PAD * 2 - towerGap) / 2;
        traversalButton = button(left + PAD, towerY, towerWidth,
            traversal() == GuardOrder.Traversal.LOOP
                ? "hearthstead.guard.command.traversal_loop"
                : "hearthstead.guard.command.traversal_ping_pong",
            GuardOrderActionPayload.Kind.TOGGLE_TRAVERSAL);
        towerButton = button(left + PAD + towerWidth + towerGap, towerY,
            towerWidth, "hearthstead.guard.command.tower",
            GuardOrderActionPayload.Kind.TOWER_POST);

        int backWidth = compact ? 52 : 72;
        int backY = compact ? top + 6
            : top + panelHeight - PAD - BUTTON_H;
        addRenderableWidget(HsButton.normal(
            left + panelWidth - PAD - backWidth, backY, backWidth, BUTTON_H,
            Component.translatable("hearthstead.guard.command.back"),
            this::returnToParent));
        refreshButtonStates();
    }

    private HsButton button(int x, int y, int width, String key,
                            GuardOrderActionPayload.Kind kind) {
        Component label = Component.translatable(key);
        HsButton button = HsButton.normal(x, y, width, BUTTON_H,
            label, () -> send(kind));
        // Compact profiles deliberately keep all six actions on two stable
        // rows. The full label remains readable even if one translation is
        // wider than its bounded button.
        button.setTooltip(Tooltip.create(label));
        addRenderableWidget(button);
        return button;
    }

    private void refreshButtonStates() {
        boolean manage = snapshot != null && snapshot.canManage() && !waiting;
        int points = snapshot == null ? 0 : snapshot.patrolPoints().size();
        holdButton.active = manage;
        defendButton.active = manage;
        stopButton.active = manage && mode() != GuardOrder.Mode.NONE;
        addPointButton.active = manage
            && points < GuardOrder.MAX_PATROL_POINTS;
        removePointButton.active = manage && points > 0;
        startPatrolButton.active = manage
            && points >= GuardOrder.MIN_PATROL_POINTS;
        traversalButton.active = manage
            && points >= GuardOrder.MIN_PATROL_POINTS;
        towerButton.active = manage && snapshot.towerPostAvailable();
        towerButton.setTooltip(Tooltip.create(Component.translatable(
            snapshot != null && snapshot.towerPostAvailable()
                ? "hearthstead.guard.command.tower_tip"
                : "hearthstead.guard.command.tower_locked_tip")));
    }

    private void send(GuardOrderActionPayload.Kind kind) {
        if (minecraft == null || minecraft.getConnection() == null) {
            waiting = false;
            failed = true;
            return;
        }
        UUID settlementId = snapshot == null
            ? SettlerActionPayload.NO_SETTLER : snapshot.settlementId();
        int revision = snapshot == null ? -1 : snapshot.revision();
        if (kind != GuardOrderActionPayload.Kind.REFRESH
            && (snapshot == null || !snapshot.canManage() || waiting)) {
            return;
        }
        waiting = true;
        failed = false;
        requestStartedMillis = Util.getMillis();
        rebuildTextCache();
        refreshButtonStates();
        PacketDistributor.sendToServer(new GuardOrderActionPayload(
            guardEntityId, guardId, sessionId, settlementId, kind, revision));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY,
                       float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        if (waiting && Util.getMillis() - requestStartedMillis
                >= RESPONSE_TIMEOUT_MS) {
            waiting = false;
            failed = true;
            rebuildTextCache();
            refreshButtonStates();
        }
        HsUi.modalWindow(graphics, left, top, panelWidth, panelHeight);
        HsUi.centred(graphics, font, title, left + panelWidth / 2,
            top + (compact ? 10 : 14), HsUiTokens.TEXT_STRONG);
        HsUi.labelIn(graphics, font, SUBTITLE,
            left + PAD, top + (compact ? 27 : 31), panelWidth - PAD * 2,
            HsUiTokens.TEXT_MUTED);

        int statusY = top + (compact ? 36 : 47);
        HsUi.card(graphics, left + PAD, statusY,
            panelWidth - PAD * 2, 18, false);
        HsUi.labelIn(graphics, font, cachedPrimary, left + PAD + 5,
            statusY + 5, panelWidth - PAD * 2 - 10, statusColour());

        HsUi.divider(graphics, left + PAD, top + (compact ? 81 : 100),
            panelWidth - PAD * 2);
        HsUi.labelIn(graphics, font, PATROL_TITLE,
            left + PAD, top + (compact ? 88 : 111), 150,
            HsUiTokens.TEXT_STRONG);
        HsUi.labelIn(graphics, font, cachedPatrolCount,
            left + panelWidth - PAD - 92, top + (compact ? 88 : 111), 92,
            HsUiTokens.TEXT_MUTED);
        drawPoints(graphics);

        int towerDividerY = compact ? top + panelHeight - 45 : top + 230;
        HsUi.divider(graphics, left + PAD, towerDividerY,
            panelWidth - PAD * 2);
        HsUi.labelIn(graphics, font, TOWER_TITLE,
            left + PAD, towerDividerY + 3, 120, HsUiTokens.TEXT_STRONG);
        if (!compact) {
            HsUi.labelIn(graphics, font, cachedFeedback, left + PAD,
                top + 273, panelWidth - PAD * 2,
                failed || snapshot != null && snapshot.outcome()
                    == GuardOrderSnapshotPayload.Outcome.REFUSED
                        ? HsUiTokens.WARN : HsUiTokens.TEXT_MUTED);
        }
        HsUi.widgets(this, graphics, mouseX, mouseY, partialTick);
    }

    private void drawPoints(GuiGraphics graphics) {
        List<BlockPos> points = snapshot == null ? List.of()
            : snapshot.patrolPoints();
        if (points.isEmpty()) {
            int emptyY = top + (compact ? 136 : 163);
            HsUi.labelIn(graphics, font, NO_POINTS,
                left + PAD, emptyY, panelWidth - PAD * 2,
                HsUiTokens.TEXT_MUTED);
            return;
        }
        int columnW = (panelWidth - PAD * 2 - 8) / 2;
        int startY = top + (compact ? 127 : 160);
        int rowStep = compact ? 13 : 15;
        for (int i = 0; i < points.size(); i++) {
            BlockPos point = points.get(i);
            int column = i / 4;
            int row = i % 4;
            int x = left + PAD + column * (columnW + 8);
            int y = startY + row * rowStep;
            HsUi.card(graphics, x, y, columnW, compact ? 11 : 13, false);
            HsUi.labelIn(graphics, font, cachedPointLabels.get(i),
                x + 4, y + (compact ? 1 : 3), columnW - 8,
                HsUiTokens.TEXT);
        }
    }

    private Component buildStatusLine() {
        if (snapshot == null) return LOADING;
        Component order = Component.translatable(switch (mode()) {
            case RALLY_HERE -> "hearthstead.guard.command.mode.hold";
            case DEFEND_HEARTH -> "hearthstead.guard.command.mode.defend";
            case PATROL_ROUTE -> "hearthstead.guard.command.mode.patrol";
            case STAND_POST -> "hearthstead.guard.command.mode.stand";
            case TOWER_POST -> "hearthstead.guard.command.mode.tower";
            case NONE -> "hearthstead.guard.command.mode.none";
        });
        return snapshot.equipmentReady()
            ? Component.translatable("hearthstead.guard.command.status", order)
            : Component.translatable("hearthstead.guard.command.status_unarmed",
                order);
    }

    private void rebuildTextCache() {
        cachedStatus = buildStatusLine();
        cachedFeedback = failed ? NO_REPLY : waiting ? WAITING
            : snapshot == null || snapshot.feedback().isEmpty()
                ? HELP : snapshot.feedback().get();
        cachedPrimary = compact && (failed || waiting || snapshot != null
            && (snapshot.outcome() == GuardOrderSnapshotPayload.Outcome.REFUSED
                || snapshot.feedback().isPresent()))
            ? cachedFeedback : cachedStatus;
        int count = pointCount();
        cachedPatrolCount = Component.translatable(
            "hearthstead.guard.command.patrol_count", count,
            GuardOrder.MAX_PATROL_POINTS);
        if (snapshot == null || snapshot.patrolPoints().isEmpty()) {
            cachedPointLabels = List.of();
        } else {
            java.util.ArrayList<Component> labels = new java.util.ArrayList<>(
                snapshot.patrolPoints().size());
            for (int i = 0; i < snapshot.patrolPoints().size(); i++) {
                BlockPos point = snapshot.patrolPoints().get(i);
                labels.add(Component.translatable(
                    "hearthstead.guard.command.point", i + 1,
                    point.getX(), point.getY(), point.getZ()));
            }
            cachedPointLabels = List.copyOf(labels);
        }
    }

    private int statusColour() {
        if (compact && (failed || snapshot != null && snapshot.outcome()
            == GuardOrderSnapshotPayload.Outcome.REFUSED)) {
            return HsUiTokens.WARN;
        }
        return snapshot != null && !snapshot.equipmentReady()
            ? HsUiTokens.WARN : HsUiTokens.ACCENT;
    }

    private int pointCount() {
        return snapshot == null ? 0 : snapshot.patrolPoints().size();
    }

    private GuardOrder.Mode mode() {
        return snapshot == null ? GuardOrder.Mode.NONE
            : GuardOrder.Mode.tryFromWireId(snapshot.modeWireId())
                .orElse(GuardOrder.Mode.NONE);
    }

    private GuardOrder.Traversal traversal() {
        return snapshot == null ? GuardOrder.Traversal.LOOP
            : GuardOrder.Traversal.tryFromWireId(snapshot.traversalWireId())
                .orElse(GuardOrder.Traversal.LOOP);
    }

    @Override
    public void onClose() {
        returnToParent();
    }

    private void returnToParent() {
        if (minecraft != null) {
            QaClientObserver.markUiTransition("guard_orders_close");
            HsUi.playCloseSound();
            minecraft.setScreen(parent);
        }
    }

    @Override
    public String qaUiState() {
        return "mode=" + mode().id() + ",points=" + pointCount()
            + ",equipment=" + (snapshot != null && snapshot.equipmentReady()
                ? "ready" : "missing")
            + ",traversal=" + traversal().id()
            + ",tower=" + (snapshot != null && snapshot.towerPostAvailable()
                ? "available" : "locked")
            + ",layout=" + (compact ? "compact" : "normal")
            + ",panel=" + panelWidth + "x" + panelHeight
            + ",waiting=" + waiting + ",failed=" + failed;
    }
}
