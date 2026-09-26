package com.hearthstead.entity.combat.role;

import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.GuardRank;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.GuardMeleeGoal;
import com.hearthstead.entity.ai.RaiderLootGoal;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.guard.BannerTeams;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Runtime helpers shared by the battle-role goals: who counts as an enemy of
 * this settlement, how hard one blow lands, and the impact side effects. The
 * rules mirror {@link GuardMeleeGoal} (settlement ring, own-raid only,
 * banner-team orders) without touching the Guard's own goal or its
 * guard-only contact ledger on {@link SettlerEntity}.
 */
public final class RoleCombat {
    /** Extra reach past the claim within which a role may engage. */
    public static final double DEFENDED_MARGIN = 8.0D;

    /** GameTest seam; null = read the server config. */
    @Nullable private static Boolean enabledOverride;

    private RoleCombat() {
    }

    /**
     * Owner kill-switch ({@code [features] battleRoles}). When off, every
     * role goal, the role damage rules, role attribute modifiers and the
     * Healer's revive hook stand down; Guards and Archers are untouched.
     */
    public static boolean enabled() {
        Boolean o = enabledOverride;
        return o != null ? o : com.hearthstead.HearthsteadServerConfig.battleRolesEnabled();
    }

    public static void overrideEnabledForTests(@Nullable Boolean value) {
        enabledOverride = value;
    }

    /**
     * Added to a raider skirmisher's "exposure" score for a settler of this
     * trade (base: Guard 0, everyone else 3). The two blades are as hard a
     * target as a Guard (-3 cancels the base); the Rune Mage is the juiciest
     * target on the field and the Healer close behind.
     */
    public static double skirmisherExposureBonus(com.hearthstead.entity.Profession p) {
        if (!enabled()) {
            return 0.0D;
        }
        return switch (p) {
            case SPEARMAN, LONGSWORDSMAN -> -3.0D;
            case RUNE_MAGE -> 0.75D;
            case HEALER -> 0.5D;
            default -> 0.0D;
        };
    }

    /** One of the four battle roles (plan/BATTLE-ROLES.md). */
    public static boolean isRole(com.hearthstead.entity.Profession p) {
        return p == com.hearthstead.entity.Profession.SPEARMAN
            || p == com.hearthstead.entity.Profession.LONGSWORDSMAN
            || p == com.hearthstead.entity.Profession.HEALER
            || p == com.hearthstead.entity.Profession.RUNE_MAGE;
    }

    /** A live, server-side enemy of THIS settlement (ring + own raid + orders). */
    public static boolean isAuthorizedHostile(SettlerEntity settler, @Nullable LivingEntity target) {
        if (!(settler.level() instanceof ServerLevel level)
            || target == null || target.level() != level
            || !target.isAlive() || target.isRemoved()
            || !(target instanceof Enemy)
            || target instanceof Player
            || !settler.canAttack(target)) {
            return false;
        }
        Settlement settlement = settler.settlement();
        if (settlement == null) {
            return false;
        }
        if (BannerTeams.active(settler) != null && !BannerTeams.allowsTarget(settler, target)) {
            return false;
        }
        return hostileTo(settlement, target);
    }

    /** Settlement-scope hostility without a specific settler (frost zones, fireballs). */
    public static boolean hostileTo(@Nullable Settlement settlement, @Nullable LivingEntity target) {
        if (settlement == null || target == null || !target.isAlive() || target.isRemoved()
            || !(target instanceof Enemy) || target instanceof Player) {
            return false;
        }
        double radius = settlement.radius + DEFENDED_MARGIN;
        if (target instanceof RaiderEntity raider && raider.lootCount() > 0) {
            radius = settlement.radius + RaiderLootGoal.ESCAPE_MARGIN;
        }
        if (target.blockPosition().distSqr(settlement.center) > radius * radius) {
            return false;
        }
        return !(target instanceof RaiderEntity raider)
            || raider.settlementId() == null
            || settlement.id.equals(raider.settlementId());
    }

    /** An ally the mage and healer look after: this settlement's people and players. */
    public static boolean isAlly(@Nullable Settlement settlement, @Nullable LivingEntity other) {
        if (settlement == null || other == null || !other.isAlive()) {
            return false;
        }
        if (other instanceof SettlerEntity s) {
            return settlement.id.equals(s.getSettlementId()) && s.isBound();
        }
        if (other instanceof Player p) {
            return !p.isSpectator()
                && p.blockPosition().distSqr(settlement.center)
                    <= (double) (settlement.radius + 32) * (settlement.radius + 32);
        }
        return false;
    }

    /** Charges readily: brutes, wolves, ravagers, horses (later cavalry). */
    public static boolean knownCharger(LivingEntity target) {
        return target instanceof RaiderEntity raider && raider.variant() == RaiderEntity.Variant.BRUTE
            || target.getType() == EntityType.WOLF
            || target.getType() == EntityType.RAVAGER
            || target instanceof net.minecraft.world.entity.animal.horse.AbstractHorse;
    }

    /** A raised shield or a shield-bearing raider (raid lane's shieldbearers). */
    public static boolean guarded(LivingEntity target) {
        return target.isBlocking()
            || target instanceof RaiderEntity raider && raider.getOffhandItem().is(Items.SHIELD)
                && !guardBroken(target);
    }

    public static boolean guardBroken(LivingEntity target) {
        return target.level() instanceof ServerLevel level
            && target.getPersistentData().getLong(RoleWorld.GUARD_BROKEN_KEY) > level.getGameTime();
    }

    public static boolean strong(LivingEntity target) {
        if (target instanceof RaiderEntity raider) {
            return raider.isCaptain() || raider.variant() == RaiderEntity.Variant.BRUTE;
        }
        return target.getArmorValue() >= GuardMeleeGoal.STRONG_ARMOR
            || target.getMaxHealth() >= GuardMeleeGoal.STRONG_MAX_HEALTH;
    }

    /**
     * Damage of one blow: the settler's attack attribute (weapon included),
     * the same bounded training edge Guards get, the rank edge, then the
     * move multiplier and any cleave share.
     */
    public static float blowDamage(SettlerEntity settler, RoleMove move, double share) {
        double attack = settler.getAttributeValue(Attributes.ATTACK_DAMAGE);
        double edge = GuardRank.MELEE_EDGE_PER_RANK * GuardRank.of(settler).ordinal();
        double base = attack + GuardMeleeGoal.GUARD_TRAINING_DAMAGE + edge;
        // Strength: +0..20% melee damage (AttributeRuntime, plan/ATTRIBUTES.md).
        return (float) Math.max(0.0D, base * move.damageMultiplier() * share
            * com.hearthstead.entity.AttributeRuntime.meleeGain(settler));
    }

    /** One damage pass. Returns true if the target actually took it. */
    public static boolean strike(SettlerEntity settler, LivingEntity target, float amount,
                                 boolean ignoreHurtCooldown) {
        if (!(settler.level() instanceof ServerLevel level) || amount <= 0.0F) {
            return false;
        }
        if (ignoreHurtCooldown) {
            target.invulnerableTime = 0;
        }
        boolean hit = target.hurt(level.damageSources().mobAttack(settler), amount);
        if (hit) {
            settler.train(Attribute.STRENGTH, GuardRank.TRAIN_COMBAT);
            settler.setLastHurtMob(target);
        }
        return hit;
    }

    public static void knockback(SettlerEntity settler, LivingEntity target, double strength,
                                 float swingYaw) {
        if (strength <= 0.0D || !target.isAlive()) {
            return;
        }
        double sin = Mth.sin(swingYaw * Mth.DEG_TO_RAD);
        double cos = Mth.cos(swingYaw * Mth.DEG_TO_RAD);
        target.knockback(strength, sin, -cos);
    }

    /** A raider loses its wind-up; any other mob is briefly rooted. */
    public static void stagger(LivingEntity target, int ticks) {
        if (ticks <= 0 || !target.isAlive()) {
            return;
        }
        if (target instanceof RaiderEntity raider) {
            raider.stagger(ticks);
        } else if (target instanceof Mob mob) {
            mob.getNavigation().stop();
            mob.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, ticks, 3, false, false));
        }
    }

    /** Half-sword guard break: a player's shield is disabled, a raider's opened. */
    public static void breakGuard(LivingEntity target, int ticks) {
        if (!(target.level() instanceof ServerLevel level)) {
            return;
        }
        if (target instanceof Player player && player.isBlocking()) {
            player.disableShield();
        } else if (target.isUsingItem()) {
            target.stopUsingItem();
        }
        target.getPersistentData().putLong(RoleWorld.GUARD_BROKEN_KEY, level.getGameTime() + ticks);
    }

    public static float yawTo(LivingEntity from, LivingEntity to) {
        return RoleCombatRules.yawToward(from.getX(), from.getZ(), to.getX(), to.getZ());
    }

    public static double horizontalDistSqr(LivingEntity a, LivingEntity b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    /** Stable identity for de-duplication in pure helpers. */
    public static UUID id(LivingEntity e) {
        return e.getUUID();
    }
}
