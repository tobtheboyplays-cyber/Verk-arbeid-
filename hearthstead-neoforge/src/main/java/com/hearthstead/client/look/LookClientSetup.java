package com.hearthstead.client.look;

import com.hearthstead.Hearthstead;
import com.hearthstead.client.model.SettlerModel;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.look.CharacterLooks;
import com.hearthstead.registry.ModEntities;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.EntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

/**
 * Client wiring for character looks, kept out of the shared ClientSetup and
 * SettlerRenderer: the accessory layer goes in through AddLayers, the trader
 * costume renderer replaces the vanilla WanderingTrader one, and composed
 * textures are dropped on resource reload.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class LookClientSetup {
    private LookClientSetup() {
    }

    @SubscribeEvent
    public static void layers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(SettlerAccessoryLayer.LAYER, SettlerAccessoryLayer::createLayer);
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(EntityType.WANDERING_TRADER, LookTraderRenderer::new);
    }

    @SubscribeEvent
    @SuppressWarnings("unchecked")
    public static void addLayers(EntityRenderersEvent.AddLayers event) {
        var renderer = event.getRenderer(ModEntities.SETTLER.get());
        if (renderer instanceof LivingEntityRenderer<?, ?> living) {
            var settlerRenderer = (LivingEntityRenderer<SettlerEntity, SettlerModel>) living;
            settlerRenderer.addLayer(new SettlerAccessoryLayer(settlerRenderer, event.getEntityModels()));
        }
    }

    @SubscribeEvent
    public static void reload(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager -> LookTextureCache.clear());
    }

    /** Leaving a world frees every composed texture and decoded layer (game bus, render thread). */
    @SubscribeEvent
    public static void logout(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        LookTextureCache.clear();
    }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        CharacterLooks.heroHook = HeroHooks::isHero;
    }
}
