package com.hearthstead.client.ui2;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.time.Duration;

/**
 * One tooltip contract for every screen.
 *
 * <ul>
 *   <li>Every action has a tooltip that says what it does.</li>
 *   <li>A disabled action stays visible, shows the padlock (drawn by
 *       {@link Ui2Button}) and its tooltip is "action" on the first line and
 *       the reason, grey, on the second -- never a silent grey button.</li>
 *   <li>Navigation (rails, tabs) waits {@link #NAV_DELAY} so passing over it
 *       never covers the page.</li>
 * </ul>
 */
public final class Ui2Tips {
    public static final Duration NAV_DELAY = Duration.ofMillis(650);

    private Ui2Tips() {
    }

    public static <T extends AbstractWidget> T tip(T widget, Component text) {
        widget.setTooltip(text == null ? null : Tooltip.create(text));
        return widget;
    }

    /** Tooltip for navigation: delayed. */
    public static <T extends AbstractWidget> T nav(T widget, Component text) {
        tip(widget, text);
        widget.setTooltipDelay(NAV_DELAY);
        return widget;
    }

    /**
     * Enables or disables {@code widget}; when disabled the tooltip becomes
     * {@link #why(Component, Component)} of its label and {@code reason}.
     */
    public static <T extends AbstractWidget> T enable(T widget, boolean enabled, Component tipWhenEnabled,
                                                      Component reasonWhenDisabled) {
        widget.active = enabled;
        if (enabled) {
            tip(widget, tipWhenEnabled);
        } else {
            tip(widget, why(widget.getMessage(), reasonWhenDisabled));
        }
        return widget;
    }

    /** "Action" then the grey reason on its own line. */
    public static Component why(Component action, Component reason) {
        if (reason == null) return action;
        return action.copy().append("\n").append(reason.copy().withStyle(ChatFormatting.GRAY));
    }
}
