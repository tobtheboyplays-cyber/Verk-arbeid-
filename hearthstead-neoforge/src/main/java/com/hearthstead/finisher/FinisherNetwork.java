package com.hearthstead.finisher;

import com.hearthstead.Hearthstead;
import com.hearthstead.event.ModBusEvents;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Registers the finisher request (C2S) and presentation (S2C) payloads. */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class FinisherNetwork {
    private FinisherNetwork() {
    }

    /** Runs only on a physical client; the supplier keeps client classes unloaded elsewhere. */
    private static void onClient(java.util.function.Supplier<Runnable> action) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            action.get().run();
        }
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(ModBusEvents.NETWORK_PROTOCOL);
        registrar.playToServer(FinisherPayloads.Request.TYPE, FinisherPayloads.Request.CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    FinisherService.handleRequest(player, payload.targetId());
                }
            }));
        registrar.playToClient(FinisherPayloads.Window.TYPE, FinisherPayloads.Window.CODEC,
            (payload, context) -> context.enqueueWork(() -> onClient(
                () -> () -> com.hearthstead.client.finisher.FinisherClient.acceptWindow(payload))));
        registrar.playToClient(FinisherPayloads.Start.TYPE, FinisherPayloads.Start.CODEC,
            (payload, context) -> context.enqueueWork(() -> onClient(
                () -> () -> com.hearthstead.client.finisher.FinisherClient.acceptStart(payload))));
        registrar.playToClient(FinisherPayloads.Balance.TYPE, FinisherPayloads.Balance.CODEC,
            (payload, context) -> context.enqueueWork(() -> onClient(
                () -> () -> com.hearthstead.client.finisher.FinisherClient.acceptBalance(payload))));
        registrar.playToClient(FinisherPayloads.End.TYPE, FinisherPayloads.End.CODEC,
            (payload, context) -> context.enqueueWork(() -> onClient(
                () -> () -> com.hearthstead.client.finisher.FinisherClient.acceptEnd(payload))));
    }
}
