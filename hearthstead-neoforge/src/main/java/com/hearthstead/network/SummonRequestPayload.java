package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/**
 * Client to server: "summon this settler to me". The server re-validates
 * everything ({@code PlayerSummons.request}). {@code entityId} may be -1 when
 * the settler is not loaded on the client (e.g. from the Banner map); the
 * server then resolves it by UUID.
 */
public record SummonRequestPayload(int entityId, UUID settlerId) implements CustomPacketPayload {
    public static final Type<SummonRequestPayload> TYPE = new Type<>(Hearthstead.id("summon_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SummonRequestPayload> CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeVarInt(p.entityId + 1);
            buf.writeUUID(p.settlerId);
        },
        buf -> new SummonRequestPayload(buf.readVarInt() - 1, buf.readUUID()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
