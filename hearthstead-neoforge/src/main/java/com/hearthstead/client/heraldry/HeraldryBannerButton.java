package com.hearthstead.client.heraldry;

import com.hearthstead.client.ui2.BannerChrome;
import com.hearthstead.client.ui2.Ui2Palette;
import com.hearthstead.heraldry.VillageDesign;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * The Banner screen's hanging banner, now the village's real colours and
 * cloth shape, and the "Heraldry" button: clicking it opens the Banner
 * designer. {@link #paint} draws the banner (called from the screen's
 * background pass so it also shows under popouts); the button itself adds
 * only the hover rim.
 */
public final class HeraldryBannerButton extends AbstractButton {
    private final Runnable action;

    public HeraldryBannerButton(int x, int y, int w, int h, Runnable action) {
        super(x, y, w, h, Component.translatable("hearthstead.heraldry.button"));
        this.action = action;
        setTooltip(Tooltip.create(Component.translatable("hearthstead.heraldry.button")
            .append("\n").append(Component.translatable("hearthstead.heraldry.button.tip")
                .withStyle(net.minecraft.ChatFormatting.GRAY))));
    }

    @Override
    public void onPress() {
        action.run();
    }

    /** Iron rod and the village banner in the rect the old pixel banner used. */
    public static void paint(GuiGraphics g, BlockPos hearth, int x, int y, int w, int h) {
        VillageDesign design = BannerDesignerClient.designAt(hearth);
        if (design == null) {
            BannerChrome.hangingBanner(g, x, y, w, h);
            return;
        }
        g.fill(x - 3, y + 1, x + w + 3, y + 4, Ui2Palette.IRON_DARK);
        g.fill(x - 2, y + 2, x + w + 2, y + 3, Ui2Palette.IRON_LIGHT);
        g.fill(x - 4, y, x - 2, y + 5, Ui2Palette.IRON_DARK);
        g.fill(x + w + 2, y, x + w + 4, y + 5, Ui2Palette.IRON_DARK);
        int cw = Math.min(w, (h - 4) / 2);
        HeraldryGui.drawBanner(g, design, x + (w - cw) / 2, y + 3, cw);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (active && isHoveredOrFocused()) {
            BannerChrome.outline(g, getX() - 2, getY() + 3, getWidth() + 4, getHeight() - 3, BannerChrome.GOLD_EDGE);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
