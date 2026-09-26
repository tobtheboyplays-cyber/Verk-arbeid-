package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import com.hearthstead.network.BannerOrderActionPayload;
import com.hearthstead.network.BannerOrderMenuPayload;
import com.hearthstead.network.BannerOrderNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@EventBusSubscriber(modid=Hearthstead.MODID)
public final class BannerNetworkRegistration {
    @SubscribeEvent public static void register(RegisterPayloadHandlersEvent event){
        var registrar=event.registrar(ModBusEvents.NETWORK_PROTOCOL);
        registrar.playToServer(BannerOrderActionPayload.TYPE,BannerOrderActionPayload.CODEC,(payload,context)->
            context.enqueueWork(()->{if(context.player() instanceof ServerPlayer player)BannerOrderNetwork.handle(player,payload);}));
        registrar.playToClient(BannerOrderMenuPayload.TYPE,BannerOrderMenuPayload.CODEC,(payload,context)->
            context.enqueueWork(()->ModBusEvents.runClientOnly(FMLEnvironment.dist,
                ()->()->com.hearthstead.client.BannerOrderClient.accept(payload))));
    }
    private BannerNetworkRegistration(){}
}
