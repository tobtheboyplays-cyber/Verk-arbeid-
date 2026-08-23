package com.hearthstead.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The settler's handbook. (Page art lands in the GUI phase.) */
public class HandbookScreen extends Screen {

    public HandbookScreen() {
        super(Component.translatable("hearthstead.guide.title"));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
