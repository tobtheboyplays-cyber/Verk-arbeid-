package com.hearthstead.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** Client -> server: learn one v3 tech node, refresh, or close the tree. */
public record TechTreeActionPayload(BlockPos hearthPos, UUID settlementId, int kind,
                                    String nodeId, int revision) implements CustomPacketPayload {
    public static final int LEARN = 0;
    public static final int REFRESH = 1;
    public static final int CLOSE = 2;

    public static final Type<TechTreeActionPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("hearthstead", "techtree_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TechTreeActionPayload> CODEC =
        StreamCodec.of(TechTreeActionPayload::write, TechTreeActionPayload::read);

    public TechTreeActionPayload {
        nodeId = nodeId == null ? "" : nodeId;
    }

    private static void write(RegistryFriendlyByteBuf buf, TechTreeActionPayload p) {
        buf.writeBlockPos(p.hearthPos);
        UUIDUtil.STREAM_CODEC.encode(buf, p.settlementId);
        buf.writeVarInt(p.kind);
        buf.writeUtf(p.nodeId, 64);
        buf.writeVarInt(p.revision);
    }

    private static TechTreeActionPayload read(RegistryFriendlyByteBuf buf) {
        return new TechTreeActionPayload(buf.readBlockPos(), UUIDUtil.STREAM_CODEC.decode(buf),
            buf.readVarInt(), buf.readUtf(64), buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
