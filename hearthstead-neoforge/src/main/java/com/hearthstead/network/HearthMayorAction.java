package com.hearthstead.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * A request from the hearth screen's Mayor or Founding Journey tab. A
 * request, not a command --
 * {@link PlaqueAction}'s discipline exactly: the server decides whether it
 * happens, re-checking the settlement and the candidate from scratch.
 *
 * <p>The hearth position, settlement UUID and menu id are opaque identity
 * echoes from the server-opened {@code HearthMenu}. They grant no authority:
 * the handler requires all three to match the exact menu that is still open,
 * then resolves the live hearth block entity and settlement again. A packet
 * copied from another hearth, another screen generation or an already-closed
 * menu is therefore inert even when the player happens to stand nearby.
 *
 * <p>{@code revision} is the revision the player was looking at when they
 * clicked. APPOINT compares it with the mayoral revision; SKIP_JOURNEY with
 * the journey revision. Stale clicks are refused rather than applied to a
 * settlement that has moved on.
 */
public record HearthMayorAction(BlockPos hearthPos, UUID settlementId,
                                int containerId, Kind kind, UUID target,
                                int revision)
    implements CustomPacketPayload {

    public static final UUID NO_ID = new UUID(0L, 0L);

    public enum Kind {
        APPOINT(0),
        REFRESH(1),
        SKIP_JOURNEY(2),
        /** Opens the Hearth-owned Development map. */
        OPEN_DEVELOPMENT(3),
        /** Records an exact server-validated open Journey view session. */
        OPEN_JOURNEY(4),
        /** Explicitly admits the exact persisted waiting traveler. */
        ADMIT_TRAVELER(5),
        /** Opens the Hearth's bounded, read-only Request Ledger. */
        OPEN_REQUEST_LEDGER(6),
        /** Explicitly dismisses the exact persisted waiting traveler. */
        REJECT_TRAVELER(7),
        /** Opens a bounded, server-authored first-raid readiness report. */
        OPEN_RAID_READINESS(8),
        /** Consumes one exact readiness session and declares the Hearth ready. */
        CONFIRM_RAID_READINESS(9),
        /** Opens a bounded read-only projection of the recorded settlement members. */
        OPEN_PEOPLE(10),
        /** Opens one exact loaded resident through the existing inspection route. */
        VIEW_SETTLER(11),
        /** Unknown future/corrupt wire value. Always inert. */
        UNKNOWN(-1);

        private final int wireId;

        Kind(int wireId) {
            this.wireId = wireId;
        }

        public int wireId() {
            return wireId;
        }

        public static Kind fromWireId(int wireId) {
            return switch (wireId) {
                case 0 -> APPOINT;
                case 1 -> REFRESH;
                case 2 -> SKIP_JOURNEY;
                case 3 -> OPEN_DEVELOPMENT;
                case 4 -> OPEN_JOURNEY;
                case 5 -> ADMIT_TRAVELER;
                case 6 -> OPEN_REQUEST_LEDGER;
                case 7 -> REJECT_TRAVELER;
                case 8 -> OPEN_RAID_READINESS;
                case 9 -> CONFIRM_RAID_READINESS;
                case 10 -> OPEN_PEOPLE;
                case 11 -> VIEW_SETTLER;
                default -> UNKNOWN;
            };
        }
    }

    public static final Type<HearthMayorAction> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("hearthstead", "hearth_mayor_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HearthMayorAction> CODEC =
        StreamCodec.of(HearthMayorAction::write, HearthMayorAction::read);

    public HearthMayorAction {
        hearthPos = hearthPos == null ? BlockPos.ZERO : hearthPos.immutable();
        settlementId = settlementId == null ? NO_ID : settlementId;
        kind = kind == null ? Kind.UNKNOWN : kind;
        target = target == null ? NO_ID : target;
    }

    private static void write(RegistryFriendlyByteBuf buf,
                              HearthMayorAction action) {
        BlockPos.STREAM_CODEC.encode(buf, action.hearthPos);
        UUIDUtil.STREAM_CODEC.encode(buf, action.settlementId);
        buf.writeVarInt(action.containerId);
        buf.writeVarInt(action.kind.wireId());
        UUIDUtil.STREAM_CODEC.encode(buf, action.target);
        buf.writeVarInt(action.revision);
    }

    private static HearthMayorAction read(RegistryFriendlyByteBuf buf) {
        return new HearthMayorAction(BlockPos.STREAM_CODEC.decode(buf),
            UUIDUtil.STREAM_CODEC.decode(buf), buf.readVarInt(),
            Kind.fromWireId(buf.readVarInt()), UUIDUtil.STREAM_CODEC.decode(buf),
            buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
