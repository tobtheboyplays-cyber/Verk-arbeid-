package com.hearthstead.client.render;

import com.hearthstead.client.model.SettlerModel;
import com.hearthstead.entity.SettlerEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * The guard's greeting (GuardSaluteGoal): a leather scabbard at the right hip for the whole
 * greeting, and -- while {@link SettlerModel#swordSheathed} -- the guard's REAL mainhand sword
 * drawn in it instead of in his hand (the hand layer skips it for that window). Presentation
 * only: the MAINHAND slot is read for the stack to draw, never written.
 *
 * <p>The hip frame is the exact torso-relative hand frame at the seat of GUARD_SHEATHE_SWORD
 * (tools/blender/pipeline/clips/combat/greet.py writes anim-overkill/scabbard_frame.json), so
 * the hand -> scabbard swap at 0.70 s and the scabbard -> hand swap in GUARD_DRAW_SWORD match.
 */
public final class GuardScabbardLayer extends RenderLayer<SettlerEntity, SettlerModel> {
    /** torso -> "translateToHand" frame of the seated hilt (blocks). Re-derive after re-authoring. */
    private static final Matrix4f HIP = new Matrix4f()
        .m00(0.986010F).m01(0.162612F).m02(0.036617F)
        .m10(0.063219F).m11(-0.568101F).m12(0.820527F)
        .m20(0.154230F).m21(-0.806733F).m22(-0.570434F)
        .m30(-0.498906F).m31(0.297397F).m32(-0.759744F);
    private static final ResourceLocation LEATHER =
        ResourceLocation.withDefaultNamespace("textures/block/brown_wool.png");
    private static final ResourceLocation BRASS =
        ResourceLocation.withDefaultNamespace("textures/block/gold_block.png");
    /** Boxes in the sword sprite's pixel space: from x,y, to x,y (along the blade), half width, half depth. */
    private static final float[][] LEATHER_BOXES = {{4.6F, 4.6F, 16.0F, 16.0F, 1.55F, 1.1F}};
    private static final float[][] BRASS_BOXES = {{4.4F, 4.4F, 6.2F, 6.2F, 1.85F, 1.35F},
        {14.6F, 14.6F, 16.2F, 16.2F, 1.8F, 1.3F}};
    private static final int[][] FACES = {{0, 1, 3, 2}, {4, 6, 7, 5}, {0, 4, 5, 1}, {2, 3, 7, 6}, {0, 2, 6, 4},
        {1, 5, 7, 3}};
    private static final float[][] UV = {{0.0F, 0.0F}, {0.5F, 0.0F}, {0.5F, 0.5F}, {0.0F, 0.5F}};

    private final ItemInHandRenderer items;

    public GuardScabbardLayer(RenderLayerParent<SettlerEntity, SettlerModel> parent, ItemInHandRenderer items) {
        super(parent);
        this.items = items;
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int light, SettlerEntity entity,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        if (entity.isInvisible() || !SettlerModel.greetingScabbard(entity)) {
            return;
        }
        ItemStack sword = entity.getMainHandItem();
        pose.pushPose();
        getParentModel().translateToTorso(pose);
        pose.last().pose().mul(HIP);
        pose.last().normal().mul(new Matrix3f(HIP));
        // exactly what ItemInHandLayer does after translateToHand (right hand)
        pose.mulPose(Axis.XP.rotationDegrees(-90.0F));
        pose.mulPose(Axis.YP.rotationDegrees(180.0F));
        pose.translate(1.0F / 16.0F, 0.125F, -0.625F);
        if (SettlerModel.swordSheathed(entity) && !sword.isEmpty()) {
            items.renderItem(entity, sword, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, false, pose, buffers, light);
        }
        // item/handheld third-person transform, then the sprite's pixel space
        pose.translate(0.0F, 4.0F / 16.0F, 0.5F / 16.0F);
        pose.mulPose(new Quaternionf().rotationXYZ(0.0F, (float) Math.toRadians(-90.0D),
            (float) Math.toRadians(55.0D)));
        pose.scale(0.85F, 0.85F, 0.85F);
        pose.translate(-0.5F, -0.5F, -0.5F);
        pose.scale(1.0F / 16.0F, 1.0F / 16.0F, 1.0F / 16.0F);
        VertexConsumer leather = buffers.getBuffer(RenderType.entityCutoutNoCull(LEATHER));
        for (float[] b : LEATHER_BOXES) {
            box(pose, leather, b, light, 0xFF7A5238);
        }
        VertexConsumer brass = buffers.getBuffer(RenderType.entityCutoutNoCull(BRASS));
        for (float[] b : BRASS_BOXES) {
            box(pose, brass, b, light, 0xFFC8A870);
        }
        pose.popPose();
    }

    /** A box whose long axis runs (b0,b1) -> (b2,b3) in the sprite plane, centred on z = 8. */
    private static void box(PoseStack pose, VertexConsumer vc, float[] b, int light, int argb) {
        float ax = b[2] - b[0];
        float ay = b[3] - b[1];
        float len = (float) Math.sqrt(ax * ax + ay * ay);
        ax /= len;
        ay /= len;
        float nx = -ay * b[4];
        float ny = ax * b[4];
        float[][] c = new float[8][];
        int i = 0;
        for (int end = 0; end < 2; end++) {
            float px = end == 0 ? b[0] : b[2];
            float py = end == 0 ? b[1] : b[3];
            for (int sw = -1; sw <= 1; sw += 2) {
                for (int sd = -1; sd <= 1; sd += 2) {
                    c[i++] = new float[] {px + nx * sw, py + ny * sw, 8.0F + sd * b[5]};
                }
            }
        }
        PoseStack.Pose last = pose.last();
        for (int[] f : FACES) {
            float[] a0 = c[f[0]];
            float[] a1 = c[f[1]];
            float[] a2 = c[f[3]];
            float ux = a1[0] - a0[0], uy = a1[1] - a0[1], uz = a1[2] - a0[2];
            float vx = a2[0] - a0[0], vy = a2[1] - a0[1], vz = a2[2] - a0[2];
            float qx = uy * vz - uz * vy;
            float qy = uz * vx - ux * vz;
            float qz = ux * vy - uy * vx;
            float ql = (float) Math.sqrt(qx * qx + qy * qy + qz * qz);
            if (ql < 1.0E-6F) {
                continue;
            }
            for (int k = 0; k < 4; k++) {
                float[] p = c[f[k]];
                vc.addVertex(last, p[0], p[1], p[2]).setColor(argb).setUv(UV[k][0], UV[k][1])
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                    .setNormal(last, qx / ql, qy / ql, qz / ql);
            }
        }
    }
}
