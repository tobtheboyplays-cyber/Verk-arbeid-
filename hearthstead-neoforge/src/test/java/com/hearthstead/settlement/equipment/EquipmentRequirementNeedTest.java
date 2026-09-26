package com.hearthstead.settlement.equipment;

import com.hearthstead.entity.Profession;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BH-24: a stored request carries its requester's Gear Tier cap, so the Arm
 * the Watch Guard-delivery credit must compare the need, not the record.
 */
class EquipmentRequirementNeedTest {

    @Test
    void aGearCappedGuardRequestIsStillTheGuardNeed() {
        EquipmentRequirement guard = EquipmentRequests.requirementFor(Profession.GUARD);
        assertNotNull(guard);
        EquipmentRequirement recruit = guard.withMaxGearTier(1);
        assertNotEquals(guard, recruit, "the cap is part of the record: equality alone would miss the credit");
        assertTrue(recruit.sameNeed(guard), "a Recruit's capped sword request is the Guard need");
        assertTrue(guard.sameNeed(recruit));
        assertFalse(recruit.sameNeed(EquipmentRequests.requirementFor(Profession.ARCHER)),
            "a bow request is never credited as the Guard's");
        assertFalse(recruit.sameNeed(null));
    }
}
