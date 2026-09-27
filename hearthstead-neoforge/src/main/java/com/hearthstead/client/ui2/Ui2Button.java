package com.hearthstead.client.ui2;

import com.hearthstead.client.ui.HsButton;
import com.hearthstead.client.ui.HsUi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * UI2 button on the shared {@link HsButton} action/narration/tooltip contract.
 *
 * <ul>
 *   <li>PRIMARY: forest face, 1px darker border, 1px top highlight. One per view.</li>
 *   <li>SECONDARY: a real framed button (owner, 26 Sep: "underlined text buttons
 *   look cheap"): brass rim, dark walnut face, parchment label, lighter on
 *   hover, darker when pressed. Same family as the Banner primary.</li>
 *   <li>DANGER: burgundy face with the same construction as PRIMARY.</li>
 *   <li>DANGER_TEXT: a SECONDARY text button in danger red. Row and footer
 *   actions such as Fire and Dismiss use it, so a destructive action sits at
 *   the same size and weight as its neighbours (Summon, Locate) and is told
 *   apart by colour, not by a large red box (owner, 26 Sep).</li>
 *   <li>Disabled (any variant): muted face or text plus a padlock glyph.</li>
 * </ul>
 */
public class Ui2Button extends HsButton {
    public enum Variant { PRIMARY, SECONDARY, DANGER, BANNER, DANGER_TEXT }

    private final Variant variant;
    private final HsUi.FittedLabelCache labelCache = new HsUi.FittedLabelCache();
    private final Ui2Serif.Text serifLabel = new Ui2Serif.Text(Ui2Serif.Size.HEADING);
    private net.minecraft.world.item.ItemStack icon = net.minecraft.world.item.ItemStack.EMPTY;

    /** Banner primaries may carry an item icon on the left (they always show a chevron). */
    public Ui2Button withIcon(net.minecraft.world.item.ItemStack stack) {
        icon = stack == null ? net.minecraft.world.item.ItemStack.EMPTY : stack;
        return this;
    }

    public Ui2Button(int x, int y, int w, int h, Component label, Variant variant, Runnable onPress) {
        super(x, y, w, h, label, variant == Variant.DANGER || variant == Variant.DANGER_TEXT
            ? Kind.DANGER : Kind.NORMAL, onPress);
        this.variant = variant;
    }

    public static Ui2Button primary(int x, int y, int w, int h, Component label, Runnable onPress) {
        return new Ui2Button(x, y, w, h, label, Variant.PRIMARY, onPress);
    }

    public static Ui2Button secondary(int x, int y, int w, int h, Component label, Runnable onPress) {
        return new Ui2Button(x, y, w, h, label, Variant.SECONDARY, onPress);
    }

    public static Ui2Button danger(int x, int y, int w, int h, Component label, Runnable onPress) {
        return new Ui2Button(x, y, w, h, label, Variant.DANGER, onPress);
    }

    /** Destructive text action (Fire, Dismiss): secondary size, danger ink. */
    public static Ui2Button dangerText(int x, int y, int w, int h, Component label, Runnable onPress) {
        return new Ui2Button(x, y, w, h, label, Variant.DANGER_TEXT, onPress);
    }

    /** Banner screen primary: burgundy face, same construction as PRIMARY. One per view. */
    public static Ui2Button banner(int x, int y, int w, int h, Component label, Runnable onPress) {
        return new Ui2Button(x, y, w, h, label, Variant.BANNER, onPress);
    }

    /** Natural width of a secondary (framed) button for {@code label}. */
    public static int textWidth(Font font, Component label) {
        return font.width(label) + 10;
    }

    /** Natural width of a filled button for {@code label}. */
    public static int filledWidth(Font font, Component label) {
        return font.width(label) + 16;
    }

    public Variant variant() {
        return variant;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        boolean lit = active && isHoveredOrFocused();
        float hover = hoverProgress(lit);
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        // Text buttons are sized by textWidth (label + 10): never ellipsize their own label.
        // Disabled framed buttons show the padlock only when it fits beside the whole
        // label; otherwise the muted face alone says "disabled" (the tooltip says why).
        boolean text = variant == Variant.SECONDARY || variant == Variant.DANGER_TEXT;
        int lockSpace = active ? 0 : text && font.width(getMessage()) + 8 > w - 6 ? 0 : 8;
        int inner = Math.max(1, w - (text ? 6 : 8) - lockSpace);
        HsUi.FittedLabel label = labelCache.fit(font, getMessage(), inner,
            mc.getLanguageManager().getSelected());
        int textW = Math.min(inner, label.width());
        int contentW = textW + lockSpace;
        int textX = x + (w - contentW) / 2 + lockSpace;
        int textY = y + (h - 8) / 2 + labelPressOffset();

        if (text) {
            renderFramed(g, font, label, textX, textY, textW, x, y, w, h, hover);
            return;
        }

        if (variant == Variant.BANNER) {
            renderBanner(g, font, hover);
            return;
        }
        boolean danger = variant == Variant.DANGER;
        boolean banner = false;
        int face = !active ? Ui2Palette.DISABLED_FILL
            : banner ? Ui2Palette.BURGUNDY : danger ? Ui2Palette.DANGER : Ui2Palette.FOREST;
        int border = !active ? Ui2Palette.DISABLED_BORDER
            : banner ? Ui2Palette.BURGUNDY_DARK : danger ? Ui2Palette.DANGER_DARK : Ui2Palette.FOREST_DARK;
        int highlight = !active ? Ui2Palette.PAPER
            : banner ? Ui2Palette.BURGUNDY_HIGHLIGHT
            : danger ? Ui2Palette.DANGER_HIGHLIGHT : Ui2Palette.FOREST_HIGHLIGHT;
        g.fill(x, y, x + w, y + h, border);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, face);
        if (active && hover > 0.01F) {
            int alpha = Math.round(0x30 * hover);
            g.fill(x + 1, y + 1, x + w - 1, y + h - 1, (alpha << 24) | 0xFFFFFF);
        }
        boolean pressed = active && isHovered() && com.hearthstead.client.ui.HsMotion.mouseHeld();
        if (!pressed) g.fill(x + 1, y + 1, x + w - 1, y + 2, highlight);
        else g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0x22000000);
        int ink = active ? (banner ? Ui2Palette.ON_BURGUNDY : Ui2Palette.ON_ACCENT) : Ui2Palette.INK_DISABLED;
        if (!active) Ui2Surface.lockGlyph(g, textX - 8, textY, Ui2Palette.INK_DISABLED);
        g.drawString(font, label.text(), textX, textY, ink, false);
        if (active && isFocused() && !isHovered()) Ui2Surface.focus(g, x, y + 1, w, h);
    }

    /** Brass rim (bright on hover) around a walnut or deep-red face; parchment label. */
    private static final int RIM = 0xFFB08A4E;
    private static final int RIM_LIT = 0xFFE1C58A;
    private static final int RIM_DARK = 0xFF2A1A10;
    private static final int FACE = 0xFF4B3323;
    private static final int FACE_LIT = 0xFF5E4130;
    private static final int FACE_PRESSED = 0xFF38261A;
    private static final int DANGER_FACE = 0xFF7A2A24;
    private static final int DANGER_FACE_LIT = 0xFF923730;
    private static final int DANGER_FACE_PRESSED = 0xFF5C1E1A;
    private static final int LABEL = 0xFFF4E9D8;

    private void renderFramed(GuiGraphics g, Font font, HsUi.FittedLabel label, int textX, int textY, int textW,
                              int x, int y, int w, int h, float hover) {
        boolean danger = variant == Variant.DANGER_TEXT;
        boolean pressed = active && isHovered() && com.hearthstead.client.ui.HsMotion.mouseHeld();
        int rim = !active ? Ui2Palette.DISABLED_BORDER : hover > 0.5F ? RIM_LIT : RIM;
        int face = !active ? Ui2Palette.DISABLED_FILL
            : pressed ? (danger ? DANGER_FACE_PRESSED : FACE_PRESSED)
            : hover > 0.01F ? (danger ? DANGER_FACE_LIT : FACE_LIT) : (danger ? DANGER_FACE : FACE);
        boolean roomy = h >= 14;
        int o = roomy ? 1 : 0;
        if (roomy) {
            // Dark outer line, then the brass rim.
            g.fill(x, y, x + w, y + h, active ? RIM_DARK : Ui2Palette.DISABLED_BORDER);
        }
        g.fill(x + o, y + o, x + w - o, y + h - o, rim);
        if (h < 12) {
            // Too short for a full rim around 8 px text: brass sides only.
            g.fill(x + 1, y, x + w - 1, y + h, face);
        } else {
            g.fill(x + o + 1, y + o + 1, x + w - o - 1, y + h - o - 1, face);
        }
        if (active && !pressed) {
            // A one-pixel top light on the face, like the Banner primary.
            g.fill(x + o + 1, y + o + 1, x + w - o - 1, y + o + 2, (0x30 << 24) | 0xFFFFFF);
        }
        int ink = !active ? Ui2Palette.INK_DISABLED : LABEL;
        int ty = textY;
        if (!active && textX - 8 >= x + 2) {
            Ui2Surface.lockGlyph(g, textX - 8, ty, Ui2Palette.INK_DISABLED);
        }
        g.drawString(font, label.text(), textX, ty, ink, false);
        if (active && isFocused() && !isHovered()) Ui2Surface.focus(g, x, y, w, h);
    }

    /**
     * Banner primary: burgundy plate with a light gold rim, optional item icon,
     * a serif small-caps label and a chevron. Disabled keeps the shape, mutes
     * the face and shows the padlock (the tooltip says why).
     */
    private void renderBanner(GuiGraphics g, Font font, float hover) {
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        boolean pressed = active && isHovered() && com.hearthstead.client.ui.HsMotion.mouseHeld();
        int face = active ? Ui2Palette.BURGUNDY : Ui2Palette.DISABLED_FILL;
        g.fill(x, y, x + w, y + h, active ? Ui2Palette.BURGUNDY_DARK : Ui2Palette.DISABLED_BORDER);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, active ? 0xFFC9A46A : Ui2Palette.DISABLED_BORDER);
        g.fill(x + 2, y + 2, x + w - 2, y + h - 2, face);
        if (active && hover > 0.01F) {
            g.fill(x + 2, y + 2, x + w - 2, y + h - 2, (Math.round(0x28 * hover) << 24) | 0xFFFFFF);
        }
        if (!pressed && active) g.fill(x + 2, y + 2, x + w - 2, y + 3, Ui2Palette.BURGUNDY_HIGHLIGHT);
        if (pressed) g.fill(x + 2, y + 2, x + w - 2, y + h - 2, 0x22000000);
        int ink = active ? Ui2Palette.ON_BURGUNDY : Ui2Palette.INK_DISABLED;
        int left = x + 6;
        serifLabel.set(font, getMessage().getString());
        if (!icon.isEmpty() && serifLabel.width() + 18 + 22 <= w) {
            g.renderItem(icon, left, y + (h - 16) / 2);
            left += 18;
        } else if (!active) {
            Ui2Surface.lockGlyph(g, left, y + (h - 7) / 2, Ui2Palette.INK_DISABLED);
            left += 8;
        }
        int right = x + w - 10;
        int cy = y + h / 2;
        // Chevron.
        for (int i = 0; i < 3; i++) {
            g.fill(right + i, cy - 3 + i, right + i + 1, cy - 2 + i, ink);
            g.fill(right + i, cy + 2 - i, right + i + 1, cy + 3 - i, ink);
        }
        g.fill(right + 3, cy - 1, right + 4, cy + 1, ink);
        int room = right - 4 - left;
        serifLabel.fit(font, getMessage().getString(), room);
        int tx = left + Math.max(0, (room - serifLabel.width()) / 2);
        g.pose().pushPose();
        g.pose().translate(0, 0, 200);
        serifLabel.draw(g, font, tx, y + (h - 8) / 2 + labelPressOffset(), ink);
        g.pose().popPose();
        if (active && isFocused() && !isHovered()) Ui2Surface.focus(g, x, y + 1, w, h);
    }
}
