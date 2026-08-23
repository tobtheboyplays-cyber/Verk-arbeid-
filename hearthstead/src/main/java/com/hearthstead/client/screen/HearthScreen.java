package com.hearthstead.client.screen;

import com.hearthstead.Hearthstead;
import com.hearthstead.menu.HearthMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;

/**
 * The settlement ledger: stats column on parchment, communal stores grid,
 * player inventory below. Texture is 256x256; screen is 220x222.
 */
public class HearthScreen extends AbstractContainerScreen<HearthMenu> {
    private static final ResourceLocation TEXTURE =
        Hearthstead.id("textures/gui/hearth_screen.png");

    private static final int INK = 0xFF3F3024;
    private static final int INK_SOFT = 0xFF69573C;
    private static final int PARCHMENT_LIGHT = 0xFFEFE0BD;

    // Stat rows (icon + value) in the left column.
    private static final int STAT_X = 14;
    private static final int STAT_Y = 34;
    private static final int STAT_ROW_H = 16;
    private static final int STAT_W = 84;

    // Morale bar geometry.
    private static final int BAR_X = 14;
    private static final int BAR_Y = 103;
    private static final int BAR_W = 80;
    private static final int BAR_H = 8;

    public HearthScreen(HearthMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        imageWidth = 220;
        imageHeight = 222;
        inventoryLabelX = HearthMenu.PLAYER_INV_X + 1;
        inventoryLabelY = HearthMenu.PLAYER_INV_Y - 11;
        titleLabelY = -1000; // we draw our own header
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = (width - imageWidth) / 2;
        int y = (height - imageHeight) / 2;
        graphics.blit(TEXTURE, x, y, 0, 0, imageWidth, imageHeight);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // Header: settlement name, centered in the title band.
        String name = menu.getSettlementName();
        Component header = name == null || name.isEmpty()
            ? Component.translatable("container.hearthstead.hearth")
            : Component.literal(name);
        graphics.drawString(font, header,
            (imageWidth - font.width(header)) / 2, 10, PARCHMENT_LIGHT, false);

        graphics.drawString(font, Component.translatable("hearthstead.gui.stores"),
            HearthMenu.COMMUNAL_X + 1, HearthMenu.COMMUNAL_Y - 11, INK, false);
        graphics.drawString(font, playerInventoryTitle,
            inventoryLabelX, inventoryLabelY, INK, false);

        // Stat rows.
        int pop = menu.get(HearthMenu.DATA_POPULATION);
        int cap = menu.get(HearthMenu.DATA_CAPACITY);
        int employed = menu.get(HearthMenu.DATA_EMPLOYED);
        int food = menu.get(HearthMenu.DATA_FOOD);
        int radius = menu.get(HearthMenu.DATA_RADIUS);
        drawStat(graphics, 0, 0, pop + " / " + cap);
        drawStat(graphics, 1, 16, employed + " / " + pop);
        drawStat(graphics, 2, 32, String.valueOf(food));
        drawStat(graphics, 3, 48, radius + " m");

        // Morale bar with semantic color.
        int morale = Mth.clamp(menu.get(HearthMenu.DATA_MORALE), 0, 100);
        graphics.drawString(font, Component.translatable("hearthstead.gui.morale"),
            STAT_X, BAR_Y - 10, INK, false);
        int fillColor = moraleColor(morale);
        int fill = morale * (BAR_W - 2) / 100;
        graphics.fill(BAR_X + 1, BAR_Y + 1, BAR_X + 1 + fill, BAR_Y + BAR_H - 1, fillColor);
        // Quarter tick marks over the fill.
        for (int q = 1; q < 4; q++) {
            int tx = BAR_X + q * BAR_W / 4;
            graphics.fill(tx, BAR_Y + 1, tx + 1, BAR_Y + BAR_H - 1, 0x33000000);
        }
        Component band = moraleBand(morale);
        graphics.drawString(font, band, BAR_X + BAR_W + 4, BAR_Y, fillColor | 0xFF000000, false);

        // Alert banner / recruit progress share the strip under the bar.
        if (menu.get(HearthMenu.DATA_ALERT) == 1) {
            boolean blink = (System.currentTimeMillis() / 400) % 2 == 0;
            graphics.drawString(font, Component.translatable("hearthstead.gui.alert"),
                STAT_X, BAR_Y + 14, blink ? 0xFFA03030 : 0xFF702020, false);
        } else {
            int recruit = menu.get(HearthMenu.DATA_RECRUIT);
            if (recruit > 0) {
                graphics.drawString(font,
                    Component.translatable("hearthstead.gui.recruit_progress"),
                    STAT_X, BAR_Y + 13, INK_SOFT, false);
                graphics.fill(BAR_X, BAR_Y + 23, BAR_X + BAR_W, BAR_Y + 26, 0xFF54432F);
                graphics.fill(BAR_X, BAR_Y + 23, BAR_X + recruit * BAR_W / 100,
                    BAR_Y + 26, 0xFFC9A83C);
            }
        }
    }

    /** One icon (from the texture's icon strip at u=224) + value text. */
    private void drawStat(GuiGraphics graphics, int row, int iconV, String value) {
        int y = STAT_Y + row * STAT_ROW_H;
        graphics.blit(TEXTURE, STAT_X, y, 224, iconV, 12, 12);
        graphics.drawString(font, value, STAT_X + 17, y + 2, INK, false);
    }

    private static int moraleColor(int morale) {
        if (morale < 25) {
            return 0xFFA03535;
        }
        if (morale < 50) {
            return 0xFFC07A35;
        }
        if (morale < 75) {
            return 0xFFC9A83C;
        }
        return 0xFF5B8A4A;
    }

    private static Component moraleBand(int morale) {
        String key = morale < 25 ? "miserable" : morale < 50 ? "uneasy"
            : morale < 75 ? "content" : "joyful";
        return Component.translatable("hearthstead.morale." + key);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        renderStatTooltips(graphics, mouseX, mouseY);
    }

    private void renderStatTooltips(GuiGraphics graphics, int mouseX, int mouseY) {
        int localX = mouseX - leftPos;
        int localY = mouseY - topPos;
        List<Component> lines = new ArrayList<>();
        if (localX >= STAT_X && localX < STAT_X + STAT_W) {
            int row = (localY - STAT_Y) / STAT_ROW_H;
            if (localY >= STAT_Y && row >= 0 && row < 4
                && localY < STAT_Y + 4 * STAT_ROW_H) {
                String key = switch (row) {
                    case 0 -> "population";
                    case 1 -> "employed";
                    case 2 -> "food";
                    default -> "radius";
                };
                lines.add(Component.translatable("hearthstead.gui.tooltip." + key));
                lines.add(Component.translatable("hearthstead.gui.tooltip." + key + ".desc")
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
            } else if (localY >= BAR_Y - 10 && localY < BAR_Y + BAR_H + 2) {
                lines.add(Component.translatable("hearthstead.gui.tooltip.morale"));
                lines.add(Component.translatable("hearthstead.gui.tooltip.morale.desc")
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
            } else if (menu.get(HearthMenu.DATA_ALERT) != 1
                && menu.get(HearthMenu.DATA_RECRUIT) > 0
                && localY >= BAR_Y + 12 && localY < BAR_Y + 27) {
                lines.add(Component.translatable("hearthstead.gui.tooltip.recruit",
                    menu.get(HearthMenu.DATA_RECRUIT)));
            }
        }
        if (!lines.isEmpty()) {
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
        }
    }
}
