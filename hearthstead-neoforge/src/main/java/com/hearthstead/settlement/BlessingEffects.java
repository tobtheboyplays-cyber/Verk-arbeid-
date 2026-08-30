package com.hearthstead.settlement;

import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.raid.RaidPlan;
import com.hearthstead.settlement.state.BlessingId;
import com.hearthstead.settlement.state.FirstRaidState;
import com.hearthstead.settlement.state.TargetBlessingState;
import net.minecraft.util.Mth;

/**
 * Pure calculations and raid authority for target-bound Blessings.
 *
 * <p>There is deliberately no settlement-ledger rank fallback here. Permanent
 * effect strength comes only from the exact settler target or the strongest
 * valid building zone covering that target. Building and personal ranks use
 * max rather than addition, keeping the rank-III cap meaningful. All reads are
 * constant-time or use Settlement's bounded per-chunk index.
 */
public final class BlessingEffects {
    public static final float WARDEN_DAMAGE_PER_RANK = 0.10F;
    public static final float HEARTHWARD_REDUCTION_PER_RANK = 0.10F;
    public static final double THORNED_ROADS_SLOW_PER_RANK = 0.08D;

    /**
     * True only while one authoritative plan owns the compatibility mirror.
     * This deliberately says nothing about a particular entity; effect hooks
     * must use {@link #isAuthorizedRaidParticipant(Settlement, RaiderEntity)}.
     */
    public static boolean raidActive(Settlement settlement) {
        if (settlement == null || settlement.pendingRaid == null) {
            return false;
        }
        if (settlement.raidLifecycle.isAuthoredFirstRaidActive()
            || settlement.raidLifecycle.isLegacyBridgeActive()) {
            return settlement.raidLifecycle.activePlanMatches(
                settlement.pendingRaid);
        }
        return settlement.raidLifecycle.firstState() == FirstRaidState.COMPLETED
            && (settlement.recurringRaidRun.isActive()
                || settlement.recurringRaidRun.isLegacyBridgeActive())
            && settlement.recurringRaidRun.planMatches(settlement.pendingRaid);
    }

    /**
     * The single participant-authority predicate for every Blessing effect.
     *
     * <p>Modern first and recurring raids authorize only an entity UUID from
     * their sealed capture. Merely carrying the settlement id is insufficient:
     * a hand-spawned or stale raider must never inherit a permanent Blessing.
     * The two migration-only legacy bridges predate UUID capture, so they use
     * the narrowest auditable entity evidence that survives those saves: the
     * exact authoritative/mirrored plan plus matching settlement, captain and
     * objective. Telegraph scouts are excluded before either path.
     */
    public static boolean isAuthorizedRaidParticipant(Settlement settlement,
                                                        RaiderEntity raider) {
        if (settlement == null || raider == null || raider.isScout()
            || !settlement.id.equals(raider.settlementId())
            || !raidActive(settlement)) {
            return false;
        }

        if (settlement.raidLifecycle.isAuthoredFirstRaidActive()) {
            return settlement.raidLifecycle.participantsTracked()
                && settlement.raidLifecycle.isParticipant(raider.getUUID());
        }
        if (settlement.raidLifecycle.isLegacyBridgeActive()) {
            // raidActive already proved the lifecycle plan equals this mirror.
            return legacyAssignmentMatches(settlement.pendingRaid, raider);
        }
        if (settlement.recurringRaidRun.isActive()) {
            return settlement.recurringRaidRun.participantsSealed()
                && settlement.recurringRaidRun.isParticipant(raider.getUUID());
        }
        if (settlement.recurringRaidRun.isLegacyBridgeActive()) {
            // raidActive already proved the recurring plan equals this mirror.
            return legacyAssignmentMatches(settlement.pendingRaid, raider);
        }
        return false;
    }

    /** Personal + building zone, max-not-stack and fail-closed by membership. */
    public static int effectiveSettlerRank(Settlement settlement,
                                           SettlerEntity settler,
                                           BlessingId blessing) {
        if (settlement == null || settler == null || blessing == null
            || settler.getSettlementId() == null
            || !settlement.id.equals(settler.getSettlementId())) {
            return 0;
        }
        int personal = boundedRank(settler.blessingRank(blessing));
        if (personal == TargetBlessingState.MAX_RANK) {
            return personal;
        }
        int building = settlement.strongestBuildingBlessingAt(
            Mth.floor(settler.getX()), Mth.floor(settler.getY()),
            Mth.floor(settler.getZ()), blessing);
        return Math.max(personal, building);
    }

    /** Strongest valid building rank at a raider's current block. */
    public static int buildingRankAt(Settlement settlement, RaiderEntity raider,
                                     BlessingId blessing) {
        if (settlement == null || raider == null || blessing == null
            || raider.settlementId() == null
            || !settlement.id.equals(raider.settlementId())) {
            return 0;
        }
        return settlement.strongestBuildingBlessingAt(
            Mth.floor(raider.getX()), Mth.floor(raider.getY()),
            Mth.floor(raider.getZ()), blessing);
    }

    public static float wardenDamageMultiplier(int rank) {
        return 1.0F + boundedRank(rank)
            * WARDEN_DAMAGE_PER_RANK;
    }

    public static float hearthwardDamageMultiplier(int rank) {
        return 1.0F - boundedRank(rank)
            * HEARTHWARD_REDUCTION_PER_RANK;
    }

    /** Negative ADD_MULTIPLIED_TOTAL amount for a transient speed modifier. */
    public static double thornedRoadsModifier(int rank) {
        return -boundedRank(rank)
            * THORNED_ROADS_SLOW_PER_RANK;
    }

    private static int boundedRank(int rank) {
        return Mth.clamp(rank, 0, TargetBlessingState.MAX_RANK);
    }

    private static boolean legacyAssignmentMatches(RaidPlan plan,
                                                   RaiderEntity raider) {
        return RaidPlan.isValid(plan)
            && plan.captainId().equals(raider.captainId())
            && plan.objective() == raider.objective();
    }

    private BlessingEffects() {
    }
}
