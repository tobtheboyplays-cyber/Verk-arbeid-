package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.Optional;
import java.util.UUID;

/** Bounded right-click target hint; the server resolves every live authority. */
public record WorkZoneSelectionPayload(Kind kind, Optional<UUID> settlerId,
                                       Optional<BlockPos> workplacePos)
    implements CustomPacketPayload {
    public enum Kind {
        SETTLER(0), WORKPLACE(1), UNKNOWN(-1);
        private final int wireId;
        Kind(int wireId) { this.wireId = wireId; }
        int wireId() { return wireId; }
        static Kind fromWireId(int id) {
            return id == 0 ? SETTLER : id == 1 ? WORKPLACE : UNKNOWN;
        }
    }

    public static final Type<WorkZoneSelectionPayload> TYPE = new Type<>(
        Hearthstead.id("work_zone_selection"));
    public static final StreamCodec<RegistryFriendlyByteBuf,
        WorkZoneSelectionPayload> CODEC = StreamCodec.of(
            WorkZoneSelectionPayload::write, WorkZoneSelectionPayload::read);

    public WorkZoneSelectionPayload {
        kind = kind == null ? Kind.UNKNOWN : kind;
        settlerId = settlerId == null ? Optional.empty() : settlerId;
        workplacePos = workplacePos == null ? Optional.empty()
            : workplacePos.map(BlockPos::immutable);
    }

    public static WorkZoneSelectionPayload settler(UUID id) {
        return new WorkZoneSelectionPayload(Kind.SETTLER, Optional.ofNullable(id),
            Optional.empty());
    }

    public static WorkZoneSelectionPayload workplace(BlockPos pos) {
        return new WorkZoneSelectionPayload(Kind.WORKPLACE, Optional.empty(),
            Optional.ofNullable(pos));
    }

    private static void write(RegistryFriendlyByteBuf buf,
                              WorkZoneSelectionPayload payload) {
        buf.writeVarInt(payload.kind.wireId);
        buf.writeBoolean(payload.settlerId.isPresent());
        payload.settlerId.ifPresent(id -> UUIDUtil.STREAM_CODEC.encode(buf, id));
        buf.writeBoolean(payload.workplacePos.isPresent());
        payload.workplacePos.ifPresent(pos -> BlockPos.STREAM_CODEC.encode(buf, pos));
    }

    private static WorkZoneSelectionPayload read(RegistryFriendlyByteBuf buf) {
        Kind kind = Kind.fromWireId(buf.readVarInt());
        Optional<UUID> settler = buf.readBoolean()
            ? Optional.of(UUIDUtil.STREAM_CODEC.decode(buf)) : Optional.empty();
        Optional<BlockPos> workplace = buf.readBoolean()
            ? Optional.of(BlockPos.STREAM_CODEC.decode(buf)) : Optional.empty();
        return new WorkZoneSelectionPayload(kind, settler, workplace);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
