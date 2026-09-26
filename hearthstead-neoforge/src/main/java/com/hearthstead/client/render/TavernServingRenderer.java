package com.hearthstead.client.render;

import com.hearthstead.client.model.TavernServingMotion;
import com.hearthstead.entity.TavernServingEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/** One world-owned serving, with original tableware and shared physical hand contacts. */
public final class TavernServingRenderer extends EntityRenderer<TavernServingEntity> {
    private static final ResourceLocation TABLEWARE = ResourceLocation.fromNamespaceAndPath(
        "hearthstead", "textures/entity/tavern/tableware.png");
    private final ItemRenderer items;

    public TavernServingRenderer(EntityRendererProvider.Context context) {
        super(context);
        items = context.getItemRenderer();
        shadowRadius = 0;
    }

    @Override public ResourceLocation getTextureLocation(TavernServingEntity entity) {
        return TABLEWARE;
    }

    @Override public void render(TavernServingEntity entity, float yaw, float partial,
                                PoseStack pose, MultiBufferSource buffers, int light) {
        Vec3 origin = new Vec3(Mth.lerp(partial, entity.xo, entity.getX()),
            Mth.lerp(partial, entity.yo, entity.getY()), Mth.lerp(partial, entity.zo, entity.getZ()));
        if (TavernServingMotion.hasTableware(entity)) {
            pose.pushPose();
            translate(pose, entity.foodPosition(partial).subtract(origin));
            pose.mulPose(Axis.YP.rotationDegrees(-entity.presentationYaw(partial)));
            TavernTablewareMesh.render(TavernTablewareMesh.PLATE, pose,
                buffers.getBuffer(RenderType.entityCutoutNoCull(TABLEWARE)), light);
            pose.popPose();
        }

        var food = entity.displayFood();
        if (!food.isEmpty()) {
            var model = items.getModel(food, entity.level(), null, entity.getId());
            pose.pushPose();
            // Vanilla generated food is 1/16 thick: .32 / 32 half-thickness.
            // Its underside rests at the .018 dish well, rather than hovering over the rim.
            translate(pose, entity.foodPosition(partial).subtract(origin).add(0, .028, 0));
            pose.mulPose(Axis.YP.rotationDegrees(-entity.presentationYaw(partial)));
            if (!model.isGui3d()) pose.mulPose(Axis.XP.rotationDegrees(-90));
            pose.scale(.32F, .32F, .32F);
            items.render(food, ItemDisplayContext.FIXED, false, pose, buffers,
                light, OverlayTexture.NO_OVERLAY, model);
            pose.popPose();
        }

        var payment = entity.displayPayment();
        if (!payment.isEmpty()) {
            var model = items.getModel(payment, entity.level(), null, entity.getId());
            pose.pushPose();
            translate(pose, entity.foodPosition(partial).subtract(origin).add(0, .025, 0));
            pose.mulPose(Axis.YP.rotationDegrees(-entity.presentationYaw(partial)));
            if (!model.isGui3d()) pose.mulPose(Axis.XP.rotationDegrees(-90));
            pose.scale(.22F, .22F, .22F);
            items.render(payment, ItemDisplayContext.FIXED, false, pose, buffers,
                light, OverlayTexture.NO_OVERLAY, model);
            pose.popPose();
        }

        if (!entity.displayGlass().isEmpty()) {
            TavernServingMotion.CupPose cup = TavernServingMotion.cupPose(entity, partial);
            pose.pushPose();
            translate(pose, cup.base().subtract(origin));
            Vec3 axis = cup.tiltAxis();
            // World tilt precedes local yaw, matching the exact transform used by hand IK.
            pose.mulPose(new Quaternionf().rotationAxis(cup.tiltDegrees() * Mth.DEG_TO_RAD,
                (float) axis.x, (float) axis.y, (float) axis.z));
            pose.mulPose(Axis.YP.rotationDegrees(-entity.presentationYaw(partial)));
            var solid = buffers.getBuffer(RenderType.entityCutoutNoCull(TABLEWARE));
            TavernTablewareMesh.render(TavernTablewareMesh.CUP_OPAQUE, pose, solid, light);
            // Only authoritative Ale custody supplies fill. The exterior is never swapped.
            if (!entity.displayAle().isEmpty()) {
                TavernTablewareMesh.render(TavernTablewareMesh.ALE, pose, solid, light);
                TavernTablewareMesh.render(TavernTablewareMesh.FOAM, pose, solid, light);
            }
            TavernTablewareMesh.render(TavernTablewareMesh.CUP_GLASS, pose,
                buffers.getBuffer(RenderType.entityTranslucent(TABLEWARE)), light);
            pose.popPose();
        }
        super.render(entity, yaw, partial, pose, buffers, light);
    }

    private static void translate(PoseStack pose, Vec3 vector) {
        pose.translate(vector.x, vector.y, vector.z);
    }
}
