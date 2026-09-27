package com.hearthstead.settlement.raid;

import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.state.FirstRaidState;
import com.hearthstead.settlement.state.RaidLifecycle;
import com.hearthstead.settlement.state.RecurringRaidRun;
import net.minecraft.network.chat.Component;

/**
 * The threat line of {@code /hearthstead info}, read from the raid schedule
 * that actually decides raids.
 *
 * <p>The old line printed {@link RaidPressure#chanceTonight()} and
 * {@link RaidPressure#nightsSinceRaid()}. Since the 25 Sep cadence decision
 * that nightly chance is bookkeeping only, and before the first raid the
 * counter is its "never raided" default of 99. A fresh village therefore
 * read "5% tonight, 99 nights since a raid" although no raid can come before
 * the player chooses Declare Ready. Pure: no world access, so every branch is
 * unit-testable.
 */
public final class RaidThreatInfo {

    private RaidThreatInfo() {
    }

    public static Component line(Settlement settlement, long dayTime) {
        RaidLifecycle lifecycle = settlement.raidLifecycle;
        RecurringRaidRun recurring = settlement.recurringRaidRun;
        long tonight = RaidDirector.nightOf(Math.max(0L, dayTime));
        FirstRaidState first = lifecycle.firstState();

        if (first == FirstRaidState.ACTIVE
            || recurring.isActive() || recurring.isLegacyBridgeActive()) {
            return Component.translatable("hearthstead.command.info_threat_active");
        }
        if (first == FirstRaidState.PREPARING
            || first == FirstRaidState.UNINITIALIZED && !lifecycle.integrityLost()) {
            return Component.translatable("hearthstead.command.info_threat_preparing");
        }
        if (first == FirstRaidState.SCHEDULED) {
            if (lifecycle.integrityLost() || lifecycle.firstAttackNight() < 0L) {
                return Component.translatable("hearthstead.command.info_threat_paused");
            }
            if (tonight > lifecycle.firstAttackNight()) {
                // The warned night passed without an arrival: the plan is held
                // until readiness returns (RaidHoldNotice explains why).
                return Component.translatable("hearthstead.command.info_threat_first_held");
            }
            return Component.translatable("hearthstead.command.info_threat_first_scheduled",
                when(lifecycle.firstAttackNight(), tonight));
        }
        if (first != FirstRaidState.COMPLETED || recurring.isBlocked()
            || lifecycle.recurringScheduleBlocked()) {
            return Component.translatable("hearthstead.command.info_threat_paused");
        }
        if (recurring.isQueued()) {
            return Component.translatable("hearthstead.command.info_threat_due");
        }
        long next = lifecycle.recurringWarnedPlan().map(RaidPlan::night)
            .orElse(lifecycle.recurringNextAttackNight());
        Component nextWhen = next < 0L
            ? Component.translatable("hearthstead.command.when.soon")
            : when(next, tonight);
        return Component.translatable("hearthstead.command.info_threat_recurring",
            Component.translatable("hearthstead.raid.stage."
                + settlement.raidPressure.stage().id()),
            settlement.raidPressure.pressure(), lifecycle.raidsSurvived(), nextWhen);
    }

    /** "tonight", "tomorrow night" or "in N nights"; a passed night reads as soon. */
    static Component when(long night, long tonight) {
        long delta = night - tonight;
        if (delta <= 0L) {
            return Component.translatable("hearthstead.command.when.tonight");
        }
        if (delta == 1L) {
            return Component.translatable("hearthstead.command.when.tomorrow");
        }
        return Component.translatable("hearthstead.command.when.nights", delta);
    }
}
