package com.hearthstead.client.ui;

import net.minecraft.client.gui.GuiGraphics;

/** Pixel-aligned timber, parchment and painted-wood surfaces for settlement pages. */
public final class HearthPixelSurface {
    public static final int INK = 0xFF252A22;
    public static final int MUTED = 0xFF596050;
    public static final int COPPER = 0xFFBE8D58;
    public static final int LIGHT_TEXT = 0xFFF1F3E8;
    private HearthPixelSurface() { }

    public static void table(GuiGraphics g, int x, int y, HearthLayout layout) {
        HearthMaterials.frame(g, x, y, layout.width(), layout.height());
        HearthMaterials.header(g, x + 5, y + 5, layout.width() - 10, 39);
        if (layout.supplies()) {
            HearthLayout.Rect drawer = layout.drawer();
            HearthMaterials.panel(g, x + drawer.x(), y + drawer.y(), drawer.width(), drawer.height());
        } else {
            for (int index = 0; index < 5; index++) {
                HearthLayout.Rect card = layout.stat(index);
                HearthMaterials.panel(g, x + card.x(), y + card.y(), card.width(), card.height());
            }
            HearthLayout.Rect attention = layout.stat(5);
            HearthMaterials.panel(g, x + attention.x(), y + attention.y(), attention.width(), attention.height());
        }
    }

    public static void priorityNote(GuiGraphics g, int x, int y, int width, int height) {
        HearthMaterials.panel(g, x, y, width, height);
        HearthMaterials.header(g, x + 2, y + 2, width - 4, 12);
    }

    public static void note(GuiGraphics g, int x, int y, int width, int height) {
        HearthMaterials.panel(g, x, y, width, height);
    }

    public static void slot(GuiGraphics g, int x, int y) {
        HearthMaterials.slot(g, x, y);
    }

    public static void button(GuiGraphics g, int x, int y, int width, int height,
                              boolean selected, boolean hoveredOrFocused) {
        HearthMaterials.button(g, x, y, width, height, selected, false, hoveredOrFocused, true);
    }

    public static void tabButton(GuiGraphics g, int x, int y, int width, int height,
                                 boolean selected, boolean hoveredOrFocused) {
        if (selected) {
            HearthMaterials.button(g, x, y, width, height, true, false, hoveredOrFocused, true);
        } else {
            HearthMaterials.panel(g, x, y, width, height);
            if (hoveredOrFocused) g.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0x30FFFFFF);
        }
    }

    public static void actionButton(GuiGraphics g, int x, int y, int width, int height,
                                    boolean pressed, boolean hoveredOrFocused) {
        HearthMaterials.button(g, x, y, width, height, true, false, hoveredOrFocused, true);
        if (pressed) g.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0x22000000);
    }
}
