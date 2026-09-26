package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import com.hearthstead.network.PatrolActionPayload;
import com.hearthstead.network.PatrolSnapshotPayload;
import com.hearthstead.settlement.guard.patrol.PatrolService;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Registers the patrol-route payloads (PATROL ROUTES lane). Two new payload
 * types; no existing payload's byte layout changes, so the network protocol
 * generation stays as it is.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class PatrolNetworkRegistration {
    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(ModBusEvents.NETWORK_PROTOCOL);
        registrar.playToServer(PatrolActionPayload.TYPE, PatrolActionPayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    PatrolService.handle(player, payload);
                }
            }));
        registrar.playToClient(PatrolSnapshotPayload.TYPE, PatrolSnapshotPayload.CODEC,
            (payload, context) -> context.enqueueWork(
                () -> ModBusEvents.runClientOnly(FMLEnvironment.dist,
                    () -> () -> com.hearthstead.client.patrol.PatrolClient.accept(payload))));
    }

    private PatrolNetworkRegistration() {
    }
}
