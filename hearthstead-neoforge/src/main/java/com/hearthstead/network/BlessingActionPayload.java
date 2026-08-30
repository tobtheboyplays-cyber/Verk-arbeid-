package com.hearthstead.network;

import com.hearthstead.settlement.state.BlessingId;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.UUID;

/**
 * One action against an exact server-authored Blessing-screen session.
 *
 * <p>The client echoes the opaque session UUID and offer identity it was
 * shown. Stable integer ids are decoded explicitly; neither an unknown action
 * kind nor an unknown Blessing id can become an enum index or a confirmation.
 */
public record BlessingActionPayload(UUID settlementId, UUID sessionId,
                                    Kind kind, int revision, int offerSerial,
                                    int blessingWireId)
    implements CustomPacketPayload {

    private static final UUID NIL_UUID = new UUID(0L, 0L);

    /** Stable action ids. Unknown future values are inert, never CONFIRM. */
    public enum Kind {
        CONFIRM(0),
        CLOSE(1),
        UNKNOWN(2);

        private final int wireId;

        Kind(int wireId) {
            this.wireId = wireId;
        }

        public int wireId() {
            return wireId;
        }

        public static Kind fromWireId(int wireId) {
            return switch (wireId) {
                case 0 -> CONFIRM;
                case 1 -> CLOSE;
                case 2 -> UNKNOWN;
                default -> UNKNOWN;
            };
        }
    }

    public BlessingActionPayload {
        settlementId = settlementId == null ? NIL_UUID : settlementId;
        sessionId = sessionId == null ? NIL_UUID : sessionId;
        kind = kind == null ? Kind.UNKNOWN : kind;
    }

    public static final Type<BlessingActionPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("hearthstead", "blessing_action"));

    /** Fixed-width/fixed-field codec: no packet-controlled collections. */
    public static final StreamCodec<RegistryFriendlyByteBuf, BlessingActionPayload> CODEC =
        StreamCodec.of(BlessingActionPayload::write, BlessingActionPayload::read);

    public Optional<BlessingId> choice() {
        return kind == Kind.CONFIRM
            ? BlessingId.tryFromWireId(blessingWireId) : Optional.empty();
    }

    private static void write(RegistryFriendlyByteBuf buf, BlessingActionPayload payload) {
        buf.writeUUID(payload.settlementId);
        buf.writeUUID(payload.sessionId);
        buf.writeVarInt(payload.kind.wireId());
        buf.writeVarInt(payload.revision);
        buf.writeVarInt(payload.offerSerial);
        buf.writeVarInt(payload.blessingWireId);
    }

    private static BlessingActionPayload read(RegistryFriendlyByteBuf buf) {
        UUID settlementId = buf.readUUID();
        UUID sessionId = buf.readUUID();
        Kind kind = Kind.fromWireId(buf.readVarInt());
        return new BlessingActionPayload(settlementId, sessionId, kind,
            buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
