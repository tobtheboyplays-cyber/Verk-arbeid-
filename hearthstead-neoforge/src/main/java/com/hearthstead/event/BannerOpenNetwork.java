package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlock;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.block.SettlementBannerInteraction;
import com.hearthstead.network.BannerOpenPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * QA U9: a click on the Banner's pole or cloth opens the Banner exactly like
 * a click on its stand. The client only reports the position; the server
 * re-checks that a Banner is there, that the player is in reach and that the
 * player's view actually meets the cloth before opening anything.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class BannerOpenNetwork {
    /** Slack over the player's block reach for the cloth hit (it is a drawn shape, not a block). */
    static final double REACH_SLACK = 1.0D;

    private BannerOpenNetwork() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(ModBusEvents.NETWORK_PROTOCOL);
        registrar.playToServer(BannerOpenPayload.TYPE, BannerOpenPayload.CODEC, (payload, context) ->
            context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    handle(player, payload);
                }
            }));
    }

    static void handle(ServerPlayer player, BannerOpenPayload payload) {
        var level = player.serverLevel();
        if (!player.isAlive() || player.isSpectator() || !level.isLoaded(payload.pos())
            || !(level.getBlockEntity(payload.pos()) instanceof HearthBlockEntity hearth)) {
            return;
        }
        double reach = player.blockInteractionRange() + REACH_SLACK;
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(reach));
        if (SettlementBannerInteraction.clothBox(payload.pos()).inflate(0.25D).clip(eye, end).isEmpty()) {
            return; // not actually looking at this Banner's cloth within reach
        }
        HearthBlock.openFromUse(player, level, hearth);
    }
}
