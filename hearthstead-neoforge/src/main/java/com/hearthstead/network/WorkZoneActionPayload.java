package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.Optional;
import java.util.UUID;

/** One compare-and-commit action against an exact Work Scepter session. */
public record WorkZoneActionPayload(UUID sessionId, UUID settlementId,
                                    UUID buildingId, int typeWireId,
                                    Kind kind, int expectedRevision,
                                    Optional<BlockPos> corner)
    implements CustomPacketPayload {

    public enum Kind {
        SET_SECOND_CORNER(0), CONFIRM(1), CANCEL(2), SET_HEIGHT(3),
        SET_FIRST_CORNER(4), UNKNOWN(-1);

        private final int wireId;

        Kind(int wireId) {
            this.wireId = wireId;
        }

        int wireId() {
            return wireId;
        }

        static Kind fromWireId(int id) {
            return id == 0 ? SET_SECOND_CORNER
                : id == 1 ? CONFIRM : id == 2 ? CANCEL
                : id == 3 ? SET_HEIGHT : id == 4 ? SET_FIRST_CORNER : UNKNOWN;
        }
    }

    public static final Type<WorkZoneActionPayload> TYPE = new Type<>(
        Hearthstead.id("work_zone_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf,
        WorkZoneActionPayload> CODEC = StreamCodec.of(
            WorkZoneActionPayload::write, WorkZoneActionPayload::read);

    public WorkZoneActionPayload {
        sessionId = sessionId == null ? SettlerActionPayload.NO_SETTLER : sessionId;
        settlementId = settlementId == null ? SettlerActionPayload.NO_SETTLER
            : settlementId;
        buildingId = buildingId == null ? SettlerActionPayload.NO_SETTLER : buildingId;
        kind = kind == null ? Kind.UNKNOWN : kind;
        corner = corner == null ? Optional.empty() : corner.map(BlockPos::immutable);
    }

    private static void write(RegistryFriendlyByteBuf buf,
                              WorkZoneActionPayload payload) {
        UUIDUtil.STREAM_CODEC.encode(buf, payload.sessionId);
        UUIDUtil.STREAM_CODEC.encode(buf, payload.settlementId);
        UUIDUtil.STREAM_CODEC.encode(buf, payload.buildingId);
        buf.writeVarInt(payload.typeWireId);
        buf.writeVarInt(payload.kind.wireId);
        buf.writeVarInt(payload.expectedRevision);
        buf.writeBoolean(payload.corner.isPresent());
        payload.corner.ifPresent(pos -> BlockPos.STREAM_CODEC.encode(buf, pos));
    }

    private static WorkZoneActionPayload read(RegistryFriendlyByteBuf buf) {
        UUID session = UUIDUtil.STREAM_CODEC.decode(buf);
        UUID settlement = UUIDUtil.STREAM_CODEC.decode(buf);
        UUID building = UUIDUtil.STREAM_CODEC.decode(buf);
        int type = buf.readVarInt();
        Kind kind = Kind.fromWireId(buf.readVarInt());
        int revision = buf.readVarInt();
        Optional<BlockPos> corner = buf.readBoolean()
            ? Optional.of(BlockPos.STREAM_CODEC.decode(buf)) : Optional.empty();
        return new WorkZoneActionPayload(session, settlement, building, type,
            kind, revision, corner);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
