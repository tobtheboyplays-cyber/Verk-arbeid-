package com.hearthstead.client.render;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.FisherNetBuoyEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Fisher's Nets float: an original cork float with a marker stick and pennant
 * over a square of net just under the surface (standalone block model,
 * models/block/net_buoy.json). It bobs on the swell; a net with fish in it
 * sits a little lower and gives a small tug now and then.
 */
public final class FisherNetBuoyRenderer extends EntityRenderer<FisherNetBuoyEntity> {
    public static final ResourceLocation MODEL_ID = Hearthstead.id("block/net_buoy");
    public static final ModelResourceLocation MODEL = ModelResourceLocation.standalone(MODEL_ID);
    private final BlockRenderDispatcher blocks;

    public FisherNetBuoyRenderer(EntityRendererProvider.Context context) {
        super(context);
        blocks = context.getBlockRenderDispatcher();
        shadowRadius = 0;
    }

    @Override
    public ResourceLocation getTextureLocation(FisherNetBuoyEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }

    @Override
    public void render(FisherNetBuoyEntity entity, float yaw, float partial, PoseStack pose,
                       MultiBufferSource buffers, int light) {
        float t = entity.tickCount + partial;
        float seed = (entity.getId() % 97) * 0.37F;
        int fish = entity.storedForDisplay();
        // Swell: two slow sines, never in step between floats.
        float bob = Mth.sin(t * 0.07F + seed) * 0.035F + Mth.sin(t * 0.023F + seed * 2F) * 0.02F;
        float sink = -0.018F * fish;
        // A held fish tugs: a short dip every few seconds, livelier the fuller the net.
        float tugPhase = (t + seed * 40F) % (140F - fish * 20F);
        float tug = fish > 0 && tugPhase < 10F ? -0.05F * Mth.sin(tugPhase / 10F * Mth.PI) : 0F;
        float rollX = Mth.sin(t * 0.05F + seed) * 3F;
        float rollZ = Mth.cos(t * 0.041F + seed) * 2.5F;

        BakedModel model = Minecraft.getInstance().getModelManager().getModel(MODEL);
        pose.pushPose();
        pose.translate(0F, bob + sink + tug, 0F);
        pose.mulPose(Axis.YP.rotationDegrees((entity.getId() * 53) % 360));
        pose.mulPose(Axis.XP.rotationDegrees(rollX));
        pose.mulPose(Axis.ZP.rotationDegrees(rollZ));
        pose.translate(-0.5F, -2F / 16F, -0.5F);
        blocks.getModelRenderer().renderModel(pose.last(), buffers.getBuffer(Sheets.cutoutBlockSheet()),
            Blocks.AIR.defaultBlockState(), model, 1F, 1F, 1F, light, OverlayTexture.NO_OVERLAY,
            ModelData.EMPTY, null);
        pose.popPose();
        super.render(entity, yaw, partial, pose, buffers, light);
    }
}
