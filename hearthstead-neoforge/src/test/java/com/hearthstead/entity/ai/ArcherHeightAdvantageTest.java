package com.hearthstead.entity.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Owner addition (27 Sep): archers above their target shoot farther and truer;
 * ground-level archers keep today's balance. Pure numbers at the defaults.
 */
class ArcherHeightAdvantageTest {

    @Test
    void levelGroundKeepsTodaysRangeAndSpread() {
        assertEquals(0.0D, ArcherHeightAdvantage.bonusRange(0.0D));
        assertEquals(0.0D, ArcherHeightAdvantage.bonusRange(0.5D), "a slab or step is level ground");
        assertEquals(1.0D, ArcherHeightAdvantage.spreadScale(0.0D));
        assertEquals(1.0D, ArcherHeightAdvantage.spreadScale(Double.NaN));
    }

    @Test
    void rangeGrowsOneBlockPerBlockOfHeightUpToTheCap() {
        assertEquals(4.0D, ArcherHeightAdvantage.bonusRange(4.0D), 1.0E-9D);
        assertEquals(6.0D, ArcherHeightAdvantage.bonusRange(6.0D), 1.0E-9D);
        assertEquals(8.0D, ArcherHeightAdvantage.bonusRange(8.0D), 1.0E-9D);
        assertEquals(8.0D, ArcherHeightAdvantage.bonusRange(30.0D), 1.0E-9D, "capped at +8 (18 -> 26)");
    }

    @Test
    void spreadShrinksToAboutAThirdAtSixBlocks() {
        double atThree = ArcherHeightAdvantage.spreadScale(3.0D);
        double atSix = ArcherHeightAdvantage.spreadScale(6.0D);
        assertTrue(atThree < 1.0D && atThree > atSix, "accuracy improves steadily with height");
        assertEquals(0.33D, atSix, 1.0E-9D);
        assertEquals(atSix, ArcherHeightAdvantage.spreadScale(20.0D), 1.0E-9D, "no extra past six blocks");
    }

    /** The extra range must be physically reachable by a vanilla arrow's solved arc. */
    @Test
    void cappedRangeIsReachableByTheBallisticSolve() {
        for (double drop : new double[]{-2.0D, -5.0D, -8.0D}) {
            double input = ArcherAttackGoal.towerBallisticVerticalInput(26.0D, drop);
            assertTrue(Double.isFinite(input), "arc solved for 26 blocks at dy=" + drop);
            assertTrue(input != drop + 26.0D * .2D, "a real solution, not the fallback, at dy=" + drop);
        }
    }
}
