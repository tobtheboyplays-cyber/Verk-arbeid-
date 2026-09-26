package com.hearthstead.settlement.development;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DevelopmentBonusesBaselineTest {
    @Test
    void absentServerOrSettlementLeavesEveryBonusAtItsOldValue() {
        assertFalse(DevelopmentBonuses.owned(null, null, PostRaidUpgrade.SHARPENED_AXES));
        assertEquals(63, DevelopmentBonuses.fellingTicks(null, null, 63));
        assertEquals(0, DevelopmentBonuses.courierStrapBonus(null, null));
        assertEquals(7, DevelopmentBonuses.guardCombatXp(null, null, 7));
        assertEquals(0, DevelopmentBonuses.guardCombatXp(null, null, 0));
        assertEquals(-1, DevelopmentBonuses.guardCombatXp(null, null, -1));
        assertEquals(1F, DevelopmentBonuses.hungerDrainScale(null));
        assertEquals(0, DevelopmentBonuses.bedMorale(null));
        assertEquals(1F, DevelopmentBonuses.sleepEnergyScale(null));
        assertEquals(80, DevelopmentBonuses.fisherCycleTicks(null, 80));
    }
}
