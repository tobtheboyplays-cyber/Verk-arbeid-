package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import com.hearthstead.network.TechTreeActionPayload;
import com.hearthstead.network.TechTreeNetwork;
import com.hearthstead.network.TechTreeSnapshotPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Registers the v3 tech tree payloads (techtree_action / techtree_snapshot). */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class TechTreeNetworkRegistration {
    private TechTreeNetworkRegistration() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(ModBusEvents.NETWORK_PROTOCOL);
        registrar.playToServer(TechTreeActionPayload.TYPE, TechTreeActionPayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    TechTreeNetwork.handle(player, payload);
                }
            }));
        registrar.playToClient(com.hearthstead.network.TechKnowledgePayload.TYPE,
            com.hearthstead.network.TechKnowledgePayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> ModBusEvents.runClientOnly(FMLEnvironment.dist,
                () -> () -> com.hearthstead.client.techtree.TechKnowledgeClient.accept(payload))));
        registrar.playToClient(TechTreeSnapshotPayload.TYPE, TechTreeSnapshotPayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> ModBusEvents.runClientOnly(FMLEnvironment.dist,
                () -> () -> com.hearthstead.client.techtree.TechTreeClient.accept(payload))));
    }
}
