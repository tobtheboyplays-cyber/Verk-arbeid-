package com.hearthstead.client.screen;

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
import com.hearthstead.network.WorkZoneActionPayload;
import com.hearthstead.network.WorkZoneSnapshotPayload;
import net.minecraft.Util;
import net.minecraft.util.FormattedCharSequence;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Explicit non-colour-only confirmation for a server-validated 3D volume. */
public final class WorkZoneConfirmScreen extends Screen
        implements QaUiInspectable {
    private static final int PANEL_W = 460;
    private static final int PANEL_H = 256; // size check: Banner footprint (was 320)
    private static final int BOUNDS_H = 43;
    private static final long RESPONSE_TIMEOUT_MS = 5_000L;

    private WorkZoneSnapshotPayload snapshot;
    private int left;
    private int top;
    private int panelW;
    private int panelH;
    private Ui2FrameLayout frame;
    private final Ui2Serif.Text titleText = new Ui2Serif.Text(Ui2Serif.Size.TITLE);
    private boolean waiting;
    private boolean failed;
    private boolean uiSoundActive;
    private long requestStarted;
    private HsButton confirm;
    private HsButton cancel;
    private Component subtitleLine = Component.empty();
    private Component boundsLabel = Component.empty();
    private Component firstCorner = Component.empty();
    private Component secondCorner = Component.empty();
    private Component statusLabel = Component.empty();
    private List<FormattedCharSequence> sizeLines = List.of();
    private List<FormattedCharSequence> statusLines = List.of();
    private List<FormattedCharSequence> headerTooltip = List.of();
    private List<FormattedCharSequence> workplaceTooltip = List.of();
    private List<FormattedCharSequence> boundsTooltip = List.of();
    private List<FormattedCharSequence> sizeTooltip = List.of();
    private List<FormattedCharSequence> statusTooltip = List.of();
    private List<FormattedCharSequence> confirmTooltip = List.of();
    private List<FormattedCharSequence> cancelTooltip = List.of();
    private String cachedLanguage = "";

    public WorkZoneConfirmScreen(WorkZoneSnapshotPayload snapshot) {
        super(Component.translatable("hearthstead.work_zone.screen.title"));
        this.snapshot = snapshot;
    }

    public boolean accepts(WorkZoneSnapshotPayload fresh) {
        return fresh != null && snapshot.sessionId().equals(fresh.sessionId())
            && snapshot.settlementId().equals(fresh.settlementId())
            && snapshot.buildingId().equals(fresh.buildingId());
    }

    /** Used on dimension changes to close this exact stale modal fail-closed. */
    public boolean matchesDimension(String dimension) {
        return dimension != null && dimension.equals(snapshot.dimension());
    }

    public void update(WorkZoneSnapshotPayload fresh) {
        if (!accepts(fresh)) {
            return;
        }
        snapshot = fresh;
        waiting = false;
        failed = fresh.stage() == WorkZoneSnapshotPayload.Stage.REJECTED
            || fresh.stage() == WorkZoneSnapshotPayload.Stage.RESET;
        if (fresh.stage() == WorkZoneSnapshotPayload.Stage.COMMITTED) {
            HsUi.playConfirmSound();
            uiSoundActive = false;
            if (minecraft != null && minecraft.screen == this) {
                minecraft.setScreen(null);
            }
        } else if (fresh.stage() == WorkZoneSnapshotPayload.Stage.CANCELLED) {
            if (uiSoundActive) {
                uiSoundActive = false;
                HsUi.playCloseSound();
            }
            if (minecraft != null && minecraft.screen == this) {
                minecraft.setScreen(null);
            }
        } else if (fresh.stage() == WorkZoneSnapshotPayload.Stage.RESET) {
            HsUi.playErrorSound();
            uiSoundActive = false;
            if (minecraft != null && minecraft.screen == this) {
                minecraft.setScreen(null);
            }
        } else if (failed) {
            HsUi.playErrorSound();
        }
        refreshButtons();
    }

    @Override
    protected void init() {
        Layout layout = layoutFor(width, height);
        panelW = layout.panelWidth();
        panelH = layout.panelHeight();
        left = layout.left();
        top = layout.top();
        boolean restoreConfirm = confirm != null && confirm.isFocused();
        boolean restoreCancel = cancel != null && cancel.isFocused();
        frame = layout.frame();
        clearWidgets();
        // Standard footer: the one burgundy primary on the right, the text
        // action to its left; Esc and the close key both cancel.
        Rect[] footer = frame.footerButtons(2, 132);
        Component cancelLabel = Component.translatable("hearthstead.work_zone.screen.cancel");
        // Cancel is disabled while waiting; the padlock needs 8 px beside the label.
        int cancelW = Math.min(footer[0].width(), Ui2Button.textWidth(font, cancelLabel) + 8);
        cancel = addRenderableWidget(Ui2Button.secondary(footer[0].right() - cancelW,
            footer[0].y() + (Ui2FrameLayout.BUTTON_H - Ui2FrameLayout.TEXT_BUTTON_H) / 2, cancelW,
            Ui2FrameLayout.TEXT_BUTTON_H, cancelLabel, () -> send(WorkZoneActionPayload.Kind.CANCEL)));
        confirm = addRenderableWidget(Ui2Button.banner(footer[1].x(), footer[1].y(), footer[1].width(),
            footer[1].height(), Component.translatable("hearthstead.work_zone.screen.confirm"),
            () -> send(WorkZoneActionPayload.Kind.CONFIRM)));
        addRenderableWidget(Ui2Frame.closeKey(frame, () -> send(WorkZoneActionPayload.Kind.CANCEL)));
        refreshButtons();
        if (restoreConfirm) setFocused(confirm);
        else if (restoreCancel) setFocused(cancel);
        if (!uiSoundActive) {
            uiSoundActive = true;
            HsUi.playOpenSound();
        }
    }

    @Override
    public void removed() {
        if (uiSoundActive) {
            uiSoundActive = false;
            HsUi.playCloseSound();
        }
        super.removed();
    }

    private void send(WorkZoneActionPayload.Kind kind) {
        if (waiting) {
            return;
        }
        if (minecraft == null || minecraft.getConnection() == null) {
            if (kind == WorkZoneActionPayload.Kind.CANCEL
                && minecraft != null && minecraft.screen == this) {
                minecraft.setScreen(null);
                return;
            }
            failed = true;
            refreshButtons();
            return;
        }
        waiting = true;
        failed = false;
        requestStarted = Util.getMillis();
        refreshButtons();
        PacketDistributor.sendToServer(new WorkZoneActionPayload(
            snapshot.sessionId(), snapshot.settlementId(), snapshot.buildingId(),
            snapshot.typeWireId(), kind, snapshot.expectedRevision(),
            java.util.Optional.empty()));
    }

    private void refreshButtons() {
        if (confirm != null) {
            confirm.active = !waiting && !failed
                && snapshot.stage() == WorkZoneSnapshotPayload.Stage.PREVIEW_READY;
        }
        if (cancel != null) {
            cancel.active = !waiting;
        }
        if (font != null && panelW > 0) rebuildPresentation();
    }

    // Motion only: first-open intro (4 px slide + fade); created once, survives re-init.
    private HsMotion.ScreenIntro hsIntro;
    private boolean hsIntroRendering;

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (hsIntro == null) hsIntro = new HsMotion.ScreenIntro();
        if (!hsIntroRendering && !hsIntro.done()) {
            hsIntroRendering = true;
            try {
                hsIntro.render(g, 4.0F, () -> render(g, mouseX, mouseY, partialTick));
            } finally {
                hsIntroRendering = false;
            }
            return;
        }
        renderBackground(g, mouseX, mouseY, partialTick);
        if (waiting && Util.getMillis() - requestStarted >= RESPONSE_TIMEOUT_MS) {
            waiting = false;
            failed = true;
            refreshButtons();
        }
        if (minecraft != null && !cachedLanguage.equals(
                minecraft.getLanguageManager().getSelected())) rebuildPresentation();
        Ui2Frame.draw(g, frame);
        Ui2Frame.title(g, font, frame, titleText, title.getString(), subtitleLine);

        // Decision first, then the exact selection it applies to. The status
        // block takes the spare height rather than hiding long reasons.
        Rect status = statusRect();
        Ui2Frame.status(g, font, status, statusLabel, statusTone());
        HsUi.drawLines(g, font, statusLines, status.x() + 14, status.y() + 17,
            waiting ? Ui2Palette.INK_SOFT : isValidated() && !failed ? Ui2Palette.FOREST : Ui2Palette.DANGER);

        Rect bounds = boundsRect();
        g.drawString(font, boundsLabel, bounds.x(), bounds.y() + 2, Ui2Palette.INK_MUTED, false);
        Ui2Surface.rule(g, bounds.x(), bounds.y() + 12, bounds.width());
        g.drawString(font, firstCorner, bounds.x(), bounds.y() + 17, Ui2Palette.INK, false);
        g.drawString(font, secondCorner, bounds.x(), bounds.y() + 30, Ui2Palette.INK, false);
        HsUi.drawLines(g, font, sizeLines, bounds.x(), bounds.bottom() + 4, Ui2Palette.INK_SOFT);
        HsUi.widgets(this, g, mouseX, mouseY, partialTick);

        List<FormattedCharSequence> tooltip = null;
        Rect t = frame.title();
        Rect b = boundsRect();
        if (over(mouseX, mouseY, t.x(), t.y(), t.width(), 14)) tooltip = headerTooltip;
        else if (over(mouseX, mouseY, t.x(), t.y() + 14, t.width(), 14)) tooltip = workplaceTooltip;
        else if (over(mouseX, mouseY, b.x(), b.y(), b.width(), b.height())) tooltip = boundsTooltip;
        else if (over(mouseX, mouseY, b.x(), b.bottom(), b.width(), 22)) tooltip = sizeTooltip;
        else if (statusRect().contains(mouseX, mouseY)) tooltip = statusTooltip;
        if (tooltip == null && confirm != null && confirm.isHoveredOrFocused()) tooltip = confirmTooltip;
        else if (tooltip == null && cancel != null && cancel.isHoveredOrFocused()) tooltip = cancelTooltip;
        if (tooltip != null && !tooltip.isEmpty()) g.renderTooltip(font, tooltip, mouseX, mouseY);
    }

    /** Rebuilt on snapshot/state/viewport/language changes, never for each frame. */
    private void rebuildPresentation() {
        if (minecraft != null) cachedLanguage = minecraft.getLanguageManager().getSelected();
        int inner = frame == null ? Math.max(1, panelW - 40) : frame.content().width();
        Component type = snapshot.typeWireId() == 0
            ? Component.translatable("hearthstead.work_zone.type.lumber")
            : snapshot.typeWireId() == 1
                ? Component.translatable("hearthstead.work_zone.type.farm") : Component.literal("—");
        Component workplace = field("workplace", snapshot.workplace());
        Component typeFact = field("type", type);
        Component bounds = Component.translatable("hearthstead.work_zone.screen.bounds");
        Component a = Component.literal("1  " + snapshot.cornerOne().map(WorkZoneConfirmScreen::format).orElse("—"));
        Component b = Component.literal("2  " + snapshot.cornerTwo().map(WorkZoneConfirmScreen::format).orElse("—"));
        Component size = Component.literal("—");
        if (snapshot.cornerOne().isPresent() && snapshot.cornerTwo().isPresent()) {
            BlockPos one = snapshot.cornerOne().orElseThrow();
            BlockPos two = snapshot.cornerTwo().orElseThrow();
            long sx = Math.abs((long) one.getX() - two.getX()) + 1L;
            long sy = Math.abs((long) one.getY() - two.getY()) + 1L;
            long sz = Math.abs((long) one.getZ() - two.getZ()) + 1L;
            java.math.BigInteger volume = java.math.BigInteger.valueOf(sx)
                .multiply(java.math.BigInteger.valueOf(sy)).multiply(java.math.BigInteger.valueOf(sz));
            size = Component.translatable("hearthstead.work_zone.screen.size_value", sx, sy, sz, volume.toString());
        }
        Component sizeFact = field("size", size);
        Component feedback = status();
        subtitleLine = snapshot.workplace().copy().append("  \u00b7  ").append(type);
        boundsLabel = fit(bounds, inner);
        firstCorner = fit(a, inner);
        secondCorner = fit(b, inner);
        statusLabel = fit(Component.translatable("hearthstead.work_zone.screen.status"), inner - 18);
        sizeLines = boundedLines(sizeFact, inner, 2);
        int statusH = frame == null ? 34 : statusRect().height();
        statusLines = boundedLines(feedback, inner - 18, Math.max(1, (statusH - 20) / 9));
        Component subtitle = Component.translatable("hearthstead.work_zone.screen.subtitle");
        headerTooltip = wrap(title, subtitle);
        workplaceTooltip = wrap(workplace, typeFact, Component.literal(snapshot.dimension()));
        boundsTooltip = wrap(bounds, a, b);
        sizeTooltip = wrap(sizeFact);
        statusTooltip = wrap(Component.translatable("hearthstead.work_zone.screen.status"), feedback);
        confirmTooltip = wrap(Component.translatable("hearthstead.work_zone.screen.confirm"), feedback, sizeFact);
        cancelTooltip = wrap(Component.translatable("hearthstead.work_zone.screen.cancel"), subtitle);
    }

    /** Status block: the body height left after the bounds block and size lines. */
    private Rect statusRect() {
        return statusRect(frame);
    }

    static Rect statusRect(Ui2FrameLayout frame) {
        Rect body = frame.body(false, true);
        int h = Math.max(34, body.height() - Ui2FrameLayout.M - BOUNDS_H - 4 - 18);
        return new Rect(body.x(), body.y(), body.width(), h);
    }

    private Rect boundsRect() {
        return boundsRect(frame);
    }

    static Rect boundsRect(Ui2FrameLayout frame) {
        Rect status = statusRect(frame);
        return new Rect(status.x(), status.bottom() + Ui2FrameLayout.M, status.width(), BOUNDS_H);
    }

    private static Component field(String key, Component value) {
        return Component.translatable("hearthstead.work_zone.screen." + key).append(": ").append(value);
    }

    private Component fit(Component text, int available) {
        return HsUi.fitLabel(font, text, Math.max(1, available)).text();
    }

    private List<FormattedCharSequence> boundedLines(Component text, int available, int limit) {
        List<FormattedCharSequence> lines = HsUi.fitLines(font, text, available);
        return List.copyOf(lines.subList(0, Math.min(limit, lines.size())));
    }

    private List<FormattedCharSequence> wrap(Component... facts) {
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (Component fact : facts) lines.addAll(font.split(fact, Math.max(80, width - 32)));
        return List.copyOf(lines);
    }

    private static boolean over(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    /** Bar and glyph only mirror the exact status text; they never change button eligibility. */
    private Ui2Frame.Tone statusTone() {
        if (failed || snapshot.stage() == WorkZoneSnapshotPayload.Stage.REJECTED
                || snapshot.stage() == WorkZoneSnapshotPayload.Stage.RESET) return Ui2Frame.Tone.BAD;
        if (isValidated()) return Ui2Frame.Tone.GOOD;
        return waiting ? Ui2Frame.Tone.WAIT : Ui2Frame.Tone.NEUTRAL;
    }
    private boolean isValidated() {
        return snapshot.stage() == WorkZoneSnapshotPayload.Stage.PREVIEW_READY
            && snapshot.cornerOne().isPresent() && snapshot.cornerTwo().isPresent();
    }

    @Override
    protected void rebuildWidgets() {
        boolean restoreConfirm = getFocused() == confirm && confirm != null;
        boolean restoreCancel = getFocused() == cancel && cancel != null;
        super.rebuildWidgets();
        if (restoreConfirm) setFocused(confirm);
        else if (restoreCancel) setFocused(cancel);
    }

    private Component status() {
        if (waiting) {
            return Component.translatable("hearthstead.work_zone.screen.waiting");
        }
        if (failed || snapshot.stage() == WorkZoneSnapshotPayload.Stage.REJECTED
                || snapshot.stage() == WorkZoneSnapshotPayload.Stage.RESET) {
            return snapshot.feedback().orElse(Component.translatable(
                "hearthstead.work_zone.screen.failed"));
        }
        if (isValidated()) return Component.translatable("hearthstead.work_zone.screen.validated");
        return snapshot.feedback().orElse(Component.translatable(switch (snapshot.stage()) {
            case TARGET_SELECTED -> "hearthstead.work_zone.result.selected";
            case CORNER_ONE -> "hearthstead.work_zone.result.first_corner";
            case CORNER_TWO -> "hearthstead.work_zone.result.second_corner";
            default -> "hearthstead.work_zone.screen.failed";
        }));
    }

    private static String format(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { // GLFW_ESCAPE
            send(WorkZoneActionPayload.Kind.CANCEL);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public String qaUiState() {
        return "stage=" + snapshot.stage().name().toLowerCase()
            + ",waiting=" + waiting + ",failed=" + failed
            + ",panel=" + left + ":" + top + ":" + panelW + ":" + panelH;
    }

    static Layout layoutFor(int viewportWidth, int viewportHeight) {
        int panelWidth = Math.min(PANEL_W, Math.max(1, viewportWidth - 16));
        int panelHeight = Math.min(PANEL_H, Math.max(1, viewportHeight - 16));
        return new Layout(panelWidth, panelHeight,
            (viewportWidth - panelWidth) / 2,
            (viewportHeight - panelHeight) / 2);
    }

    record Layout(int panelWidth, int panelHeight, int left, int top) {
        Ui2FrameLayout frame() {
            return Ui2FrameLayout.at(left, top, panelWidth, panelHeight, true);
        }
    }
}
