package com.hearthstead.network;

import com.hearthstead.settlement.state.BlessingId;
import com.hearthstead.settlement.state.BlessingState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.UUID;

/**
 * Server-authored state for the three-card Blessing screen.
 *
 * <p>The shape is deliberately fixed: one bounded settlement name, three
 * named issuance counters and scalar offer/feedback fields. There are no decoded
 * lists, ordinals or packet-sized allocations. Unknown future feedback and
 * Blessing ids fail closed to an unavailable message or an empty optional.
 */
public record BlessingSnapshotPayload(UUID settlementId, UUID sessionId,
                                      String settlementName, int revision,
                                      int offerSerial,
                                      int wardenOathIssued, int hearthwardIssued,
                                      int thornedRoadsIssued, Delivery delivery,
                                      Feedback feedback,
                                      int feedbackBlessingWireId)
    implements CustomPacketPayload {

    public static final int MAX_SETTLEMENT_NAME_LENGTH = 64;
    private static final UUID NIL_UUID = new UUID(0L, 0L);

    public BlessingSnapshotPayload {
        settlementId = settlementId == null ? NIL_UUID : settlementId;
        sessionId = sessionId == null ? NIL_UUID : sessionId;
        settlementName = settlementName == null ? "" : settlementName;
        delivery = delivery == null ? Delivery.RESULT : delivery;
        feedback = feedback == null ? Feedback.UNAVAILABLE : feedback;
    }

    /**
     * Why the server sent this snapshot.
     *
     * <p>Only {@link #OPEN} may create a client screen. UPDATE and RESULT are
     * deliberately update-only so a delayed response, another player's
     * choice, or a hostile/unknown wire value can never steal focus after the
     * player has closed the screen.
     */
    public enum Delivery {
        OPEN(0),
        UPDATE(1),
        RESULT(2);

        private final int wireId;

        Delivery(int wireId) {
            this.wireId = wireId;
        }

        public int wireId() {
            return wireId;
        }

        /** Unknown delivery kinds are terminal and therefore cannot open UI. */
        public static Delivery fromWireId(int wireId) {
            for (Delivery value : values()) {
                if (value.wireId == wireId) {
                    return value;
                }
            }
            return RESULT;
        }
    }

    public enum Feedback {
        NONE(0, true),
        ACCEPTED(1, false),
        STALE(2, true),
        OTHER_PLAYER_CHOSE(3, false),
        INVALID_CHOICE(4, true),
        TOO_FAR(5, false),
        MAXED(6, true),
        UNAVAILABLE(7, false),
        /** Offer remains spendable; durable delivery capacity refused mutation. */
        DELIVERY_BACKLOG(8, true);

        private final int wireId;
        private final boolean allowsChoice;

        Feedback(int wireId, boolean allowsChoice) {
            this.wireId = wireId;
            this.allowsChoice = allowsChoice;
        }

        public int wireId() {
            return wireId;
        }

        public boolean allowsChoice() {
            return allowsChoice;
        }

        /** Unknown feedback never opens an actionable screen state. */
        public static Feedback fromWireId(int wireId) {
            for (Feedback value : values()) {
                if (value.wireId == wireId) {
                    return value;
                }
            }
            return UNAVAILABLE;
        }
    }

    public static final Type<BlessingSnapshotPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("hearthstead", "blessing_snapshot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BlessingSnapshotPayload> CODEC =
        StreamCodec.of(BlessingSnapshotPayload::write, BlessingSnapshotPayload::read);

    /** Audit count only; permanent effect ranks live on physical targets. */
    public int issuedCount(BlessingId blessing) {
        if (blessing == null) {
            return 0;
        }
        return switch (blessing) {
            case WARDEN_OATH -> boundedCount(wardenOathIssued);
            case HEARTHWARD -> boundedCount(hearthwardIssued);
            case THORNED_ROADS -> boundedCount(thornedRoadsIssued);
        };
    }

    /** Compatibility alias. This value is not a target rank. */
    @Deprecated(forRemoval = true)
    public int rank(BlessingId blessing) {
        return issuedCount(blessing);
    }

    public Optional<BlessingId> feedbackBlessing() {
        return BlessingId.tryFromWireId(feedbackBlessingWireId);
    }

    public boolean hasPendingOffer() {
        return offerSerial > 0 && delivery != Delivery.RESULT
            && feedback.allowsChoice();
    }

    /** Only a valid server-authored Hearth-use snapshot may create a screen. */
    public boolean mayOpenScreen() {
        return delivery == Delivery.OPEN && feedback == Feedback.NONE
            && !NIL_UUID.equals(sessionId) && hasPendingOffer();
    }

    /**
     * A terminal-looking feedback card that still has another shared offer
     * behind it. The screen may show the result briefly, then replace it with
     * {@link #asChoiceUpdate()} without another server round trip.
     */
    public boolean hasFollowUpOffer() {
        return delivery == Delivery.UPDATE && offerSerial > 0
            && (feedback == Feedback.ACCEPTED
                || feedback == Feedback.OTHER_PLAYER_CHOSE);
    }

    /**
     * Clears transient co-op/result feedback while retaining only the
     * server-authored revision, serial and issuance counters for the next choice.
     */
    public BlessingSnapshotPayload asChoiceUpdate() {
        if (!hasFollowUpOffer()) {
            return this;
        }
        return new BlessingSnapshotPayload(settlementId, sessionId, settlementName,
            revision, offerSerial, wardenOathIssued, hearthwardIssued,
            thornedRoadsIssued, Delivery.UPDATE, Feedback.NONE, -1);
    }

    private static int boundedCount(int count) {
        return Math.max(0, Math.min(BlessingState.MAX_COUNTER, count));
    }

    private static void write(RegistryFriendlyByteBuf buf, BlessingSnapshotPayload payload) {
        buf.writeUUID(payload.settlementId);
        buf.writeUUID(payload.sessionId);
        buf.writeUtf(payload.settlementName, MAX_SETTLEMENT_NAME_LENGTH);
        buf.writeVarInt(payload.revision);
        buf.writeVarInt(payload.offerSerial);
        buf.writeVarInt(payload.wardenOathIssued);
        buf.writeVarInt(payload.hearthwardIssued);
        buf.writeVarInt(payload.thornedRoadsIssued);
        buf.writeVarInt(payload.delivery.wireId());
        buf.writeVarInt(payload.feedback.wireId());
        buf.writeVarInt(payload.feedbackBlessingWireId);
    }

    private static BlessingSnapshotPayload read(RegistryFriendlyByteBuf buf) {
        UUID settlementId = buf.readUUID();
        UUID sessionId = buf.readUUID();
        String settlementName = buf.readUtf(MAX_SETTLEMENT_NAME_LENGTH);
        int revision = buf.readVarInt();
        int offerSerial = buf.readVarInt();
        int wardenOathIssued = buf.readVarInt();
        int hearthwardIssued = buf.readVarInt();
        int thornedRoadsIssued = buf.readVarInt();
        Delivery delivery = Delivery.fromWireId(buf.readVarInt());
        Feedback feedback = Feedback.fromWireId(buf.readVarInt());
        int feedbackBlessingWireId = buf.readVarInt();
        return new BlessingSnapshotPayload(settlementId, sessionId, settlementName,
            revision, offerSerial, wardenOathIssued, hearthwardIssued,
            thornedRoadsIssued, delivery, feedback, feedbackBlessingWireId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
