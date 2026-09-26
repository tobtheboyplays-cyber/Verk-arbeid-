package com.hearthstead.client.render;

import com.hearthstead.block.ButcheringTableBlock;
import com.hearthstead.block.ButcheringTableBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

/** The carcass on a Butchering Table: its species' model lying on its side along the table. */
public final class ButcheringTableRenderer implements BlockEntityRenderer<ButcheringTableBlockEntity> {
    public ButcheringTableRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(ButcheringTableBlockEntity table, float partial, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        if (!table.hasCarcass()) {
            return;
        }
        pose.pushPose();
        pose.translate(0.5D, 13.25D / 16.0D + 0.2D, 0.5D);
        pose.mulPose(Axis.YP.rotationDegrees(
            -table.getBlockState().getValue(ButcheringTableBlock.FACING).toYRot()));
        CarcassDisplay.render(table.carcass(), pose, buffers, light);
        pose.popPose();
    }
}
