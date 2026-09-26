package com.hearthstead.settlement;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingPlayerDeliveryLedgerTest {

    @Test
    void boundedRowsRoundTripAndMalformedOverflowStaysQuarantined() {
        PendingPlayerDeliveryLedger ledger = new PendingPlayerDeliveryLedger();
        UUID player = UUID.randomUUID();
        for (int i = 0; i < PendingPlayerDeliveryLedger.MAX_PENDING; i++) {
            assertEquals(PendingPlayerDeliveryLedger.ReserveResult.INSERTED,
                ledger.reserve(reservation(new UUID(1L, i + 1L), player, i)));
        }
        assertEquals(PendingPlayerDeliveryLedger.ReserveResult.CAPACITY,
            ledger.reserve(reservation(UUID.randomUUID(), player, 100.0D)));

        CompoundTag exact = ledger.writeNbt();
        PendingPlayerDeliveryLedger restored =
            PendingPlayerDeliveryLedger.readNbt(exact);
        assertFalse(restored.quarantined());
        assertEquals(PendingPlayerDeliveryLedger.MAX_PENDING,
            restored.pendingCount());

        ListTag overflow = exact.getList("Pending", CompoundTag.TAG_COMPOUND);
        overflow.add(overflow.getCompound(0).copy());
        PendingPlayerDeliveryLedger quarantined =
            PendingPlayerDeliveryLedger.readNbt(exact);
        assertTrue(quarantined.quarantined());
        assertTrue(quarantined.writeNbt().getBoolean("Quarantined"),
            "a rejected oversized ledger must retain sticky fail-closed state");
    }

    @Test
    void stableIdentityIsIdempotentOnlyForTheBitExactReservation() {
        PendingPlayerDeliveryLedger ledger = new PendingPlayerDeliveryLedger();
        UUID id = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        PendingPlayerDeliveryLedger.Reservation exact =
            reservation(id, player, 4.0D);
        assertEquals(PendingPlayerDeliveryLedger.ReserveResult.INSERTED,
            ledger.reserve(exact));
        assertEquals(PendingPlayerDeliveryLedger.ReserveResult.IDEMPOTENT,
            ledger.reserve(exact));
        assertEquals(PendingPlayerDeliveryLedger.ReserveResult.COLLISION,
            ledger.reserve(reservation(id, player, 5.0D)));
        assertEquals(1, ledger.pendingCount());
    }

    private static PendingPlayerDeliveryLedger.Reservation reservation(
            UUID id, UUID player, double x) {
        CompoundTag exactStack = new CompoundTag();
        exactStack.putString("id", "minecraft:stone");
        exactStack.putInt("count", 1);
        return new PendingPlayerDeliveryLedger.Reservation(id, player, x,
            64.0D, 0.0D, exactStack);
    }
}
