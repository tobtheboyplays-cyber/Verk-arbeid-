package com.hearthstead.client.screen;

import com.hearthstead.client.QaClientObserver;
import com.hearthstead.client.QaUiInspectable;
import com.hearthstead.client.ui.HsMotion;
import com.hearthstead.client.ui.HsButton;
import com.hearthstead.client.ui.HsUi;
import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import com.hearthstead.client.ui2.Ui2Button;
import com.hearthstead.client.ui2.Ui2Frame;
import com.hearthstead.client.ui2.Ui2FrameLayout;
import com.hearthstead.client.ui2.Ui2Palette;
import com.hearthstead.client.ui2.Ui2Serif;
import com.hearthstead.client.ui2.Ui2Surface;
import com.hearthstead.client.ui2.Ui2Tips;
import com.hearthstead.network.GuardOrderActionPayload;
import com.hearthstead.network.GuardOrderSnapshotPayload;
import com.hearthstead.network.SettlerActionPayload;
import com.hearthstead.network.SettlerSnapshotPayload;
import com.hearthstead.settlement.state.GuardOrder;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.UUID;

/**
 * Compact command board for one exact inspected Guard/Archer session.
 * Every coordinate and outcome displayed here came back from the server.
 *
 * <p>Standard Bannerhold window ({@link Ui2Frame}): walnut board, crest,
 * serif title with the subtitle on the wood, the wooden close key (Back,
 * Esc) and one parchment page holding the status strip, the three order
 * actions, the patrol route and the tower post.
 */
public final class GuardOrderScreen extends Screen implements QaUiInspectable {

    private static final int PANEL_W = 460;
    private static final int PANEL_H = 256;
    /** Two-line status strip in the normal layout, one line when compact. */
    private static final int STATUS_H = 34;
    private static final int STATUS_H_COMPACT = 24;
    /** One dense patrol-point row (2 columns x 4 rows). */
    private static final int POINT_ROW_H = 12;
    private static final int POINT_ROWS = 4;
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
    private static final Component READ_ONLY = Component.translatable(
        "hearthstead.guard.command.read_only");

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
    private Layout layout;
    private final Ui2Serif.Text titleText = new Ui2Serif.Text(Ui2Serif.Size.TITLE);
    private final Ui2Serif.Text patrolHeading = new Ui2Serif.Text(Ui2Serif.Size.HEADING);
    private final Ui2Serif.Text towerHeading = new Ui2Serif.Text(Ui2Serif.Size.HEADING);
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
    private Component cachedPatrolCount = Component.empty();
    private List<Component> cachedPointLabels = List.of();

    private boolean visualDirty = true;
    private Font visualFont;
    private String visualLanguage = "";
    private int visualWidth = -1;
    private HsUi.FittedLabel orderLine, feedbackLine, patrolCountLine;
    private List<HsUi.FittedLabel> pointLines = List.of();
    private List<List<Component>> pointTooltips = List.of();
    private List<Component> statusTooltip = List.of();
    private List<net.minecraft.util.FormattedCharSequence> feedbackLines = List.of();
    private List<net.minecraft.util.FormattedCharSequence> noPointLines = List.of();
    private static final List<Component> SUBTITLE_TOOLTIP = List.of(SUBTITLE);

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
        layout = layoutFor(width, height);
        Ui2FrameLayout frame = layout.frame();
        panelWidth = frame.width();
        panelHeight = frame.height();
        compact = layout.compact();
        left = frame.x();
        top = frame.y();
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
        int focusedIndex = children().indexOf(getFocused());
        clearWidgets();
        Rect[] orders = layout.orders();
        holdButton = button(orders[0], "hearthstead.guard.command.hold",
            GuardOrderActionPayload.Kind.HOLD_HERE);
        defendButton = button(orders[1], "hearthstead.guard.command.defend",
            GuardOrderActionPayload.Kind.DEFEND_HEARTH);
        stopButton = button(orders[2], "hearthstead.guard.command.stop",
            GuardOrderActionPayload.Kind.CLEAR_ORDER);

        Rect[] patrol = layout.patrol();
        addPointButton = button(patrol[0], "hearthstead.guard.command.add_point",
            GuardOrderActionPayload.Kind.ADD_PATROL_POINT);
        removePointButton = button(patrol[1], "hearthstead.guard.command.undo_point",
            GuardOrderActionPayload.Kind.REMOVE_PATROL_POINT);
        startPatrolButton = button(patrol[2], "hearthstead.guard.command.start_patrol",
            GuardOrderActionPayload.Kind.START_PATROL);

        traversalButton = button(layout.traversal(),
            traversal() == GuardOrder.Traversal.LOOP
                ? "hearthstead.guard.command.traversal_loop"
                : "hearthstead.guard.command.traversal_ping_pong",
            GuardOrderActionPayload.Kind.TOGGLE_TRAVERSAL);
        towerButton = button(layout.tower(), "hearthstead.guard.command.tower",
            GuardOrderActionPayload.Kind.TOWER_POST);

        // The footer "Back" button is the standard wooden close key; Esc
        // (onClose) returns to the settler sheet the same way.
        addRenderableWidget(Ui2Frame.closeKey(layout.frame(),
            Component.translatable("hearthstead.guard.command.back").append(" (Esc)"),
            this::returnToParent));
        refreshButtonStates();
        if (focusedIndex >= 0 && focusedIndex < children().size()) {
            setFocused(children().get(focusedIndex));
        }
    }

    /** A text action left-aligned in its column, as wide as its label allows. */
    private HsButton button(Rect column, String key, GuardOrderActionPayload.Kind kind) {
        Component label = Component.translatable(key);
        // +8 reserves the lock glyph a disabled text button draws before its
        // label; without it every locked action ellipsized ("Start Pa...").
        int w = Math.min(column.width(), Ui2Button.textWidth(font, label) + 8);
        HsButton button = Ui2Button.secondary(column.x(), column.y(), w,
            column.height(), label, () -> send(kind));
        // The full label stays readable even if one translation is wider
        // than its bounded column.
        Ui2Tips.tip(button, label);
        addRenderableWidget(button);
        return button;
    }

    private void refreshButtonStates() {
        boolean manage = snapshot != null && snapshot.canManage() && !waiting;
        Component locked = snapshot == null ? LOADING : waiting ? WAITING : READ_ONLY;
        int points = snapshot == null ? 0 : snapshot.patrolPoints().size();
        Ui2Tips.enable(holdButton, manage, Component.translatable(
            "hearthstead.guard.command.hold_tip"), locked);
        Ui2Tips.enable(defendButton, manage, defendButton.getMessage(), locked);
        Ui2Tips.enable(stopButton, manage && mode() != GuardOrder.Mode.NONE,
            stopButton.getMessage(), manage ? Component.translatable(
                "hearthstead.guard.command.no_active_order") : locked);
        Ui2Tips.enable(addPointButton, manage && points < GuardOrder.MAX_PATROL_POINTS,
            addPointButton.getMessage(), manage ? Component.translatable(
                "hearthstead.guard.command.route_full") : locked);
        Ui2Tips.enable(removePointButton, manage && points > 0,
            removePointButton.getMessage(), manage ? Component.translatable(
                "hearthstead.guard.command.no_point") : locked);
        Component needTwo = Component.translatable("hearthstead.guard.command.need_two_points");
        Ui2Tips.enable(startPatrolButton, manage && points >= GuardOrder.MIN_PATROL_POINTS,
            startPatrolButton.getMessage(), manage ? needTwo : locked);
        Ui2Tips.enable(traversalButton, manage && points >= GuardOrder.MIN_PATROL_POINTS,
            traversalButton.getMessage(), manage ? needTwo : locked);
        boolean towerAvailable = snapshot != null && snapshot.towerPostAvailable();
        Ui2Tips.enable(towerButton, manage && towerAvailable,
            Component.translatable(towerAvailable
                ? "hearthstead.guard.command.tower_tip"
                : "hearthstead.guard.command.tower_locked_tip"),
            manage || snapshot != null && !towerAvailable
                ? Component.translatable("hearthstead.guard.command.tower_locked_tip") : locked);
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

    // Motion only: first-open intro (4 px slide + fade); created once, survives re-init.
    private HsMotion.ScreenIntro hsIntro;
    private boolean hsIntroRendering;

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY,
                       float partialTick) {
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
        if (waiting && Util.getMillis() - requestStartedMillis
                >= RESPONSE_TIMEOUT_MS) {
            waiting = false;
            failed = true;
            rebuildTextCache();
            refreshButtonStates();
        }
        refreshVisualCache();
        Ui2FrameLayout frame = layout.frame();
        Ui2Frame.draw(graphics, frame);
        Ui2Frame.title(graphics, font, frame, titleText, title.getString(), SUBTITLE);

        // Status: the active order in ink, the latest server feedback below.
        Rect status = layout.status();
        Ui2Frame.status(graphics, font, status, Component.empty(), statusTone());
        graphics.drawString(font, orderLine.text(), status.x() + 14, status.y() + 4,
            Ui2Palette.INK, false);
        if (compact) {
            graphics.drawString(font, feedbackLine.text(), status.x() + 14, status.y() + 14,
                feedbackColour(), false);
        } else {
            HsUi.drawLines(graphics, font, feedbackLines, status.x() + 14, status.y() + 15,
                feedbackColour());
        }

        Rect patrolRow = layout.patrolHeading();
        Ui2Frame.heading(graphics, font, patrolHeading, PATROL_TITLE.getString(),
            patrolRow.x(), patrolRow.y(), Math.max(1, patrolRow.width()
                - Math.min(patrolRow.width() / 2, patrolCountLine.width()) - Ui2FrameLayout.M));
        graphics.drawString(font, patrolCountLine.text(),
            patrolRow.right() - patrolCountLine.width(), patrolRow.y() + 3, Ui2Palette.INK_MUTED, false);
        drawPoints(graphics, mouseX, mouseY);

        Rect towerRow = layout.towerHeading();
        Ui2Frame.heading(graphics, font, towerHeading, TOWER_TITLE.getString(),
            towerRow.x(), towerRow.y(), towerRow.width());

        HsUi.widgets(this, graphics, mouseX, mouseY, partialTick);
        renderInformationTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void updateNarrationState(net.minecraft.client.gui.narration.NarrationElementOutput output) {
        super.updateNarrationState(output);
        output.add(net.minecraft.client.gui.narration.NarratedElementType.HINT, SUBTITLE, cachedStatus, cachedFeedback);
    }

    /** A ready guard is neutral gold; only a concrete refusal is red. */
    private Ui2Frame.Tone statusTone() {
        if (failed || snapshot != null && snapshot.outcome()
                == GuardOrderSnapshotPayload.Outcome.REFUSED) return Ui2Frame.Tone.BAD;
        if (snapshot != null && !snapshot.equipmentReady()) return Ui2Frame.Tone.WAIT;
        return Ui2Frame.Tone.NEUTRAL;
    }

    private int feedbackColour() {
        return failed || snapshot != null && snapshot.outcome()
            == GuardOrderSnapshotPayload.Outcome.REFUSED ? Ui2Palette.DANGER : Ui2Palette.INK_SOFT;
    }

    private void refreshVisualCache() {
        String language = minecraft.getLanguageManager().getSelected();
        if (!visualDirty && visualFont == font && visualWidth == panelWidth
            && visualLanguage.equals(language)) return;
        int innerWidth = layout.frame().content().width();
        int statusText = layout.status().width() - 18;
        orderLine = HsUi.fitLabel(font, cachedStatus, statusText);
        feedbackLine = HsUi.fitLabel(font, cachedFeedback, statusText);
        List<net.minecraft.util.FormattedCharSequence> wrappedFeedback = font.split(cachedFeedback, statusText);
        feedbackLines = List.copyOf(wrappedFeedback.subList(0, Math.min(2, wrappedFeedback.size())));
        patrolCountLine = HsUi.fitLabel(font, cachedPatrolCount, layout.patrolHeading().width() / 2);
        List<net.minecraft.util.FormattedCharSequence> wrappedNoPoints = font.split(NO_POINTS, innerWidth - 8);
        int noPointRows = Math.max(1, layout.points().height() / 10);
        noPointLines = List.copyOf(wrappedNoPoints.subList(0, Math.min(noPointRows, wrappedNoPoints.size())));
        java.util.ArrayList<HsUi.FittedLabel> fitted = new java.util.ArrayList<>();
        java.util.ArrayList<List<Component>> tooltips = new java.util.ArrayList<>();
        int pointWidth = layout.point(0).width() - 8;
        for (Component point : cachedPointLabels) {
            fitted.add(HsUi.fitLabel(font, point, pointWidth));
            tooltips.add(List.of(point));
        }
        pointLines = List.copyOf(fitted);
        pointTooltips = List.copyOf(tooltips);
        statusTooltip = List.of(cachedStatus, cachedFeedback);
        visualDirty = false;
        visualFont = font;
        visualWidth = panelWidth;
        visualLanguage = language;
    }

    private void drawPoints(GuiGraphics graphics, int mouseX, int mouseY) {
        Rect list = layout.points();
        if (pointLines.isEmpty()) {
            HsUi.drawLines(graphics, font, noPointLines, list.x() + 4, list.y() + 2, Ui2Palette.INK_MUTED);
            return;
        }
        for (int i = 0; i < pointLines.size() && i < POINT_ROWS * 2; i++) {
            Rect r = layout.point(i);
            Ui2Surface.row(graphics, r.x(), r.y(), r.width(), r.height(),
                r.contains(mouseX, mouseY) ? 1.0F : 0.0F, false);
            graphics.drawString(font, pointLines.get(i).text(), r.x() + 4, r.y() + 2, Ui2Palette.INK, false);
        }
        // Hairline between the two columns of the numbered route.
        Rect second = layout.point(POINT_ROWS);
        Ui2Surface.ruleVertical(graphics, second.x() - Ui2FrameLayout.M / 2, list.y(), list.height());
    }

    private void renderInformationTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        Rect t = layout.frame().title();
        if (t.contains(mouseX, mouseY)) {
            graphics.renderComponentTooltip(font, SUBTITLE_TOOLTIP, mouseX, mouseY);
            return;
        }
        if (layout.status().contains(mouseX, mouseY)) {
            graphics.renderComponentTooltip(font, statusTooltip, mouseX, mouseY);
            return;
        }
        for (int i = 0; i < pointTooltips.size() && i < POINT_ROWS * 2; i++) {
            if (layout.point(i).contains(mouseX, mouseY)) {
                graphics.renderComponentTooltip(font, pointTooltips.get(i), mouseX, mouseY);
                return;
            }
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
        visualDirty = true;
        cachedStatus = buildStatusLine();
        cachedFeedback = failed ? NO_REPLY : waiting ? WAITING
            : snapshot == null || snapshot.feedback().isEmpty()
                ? HELP : snapshot.feedback().get();
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
    public boolean isPauseScreen() {
        return false;
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

    // ------------------------------------------------------------ layout ---

    /**
     * Pure geometry for the command board (screen pixels). Content, top to
     * bottom: status strip; the three orders; the patrol heading with the
     * point count and the traversal toggle; the three patrol actions; the
     * numbered route (2 x 4 dense rows); the tower heading with its action,
     * anchored to the bottom of the page. Actions sit in three equal
     * columns so every row lines up.
     */
    static Layout layoutFor(int viewportWidth, int viewportHeight) {
        Ui2FrameLayout frame = Ui2FrameLayout.centred(viewportWidth, viewportHeight, PANEL_W, PANEL_H, true);
        Rect c = frame.content();
        int m = Ui2FrameLayout.M;
        int s = Ui2FrameLayout.S;
        int rowH = Ui2FrameLayout.TEXT_BUTTON_H;
        int pointsH = POINT_ROWS * POINT_ROW_H;
        int fixed = m + rowH + m + rowH + s + rowH + s + pointsH + m + rowH;
        boolean compact = frame.width() < PANEL_W || c.height() < STATUS_H + fixed;
        int statusH = compact ? STATUS_H_COMPACT : STATUS_H;
        Rect status = new Rect(c.x(), c.y(), c.width(), statusH);
        int y = status.bottom() + m;
        Rect[] orders = columns(c, y, rowH);
        y += rowH + m;
        Rect[] headingCols = columns(c, y, rowH);
        Rect patrolHeading = new Rect(c.x(), y, headingCols[2].x() - m - c.x(), rowH);
        Rect traversal = headingCols[2];
        y += rowH + s;
        Rect[] patrol = columns(c, y, rowH);
        y += rowH + s;
        Rect points = new Rect(c.x(), y, c.width(), pointsH);
        int towerY = Math.max(points.bottom() + m, c.bottom() - rowH);
        Rect[] towerCols = columns(c, towerY, rowH);
        Rect towerHeading = new Rect(c.x(), towerY, towerCols[2].x() - m - c.x(), rowH);
        return new Layout(frame, compact, status, orders, patrolHeading, traversal, patrol, points,
            towerHeading, towerCols[2]);
    }

    private static Rect[] columns(Rect c, int y, int h) {
        int m = Ui2FrameLayout.M;
        int w = (c.width() - m * 2) / 3;
        return new Rect[] {
            new Rect(c.x(), y, w, h),
            new Rect(c.x() + w + m, y, w, h),
            new Rect(c.right() - w, y, w, h)};
    }

    record Layout(Ui2FrameLayout frame, boolean compact, Rect status, Rect[] orders, Rect patrolHeading,
                  Rect traversal, Rect[] patrol, Rect points, Rect towerHeading, Rect tower) {
        /** Patrol point {@code i}: column {@code i / 4}, row {@code i % 4}. */
        Rect point(int i) {
            int m = Ui2FrameLayout.M;
            int w = (points.width() - m) / 2;
            int col = i / POINT_ROWS;
            int row = i % POINT_ROWS;
            return new Rect(col == 0 ? points.x() : points.right() - w,
                points.y() + row * POINT_ROW_H, w, POINT_ROW_H);
        }
    }
}
