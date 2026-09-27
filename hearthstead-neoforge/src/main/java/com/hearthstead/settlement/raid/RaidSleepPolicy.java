package com.hearthstead.settlement.raid;

import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.state.FirstRaidState;
import net.minecraft.server.level.ServerLevel;

import java.util.Collection;

/** Dimension-wide, allocation-light authority for raid sleep denial. */
public final class RaidSleepPolicy {
    public static boolean blocksSleep(ServerLevel level) {
        if (level == null) {
            return false;
        }
        return blocksSleep(SettlementSavedData.get(level).settlements.values(),
            RaidDirector.nightOf(level.getDayTime()));
    }

    /**
     * Radius beyond a settlement's own boundary within which its raid denies
     * sleep: the same player-presence distance at which a recurring raid
     * starts and its lines are heard (see
     * {@link RaidDirector#PLAYER_PRESENCE_MARGIN}). It was 64 until 26 Sep,
     * which kept a player 33-64 blocks out awake for a raid that waited for
     * someone within 32 to start.
     */
    public static final int SLEEP_DENIAL_MARGIN = RaidDirector.PLAYER_PRESENCE_MARGIN;

    /**
     * Per-player denial: only a raid at a settlement near this sleeper keeps
     * them awake. A dimension-wide rule let one group's raid (or a stalled
     * schedule) stop every co-op player in the Overworld from sleeping.
     */
    public static boolean blocksSleep(ServerLevel level, net.minecraft.world.entity.player.Player sleeper) {
        if (level == null || sleeper == null) {
            return false;
        }
        return blocksSleep(SettlementSavedData.get(level).settlements.values(),
            RaidDirector.nightOf(level.getDayTime()), sleeper.blockPosition());
    }

    /** Pure policy seam used by deterministic boundary tests. */
    public static boolean blocksSleep(Collection<Settlement> settlements,
                                      long currentNight) {
        return blocksSleep(settlements, currentNight, null);
    }

    public static boolean blocksSleep(Collection<Settlement> settlements,
                                      long currentNight,
                                      net.minecraft.core.BlockPos near) {
        if (settlements == null || currentNight < 0L) {
            return false;
        }
        for (Settlement settlement : settlements) {
            if (settlement == null || settlement.raidLifecycle == null
                || settlement.recurringRaidRun == null) {
                continue;
            }
            if (near != null && settlement.center != null) {
                long reach = (long) settlement.radius + SLEEP_DENIAL_MARGIN;
                long dx = near.getX() - settlement.center.getX();
                long dz = near.getZ() - settlement.center.getZ();
                if (dx * dx + dz * dz > reach * reach) {
                    continue;
                }
            }
            FirstRaidState first = settlement.raidLifecycle.firstState();
            if (first == FirstRaidState.ACTIVE) {
                // Integrity loss removes reward/presentation authority, not
                // the known fact that a live raid must not be slept away.
                return true;
            }
            if (first == FirstRaidState.SCHEDULED
                // A schedule that lost integrity can never start by itself;
                // it must not hold every night hostage.
                && !settlement.raidLifecycle.integrityLost()
                && settlement.raidLifecycle.firstAttackNight() >= 0L
                && currentNight >= settlement.raidLifecycle.firstAttackNight()) {
                return true;
            }
            if (settlement.recurringRaidRun.isQueued()
                || settlement.recurringRaidRun.isActive()
                || settlement.recurringRaidRun.isLegacyBridgeActive()) {
                return true;
            }
            // Cadence: the warned attack night itself cannot be slept away
            // before the band arrives (mirrors the scheduled first raid).
            if (first == FirstRaidState.COMPLETED
                && settlement.recurringRaidRun.isEmpty()
                && !settlement.raidLifecycle.recurringScheduleBlocked()
                && settlement.raidLifecycle.recurringWarnedPlan()
                    .filter(plan -> currentNight >= plan.night()).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private RaidSleepPolicy() {
    }
}
