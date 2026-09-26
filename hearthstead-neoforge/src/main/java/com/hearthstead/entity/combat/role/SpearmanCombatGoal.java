package com.hearthstead.entity.combat.role;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.registry.RoleItems;
import com.hearthstead.settlement.guard.FieldOrders;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The Spearman (plan/BATTLE-ROLES.md §1): 1.5x reach, a narrow thrust, and a
 * brace that stops the first charger dead.
 *
 * <p><b>Brace.</b> A spearman is braced while standing at its slot in a line
 * order ({@link FieldOrders#bracing}) or, with no orders, for a moment after
 * it sees an enemy charging within {@link RoleCombatRules#AUTO_BRACE_RADIUS}.
 * Braced, it plants and does not chase; a charging target entering reach
 * takes a {@link RoleMove#SPEAR_BRACE_STRIKE}. A flank hit (see
 * {@link RoleWorld#onIncomingDamage}) breaks the brace for two seconds.
 */
public class SpearmanCombatGoal extends RoleMeleeGoal {
    private long autoBraceUntil = Long.MIN_VALUE;
    private long braceStrikeReadyAt = Long.MIN_VALUE;
    private long forcedBraceUntil = Long.MIN_VALUE;
    private int braceStrikes;

    public SpearmanCombatGoal(SettlerEntity settler) {
        super(settler, Profession.SPEARMAN);
    }

    @Override
    protected boolean holdsWeapon() {
        return RoleItems.isSpear(settler.getMainHandItem());
    }

    @Override
    protected double engageReachScale() {
        return RoleMove.SPEAR_THRUST.reachScale();
    }

    public boolean braced(ServerLevel level, long now) {
        if (RoleWorld.braceBrokenUntil(level, settler.getUUID()) > now) {
            return false;
        }
        return now < forcedBraceUntil || now < autoBraceUntil || FieldOrders.bracing(settler);
    }

    @Override
    protected void preTick(ServerLevel level, long now) {
        if (now % 5 != 0) {
            return;
        }
        LivingEntity charger = nearestCharger(level);
        if (charger != null) {
            autoBraceUntil = now + RoleCombatRules.BRACE_HOLD_TICKS;
            if (settler.getTarget() == null) {
                settler.setTarget(charger);
            }
        }
    }

    @Override
    protected boolean holdGround(ServerLevel level, long now) {
        return braced(level, now);
    }

    @Nullable
    private LivingEntity nearestCharger(ServerLevel level) {
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class,
                settler.getBoundingBox().inflate(RoleCombatRules.AUTO_BRACE_RADIUS, 3.0D,
                    RoleCombatRules.AUTO_BRACE_RADIUS))) {
            if (!RoleCombat.isAuthorizedHostile(settler, e) || !charging(e)) {
                continue;
            }
            double d = settler.distanceToSqr(e);
            if (d < bestD) {
                best = e;
                bestD = d;
            }
        }
        return best;
    }

    /** Is {@code e} closing on this spearman fast enough to count as a charge? */
    public boolean charging(LivingEntity e) {
        Vec3 v = e.getDeltaMovement();
        double closing = RoleCombatRules.closingSpeed(settler.getX(), settler.getZ(),
            e.getX(), e.getZ(), v.x, v.z);
        boolean aimed = !(e instanceof Mob mob) || mob.getTarget() == null
            || mob.getTarget() == settler
            || mob.getTarget().distanceToSqr(settler) < 16.0D;
        return aimed && RoleCombatRules.isCharging(closing, RoleCombat.knownCharger(e));
    }

    @Override
    @Nullable
    protected RoleMove choose(ServerLevel level, LivingEntity target, long now) {
        RoleCombatRules.SpearSituation s = new RoleCombatRules.SpearSituation(
            braced(level, now), charging(target), now >= braceStrikeReadyAt, rankOrdinal());
        return RoleCombatRules.chooseSpearMove(s, settler.getRandom().nextDouble());
    }

    @Override
    protected void onBegin(ServerLevel level, RoleMove move, LivingEntity target, long now) {
        if (move == RoleMove.SPEAR_BRACE_STRIKE) {
            braceStrikeReadyAt = now + move.cooldownTicks() + move.lengthTicks();
        }
    }

    @Override
    protected List<LivingEntity> contact(ServerLevel level, RoleMove move, LivingEntity target,
                                         float swingYaw, int contactIndex, long now) {
        // Tech tree (Pike Square): the brace strike hits 50% harder.
        float damage = RoleCombat.blowDamage(settler, move, move == RoleMove.SPEAR_BRACE_STRIKE
            ? com.hearthstead.settlement.techtree.effects.WatchEffects.braceStrikeShare(settler) : 1.0D);
        // The double thrust's second poke is its own ticketed contact six
        // ticks after the first, inside vanilla's hurt window.
        boolean secondPoke = move == RoleMove.SPEAR_DOUBLE_THRUST && contactIndex > 0;
        if (!RoleCombat.strike(settler, target, damage, secondPoke)) {
            return List.of();
        }
        RoleCombat.knockback(settler, target, move.knockback(), swingYaw);
        level.playSound(null, target.blockPosition(), ModSounds.BLADE_HIT.get(),
            SoundSource.NEUTRAL, 0.8F, 1.1F + settler.getRandom().nextFloat() * 0.1F);
        if (move == RoleMove.SPEAR_BRACE_STRIKE) {
            RoleCombat.stagger(target, move.staggerTicks());
            braceStrikes++;
            level.playSound(null, target.blockPosition(), ModSounds.COMBAT_HEAVY_IMPACT.get(),
                SoundSource.NEUTRAL, 0.9F, 0.85F);
            level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY(0.6D), target.getZ(),
                10, 0.3D, 0.3D, 0.3D, 0.2D);
        }
        return List.of(target);
    }

    @Override
    protected void playWhoosh(ServerLevel level, RoleMove move) {
        level.playSound(null, settler.blockPosition(), RoleItems.SPEAR_THRUST_SOUND.get(),
            SoundSource.NEUTRAL, 0.5F, move == RoleMove.SPEAR_BRACE_STRIKE ? 0.9F : 1.25F);
    }

    // --------------------------------------------- test seams / evidence ---

    /** Deterministic QA: stand braced for {@code ticks} regardless of orders. */
    public void forceBrace(long now, int ticks) {
        forcedBraceUntil = now + ticks;
    }

    public int braceStrikes() {
        return braceStrikes;
    }
}
