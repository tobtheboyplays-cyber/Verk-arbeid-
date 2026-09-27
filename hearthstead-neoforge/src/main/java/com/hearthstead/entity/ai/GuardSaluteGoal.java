package com.hearthstead.entity.ai;

import com.hearthstead.entity.GuardRank;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.settlement.BlessingEffects;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.guard.FieldOrders;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The guard's greeting (owner spec 2026-09-26). A guard on patrol or post who sees a player
 * -- or his Captain -- coming (about 8-10 blocks out, not at the last moment) stops, turns to
 * face them and snaps to attention, sheathes his sword at the hip, and holds a crisp hand
 * salute to the brow while they approach and walk PAST him, his head and body tracking them.
 * Only when every greeted player is behind him or about 4 blocks past does he cut the salute
 * away, draw his sword again and resume his patrol or post.
 *
 * <p>Rules kept from the first version: each player (and the Captain) is saluted once per pass
 * with a cooldown; never in danger (a target, an alarm or raid, a hostile within 16 blocks, a
 * live field order) -- danger cancels the greeting instantly and the sword is back in hand;
 * the Captain answers with a small nod. Co-op: a player who comes into range during the hold
 * joins it, and the salute is held until the LAST one has passed.
 *
 * <p>Timeline (server ticks, matching the authored clips; client: SettlerModel):
 * EV_GUARD_SALUTE at 0 -- GUARD_ATTENTION_SNAP 0-8, GUARD_SHEATHE_SWORD 8-28 (in the scabbard
 * from tick 22), GUARD_SALUTE_RAISE 28-34, GUARD_SALUTE_HOLD_BROW from 34 while held;
 * EV_GUARD_SALUTE_END -- GUARD_SALUTE_RELEASE 0-7, GUARD_DRAW_SWORD 7-25 (in hand from tick 8);
 * EV_GUARD_SALUTE_CANCEL snaps the presentation back to the drawn sword. The sword never
 * leaves the MAINHAND slot: sheathing is presentation only (GuardScabbardLayer).
 */
public final class GuardSaluteGoal extends Goal {
    /** A walker is greeted from this far out (blocks). */
    public static final double TRIGGER_RANGE = 9.0D;
    /** Someone standing still this close is greeted too (the original rule). */
    public static final double RANGE = 5.0D;
    /** "Walked past": this much further away than their closest approach. */
    public static final double PAST_DISTANCE = 4.0D;
    /** "Behind him": more than this far round from the direction he first faced. */
    public static final double BEHIND_DEGREES = 150.0D;
    public static final int PLAYER_COOLDOWN_TICKS = 60 * 20;
    public static final int CAPTAIN_COOLDOWN_TICKS = 120 * 20;
    public static final int SNAP_TICKS = 8;
    public static final int SHEATHED_AT = 22;
    public static final int HOLD_AT = 34;
    public static final int MIN_HOLD_TICKS = 10;
    /** Nobody stands at a salute forever: someone loitering in front ends it after 20 s. */
    public static final int MAX_HOLD_TICKS = 400;
    public static final int END_TICKS = 25;
    /** Someone who stops in front of him (not passing) is saluted, then released after 2 s still. */
    public static final int STILL_RELEASE_TICKS = 40;
    /** An ordered post further than this away: he is on his way there, no greeting yet. */
    private static final double POST_SLACK = 2.5D;
    public static final int DRAWN_AT = 8;
    private static final double DANGER_RADIUS = 16.0D;
    private static final int SCAN_INTERVAL = 5;
    private static final float MAX_BODY_TURN = 70.0F;
    private static final float BODY_TURN_PER_TICK = 9.0F;
    /** Persistent-data counter (test evidence and QA). */
    public static final String SALUTE_COUNT_TAG = "HearthsteadSalutes";

    public enum Phase { NONE, GREET, END }


    private final SettlerEntity settler;
    private final Map<UUID, Long> lastSaluted = new HashMap<>();
    private final Map<UUID, Double> lastSeenDistance = new HashMap<>();
    /** Greeted, not yet past: uuid -> closest approach (blocks). */
    private final Map<UUID, Double> audience = new LinkedHashMap<>();
    /** uuid -> {x, z, tick of last movement} (stationary visitors are not "passing"). */
    private final Map<UUID, double[]> moved = new HashMap<>();
    @Nullable private LivingEntity candidate;
    private Phase phase = Phase.NONE;
    private int ticks;
    private int endTicks;
    private float facingYaw;
    private float bodyYaw;
    private boolean finished;
    private int scanIn;

    public GuardSaluteGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /** The greeting goal of this guard (tests, QA), or null. */
    @Nullable
    public static GuardSaluteGoal of(SettlerEntity settler) {
        for (var wrapped : settler.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof GuardSaluteGoal goal) return goal;
        }
        return null;
    }

    public Phase phase() {
        return phase;
    }

    /** True while the sword is in the scabbard (never the inventory: presentation only). */
    public boolean isSheathed() {
        return phase == Phase.GREET && ticks >= SHEATHED_AT || phase == Phase.END && endTicks < DRAWN_AT;
    }

    /** True while the hand salute is being held (after the raise, before the release). */
    public boolean isHolding() {
        return phase == Phase.GREET && ticks >= HOLD_AT;
    }

    @Override
    public boolean canUse() {
        if (--scanIn > 0) return false;
        scanIn = SCAN_INTERVAL;
        if (!(settler.level() instanceof ServerLevel level) || !safe(level)) {
            lastSeenDistance.clear();
            return false;
        }
        candidate = pickTarget(level);
        return candidate != null;
    }

    private boolean yieldNow;

    @Override
    public boolean canContinueToUse() {
        return !finished && !yieldNow && phase != Phase.NONE && settler.level() instanceof ServerLevel level
            && safe(level);
    }

    @Override
    public void start() {
        ServerLevel level = (ServerLevel) settler.level();
        LivingEntity first = candidate;
        candidate = null;
        finished = false;
        phase = Phase.GREET;
        ticks = 0;
        endTicks = 0;
        audience.clear();
        moved.clear();
        yieldNow = false;
        settler.getNavigation().stop();
        facingYaw = yawTo(first);
        bodyYaw = facingYaw;
        greet(level, first);
        face(first, true);
        level.broadcastEntityEvent(settler, SettlerEntity.EV_GUARD_SALUTE);
        settler.getPersistentData().putInt(SALUTE_COUNT_TAG,
            settler.getPersistentData().getInt(SALUTE_COUNT_TAG) + 1);
        // A quiet, clipped acknowledgement (subtitle "Settler hums"); no shouting.
        level.playSound(null, settler.blockPosition(), ModSounds.SETTLER_HM.get(), SoundSource.NEUTRAL,
            0.5F, 0.62F + settler.getRandom().nextFloat() * 0.06F);
    }

    @Override
    public void tick() {
        ServerLevel level = (ServerLevel) settler.level();
        // Someone else sent him somewhere (an order, a command): the greeting yields at once.
        if (settler.getNavigation().isInProgress()) {
            yieldNow = true;
            return;
        }
        if (phase == Phase.END) {
            if (++endTicks >= END_TICKS) {
                finished = true;
            }
            return;
        }
        ticks++;
        // co-op: anyone else coming by joins the salute (their own once-per-pass cooldown)
        if (ticks % SCAN_INTERVAL == 0) {
            for (ServerPlayer player : level.players()) {
                if (!audience.containsKey(player.getUUID()) && eligible(player, level.getGameTime())
                    && settler.distanceToSqr(player) <= TRIGGER_RANGE * TRIGGER_RANGE) {
                    greet(level, player);
                }
            }
        }
        LivingEntity watch = null;
        double watchDistance = Double.MAX_VALUE;
        for (Map.Entry<UUID, Double> e : audience.entrySet()) {
            LivingEntity who = find(level, e.getKey());
            if (who == null || !who.isAlive() || passed(who, e)) {
                e.setValue(-1.0D);      // marked past
                continue;
            }
            double d = settler.distanceTo(who);
            if (d < watchDistance) {
                watch = who;
                watchDistance = d;
            }
        }
        audience.values().removeIf(v -> v < 0.0D);
        if (watch != null) {
            face(watch, false);
        }
        boolean allPast = audience.isEmpty();
        boolean allStill = !allPast;
        for (UUID id : audience.keySet()) {
            LivingEntity who = find(level, id);
            if (who == null) continue;
            double[] m = moved.computeIfAbsent(id, k -> new double[] {who.getX(), who.getZ(), ticks});
            if (Math.abs(who.getX() - m[0]) + Math.abs(who.getZ() - m[1]) > 0.3D) {
                m[0] = who.getX();
                m[1] = who.getZ();
                m[2] = ticks;
            }
            if (ticks - m[2] < STILL_RELEASE_TICKS) allStill = false;
        }
        boolean stillTooLong = allStill && ticks >= HOLD_AT + STILL_RELEASE_TICKS;
        if (ticks >= HOLD_AT + MIN_HOLD_TICKS && (allPast || stillTooLong || ticks >= HOLD_AT + MAX_HOLD_TICKS)) {
            phase = Phase.END;
            endTicks = 0;
            level.broadcastEntityEvent(settler, SettlerEntity.EV_GUARD_SALUTE_END);
        }
    }

    @Override
    public void stop() {
        if (phase != Phase.NONE && !finished && settler.level() instanceof ServerLevel level) {
            // Danger (alarm, target, enemy, order): the greeting is dropped on the spot and the
            // sword is in hand at once.
            level.broadcastEntityEvent(settler, SettlerEntity.EV_GUARD_SALUTE_CANCEL);
        }
        phase = Phase.NONE;
        finished = false;
        yieldNow = false;
        audience.clear();
        moved.clear();
        candidate = null;
        ticks = 0;
        endTicks = 0;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    // ------------------------------------------------------------------ targets

    private void greet(ServerLevel level, LivingEntity who) {
        long now = level.getGameTime();
        lastSaluted.put(who.getUUID(), now);
        audience.put(who.getUUID(), (double) settler.distanceTo(who));
        if (who instanceof SettlerEntity captain && captain.isAlive()) {
            // The Captain returns a small nod without stopping his own work.
            level.broadcastEntityEvent(captain, SettlerEntity.EV_GUARD_NOD);
            captain.getLookControl().setLookAt(settler, 30.0F, 30.0F);
        }
    }

    /** Behind him (round from where he first faced) or well past the closest approach. */
    private boolean passed(LivingEntity who, Map.Entry<UUID, Double> entry) {
        double d = settler.distanceTo(who);
        double closest = Math.min(entry.getValue(), d);
        entry.setValue(closest);
        if (d > TRIGGER_RANGE + 3.0D) return true;
        if (d >= closest + PAST_DISTANCE) return true;
        float off = Math.abs(Mth.wrapDegrees(yawTo(who) - facingYaw));
        return off > BEHIND_DEGREES && d > 2.0D;
    }

    @Nullable
    private LivingEntity find(ServerLevel level, UUID id) {
        net.minecraft.world.entity.player.Player p = level.getPlayerByUUID(id);
        if (p != null) return p;
        return level.getEntity(id) instanceof LivingEntity l ? l : null;
    }

    private boolean eligible(ServerPlayer player, long now) {
        return !player.isSpectator() && player.isAlive() && !player.isInvisible() && inView(player)
            && !cooling(player.getUUID(), now, PLAYER_COOLDOWN_TICKS);
    }

    /** Not in danger, not commanded, armed guard of a settlement. */
    private boolean safe(ServerLevel level) {
        // Only the Guard greets: the salute presentation (sheathe, hand salute, scabbard) is the
        // guard's; an archer or battle role would just stand still holding MOVE for nothing.
        if (settler.getProfession() != com.hearthstead.entity.Profession.GUARD
            || settler.getTarget() != null || settler.isSleeping()
            || FieldOrders.controls(settler)
            || com.hearthstead.settlement.summon.PlayerSummons.active(settler) != null) {
            return false;
        }
        Settlement settlement = settler.settlement();
        if (settlement == null || settlement.alertActive(level.getGameTime())
            || BlessingEffects.raidActive(settlement)) {
            return false;
        }
        // Duty first: a hungry defender goes to eat, and one walking to his ordered post gets
        // there before he greets anyone (the greeting takes MOVE; it must never park him).
        if (settler.hasMeal() || settler.getHunger() < 40.0F || awayFromOrderedPost(level, settlement)) {
            return false;
        }
        return level.getEntitiesOfClass(Mob.class, settler.getBoundingBox().inflate(DANGER_RADIUS),
            mob -> mob instanceof Enemy && mob.isAlive()).isEmpty();
    }

    /**
     * The nearest player who is coming (closer than at the last scan) within 9 blocks, or
     * standing within 5; else the Captain the same way. Early, so the whole attention /
     * sheathe / salute is done before they arrive.
     */
    @Nullable
    private LivingEntity pickTarget(ServerLevel level) {
        long now = level.getGameTime();
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        Map<UUID, Double> seen = new HashMap<>();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || !player.isAlive() || player.isInvisible()) continue;
            double distance = Math.sqrt(settler.distanceToSqr(player));
            seen.put(player.getUUID(), distance);
            if (!coming(player.getUUID(), distance) || distance >= bestDistance || !inView(player)
                || cooling(player.getUUID(), now, PLAYER_COOLDOWN_TICKS)) continue;
            best = player;
            bestDistance = distance;
        }
        SettlerEntity captain = best == null ? captain(level) : null;
        // A patrol squad does not salute its own Captain marching in its ranks (PATROL ROUTES lane).
        if (captain != null && captain != settler
            && !com.hearthstead.settlement.guard.patrol.PatrolService.sameSquad(settler, captain)) {
            double distance = Math.sqrt(settler.distanceToSqr(captain));
            seen.put(captain.getUUID(), distance);
            if (coming(captain.getUUID(), distance) && inView(captain)
                && !cooling(captain.getUUID(), now, CAPTAIN_COOLDOWN_TICKS)) {
                best = captain;
            }
        }
        lastSeenDistance.clear();
        lastSeenDistance.putAll(seen);
        return best;
    }

    private boolean coming(UUID id, double distance) {
        if (distance <= RANGE) return true;
        if (distance > TRIGGER_RANGE) return false;
        Double last = lastSeenDistance.get(id);
        return last != null && distance < last - 0.05D;
    }

    private boolean cooling(UUID who, long now, int cooldown) {
        Long last = lastSaluted.get(who);
        return last != null && now - last < cooldown;
    }

    /** In front of the guard (within 100 degrees of where he faces) and visible. */
    private boolean inView(LivingEntity other) {
        Vec3 look = Vec3.directionFromRotation(0.0F, settler.getYHeadRot());
        Vec3 to = other.position().subtract(settler.position()).multiply(1, 0, 1);
        if (to.lengthSqr() < 1.0E-4D) return true;
        double cos = look.dot(to.normalize());
        return cos >= Math.cos(Math.toRadians(100.0D)) && settler.getSensing().hasLineOfSight(other);
    }

    private long postCheckedAt = Long.MIN_VALUE;
    private boolean postAway;

    /** True while a persisted Stand/Tower post order is more than 2.5 blocks away (cached 2 ticks). */
    private boolean awayFromOrderedPost(ServerLevel level, Settlement settlement) {
        long now = level.getGameTime();
        if (postCheckedAt != Long.MIN_VALUE && now - postCheckedAt < 2L) return postAway;
        postCheckedAt = now;
        postAway = false;
        com.hearthstead.settlement.guard.GuardAssignmentService.Validation v =
            com.hearthstead.settlement.guard.GuardAssignmentService.validate(level, settlement, settler, false);
        if (v.valid() && v.order().isPresent()) {
            com.hearthstead.settlement.state.GuardOrder order = v.order().get();
            com.hearthstead.settlement.state.GuardOrder.Mode mode = order.modeAt(now);
            if ((mode == com.hearthstead.settlement.state.GuardOrder.Mode.STAND_POST
                    || mode == com.hearthstead.settlement.state.GuardOrder.Mode.TOWER_POST)
                && order.pos().isPresent()) {
                net.minecraft.core.BlockPos post = order.pos().get();
                postAway = settler.distanceToSqr(post.getX() + 0.5D, post.getY(), post.getZ() + 0.5D)
                    > POST_SLACK * POST_SLACK;
            }
        }
        return postAway;
    }

    @Nullable
    private SettlerEntity captain(ServerLevel level) {
        Settlement settlement = settler.settlement();
        if (settlement == null) return null;
        return GuardRank.captainOf(SettlementManager.loadedMembers(level, settlement));
    }

    private float yawTo(LivingEntity other) {
        double dx = other.getX() - settler.getX();
        double dz = other.getZ() - settler.getZ();
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
    }

    /**
     * Eyes on them; the body turns after them (at most 70 degrees round from where he first
     * faced, 9 degrees a tick) and the head does the rest.
     */
    private void face(LivingEntity other, boolean snap) {
        settler.getLookControl().setLookAt(other.getX(), other.getEyeY(), other.getZ());
        float goal = facingYaw + Mth.clamp(Mth.wrapDegrees(yawTo(other) - facingYaw), -MAX_BODY_TURN, MAX_BODY_TURN);
        bodyYaw = snap ? goal : bodyYaw + Mth.clamp(Mth.wrapDegrees(goal - bodyYaw), -BODY_TURN_PER_TICK,
            BODY_TURN_PER_TICK);
        settler.setYRot(bodyYaw);
        settler.setYBodyRot(bodyYaw);
    }

    public static int saluteCount(SettlerEntity settler) {
        return settler.getPersistentData().getInt(SALUTE_COUNT_TAG);
    }
}
