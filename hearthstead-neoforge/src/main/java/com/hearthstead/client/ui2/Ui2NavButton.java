package com.hearthstead.client.ui2;

import com.hearthstead.client.ui.HsButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * One destination in the Banner screen's left rail: icon plus label, or
 * icon only when the rail is compact (the label becomes the tooltip).
 *
 * <p>Each destination is a wooden plate on the walnut board; the selected
 * one is burgundy with a light gold border. Hover lifts the plate slightly;
 * keyboard focus adds a gold dotted line. Destinations that open another
 * window carry the north-east arrow glyph.
 */
public class Ui2NavButton extends HsButton {
    private final ItemStack icon;
    private final boolean selected;
    private final boolean compact;
    private final boolean external;
    private final boolean last;

    public Ui2NavButton(int x, int y, int w, int h, Component label, ItemStack icon, boolean selected,
                        boolean compact, boolean external, boolean last, Runnable onPress) {
        super(x, y, w, h, label, Kind.NORMAL, onPress);
        this.icon = icon;
        this.selected = selected;
        this.compact = compact;
        this.external = external;
        this.last = last;
    }

    public boolean selected() {
        return selected;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        float hover = hoverProgress(active && isHoveredOrFocused());
        int plateH = h - 2;
        BannerChrome.navPlate(g, x, y, w, plateH, selected, hover);
        int iconX = compact ? x + (w - 16) / 2 : x + 3;
        int iconY = y + (plateH - 16) / 2;
        g.renderItem(icon, iconX, iconY);
        g.pose().pushPose();
        g.pose().translate(0, 0, 200);
        int ink = !active ? BannerChrome.TEXT_ON_WOOD_MUTED
            : selected || hover > 0.5F ? BannerChrome.TEXT_ON_WOOD : BannerChrome.TEXT_ON_WOOD_IDLE;
        if (!compact) {
            int textY = y + (plateH - 8) / 2;
            g.drawString(font, getMessage(), x + 21, textY, ink, false);
        }
        if (external) {
            // QA U2: a clear gap after the label; if the label runs to the edge,
            // the arrow sits in the corner above the text line instead of on it.
            int after = x + 21 + font.width(getMessage()) + 3;
            if (!compact && after + 5 <= x + w - 3) {
                Ui2Surface.externalGlyph(g, after, y + (plateH - 8) / 2, ink);
            } else {
                Ui2Surface.externalGlyph(g, x + w - 8, y + 1, ink);
            }
        }
        if (isFocused() && !isHovered()) {
            for (int px = x + 2; px < x + w - 2; px += 2) g.fill(px, y + plateH - 2, px + 1, y + plateH - 1, BannerChrome.GOLD_EDGE);
        }
        g.pose().popPose();
    }
}
