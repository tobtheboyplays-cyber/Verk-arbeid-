package com.hearthstead.client.screen;

import com.hearthstead.network.StorageIndexPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Read-only view of the settlement's stores — the Tingboka's storage tab
 * in embryo.
 *
 * <p>Read-only on purpose: chests are the truth (D-A2a-2), and a UI that
 * could move items would become a second source of truth the moment it
 * disagreed with the world. This shows; the couriers do.
 */
public class StorageScreen extends Screen {

    private static final int PANEL_W = 216;
    private static final int PANEL_H = 168;
    private static final int COLS = 6;
    private static final int CELL = 20;

    private static final int PANEL_BG = 0xF0231A14;   // dark oak
    private static final int PANEL_EDGE = 0xFF4A3726; // iron-brown rim
    private static final int INK = 0xFFE8DCC8;
    private static final int INK_DIM = 0xFF9C8B72;

    private StorageIndexPayload data;
    private int left;
    private int top;

    public StorageScreen(StorageIndexPayload data) {
        super(Component.translatable("hearthstead.storage.title"));
        this.data = data;
    }

    /** A fresh snapshot arrived while the screen is open. */
    public void update(StorageIndexPayload snapshot) {
        this.data = snapshot;
    }

    @Override
    protected void init() {
        left = (width - PANEL_W) / 2;
        top = (height - PANEL_H) / 2;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        g.fill(left - 1, top - 1, left + PANEL_W + 1, top + PANEL_H + 1, PANEL_EDGE);
        g.fill(left, top, left + PANEL_W, top + PANEL_H, PANEL_BG);

        g.drawString(font, Component.translatable("hearthstead.storage.title"),
            left + 10, top + 10, INK, false);

        // -1 distinctTypes is the server's "this settlement has no valid
        // warehouse" marker; an empty name means "you are not in a
        // settlement at all". Both are real states a player can be in, so
        // both get a real message rather than an empty grid.
        if (data.warehouseName().isEmpty() && data.distinctTypes() != -1) {
            g.drawString(font, Component.translatable("hearthstead.storage.no_settlement"),
                left + 10, top + 30, INK_DIM, false);
            return;
        }
        if (data.distinctTypes() == -1) {
            g.drawString(font, Component.translatable("hearthstead.storage.no_warehouse"),
                left + 10, top + 30, INK_DIM, false);
            return;
        }

        g.drawString(font, Component.translatable("hearthstead.storage.summary",
                data.distinctTypes(), data.totalItems()),
            left + 10, top + 24, INK_DIM, false);

        if (data.top().isEmpty()) {
            g.drawString(font, Component.translatable("hearthstead.storage.empty"),
                left + 10, top + 44, INK_DIM, false);
            return;
        }

        int gridTop = top + 42;
        for (int i = 0; i < data.top().size(); i++) {
            ItemStack stack = data.top().get(i);
            int x = left + 12 + (i % COLS) * CELL;
            int y = gridTop + (i / COLS) * (CELL + 12);
            g.fill(x - 2, y - 2, x + 18, y + 18, PANEL_EDGE);
            g.renderItem(stack, x, y);
            // The count is a settlement-wide total, which can exceed a
            // stack, so it is drawn as text rather than as the item's own
            // stack-size overlay (which caps at 99 and would mislead).
            String count = stack.getCount() >= 1000
                ? (stack.getCount() / 1000) + "k"
                : String.valueOf(stack.getCount());
            g.drawString(font, count, x, y + 20, INK, false);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
