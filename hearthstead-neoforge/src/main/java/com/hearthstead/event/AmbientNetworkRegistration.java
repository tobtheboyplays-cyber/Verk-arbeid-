package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import com.hearthstead.network.AmbientCuePayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Registers the display-only living-village cue payload (server to client). */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class AmbientNetworkRegistration {
    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(ModBusEvents.NETWORK_PROTOCOL);
        registrar.playToClient(AmbientCuePayload.TYPE, AmbientCuePayload.CODEC,
            (payload, context) -> context.enqueueWork(
                () -> ModBusEvents.runClientOnly(FMLEnvironment.dist,
                    () -> () -> com.hearthstead.client.ambient.AmbientClient.accept(payload))));
    }

    private AmbientNetworkRegistration() {
    }
}
