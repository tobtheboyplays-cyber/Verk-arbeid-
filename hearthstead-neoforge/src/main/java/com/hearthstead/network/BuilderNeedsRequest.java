package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.UUID;

/** Only an existing server-issued needs session may refresh or open its inventory. */
public record BuilderNeedsRequest(int entityId, UUID builderId, UUID sessionId, int action)
    implements CustomPacketPayload {
    public static final int REFRESH = 0, INVENTORY = 1, CLOSE = 2;
    public static final Type<BuilderNeedsRequest> TYPE = new Type<>(Hearthstead.id("builder_needs_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BuilderNeedsRequest> CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeVarInt(p.entityId);
            UUIDUtil.STREAM_CODEC.encode(buf, p.builderId);
            UUIDUtil.STREAM_CODEC.encode(buf, p.sessionId);
            buf.writeVarInt(p.action);
        }, buf -> new BuilderNeedsRequest(buf.readVarInt(), UUIDUtil.STREAM_CODEC.decode(buf),
            UUIDUtil.STREAM_CODEC.decode(buf), buf.readVarInt()));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
