package com.hearthstead.client.render;

import com.hearthstead.entity.FallingTreeEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;

/** Original rigid tree pivot, using the snapshotted world's own block models. */
public final class FallingTreeRenderer extends EntityRenderer<FallingTreeEntity> {
    private final BlockRenderDispatcher blocks;
    public FallingTreeRenderer(EntityRendererProvider.Context context) {
        super(context);blocks=context.getBlockRenderDispatcher();shadowRadius=0;
    }
    @Override public void render(FallingTreeEntity entity,float yaw,float partialTick,PoseStack pose,MultiBufferSource buffers,int light) {
        pose.pushPose();
        var direction=entity.fallDirection();
        pose.mulPose(Axis.of(new Vector3f(direction.getStepZ(),0,-direction.getStepX()))
            .rotationDegrees(entity.fallAngle(partialTick)));
        for(var piece:entity.pieces()) {
            pose.pushPose();
            pose.translate(piece.offset().getX()-.5,piece.offset().getY(),piece.offset().getZ()-.5);
            blocks.renderSingleBlock(piece.state(),pose,buffers,light,OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
        pose.popPose();
        super.render(entity,yaw,partialTick,pose,buffers,light);
    }
    @Override public ResourceLocation getTextureLocation(FallingTreeEntity entity) { return TextureAtlas.LOCATION_BLOCKS; }
}
