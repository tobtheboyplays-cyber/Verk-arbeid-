package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import com.hearthstead.settlement.state.GuardOrder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Bounded server truth for the Guard Orders child screen. */
public record GuardOrderSnapshotPayload(int guardEntityId, UUID guardId,
                                        UUID sessionId, UUID settlementId,
                                        int revision, int modeWireId,
                                        Optional<BlockPos> destination,
                                        List<BlockPos> patrolPoints,
                                        int traversalWireId,
                                        int facingWireId,
                                        int leashRadius,
                                        int facingArc,
                                        Optional<UUID> linkedBuildingId,
                                        boolean canManage,
                                        boolean equipmentReady,
                                        boolean towerPostAvailable,
                                        Outcome outcome,
                                        Optional<Component> feedback)
    implements CustomPacketPayload {

    public enum Outcome {
        NEUTRAL(0), APPLIED(1), REFUSED(2);

        private final int wireId;

        Outcome(int wireId) {
            this.wireId = wireId;
        }

        static Outcome fromWireId(int id) {
            return id == 1 ? APPLIED : id == 2 ? REFUSED : NEUTRAL;
        }
    }

    public static final Type<GuardOrderSnapshotPayload> TYPE = new Type<>(
        Hearthstead.id("guard_order_snapshot"));

    public static final StreamCodec<RegistryFriendlyByteBuf,
        GuardOrderSnapshotPayload> CODEC = StreamCodec.of(
            GuardOrderSnapshotPayload::write, GuardOrderSnapshotPayload::read);

    public GuardOrderSnapshotPayload {
        guardId = guardId == null ? SettlerActionPayload.NO_SETTLER : guardId;
        sessionId = sessionId == null ? SettlerActionPayload.NO_SETTLER : sessionId;
        settlementId = settlementId == null ? SettlerActionPayload.NO_SETTLER
            : settlementId;
        destination = destination == null ? Optional.empty()
            : destination.map(BlockPos::immutable);
        patrolPoints = List.copyOf(patrolPoints == null ? List.of()
            : patrolPoints.subList(0, Math.min(GuardOrder.MAX_PATROL_POINTS,
                patrolPoints.size())));
        traversalWireId = GuardOrder.Traversal.tryFromWireId(traversalWireId)
            .map(GuardOrder.Traversal::wireId)
            .orElse(GuardOrder.Traversal.LOOP.wireId());
        facingWireId = Math.max(0, Math.min(5, facingWireId));
        leashRadius = Math.max(GuardOrder.MIN_LEASH_RADIUS,
            Math.min(GuardOrder.MAX_LEASH_RADIUS, leashRadius));
        facingArc = Math.max(GuardOrder.MIN_FACING_ARC,
            Math.min(GuardOrder.MAX_FACING_ARC, facingArc));
        linkedBuildingId = linkedBuildingId == null ? Optional.empty()
            : linkedBuildingId;
        outcome = outcome == null ? Outcome.NEUTRAL : outcome;
        feedback = feedback == null ? Optional.empty() : feedback;
    }

    private static void write(RegistryFriendlyByteBuf buf,
                              GuardOrderSnapshotPayload snapshot) {
        buf.writeVarInt(snapshot.guardEntityId);
        UUIDUtil.STREAM_CODEC.encode(buf, snapshot.guardId);
        UUIDUtil.STREAM_CODEC.encode(buf, snapshot.sessionId);
        UUIDUtil.STREAM_CODEC.encode(buf, snapshot.settlementId);
        buf.writeVarInt(snapshot.revision);
        buf.writeVarInt(snapshot.modeWireId);
        buf.writeBoolean(snapshot.destination.isPresent());
        snapshot.destination.ifPresent(pos -> BlockPos.STREAM_CODEC.encode(buf, pos));
        buf.writeVarInt(snapshot.patrolPoints.size());
        for (BlockPos point : snapshot.patrolPoints) {
            BlockPos.STREAM_CODEC.encode(buf, point);
        }
        buf.writeVarInt(snapshot.traversalWireId);
        buf.writeVarInt(snapshot.facingWireId);
        buf.writeVarInt(snapshot.leashRadius);
        buf.writeVarInt(snapshot.facingArc);
        buf.writeBoolean(snapshot.linkedBuildingId.isPresent());
        snapshot.linkedBuildingId.ifPresent(id -> UUIDUtil.STREAM_CODEC.encode(
            buf, id));
        buf.writeBoolean(snapshot.canManage);
        buf.writeBoolean(snapshot.equipmentReady);
        buf.writeBoolean(snapshot.towerPostAvailable);
        buf.writeByte(snapshot.outcome.wireId);
        ComponentSerialization.OPTIONAL_STREAM_CODEC.encode(buf,
            snapshot.feedback);
    }

    private static GuardOrderSnapshotPayload read(
            RegistryFriendlyByteBuf buf) {
        int entityId = buf.readVarInt();
        UUID guardId = UUIDUtil.STREAM_CODEC.decode(buf);
        UUID sessionId = UUIDUtil.STREAM_CODEC.decode(buf);
        UUID settlementId = UUIDUtil.STREAM_CODEC.decode(buf);
        int revision = buf.readVarInt();
        int mode = buf.readVarInt();
        Optional<BlockPos> destination = buf.readBoolean()
            ? Optional.of(BlockPos.STREAM_CODEC.decode(buf)) : Optional.empty();
        int count = buf.readVarInt();
        if (count < 0 || count > GuardOrder.MAX_PATROL_POINTS) {
            throw new IllegalArgumentException(
                "guard patrol point count out of range: " + count);
        }
        List<BlockPos> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            points.add(BlockPos.STREAM_CODEC.decode(buf).immutable());
        }
        int traversal = buf.readVarInt();
        int facing = buf.readVarInt();
        int leash = buf.readVarInt();
        int arc = buf.readVarInt();
        Optional<UUID> buildingId = buf.readBoolean()
            ? Optional.of(UUIDUtil.STREAM_CODEC.decode(buf)) : Optional.empty();
        boolean canManage = buf.readBoolean();
        boolean equipmentReady = buf.readBoolean();
        boolean towerPostAvailable = buf.readBoolean();
        Outcome outcome = Outcome.fromWireId(buf.readUnsignedByte());
        Optional<Component> feedback =
            ComponentSerialization.OPTIONAL_STREAM_CODEC.decode(buf);
        return new GuardOrderSnapshotPayload(entityId, guardId, sessionId,
            settlementId, revision, mode, destination, points,
            traversal, facing, leash, arc, buildingId, canManage,
            equipmentReady, towerPostAvailable, outcome, feedback);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
