package com.hearthstead.settlement;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TavernVisitScheduleTest {
    @Test
    void exactEveningBoundariesRepeatAcrossDaysWithoutLunchAdmissions() {
        for (long day : new long[]{-2, -1, 0, 1, 400_000}) {
            long base = day * 24_000;
            for (int tick : new int[]{11000, 11500, 12000, 12699})
                assertTrue(TavernVisitSchedule.isOpen(base + tick), "open at " + (base + tick));
            for (int tick : new int[]{0, 999, 1000, 5499, 5500, 6000, 6999, 7000, 10999, 12700, 18000, 22999, 23000, 23999})
                assertFalse(TavernVisitSchedule.isOpen(base + tick), "closed at " + (base + tick));
        }
    }

    @Test
    void noVisitWindowOverlapsWorkSleepOrLunchAnywhereInTheDay() {
        for (int tick = 0; tick < 24_000; tick++) {
            DayPhase phase = DayPhase.of(tick);
            if (phase.work() || phase.rest() || phase.meal() || phase == DayPhase.RISE)
                assertFalse(TavernVisitSchedule.isOpen(tick), "work/sleep/waking exclusion at " + tick);
        }
    }
}
