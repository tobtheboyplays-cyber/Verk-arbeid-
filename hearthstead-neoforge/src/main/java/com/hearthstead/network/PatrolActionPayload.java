package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/**
 * One route edit from the Patrol Map's route screen. The server re-checks
 * everything: the feature switch, that the sender is a member of that exact
 * settlement, the limits, and (for index-based edits) the book revision.
 */
public record PatrolActionPayload(UUID settlementId, Kind kind, int routeId, int value, UUID guard, String text,
                                  int expectedRevision) implements CustomPacketPayload {
    public static final UUID NONE = new UUID(0L, 0L);

    public enum Kind {
        REFRESH, CREATE, DELETE, SELECT, TOGGLE_LOOP, SET_FORMATION, SET_PER_SHIFT, TOGGLE_MEMBER, RENAME,
        REMOVE_POINT, UNKNOWN;

        static Kind byOrdinal(int ordinal) {
            Kind[] all = values();
            return ordinal >= 0 && ordinal < all.length ? all[ordinal] : UNKNOWN;
        }
    }

    public static final Type<PatrolActionPayload> TYPE = new Type<>(Hearthstead.id("patrol_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PatrolActionPayload> CODEC =
        StreamCodec.of(PatrolActionPayload::write, PatrolActionPayload::read);

    public PatrolActionPayload {
        settlementId = settlementId == null ? NONE : settlementId;
        kind = kind == null ? Kind.UNKNOWN : kind;
        guard = guard == null ? NONE : guard;
        text = text == null ? "" : text;
    }

    public static PatrolActionPayload of(UUID settlementId, Kind kind, int routeId, int value, int revision) {
        return new PatrolActionPayload(settlementId, kind, routeId, value, NONE, "", revision);
    }

    private static void write(RegistryFriendlyByteBuf buf, PatrolActionPayload p) {
        UUIDUtil.STREAM_CODEC.encode(buf, p.settlementId);
        buf.writeVarInt(p.kind.ordinal());
        buf.writeVarInt(p.routeId);
        buf.writeVarInt(p.value);
        UUIDUtil.STREAM_CODEC.encode(buf, p.guard);
        buf.writeUtf(p.text, 64);
        buf.writeVarInt(p.expectedRevision);
    }

    private static PatrolActionPayload read(RegistryFriendlyByteBuf buf) {
        return new PatrolActionPayload(UUIDUtil.STREAM_CODEC.decode(buf), Kind.byOrdinal(buf.readVarInt()),
            buf.readVarInt(), buf.readVarInt(), UUIDUtil.STREAM_CODEC.decode(buf), buf.readUtf(64),
            buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
