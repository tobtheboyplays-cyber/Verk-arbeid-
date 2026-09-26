package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/** One narrow, revision-checked Request Queue drag commit. */
public record EquipmentRequestMovePayload(int courierEntityId,
                                          UUID courierId,
                                          UUID sessionId,
                                          UUID settlementId,
                                          long queueRevision,
                                          UUID movedRequestId,
                                          UUID beforeRequestId)
    implements CustomPacketPayload {

    /** Nil means append after the currently visible/complete queue. */
    public static final UUID END = new UUID(0L, 0L);

    public static final Type<EquipmentRequestMovePayload> TYPE =
        new Type<>(Hearthstead.id("equipment_request_move"));

    public static final StreamCodec<RegistryFriendlyByteBuf,
        EquipmentRequestMovePayload> CODEC = StreamCodec.of(
            EquipmentRequestMovePayload::write,
            EquipmentRequestMovePayload::read);

    private static void write(RegistryFriendlyByteBuf buf,
                              EquipmentRequestMovePayload move) {
        buf.writeVarInt(move.courierEntityId);
        UUIDUtil.STREAM_CODEC.encode(buf, move.courierId);
        UUIDUtil.STREAM_CODEC.encode(buf, move.sessionId);
        UUIDUtil.STREAM_CODEC.encode(buf, move.settlementId);
        buf.writeVarLong(move.queueRevision);
        UUIDUtil.STREAM_CODEC.encode(buf, move.movedRequestId);
        UUIDUtil.STREAM_CODEC.encode(buf, move.beforeRequestId);
    }

    private static EquipmentRequestMovePayload read(
            RegistryFriendlyByteBuf buf) {
        return new EquipmentRequestMovePayload(buf.readVarInt(),
            UUIDUtil.STREAM_CODEC.decode(buf),
            UUIDUtil.STREAM_CODEC.decode(buf),
            UUIDUtil.STREAM_CODEC.decode(buf), buf.readVarLong(),
            UUIDUtil.STREAM_CODEC.decode(buf),
            UUIDUtil.STREAM_CODEC.decode(buf));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
