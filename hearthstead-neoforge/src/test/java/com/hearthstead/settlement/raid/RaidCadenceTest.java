package com.hearthstead.settlement.raid;

import com.hearthstead.settlement.state.RaidLifecycle;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure cadence math, save migration and the hold-notice throttle. */
class RaidCadenceTest {

    private static final long DAY = RaidLifecycle.DAY_LENGTH;

    @Test
    void defaultWindowIsThreeToFourDays() {
        RaidCadence.Window window = RaidCadence.DEFAULT_WINDOW;
        assertEquals(3, window.minDays());
        assertEquals(4, window.maxDays());
        assertEquals(window, com.hearthstead.HearthsteadServerConfig.recurringRaidWindow(),
            "an unloaded server config must fall back to the owner default");
    }

    @Test
    void pickStaysInsideTheWindowAndUsesBothDays() {
        RandomSource random = RandomSource.create(1234L);
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            int days = RaidCadence.pickIntervalDays(RaidCadence.DEFAULT_WINDOW, random);
            assertTrue(days == 3 || days == 4, "pick " + days);
            seen.add(days);
        }
        assertEquals(Set.of(3, 4), seen, "the small random pick must reach both days");
        assertEquals(5, RaidCadence.pickIntervalDays(RaidCadence.window(5, 5), random));
        assertEquals(3, RaidCadence.pickIntervalDays(RaidCadence.DEFAULT_WINDOW, null));
    }

    @Test
    void windowIsValidatedAndNeverEmpty() {
        assertEquals(new RaidCadence.Window(6, 6), RaidCadence.window(6, 2),
            "a max below the min reads as exactly the min");
        RaidCadence.Window clamped = RaidCadence.window(-4, 999);
        assertEquals(RaidCadence.MIN_ALLOWED_DAYS, clamped.minDays());
        assertEquals(RaidCadence.MAX_ALLOWED_DAYS, clamped.maxDays());
    }

    @Test
    void attackIsOnDayThreeOrFourAndWarnedTheEveningBefore() {
        long raidNight = 20L;
        for (int days = 3; days <= 4; days++) {
            long attack = RaidCadence.attackNightAfter(raidNight, days);
            assertEquals(raidNight + days, attack);
            assertEquals(attack - 1L, RaidCadence.warningNightAfter(raidNight, days));
            assertTrue(RaidCadence.attackWithinWindow(raidNight, attack,
                RaidCadence.DEFAULT_WINDOW));
        }
        assertFalse(RaidCadence.attackWithinWindow(raidNight, raidNight + 2L,
            RaidCadence.DEFAULT_WINDOW), "day 2 is too early");
        assertFalse(RaidCadence.attackWithinWindow(raidNight, raidNight + 5L,
            RaidCadence.DEFAULT_WINDOW), "day 5 is too late");
        assertEquals(Long.MAX_VALUE, RaidCadence.attackNightAfter(Long.MAX_VALUE - 1L, 4));
    }

    @Test
    void lifecyclePersistsThePickAndCountsFromTheRaidNight() {
        RaidLifecycle lifecycle = completedLifecycle();
        long raidNight = 30L;
        // Resolved after midnight: still raid night 30 (dusk of 31 not yet passed).
        lifecycle.recordRecurringCadence(10_000L, (raidNight + 1L) * DAY + 2_000L,
            RaidLifecycle.CadenceOutcome.LOST, 4);
        assertEquals(raidNight, lifecycle.recurringLastRaidNight());
        assertEquals(4, lifecycle.recurringIntervalDays());
        assertEquals(raidNight + 4L, lifecycle.recurringNextAttackNight());
        assertFalse(lifecycle.recurringCadenceDue(raidNight + 2L));
        assertTrue(lifecycle.recurringCadenceDue(raidNight + 3L),
            "the warning dusk is the evening before the attack");

        RaidLifecycle reloaded = RaidLifecycle.readNbt(lifecycle.writeNbt(),
            RaidCadence.window(7, 9));
        assertEquals(raidNight + 4L, reloaded.recurringNextAttackNight(),
            "a reload never rerolls or re-windows an already drawn pick");
        assertEquals(4, reloaded.recurringIntervalDays());
        assertFalse(reloaded.recurringScheduleBlocked());
        assertFalse(reloaded.integrityLost());
    }

    @Test
    void legacySaveIsMigratedOntoTheWindow() {
        // 25-Sep cadence, held raid on night 40: warning 41, attack 42.
        CompoundTag held = legacyTag(41L, false);
        RaidLifecycle migratedHeld = RaidLifecycle.readNbt(held, RaidCadence.DEFAULT_WINDOW);
        assertEquals(40L, migratedHeld.recurringLastRaidNight());
        assertEquals(43L, migratedHeld.recurringNextAttackNight(),
            "a held legacy raid moves from day 2 to the window's day 3");
        assertEquals(3, migratedHeld.recurringIntervalDays());
        assertFalse(migratedHeld.recurringScheduleBlocked());
        assertFalse(migratedHeld.integrityLost());

        // Lost raid on night 40 (breather set): warning 42, attack 43 -- unchanged.
        RaidLifecycle migratedLost = RaidLifecycle.readNbt(legacyTag(42L, true),
            RaidCadence.DEFAULT_WINDOW);
        assertEquals(40L, migratedLost.recurringLastRaidNight());
        assertEquals(43L, migratedLost.recurringNextAttackNight());

        // Migration runs once: the re-saved tag carries schema 2.
        RaidLifecycle again = RaidLifecycle.readNbt(migratedHeld.writeNbt(),
            RaidCadence.DEFAULT_WINDOW);
        assertEquals(43L, again.recurringNextAttackNight());

        // No cadence yet stays "next eligible dusk".
        CompoundTag none = legacyTag(41L, false);
        none.remove("RecurringCadenceNextWarningNight");
        RaidLifecycle fresh = RaidLifecycle.readNbt(none, RaidCadence.DEFAULT_WINDOW);
        assertEquals(RaidLifecycle.UNSET_NIGHT, fresh.recurringNextWarningNight());
        assertTrue(fresh.recurringCadenceDue(0L));
    }

    @Test
    void legacyCommittedWarningIsLeftUntouched() {
        RaidLifecycle lifecycle = completedLifecycle();
        lifecycle.recordRecurringCadence(10_000L, 40L * DAY + 18_000L,
            RaidLifecycle.CadenceOutcome.HELD, 3);
        RaidPlan warned = new RaidPlan(UUID.randomUUID(), RaidObjective.KORN, 0.0F, 42L);
        assertTrue(lifecycle.commitRecurringWarning(warned, 20_000L, 41L));
        CompoundTag tag = lifecycle.writeNbt();
        tag.remove("RecurringCadenceSchema");
        tag.remove("RecurringCadenceLastRaidNight");
        tag.remove("RecurringCadenceIntervalDays");
        tag.putLong("RecurringCadenceNextWarningNight", 41L);
        RaidLifecycle migrated = RaidLifecycle.readNbt(tag, RaidCadence.DEFAULT_WINDOW);
        assertEquals(warned, migrated.recurringWarnedPlan().orElseThrow(),
            "a raid players were already warned about is never moved");
        assertEquals(41L, migrated.recurringNextWarningNight());
        assertFalse(migrated.recurringScheduleBlocked());
    }

    @Test
    void holdNoticeThrottle() {
        RaidHoldNotice.Reason warning = RaidHoldNotice.Reason.FIRST_WARNING_READINESS;
        assertTrue(RaidHoldNotice.shouldNotify(null, warning, 12L, 7, 0L));
        RaidHoldNotice.Last last = new RaidHoldNotice.Last(warning, 12L, 7, 1_000L);
        assertFalse(RaidHoldNotice.shouldNotify(last, warning, 12L, 7, 900_000L),
            "the same blockers on the same night are said once");
        assertFalse(RaidHoldNotice.shouldNotify(last, warning, 12L, 8, 1_100L),
            "a changed list waits for the renotify gap");
        assertTrue(RaidHoldNotice.shouldNotify(last, warning, 12L, 8,
            1_000L + RaidHoldNotice.RENOTIFY_TICKS));
        assertTrue(RaidHoldNotice.shouldNotify(last, warning, 13L, 7, 1_001L),
            "every held night is told again");
        assertTrue(RaidHoldNotice.shouldNotify(last,
            RaidHoldNotice.Reason.FIRST_ATTACK_READINESS, 12L, 7, 1_001L));

        RaidHoldNotice.Checked checked = new RaidHoldNotice.Checked(12L, 500L);
        assertFalse(RaidHoldNotice.mayCheck(checked, 12L, 501L));
        assertTrue(RaidHoldNotice.mayCheck(checked, 12L, 500L + RaidHoldNotice.RECHECK_TICKS));
        assertTrue(RaidHoldNotice.mayCheck(checked, 13L, 501L));
        assertEquals(RaidHoldNotice.signatureOf(List.of(
                FirstRaidReadinessService.Blocker.GUARD_UNARMED)),
            RaidHoldNotice.signatureOf(List.of(
                FirstRaidReadinessService.Blocker.GUARD_UNARMED)));
    }

    private static CompoundTag legacyTag(long legacyWarningNight, boolean breather) {
        CompoundTag tag = completedLifecycle().writeNbt();
        tag.remove("RecurringCadenceSchema");
        tag.remove("RecurringCadenceLastRaidNight");
        tag.remove("RecurringCadenceIntervalDays");
        tag.putLong("RecurringCadenceNextWarningNight", legacyWarningNight);
        tag.putBoolean("RaidBreather", breather);
        return tag;
    }

    private static RaidLifecycle completedLifecycle() {
        RaidLifecycle lifecycle = new RaidLifecycle();
        RaidPlan plan = new RaidPlan(UUID.randomUUID(), RaidObjective.KORN, 0.0F, 4L);
        UUID participant = UUID.randomUUID();
        assertTrue(lifecycle.initializeAtFounding(0L, 4, 2)
            && lifecycle.queueFirstPlan(plan)
            && lifecycle.beginFirstRaid(plan)
            && lifecycle.recordParticipant(participant)
            && lifecycle.sealParticipants()
            && lifecycle.recordTerminalParticipant(participant)
            && lifecycle.completeFirstRaid(false));
        return lifecycle;
    }
}
