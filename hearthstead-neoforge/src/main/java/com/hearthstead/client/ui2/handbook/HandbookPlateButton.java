package com.hearthstead.client.ui2.handbook;

import com.hearthstead.client.ui.HsButton;
import com.hearthstead.client.ui2.BannerChrome;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** Wooden page-turn plate for the footer: a chevron and a short label ("Back" / "Next"). */
public class HandbookPlateButton extends HsButton {
    private final boolean forward;

    public HandbookPlateButton(int x, int y, int w, int h, Component label, boolean forward, Runnable onPress) {
        super(x, y, w, h, label, Kind.NORMAL, onPress);
        this.forward = forward;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        float hover = hoverProgress(active && isHoveredOrFocused());
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        BannerChrome.navPlate(g, x, y, w, h, false, hover);
        int ink = !active ? BannerChrome.TEXT_ON_WOOD_MUTED : hover > 0.5F ? BannerChrome.TEXT_ON_WOOD
            : com.hearthstead.client.screen.HandbookScreen.NAV_IDLE_INK;
        String text = getMessage().getString();
        int tw = font.width(text);
        int cy = y + h / 2;
        int chevronX = forward ? x + w - 9 : x + 5;
        for (int i = 0; i < 3; i++) {
            int px = forward ? chevronX + i : chevronX + 2 - i;
            g.fill(px, cy - 3 + i, px + 1, cy - 2 + i, ink);
            g.fill(px, cy + 2 - i, px + 1, cy + 3 - i, ink);
        }
        g.fill(forward ? chevronX + 3 : chevronX - 1, cy - 1, forward ? chevronX + 4 : chevronX, cy + 1, ink);
        int room = w - 16;
        int tx = forward ? x + 4 + Math.max(0, (room - tw) / 2) : x + 12 + Math.max(0, (room - tw) / 2);
        if (tw <= room) g.drawString(font, text, tx, y + (h - 8) / 2 + labelPressOffset(), ink, false);
        if (active && isFocused() && !isHovered()) {
            for (int px = x + 2; px < x + w - 2; px += 2) g.fill(px, y + h - 2, px + 1, y + h - 1, BannerChrome.GOLD_EDGE);
        }
    }
}
