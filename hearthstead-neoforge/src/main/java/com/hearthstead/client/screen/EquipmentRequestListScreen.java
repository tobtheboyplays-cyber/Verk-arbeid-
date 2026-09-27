package com.hearthstead.client.screen;

import com.hearthstead.building.BuildingType;
import com.hearthstead.client.QaUiInspectable;
import com.hearthstead.client.ui.HsMotion;
import com.hearthstead.client.ui.HsUi;
import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import com.hearthstead.client.ui2.Ui2Button;
import com.hearthstead.client.ui2.Ui2Frame;
import com.hearthstead.client.ui2.Ui2FrameLayout;
import com.hearthstead.client.ui2.Ui2Palette;
import com.hearthstead.client.ui2.Ui2Serif;
import com.hearthstead.client.ui2.Ui2Surface;
import com.hearthstead.network.EquipmentRequestListPayload;
import com.hearthstead.network.EquipmentRequestListRequestPayload;
import com.hearthstead.network.EquipmentRequestMovePayload;
import com.hearthstead.network.SettlerSnapshotPayload;
import com.hearthstead.settlement.request.RequestBlocker;
import com.hearthstead.settlement.request.RequestState;
import net.minecraft.Util;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
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

    private static final int DESIGNED_W = 464; // size check: Banner footprint (was 520)
    private static final int DESIGNED_H = 256; // size check: Banner footprint (was 356)
    /** Server state strip above the list. */
    static final int STATUS_H = 16;
    static final int CARD_H = 44;
    static final int CARD_STEP = 46;
    static final int BUTTON_W = 78;
    static final int POSITION_W = 26;
    static final int HANDLE_W = 13;
    static final int SCROLL_GUTTER = 6;
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
    private Layout layout = layoutFor(DESIGNED_W + 16, DESIGNED_H + 16);
    private final Ui2Serif.Text titleText = new Ui2Serif.Text(Ui2Serif.Size.TITLE);
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;
    private int visibleRows;
    private int scroll;
    private int inspectedRow = -1;
    private boolean moveKeyHeld;
    private static final Component KEY_HELP = Component.translatable(
        "hearthstead.equipment.list.keyboard_help");
    private List<FormattedCharSequence> pendingTooltip;
    private List<FormattedCharSequence> headerTooltip = List.of();
    private List<FormattedCharSequence> helperTooltip = List.of();
    private List<FormattedCharSequence> stateTooltip = List.of();
    private Component helperLine = Component.empty();
    private Component fittedState = Component.empty();
    private Component cachedState;
    private String cachedLanguage = "";
    private Ui2Button refreshButton;
    private Ui2Button backButton;
    private List<FormattedCharSequence> refreshTooltip = List.of();
    private List<FormattedCharSequence> backTooltip = List.of();

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
        inspectedRow = -1;
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
        layout = layoutFor(width, height);
        panelWidth = layout.frame().width();
        panelHeight = layout.frame().height();
        left = layout.frame().x();
        top = layout.frame().y();
        visibleRows = layout.visibleRows();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.length - visibleRows)));
        cancelDrag();
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
        boolean restoreRefresh = refreshButton != null && refreshButton.isFocused();
        boolean restoreBack = backButton != null && backButton.isFocused();
        clearWidgets();
        // Standard footer: two text actions (refresh left, back right); the
        // wooden close key and Esc both go back to the settler sheet.
        Component refreshLabel = Component.translatable("hearthstead.equipment.list.refresh");
        Component backLabel = Component.translatable("hearthstead.equipment.list.back");
        Rect r = layout.refresh();
        int refreshW = Math.min(r.width(), Ui2Button.textWidth(font, refreshLabel));
        refreshButton = addRenderableWidget(Ui2Button.secondary(r.x(), r.y(), refreshW, r.height(),
            refreshLabel, this::requestSnapshot));
        Rect b = layout.back();
        int backW = Math.min(b.width(), Ui2Button.textWidth(font, backLabel));
        backButton = addRenderableWidget(Ui2Button.secondary(b.right() - backW, b.y(), backW, b.height(),
            backLabel, this::returnToParent));
        addRenderableWidget(Ui2Frame.closeKey(layout.frame(), this::onClose));
        if (restoreRefresh) setFocused(refreshButton);
        else if (restoreBack) setFocused(backButton);
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
        if (minecraft != null) {
            cachedLanguage = minecraft.getLanguageManager().getSelected();
            Component helper = snapshot == null ? NO_HELP
                : snapshot.canReorder() ? DRAG_HELP : READ_ONLY;
            helperLine = snapshot != null && snapshot.canReorder() ? KEY_HELP : helper;
            headerTooltip = wrap(title, SUBTITLE);
            helperTooltip = snapshot != null && snapshot.canReorder() ? wrap(helper, KEY_HELP) : wrap(helper);
            refreshTooltip = wrap(Component.translatable("hearthstead.equipment.list.refresh"));
            backTooltip = wrap(Component.translatable("hearthstead.equipment.list.back"));
            cachedState = null;
        }
        if (snapshot == null || minecraft == null) {
            rows = new CachedRow[0];
            return;
        }
        int textWidth = rowTextWidth();
        CachedRow[] rebuilt = new CachedRow[snapshot.rows().size()];
        for (int i = 0; i < rebuilt.length; i++) {
            rebuilt[i] = CachedRow.create(snapshot.rows().get(i), textWidth,
                minecraft.font, Math.max(80, width - 32));
        }
        rows = rebuilt;
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
        renderBackground(graphics, mouseX, mouseY, partialTick);
        if ((waiting || movePending) && Util.getMillis() - requestStartedMillis
                >= RESPONSE_TIMEOUT_MS) {
            waiting = false;
            movePending = false;
            failed = true;
        }
        pendingTooltip = null;

        if (minecraft != null && !cachedLanguage.equals(
                minecraft.getLanguageManager().getSelected())) {
            rebuildCache();
        }
        Ui2FrameLayout frame = layout.frame();
        Ui2Frame.draw(graphics, frame);
        Ui2Frame.title(graphics, font, frame, titleText, title.getString(), helperLine);

        Component state = stateLine();
        Rect status = layout.status();
        if (cachedState != state) {
            cachedState = state;
            fittedState = CachedRow.fit(state, status.width() - 18, font);
            stateTooltip = wrap(state);
        }
        Ui2Frame.status(graphics, font, status, fittedState, stateTone());
        Rect t = frame.title();
        if (t.contains(mouseX, mouseY) && mouseY < t.y() + 14) pendingTooltip = headerTooltip;
        else if (t.contains(mouseX, mouseY)) pendingTooltip = helperTooltip;
        else if (status.contains(mouseX, mouseY)) pendingTooltip = stateTooltip;
        if (!waiting && !failed) {
            drawRows(graphics, mouseX, mouseY);
        }

        Rect list = layout.list();
        if (rows.length > visibleRows) {
            Ui2Surface.scrollbar(graphics, list.right() - 2, list.y(), visibleRows * CARD_STEP - 2,
                Math.min(1.0F, (float) visibleRows / Math.max(1, rows.length)),
                (float) scroll / (rows.length - visibleRows));
        }
        HsUi.widgets(this, graphics, mouseX, mouseY, partialTick);
        if (pendingTooltip == null && refreshButton != null
                && refreshButton.isHoveredOrFocused()) pendingTooltip = refreshTooltip;
        else if (pendingTooltip == null && backButton != null
                && backButton.isHoveredOrFocused()) pendingTooltip = backTooltip;
        if (pendingTooltip != null && !pendingTooltip.isEmpty() && dragSource < 0) {
            graphics.renderTooltip(font, pendingTooltip, mouseX, mouseY);
        }
    }

    /** Bar and glyph mirror the state text only. */
    private Ui2Frame.Tone stateTone() {
        if (failed) return Ui2Frame.Tone.BAD;
        if (movePending || waiting || snapshot == null) return Ui2Frame.Tone.WAIT;
        return rows.length == 0 ? Ui2Frame.Tone.NEUTRAL : Ui2Frame.Tone.GOOD;
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
            Rect card = layout.row(visualIndex - scroll);
            int y = card.y();
            boolean hovered = insideCard(mouseX, mouseY, y);
            boolean focused = inspectedRow == sourceIndex;
            boolean dragged = sourceIndex == dragSource;
            if (visualIndex + 1 < last) {
                Ui2Surface.rule(graphics, card.x(), card.bottom() + 1, card.width());
            }
            drawRow(graphics, row, positionLabel(visualIndex + 1), visualIndex - scroll,
                hovered || focused, dragged);
            if (focused) Ui2Surface.focus(graphics, card.x(), card.y(), card.width(), card.height() - 1);
            if ((hovered || focused && pendingTooltip == null) && !dragged) {
                pendingTooltip = row.tooltip;
            }
        }
    }

    private void drawRow(GuiGraphics graphics, CachedRow row,
                         Component position, int visibleRow, boolean hovered,
                         boolean dragged) {
        Rect card = layout.row(visibleRow);
        int y = card.y();
        // The dragged ticket carries the selected bar; hover is the standard tint.
        Ui2Surface.row(graphics, card.x(), y, card.width(), card.height(), hovered ? 1.0F : 0.0F, dragged);
        Rect well = layout.well(visibleRow);
        Ui2Surface.slotWell(graphics, well.x(), well.y());
        graphics.renderItem(row.item, well.x() + 1, well.y() + 1);
        drawClipped(graphics, position, well.x(), well.bottom() + 3,
            POSITION_W - 4, Ui2Palette.INK_MUTED);

        int textX = layout.textX();
        int textWidth = rowTextWidth();
        if (!row.titleLines.isEmpty()) {
            graphics.drawString(font, row.titleLines.get(0), textX, y + 3, Ui2Palette.INK, false);
        }
        drawClipped(graphics, row.status, textX, y + 13,
            textWidth, row.statusColour);
        drawClipped(graphics, row.meta, textX, y + 23,
            textWidth, row.priorityColour);
        drawClipped(graphics, row.reason, textX, y + 33,
            textWidth, Ui2Palette.INK_MUTED);

        if (snapshot != null && snapshot.canReorder()) {
            int handleX = layout.handleX();
            int cy = y + CARD_H / 2;
            int colour = dragged ? Ui2Palette.INK_SOFT : Ui2Palette.INK_MUTED;
            graphics.fill(handleX, cy - 4, handleX + 5, cy - 3, colour);
            graphics.fill(handleX, cy, handleX + 5, cy + 1, colour);
            graphics.fill(handleX, cy + 4, handleX + 5, cy + 5, colour);
        }
    }

    /** Scissor clipping keeps translated/player text bounded without copies. */
    private void drawClipped(GuiGraphics graphics, Component text,
                             int x, int y, int width, int colour) {
        if (width <= 0) {
            return;
        }
        graphics.enableScissor(x, y, x + width, y + 9);
        graphics.drawString(font, text, x, y, colour, false);
        graphics.disableScissor();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && canStartDrag()) {
            int index = rowAt(mouseX, mouseY);
            if (index >= 0) {
                inspectedRow = -1;
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
        if (dragSource < 0 && !rowsEmptyOrUnavailable()
                && (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN)) {
            int direction = keyCode == GLFW.GLFW_KEY_UP ? -1 : 1;
            if (hasAltDown()) {
                if (!moveKeyHeld) {
                    moveKeyHeld = true;
                    if (inspectedRow >= 0 && canStartDrag()) {
                        dragSource = inspectedRow;
                        dragTarget = Math.max(0, Math.min(rows.length - 1, inspectedRow + direction));
                        commitDrag();
                    }
                }
            } else {
                inspectedRow = Math.max(0, Math.min(rows.length - 1,
                    inspectedRow < 0 ? scroll : inspectedRow + direction));
                scroll = Math.max(0, Math.min(Math.max(0, rows.length - visibleRows),
                    inspectedRow < scroll ? inspectedRow : Math.max(scroll, inspectedRow - visibleRows + 1)));
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && dragSource >= 0) {
            cancelDrag();
            return true;
        }
        if (dragSource < 0 && (keyCode == GLFW.GLFW_KEY_PAGE_DOWN
                || keyCode == GLFW.GLFW_KEY_PAGE_UP)) {
            int direction = keyCode == GLFW.GLFW_KEY_PAGE_DOWN ? 1 : -1;
            scroll = Math.max(0, Math.min(Math.max(0, rows.length - visibleRows),
                scroll + direction * visibleRows));
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private boolean rowsEmptyOrUnavailable() {
        return rows.length == 0 || waiting || failed || movePending;
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) moveKeyHeld = false;
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    protected void updateNarrationState(net.minecraft.client.gui.narration.NarrationElementOutput output) {
        super.updateNarrationState(output);
        output.add(net.minecraft.client.gui.narration.NarratedElementType.HINT,
            snapshot != null && snapshot.canReorder() ? KEY_HELP : READ_ONLY);
        if (inspectedRow >= 0 && inspectedRow < rows.length) {
            output.add(net.minecraft.client.gui.narration.NarratedElementType.HINT,
                rows[inspectedRow].title);
        }
    }

    private boolean canStartDrag() {
        return snapshot != null && snapshot.canReorder() && !waiting
            && !failed && !movePending && rows.length > 1;
    }

    private int rowAt(double mouseX, double mouseY) {
        if (!insideList(mouseX, mouseY)) {
            return -1;
        }
        int listTop = layout.list().y();
        int visible = (int) ((mouseY - listTop) / CARD_STEP);
        int yWithin = (int) (mouseY - listTop) % CARD_STEP;
        int index = scroll + visible;
        return yWithin < CARD_H && index >= 0 && index < rows.length
            ? index : -1;
    }

    private boolean insideCard(double mouseX, double mouseY, int y) {
        Rect list = layout.list();
        return mouseX >= list.x() && mouseX < list.right()
            && mouseY >= y && mouseY < y + CARD_H;
    }

    private boolean insideList(double mouseX, double mouseY) {
        Rect list = layout.list();
        return mouseX >= list.x() && mouseX < list.right()
            && mouseY >= list.y()
            && mouseY < list.y() + visibleRows * CARD_STEP;
    }

    private void updateDragTarget(double mouseY) {
        int visible = (int) Math.floor((mouseY - layout.list().y()
            + CARD_STEP / 2.0D) / CARD_STEP);
        dragTarget = Math.max(0, Math.min(rows.length - 1,
            scroll + visible));
    }

    private void autoScroll(double mouseY) {
        long now = Util.getMillis();
        if (now - lastAutoScrollMillis < AUTO_SCROLL_INTERVAL_MS) {
            return;
        }
        int listY = layout.list().y();
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
        return layout.rowTextWidth();
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

    private record CachedRow(ItemStack item, Component title, List<FormattedCharSequence> titleLines,
                             Component status, Component meta,
                             Component reason, int statusColour,
                             int priorityColour, List<FormattedCharSequence> tooltip) {

        private static CachedRow create(EquipmentRequestListPayload.Row source,
                                        int textWidth,
                                        net.minecraft.client.gui.Font font, int tooltipWidth) {
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
            List<Component> facts = new ArrayList<>(List.of(position, title,
                destination, priority, status, reason));
            // Only server-supplied trace facts; an equipment adapter does not
            // prove that an item is physically in stock or in a Courier bag.
            RequestState state = RequestState.fromWireId(source.requestStateWireId())
                .orElse(RequestState.BLOCKED);
            RequestBlocker blocker = RequestBlocker.fromWireId(source.blockerWireId())
                .orElse(RequestBlocker.MALFORMED);
            facts.add(Component.translatable("hearthstead.request.row.stop",
                Component.translatable("hearthstead.request.state."
                    + state.name().toLowerCase(java.util.Locale.ROOT)),
                Component.translatable("hearthstead.request.blocker." + blocker.id())));
            Component courier = source.courierName().isBlank()
                ? Component.translatable(source.statusWireId() == 1
                    ? "hearthstead.request.courier.assigned" : "hearthstead.request.courier.unassigned")
                : Component.literal(source.courierName());
            String owner = switch (source.physicalOwnerWireId()) {
                    case 0 -> "source";
                    case 1 -> "courier_bag";
                    case 2 -> "target";
                    default -> "unknown";
                };
            facts.add(Component.translatable("hearthstead.request.row.assignment", courier,
                Component.translatable("hearthstead.request.owner." + owner)));
            long seconds = source.ageTicks() / 20L;
            Component age = source.ageTicks() < 0L
                ? Component.translatable("hearthstead.request.age.unknown")
                : seconds < 60L ? Component.translatable("hearthstead.request.age.seconds", seconds)
                : seconds < 3600L ? Component.translatable("hearthstead.request.age.minutes", seconds / 60L)
                : Component.translatable("hearthstead.request.age.hours", seconds / 3600L);
            facts.add(Component.translatable("hearthstead.request.row.age", age));
            if (!source.fullTransportTrace() && blocker != RequestBlocker.EQUIPMENT_ADAPTER_LIMITED) {
                facts.add(Component.translatable("hearthstead.request.blocker.equipment_adapter_limited"));
            }
            List<FormattedCharSequence> tooltip = new ArrayList<>();
            for (Component fact : facts) tooltip.addAll(font.split(fact, tooltipWidth));
            List<FormattedCharSequence> fullTitle = font.split(title, textWidth);
            return new CachedRow(source.item().copy(), title,
                List.copyOf(fullTitle.subList(0, Math.min(1, fullTitle.size()))),
                fit(status, textWidth, font), fit(meta, textWidth, font),
                fit(reason, textWidth, font), statusColour(source.statusWireId()),
                priorityColour(source.priorityWireId()), List.copyOf(tooltip));
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

        /** The line always names the priority, so colour is never the only signal. */
        private static int priorityColour(int id) {
            return id >= 2 ? Ui2Palette.DANGER
                : id == 1 ? Ui2Palette.AMBER : Ui2Palette.INK_MUTED;
        }

        /** Delivered is good (forest); claimed is in progress (gold); open is quiet. */
        private static int statusColour(int id) {
            return id == 2 ? Ui2Palette.FOREST
                : id == 1 ? Ui2Palette.GOLD : Ui2Palette.INK_MUTED;
        }
    }

    @Override
    protected void rebuildWidgets() {
        boolean restoreRefresh = getFocused() == refreshButton && refreshButton != null;
        boolean restoreBack = getFocused() == backButton && backButton != null;
        super.rebuildWidgets();
        if (restoreRefresh) setFocused(refreshButton);
        else if (restoreBack) setFocused(backButton);
    }

    private List<FormattedCharSequence> wrap(Component... facts) {
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (Component fact : facts) lines.addAll(font.split(fact, Math.max(80, width - 32)));
        return List.copyOf(lines);
    }

    /** Pure geometry of the Courier Board inside the standard frame (tested at GUI 2-4). */
    static Layout layoutFor(int viewportWidth, int viewportHeight) {
        return new Layout(Ui2FrameLayout.centred(viewportWidth, viewportHeight, DESIGNED_W, DESIGNED_H, true));
    }

    /**
     * State strip, the ticket list (rows of {@link #CARD_H} every
     * {@link #CARD_STEP}) and the footer text actions. The close key sits in
     * the frame header.
     */
    record Layout(Ui2FrameLayout frame) {
        Rect status() {
            Rect body = frame.body(false, true);
            return new Rect(body.x(), body.y(), body.width(), STATUS_H);
        }

        Rect list() {
            Rect body = frame.body(false, true);
            int y = status().bottom() + Ui2FrameLayout.M;
            return new Rect(body.x(), y, body.width(), Math.max(1, body.bottom() - y));
        }

        int visibleRows() {
            return Math.max(1, (list().height() + CARD_STEP - CARD_H) / CARD_STEP);
        }

        Rect row(int visibleRow) {
            Rect l = list();
            return new Rect(l.x(), l.y() + visibleRow * CARD_STEP, l.width() - SCROLL_GUTTER, CARD_H);
        }

        Rect well(int visibleRow) {
            Rect r = row(visibleRow);
            return new Rect(r.x() + Ui2FrameLayout.S, r.y() + 3, 18, 18);
        }

        int textX() {
            return list().x() + Ui2FrameLayout.S + POSITION_W;
        }

        int handleX() {
            return row(0).right() - HANDLE_W + 4;
        }

        int rowTextWidth() {
            return Math.max(1, row(0).right() - HANDLE_W - Ui2FrameLayout.S - textX());
        }

        Rect refresh() {
            Rect f = frame.footer();
            return new Rect(f.x(), f.y() + (Ui2FrameLayout.BUTTON_H - Ui2FrameLayout.TEXT_BUTTON_H) / 2,
                BUTTON_W, Ui2FrameLayout.TEXT_BUTTON_H);
        }

        Rect back() {
            Rect f = frame.footer();
            return new Rect(f.right() - BUTTON_W, refresh().y(), BUTTON_W, Ui2FrameLayout.TEXT_BUTTON_H);
        }
    }
}
