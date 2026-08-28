package com.hearthstead.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
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
        // long translation shortens instead of spilling over the frame.
        HsUi.labelIn(graphics, net.minecraft.client.Minecraft.getInstance().font,
            getMessage(),
            getX() + (getWidth() - Math.min(getWidth() - 8,
                net.minecraft.client.Minecraft.getInstance().font
                    .width(getMessage()))) / 2,
            getY() + (getHeight() - HsUiTokens.TEXT_H) / 2 + 1,
            getWidth() - 8, colour);
    }

    private boolean isMouseDown() {
        return isFocused() && isActive() && isHovered;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
