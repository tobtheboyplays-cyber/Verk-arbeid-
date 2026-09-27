package com.hearthstead.entity.ai;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.path.RoadNavigation;
import com.hearthstead.settlement.BuildingManager;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.Schedule;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Night rest: settlers with a claimed bed walk home and genuinely sleep in
 * it; the homeless doze rough by the hearth at a morale cost. Anyone can
 * also collapse into rest when exhausted. Guards skip the night curfew.
 */
public class RestAtNightGoal extends Goal {
    // A resident may need to cross the settlement, doors and stairs to reach
    // an upstairs bed. This is a bounded home-only search; it does not alter
    // the settler's FOLLOW_RANGE or ordinary work/combat navigation.
    private static final int BED_ROUTE_FAILURE_LIMIT = 2;
    /** Beyond this (48 blocks) walk home first; plan the indoor route near the house. */
    private static final double HOME_APPROACH_RANGE_SQR = 48.0D * 48.0D;
    private static final int BED_RETRY_TICKS = 200;
    /** Longest wait between two retries of a bed that keeps failing. */
    private static final int BED_RETRY_MAX_TICKS = 2_400;
    /**
     * A walker that can get no closer to the Hearth (a crowd of rough
     * sleepers around it) settles within the same 7-block ring it is later
     * allowed to stay in, instead of repathing into the crowd all night.
     */
    private static final int ROUGH_REST_CROWD_SQR = 49;
    private final SettlerEntity settler;
    private boolean resting;
    private boolean hearthFallback;
    private int bedRouteFailures;
    private int bedRetryTimer;
    /** Current retry interval; doubles after each failed bed retry. */
    private int bedRetryInterval = BED_RETRY_TICKS;
    /** True while a timed bed retry is in flight. */
    private boolean bedRetryPending;
    private boolean roughNightCharged;
    private int repathTimer;
    private int claimRetryTimer;
    private Vec3 lastHomeRoutePosition;
    private BlockPos lastHomeRouteTarget;

    /** Below this energy a fighter keeps resting through an alarm (canUse's own exhaustion line). */
    private static final float CRITICAL_REST_ENERGY = 12.0F;

    public RestAtNightGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    /**
     * The village's own bedtime, not the sky's.
     *
     * <p>Civilians sleep through the rest phase. Guards sleep on the opposite
     * half of the clock from their watch — see {@link Schedule#shouldSleep} —
     * so a raid at two in the morning meets guards who are already awake
     * rather than a settlement of sleepers.
     */
    private boolean nightCurfew() {
        Settlement settlement = settler.settlement();
        if (settlement == null) {
            return !settler.getProfession().martial()
                && settler.dayPhase().rest();
        }
        return Schedule.shouldSleep(settlement, settler, settler.dayPhase());
    }

    @Override
    public boolean canUse() {
        return settler.isBound() && settler.getHearthPos() != null
            // Adopt vanilla's restored sleep so the existing continuation/stop
            // lifecycle can wake a rested sleeper whose clock changed on load.
            && (settler.isSleeping() || nightCurfew() || settler.getEnergy() < 12)
            // A Healer called to a raid, or a fighter with a live enemy or an
            // alarm, never lies down; one already asleep is adopted here only
            // so canContinueToUse/stop wake it (J-04, W3b archers).
            && (settler.isSleeping() || !calledToFight());
    }

    /**
     * J-04: the Healer is not on the Guards' watch rota, so it used to sleep
     * through a night raid. During an active raid or alarm it stays awake and
     * takes up its combat role (HealerMedicGoal, the alarm response).
     */
    private boolean calledToFight() {
        return healerCalledToFight() || fighterCalledToFight();
    }

    /**
     * W3b: an off-watch Guard or Archer (and a battle role while roles are on)
     * slept through a live enemy, and an unarmed one never fetched its weapon
     * from the rack, because this goal holds the same priority as the fetch.
     * A live hostile target always wakes it. An alarm or raid wakes it too,
     * unless it is exhausted (energy below the critical-rest line): then the
     * rest that keeps it standing tomorrow wins, and the watch on duty fights.
     */
    private boolean fighterCalledToFight() {
        Profession profession = settler.getProfession();
        if (profession == Profession.HEALER || !GuardRespondToAlertGoal.answersAlarms(profession)) {
            return false;
        }
        net.minecraft.world.entity.LivingEntity target = settler.getTarget();
        if (target != null && target.isAlive()) {
            return true;
        }
        if (settler.getEnergy() < CRITICAL_REST_ENERGY) {
            return false;
        }
        Settlement settlement = settler.settlement();
        return settlement != null
            && (com.hearthstead.settlement.BlessingEffects.raidActive(settlement)
                || settlement.alertActive(settler.level().getGameTime()));
    }

    private boolean healerCalledToFight() {
        if (settler.getProfession() != Profession.HEALER
            || !com.hearthstead.entity.combat.role.RoleCombat.enabled()) {
            return false;
        }
        Settlement settlement = settler.settlement();
        return settlement != null
            && (com.hearthstead.settlement.BlessingEffects.raidActive(settlement)
                || settlement.alertActive(settler.level().getGameTime()));
    }

    @Override
    public void start() {
        resting = false;
        hearthFallback = false;
        bedRouteFailures = 0;
        bedRetryTimer = 0;
        bedRetryInterval = BED_RETRY_TICKS;
        bedRetryPending = false;
        repathTimer = 60;
        roughNightCharged = false;
        lastHomeRoutePosition = null;
        lastHomeRouteTarget = null;
        claimBedIfNeeded();
        path();
    }

    private void claimBedIfNeeded() {
        if (settler.getClaimedBed() != null
            || !(settler.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        Settlement settlement = settler.settlement();
        if (settlement != null) {
            BlockPos free = BuildingManager.findFreeBed(serverLevel, settlement);
            if (free != null) {
                settler.claimBed(free);
            }
        }
    }

    private BlockPos restTarget() {
        BlockPos bed = settler.getClaimedBed();
        return bed != null && !hearthFallback ? bed : settler.getHearthPos();
    }

    private boolean canRestAtRealHearth(ServerLevel level) {
        BlockPos hearth = settler.getHearthPos();
        return hearth != null && level.hasChunkAt(hearth)
            && level.getBlockEntity(hearth) instanceof HearthBlockEntity;
    }

    /** A valid bed claim remains owned; only the unreachable route yields. */
    private boolean failedBedRoute(ServerLevel level) {
        if (++bedRouteFailures < BED_ROUTE_FAILURE_LIMIT
            || !canRestAtRealHearth(level)) return false;
        // A bed that failed again after a retry waits longer before the next
        // one (soak 2026-09-25): each retry is a round trip that recovers no
        // energy, and a short fixed interval kept exhausted settlers walking
        // between bed and Hearth for the whole workday.
        if (bedRetryPending) {
            bedRetryInterval = Math.min(BED_RETRY_MAX_TICKS, bedRetryInterval * 2);
        }
        bedRetryPending = false;
        hearthFallback = true;
        bedRetryTimer = bedRetryInterval;
        settler.recordRouteFailure("rest:bed_unreachable_hearth_fallback");
        settler.getNavigation().stop();
        path();
        return true;
    }

    private void path() {
        BlockPos target = restTarget();
        if (target == null) return;
        // Rough rest retains its old, local Hearth walk. A claimed bed instead
        // needs a complete route to a grounded, ray-clear sleeping contact
        // cell. Blind moveTo coordinates may accept a partial route at a door
        // or stair landing and strand the resident short of the real bed.
        if (settler.getClaimedBed() == null || hearthFallback) {
            settler.getNavigation().moveTo(target.getX() + 0.5, target.getY() + 1,
                target.getZ() + 0.5, 0.9);
            return;
        }
        if (!(settler.level() instanceof ServerLevel level)) return;
        Set<BlockPos> contacts = bedContactTargets(level, target);
        Path installed = settler.getNavigation().getPath();
        boolean countedStall = false;
        if (installed != null && !installed.isDone() && installed.canReach()
            && contacts.contains(installed.getTarget())) {
            if (routeIsProgressing(installed.getTarget())) return;
            if (failedBedRoute(level)) return;
            countedStall = true;
        } else if (lastHomeRouteTarget != null
            && lastHomeRoutePosition != null
            && settler.position().distanceToSqr(lastHomeRoutePosition) < 0.04D) {
            // A path can be accepted and then finish without moving its owner.
            // Count that as a failed physical route, not a successful plan.
            if (failedBedRoute(level)) return;
            countedStall = true;
        }
        if (lastHomeRoutePosition != null
            && settler.position().distanceToSqr(lastHomeRoutePosition) >= 0.04D) {
            bedRouteFailures = 0;
        }
        HomeNavigation home = new HomeNavigation(settler, level);
        Path route = contacts.isEmpty() ? null : home.createHomePath(contacts);
        if (route == null && home.deferredLastSearch()) {
            // Many residents plan their way home on the same tick; the
            // navigator spreads those expensive searches over ticks. Being
            // deferred is not a failed route: try again in a few ticks.
            repathTimer = 1 + settler.getRandom().nextInt(10);
            return;
        }
        if ((route == null || !route.canReach()) && !contacts.isEmpty()
            && settler.blockPosition().distSqr(target) > HOME_APPROACH_RANGE_SQR) {
            // Far from home (a field, the far side of a large settlement):
            // the exact indoor route is only planned near the house. Walk
            // towards it on the ordinary navigator first. A motionless sample
            // still counts as a failed route through lastHomeRoutePosition.
            if (settler.getNavigation().moveTo(target.getX() + 0.5D, target.getY(),
                    target.getZ() + 0.5D, 0.9D)) {
                lastHomeRoutePosition = settler.position();
                lastHomeRouteTarget = target.immutable();
                return;
            }
        }
        if (route == null || !route.canReach() || !contacts.contains(route.getTarget())) {
            // This goal owns MOVE. Do not retain a stale partial path, but do
            // retain the valid bed claim; the existing timed repath can recover
            // after a door or terrain change.
            settler.getNavigation().stop();
            lastHomeRoutePosition = null;
            lastHomeRouteTarget = null;
            if (!countedStall) failedBedRoute(level);
            return;
        }
        if (!settler.getNavigation().moveTo(route, 0.9D)) {
            if (!countedStall) failedBedRoute(level);
            return;
        }
        lastHomeRoutePosition = settler.position();
        lastHomeRouteTarget = route.getTarget().immutable();
    }

    /** Reuse a live full route; a motionless 60-tick sample is replanned. */
    private boolean routeIsProgressing(BlockPos target) {
        Vec3 here = settler.position();
        boolean changedTarget = lastHomeRouteTarget == null || !lastHomeRouteTarget.equals(target);
        boolean moved = lastHomeRoutePosition == null
            || here.distanceToSqr(lastHomeRoutePosition) >= 0.0001D;
        if (lastHomeRoutePosition != null
            && here.distanceToSqr(lastHomeRoutePosition) >= 0.04D) {
            bedRouteFailures = 0;
        }
        lastHomeRoutePosition = here;
        lastHomeRouteTarget = target.immutable();
        return changedTarget || moved;
    }

    private Set<BlockPos> bedContactTargets(ServerLevel level, BlockPos bed) {
        return com.hearthstead.entity.path.BedApproach.contactCells(level, settler, bed);
    }

    private boolean canContactBed(ServerLevel level, BlockPos bed) {
        return com.hearthstead.entity.path.BedApproach.canContact(level, settler, bed);
    }

    /** Isolated bounded search with the same door/stair rules as the settler. */
    private static final class HomeNavigation extends RoadNavigation {
        private HomeNavigation(SettlerEntity settler, ServerLevel level) {
            super(settler, level);
        }

        private Path createHomePath(Set<BlockPos> contacts) {
            return createBuildingPath(contacts, 0);
        }
    }

    @Override
    public boolean canContinueToUse() {
        if (!settler.isBound() || settler.getHearthPos() == null || calledToFight()) {
            return false;
        }
        if (nightCurfew()) {
            return true;
        }
        return settler.getEnergy() < 60;
    }

    @Override
    public void tick() {
        BlockPos target = restTarget();
        if (target == null) {
            return;
        }
        if (settler.isSleeping()) {
            // Vanilla restores the sleeping position on load; our activity is
            // transient and starts IDLE. Reconcile it before the needs tick so
            // a real reloaded sleeper recovers energy instead of losing it.
            settler.getNavigation().stop();
            settler.setActivity(SettlerActivity.SLEEPING);
            return; // Vanilla remains the owner of sleep pose and bed position.
        }
        // Only an arrived (or stranded) walker spends its retry budget. While
        // the route to the Hearth is still progressing, counting down turned a
        // long walk into a bed/Hearth oscillation that never rested at all.
        if (hearthFallback && !resting && settler.getNavigation().isDone()
            && --bedRetryTimer <= 0) {
            hearthFallback = false;
            bedRouteFailures = BED_ROUTE_FAILURE_LIMIT - 1;
            bedRetryPending = true;
            path();
            repathTimer = 60;
            return;
        }
        if (settler.getClaimedBed() == null && --claimRetryTimer <= 0) {
            // Homes can register mid-night; the homeless keep checking.
            claimRetryTimer = 40;
            claimBedIfNeeded();
            if (settler.getClaimedBed() != null) {
                resting = false;
                settler.setActivity(SettlerActivity.IDLE);
                path();
                return;
            }
        }
        if (resting && settler.getClaimedBed() != null && !hearthFallback) {
            // Housing assignment is also push-driven: a Plaque survey may
            // hand this settler a bed between two retry ticks.  Previously
            // that bypassed the branch above (the bed was no longer null),
            // leaving the goal latched in rough rest until dawn even though
            // a real bed was waiting.  Treat either assignment path as the
            // same state transition and walk to the newly claimed bed now.
            resting = false;
            settler.setActivity(SettlerActivity.IDLE);
            path();
            return;
        }
        if (resting) {
            if (hearthFallback && settler.level() instanceof ServerLevel level) {
                if (!canRestAtRealHearth(level)) {
                    resting = false;
                    hearthFallback = false;
                    settler.setActivity(SettlerActivity.IDLE);
                    path();
                    return;
                }
                if (--bedRetryTimer <= 0) {
                    resting = false;
                    hearthFallback = false;
                    // One fresh full-route attempt; return to real Hearth
                    // rest immediately if the claimed bed remains blocked.
                    bedRouteFailures = BED_ROUTE_FAILURE_LIMIT - 1;
                    bedRetryPending = true;
                    settler.setActivity(SettlerActivity.IDLE);
                    path();
                    repathTimer = 60;
                    return;
                }
            }
            // Rough rest at the hearth: stay put, gaze into the fire.
            settler.getNavigation().stop();
            settler.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5,
                target.getZ() + 0.5);
            if (settler.blockPosition().distSqr(target) > 49) {
                resting = false; // shoved away; wander back
                path();
                settler.setActivity(SettlerActivity.IDLE);
            }
            return;
        }

        BlockPos bed = settler.getClaimedBed();
        if (bed != null && !hearthFallback) {
            // The claim can rot: bed broken or its home invalidated.
            if (!(settler.level().getBlockState(bed).getBlock() instanceof BedBlock)) {
                settler.releaseBed();
                claimBedIfNeeded();
                path();
                return;
            }
            if (settler.level() instanceof ServerLevel level && canContactBed(level, bed)) {
                settler.getNavigation().stop();
                settler.startSleeping(bed);
                settler.setActivity(SettlerActivity.SLEEPING);
                return;
            }
        } else if (settler.blockPosition().distSqr(target)
                <= (settler.getNavigation().isDone() ? ROUGH_REST_CROWD_SQR : 20)
            && (!hearthFallback || settler.level() instanceof ServerLevel level
                && canRestAtRealHearth(level))) {
            resting = true;
            settler.getNavigation().stop();
            settler.setActivity(SettlerActivity.RESTING);
            if (nightCurfew() && !roughNightCharged) {
                roughNightCharged = true;
                settler.addMorale(-3.0F); // slept rough on cold ground
            }
            return;
        }
        if (--repathTimer <= 0) {
            repathTimer = 60;
            path();
        }
    }

    @Override
    public void stop() {
        if (settler.isSleeping()) {
            settler.stopSleeping();
            // WAKE_STRETCH: only for a genuine bed-wake, not every goal stop
            // (e.g. being attacked mid-rest at the hearth).
            settler.triggerWakeStretch();
        }
        resting = false;
        hearthFallback = false;
        bedRouteFailures = 0;
        bedRetryTimer = 0;
        lastHomeRoutePosition = null;
        lastHomeRouteTarget = null;
        settler.setActivity(SettlerActivity.IDLE);
    }
}
