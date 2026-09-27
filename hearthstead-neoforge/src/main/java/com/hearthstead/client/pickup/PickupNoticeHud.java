package com.hearthstead.client.pickup;

import com.hearthstead.Hearthstead;
import com.hearthstead.HearthsteadClientConfig;
import com.hearthstead.HearthsteadClientConfig.PickupCorner;
import com.hearthstead.network.PickupNoticePayload;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Pickup notice HUD: a quiet "+12 Oak Log" feed stacked from a screen corner
 * (bottom-left by default). Each row is the item icon plus its count and name
 * on a faint dark plate; identical items merge, new rows slide in from the
 * screen edge and every row fades out a few seconds after its last pickup.
 *
 * <p>Hidden with F1, while any screen is open, and in spectator mode. Turn it
 * off with {@code pickupNotices.enabled=false} in {@code hearthstead-client.toml}.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class PickupNoticeHud {
    private static final int MARGIN = 4;
    private static final int BOTTOM_LEFT_ROWS = 2;
    private static final int ROW_HEIGHT = 18;
    private static final int ROW_GAP = 2;
    private static final int PAD = 2;
    private static final int ICON_TEXT_GAP = 4;
    private static final int SLIDE_DISTANCE = 16;
    /** Max opacity of the dark plate behind a row (0-255): readable, not a frame. */
    /** Pickup rows stay lighter than other HUD plates: they pass by constantly. */
    private static final float PLATE_FADE = 0.75F;
    /** Hotbar half width plus the offhand slot; bottom rows keep clear of it. */
    private static final int HOTBAR_CLEARANCE = 91 + 29 + 4;
    private static final int MIN_TEXT_WIDTH = 48;

    private static final PickupNoticeQueue<StackKey, ItemStack> QUEUE = new PickupNoticeQueue<>();

    /** Merge key: same item with the same data components. */
    private record StackKey(ItemStack stack) {
        @Override
        public boolean equals(Object other) {
            return other instanceof StackKey key && ItemStack.isSameItemSameComponents(stack, key.stack);
        }

        @Override
        public int hashCode() {
            return ItemStack.hashItemAndComponents(stack);
        }
    }

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR, Hearthstead.id("pickup_notices"), PickupNoticeHud::render);
    }

    /** Payload handler (main thread). */
    public static void accept(PickupNoticePayload payload) {
        if (!HearthsteadClientConfig.pickupEnabled()) {
            return;
        }
        ItemStack exemplar = payload.stack().copyWithCount(1);
        QUEUE.add(new StackKey(exemplar), exemplar, payload.count(), Util.getMillis(),
            HearthsteadClientConfig.pickupMaxRows(), HearthsteadClientConfig.pickupHoldMillis());
    }

    private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        long now = Util.getMillis();
        long hold = HearthsteadClientConfig.pickupHoldMillis();
        if (minecraft.player == null || !HearthsteadClientConfig.pickupEnabled()) {
            QUEUE.clear();
            return;
        }
        QUEUE.prune(now, hold);
        if (QUEUE.isEmpty() || minecraft.options.hideGui || minecraft.screen != null
            || minecraft.player.isSpectator()) {
            return;
        }

        Font font = minecraft.font;
        PickupCorner corner = HearthsteadClientConfig.pickupCorner();
        boolean left = corner == PickupCorner.BOTTOM_LEFT || corner == PickupCorner.TOP_LEFT;
        boolean bottom = corner == PickupCorner.BOTTOM_LEFT || corner == PickupCorner.BOTTOM_RIGHT;
        int screenWidth = graphics.guiWidth();
        int screenHeight = graphics.guiHeight();
        int textBudget = textBudget(screenWidth, bottom);

        List<PickupNoticeQueue.Entry<StackKey, ItemStack>> rows = QUEUE.entries();
        int shown = Math.min(rows.size(), HearthsteadClientConfig.pickupMaxRows());
        if (bottom && left) {
            // Size check: vanilla chat sits from h-40 upward, so only the two newest
            // rows fit below it; older rows would hide behind recent chat lines.
            shown = Math.min(shown, BOTTOM_LEFT_ROWS);
        }
        for (int slot = 0; slot < shown; slot++) {
            // Newest row sits nearest the corner; older rows stack away from it.
            PickupNoticeQueue.Entry<StackKey, ItemStack> entry = rows.get(rows.size() - 1 - slot);
            float alpha = PickupNoticeQueue.alpha(entry, now, hold);
            if (alpha <= 0.02F) {
                continue;
            }
            int slide = Math.round(PickupNoticeQueue.slideRemaining(entry, now) * SLIDE_DISTANCE);
            FormattedCharSequence text = rowText(font, entry.display(), entry.count(), textBudget);
            int width = PAD + 16 + ICON_TEXT_GAP + font.width(text) + PAD + 1;
            int x = left ? MARGIN - slide : screenWidth - MARGIN - width + slide;
            int step = slot * (ROW_HEIGHT + ROW_GAP);
            int y = bottom ? screenHeight - MARGIN - ROW_HEIGHT - step : MARGIN + step;
            drawRow(graphics, font, entry.display(), text, x, y, width, alpha);
        }
    }

    private static void drawRow(GuiGraphics graphics, Font font, ItemStack stack, FormattedCharSequence text,
                                int x, int y, int width, float alpha) {
        // Shared HUD plate (walnut, gold top line), faded with the row.
        com.hearthstead.client.ui2.Ui2Hud.plate(graphics, x, y, width, ROW_HEIGHT, alpha * PLATE_FADE);

        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1F, 1F, 1F, alpha);
        graphics.renderItem(stack, x + PAD, y + 1);
        graphics.flush();
        RenderSystem.setShaderColor(1F, 1F, 1F, 1F);

        // Font treats alpha below 4 as opaque, so keep a floor while fading.
        int textAlpha = Mth.clamp(Math.round(255 * alpha), 5, 255);
        graphics.drawString(font, text, x + PAD + 16 + ICON_TEXT_GAP, y + 5,
            textAlpha << 24 | (com.hearthstead.client.ui2.Ui2Hud.TEXT & 0xFFFFFF), false);
    }

    /** "+12 Oak Log" with the name in its rarity color, ellipsized to fit. */
    private static FormattedCharSequence rowText(Font font, ItemStack stack, long count, int budget) {
        Component name = (com.hearthstead.util.CoinText.isCoin(stack)
            ? Component.literal(com.hearthstead.util.CoinText.unitName(stack, count))
            : stack.getHoverName().copy()).withStyle(stack.getRarity().getStyleModifier());
        Component full = Component.translatable("hud.hearthstead.pickup.entry", count, name);
        if (font.width(full) <= budget) {
            return full.getVisualOrderText();
        }
        Component ellipsis = Component.translatable("hud.hearthstead.pickup.ellipsis");
        FormattedText cut = font.substrByWidth(full, Math.max(0, budget - font.width(ellipsis)));
        return Language.getInstance().getVisualOrder(FormattedText.composite(cut, ellipsis));
    }

    /** Widest text a row may use without running under the hotbar. */
    private static int textBudget(int screenWidth, boolean bottom) {
        int fixed = MARGIN + PAD + 16 + ICON_TEXT_GAP + PAD + 1;
        int available = bottom ? screenWidth / 2 - HOTBAR_CLEARANCE - fixed : screenWidth / 3 - fixed;
        return Math.max(MIN_TEXT_WIDTH, available);
    }

    private PickupNoticeHud() {
    }
}
