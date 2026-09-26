package com.hearthstead.entity.ai;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.combat.RaiderMove;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.UUID;

/**
 * The ordinary Skirmisher's hit-and-run melee: small, fast and annoying, but
 * readable and never unfair.
 *
 * <ol>
 *   <li><b>APPROACH</b>: dart in, flanking round to the back of anyone who
 *       can fight back (guards, archers, players).</li>
 *   <li><b>WINDUP</b>: one quick ticketed jab ({@link RaiderMove#LIGHT});
 *       damage lands only on its hit tick.</li>
 *   <li><b>RECOVER</b>: a short, readable follow-through after the jab
 *       ({@link #JAB_RECOVERY_TICKS}); this is the Guard's window to punish
 *       the dart-in.</li>
 *   <li><b>RETREAT</b>: then hop back out of reach, landed or not.</li>
 *   <li><b>CIRCLE / TAUNT</b>: circle at a few blocks, now and then jeering
 *       from out of reach, until the next jab is ready.</li>
 *   <li><b>DODGE</b>: when a guard winds up a heavy (or finisher) on it, the
 *       skirmisher usually sidesteps, then darts back in to punish the
 *       guard's recovery. A cooldown and a miss chance keep it beatable.</li>
 * </ol>
 *
 * <p>Every few seconds it also re-picks its quarry, preferring exposed
 * targets: civilians, lone archers and players with their back turned, and
 * shying away from anyone standing next to a guard.
 */
public final class RaiderSkirmishGoal extends Goal {

    public enum Phase { APPROACH, WINDUP, RECOVER, RETREAT, CIRCLE, TAUNT, DODGE }

    public static final double APPROACH_SPEED = 1.25D;
    public static final double RETREAT_SPEED = 1.35D;
    public static final int RETREAT_TICKS = 14;
    /**
     * Follow-through after the jab before the hop back (26 Sep). Without it
     * the skirmisher left reach one tick after its 3-tick jab, while a Guard
     * light only connects 4 ticks after it starts: a GameTest showed two
     * Guards swinging at one skirmisher for 11 s without a single contact.
     * Eight ticks (0.4 s) is one Guard light plus a tick of reaction.
     */
    public static final int JAB_RECOVERY_TICKS = 8;
    public static final double RETREAT_DISTANCE = 4.5D;
    /** Start-to-start jab cadence; slower than a line raider's swing. */
    public static final int JAB_CADENCE_TICKS = 30;
    public static final double DODGE_CHANCE = 0.75D;
    public static final int DODGE_COOLDOWN_TICKS = 50;
    public static final int DODGE_TICKS = 9;
    /** Lateral launch speed of a sidestep (about 1.7 blocks with friction). */
    public static final double DODGE_PUSH = 0.8D;
    public static final double THREAT_RADIUS = 4.0D;
    public static final double TAUNT_CHANCE = 0.25D;
    public static final int TAUNT_TICKS = 40;
    public static final int RETARGET_INTERVAL_TICKS = 40;
    public static final double RETARGET_RADIUS = 14.0D;

    private final RaiderEntity raider;
    private Phase phase = Phase.APPROACH;
    private long phaseUntil = Long.MIN_VALUE;
    private long pendingTicket;
    private long pendingContactTick = Long.MIN_VALUE;
    private UUID pendingTargetId;
    private long nextJabTick = Long.MIN_VALUE;
    private long nextDodgeTick = Long.MIN_VALUE;
    private long nextRetargetTick = Long.MIN_VALUE;
    private long nextPathTick = Long.MIN_VALUE;
    private int circleDir = 1;
    private Vec3 retreatPoint;
    private Boolean forcedDodge;

    // Read-only evidence for GameTests.
    private int jabs;
    private int dodges;
    private boolean lastJabLanded;
    private long lastJabContactTick = Long.MIN_VALUE;
    private long lastDodgeTick = Long.MIN_VALUE;

    public RaiderSkirmishGoal(RaiderEntity raider) {
        this.raider = raider;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return raider.isSkirmisherPest() && validTarget(raider.getTarget());
    }

    @Override
    public boolean canContinueToUse() {
        return raider.isSkirmisherPest() && validTarget(raider.getTarget());
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        phase = Phase.APPROACH;
        raider.setAggressive(true);
    }

    @Override
    public void stop() {
        cancelPending();
        raider.getNavigation().stop();
        raider.setAggressive(false);
        phase = Phase.APPROACH;
    }

    private boolean validTarget(LivingEntity target) {
        return target != null && target.isAlive() && !target.isRemoved()
            && target.level() == raider.level() && raider.canAttack(target)
            && raider.distanceToSqr(target) < 32.0D * 32.0D;
    }

    @Override
    public void tick() {
        if (!(raider.level() instanceof ServerLevel level)) {
            return;
        }
        LivingEntity target = raider.getTarget();
        if (target == null) {
            return;
        }
        long now = level.getGameTime();
        if (raider.isStaggered()) {
            cancelPending();
            raider.getNavigation().stop();
            return;
        }

        // Read the guards: a heavy wind-up on us is a cue to sidestep.
        if (phase != Phase.DODGE && now >= nextDodgeTick) {
            SettlerEntity threat = incomingHeavy();
            if (threat != null) {
                nextDodgeTick = now + DODGE_COOLDOWN_TICKS;
                boolean dodge = forcedDodge != null ? forcedDodge
                    : raider.getRandom().nextDouble() < DODGE_CHANCE;
                if (dodge) {
                    dodge(threat, now);
                    return;
                }
            }
        }

        if ((phase == Phase.APPROACH || phase == Phase.CIRCLE)
            && now >= nextRetargetTick) {
            nextRetargetTick = now + RETARGET_INTERVAL_TICKS;
            LivingEntity better = pickExposedTarget(level, target);
            if (better != null && better != target) {
                raider.setTarget(better);
                target = better;
            }
        }

        raider.getLookControl().setLookAt(target, 30.0F, 30.0F);
        switch (phase) {
            case APPROACH -> tickApproach(target, now);
            case WINDUP -> tickWindup(target, now);
            case RECOVER -> tickRecover(target, now);
            case RETREAT -> tickRetreat(target, now);
            case CIRCLE -> tickCircle(target, now);
            case TAUNT -> tickTaunt(target, now);
            case DODGE -> {
                if (now >= phaseUntil) {
                    // Punish the guard's heavy recovery with a quick dart in.
                    phase = Phase.APPROACH;
                    nextJabTick = Math.min(nextJabTick, now);
                }
            }
        }
    }

    // ------------------------------------------------------------- phases

    private void tickApproach(LivingEntity target, long now) {
        if (now >= nextPathTick) {
            nextPathTick = now + 4L;
            Vec3 goal = flankPoint(target);
            raider.getNavigation().moveTo(goal.x, goal.y, goal.z, APPROACH_SPEED);
        }
        if (now >= nextJabTick && raider.isWithinMeleeAttackRange(target)
            && raider.getSensing().hasLineOfSight(target)
            && raider.isAuthorizedMeleeMoveTarget(target)) {
            long ticket = raider.beginMeleeMove(target, RaiderMove.LIGHT);
            if (ticket != 0L) {
                jabs++;
                pendingTicket = ticket;
                pendingTargetId = target.getUUID();
                pendingContactTick = now + RaiderMove.LIGHT.hitTick();
                nextJabTick = now + JAB_CADENCE_TICKS;
                phase = Phase.WINDUP;
                raider.getNavigation().stop();
            }
        }
    }

    private void tickWindup(LivingEntity target, long now) {
        raider.getNavigation().stop();
        if (pendingTicket == 0L || pendingTargetId == null
            || !pendingTargetId.equals(target.getUUID()) || now > pendingContactTick) {
            cancelPending();
            hopBack(target, now);
            return;
        }
        if (now < pendingContactTick) {
            return;
        }
        long ticket = pendingTicket;
        clearPending();
        lastJabLanded = raider.commitMeleeMove(ticket, target);
        lastJabContactTick = now;
        // Jab, a short committed follow-through, then back out of reach,
        // landed or not.
        phase = Phase.RECOVER;
        phaseUntil = now + JAB_RECOVERY_TICKS;
    }

    /**
     * The committed follow-through. The jab's own hurt knockback shoves the
     * victim about a block away, which alone put a Guard out of its short
     * melee reach for the whole window (26 Sep engagement trace: dist 1.8
     * during RECOVER, zero contacts). The skirmisher therefore presses in to
     * its own reach while it recovers, then hops back.
     */
    private void tickRecover(LivingEntity target, long now) {
        if (now >= phaseUntil) {
            hopBack(target, now);
            return;
        }
        if (raider.isWithinMeleeAttackRange(target)) {
            raider.getNavigation().stop();
        } else {
            raider.getNavigation().moveTo(target, APPROACH_SPEED);
        }
    }

    private void hopBack(LivingEntity target, long now) {
        Vec3 away = horizontal(raider.position().subtract(target.position()));
        if (raider.onGround()) {
            raider.setDeltaMovement(away.x * 0.55D, 0.32D, away.z * 0.55D);
            raider.hasImpulse = true;
        }
        raider.presentHopBack();
        retreatPoint = target.position().add(away.scale(RETREAT_DISTANCE));
        raider.getNavigation().moveTo(retreatPoint.x, retreatPoint.y, retreatPoint.z,
            RETREAT_SPEED);
        phase = Phase.RETREAT;
        phaseUntil = now + RETREAT_TICKS;
    }

    private void tickRetreat(LivingEntity target, long now) {
        if (retreatPoint != null && now % 5L == 0L) {
            raider.getNavigation().moveTo(retreatPoint.x, retreatPoint.y,
                retreatPoint.z, RETREAT_SPEED);
        }
        if (now < phaseUntil) {
            return;
        }
        raider.getNavigation().stop();
        boolean guardClose = nearestGuardWithin(raider, 4.0D) != null;
        if (!guardClose && raider.distanceTo(target) >= 3.5D
            && raider.getRandom().nextDouble() < TAUNT_CHANCE) {
            raider.presentTaunt();
            phase = Phase.TAUNT;
            phaseUntil = now + TAUNT_TICKS;
        } else {
            phase = Phase.CIRCLE;
            phaseUntil = now + 20L + raider.getRandom().nextInt(21);
            circleDir = raider.getRandom().nextBoolean() ? 1 : -1;
        }
    }

    private void tickCircle(LivingEntity target, long now) {
        raider.getNavigation().stop();
        double dist = raider.distanceTo(target);
        float forward = dist > 5.0D ? 0.5F : dist < 3.0D ? -0.5F : 0.0F;
        if (raider.horizontalCollision) {
            circleDir = -circleDir;
        }
        raider.getMoveControl().strafe(forward, circleDir * 0.7F);
        if (now >= phaseUntil && now >= nextJabTick) {
            phase = Phase.APPROACH;
        }
    }

    private void tickTaunt(LivingEntity target, long now) {
        raider.getNavigation().stop();
        if (nearestGuardWithin(raider, 2.5D) != null) {
            // Taunting is for out of reach; a guard closing in ends it.
            hopBack(target, now);
            return;
        }
        if (now >= phaseUntil) {
            phase = now >= nextJabTick ? Phase.APPROACH : Phase.CIRCLE;
            phaseUntil = now + 20L;
        }
    }

    private void dodge(SettlerEntity guard, long now) {
        cancelPending();
        Vec3 facing = horizontal(guard.position().subtract(raider.position()));
        Vec3 left = new Vec3(facing.z, 0.0D, -facing.x);
        boolean goLeft = raider.getRandom().nextBoolean();
        Vec3 side = goLeft ? left : left.scale(-1.0D);
        // Purely lateral: stepping off the blow's line (not merely back
        // along it) is what takes the body out of a locked swing arc.
        Vec3 push = side.scale(DODGE_PUSH);
        raider.setDeltaMovement(push.x, raider.onGround() ? 0.22D : 0.0D, push.z);
        raider.hasImpulse = true;
        raider.getNavigation().stop();
        raider.presentDodge(goLeft);
        dodges++;
        lastDodgeTick = now;
        phase = Phase.DODGE;
        phaseUntil = now + DODGE_TICKS;
    }

    // ------------------------------------------------------------ helpers

    /** The nearest guard winding up a heavy/finisher on this skirmisher. */
    private SettlerEntity incomingHeavy() {
        SettlerEntity best = null;
        int bestIn = Integer.MAX_VALUE;
        for (SettlerEntity guard : raider.level().getEntitiesOfClass(SettlerEntity.class,
                raider.getBoundingBox().inflate(THREAT_RADIUS))) {
            int in = guard.ticksUntilHeavyBlowOn(raider);
            if (in >= 1 && in < bestIn) {
                best = guard;
                bestIn = in;
            }
        }
        return best;
    }

    /** Behind anyone who can fight back; straight at anyone who cannot. */
    private static Vec3 flankPoint(LivingEntity target) {
        boolean dangerous = target instanceof Player
            || target instanceof SettlerEntity settler && settler.getProfession().martial();
        if (!dangerous) {
            return target.position();
        }
        Vec3 look = horizontal(target.getLookAngle());
        return target.position().subtract(look.scale(1.3D));
    }

    private LivingEntity pickExposedTarget(ServerLevel level, LivingEntity current) {
        LivingEntity best = current;
        double bestScore = exposure(current);
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class,
                raider.getBoundingBox().inflate(RETARGET_RADIUS))) {
            if (candidate == current || !candidate.isAlive()
                || !(candidate instanceof SettlerEntity || candidate instanceof Player)
                || !raider.canAttack(candidate) || !raider.isMyWar(candidate)
                || candidate instanceof Player p && (p.isCreative() || p.isSpectator())
                || !raider.getSensing().hasLineOfSight(candidate)) {
                continue;
            }
            double score = exposure(candidate);
            if (score > bestScore + 1.0D) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    /** Higher = more exposed, closer and less protected. */
    private double exposure(LivingEntity candidate) {
        double score;
        if (candidate instanceof SettlerEntity settler) {
            Profession p = settler.getProfession();
            score = p == Profession.GUARD ? 0.0D : 3.0D;
            // BATTLE-ROLES: the blades are no softer than a Guard; the mage
            // and the healer are priority targets (plan/BATTLE-ROLES.md).
            score += com.hearthstead.entity.combat.role.RoleCombat.skirmisherExposureBonus(p);
        } else if (candidate instanceof Player player) {
            Vec3 toMe = horizontal(raider.position().subtract(player.position()));
            boolean backTurned = horizontal(player.getLookAngle()).dot(toMe) < -0.2D;
            score = 2.0D + (backTurned ? 2.0D : 0.0D);
        } else {
            score = 1.0D;
        }
        for (SettlerEntity guard : raider.level().getEntitiesOfClass(SettlerEntity.class,
                candidate.getBoundingBox().inflate(5.0D))) {
            if (guard != candidate && guard.getProfession().frontline()) {
                score -= 1.5D;
            }
        }
        return score - raider.distanceTo(candidate) * 0.15D;
    }

    private static SettlerEntity nearestGuardWithin(RaiderEntity raider, double r) {
        for (SettlerEntity guard : raider.level().getEntitiesOfClass(SettlerEntity.class,
                raider.getBoundingBox().inflate(r))) {
            if (guard.getProfession() == Profession.GUARD && guard.isAlive()) {
                return guard;
            }
        }
        return null;
    }

    private static Vec3 horizontal(Vec3 v) {
        Vec3 flat = new Vec3(v.x, 0.0D, v.z);
        double len = flat.length();
        return len < 1.0E-4D ? new Vec3(1.0D, 0.0D, 0.0D) : flat.scale(1.0D / len);
    }

    private void cancelPending() {
        if (pendingTicket != 0L) {
            raider.cancelMeleeMove(pendingTicket);
        }
        clearPending();
    }

    private void clearPending() {
        pendingTicket = 0L;
        pendingContactTick = Long.MIN_VALUE;
        pendingTargetId = null;
    }

    // ------------------------------------------------ test seams / evidence

    /** Deterministic QA seam: always (true) / never (false) / randomly (null) dodge. */
    public void forceDodge(Boolean dodge) {
        this.forcedDodge = dodge;
    }

    public Phase phase() {
        return phase;
    }

    public int jabs() {
        return jabs;
    }

    public int dodges() {
        return dodges;
    }

    public boolean lastJabLanded() {
        return lastJabLanded;
    }

    public long lastJabContactTick() {
        return lastJabContactTick;
    }

    public long lastDodgeTick() {
        return lastDodgeTick;
    }
}
