package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** S2C: open the settler inspection card for the given entity. */
public record OpenSettlerScreenPayload(int entityId) implements CustomPacketPayload {
    public static final Type<OpenSettlerScreenPayload> TYPE =
        new Type<>(Hearthstead.id("open_settler_screen"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenSettlerScreenPayload> CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, OpenSettlerScreenPayload::entityId,
            OpenSettlerScreenPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
