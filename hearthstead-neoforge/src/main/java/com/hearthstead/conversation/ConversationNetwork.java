package com.hearthstead.conversation;

import com.hearthstead.Hearthstead;
import com.hearthstead.conversation.net.ConvActionPayload;
import com.hearthstead.conversation.net.ConvBarterPayload;
import com.hearthstead.conversation.net.ConvClosePayload;
import com.hearthstead.conversation.net.ConvMarkersPayload;
import com.hearthstead.conversation.net.ConvStatePayload;
import com.hearthstead.event.ModBusEvents;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Conversation payloads. Client handlers are reached only through
 * {@link #clientOnly}, so a dedicated server never loads a
 * client class.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class ConversationNetwork {
    private ConversationNetwork() {
    }

    /** Same contract as ModBusEvents.runClientOnly: the supplier is never evaluated off the client. */
    private static void clientOnly(java.util.function.Supplier<Runnable> action) {
        if (FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.CLIENT) action.get().run();
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(ModBusEvents.NETWORK_PROTOCOL);
        registrar.playToServer(ConvActionPayload.TYPE, ConvActionPayload.CODEC, (payload, context) ->
            context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) ConversationService.handle(player, payload);
            }));
        registrar.playToClient(ConvStatePayload.TYPE, ConvStatePayload.CODEC, (payload, context) ->
            context.enqueueWork(() -> clientOnly(
                () -> () -> com.hearthstead.client.conversation.ConversationClient.state(payload))));
        registrar.playToClient(ConvClosePayload.TYPE, ConvClosePayload.CODEC, (payload, context) ->
            context.enqueueWork(() -> clientOnly(
                () -> () -> com.hearthstead.client.conversation.ConversationClient.close(payload))));
        registrar.playToClient(ConvBarterPayload.TYPE, ConvBarterPayload.CODEC, (payload, context) ->
            context.enqueueWork(() -> clientOnly(
                () -> () -> com.hearthstead.client.conversation.ConversationClient.barter(payload))));
        registrar.playToClient(ConvMarkersPayload.TYPE, ConvMarkersPayload.CODEC, (payload, context) ->
            context.enqueueWork(() -> clientOnly(
                () -> () -> com.hearthstead.client.conversation.ConversationClient.markers(payload))));
    }
}
