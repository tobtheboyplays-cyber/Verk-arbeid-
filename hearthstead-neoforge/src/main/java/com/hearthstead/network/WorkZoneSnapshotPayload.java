package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.Optional;
import java.util.UUID;

/** Server-authored Work Scepter state used by both preview and confirm UI. */
public record WorkZoneSnapshotPayload(UUID sessionId, UUID settlementId,
                                      UUID buildingId, int typeWireId,
                                      String dimension, int expectedRevision,
                                      Stage stage, Optional<BlockPos> cornerOne,
                                      Optional<BlockPos> cornerTwo,
                                      Component workplace,
                                      Optional<Component> feedback)
    implements CustomPacketPayload {

    public enum Stage {
        TARGET_SELECTED(0), CORNER_ONE(1), PREVIEW_READY(2),
        COMMITTED(3), CANCELLED(4), REJECTED(5), RESET(6),
        CORNER_TWO(7), UNKNOWN(-1);

        private final int wireId;

        Stage(int wireId) {
            this.wireId = wireId;
        }

        int wireId() {
            return wireId;
        }

        static Stage fromWireId(int id) {
            return switch (id) {
                case 0 -> TARGET_SELECTED;
                case 1 -> CORNER_ONE;
                case 2 -> PREVIEW_READY;
                case 3 -> COMMITTED;
                case 4 -> CANCELLED;
                case 5 -> REJECTED;
                case 6 -> RESET;
                case 7 -> CORNER_TWO;
                default -> UNKNOWN;
            };
        }
    }

    public static final Type<WorkZoneSnapshotPayload> TYPE = new Type<>(
        Hearthstead.id("work_zone_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf,
        WorkZoneSnapshotPayload> CODEC = StreamCodec.of(
            WorkZoneSnapshotPayload::write, WorkZoneSnapshotPayload::read);

    public WorkZoneSnapshotPayload {
        sessionId = sessionId == null ? SettlerActionPayload.NO_SETTLER : sessionId;
        settlementId = settlementId == null ? SettlerActionPayload.NO_SETTLER
            : settlementId;
        buildingId = buildingId == null ? SettlerActionPayload.NO_SETTLER : buildingId;
        dimension = dimension == null ? "" : dimension;
        stage = stage == null ? Stage.UNKNOWN : stage;
        cornerOne = cornerOne == null ? Optional.empty()
            : cornerOne.map(BlockPos::immutable);
        cornerTwo = cornerTwo == null ? Optional.empty()
            : cornerTwo.map(BlockPos::immutable);
        workplace = workplace == null ? Component.empty() : workplace;
        feedback = feedback == null ? Optional.empty() : feedback;
    }

    private static void write(RegistryFriendlyByteBuf buf,
                              WorkZoneSnapshotPayload payload) {
        UUIDUtil.STREAM_CODEC.encode(buf, payload.sessionId);
        UUIDUtil.STREAM_CODEC.encode(buf, payload.settlementId);
        UUIDUtil.STREAM_CODEC.encode(buf, payload.buildingId);
        buf.writeVarInt(payload.typeWireId);
        buf.writeUtf(payload.dimension, 128);
        buf.writeVarInt(payload.expectedRevision);
        buf.writeVarInt(payload.stage.wireId);
        buf.writeBoolean(payload.cornerOne.isPresent());
        payload.cornerOne.ifPresent(pos -> BlockPos.STREAM_CODEC.encode(buf, pos));
        buf.writeBoolean(payload.cornerTwo.isPresent());
        payload.cornerTwo.ifPresent(pos -> BlockPos.STREAM_CODEC.encode(buf, pos));
        ComponentSerialization.STREAM_CODEC.encode(buf, payload.workplace);
        ComponentSerialization.OPTIONAL_STREAM_CODEC.encode(buf, payload.feedback);
    }

    private static WorkZoneSnapshotPayload read(RegistryFriendlyByteBuf buf) {
        UUID session = UUIDUtil.STREAM_CODEC.decode(buf);
        UUID settlement = UUIDUtil.STREAM_CODEC.decode(buf);
        UUID building = UUIDUtil.STREAM_CODEC.decode(buf);
        int type = buf.readVarInt();
        String dimension = buf.readUtf(128);
        int revision = buf.readVarInt();
        Stage stage = Stage.fromWireId(buf.readVarInt());
        Optional<BlockPos> first = buf.readBoolean()
            ? Optional.of(BlockPos.STREAM_CODEC.decode(buf)) : Optional.empty();
        Optional<BlockPos> second = buf.readBoolean()
            ? Optional.of(BlockPos.STREAM_CODEC.decode(buf)) : Optional.empty();
        Component workplace = ComponentSerialization.STREAM_CODEC.decode(buf);
        Optional<Component> feedback =
            ComponentSerialization.OPTIONAL_STREAM_CODEC.decode(buf);
        return new WorkZoneSnapshotPayload(session, settlement, building,
            type, dimension, revision, stage, first, second, workplace,
            feedback);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
