package com.hearthstead.entity.combat.captain;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.event.ModBusEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Registration and sending for the Captain packets (server authority, client view). */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class CaptainNetwork {
    private CaptainNetwork() {
    }

    private static void clientOnly(java.util.function.Supplier<Runnable> action) {
        if (FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.CLIENT) {
            action.get().run();
        }
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(ModBusEvents.NETWORK_PROTOCOL);
        registrar.playToServer(CaptainPayloads.Action.TYPE, CaptainPayloads.Action.CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    CaptainService.handle(player, payload);
                }
            }));
        registrar.playToClient(CaptainPayloads.State.TYPE, CaptainPayloads.State.CODEC,
            (payload, context) -> context.enqueueWork(() -> clientOnly(
                () -> () -> com.hearthstead.client.captain.CaptainClient.state(payload))));
    }

    public static CaptainPayloads.State snapshot(SettlerEntity settler, String feedback) {
        boolean hero = CaptainStatus.isHero(settler);
        CaptainState cs = CaptainWorld.stateOf(settler);
        long now = settler.level().getGameTime();
        CaptainSpecial[] all = CaptainSpecial.values();
        int[] cds = new int[all.length];
        java.util.List<CaptainSpecial> avail = CaptainSpecial.available(cs.loadout());
        for (int i = 0; i < all.length; i++) {
            if (!avail.contains(all[i])) {
                cds[i] = -1;
            } else if (all[i].oncePerFight()) {
                cds[i] = cs.secondWindUsed() ? 1 : 0;
            } else {
                cds[i] = cs.cooldownLeft(all[i], now);
            }
        }
        CaptainLoadout held = CaptainKit.heldLoadout(settler);
        int rearm = cs.rearming(now) ? CaptainState.REARM_TICKS : 0;
        return new CaptainPayloads.State(settler.getId(), hero, cs.loadout().wireId(), cs.capeColour(),
            cs.plume(), cs.promptPending(), rearm, cds, held == null ? -1 : held.wireId(),
            feedback == null ? "" : feedback);
    }

    /** Everyone who can see him (and nobody else) learns his hero state. */
    public static void broadcastState(SettlerEntity settler) {
        if (settler.level() instanceof ServerLevel) {
            PacketDistributor.sendToPlayersTrackingEntity(settler, snapshot(settler, ""));
        }
    }

    public static void sendTo(ServerPlayer player, SettlerEntity settler, String feedback) {
        PacketDistributor.sendToPlayer(player, snapshot(settler, feedback));
    }

    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof SettlerEntity settler
            && event.getEntity() instanceof ServerPlayer player
            && CaptainStatus.isHero(settler)) {
            sendTo(player, settler, "");
        }
    }
}
