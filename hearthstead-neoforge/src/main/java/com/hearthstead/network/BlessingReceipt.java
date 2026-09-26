package com.hearthstead.network;

import com.hearthstead.item.BlessingSealItem;
import com.hearthstead.settlement.PendingPlayerDeliveryLedger;
import com.hearthstead.settlement.state.BlessingId;
import com.hearthstead.settlement.state.BlessingState;
import com.hearthstead.settlement.state.BlessingQuality;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import java.util.UUID;

/** Fixed-size, winner-only proof of one consumed offer and its physical destination. */
public record BlessingReceipt(UUID winnerId, UUID deliveryId, int consumedRevision,
        int consumedOfferSerial, int blessingWireId, Outcome outcome, int playerSlot, int rankUnits) {
    public BlessingReceipt(UUID winnerId, UUID deliveryId, int consumedRevision,
            int consumedOfferSerial, int blessingWireId, Outcome outcome, int playerSlot) {
        this(winnerId, deliveryId, consumedRevision, consumedOfferSerial, blessingWireId, outcome, playerSlot, 1);
    }
    public enum Outcome {
        MAIN_HAND(0), OFF_HAND(1), INVENTORY(2), DROP(3), ALREADY_DELIVERED(4), PENDING(5);
        private final int wireId;
        Outcome(int wireId) { this.wireId = wireId; }
        public int wireId() { return wireId; }
        public boolean direct() { return this == MAIN_HAND || this == OFF_HAND || this == INVENTORY; }
        public static Outcome fromWireId(int id) {
            for (Outcome outcome : values()) if (outcome.wireId == id) return outcome;
            throw new IllegalArgumentException("unknown blessing receipt outcome");
        }
        public static Outcome fromDelivery(PendingPlayerDeliveryLedger.Outcome outcome) {
            return switch (outcome) {
                case MAIN_HAND -> MAIN_HAND; case OFF_HAND -> OFF_HAND;
                case INVENTORY -> INVENTORY; case DROP -> DROP;
                case ALREADY_DELIVERED -> ALREADY_DELIVERED; case PENDING -> PENDING;
            };
        }
    }
    public BlessingReceipt {
        if (winnerId == null || deliveryId == null || winnerId.equals(new UUID(0, 0))
                || deliveryId.equals(new UUID(0, 0)) || consumedRevision < 0
                || consumedRevision >= BlessingState.MAX_REVISION || consumedOfferSerial < 1
                || consumedOfferSerial > BlessingState.MAX_COUNTER
                || BlessingId.tryFromWireId(blessingWireId).isEmpty() || outcome == null
                || !validSlot(outcome, playerSlot) || BlessingQuality.fromRankUnits(rankUnits).isEmpty()) {
            throw new IllegalArgumentException("invalid blessing receipt");
        }
    }
    public static boolean validSlot(Outcome outcome, int slot) {
        if (outcome == null) return false;
        return switch (outcome) {
            case MAIN_HAND -> slot >= 0 && slot <= 8;
            case OFF_HAND -> slot == 40;
            case INVENTORY -> slot >= 0 && slot <= 35;
            case DROP, ALREADY_DELIVERED, PENDING -> slot == -1;
        };
    }
    public boolean matchesSubmission(UUID player, int revision, int serial, BlessingId choice) {
        return winnerId.equals(player) && consumedRevision == revision && consumedOfferSerial == serial
            && choice != null && choice.wireId() == blessingWireId;
    }
    public boolean matchesSubmission(UUID player, int revision, int serial, BlessingId choice, int expectedUnits) {
        return rankUnits == expectedUnits && matchesSubmission(player, revision, serial, choice);
    }
    /** Caller additionally proves exact visible settlement/session and canonical slot. */
    public boolean matchesSlot(UUID player, int slot, ItemStack stack) {
        return outcome.direct() && winnerId.equals(player) && playerSlot == slot
            && PendingPlayerDeliveryLedger.matchesDelivery(stack,
                BlessingSealItem.stackFor(BlessingId.tryFromWireId(blessingWireId).orElseThrow(),
                    BlessingQuality.fromRankUnits(rankUnits).orElseThrow()),
                deliveryId, winnerId);
    }
    public static final StreamCodec<RegistryFriendlyByteBuf, BlessingReceipt> CODEC = StreamCodec.of(
        (buf, receipt) -> {
            buf.writeVarInt(2);
            buf.writeUUID(receipt.winnerId); buf.writeUUID(receipt.deliveryId);
            buf.writeVarInt(receipt.consumedRevision); buf.writeVarInt(receipt.consumedOfferSerial);
            buf.writeVarInt(receipt.blessingWireId); buf.writeVarInt(receipt.outcome.wireId);
            buf.writeVarInt(receipt.playerSlot);
            buf.writeVarInt(receipt.rankUnits);
        }, buf -> {
            if (buf.readVarInt() != 2) throw new IllegalArgumentException("unknown blessing receipt version");
            return new BlessingReceipt(buf.readUUID(), buf.readUUID(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), Outcome.fromWireId(buf.readVarInt()), buf.readVarInt(), buf.readVarInt());
        });
}
