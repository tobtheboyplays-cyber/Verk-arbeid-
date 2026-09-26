package com.hearthstead.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillLevelsBoundaryTest {
    @Test
    void progressResetsAtEachThresholdAndClampsAtBothEnds() {
        assertEquals(0F, SkillLevels.progress(-1));
        for (int level = 2; level < SkillLevels.MAX_LEVEL; level++) {
            int threshold = SkillLevels.xpForLevel(level);
            assertTrue(SkillLevels.progress(threshold - 1) < 1F);
            assertEquals(0F, SkillLevels.progress(threshold), "level " + level);
        }
        assertEquals(1F, SkillLevels.progress(Integer.MAX_VALUE));
        assertEquals(SkillLevels.MAX_XP, SkillLevels.clampXp(Integer.MAX_VALUE));
    }

    @Test
    void visibleBonusesRespectStartingLevelsAndProfessionGates() {
        assertTrue(SkillLevels.describeBonuses(Profession.LUMBERER, 1, 50, 50, 15).isEmpty());
        assertEquals(1, SkillLevels.describeBonuses(Profession.LUMBERER, 1, 50, 50, 16).size(),
            "baseline learning may start at level one when Wits exceeds the start cap");
        assertEquals(1, SkillLevels.describeBonuses(Profession.COURIER, 4, 50, 50, 15).size(),
            "Courier carry first appears at level four");
        assertEquals(2, SkillLevels.describeBonuses(Profession.SCHOLAR, 2, 50, 50, 20).size(),
            "Scholar research and learning are both visible above baseline Wits");
        assertTrue(SkillLevels.describeBonuses(Profession.GUARD, 10, 50, 50, 99).isEmpty(),
            "martial experience does not create trade bonus lines");
    }
}
