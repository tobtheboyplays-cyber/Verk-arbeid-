package com.hearthstead.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/**
 * Server-to-client sends that never throw from a tick.
 *
 * <p>NeoForge's {@link PacketDistributor} throws UnsupportedOperationException
 * for a connection that did not negotiate the payload's channel: GameTest mock
 * players, fake players, a proxy or vanilla client. A send inside a server or
 * level tick then crashes the whole server (integration captain, W1 06:28 on
 * the revive sync). Every proactive broadcast (a tick, tracking or pickup, not
 * the reply to a player's own request) goes through here. BH-20.
 */
public final class PayloadSend {
    private PayloadSend() {
    }

    /** True when this player's connection can receive {@code payload}. */
    public static boolean canReceive(ServerPlayer player, CustomPacketPayload payload) {
        return player != null && !(player instanceof FakePlayer) && player.connection != null
            && payload != null && NetworkRegistry.hasChannel(player.connection, payload.type().id());
    }

    /** Sends when the player can receive it; returns whether it was sent. */
    public static boolean toPlayer(ServerPlayer player, CustomPacketPayload payload) {
        if (!canReceive(player, payload)) {
            return false;
        }
        PacketDistributor.sendToPlayer(player, payload);
        return true;
    }

    /** To every player tracking {@code entity} that can receive the payload. */
    public static void toTracking(Entity entity, CustomPacketPayload payload) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        for (ServerPlayer player : level.getChunkSource().chunkMap.getPlayersWatching(entity)) {
            toPlayer(player, payload);
        }
    }
}
