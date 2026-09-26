package com.hearthstead.client.screen;

import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Presentation only, created after an exact winner receipt and client slot match. */
final class BlessingRewardAnimation {
    private static final int SPIN_TICKS = 32;
    private static final int FLIGHT_TICKS = 16;
    private static final int TOTAL_TICKS = 54;
    private final ItemStack reward;
    private final int slot;
    private int age;
    private boolean destinationLost;

    BlessingRewardAnimation(ItemStack exactReward, int destinationSlot) {
        if (exactReward.isEmpty() || !(destinationSlot >= 0 && destinationSlot <= 35
                || destinationSlot == 40)) {
            throw new IllegalArgumentException("Reward animation requires an actual inventory destination");
        }
        reward = exactReward.copy();
        slot = destinationSlot;
    }

    void tick(Player player) {
        // Never redirect a spent reward to whichever hotbar slot is selected now.
        // If the item moves, finish the celebration without inventing a landing.
        if (player == null || !ItemStack.isSameItemSameComponents(
                reward, player.getInventory().getItem(slot))) {
            destinationLost = true;
        }
        age = Math.min(TOTAL_TICKS, age + 1);
    }

    int elapsedTicks() { return age; }

    boolean finished() {
        return age >= TOTAL_TICKS;
    }

    void render(GuiGraphics graphics, Font font, Player player,
                int width, int height, float partialTick) {
        float time = Math.min(TOTAL_TICKS, age + Math.clamp(partialTick, 0.0F, 1.0F));
        float spin = Math.clamp(time / SPIN_TICKS, 0.0F, 1.0F);
        float travel = destinationLost ? 0.0F
            : smooth((time - SPIN_TICKS) / FLIGHT_TICKS);
        float centerX = width * 0.5F;
        float centerY = height * 0.44F;
        float targetX = centerX;
        float targetY = height - 32.0F;
        if (slot < 9) {
            targetX = width / 2 - 80 + slot * 20;
            targetY = height - 11;
        } else if (slot == 40 && player != null) {
            targetX = width / 2 + (player.getMainArm() == HumanoidArm.RIGHT ? -109 : 109);
            targetY = height - 11;
        } else if (!destinationLost && time >= SPIN_TICKS - 4) {
            // Main-inventory slots have no visible world HUD position. Use an
            // explicit inventory receipt, never pretend they landed in a hotbar slot.
            drawInventoryReceipt(graphics, font, (int) centerX, height - 32);
        }
        float x = centerX + (targetX - centerX) * travel;
        float y = centerY + (targetY - centerY) * travel
            - (float) Math.sin(travel * Math.PI) * Math.min(30, height * 0.12F);
        float anticipation = (float) Math.sin(spin * Math.PI);
        float scale = (3.0F + anticipation * 0.55F) * (1.0F - travel) + travel;
        float spinEase = 1.0F - (float) Math.pow(1.0F - spin, 3.0D);
        float yaw = spinEase * 720.0F;
        float tilt = (float) Math.sin(spin * Math.PI * 2.0D) * 12.0F * (1.0F - travel);

        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 300);
        graphics.pose().mulPose(Axis.ZP.rotationDegrees(tilt));
        graphics.pose().mulPose(Axis.YP.rotationDegrees(yaw));
        graphics.pose().scale(scale, scale, scale);
        graphics.renderItem(reward, -8, -8);
        graphics.pose().popPose();

        if (time < SPIN_TICKS) {
            drawSparks(graphics, centerX, centerY, spin);
        }
        if (destinationLost && time >= SPIN_TICKS) {
            graphics.drawCenteredString(font,
                Component.translatable("hearthstead.blessing.animation.secured"),
                width / 2, (int) centerY + 35, 0xFFF1D5A0);
        } else if (time >= SPIN_TICKS + FLIGHT_TICKS && slot < 9) {
            int alpha = (int) ((1.0F - (time - SPIN_TICKS - FLIGHT_TICKS) / 6.0F) * 180);
            int color = Math.clamp(alpha, 0, 180) << 24 | 0xF4D88C;
            int tx = (int) targetX, ty = (int) targetY;
            graphics.fill(tx - 10, ty - 10, tx + 10, ty - 9, color);
            graphics.fill(tx - 10, ty + 9, tx + 10, ty + 10, color);
            graphics.fill(tx - 10, ty - 9, tx - 9, ty + 9, color);
            graphics.fill(tx + 9, ty - 9, tx + 10, ty + 9, color);
        }
    }

    private static void drawSparks(GuiGraphics graphics, float x, float y, float progress) {
        int alpha = (int) (Math.sin(progress * Math.PI) * 190);
        int color = Math.clamp(alpha, 0, 190) << 24 | 0xF4D88C;
        float radius = 20.0F + progress * 31.0F;
        for (int index = 0; index < 8; index++) {
            double angle = index * Math.PI / 4.0D + progress * 0.7D;
            int px = (int) (x + Math.cos(angle) * radius);
            int py = (int) (y + Math.sin(angle) * radius * 0.75D);
            graphics.fill(px, py, px + 2, py + 2, color);
        }
    }

    private static void drawInventoryReceipt(GuiGraphics graphics, Font font, int x, int y) {
        graphics.fill(x - 12, y - 12, x + 12, y + 12, 0xE52A2927);
        graphics.fill(x - 12, y - 12, x + 12, y - 11, 0xFFBAA57C);
        graphics.fill(x - 12, y + 11, x + 12, y + 12, 0xFFBAA57C);
        graphics.drawCenteredString(font,
            Component.translatable("hearthstead.blessing.animation.inventory"),
            x, y + 15, 0xFFE8DABF);
    }

    private static float smooth(float value) {
        float bounded = Math.clamp(value, 0.0F, 1.0F);
        return bounded * bounded * (3.0F - 2.0F * bounded);
    }
}
