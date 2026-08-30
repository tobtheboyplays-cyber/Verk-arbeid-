package com.hearthstead.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * A button in Hearthstead's material rather than vanilla's stone-grey.
 *
 * <p>Four states, because a button with no pressed state feels dead under the
 * mouse and a disabled button that looks enabled is a lie. The DANGER kind is
 * for the one press a player can regret — dismissing a settler — and it is a
 * different colour for the same reason a fire alarm is: you should never
 * confuse it with the button beside it.
 */
public class HsButton extends AbstractButton {

    public enum Kind {
        NORMAL("idle", "hover", "pressed"),
        DANGER("danger", "danger_hover", "danger");

        private final ResourceLocation idle;
        private final ResourceLocation hover;
        private final ResourceLocation pressed;

        Kind(String idleName, String hoverName, String pressedName) {
            this.idle = button(idleName);
            this.hover = button(hoverName);
            this.pressed = button(pressedName);
        }
    }

    private static final ResourceLocation DISABLED = button("disabled");

    private static ResourceLocation button(String state) {
        return ResourceLocation.fromNamespaceAndPath(
            com.hearthstead.Hearthstead.MODID, "widget/button_" + state);
    }

    private final Runnable onPress;
    private final Kind kind;
    /** One entry per button; never a process-wide translation or label cache. */
    private final HsUi.FittedLabelCache fittedLabel = new HsUi.FittedLabelCache();

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
        ResourceLocation sprite;
        if (!active) {
            sprite = DISABLED;
        } else if (isHoveredOrFocused()) {
            sprite = isMouseDown() ? kind.pressed : kind.hover;
        } else {
            sprite = kind.idle;
        }
        graphics.blitSprite(sprite, getX(), getY(), getWidth(), getHeight());
        int colour = active ? HsUiTokens.TEXT : HsUiTokens.TEXT_MUTED;
        // The label is centred on the button's own box and clipped to it, so a
        // long translation shortens instead of spilling over the frame. The
        // fitting itself is cached until one of its visual inputs changes.
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        int innerWidth = Math.max(1, getWidth() - 8);
        HsUi.FittedLabel label = fittedLabel.fit(font, getMessage(), innerWidth,
            minecraft.getLanguageManager().getSelected());
        int drawnWidth = Math.min(innerWidth, label.width());
        int labelX = getX() + 4 + (innerWidth - drawnWidth) / 2;
        graphics.drawString(font, label.text(), labelX,
            getY() + (getHeight() - HsUiTokens.TEXT_H) / 2 + 1, colour, true);
    }

    private boolean isMouseDown() {
        return isFocused() && isActive() && isHovered;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
