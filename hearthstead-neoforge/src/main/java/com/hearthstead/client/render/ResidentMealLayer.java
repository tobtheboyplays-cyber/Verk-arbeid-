package com.hearthstead.client.render;

import com.hearthstead.client.model.SettlerModel;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Exact owned food in the eating hand, with existing equipment visibly stowed. */
public final class ResidentMealLayer extends RenderLayer<SettlerEntity, SettlerModel> {
    private final ItemInHandRenderer items;
    public ResidentMealLayer(RenderLayerParent<SettlerEntity, SettlerModel> parent, ItemInHandRenderer items) {
        super(parent); this.items = items;
    }
    @Override public void render(PoseStack pose, MultiBufferSource buffers, int light, SettlerEntity actor,
        float limbSwing, float limbSwingAmount, float partialTick, float age, float yaw, float pitch) {
        boolean eating = actor.getActivity() == SettlerActivity.EATING && actor.hasMeal();
        boolean serviceHands = getParentModel().hasServiceHandPose(HumanoidArm.RIGHT)
            || getParentModel().hasServiceHandPose(HumanoidArm.LEFT);
        if (!eating && !serviceHands) return;
        if (eating) drawOwnedMeal(actor, partialTick, pose, buffers, light);
        stow(actor, actor.getMainHandItem(), .36, pose, buffers, light);
        stow(actor, actor.getOffhandItem(), -.36, pose, buffers, light);
    }
    private void drawOwnedMeal(SettlerEntity actor, float partial, PoseStack pose,
                               MultiBufferSource buffers, int light) {
        ItemStack meal = actor.mealDisplayCopy();
        if (meal.isEmpty()) return;
        // The receiving solver and normal EAT pose share this exact palm endpoint.
        // Render only the resident's real owned stack with the same FIXED model,
        // size and surface offset used on the table; no duplicate handoff prop.
        PoseStack hand = new PoseStack();
        getParentModel().translateToServiceHand(HumanoidArm.RIGHT, hand);
        org.joml.Vector3f palm = hand.last().pose().getTranslation(new org.joml.Vector3f());
        var serving = com.hearthstead.client.model.TavernServingHandPose.findServing(actor);
        float bodyYaw = net.minecraft.util.Mth.rotLerp(partial, actor.yBodyRotO, actor.yBodyRot);
        float presentationYaw = serving != null ? serving.presentationYaw(partial) : bodyYaw;
        var renderer = Minecraft.getInstance().getItemRenderer();
        var model = renderer.getModel(meal, actor.level(), actor, actor.getId());
        pose.pushPose();
        pose.translate(palm.x, palm.y, palm.z);
        // Cancel the living renderer's mirror/yaw while retaining the palm's
        // transformed position. Item orientation then matches its world owner.
        float inverseScale = 1F / actor.getScale();
        pose.scale(-inverseScale, -inverseScale, inverseScale);
        pose.mulPose(Axis.YP.rotationDegrees(bodyYaw - 180F));
        pose.translate(0, .025, 0);
        pose.mulPose(Axis.YP.rotationDegrees(-presentationYaw));
        if (!model.isGui3d()) pose.mulPose(Axis.XP.rotationDegrees(-90F));
        pose.scale(.42F, .42F, .42F);
        renderer.render(meal, ItemDisplayContext.FIXED, false, pose, buffers,
            light, OverlayTexture.NO_OVERLAY, model);
        pose.popPose();
    }
    private void stow(SettlerEntity actor, ItemStack item, double side, PoseStack pose,
                      MultiBufferSource buffers, int light) {
        if (item.isEmpty()) return;
        pose.pushPose();
        // Renderer coordinates: hip is y=.75 under the vanilla inverted model transform.
        pose.translate(side, .72, .08);
        pose.mulPose(Axis.ZP.rotationDegrees(side > 0 ? -12F : 12F));
        pose.scale(.6F, .6F, .6F);
        items.renderItem(actor, item, ItemDisplayContext.FIXED, false, pose, buffers, light);
        pose.popPose();
    }
}
