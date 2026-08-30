package com.hearthstead.client.screen;

import com.hearthstead.building.BuildingType;
import com.hearthstead.client.QaUiInspectable;
import com.hearthstead.client.ui.HsButton;
import com.hearthstead.client.ui.HsUi;
import com.hearthstead.client.ui.HsUiTokens;
import com.hearthstead.network.EquipmentRequestListPayload;
import com.hearthstead.network.EquipmentRequestListRequestPayload;
import com.hearthstead.network.EquipmentRequestMovePayload;
import com.hearthstead.network.SettlerSnapshotPayload;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server-authored delivery order for one exact inspected Courier session.
 *
 * <p>Dragging is only a local preview until mouse release sends one narrow
 * revision-checked move. There is no per-frame network traffic and the client
 * never claims, completes, creates or otherwise owns a request or item.
 */
public final class EquipmentRequestListScreen extends Screen
        implements QaUiInspectable {

    private static final int DESIGNED_W = 430;
    private static final int DESIGNED_H = 356;
    private static final int PAD = 10;
    private static final int TITLE_Y = 12;
    private static final int SUBTITLE_Y = 28;
    private static final int HELP_Y = 41;
    private static final int DIVIDER_Y = 56;
    private static final int SUMMARY_Y = 62;
    private static final int LIST_TOP = 78;
    private static final int CARD_H = 44;
    private static final int CARD_STEP = 47;
    private static final int FOOTER_H = 38;
    private static final int BUTTON_W = 78;
    private static final int POSITION_W = 26;
    private static final int HANDLE_W = 13;
    /** English canonical worst case "Courier assigned" is 86 vanilla pixels. */
    private static final int STATUS_W = 90;
    private static final long RESPONSE_TIMEOUT_MS = 5_000L;
    private static final long AUTO_SCROLL_INTERVAL_MS = 110L;

    private static final Component SUBTITLE = Component.translatable(
        "hearthstead.equipment.list.subtitle");
    private static final Component DRAG_HELP = Component.translatable(
        "hearthstead.equipment.list.drag_help");
    private static final Component READ_ONLY = Component.translatable(
        "hearthstead.equipment.list.read_only");
    private static final Component SAVING = Component.translatable(
        "hearthstead.equipment.list.saving");
    private static final Component WAITING = Component.translatable(
        "hearthstead.equipment.list.waiting");
    private static final Component EMPTY = Component.translatable(
        "hearthstead.equipment.list.empty");
    private static final Component NO_REPLY = Component.translatable(
        "hearthstead.equipment.list.no_reply");
    private static final Component NO_HELP = Component.empty();
    private static final Component[] POSITION_LABELS = positionLabels();

    private final Screen parent;
    private final int courierEntityId;
    private final UUID courierId;
    private final UUID sessionId;
    private EquipmentRequestListPayload snapshot;
    private CachedRow[] rows = new CachedRow[0];
    private Component readySummary = EMPTY;
    private boolean requestIssued;
    private boolean waiting;
    private boolean failed;
    private boolean movePending;
    private boolean uiSoundActive;
    private long requestStartedMillis;
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;
    private int visibleRows;
    private int scroll;
    private List<Component> pendingTooltip;

    /** Absolute snapshot-row indices while one row is being dragged. */
    private int dragSource = -1;
    private int dragTarget = -1;
    private long lastAutoScrollMillis;

    public EquipmentRequestListScreen(Screen parent, int courierEntityId,
                                      UUID courierId, UUID sessionId) {
        super(Component.translatable("hearthstead.equipment.list.title"));
        this.parent = parent;
        this.courierEntityId = courierEntityId;
        this.courierId = courierId;
        this.sessionId = sessionId;
    }

    /** Delayed/stale packets from another sheet can never repaint this one. */
    public boolean accepts(EquipmentRequestListPayload fresh) {
        return fresh != null && fresh.courierEntityId() == courierEntityId
            && fresh.courierId().equals(courierId)
            && fresh.sessionId().equals(sessionId);
    }

    /** Keep the hidden parent inspection live without reopening either view. */
    public boolean acceptsParentSnapshot(SettlerSnapshotPayload fresh) {
        return parent instanceof SettlerScreen sheet
            && sheet.acceptsSnapshot(fresh);
    }

    public void updateParentSnapshot(SettlerSnapshotPayload fresh) {
        if (parent instanceof SettlerScreen sheet
            && sheet.acceptsSnapshot(fresh)) {
            sheet.update(fresh);
        }
    }

    /** Swap in one immutable server result and rebuild only on receipt. */
    public void update(EquipmentRequestListPayload fresh) {
        if (!accepts(fresh)) {
            return;
        }
        cancelDrag();
        snapshot = fresh;
        waiting = false;
        failed = false;
        movePending = false;
        rebuildCache();
        readySummary = rows.length == 0 ? EMPTY
            : Component.translatable("hearthstead.equipment.list.summary",
                snapshot.totalRequests(), rows.length);
        scroll = Math.max(0, Math.min(scroll,
            Math.max(0, rows.length - visibleRows)));
    }

    @Override
    protected void init() {
        panelWidth = Math.min(DESIGNED_W, Math.max(1, width - 16));
        panelHeight = Math.min(DESIGNED_H, Math.max(1, height - 16));
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        visibleRows = Math.max(1,
            (panelHeight - LIST_TOP - FOOTER_H) / CARD_STEP);
        rebuildCache();
        rebuildButtons();
        if (!requestIssued) {
            requestSnapshot();
        }
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

    private void rebuildButtons() {
        clearWidgets();
        int buttonY = top + panelHeight - 27;
        addRenderableWidget(HsButton.normal(left + PAD, buttonY,
            BUTTON_W, HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.equipment.list.refresh"),
            this::requestSnapshot));
        addRenderableWidget(HsButton.normal(
            left + panelWidth - PAD - BUTTON_W, buttonY,
            BUTTON_W, HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.equipment.list.back"),
            this::returnToParent));
    }

    private void requestSnapshot() {
        cancelDrag();
        requestIssued = true;
        failed = false;
        waiting = true;
        movePending = false;
        requestStartedMillis = Util.getMillis();
        if (minecraft == null || minecraft.getConnection() == null) {
            waiting = false;
            failed = true;
            return;
        }
        PacketDistributor.sendToServer(new EquipmentRequestListRequestPayload(
            courierEntityId, courierId, sessionId));
    }

    private void rebuildCache() {
        if (snapshot == null || minecraft == null) {
            rows = new CachedRow[0];
            return;
        }
        int textWidth = rowTextWidth();
        CachedRow[] rebuilt = new CachedRow[snapshot.rows().size()];
        for (int i = 0; i < rebuilt.length; i++) {
            rebuilt[i] = CachedRow.create(snapshot.rows().get(i), textWidth,
                minecraft.font);
        }
        rows = rebuilt;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY,
                       float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        if ((waiting || movePending) && Util.getMillis() - requestStartedMillis
                >= RESPONSE_TIMEOUT_MS) {
            waiting = false;
            movePending = false;
            failed = true;
        }
        pendingTooltip = null;

        HsUi.window(graphics, left, top, panelWidth, panelHeight);
        HsUi.centred(graphics, font, title, left + panelWidth / 2,
            top + TITLE_Y, HsUiTokens.TEXT_STRONG);
        drawClipped(graphics, SUBTITLE, left + PAD, top + SUBTITLE_Y,
            panelWidth - 2 * PAD, HsUiTokens.TEXT_MUTED);
        Component helper = snapshot == null ? NO_HELP
            : snapshot.canReorder() ? DRAG_HELP : READ_ONLY;
        drawClipped(graphics, helper, left + PAD, top + HELP_Y,
            panelWidth - 2 * PAD, snapshot != null && snapshot.canReorder()
                ? HsUiTokens.ACCENT : HsUiTokens.TEXT_MUTED);
        HsUi.divider(graphics, left + PAD, top + DIVIDER_Y,
            panelWidth - 2 * PAD);

        Component state = stateLine();
        drawClipped(graphics, state, left + PAD, top + SUMMARY_Y,
            panelWidth - 2 * PAD,
            failed ? HsUiTokens.WARN : HsUiTokens.TEXT_MUTED);
        if (!waiting && !failed) {
            drawRows(graphics, mouseX, mouseY);
        }

        HsUi.scrollbar(graphics, left + panelWidth - 5, top + LIST_TOP,
            visibleRows * CARD_STEP - 3,
            Math.min(1.0F, (float) visibleRows / Math.max(1, rows.length)),
            rows.length <= visibleRows ? 0.0F
                : (float) scroll / (rows.length - visibleRows), false);
        HsUi.divider(graphics, left + PAD, top + panelHeight - FOOTER_H,
            panelWidth - 2 * PAD);
        HsUi.widgets(this, graphics, mouseX, mouseY, partialTick);
        if (pendingTooltip != null && dragSource < 0) {
            graphics.renderComponentTooltip(font, pendingTooltip, mouseX, mouseY);
        }
    }

    private Component stateLine() {
        if (movePending) {
            return SAVING;
        }
        if (waiting) {
            return WAITING;
        }
        if (failed) {
            return NO_REPLY;
        }
        if (snapshot == null) {
            return WAITING;
        }
        return rows.length == 0 ? EMPTY : readySummary;
    }

    /** Draws the locally reordered array so neighbouring cards slide live. */
    private void drawRows(GuiGraphics graphics, int mouseX, int mouseY) {
        int last = Math.min(rows.length, scroll + visibleRows);
        for (int visualIndex = scroll; visualIndex < last; visualIndex++) {
            int sourceIndex = sourceIndexAtVisual(visualIndex);
            CachedRow row = rows[sourceIndex];
            int y = top + LIST_TOP + (visualIndex - scroll) * CARD_STEP;
            boolean hovered = insideCard(mouseX, mouseY, y);
            boolean dragged = sourceIndex == dragSource;
            drawRow(graphics, row, positionLabel(visualIndex + 1), y,
                hovered, dragged);
            if (hovered && !dragged) {
                pendingTooltip = row.tooltip;
            }
        }
    }

    private void drawRow(GuiGraphics graphics, CachedRow row,
                         Component position, int y, boolean hovered,
                         boolean dragged) {
        int cardX = left + PAD;
        int cardWidth = panelWidth - 2 * PAD;
        HsUi.card(graphics, cardX, y, cardWidth, CARD_H,
            hovered || dragged);
        if (dragged) {
            graphics.fill(cardX, y, cardX + 2, y + CARD_H,
                HsUiTokens.ACCENT);
        }
        HsUi.centred(graphics, font, position,
            cardX + POSITION_W / 2 + 1, y + 18, HsUiTokens.TEXT_STRONG);

        int textX = cardX + POSITION_W;
        int textWidth = rowTextWidth();
        drawClipped(graphics, row.title, textX, y + 4,
            textWidth - STATUS_W - 5, HsUiTokens.TEXT_STRONG);
        drawClipped(graphics, row.status,
            textX + textWidth - STATUS_W, y + 4,
            STATUS_W, row.statusColour);
        drawClipped(graphics, row.meta, textX, y + 17,
            textWidth, row.priorityColour);
        drawClipped(graphics, row.reason, textX, y + 30,
            textWidth, HsUiTokens.TEXT_MUTED);

        if (snapshot != null && snapshot.canReorder()) {
            int handleX = cardX + cardWidth - 9;
            int colour = dragged ? HsUiTokens.ACCENT : HsUiTokens.TEXT_MUTED;
            graphics.fill(handleX, y + 17, handleX + 5, y + 18, colour);
            graphics.fill(handleX, y + 21, handleX + 5, y + 22, colour);
            graphics.fill(handleX, y + 25, handleX + 5, y + 26, colour);
        }
    }

    /** Scissor clipping keeps translated/player text bounded without copies. */
    private void drawClipped(GuiGraphics graphics, Component text,
                             int x, int y, int width, int colour) {
        if (width <= 0) {
            return;
        }
        graphics.enableScissor(x, y, x + width, y + HsUiTokens.TEXT_H + 1);
        graphics.drawString(font, text, x, y, colour, true);
        graphics.disableScissor();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && canStartDrag()) {
            int index = rowAt(mouseX, mouseY);
            if (index >= 0) {
                dragSource = index;
                dragTarget = index;
                lastAutoScrollMillis = Util.getMillis();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                double dragX, double dragY) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && dragSource >= 0) {
            autoScroll(mouseY);
            updateDragTarget(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && dragSource >= 0) {
            if (insideList(mouseX, mouseY)) {
                commitDrag();
            } else {
                cancelDrag();
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double scrollX, double scrollY) {
        if (rows.length > visibleRows) {
            int before = scroll;
            scroll = Math.max(0, Math.min(rows.length - visibleRows,
                scroll - (int) Math.signum(scrollY)));
            if (before != scroll) {
                if (dragSource >= 0) {
                    updateDragTarget(mouseY);
                }
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && dragSource >= 0) {
            cancelDrag();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private boolean canStartDrag() {
        return snapshot != null && snapshot.canReorder() && !waiting
            && !failed && !movePending && rows.length > 1;
    }

    private int rowAt(double mouseX, double mouseY) {
        if (!insideList(mouseX, mouseY)) {
            return -1;
        }
        int visible = (int) ((mouseY - (top + LIST_TOP)) / CARD_STEP);
        int yWithin = (int) (mouseY - (top + LIST_TOP)) % CARD_STEP;
        int index = scroll + visible;
        return yWithin < CARD_H && index >= 0 && index < rows.length
            ? index : -1;
    }

    private boolean insideCard(double mouseX, double mouseY, int y) {
        return mouseX >= left + PAD && mouseX < left + panelWidth - PAD
            && mouseY >= y && mouseY < y + CARD_H;
    }

    private boolean insideList(double mouseX, double mouseY) {
        return mouseX >= left + PAD && mouseX < left + panelWidth - PAD
            && mouseY >= top + LIST_TOP
            && mouseY < top + LIST_TOP + visibleRows * CARD_STEP;
    }

    private void updateDragTarget(double mouseY) {
        int visible = (int) Math.floor((mouseY - (top + LIST_TOP)
            + CARD_STEP / 2.0D) / CARD_STEP);
        dragTarget = Math.max(0, Math.min(rows.length - 1,
            scroll + visible));
    }

    private void autoScroll(double mouseY) {
        long now = Util.getMillis();
        if (now - lastAutoScrollMillis < AUTO_SCROLL_INTERVAL_MS) {
            return;
        }
        int listY = top + LIST_TOP;
        int listBottom = listY + visibleRows * CARD_STEP;
        int before = scroll;
        if (mouseY < listY + 12 && scroll > 0) {
            scroll--;
        } else if (mouseY > listBottom - 12
            && scroll < rows.length - visibleRows) {
            scroll++;
        }
        if (scroll != before) {
            lastAutoScrollMillis = now;
        }
    }

    private void commitDrag() {
        if (!canStartDrag() || dragSource == dragTarget || snapshot == null) {
            cancelDrag();
            return;
        }
        UUID moved = snapshot.rows().get(dragSource).requestId();
        List<UUID> remaining = new ArrayList<>(snapshot.rows().size() - 1);
        for (int i = 0; i < snapshot.rows().size(); i++) {
            if (i != dragSource) {
                remaining.add(snapshot.rows().get(i).requestId());
            }
        }
        int insertion = Math.max(0, Math.min(dragTarget, remaining.size()));
        UUID before = insertion < remaining.size() ? remaining.get(insertion)
            : snapshot.nextRequestId();
        long revision = snapshot.queueRevision();
        UUID settlementId = snapshot.settlementId();
        cancelDrag();
        movePending = true;
        requestStartedMillis = Util.getMillis();
        PacketDistributor.sendToServer(new EquipmentRequestMovePayload(
            courierEntityId, courierId, sessionId, settlementId, revision,
            moved, before));
    }

    /** Maps a visual slot to the immutable server row during local preview. */
    private int sourceIndexAtVisual(int visualIndex) {
        if (dragSource < 0 || dragTarget < 0 || dragSource == dragTarget) {
            return visualIndex;
        }
        if (dragSource < dragTarget) {
            if (visualIndex < dragSource || visualIndex > dragTarget) {
                return visualIndex;
            }
            return visualIndex == dragTarget ? dragSource : visualIndex + 1;
        }
        if (visualIndex < dragTarget || visualIndex > dragSource) {
            return visualIndex;
        }
        return visualIndex == dragTarget ? dragSource : visualIndex - 1;
    }

    private void cancelDrag() {
        dragSource = -1;
        dragTarget = -1;
    }

    private int rowTextWidth() {
        return panelWidth - 2 * PAD - POSITION_W - HANDLE_W - 4;
    }

    @Override
    public void onClose() {
        if (dragSource >= 0) {
            cancelDrag();
            return;
        }
        returnToParent();
    }

    private void returnToParent() {
        cancelDrag();
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public String qaUiState() {
        return "rows=" + rows.length + ",visible=" + visibleRows
            + ",scroll=" + scroll + "/"
            + Math.max(0, rows.length - visibleRows)
            + ",drag=" + dragSource + "->" + dragTarget
            + ",waiting=" + waiting + ",failed=" + failed
            + ",movePending=" + movePending + ",panel=" + left + ":"
            + top + ":" + panelWidth + ":" + panelHeight;
    }

    private static Component[] positionLabels() {
        Component[] labels = new Component[EquipmentRequestListPayload.MAX_ROWS];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = Component.translatable(
                "hearthstead.equipment.list.row.position", i + 1);
        }
        return labels;
    }

    private static Component positionLabel(int position) {
        int index = Math.max(1, Math.min(POSITION_LABELS.length, position)) - 1;
        return POSITION_LABELS[index];
    }

    private record CachedRow(Component title, Component status, Component meta,
                             Component reason, int statusColour,
                             int priorityColour, List<Component> tooltip) {

        private static CachedRow create(EquipmentRequestListPayload.Row source,
                                        int textWidth,
                                        net.minecraft.client.gui.Font font) {
            Component title = Component.translatable(
                "hearthstead.equipment.list.row.item",
                source.requesterName(), source.count(),
                source.item().getHoverName());
            Component priority = priority(source.priorityWireId());
            Component destination = Component.translatable(
                "hearthstead.equipment.list.row.destination",
                BuildingType.byId(source.destinationType()).displayName());
            Component meta = Component.translatable(
                "hearthstead.equipment.list.row.meta", priority, destination);
            Component status = status(source.statusWireId());
            Component reason = reason(source.reasonWireId());
            Component position = Component.translatable(
                "hearthstead.equipment.list.row.position",
                source.queuePosition());
            List<Component> tooltip = List.of(position, title, destination,
                priority, status, reason);
            return new CachedRow(fit(title, textWidth - STATUS_W - 5, font),
                fit(status, STATUS_W, font), fit(meta, textWidth, font),
                fit(reason, textWidth, font), statusColour(source.statusWireId()),
                priorityColour(source.priorityWireId()), tooltip);
        }

        private static Component fit(Component component, int width,
                                     net.minecraft.client.gui.Font font) {
            if (width <= 0 || font.width(component) <= width) {
                return component;
            }
            int ellipsis = font.width("...");
            return Component.literal(font.plainSubstrByWidth(
                component.getString(), Math.max(0, width - ellipsis)) + "...");
        }

        private static Component priority(int id) {
            return Component.translatable(switch (id) {
                case 2 -> "hearthstead.equipment.priority.urgent";
                case 1 -> "hearthstead.equipment.priority.high";
                default -> "hearthstead.equipment.priority.normal";
            });
        }

        private static Component status(int id) {
            return Component.translatable(switch (id) {
                case 2 -> "hearthstead.equipment.status.delivered";
                case 1 -> "hearthstead.equipment.status.claimed";
                default -> "hearthstead.equipment.status.open";
            });
        }

        private static Component reason(int id) {
            return Component.translatable(switch (id) {
                case 2 -> "hearthstead.equipment.reason.worn";
                case 1 -> "hearthstead.equipment.reason.wrong_tool";
                default -> "hearthstead.equipment.reason.missing";
            });
        }

        private static int priorityColour(int id) {
            return id >= 2 ? HsUiTokens.WARN
                : id == 1 ? HsUiTokens.ACCENT : HsUiTokens.TEXT_MUTED;
        }

        private static int statusColour(int id) {
            return id == 2 ? HsUiTokens.GOOD
                : id == 1 ? HsUiTokens.ACCENT : HsUiTokens.TEXT_MUTED;
        }
    }
}
