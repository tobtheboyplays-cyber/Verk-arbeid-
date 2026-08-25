package com.hearthstead.client.render;

import com.hearthstead.Hearthstead;
import com.hearthstead.client.model.RaiderModel;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

/**
 * Draws a raider. Captains are visibly larger as well as differently
 * equipped: the point is that you can read who is leading a raid from
 * across the field rather than by hitting them and watching the health bar.
 */
public class RaiderRenderer extends MobRenderer<com.hearthstead.entity.RaiderEntity, RaiderModel> {

    private static final ResourceLocation TEXTURE =
        Hearthstead.id("textures/entity/raider/raider.png");
    private static final ResourceLocation CAPTAIN_TEXTURE =
        Hearthstead.id("textures/entity/raider/raider_captain.png");

    public RaiderRenderer(EntityRendererProvider.Context context) {
        super(context, new RaiderModel(context.bakeLayer(RaiderModel.LAYER)), 0.4F);
    }

    @Override
    protected void scale(com.hearthstead.entity.RaiderEntity entity, PoseStack pose,
                         float partialTick) {
        float s = entity.isCaptain() ? 1.12F : 1.0F;
        pose.scale(s, s, s);
    }

    @Override
    public void render(com.hearthstead.entity.RaiderEntity entity, float entityYaw,
                       float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int packedLight) {
        this.model.attackTime = entity.getAttackAnim(partialTick);
        super.render(entity, entityYaw, partialTick, pose, buffers, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(com.hearthstead.entity.RaiderEntity entity) {
        return entity.isCaptain() ? CAPTAIN_TEXTURE : TEXTURE;
    }
}
