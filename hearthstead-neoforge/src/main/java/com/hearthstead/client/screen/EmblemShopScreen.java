package com.hearthstead.client.screen;

import com.hearthstead.client.QaClientObserver;
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
import com.hearthstead.client.ui2.Ui2Tips;
import com.hearthstead.client.ui2.Ui2WoodKey;
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
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.BelowOrAboveWidgetTooltipPositioner;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Mayor-owned catalogue for buying physical Job Emblems, in the standard Bannerhold window. */
public final class EmblemShopScreen extends Screen implements QaUiInspectable {
    private static final int PANEL_W = 460;
    private static final int PANEL_H = 338;
    private static final int MAX_ROWS = 4;
    /** Balance line and its rule above the list. */
    static final int INFO_H = 14;
    static final int CARD_H = 44;
    static final int CARD_STEP = 46;
    /** Burgundy Buy plate; wide enough for the serif "Need goods" label plus padlock. */
    static final int BTN_W = 88;
    static final int INSPECT_BTN_W = 112;
    /** Pager ("1-4 / 12") between the feedback strip and Inspect Mayor. */
    static final int PAGER_W = 64;
    /** Two-line feedback status strip. */
    static final int FOOTER_H = 24;
    static final int SCROLL_GUTTER = 6;
    /** A lost INSPECT_MAYOR reply (hearth gone, Mayor replaced) must not lock the button. */
    private static final long INSPECT_TIMEOUT_MS = 3_000L;
    private static final Component INSPECT_NO_REPLY = Component.literal(
        "The mayor could not be inspected. Try again.");
    private static final Component SUBTITLE = Component.translatable(
        "hearthstead.emblem_shop.subtitle");
    private static final Component DEFAULT_FOOTER = Component.translatable(
        "hearthstead.emblem_shop.footer");

    private DevelopmentSnapshotPayload snapshot;
    private CachedEmblem[] cached = new CachedEmblem[0];
    private Component footer = DEFAULT_FOOTER;
    private Ui2Frame.Tone footerTone = Ui2Frame.Tone.NEUTRAL;
    private FormattedCharSequence footerLine1 = FormattedCharSequence.EMPTY;
    private FormattedCharSequence footerLine2 = FormattedCharSequence.EMPTY;
    private List<FormattedCharSequence> footerTooltip = List.of();
    private Component availableCoinsLine = Component.empty();
    private boolean hasAvailableCoins;
    private Component pagerLine = Component.empty();
    private final Ui2Serif.Text titleText = new Ui2Serif.Text(Ui2Serif.Size.TITLE);
    private ScreenLayout layout = layoutFor(PANEL_W + 16, PANEL_H + 16);
    private int left;
    private int top;
    private int panelWidth = PANEL_W;
    private int panelHeight = PANEL_H;
    private int visibleRows = MAX_ROWS;
    private int scroll;
    private final List<ShopButton> rowButtons = new ArrayList<>();
    private ShopButton inspectMayorButton;
    private CloseKey closeButton;
    private boolean inspectPending;
    private long inspectStartedMillis;
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
        rebuildHeaderLines();
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
            boolean accepted = isApplied(feedback);
            QaClientObserver.markUiTransition(accepted
                ? "emblem_result_ok" : "emblem_result_error");
            if (accepted) {
                HsUi.playConfirmSound();
            } else {
                HsUi.playErrorSound();
            }
        });
    }

    private static boolean isApplied(Component feedback) {
        return feedback.getContents() instanceof TranslatableContents translated
            && Development.Result.APPLIED.translationKey().equals(translated.getKey());
    }

    @Override
    protected void init() {
        layout = layoutFor(width, height);
        panelWidth = layout.panelWidth();
        panelHeight = layout.panelHeight();
        visibleRows = layout.visibleRows();
        left = layout.left();
        top = layout.top();
        rebuildCache();
        rebuildHeaderLines();
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
        java.util.ArrayList<CachedEmblem> next = new java.util.ArrayList<>(
            JobEmblemCatalog.RELEASE_CATALOG.size());
        for (JobEmblemCatalog.Entry entry : JobEmblemCatalog.RELEASE_CATALOG) {
            DevelopmentSnapshotPayload.EmblemView view = views.get((int) entry.profession().id());
            // The server leaves extended trades out while [features]
            // extendedTrades is off: hide the row rather than grey it.
            if (view == null && snapshot != null && entry.unlock().extendedTrade()) {
                continue;
            }
            boolean available = view != null && view.available();
            String reason = view == null
                ? "hearthstead.development.blocked.quarantined" : view.reasonKey();
            next.add(CachedEmblem.create(entry, available, reason, font,
                layout.rowTextWidth()));
        }
        cached = next.toArray(new CachedEmblem[0]);
        if (snapshot != null && snapshot.feedback().isPresent()) {
            footer = purchaseFeedback(snapshot.feedback().get());
            footerTone = isApplied(snapshot.feedback().get()) ? Ui2Frame.Tone.GOOD : Ui2Frame.Tone.BAD;
        } else {
            footer = DEFAULT_FOOTER;
            footerTone = Ui2Frame.Tone.NEUTRAL;
        }
    }

    /** The shop presents its own action copy; the authoritative result stays intact. */
    private static Component purchaseFeedback(Component feedback) {
        return isApplied(feedback)
            ? Component.translatable("hearthstead.emblem_shop.purchase.confirmed")
            : feedback;
    }

    /** Snapshot-backed balance shares the exact physical payment sources with purchases. */
    private void rebuildHeaderLines() {
        hasAvailableCoins = snapshot != null && snapshot.availableCoins() >= 0;
        availableCoinsLine = hasAvailableCoins
            ? clipLine(font, Component.literal("Available: " + snapshot.availableCoins() + " Coins"),
                layout.info().width())
            : Component.empty();
    }

    /** Wrap dynamic server feedback once per snapshot, never once per frame. */
    private void rebuildFooterLines() {
        int width = Math.max(1, layout.footerTextWidth() - 18);
        var lines = font.split(footer, width);
        footerLine1 = lines.isEmpty() ? FormattedCharSequence.EMPTY : lines.get(0);
        footerLine2 = lines.size() < 2 ? FormattedCharSequence.EMPTY : lines.get(1);
        footerTooltip = List.copyOf(font.split(footer, tooltipWidth()));
    }

    private int tooltipWidth() {
        return Math.min(260, Math.max(80, panelWidth - 32));
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
        FocusTarget retainedFocus = focusedTarget();
        clearWidgets();
        setFocused(null);
        rowButtons.clear();
        inspectMayorButton = null;
        closeButton = null;
        scroll = Math.max(0, Math.min(scroll,
            Math.max(0, cached.length - visibleRows)));
        if (retainedFocus != null && retainedFocus.kind() == FocusKind.ROW
            && retainedFocus.catalogIndex() >= 0
            && retainedFocus.catalogIndex() < cached.length) {
            reveal(retainedFocus.catalogIndex());
        }
        for (int row = 0; row < visibleRows; row++) {
            int visibleRow = row;
            Rect r = layout.buy(row);
            ShopButton buy = new ShopButton(r.x(), r.y(), r.width(), r.height(), Component.empty(),
                Ui2Button.Variant.BANNER, () -> buyVisibleRow(visibleRow), null);
            rowButtons.add(buy);
            addRenderableWidget(buy);
        }
        Rect ir = layout.inspect();
        inspectMayorButton = new ShopButton(ir.x(), ir.y(), ir.width(), ir.height(),
            Component.translatable("hearthstead.emblem_shop.inspect"), Ui2Button.Variant.SECONDARY,
            this::inspectMayor, FocusTarget.inspect());
        refreshInspectButton();
        addRenderableWidget(inspectMayorButton);
        Rect c = layout.frame().close();
        closeButton = new CloseKey(c.x(), c.y(), c.width(), c.height(), this::onClose);
        closeButton.setTooltip(Tooltip.create(Component.literal("Close (Esc)")));
        addRenderableWidget(closeButton);
        refreshVisibleRows();
        restoreFocus(retainedFocus);
    }

    /** Disabled Inspect keeps the padlock plus the exact reason (spectator, no Mayor, waiting). */
    private void refreshInspectButton() {
        if (inspectMayorButton == null) {
            return;
        }
        boolean spectator = minecraft != null && minecraft.player != null
            && minecraft.player.isSpectator();
        boolean mayor = snapshot != null && snapshot.mayorEntityId() >= 0;
        boolean enabled = !inspectPending && !spectator && mayor;
        Component tip = Component.translatable("hearthstead.emblem_shop.inspect.tip");
        Component reason = Component.translatable(spectator
            ? "hearthstead.emblem_shop.inspect.read_only"
            : !mayor ? "hearthstead.emblem_shop.inspect.unavailable"
            : "hearthstead.emblem_shop.inspect.waiting");
        Ui2Tips.enable(inspectMayorButton, enabled, tip, reason);
        inspectMayorButton.setNarrationHint(enabled ? tip : reason);
    }

    /** Retain the same logical catalogue action when GUI scale resizes the screen. */
    @Override
    protected void rebuildWidgets() {
        FocusTarget retainedFocus = focusedTarget();
        super.rebuildWidgets();
        if (retainedFocus != null && retainedFocus.kind() == FocusKind.ROW
            && retainedFocus.catalogIndex() >= 0
            && retainedFocus.catalogIndex() < cached.length) {
            reveal(retainedFocus.catalogIndex());
            refreshVisibleRows();
        }
        restoreFocus(retainedFocus);
    }

    /**
     * Rebinds the fixed visible-row controls after scrolling. No widgets,
     * translated Components or Tooltips are allocated on the scroll path.
     */
    private void refreshVisibleRows() {
        for (int row = 0; row < rowButtons.size(); row++) {
            ShopButton button = rowButtons.get(row);
            int index = row + scroll;
            boolean present = index >= 0 && index < cached.length;
            button.visible = present;
            button.active = present;
            button.setFocusTarget(present ? FocusTarget.row(index) : null);
            if (!present) {
                continue;
            }
            CachedEmblem emblem = cached[index];
            button.setMessage(emblem.buttonLabel);
            // Keep locked rows in the Tab order: the cached tooltip explains
            // the exact research/material/Mayor reason. The guarded callback
            // still prevents Enter from buying a locked emblem. They paint
            // as disabled (muted face and padlock).
            button.setPurchasable(emblem.available);
            button.setNarrationHint(emblem.tooltipText);
            button.setTooltip(emblem.tooltip);
        }
        int first = cached.length == 0 ? 0 : scroll + 1;
        int last = Math.min(cached.length, scroll + visibleRows);
        pagerLine = Component.literal(first + "-" + last + " / " + cached.length);
    }

    private FocusTarget focusedTarget() {
        return getFocused() instanceof FocusTagged tagged
            ? tagged.focusTarget() : null;
    }

    private void reveal(int catalogIndex) {
        if (catalogIndex < scroll) {
            scroll = catalogIndex;
        } else if (catalogIndex >= scroll + visibleRows) {
            scroll = catalogIndex - visibleRows + 1;
        }
        scroll = Math.max(0, Math.min(scroll,
            Math.max(0, cached.length - visibleRows)));
    }

    private void restoreFocus(FocusTarget target) {
        setFocused(currentChildFor(target));
    }

    private GuiEventListener currentChildFor(FocusTarget target) {
        if (target == null) {
            return null;
        }
        for (GuiEventListener child : children()) {
            if (child instanceof FocusTagged tagged
                && target.equals(tagged.focusTarget())) {
                return child;
            }
        }
        return null;
    }

    private boolean isCurrentChild(GuiEventListener candidate) {
        if (candidate == null) {
            return false;
        }
        for (GuiEventListener child : children()) {
            if (child == candidate) {
                return true;
            }
        }
        return false;
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
        inspectStartedMillis = net.minecraft.Util.getMillis();
        footer = Component.translatable(
            "hearthstead.emblem_shop.inspect.waiting");
        footerTone = Ui2Frame.Tone.WAIT;
        rebuildFooterLines();
        refreshInspectButton();
        QaClientObserver.markUiTransition("emblem_shop_inspect_mayor_request");
        PacketDistributor.sendToServer(new DevelopmentActionPayload(
            snapshot.hearthPos(), snapshot.settlementId(), snapshot.mayorId(),
            DevelopmentActionPayload.View.EMBLEM_SHOP,
            DevelopmentActionPayload.Kind.INSPECT_MAYOR,
            snapshot.mayorEntityId(), snapshot.revision()));
    }

    @Override
    public void tick() {
        super.tick();
        if (inspectPending
            && net.minecraft.Util.getMillis() - inspectStartedMillis >= INSPECT_TIMEOUT_MS) {
            inspectPending = false;
            footer = INSPECT_NO_REPLY;
            footerTone = Ui2Frame.Tone.BAD;
            rebuildFooterLines();
            refreshInspectButton();
        }
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
        renderBackground(graphics, mouseX, mouseY, partialTick);
        Ui2FrameLayout frame = layout.frame();
        Ui2Frame.draw(graphics, frame);
        Ui2Frame.title(graphics, font, frame, titleText, title.getString(), SUBTITLE);

        Rect info = layout.info();
        if (hasAvailableCoins) {
            graphics.drawString(font, availableCoinsLine, info.x(), info.y() + 1, Ui2Palette.INK_SOFT, false);
        }
        Ui2Surface.rule(graphics, info.x(), info.bottom() - 2, info.width());

        List<FormattedCharSequence> pendingTooltip = null;
        for (int row = 0; row < visibleRows && row + scroll < cached.length; row++) {
            CachedEmblem emblem = cached[row + scroll];
            Rect card = layout.row(row);
            boolean hovered = card.contains(mouseX, mouseY);
            Ui2Surface.row(graphics, card.x(), card.y(), card.width(), card.height(), hovered ? 1.0F : 0.0F, false);
            if (row + 1 < visibleRows && row + 1 + scroll < cached.length) {
                Ui2Surface.rule(graphics, card.x(), card.bottom() + 1, card.width());
            }
            Rect well = layout.well(row);
            Ui2Surface.slotWell(graphics, well.x(), well.y());
            graphics.renderItem(emblem.icon, well.x() + 1, well.y() + 1);
            int textX = layout.textX(row);
            if (!emblem.nameLines.isEmpty()) {
                graphics.drawString(font, emblem.nameLines.get(0), textX, card.y() + 4, Ui2Palette.INK, false);
            }
            graphics.drawString(font, emblem.costLine, textX, card.y() + 14,
                emblem.available ? Ui2Palette.INK_SOFT : Ui2Palette.INK_MUTED, false);
            HsUi.drawLines(graphics, font, emblem.reasonLines, textX, card.y() + 24, Ui2Palette.INK_MUTED);
            if (hovered) {
                pendingTooltip = emblem.tooltipLines;
            }
        }

        Rect list = layout.list();
        if (cached.length > visibleRows) {
            Ui2Surface.scrollbar(graphics, list.right() - 2, list.y(), list.height(),
                Math.min(1.0F, (float) visibleRows / Math.max(1, cached.length)),
                (float) scroll / (cached.length - visibleRows));
        }

        Rect feedback = layout.feedback();
        Ui2Frame.status(graphics, font, feedback, Component.empty(), footerTone);
        graphics.drawString(font, footerLine1, feedback.x() + 14, feedback.y() + 4, Ui2Palette.INK_SOFT, false);
        graphics.drawString(font, footerLine2, feedback.x() + 14, feedback.y() + 13, Ui2Palette.INK_SOFT, false);
        if (feedback.contains(mouseX, mouseY)) {
            pendingTooltip = footerTooltip;
        }
        Rect pager = layout.pager();
        graphics.drawString(font, pagerLine, pager.right() - font.width(pagerLine),
            pager.y() + (pager.height() - 8) / 2, Ui2Palette.INK_MUTED, false);
        // Screen#render would repaint Minecraft 1.21's blur/menu background
        // over the custom panel. Draw only the registered controls here so
        // the panel and labels receive exactly one background pass.
        HsUi.widgets(this, graphics, mouseX, mouseY, partialTick);
        // Use Screen's single deferred pass for both native controls and full
        // cached facts. A hovered row (including its button) or footer wins
        // over keyboard focus, so these channels cannot paint two panels.
        if (pendingTooltip != null) {
            setTooltipForNextRenderPass(pendingTooltip);
        } else if (minecraft != null && minecraft.getLastInputType().isKeyboard()
                && getFocused() instanceof ShopButton focused
                && focused.active && focused.visible && isCurrentChild(focused)
                && (inspectMayorButton == null || !inspectMayorButton.isHovered())
                && (closeButton == null || !closeButton.isHovered())) {
            FocusTarget target = focused.focusTarget();
            if (target != null && target.kind() == FocusKind.ROW
                    && target.catalogIndex() >= scroll
                    && target.catalogIndex() < Math.min(cached.length, scroll + visibleRows)) {
                setTooltipForNextRenderPass(cached[target.catalogIndex()].tooltipLines,
                    focused.focusTooltipPositioner, true);
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (cached.length > visibleRows) {
            FocusTarget retainedFocus = focusedTarget();
            int before = scroll;
            scroll = Math.max(0, Math.min(cached.length - visibleRows,
                scroll - (int) Math.signum(dy)));
            if (scroll != before) {
                QaClientObserver.markUiTransition("emblem_shop_scroll");
                refreshVisibleRows();
                restoreFocus(retainedFocus);
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
        FocusTarget focused = focusedTarget();
        int focusedIndex = focused != null && focused.kind() == FocusKind.ROW
            ? focused.catalogIndex() : -1;
        int anchor = focusedIndex >= 0 ? focusedIndex : scroll;
        int target = switch (keyCode) {
            case 265 -> anchor - 1; // up
            case 264 -> anchor + 1; // down
            case 266 -> anchor - visibleRows; // page up
            case 267 -> anchor + visibleRows; // page down
            case 268 -> 0; // home
            case 269 -> cached.length - 1; // end
            default -> Integer.MIN_VALUE;
        };
        if (target != Integer.MIN_VALUE) {
            if (cached.length > 0) {
                int next = Math.max(0, Math.min(cached.length - 1, target));
                int before = scroll;
                reveal(next);
                refreshVisibleRows();
                restoreFocus(FocusTarget.row(next));
                if (scroll != before || next != focusedIndex) {
                    QaClientObserver.markUiTransition("emblem_shop_scroll_key");
                }
                return true;
            }
            if (scroll != 0) {
                scroll = 0;
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
        GuiEventListener focusedWidget = getFocused();
        FocusTarget focused = focusedTarget();
        int focusedRow = focused != null && focused.kind() == FocusKind.ROW
            ? focused.catalogIndex() : -1;
        return "scroll=" + scroll + "/"
            + Math.max(0, cached.length - visibleRows) + ",rows=" + visibleRows
            + ",catalog=" + cached.length + ",focused=" + focusedRow
            + ",focus=" + qaFocus(focused) + ",focusCurrentChild="
            + isCurrentChild(focusedWidget) + ",panel=" + left + ":" + top + ":"
            + panelWidth + ":" + panelHeight + ",mayorEntity="
            + (snapshot == null ? -1 : snapshot.mayorEntityId())
            + ",availableCoins=" + (snapshot == null ? -1 : snapshot.availableCoins())
            + ",inspectPending=" + inspectPending + ",inspectActive="
            + (inspectMayorButton != null && inspectMayorButton.active);
    }

    /** Deterministic geometry contract used by the native viewport preflight and layout tests. */
    static ScreenLayout layoutFor(int viewportWidth, int viewportHeight) {
        return new ScreenLayout(Ui2FrameLayout.centred(viewportWidth, viewportHeight, PANEL_W, PANEL_H, true));
    }

    /**
     * Pure geometry of the shop inside the standard frame: balance line,
     * up to {@link #MAX_ROWS} emblem rows with a burgundy Buy plate each,
     * then the footer (feedback status, pager, Inspect Mayor). The close
     * key sits in the frame header.
     */
    record ScreenLayout(Ui2FrameLayout frame) {
        int panelWidth() {
            return frame.width();
        }

        int panelHeight() {
            return frame.height();
        }

        int left() {
            return frame.x();
        }

        int top() {
            return frame.y();
        }

        Rect info() {
            Rect c = frame.content();
            return new Rect(c.x(), c.y(), c.width(), INFO_H);
        }

        Rect footer() {
            Rect c = frame.content();
            return new Rect(c.x(), c.bottom() - FOOTER_H, c.width(), FOOTER_H);
        }

        /** The list area between the balance line and the footer. */
        Rect list() {
            int y = info().bottom();
            return new Rect(frame.content().x(), y, frame.content().width(),
                Math.max(1, footer().y() - Ui2FrameLayout.M - y));
        }

        int visibleRows() {
            return Math.max(1, Math.min(MAX_ROWS, (list().height() + CARD_STEP - CARD_H) / CARD_STEP));
        }

        Rect row(int i) {
            Rect l = list();
            return new Rect(l.x(), l.y() + i * CARD_STEP, l.width() - SCROLL_GUTTER, CARD_H);
        }

        Rect well(int i) {
            Rect r = row(i);
            return new Rect(r.x() + Ui2FrameLayout.S, r.y() + (CARD_H - 18) / 2, 18, 18);
        }

        int textX(int i) {
            return well(i).right() + Ui2FrameLayout.M;
        }

        Rect buy(int i) {
            Rect r = row(i);
            return new Rect(r.right() - BTN_W - Ui2FrameLayout.S, r.y() + (CARD_H - Ui2FrameLayout.BUTTON_H) / 2,
                BTN_W, Ui2FrameLayout.BUTTON_H);
        }

        /** Width for the name, cost and reason lines of a row. */
        int rowTextWidth() {
            return Math.max(1, buy(0).x() - Ui2FrameLayout.M - textX(0));
        }

        int inspectButtonWidth() {
            return Math.min(INSPECT_BTN_W, Math.max(72,
                frame.content().width() - PAGER_W - 2 * Ui2FrameLayout.M - 120));
        }

        Rect inspect() {
            Rect f = footer();
            int w = inspectButtonWidth();
            return new Rect(f.right() - w, f.y() + (FOOTER_H - Ui2FrameLayout.TEXT_BUTTON_H) / 2, w,
                Ui2FrameLayout.TEXT_BUTTON_H);
        }

        Rect pager() {
            Rect f = footer();
            return new Rect(inspect().x() - Ui2FrameLayout.M - PAGER_W, f.y(), PAGER_W, FOOTER_H);
        }

        int footerTextWidth() {
            return Math.max(1, pager().x() - Ui2FrameLayout.M - footer().x());
        }

        Rect feedback() {
            Rect f = footer();
            return new Rect(f.x(), f.y(), footerTextWidth(), FOOTER_H);
        }
    }

    private enum FocusKind {
        ROW,
        INSPECT,
        CLOSE
    }

    private record FocusTarget(FocusKind kind, int catalogIndex) {
        static FocusTarget row(int catalogIndex) {
            return new FocusTarget(FocusKind.ROW, catalogIndex);
        }

        static FocusTarget inspect() {
            return new FocusTarget(FocusKind.INSPECT, -1);
        }

        static FocusTarget close() {
            return new FocusTarget(FocusKind.CLOSE, -1);
        }
    }

    private interface FocusTagged {
        FocusTarget focusTarget();
    }

    private static String qaFocus(FocusTarget target) {
        if (target == null) {
            return "none";
        }
        return target.kind() == FocusKind.ROW
            ? "row:" + target.catalogIndex()
            : target.kind().name().toLowerCase(java.util.Locale.ROOT);
    }

    /** The standard wooden close key, tagged so focus survives a rebuild. */
    private static final class CloseKey extends Ui2WoodKey implements FocusTagged {
        CloseKey(int x, int y, int w, int h, Runnable onClose) {
            super(x, y, w, h, Component.literal("×"), onClose);
        }

        @Override
        public FocusTarget focusTarget() {
            return FocusTarget.close();
        }
    }

    /**
     * A kit button that keeps the shop's focus identity and narration hint.
     * A locked emblem's Buy stays focusable (so its tooltip can explain the
     * exact reason) but paints as disabled: muted face plus padlock.
     */
    private static final class ShopButton extends Ui2Button implements FocusTagged {
        private final BelowOrAboveWidgetTooltipPositioner focusTooltipPositioner;
        private FocusTarget focusTarget;
        private Component narrationHint = Component.empty();
        private boolean purchasable = true;

        ShopButton(int x, int y, int width, int height, Component label, Variant variant,
                   Runnable action, FocusTarget focusTarget) {
            super(x, y, width, height, label, variant, action);
            this.focusTooltipPositioner = new BelowOrAboveWidgetTooltipPositioner(getRectangle());
            this.focusTarget = focusTarget;
        }

        @Override
        public FocusTarget focusTarget() {
            return focusTarget;
        }

        void setFocusTarget(FocusTarget focusTarget) {
            this.focusTarget = focusTarget;
        }

        void setPurchasable(boolean purchasable) {
            this.purchasable = purchasable;
        }

        void setNarrationHint(Component narrationHint) {
            this.narrationHint = narrationHint == null
                ? Component.empty() : narrationHint;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY,
                                    float partialTick) {
            boolean wasActive = active;
            if (!purchasable) active = false;
            try {
                super.renderWidget(graphics, mouseX, mouseY, partialTick);
            } finally {
                active = wasActive;
            }
            if (wasActive && !purchasable && isFocused() && !isHovered()) {
                Ui2Surface.focus(graphics, getX(), getY() + 1, getWidth(), getHeight());
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
            if (!narrationHint.getString().isEmpty()) {
                output.add(NarratedElementType.HINT, narrationHint);
            }
        }
    }

    /**
     * The node that opens this emblem: the v3 tech node that claims it
     * (builders_hut, carpenter_mason, ...) when one does, else the legacy node.
     */
    static Component unlockName(JobEmblemCatalog.Entry entry) {
        if (com.hearthstead.settlement.techtree.TechTreeConfig.enabled()) {
            for (String id : com.hearthstead.settlement.techtree.EffectRegistry.get()
                    .professionClaimants(entry.profession())) {
                var def = com.hearthstead.settlement.techtree.TechTreeData.get().node(id);
                if (def != null) {
                    return def.displayName();
                }
            }
        }
        return entry.unlock().displayName();
    }

    private record CachedEmblem(JobEmblemCatalog.Entry entry, Component name,
                                Component cost, List<FormattedCharSequence> nameLines,
                                Component costLine, List<FormattedCharSequence> reasonLines,
                                Component buttonLabel, Component tooltipText,
                                List<FormattedCharSequence> tooltipLines,
                                Tooltip tooltip,
                                ItemStack icon, boolean available) {
        static CachedEmblem create(JobEmblemCatalog.Entry entry, boolean available,
                                   String reasonKey, Font font, int textWidth) {
            Component name = entry.displayName();
            Component cost = costLine(entry);
            Component reason = available
                ? Component.translatable("hearthstead.emblem_shop.buy.tip")
                : Component.translatable(reasonKey);
            Component visibleReason = !available
                && "hearthstead.development.blocked.emblem_locked".equals(reasonKey)
                ? Component.translatable("hearthstead.emblem_shop.locked.route", unlockName(entry)) : reason;
            MutableComponent tooltip = name.copy().append("\n").append(cost)
                .append("\n").append(reason)
                .append("\n").append(Component.translatable(
                    "item.hearthstead.job_emblem.use"));
            if (!available
                && "hearthstead.development.blocked.emblem_locked".equals(reasonKey)) {
                tooltip.append("\n").append(Component.translatable(
                    "hearthstead.emblem_shop.locked.route",
                    unlockName(entry)));
            }
            return new CachedEmblem(entry, name, cost,
                wrap(font, name, textWidth, 1), clipLine(font, cost, textWidth),
                wrap(font, visibleReason, textWidth, 2),
                Component.translatable(available
                    ? "hearthstead.emblem_shop.buy"
                    : unaffordable(reasonKey)
                        ? "hearthstead.emblem_shop.cant_afford"
                        : "hearthstead.development.status.locked"),
                tooltip, font == null ? List.of(tooltip.getVisualOrderText())
                    : List.copyOf(font.split(tooltip,
                        Math.min(260, Math.max(80, textWidth + BTN_W + 18)))),
                Tooltip.create(tooltip),
                JobEmblemItem.stackFor(entry.profession()), available);
        }

        /** QA: a job the settlement knows but cannot pay for is not "LOCKED". */
        private static boolean unaffordable(String reasonKey) {
            return "hearthstead.development.blocked.materials".equals(reasonKey)
                || "hearthstead.development.blocked.missing".equals(reasonKey)
                || "hearthstead.emblem_shop.blocked.materials".equals(reasonKey)
                || "hearthstead.emblem_shop.blocked.missing".equals(reasonKey);
        }

        private static List<FormattedCharSequence> wrap(Font font, Component text, int width, int limit) {
            if (font == null) return List.of(text.getVisualOrderText());
            List<FormattedCharSequence> lines = font.split(text, Math.max(1, width));
            return List.copyOf(lines.subList(0, Math.min(limit, lines.size())));
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
