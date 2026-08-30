package com.hearthstead.client.screen;

import com.hearthstead.client.QaUiInspectable;
import com.hearthstead.client.ui.HsButton;
import com.hearthstead.client.ui.HsUi;
import com.hearthstead.client.ui.HsUiTokens;
import com.hearthstead.network.WorkZoneActionPayload;
import com.hearthstead.network.WorkZoneSnapshotPayload;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Explicit non-colour-only confirmation for a server-validated 3D volume. */
public final class WorkZoneConfirmScreen extends Screen
        implements QaUiInspectable {
    private static final int PANEL_W = 360;
    private static final int PANEL_H = 226;
    private static final int PAD = 12;
    private static final long RESPONSE_TIMEOUT_MS = 5_000L;

    private WorkZoneSnapshotPayload snapshot;
    private int left;
    private int top;
    private int panelW;
    private int panelH;
    private boolean waiting;
    private boolean failed;
    private boolean uiSoundActive;
    private long requestStarted;
    private HsButton confirm;
    private HsButton cancel;

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
            refreshButtons();
        }
    }

    @Override
    protected void init() {
        Layout layout = layoutFor(width, height);
        panelW = layout.panelWidth();
        panelH = layout.panelHeight();
        left = layout.left();
        top = layout.top();
        clearWidgets();
        int gap = 8;
        int buttonW = Math.max(82, (panelW - PAD * 2 - gap) / 2);
        int y = top + panelH - PAD - HsUiTokens.BUTTON_H;
        confirm = addRenderableWidget(HsButton.normal(left + PAD, y,
            buttonW, HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.work_zone.screen.confirm"),
            () -> send(WorkZoneActionPayload.Kind.CONFIRM)));
        cancel = addRenderableWidget(HsButton.danger(
            left + panelW - PAD - buttonW, y, buttonW,
            HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.work_zone.screen.cancel"),
            () -> send(WorkZoneActionPayload.Kind.CANCEL)));
        refreshButtons();
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
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        if (waiting && Util.getMillis() - requestStarted >= RESPONSE_TIMEOUT_MS) {
            waiting = false;
            failed = true;
            refreshButtons();
        }
        HsUi.modalWindow(g, left, top, panelW, panelH);
        HsUi.centred(g, font, title, left + panelW / 2, top + 13,
            HsUiTokens.TEXT_STRONG);
        HsUi.labelIn(g, font, Component.translatable(
            "hearthstead.work_zone.screen.subtitle"), left + PAD, top + 31,
            panelW - PAD * 2, HsUiTokens.TEXT_MUTED);
        HsUi.divider(g, left + PAD, top + 48, panelW - PAD * 2);

        int cardY = top + 57;
        int cardH = Math.max(90, panelH - 110);
        HsUi.card(g, left + PAD, cardY, panelW - PAD * 2, cardH, false);
        int x = left + PAD + 8;
        int valueX = left + Math.min(144, panelW / 2);
        drawRow(g, "hearthstead.work_zone.screen.workplace", snapshot.workplace(),
            x, valueX, cardY + 9);
        drawRow(g, "hearthstead.work_zone.screen.type", Component.translatable(
            snapshot.typeWireId() == 0 ? "hearthstead.work_zone.type.lumber"
                : "hearthstead.work_zone.type.farm"), x, valueX, cardY + 25);
        BlockPos a = snapshot.cornerOne().orElse(BlockPos.ZERO);
        BlockPos b = snapshot.cornerTwo().orElse(a);
        drawRow(g, "hearthstead.work_zone.screen.bounds", Component.literal(
            format(a) + "  ->  " + format(b)), x, valueX, cardY + 41);
        int sx = Math.abs(a.getX() - b.getX()) + 1;
        int sy = Math.abs(a.getY() - b.getY()) + 1;
        int sz = Math.abs(a.getZ() - b.getZ()) + 1;
        drawRow(g, "hearthstead.work_zone.screen.size", Component.translatable(
            "hearthstead.work_zone.screen.size_value", sx, sy, sz,
            (long) sx * sy * sz), x, valueX, cardY + 57);
        drawRow(g, "hearthstead.work_zone.screen.status", status(),
            x, valueX, cardY + 73);
        HsUi.widgets(this, g, mouseX, mouseY, partialTick);
    }

    private void drawRow(GuiGraphics g, String key, Component value,
                         int x, int valueX, int y) {
        HsUi.labelIn(g, font, Component.translatable(key), x, y,
            Math.max(60, valueX - x - 4), HsUiTokens.TEXT_MUTED);
        HsUi.labelIn(g, font, value, valueX, y,
            left + panelW - PAD - 8 - valueX,
            failed ? HsUiTokens.BAD : HsUiTokens.TEXT_STRONG);
    }

    private Component status() {
        if (waiting) {
            return Component.translatable("hearthstead.work_zone.screen.waiting");
        }
        if (failed) {
            return snapshot.feedback().orElse(Component.translatable(
                "hearthstead.work_zone.screen.failed"));
        }
        return Component.translatable("hearthstead.work_zone.screen.validated");
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

    record Layout(int panelWidth, int panelHeight, int left, int top) { }
}
