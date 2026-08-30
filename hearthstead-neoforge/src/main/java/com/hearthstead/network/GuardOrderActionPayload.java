package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/**
 * One narrow request from an exact, still-open martial settler inspection.
 * Coordinates never travel client-to-server: Hold and Add Point use the
 * server's current player position, while Defend derives a physical Hearth
 * position from live world state.
 */
public record GuardOrderActionPayload(int guardEntityId, UUID guardId,
                                      UUID sessionId, UUID settlementId,
                                      Kind kind, int expectedRevision)
    implements CustomPacketPayload {

    public enum Kind {
        REFRESH(0),
        HOLD_HERE(1),
        DEFEND_HEARTH(2),
        ADD_PATROL_POINT(3),
        REMOVE_PATROL_POINT(4),
        START_PATROL(5),
        CLEAR_ORDER(6),
        /** Appended; existing action ids 0..6 are frozen. */
        TOWER_POST(7),
        /** Switches this Guard's route between LOOP and PING_PONG. */
        TOGGLE_TRAVERSAL(8),
        UNKNOWN(-1);

        private final int wireId;

        Kind(int wireId) {
            this.wireId = wireId;
        }

        int wireId() {
            return wireId;
        }

        static Kind fromWireId(int id) {
            return switch (id) {
                case 0 -> REFRESH;
                case 1 -> HOLD_HERE;
                case 2 -> DEFEND_HEARTH;
                case 3 -> ADD_PATROL_POINT;
                case 4 -> REMOVE_PATROL_POINT;
                case 5 -> START_PATROL;
                case 6 -> CLEAR_ORDER;
                case 7 -> TOWER_POST;
                case 8 -> TOGGLE_TRAVERSAL;
                default -> UNKNOWN;
            };
        }
    }

    public static final Type<GuardOrderActionPayload> TYPE = new Type<>(
        Hearthstead.id("guard_order_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf,
        GuardOrderActionPayload> CODEC = StreamCodec.of(
            GuardOrderActionPayload::write, GuardOrderActionPayload::read);

    public GuardOrderActionPayload {
        guardId = guardId == null ? SettlerActionPayload.NO_SETTLER : guardId;
        sessionId = sessionId == null ? SettlerActionPayload.NO_SETTLER : sessionId;
        settlementId = settlementId == null ? SettlerActionPayload.NO_SETTLER
            : settlementId;
        kind = kind == null ? Kind.UNKNOWN : kind;
    }

    private static void write(RegistryFriendlyByteBuf buf,
                              GuardOrderActionPayload action) {
        buf.writeVarInt(action.guardEntityId);
        UUIDUtil.STREAM_CODEC.encode(buf, action.guardId);
        UUIDUtil.STREAM_CODEC.encode(buf, action.sessionId);
        UUIDUtil.STREAM_CODEC.encode(buf, action.settlementId);
        buf.writeVarInt(action.kind.wireId());
        buf.writeVarInt(action.expectedRevision);
    }

    private static GuardOrderActionPayload read(RegistryFriendlyByteBuf buf) {
        return new GuardOrderActionPayload(buf.readVarInt(),
            UUIDUtil.STREAM_CODEC.decode(buf),
            UUIDUtil.STREAM_CODEC.decode(buf),
            UUIDUtil.STREAM_CODEC.decode(buf),
            Kind.fromWireId(buf.readVarInt()), buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
