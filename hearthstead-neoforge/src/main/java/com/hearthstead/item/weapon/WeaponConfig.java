package com.hearthstead.item.weapon;

import com.hearthstead.HearthsteadServerConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * The {@code [weapons]} section of the server config (weapons lane, 26 Sep): the strength of the
 * captain weapons' special traits. Base damage / speed / reach are item attributes (see
 * {@link WeaponType} and plan/WEAPONS.md); only the traits are tunable at runtime. Every getter
 * is safe before the server config has loaded (it returns the default).
 */
public final class WeaponConfig {
    public static final double DEFAULT_SHRED_PER_ARMOR = 0.3D;
    public static final double DEFAULT_SHRED_MAX = 4.0D;
    public static final double DEFAULT_BRUTE_BONUS = 2.0D;
    public static final double DEFAULT_CHARGE_MULTIPLIER = 1.35D;
    public static final double DEFAULT_CHARGE_SPEED = 0.08D;
    public static final double DEFAULT_STUN_CHANCE = 0.25D;
    public static final int DEFAULT_STUN_TICKS = 20;

    private static ModConfigSpec.DoubleValue shredPerArmor;
    private static ModConfigSpec.DoubleValue shredMax;
    private static ModConfigSpec.DoubleValue bruteBonus;
    private static ModConfigSpec.DoubleValue chargeMultiplier;
    private static ModConfigSpec.DoubleValue chargeSpeed;
    private static ModConfigSpec.DoubleValue stunChance;
    private static ModConfigSpec.IntValue stunTicks;

    private WeaponConfig() {
    }

    /** Called once from {@link HearthsteadServerConfig}'s static builder. */
    public static void define(ModConfigSpec.Builder builder) {
        builder.comment("Captain weapons (short sword, double axe, halberd, warhammer): trait strength.",
            "Damage, speed and reach per type and tier are listed in plan/WEAPONS.md.").push("weapons");
        shredPerArmor = builder.comment("Double axe: bonus damage per armour point of the target.")
            .defineInRange("doubleAxeShredPerArmor", DEFAULT_SHRED_PER_ARMOR, 0.0D, 2.0D);
        shredMax = builder.comment("Double axe: the armour-shred bonus never exceeds this.")
            .defineInRange("doubleAxeShredMax", DEFAULT_SHRED_MAX, 0.0D, 20.0D);
        bruteBonus = builder.comment("Double axe: extra damage against raid Brutes.")
            .defineInRange("doubleAxeBruteBonus", DEFAULT_BRUTE_BONUS, 0.0D, 20.0D);
        chargeMultiplier = builder.comment("Halberd: damage multiplier against a foe running at the wielder.")
            .defineInRange("halberdChargeMultiplier", DEFAULT_CHARGE_MULTIPLIER, 1.0D, 3.0D);
        chargeSpeed = builder.comment("Halberd: closing speed (blocks per tick) that counts as a charge.")
            .defineInRange("halberdChargeSpeed", DEFAULT_CHARGE_SPEED, 0.01D, 1.0D);
        stunChance = builder.comment("Warhammer: chance per hit to stun (heavy slowness).")
            .defineInRange("warhammerStunChance", DEFAULT_STUN_CHANCE, 0.0D, 1.0D);
        stunTicks = builder.comment("Warhammer: stun length in ticks.")
            .defineInRange("warhammerStunTicks", DEFAULT_STUN_TICKS, 1, 200);
        builder.pop();
    }

    public static double shredPerArmor() {
        return get(shredPerArmor, DEFAULT_SHRED_PER_ARMOR);
    }

    public static double shredMax() {
        return get(shredMax, DEFAULT_SHRED_MAX);
    }

    public static double bruteBonus() {
        return get(bruteBonus, DEFAULT_BRUTE_BONUS);
    }

    public static double chargeMultiplier() {
        return get(chargeMultiplier, DEFAULT_CHARGE_MULTIPLIER);
    }

    public static double chargeSpeed() {
        return get(chargeSpeed, DEFAULT_CHARGE_SPEED);
    }

    public static double stunChance() {
        return get(stunChance, DEFAULT_STUN_CHANCE);
    }

    public static int stunTicks() {
        if (stunTicks == null || !HearthsteadServerConfig.SPEC.isLoaded()) {
            return DEFAULT_STUN_TICKS;
        }
        try {
            return stunTicks.get();
        } catch (IllegalStateException notLoaded) {
            return DEFAULT_STUN_TICKS;
        }
    }

    private static double get(ModConfigSpec.DoubleValue value, double fallback) {
        if (value == null || !HearthsteadServerConfig.SPEC.isLoaded()) {
            return fallback;
        }
        try {
            return value.get();
        } catch (IllegalStateException notLoaded) {
            return fallback;
        }
    }
}
