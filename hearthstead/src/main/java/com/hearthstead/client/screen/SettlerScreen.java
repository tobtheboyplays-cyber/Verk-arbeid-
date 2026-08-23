package com.hearthstead.client.screen;

import com.hearthstead.entity.SettlerEntity;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Concise settler inspection card. (Layout art lands in the GUI phase.) */
public class SettlerScreen extends Screen {
    protected final SettlerEntity settler;

    public SettlerScreen(SettlerEntity settler) {
        super(Component.literal(settler.getSettlerName()));
        this.settler = settler;
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

    @Override
    public void tick() {
        if (settler.isRemoved()) {
            onClose();
        }
    }
}
