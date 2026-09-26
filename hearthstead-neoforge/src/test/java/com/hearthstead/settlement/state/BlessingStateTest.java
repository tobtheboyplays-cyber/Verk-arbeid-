package com.hearthstead.settlement.state;

import com.hearthstead.settlement.PendingPlayerDeliveryLedger;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlessingStateTest {

    @Test
    void deliveryCapacityRefusesBeforeOfferMutationAndRoundTripsExactly() {
        BlessingState state = new BlessingState();
        UUID player = UUID.randomUUID();
        for (int i = 0; i < PendingPlayerDeliveryLedger.MAX_PENDING; i++) {
            assertTrue(state.grantOffer());
            assertEquals(BlessingState.CommitResult.ACCEPTED,
                state.compareAndCommit(state.revision(), state.offerSerial(),
                    BlessingId.WARDEN_OATH,
                    reservation(new UUID(2L, i + 1L), player, i)));
        }
        assertEquals(PendingPlayerDeliveryLedger.MAX_PENDING,
            state.pendingDeliveryCount());

        assertTrue(state.grantOffer());
        int earned = state.earned();
        int spent = state.spent();
        int revision = state.revision();
        int issued = state.issuedCount(BlessingId.WARDEN_OATH);
        assertEquals(BlessingState.CommitResult.DELIVERY_BACKLOG,
            state.compareAndCommit(revision, state.offerSerial(),
                BlessingId.WARDEN_OATH,
                reservation(UUID.randomUUID(), player, 100.0D)));
        assertEquals(earned, state.earned());
        assertEquals(spent, state.spent());
        assertEquals(revision, state.revision());
        assertEquals(issued, state.issuedCount(BlessingId.WARDEN_OATH));

        CompoundTag current = state.writeNbt();
        BlessingState restored = BlessingState.readNbt(current);
        assertFalse(restored.quarantined());
        assertEquals(PendingPlayerDeliveryLedger.MAX_PENDING,
            restored.pendingDeliveryCount());

        CompoundTag missingCurrentOutbox = current.copy();
        missingCurrentOutbox.remove("PendingPlayerDeliveries");
        assertTrue(BlessingState.readNbt(missingCurrentOutbox).quarantined());

        CompoundTag versionTwo = current.copy();
        versionTwo.putInt("DataVersion", 2);
        versionTwo.remove("PendingPlayerDeliveries");
        versionTwo.remove("QualityPolicy");
        BlessingState migrated = BlessingState.readNbt(versionTwo);
        assertFalse(migrated.quarantined());
        assertEquals(0, migrated.pendingDeliveryCount());
    }

    private static PendingPlayerDeliveryLedger.Reservation reservation(
            UUID id, UUID player, double x) {
        CompoundTag exactStack = new CompoundTag();
        exactStack.putString("id", "minecraft:paper");
        exactStack.putInt("count", 1);
        return new PendingPlayerDeliveryLedger.Reservation(id, player, x,
            64.0D, 0.0D, exactStack);
    }
}
