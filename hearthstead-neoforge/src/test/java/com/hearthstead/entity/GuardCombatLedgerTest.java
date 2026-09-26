package com.hearthstead.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardCombatLedgerTest {

    @Test
    void xpSourceCommitsOnceAndSurvivesRestart() {
        GuardCombatLedger ledger = new GuardCombatLedger();
        UUID source = UUID.randomUUID();

        GuardCombatLedger.XpCommit first = ledger.commitXp(source, 10, 25);
        assertNotNull(first);
        assertEquals(15, first.added());
        assertEquals(0L, first.revisionBefore());
        assertEquals(1L, first.revisionAfter());
        assertNull(ledger.commitXp(source, 25, 40),
            "the same persisted source can never grant XP twice");

        GuardCombatLedger loaded = new GuardCombatLedger();
        loaded.readNbt(ledger.writeNbt());
        assertTrue(loaded.containsXpSource(source));
        assertEquals(1L, loaded.xpCount());
        assertNull(loaded.commitXp(source, 25, 40),
            "restart must not reopen an already-committed source");
    }

    @Test
    void malformedOrFutureEvidenceQuarantinesFailClosed() {
        GuardCombatLedger ledger = new GuardCombatLedger();
        CompoundTag future = new CompoundTag();
        future.putInt("Schema", GuardCombatLedger.SCHEMA_VERSION + 1);
        ledger.readNbt(future);

        assertTrue(ledger.quarantined());
        assertNull(ledger.commitXp(UUID.randomUUID(), 0, 10));
        assertNull(ledger.commitShield(UUID.randomUUID(), 20L));

        GuardCombatLedger reloaded = new GuardCombatLedger();
        reloaded.readNbt(ledger.writeNbt());
        assertTrue(reloaded.quarantined(),
            "quarantine itself must survive the next entity save");
    }

    @Test
    void wrongTypeParentFieldQuarantinesButAbsentFieldMigratesLegacy() {
        GuardCombatLedger wrongType = new GuardCombatLedger();
        wrongType.readOptionalNbt(StringTag.valueOf("not_a_compound"));

        assertTrue(wrongType.quarantined(),
            "a present ledger key with the wrong NBT type is corruption");
        assertNull(wrongType.commitXp(UUID.randomUUID(), 0, 10),
            "wrong-type evidence must never be washed into an empty ledger");

        GuardCombatLedger legacyAbsent = new GuardCombatLedger();
        legacyAbsent.readOptionalNbt(null);
        assertFalse(legacyAbsent.quarantined(),
            "only genuine key absence is the pre-ledger migration path");
        assertNotNull(legacyAbsent.commitXp(UUID.randomUUID(), 0, 10),
            "an absent legacy field must remain eligible for its first receipt");
    }

    @Test
    void shieldReplayWindowRejectsDuplicateOldAndRestartReplay() {
        GuardCombatLedger ledger = new GuardCombatLedger();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();

        GuardCombatLedger.ShieldCommit first = ledger.commitShield(firstId, 90L);
        assertNotNull(first);
        assertNull(ledger.commitShield(firstId, 90L));
        GuardCombatLedger.ShieldCommit second = ledger.commitShield(secondId, 91L);
        assertNotNull(second);
        assertEquals(2L, second.countAfter());
        assertNull(ledger.commitShield(UUID.randomUUID(), 90L),
            "an older terminal tick can never return after the window advances");

        GuardCombatLedger loaded = new GuardCombatLedger();
        loaded.readNbt(ledger.writeNbt());
        assertNull(loaded.commitShield(secondId, 91L),
            "same-tick identity must remain consumed after restart");
        assertNotNull(loaded.commitShield(UUID.randomUUID(), 92L));
    }

    @Test
    void sameTickShieldSetIsBoundedWithoutBlockingFutureTicks() {
        GuardCombatLedger ledger = new GuardCombatLedger();
        for (int i = 0; i < GuardCombatLedger.MAX_SHIELD_ACTIONS_PER_TICK; i++) {
            assertNotNull(ledger.commitShield(new UUID(1L, i + 1L), 100L));
        }
        assertNull(ledger.commitShield(UUID.randomUUID(), 100L));
        assertNotNull(ledger.commitShield(UUID.randomUUID(), 101L));
        assertFalse(ledger.quarantined());
    }
}
