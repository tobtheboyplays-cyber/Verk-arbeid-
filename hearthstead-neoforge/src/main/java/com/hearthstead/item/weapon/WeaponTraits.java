package com.hearthstead.item.weapon;

import com.hearthstead.HearthsteadServerConfig;
import com.hearthstead.entity.RaiderEntity;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * The captain weapons' special traits, shared by the player path
 * ({@link CaptainWeaponItem#getAttackDamageBonus}) and the settler path
 * ({@link WeaponEvents}). The maths is split into pure static helpers so JUnit can pin it.
 */
public final class WeaponTraits {
    private WeaponTraits() {
    }

    public static boolean enabled() {
        return HearthsteadServerConfig.captainWeaponsEnabled();
    }

    /** Trait type of a stack, or null when it is not a captain weapon. */
    public static WeaponType typeOf(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof CaptainWeaponItem w ? w.type() : null;
    }

    /** Two-handed captain weapons want an empty offhand (the Captain's loadout enforces it). */
    public static boolean isTwoHanded(ItemStack stack) {
        WeaponType t = typeOf(stack);
        return t != null && t.twoHanded();
    }

    /**
     * Extra damage (added to {@code baseDamage}) a hit with {@code type} deals to {@code target}.
     * Double axe: armour shred (+ per armour point, capped) and a flat bonus vs Brutes. Halberd:
     * a multiplier against a foe closing in on the attacker faster than the charge speed.
     */
    public static float bonus(WeaponType type, LivingEntity attacker, Entity target, float baseDamage) {
        if (type == null || !enabled() || !(target instanceof LivingEntity living)) {
            return 0.0F;
        }
        return switch (type) {
            case DOUBLE_AXE -> shredBonus(living.getArmorValue(), isBrute(living),
                WeaponConfig.shredPerArmor(), WeaponConfig.shredMax(), WeaponConfig.bruteBonus());
            case HALBERD -> attacker != null && isCharging(living, attacker)
                ? chargeBonus(baseDamage, WeaponConfig.chargeMultiplier()) : 0.0F;
            default -> 0.0F;
        };
    }

    public static float shredBonus(int armor, boolean brute, double perArmor, double max, double bruteBonus) {
        double b = Math.min(max, Math.max(0, armor) * perArmor);
        if (brute) {
            b += bruteBonus;
        }
        return (float) b;
    }

    public static float chargeBonus(float baseDamage, double multiplier) {
        return (float) (Math.max(0.0F, baseDamage) * (multiplier - 1.0D));
    }

    /**
     * Horizontal closing speed of {@code target} toward {@code attacker} in blocks per tick
     * (positive = coming at the attacker).
     */
    public static double closingSpeed(double vx, double vz, double tx, double tz, double ax, double az) {
        double dx = ax - tx;
        double dz = az - tz;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-4D) {
            return 0.0D;
        }
        return (vx * dx + vz * dz) / len;
    }

    public static boolean isCharging(LivingEntity target, LivingEntity attacker) {
        Vec3 v = target.getDeltaMovement();
        double closing = closingSpeed(v.x, v.z, target.getX(), target.getZ(), attacker.getX(), attacker.getZ());
        return closing >= WeaponConfig.chargeSpeed() || target.isSprinting() && closing > 0.0D;
    }

    public static boolean isBrute(LivingEntity e) {
        return e instanceof RaiderEntity r && r.variant() == RaiderEntity.Variant.BRUTE;
    }

    /** Warhammer stun: heavy slowness; raiders also stagger. Returns true when it procs. */
    public static boolean rollStun(WeaponType type, LivingEntity target, float roll) {
        if (type != WeaponType.WARHAMMER || !enabled() || roll >= WeaponConfig.stunChance() || !target.isAlive()) {
            return false;
        }
        int ticks = WeaponConfig.stunTicks();
        target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, ticks, 3, false, true));
        if (target instanceof RaiderEntity raider) {
            raider.stagger(ticks);
        }
        return true;
    }
}
