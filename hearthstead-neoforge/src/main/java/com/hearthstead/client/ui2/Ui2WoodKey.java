package com.hearthstead.client.ui2;

import com.hearthstead.client.ui.HsButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** A small light glyph key set on the walnut board (the close key). */
public class Ui2WoodKey extends HsButton {
    public Ui2WoodKey(int x, int y, int w, int h, Component label, Runnable onPress) {
        super(x, y, w, h, label, Kind.NORMAL, onPress);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        float hover = hoverProgress(active && isHoveredOrFocused());
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        if (hover > 0.01F) g.fill(x, y, x + w, y + h, BannerChrome.PLATE_HOVER);
        BannerChrome.outline(g, x, y, w, h, hover > 0.5F ? BannerChrome.GOLD_EDGE : BannerChrome.PLATE_HIGHLIGHT);
        var font = Minecraft.getInstance().font;
        int tw = font.width(getMessage());
        g.drawString(font, getMessage(), x + (w - tw + 1) / 2, y + (h - 8) / 2 + 1,
            hover > 0.5F ? BannerChrome.TEXT_ON_WOOD : BannerChrome.TEXT_ON_WOOD_MUTED, false);
    }
}
