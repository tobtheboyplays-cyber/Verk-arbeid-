package com.hearthstead.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure per-trade bonus formulas: level 1 is identity, every bonus capped. */
class SkillLevelsBonusTest {

    @Test
    void carryBonusIsOnePerThreeLevelsCappedAtThree() {
        int[] expected = {0, 0, 0, 0, 1, 1, 1, 2, 2, 2, 3};
        for (int level = 0; level <= 10; level++) {
            assertEquals(expected[level], SkillLevels.carryBonus(level), "level " + level);
        }
        assertEquals(SkillLevels.MAX_CARRY_BONUS, SkillLevels.carryBonus(99));
    }

    @Test
    void paceBonusStartsAtLevelFiveAndCapsAtEightPercent() {
        for (int level = 1; level < 5; level++) {
            assertEquals(0.0D, SkillLevels.paceBonus(level, 99));
        }
        assertEquals(0.01D + 10 / 2000.0D, SkillLevels.paceBonus(5, 10), 1e-9);
        assertEquals(SkillLevels.MAX_SIDE_CHANCE, SkillLevels.paceBonus(10, 99), 1e-9);
    }

    @Test
    void researchBonusIsWitsAboveFifteenFromLevelTwo() {
        assertEquals(0.0D, SkillLevels.researchBonus(1, 99), "level 1 is unchanged");
        assertEquals(0.0D, SkillLevels.researchBonus(5, 15));
        assertEquals(0.05D, SkillLevels.researchBonus(2, 20), 1e-9);
        assertEquals(0.25D, SkillLevels.researchBonus(10, 99), 1e-9);
    }

    @Test
    void levelOneWaitsAndSideRollsAreIdentity() {
        for (int ticks : new int[] {0, 1, 20, 39, 60, 80, 100}) {
            assertEquals(ticks, SkillLevels.shortenWait(ticks, 1, 99));
        }
        assertEquals(0.0D, SkillLevels.sideChance(1, 99));
        assertEquals(0.0D, SkillLevels.sideChance(4, 99));
    }

    @Test
    void highLevelWaitsAreShorterButCapped() {
        // Innkeeper cooked meal 80 -> 66 at the 18% cap; miner 20 -> 17.
        assertEquals(66, SkillLevels.shortenWait(80, 10, 50));
        assertEquals(17, SkillLevels.shortenWait(20, 10, 50));
        for (int ticks = 2; ticks <= 200; ticks++) {
            int shortened = SkillLevels.shortenWait(ticks, 10, 99);
            assertTrue(shortened >= ticks * (1 - SkillLevels.MAX_SPEED_BONUS) - 1e-9
                && shortened >= 1 && shortened <= ticks, "ticks " + ticks);
        }
    }

    @Test
    void bonusesUseTheProfileCoreAttributes() {
        assertEquals(Attribute.STAMINA, SkillLevels.secondaryOf(Profession.FARMER).orElseThrow());
        assertEquals(Attribute.STAMINA, SkillLevels.secondaryOf(Profession.COURIER).orElseThrow());
        assertEquals(Attribute.SPIRIT, SkillLevels.secondaryOf(Profession.INNKEEPER).orElseThrow());
        assertEquals(Attribute.DEXTERITY, SkillLevels.secondaryOf(Profession.HUNTER).orElseThrow());
        assertEquals(Attribute.PRESENCE, SkillLevels.primaryOf(Profession.INNKEEPER).orElseThrow());
    }
}
