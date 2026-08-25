package com.hearthstead.entity.ai;

import com.hearthstead.entity.GuardRank;
import com.hearthstead.entity.Profession;
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
 * damage stops being a special move and starts being the only move.
 */
public class GuardLeapGoal extends Goal {

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
        return airborne && airTicks < 40;
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
        airborne = false;
        target = null;
        cooldown = GuardRank.LEAP_COOLDOWN;
    }

    @Override
    public void tick() {
        airTicks++;
        // Land after the clip has actually left the ground, so a guard who
        // starts on a slope cannot resolve the blow on the first tick.
        if (airTicks > 4 && guard.onGround()) {
            land();
            airborne = false;
        }
    }

    /** The blow, resolved where the guard actually came down. */
    private void land() {
        if (!(guard.level() instanceof ServerLevel level)) {
            return;
        }
        float base = 4.0F + GuardRank.of(guard).ordinal();
        DamageSource source = level.damageSources().mobAttack(guard);
        AABB blast = guard.getBoundingBox().inflate(GuardRank.LEAP_RADIUS);
        boolean hitAnything = false;
        for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, blast)) {
            if (victim == guard || victim instanceof SettlerEntity) {
                continue;  // never your own people
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
        level.playSound(null, guard.blockPosition(), ModSounds.LEAP_SLAM.get(),
            SoundSource.HOSTILE, 1.0F, hitAnything ? 0.92F : 1.05F);
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.CRIT,
            guard.getX(), guard.getY() + 0.1, guard.getZ(), 14, 0.6, 0.1, 0.6, 0.05);
    }
}
