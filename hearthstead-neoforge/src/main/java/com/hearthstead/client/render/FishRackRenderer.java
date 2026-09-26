package com.hearthstead.client.render;

import com.hearthstead.block.FishRackBlock;
import com.hearthstead.block.FishRackBlockEntity;
import com.hearthstead.registry.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Each occupied hook renders its actual species' cuboid fish model, never an item sprite. */
public final class FishRackRenderer implements BlockEntityRenderer<FishRackBlockEntity> {
    public FishRackRenderer(BlockEntityRendererProvider.Context context) {}
    @Override public void render(FishRackBlockEntity rack,float partial,PoseStack pose,MultiBufferSource buffers,int light,int overlay) {
        pose.pushPose();pose.translate(.5,0,.5);
        pose.mulPose(Axis.YP.rotationDegrees(-rack.getBlockState().getValue(FishRackBlock.FACING).toYRot()+180));
        for(int hook=0;hook<rack.getContainerSize();hook++) {
            ItemStack fish=rack.getItem(hook);
            if(fish.isEmpty()) continue;
            // Vanilla fish can be stored too; give those a full physical body instead of their flat icon.
            ItemStack display=fish;
            if(!(fish.is(ModItems.RIVER_PERCH.get()) || fish.is(ModItems.BROWN_TROUT.get())
                || fish.is(ModItems.SILVER_PIKE.get()) || fish.is(ModItems.GOLDEN_CHAR.get())))
                display=new ItemStack(fish.is(Items.SALMON)?ModItems.BROWN_TROUT.get():ModItems.RIVER_PERCH.get());
            pose.pushPose();pose.translate(-.3+hook*.2,.62,0);
            pose.mulPose(Axis.ZP.rotationDegrees(-90));pose.scale(.65F,.65F,.65F);
            Minecraft.getInstance().getItemRenderer().renderStatic(display,ItemDisplayContext.FIXED,
                light,overlay,pose,buffers,rack.getLevel(),hook);
            pose.popPose();
        }
        pose.popPose();
    }
}
