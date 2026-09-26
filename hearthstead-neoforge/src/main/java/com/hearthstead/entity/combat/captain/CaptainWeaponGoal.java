package com.hearthstead.entity.combat.captain;

import com.hearthstead.entity.GuardRank;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.GuardMeleeGoal;
import com.hearthstead.entity.combat.role.RoleCombat;
import com.hearthstead.entity.combat.role.RoleCombatRules;
import com.hearthstead.registry.ModSounds;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * The hero Captain's plain attacks for the loadouts the Guard moveset cannot
 * swing (great axe, halberd, warhammer, bow). Sword &amp; Shield and Dual Swords
 * keep the Guard's own GuardMeleeGoal. Same two-phase rule as every melee
 * goal: the swing is broadcast at wind-up, damage lands once on the contact
 * tick if the target is still an authorized enemy in reach; the bow shoots
 * only with a real arrow in the offhand.
 */
public class CaptainWeaponGoal extends Goal {
    public static final int SWING_LENGTH = 18;
    public static final int SWING_HIT = 9;
    public static final int SWING_COOLDOWN = 12;
    public static final int SHOT_CADENCE = 30;
    public static final float SHOT_DAMAGE = 6.0F;
    public static final double BOW_MIN = 6.0D;
    public static final double BOW_MAX = 18.0D;
    /** The captain bow clips loose on tick 14 (0.70 s), like the Hunter's draw. */
    public static final int BOW_RELEASE_TICK = 14;

    private final SettlerEntity captain;
    private long nextSwing = Long.MIN_VALUE;
    private long contactAt = Long.MIN_VALUE;
    @Nullable private LivingEntity swingTarget;
    private float swingYaw;
    private long repathAt = Long.MIN_VALUE;
    private int landed;

    public CaptainWeaponGoal(SettlerEntity captain) {
        this.captain = captain;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Nullable
    private CaptainLoadout loadout() {
        if (!CaptainStatus.isHero(captain)) {
            return null;
        }
        CaptainLoadout l = CaptainKit.heldLoadout(captain);
        return l == null || l == CaptainLoadout.SWORD_SHIELD || l == CaptainLoadout.DUAL_SWORDS ? null : l;
    }

    @Override
    public boolean canUse() {
        return loadout() != null && RoleCombat.isAuthorizedHostile(captain, captain.getTarget());
    }

    @Override
    public boolean canContinueToUse() {
        return loadout() != null && (contactAt != Long.MIN_VALUE
            || RoleCombat.isAuthorizedHostile(captain, captain.getTarget()));
    }

    @Override
    public void start() {
        captain.setActivity(SettlerActivity.COMBAT);
    }

    @Override
    public void stop() {
        contactAt = Long.MIN_VALUE;
        swingTarget = null;
        captain.getNavigation().stop();
        captain.setActivity(SettlerActivity.IDLE);
    }

    /** Owner rule: timing and reach follow the held weapon TYPE (WeaponClass). */
    private com.hearthstead.entity.combat.WeaponClass weapon() {
        return com.hearthstead.entity.combat.WeaponClass.of(captain.getMainHandItem());
    }

    private double reachScale(CaptainLoadout l) {
        double r = weapon().reachScale();
        return r > 0.0D ? r : 1.2D;
    }

    private double multiplier(CaptainLoadout l) {
        return switch (l) {
            case GREAT_AXE -> 1.3D;
            case WARHAMMER -> 1.4D;
            case HALBERD -> 1.1D;
            default -> 1.0D;
        };
    }

    @Override
    public void tick() {
        ServerLevel level = (ServerLevel) captain.level();
        long now = level.getGameTime();
        CaptainLoadout l = loadout();
        LivingEntity target = captain.getTarget();
        if (l == null || captain.isCombatStaggered()) {
            contactAt = Long.MIN_VALUE;
            return;
        }
        if (contactAt != Long.MIN_VALUE) {
            if (now >= contactAt) {
                LivingEntity t = swingTarget;
                contactAt = Long.MIN_VALUE;
                swingTarget = null;
                if (l == CaptainLoadout.BOW) {
                    release(level, t);
                    return;
                }
                if (t != null && t == target && RoleCombat.isAuthorizedHostile(captain, t)
                    && RoleCombatRules.inReach(RoleCombat.horizontalDistSqr(captain, t), captain.getBbWidth(),
                        t.getBbWidth(), reachScale(l))
                    && RoleCombatRules.inArc(swingYaw, RoleCombat.yawTo(captain, t), 60.0F)
                    && RoleCombat.strike(captain, t, blow(multiplier(l)), false)) {
                    landed++;
                    level.playSound(null, t.blockPosition(), ModSounds.COMBAT_HEAVY_IMPACT.get(),
                        SoundSource.NEUTRAL, 0.9F, 0.85F);
                }
            }
            return;
        }
        if (target == null || !RoleCombat.isAuthorizedHostile(captain, target)) {
            return;
        }
        captain.getLookControl().setLookAt(target, 30.0F, 30.0F);
        double dist = Math.sqrt(RoleCombat.horizontalDistSqr(captain, target));
        if (l == CaptainLoadout.BOW) {
            if ((dist > BOW_MAX || !captain.getSensing().hasLineOfSight(target)) && now >= repathAt) {
                captain.getNavigation().moveTo(target, 1.0D);
                repathAt = now + 10;
            } else if (dist < BOW_MIN && now >= repathAt) {
                Vec3 away = captain.position().subtract(target.position()).normalize().scale(5.0D);
                captain.getNavigation().moveTo(captain.getX() + away.x, captain.getY(), captain.getZ() + away.z, 1.1D);
                repathAt = now + 10;
            } else if (dist <= BOW_MAX) {
                captain.getNavigation().stop();
            }
            if (now >= nextSwing && dist <= BOW_MAX && captain.getSensing().hasLineOfSight(target)
                && captain.getOffhandItem().is(Items.ARROW)) {
                nextSwing = now + SHOT_CADENCE;
                swingTarget = target;
                contactAt = now + BOW_RELEASE_TICK;      // the draw: the arrow flies on the release
                level.broadcastEntityEvent(captain, SettlerEntity.EV_ARCHER_LOOSE);
            }
            return;
        }
        double reach = RoleCombatRules.reach(captain.getBbWidth(), target.getBbWidth(), reachScale(l));
        if (dist > reach * 0.85D) {
            if (now >= repathAt) {
                captain.getNavigation().moveTo(target, 1.15D);
                repathAt = now + 10;
            }
        } else {
            captain.getNavigation().stop();
        }
        if (now >= nextSwing && dist <= reach && captain.getSensing().hasLineOfSight(target)) {
            swingYaw = RoleCombat.yawTo(captain, target);
            captain.setYRot(swingYaw);
            captain.yBodyRot = swingYaw;
            swingTarget = target;
            contactAt = now + weapon().contactTick();
            nextSwing = now + weapon().swingLength() + SWING_COOLDOWN;
            level.broadcastEntityEvent(captain, SettlerEntity.EV_GUARD_HEAVY);
            level.playSound(null, captain.blockPosition(), ModSounds.COMBAT_SWING_HEAVY.get(),
                SoundSource.NEUTRAL, 0.8F, 0.75F);
        }
    }

    /** One real arrow, one hitscan shot at a still-valid target in sight. */
    private void release(ServerLevel level, @Nullable LivingEntity t) {
        if (t == null || !RoleCombat.isAuthorizedHostile(captain, t) || !captain.getOffhandItem().is(Items.ARROW)
            || !captain.getSensing().hasLineOfSight(t)
            || RoleCombat.horizontalDistSqr(captain, t) > BOW_MAX * BOW_MAX) {
            return;
        }
        captain.getOffhandItem().shrink(1);
        level.playSound(null, captain.blockPosition(), SoundEvents.ARROW_SHOOT, SoundSource.NEUTRAL, 1.0F, 1.0F);
        Vec3 from = captain.getEyePosition();
        Vec3 to = t.getEyePosition();
        for (int i = 0; i <= 12; i++) {
            Vec3 p = from.lerp(to, i / 12.0D);
            level.sendParticles(ParticleTypes.CRIT, p.x, p.y, p.z, 1, 0, 0, 0, 0);
        }
        if (RoleCombat.strike(captain, t, SHOT_DAMAGE, false)) {
            landed++;
        }
    }

    private float blow(double mult) {
        double attack = captain.getAttributeValue(Attributes.ATTACK_DAMAGE);
        double edge = GuardRank.MELEE_EDGE_PER_RANK * GuardRank.of(captain).ordinal();
        return (float) ((attack + GuardMeleeGoal.GUARD_TRAINING_DAMAGE + edge) * mult);
    }

    public int landed() {
        return landed;
    }
}
