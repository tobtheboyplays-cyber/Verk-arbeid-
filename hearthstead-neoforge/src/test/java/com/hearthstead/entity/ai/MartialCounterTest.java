package com.hearthstead.entity.ai;

import com.hearthstead.entity.RaiderEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Exact, world-free contract for the first Guard/Archer role counters. */
class MartialCounterTest {

    @Test
    void guardGetsExactlyTwentyFivePercentAgainstOrdinaryBrutesOnly() {
        assertEquals(1.25D, GuardMeleeGoal.counterDamageMultiplier(
            RaiderEntity.Variant.BRUTE, false));
        assertEquals(1.0D, GuardMeleeGoal.counterDamageMultiplier(
            RaiderEntity.Variant.SKIRMISHER, false));
        assertEquals(1.0D, GuardMeleeGoal.counterDamageMultiplier(
            RaiderEntity.Variant.BRUTE, true),
            "captain remains neutral even when visually built as a brute");
    }

    @Test
    void archerGetsExactlyTwentyFivePercentAgainstOrdinarySkirmishersOnly() {
        assertEquals(1.25D, ArcherAttackGoal.counterDamageMultiplier(
            RaiderEntity.Variant.SKIRMISHER, false));
        assertEquals(1.0D, ArcherAttackGoal.counterDamageMultiplier(
            RaiderEntity.Variant.BRUTE, false));
        assertEquals(1.0D, ArcherAttackGoal.counterDamageMultiplier(
            RaiderEntity.Variant.SKIRMISHER, true),
            "captain remains neutral even when visually built as a skirmisher");
    }
}
