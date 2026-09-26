package com.hearthstead.client.render;

import com.hearthstead.client.ambient.AmbientClient;
import com.hearthstead.entity.SettlerEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

/**
 * Living village: one short spoken line over a settler's head for a few
 * seconds (a greeting, the weather, a won raid). Linen plate, walnut italic
 * text, the same near-distance cap as the name plate; depth tested, so it
 * never shows through walls. Nothing is ever written to chat.
 *
 * <p>While shown it owns the space above the head (the caller then skips the
 * thought bubble for those few seconds).
 */
public final class SettlerBarkLabel {
    private static final double RANGE = 16.0D;
    private static final double HEIGHT = 1.0D;
    private static final int WRAP = 150;
    private static final long FADE_IN_MS = 180L;
    private static final long FADE_OUT_MS = 500L;
    private static final int LINEN = 0xF1E6CF;
    private static final int WALNUT = 0x5A3E2B;

    private SettlerBarkLabel() {
    }

    public static boolean render(SettlerEntity entity, PoseStack pose, MultiBufferSource buffers,
                                 int light, Font font, EntityRenderDispatcher dispatcher,
                                 boolean suppressed) {
        AmbientClient.Bark bark = AmbientClient.bark(entity.getId());
        if (bark == null || suppressed || !entity.isAlive() || entity.isInvisible()
            || Minecraft.getInstance().options.hideGui) {
            return false;
        }
        double distance = Math.sqrt(dispatcher.distanceToSqr(entity));
        if (distance > RANGE) {
            return false;
        }
        long age = Util.getMillis() - bark.startMillis();
        float fade = Mth.clamp(age / (float) FADE_IN_MS, 0.0F, 1.0F);
        fade = Math.min(fade, Mth.clamp((AmbientClient.BARK_MILLIS - age) / (float) FADE_OUT_MS, 0.0F, 1.0F));
        fade *= Mth.clamp((float) ((RANGE - distance) / 3.0D), 0.0F, 1.0F);
        if (fade <= 0.05F) {
            return true;
        }
        Component text = bark.text().copy().withStyle(ChatFormatting.ITALIC);
        List<FormattedCharSequence> lines = font.split(text, WRAP);
        if (lines.isEmpty()) {
            return false;
        }
        // A small rise as it appears, like a word leaving the mouth.
        float rise = (1.0F - Mth.clamp(age / 300.0F, 0.0F, 1.0F)) * -0.08F;
        pose.pushPose();
        pose.translate(0.0D, entity.getBbHeight() + HEIGHT + rise, 0.0D);
        pose.mulPose(dispatcher.cameraOrientation());
        float scale = 0.022F * SettlerRenderer.nearLabelScale(distance);
        pose.scale(scale, -scale, scale);
        Matrix4f matrix = pose.last().pose();
        int textColor = argb(WALNUT, fade * 255.0F);
        int plate = argb(LINEN, fade * 225.0F);
        int lineHeight = font.lineHeight + 1;
        float top = -lineHeight * lines.size();
        for (int i = 0; i < lines.size(); i++) {
            FormattedCharSequence line = lines.get(i);
            float x = -font.width(line) / 2.0F;
            font.drawInBatch(line, x, top + i * lineHeight, textColor, false, matrix, buffers,
                Font.DisplayMode.NORMAL, plate, light);
        }
        pose.popPose();
        return true;
    }

    private static int argb(int rgb, float alpha) {
        return Mth.clamp(Math.round(alpha), 0, 255) << 24 | (rgb & 0xFFFFFF);
    }
}
