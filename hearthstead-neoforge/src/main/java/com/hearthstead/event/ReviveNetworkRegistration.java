package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import com.hearthstead.network.ReviveEventPayload;
import com.hearthstead.network.ReviveStatePayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Registers the two display-only revive payloads (wire ids revive_state / revive_event). */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class ReviveNetworkRegistration {
    private ReviveNetworkRegistration() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(ModBusEvents.NETWORK_PROTOCOL);
        registrar.playToClient(ReviveStatePayload.TYPE, ReviveStatePayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> ModBusEvents.runClientOnly(FMLEnvironment.dist,
                () -> () -> com.hearthstead.client.revive.DownedClient.acceptState(payload))));
        registrar.playToClient(ReviveEventPayload.TYPE, ReviveEventPayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> ModBusEvents.runClientOnly(FMLEnvironment.dist,
                () -> () -> com.hearthstead.client.revive.DownedClient.acceptEvent(payload))));
    }
}
