package com.hearthstead.client.screen;

import com.hearthstead.client.QaClientObserver;
import com.hearthstead.client.QaUiInspectable;
import com.hearthstead.client.ui.HsButton;
import com.hearthstead.client.ui.HsUi;
import com.hearthstead.client.ui.HsUiTokens;
import com.hearthstead.item.BlessingSealItem;
import com.hearthstead.network.BlessingActionPayload;
import com.hearthstead.network.BlessingSnapshotPayload;
import com.hearthstead.settlement.state.BlessingId;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A deliberate review-and-confirm choice for one physical Blessing seal.
 * Cards only change the local selection; the separate footer button is the
 * sole action that can request a seal from the server. Permanent binding to a
 * settler or registered plaque happens afterwards, in the world.
 */
public final class BlessingScreen extends Screen implements QaUiInspectable {

    /** values() clones its backing array; keep the fixed three-card order once. */
    private static final BlessingId[] BLESSING_IDS = BlessingId.values();

    private static final int PANEL_MAX_W = 444;
    private static final int PANEL_MAX_H = 252;
    private static final int OUTER_MARGIN = 4;
    private static final int PANEL_PAD = 6;
    private static final int CARD_GAP = 4;
    private static final int CARDS_TOP = 53;
    private static final int FOOTER_H = 28;
    private static final int FOOTER_GAP = 4;
    private static final int CONFIRM_W = 66;
    private static final int CLOSE_W = 52;
    private static final int COMPACT_CARD_H = 125;
    /** One readable co-op receipt before a queued offer becomes actionable. */
    private static final int FOLLOW_UP_FEEDBACK_TICKS = 30;
    /**
     * Seven seconds at 20 TPS: enough for the release gate's required 180
     * post-warm-up frames even at its 30 FPS acceptance floor, while still a
     * bounded receipt that closes without another player action.
     */
    static final int LATER_RECEIPT_TICKS = 140;

    private static final int IRON_DARK = 0xFF292D2F;
    private static final int IRON = 0xFF53595C;
    private static final int IRON_LIGHT = 0xFF81878A;
    private static final int OAK_SHADOW = 0xFF1B120B;
    private static final Component TITLE = Component.translatable(
        "hearthstead.blessing.title");
    private static final Component SEAL_LABEL = Component.translatable(
        "hearthstead.blessing.seal");
    private static final Component TERMS_LABEL = Component.translatable(
        "hearthstead.blessing.terms");
    private static final Component TARGET_RANKS_LABEL = Component.translatable(
        "hearthstead.blessing.target_ranks");
    private static final Component FOOTER = Component.translatable(
        "hearthstead.blessing.footer");

    private BlessingSnapshotPayload snapshot;
    /** Built once per screen; rendering must not allocate three stacks/frame. */
    private final EnumMap<BlessingId, net.minecraft.world.item.ItemStack> sealIcons =
        new EnumMap<>(BlessingId.class);
    /**
     * Localized card copy and its already-fitted lines. Rebuilt only when the
     * screen layout is initialized/resized, never during render().
     */
    private final EnumMap<BlessingId, CardCopy> cardCopy =
        new EnumMap<>(BlessingId.class);
    private String titleLine = "";
    private String subtitleLine = "";
    private String headerLine = "";
    private String footerLine = "";
    private String sealLine = "";
    private String termsLine = "";
    private String targetRanksLine = "";
    private int titleLineX;
    private int subtitleLineX;
    private int cachedHeaderColour = HsUiTokens.TEXT_MUTED;
    private BlessingId selected;
    private boolean waiting;
    private boolean closeSent;
    private boolean uiSoundActive;
    private int followUpFeedbackTicks;
    private int laterReceiptTicks;
    private boolean savedForLater;
    private int left;
    private int top;
    private int panelW;
    private int panelH;
    private int cardW;
    private int cardH;

    public BlessingScreen(BlessingSnapshotPayload snapshot) {
        super(TITLE);
        this.snapshot = snapshot;
        for (BlessingId blessing : BLESSING_IDS) {
            sealIcons.put(blessing, BlessingSealItem.stackFor(blessing));
            cardCopy.put(blessing, CardCopy.unmeasured(blessing));
        }
        this.followUpFeedbackTicks = snapshot.hasFollowUpOffer()
            ? FOLLOW_UP_FEEDBACK_TICKS : 0;
        if (!snapshot.hasPendingOffer() && !snapshot.hasFollowUpOffer()
            && snapshot.feedback() == BlessingSnapshotPayload.Feedback.ACCEPTED) {
            this.selected = snapshot.feedbackBlessing().orElse(null);
        }
    }

    /** UPDATE/RESULT packets may refresh only this exact server session. */
    public boolean isInspecting(UUID settlementId, UUID sessionId) {
        return snapshot != null
            && Objects.equals(snapshot.settlementId(), settlementId)
            && Objects.equals(snapshot.sessionId(), sessionId);
    }

    /** A commit response or refreshed co-op snapshot replaces the whole view. */
    public void update(BlessingSnapshotPayload fresh) {
        if (savedForLater) {
            // CLOSE already released this exact server session. A late packet
            // may not silently make the retired screen actionable again.
            return;
        }
        if (fresh.feedback() == BlessingSnapshotPayload.Feedback.ACCEPTED) {
            HsUi.playConfirmSound();
            QaClientObserver.markUiTransition("blessing_confirm_accepted");
        } else if (fresh.feedback() == BlessingSnapshotPayload.Feedback.STALE
                || fresh.feedback() == BlessingSnapshotPayload.Feedback.INVALID_CHOICE
                || fresh.feedback() == BlessingSnapshotPayload.Feedback.MAXED
                || fresh.feedback() == BlessingSnapshotPayload.Feedback.DELIVERY_BACKLOG
                || fresh.feedback() == BlessingSnapshotPayload.Feedback.TOO_FAR
                || fresh.feedback() == BlessingSnapshotPayload.Feedback.UNAVAILABLE) {
            HsUi.playErrorSound();
        }
        this.snapshot = fresh;
        this.waiting = false;
        this.followUpFeedbackTicks = fresh.hasFollowUpOffer()
            ? FOLLOW_UP_FEEDBACK_TICKS : 0;
        // A successful commit may reveal another stacked offer. Keep the
        // accepted card highlighted only on the terminal receipt: the next
        // offer must always begin unselected and require Select + Confirm.
        this.selected = !fresh.hasPendingOffer() && !fresh.hasFollowUpOffer()
            && fresh.feedback() == BlessingSnapshotPayload.Feedback.ACCEPTED
            ? fresh.feedbackBlessing().orElse(null) : null;
        rebuild();
    }

    @Override
    public void tick() {
        if (savedForLater) {
            laterReceiptTicks = advanceLaterReceipt(laterReceiptTicks);
            if (laterReceiptTicks == 0) {
                onClose();
            }
            return;
        }
        if (followUpFeedbackTicks > 0 && --followUpFeedbackTicks == 0
            && snapshot != null && snapshot.hasFollowUpOffer()) {
            // The server has already authored the next revision and serial.
            // Clearing only the transient receipt cannot mint or spend state.
            update(snapshot.asChoiceUpdate());
        }
    }

    @Override
    protected void init() {
        panelW = Math.min(PANEL_MAX_W, Math.max(1, width - OUTER_MARGIN * 2));
        panelH = Math.min(PANEL_MAX_H, Math.max(1, height - 4));
        left = (width - panelW) / 2;
        top = (height - panelH) / 2;
        cardW = Math.max(1, (panelW - PANEL_PAD * 2 - CARD_GAP * 2) / 3);
        cardH = Math.max(1, panelH - CARDS_TOP - FOOTER_H);
        rebuildCardCopy();
        rebuild();
        if (!uiSoundActive) {
            uiSoundActive = true;
            HsUi.playOpenSound();
        }
    }

    private void rebuild() {
        clearWidgets();
        if (snapshot == null || panelW <= 0) {
            return;
        }
        int centredWidth = panelW - 20;
        titleLine = fitOneLine(TITLE, centredWidth);
        titleLineX = left + 10 + (centredWidth - font.width(titleLine)) / 2;
        subtitleLine = fitOneLine(Component.translatable(
            "hearthstead.blessing.subtitle", snapshot.settlementName(),
            snapshot.offerSerial()), centredWidth);
        subtitleLineX = left + 10 + (centredWidth - font.width(subtitleLine)) / 2;
        headerLine = fitOneLine(headerStatus(), panelW - 24);
        int footerTextRight = savedForLater ? left + panelW - PANEL_PAD
            : snapshot.hasPendingOffer()
            ? left + panelW - PANEL_PAD - CLOSE_W - FOOTER_GAP - CONFIRM_W
                - FOOTER_GAP
            : left + panelW - PANEL_PAD - CLOSE_W - FOOTER_GAP;
        footerLine = fitOneLine(savedForLater
                ? Component.translatable("hearthstead.blessing.later.tip")
                : FOOTER,
            Math.max(1, footerTextRight - (left + 12)));
        cachedHeaderColour = headerColour();

        if (savedForLater) {
            // This short receipt deliberately has no early-close widget. It
            // remains on the same screen until the native frame barrier can
            // acknowledge blessing_later, then tick() closes automatically.
            return;
        }

        if (snapshot.hasPendingOffer()) {
            boolean choiceAllowed = !waiting;
            for (int index = 0; index < BLESSING_IDS.length; index++) {
                BlessingId blessing = BLESSING_IDS[index];
                int x = cardX(index);
                int buttonY = top + CARDS_TOP + cardH - HsUiTokens.BUTTON_H - 6;
                HsButton button = HsButton.normal(x + 6, buttonY,
                    Math.max(1, cardW - 12), HsUiTokens.BUTTON_H,
                    cardButtonLabel(blessing), () -> choose(blessing));
                button.active = choiceAllowed;
                button.setTooltip(Tooltip.create(Component.translatable(
                    selected == blessing
                        ? "hearthstead.blessing.selected.tip"
                        : "hearthstead.blessing.select.tip")));
                addRenderableWidget(button);
            }

            int laterX = left + panelW - PANEL_PAD - CLOSE_W;
            int footerY = top + panelH - HsUiTokens.BUTTON_H - 4;
            HsButton confirm = HsButton.normal(
                laterX - FOOTER_GAP - CONFIRM_W, footerY,
                CONFIRM_W, HsUiTokens.BUTTON_H,
                Component.translatable("hearthstead.blessing.confirm"),
                this::confirmSelection);
            confirm.active = choiceAllowed && selected != null;
            confirm.setTooltip(Tooltip.create(Component.translatable(
                "hearthstead.blessing.confirm.tip")));
            addRenderableWidget(confirm);

            HsButton later = HsButton.normal(
                laterX, footerY, CLOSE_W, HsUiTokens.BUTTON_H,
                Component.translatable("hearthstead.blessing.later"),
                this::chooseLater);
            later.setTooltip(Tooltip.create(Component.translatable(
                "hearthstead.blessing.later.tip")));
            addRenderableWidget(later);
            return;
        }

        addRenderableWidget(HsButton.normal(
            left + panelW - PANEL_PAD - CLOSE_W,
            top + panelH - HsUiTokens.BUTTON_H - 4,
            CLOSE_W, HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.blessing.close"), this::onClose));
    }

    private Component cardButtonLabel(BlessingId blessing) {
        return Component.translatable(selected == blessing
            ? "hearthstead.blessing.selected" : "hearthstead.blessing.select");
    }

    private void choose(BlessingId blessing) {
        if (snapshot == null || waiting || !snapshot.hasPendingOffer()) {
            return;
        }
        selected = blessing;
        QaClientObserver.markUiTransition("blessing_select");
        rebuild();
    }

    private void chooseLater() {
        if (snapshot == null || waiting || !snapshot.hasPendingOffer()) {
            return;
        }
        savedForLater = true;
        waiting = true;
        selected = null;
        laterReceiptTicks = LATER_RECEIPT_TICKS;
        releaseServerSession();
        rebuild();
        QaClientObserver.markUiTransition("blessing_later");
    }

    private void confirmSelection() {
        if (snapshot == null || waiting || selected == null
            || !snapshot.hasPendingOffer()) {
            return;
        }
        waiting = true;
        QaClientObserver.markUiTransition("blessing_confirm_submit");
        PacketDistributor.sendToServer(new BlessingActionPayload(
            snapshot.settlementId(), snapshot.sessionId(),
            BlessingActionPayload.Kind.CONFIRM, snapshot.revision(),
            snapshot.offerSerial(),
            selected.wireId()));
        rebuild();
    }

    /** Release only this exact OPEN; old screens cannot close their successor. */
    @Override
    public void removed() {
        releaseServerSession();
        if (uiSoundActive) {
            uiSoundActive = false;
            HsUi.playCloseSound();
        }
        super.removed();
    }

    /** Releases the exact server OPEN once, even while Later shows its receipt. */
    private void releaseServerSession() {
        if (closeSent || snapshot == null) {
            return;
        }
        closeSent = true;
        if (minecraft != null && minecraft.getConnection() != null) {
            PacketDistributor.sendToServer(new BlessingActionPayload(
                snapshot.settlementId(), snapshot.sessionId(),
                BlessingActionPayload.Kind.CLOSE, snapshot.revision(),
                snapshot.offerSerial(), -1));
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Widgets keep normal keyboard/mouse semantics. A click on the rest of
        // a card is only a local selection; it can never bypass Confirm.
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != 0 || snapshot == null || waiting
            || !snapshot.hasPendingOffer()) {
            return false;
        }
        int cardY = top + CARDS_TOP;
        for (int index = 0; index < BLESSING_IDS.length; index++) {
            int cardX = cardX(index);
            if (mouseX >= cardX && mouseX < cardX + cardW
                && mouseY >= cardY && mouseY < cardY + cardH) {
                choose(BLESSING_IDS[index]);
                return true;
            }
        }
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY,
                       float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        if (snapshot == null) {
            HsUi.widgets(this, graphics, mouseX, mouseY, partialTick);
            return;
        }

        // Dark oak body held inside a simple iron-and-rivet frame. The
        // existing UI sprites supply material detail; no new binary art is
        // needed for a panel that can resize at every GUI scale.
        graphics.fill(left - 2, top - 2, left + panelW + 2, top + panelH + 2,
            OAK_SHADOW);
        HsUi.window(graphics, left, top, panelW, panelH);
        drawIronFrame(graphics);

        graphics.drawString(font, titleLine, titleLineX, top + 8,
            HsUiTokens.TEXT_STRONG, true);
        graphics.drawString(font, subtitleLine, subtitleLineX, top + 20,
            HsUiTokens.ACCENT, true);
        HsUi.divider(graphics, left + 10, top + 32, panelW - 20);
        graphics.drawString(font, headerLine, left + 12, top + 39,
            cachedHeaderColour, true);

        for (int index = 0; index < BLESSING_IDS.length; index++) {
            drawCard(graphics, BLESSING_IDS[index], index,
                mouseX, mouseY);
        }

        HsUi.divider(graphics, left + 10, top + panelH - FOOTER_H + 1,
            panelW - 20);
        graphics.drawString(font, footerLine, left + 12, top + panelH - 17,
            HsUiTokens.TEXT_MUTED, true);
        HsUi.widgets(this, graphics, mouseX, mouseY, partialTick);
    }

    private void drawCard(GuiGraphics graphics, BlessingId blessing, int index,
                          int mouseX, int mouseY) {
        int x = cardX(index);
        int y = top + CARDS_TOP;
        boolean hovered = mouseX >= x && mouseX < x + cardW
            && mouseY >= y && mouseY < y + cardH;
        boolean highlighted = selected == blessing;
        HsUi.card(graphics, x, y, cardW, cardH, hovered || highlighted);
        graphics.fill(x + 2, y + 2, x + cardW - 2, y + 4,
            blessingColour(blessing));
        if (highlighted) {
            drawSelectionFrame(graphics, x, y, cardW, cardH);
        }

        int innerX = x + 6;
        int innerW = Math.max(1, cardW - 12);
        int buttonY = top + CARDS_TOP + cardH - HsUiTokens.BUTTON_H - 6;
        boolean compact = cardH < COMPACT_CARD_H;
        CardCopy copy = cardCopy.get(blessing);
        graphics.renderItem(sealIcons.get(blessing), innerX, y + 6);
        int cursorY = drawCachedLines(graphics, copy.nameLines(),
            innerX + 20, y + 8, HsUiTokens.TEXT_STRONG);
        cursorY = Math.max(cursorY, y + 24) + 1;

        graphics.drawString(font, sealLine, innerX, cursorY,
            HsUiTokens.ACCENT, true);
        cursorY += 11;

        if (compact) {
            graphics.drawString(font, termsLine, innerX, cursorY,
                HsUiTokens.TEXT_MUTED, true);
            drawCachedLines(graphics, copy.compactLines(), innerX,
                cursorY + 9, HsUiTokens.TEXT);
            graphics.drawString(font, targetRanksLine, innerX, buttonY - 13,
                HsUiTokens.GOOD, true);
            return;
        }

        graphics.drawString(font, termsLine, innerX, cursorY,
            HsUiTokens.TEXT_MUTED, true);
        cursorY += 11;

        int compareY = buttonY - 13;
        drawCachedLines(graphics, copy.effectLines(), innerX,
            cursorY, HsUiTokens.TEXT);
        graphics.drawString(font, targetRanksLine, innerX, compareY,
            HsUiTokens.GOOD, true);
    }

    private int drawCachedLines(GuiGraphics graphics,
                                List<FormattedCharSequence> lines,
                                int x, int y, int colour) {
        int cursor = y;
        for (int index = 0, size = lines.size(); index < size; index++) {
            graphics.drawString(font, lines.get(index), x, cursor, colour, true);
            cursor += 9;
        }
        return cursor;
    }

    /** Fits every possible card variant once for this exact GUI layout. */
    private void rebuildCardCopy() {
        int innerW = Math.max(1, cardW - 12);
        int nameWidth = Math.max(1, innerW - 20);
        int buttonY = top + CARDS_TOP + cardH - HsUiTokens.BUTTON_H - 6;
        sealLine = fitOneLine(SEAL_LABEL, innerW);
        termsLine = fitOneLine(TERMS_LABEL, innerW);
        targetRanksLine = fitOneLine(TARGET_RANKS_LABEL, innerW);
        for (BlessingId blessing : BLESSING_IDS) {
            CardCopy source = cardCopy.get(blessing);
            List<FormattedCharSequence> names = fitLines(source.name(), nameWidth, 2);
            int cursorY = Math.max(top + CARDS_TOP + 8 + names.size() * 9,
                top + CARDS_TOP + 24) + 1 + 11;
            // Compact keeps three independent factual rows: valid targets,
            // the type's exact number, then the permanent I-III contract.
            // Keeping the numeric copy deliberately short prevents scale-4
            // from ellipsizing the balance value out of view.
            int compactTextY = cursorY + 9;
            int compactCompareY = buttonY - 13;
            int compactMax = Math.max(0,
                (compactCompareY - compactTextY) / 9);
            int regularTextY = cursorY + 11;
            int compareY = buttonY - 13;
            int regularMax = Math.max(0, (compareY - 2 - regularTextY) / 9);
            cardCopy.put(blessing, source.withLines(names,
                fitLines(source.effect(), innerW, regularMax),
                fitLines(source.compact(), innerW, compactMax)));
        }
    }

    /**
     * Returns immutable, already-ellipsized lines. Any string work happens on
     * init/resize instead of being multiplied by three cards and every frame.
     */
    private List<FormattedCharSequence> fitLines(Component text, int maxWidth,
                                                  int maxLines) {
        if (maxLines <= 0) {
            return List.of();
        }
        List<FormattedCharSequence> split = font.split(text, Math.max(1, maxWidth));
        if (split.size() <= maxLines) {
            return List.copyOf(split);
        }
        ArrayList<FormattedCharSequence> fitted =
            new ArrayList<>(split.subList(0, maxLines));
        String suffix = "...";
        String plain = plainText(fitted.get(maxLines - 1));
        int textWidth = Math.max(1, maxWidth - font.width(suffix));
        String shown = font.plainSubstrByWidth(plain, textWidth) + suffix;
        fitted.set(maxLines - 1, FormattedCharSequence.forward(shown, Style.EMPTY));
        return List.copyOf(fitted);
    }

    private String fitOneLine(Component text, int maxWidth) {
        maxWidth = Math.max(1, maxWidth);
        String shown = text.getString();
        if (font.width(shown) > maxWidth) {
            String suffix = "...";
            shown = font.plainSubstrByWidth(shown,
                Math.max(1, maxWidth - font.width(suffix))) + suffix;
        }
        return shown;
    }

    private static String plainText(FormattedCharSequence sequence) {
        StringBuilder text = new StringBuilder();
        sequence.accept((index, style, codePoint) -> {
            text.appendCodePoint(codePoint);
            return true;
        });
        return text.toString();
    }

    private Component headerStatus() {
        if (savedForLater) {
            return Component.translatable("hearthstead.blessing.later.tip");
        }
        if (waiting) {
            return Component.translatable("hearthstead.blessing.feedback.waiting");
        }
        if (selected != null && snapshot.hasPendingOffer()) {
            return Component.translatable("hearthstead.blessing.feedback.confirm",
                blessingName(selected));
        }
        return switch (snapshot.feedback()) {
            case NONE -> Component.translatable("hearthstead.blessing.feedback.select");
            case ACCEPTED -> Component.translatable(
                "hearthstead.blessing.feedback.accepted",
                snapshot.feedbackBlessing().map(this::blessingName)
                    .orElse(Component.literal("?")));
            case STALE -> Component.translatable("hearthstead.blessing.feedback.stale");
            case OTHER_PLAYER_CHOSE -> Component.translatable(
                "hearthstead.blessing.feedback.other_player");
            case INVALID_CHOICE -> Component.translatable(
                "hearthstead.blessing.feedback.invalid");
            case TOO_FAR -> Component.translatable("hearthstead.blessing.feedback.too_far");
            case MAXED -> Component.translatable("hearthstead.blessing.feedback.maxed");
            case DELIVERY_BACKLOG -> Component.translatable(
                "hearthstead.blessing.feedback.delivery_backlog");
            case UNAVAILABLE -> Component.translatable(
                "hearthstead.blessing.feedback.unavailable");
        };
    }

    private int headerColour() {
        if (selected != null && snapshot.hasPendingOffer()) {
            return HsUiTokens.ACCENT;
        }
        return switch (snapshot.feedback()) {
            case ACCEPTED -> HsUiTokens.GOOD;
            case STALE, MAXED, DELIVERY_BACKLOG -> HsUiTokens.WARN;
            case OTHER_PLAYER_CHOSE, INVALID_CHOICE, TOO_FAR, UNAVAILABLE -> HsUiTokens.BAD;
            case NONE -> selected == null ? HsUiTokens.TEXT_MUTED : HsUiTokens.ACCENT;
        };
    }

    private Component blessingName(BlessingId blessing) {
        return cardCopy.get(blessing).name();
    }

    private int cardX(int index) {
        return left + PANEL_PAD + index * (cardW + CARD_GAP);
    }

    private static int blessingColour(BlessingId blessing) {
        return switch (blessing) {
            case WARDEN_OATH -> 0xFF9B583F;
            case HEARTHWARD -> 0xFFB8912F;
            case THORNED_ROADS -> 0xFF557A4B;
        };
    }

    private void drawIronFrame(GuiGraphics graphics) {
        graphics.fill(left, top, left + panelW, top + 2, IRON_LIGHT);
        graphics.fill(left, top + panelH - 2, left + panelW, top + panelH, IRON_DARK);
        graphics.fill(left, top, left + 2, top + panelH, IRON);
        graphics.fill(left + panelW - 2, top, left + panelW, top + panelH, IRON_DARK);
        drawRivet(graphics, left + 4, top + 4);
        drawRivet(graphics, left + panelW - 6, top + 4);
        drawRivet(graphics, left + 4, top + panelH - 6);
        drawRivet(graphics, left + panelW - 6, top + panelH - 6);
    }

    private static void drawRivet(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + 2, y + 2, IRON_LIGHT);
    }

    private static void drawSelectionFrame(GuiGraphics graphics, int x, int y,
                                           int w, int h) {
        int colour = HsUiTokens.ACCENT;
        graphics.fill(x, y, x + w, y + 1, colour);
        graphics.fill(x, y + h - 1, x + w, y + h, colour);
        graphics.fill(x, y, x + 1, y + h, colour);
        graphics.fill(x + w - 1, y, x + w, y + h, colour);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !savedForLater;
    }

    static int advanceLaterReceipt(int remainingTicks) {
        return Math.max(0, remainingTicks - 1);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public String qaUiState() {
        String offer = savedForLater ? "saved_for_later"
            : snapshot == null ? "none"
            : snapshot.hasPendingOffer() ? "pending"
            : snapshot.hasFollowUpOffer() ? "follow_up" : "receipt";
        return "offer=" + offer + ",selected="
            + (selected == null ? "none" : selected.id())
            + ",waiting=" + waiting + ",panel=" + left + ":" + top
            + ":" + panelW + ":" + panelH + ",cards=" + cardW + "x"
            + cardH + ",compact=" + (cardH < COMPACT_CARD_H)
            + ",later_ticks=" + laterReceiptTicks;
    }

    private record CardCopy(Component name, Component effect, Component compact,
                            List<FormattedCharSequence> nameLines,
                            List<FormattedCharSequence> effectLines,
                            List<FormattedCharSequence> compactLines) {
        private static CardCopy unmeasured(BlessingId blessing) {
            String base = "hearthstead.blessing." + blessing.id();
            return new CardCopy(Component.translatable(base + ".name"),
                Component.translatable(base + ".effect"),
                Component.translatable(base + ".compact"),
                List.of(), List.of(), List.of());
        }

        private CardCopy withLines(List<FormattedCharSequence> names,
                                   List<FormattedCharSequence> effects,
                                   List<FormattedCharSequence> compactEffects) {
            return new CardCopy(name, effect, compact,
                names, effects, compactEffects);
        }
    }
}
