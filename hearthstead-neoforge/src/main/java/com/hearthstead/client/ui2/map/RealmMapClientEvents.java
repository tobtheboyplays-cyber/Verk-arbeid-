package com.hearthstead.client.ui2.map;

import com.hearthstead.Hearthstead;
import com.hearthstead.client.screen.HearthScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/** Leaving a world drops the realm map's client state and any queued "show on map" focus. */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class RealmMapClientEvents {
    private RealmMapClientEvents() {
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        RealmMapClient.reset();
        HearthScreen.clearPendingMapFocus();
    }
}
