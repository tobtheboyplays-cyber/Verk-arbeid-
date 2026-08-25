package com.hearthstead.client.screen;

import com.hearthstead.client.ui.HsButton;
import com.hearthstead.client.ui.HsUi;
import com.hearthstead.client.ui.HsUiTokens;
import com.hearthstead.network.StorageIndexPayload;
import com.hearthstead.network.StorageRequestPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Read-only view of the settlement's stores — the Tingboka's storage tab
 * in embryo.
 *
 * <p>Read-only on purpose: chests are the truth (D-A2a-2), and a UI that
 * could move items would become a second source of truth the moment it
 * disagreed with the world. This shows; the couriers do. Every number on
 * screen is redrawn straight from the latest {@link StorageIndexPayload}
 * with no client-side arithmetic of its own — {@link #update} simply swaps
 * the snapshot this renders from, so what the player sees can never drift
 * from what the last server answer actually said.
 *
 * <p>Built on the {@code HsUi} nine-slice kit (see the {@code minecraft-ui}
 * skill and {@link PlaqueScreen}, its sibling screen) rather than the flat
 * fills this used to draw with.
 */
public class StorageScreen extends Screen {

    private static final int PANEL_W = 200;
    private static final int COLS = 6;
    /** One item cell: an 18x18 slot plus a 2px gutter. */
    private static final int CELL = 20;
    /** One grid row: a cell plus room for the count text under it. */
    private static final int ROW_STEP = CELL + 14;
    private static final int ROWS_SHOWN =
        (StorageIndexPayload.MAX_LISTED + COLS - 1) / COLS;

    private static final int TITLE_Y = 12;
    private static final int DIVIDER_TOP_Y = 26;
    private static final int NAME_Y = 34;
    private static final int SUMMARY_Y = 46;
    private static final int GRID_TOP = 60;
    private static final int GRID_H = ROWS_SHOWN * ROW_STEP - 14;
    private static final int DIVIDER_BOTTOM_Y = GRID_TOP + GRID_H + 6;
    private static final int BUTTON_Y = DIVIDER_BOTTOM_Y + 8;
    private static final int BUTTON_W = 76;
    private static final int PANEL_H = BUTTON_Y + HsUiTokens.BUTTON_H + HsUiTokens.PAD;

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

        // Both buttons work in every state the screen can be in (D-014): a
        // player standing outside a settlement can still ask again the
        // moment they step inside one, and closing never depends on data.
        addRenderableWidget(HsButton.normal(left + HsUiTokens.PAD, top + BUTTON_Y,
            BUTTON_W, HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.storage.refresh"),
            () -> PacketDistributor.sendToServer(new StorageRequestPayload())));
        addRenderableWidget(HsButton.normal(
            left + PANEL_W - HsUiTokens.PAD - BUTTON_W, top + BUTTON_Y,
            BUTTON_W, HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.storage.close"), this::onClose));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        HsUi.window(g, left, top, PANEL_W, PANEL_H);
        HsUi.centred(g, font, title(), left + PANEL_W / 2, top + TITLE_Y,
            HsUiTokens.TEXT_STRONG);
        HsUi.divider(g, left + HsUiTokens.PAD, top + DIVIDER_TOP_Y,
            PANEL_W - 2 * HsUiTokens.PAD);

        if (data == null) {
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }

        int textBox = PANEL_W - 2 * HsUiTokens.PAD;
        // -1 distinctTypes is the server's "this settlement has no valid
        // warehouse" marker; an empty name means "you are not in a
        // settlement at all". Both are real states a player can be in, so
        // both get a real message rather than an empty grid.
        if (data.settlementName().isEmpty() && data.distinctTypes() != -1) {
            HsUi.labelIn(g, font, Component.translatable("hearthstead.storage.no_settlement"),
                left + HsUiTokens.PAD, top + NAME_Y, textBox, HsUiTokens.TEXT_MUTED);
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }
        HsUi.labelIn(g, font, Component.literal(data.settlementName()),
            left + HsUiTokens.PAD, top + NAME_Y, textBox, HsUiTokens.ACCENT);
        if (data.distinctTypes() == -1) {
            HsUi.labelIn(g, font, Component.translatable("hearthstead.storage.no_warehouse"),
                left + HsUiTokens.PAD, top + SUMMARY_Y, textBox, HsUiTokens.TEXT_MUTED);
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }

        HsUi.labelIn(g, font, Component.translatable("hearthstead.storage.summary",
                data.distinctTypes(), data.totalItems()),
            left + HsUiTokens.PAD, top + SUMMARY_Y, textBox, HsUiTokens.TEXT_MUTED);
        HsUi.divider(g, left + HsUiTokens.PAD, top + DIVIDER_BOTTOM_Y - 6,
            PANEL_W - 2 * HsUiTokens.PAD);

        if (data.top().isEmpty()) {
            HsUi.labelIn(g, font, Component.translatable("hearthstead.storage.empty"),
                left + HsUiTokens.PAD, top + GRID_TOP + 4, textBox, HsUiTokens.TEXT_MUTED);
        } else {
            drawGrid(g, mouseX, mouseY);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void drawGrid(GuiGraphics g, int mouseX, int mouseY) {
        ItemStack hovered = ItemStack.EMPTY;
        for (int i = 0; i < data.top().size(); i++) {
            ItemStack stack = data.top().get(i);
            int x = left + HsUiTokens.PAD + (i % COLS) * CELL;
            int y = top + GRID_TOP + (i / COLS) * ROW_STEP;
            HsUi.slot(g, x, y);
            g.renderItem(stack, x + 1, y + 1);
            // The count is a settlement-wide total, which can exceed a
            // stack, so it is drawn as text rather than as the item's own
            // stack-size overlay (which caps at 99 and would mislead).
            String count = stack.getCount() >= 1000
                ? (stack.getCount() / 1000) + "k"
                : String.valueOf(stack.getCount());
            g.drawString(font, count, x + 1, y + 20, HsUiTokens.TEXT, false);
            if (mouseX >= x && mouseX < x + HsUiTokens.SLOT
                && mouseY >= y && mouseY < y + HsUiTokens.SLOT) {
                hovered = stack;
            }
        }
        if (!hovered.isEmpty()) {
            // The exact real name and vanilla's own count line -- useful
            // beyond the "k"-abbreviated total drawn under the slot.
            g.renderTooltip(font, hovered, mouseX, mouseY);
        }
    }

    private Component title() {
        return Component.translatable("hearthstead.storage.title");
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
