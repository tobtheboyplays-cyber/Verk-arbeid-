package com.hearthstead.client.screen;

import com.hearthstead.client.QaClientObserver;
import com.hearthstead.client.QaUiInspectable;
import com.hearthstead.client.ui.HsButton;
import com.hearthstead.client.ui.HsUi;
import com.hearthstead.client.ui.HsUiTokens;
import com.hearthstead.entity.Profession;
import com.hearthstead.item.JobEmblemItem;
import com.hearthstead.network.DevelopmentActionPayload;
import com.hearthstead.network.DevelopmentSnapshotPayload;
import com.hearthstead.settlement.development.Development;
import com.hearthstead.settlement.development.DevelopmentNode;
import com.hearthstead.settlement.development.JobEmblemCatalog;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Mayor-owned catalogue for buying physical Job Emblems. */
public final class EmblemShopScreen extends Screen implements QaUiInspectable {
    private static final int PANEL_W = 354;
    private static final int PANEL_H = 264;
    private static final int PAD = 10;
    private static final int LIST_TOP = 52;
    private static final int MAX_ROWS = 4;
    private static final int CARD_H = 39;
    private static final int CARD_STEP = 43;
    private static final int BTN_W = 64;
    private static final int INSPECT_BTN_W = 112;
    private static final int FOOTER_GAP = 6;
    private static final int FOOTER_TEXT_GAP = 8;
    private static final Component SUBTITLE = Component.translatable(
        "hearthstead.emblem_shop.subtitle");
    private static final Component DEFAULT_FOOTER = Component.translatable(
        "hearthstead.emblem_shop.footer");

    private DevelopmentSnapshotPayload snapshot;
    private CachedEmblem[] cached = new CachedEmblem[0];
    private Component footer = DEFAULT_FOOTER;
    private FormattedCharSequence footerLine1 = FormattedCharSequence.EMPTY;
    private FormattedCharSequence footerLine2 = FormattedCharSequence.EMPTY;
    private Component subtitleLine = SUBTITLE;
    private int left;
    private int top;
    private int panelWidth = PANEL_W;
    private int panelHeight = PANEL_H;
    private int visibleRows = MAX_ROWS;
    private int scroll;
    private final List<HsButton> rowButtons = new ArrayList<>();
    private HsButton inspectMayorButton;
    private boolean inspectPending;
    private boolean uiOpenSoundPlayed;
    private boolean uiCloseSoundPlayed;

    public EmblemShopScreen(DevelopmentSnapshotPayload snapshot) {
        super(Component.translatable("hearthstead.emblem_shop.title"));
        this.snapshot = snapshot;
        rebuildCache();
    }

    public boolean accepts(DevelopmentSnapshotPayload fresh) {
        return fresh != null
            && fresh.view() == DevelopmentActionPayload.View.EMBLEM_SHOP
            && fresh.settlementId().equals(snapshot.settlementId())
            && fresh.hearthPos().equals(snapshot.hearthPos())
            && fresh.mayorId().equals(snapshot.mayorId());
    }

    public void update(DevelopmentSnapshotPayload fresh) {
        if (!accepts(fresh)) {
            return;
        }
        playAuthoritativeFeedback(fresh);
        inspectPending = false;
        snapshot = fresh;
        rebuildCache();
        rebuildFooterLines();
        rebuildControls();
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
                ? "emblem_result_ok" : "emblem_result_error");
            if (accepted) {
                HsUi.playConfirmSound();
            } else {
                HsUi.playErrorSound();
            }
        });
    }

    @Override
    protected void init() {
        ScreenLayout layout = layoutFor(width, height);
        panelWidth = layout.panelWidth();
        panelHeight = layout.panelHeight();
        visibleRows = layout.visibleRows();
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        rebuildCache();
        subtitleLine = clipLine(font, SUBTITLE, panelWidth - 2 * PAD);
        rebuildFooterLines();
        rebuildControls();
        if (!uiOpenSoundPlayed) {
            uiOpenSoundPlayed = true;
            HsUi.playOpenSound();
        }
    }

    private void rebuildCache() {
        Map<Integer, DevelopmentSnapshotPayload.EmblemView> views = new HashMap<>();
        if (snapshot != null) {
            for (DevelopmentSnapshotPayload.EmblemView view : snapshot.emblems()) {
                views.put(view.professionWireId(), view);
            }
        }
        CachedEmblem[] next = new CachedEmblem[JobEmblemCatalog.RELEASE_CATALOG.size()];
        for (int i = 0; i < next.length; i++) {
            JobEmblemCatalog.Entry entry = JobEmblemCatalog.RELEASE_CATALOG.get(i);
            DevelopmentSnapshotPayload.EmblemView view = views.get((int) entry.profession().id());
            boolean available = view != null && view.available();
            String reason = view == null
                ? "hearthstead.development.blocked.quarantined" : view.reasonKey();
            next[i] = CachedEmblem.create(entry, available, reason, font,
                rowTextWidth());
        }
        cached = next;
        footer = snapshot != null && snapshot.feedback().isPresent()
            ? snapshot.feedback().get()
            : DEFAULT_FOOTER;
    }

    /** Wrap dynamic server feedback once per snapshot, never once per frame. */
    private void rebuildFooterLines() {
        int width = layoutForPanel(panelWidth, panelHeight).footerTextWidth();
        var lines = font.split(footer, width);
        footerLine1 = lines.isEmpty() ? FormattedCharSequence.EMPTY : lines.get(0);
        footerLine2 = lines.size() < 2 ? FormattedCharSequence.EMPTY : lines.get(1);
    }

    private int rowTextWidth() {
        return Math.max(1, panelWidth - 2 * PAD - BTN_W - 44);
    }

    /** Ellipsise only on snapshot/layout work, never in the render loop. */
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
        rowButtons.clear();
        inspectMayorButton = null;
        scroll = Math.max(0, Math.min(scroll,
            Math.max(0, cached.length - visibleRows)));
        for (int row = 0; row < visibleRows; row++) {
            int visibleRow = row;
            int y = top + LIST_TOP + row * CARD_STEP;
            HsButton buy = HsButton.normal(left + panelWidth - PAD - BTN_W,
                y + (CARD_H - HsUiTokens.BUTTON_H) / 2, BTN_W,
                HsUiTokens.BUTTON_H, Component.empty(),
                () -> buyVisibleRow(visibleRow));
            rowButtons.add(buy);
            addRenderableWidget(buy);
        }
        ScreenLayout layout = layoutForPanel(panelWidth, panelHeight);
        int footerY = top + panelHeight - 25;
        int closeX = left + panelWidth - PAD - BTN_W;
        int inspectX = closeX - FOOTER_GAP - layout.inspectButtonWidth();
        inspectMayorButton = HsButton.normal(inspectX, footerY,
            layout.inspectButtonWidth(), HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.emblem_shop.inspect"),
            this::inspectMayor);
        boolean spectator = minecraft != null && minecraft.player != null
            && minecraft.player.isSpectator();
        inspectMayorButton.active = !inspectPending && !spectator
            && snapshot != null && snapshot.mayorEntityId() >= 0;
        inspectMayorButton.setTooltip(Tooltip.create(Component.translatable(
            spectator
                ? "hearthstead.emblem_shop.inspect.read_only"
                : snapshot == null || snapshot.mayorEntityId() < 0
                    ? "hearthstead.emblem_shop.inspect.unavailable"
                    : "hearthstead.emblem_shop.inspect.tip")));
        addRenderableWidget(inspectMayorButton);
        addRenderableWidget(HsButton.normal(closeX,
            top + panelHeight - 25, BTN_W, HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.gui.close"), this::onClose));
        refreshVisibleRows();
    }

    /**
     * Rebinds the fixed visible-row controls after scrolling. No widgets,
     * translated Components or Tooltips are allocated on the scroll path.
     */
    private void refreshVisibleRows() {
        for (int row = 0; row < rowButtons.size(); row++) {
            HsButton button = rowButtons.get(row);
            int index = row + scroll;
            boolean present = index >= 0 && index < cached.length;
            button.visible = present;
            button.active = present;
            if (!present) {
                continue;
            }
            CachedEmblem emblem = cached[index];
            button.setMessage(emblem.buttonLabel);
            // Keep locked rows in the Tab order: the cached tooltip explains
            // the exact research/material/Mayor reason. The guarded callback
            // still prevents Enter from buying a locked emblem.
            button.setTooltip(emblem.tooltip);
        }
    }

    private void buyVisibleRow(int visibleRow) {
        int index = visibleRow + scroll;
        if (index < 0 || index >= cached.length) {
            return;
        }
        CachedEmblem emblem = cached[index];
        if (emblem.available) {
            buy(emblem.entry.profession());
        }
    }

    private void buy(Profession profession) {
        PacketDistributor.sendToServer(new DevelopmentActionPayload(snapshot.hearthPos(),
            snapshot.settlementId(), snapshot.mayorId(),
            DevelopmentActionPayload.View.EMBLEM_SHOP,
            DevelopmentActionPayload.Kind.BUY_EMBLEM, profession.id(), snapshot.revision()));
    }

    /**
     * The Mayor owns two equally diegetic interactions: issuing emblems and
     * being inspected as an ordinary settler. This explicit button avoids a
     * hidden gesture while the server still resolves both entity identities,
     * settlement membership, reach and the exact shop revision.
     */
    private void inspectMayor() {
        if (inspectPending || snapshot == null || snapshot.mayorEntityId() < 0
            || minecraft == null || minecraft.player == null
            || minecraft.player.isSpectator()) {
            return;
        }
        inspectPending = true;
        footer = Component.translatable(
            "hearthstead.emblem_shop.inspect.waiting");
        rebuildFooterLines();
        if (inspectMayorButton != null) {
            inspectMayorButton.active = false;
        }
        QaClientObserver.markUiTransition("emblem_shop_inspect_mayor_request");
        PacketDistributor.sendToServer(new DevelopmentActionPayload(
            snapshot.hearthPos(), snapshot.settlementId(), snapshot.mayorId(),
            DevelopmentActionPayload.View.EMBLEM_SHOP,
            DevelopmentActionPayload.Kind.INSPECT_MAYOR,
            snapshot.mayorEntityId(), snapshot.revision()));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        HsUi.window(graphics, left, top, panelWidth, panelHeight);
        HsUi.centred(graphics, font, title, left + panelWidth / 2, top + 12,
            HsUiTokens.TEXT_STRONG);
        graphics.drawString(font, subtitleLine, left + PAD, top + 29,
            HsUiTokens.TEXT_MUTED, true);
        HsUi.divider(graphics, left + PAD, top + 44, panelWidth - 2 * PAD);

        for (int row = 0; row < visibleRows && row + scroll < cached.length; row++) {
            CachedEmblem emblem = cached[row + scroll];
            int y = top + LIST_TOP + row * CARD_STEP;
            HsUi.card(graphics, left + PAD, y, panelWidth - 2 * PAD, CARD_H,
                mouseX >= left + PAD && mouseX < left + panelWidth - PAD
                    && mouseY >= y && mouseY < y + CARD_H);
            graphics.renderItem(emblem.icon, left + PAD + 7, y + 6);
            graphics.drawString(font, emblem.nameLine, left + PAD + 29, y + 6,
                HsUiTokens.TEXT_STRONG, true);
            graphics.drawString(font, emblem.costLine, left + PAD + 29, y + 21,
                emblem.available ? HsUiTokens.GOOD : HsUiTokens.TEXT_MUTED, true);
        }

        HsUi.scrollbar(graphics, left + panelWidth - 5, top + LIST_TOP,
            visibleRows * CARD_STEP - 4,
            Math.min(1.0F, (float) visibleRows / Math.max(1, cached.length)),
            cached.length <= visibleRows ? 0.0F
                : (float) scroll / (cached.length - visibleRows), false);
        HsUi.divider(graphics, left + PAD, top + panelHeight - 38,
            panelWidth - 2 * PAD);
        graphics.drawString(font, footerLine1, left + PAD,
            top + panelHeight - 30, HsUiTokens.ACCENT, true);
        graphics.drawString(font, footerLine2, left + PAD,
            top + panelHeight - 20, HsUiTokens.ACCENT, true);
        // Screen#render would repaint Minecraft 1.21's blur/menu background
        // over the custom panel. Draw only the registered controls here so
        // the panel and labels receive exactly one background pass.
        HsUi.widgets(this, graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (cached.length > visibleRows) {
            int before = scroll;
            scroll = Math.max(0, Math.min(cached.length - visibleRows,
                scroll - (int) Math.signum(dy)));
            if (scroll != before) {
                QaClientObserver.markUiTransition("emblem_shop_scroll");
                refreshVisibleRows();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, dx, dy);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // The visible buy controls retain ordinary Tab/Shift+Tab focus. These
        // list-navigation keys expose every off-screen emblem to keyboard-only
        // players without creating another steady-frame allocation path.
        int target = switch (keyCode) {
            case 265 -> scroll - 1; // up
            case 264 -> scroll + 1; // down
            case 266 -> scroll - visibleRows; // page up
            case 267 -> scroll + visibleRows; // page down
            case 268 -> 0; // home
            case 269 -> Math.max(0, cached.length - visibleRows); // end
            default -> Integer.MIN_VALUE;
        };
        if (target != Integer.MIN_VALUE) {
            int next = Math.max(0, Math.min(Math.max(0,
                cached.length - visibleRows), target));
            if (next != scroll) {
                scroll = next;
                QaClientObserver.markUiTransition("emblem_shop_scroll_key");
                refreshVisibleRows();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public String qaUiState() {
        int focusedRow = -1;
        for (int row = 0; row < rowButtons.size(); row++) {
            if (rowButtons.get(row).isFocused()) {
                focusedRow = row + scroll;
                break;
            }
        }
        return "scroll=" + scroll + "/"
            + Math.max(0, cached.length - visibleRows) + ",rows=" + visibleRows
            + ",focused=" + focusedRow + ",panel=" + left + ":" + top + ":"
            + panelWidth + ":" + panelHeight + ",mayorEntity="
            + (snapshot == null ? -1 : snapshot.mayorEntityId())
            + ",inspectPending=" + inspectPending + ",inspectActive="
            + (inspectMayorButton != null && inspectMayorButton.active);
    }

    /** Deterministic geometry contract used by the native viewport preflight. */
    static ScreenLayout layoutFor(int viewportWidth, int viewportHeight) {
        int width = Math.min(PANEL_W, Math.max(1, viewportWidth - 16));
        int height = Math.min(PANEL_H, Math.max(1, viewportHeight - 16));
        return layoutForPanel(width, height);
    }

    private static ScreenLayout layoutForPanel(int width, int height) {
        int rows = Math.max(1, Math.min(MAX_ROWS,
            (height - LIST_TOP - 38) / CARD_STEP));
        int inspectWidth = Math.min(INSPECT_BTN_W, Math.max(72,
            width - 2 * PAD - BTN_W - FOOTER_GAP - 64));
        int footerTextWidth = Math.max(1, width - 2 * PAD - BTN_W
            - FOOTER_GAP - inspectWidth - FOOTER_TEXT_GAP);
        return new ScreenLayout(width, height, rows, inspectWidth,
            footerTextWidth);
    }

    record ScreenLayout(int panelWidth, int panelHeight, int visibleRows,
                        int inspectButtonWidth, int footerTextWidth) {
    }

    private record CachedEmblem(JobEmblemCatalog.Entry entry, Component name,
                                Component cost, Component nameLine,
                                Component costLine, Component buttonLabel,
                                Tooltip tooltip,
                                ItemStack icon, boolean available) {
        static CachedEmblem create(JobEmblemCatalog.Entry entry, boolean available,
                                   String reasonKey, Font font, int textWidth) {
            Component name = entry.displayName();
            Component cost = costLine(entry);
            Component reason = available
                ? Component.translatable("hearthstead.emblem_shop.buy.tip")
                : Component.translatable(reasonKey);
            MutableComponent tooltip = name.copy().append("\n").append(cost)
                .append("\n").append(reason)
                .append("\n").append(Component.translatable(
                    "item.hearthstead.job_emblem.use"));
            if (!available
                && "hearthstead.development.blocked.emblem_locked".equals(reasonKey)) {
                tooltip.append("\n").append(Component.translatable(
                    "hearthstead.emblem_shop.locked.route",
                    entry.unlock().displayName()));
            }
            return new CachedEmblem(entry, name, cost,
                clipLine(font, name, textWidth), clipLine(font, cost, textWidth),
                Component.translatable(available
                    ? "hearthstead.emblem_shop.buy"
                    : "hearthstead.development.status.locked"),
                Tooltip.create(tooltip),
                JobEmblemItem.stackFor(entry.profession()), available);
        }

        private static Component costLine(JobEmblemCatalog.Entry entry) {
            MutableComponent line = Component.translatable(
                "hearthstead.development.cost.prefix");
            for (int i = 0; i < entry.costs().size(); i++) {
                DevelopmentNode.Cost cost = entry.costs().get(i);
                if (i > 0) {
                    line.append(Component.literal(" + "));
                }
                line.append(Component.translatable("hearthstead.development.cost.line",
                    cost.count(), cost.displayName()));
            }
            return line;
        }
    }
}
