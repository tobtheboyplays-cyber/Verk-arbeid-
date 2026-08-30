package com.hearthstead.client.screen;

import com.hearthstead.client.QaClientObserver;
import com.hearthstead.client.QaUiInspectable;
import com.hearthstead.client.ui.HsUi;
import com.hearthstead.client.ui.HsUiTokens;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.menu.SettlerInventoryMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Real slot UI for a settler's persisted inventory.
 *
 * <p>There are intentionally no synthetic item buttons here. The eight top
 * slots are the server entity's bag and the lower slots are the player's
 * ordinary inventory, so drag, split, hotbar swap and shift-click all use
 * vanilla's proven container transactions.
 */
public final class SettlerInventoryScreen
        extends AbstractContainerScreen<SettlerInventoryMenu>
        implements QaUiInspectable {
    private static final int WIDTH = 176;
    private static final int HEIGHT = 187;
    private static final int REQUEST_Y = 70;
    private static final int REQUEST_H = 22;
    private boolean uiSoundActive;

    public SettlerInventoryScreen(SettlerInventoryMenu menu,
                                  Inventory playerInventory,
                                  Component title) {
        super(menu, playerInventory, title);
        imageWidth = WIDTH;
        imageHeight = HEIGHT;
        titleLabelX = 8;
        titleLabelY = 8;
        inventoryLabelX = SettlerInventoryMenu.PLAYER_X;
        inventoryLabelY = SettlerInventoryMenu.PLAYER_Y - 9;
    }

    @Override
    protected void init() {
        super.init();
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

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY,
                       float partialTick) {
        // NeoForge's AbstractContainerScreen#render already invokes this
        // screen's renderBackground (transparent field + renderBg). Calling it
        // here as well painted the complete 44-slot inventory twice per frame
        // and was the last surviving double-background path in Hearthstead.
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        if (handled) {
            QaClientObserver.markUiTransition("settler_inventory_click");
        }
        return handled;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick,
                            int mouseX, int mouseY) {
        HsUi.window(graphics, leftPos, topPos, imageWidth, imageHeight);
        HsUi.divider(graphics, leftPos + 8, topPos + 20, imageWidth - 16);

        for (int row = 0; row < SettlerInventoryMenu.BAG_ROWS; row++) {
            for (int column = 0; column < SettlerInventoryMenu.BAG_COLUMNS;
                 column++) {
                HsUi.slot(graphics,
                    leftPos + SettlerInventoryMenu.BAG_X - 1 + column * 18,
                    topPos + SettlerInventoryMenu.BAG_Y - 1 + row * 18);
            }
        }
        HsUi.card(graphics, leftPos + 8, topPos + REQUEST_Y,
            imageWidth - 16, REQUEST_H, false);
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                HsUi.slot(graphics,
                    leftPos + SettlerInventoryMenu.PLAYER_X - 1 + column * 18,
                    topPos + SettlerInventoryMenu.PLAYER_Y - 1 + row * 18);
            }
        }
        for (int column = 0; column < 9; column++) {
            HsUi.slot(graphics,
                leftPos + SettlerInventoryMenu.PLAYER_X - 1 + column * 18,
                topPos + SettlerInventoryMenu.PLAYER_Y + 57);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        HsUi.labelIn(graphics, font, title, titleLabelX, titleLabelY,
            imageWidth - 16, HsUiTokens.TEXT_STRONG);
        HsUi.labelIn(graphics, font,
            Component.translatable("hearthstead.settler.inventory.bag"),
            8, 23, imageWidth - 16, HsUiTokens.TEXT_MUTED);
        HsUi.labelIn(graphics, font, playerInventoryTitle,
            inventoryLabelX, inventoryLabelY, imageWidth - 16,
            HsUiTokens.TEXT_MUTED);

        SettlerEntity settler = menu.settler();
        ItemStack request = settler == null ? ItemStack.EMPTY
            : settler.requestedEquipmentIcon();
        int textX = 14;
        if (!request.isEmpty()) {
            graphics.renderItem(request, 12, REQUEST_Y + 3);
            textX = 34;
        }
        Component line = request.isEmpty()
            ? Component.translatable("hearthstead.settler.request.none")
            : Component.translatable("hearthstead.settler.inventory.request",
                request.getHoverName());
        HsUi.labelIn(graphics, font, line, textX, REQUEST_Y + 3,
            imageWidth - textX - 12,
            request.isEmpty() ? HsUiTokens.GOOD : HsUiTokens.WARN);
        Component instruction = request.isEmpty()
            ? Component.translatable("hearthstead.settler.request.none.instruction")
            : Component.translatable("hearthstead.settler.inventory.authority");
        HsUi.labelIn(graphics, font,
            instruction, textX, REQUEST_Y + 13, imageWidth - textX - 12,
            HsUiTokens.TEXT_MUTED);
    }

    @Override
    public String qaUiState() {
        SettlerEntity settler = menu.settler();
        ItemStack request = settler == null ? ItemStack.EMPTY
            : settler.requestedEquipmentIcon();
        String equipment = request.isEmpty() ? "ready"
            : "needs_" + BuiltInRegistries.ITEM.getKey(request.getItem());
        int bagUsed = 0;
        for (int slot = 0; slot < SettlerInventoryMenu.SETTLER_SLOTS; slot++) {
            if (menu.getSlot(slot).hasItem()) {
                bagUsed++;
            }
        }
        return "equipment=" + equipment + ",bagUsed=" + bagUsed + "/"
            + SettlerInventoryMenu.SETTLER_SLOTS + ",requestCard=" + REQUEST_Y
            + ":" + REQUEST_H + ",inventoryLabelY=" + inventoryLabelY
            + ",labelGap=" + (inventoryLabelY - (REQUEST_Y + REQUEST_H));
    }
}
