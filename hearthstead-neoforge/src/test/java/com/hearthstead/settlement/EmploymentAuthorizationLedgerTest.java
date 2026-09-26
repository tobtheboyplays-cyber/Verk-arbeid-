package com.hearthstead.settlement;

import com.hearthstead.entity.Profession;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmploymentAuthorizationLedgerTest {

    @Test
    void chargedCurrentRelationOverwritesAndRoundTripsExactly() {
        UUID settlement = UUID.randomUUID();
        UUID worker = UUID.randomUUID();
        UUID towerA = UUID.randomUUID();
        UUID towerB = UUID.randomUUID();
        UUID firstSale = UUID.randomUUID();
        UUID secondSale = UUID.randomUUID();
        EmploymentAuthorizationLedger ledger =
            EmploymentAuthorizationLedger.fresh();

        assertFalse(ledger.matches(settlement, worker, towerA,
            Profession.ARCHER), "a free/admin relation has no receipt");
        assertTrue(ledger.authorize(settlement, worker, towerA,
            Profession.ARCHER, firstSale));
        assertTrue(ledger.matches(settlement, worker, towerA,
            Profession.ARCHER));

        EmploymentAuthorizationLedger loaded = EmploymentAuthorizationLedger
            .readNbt(ledger.writeNbt(), settlement);
        assertFalse(loaded.quarantinedState());
        assertTrue(loaded.matches(settlement, worker, towerA,
            Profession.ARCHER));
        assertEquals(firstSale,
            loaded.receipt(worker).saleTransactionId());

        assertTrue(loaded.authorize(settlement, worker, towerB,
            Profession.ARCHER, secondSale));
        assertFalse(loaded.matches(settlement, worker, towerA,
            Profession.ARCHER));
        assertTrue(loaded.matches(settlement, worker, towerB,
            Profession.ARCHER));
        assertEquals(secondSale,
            loaded.receipt(worker).saleTransactionId());

        assertTrue(loaded.clear(worker));
        assertNull(loaded.receipt(worker));
        assertFalse(loaded.matches(settlement, worker, towerB,
            Profession.ARCHER));
    }

    @Test
    void oneSaleTransactionCanAuthorizeOnlyOneExactCurrentRelation() {
        UUID settlement = UUID.randomUUID();
        UUID workerA = UUID.randomUUID();
        UUID workerB = UUID.randomUUID();
        UUID buildingA = UUID.randomUUID();
        UUID buildingB = UUID.randomUUID();
        UUID onePhysicalSale = UUID.randomUUID();
        EmploymentAuthorizationLedger ledger =
            EmploymentAuthorizationLedger.fresh();

        assertTrue(ledger.authorize(settlement, workerA, buildingA,
            Profession.GUARD, onePhysicalSale));
        long authoredRevision = ledger.revision();
        assertTrue(ledger.authorize(settlement, workerA, buildingA,
            Profession.GUARD, onePhysicalSale),
            "the exact same relation may replay idempotently");
        assertEquals(authoredRevision, ledger.revision(),
            "an idempotent replay must not author another receipt revision");
        assertFalse(ledger.authorize(settlement, workerB, buildingB,
            Profession.ARCHER, onePhysicalSale),
            "a copied stamp may not authorize a second worker");
        assertFalse(ledger.authorize(settlement, workerA, buildingB,
            Profession.GUARD, onePhysicalSale),
            "the same sale may not be moved to a different building");

        CompoundTag duplicatedSale = ledger.writeNbt();
        ListTag rows = duplicatedSale.getList("Receipts",
            CompoundTag.TAG_COMPOUND);
        CompoundTag forgedSecondRelation = rows.getCompound(0).copy();
        forgedSecondRelation.putUUID("Worker", workerB);
        forgedSecondRelation.putUUID("Building", buildingB);
        forgedSecondRelation.putString("Profession", Profession.ARCHER.key());
        rows.add(forgedSecondRelation);
        EmploymentAuthorizationLedger restarted =
            EmploymentAuthorizationLedger.readNbt(duplicatedSale, settlement);
        assertTrue(restarted.quarantinedState(),
            "restart must quarantine duplicate sale authority without choosing a winner");
        assertEquals(0, restarted.size());

        assertTrue(ledger.clear(workerA));
        assertEquals(0, ledger.size());
        assertEquals(1, ledger.spentSaleCount(),
            "dismissal must clear current authority without unspending its sale");
        assertFalse(ledger.authorize(settlement, workerB, buildingB,
            Profession.ARCHER, onePhysicalSale),
            "a copied stamp must remain spent after the original relation clears");
        EmploymentAuthorizationLedger clearedRestart =
            EmploymentAuthorizationLedger.readNbt(ledger.writeNbt(),
                settlement);
        assertFalse(clearedRestart.quarantinedState());
        assertEquals(1, clearedRestart.spentSaleCount());
        assertFalse(clearedRestart.authorize(settlement, workerB, buildingB,
            Profession.ARCHER, onePhysicalSale),
            "restart may not resurrect a serially replayed emblem sale");

        UUID replacementSale = UUID.randomUUID();
        assertTrue(clearedRestart.authorize(settlement, workerA, buildingB,
            Profession.GUARD, replacementSale));
        assertEquals(2, clearedRestart.spentSaleCount(),
            "a legitimate replacement consumes a distinct immutable sale");
    }

    @Test
    void malformedDuplicateOrCrossSettlementRowsQuarantineWithoutWinner() {
        UUID settlement = UUID.randomUUID();
        UUID worker = UUID.randomUUID();
        EmploymentAuthorizationLedger ledger =
            EmploymentAuthorizationLedger.fresh();
        assertTrue(ledger.authorize(settlement, worker, UUID.randomUUID(),
            Profession.GUARD, UUID.randomUUID()));

        CompoundTag duplicate = ledger.writeNbt();
        ListTag rows = duplicate.getList("Receipts", CompoundTag.TAG_COMPOUND);
        rows.add(rows.getCompound(0).copy());
        EmploymentAuthorizationLedger duplicateLoaded =
            EmploymentAuthorizationLedger.readNbt(duplicate, settlement);
        assertTrue(duplicateLoaded.quarantinedState());
        assertEquals(0, duplicateLoaded.size());
        assertFalse(duplicateLoaded.matches(settlement, worker,
            UUID.randomUUID(), Profession.GUARD));

        CompoundTag crossSettlement = ledger.writeNbt();
        crossSettlement.getList("Receipts", CompoundTag.TAG_COMPOUND)
            .getCompound(0).putUUID("Settlement", UUID.randomUUID());
        EmploymentAuthorizationLedger crossLoaded =
            EmploymentAuthorizationLedger.readNbt(crossSettlement,
                settlement);
        assertTrue(crossLoaded.quarantinedState());
        assertEquals(0, crossLoaded.size());

        CompoundTag wrongType = ledger.writeNbt();
        wrongType.putString("Receipts", "forged");
        assertTrue(EmploymentAuthorizationLedger.readNbt(wrongType,
            settlement).quarantinedState());
    }

    @Test
    void nilAndUnemployedIdentitiesNeverCreateAuthority() {
        EmploymentAuthorizationLedger ledger =
            EmploymentAuthorizationLedger.fresh();
        UUID nil = new UUID(0L, 0L);
        UUID settlement = UUID.randomUUID();
        UUID worker = UUID.randomUUID();
        UUID building = UUID.randomUUID();
        UUID transaction = UUID.randomUUID();

        assertFalse(ledger.authorize(nil, worker, building, Profession.GUARD,
            transaction));
        assertFalse(ledger.authorize(settlement, nil, building,
            Profession.GUARD, transaction));
        assertFalse(ledger.authorize(settlement, worker, nil,
            Profession.GUARD, transaction));
        assertFalse(ledger.authorize(settlement, worker, building,
            Profession.NONE, transaction));
        assertFalse(ledger.authorize(settlement, worker, building,
            Profession.GUARD, nil));
        assertEquals(0, ledger.size());
    }

    @Test
    void settlementSaveOwnsReceiptAndMissingLegacyKeyDoesNotInventOne() {
        Settlement settlement = new Settlement(UUID.randomUUID(), "Receipt",
            BlockPos.ZERO);
        UUID worker = UUID.randomUUID();
        UUID building = UUID.randomUUID();
        assertTrue(settlement.employmentAuthorizations.authorize(settlement.id,
            worker, building, Profession.ARCHER, UUID.randomUUID()));

        Settlement loaded = Settlement.readNbt(settlement.writeNbt());
        assertTrue(loaded.employmentAuthorizations.matches(settlement.id,
            worker, building, Profession.ARCHER));

        CompoundTag legacy = settlement.writeNbt();
        legacy.remove("EmploymentAuthorizations");
        Settlement migrated = Settlement.readNbt(legacy);
        assertFalse(migrated.employmentAuthorizations.quarantinedState());
        assertEquals(0, migrated.employmentAuthorizations.size(),
            "SKIPPED/admin history may not invent a charged receipt");

        CompoundTag malformed = settlement.writeNbt();
        malformed.putString("EmploymentAuthorizations", "forged");
        assertTrue(Settlement.readNbt(malformed).employmentAuthorizations
            .quarantinedState());
    }
}
