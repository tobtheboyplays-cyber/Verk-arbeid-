package com.hearthstead.client.builder;

import com.hearthstead.Hearthstead;
import com.hearthstead.network.BuilderPayloads;
import com.hearthstead.settlement.builder.BuildStatus;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * The small floating line over an active construction site (owner: "we need
 * a way to see what he's missing"): what the site is doing, and in red what
 * it waits for -- readable from the street, compact up close, and only for
 * sites within {@link #RANGE} blocks. Same data as the Sites tab.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class BuildSiteLabels {

    public static final double RANGE = 48.0D;
    private static final float SCALE = 0.022F;

    private BuildSiteLabels() {
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || BuildSitesClient.sites().isEmpty()) {
            return;
        }
        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        Font font = mc.font;
        for (BuilderPayloads.Site site : BuildSitesClient.sites()) {
            if (site.order() < 0) {
                continue; // finished or stopped
            }
            double x = (site.minX() + site.maxX() + 1) / 2.0D;
            double y = site.maxY() + 1.6D;
            double z = (site.minZ() + site.maxZ() + 1) / 2.0D;
            if (cam.distanceToSqr(x, y, z) > RANGE * RANGE) {
                continue;
            }
            BuildStatus status = BuildStatus.byOrdinal(site.status());
            boolean bad = status == BuildStatus.WAITING_FOR || status == BuildStatus.NEEDS_PLAYER
                || status == BuildStatus.BLOCKED_PLAYER_BLOCK || status == BuildStatus.NO_BUILDER;
            Component head = Component.literal(site.label() + "  " + Math.round(site.progress() * 100) + "%");
            Component line = BuildSitesClient.status(site);
            pose.pushPose();
            pose.translate(x - cam.x, y - cam.y, z - cam.z);
            pose.mulPose(event.getCamera().rotation());
            pose.scale(SCALE, -SCALE, SCALE);
            Matrix4f matrix = pose.last().pose();
            int background = (int) (mc.options.getBackgroundOpacity(0.35F) * 255.0F) << 24;
            float hw = -font.width(head) / 2.0F;
            font.drawInBatch(head, hw, 0, 0xFFF1E5CB, false, matrix, buffers, Font.DisplayMode.SEE_THROUGH,
                background, LightTexture.FULL_BRIGHT);
            float lw = -font.width(line) / 2.0F;
            font.drawInBatch(line, lw, 10, bad ? 0xFFFF8A7A : 0xFFD9CFB8, false, matrix, buffers,
                Font.DisplayMode.SEE_THROUGH, background, LightTexture.FULL_BRIGHT);
            pose.popPose();
        }
        buffers.endBatch();
    }
}
