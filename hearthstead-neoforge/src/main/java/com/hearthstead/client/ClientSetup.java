package com.hearthstead.client;

import com.hearthstead.Hearthstead;
import com.hearthstead.client.model.SettlerModel;
import com.hearthstead.client.render.SettlerRenderer;
import com.hearthstead.client.render.SettlerTextureCache;
import com.hearthstead.client.screen.HearthScreen;
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
    }

    @SubscribeEvent
    public static void onRegisterLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(SettlerModel.LAYER, SettlerModel::createBodyLayer);
        event.registerLayerDefinition(com.hearthstead.client.model.RaiderModel.LAYER,
            com.hearthstead.client.model.RaiderModel::createBodyLayer);
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.SETTLER.get(), SettlerRenderer::new);
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
