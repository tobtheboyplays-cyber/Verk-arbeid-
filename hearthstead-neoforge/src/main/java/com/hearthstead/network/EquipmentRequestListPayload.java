package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Bounded S2C snapshot for the selected Courier's request-list tab.
 * Every row is server-derived; the client may display it but never mutate it.
 */
public record EquipmentRequestListPayload(int courierEntityId,
                                          UUID courierId,
                                          UUID sessionId,
                                          UUID settlementId,
                                          long queueRevision,
                                          boolean canReorder,
                                          long serverGameTime,
                                          int totalRequests,
                                          List<Row> rows,
                                          UUID nextRequestId)
    implements CustomPacketPayload {

    /** UI/network budget. Remaining rows are represented by totalRequests. */
    public static final int MAX_ROWS = 64;

    public record Row(UUID requestId, UUID requesterId, String requesterName,
                      UUID destinationBuildingId, String destinationType,
                      int queuePosition, int priorityWireId, int statusWireId,
                      ItemStack item, int count, int reasonWireId,
                      int requestStateWireId, int blockerWireId,
                      int physicalOwnerWireId, long ageTicks,
                      String courierName, boolean fullTransportTrace) {
        public Row {
            requesterName = requesterName == null ? "" : requesterName;
            destinationType = destinationType == null ? "" : destinationType;
            courierName = courierName == null ? "" : courierName;
            item = item == null ? ItemStack.EMPTY : item.copy();
            count = Math.max(1, count);
            queuePosition = Math.max(1, queuePosition);
            ageTicks = Math.max(-1L, ageTicks);
        }
    }

    public static final Type<EquipmentRequestListPayload> TYPE =
        new Type<>(Hearthstead.id("equipment_request_list"));

    public static final StreamCodec<RegistryFriendlyByteBuf,
        EquipmentRequestListPayload> CODEC = StreamCodec.of(
            EquipmentRequestListPayload::write,
            EquipmentRequestListPayload::read);

    public EquipmentRequestListPayload {
        courierId = courierId == null ? new UUID(0L, 0L) : courierId;
        sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
        settlementId = settlementId == null ? new UUID(0L, 0L) : settlementId;
        nextRequestId = nextRequestId == null ? new UUID(0L, 0L)
            : nextRequestId;
        rows = List.copyOf(rows == null ? List.of()
            : rows.subList(0, Math.min(MAX_ROWS, rows.size())));
        totalRequests = Math.max(rows.size(), totalRequests);
    }

    private static void write(RegistryFriendlyByteBuf buf,
                              EquipmentRequestListPayload snapshot) {
        buf.writeVarInt(snapshot.courierEntityId);
        UUIDUtil.STREAM_CODEC.encode(buf, snapshot.courierId);
        UUIDUtil.STREAM_CODEC.encode(buf, snapshot.sessionId);
        UUIDUtil.STREAM_CODEC.encode(buf, snapshot.settlementId);
        buf.writeVarLong(snapshot.queueRevision);
        buf.writeBoolean(snapshot.canReorder);
        buf.writeVarLong(snapshot.serverGameTime);
        buf.writeVarInt(snapshot.totalRequests);
        buf.writeVarInt(snapshot.rows.size());
        for (Row row : snapshot.rows) {
            UUIDUtil.STREAM_CODEC.encode(buf, row.requestId);
            UUIDUtil.STREAM_CODEC.encode(buf, row.requesterId);
            buf.writeUtf(row.requesterName, 64);
            UUIDUtil.STREAM_CODEC.encode(buf, row.destinationBuildingId);
            buf.writeUtf(row.destinationType, 64);
            buf.writeVarInt(row.queuePosition);
            buf.writeVarInt(row.priorityWireId);
            buf.writeVarInt(row.statusWireId);
            ItemStack.STREAM_CODEC.encode(buf, row.item);
            buf.writeVarInt(row.count);
            buf.writeVarInt(row.reasonWireId);
            buf.writeVarInt(row.requestStateWireId);
            buf.writeVarInt(row.blockerWireId);
            buf.writeVarInt(row.physicalOwnerWireId);
            buf.writeVarLong(row.ageTicks);
            buf.writeUtf(row.courierName, 64);
            buf.writeBoolean(row.fullTransportTrace);
        }
        UUIDUtil.STREAM_CODEC.encode(buf, snapshot.nextRequestId);
    }

    private static EquipmentRequestListPayload read(RegistryFriendlyByteBuf buf) {
        int courierEntityId = buf.readVarInt();
        UUID courierId = UUIDUtil.STREAM_CODEC.decode(buf);
        UUID sessionId = UUIDUtil.STREAM_CODEC.decode(buf);
        UUID settlementId = UUIDUtil.STREAM_CODEC.decode(buf);
        long queueRevision = buf.readVarLong();
        boolean canReorder = buf.readBoolean();
        long serverGameTime = buf.readVarLong();
        int totalRequests = Math.max(0, buf.readVarInt());
        int rowCount = buf.readVarInt();
        if (rowCount < 0 || rowCount > MAX_ROWS) {
            throw new IllegalArgumentException(
                "equipment request snapshot rows out of range: " + rowCount);
        }
        List<Row> rows = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            rows.add(new Row(UUIDUtil.STREAM_CODEC.decode(buf),
                UUIDUtil.STREAM_CODEC.decode(buf), buf.readUtf(64),
                UUIDUtil.STREAM_CODEC.decode(buf), buf.readUtf(64),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                ItemStack.STREAM_CODEC.decode(buf),
                Math.max(1, buf.readVarInt()), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarLong(), buf.readUtf(64), buf.readBoolean()));
        }
        UUID nextRequestId = UUIDUtil.STREAM_CODEC.decode(buf);
        return new EquipmentRequestListPayload(courierEntityId, courierId,
            sessionId, settlementId, queueRevision, canReorder,
            serverGameTime, totalRequests, rows, nextRequestId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
