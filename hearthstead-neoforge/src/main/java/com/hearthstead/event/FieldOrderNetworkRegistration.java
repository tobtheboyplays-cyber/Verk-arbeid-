package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import com.hearthstead.network.FieldOrderRequestPayload;
import com.hearthstead.network.FieldOrderStatePayload;
import com.hearthstead.network.SummonRequestPayload;
import com.hearthstead.network.SummonStatePayload;
import com.hearthstead.settlement.guard.FieldOrders;
import com.hearthstead.settlement.summon.PlayerSummons;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Wire registration for field orders (R/G...) and player summons. Handlers run on the main thread. */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class FieldOrderNetworkRegistration {
    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(ModBusEvents.NETWORK_PROTOCOL);
        registrar.playToServer(FieldOrderRequestPayload.TYPE, FieldOrderRequestPayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) FieldOrders.issue(player, payload);
            }));
        registrar.playToClient(FieldOrderStatePayload.TYPE, FieldOrderStatePayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> ModBusEvents.runClientOnly(FMLEnvironment.dist,
                () -> () -> com.hearthstead.client.command.CommandClientState.accept(payload))));
        registrar.playToServer(SummonRequestPayload.TYPE, SummonRequestPayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    PlayerSummons.request(player, payload.entityId(), payload.settlerId());
                }
            }));
        registrar.playToClient(SummonStatePayload.TYPE, SummonStatePayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> ModBusEvents.runClientOnly(FMLEnvironment.dist,
                () -> () -> com.hearthstead.client.command.SummonClient.accept(payload))));
    }

    private FieldOrderNetworkRegistration() {
    }
}
