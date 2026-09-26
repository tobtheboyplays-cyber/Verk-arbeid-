package com.hearthstead.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * Shared Hearthstead action button: moss for normal actions, red only for
 * dangerous actions, warm-pale labels on the intentionally dark button face.
 */
public class HsButton extends AbstractButton {

    public enum Kind {
        NORMAL,
        DANGER
    }

    private final Runnable onPress;
    private final Kind kind;
    private final HsUi.FittedLabelCache fittedLabel = new HsUi.FittedLabelCache();
    private final HsMotion.Tween hoverTween = new HsMotion.Tween();

    public HsButton(int x, int y, int width, int height, Component label,
                    Kind kind, Runnable onPress) {
        super(x, y, width, height, label);
        this.kind = kind;
        this.onPress = onPress;
    }

    public static HsButton normal(int x, int y, int w, int h, Component label,
                                  Runnable onPress) {
        return new HsButton(x, y, w, h, label, Kind.NORMAL, onPress);
    }

    public static HsButton danger(int x, int y, int w, int h, Component label,
                                  Runnable onPress) {
        return new HsButton(x, y, w, h, label, Kind.DANGER, onPress);
    }

    @Override
    public void onPress() {
        onPress.run();
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY,
                                float partialTick) {
        boolean hovered = active && isHoveredOrFocused();
        boolean lit = hovered || isMouseDown();
        HsMotion.blendHover(graphics, hoverProgress(lit), h ->
            HsUi.actionButton(graphics, getX(), getY(), getWidth(), getHeight(),
                kind == Kind.DANGER, active, h, false));
        if (active && isFocused()) {
            HsUi.keyboardFocus(graphics, getX(), getY(), getWidth(), getHeight());
        }

        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        int innerWidth = Math.max(1, getWidth() - 8);
        HsUi.FittedLabel label = fittedLabel.fit(font, getMessage(), innerWidth,
            minecraft.getLanguageManager().getSelected());
        int drawnWidth = Math.min(innerWidth, label.width());
        int labelX = getX() + 4 + (innerWidth - drawnWidth) / 2;
        graphics.drawString(font, label.text(), labelX,
            getY() + (getHeight() - HsUiTokens.TEXT_H) / 2 + 1 + labelPressOffset(),
            active ? HsUiTokens.TEXT_STRONG : HsUiTokens.TEXT_DISABLED, true);
    }

    /**
     * Eased 0..1 hover progress toward {@code lit}. Subclasses blend their
     * original idle and hover faces with it; at rest it is exactly 0 or 1.
     */
    protected float hoverProgress(boolean lit) {
        return hoverTween.update(lit);
    }

    /** 1 px label press-down only while the pointer physically holds the button. */
    protected int labelPressOffset() {
        return active && isHovered && HsMotion.mouseHeld() ? 1 : 0;
    }

    private boolean isMouseDown() {
        return isFocused() && isActive() && isHovered;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
