package com.hearthstead.settlement.guard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** JVM-level fail-closed contracts for the pre-raid Tower Post authority. */
class GuardAssignmentServiceTest {

    @Test
    void towerPostAvailabilityRejectsMissingAuthoritativeContext() {
        assertFalse(GuardAssignmentService.towerPostAvailable(null, null, null));
        assertEquals("guard_tower_locked",
            GuardAssignmentService.InvalidReason.TOWER_LOCKED.id());
    }
}
