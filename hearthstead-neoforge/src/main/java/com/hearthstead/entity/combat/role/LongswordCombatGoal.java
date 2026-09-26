package com.hearthstead.entity.combat.role;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.registry.RoleItems;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The Longswordsman (plan/BATTLE-ROLES.md §2): a two-handed blade with a
 * wide cleave that catches up to three enemies (each at most once per
 * swing), a heavy overhead, and a half-sword pommel strike that breaks a
 * raised guard. No shield, slower on its feet, and it takes extra damage from
 * arrows (both applied by {@link RoleWorld}).
 */
public class LongswordCombatGoal extends RoleMeleeGoal {
    private long halfSwordReadyAt = Long.MIN_VALUE;
    private int guardBreaks;

    public LongswordCombatGoal(SettlerEntity settler) {
        super(settler, Profession.LONGSWORDSMAN);
    }

    @Override
    protected boolean holdsWeapon() {
        return RoleItems.isLongsword(settler.getMainHandItem());
    }

    @Override
    protected double engageReachScale() {
        return RoleMove.LONGSWORD_HALF_SWORD.reachScale();
    }

    @Override
    @Nullable
    protected RoleMove choose(ServerLevel level, LivingEntity target, long now) {
        boolean open = RoleCombat.guardBroken(target)
            || target instanceof RaiderEntity raider
                && (raider.isStaggered() || raider.isRecoveringFromWhiff());
        RoleCombatRules.SwordSituation s = new RoleCombatRules.SwordSituation(
            RoleCombat.guarded(target), now >= halfSwordReadyAt, open,
            RoleCombat.strong(target), cleaveCandidates(level, target, RoleCombat.yawTo(settler, target)).size());
        return RoleCombatRules.chooseLongswordMove(s, settler.getRandom().nextDouble());
    }

    @Override
    protected void onBegin(ServerLevel level, RoleMove move, LivingEntity target, long now) {
        if (move == RoleMove.LONGSWORD_HALF_SWORD) {
            halfSwordReadyAt = now + move.cooldownTicks() + move.lengthTicks();
        }
    }

    /** Enemies a cleave locked at {@code swingYaw} would catch, primary first. */
    private List<LivingEntity> cleaveCandidates(ServerLevel level, LivingEntity primary,
                                                float swingYaw) {
        RoleMove move = RoleMove.LONGSWORD_CLEAVE;
        double reach = RoleCombatRules.reach(settler.getBbWidth(), 0.6D, move.reachScale());
        List<RoleCombatRules.Candidate<UUID>> candidates = new ArrayList<>();
        List<LivingEntity> byId = new ArrayList<>();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class,
                settler.getBoundingBox().inflate(reach + 1.0D, 1.5D, reach + 1.0D))) {
            if (e == settler || !RoleCombat.isAuthorizedHostile(settler, e)) {
                continue;
            }
            double r = RoleCombatRules.reach(settler.getBbWidth(), e.getBbWidth(), move.reachScale());
            double d = RoleCombat.horizontalDistSqr(settler, e);
            if (d > r * r) {
                continue;
            }
            candidates.add(new RoleCombatRules.Candidate<>(e.getUUID(), d,
                RoleCombat.yawTo(settler, e), e == primary));
            byId.add(e);
        }
        List<UUID> chosen = RoleCombatRules.cleaveTargets(candidates, swingYaw,
            move.arcHalfDegrees(), Double.MAX_VALUE, move.maxTargets());
        List<LivingEntity> out = new ArrayList<>();
        for (UUID id : chosen) {
            for (LivingEntity e : byId) {
                if (e.getUUID().equals(id)) {
                    out.add(e);
                    break;
                }
            }
        }
        return out;
    }

    @Override
    protected List<LivingEntity> contact(ServerLevel level, RoleMove move, LivingEntity target,
                                         float swingYaw, int contactIndex, long now) {
        List<LivingEntity> landed = new ArrayList<>();
        if (move == RoleMove.LONGSWORD_CLEAVE) {
            // The hit set is fixed here, once: every enemy in it at most once.
            List<LivingEntity> set = cleaveCandidates(level, target, swingYaw);
            if (set.isEmpty() || set.get(0) != target) {
                set.remove(target);
                set.add(0, target);
            }
            for (int i = 0; i < Math.min(set.size(), move.maxTargets()); i++) {
                LivingEntity e = set.get(i);
                double share = i == 0 ? 1.0D : move.secondaryShare();
                if (RoleCombat.strike(settler, e, RoleCombat.blowDamage(settler, move, share), false)) {
                    RoleCombat.knockback(settler, e, move.knockback(), swingYaw);
                    landed.add(e);
                }
            }
            if (!landed.isEmpty()) {
                double sin = Mth.sin(swingYaw * Mth.DEG_TO_RAD);
                double cos = Mth.cos(swingYaw * Mth.DEG_TO_RAD);
                level.sendParticles(ParticleTypes.SWEEP_ATTACK, settler.getX() - sin * 1.2D,
                    settler.getY(0.6D), settler.getZ() + cos * 1.2D, 1, 0, 0, 0, 0);
                level.playSound(null, target.blockPosition(), ModSounds.BLADE_HIT.get(),
                    SoundSource.NEUTRAL, 0.85F, 0.9F);
            }
            return landed;
        }
        if (move == RoleMove.LONGSWORD_HALF_SWORD && RoleCombat.guarded(target)) {
            RoleCombat.breakGuard(target, RoleMove.GUARD_BREAK_TICKS);
            guardBreaks++;
        }
        if (RoleCombat.strike(settler, target, RoleCombat.blowDamage(settler, move, 1.0D),
                move == RoleMove.LONGSWORD_HALF_SWORD)) {
            RoleCombat.knockback(settler, target, move.knockback(), swingYaw);
            RoleCombat.stagger(target, move.staggerTicks());
            level.playSound(null, target.blockPosition(), ModSounds.COMBAT_HEAVY_IMPACT.get(),
                SoundSource.NEUTRAL, 0.9F, move == RoleMove.LONGSWORD_HEAVY ? 0.75F : 1.1F);
            landed.add(target);
        }
        return landed;
    }

    @Override
    protected void playWhoosh(ServerLevel level, RoleMove move) {
        if (move == RoleMove.LONGSWORD_CLEAVE) {
            level.playSound(null, settler.blockPosition(), RoleItems.LONGSWORD_CLEAVE_SOUND.get(),
                SoundSource.NEUTRAL, 0.7F, 0.95F);
        } else if (move == RoleMove.LONGSWORD_HEAVY) {
            level.playSound(null, settler.blockPosition(), ModSounds.COMBAT_SWING_HEAVY.get(),
                SoundSource.NEUTRAL, 0.85F, 0.7F);
        } else {
            level.playSound(null, settler.blockPosition(), ModSounds.COMBAT_BASH_SWING.get(),
                SoundSource.NEUTRAL, 0.5F, 0.9F);
        }
    }

    public int guardBreaks() {
        return guardBreaks;
    }
}
