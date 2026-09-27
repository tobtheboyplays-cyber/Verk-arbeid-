package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import com.hearthstead.client.ClientHooks;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.network.OpenSettlerScreenPayload;
import com.hearthstead.registry.ModBlockEntities;
import com.hearthstead.registry.ModEntities;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.function.Supplier;

@EventBusSubscriber(modid = Hearthstead.MODID)
public final class ModBusEvents {

    /**
     * NeoForge payload compatibility generation.
     *
     * <p>Bump this whenever the byte layout of an already-released payload
     * changes. Generation 8 adds the concrete active equipment request to the
     * settler inspection snapshot and the real settler-inventory menu.
     * Generation 9 adds bounded Guard Orders action/snapshot payloads while
     * retaining every existing action and mode wire id.
     * Generation 10 adds the Mayor's runtime entity id to Development
     * snapshots and appends the explicit Inspect Mayor action.
     * Generation 11 adds strict Work Scepter action/snapshot payloads.
     * Generation 12 adds equipment need reasons and unsourced proof to Hearth request rows.
     * Generation 17 adds physical Coin availability, the bounded resident
     * roster and component-aware Warehouse locations to UI snapshots.
     * Advertising an older generation would
     * let mismatched peers accept one another and decode those fields at the
     * wrong offsets.
     */
    // Generation 18 adds the exact employment revision to Staff snapshots/actions.
    public static final String NETWORK_PROTOCOL = "20";

    @SubscribeEvent
    public static void onAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(ModEntities.SETTLER.get(), SettlerEntity.createAttributes().build());
        event.put(ModEntities.RAIDER.get(),
            com.hearthstead.entity.RaiderEntity.createAttributes().build());
    }

    @SubscribeEvent
    public static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK,
            ModBlockEntities.HEARTH.get(), (hearth, side) -> hearth.getInventory());
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(NETWORK_PROTOCOL);
        registrar.playToClient(com.hearthstead.network.MerchantPursePayload.TYPE,
            com.hearthstead.network.MerchantPursePayload.CODEC,
            (payload, context) -> context.enqueueWork(
                () -> runClientOnly(FMLEnvironment.dist,
                    () -> () -> com.hearthstead.client.CoinMerchantScreenAdapter.acceptPurse(payload))));
        registrar.playToClient(com.hearthstead.network.BedMarkersPayload.TYPE,
            com.hearthstead.network.BedMarkersPayload.CODEC,
            (payload, context) -> context.enqueueWork(
                () -> runClientOnly(FMLEnvironment.dist,
                    () -> () -> com.hearthstead.client.BedMarkerRenderer.accept(payload))));
        registrar.playToClient(OpenSettlerScreenPayload.TYPE, OpenSettlerScreenPayload.CODEC,
            (payload, context) -> context.enqueueWork(
                () -> runClientOnly(FMLEnvironment.dist,
                    () -> () -> ClientHooks.openSettlerScreen(payload.entityId()))));
        registrar.playToClient(com.hearthstead.network.PlaqueSnapshot.TYPE,
            com.hearthstead.network.PlaqueSnapshot.CODEC,
            (payload, context) -> context.enqueueWork(
                () -> runClientOnly(FMLEnvironment.dist,
                    () -> () -> ClientHooks.showPlaque(payload))));
        registrar.playToServer(com.hearthstead.network.PlaqueAction.TYPE,
            com.hearthstead.network.PlaqueAction.CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                    com.hearthstead.network.PlaqueNetwork.handle(player, payload);
                }
            }));
        registrar.playToClient(com.hearthstead.network.StorageIndexPayload.TYPE,
            com.hearthstead.network.StorageIndexPayload.CODEC,
            (payload, context) -> context.enqueueWork(
                () -> runClientOnly(FMLEnvironment.dist,
                    () -> () -> ClientHooks.showStorage(payload))));
        registrar.playToServer(com.hearthstead.network.StorageRequestPayload.TYPE,
            com.hearthstead.network.StorageRequestPayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                    com.hearthstead.network.StorageNetwork.handleRequest(player);
                }
            }));
        registrar.playToClient(com.hearthstead.network.SettlerSnapshotPayload.TYPE,
            com.hearthstead.network.SettlerSnapshotPayload.CODEC,
            (payload, context) -> context.enqueueWork(
                () -> runClientOnly(FMLEnvironment.dist,
                    () -> () -> ClientHooks.showSettlerSnapshot(payload))));
        registrar.playToServer(com.hearthstead.network.SettlerActionPayload.TYPE,
            com.hearthstead.network.SettlerActionPayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                    com.hearthstead.network.SettlerNetwork.handle(player, payload);
                }
            }));
        registrar.playToClient(com.hearthstead.network.GuardOrderSnapshotPayload.TYPE,
            com.hearthstead.network.GuardOrderSnapshotPayload.CODEC,
            (payload, context) -> context.enqueueWork(
                () -> runClientOnly(FMLEnvironment.dist,
                    () -> () -> ClientHooks.showGuardOrder(payload))));
        registrar.playToServer(com.hearthstead.network.GuardOrderActionPayload.TYPE,
            com.hearthstead.network.GuardOrderActionPayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                    com.hearthstead.network.GuardOrderNetwork.handle(player, payload);
                }
            }));
        registrar.playToClient(com.hearthstead.network.HearthMayorSnapshot.TYPE,
            com.hearthstead.network.HearthMayorSnapshot.CODEC,
            (payload, context) -> context.enqueueWork(
                () -> runClientOnly(FMLEnvironment.dist,
                    () -> () -> ClientHooks.showHearthMayor(payload))));
        registrar.playToServer(com.hearthstead.network.HearthMayorAction.TYPE,
            com.hearthstead.network.HearthMayorAction.CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                    com.hearthstead.network.HearthNetwork.handle(player, payload);
                }
            }));
        registrar.playToClient(com.hearthstead.network.ResearchSnapshotPayload.TYPE,
            com.hearthstead.network.ResearchSnapshotPayload.CODEC,
            (payload, context) -> context.enqueueWork(
                () -> runClientOnly(FMLEnvironment.dist,
                    () -> () -> ClientHooks.showResearchSnapshot(payload))));
        registrar.playToServer(com.hearthstead.network.ResearchActionPayload.TYPE,
            com.hearthstead.network.ResearchActionPayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                    com.hearthstead.network.ResearchNetwork.handle(player, payload);
                }
            }));
        registrar.playToClient(com.hearthstead.network.DevelopmentSnapshotPayload.TYPE,
            com.hearthstead.network.DevelopmentSnapshotPayload.CODEC,
            (payload, context) -> context.enqueueWork(
                () -> runClientOnly(FMLEnvironment.dist,
                    () -> () -> ClientHooks.showDevelopmentSnapshot(payload))));
        registrar.playToServer(com.hearthstead.network.DevelopmentActionPayload.TYPE,
            com.hearthstead.network.DevelopmentActionPayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                    com.hearthstead.network.DevelopmentNetwork.handle(player, payload);
                }
            }));
        registrar.playToClient(com.hearthstead.network.BlessingSnapshotPayload.TYPE,
            com.hearthstead.network.BlessingSnapshotPayload.CODEC,
            (payload, context) -> context.enqueueWork(
                () -> runClientOnly(FMLEnvironment.dist,
                    () -> () -> ClientHooks.showBlessingSnapshot(payload))));
        registrar.playToServer(com.hearthstead.network.BlessingActionPayload.TYPE,
            com.hearthstead.network.BlessingActionPayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                    com.hearthstead.network.BlessingNetwork.handle(player, payload);
                }
            }));
        registrar.playToClient(com.hearthstead.network.EquipmentRequestListPayload.TYPE,
            com.hearthstead.network.EquipmentRequestListPayload.CODEC,
            (payload, context) -> context.enqueueWork(
                () -> runClientOnly(FMLEnvironment.dist,
                    () -> () -> ClientHooks.showEquipmentRequestList(payload))));
        registrar.playToServer(
            com.hearthstead.network.EquipmentRequestListRequestPayload.TYPE,
            com.hearthstead.network.EquipmentRequestListRequestPayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                    com.hearthstead.network.EquipmentRequestListNetwork.handle(
                        player, payload);
                }
            }));
        registrar.playToServer(
            com.hearthstead.network.EquipmentRequestMovePayload.TYPE,
            com.hearthstead.network.EquipmentRequestMovePayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                    com.hearthstead.network.EquipmentRequestListNetwork.handleMove(
                        player, payload);
                }
            }));
        registrar.playToClient(com.hearthstead.network.WorkZoneSnapshotPayload.TYPE,
            com.hearthstead.network.WorkZoneSnapshotPayload.CODEC,
            (payload, context) -> context.enqueueWork(
                () -> runClientOnly(FMLEnvironment.dist,
                    () -> () -> ClientHooks.showWorkZone(payload))));
        registrar.playToServer(com.hearthstead.network.WorkZoneActionPayload.TYPE,
            com.hearthstead.network.WorkZoneActionPayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                    com.hearthstead.settlement.workzone.WorkZoneService.handle(
                        player, payload);
                }
            }));
        registrar.playToServer(com.hearthstead.network.WorkZoneSelectionPayload.TYPE,
            com.hearthstead.network.WorkZoneSelectionPayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                    com.hearthstead.settlement.workzone.WorkZoneService.handleSelection(
                        player, payload);
                }
            }));
    }

    /**
     * The supplier is intentionally not evaluated on a dedicated server, so
     * its bytecode may refer to client-only Minecraft classes without making
     * common payload registration resolve those classes during server boot.
     */
    static boolean runClientOnly(Dist distribution,
                                 Supplier<Runnable> clientAction) {
        if (distribution != Dist.CLIENT) {
            return false;
        }
        clientAction.get().run();
        return true;
    }

    private ModBusEvents() {
    }
}
