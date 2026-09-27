package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: one field order (R = Knights, G = Archers). Every field is
 * re-validated by {@code FieldOrders.issue}; the client only proposes. Group
 * and kind use the frozen wire ids in {@code FieldOrderRules}.
 *
 * @param enemyEntityId network entity id of the aimed enemy, or -1
 */
public record FieldOrderRequestPayload(int group, int kind, BlockPos pos, int octant,
                                       int width, int enemyEntityId) implements CustomPacketPayload {
    public static final BlockPos NO_POS = new BlockPos(0, Integer.MIN_VALUE / 2, 0);
    public static final Type<FieldOrderRequestPayload> TYPE =
        new Type<>(Hearthstead.id("field_order_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FieldOrderRequestPayload> CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeVarInt(p.group);
            buf.writeVarInt(p.kind);
            buf.writeBoolean(p.hasPos());
            if (p.hasPos()) buf.writeBlockPos(p.pos);
            buf.writeByte(p.octant);
            buf.writeVarInt(p.width);
            buf.writeVarInt(p.enemyEntityId + 1);
        },
        buf -> {
            int group = buf.readVarInt();
            int kind = buf.readVarInt();
            BlockPos pos = buf.readBoolean() ? buf.readBlockPos() : NO_POS;
            int octant = buf.readByte();
            int width = buf.readVarInt();
            int enemy = buf.readVarInt() - 1;
            return new FieldOrderRequestPayload(group, kind, pos, octant, width, enemy);
        });

    public FieldOrderRequestPayload {
        if (pos == null) pos = NO_POS;
    }

    public boolean hasPos() {
        return pos != null && !NO_POS.equals(pos);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
