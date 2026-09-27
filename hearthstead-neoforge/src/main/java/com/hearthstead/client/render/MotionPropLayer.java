package com.hearthstead.client.render;

import com.hearthstead.client.model.SettlerModel;
import com.hearthstead.client.motion.LimbMotion;
import com.hearthstead.client.motion.MotionProp;
import com.hearthstead.entity.SettlerEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Draws the display-only hand prop a motion clip asks for (a whetstone
 * flint, a mug, a crust) exactly where vanilla's ItemInHandLayer would draw a
 * held item, bend-aware through {@code translateToHand}. Presentation only:
 * the settler's inventory and equipment are never read for anything but
 * "is that hand already full", and never written.
 */
public final class MotionPropLayer extends RenderLayer<SettlerEntity, SettlerModel> {
    private final ItemInHandRenderer items;

    public MotionPropLayer(RenderLayerParent<SettlerEntity, SettlerModel> parent, ItemInHandRenderer items) {
        super(parent);
        this.items = items;
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int light, SettlerEntity entity,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        if (entity.isInvisible()) {
            return;
        }
        LimbMotion motion = getParentModel().motion();
        for (int hand = MotionProp.MAINHAND; hand <= MotionProp.OFFHAND; hand++) {
            MotionProp prop = motion.prop(hand);
            if (prop == null) {
                continue;
            }
            ItemStack real = hand == MotionProp.MAINHAND ? entity.getMainHandItem() : entity.getOffhandItem();
            if (!real.isEmpty() && !prop.hideReal() && !prop.overReal()) {
                continue;
            }
            ItemStack stack = prop.stack();
            if (stack.isEmpty()) {
                continue;
            }
            HumanoidArm arm = hand == MotionProp.MAINHAND ? entity.getMainArm() : entity.getMainArm().getOpposite();
            boolean left = arm == HumanoidArm.LEFT;
            pose.pushPose();
            getParentModel().translateToHand(arm, pose);
            pose.mulPose(Axis.XP.rotationDegrees(-90.0F));
            pose.mulPose(Axis.YP.rotationDegrees(180.0F));
            pose.translate((left ? -1.0F : 1.0F) / 16.0F, 0.125F, -0.625F);
            items.renderItem(entity, stack, left ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, left, pose, buffers, light);
            pose.popPose();
        }
    }

    /** True when a playing clip hides the real item of this arm for its prop window. */
    public static boolean hidesReal(SettlerModel model, SettlerEntity entity, HumanoidArm arm) {
        int hand = arm == entity.getMainArm() ? MotionProp.MAINHAND : MotionProp.OFFHAND;
        MotionProp prop = model.motion().prop(hand);
        return prop != null && prop.hideReal();
    }
}
