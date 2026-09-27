package com.hearthstead.revive;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviveRulesTest {
    private static ReviveRules.DownContext ctx(boolean enabled, boolean creative, boolean bypass,
                                               boolean already, boolean raid, boolean at,
                                               boolean ally, boolean solo) {
        return new ReviveRules.DownContext(enabled, creative, bypass, already, raid, at, ally, solo);
    }

    @Test
    void downsOnlyDuringRaidAtSettlementWithAlly() {
        assertTrue(ReviveRules.shouldDown(ctx(true, false, false, false, true, true, true, false)));
        assertFalse(ReviveRules.shouldDown(ctx(true, false, false, false, false, true, true, false)),
            "no raid (and no grace) -> normal death");
        assertFalse(ReviveRules.shouldDown(ctx(true, false, false, false, true, false, true, false)),
            "away from the settlement -> normal death");
        assertFalse(ReviveRules.shouldDown(ctx(true, false, false, false, true, true, false, false)),
            "solo without the option -> normal death");
        assertTrue(ReviveRules.shouldDown(ctx(true, false, false, false, true, true, false, true)),
            "solo option on");
    }

    @Test
    void neverBypassesKillVoidCreativeOrDoubleDown() {
        assertFalse(ReviveRules.shouldDown(ctx(false, false, false, false, true, true, true, true)));
        assertFalse(ReviveRules.shouldDown(ctx(true, true, false, false, true, true, true, true)),
            "creative / spectator");
        assertFalse(ReviveRules.shouldDown(ctx(true, false, true, false, true, true, true, true)),
            "/kill, void, bleed-out bypass invulnerability");
        assertFalse(ReviveRules.shouldDown(ctx(true, false, false, true, true, true, true, true)),
            "a downed player hit to zero again is finished off");
        assertFalse(ReviveRules.shouldDown(null));
    }

    @Test
    void graceWindow() {
        assertTrue(ReviveRules.inGrace(1_000L, 1_000L, 1_200L));
        assertTrue(ReviveRules.inGrace(2_200L, 1_000L, 1_200L));
        assertFalse(ReviveRules.inGrace(2_201L, 1_000L, 1_200L));
        assertFalse(ReviveRules.inGrace(1_000L, -1L, 1_200L), "never seen active");
        assertFalse(ReviveRules.inGrace(1_000L, 900L, 0L), "grace disabled");
        assertFalse(ReviveRules.inGrace(800L, 900L, 1_200L), "clock went backwards");
    }

    @Test
    void bleedTimerCountsDownAndPauses() {
        ReviveRules.BleedTimer timer = new ReviveRules.BleedTimer(3);
        assertFalse(timer.tick(false));
        assertEquals(2, timer.ticksLeft());
        assertFalse(timer.tick(true), "paused while reviving");
        assertEquals(2, timer.ticksLeft());
        assertFalse(timer.tick(false));
        assertTrue(timer.tick(false), "fires exactly once at zero");
        assertTrue(timer.expired());
        assertFalse(timer.tick(false), "never fires twice");
        assertEquals(0, timer.ticksLeft());
        assertEquals(1, new ReviveRules.BleedTimer(0).totalTicks(), "at least one tick");
    }

    @Test
    void reviveCompletesWhileHeld() {
        ReviveRules.ReviveProgress p = new ReviveRules.ReviveProgress(60);
        assertEquals(ReviveRules.Step.IDLE, p.tick(0));
        long t = 0;
        ReviveRules.Step step = ReviveRules.Step.IDLE;
        int ticks = 0;
        while (step != ReviveRules.Step.COMPLETE && ticks < 200) {
            if (t % 4 == 0) {
                p.ping(t); // a held use key re-sends every 4 ticks
            }
            step = p.tick(t);
            t++;
            ticks++;
        }
        assertEquals(ReviveRules.Step.COMPLETE, step);
        assertEquals(60, ticks, "exactly the configured hold");
        assertFalse(p.active());
    }

    @Test
    void releasingTheKeyLapsesAndResets() {
        ReviveRules.ReviveProgress p = new ReviveRules.ReviveProgress(60);
        p.ping(0);
        for (long t = 0; t <= 10; t++) {
            if (t % 4 == 0) {
                p.ping(t);
            }
            assertEquals(ReviveRules.Step.PROGRESS, p.tick(t));
        }
        // key released after the ping at t=8
        ReviveRules.Step step = ReviveRules.Step.PROGRESS;
        long t = 11;
        while (step == ReviveRules.Step.PROGRESS) {
            step = p.tick(t++);
        }
        assertEquals(ReviveRules.Step.LAPSED, step);
        assertEquals(8 + ReviveRules.REVIVE_PING_GRACE_TICKS + 1, t - 1, "lapses after the grace");
        assertEquals(0, p.progressTicks());
    }

    @Test
    void damageInterruptResetsProgress() {
        ReviveRules.ReviveProgress p = new ReviveRules.ReviveProgress(20);
        for (long t = 0; t < 10; t++) {
            p.ping(t);
            p.tick(t);
        }
        assertEquals(10, p.progressTicks());
        p.interrupt();
        assertEquals(0, p.progressTicks());
        assertFalse(p.active());
        assertEquals(ReviveRules.Step.IDLE, p.tick(11));
    }

    @Test
    void reviveHealthAndConversions() {
        assertEquals(6.0F, ReviveRules.reviveHealth(20.0F, 30), 1.0E-6F);
        assertEquals(1.0F, ReviveRules.reviveHealth(2.0F, 1), 1.0E-6F, "at least one half-heart");
        assertEquals(20.0F, ReviveRules.reviveHealth(20.0F, 250), 1.0E-6F, "clamped to max");
        assertEquals(60, ReviveRules.secondsToTicks(3.0D));
        assertEquals(1000, ReviveRules.secondsToTicks(50));
        assertEquals(1, ReviveRules.secondsToTicks(0.0D));
    }

    @Test
    void crashRecoveryKeepsMarkerUntilDeathIsConfirmed() {
        assertEquals(ReviveRules.LoginRecovery.NOTHING, ReviveRules.loginRecovery(false, true, false));
        assertEquals(ReviveRules.LoginRecovery.KILL, ReviveRules.loginRecovery(true, true, false));
        assertEquals(ReviveRules.LoginRecovery.CLEAR_MARKER, ReviveRules.loginRecovery(true, false, false),
            "already dead: nothing to kill, marker goes");
        assertEquals(ReviveRules.LoginRecovery.CLEAR_MARKER, ReviveRules.loginRecovery(true, true, true),
            "creative/spectator are never killed by recovery");
        assertFalse(ReviveRules.markerMayClear(true),
            "another mod refused the hit: the marker must survive for the retry");
        assertTrue(ReviveRules.markerMayClear(false));
        assertTrue(ReviveRules.LOGIN_RECOVERY_RETRIES > 0);
    }

    @Test
    void heartbeatQuickensAsTimeRunsOut() {
        int calm = ReviveRules.heartbeatInterval(1000, 1000);
        int racing = ReviveRules.heartbeatInterval(0, 1000);
        assertEquals(22, calm);
        assertEquals(13, racing);
        assertTrue(ReviveRules.heartbeatInterval(500, 1000) < calm);
        assertTrue(racing * 50 >= 620, "never faster than the 0.62 s sample");
    }
}
