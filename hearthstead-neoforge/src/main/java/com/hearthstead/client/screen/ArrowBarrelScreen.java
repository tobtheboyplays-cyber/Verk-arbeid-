package com.hearthstead.client.screen;

import com.hearthstead.client.ui.HsUi;
import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import com.hearthstead.client.ui2.Ui2Button;
import com.hearthstead.client.ui2.Ui2Frame;
import com.hearthstead.client.ui2.Ui2FrameLayout;
import com.hearthstead.client.ui2.Ui2Palette;
import com.hearthstead.client.ui2.Ui2Serif;
import com.hearthstead.client.ui2.Ui2Surface;
import com.hearthstead.menu.ArrowBarrelMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * The Arrow Barrel in the standard Bannerhold window (the fish rack's frame):
 * nine arrow-only slots in sunken wells, one ink line, and one framed
 * primary button "Call archers to resupply". The button is vanilla's
 * container button click; the server validates and answers on the action bar.
 */
public final class ArrowBarrelScreen extends AbstractContainerScreen<ArrowBarrelMenu> {
    static final int IMAGE_W = 202;
    static final int IMAGE_H = 224;
    static final int TEXT_X = 20;
    static final int HINT_Y = 74;
    static final int BUTTON_Y = 86;
    static final int BUTTON_H = 18;
    static final int INVENTORY_Y = 110;
    static final int FOOTER_Y = 203;

    private final Ui2Serif.Text titleText = new Ui2Serif.Text(Ui2Serif.Size.TITLE);
    private Ui2FrameLayout frame;
    private Component hintLine = Component.empty();
    private Component footerLine = Component.empty();

    public ArrowBarrelScreen(ArrowBarrelMenu menu, Inventory inventory, Component title) {
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
        frame = Ui2FrameLayout.at(leftPos, topPos, IMAGE_W, IMAGE_H, true);
        int textW = textWidth();
        hintLine = HsUi.fitLabel(font, Component.translatable("hearthstead.arrow_barrel.hint"), textW).text();
        footerLine = HsUi.fitLabel(font, Component.translatable("hearthstead.arrow_barrel.footer"), textW).text();
        addRenderableWidget(Ui2Frame.closeKey(frame, this::onClose));
        addRenderableWidget(Ui2Button.primary(leftPos + TEXT_X, topPos + BUTTON_Y, textW, BUTTON_H,
            Component.translatable("hearthstead.arrow_barrel.call"), this::callArchers));
    }

    private void callArchers() {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, ArrowBarrelMenu.BUTTON_CALL);
        }
    }

    @Override
    protected void renderBg(GuiGraphics g, float partial, int mouseX, int mouseY) {
        Ui2Frame.draw(g, frame);
        Ui2Frame.title(g, font, frame, titleText, title.getString(),
            Component.translatable("hearthstead.arrow_barrel.subtitle"));
        for (var slot : menu.slots) {
            Ui2Surface.slotWell(g, leftPos + slot.x - 1, topPos + slot.y - 1);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, hintLine, TEXT_X, HINT_Y, Ui2Palette.INK_SOFT, false);
        Ui2Surface.sectionHeader(g, font, playerInventoryTitle, TEXT_X, INVENTORY_Y, textWidth());
        g.drawString(font, footerLine, TEXT_X, FOOTER_Y, Ui2Palette.INK_MUTED, false);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        renderTooltip(g, mouseX, mouseY);
    }

    /** Page text runs from {@link #TEXT_X} to the page's inner right edge. */
    static int textWidth() {
        Rect page = Ui2FrameLayout.at(0, 0, IMAGE_W, IMAGE_H, true).page();
        return page.right() - 4 - TEXT_X;
    }
}
