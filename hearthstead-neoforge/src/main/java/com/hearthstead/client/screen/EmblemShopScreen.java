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
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.item.JobEmblemItem;
import com.hearthstead.network.DevelopmentActionPayload;
import com.hearthstead.network.DevelopmentSnapshotPayload;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.development.Development;
import com.hearthstead.settlement.development.DevelopmentNode;
import com.hearthstead.settlement.development.JobEmblemCatalog;
import com.hearthstead.settlement.equipment.EquipmentRequirement;
import com.hearthstead.settlement.guildmaster.GuildmasterTrade;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Professions & Emblems": the Guildmaster's trade screen.
 *
 * <p>Dark oak frame and parchment page. Profession list on the left; the
 * selected emblem on the right with what it takes to BUY it (research and
 * price) kept apart from what it takes to USE it (workplace and tool), a
 * quantity, the total, the player's Coins, and Buy / Close.
 *
 * <p>Everything shown comes from real game data: prices from
 * {@link JobEmblemCatalog}, availability and refusal reasons from the
 * server snapshot, workplaces from {@link Employment#tradeOf}, tools from
 * the equipment requests. The server re-validates every purchase; a
 * disabled Buy always states a concrete reason. Selection, quantity and
 * scroll survive every snapshot update, so a purchase never moves the list.
 */
public final class EmblemShopScreen extends Screen implements QaUiInspectable {
    private static final int PANEL_W = 464; // size check: Banner footprint (was 470)
    private static final int PANEL_H = 256; // size check: Banner footprint (was 300)
    /** Funds / caption line above the two columns. */
    static final int INFO_H = 12;
    static final int ROW_H = 20;
    static final int ROW_STEP = 21;
    static final int SCROLL_GUTTER = 6;
    static final int FOOTER_H = Ui2FrameLayout.BUTTON_H;
    static final int CLOSE_W = 52;
    static final int QTY_BTN = 14;
    /** Wide enough for a disabled "-"/"+": 6px label + 8px padlock + 6px framed-button inset. */
    static final int QTY_BTN_W = 20;
    static final int QTY_BOX = 22;
    /** Detail text starts under the emblem icon and names. */
    static final int DETAIL_TEXT_TOP = 21;
    /** Periodic refresh while open (GUILD-UI01), and the floor after an inventory change. */
    static final int POLL_TICKS = 20;
    static final int MIN_POLL_TICKS = 5;
    /** A lost Buy reply must never lock the button. */
    private static final long PENDING_TIMEOUT_MS = 3_000L;

    private static final Component SUBTITLE = Component.translatable("hearthstead.emblem_shop.subtitle");
    private static final Component DEFAULT_FOOTER = Component.translatable("hearthstead.emblem_shop.footer");

    private DevelopmentSnapshotPayload snapshot;
    private CachedEmblem[] cached = new CachedEmblem[0];
    private int selectedProfessionId = -1;
    private int quantity = 1;
    private int scroll;
    private boolean pending;
    private long pendingSinceMillis;
    private int pollTicks;
    private int lastInventoryChange = Integer.MIN_VALUE;

    private Component footer = DEFAULT_FOOTER;
    private Ui2Frame.Tone footerTone = Ui2Frame.Tone.NEUTRAL;
    private FormattedCharSequence footerLine1 = FormattedCharSequence.EMPTY;
    private FormattedCharSequence footerLine2 = FormattedCharSequence.EMPTY;
    private List<FormattedCharSequence> footerTooltip = List.of();

    private final Ui2Serif.Text titleText = new Ui2Serif.Text(Ui2Serif.Size.TITLE);
    private ScreenLayout layout = layoutFor(PANEL_W + 16, PANEL_H + 16);
    private Ui2Button buyButton;
    private Ui2Button closeButton;
    private Ui2Button minusButton;
    private Ui2Button plusButton;
    private Component buyReason = Component.empty();
    private boolean buyEnabled;
    private DetailLines detail = DetailLines.EMPTY;
    private boolean uiOpenSoundPlayed;
    private boolean uiCloseSoundPlayed;

    public EmblemShopScreen(DevelopmentSnapshotPayload snapshot) {
        super(Component.translatable("hearthstead.emblem_shop.title"));
        this.snapshot = snapshot;
    }

    public boolean accepts(DevelopmentSnapshotPayload fresh) {
        return fresh != null
            && fresh.view() == DevelopmentActionPayload.View.EMBLEM_SHOP
            && fresh.settlementId().equals(snapshot.settlementId())
            && fresh.hearthPos().equals(snapshot.hearthPos())
            && fresh.mayorId().equals(snapshot.mayorId());
    }

    /**
     * A fresh authoritative snapshot (a purchase reply or a periodic refresh):
     * keep selection, quantity and scroll. Only a reply carrying feedback
     * ends a pending Buy and makes a sound; a quiet refresh never does, so
     * the open screen can poll without noise.
     */
    public void update(DevelopmentSnapshotPayload fresh) {
        if (!accepts(fresh)) {
            return;
        }
        if (fresh.feedback().isPresent()) {
            if (pending) {
                playAuthoritativeFeedback(fresh);
            }
            pending = false;
        }
        snapshot = fresh;
        rebuildAll();
    }

    @Override
    public void removed() {
        if (uiOpenSoundPlayed && !uiCloseSoundPlayed) {
            uiCloseSoundPlayed = true;
            HsUi.playCloseSound();
        }
        super.removed();
    }

    private static void playAuthoritativeFeedback(DevelopmentSnapshotPayload fresh) {
        fresh.feedback().ifPresent(feedback -> {
            boolean accepted = isApplied(feedback);
            QaClientObserver.markUiTransition(accepted ? "emblem_result_ok" : "emblem_result_error");
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
        rebuildAll();
        if (!uiOpenSoundPlayed) {
            uiOpenSoundPlayed = true;
            HsUi.playOpenSound();
        }
    }

    private void rebuildAll() {
        if (font == null) {
            return;
        }
        rebuildCache();
        rebuildFooterLines();
        rebuildControls();
    }

    // ------------------------------------------------------------ data ---

    private void rebuildCache() {
        Map<Integer, DevelopmentSnapshotPayload.EmblemView> views = new HashMap<>();
        if (snapshot != null) {
            for (DevelopmentSnapshotPayload.EmblemView view : snapshot.emblems()) {
                views.put(view.professionWireId(), view);
            }
        }
        ArrayList<CachedEmblem> next = new ArrayList<>(JobEmblemCatalog.RELEASE_CATALOG.size());
        for (JobEmblemCatalog.Entry entry : JobEmblemCatalog.RELEASE_CATALOG) {
            DevelopmentSnapshotPayload.EmblemView view = views.get((int) entry.profession().id());
            // Extended trades the server leaves out ([features] off) are hidden.
            if (view == null && snapshot != null && entry.unlock().extendedTrade()) {
                continue;
            }
            boolean available = view != null && view.available();
            String reason = view == null ? "hearthstead.development.blocked.quarantined" : view.reasonKey();
            next.add(new CachedEmblem(entry, available, reason));
        }
        cached = next.toArray(new CachedEmblem[0]);
        int index = selectedIndex();
        if (index < 0 && cached.length > 0) {
            selectedProfessionId = cached[0].entry.profession().id();
            quantity = 1;
        }
        scroll = clampScroll(scroll);
        // A quiet refresh keeps the last purchase message on the footer.
        if (snapshot != null && snapshot.feedback().isPresent()) {
            Component fb = snapshot.feedback().get();
            footer = isApplied(fb) ? Component.translatable("hearthstead.emblem_shop.purchase.confirmed") : fb;
            footerTone = isApplied(fb) ? Ui2Frame.Tone.GOOD : Ui2Frame.Tone.BAD;
        }
    }

    private int selectedIndex() {
        for (int i = 0; i < cached.length; i++) {
            if (cached[i].entry.profession().id() == selectedProfessionId) {
                return i;
            }
        }
        return -1;
    }

    private CachedEmblem selected() {
        int i = selectedIndex();
        return i < 0 ? null : cached[i];
    }

    private int clampScroll(int value) {
        return Math.max(0, Math.min(value, Math.max(0, cached.length - layout.visibleRows())));
    }

    private void rebuildFooterLines() {
        int width = Math.max(1, layout.feedback().width() - 18);
        List<FormattedCharSequence> lines = font.split(footer, width);
        footerLine1 = lines.isEmpty() ? FormattedCharSequence.EMPTY : lines.get(0);
        footerLine2 = lines.size() < 2 ? FormattedCharSequence.EMPTY : lines.get(1);
        if (lines.size() > 2) {
            // Only two lines fit the strip: end the second with "..." instead of
            // silently dropping the rest (the hover tooltip keeps the full text).
            StringBuilder first = new StringBuilder();
            lines.get(0).accept((index, style, codePoint) -> {
                first.appendCodePoint(codePoint);
                return true;
            });
            String full = footer.getString();
            if (full.startsWith(first.toString())) {
                String rest = full.substring(first.length()).stripLeading();
                footerLine2 = HsUi.fitLabel(font, Component.literal(rest), width).text().getVisualOrderText();
            }
        }
        footerTooltip = List.copyOf(font.split(footer, 240));
    }

    // -------------------------------------------------------- controls ---

    private void rebuildControls() {
        clearWidgets();
        Rect buy = layout.buy();
        Rect close = layout.close();
        buyButton = Ui2Button.primary(buy.x(), buy.y(), buy.width(), buy.height(),
            Component.translatable("hearthstead.guildmaster.buy"), this::buySelected);
        closeButton = Ui2Button.secondary(close.x(), close.y(), close.width(), close.height(),
            Component.translatable("hearthstead.guildmaster.close"), this::onClose);
        Rect minus = layout.minus();
        Rect plus = layout.plus();
        minusButton = Ui2Button.secondary(minus.x(), minus.y(), minus.width(), minus.height(),
            Component.literal("-"), () -> changeQuantity(-1));
        plusButton = Ui2Button.secondary(plus.x(), plus.y(), plus.width(), plus.height(),
            Component.literal("+"), () -> changeQuantity(1));
        Ui2Tips.tip(closeButton, Component.literal("Close (Esc)"));
        addRenderableWidget(minusButton);
        addRenderableWidget(plusButton);
        addRenderableWidget(buyButton);
        addRenderableWidget(closeButton);
        Ui2WoodKey key = Ui2Frame.closeKey(layout.frame(), this::onClose);
        addRenderableWidget(key);
        refreshSelectionState();
    }

    /** Recomputes the detail lines, quantity limits and the Buy state. */
    private void refreshSelectionState() {
        CachedEmblem emblem = selected();
        quantity = GuildmasterTrade.clampQuantity(quantity);
        detail = emblem == null ? DetailLines.EMPTY : DetailLines.build(font, emblem, quantity,
            layout.detail().width(), layout.detailTextLines());
        if (minusButton != null) {
            minusButton.active = emblem != null && quantity > 1;
            plusButton.active = emblem != null && quantity < GuildmasterTrade.MAX_QUANTITY;
        }
        buyReason = buyBlockReason(emblem);
        buyEnabled = buyReason == null;
        if (buyButton != null) {
            Component label = quantity > 1
                ? Component.translatable("hearthstead.guildmaster.buy_count", quantity)
                : Component.translatable("hearthstead.guildmaster.buy");
            buyButton.setMessage(label);
            Ui2Tips.enable(buyButton, buyEnabled,
                Component.translatable("hearthstead.guildmaster.buy.tip"),
                buyReason == null ? Component.empty() : buyReason);
        }
        if (buyReason == null) {
            buyReason = Component.translatable("hearthstead.guildmaster.delivery_note");
        }
    }

    /** The concrete reason Buy is disabled, or null when it can be pressed. */
    private Component buyBlockReason(CachedEmblem emblem) {
        if (emblem == null) {
            return Component.translatable("hearthstead.guildmaster.reason.select");
        }
        if (pending) {
            return Component.translatable("hearthstead.guildmaster.reason.waiting");
        }
        if (minecraft != null && minecraft.player != null && minecraft.player.isSpectator()) {
            return Component.translatable("hearthstead.guildmaster.reason.spectator");
        }
        if (!emblem.available) {
            return emblem.reason();
        }
        int needCoins = GuildmasterTrade.totalCoins(emblem.entry, quantity);
        if (snapshot != null && snapshot.availableCoins() >= 0 && snapshot.availableCoins() < needCoins) {
            return Component.translatable("hearthstead.guildmaster.reason.coins", needCoins,
                snapshot.availableCoins());
        }
        if (minecraft != null && minecraft.player != null) {
            int free = GuildmasterTrade.emptySlots(minecraft.player.getInventory());
            if (free < quantity) {
                return Component.translatable("hearthstead.guildmaster.reason.inventory", quantity, free);
            }
        }
        return null;
    }

    private void changeQuantity(int delta) {
        int next = GuildmasterTrade.clampQuantity(quantity + delta);
        if (next != quantity) {
            quantity = next;
            QaClientObserver.markUiTransition("emblem_shop_quantity");
            refreshSelectionState();
        }
    }

    private void select(int index) {
        if (index < 0 || index >= cached.length) {
            return;
        }
        int id = cached[index].entry.profession().id();
        if (id != selectedProfessionId) {
            selectedProfessionId = id;
            quantity = 1;
            QaClientObserver.markUiTransition("emblem_shop_select");
        }
        reveal(index);
        refreshSelectionState();
    }

    private void reveal(int index) {
        int rows = layout.visibleRows();
        if (index < scroll) {
            scroll = index;
        } else if (index >= scroll + rows) {
            scroll = index - rows + 1;
        }
        scroll = clampScroll(scroll);
    }

    private void buySelected() {
        CachedEmblem emblem = selected();
        if (emblem == null || !buyEnabled || pending || snapshot == null) {
            return;
        }
        pending = true;
        pendingSinceMillis = net.minecraft.Util.getMillis();
        QaClientObserver.markUiTransition("emblem_shop_buy");
        PacketDistributor.sendToServer(new DevelopmentActionPayload(snapshot.hearthPos(),
            snapshot.settlementId(), snapshot.mayorId(),
            DevelopmentActionPayload.View.EMBLEM_SHOP,
            DevelopmentActionPayload.Kind.BUY_EMBLEM,
            GuildmasterTrade.encode(emblem.entry.profession().id(), quantity),
            snapshot.revision()));
        refreshSelectionState();
    }

    @Override
    public void tick() {
        super.tick();
        if (pending && net.minecraft.Util.getMillis() - pendingSinceMillis >= PENDING_TIMEOUT_MS) {
            pending = false;
            footer = Component.translatable("hearthstead.guildmaster.reason.no_reply");
            footerTone = Ui2Frame.Tone.BAD;
            rebuildFooterLines();
            refreshSelectionState();
        }
        if (minecraft == null || minecraft.player == null || snapshot == null) {
            return;
        }
        // GUILD-UI01: funds and availability live on the server (the buyer's
        // inventory, the Banner, linked Warehouses, other players' research).
        // Ask for a fresh snapshot about once a second, and sooner (bounded)
        // right after this player's inventory changed. Never while a Buy is
        // in flight; the server stays authoritative for every purchase.
        int changed = minecraft.player.getInventory().getTimesChanged();
        boolean inventoryChanged = changed != lastInventoryChange;
        lastInventoryChange = changed;
        if (inventoryChanged) {
            refreshSelectionState(); // free slots are known client-side at once
        }
        pollTicks++;
        if (!pending && (pollTicks >= POLL_TICKS || inventoryChanged && pollTicks >= MIN_POLL_TICKS)) {
            pollTicks = 0;
            PacketDistributor.sendToServer(new DevelopmentActionPayload(snapshot.hearthPos(),
                snapshot.settlementId(), snapshot.mayorId(),
                DevelopmentActionPayload.View.EMBLEM_SHOP,
                DevelopmentActionPayload.Kind.REFRESH, -1, snapshot.revision()));
        }
    }

    // ---------------------------------------------------------- render ---

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

        List<FormattedCharSequence> tooltip = null;
        // Funds, top right; list caption top left.
        Rect info = layout.info();
        Ui2Surface.sectionHeader(graphics, font, Component.translatable("hearthstead.guildmaster.professions"),
            info.x(), info.y() + 2, layout.list().width() - 4);
        if (snapshot != null && snapshot.availableCoins() >= 0) {
            Component funds = Component.translatable("hearthstead.guildmaster.funds", snapshot.availableCoins());
            int fw = font.width(funds);
            int fx = info.right() - fw;
            Ui2Surface.icon(graphics, new ItemStack(ModItems.GOLD_COIN.get()), fx - 12, info.y(), 10);
            graphics.drawString(font, funds, fx, info.y() + 2, Ui2Palette.INK, false);
            if (mouseX >= fx - 12 && mouseX < info.right() && mouseY >= info.y() && mouseY < info.bottom()) {
                tooltip = font.split(Component.translatable("hearthstead.guildmaster.funds.tip"), 220);
            }
        }

        // Left: profession list.
        Rect list = layout.list();
        Ui2Surface.sheet(graphics, list.x() - 2, list.y() - 2, list.width() + 4, list.height() + 4);
        int rows = layout.visibleRows();
        int sel = selectedIndex();
        for (int r = 0; r < rows && r + scroll < cached.length; r++) {
            int index = r + scroll;
            CachedEmblem emblem = cached[index];
            Rect row = layout.row(r);
            boolean hovered = row.contains(mouseX, mouseY);
            Ui2Surface.row(graphics, row.x(), row.y(), row.width(), row.height(), hovered ? 1.0F : 0.0F,
                index == sel);
            Ui2Surface.icon(graphics, emblem.icon, row.x() + 4, row.y() + 2, 16);
            int textX = row.x() + 24;
            Component price = emblem.shortPrice;
            int priceW = font.width(price);
            int nameW = Math.max(10, row.right() - 6 - priceW - 4 - textX);
            graphics.drawString(font, HsUi.fitLabel(font, emblem.name, nameW).text(), textX, row.y() + 2,
                Ui2Palette.INK, false);
            graphics.drawString(font, price, row.right() - 4 - priceW, row.y() + 2, Ui2Palette.GOLD, false);
            graphics.drawString(font, HsUi.fitLabel(font, emblem.status(), row.right() - 6 - textX).text(),
                textX, row.y() + 11, emblem.available ? Ui2Palette.FOREST : Ui2Palette.INK_MUTED, false);
            if (hovered) {
                tooltip = emblem.rowTooltip(font);
            }
        }
        if (cached.length == 0) {
            graphics.drawString(font, Component.translatable("hearthstead.emblem_shop.empty"),
                list.x() + 4, list.y() + 4, Ui2Palette.INK_MUTED, false);
        }
        if (cached.length > rows) {
            Ui2Surface.scrollbar(graphics, list.right() - 3, list.y(), list.height(),
                Math.min(1.0F, (float) rows / Math.max(1, cached.length)),
                (float) scroll / Math.max(1, cached.length - rows));
        }

        // Right: selected emblem.
        Rect d = layout.detail();
        CachedEmblem emblem = selected();
        if (emblem != null) {
            Ui2Surface.slotWell(graphics, d.x(), d.y());
            graphics.renderItem(emblem.icon, d.x() + 1, d.y() + 1);
            graphics.drawString(font, HsUi.fitLabel(font, emblem.emblemName, d.width() - 24).text(),
                d.x() + 23, d.y() + 1, Ui2Palette.INK, false);
            graphics.drawString(font, HsUi.fitLabel(font, emblem.name, d.width() - 24).text(),
                d.x() + 23, d.y() + 10, Ui2Palette.INK_MUTED, false);
            int y = d.y() + DETAIL_TEXT_TOP;
            int limit = layout.quantityRow().y() - 3;
            for (DetailLine line : detail.lines()) {
                if (y + 10 > limit) {
                    break;
                }
                if (line.header()) {
                    Ui2Surface.sectionHeader(graphics, font, line.text(), d.x(), y + 1, d.width());
                } else {
                    graphics.drawString(font, line.seq(), d.x() + (line.indent() ? 4 : 0), y + 1,
                        line.color(), false);
                }
                y += 10;
            }
            if (d.contains(mouseX, mouseY) && mouseY < limit) {
                tooltip = detail.tooltip();
            }
            // Quantity and total.
            Rect q = layout.quantityRow();
            Rect box = layout.quantityBox();
            graphics.fill(box.x(), box.y(), box.right(), box.bottom(), Ui2Palette.INSET);
            graphics.fill(box.x(), box.y(), box.right(), box.y() + 1, Ui2Palette.RULE_STRONG);
            String qty = Integer.toString(quantity);
            graphics.drawString(font, qty, box.x() + (box.width() - font.width(qty)) / 2 + 1,
                box.y() + 3, Ui2Palette.INK, false);
            int totalX = layout.plus().right() + 6;
            graphics.drawString(font, HsUi.fitLabel(font, detail.total(), q.right() - totalX).text(),
                totalX, q.y() + 3, Ui2Palette.INK, false);
            if (mouseX >= totalX && mouseX < q.right() && mouseY >= q.y() && mouseY < q.bottom()) {
                tooltip = font.split(detail.total(), 240);
            }
            // Reason (or delivery note) right above Buy.
            Rect reason = layout.reason();
            graphics.drawString(font, HsUi.fitLabel(font, buyReason, reason.width()).text(),
                reason.x(), reason.y() + 1, buyEnabled ? Ui2Palette.INK_MUTED : Ui2Palette.DANGER, false);
            if (reason.contains(mouseX, mouseY)) {
                tooltip = font.split(buyReason, 240);
            }
        }

        // Footer: authoritative server feedback.
        Rect feedback = layout.feedback();
        Ui2Frame.status(graphics, font, feedback, Component.empty(), footerTone);
        graphics.drawString(font, footerLine1, feedback.x() + 14, feedback.y() + 2, Ui2Palette.INK_SOFT, false);
        graphics.drawString(font, footerLine2, feedback.x() + 14, feedback.y() + 11, Ui2Palette.INK_SOFT, false);
        if (feedback.contains(mouseX, mouseY)) {
            tooltip = footerTooltip;
        }
        HsUi.widgets(this, graphics, mouseX, mouseY, partialTick);
        if (tooltip != null && !anyWidgetHovered()) {
            setTooltipForNextRenderPass(tooltip);
        }
    }

    private boolean anyWidgetHovered() {
        return (buyButton != null && buyButton.isHovered()) || (closeButton != null && closeButton.isHovered())
            || (minusButton != null && minusButton.isHovered()) || (plusButton != null && plusButton.isHovered());
    }

    // ----------------------------------------------------------- input ---

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int rows = layout.visibleRows();
            for (int r = 0; r < rows && r + scroll < cached.length; r++) {
                if (layout.row(r).contains(mouseX, mouseY)) {
                    select(r + scroll);
                    HsUi.playOpenSound();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (layout.list().contains(mouseX, mouseY) && cached.length > layout.visibleRows()) {
            int before = scroll;
            scroll = clampScroll(scroll - (int) Math.signum(dy));
            if (scroll != before) {
                QaClientObserver.markUiTransition("emblem_shop_scroll");
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, dx, dy);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        int sel = Math.max(0, selectedIndex());
        int target = switch (keyCode) {
            case 265 -> sel - 1; // up
            case 264 -> sel + 1; // down
            case 266 -> sel - layout.visibleRows(); // page up
            case 267 -> sel + layout.visibleRows(); // page down
            case 268 -> 0; // home
            case 269 -> cached.length - 1; // end
            default -> Integer.MIN_VALUE;
        };
        if (target != Integer.MIN_VALUE && cached.length > 0) {
            select(Math.max(0, Math.min(cached.length - 1, target)));
            return true;
        }
        if (keyCode == 45 || keyCode == 333) { // '-' and keypad minus
            changeQuantity(-1);
            return true;
        }
        if (keyCode == 61 || keyCode == 334) { // '=' / '+' and keypad plus
            changeQuantity(1);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public String qaUiState() {
        return "scroll=" + scroll + "/" + Math.max(0, cached.length - layout.visibleRows())
            + ",rows=" + layout.visibleRows() + ",catalog=" + cached.length
            + ",selected=" + selectedProfessionId + ",quantity=" + quantity
            + ",buyEnabled=" + buyEnabled + ",pending=" + pending
            + ",panel=" + layout.left() + ":" + layout.top() + ":" + layout.panelWidth() + ":"
            + layout.panelHeight() + ",guildmasterEntity="
            + (snapshot == null ? -1 : snapshot.mayorEntityId())
            + ",availableCoins=" + (snapshot == null ? -1 : snapshot.availableCoins());
    }

    // ---------------------------------------------------------- layout ---

    /** Deterministic geometry used by layout tests at GUI scale 2, 3 and 4. */
    static ScreenLayout layoutFor(int viewportWidth, int viewportHeight) {
        return new ScreenLayout(Ui2FrameLayout.centred(viewportWidth, viewportHeight, PANEL_W, PANEL_H, true));
    }

    /**
     * Two columns inside the standard frame: funds line on top, the list on
     * the left, the emblem detail on the right; the bottom row carries the
     * feedback strip under the list and Buy / Close under the detail.
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

        int listWidth() {
            int w = frame.content().width();
            return Math.max(130, Math.min(210, (w * 45) / 100));
        }

        Rect bottomRow() {
            Rect c = frame.content();
            return new Rect(c.x(), c.bottom() - FOOTER_H, c.width(), FOOTER_H);
        }

        Rect list() {
            Rect c = frame.content();
            int top = info().bottom() + 6;
            return new Rect(c.x() + 2, top, listWidth() - 4,
                Math.max(ROW_H, bottomRow().y() - Ui2FrameLayout.M - 2 - top));
        }

        int visibleRows() {
            return Math.max(1, (list().height() + ROW_STEP - ROW_H) / ROW_STEP);
        }

        Rect row(int i) {
            Rect l = list();
            return new Rect(l.x(), l.y() + i * ROW_STEP, l.width() - SCROLL_GUTTER, ROW_H);
        }

        Rect detail() {
            Rect c = frame.content();
            int x = c.x() + listWidth() + Ui2FrameLayout.GUTTER;
            int top = info().bottom() + 4;
            return new Rect(x, top, Math.max(1, c.right() - x), Math.max(1, bottomRow().y() - Ui2FrameLayout.S - top));
        }

        Rect reason() {
            Rect d = detail();
            return new Rect(d.x(), d.bottom() - 10, d.width(), 10);
        }

        Rect quantityRow() {
            Rect d = detail();
            return new Rect(d.x(), reason().y() - QTY_BTN - 3, d.width(), QTY_BTN);
        }

        Rect minus() {
            Rect q = quantityRow();
            return new Rect(q.x(), q.y(), QTY_BTN_W, QTY_BTN);
        }

        Rect quantityBox() {
            Rect q = quantityRow();
            return new Rect(minus().right() + 2, q.y(), QTY_BOX, QTY_BTN);
        }

        Rect plus() {
            Rect q = quantityRow();
            return new Rect(quantityBox().right() + 2, q.y(), QTY_BTN_W, QTY_BTN);
        }

        Rect close() {
            Rect b = bottomRow();
            return new Rect(b.right() - CLOSE_W, b.y(), CLOSE_W, FOOTER_H);
        }

        Rect buy() {
            Rect b = bottomRow();
            Rect d = detail();
            return new Rect(d.x(), b.y(), Math.max(40, close().x() - Ui2FrameLayout.S - d.x()), FOOTER_H);
        }

        Rect feedback() {
            Rect b = bottomRow();
            return new Rect(b.x(), b.y(), Math.max(1, detail().x() - Ui2FrameLayout.GUTTER - b.x()), FOOTER_H);
        }

        /** Lines of detail text that fit above the quantity row. */
        int detailTextLines() {
            return Math.max(0, (quantityRow().y() - 3 - (detail().y() + DETAIL_TEXT_TOP)) / 10);
        }
    }

    // ----------------------------------------------------------- model ---

    /** One catalogue row, derived once per snapshot. */
    private static final class CachedEmblem {
        final JobEmblemCatalog.Entry entry;
        final boolean available;
        final String reasonKey;
        final Component name;
        final Component emblemName;
        final Component shortPrice;
        final ItemStack icon;

        CachedEmblem(JobEmblemCatalog.Entry entry, boolean available, String reasonKey) {
            this.entry = entry;
            this.available = available;
            this.reasonKey = reasonKey == null ? "" : reasonKey;
            this.name = entry.profession().displayName();
            this.emblemName = entry.displayName();
            int coins = entry.coinPrice();
            this.shortPrice = Component.translatable(coins == 1
                ? "hearthstead.guildmaster.coin_one" : "hearthstead.guildmaster.coin_many", coins);
            this.icon = JobEmblemItem.stackFor(entry.profession());
        }

        boolean locked() {
            return "hearthstead.development.blocked.emblem_locked".equals(reasonKey);
        }

        boolean needsGoods() {
            return "hearthstead.development.blocked.materials".equals(reasonKey)
                || "hearthstead.emblem_shop.blocked.materials".equals(reasonKey)
                || "hearthstead.emblem_shop.blocked.missing".equals(reasonKey);
        }

        Component status() {
            if (available) return Component.translatable("hearthstead.guildmaster.status.ready");
            if (locked()) return Component.translatable("hearthstead.guildmaster.status.research");
            if (needsGoods()) return Component.translatable("hearthstead.guildmaster.status.goods");
            return Component.translatable("hearthstead.guildmaster.status.unavailable");
        }

        /** The concrete reason this emblem cannot be bought right now. */
        Component reason() {
            if (locked()) {
                return Component.translatable("hearthstead.emblem_shop.locked.route", unlockName(entry));
            }
            return reasonKey.isEmpty() ? Component.translatable("hearthstead.guildmaster.status.unavailable")
                : Component.translatable(reasonKey);
        }

        List<FormattedCharSequence> rowTooltip(Font font) {
            MutableComponent t = emblemName.copy().append("\n").append(priceLine(entry, 1));
            if (!available) {
                t.append("\n").append(reason());
            }
            return font.split(t, 220);
        }
    }

    private record DetailLine(Component text, FormattedCharSequence seq, int color, boolean header,
                              boolean indent) {
    }

    /** The detail column's text, wrapped once per selection/quantity change. */
    private record DetailLines(List<DetailLine> lines, Component total, List<FormattedCharSequence> tooltip) {
        static final DetailLines EMPTY = new DetailLines(List.of(), Component.empty(), List.of());

        static DetailLines build(Font font, CachedEmblem emblem, int quantity, int width, int maxLines) {
            JobEmblemCatalog.Entry entry = emblem.entry;
            ArrayList<DetailLine> out = new ArrayList<>();
            MutableComponent tip = Component.empty();

            Component desc = description(entry.profession());
            Component give = Component.translatable("hearthstead.guildmaster.give_to_settler");
            Component buyHeader = Component.translatable("hearthstead.guildmaster.to_buy");
            Component research = Component.translatable(emblem.locked()
                ? "hearthstead.guildmaster.research_missing" : "hearthstead.guildmaster.research_done",
                unlockName(entry));
            Component price = Component.translatable("hearthstead.guildmaster.price", priceLine(entry, 1));
            Component useHeader = Component.translatable("hearthstead.guildmaster.to_use");
            Component workplace = Component.translatable("hearthstead.guildmaster.workplace",
                workplaces(entry.profession()));
            Component tool = toolLine(entry.profession());
            Component attributes = attributeLine(entry.profession());

            // Requirements always stay visible (GUI scale 4 included); the
            // description takes only the lines left above them.
            ArrayList<DetailLine> req = new ArrayList<>();
            header(req, buyHeader);
            text(req, font, research, width, emblem.locked() ? Ui2Palette.DANGER : Ui2Palette.INK_SOFT);
            text(req, font, price, width, Ui2Palette.INK_SOFT);
            header(req, useHeader);
            text(req, font, workplace, width, Ui2Palette.INK_SOFT);
            text(req, font, tool, width, Ui2Palette.INK_SOFT);
            if (attributes != null) {
                text(req, font, attributes, width, Ui2Palette.INK_SOFT);
            }
            ArrayList<DetailLine> intro = new ArrayList<>();
            for (FormattedCharSequence seq : font.split(desc, width)) {
                intro.add(new DetailLine(desc, seq, Ui2Palette.INK, false, false));
                if (intro.size() == 2) break;
            }
            intro.add(new DetailLine(give, fit(font, give, width), Ui2Palette.INK_MUTED, false, false));
            int room = Math.max(0, maxLines - req.size());
            out.addAll(intro.subList(0, Math.min(room, intro.size())));
            out.addAll(req);

            tip.append(emblem.emblemName).append("\n").append(desc).append("\n").append(give)
                .append("\n\n").append(buyHeader).append("\n").append(research).append("\n").append(price)
                .append("\n\n").append(useHeader).append("\n").append(workplace).append("\n").append(tool);
            if (attributes != null) {
                tip.append("\n").append(attributes);
            }
            Component total = Component.translatable("hearthstead.guildmaster.total", priceLine(entry, quantity));
            return new DetailLines(List.copyOf(out), total, font.split(tip, 240));
        }

        private static void header(List<DetailLine> out, Component text) {
            out.add(new DetailLine(text, text.getVisualOrderText(), Ui2Palette.INK_MUTED, true, false));
        }

        private static void text(List<DetailLine> out, Font font, Component text, int width, int color) {
            out.add(new DetailLine(text, fit(font, text, width - 4), color, false, true));
        }

        private static FormattedCharSequence fit(Font font, Component text, int width) {
            return HsUi.fitLabel(font, text, Math.max(1, width)).text().getVisualOrderText();
        }
    }

    /**
     * Playtest 27 Sep #3: the job's PRIMARY / SECONDARY (core) attributes as
     * green / gold chips plus its support attribute, from JobAttributeProfile.
     */
    @org.jetbrains.annotations.Nullable
    static Component attributeLine(Profession profession) {
        var profile = com.hearthstead.entity.JobAttributeProfile.find(profession).orElse(null);
        if (profile == null) {
            return null;
        }
        MutableComponent chips = Component.empty();
        int core = 0;
        Component support = null;
        for (var slot : profile.slots()) {
            if (slot.importance() == com.hearthstead.entity.JobAttributeProfile.Importance.CORE) {
                if (core > 0) {
                    chips.append(Component.literal("  "));
                }
                chips.append(Component.literal("\u25CF ").append(slot.attribute().displayName())
                    .withStyle(core == 0 ? net.minecraft.ChatFormatting.DARK_GREEN
                        : net.minecraft.ChatFormatting.GOLD));
                core++;
            } else {
                support = slot.attribute().displayName();
            }
        }
        if (support != null) {
            chips.append(Component.literal("  \u25CB ").append(support)
                .withStyle(net.minecraft.ChatFormatting.GRAY));
        }
        return Component.translatable("hearthstead.guildmaster.attributes", chips);
    }

    /** Short role description; falls back to a generic line if a trade has none yet. */
    static Component description(Profession profession) {
        String key = "hearthstead.guildmaster.role." + profession.key();
        return I18n.exists(key) ? Component.translatable(key)
            : Component.translatable("hearthstead.guildmaster.role.generic", profession.displayName());
    }

    /** The workplaces that employ this profession, from the real employment table. */
    static Component workplaces(Profession profession) {
        MutableComponent out = null;
        for (BuildingType type : BuildingType.values()) {
            if (Employment.tradeOf(type) == profession) {
                out = out == null ? type.displayName().copy() : out.append(" / ").append(type.displayName());
            }
        }
        return out == null ? Component.translatable("hearthstead.guildmaster.workplace.none") : out;
    }

    static Component toolLine(Profession profession) {
        EquipmentRequirement requirement =
            com.hearthstead.settlement.equipment.EquipmentRequests.requirementFor(profession);
        return requirement == null
            ? Component.translatable("hearthstead.guildmaster.tool.none")
            : Component.translatable("hearthstead.guildmaster.tool",
                new ItemStack(requirement.preferredItem()).getHoverName());
    }

    /** "3 Coins + 4 Flint" for {@code quantity} emblems, from the catalogue. */
    static Component priceLine(JobEmblemCatalog.Entry entry, int quantity) {
        MutableComponent line = Component.empty();
        List<DevelopmentNode.Cost> costs = GuildmasterTrade.total(entry, quantity);
        for (int i = 0; i < costs.size(); i++) {
            DevelopmentNode.Cost cost = costs.get(i);
            if (i > 0) {
                line.append(Component.literal(" + "));
            }
            line.append(Component.translatable("hearthstead.development.cost.line",
                cost.count(), cost.displayName()));
        }
        return line;
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
}
