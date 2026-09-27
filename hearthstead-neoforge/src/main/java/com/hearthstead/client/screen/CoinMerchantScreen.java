package com.hearthstead.client.screen;

import com.hearthstead.client.QaUiInspectable;
import com.hearthstead.client.ui.HsMotion;
import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import com.hearthstead.client.ui2.Ui2Frame;
import com.hearthstead.client.ui2.Ui2FrameLayout;
import com.hearthstead.client.ui2.Ui2Palette;
import com.hearthstead.client.ui2.Ui2Serif;
import com.hearthstead.client.ui2.Ui2Surface;
import com.hearthstead.event.GoldCoinTrades;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.work.GoodsQuality;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.ArrayList;
import java.util.List;

/**
 * Client presentation for a merchant whose real offer list contains Hearthstead Coins.
 *
 * <p>The visible list deliberately contains original {@link MerchantOffers} indexes.
 * It may omit a completed basic Coin row, but it never removes, sorts, or otherwise
 * renumbers offers in {@link MerchantMenu}. Selecting a visible card uses the same
 * original index for the menu hint, vanilla's inventory fill, and the select-trade
 * packet.</p>
 *
 * <p>Drawn in the standard Bannerhold window. MerchantMenu's slot offsets are
 * fixed by the server menu, so the frame is built around them: the walnut
 * board reaches {@link #SIDE_OUT} past the image on each side and
 * {@link #TOP_OUT} above it, which puts every slot on the parchment page.</p>
 */
public final class CoinMerchantScreen extends AbstractContainerScreen<MerchantMenu> implements QaUiInspectable {
    static final int IMAGE_W = 300;
    static final int IMAGE_H = 166;
    /** Board beyond the image on the left and right. */
    static final int SIDE_OUT = 13;
    /** Board, header, rule and page inset above topPos (content starts at topPos). */
    static final int TOP_OUT = 44;
    static final int FRAME_W = IMAGE_W + 2 * SIDE_OUT;
    static final int FRAME_H = 224;
    static final int LIST_X = 7;
    static final int LIST_Y = 16;
    static final int LIST_W = 88;
    static final int LIST_H = 24;
    /** Row text runs between the cost icon (x+4..x+20, count ends x+21) and the result icon (x+68). */
    static final int ROW_TEXT_X = 23;
    static final int ROW_TEXT_W = LIST_W - 20 - ROW_TEXT_X - 1;
    static final int VISIBLE_ROWS = 6;
    static final int SCROLL_X = 99;
    static final int DETAIL_X = 108;
    static final int DETAIL_W = 185;
    static final int PURSE_Y = 57;
    static final int INVENTORY_LABEL_Y = 73;

    /** Original MerchantOffers index; never an index into the visual subset. */
    private int selectedOffer = -1;
    private int scrollOffset;
    private Layout layout;
    private final Ui2Serif.Text titleText = new Ui2Serif.Text(Ui2Serif.Size.TITLE);
    private final Ui2Serif.Text headingText = new Ui2Serif.Text(Ui2Serif.Size.HEADING);

    public CoinMerchantScreen(MerchantMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = IMAGE_W;
        this.imageHeight = IMAGE_H;
        this.inventoryLabelX = DETAIL_X;
        this.inventoryLabelY = INVENTORY_LABEL_Y;
    }

    @Override
    protected void init() {
        super.init();
        // Centre the whole board (not just the slot image); slot offsets from
        // leftPos/topPos are unchanged.
        layout = layoutFor(this.width, this.height);
        this.leftPos = layout.leftPos();
        this.topPos = layout.topPos();
        addRenderableWidget(Ui2Frame.closeKey(layout.frame(), this::onClose));
    }

    /** Read-only state sampled by the dormant QA observer at an explicit frame barrier. */
    @Override
    public String qaUiState() {
        return "offers=" + this.menu.getOffers().size()
            + ",visibleOffers=" + visibleOfferIndices().size()
            + ",selectedOffer=" + selectedOffer + ",scroll=" + scrollOffset
            + ",visitorPurse=" + com.hearthstead.client.CoinMerchantScreenAdapter.purse(this.menu)
            + ",inputA=" + this.menu.getSlot(0).getItem().getCount()
            + ",inputB=" + this.menu.getSlot(1).getItem().getCount()
            + ",output=" + this.menu.getSlot(2).getItem().getCount()
            + ",carriedEmpty=" + this.menu.getCarried().isEmpty();
    }

    /** The adapter calls this only after the server's real offers have arrived. */
    public static boolean supports(MerchantMenu menu) {
        for (MerchantOffer offer : menu.getOffers()) {
            if (offer.getResult().is(ModItems.GOLD_COIN.get())
                || offer.getCostA().is(ModItems.GOLD_COIN.get())
                || offer.getCostB().is(ModItems.GOLD_COIN.get())) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        Ui2FrameLayout frame = layout.frame();
        Ui2Frame.draw(graphics, frame);
        Ui2Frame.title(graphics, this.font, frame, titleText, this.title.getString(), null);
        // Sunken wells at MerchantMenu's exact slot positions.
        for (Slot slot : this.menu.slots) {
            Ui2Surface.slotWell(graphics, this.leftPos + slot.x - 1, this.topPos + slot.y - 1);
        }
        graphics.drawString(this.font, "→", this.leftPos + 195, this.topPos + 42, Ui2Palette.INK_MUTED, false);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // AbstractContainerScreen has already translated its pose by leftPos/topPos.
        // Every coordinate in this method is therefore local to the menu frame.
        Rect heading = layout.heading();
        Ui2Frame.heading(graphics, this.font, headingText, "Trade offers", heading.x(), heading.y(),
            heading.width());
        Rect inv = layout.inventoryLabel();
        paperLabelIn(graphics, this.font, this.playerInventoryTitle, inv.x(), inv.y(), inv.width(),
            Ui2Palette.INK_MUTED);

        MerchantOffer selected = selectedVisibleOffer();
        int purse = com.hearthstead.client.CoinMerchantScreenAdapter.purse(this.menu);
        if (purse >= 0) {
            Rect p = layout.purse();
            Component line = Component.literal(purse == 0
                ? "Purse empty. Await next visitor."
                : "Purse (shared): " + com.hearthstead.util.CoinText.coins(purse));
            Ui2Frame.status(graphics, this.font, p, fitted(line, p.width() - 18),
                purse == 0 ? Ui2Frame.Tone.BAD : Ui2Frame.Tone.NEUTRAL);
        }
        Rect d = layout.detail();
        if (selected == null) {
            paperLabelIn(graphics, this.font, Component.literal(selectedOffer >= 0
                    ? "Deal done. Choose another offer." : "Choose an offer."),
                d.x(), d.y() + 11, d.width(), Ui2Palette.INK_SOFT);
            return;
        }

        paperLabelIn(graphics, this.font, Component.literal("You give: " + costsText(selected)),
            d.x(), d.y(), d.width(), Ui2Palette.INK);
        paperLabelIn(graphics, this.font, Component.literal("You receive: " + stackText(selected.getResult())),
            d.x(), d.y() + 11, d.width(), Ui2Palette.INK);
        int remaining = Math.max(0, selected.getMaxUses() - selected.getUses());
        paperLabelIn(graphics, this.font,
            Component.literal(qualityText(selected) + "  •  Row: " + remaining + " trades"),
            d.x(), d.y() + 22, d.width(),
            selected.isOutOfStock() ? Ui2Palette.DANGER : Ui2Palette.INK_SOFT);
    }
    // Motion only: first-open intro (container: fade only so slots stay aligned with hit-testing); created once, survives re-init.
    private HsMotion.ScreenIntro hsIntro;
    private boolean hsIntroRendering;

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (hsIntro == null) hsIntro = new HsMotion.ScreenIntro();
        if (!hsIntroRendering && !hsIntro.done()) {
            hsIntroRendering = true;
            try {
                hsIntro.render(graphics, 0.0F, () -> render(graphics, mouseX, mouseY, partialTick));
            } finally {
                hsIntroRendering = false;
            }
            return;
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        List<Integer> visible = visibleOfferIndices();
        clampScroll(visible.size());

        for (int row = 0; row < VISIBLE_ROWS; row++) {
            int visualIndex = scrollOffset + row;
            if (visualIndex >= visible.size()) {
                break;
            }
            int rawOfferIndex = visible.get(visualIndex);
            MerchantOffer offer = this.menu.getOffers().get(rawOfferIndex);
            Rect r = layout.row(row);
            int x = this.leftPos + r.x();
            int y = this.topPos + r.y();
            boolean hovered = inside(mouseX, mouseY, x, y, LIST_W, LIST_H);
            boolean selected = rawOfferIndex == selectedOffer;
            // Standard list row: hover tint, forest bar on the selected server offer.
            Ui2Surface.row(graphics, x, y, LIST_W, LIST_H, hovered ? 1.0F : 0.0F, selected);
            if (visualIndex + 1 < visible.size() && row + 1 < VISIBLE_ROWS) {
                Ui2Surface.rule(graphics, x + 2, y + LIST_H - 1, LIST_W - 4);
            }

            ItemStack cost = offer.getCostA();
            ItemStack result = offer.getResult();
            graphics.renderFakeItem(cost, x + 4, y + 4);
            graphics.renderItemDecorations(this.font, cost, x + 4, y + 4);
            graphics.renderFakeItem(result, x + LIST_W - 20, y + 4);
            graphics.renderItemDecorations(this.font, result, x + LIST_W - 20, y + 4);

            paperLabelIn(graphics, this.font, Component.literal(result.is(ModItems.GOLD_COIN.get())
                    ? qualityText(offer).replace(" shipment", "") : "Trade"),
                x + ROW_TEXT_X, y + 4, ROW_TEXT_W, Ui2Palette.INK);
            paperLabelIn(graphics, this.font, Component.literal(offer.isOutOfStock() ? "Sold out" : "→ " + result.getCount()),
                x + ROW_TEXT_X, y + 14, ROW_TEXT_W,
                offer.isOutOfStock() ? Ui2Palette.DANGER : Ui2Palette.INK_SOFT);

            if (hovered && mouseX < x + 22) {
                graphics.renderTooltip(this.font, cost, mouseX, mouseY);
            } else if (hovered && mouseX > x + LIST_W - 24) {
                graphics.renderTooltip(this.font, result, mouseX, mouseY);
            } else if (hovered) {
                // "Exceptional" / "Masterwork" / "Legendary" do not fit between the icons.
                graphics.renderTooltip(this.font, Component.literal(qualityText(offer)), mouseX, mouseY);
            }
        }
        renderScrollbar(graphics, visible.size());
        this.renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            List<Integer> visible = visibleOfferIndices();
            for (int row = 0; row < VISIBLE_ROWS; row++) {
                int visualIndex = scrollOffset + row;
                if (visualIndex >= visible.size()) {
                    break;
                }
                Rect r = layout.row(row);
                if (inside(mouseX, mouseY, this.leftPos + r.x(), this.topPos + r.y(), LIST_W, LIST_H)) {
                    selectOffer(visible.get(visualIndex));
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount,
                                 double verticalAmount) {
        List<Integer> visible = visibleOfferIndices();
        if (visible.size() > VISIBLE_ROWS) {
            scrollOffset = Mth.clamp(scrollOffset - (int)Math.signum(verticalAmount), 0,
                visible.size() - VISIBLE_ROWS);
        }
        return true;
    }

    private void selectOffer(int rawOfferIndex) {
        // Cached 1.21.1 MerchantScreen protocol sequence; raw, unfiltered index.
        this.selectedOffer = rawOfferIndex;
        this.menu.setSelectionHint(rawOfferIndex);
        this.menu.tryMoveItems(rawOfferIndex);
        this.minecraft.getConnection().send(new ServerboundSelectTradePacket(rawOfferIndex));
    }

    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int left, int top, int button) {
        Ui2FrameLayout f = layout.frame();
        return !inside(mouseX, mouseY, f.x(), f.y(), f.width(), f.height());
    }

    private List<Integer> visibleOfferIndices() {
        MerchantOffers offers = this.menu.getOffers();
        List<Integer> visible = new ArrayList<>(offers.size());
        for (int rawIndex = 0; rawIndex < offers.size(); rawIndex++) {
            MerchantOffer offer = offers.get(rawIndex);
            // Presentation filtering only: MerchantOffers itself remains unchanged.
            if (!GoldCoinTrades.isCompletedBasicPurchase(offer)) {
                visible.add(rawIndex);
            }
        }
        return visible;
    }

    private MerchantOffer selectedVisibleOffer() {
        if (selectedOffer < 0 || selectedOffer >= this.menu.getOffers().size()) {
            return null;
        }
        MerchantOffer offer = this.menu.getOffers().get(selectedOffer);
        return GoldCoinTrades.isCompletedBasicPurchase(offer) ? null : offer;
    }

    private void clampScroll(int visibleCount) {
        scrollOffset = Mth.clamp(scrollOffset, 0, Math.max(0, visibleCount - VISIBLE_ROWS));
    }

    private void renderScrollbar(GuiGraphics graphics, int visibleCount) {
        if (visibleCount <= VISIBLE_ROWS) {
            return;
        }
        Rect s = layout.scrollbar();
        Ui2Surface.scrollbar(graphics, this.leftPos + s.x() + 1, this.topPos + s.y(), s.height(),
            (float) VISIBLE_ROWS / visibleCount, (float) scrollOffset / (visibleCount - VISIBLE_ROWS));
    }

    private Component fitted(Component text, int width) {
        return this.font.width(text) <= width ? text
            : Component.literal(this.font.plainSubstrByWidth(text.getString(),
                Math.max(0, width - this.font.width("..."))) + "...");
    }

    /** Dark ink on parchment, ellipsised to {@code width}, no shadow. */
    private static void paperLabelIn(GuiGraphics graphics, net.minecraft.client.gui.Font font,
                                     Component text, int x, int y, int width, int color) {
        Component shown = font.width(text) <= width ? text
            : Component.literal(font.plainSubstrByWidth(text.getString(),
                Math.max(0, width - font.width("..."))) + "...");
        graphics.drawString(font, shown, x, y, color, false);
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private static String costsText(MerchantOffer offer) {
        String costs = stackText(offer.getCostA());
        if (!offer.getCostB().isEmpty()) {
            costs += " + " + stackText(offer.getCostB());
        }
        return costs;
    }

    private static String stackText(ItemStack stack) {
        return com.hearthstead.util.CoinText.stack(stack);
    }

    private static String qualityText(MerchantOffer offer) {
        if (!offer.getResult().is(ModItems.GOLD_COIN.get())) {
            return "Merchant offer";
        }
        return switch (GoodsQuality.of(offer.getBaseCostA())) {
            case GoodsQuality.FINE -> "Fine shipment";
            case GoodsQuality.SUPERIOR -> "Superior shipment";
            case GoodsQuality.EXCEPTIONAL -> "Exceptional shipment";
            case GoodsQuality.MASTERWORK -> "Masterwork shipment";
            case GoodsQuality.LEGENDARY -> "Legendary shipment";
            default -> "Basic shipment";
        };
    }

    /** Board centred in the viewport; leftPos/topPos follow from it (pure, tested at GUI 2-4). */
    static Layout layoutFor(int viewportW, int viewportH) {
        int fx = (viewportW - FRAME_W) / 2;
        int fy = Math.max(0, (viewportH - FRAME_H) / 2);
        return new Layout(fx + SIDE_OUT, fy + TOP_OUT,
            Ui2FrameLayout.at(fx, fy, FRAME_W, FRAME_H, false));
    }

    /**
     * Absolute frame plus the screen's own pieces in coordinates local to
     * leftPos/topPos (the same space as the menu's slot x/y).
     */
    record Layout(int leftPos, int topPos, Ui2FrameLayout frame) {
        /** The page content area, local to leftPos/topPos. */
        Rect localContent() {
            Rect c = frame.content();
            return new Rect(c.x() - leftPos, c.y() - topPos, c.width(), c.height());
        }

        Rect heading() {
            return new Rect(LIST_X, 0, SCROLL_X + 3 - LIST_X, 12);
        }

        Rect row(int i) {
            return new Rect(LIST_X, LIST_Y + i * LIST_H, LIST_W, LIST_H);
        }

        Rect scrollbar() {
            return new Rect(SCROLL_X, LIST_Y, 3, VISIBLE_ROWS * LIST_H - 2);
        }

        /** Give / receive / quality lines above the payment slots. */
        Rect detail() {
            return new Rect(DETAIL_X, 0, DETAIL_W, 31);
        }

        /** Visitor purse status strip between the trade slots and the inventory label. */
        Rect purse() {
            return new Rect(DETAIL_X - 1, PURSE_Y, DETAIL_W + 1, 14);
        }

        Rect inventoryLabel() {
            return new Rect(DETAIL_X, INVENTORY_LABEL_Y, DETAIL_W, 9);
        }
    }
}
