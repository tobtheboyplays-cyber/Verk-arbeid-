package com.hearthstead.client.screen;

import com.hearthstead.client.ui.HsUi;
import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import com.hearthstead.client.ui2.Ui2Frame;
import com.hearthstead.client.ui2.Ui2FrameLayout;
import com.hearthstead.client.ui2.Ui2Palette;
import com.hearthstead.client.ui2.Ui2Serif;
import com.hearthstead.client.ui2.Ui2Surface;
import com.hearthstead.menu.FishRackMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * The catch rack in the standard Bannerhold window: walnut board, crest,
 * serif title and subtitle on the wood, the wooden close key and one
 * parchment page. The four hooks and the player inventory keep the exact
 * slot positions of {@link FishRackMenu}; each sits in a sunken well.
 */
public final class FishRackScreen extends AbstractContainerScreen<FishRackMenu> {
    /** Unchanged width: the slots stay centred on the page. */
    static final int IMAGE_W = 202;
    /** 212 before; taller so the footer line sits on the page, slot offsets unchanged. */
    static final int IMAGE_H = 224;
    static final int TEXT_X = 20;
    static final int FOOD_Y = 83;
    static final int TRADE_Y = 94;
    static final int INVENTORY_Y = 110;
    static final int FOOTER_Y = 203;

    private final Ui2Serif.Text titleText = new Ui2Serif.Text(Ui2Serif.Size.TITLE);
    private Ui2FrameLayout frame;
    private Component foodLine = Component.empty();
    private Component tradeLine = Component.empty();
    private Component footerLine = Component.empty();

    public FishRackScreen(FishRackMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = IMAGE_W;
        imageHeight = IMAGE_H;
        titleLabelX = TEXT_X;
        titleLabelY = 10;
        inventoryLabelX = TEXT_X;
        inventoryLabelY = INVENTORY_Y;
    }

    @Override
    protected void init() {
        super.init();
        frame = frameAt(leftPos, topPos);
        int textW = textWidth();
        foodLine = HsUi.fitLabel(font, Component.translatable("hearthstead.fisher.rack.food"), textW).text();
        tradeLine = HsUi.fitLabel(font, Component.translatable("hearthstead.fisher.rack.trade"), textW).text();
        footerLine = HsUi.fitLabel(font, Component.translatable("hearthstead.fisher.rack.footer"), textW).text();
        addRenderableWidget(Ui2Frame.closeKey(frame, this::onClose));
    }

    @Override
    protected void renderBg(GuiGraphics g, float partial, int mouseX, int mouseY) {
        Ui2Frame.draw(g, frame);
        Ui2Frame.title(g, font, frame, titleText, title.getString(),
            Component.translatable("hearthstead.fisher.rack.subtitle"));
        for (var slot : menu.slots) {
            Ui2Surface.slotWell(g, leftPos + slot.x - 1, topPos + slot.y - 1);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // Title and subtitle are on the wood (renderBg); page text is ink, no shadow.
        g.drawString(font, foodLine, TEXT_X, FOOD_Y, Ui2Palette.INK_SOFT, false);
        g.drawString(font, tradeLine, TEXT_X, TRADE_Y, Ui2Palette.INK_SOFT, false);
        Ui2Surface.sectionHeader(g, font, playerInventoryTitle, TEXT_X, INVENTORY_Y, textWidth());
        g.drawString(font, footerLine, TEXT_X, FOOTER_Y, Ui2Palette.INK_MUTED, false);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        renderTooltip(g, mouseX, mouseY);
        // The 125 px title column cuts the subtitle; show the full words on hover.
        if (menu.getCarried().isEmpty() && hoveredSlot == null) {
            Ui2Frame.titleTooltip(g, font, frame, titleText, title.getString(),
                Component.translatable("hearthstead.fisher.rack.subtitle"), mouseX, mouseY);
        }
    }

    /** Frame built around the container image so every slot lands on the page. */
    static Ui2FrameLayout frameAt(int leftPos, int topPos) {
        return Ui2FrameLayout.at(leftPos, topPos, IMAGE_W, IMAGE_H, true);
    }

    /** Page text runs from {@link #TEXT_X} to the page's inner right edge. */
    static int textWidth() {
        Rect page = frameAt(0, 0).page();
        return page.right() - 4 - TEXT_X;
    }

    /** Page text lines, image-relative, for the layout test. */
    static Rect[] textRects() {
        int w = textWidth();
        return new Rect[] {
            new Rect(TEXT_X, FOOD_Y, w, 9),
            new Rect(TEXT_X, TRADE_Y, w, 9),
            new Rect(TEXT_X, INVENTORY_Y, w, 9),
            new Rect(TEXT_X, FOOTER_Y, w, 9)};
    }
}
