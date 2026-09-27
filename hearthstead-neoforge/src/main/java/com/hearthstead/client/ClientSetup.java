package com.hearthstead.client;

import com.hearthstead.Hearthstead;
import com.hearthstead.client.model.SettlerModel;
import com.hearthstead.client.render.SettlerRenderer;
import com.hearthstead.client.render.SettlerTextureCache;
import com.hearthstead.client.screen.HearthScreen;
import com.hearthstead.client.screen.SettlerInventoryScreen;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModMenus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class ClientSetup {

    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.HEARTH.get(), HearthScreen::new);
        event.register(ModMenus.SETTLER_INVENTORY.get(), SettlerInventoryScreen::new);
        event.register(ModMenus.FISH_RACK.get(), com.hearthstead.client.screen.FishRackScreen::new);
        event.register(ModMenus.ARROW_BARREL.get(), com.hearthstead.client.screen.ArrowBarrelScreen::new);
    }

    @SubscribeEvent
    public static void onRegisterLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(SettlerModel.LAYER, SettlerModel::createBodyLayer);
        event.registerLayerDefinition(com.hearthstead.client.model.RaiderModel.LAYER,
            com.hearthstead.client.model.RaiderModel::createBodyLayer);
        event.registerLayerDefinition(com.hearthstead.client.model.GoblinThiefModel.LAYER,
            com.hearthstead.client.model.GoblinThiefModel::createBodyLayer);
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.SETTLER.get(), SettlerRenderer::new);
        event.registerBlockEntityRenderer(com.hearthstead.registry.ModBlockEntities.FISH_RACK.get(), com.hearthstead.client.render.FishRackRenderer::new);
        event.registerBlockEntityRenderer(com.hearthstead.registry.ModBlockEntities.BUTCHERING_TABLE.get(), com.hearthstead.client.render.ButcheringTableRenderer::new);
        event.registerEntityRenderer(ModEntities.FISHER_SEAT.get(), net.minecraft.client.renderer.entity.NoopRenderer::new);
        event.registerEntityRenderer(ModEntities.TAVERN_SEAT.get(),
            net.minecraft.client.renderer.entity.NoopRenderer::new);
        event.registerEntityRenderer(ModEntities.TAVERN_SERVING.get(),
            com.hearthstead.client.render.TavernServingRenderer::new);
        event.registerEntityRenderer(ModEntities.FALLING_TREE.get(),
            com.hearthstead.client.render.FallingTreeRenderer::new);
        event.registerEntityRenderer(ModEntities.FISHER_NET_BUOY.get(),
            com.hearthstead.client.render.FisherNetBuoyRenderer::new);
        // Replaces vanilla's floating/spinning ItemEntity display with items
        // that genuinely REST on the ground (user request; W19). Mod
        // registration fires after vanilla's bootstrap, so this overwrites
        // the EntityType.ITEM provider cleanly.
        event.registerEntityRenderer(net.minecraft.world.entity.EntityType.ITEM,
            com.hearthstead.client.render.GroundedItemRenderer::new);
        event.registerEntityRenderer(ModEntities.RAIDER.get(),
            com.hearthstead.client.render.RaiderRenderer::new);
        // The plaque's parchment: the survey is on the wire (step 1), the
        // sheet is big enough to read (step 3), and this is what writes on it.
        event.registerBlockEntityRenderer(
            com.hearthstead.registry.ModBlockEntities.PLAQUE.get(),
            com.hearthstead.client.render.PlaqueRenderer::new);
        // The settlement Banner's pole and cloth above its ledger stand.
        event.registerBlockEntityRenderer(
            com.hearthstead.registry.ModBlockEntities.HEARTH.get(),
            com.hearthstead.client.render.SettlementBannerRenderer::new);
    }

    @SubscribeEvent
    public static void onRegisterAdditionalModels(
            net.neoforged.neoforge.client.event.ModelEvent.RegisterAdditional event) {
        event.register(com.hearthstead.client.render.SettlementBannerRenderer.POLE_MODEL);
        event.register(com.hearthstead.client.render.FisherNetBuoyRenderer.MODEL);
    }

    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        // Composed settler textures are built from layer bytes read out of
        // the active resource pack; a reload can change those bytes, so
        // every cached composition must be dropped, not reused.
        event.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener)
            manager -> SettlerTextureCache.clear());
    }

    private ClientSetup() {
    }
}
