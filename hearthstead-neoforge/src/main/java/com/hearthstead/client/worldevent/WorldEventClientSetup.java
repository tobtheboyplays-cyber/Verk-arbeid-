package com.hearthstead.client.worldevent;

import com.hearthstead.Hearthstead;
import com.hearthstead.event.worldevent.PackWolfEntity;
import com.hearthstead.event.worldevent.WildBoarEntity;
import com.hearthstead.event.worldevent.WorldEventEntities;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.PigModel;
import net.minecraft.client.model.QuadrupedModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Client renderers for the world-event creatures (mod bus, client only). */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class WorldEventClientSetup {
    public static final ModelLayerLocation WILD_BOAR_LAYER =
        new ModelLayerLocation(Hearthstead.id("wild_boar"), "main");

    private WorldEventClientSetup() {
    }

    @SubscribeEvent
    public static void layers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(WILD_BOAR_LAYER, WorldEventClientSetup::boarLayer);
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(WorldEventEntities.PACK_WOLF.get(), PackWolfRenderer::new);
        event.registerEntityRenderer(WorldEventEntities.WILD_BOAR.get(), WildBoarRenderer::new);
    }

    /** The pig body with a longer snout, a bristle ridge and two tusks. 64x32 texture. */
    static LayerDefinition boarLayer() {
        MeshDefinition mesh = QuadrupedModel.createBodyMesh(6, CubeDeformation.NONE);
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("head", CubeListBuilder.create()
                .texOffs(0, 0).addBox(-4.0F, -4.0F, -8.0F, 8.0F, 8.0F, 8.0F)
                .texOffs(16, 16).addBox(-2.0F, 0.0F, -10.0F, 4.0F, 3.0F, 2.0F)
                .texOffs(56, 0).addBox(-3.0F, -1.0F, -10.0F, 1.0F, 3.0F, 1.0F)
                .texOffs(60, 0).addBox(2.0F, -1.0F, -10.0F, 1.0F, 3.0F, 1.0F)
                .texOffs(32, 0).addBox(-1.0F, -5.5F, -7.0F, 2.0F, 2.0F, 6.0F),
            PartPose.offset(0.0F, 12.0F, -6.0F));
        return LayerDefinition.create(mesh, 64, 32);
    }

    static final class PackWolfRenderer extends MobRenderer<PackWolfEntity, PackWolfModel> {
        private static final ResourceLocation[] SKINS = {
            ResourceLocation.withDefaultNamespace("textures/entity/wolf/wolf_ashen_angry.png"),
            ResourceLocation.withDefaultNamespace("textures/entity/wolf/wolf_angry.png"),
            ResourceLocation.withDefaultNamespace("textures/entity/wolf/wolf_black_angry.png")
        };

        PackWolfRenderer(EntityRendererProvider.Context context) {
            super(context, new PackWolfModel(context.bakeLayer(ModelLayers.WOLF)), 0.5F);
        }

        @Override
        public ResourceLocation getTextureLocation(PackWolfEntity wolf) {
            return SKINS[wolf.variant()];
        }

        @Override
        protected void scale(PackWolfEntity wolf, PoseStack pose, float partialTick) {
            pose.scale(1.1F, 1.1F, 1.1F);
        }
    }

    static final class WildBoarRenderer extends MobRenderer<WildBoarEntity, PigModel<WildBoarEntity>> {
        private static final ResourceLocation SKIN = Hearthstead.id("textures/entity/wild_boar.png");

        WildBoarRenderer(EntityRendererProvider.Context context) {
            super(context, new PigModel<>(context.bakeLayer(WILD_BOAR_LAYER)), 0.8F);
        }

        @Override
        public ResourceLocation getTextureLocation(WildBoarEntity boar) {
            return SKIN;
        }

        @Override
        protected void scale(WildBoarEntity boar, PoseStack pose, float partialTick) {
            pose.scale(1.25F, 1.2F, 1.3F);
        }
    }
}
