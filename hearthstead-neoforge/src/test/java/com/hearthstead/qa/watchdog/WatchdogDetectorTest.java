package com.hearthstead.qa.watchdog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class WatchdogDetectorTest {
    private final WatchdogDetector.Track track = new WatchdogDetector.Track();
    private final WatchdogDetector.Sample sample = new WatchdogDetector.Sample();
    private final WatchdogDetector.Result result = new WatchdogDetector.Result();
    private int raised;
    private int cleared;

    private void base(long tick) {
        sample.tick = tick;
        sample.workPhase = true;
        sample.employed = true;
        sample.kind = GoalKind.WORK;
        sample.jobGoalId = 7;
    }

    private void step() {
        WatchdogDetector.update(track, sample, result);
        raised |= result.raised;
        cleared |= result.cleared;
    }

    private void run(long from, long to) {
        for (long t = from; t <= to; t += 20) {
            sample.tick = t;
            step();
        }
    }

    private boolean active(WatchdogFlag flag) {
        return (track.active & flag.bit()) != 0;
    }

    @Test
    void standingStillWithAWorkGoalIsStuckAfterSixtySeconds() {
        base(0);
        step();
        run(20, 1_180);
        assertFalse(active(WatchdogFlag.STUCK), "59 s is not yet a stall");
        run(1_200, 1_220);
        assertTrue(active(WatchdogFlag.STUCK));
        assertFalse(track.stuckNoOutput);
        // Output resumes: cleared, with the episode duration reported.
        sample.outputHash = 99;
        sample.tick = 1_240;
        step();
        assertFalse(active(WatchdogFlag.STUCK));
        assertTrue(result.clearedDuration[WatchdogFlag.STUCK.ordinal()] >= 20);
        assertTrue(track.productive);
    }

    @Test
    void movingWithoutOutputIsANoOutputStall() {
        base(0);
        step();
        for (long t = 20; t <= 3_700; t += 20) {
            sample.tick = t;
            sample.x = (t / 20) % 2 == 0 ? 0 : 5; // pacing back and forth
            step();
        }
        assertTrue(active(WatchdogFlag.STUCK));
        assertTrue(track.stuckNoOutput);
    }

    @Test
    void aGuardHoldingAPostIsNotStuck() {
        base(0);
        sample.stationaryOk = true;
        step();
        run(20, 5_000);
        assertFalse(active(WatchdogFlag.STUCK));
        assertTrue(track.productive);
    }

    @Test
    void theNightIsNotAStallAndANewWorkdayRestartsTheClock() {
        base(0);
        sample.workPhase = false;
        step();
        run(20, 10_000);
        assertEquals(0, raised);
        sample.workPhase = true;
        run(10_020, 11_000);
        assertFalse(active(WatchdogFlag.STUCK), "a fresh workday gets its own 60 s");
    }

    @Test
    void employedWithNoJobGoalInAWorkPhaseIsIdle() {
        base(0);
        sample.kind = GoalKind.IDLE;
        sample.jobGoalId = 0;
        step();
        run(20, 1_300);
        assertTrue(active(WatchdogFlag.IDLE_IN_WORK));
        assertFalse(active(WatchdogFlag.STUCK), "idle is its own class, not STUCK");
        sample.kind = GoalKind.NEED; // went to eat: legitimate
        sample.tick = 1_320;
        step();
        assertFalse(active(WatchdogFlag.IDLE_IN_WORK));
    }

    @Test
    void restartingTheSameGoalWithoutOutputIsALoop() {
        base(0);
        step();
        for (long t = 20; t <= 1_400; t += 20) {
            sample.tick = t;
            sample.jobGoalId = (t / 100) % 2 == 0 ? 7 : 0; // on, off, on ...
            sample.kind = sample.jobGoalId == 0 ? GoalKind.IDLE : GoalKind.WORK;
            sample.x = t % 200 == 0 ? 3 : 0; // enough movement to dodge STUCK
            step();
        }
        assertTrue((raised & WatchdogFlag.LOOP.bit()) != 0);
    }

    @Test
    void repeatedRouteFailuresArePathFail() {
        base(0);
        step();
        sample.routeFailureTick = 10;
        run(20, 100);
        sample.routeFailureTick = 110;
        run(120, 200);
        assertFalse(active(WatchdogFlag.PATH_FAIL));
        sample.routeFailureTick = 210;
        run(220, 240);
        assertTrue(active(WatchdogFlag.PATH_FAIL));
        run(260, 1_600);
        assertFalse(active(WatchdogFlag.PATH_FAIL), "a quiet window clears it");
    }

    @Test
    void carryingTheSameThingForTenMinutesIsOrphaned() {
        base(0);
        sample.carrying = true;
        sample.carryHash = 5;
        step();
        run(20, 11_980);
        assertFalse(active(WatchdogFlag.ORPHANED_ITEMS));
        run(12_000, 12_020);
        assertTrue(active(WatchdogFlag.ORPHANED_ITEMS));
        sample.carrying = false;
        sample.tick = 12_040;
        step();
        assertFalse(active(WatchdogFlag.ORPHANED_ITEMS));
    }

    @Test
    void anUnloadedGapClosesEpisodesAndDoesNotCountAsStall() {
        base(0);
        step();
        run(20, 1_300);
        assertTrue(active(WatchdogFlag.STUCK));
        // Player left; chunk unloaded for a whole day.
        sample.tick = 30_000;
        step();
        assertEquals(0, track.active);
        assertTrue((result.cleared & WatchdogFlag.STUCK.bit()) != 0);
        assertTrue(result.clearedDuration[WatchdogFlag.STUCK.ordinal()] < 200,
            "the gap must not be billed to the stall");
        run(30_020, 31_000);
        assertFalse(active(WatchdogFlag.STUCK));
    }

    @Test
    void aRestThatNeverRecoversInTheWorkdayIsStuck() {
        base(0);
        sample.kind = GoalKind.NEED;
        sample.jobGoalId = 0;
        sample.needLevel = 20;
        step();
        for (long t = 20; t <= 3_700; t += 20) {
            sample.tick = t;
            sample.x = (t / 20) % 2 == 0 ? 0 : 5; // walking bed <-> hearth
            sample.needLevel = 20 - t / 1_000F; // slowly draining
            step();
        }
        assertTrue(active(WatchdogFlag.STUCK));
        assertTrue(track.stuckNeed);
    }

    @Test
    void aRestThatRecoversIsNotStuck() {
        base(0);
        sample.kind = GoalKind.NEED;
        sample.jobGoalId = 0;
        sample.needLevel = 20;
        step();
        for (long t = 20; t <= 6_000; t += 20) {
            sample.tick = t;
            sample.needLevel = 20 + t / 100F; // +1 every 100 ticks
            step();
        }
        assertFalse(active(WatchdogFlag.STUCK));
    }
}
