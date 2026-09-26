package com.hearthstead.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillLevelsTest {

    @Test
    void documentedCurve() {
        int[] expected = {0, 0, 25, 74, 147, 244, 365, 510, 679, 872, 1089};
        for (int level = 1; level <= SkillLevels.MAX_LEVEL; level++) {
            assertEquals(expected[level], SkillLevels.xpForLevel(level), "level " + level);
            assertEquals(level, SkillLevels.levelOf(expected[level]));
            if (level > 1) {
                assertEquals(level - 1, SkillLevels.levelOf(expected[level] - 1));
            }
        }
        assertEquals(1089, SkillLevels.MAX_XP);
        assertEquals(10, SkillLevels.levelOf(Integer.MAX_VALUE));
        assertEquals(1, SkillLevels.levelOf(-5));
        assertEquals(25, SkillLevels.xpToNext(0));
        assertEquals(0, SkillLevels.xpToNext(5000));
        assertEquals(1.0F, SkillLevels.progress(SkillLevels.MAX_XP));
    }

    @Test
    void levelOneIsIdentity() {
        for (int t = 0; t < 500; t += 7) {
            assertEquals(t, SkillLevels.shortenWait(t, 1, 99));
            assertEquals(t, SkillLevels.shortenLooped(t, 20, 1, 99));
        }
        assertEquals(0.0D, SkillLevels.speedBonus(1, 99));
        assertEquals(0.0D, SkillLevels.sideChance(4, 99));
        assertEquals(3, SkillLevels.witsScaledXp(3, SettlerAttributes.START_CAP, 0.0D));
    }

    @Test
    void effectsAreModestAndCapped() {
        assertEquals(0.18D, SkillLevels.speedBonus(10, 50), 1e-9);
        assertEquals(0.09D, SkillLevels.speedBonus(10, 0), 1e-9);
        assertTrue(SkillLevels.speedBonus(99, 99) <= SkillLevels.MAX_SPEED_BONUS);
        assertTrue(SkillLevels.sideChance(10, 99) <= SkillLevels.MAX_SIDE_CHANCE);
        assertEquals(0.25D, SkillLevels.witsXpBonus(99), 1e-9);
        assertEquals(0.10D, SkillLevels.witsXpBonus(25), 1e-9);
        // 1 XP at +10%: 1.1 -> 1 plus a 10% chance of one more.
        assertEquals(2, SkillLevels.witsScaledXp(1, 25, 0.05D));
        assertEquals(1, SkillLevels.witsScaledXp(1, 25, 0.5D));
        int looped = SkillLevels.shortenLooped(200, 20, 10, 50);
        assertEquals(180, looped);
        assertEquals(20, SkillLevels.shortenLooped(20, 20, 10, 50));
    }
}
