package com.hearthstead.client.techtree;

import com.hearthstead.Hearthstead;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

/** Clears the custom tech tree icon cache whenever resources reload. */
@EventBusSubscriber(modid = Hearthstead.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class TechTreeClientEvents {
    private TechTreeClientEvents() {
    }

    @SubscribeEvent
    public static void onReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager -> TechTreeIcons.clear());
    }
}
