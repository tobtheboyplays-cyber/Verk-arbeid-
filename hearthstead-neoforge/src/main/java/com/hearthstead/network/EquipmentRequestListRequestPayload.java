package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/** C2S refresh for one exact, still-open Courier inspection session. */
public record EquipmentRequestListRequestPayload(int courierEntityId,
                                                 UUID courierId,
                                                 UUID sessionId)
    implements CustomPacketPayload {

    public static final Type<EquipmentRequestListRequestPayload> TYPE =
        new Type<>(Hearthstead.id("equipment_request_list_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf,
        EquipmentRequestListRequestPayload> CODEC = StreamCodec.of(
            EquipmentRequestListRequestPayload::write,
            EquipmentRequestListRequestPayload::read);

    private static void write(RegistryFriendlyByteBuf buf,
                              EquipmentRequestListRequestPayload request) {
        buf.writeVarInt(request.courierEntityId);
        UUIDUtil.STREAM_CODEC.encode(buf, request.courierId);
        UUIDUtil.STREAM_CODEC.encode(buf, request.sessionId);
    }

    private static EquipmentRequestListRequestPayload read(
            RegistryFriendlyByteBuf buf) {
        return new EquipmentRequestListRequestPayload(buf.readVarInt(),
            UUIDUtil.STREAM_CODEC.decode(buf),
            UUIDUtil.STREAM_CODEC.decode(buf));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
