package com.hearthstead.entity.combat.captain;

import com.hearthstead.HearthsteadServerConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * {@code [captain]} in the server config: the hero Captain's numbers
 * (plan/CAPTAIN.md). A hero, not invincible. Safe before the server config has
 * loaded (defaults). The master switch is {@code [features] battleRoles}.
 */
public final class CaptainConfig {
    public static final boolean DEFAULT_ENABLED = true;
    public static final double DEFAULT_RENDER_SCALE = 1.13D;
    public static final double DEFAULT_HEALTH_BONUS = 16.0D;
    public static final double DEFAULT_ARMOR_BONUS = 4.0D;
    public static final double DEFAULT_DAMAGE_BONUS = 2.0D;
    public static final double DEFAULT_KNOCKBACK_RESISTANCE = 0.5D;
    public static final boolean DEFAULT_EXTRA_LOADOUTS = false;

    private static ModConfigSpec.BooleanValue enabled;
    private static ModConfigSpec.DoubleValue renderScale;
    private static ModConfigSpec.DoubleValue healthBonus;
    private static ModConfigSpec.DoubleValue armorBonus;
    private static ModConfigSpec.DoubleValue damageBonus;
    private static ModConfigSpec.DoubleValue knockbackResistance;
    private static ModConfigSpec.BooleanValue extraLoadouts;
    /** Test seam; null = config. */
    private static volatile Boolean enabledOverride;
    private static volatile Boolean extraOverride;

    private CaptainConfig() {
    }

    /** Called once from {@link HearthsteadServerConfig}'s static builder. */
    public static void define(ModConfigSpec.Builder builder) {
        builder.comment("The hero Captain: the top guard at Sergeant+ once Captain's Commission is learned.",
            "Master switch: [features] battleRoles.").push("captain");
        enabled = builder.comment("false = no hero Captain (the title stays, the kit and specials do not).")
            .define("enabled", DEFAULT_ENABLED);
        renderScale = builder.comment("Drawn size of the Captain (the hitbox stays normal so doors and paths work).")
            .defineInRange("renderScale", DEFAULT_RENDER_SCALE, 1.0D, 1.3D);
        healthBonus = builder.comment("Extra max health over a settler's 24.")
            .defineInRange("healthBonus", DEFAULT_HEALTH_BONUS, 0.0D, 40.0D);
        armorBonus = builder.comment("Extra armour points on top of his worn kit.")
            .defineInRange("armorBonus", DEFAULT_ARMOR_BONUS, 0.0D, 10.0D);
        damageBonus = builder.comment("Extra attack damage.")
            .defineInRange("damageBonus", DEFAULT_DAMAGE_BONUS, 0.0D, 8.0D);
        knockbackResistance = builder.comment("Knockback resistance (0..1).")
            .defineInRange("knockbackResistance", DEFAULT_KNOCKBACK_RESISTANCE, 0.0D, 1.0D);
        extraLoadouts = builder.comment("Halberd and Warhammer loadouts (hidden until their clips ship).")
            .define("extraLoadouts", DEFAULT_EXTRA_LOADOUTS);
        builder.pop();
    }

    public static boolean enabled() {
        Boolean o = enabledOverride;
        if (o != null) {
            return o;
        }
        return HearthsteadServerConfig.battleRolesEnabled() && get(enabled, DEFAULT_ENABLED);
    }

    public static void overrideEnabledForTests(Boolean value) {
        enabledOverride = value;
    }

    /** Test seam for the Halberd/Warhammer gate; null = config. */
    public static void overrideExtraLoadoutsForTests(Boolean value) {
        extraOverride = value;
    }

    public static double renderScale() { return get(renderScale, DEFAULT_RENDER_SCALE); }
    public static double healthBonus() { return get(healthBonus, DEFAULT_HEALTH_BONUS); }
    public static double armorBonus() { return get(armorBonus, DEFAULT_ARMOR_BONUS); }
    public static double damageBonus() { return get(damageBonus, DEFAULT_DAMAGE_BONUS); }
    public static double knockbackResistance() { return get(knockbackResistance, DEFAULT_KNOCKBACK_RESISTANCE); }
    public static boolean extraLoadouts() {
        Boolean o = extraOverride;
        return o != null ? o : get(extraLoadouts, DEFAULT_EXTRA_LOADOUTS);
    }

    public static boolean loadoutAllowed(CaptainLoadout loadout) {
        return loadout.core() || extraLoadouts();
    }

    private static boolean get(ModConfigSpec.BooleanValue v, boolean fallback) {
        try {
            return v == null || !HearthsteadServerConfig.SPEC.isLoaded() ? fallback : v.get();
        } catch (IllegalStateException notLoaded) {
            return fallback;
        }
    }

    private static double get(ModConfigSpec.DoubleValue v, double fallback) {
        try {
            return v == null || !HearthsteadServerConfig.SPEC.isLoaded() ? fallback : v.get();
        } catch (IllegalStateException notLoaded) {
            return fallback;
        }
    }
}
