package com.hearthstead.client.guildmaster;

import com.hearthstead.Hearthstead;
import com.hearthstead.registry.GuildmasterEntities;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Client registration for the Guildmaster (mod bus, client only). */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class GuildmasterClientSetup {
    public static final ModelLayerLocation LAYER =
        new ModelLayerLocation(Hearthstead.id("guildmaster"), "main");

    private GuildmasterClientSetup() {
    }

    @SubscribeEvent
    public static void layers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(LAYER, GuildmasterModel::createLayer);
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(GuildmasterEntities.GUILDMASTER.get(), GuildmasterRenderer::new);
    }
}
