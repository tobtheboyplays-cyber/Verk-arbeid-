package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import com.hearthstead.network.PickupNoticePayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Registers the display-only pickup notice payload (server to client). */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class PickupNoticeNetworkRegistration {
    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(ModBusEvents.NETWORK_PROTOCOL);
        registrar.playToClient(PickupNoticePayload.TYPE, PickupNoticePayload.CODEC,
            (payload, context) -> context.enqueueWork(
                () -> ModBusEvents.runClientOnly(FMLEnvironment.dist,
                    () -> () -> com.hearthstead.client.pickup.PickupNoticeHud.accept(payload))));
    }

    private PickupNoticeNetworkRegistration() {
    }
}
