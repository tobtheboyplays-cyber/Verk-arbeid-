package com.hearthstead.network;

import com.hearthstead.settlement.state.BlessingId;
import com.hearthstead.settlement.state.BlessingState;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class BlessingReceiptTest {
    private static final UUID PLAYER = new UUID(1, 2), DELIVERY = new UUID(3, 4);
    private static final int CHOICE = BlessingId.WARDEN_OATH.wireId();
    @Test void everyPhysicalOutcomeRoundTripsAndNextOfferClearsReceipt() {
        for (BlessingReceipt.Outcome outcome : BlessingReceipt.Outcome.values()) {
            int slot = switch (outcome) { case MAIN_HAND -> 6; case OFF_HAND -> 40;
                case INVENTORY -> 17; default -> -1; };
            var receipt = new BlessingReceipt(PLAYER, DELIVERY, 20, 4, CHOICE, outcome, slot);
            var snapshot = snapshot(receipt, BlessingSnapshotPayload.Feedback.ACCEPTED, 21, 5);
            var buffer = buffer();
            try {
                BlessingSnapshotPayload.CODEC.encode(buffer, snapshot);
                assertEquals(snapshot, BlessingSnapshotPayload.CODEC.decode(buffer));
                assertEquals(0, buffer.readableBytes());
            } finally { buffer.release(); }
            assertNull(snapshot.asChoiceUpdate().receipt());
            assertEquals(5, snapshot.asChoiceUpdate().offerSerial());
            assertTrue(receipt.matchesSubmission(PLAYER, 20, 4, BlessingId.WARDEN_OATH));
            assertFalse(receipt.matchesSubmission(new UUID(7, 8), 20, 4, BlessingId.WARDEN_OATH));
            assertFalse(receipt.matchesSubmission(PLAYER, 21, 5, BlessingId.WARDEN_OATH));
        }
    }
    @Test void receiptFreeSnapshotsRetainCompatibilityConstructor() {
        var snapshot = snapshot(null, BlessingSnapshotPayload.Feedback.NONE, 20, 4);
        var buffer = buffer();
        try {
            BlessingSnapshotPayload.CODEC.encode(buffer, snapshot);
            assertEquals(snapshot, BlessingSnapshotPayload.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }
    @Test void impossibleIdentityOutcomeAndSnapshotBindingsReject() {
        assertThrows(IllegalArgumentException.class, () -> receipt(BlessingReceipt.Outcome.DROP, 0));
        assertThrows(IllegalArgumentException.class, () -> receipt(BlessingReceipt.Outcome.MAIN_HAND, 9));
        assertThrows(IllegalArgumentException.class, () -> receipt(BlessingReceipt.Outcome.OFF_HAND, 0));
        assertThrows(IllegalArgumentException.class, () -> receipt(BlessingReceipt.Outcome.INVENTORY, 40));
        assertThrows(IllegalArgumentException.class, () -> new BlessingReceipt(PLAYER, DELIVERY,
            BlessingState.MAX_REVISION, 4, CHOICE, BlessingReceipt.Outcome.PENDING, -1));
        assertThrows(IllegalArgumentException.class, () -> new BlessingReceipt(new UUID(0, 0), DELIVERY,
            20, 4, CHOICE, BlessingReceipt.Outcome.PENDING, -1));
        assertThrows(IllegalArgumentException.class, () -> BlessingReceipt.Outcome.fromWireId(99));
        var receipt = receipt(BlessingReceipt.Outcome.MAIN_HAND, 0);
        assertThrows(IllegalArgumentException.class, () -> snapshot(receipt, BlessingSnapshotPayload.Feedback.OTHER_PLAYER_CHOSE,21,5));
        assertThrows(IllegalArgumentException.class, () -> snapshot(receipt, BlessingSnapshotPayload.Feedback.ACCEPTED,22,5));
        assertThrows(IllegalArgumentException.class, () -> snapshot(receipt, BlessingSnapshotPayload.Feedback.ACCEPTED,21,6));
        var buffer=buffer();
        try { buffer.writeVarInt(3); assertThrows(IllegalArgumentException.class, () -> BlessingReceipt.CODEC.decode(buffer)); }
        finally { buffer.release(); }
    }
    @Test
    void rareReceiptAndNextOfferKeepTheirSeparateQualityAcrossCodecAndTransition() {
        var rare = new BlessingReceipt(PLAYER, DELIVERY, 20, 4, CHOICE,
            BlessingReceipt.Outcome.INVENTORY, 12, 2);
        var source = new BlessingSnapshotPayload(new UUID(5,6), new UUID(7,8), "Hearth",
            21, 5, 1, 0, 0, BlessingSnapshotPayload.Delivery.UPDATE,
            BlessingSnapshotPayload.Feedback.ACCEPTED, CHOICE, rare, 1, 2, 1);
        var buffer = buffer();
        try {
            BlessingSnapshotPayload.CODEC.encode(buffer, source);
            var decoded = BlessingSnapshotPayload.CODEC.decode(buffer);
            assertEquals(source, decoded);
            assertEquals(2, decoded.receipt().rankUnits());
            var next = decoded.asChoiceUpdate();
            assertNull(next.receipt());
            assertEquals(1, next.rankUnits(BlessingId.WARDEN_OATH));
            assertEquals(2, next.rankUnits(BlessingId.HEARTHWARD));
            assertEquals(1, next.rankUnits(BlessingId.THORNED_ROADS));
            assertTrue(rare.matchesSubmission(PLAYER, 20, 4,
                BlessingId.tryFromWireId(CHOICE).orElseThrow(), 2));
            assertFalse(rare.matchesSubmission(PLAYER, 20, 4,
                BlessingId.tryFromWireId(CHOICE).orElseThrow(), 1));
        } finally { buffer.release(); }
        assertThrows(IllegalArgumentException.class, () -> new BlessingReceipt(PLAYER, DELIVERY,
            20, 4, CHOICE, BlessingReceipt.Outcome.PENDING, -1, 3));
        assertThrows(IllegalArgumentException.class, () -> new BlessingSnapshotPayload(new UUID(5,6),
            new UUID(7,8), "Hearth", 21, 5, 1, 0, 0, BlessingSnapshotPayload.Delivery.UPDATE,
            BlessingSnapshotPayload.Feedback.NONE, -1, null, 1, 0, 1));
    }

    private static BlessingReceipt receipt(BlessingReceipt.Outcome outcome,int slot) {
        return new BlessingReceipt(PLAYER,DELIVERY,20,4,CHOICE,outcome,slot);
    }
    private static BlessingSnapshotPayload snapshot(BlessingReceipt receipt, BlessingSnapshotPayload.Feedback feedback,int revision,int serial) {
        return new BlessingSnapshotPayload(new UUID(5,6),new UUID(7,8),"Hearth",revision,serial,1,0,0,
            BlessingSnapshotPayload.Delivery.UPDATE,feedback,CHOICE,receipt);
    }
    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY,ConnectionType.NEOFORGE);
    }
}
