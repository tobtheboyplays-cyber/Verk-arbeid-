package com.hearthstead.entity.combat;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CinematicOpportunityTest {
    @Test
    void onlyARealLowHealthOutcomeOpensAWindow() {
        UUID raider = UUID.randomUUID();
        UUID guard = UUID.randomUUID();

        assertNull(CinematicOpportunity.open(raider, guard, 8.0F, 20.0F, 100L));
        assertNull(CinematicOpportunity.open(raider, guard, 0.0F, 20.0F, 100L));
        assertNull(CinematicOpportunity.open(raider, guard, 4.0F, 0.0F, 100L));
        assertNull(CinematicOpportunity.open(raider, guard, Float.NaN, 20.0F, 100L));
        assertNull(CinematicOpportunity.open(raider, guard, 4.0F,
            Float.POSITIVE_INFINITY, 100L));

        CinematicOpportunity window = CinematicOpportunity.open(raider, guard,
            7.0F, 20.0F, 100L);
        assertEquals(CinematicOpportunity.State.OFFERED, window.state());
        assertEquals(120L, window.expiresAt());
    }

    @Test
    void sideHelpOrAnArrowCanClearAClaimedWindow() {
        UUID raider = UUID.randomUUID();
        UUID firstGuard = UUID.randomUUID();
        UUID sideGuard = UUID.randomUUID();
        CinematicOpportunity window = CinematicOpportunity.open(raider,
            firstGuard, 3.0F, 20.0F, 100L);

        assertTrue(window.claim(firstGuard, 104L));
        assertTrue(window.mayResolve(firstGuard, 105L));

        // The second guard is not rejected; their ordinary accepted hit clears
        // only the cinematic invitation before its own damage resolves.
        window.clear(CinematicOpportunity.ClearReason.INTERVENING_HIT);
        assertFalse(window.mayResolve(firstGuard, 106L));
        assertFalse(window.claim(sideGuard, 106L));
        assertEquals(CinematicOpportunity.ClearReason.INTERVENING_HIT,
            window.clearReason());
    }

    @Test
    void anExpiredOrWrongGuardFinisherFailsClosedWithoutOwningEntityCleanup() {
        UUID raider = UUID.randomUUID();
        UUID guard = UUID.randomUUID();
        CinematicOpportunity window = CinematicOpportunity.open(raider, guard,
            2.0F, 20.0F, 100L);

        assertTrue(window.claim(guard, 100L));
        assertFalse(window.mayResolve(UUID.randomUUID(), 104L));
        assertFalse(window.mayResolve(guard, 121L));
        assertEquals(CinematicOpportunity.State.CLAIMED, window.state(),
            "RaiderEntity owns the expiry transition so it can notify the claimant");
        assertNull(window.clearReason());
    }
}
