package com.hearthstead.entity.ai;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.guard.GuardAssignmentService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Map;
import java.util.WeakHashMap;

/** A civilian reports one observed hostile at an own Guard, then shelters indoors. */
public class SettlerPanicGoal extends Goal {
    public static final int PANIC_YELP_TICK = 3;
    public static final int PANIC_VOCAL_THROTTLE = 40;
    public static final int SHELTER_TICKS = 600;
    private static final int ROUTE_TIMEOUT_TICKS = 240;
    /** A single navigation query is not enough to classify an otherwise
     * serviceable Guard as unreachable. A short retry keeps the report route
     * distinct from the bounded home fallback for a truly unreachable Guard. */
    private static final int GUARD_ROUTE_RETRY_TICKS = 60;
    /** A fresh civilian can briefly have no usable navigation path while its
     * footing settles. Do not turn that one probe into a permanent loss of the
     * already-completed Guard-to-home handoff. */
    private static final int HOME_TARGET_RETRY_TICKS = 60;
    private static final int HOME_RETRY_TICKS = 100;
    private static final double ALERT_CONTACT_SQR = 9.0D;
    private static final double ARRIVED_HOME_SQR = 0.64D;
    private static final Map<Settlement, Long> NEXT_CRY = new WeakHashMap<>();
    /**
     * BH-31 (captain JFR, raid melee P99 764 ms): every path query here is a
     * full A* (plus RoadNavigation's expanded detour on a miss). Candidates
     * are tried nearest-first and lazily, never all of them: at most this
     * many interior cells per building ...
     */
    static final int PATH_TRIES_PER_BUILDING = 3;
    /** ... at most this many housing buildings per home probe (the probe rotates on) ... */
    static final int BUILDINGS_PER_PROBE = 2;
    /** ... at most this many Guard-contact cells per route attempt ... */
    static final int GUARD_CONTACT_TRIES = 6;
    /** ... and a whole village's first probes spread over this many ticks (UUID hash). */
    static final int PROBE_STAGGER_TICKS = 10;
    /** Test/diagnostic: path queries this goal made (all settlers, since start). */
    private static final java.util.concurrent.atomic.AtomicLong PATH_QUERIES = new java.util.concurrent.atomic.AtomicLong();
    /**
     * BH-31 v3, threat-aware shelter (balance lane, 26 Sep). captain2 soak:
     * the innkeeper and the baker ran ~60 blocks from their workplaces to
     * their claimed homes -- straight into the band attacking from that side
     * -- and died there. A shelter is now refused when it lies closer to a
     * known threat than this margin (or than the settler itself already is,
     * whichever is smaller). Pure geometry, checked BEFORE any path query.
     */
    static final double SHELTER_THREAT_MARGIN = 12.0D;
    /** A raid route must pass at least this far (squared) from every known raider. */
    static final double RAID_ROUTE_CLEARANCE_SQR = 36.0D;
    /** How far a civilian with no safe shelter runs directly away from the band. */
    static final int FLEE_AWAY_DISTANCE = 16;
    /** A sheltered civilian leaves its shelter when a raider comes this close. */
    static final double SHELTER_BREACHED_DISTANCE = 4.0D;
    /** Retry delay after a failed panic trip while a raid is on (was 100 ticks for every trip). */
    static final int RAID_HOME_RETRY_TICKS = 20;
    /** Raid positions shared by one settlement's civilians for one tick (one box query per tick). */
    private static final Map<Settlement, RaidSnapshot> RAID_THREATS = new WeakHashMap<>();
    private record RaidSnapshot(long tick, java.util.List<Vec3> positions) { }
    /** Open-ground refuge chosen when no safe interior exists during a raid; never a shelter. */
    @Nullable private BlockPos fleeTarget;
    /** The building {@link #homeTarget} belongs to, re-checked against the moving band. */
    @Nullable private Building homeBuilding;
    private final SettlerEntity settler;
    private int panicTicks;
    private long nextScan;
    private long nextCry;
    private long tripDeadline;
    private long guardRouteRetryUntil;
    private long homeTargetRetryDeadline;
    private long nextHomeRetry;
    @Nullable private Monster seenThreat;
    @Nullable private BlockPos observedThreatPos;
    /** Nearest known hostile during a bell ALARM; only steers the shelter route away from it. */
    @Nullable private BlockPos alarmAvoidPos;
    /** This trip was started by an already-raised ALARM alone (no sighting, hurt or raid). */
    private boolean alarmTrip;
    @Nullable private BlockPos homeTarget;
    /** No home probe (the expensive part) before this tick: spreads a village-wide panic. */
    private long homeProbeAt;
    /**
     * W17a: buildings this settler already proved unreachable (every tried
     * cell refused), skipped until the tick stored here. Kept across trips:
     * restarting each trip at the nearest -- sealed -- house again stalled a
     * whole village (3/30 reached cover). Deferrals do not count as refusals
     * until a building has been deferred MAX_DEFERRALS times in a row.
     */
    private final java.util.Map<java.util.UUID, Long> unreachableUntil = new java.util.HashMap<>();
    static final int UNREACHABLE_SKIP_TICKS = 600;
    static final int DEFERRED_SKIP_TICKS = 60;
    static final int MAX_DEFERRALS = 6;
    @Nullable private java.util.UUID deferredBuilding;
    private int deferralsInRow;
    /**
     * The last path query was only DEFERRED (RoadNavigation's shared per-tick
     * budget of long searches was spent), not refused. Soak 26 Sep: 30
     * civilians panicking together starved each other of that budget, found
     * "no home" and six were cut down in the open.
     */
    private boolean pathDeferred;
    /** One home probe per tick at most (start() and routeCurrent() both ask). */
    private long lastProbeTick = Long.MIN_VALUE;
    @Nullable private SettlerEntity alertGuard;
    private Phase phase = Phase.HOME;

    private enum Phase { ALERT_GUARD, HOME, SHELTERED }

    public SettlerPanicGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override public boolean requiresUpdateEveryTick() { return true; }

    private boolean sheltering(long now) { return settler.panicShelterUntil() > now; }

    private boolean danger() {
        Settlement s = settler.settlement();
        if (s == null || !(settler.level() instanceof ServerLevel level)) return false;
        long now = level.getGameTime();
        if (now >= nextScan) {
            nextScan = now + 10;
            // Attributes lane: 12 blocks, FEARFUL 16 (flees earlier), WATCHFUL
            // ~15.6 (spots sooner); AttributeRuntime.panicScanRadius.
            double scan = com.hearthstead.entity.AttributeRuntime.panicScanRadius(settler);
            seenThreat = level.getEntitiesOfClass(Monster.class, settler.getBoundingBox().inflate(scan),
                enemy -> enemy.isAlive() && !enemy.isAlliedTo(settler)
                    && settler.distanceToSqr(enemy) <= scan * scan && settler.hasLineOfSight(enemy)
                    && (!(enemy instanceof com.hearthstead.entity.RaiderEntity raider)
                        || !raider.isGoblinThiefDemo()
                        || com.hearthstead.event.GoblinThiefDemo.canNotice(settler, raider)))
                .stream().min(Comparator.comparingDouble(settler::distanceToSqr)).orElse(null);
        }
        return seenThreat != null || settler.hurtTime > 0 || s.pendingRaid != null || s.alertActive(now);
    }

    @Override public boolean canUse() {
        if (settler.getProfession().battlefield() || !settler.isBound()
                || !(settler.level() instanceof ServerLevel level)) return false;
        long now = level.getGameTime();
        return sheltering(now) || now >= nextHomeRetry && danger();
    }

    @Override public void start() {
        if (settler.isSleeping()) settler.stopSleeping();
        if (settler.isPassenger()) settler.stopRiding();
        settler.setTarget(null);
        panicTicks = 0;
        long now = settler.level().getGameTime();
        alarmAvoidPos = null;
        alarmTrip = false;
        fleeTarget = null;
        Settlement alarmSettlement = settler.settlement();
        if (seenThreat == null && alarmSettlement != null && alarmSettlement.alertActive(now)
                && settler.level() instanceof ServerLevel alarmLevel) {
            alarmAvoidPos = com.hearthstead.settlement.defense.AlarmBell.nearestKnownThreat(
                alarmLevel, alarmSettlement, settler.blockPosition());
        }
        tripDeadline = now + ROUTE_TIMEOUT_TICKS;
        guardRouteRetryUntil = 0L;
        homeProbeAt = now + Math.floorMod(settler.getUUID().hashCode(), PROBE_STAGGER_TICKS);
        homeTarget = findHomeInterior();
        homeTargetRetryDeadline = homeProbeAt + HOME_TARGET_RETRY_TICKS;
        if (sheltering(now)) {
            // A loaded civilian cannot inherit a shelter timer while standing in a field.
            phase = arrivedHome() ? Phase.SHELTERED : Phase.HOME;
        } else {
            observedThreatPos = seenThreat != null && seenThreat.isAlive()
                ? seenThreat.blockPosition().immutable() : null;
            // Attributes lane: a WATCHFUL settler raises the alarm on a real
            // sighting at once, not only after reaching a Guard. Only when no
            // alert runs yet, so a retry never re-announces or moves it.
            if (observedThreatPos != null && alarmSettlement != null
                    && !alarmSettlement.alertActive(now)
                    && com.hearthstead.entity.AttributeRuntime.criesAlarm(settler)
                    && settler.level() instanceof ServerLevel watchLevel) {
                SettlementManager.raiseAlert(watchLevel, alarmSettlement, observedThreatPos);
            }
            // A Guard's serviceability is an authority decision. Its route is
            // retried below so one path miss cannot skip the report.
            // An already-raised ALARM (e.g. the bell) needs no report: shelter directly.
            alarmTrip = observedThreatPos == null && alarmOnly(now);
            alertGuard = alarmTrip ? null : nearestOwnGuard();
            phase = alertGuard == null ? Phase.HOME : Phase.ALERT_GUARD;
        }
        routeCurrent();
        settler.setActivity(SettlerActivity.FLEEING);
    }

    @Override public boolean canContinueToUse() {
        if (settler.getProfession().battlefield() || !settler.isBound()
                || !(settler.level() instanceof ServerLevel level)) return false;
        long now = level.getGameTime();
        if (phase == Phase.SHELTERED) return sheltering(now) || danger();
        // Once a real sighting started a trip, losing sight does not abandon the
        // Guard report or indoor home route. A bounded timeout prevents a bad
        // route/home from monopolising MOVE forever.
        // Keep MOVE for the short, bounded home-target retry after a report.
        // A real missing home still calls failHome at that retry deadline.
        return (phase == Phase.ALERT_GUARD || phase == Phase.HOME) && now < tripDeadline;
    }

    @Override public void tick() {
        panicTicks++;
        long now = settler.level().getGameTime();
        Settlement s = settler.settlement();
        if (s != null && panicTicks % PANIC_VOCAL_THROTTLE == PANIC_YELP_TICK
                && phase != Phase.SHELTERED && now >= nextCry
                && NEXT_CRY.getOrDefault(s, Long.MIN_VALUE) <= now) {
            nextCry = now + 100;
            NEXT_CRY.put(s, now + PANIC_VOCAL_THROTTLE);
            settler.level().playSound(null, settler.getX(), settler.getY(), settler.getZ(),
                ModSounds.SETTLER_PANIC.get(), SoundSource.NEUTRAL, 0.9F,
                0.95F + settler.getRandom().nextFloat() * 0.1F);
        }
        if (phase == Phase.ALERT_GUARD) {
            if (!availableOwnGuard(alertGuard)) {
                alertGuard = nearestOwnGuard();
                if (alertGuard == null) beginHome(now);
            }
            if (phase == Phase.ALERT_GUARD && alertGuard != null
                    && settler.distanceToSqr(alertGuard) <= ALERT_CONTACT_SQR) {
                if (s != null && observedThreatPos != null) {
                    // Existing alert/ThreatBoard/Guard-order policy owns every defender response.
                    SettlementManager.raiseAlert((ServerLevel) settler.level(), s, observedThreatPos);
                }
                beginHome(now);
                routeCurrent();
            }
        }
        if (phase == Phase.HOME && fleeTarget != null
                && settler.position().distanceToSqr(Vec3.atBottomCenterOf(fleeTarget)) <= 4.0D) {
            // Open ground is a refuge, not a shelter: look for a safe interior again.
            fleeTarget = null;
            homeProbeAt = now;
        }
        if (phase == Phase.HOME && arrivedHome()) {
            // A bell ALARM alone holds shelter only while it lasts; a real
            // sighting, hurt or raid keeps the persisted 600-tick minimum.
            if (!alarmTrip || realDanger()) {
                settler.extendPanicShelterUntil(now + SHELTER_TICKS);
            }
            phase = Phase.SHELTERED;
            settler.getNavigation().stop();
        }
        if (phase == Phase.SHELTERED && (panicTicks + Math.floorMod(settler.getUUID().hashCode(), 20)) % 20 == 0
                && raiderWithin(SHELTER_BREACHED_DISTANCE)) {
            // W20: civilians sheltered in a doorway were cut down where they
            // stood. A raider inside the shelter's reach makes it no shelter:
            // leave it and choose again (geometry only, BH-31 caps unchanged).
            phase = Phase.HOME;
            homeTarget = null;
            homeBuilding = null;
            homeProbeAt = now;
            tripDeadline = now + ROUTE_TIMEOUT_TICKS;
            homeTargetRetryDeadline = now + HOME_TARGET_RETRY_TICKS;
            settler.recordRouteFailure("panic_shelter_breached");
            routeCurrent();
        }
        if (phase == Phase.SHELTERED) {
            settler.getNavigation().stop();
        } else if (phase == Phase.HOME && homeTarget == null && fleeTarget == null
                && now >= homeTargetRetryDeadline) {
            failHome("panic_home_unavailable", now);
        } else if (now >= tripDeadline) {
            failHome(homeTarget == null ? "panic_home_unavailable" : "panic_home_path_blocked", now);
        } else if ((panicTicks + Math.floorMod(settler.getUUID().hashCode(), 20)) % 20 == 0
                || phase == Phase.HOME && homeTarget == null && now == homeProbeAt) {
            // (homeProbeAt is also moved a few ticks on by a deferred search)
            // BH-31: each settler re-routes on its own phase of the 20-tick
            // cycle, so a village that panicked together never re-plans together.
            routeCurrent();
        }
        settler.setActivity(SettlerActivity.FLEEING);
    }

    private void routeCurrent() {
        if (phase == Phase.ALERT_GUARD && alertGuard != null && routeAround(alertGuard.blockPosition())) return;
        if (phase == Phase.ALERT_GUARD) {
            long now = settler.level().getGameTime();
            if (guardRouteRetryUntil == 0L) {
                guardRouteRetryUntil = now + GUARD_ROUTE_RETRY_TICKS;
                settler.recordRouteFailure("panic_guard_path_retry");
                return;
            }
            if (now < guardRouteRetryUntil) return;
            // A still-unreachable Guard must yield MOVE ownership. The usual
            // physical home route remains the civilian's safe fallback.
            beginHome(now);
        }
        if (phase != Phase.HOME) return;
        // Codex review P2 (26 Sep): the path being followed is re-validated
        // geometrically every route cycle (no path query). A band that moved
        // into the corridor stops the civilian BEFORE any replacement is
        // tried, so a deferred or failed search can never leave it walking on.
        Path current = settler.getNavigation().getPath();
        boolean moving = current != null && !current.isDone();
        if (moving && !keepsRaidClearance(current)) {
            settler.getNavigation().stop();
            moving = false;
            homeTarget = null;
            homeBuilding = null;
            fleeTarget = null;
            homeProbeAt = settler.level().getGameTime();
            settler.recordRouteFailure("panic_route_unsafe");
        }
        BlockPos previousTarget = homeTarget;
        Building previousBuilding = homeBuilding;
        if (homeTarget != null && homeBuilding != null && !arrivedHome()
                && !safeShelter(homeBuilding, knownThreats())) {
            // The band moved onto the chosen shelter's side: choose again
            // (geometry only; the next probe keeps the BH-31 caps).
            homeTarget = null;
            homeBuilding = null;
            homeProbeAt = settler.level().getGameTime();
        }
        if (homeTarget == null) {
            long now = settler.level().getGameTime();
            homeTarget = findHomeInterior();
            if (homeTarget == null && pathDeferred && moving && previousTarget != null) {
                // Only the search budget deferred the replacement and the
                // current path is still clear of the band: keep following it
                // until the next cycle re-checks both.
                homeTarget = previousTarget;
                homeBuilding = previousBuilding;
                return;
            }
            if (homeTarget == null && fleeTarget != null && moving) {
                return; // still running away on a path just re-checked above; re-probe on arrival
            }
            if (homeTarget == null && !pathDeferred && fleeAway()) {
                return; // bounded by tripDeadline; danger is re-checked each route cycle
            }
            if (homeTarget == null) {
                if (now >= homeTargetRetryDeadline) {
                    failHome("panic_home_unavailable", now);
                } else {
                    settler.recordRouteFailure("panic_home_path_retry");
                }
                return;
            }
        }
        if (!routeTo(homeTarget)) {
            // Keep trying only within this bounded trip. The stop/retry gate below
            // prevents a permanently obstructed home from retaining MOVE forever.
            settler.recordRouteFailure("panic_home_path_blocked");
        }
    }

    private void failHome(String reason, long now) {
        settler.getNavigation().stop();
        settler.recordRouteFailure(reason);
        // During a raid a civilian with no safe place retries within a
        // second instead of drifting back to work beside the band for five.
        nextHomeRetry = now + (raidThreats().isEmpty() ? HOME_RETRY_TICKS : RAID_HOME_RETRY_TICKS);
        tripDeadline = now;
    }

    /** Starts one bounded physical route home after the Guard leg completes or yields. */
    private void beginHome(long now) {
        phase = Phase.HOME;
        tripDeadline = now + ROUTE_TIMEOUT_TICKS;
        // The initial probe can race a newly spawned civilian's navigation.
        // Re-prove a home briefly here; permanent absence still yields MOVE.
        homeTargetRetryDeadline = Math.max(now, homeProbeAt) + HOME_TARGET_RETRY_TICKS;
        if (homeTarget == null) homeTarget = findHomeInterior();
    }

    @Nullable private SettlerEntity nearestOwnGuard() {
        Settlement s = settler.settlement();
        if (s == null || !(settler.level() instanceof ServerLevel level)) return null;
        return SettlementManager.loadedMembers(level, s).stream().filter(this::availableOwnGuard)
            .min(Comparator.comparingDouble(settler::distanceToSqr)).orElse(null);
    }

    private boolean availableOwnGuard(@Nullable SettlerEntity guard) {
        Settlement s = settler.settlement();
        return guard != null && guard != settler && guard.isAlive() && guard.level() == settler.level()
            && guard.getProfession() == Profession.GUARD && s != null && guard.settlement() != null
            && s.id.equals(guard.settlement().id) && guard.getTarget() == null
            && GuardAssignmentService.hasServiceableEquipment(guard);
    }

    @Nullable private BlockPos findHomeInterior() {
        Settlement s = settler.settlement();
        if (s == null) return null;
        long now = settler.level().getGameTime();
        if (now < homeProbeAt || now == lastProbeTick) return null; // staggered, once a tick (BH-31)
        lastProbeTick = now;
        pathDeferred = false;
        BlockPos bed = settler.getClaimedBed();
        Building claimed = s.buildings.stream().filter(b -> b != null && b.valid
            && b.bounds != null && b.beds.contains(bed) && validClaimedBed(bed)).findFirst().orElse(null);
        unreachableUntil.values().removeIf(until -> until <= now);
        java.util.List<Vec3> threats = knownThreats();
        if (claimed != null && !unreachableUntil.containsKey(claimed.id)
                && safeShelter(claimed, threats)) {
            BlockPos own = interiorTarget(claimed, bed);
            if (own != null) {
                homeBuilding = claimed;
                return own;
            }
            if (pathDeferred && !deferredTooOften(claimed, now)) return deferProbe(now);
            markUnreachable(claimed, now, pathDeferred);
        }
        // Existing housing can be a safe room but is never a claimed/free home.
        // Nearest building first; only BUILDINGS_PER_PROBE per call, rotating on.
        java.util.List<Building> housing = s.buildings.stream().filter(b -> b != null && b.valid
                && b.type != null && b.type.housesResidents() && b.bounds != null && b != claimed
                && !unreachableUntil.containsKey(b.id) && settler.level().hasChunkAt(b.plaquePos)
                && safeShelter(b, threats))
            .sorted(Comparator.comparingDouble((Building b) -> settler.position().distanceToSqr(
                Vec3.atCenterOf(b.bounds.getCenter()))))
            .toList();
        if (housing.isEmpty() && !raidThreats().isEmpty()) {
            // Raid overflow: every safe home is taken by the band's side, so
            // any other safe building interior will do (never the Banner).
            // Same caps: BUILDINGS_PER_PROBE, PATH_TRIES_PER_BUILDING, unreachableUntil.
            housing = s.buildings.stream().filter(b -> b != null && b.valid && b.type != null
                    && !b.type.housesResidents() && b.bounds != null && b != claimed
                    && !unreachableUntil.containsKey(b.id) && settler.level().hasChunkAt(b.plaquePos)
                    && safeShelter(b, threats))
                .sorted(Comparator.comparingDouble((Building b) -> settler.position().distanceToSqr(
                    Vec3.atCenterOf(b.bounds.getCenter()))))
                .toList();
        }
        if (housing.isEmpty()) return null;
        for (int i = 0; i < Math.min(BUILDINGS_PER_PROBE, housing.size()); i++) {
            Building b = housing.get(i);
            BlockPos found = interiorTarget(b, null);
            if (found != null) {
                deferredBuilding = null;
                deferralsInRow = 0;
                homeBuilding = b;
                return found;
            }
            if (pathDeferred && !deferredTooOften(b, now)) return deferProbe(now); // same building again soon
            markUnreachable(b, now, pathDeferred); // proven (or deferred too long): try the next one
        }
        return null;
    }

    private void markUnreachable(Building building, long now, boolean onlyDeferred) {
        // A building only ever deferred (search budget) may well be reachable:
        // skip it briefly; one proven unreachable waits the full window.
        unreachableUntil.put(building.id, now + (onlyDeferred ? DEFERRED_SKIP_TICKS : UNREACHABLE_SKIP_TICKS));
        if (building.id.equals(deferredBuilding)) {
            deferredBuilding = null;
            deferralsInRow = 0;
        }
        pathDeferred = false;
    }

    /** Counts a deferral for this building; true once it has been deferred too often to wait on. */
    private boolean deferredTooOften(Building building, long now) {
        if (!building.id.equals(deferredBuilding)) {
            deferredBuilding = building.id;
            deferralsInRow = 0;
        }
        return ++deferralsInRow > MAX_DEFERRALS;
    }

    /**
     * A deferred (not refused) search: probe again within a few ticks rather
     * than the 20-tick cycle, and do not let the budget race alone run out
     * the home-target window. The 240-tick trip deadline still bounds it.
     */
    @Nullable private BlockPos deferProbe(long now) {
        homeProbeAt = now + 1 + Math.floorMod(settler.getUUID().hashCode(), 3);
        homeTargetRetryDeadline = Math.max(homeTargetRetryDeadline, homeProbeAt + 20);
        settler.recordRouteFailure("panic_home_path_deferred");
        return null;
    }

    private boolean validClaimedBed(@Nullable BlockPos bed) {
        return bed != null && settler.level().hasChunkAt(bed)
            && settler.level().getBlockState(bed).getBlock() instanceof BedBlock;
    }

    @Nullable private BlockPos interiorTarget(Building building, @Nullable BlockPos preferred) {
        java.util.List<BlockPos> candidates = new java.util.ArrayList<>();
        if (preferred != null) for (int[] offset : offsets()) candidates.add(preferred.offset(offset[0], 0, offset[1]));
        int y = building.bounds.minY() + 1;
        for (int x = building.bounds.minX(); x <= building.bounds.maxX(); x++)
            for (int z = building.bounds.minZ(); z <= building.bounds.maxZ(); z++) candidates.add(new BlockPos(x, y, z));
        // BH-31: nearest first, then path LAZILY and stop at the first safe
        // route (was: a full A* for every floor cell, then the min).
        java.util.List<BlockPos> ordered = candidates.stream().filter(target -> target.getX() > building.bounds.minX()
                && target.getX() < building.bounds.maxX() && target.getZ() > building.bounds.minZ()
                && target.getZ() < building.bounds.maxZ() && building.bounds.isInside(target)).filter(this::standingCell)
            .distinct().sorted(Comparator.comparingDouble(target -> preferred == null
                ? settler.position().distanceToSqr(Vec3.atBottomCenterOf(target)) : target.distSqr(preferred))).toList();
        int tries = 0;
        for (BlockPos target : ordered) {
            if (tries++ >= PATH_TRIES_PER_BUILDING) break;
            if (pathSafe(target) != null) return target;
            if (pathDeferred) return null; // out of search budget this tick: retry soon
        }
        return null;
    }

    private boolean arrivedHome() {
        return homeTarget != null && settler.position().distanceToSqr(Vec3.atBottomCenterOf(homeTarget)) <= ARRIVED_HOME_SQR;
    }

    private boolean routeTo(BlockPos target) {
        Path path = pathSafe(target);
        if (path == null) return false;
        // Tech tree (Watchfires): +25% shelter run speed.
        settler.getNavigation().moveTo(path,
            com.hearthstead.settlement.techtree.effects.WatchEffects.shelterSpeed(settler, 1.25));
        return true;
    }

    private boolean routeAround(BlockPos anchor) {
        int tries = 0;
        for (int[] offset : guardContactOffsets(anchor)) {
            BlockPos target = anchor.offset(offset[0], 0, offset[1]);
            if (!standingCell(target)) continue;
            if (tries++ >= GUARD_CONTACT_TRIES) return false; // BH-31: bounded per attempt
            if (routeTo(target)) return true;
        }
        return false;
    }

    /**
     * All walkable approach cells inside the real radius-three Guard-contact
     * disk. Home-interior selection intentionally keeps its own immediate
     * neighbour offsets: this list belongs only to the Guard-report leg.
     */
    private java.util.List<int[]> guardContactOffsets(BlockPos anchor) {
        java.util.List<int[]> result = new java.util.ArrayList<>();
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
            int distanceSqr = x * x + z * z;
            // Do not route onto the Guard's occupied cell. The remaining 28
            // cells are exactly the physical contact disk excluding its centre.
            if (distanceSqr == 0 || distanceSqr > ALERT_CONTACT_SQR) continue;
            result.add(new int[] {x, z});
        }
        result.sort(Comparator
            .comparingInt((int[] offset) -> offset[0] * offset[0] + offset[1] * offset[1])
            .thenComparingDouble(offset -> settler.position().distanceToSqr(
                Vec3.atBottomCenterOf(anchor.offset(offset[0], 0, offset[1]))))
            .thenComparingInt(offset -> offset[0])
            .thenComparingInt(offset -> offset[1]));
        return result;
    }

    @Nullable private Path pathSafe(BlockPos target) {
        PATH_QUERIES.incrementAndGet();
        Path path = settler.getNavigation().createPath(target, 0);
        if ((path == null || !path.canReach())
                && settler.getNavigation() instanceof com.hearthstead.entity.path.RoadNavigation road
                && road.deferredLastSearch()) {
            pathDeferred = true;
            return null;
        }
        return path != null && path.canReach() && keepsClearance(path, threatPosition())
            && keepsRaidClearance(path) ? path : null;
    }

    @Nullable private Vec3 threatPosition() {
        return seenThreat != null && seenThreat.isAlive() ? seenThreat.position()
            : observedThreatPos != null ? Vec3.atBottomCenterOf(observedThreatPos)
            : alarmAvoidPos == null ? null : Vec3.atBottomCenterOf(alarmAvoidPos);
    }

    /** Danger is only an active settlement ALARM: no sighting, no hurt, no pending raid. */
    private boolean alarmOnly(long now) {
        Settlement s = settler.settlement();
        return s != null && s.alertActive(now) && s.pendingRaid == null
            && (seenThreat == null || !seenThreat.isAlive()) && settler.hurtTime <= 0;
    }

    private boolean realDanger() {
        Settlement s = settler.settlement();
        return seenThreat != null && seenThreat.isAlive() || settler.hurtTime > 0
            || s != null && s.pendingRaid != null;
    }

    /**
     * Every threat position this civilian should keep away from: its own
     * sighting or alarm point, plus -- once the settlement knows a raid is on
     * (arrival or a raised ALARM) -- every live raider of the band.
     */
    private java.util.List<Vec3> knownThreats() {
        java.util.List<Vec3> raid = raidThreats();
        Vec3 own = threatPosition();
        if (own == null) return raid;
        java.util.List<Vec3> all = new java.util.ArrayList<>(raid.size() + 1);
        all.add(own);
        all.addAll(raid);
        return all;
    }

    /** Live band positions, shared per settlement per tick; empty outside a raid or ALARM. */
    private java.util.List<Vec3> raidThreats() {
        if (!CivilianSafety.enabled) return java.util.List.of();
        Settlement s = settler.settlement();
        if (s == null || !(settler.level() instanceof ServerLevel level)) return java.util.List.of();
        long now = level.getGameTime();
        if (s.pendingRaid == null && !s.alertActive(now)) return java.util.List.of();
        RaidSnapshot cached = RAID_THREATS.get(s);
        if (cached != null && cached.tick() == now) return cached.positions();
        java.util.List<Vec3> positions = com.hearthstead.settlement.raid.RaidDirector
            .livingRaidersOf(level, s).stream()
            .filter(raider -> !raider.isScout() && !raider.isGoblinThiefDemo())
            .map(net.minecraft.world.entity.Entity::position).toList();
        RAID_THREATS.put(s, new RaidSnapshot(now, positions));
        return positions;
    }

    private boolean raiderWithin(double blocks) {
        double limit = blocks * blocks;
        for (Vec3 threat : raidThreats()) {
            if (settler.position().distanceToSqr(threat) <= limit) return true;
        }
        return false;
    }

    /**
     * Geometric pre-filter, no path query: a shelter is refused when it lies
     * within {@link #SHELTER_THREAT_MARGIN} of a threat -- or nearer to it
     * than this civilian already is, if that is closer. A home just behind a
     * civilian that is fleeing from a nearby monster stays valid; a home in
     * the middle of the band's approach does not.
     */
    private boolean safeShelter(Building building, java.util.List<Vec3> threats) {
        if (!CivilianSafety.enabled || threats.isEmpty() || building.bounds == null) return true;
        Vec3 centre = Vec3.atCenterOf(building.bounds.getCenter());
        for (Vec3 threat : threats) {
            double limit = Math.min(SHELTER_THREAT_MARGIN, Math.sqrt(settler.position().distanceToSqr(threat)));
            if (centre.distanceToSqr(threat) < limit * limit) return false;
        }
        return true;
    }

    /** The route itself must not brush past any raider of the band. */
    private boolean keepsRaidClearance(Path path) {
        java.util.List<Vec3> raid = raidThreats();
        if (raid.isEmpty()) return true;
        Vec3 previous = settler.position();
        for (int i = path.getNextNodeIndex(); i < path.getNodeCount(); i++) {
            Vec3 next = Vec3.atBottomCenterOf(path.getNode(i).asBlockPos());
            for (Vec3 threat : raid) {
                double clearance = Math.min(RAID_ROUTE_CLEARANCE_SQR, settler.position().distanceToSqr(threat));
                if (segmentDistanceSqr(previous, next, threat) + 0.01 < clearance) return false;
            }
            previous = next;
        }
        return true;
    }

    /**
     * No safe interior during a raid: run away from the band's centre on open
     * ground instead of standing still or heading home through it. One path
     * query, bounded by the trip deadline; arrival re-probes for a shelter.
     */
    private boolean fleeAway() {
        java.util.List<Vec3> raid = raidThreats();
        if (raid.isEmpty()) return false;
        Vec3 me = settler.position();
        // Weight near raiders most: the nearest ones decide where "away" is.
        double wx = 0, wz = 0, total = 0;
        for (Vec3 threat : raid) {
            double w = 1.0D / Math.max(1.0D, me.distanceToSqr(threat));
            wx += threat.x * w; wz += threat.z * w; total += w;
        }
        Vec3 centre = new Vec3(wx / total, me.y, wz / total);
        Vec3 away = net.minecraft.world.entity.ai.util.LandRandomPos.getPosAway(settler,
            FLEE_AWAY_DISTANCE, 7, centre);
        if (away == null) {
            // Cramped or edge terrain: a shorter run is still better than none.
            away = net.minecraft.world.entity.ai.util.LandRandomPos.getPosAway(settler,
                FLEE_AWAY_DISTANCE / 2, 5, centre);
        }
        if (away == null) return false;
        BlockPos target = BlockPos.containing(away);
        if (!routeTo(target)) return false;
        fleeTarget = target;
        settler.recordRouteFailure("panic_flee_away");
        return true;
    }

    private static int[][] offsets() { return new int[][] {{0,-1},{1,0},{0,1},{-1,0},{1,-1},{1,1},{-1,1},{-1,-1}}; }

    private boolean standingCell(BlockPos target) {
        return settler.level().hasChunkAt(target) && settler.level().hasChunkAt(target.below())
            && settler.level().getBlockState(target).getCollisionShape(settler.level(), target).isEmpty()
            && settler.level().getBlockState(target.above()).getCollisionShape(settler.level(), target.above()).isEmpty()
            && settler.level().getBlockState(target.below()).isCollisionShapeFullBlock(settler.level(), target.below())
            && settler.level().getFluidState(target).isEmpty();
    }

    private boolean keepsClearance(Path path, @Nullable Vec3 threat) {
        if (!path.isDone() && path.getNextNode().asBlockPos().equals(settler.blockPosition())) path.advance();
        double clearance = threat == null ? 0 : Math.min(16, settler.position().distanceToSqr(threat));
        Vec3 previous = settler.position();
        for (int i = path.getNextNodeIndex(); i < path.getNodeCount(); i++) {
            Vec3 next = Vec3.atBottomCenterOf(path.getNode(i).asBlockPos());
            if (threat != null && segmentDistanceSqr(previous, next, threat) + 0.01 < clearance) return false;
            previous = next;
        }
        return true;
    }

    private static double segmentDistanceSqr(Vec3 from, Vec3 to, Vec3 threat) {
        Vec3 segment = to.subtract(from); double length = segment.lengthSqr();
        double fraction = length < 1.0E-8 ? 0 : Math.max(0, Math.min(1, threat.subtract(from).dot(segment) / length));
        return from.add(segment.scale(fraction)).distanceToSqr(threat);
    }

    /** Test/diagnostic seam: path queries made by every panic goal so far. */
    public static long pathQueries() { return PATH_QUERIES.get(); }

    @Override public void stop() {
        settler.getNavigation().stop();
        alertGuard = null;
        if (phase != Phase.SHELTERED) {
            observedThreatPos = null;
            nextHomeRetry = settler.level().getGameTime() + HOME_RETRY_TICKS;
        }
        settler.setActivity(SettlerActivity.IDLE);
    }
}
