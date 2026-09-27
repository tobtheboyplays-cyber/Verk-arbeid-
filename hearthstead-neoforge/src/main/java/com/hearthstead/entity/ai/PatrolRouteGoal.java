package com.hearthstead.entity.ai;

import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Schedule;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.guard.BannerTeams;
import com.hearthstead.settlement.guard.FieldOrders;
import com.hearthstead.settlement.guard.FieldTerrain;
import com.hearthstead.settlement.guard.FormationMath;
import com.hearthstead.settlement.guard.patrol.PatrolFormation;
import com.hearthstead.settlement.guard.patrol.PatrolRoute;
import com.hearthstead.settlement.guard.patrol.PatrolRules;
import com.hearthstead.settlement.guard.patrol.PatrolService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;

/**
 * A guard walking a player-drawn patrol route with its squad (PATROL ROUTES
 * lane). Priority 6 like the ordinary rounds ({@link GuardPatrolGoal}, which
 * stands aside for an assigned guard), so everything that outranks the
 * rounds outranks this too: combat (2), a field order, summons, the alarm
 * response and the salute (3), an explicit post order, eating and rest
 * (4-5). When those let go, the squad picks the route up where it left it.
 *
 * <p>The leader paths waypoint to waypoint at a walking pace, rests at each
 * waypoint while looking round, and waits for a straggler. Followers keep
 * their {@link PatrolFormation} slot measured along the leader's trail, catch
 * up when split, halt and face the player when the leader stops to salute,
 * and hold where they are while the leader is busy with anything else. An
 * alarm stands the whole squad down until it is over.
 */
public final class PatrolRouteGoal extends Goal {
    static final double REACH_SQR = 2.25D;
    static final int PAUSE_TICKS = 60;
    static final int LOOK_EVERY = 20;
    static final int REPATH_TICKS = 20;
    static final int FOLLOW_REPATH_TICKS = 10;
    /** Leader repaths with no real progress this many times: skip the waypoint. */
    static final int MAX_STALLS = 6;
    static final double LEADER_SPEED = 0.7D;
    static final double FOLLOW_SPEED = 0.8D;
    static final double CATCH_UP_SPEED = 1.05D;
    /** A follower farther than this from the leader: the leader waits. */
    static final double SPLIT_DISTANCE = 9.0D;
    /** The leader waits at most this long for a straggler, then goes on. */
    static final int MAX_WAIT_TICKS = 200;
    static final double SLOT_SLACK_SQR = 1.44D;
    static final double CATCH_UP_DISTANCE_SQR = 36.0D;

    private final SettlerEntity settler;
    @Nullable private PatrolService.Slot slot;
    @Nullable private PatrolRoute route;
    private int repathIn;
    private int pauseTicks;
    private int stalls;
    private double lastDistance = Double.MAX_VALUE;
    private int waitTicks;
    private int seenArrivals;
    private int lookIn;
    @Nullable private BlockPos moveTarget;

    public PatrolRouteGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return capture();
    }

    @Override
    public boolean canContinueToUse() {
        return capture();
    }

    private boolean capture() {
        slot = null;
        route = null;
        if (!PatrolService.enabled() || !(settler.level() instanceof ServerLevel level)
            || !GuardPatrolGoal.patrols(settler.getProfession())
            || settler.getTarget() != null || settler.isSleeping()) {
            return false;
        }
        Settlement settlement = settler.settlement();
        if (settlement == null || settlement.alertActive(level.getGameTime())
            || FieldOrders.controls(settler) || BannerTeams.active(settler) != null
            || com.hearthstead.settlement.summon.PlayerSummons.active(settler) != null
            || !Schedule.onWatch(settlement, settler, settler.dayPhase())) {
            return false;
        }
        PatrolService.Slot found = PatrolService.slot(settler);
        if (found == null) return false;
        PatrolRoute r = settlement.patrolRoutes.route(found.squad().routeId);
        if (r == null || !PatrolService.valid(r, settlement)) return false;
        slot = found;
        route = r;
        return true;
    }

    // Sound pass: the leader calls the squad into step and stamps it to a halt,
    // but only for a real departure/stop, never for a brief goal hiccup.
    private long startedAt = Long.MIN_VALUE / 2;
    private long stoppedAt = Long.MIN_VALUE / 2;

    @Override
    public void start() {
        long now = settler.level().getGameTime();
        if (slot != null && slot.leader() && now - stoppedAt > 200L) {
            settler.level().playSound(null, settler.blockPosition(),
                com.hearthstead.registry.ModSounds.PATROL_MARCH.get(),
                net.minecraft.sounds.SoundSource.NEUTRAL, 0.9F, 1.0F);
        }
        startedAt = now;
        settler.setActivity(SettlerActivity.PATROLLING);
        repathIn = 0;
        pauseTicks = 0;
        stalls = 0;
        lastDistance = Double.MAX_VALUE;
        waitTicks = 0;
        lookIn = 0;
        moveTarget = null;
        seenArrivals = slot == null ? 0 : slot.squad().arrivals();
    }

    @Override
    public void tick() {
        if (slot == null || route == null || !(settler.level() instanceof ServerLevel level)) return;
        if (slot.leader()) {
            tickLeader(level, slot.squad(), route);
        } else {
            tickFollower(level, slot.squad(), route);
        }
    }

    @Override
    public void stop() {
        settler.getNavigation().stop();
        settler.setActivity(SettlerActivity.IDLE);
        long now = settler.level().getGameTime();
        if (slot != null && slot.leader() && now - startedAt > 100L && settler.isAlive()) {
            settler.level().playSound(null, settler.blockPosition(),
                com.hearthstead.registry.ModSounds.PATROL_HALT.get(),
                net.minecraft.sounds.SoundSource.NEUTRAL, 0.9F, 1.0F);
        }
        stoppedAt = now;
        if (slot != null && slot.leader()) {
            slot.squad().setPausing(false);
            slot.squad().setRegrouping(false);
        }
        moveTarget = null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    // ----------------------------------------------------------- leader ---

    private void tickLeader(ServerLevel level, PatrolService.Squad squad, PatrolRoute route) {
        List<BlockPos> points = route.waypoints();
        if (squad.target() < 0 || squad.target() >= points.size() || squad.seenRevision() != route.revision()) {
            squad.setTarget(nearest(points), squad.direction() < 0 ? -1 : 1, route.revision());
            resetLeg();
        }
        squad.recordTrail(settler.getUUID(), settler.blockPosition());
        BlockPos dest = points.get(squad.target());
        if (arrived(dest)) {
            settler.getNavigation().stop();
            if (pauseTicks == 0) {
                squad.arrived();
                squad.setPausing(true);
                GuardPatrolGoal.drillAtWaypoint(settler);
            }
            pauseTicks++;
            if (pauseTicks % LOOK_EVERY == 1) lookAround();
            if (pauseTicks >= PAUSE_TICKS) {
                int[] next = PatrolRules.advance(squad.target(), squad.direction(), points.size(), route.loop());
                squad.setTarget(next[0], next[1], route.revision());
                squad.setPausing(false);
                resetLeg();
            }
            return;
        }
        pauseTicks = 0;
        squad.setPausing(false);
        SettlerEntity straggler = straggler(level, squad);
        if (straggler != null && waitTicks < MAX_WAIT_TICKS) {
            waitTicks++;
            squad.setRegrouping(true);
            settler.getNavigation().stop();
            settler.getLookControl().setLookAt(straggler, 30.0F, 30.0F);
            repathIn = 0;
            return;
        }
        if (straggler == null) waitTicks = 0;
        squad.setRegrouping(false);
        if (--repathIn > 0 && settler.getNavigation().isInProgress()) return;
        repathIn = REPATH_TICKS;
        double distance = Math.sqrt(settler.blockPosition().distSqr(dest));
        if (distance > lastDistance - 0.5D) {
            stalls++;
        } else {
            stalls = 0;
        }
        lastDistance = Math.min(lastDistance, distance);
        boolean moving = settler.getNavigation().moveTo(dest.getX() + 0.5D, dest.getY(), dest.getZ() + 0.5D,
            LEADER_SPEED);
        if (!moving) stalls++;
        if (stalls >= MAX_STALLS) {
            // Unreachable for now (a door shut, a block placed): note it once and go on.
            settler.recordRouteFailure("patrol_route:no_path");
            int[] next = PatrolRules.advance(squad.target(), squad.direction(), points.size(), route.loop());
            squad.setTarget(next[0], next[1], route.revision());
            resetLeg();
        }
    }

    private void resetLeg() {
        pauseTicks = 0;
        stalls = 0;
        lastDistance = Double.MAX_VALUE;
        repathIn = 0;
    }

    private boolean arrived(BlockPos dest) {
        double dx = settler.getX() - (dest.getX() + 0.5D);
        double dz = settler.getZ() - (dest.getZ() + 0.5D);
        return dx * dx + dz * dz <= REACH_SQR && Math.abs(settler.getY() - dest.getY()) <= 2.0D;
    }

    private int nearest(List<BlockPos> points) {
        int best = 0;
        double bestSq = Double.MAX_VALUE;
        for (int i = 0; i < points.size(); i++) {
            double d = settler.blockPosition().distSqr(points.get(i));
            if (d < bestSq) {
                bestSq = d;
                best = i;
            }
        }
        return best;
    }

    /** The farthest follower walking with us that fell more than {@link #SPLIT_DISTANCE} behind. */
    @Nullable
    private SettlerEntity straggler(ServerLevel level, PatrolService.Squad squad) {
        SettlerEntity worst = null;
        double worstSq = SPLIT_DISTANCE * SPLIT_DISTANCE;
        List<java.util.UUID> members = squad.members();
        for (int i = 1; i < members.size(); i++) {
            if (!(level.getEntity(members.get(i)) instanceof SettlerEntity follower) || !follower.isAlive()
                || follower.getActivity() != SettlerActivity.PATROLLING) {
                continue;
            }
            double d = follower.distanceToSqr(settler);
            if (d > worstSq) {
                worst = follower;
                worstSq = d;
            }
        }
        return worst;
    }

    // --------------------------------------------------------- follower ---

    private void tickFollower(ServerLevel level, PatrolService.Squad squad, PatrolRoute route) {
        if (squad.arrivals() != seenArrivals) {
            seenArrivals = squad.arrivals();
            GuardPatrolGoal.drillAtWaypoint(settler);
        }
        java.util.UUID leaderId = squad.leader();
        SettlerEntity leader = leaderId != null && level.getEntity(leaderId) instanceof SettlerEntity s
            && s.isAlive() ? s : null;
        if (leader == null) {
            hold();
            return;
        }
        GuardSaluteGoal leaderSalute = GuardSaluteGoal.of(leader);
        if (leaderSalute != null && leaderSalute.phase() != GuardSaluteGoal.Phase.NONE) {
            // The leader halted to salute: the squad halts and faces the same way.
            settler.getNavigation().stop();
            ServerPlayer seen = nearestPlayer(level, leader);
            if (seen != null) settler.getLookControl().setLookAt(seen, 30.0F, 30.0F);
            return;
        }
        if (leader.getActivity() != SettlerActivity.PATROLLING) {
            hold();
            return;
        }
        BlockPos target = slotTarget(level, squad, route, leader);
        double dx = settler.getX() - (target.getX() + 0.5D);
        double dz = settler.getZ() - (target.getZ() + 0.5D);
        double d2 = dx * dx + dz * dz;
        if (d2 <= SLOT_SLACK_SQR && Math.abs(settler.getY() - target.getY()) <= 2.0D) {
            settler.getNavigation().stop();
            moveTarget = null;
            if (squad.pausing() || squad.regrouping()) {
                if (--lookIn <= 0) {
                    lookIn = LOOK_EVERY;
                    lookAround();
                }
            } else {
                float yaw = leader.getYRot();
                double rad = Math.toRadians(yaw);
                settler.getLookControl().setLookAt(leader.getX() - Math.sin(rad) * 8.0D, leader.getEyeY(),
                    leader.getZ() + Math.cos(rad) * 8.0D);
            }
            return;
        }
        if (--repathIn > 0 && target.equals(moveTarget) && settler.getNavigation().isInProgress()) return;
        repathIn = FOLLOW_REPATH_TICKS;
        moveTarget = target;
        double speed = d2 > CATCH_UP_DISTANCE_SQR ? CATCH_UP_SPEED : FOLLOW_SPEED;
        settler.getNavigation().moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, speed);
    }

    /** This follower's standable slot cell behind the leader. */
    private BlockPos slotTarget(ServerLevel level, PatrolService.Squad squad, PatrolRoute route,
                                SettlerEntity leader) {
        BlockPos leaderPos = leader.blockPosition();
        List<BlockPos> trail = squad.trail();
        BlockPos back = trail.size() > 2 ? trail.get(Math.min(trail.size() - 1, 2)) : null;
        int octant = back == null ? FormationMath.octant(leader.getYRot())
            : PatrolFormation.heading(back, leaderPos, FormationMath.octant(leader.getYRot()));
        int size = squad.members().size();
        BlockPos wanted = PatrolFormation.target(route.formation(), size, slot.index(), leaderPos, trail, octant);
        BlockPos snapped = standable(level, wanted);
        if (snapped != null) return snapped;
        // A pair slot off the wall edge: fall back to single file on the leader's trail.
        BlockPos column = PatrolFormation.target(PatrolRoute.Formation.COLUMN, size, slot.index(), leaderPos, trail,
            octant);
        snapped = standable(level, column);
        return snapped != null ? snapped : leaderPos;
    }

    @Nullable
    private static BlockPos standable(ServerLevel level, BlockPos near) {
        if (!level.hasChunkAt(near)) return null;
        if (FieldTerrain.standable(level, near)) return near;
        BlockPos up = near.above();
        if (FieldTerrain.standable(level, up)) return up;
        BlockPos down = near.below();
        return FieldTerrain.standable(level, down) ? down : null;
    }

    private void hold() {
        settler.getNavigation().stop();
        moveTarget = null;
        if (--lookIn <= 0) {
            lookIn = LOOK_EVERY * 2;
            lookAround();
        }
    }

    private void lookAround() {
        double angle = settler.getRandom().nextDouble() * Math.PI * 2.0D;
        settler.getLookControl().setLookAt(settler.getX() + Math.cos(angle) * 8.0D, settler.getEyeY(),
            settler.getZ() + Math.sin(angle) * 8.0D);
    }

    @Nullable
    private static ServerPlayer nearestPlayer(ServerLevel level, SettlerEntity around) {
        ServerPlayer best = null;
        double bestSq = 12.0D * 12.0D;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || !player.isAlive()) continue;
            double d = player.distanceToSqr(around);
            if (d < bestSq) {
                best = player;
                bestSq = d;
            }
        }
        return best;
    }
}
