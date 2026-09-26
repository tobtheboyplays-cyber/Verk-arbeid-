package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import com.hearthstead.network.RealmMapLayoutPayload;
import com.hearthstead.network.RealmMapMarkersPayload;
import com.hearthstead.network.RealmMapNetwork;
import com.hearthstead.network.RealmMapRequestPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Registers the Banner screen's live realm map payloads. These are three new
 * payload types; no existing payload's byte layout changes, so the network
 * protocol generation stays as it is.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class RealmMapNetworkRegistration {
    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(ModBusEvents.NETWORK_PROTOCOL);
        registrar.playToServer(RealmMapRequestPayload.TYPE, RealmMapRequestPayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    RealmMapNetwork.handle(player, payload);
                }
            }));
        registrar.playToClient(RealmMapLayoutPayload.TYPE, RealmMapLayoutPayload.CODEC,
            (payload, context) -> context.enqueueWork(
                () -> ModBusEvents.runClientOnly(FMLEnvironment.dist,
                    () -> () -> com.hearthstead.client.ui2.map.RealmMapClient.acceptLayout(payload))));
        registrar.playToClient(RealmMapMarkersPayload.TYPE, RealmMapMarkersPayload.CODEC,
            (payload, context) -> context.enqueueWork(
                () -> ModBusEvents.runClientOnly(FMLEnvironment.dist,
                    () -> () -> com.hearthstead.client.ui2.map.RealmMapClient.acceptMarkers(payload))));
    }

    private RealmMapNetworkRegistration() {
    }
}
