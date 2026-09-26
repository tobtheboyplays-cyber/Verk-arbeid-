package com.hearthstead.client.conversation;

import com.hearthstead.Hearthstead;
import com.hearthstead.client.motion.MotionOverrides;
import com.hearthstead.conversation.net.ConvMarkersPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import org.joml.Matrix4f;

/**
 * The in-world interaction badge (owner-approved design): a round pixel-art
 * badge billboarded above the head -- blue with a yellow "!" (talk), blue
 * with a coin (trade), crimson with crossed swords (raid parley), blue with
 * a "?" (quest). The realm map uses the same textures.
 *
 * <p>Size: {@link #pixels} -- 22 px between 12 and 40 blocks, never more
 * than 30 px up close. A gentle 2 px bob and a slow glow pulse, fullbright,
 * fading in and out. Hidden while you talk to that NPC, beyond 48 blocks,
 * without line of sight, and once you have talked (until the NPC has
 * something new to say -- the server stops sending it).
 *
 * <p>Other players see "Tobias is talking" over an NPC mid-conversation,
 * and the NPC gestures for them too.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class ConversationMarkers {
    private static final double MAX_DISTANCE = 48.0D;
    private static final ResourceLocation GLOW = Hearthstead.id("textures/gui/marker/glow.png");
    private static final Int2FloatOpenHashMap ALPHA = new Int2FloatOpenHashMap();
    private static long lastFrameNanos = System.nanoTime();

    private ConversationMarkers() {
    }

    /** Owner rule: 22 px at 12-40 blocks, never above 30 px close up, shrinking beyond 40. */
    public static float pixels(double distance) {
        double d = Math.max(0.5D, distance);
        return (float) Math.min(30.0D, 22.0D * Mth.clamp(d, 12.0D, 40.0D) / d);
    }

    private static ResourceLocation texture(int state) {
        String name = switch (state) {
            case ConvMarkersPayload.TRADE -> "trade";
            case ConvMarkersPayload.PARLEY -> "parley";
            case ConvMarkersPayload.QUEST -> "quest";
            default -> "talk";
        };
        return Hearthstead.id("textures/gui/marker/" + name + ".png");
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        for (ConvMarkersPayload.Marker marker : ConversationClient.markers().values()) {
            if (marker.state() != ConvMarkersPayload.TALKING || marker.talker().isEmpty()) continue;
            Entity npc = mc.level.getEntity(marker.entityId());
            if (npc != null) MotionOverrides.overlay(npc.getId(), "settler/village_chat", npc.tickCount);
        }
    }

    @SubscribeEvent
    public static void onRender(RenderLivingEvent.Post<?, ?> event) {
        LivingEntity entity = event.getEntity();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        long now = System.nanoTime();
        float dt = Mth.clamp((now - lastFrameNanos) / 1.0e9F, 0.0F, 0.1F);
        lastFrameNanos = now;
        ConvMarkersPayload.Marker marker = ConversationClient.marker(entity.getId());
        if (marker != null && marker.state() == ConvMarkersPayload.TALKING) {
            if (!marker.talker().isEmpty()) talkingLabel(event, entity, marker.talker());
            return;
        }
        double distance = mc.player.distanceTo(entity);
        boolean wanted = marker != null
            && !(ConversationClient.active() && ConversationClient.npcId() == entity.getId())
            && distance <= MAX_DISTANCE && mc.player.hasLineOfSight(entity);
        float alpha = ALPHA.getOrDefault(entity.getId(), 0.0F);
        alpha = Mth.clamp(alpha + (wanted ? dt * 4.0F : -dt * 4.0F), 0.0F, 1.0F);
        if (alpha <= 0.0F) {
            ALPHA.remove(entity.getId());
            return;
        }
        ALPHA.put(entity.getId(), alpha);
        int state = marker == null ? ConvMarkersPayload.AVAILABLE : marker.state();

        // World size that gives the wanted size on screen (design px are on a 720-line frame).
        float px = pixels(distance);
        double fovY = Math.toRadians(mc.options.fov().get());
        double worldPerDesignPixel = 2.0D * distance * Math.tan(fovY / 2.0D) / 720.0D;
        float size = (float) (px * worldPerDesignPixel);
        float t = (entity.tickCount + event.getPartialTick()) / 20.0F;
        float bob = (float) (Math.sin(t * 2.2F + entity.getId()) * 2.0D * worldPerDesignPixel);
        double nameLift = entity.shouldShowName() || entity.hasCustomName() ? 0.55D : 0.0D;
        double y = entity.getBbHeight() + nameLift + 0.6D + size / 2.0F;

        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(0.0D, y + bob, 0.0D);
        pose.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        Matrix4f matrix = pose.last().pose();
        MultiBufferSource buffers = event.getMultiBufferSource();
        float pulse = 0.25F + 0.2F * (0.5F + 0.5F * Mth.sin(t * 1.6F));
        quad(buffers.getBuffer(RenderType.entityTranslucentEmissive(GLOW)), matrix, size * 1.35F, alpha * pulse, 0.001F);
        quad(buffers.getBuffer(RenderType.entityTranslucentEmissive(texture(state))), matrix, size, alpha, 0.0F);
        pose.popPose();
    }

    private static void quad(VertexConsumer consumer, Matrix4f matrix, float size, float alpha, float depth) {
        float h = size / 2.0F;
        int a = Math.round(Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F);
        int light = 0xF000F0;
        // Billboard facing the camera; u flipped so the glyph reads the right way round.
        vertex(consumer, matrix, -h, -h, depth, 1.0F, 1.0F, a, light);
        vertex(consumer, matrix, h, -h, depth, 0.0F, 1.0F, a, light);
        vertex(consumer, matrix, h, h, depth, 0.0F, 0.0F, a, light);
        vertex(consumer, matrix, -h, h, depth, 1.0F, 0.0F, a, light);
    }

    private static void vertex(VertexConsumer consumer, Matrix4f matrix, float x, float y, float z, float u, float v,
                               int alpha, int light) {
        consumer.addVertex(matrix, x, y, z).setColor(255, 255, 255, alpha).setUv(u, v)
            .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setLight(light)
            .setNormal(0.0F, 0.0F, 1.0F);
    }

    private static void talkingLabel(RenderLivingEvent.Post<?, ?> event, LivingEntity entity, String talker) {
        Minecraft mc = Minecraft.getInstance();
        Component text = Component.translatable("conversation.hearthstead.talking_label", talker);
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(0.0D, entity.getBbHeight() + 0.75D, 0.0D);
        pose.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        pose.scale(0.02F, -0.02F, 0.02F);
        Font font = mc.font;
        font.drawInBatch(text, -font.width(text) / 2.0F, 0.0F, 0xFFE8DCC4, false, pose.last().pose(),
            event.getMultiBufferSource(), Font.DisplayMode.NORMAL, 0x60000000, 0xF000F0);
        pose.popPose();
    }
}
