package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import com.hearthstead.client.ClientHooks;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.network.OpenSettlerScreenPayload;
import com.hearthstead.registry.ModBlockEntities;
import com.hearthstead.registry.ModEntities;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = Hearthstead.MODID)
public final class ModBusEvents {

    @SubscribeEvent
    public static void onAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(ModEntities.SETTLER.get(), SettlerEntity.createAttributes().build());
    }

    @SubscribeEvent
    public static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK,
            ModBlockEntities.HEARTH.get(), (hearth, side) -> hearth.getInventory());
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(OpenSettlerScreenPayload.TYPE, OpenSettlerScreenPayload.CODEC,
            (payload, context) -> context.enqueueWork(
                () -> ClientHooks.openSettlerScreen(payload.entityId())));
        registrar.playToClient(com.hearthstead.network.PlaqueSnapshot.TYPE,
            com.hearthstead.network.PlaqueSnapshot.CODEC,
            (payload, context) -> context.enqueueWork(
                () -> ClientHooks.showPlaque(payload)));
        registrar.playToServer(com.hearthstead.network.PlaqueAction.TYPE,
            com.hearthstead.network.PlaqueAction.CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                    com.hearthstead.network.PlaqueNetwork.handle(player, payload);
                }
            }));
    }

    private ModBusEvents() {
    }
}
