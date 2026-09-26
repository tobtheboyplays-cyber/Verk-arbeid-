package com.hearthstead.client.revive;

import com.hearthstead.Hearthstead;
import com.hearthstead.network.ReviveStatePayload;
import com.hearthstead.revive.ReviveRules;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.joml.Matrix4f;

/**
 * Downed/revive HUD and world markers.
 * <ul>
 *   <li>Downed (self): a quiet parchment-dark panel above the hotbar - "You are
 *       down", the bleed-out bar and seconds, and who is reviving / dragging.</li>
 *   <li>Being revived or reviving: a progress ring around the crosshair on
 *       both screens.</li>
 *   <li>Teammates: a see-through marker over each downed ally within 64 blocks
 *       (heart + name + seconds left), readable through walls.</li>
 * </ul>
 * Hidden with F1 and while a screen is open.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class DownedHud {
    private static final int BLOOD = 0xFFB3261E;
    private static final int BLOOD_DARK = 0xFF4A0F0C;
    // Shared HUD style (walnut plate, text on wood); blood red stays the functional bleed colour.
    private static final int GOLD = com.hearthstead.client.ui2.Ui2Hud.KEY;
    private static final int GREEN = com.hearthstead.client.ui2.Ui2Hud.GOOD;
    private static final int TEXT = com.hearthstead.client.ui2.Ui2Hud.TEXT;
    private static final int MUTED = com.hearthstead.client.ui2.Ui2Hud.MUTED;

    private DownedHud() {
    }

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CROSSHAIR, Hearthstead.id("revive_hud"), DownedHud::render);
    }

    // ------------------------------------------------------------------ HUD

    private static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null || mc.screen != null) {
            return;
        }
        Font font = mc.font;
        int w = g.guiWidth();
        int h = g.guiHeight();
        int cx = w / 2;
        int cy = h / 2;

        DownedClient.View self = DownedClient.localDowned();
        if (self != null) {
            renderSelf(g, font, mc, self, cx, h);
            if (self.beingRevived()) {
                ring(g, cx, cy, 11.0F, 14.0F, self.reviveFraction(), GREEN);
            }
            return;
        }
        DownedClient.View reviving = DownedClient.localReviving();
        if (reviving != null) {
            ring(g, cx, cy, 11.0F, 14.0F, reviving.reviveFraction(), GREEN);
            Component line = Component.translatable("hearthstead.revive.hud.reviving",
                reviving.entry.name());
            g.drawCenteredString(font, line, cx, cy + 20, TEXT);
            return;
        }
        DownedClient.View dragging = DownedClient.localDragging();
        if (dragging != null) {
            Component line = Component.translatable("hearthstead.revive.hud.dragging",
                dragging.entry.name(), Component.keybind("key.sneak"), Component.keybind("key.use"));
            g.drawCenteredString(font, line, cx, cy + 20, MUTED);
        }
    }

    private static void renderSelf(GuiGraphics g, Font font, Minecraft mc, DownedClient.View v,
                                   int cx, int h) {
        int panelW = 182;
        int panelH = 38;
        int x0 = cx - panelW / 2;
        int y0 = h - 59 - panelH - 8;
        com.hearthstead.client.ui2.Ui2Hud.plate(g, x0, y0, panelW, panelH);

        Component title = Component.translatable("hearthstead.revive.hud.down");
        g.drawString(font, title, cx - font.width(title) / 2, y0 + 4, com.hearthstead.client.ui2.Ui2Hud.WARN, false);

        int barX = x0 + 8;
        int barW = panelW - 16;
        int barY = y0 + 16;
        g.fill(barX, barY, barX + barW, barY + 4, BLOOD_DARK);
        float frac = v.bleedFraction();
        // Pulse the bar with the heartbeat once the last quarter is reached.
        int fillColor = BLOOD;
        if (frac < 0.25F && (mc.level.getGameTime() / 5L) % 2L == 0L) {
            fillColor = 0xFFE0463A;
        }
        g.fill(barX, barY, barX + Math.round(barW * frac), barY + 4, fillColor);

        int secs = Mth.ceil(v.bleedLeft / (float) ReviveRules.TPS);
        Component status;
        int color = MUTED;
        if (v.beingRevived()) {
            Entity reviver = mc.level.getEntity(v.entry.reviverId());
            status = Component.translatable("hearthstead.revive.hud.being_revived",
                reviver == null ? Component.literal("?") : reviver.getDisplayName());
            color = GREEN;
        } else if (v.entry.draggerId() != ReviveStatePayload.NONE) {
            Entity dragger = mc.level.getEntity(v.entry.draggerId());
            status = Component.translatable("hearthstead.revive.hud.dragged",
                dragger == null ? Component.literal("?") : dragger.getDisplayName());
            color = GOLD;
        } else {
            status = Component.translatable("hearthstead.revive.hud.bleed", secs);
        }
        g.drawString(font, status, cx - font.width(status) / 2, y0 + 25, color, false);
    }

    /** A smooth progress ring (triangle strip) starting at 12 o'clock, clockwise. */
    private static void ring(GuiGraphics g, float cx, float cy, float inner, float outer,
                             float fraction, int color) {
        g.flush();
        Matrix4f m = g.pose().last().pose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        int segments = 48;
        drawArc(m, cx, cy, inner, outer, 0.0F, 1.0F, segments, 0x80000000);
        if (fraction > 0.0F) {
            drawArc(m, cx, cy, inner, outer, 0.0F, Mth.clamp(fraction, 0.0F, 1.0F), segments, color);
        }
        RenderSystem.disableBlend();
    }

    private static void drawArc(Matrix4f m, float cx, float cy, float inner, float outer,
                                float from, float to, int segments, int color) {
        int steps = Math.max(2, Math.round(segments * (to - from)));
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_STRIP,
            DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i <= steps; i++) {
            float t = from + (to - from) * i / steps;
            float a = (float) (t * Math.PI * 2.0D - Math.PI / 2.0D);
            float cos = Mth.cos(a);
            float sin = Mth.sin(a);
            b.addVertex(m, cx + cos * outer, cy + sin * outer, 0.0F).setColor(color);
            b.addVertex(m, cx + cos * inner, cy + sin * inner, 0.0F).setColor(color);
        }
        BufferUploader.drawWithShader(b.buildOrThrow());
    }

    // ------------------------------------------------------------------ world markers

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null || mc.level == null
            || DownedClient.views().isEmpty()) {
            return;
        }
        PoseStack pose = event.getPoseStack();
        if (pose == null) {
            return;
        }
        Vec3 cam = event.getCamera().getPosition();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        Font font = mc.font;
        for (DownedClient.View v : DownedClient.views()) {
            if (v.entry.entityId() == mc.player.getId()) {
                continue;
            }
            Entity entity = mc.level.getEntity(v.entry.entityId());
            Vec3 at = entity != null ? entity.getPosition(partial)
                : new Vec3(v.entry.x(), v.entry.y(), v.entry.z());
            at = at.add(0.0D, 1.0D, 0.0D);
            double dist = at.distanceTo(cam);
            if (dist > ReviveRules.MARKER_RANGE) {
                continue;
            }
            int secs = Mth.ceil(v.bleedLeft / (float) ReviveRules.TPS);
            Component label = v.beingRevived()
                ? Component.translatable("hearthstead.revive.marker.reviving", v.entry.name(),
                    Math.round(v.reviveFraction() * 100.0F))
                : Component.translatable("hearthstead.revive.marker", v.entry.name(), secs);
            int color = v.beingRevived() ? GREEN : v.bleedFraction() < 0.25F ? BLOOD : GOLD;
            float scale = 0.025F * (float) Math.max(1.0D, dist / 8.0D);

            pose.pushPose();
            pose.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
            pose.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
            pose.scale(scale, -scale, scale);
            Matrix4f matrix = pose.last().pose();
            float x = -font.width(label) / 2.0F;
            font.drawInBatch(label, x, 0.0F, color, false, matrix, buffers,
                Font.DisplayMode.SEE_THROUGH, 0x60000000, 0xF000F0);
            font.drawInBatch(label, x, 0.0F, color, false, matrix, buffers,
                Font.DisplayMode.NORMAL, 0, 0xF000F0);
            pose.popPose();
        }
        buffers.endBatch();
    }
}
