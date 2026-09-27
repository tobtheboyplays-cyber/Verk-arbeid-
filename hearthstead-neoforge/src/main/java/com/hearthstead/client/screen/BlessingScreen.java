package com.hearthstead.client.screen;

import com.hearthstead.client.QaClientObserver;
import com.hearthstead.client.QaUiInspectable;
import com.hearthstead.client.ui.HsMotion;
import com.hearthstead.client.ui.HsButton;
import com.hearthstead.client.ui.HsUi;
import com.hearthstead.client.ui2.BannerChrome;
import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import com.hearthstead.client.ui2.Ui2Frame;
import com.hearthstead.client.ui2.Ui2FrameLayout;
import com.hearthstead.client.ui2.Ui2Palette;
import com.hearthstead.client.ui2.Ui2Serif;
import com.hearthstead.client.ui2.Ui2Surface;
import com.hearthstead.client.ui2.Ui2WoodKey;
import com.hearthstead.item.BlessingSealItem;
import com.hearthstead.network.BlessingActionPayload;
import com.hearthstead.network.BlessingSnapshotPayload;
import com.hearthstead.settlement.state.BlessingId;
import com.hearthstead.settlement.state.BlessingQuality;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One card activation claims one physical Blessing seal through the server.
 * The exact authoritative receipt controls its celebration and delivery.
 * Permanent binding to a settler or plaque happens afterwards, in the world.
 *
 * <p>Standard Bannerhold window ({@link Ui2Frame}): walnut board, crest,
 * serif title with the settlement subtitle on the wood and one parchment
 * page holding the status strip, the three seal cards and the footer hint.
 * The wooden close key appears only when leaving is allowed (terminal
 * receipts and unusable offers); a valid choice stays mandatory.
 */
public final class BlessingScreen extends Screen implements QaUiInspectable {

    /** values() clones its backing array; keep the fixed three-card order once. */
    private static final BlessingId[] BLESSING_IDS = BlessingId.values();

    private static final int PANEL_MAX_W = 464; // size check: Banner footprint (was 600)
    private static final int PANEL_MAX_H = 256; // size check: Banner footprint (was 330)
    private static final int STATUS_H = 16;
    private static final int CARD_GAP = 8;
    /** The seal crown rises this far above the card face (and is part of its hit area). */
    private static final int CROWN = 17;
    /** Card face offsets: name, first effect line and the claim row at the bottom. */
    private static final int NAME_Y = 26;
    private static final int EFFECT_Y = 40;
    private static final int CLAIM_ROW_H = 12;
    private static final int TERMS_MIN_CARD_H = 150;
    /** One readable co-op receipt before a queued offer becomes actionable. */
    private static final int FOLLOW_UP_FEEDBACK_TICKS = 30;
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
    private static final Component CLAIM_LABEL = Component.translatable(
        "hearthstead.blessing.claim");

    private BlessingSnapshotPayload snapshot;
    private final BlessingClientClaim claim;
    private BlessingRewardAnimation rewardAnimation;
    /** Built once per screen; rendering must not allocate three stacks/frame. */
    private final EnumMap<BlessingId, net.minecraft.world.item.ItemStack> sealIcons =
        new EnumMap<>(BlessingId.class);
    /**
     * Localized card copy and its already-fitted lines. Rebuilt only when the
     * screen layout is initialized/resized, never during render().
     */
    private final EnumMap<BlessingId, CardCopy> cardCopy =
        new EnumMap<>(BlessingId.class);
    private final EnumMap<BlessingId, Ui2Serif.Text> cardNames =
        new EnumMap<>(BlessingId.class);
    private final Ui2Serif.Text titleText = new Ui2Serif.Text(Ui2Serif.Size.TITLE);
    private final Ui2Serif.Text claimText = new Ui2Serif.Text(Ui2Serif.Size.HEADING);
    private Component subtitleLine = Component.empty();
    private Component headerLine = Component.empty();
    private Component headerNarration = Component.empty();
    private Component footerLine = Component.empty();
    private List<FormattedCharSequence> headerTooltip = List.of();
    private List<FormattedCharSequence> footerTooltip = List.of();
    private Ui2Frame.Tone cachedHeaderTone = Ui2Frame.Tone.NEUTRAL;
    private BlessingId selected;
    private boolean waiting;
    private boolean closeSent;
    private boolean uiSoundActive;
    private int followUpFeedbackTicks;
    private Layout layout;
    private int left;
    private int top;
    private int panelW;
    private int panelH;
    private int cardW;
    private int cardH;
    private final InputEdges inputEdges = new InputEdges();
    private Ui2WoodKey closeButton;

    public BlessingScreen(BlessingSnapshotPayload snapshot) {
        super(TITLE);
        this.snapshot = snapshot;
        this.claim = new BlessingClientClaim(snapshot);
        for (BlessingId blessing : BLESSING_IDS) {
            sealIcons.put(blessing, BlessingSealItem.stackFor(blessing));
            cardCopy.put(blessing, CardCopy.unmeasured(blessing));
            cardNames.put(blessing, new Ui2Serif.Text(Ui2Serif.Size.HEADING));
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
        if (fresh == null || !claim.accept(fresh,
                minecraft == null || minecraft.player == null ? null : minecraft.player.getUUID())) {
            return;
        }
        FocusTarget retainedFocus = focusedTarget();
        if (fresh.feedback() == BlessingSnapshotPayload.Feedback.ACCEPTED) {
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
        // offer must always begin unselected and require a fresh card activation.
        this.selected = !fresh.hasPendingOffer() && !fresh.hasFollowUpOffer()
            && fresh.feedback() == BlessingSnapshotPayload.Feedback.ACCEPTED
            ? fresh.feedbackBlessing().orElse(null) : null;
        rebuildCardCopy();
        rebuild(retainedFocus);
    }

    @Override
    public void tick() {
        if (claim.presenting()) {
            var player = minecraft == null ? null : minecraft.player;
            if (claim.phase() == BlessingClientClaim.Phase.WAITING_SLOT) {
                var receipt = claim.receipt();
                ItemStack slot = player == null ? ItemStack.EMPTY
                    : player.getInventory().getItem(receipt.playerSlot());
                boolean exact = player != null && receipt.matchesSlot(player.getUUID(),
                    receipt.playerSlot(), slot);
                if (claim.tickSlot(exact)) {
                    rewardAnimation = new BlessingRewardAnimation(slot, receipt.playerSlot());
                    HsUi.playConfirmSound();
                }
            } else if (claim.phase() == BlessingClientClaim.Phase.ANIMATING) {
                rewardAnimation.tick(player);
                if (rewardAnimation.finished()) claim.animationFinished();
            } else {
                claim.tickNotice();
            }
            if (claim.phase() != BlessingClientClaim.Phase.FINISHED) return;
            rewardAnimation = null;
            claim.clearFinished();
            if (snapshot.hasFollowUpOffer()) {
                // This is only the next offer already authored by the server.
                snapshot = snapshot.asChoiceUpdate();
                selected = null;
                followUpFeedbackTicks = 0;
                rebuildCardCopy();
                rebuild(null);
            } else if (snapshot.hasPendingOffer()) {
                selected = null;
                rebuildCardCopy();
                rebuild(null);
            } else if (minecraft != null && minecraft.screen == this) {
                minecraft.setScreen(null);
            }
            return;
        }
        if (followUpFeedbackTicks > 0 && --followUpFeedbackTicks == 0
                && snapshot != null && snapshot.hasFollowUpOffer()) {
            snapshot = snapshot.asChoiceUpdate();
            selected = null;
            rebuildCardCopy();
            rebuild(null);
        }
    }

    @Override
    protected void init() {
        layout = layoutFor(width, height);
        Ui2FrameLayout frame = layout.frame();
        panelW = frame.width();
        panelH = frame.height();
        left = frame.x();
        top = frame.y();
        cardW = layout.card(0).width();
        cardH = layout.card(0).height();
        rebuildCardCopy();
        rebuild();
        if (!uiSoundActive) {
            uiSoundActive = true;
            HsUi.playOpenSound();
        }
    }

    private void rebuild() {
        rebuild(focusedTarget());
    }

    private void rebuild(FocusTarget requestedFocus) {
        clearWidgets();
        setFocused(null);
        closeButton = null;
        if (snapshot == null || panelW <= 0) {
            return;
        }
        Component subtitle = snapshot.feedback() == BlessingSnapshotPayload.Feedback.ACCEPTED
            ? Component.translatable("hearthstead.blessing.subtitle.secured",
                snapshot.settlementName())
            : Component.translatable("hearthstead.blessing.subtitle", snapshot.settlementName(),
                snapshot.offerSerial());
        subtitleLine = subtitle;
        Component fullHeader = headerStatus();
        Rect status = layout.status();
        headerLine = clipLine(fullHeader, status.width() - 18);
        headerNarration = fullHeader;
        headerTooltip = wrapTooltip(fullHeader);
        Component fullFooter = !snapshot.hasPendingOffer()
                ? Component.translatable("hearthstead.blessing.footer.binding")
                : FOOTER;
        footerLine = clipLine(fullFooter, layout.footer().width());
        footerTooltip = wrapTooltip(fullFooter);
        cachedHeaderTone = headerTone();

        if (claim.presenting()) {
            return;
        }
        if (snapshot.hasPendingOffer()) {
            boolean choiceAllowed = !waiting;
            for (int index = 0; index < BLESSING_IDS.length; index++) {
                BlessingId blessing = BLESSING_IDS[index];
                Rect hit = layout.hit(index);
                CardHit button = new CardHit(hit.x(), hit.y(), hit.width(), hit.height(),
                    blessingName(blessing), () -> claimCard(blessing),
                    FocusTarget.card(blessing));
                button.active = choiceAllowed;
                button.setNarrationHint(cardCopy.get(blessing).tooltipText()
                    .copy().append("\n").append(Component.translatable(
                        "hearthstead.blessing.select.tip")));
                addRenderableWidget(button);
            }

            if (!requiresChoice(snapshot)) {
                addTerminalCloseKey();
            }
            restoreFocus(requestedFocus);
            return;
        }

        if (!snapshot.hasFollowUpOffer()) {
            addTerminalCloseKey();
        }
        restoreFocus(requestedFocus);
    }

    /** The footer "Close" button is now the standard wooden key; Esc closes the same way. */
    private void addTerminalCloseKey() {
        closeButton = Ui2Frame.closeKey(layout.frame(),
            Component.translatable("hearthstead.blessing.close").copy().append(" (Esc)"), this::onClose);
        addRenderableWidget(closeButton);
    }

    private void claimCard(BlessingId blessing) {
        if (!canClaim(snapshot, waiting, claim.presenting()) || blessing == null) {
            return;
        }
        if (!claim.submit(snapshot, blessing)) {
            return;
        }
        selected = blessing;
        waiting = true;
        QaClientObserver.markUiTransition("blessing_card_claim");
        QaClientObserver.markUiTransition("blessing_confirm_submit");
        PacketDistributor.sendToServer(new BlessingActionPayload(
            snapshot.settlementId(), snapshot.sessionId(),
            BlessingActionPayload.Kind.CONFIRM, snapshot.revision(),
            snapshot.offerSerial(), blessing.wireId()));
        rebuild(FocusTarget.card(blessing));
    }

    static boolean canClaim(BlessingSnapshotPayload state, boolean waiting,
                            boolean presenting) {
        return state != null && !waiting && !presenting && state.hasPendingOffer();
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

    /** Releases the exact server OPEN once, including terminal/world-exit cleanup. */
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
        if (button == 0 && !inputEdges.press(-1)) {
            return true;
        }
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        if (handled) {
            GuiEventListener focused = getFocused();
            if (minecraft != null && minecraft.screen == this
                && focused != null && !isCurrentChild(focused)) {
                restoreFocus(focusedTarget());
            }
        }
        return handled;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) inputEdges.release(-1);
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (isActivationKey(keyCode) && !inputEdges.press(keyCode)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (isActivationKey(keyCode)) inputEdges.release(keyCode);
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    private static boolean isActivationKey(int keyCode) {
        return keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
            || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER
            || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
    }

    /** Held activation never crosses a resize, refusal or queued-offer boundary. */
    static final class InputEdges {
        private final Set<Integer> down = new HashSet<>();

        boolean press(int input) {
            return down.add(input);
        }

        void release(int input) {
            down.remove(input);
        }
    }

    private void renderReceipt(GuiGraphics graphics) {
        int center = width / 2;
        int y = height / 2;
        var winner = claim.winner();
        if (winner != null) {
            graphics.pose().pushPose();
            graphics.pose().translate(center - 16, y - 36, 0);
            graphics.pose().scale(2.0F, 2.0F, 1.0F);
            int units = claim.receipt() == null ? 1 : claim.receipt().rankUnits();
            graphics.renderItem(BlessingSealItem.stackFor(winner,
                BlessingQuality.fromRankUnits(units).orElseThrow()), 0, 0);
            graphics.pose().popPose();
        }
        String key = "hearthstead.blessing.animation.secured";
        if (claim.phase() == BlessingClientClaim.Phase.WAITING_SLOT) {
            key = "hearthstead.blessing.animation.waiting";
        } else if (claim.receipt() != null) {
            key = switch (claim.receipt().outcome()) {
                case DROP -> "hearthstead.blessing.animation.world";
                case PENDING -> "hearthstead.blessing.animation.pending";
                default -> key;
            };
        }
        // Over the dimmed world (no window): the HUD's text-on-wood colour.
        graphics.drawCenteredString(font, Component.translatable(key), center, y + 12,
            BannerChrome.TEXT_ON_WOOD);
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
        if (snapshot == null) {
            HsUi.widgets(this, graphics, mouseX, mouseY, partialTick);
            return;
        }

        if (claim.presenting()) {
            if (rewardAnimation != null) {
                rewardAnimation.render(graphics, font, minecraft.player, width, height, partialTick);
            } else {
                renderReceipt(graphics);
            }
            return;
        }
        Ui2FrameLayout frame = layout.frame();
        Ui2Frame.draw(graphics, frame);
        Ui2Frame.title(graphics, font, frame, titleText, TITLE.getString(), subtitleLine);

        // Status strip: bar + glyph carry the state, the sentence is ink.
        Rect status = layout.status();
        Ui2Frame.status(graphics, font, status, Component.empty(), cachedHeaderTone);
        graphics.drawString(font, headerLine, status.x() + 14, status.y() + 4, Ui2Palette.INK, false);

        List<FormattedCharSequence> pendingTooltip = null;
        if (status.contains(mouseX, mouseY)) {
            pendingTooltip = headerTooltip;
        }

        for (int index = 0; index < BLESSING_IDS.length; index++) {
            List<FormattedCharSequence> cardTooltip = drawCard(graphics,
                BLESSING_IDS[index], index, mouseX, mouseY);
            if (cardTooltip != null) {
                pendingTooltip = cardTooltip;
            }
        }

        Rect footer = layout.footer();
        Ui2Surface.rule(graphics, footer.x(), footer.y() - Ui2FrameLayout.S, footer.width());
        graphics.drawString(font, footerLine, footer.x(), footer.y(), Ui2Palette.INK_MUTED, false);
        if (footer.contains(mouseX, mouseY)) {
            pendingTooltip = footerTooltip;
        }
        HsUi.widgets(this, graphics, mouseX, mouseY, partialTick);
        if (pendingTooltip != null && !pendingTooltip.isEmpty()) {
            graphics.renderTooltip(font, pendingTooltip, mouseX, mouseY);
        }
    }

    @Override
    protected void updateNarrationState(NarrationElementOutput output) {
        super.updateNarrationState(output);
        if (!headerNarration.getString().isEmpty()) {
            output.add(NarratedElementType.HINT, headerNarration);
        }
    }

    private List<FormattedCharSequence> drawCard(
            GuiGraphics graphics, BlessingId blessing, int index,
            int mouseX, int mouseY) {
        Rect card = layout.card(index);
        int x = card.x();
        int y = card.y();
        boolean hovered = layout.hit(index).contains(mouseX, mouseY);
        boolean highlighted = selected == blessing
            || FocusTarget.card(blessing).equals(focusedTarget());
        boolean active = canClaim(snapshot, waiting, claim.presenting());
        boolean rare = snapshot.rankUnits(blessing) == 2;
        int accent = cardAccent(blessing);
        // An inset parchment card: hairline edge, the blessing's colour as a
        // band under the seal, gold edge for a rare (two-rank) seal.
        graphics.fill(x, y, x + cardW, y + cardH, Ui2Palette.INSET);
        BannerChrome.outline(graphics, x, y, cardW, cardH,
            rare || hovered && active ? Ui2Palette.GOLD : Ui2Palette.RULE_STRONG);
        if (rare) BannerChrome.outline(graphics, x + 1, y + 1, cardW - 2, cardH - 2, Ui2Palette.GOLD_SOFT);
        graphics.fill(x + 2, y + 2, x + cardW - 2, y + 4, accent);
        if (hovered && active && !highlighted) {
            graphics.fill(x + 1, y + 1, x + cardW - 1, y + cardH - 1, Ui2Palette.ROW_HOVER);
        }
        if (highlighted) {
            // Selected / keyboard focus: the forest selection edge.
            BannerChrome.outline(graphics, x - 1, y - 1, cardW + 2, cardH + 2, Ui2Palette.FOREST);
            BannerChrome.outline(graphics, x, y, cardW, cardH, Ui2Palette.FOREST);
        }
        drawSealCrown(graphics, blessing, x + cardW / 2, y + 3, accent, rare, highlighted);

        CardCopy copy = cardCopy.get(blessing);
        Ui2Serif.Text name = cardNames.get(blessing);
        name.fit(font, copy.name().getString(), rowTextWidth());
        name.draw(graphics, font, x + (cardW - name.width()) / 2, y + NAME_Y, Ui2Palette.INK);

        int claimRuleY = cardH - CLAIM_ROW_H - 6;
        boolean terms = cardH >= TERMS_MIN_CARD_H;
        int textBottom = terms ? claimRuleY - 16 : claimRuleY - 2;
        int effects = copy.effectLines().size();
        int metaY0 = effects * 10 + 2;
        int metaRoom = Math.max(0, (textBottom - EFFECT_Y - metaY0) / 9);
        int metas = Math.min(copy.metaLines().size(), metaRoom);
        int blockH = metaY0 + metas * 9;
        int descriptionY = y + EFFECT_Y + Math.max(0, (textBottom - EFFECT_Y - blockH) / 2);
        for (int line = 0; line < effects; line++) {
            FormattedCharSequence text = copy.effectLines().get(line);
            graphics.drawString(font, text, x + (cardW - font.width(text)) / 2,
                descriptionY + line * 10, Ui2Palette.INK, false);
        }
        for (int line = 0; line < metas; line++) {
            FormattedCharSequence text = copy.metaLines().get(line);
            graphics.drawString(font, text, x + (cardW - font.width(text)) / 2,
                descriptionY + metaY0 + line * 9, Ui2Palette.INK_MUTED, false);
        }
        if (terms) {
            drawCentredInk(graphics, copy.termsLine(), x + cardW / 2, y + claimRuleY - 12,
                Ui2Palette.INK_MUTED);
        }
        Ui2Surface.rule(graphics, x + 8, y + claimRuleY, cardW - 16);
        drawClaimRow(graphics, x + 4, y + cardH - CLAIM_ROW_H - 3, cardW - 8, active,
            active && (hovered || highlighted && selected == null));
        // The seal crown remains the deliberate full-detail hover target.
        // Whole-card activation requests this seal without a second button.
        boolean inspectingSeal = Math.abs(mouseX - (x + cardW / 2))
            + Math.abs(mouseY - (y + 3)) <= 20;
        return inspectingSeal ? copy.tooltipLines() : null;
    }

    /**
     * The card's call to action: burgundy serif label and chevron. The card
     * under the pointer (or keyboard focus) lights as the one burgundy
     * primary; while the server is deciding the label shows the padlock.
     */
    private void drawClaimRow(GuiGraphics graphics, int x, int y, int w, boolean active, boolean lit) {
        int ink = !active ? Ui2Palette.INK_DISABLED : lit ? Ui2Palette.ON_BURGUNDY : Ui2Palette.BURGUNDY;
        if (lit) {
            graphics.fill(x, y, x + w, y + CLAIM_ROW_H, Ui2Palette.BURGUNDY_DARK);
            graphics.fill(x + 1, y + 1, x + w - 1, y + CLAIM_ROW_H - 1, Ui2Palette.BURGUNDY);
            graphics.fill(x + 1, y + 1, x + w - 1, y + 2, Ui2Palette.BURGUNDY_HIGHLIGHT);
        }
        int lock = active ? 0 : 8;
        claimText.fit(font, CLAIM_LABEL.getString(), Math.max(1, w - 16 - lock));
        int contentW = lock + claimText.width() + 7;
        int tx = x + (w - contentW) / 2;
        int ty = y + (CLAIM_ROW_H - 8) / 2;
        if (!active) Ui2Surface.lockGlyph(graphics, tx, ty, Ui2Palette.INK_DISABLED);
        claimText.draw(graphics, font, tx + lock, ty, ink);
        int cx = tx + lock + claimText.width() + 4;
        int cy = y + CLAIM_ROW_H / 2;
        for (int i = 0; i < 3; i++) {
            graphics.fill(cx + i, cy - 3 + i, cx + i + 1, cy - 2 + i, ink);
            graphics.fill(cx + i, cy + 2 - i, cx + i + 1, cy + 3 - i, ink);
        }
    }

    private void drawCentredInk(GuiGraphics graphics, Component text, int centreX, int y, int colour) {
        graphics.drawString(font, text, centreX - font.width(text) / 2, y, colour, false);
    }

    /** The seal set in a walnut diamond with the blessing's colour as its rim. */
    private void drawSealCrown(GuiGraphics graphics, BlessingId blessing, int centreX, int centreY,
                               int accent, boolean rare, boolean highlighted) {
        int rim = highlighted ? Ui2Palette.FOREST : rare ? Ui2Palette.GOLD : accent;
        for (int row = -20; row <= 20; row++) {
            int half = 20 - Math.abs(row);
            graphics.fill(centreX - half, centreY + row, centreX + half + 1,
                centreY + row + 1, Ui2Palette.WALNUT_DARK);
            if (half > 2) {
                graphics.fill(centreX - half + 2, centreY + row, centreX + half - 1,
                    centreY + row + 1, rim);
            }
            if (half > 4) {
                graphics.fill(centreX - half + 4, centreY + row, centreX + half - 3,
                    centreY + row + 1, Ui2Palette.WALNUT);
            }
        }
        graphics.pose().pushPose();
        graphics.pose().translate(centreX - 12, centreY - 12, 0);
        graphics.pose().scale(1.5F, 1.5F, 1.0F);
        graphics.renderItem(sealIcons.get(blessing), 0, 0);
        graphics.pose().popPose();
    }

    /** Each blessing keeps one palette colour: oath burgundy, hearth gold, roads forest. */
    private static int cardAccent(BlessingId blessing) {
        return switch (blessing) {
            case WARDEN_OATH -> Ui2Palette.BURGUNDY;
            case HEARTHWARD -> Ui2Palette.GOLD;
            case THORNED_ROADS -> Ui2Palette.FOREST;
        };
    }

    /** Fits every possible card variant once for this exact GUI layout. */
    private void rebuildCardCopy() {
        if (font == null || panelW <= 0) {
            return;
        }
        int textWidth = rowTextWidth();
        MutableComponent meta = TERMS_LABEL.copy().append(" • ")
            .append(TARGET_RANKS_LABEL);
        for (BlessingId blessing : BLESSING_IDS) {
            CardCopy source = cardCopy.get(blessing);
            int units = snapshot == null ? 1 : snapshot.rankUnits(blessing);
            sealIcons.put(blessing, BlessingSealItem.stackFor(blessing,
                BlessingQuality.fromRankUnits(units).orElseThrow()));
            Component qualityLine = Component.translatable(units == 2
                ? "hearthstead.blessing.card.rare" : "hearthstead.blessing.card.common");
            Component issued = Component.translatable(
                "hearthstead.blessing.status",
                snapshot == null ? 0 : snapshot.issuedCount(blessing));
            MutableComponent tooltip = source.name().copy()
                .append("\n").append(source.effect())
                .append("\n").append(SEAL_LABEL)
                .append("\n").append(qualityLine)
                .append("\n").append(meta)
                .append("\n").append(issued);
            cardCopy.put(blessing, source.measured(
                clipLine(TERMS_LABEL, textWidth),
                List.copyOf(font.split(qualityLine, textWidth)),
                List.copyOf(font.split(source.compact(), textWidth)), tooltip, List.copyOf(font.split(tooltip, tooltipWidth()))));
        }
    }

    private int rowTextWidth() {
        return Math.max(1, cardW - 16);
    }

    private int tooltipWidth() {
        return Math.min(280, Math.max(120, panelW - 32));
    }

    private List<FormattedCharSequence> wrapTooltip(Component text) {
        return font == null ? List.of()
            : List.copyOf(font.split(text, tooltipWidth()));
    }

    private Component clipLine(Component text, int maxWidth) {
        if (font == null || text == null || maxWidth <= 0) {
            return Component.empty();
        }
        if (font.width(text) <= maxWidth) {
            return text;
        }
        int contentWidth = Math.max(0, maxWidth - font.width("..."));
        return Component.literal(font.plainSubstrByWidth(
            text.getString(), contentWidth) + "...");
    }

    private Component headerStatus() {
        if (waiting) {
            return Component.translatable("hearthstead.blessing.feedback.waiting");
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

    /** Same states as the old header colours, now as the status strip's bar + glyph. */
    private Ui2Frame.Tone headerTone() {
        if (selected != null && snapshot.hasPendingOffer()) {
            return Ui2Frame.Tone.WAIT;
        }
        return switch (snapshot.feedback()) {
            case ACCEPTED -> Ui2Frame.Tone.GOOD;
            case STALE, MAXED, DELIVERY_BACKLOG -> Ui2Frame.Tone.WAIT;
            case OTHER_PLAYER_CHOSE, INVALID_CHOICE, TOO_FAR, UNAVAILABLE -> Ui2Frame.Tone.BAD;
            case NONE -> Ui2Frame.Tone.NEUTRAL;
        };
    }

    private Component blessingName(BlessingId blessing) {
        return cardCopy.get(blessing).name();
    }

    private FocusTarget focusedTarget() {
        GuiEventListener focused = getFocused();
        if (focused instanceof FocusTagged tagged) return tagged.focusTarget();
        return focused != null && focused == closeButton ? FocusTarget.close() : null;
    }

    private void restoreFocus(FocusTarget target) {
        setFocused(currentChildFor(target));
    }

    private GuiEventListener currentChildFor(FocusTarget target) {
        if (target == null) {
            return null;
        }
        if (target.kind() == FocusKind.CLOSE) {
            return closeButton != null && closeButton.active && closeButton.visible
                && isCurrentChild(closeButton) ? closeButton : null;
        }
        for (GuiEventListener child : children()) {
            if (child instanceof CardHit button
                && button.active && button.visible
                && target.equals(button.focusTarget())) {
                return button;
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

    /** GUI-scale rebuilds retain the exact Blessing action, not a stale widget. */
    @Override
    protected void rebuildWidgets() {
        FocusTarget retainedFocus = focusedTarget();
        super.rebuildWidgets();
        restoreFocus(retainedFocus);
    }

    /** Mandatory only while a valid choice or the next queued choice is available. */
    static boolean requiresChoice(BlessingSnapshotPayload state) {
        return state != null && (state.hasFollowUpOffer()
            || state.hasPendingOffer()
                && state.feedback() != BlessingSnapshotPayload.Feedback.DELIVERY_BACKLOG);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !requiresChoice(snapshot);
    }

    @Override
    public void onClose() {
        if (!requiresChoice(snapshot)) {
            super.onClose();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public String qaUiState() {
        GuiEventListener focusedWidget = getFocused();
        FocusTarget focus = focusedTarget();
        String offer = snapshot == null ? "none"
            : snapshot.hasPendingOffer() ? "pending"
            : snapshot.hasFollowUpOffer() ? "follow_up" : "receipt";
        return "offer=" + offer + ",selected="
            + (selected == null ? "none" : selected.id())
            + ",waiting=" + waiting + ",panel=" + left + ":" + top
            + ":" + panelW + ":" + panelH + ",cards=" + cardW + "x"
            + cardH + ",focus=" + qaFocus(focus) + ",focusCurrentChild="
            + isCurrentChild(focusedWidget) + ",confirmActive="
            + false + ",claimMode=direct,cardsActive="
            + canClaim(snapshot, waiting, claim.presenting()) + ",laterVisible="
            + false + ",closeVisible="
            + (closeButton != null && closeButton.visible) + ",later_ticks="
            + 0 + ",rewardPhase=" + claim.phase()
            + ",rewardTick=" + (rewardAnimation == null ? -1 : rewardAnimation.elapsedTicks());
    }

    // ------------------------------------------------------------ layout ---

    /**
     * Pure geometry (screen pixels): the standard frame with a subtitle; on
     * the page the status strip, three equal cards whose seal crowns rise
     * {@link #CROWN} px above their faces, and the footer hint under a rule.
     */
    static Layout layoutFor(int viewportWidth, int viewportHeight) {
        Ui2FrameLayout frame = Ui2FrameLayout.centred(viewportWidth, viewportHeight,
            PANEL_MAX_W, PANEL_MAX_H, true);
        Rect c = frame.content();
        Rect status = new Rect(c.x(), c.y(), c.width(), STATUS_H);
        Rect footer = new Rect(c.x(), c.bottom() - 9, c.width(), 9);
        int cardTop = status.bottom() + Ui2FrameLayout.S + CROWN;
        int cardBottom = footer.y() - Ui2FrameLayout.S - Ui2FrameLayout.S;
        int cardW = Math.max(1, (c.width() - CARD_GAP * 2) / 3);
        int cardsX = c.x() + (c.width() - (cardW * 3 + CARD_GAP * 2)) / 2;
        return new Layout(frame, status, footer, cardsX, cardTop, cardW, Math.max(1, cardBottom - cardTop));
    }

    record Layout(Ui2FrameLayout frame, Rect status, Rect footer, int cardsX, int cardTop, int cardW,
                  int cardH) {
        /** Card face {@code index} (0..2). */
        Rect card(int index) {
            return new Rect(cardsX + index * (cardW + CARD_GAP), cardTop, cardW, cardH);
        }

        /** Whole-card activation area: the face plus the seal crown above it. */
        Rect hit(int index) {
            Rect face = card(index);
            return new Rect(face.x(), face.y() - CROWN, face.width(), face.height() + CROWN);
        }
    }

    private enum FocusKind {
        CARD,
        CLOSE
    }

    private record FocusTarget(FocusKind kind, BlessingId blessing) {
        static FocusTarget card(BlessingId blessing) {
            return new FocusTarget(FocusKind.CARD, blessing);
        }

        static FocusTarget close() {
            return new FocusTarget(FocusKind.CLOSE, null);
        }
    }

    private interface FocusTagged {
        FocusTarget focusTarget();
    }

    private static String qaFocus(FocusTarget target) {
        if (target == null) {
            return "none";
        }
        return target.kind() == FocusKind.CARD && target.blessing() != null
            ? "card:" + target.blessing().id()
            : target.kind().name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Input, focus and narration for one whole card. It paints nothing: the
     * screen paints the card itself, including its keyboard focus edge.
     */
    private static final class CardHit extends HsButton implements FocusTagged {
        private final FocusTarget focusTarget;
        private Component narrationHint = Component.empty();

        private CardHit(int x, int y, int width, int height,
                        Component label, Runnable action, FocusTarget focusTarget) {
            super(x, y, width, height, label, Kind.NORMAL, action);
            this.focusTarget = focusTarget;
        }

        @Override
        public FocusTarget focusTarget() {
            return focusTarget;
        }

        void setNarrationHint(Component narrationHint) {
            this.narrationHint = narrationHint == null
                ? Component.empty() : narrationHint;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY,
                                    float partialTick) {
            // The screen paints the card itself, including keyboard focus.
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
            if (!narrationHint.getString().isEmpty()) {
                output.add(NarratedElementType.HINT, narrationHint);
            }
        }
    }

    private record CardCopy(Component name, Component effect, Component compact,
                            Component termsLine,
                            List<FormattedCharSequence> metaLines, List<FormattedCharSequence> effectLines, MutableComponent tooltipText,
                            List<FormattedCharSequence> tooltipLines) {
        private static CardCopy unmeasured(BlessingId blessing) {
            String base = "hearthstead.blessing." + blessing.id();
            return new CardCopy(Component.translatable(base + ".name"),
                Component.translatable(base + ".effect"),
                Component.translatable(base + ".compact"),
                Component.empty(), List.of(),
                List.of(), Component.empty(), List.of());
        }

        private CardCopy measured(Component termsLine,
                                  List<FormattedCharSequence> metaLines,
                                  List<FormattedCharSequence> effectLines,
                                  MutableComponent tooltipText,
                                  List<FormattedCharSequence> tooltipLines) {
            return new CardCopy(name, effect, compact,
                termsLine, metaLines, effectLines, tooltipText, tooltipLines);
        }
    }
}
