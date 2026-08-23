package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class HearthsteadNetwork {
    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        Hearthstead.id("main"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(OpenSettlerScreenPacket.class, id++,
                NetworkDirection.PLAY_TO_CLIENT)
            .encoder(OpenSettlerScreenPacket::encode)
            .decoder(OpenSettlerScreenPacket::new)
            .consumerMainThread(OpenSettlerScreenPacket::handle)
            .add();
    }

    public static void sendToPlayer(Object packet, ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    private HearthsteadNetwork() {
    }
}
