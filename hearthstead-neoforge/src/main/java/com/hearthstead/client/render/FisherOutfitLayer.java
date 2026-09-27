package com.hearthstead.client.render;

import com.hearthstead.client.model.SettlerModel;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;

/** Original geometry/materials following the owner's navy, linen and rope Fisher reference. */
public final class FisherOutfitLayer extends RenderLayer<SettlerEntity, SettlerModel> {
    private static final ResourceLocation CLOTH = com.hearthstead.Hearthstead.id("textures/block/material/linen_cloth.png");
    private static final ResourceLocation WOOD = com.hearthstead.Hearthstead.id("textures/block/material/wicker.png");
    private final ModelPart cap, apron, sleeve, basket, belt;
    public FisherOutfitLayer(RenderLayerParent<SettlerEntity, SettlerModel> parent) {
        super(parent);
        cap=part(CubeListBuilder.create().texOffs(0,0).addBox(-4.6F,-8.8F,-4.6F,9.2F,3.3F,9.2F)
            .texOffs(0,0).addBox(-4.85F,-6.5F,-4.85F,9.7F,1.2F,9.7F));
        apron=part(CubeListBuilder.create().texOffs(0,0).addBox(-5.15F,-11.2F,-2.7F,10.3F,11.4F,5.4F)
            .texOffs(0,0).addBox(-4.4F,-.1F,-2.85F,8.8F,3.4F,.6F));
        sleeve=part(CubeListBuilder.create().texOffs(0,0).addBox(-2.15F,-2.1F,-2.15F,4.3F,6.1F,4.3F)
            .texOffs(0,0).addBox(-2.3F,3.5F,-2.3F,4.6F,.8F,4.6F));
        CubeListBuilder weave=CubeListBuilder.create().texOffs(0,0).addBox(4.7F,-1F,-1.5F,3.3F,3.8F,3F);
        for(int i=0;i<4;i++) weave.texOffs(0,0).addBox(4.5F,-1F+i,-1.7F,3.7F,.3F,3.4F);
        basket=part(weave);
        belt=part(CubeListBuilder.create().texOffs(0,0).addBox(-5.3F,-2.2F,-2.9F,10.6F,.65F,5.8F)
            .texOffs(0,0).addBox(-5.3F,-1.4F,-2.9F,10.6F,.65F,5.8F)
            .texOffs(0,0).addBox(3.4F,-2F,-3.2F,.5F,4F,.5F));
    }
    private static ModelPart part(CubeListBuilder cubes) {
        MeshDefinition mesh=new MeshDefinition();
        mesh.getRoot().addOrReplaceChild("part",cubes,PartPose.ZERO);
        return LayerDefinition.create(mesh,16,16).bakeRoot().getChild("part");
    }
    private static void draw(ModelPart part,PoseStack pose,MultiBufferSource buffers,int light,int tint,ResourceLocation material) {
        part.render(pose,buffers.getBuffer(RenderType.entityCutoutNoCull(material)),light,OverlayTexture.NO_OVERLAY,tint);
    }
    @Override public void render(PoseStack pose,MultiBufferSource buffers,int light,SettlerEntity actor,
            float walk,float amount,float partial,float age,float yaw,float pitch) {
        if(actor.getProfession()!=Profession.FISHER || actor.isInvisible()) return;
        ModelPart root=getParentModel().root(), torso=root.getChild("torso");
        pose.pushPose(); root.translateAndRotate(pose); torso.translateAndRotate(pose);
        if(actor.getItemBySlot(EquipmentSlot.CHEST).isEmpty()) {
            draw(apron,pose,buffers,light,0xFF3B3F4A,CLOTH);
            draw(basket,pose,buffers,light,0xFFAF9871,WOOD);
            draw(belt,pose,buffers,light,0xFFB39A73,CLOTH);
            for(String arm:new String[]{"right_arm","left_arm"}) {
                pose.pushPose();torso.getChild(arm).translateAndRotate(pose);
                draw(sleeve,pose,buffers,light,0xFFCDBFA6,CLOTH);pose.popPose();
            }
        }
        if(actor.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) {
            pose.pushPose();torso.getChild("head").translateAndRotate(pose);
            draw(cap,pose,buffers,light,0xFF3B3F4A,CLOTH);pose.popPose();
        }
        pose.popPose();
        // Fisher v3: the line, float and catch while he fishes from the chair (render-only).
        FisherLineRenderer.render(getParentModel(),actor,pose,buffers,light,partial,age);
    }
}
