package com.hearthstead.entity;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnedProjectileLedgerTest {

    @Test
    void ownedProjectileCommitsEachVictimOnce() {
        CompoundTag persistent = new CompoundTag();
        UUID projectile = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID victim = UUID.randomUUID();

        assertTrue(OwnedProjectileLedger.issue(persistent, projectile, owner));
        OwnedProjectileLedger.ContactCommit first = OwnedProjectileLedger.commit(
            persistent, projectile, owner, victim);
        assertNotNull(first);
        assertEquals(0L, first.revisionBefore());
        assertEquals(1L, first.revisionAfter());
        assertNull(OwnedProjectileLedger.commit(persistent, projectile, owner,
            victim), "the same arrow/victim contact cannot replay");
        assertNull(OwnedProjectileLedger.commit(persistent, UUID.randomUUID(),
            owner, UUID.randomUUID()), "a copied tag cannot authorize another arrow");
        assertNull(OwnedProjectileLedger.commit(persistent, projectile,
            UUID.randomUUID(), UUID.randomUUID()),
            "a copied tag cannot authorize another archer");
    }

    @Test
    void arrowLedgerRoundTripPreservesIdentityAndReplayState() {
        CompoundTag original = new CompoundTag();
        UUID projectile = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID firstVictim = UUID.randomUUID();
        UUID secondVictim = UUID.randomUUID();
        assertTrue(OwnedProjectileLedger.issue(original, projectile, owner));
        assertNotNull(OwnedProjectileLedger.commit(original, projectile, owner,
            firstVictim));

        CompoundTag loaded = original.copy();
        assertNull(OwnedProjectileLedger.commit(loaded, projectile, owner,
            firstVictim), "restart must retain the consumed victim identity");
        OwnedProjectileLedger.ContactCommit second = OwnedProjectileLedger.commit(
            loaded, projectile, owner, secondVictim);
        assertNotNull(second);
        assertEquals(2L, second.countAfter());
    }

    @Test
    void malformedExistingLedgerCannotBeOverwrittenOrCommitted() {
        CompoundTag persistent = new CompoundTag();
        CompoundTag malformed = new CompoundTag();
        malformed.putInt("Schema", Integer.MAX_VALUE);
        persistent.put(OwnedProjectileLedger.ROOT_KEY, malformed);
        String before = persistent.toString();

        assertFalse(OwnedProjectileLedger.issue(persistent, UUID.randomUUID(),
            UUID.randomUUID()));
        assertNull(OwnedProjectileLedger.commit(persistent, UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID()));
        assertEquals(before, persistent.toString(),
            "a malformed authority tag must remain fail-closed, not be laundered");
    }

    @Test
    void contactHistoryHasAHardBound() {
        CompoundTag persistent = new CompoundTag();
        UUID projectile = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        assertTrue(OwnedProjectileLedger.issue(persistent, projectile, owner));
        for (int i = 0; i < OwnedProjectileLedger.MAX_CONTACTS; i++) {
            assertNotNull(OwnedProjectileLedger.commit(persistent, projectile,
                owner, new UUID(2L, i + 1L)));
        }
        assertNull(OwnedProjectileLedger.commit(persistent, projectile, owner,
            UUID.randomUUID()));
    }
}
