package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.event.HearthsteadDayLength;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Pure-logic checks for the long-day clock. The tick handler itself is
 * disabled on the GameTest server, so only the policy seams are exercised.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class DayLengthGameTests {

    private static long holdsOver(long startGameTime, long ticks, double multiplier) {
        long holds = 0L;
        for (long g = startGameTime; g < startGameTime + ticks; g++) {
            if (HearthsteadDayLength.holdBackThisTick(g, multiplier)) {
                holds++;
            }
        }
        return holds;
    }

    @GameTest(template = "empty5")
    public void dayLengthAccumulatorGivesExactNetRate(GameTestHelper helper) {
        long day = 24_000L;
        // 2x: two real days of ticks advance exactly one in-game day.
        long twoX = holdsOver(0L, 2L * day, 2.0D);
        helper.assertTrue(twoX == day, "2x must hold back half the ticks, got " + twoX);
        // Late in a long-lived world the rate is unchanged.
        long lateTwoX = holdsOver(987_654_321L, 2L * day, 2.0D);
        helper.assertTrue(Math.abs(lateTwoX - day) <= 1L,
            "2x rate must not drift with large game time, got " + lateTwoX);
        long fourX = holdsOver(0L, 4L * day, 4.0D);
        helper.assertTrue(fourX == 3L * day, "4x must hold back 3 of 4 ticks, got " + fourX);
        long oneAndHalf = holdsOver(0L, 3L * day, 1.5D);
        helper.assertTrue(Math.abs(oneAndHalf - day) <= 1L,
            "1.5x must hold back a third of the ticks, got " + oneAndHalf);
        helper.assertTrue(holdsOver(0L, day, 1.0D) == 0L, "1x is vanilla");
        helper.assertTrue(holdsOver(0L, day, 0.25D) == 0L, "below range clamps to vanilla");
        helper.assertTrue(holdsOver(0L, day, Double.NaN) == 0L, "NaN is vanilla");
        helper.assertTrue(holdsOver(0L, 10L * day, 99.0D) == 10L * day * 3L / 4L,
            "above range clamps to 4x");
        // Never two consecutive holds at 2x: the sun never stalls visibly.
        for (long g = 0L; g < 1000L; g++) {
            helper.assertFalse(HearthsteadDayLength.holdBackThisTick(g, 2.0D)
                    && HearthsteadDayLength.holdBackThisTick(g + 1L, 2.0D),
                "2x holds must alternate at game time " + g);
        }
        helper.succeed();
    }

    @GameTest(template = "empty5")
    public void dayLengthOnlyCorrectsOrdinaryDaylightTicks(GameTestHelper helper) {
        helper.assertTrue(HearthsteadDayLength.isOrdinaryAdvance(true, true, true, 1000L, 1001L),
            "an ordinary +1 daylight tick is corrected");
        helper.assertFalse(HearthsteadDayLength.isOrdinaryAdvance(true, true, true, 13_000L, 24_000L),
            "a sleep skip is never undone");
        helper.assertFalse(HearthsteadDayLength.isOrdinaryAdvance(true, true, true, 5000L, 1000L),
            "a /time set backwards is never touched");
        helper.assertFalse(HearthsteadDayLength.isOrdinaryAdvance(true, true, false, 1000L, 1000L),
            "a frozen clock (doDaylightCycle false) is never touched");
        helper.assertFalse(HearthsteadDayLength.isOrdinaryAdvance(true, false, true, 1000L, 1001L),
            "other dimensions are never touched");
        helper.assertFalse(HearthsteadDayLength.isOrdinaryAdvance(false, true, true, 1000L, 1001L),
            "disabled means vanilla");
        helper.succeed();
    }
}
