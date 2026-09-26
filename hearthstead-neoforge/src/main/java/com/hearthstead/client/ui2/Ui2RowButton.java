package com.hearthstead.client.ui2;

import com.hearthstead.client.ui.HsButton;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Input, focus and narration for one list row. It paints only the row state
 * (eased hover tint, selected forest bar, keyboard focus); the owning page
 * paints the row text once, above it.
 */
public class Ui2RowButton extends HsButton {
    private final boolean selected;

    public Ui2RowButton(int x, int y, int w, int h, Component label, boolean selected, Runnable onPress) {
        super(x, y, w, h, label, Kind.NORMAL, onPress);
        this.selected = selected;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        float hover = hoverProgress(active && isHoveredOrFocused());
        Ui2Surface.row(g, getX(), getY(), getWidth(), getHeight(), hover, selected);
        if (isFocused() && !isHovered()) Ui2Surface.focus(g, getX(), getY(), getWidth(), getHeight() - 1);
    }
}
