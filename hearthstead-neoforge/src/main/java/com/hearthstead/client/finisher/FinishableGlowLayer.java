package com.hearthstead.client.finisher;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * The finish cue: a finishable enemy's TORSO glows a pulsing red (owner, 26
 * Sep 2026: "glows red on its belly/torso"). Rendered inside the entity's own
 * renderer pass, so it follows every animation, and full-bright so it reads in
 * daylight and at night for every nearby player.
 *
 * <p>Two passes over only the torso's own cubes: a translucent emissive red
 * tint of the skin (the body reads red in sunlight), and a slightly larger
 * additive shell (the glow halo in the dark). Hierarchical models (raider,
 * goblin) use their {@code torso} part, vanilla humanoids their {@code body};
 * anything else glows whole-body.</p>
 */
public class FinishableGlowLayer<T extends LivingEntity, M extends EntityModel<T>> extends RenderLayer<T, M> {
    private static final float TINT_SCALE = 1.04F;
    private static final float HALO_SCALE = 1.12F;

    public FinishableGlowLayer(RenderLayerParent<T, M> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffers, int light, T entity,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        if (entity instanceof Player || entity.isInvisible()) {
            return;
        }
        float glow = FinisherClient.glowStrength(entity, partialTick);
        if (glow <= 0.0F) {
            return;
        }
        ResourceLocation texture = getTextureLocation(entity);
        int tint = argb(0.35F + 0.4F * glow, 1.0F, 0.16F, 0.08F);
        int halo = argb(1.0F, 0.75F * glow, 0.08F * glow, 0.03F * glow);
        M model = getParentModel();
        VertexConsumer tintBuffer = buffers.getBuffer(RenderType.entityTranslucentEmissive(texture));
        drawTorso(model, poseStack, tintBuffer, tint, TINT_SCALE);
        VertexConsumer haloBuffer = buffers.getBuffer(RenderType.eyes(texture));
        drawTorso(model, poseStack, haloBuffer, halo, HALO_SCALE);
    }

    private static void drawTorso(EntityModel<?> model, PoseStack poseStack, VertexConsumer buffer,
                                  int color, float scale) {
        if (model instanceof HierarchicalModel<?> hierarchical) {
            boolean[] found = {false};
            hierarchical.root().visit(poseStack, (pose, path, index, cube) -> {
                if (path.endsWith("/torso")) {
                    found[0] = true;
                    compileScaled(pose, cube, buffer, color, scale);
                }
            });
            if (found[0]) {
                return;
            }
        } else if (model instanceof HumanoidModel<?> humanoid) {
            ModelPart body = humanoid.body;
            body.visit(poseStack, (pose, path, index, cube) -> {
                if (path.isEmpty()) {
                    compileScaled(pose, cube, buffer, color, scale);
                }
            });
            return;
        }
        model.renderToBuffer(poseStack, buffer, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, color);
    }

    /** Draws one cube slightly inflated about its own centre (no z-fighting with the skin). */
    private static void compileScaled(PoseStack.Pose pose, ModelPart.Cube cube, VertexConsumer buffer,
                                      int color, float scale) {
        float cx = (cube.minX + cube.maxX) * 0.5F / 16.0F;
        float cy = (cube.minY + cube.maxY) * 0.5F / 16.0F;
        float cz = (cube.minZ + cube.maxZ) * 0.5F / 16.0F;
        PoseStack local = new PoseStack();
        local.last().pose().set(pose.pose());
        local.last().normal().set(pose.normal());
        local.translate(cx, cy, cz);
        local.scale(scale, scale, scale);
        local.translate(-cx, -cy, -cz);
        cube.compile(local.last(), buffer, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, color);
    }

    private static int argb(float a, float r, float g, float b) {
        return (clamp(a) << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255.0F)));
    }
}
