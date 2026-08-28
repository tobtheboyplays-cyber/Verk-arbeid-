package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * C2S: "show me the stores of the settlement I am standing in."
 *
 * <p>Carries no arguments on purpose. The server resolves which settlement
 * the sender is in from the sender's own position, so a client cannot ask
 * about a settlement it is not standing in.
 */
public record StorageRequestPayload() implements CustomPacketPayload {
    public static final Type<StorageRequestPayload> TYPE =
        new Type<>(Hearthstead.id("storage_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StorageRequestPayload> CODEC =
        StreamCodec.unit(new StorageRequestPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
