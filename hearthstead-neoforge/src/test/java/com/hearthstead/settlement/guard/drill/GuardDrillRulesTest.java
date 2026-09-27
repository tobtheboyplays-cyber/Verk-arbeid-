package com.hearthstead.settlement.guard.drill;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guard Drill: when the yard opens, who drills (never the last guard on watch), where, what it pays. */
final class GuardDrillRulesTest {

    @Test
    void theYardOpensAfterTheHandOverAndClosesMidMorning() {
        assertFalse(GuardDrillRules.open(999L), "still the night watch's hour");
        assertTrue(GuardDrillRules.open(1000L));
        assertTrue(GuardDrillRules.joinable(1999L));
        assertFalse(GuardDrillRules.joinable(2000L), "nobody new after 08:00");
        assertTrue(GuardDrillRules.open(2799L));
        assertFalse(GuardDrillRules.open(2800L));
        assertFalse(GuardDrillRules.open(13000L), "never at night");
        // Any day, and a clock set backwards by commands.
        assertTrue(GuardDrillRules.open(24000L * 17 + 1500L));
        assertTrue(GuardDrillRules.open(-24000L + 1500L));
        assertEquals(17L, GuardDrillRules.day(24000L * 17 + 1500L));
        // Bounded: at most 2 in-game hours of yard, 1.6 per guard.
        assertTrue(GuardDrillRules.WINDOW_END - GuardDrillRules.WINDOW_START <= 2000L);
        assertTrue(GuardDrillRules.SESSION_MAX_TICKS <= 2000);
    }

    @Test
    void twoGuardsOneEachWatchTheNightGuardDrillsAloneAndTheDayGuardKeepsWatch() {
        List<String> r = GuardDrillRules.roster(List.of("night"), List.of("day"), 1, 0);
        assertEquals(List.of("night"), r);
    }

    @Test
    void threeGuardsDraftOneDayGuardSoTheNightGuardHasAPartner() {
        List<String> r = GuardDrillRules.roster(List.of("night"), List.of("dayA", "dayB"), 2, 0);
        assertEquals(List.of("night", "dayA"), r, "a pair, and dayB stays on watch");
    }

    @Test
    void fourGuardsTheRelievedWatchPairsUpAndNobodyOnWatchIsTouched() {
        List<String> r = GuardDrillRules.roster(List.of("n1", "n2"), List.of("d1", "d2"), 2, 0);
        assertEquals(List.of("n1", "n2"), r);
    }

    @Test
    void noNightWatchStillDrillsWhenThreeAreOnWatchButNeverEmptiesTheWalls() {
        assertEquals(List.of("d1", "d2"), GuardDrillRules.roster(List.<String>of(), List.of("d1", "d2", "d3"), 3, 0));
        assertEquals(List.of(), GuardDrillRules.roster(List.<String>of(), List.of("d1", "d2"), 2, 0),
            "two on watch: drafting both would leave nobody, drafting one would drill alone for nothing");
        assertEquals(List.of(), GuardDrillRules.roster(List.<String>of(), List.of("d1"), 1, 0));
    }

    @Test
    void aLateJoinerFillsAnOddYardButTheLastWatchmanStays() {
        // One already drilling alone; a day guard is drafted only if another still watches.
        assertEquals(List.of("d1"), GuardDrillRules.roster(List.<String>of(), List.of("d1"), 2, 1));
        assertEquals(List.of(), GuardDrillRules.roster(List.<String>of(), List.of("d1"), 1, 1));
        // Never more than two drafted.
        List<String> many = GuardDrillRules.roster(List.<String>of(), List.of("a", "b", "c", "d"), 4, 0);
        assertTrue(many.size() <= GuardDrillRules.MAX_DRAFTED);
    }

    @Test
    void partnersStandSideBySideAcrossARealGapAndPairsStepOutwards() {
        assertEquals(1, GuardDrillRules.partnerIndex(0));
        assertEquals(0, GuardDrillRules.partnerIndex(1));
        assertEquals(3, GuardDrillRules.partnerIndex(2));
        int[] a = GuardDrillRules.slot(0);
        int[] b = GuardDrillRules.slot(1);
        int gap = Math.abs(a[0] - b[0]);
        assertTrue(gap >= 2 && gap <= 3, "2-3 blocks between partners: " + gap);
        assertEquals(a[1], b[1], "partners on one row");
        assertTrue(GuardDrillRules.slot(2)[1] - a[1] >= 3, "the next pair a row further out");
        assertTrue(a[1] >= 2.0, "clear of the Barracks wall");
    }

    @Test
    void theYardFacesTheSettlementCentre() {
        assertArrayEquals(new int[] {0, 1}, GuardDrillRules.face(0, 0, 20, 3));
        assertArrayEquals(new int[] {0, -1}, GuardDrillRules.face(0, 0, -20, 3));
        assertArrayEquals(new int[] {1, 1}, GuardDrillRules.face(0, 0, 2, 9));
        assertArrayEquals(new int[] {1, -1}, GuardDrillRules.face(0, 0, 2, -9));
        assertArrayEquals(new int[] {1, 1}, GuardDrillRules.face(5, 5, 5, 5), "at the centre: +Z");
    }

    @Test
    void aFreshGuardWithABiggerHealthBarIsNotWounded() {
        assertTrue(24.0F >= GuardDrillRules.minHealth(40.0F), "24 of 40 hp may drill");
        assertTrue(24.0F >= GuardDrillRules.minHealth(24.0F));
        assertFalse(10.0F >= GuardDrillRules.minHealth(24.0F), "10 of 24 is wounded");
        assertFalse(12.0F >= GuardDrillRules.minHealth(60.0F), "12 hp is wounded whatever the bar");
    }

    @Test
    void drillXpStopsAtTheTrainedTierAndStrengthIsCappedPerSession() {
        assertEquals(GuardDrillRules.SESSION_XP, GuardDrillRules.sessionXp(0));
        assertEquals(2, GuardDrillRules.sessionXp(GuardDrillRules.XP_CEILING - 2));
        assertEquals(0, GuardDrillRules.sessionXp(GuardDrillRules.XP_CEILING));
        assertEquals(0, GuardDrillRules.sessionXp(300), "a veteran learns nothing more in the yard");
        assertEquals(40, GuardDrillRules.XP_CEILING, "Trained tier");
        float paid = 0.0F;
        for (int i = 0; i < 100; i++) paid += GuardDrillRules.reps(paid, 1);
        assertEquals(GuardDrillRules.REPS_PER_SESSION, paid, 1.0E-4F);
    }
}
