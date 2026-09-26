package com.hearthstead.client.command;

import com.hearthstead.Hearthstead;
import com.hearthstead.network.FieldOrderStatePayload;
import com.hearthstead.settlement.guard.FieldOrderRules.Group;
import com.hearthstead.settlement.guard.FieldOrderRules.Kind;
import com.hearthstead.settlement.guard.FormationMath;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.List;

/**
 * The owner's "dots on the ground": while R/G is held, one disc per soldier
 * slot at the aimed spot (steel for knights, green for archers, red where a
 * soldier cannot stand), plus a chevron for the facing. After an order, and
 * while commanding, faint dots mark each soldier's assigned spot. A small
 * order icon floats over every soldier holding an order.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class CommandWorldRenderer {
    private static final int INVALID_RGB = CommandStyle.INVALID_RGB;
    private static final int ENEMY_RGB = 0xF08A3C;
    private static final long ACTIVE_DOTS_AFTER_ORDER_MS = 4000L;
    private static final double ICON_RANGE_SQR = 40.0D * 40.0D;

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.options.hideGui) return;
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer quads = buffers.getBuffer(RenderType.debugQuads());
        Matrix4f matrix = pose.last().pose();
        float pulse = 0.75F + 0.25F * (float) Math.sin(Util.getMillis() / 180.0D);

        CommandKeys.Preview preview = mc.screen == null ? CommandKeys.preview(partial) : null;
        if (preview != null && preview.target().valid()) {
            drawPreview(mc, matrix, quads, cam, preview, pulse);
        }
        FieldOrderStatePayload state = CommandClientState.state();
        boolean showActive = CommandKeys.commanding()
            || CommandClientState.millisSinceChange() < ACTIVE_DOTS_AFTER_ORDER_MS;
        if (state != null && showActive) {
            float fade = CommandKeys.commanding() ? 0.55F
                : 0.55F * (1.0F - CommandClientState.millisSinceChange() / (float) ACTIVE_DOTS_AFTER_ORDER_MS);
            for (FieldOrderStatePayload.SlotEntry slot : state.slots()) {
                Kind kind = Kind.fromWire(slot.kind()).orElse(Kind.LINE);
                if (kind == Kind.ATTACK || kind == Kind.HOLD_FIRE) continue;
                if (slot.slot().distSqr(mc.player.blockPosition()) > 64 * 64) continue;
                int rgb = !slot.reachable() ? INVALID_RGB
                    : CommandStyle.rgb(Group.fromWire(slot.group()).orElse(Group.KNIGHTS));
                disc(matrix, quads, cam, slot.slot(), 0.16F, rgb, Math.max(0.08F, fade));
            }
        }
        buffers.endBatch(RenderType.debugQuads());

        if (state != null && !state.slots().isEmpty()) {
            drawIcons(mc, pose, buffers, cam, state, partial);
        }
    }

    private static void drawPreview(Minecraft mc, Matrix4f matrix, VertexConsumer quads, Vec3 cam,
                                    CommandKeys.Preview preview, float pulse) {
        CommandAim.Target target = preview.target();
        int rgb = CommandStyle.rgb(target.group());
        if (target.kind() == Kind.ATTACK) {
            Entity enemy = mc.level.getEntity(target.enemyId());
            if (enemy == null) return;
            Vec3 at = enemy.getPosition(mc.getTimer().getGameTimeDeltaPartialTick(false));
            for (int i = 0; i < 10; i++) {
                double a = Math.PI * 2 * i / 10.0D + Util.getMillis() / 600.0D;
                discAt(matrix, quads, cam, at.x + Math.cos(a) * 1.1D, at.y + 0.04D, at.z + Math.sin(a) * 1.1D,
                    0.14F, ENEMY_RGB, 0.85F * pulse);
            }
            return;
        }
        List<CommandAim.Dot> dots = CommandAim.previewDots(mc, target, preview.width());
        float alpha = preview.soldiers() == 0 ? 0.35F : 0.8F * pulse;
        for (CommandAim.Dot dot : dots) {
            disc(matrix, quads, cam, dot.pos(), 0.3F, dot.valid() ? CommandStyle.rgb(dot.role()) : INVALID_RGB,
                alpha);
        }
        if (target.pos() != null && target.kind() != Kind.FOLLOW) {
            chevron(matrix, quads, cam, target.pos(), target.octant(), rgb, 0.85F);
        }
    }

    private static void drawIcons(Minecraft mc, PoseStack pose, MultiBufferSource.BufferSource buffers, Vec3 cam,
                                  FieldOrderStatePayload state, float partial) {
        for (FieldOrderStatePayload.SlotEntry slot : state.slots()) {
            Entity soldier = mc.level.getEntity(slot.entityId());
            if (soldier == null || soldier.distanceToSqr(mc.player) > ICON_RANGE_SQR || soldier.isInvisible()) {
                continue;
            }
            Group arm = Group.fromWire(slot.group()).orElse(Group.KNIGHTS);
            Kind kind = slot.holdFire() ? Kind.HOLD_FIRE : Kind.fromWire(slot.kind()).orElse(Kind.LINE);
            Vec3 at = soldier.getPosition(partial);
            pose.pushPose();
            pose.translate(at.x - cam.x, at.y + soldier.getBbHeight() + 0.95D - cam.y, at.z - cam.z);
            pose.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
            pose.scale(0.42F, 0.42F, 0.42F);
            mc.getItemRenderer().renderStatic(CommandStyle.icon(arm, kind), ItemDisplayContext.FIXED,
                LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, pose, buffers, mc.level, 0);
            pose.popPose();
        }
        buffers.endBatch();
    }

    private static void disc(Matrix4f m, VertexConsumer quads, Vec3 cam, BlockPos feet, float radius, int rgb,
                             float alpha) {
        discAt(m, quads, cam, feet.getX() + 0.5D, feet.getY() + 0.04D, feet.getZ() + 0.5D, radius, rgb, alpha);
    }

    /** Flat octagon disc (8 quads from the centre) with a darker rim. */
    private static void discAt(Matrix4f m, VertexConsumer quads, Vec3 cam, double x, double y, double z,
                               float radius, int rgb, float alpha) {
        float cx = (float) (x - cam.x);
        float cy = (float) (y - cam.y);
        float cz = (float) (z - cam.z);
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        int a = Math.round(Math.max(0F, Math.min(1F, alpha)) * 255);
        int rimA = Math.round(a * 0.9F);
        for (int i = 0; i < 8; i++) {
            double a0 = Math.PI * 2 * i / 8.0D;
            double a1 = Math.PI * 2 * (i + 1) / 8.0D;
            float x0 = cx + (float) Math.cos(a0) * radius, z0 = cz + (float) Math.sin(a0) * radius;
            float x1 = cx + (float) Math.cos(a1) * radius, z1 = cz + (float) Math.sin(a1) * radius;
            quads.addVertex(m, cx, cy, cz).setColor(r, g, b, a);
            quads.addVertex(m, cx, cy, cz).setColor(r, g, b, a);
            quads.addVertex(m, x1, cy, z1).setColor(r, g, b, a);
            quads.addVertex(m, x0, cy, z0).setColor(r, g, b, a);
            // rim
            float ox0 = cx + (float) Math.cos(a0) * (radius + 0.05F), oz0 = cz + (float) Math.sin(a0) * (radius + 0.05F);
            float ox1 = cx + (float) Math.cos(a1) * (radius + 0.05F), oz1 = cz + (float) Math.sin(a1) * (radius + 0.05F);
            quads.addVertex(m, x0, cy, z0).setColor(r / 3, g / 3, b / 3, rimA);
            quads.addVertex(m, x1, cy, z1).setColor(r / 3, g / 3, b / 3, rimA);
            quads.addVertex(m, ox1, cy, oz1).setColor(r / 3, g / 3, b / 3, rimA);
            quads.addVertex(m, ox0, cy, oz0).setColor(r / 3, g / 3, b / 3, rimA);
        }
    }

    /** Facing arrow in front of the formation centre. */
    private static void chevron(Matrix4f m, VertexConsumer quads, Vec3 cam, BlockPos center, int octant,
                                int rgb, float alpha) {
        double fx = FormationMath.forwardX(octant), fz = FormationMath.forwardZ(octant);
        double len = Math.sqrt(fx * fx + fz * fz);
        fx /= len;
        fz /= len;
        double rx = -fz, rz = fx;
        double baseX = center.getX() + 0.5D + fx * 1.3D, baseZ = center.getZ() + 0.5D + fz * 1.3D;
        float y = (float) (center.getY() + 0.05D - cam.y);
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255, a = Math.round(alpha * 255);
        for (int side = -1; side <= 1; side += 2) {
            // Each arm of the chevron is a thin quad from the wing tip to the point.
            double tipX = baseX + fx * 0.9D, tipZ = baseZ + fz * 0.9D;
            double wingX = baseX + rx * side * 0.8D, wingZ = baseZ + rz * side * 0.8D;
            double nx = fx * 0.18D, nz = fz * 0.18D;
            quads.addVertex(m, (float) (wingX - cam.x), y, (float) (wingZ - cam.z)).setColor(r, g, b, a);
            quads.addVertex(m, (float) (tipX - cam.x), y, (float) (tipZ - cam.z)).setColor(r, g, b, a);
            quads.addVertex(m, (float) (tipX - nx - cam.x), y, (float) (tipZ - nz - cam.z)).setColor(r, g, b, a);
            quads.addVertex(m, (float) (wingX - nx * 1.6D - cam.x), y, (float) (wingZ - nz * 1.6D - cam.z))
                .setColor(r, g, b, a);
        }
    }

    private CommandWorldRenderer() {
    }
}
