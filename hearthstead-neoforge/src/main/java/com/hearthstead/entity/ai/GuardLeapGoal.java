package com.hearthstead.entity.ai;

import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.GuardRank;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModSounds;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * The leap strike: a sergeant clears the gap and lands on everyone at once.
 *
 * <p>The owner named this one directly — TekTopia's leap that damages several
 * enemies, which is the single most memorable thing a guard there does. It is
 * gated behind {@link GuardRank#SERGEANT}, sixty points of Strength, so the
 * first time one of your own guards does it, it is because that guard has
 * survived a lot of nights.
 *
 * <h2>The whole feel is in the timing</h2>
 *
 * <p>The damage does <b>not</b> happen when the goal starts. The guard coils,
 * launches, hangs, and the blow lands when they land — so the animation is the
 * attack rather than a decoration on top of one. The window between launch and
 * landing is real airtime the player can watch and an enemy could, in
 * principle, walk out of.
 *
 * <p>Secondary targets take {@link GuardRank#CLEAVE_SHARE} of the blow, the
 * same way vanilla's sweep does — an area attack that hits everything for full
 * damage stops being a special move and starts being the only move. And the
 * splash is hostile-only: besides the marked target, only a
 * {@link RaiderEntity} takes the slam, never a bystander at the landing.
 *
 * <h2>Terrain never eats the move silently</h2>
 *
 * <p>A leap that comes down in water, or that runs out its 40-tick airtime off
 * a ledge, used to fizzle: no damage, no sound, and the full cooldown burned
 * anyway. Every leap now <i>resolves</i> — close to the mark it still lands at
 * half strength, wide of it the guard keeps half the cooldown back — see
 * {@link #resolveFizzle()}.
 */
public class GuardLeapGoal extends Goal {

    /** Air ticks before {@link #canContinueToUse()} gives up on a landing. */
    private static final int MAX_AIR_TICKS = 40;
    /** A fizzled leap that still came down within this of the target counts
     *  as arriving — badly — and resolves at half damage. */
    private static final double FIZZLE_HIT_RANGE = 2.5;

    private final SettlerEntity guard;
    private LivingEntity target;
    private boolean airborne;
    private int cooldown;
    private int airTicks;

    public GuardLeapGoal(SettlerEntity guard) {
        this.guard = guard;
        setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        if (guard.getProfession() != Profession.GUARD || !guard.onGround()) {
            return false;
        }
        if (!GuardRank.of(guard).atLeast(GuardRank.SERGEANT)) {
            return false;
        }
        LivingEntity aim = guard.getTarget();
        if (aim == null || !aim.isAlive()) {
            return false;
        }
        double distance = guard.distanceTo(aim);
        // Too close and it is just a swing; too far and it reads as flying.
        if (distance < GuardRank.LEAP_MIN || distance > GuardRank.LEAP_MAX) {
            return false;
        }
        target = aim;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return airborne && airTicks < MAX_AIR_TICKS;
    }

    @Override
    public void start() {
        airborne = true;
        airTicks = 0;
        guard.setActivity(SettlerActivity.COMBAT);
        guard.triggerLeapStrike();
        Vec3 to = target.position().subtract(guard.position());
        Vec3 flat = new Vec3(to.x, 0.0, to.z);
        double length = Math.max(0.5, flat.length());
        // Arc rather than a dash: up as well as across, so it is a leap the
        // eye can follow and not a teleport with a sound on it.
        Vec3 launch = flat.scale(0.42 / length * Math.min(length, GuardRank.LEAP_MAX))
            .add(0.0, 0.52, 0.0);
        guard.setDeltaMovement(launch);
        guard.hasImpulse = true;
        if (guard.level() instanceof ServerLevel level) {
            level.playSound(null, guard.blockPosition(), ModSounds.LEAP_SLAM.get(),
                SoundSource.HOSTILE, 0.5F, 1.35F);
        }
    }

    @Override
    public void stop() {
        // Two roads here: normally tick() has already resolved the leap and
        // cleared `airborne` — but canContinueToUse() can also expire with
        // the guard STILL in the air (40 ticks without touching down: leapt
        // off a ledge into a long fall, snagged mid-arc). That case used to
        // fizzle silently with the full cooldown burned; resolve it instead,
        // so the signature move is never punished for the terrain.
        if (airborne) {
            resolveFizzle();
        }
        target = null;
    }

    @Override
    public void tick() {
        airTicks++;
        // Land after the clip has actually left the ground, so a guard who
        // starts on a slope cannot resolve the blow on the first tick.
        if (airTicks <= 4) {
            return;
        }
        if (guard.onGround()) {
            // The clean landing: the full blow, and the full cooldown.
            land(1.0F);
            cooldown = GuardRank.LEAP_COOLDOWN;
            airborne = false;
        } else if (guard.isInWater() || guard.isInLava()) {
            // Liquid never fires onGround(), so without this branch a leap
            // into the moat hangs "airborne" until the timeout with nothing
            // to show for it.
            resolveFizzle();
        }
    }

    /**
     * A leap that did not come down clean on solid ground. Close enough to
     * the mark, the blow still lands at half strength — the guard arrived,
     * just badly — for the ordinary full cooldown. Wide of it, no blow, but
     * only HALF the cooldown is kept: the miss was the terrain's fault, not
     * the guard's, and the move the whole rank is named for must not be
     * shelved for ten seconds every time a raider stands near water or a
     * ledge.
     */
    private void resolveFizzle() {
        if (target != null && target.isAlive()
            && guard.distanceTo(target) <= FIZZLE_HIT_RANGE) {
            land(0.5F);
            cooldown = GuardRank.LEAP_COOLDOWN;
        } else {
            cooldown = GuardRank.LEAP_COOLDOWN / 2;
        }
        airborne = false;
    }

    /**
     * The blow, resolved where the guard actually came down.
     *
     * @param scale 1.0 for a clean landing; 0.5 for a fizzle that still
     *              arrived within {@link #FIZZLE_HIT_RANGE} of the target
     */
    private void land(float scale) {
        if (!(guard.level() instanceof ServerLevel level)) {
            return;
        }
        float base = (4.0F + GuardRank.of(guard).ordinal()) * scale;
        DamageSource source = level.damageSources().mobAttack(guard);
        AABB blast = guard.getBoundingBox().inflate(GuardRank.LEAP_RADIUS);
        boolean hitAnything = false;
        for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, blast)) {
            if (victim == guard || victim instanceof SettlerEntity) {
                continue;  // never your own people
            }
            // Splash is HOSTILE-ONLY: the marked target and raiders take the
            // slam; a pen of cows beside the landing does not.
            if (victim != target && !(victim instanceof RaiderEntity)) {
                continue;
            }
            if (!guard.canAttack(victim)) {
                continue;
            }
            boolean primary = victim == target;
            victim.hurt(source, primary ? base : base * GuardRank.CLEAVE_SHARE);
            Vec3 push = victim.position().subtract(guard.position()).normalize().scale(0.45);
            victim.push(push.x, 0.32, push.z);
            hitAnything = true;
        }
        if (hitAnything) {
            // A leap that connected is combat, and combat is the fast lane
            // up the rank ladder (GuardRank.TRAIN_COMBAT, 5x the patrol
            // drill) — scaled with the landing itself, so a half-strength
            // fizzle teaches half as much.
            guard.train(Attribute.STRENGTH, GuardRank.TRAIN_COMBAT * scale);
        }
        level.playSound(null, guard.blockPosition(), ModSounds.LEAP_SLAM.get(),
            SoundSource.HOSTILE, 1.0F, hitAnything ? 0.92F : 1.05F);
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.CRIT,
            guard.getX(), guard.getY() + 0.1, guard.getZ(), 14, 0.6, 0.1, 0.6, 0.05);
    }
}
