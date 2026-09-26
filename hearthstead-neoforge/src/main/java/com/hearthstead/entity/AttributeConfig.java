package com.hearthstead.entity;

import com.hearthstead.HearthsteadServerConfig;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.ModConfigSpec;

import javax.annotation.Nullable;

/**
 * The {@code [attributes]} section of the server config (attributes lane,
 * 26 Sep; plan/ATTRIBUTES.md): one global multiplier for every
 * {@link AttributeEffects.Effect} and for job fit.
 *
 * <p>Same GameTest rule as {@code QualityConfig}: on the GameTest server the
 * strength is 0 (every new attribute effect off) unless a batch opts in with
 * {@link #testOverride}, so the rest of the suite keeps the exact timings and
 * numbers it pins. The rank ladders, effort pool, lumber contacts and
 * learning rate are older structural effects and are never scaled here.
 */
public final class AttributeConfig {

    public static final double DEFAULT_STRENGTH = 1.0D;

    private static ModConfigSpec.DoubleValue effectStrength;

    /** A GameTest batch may set the strength for itself; null = 0 in tests. */
    @Nullable
    public static volatile Double testOverride;

    private AttributeConfig() {
    }

    /** Called once from {@link HearthsteadServerConfig}'s builder. */
    public static void define(ModConfigSpec.Builder builder) {
        builder.comment("Settler attributes (attributes lane). See plan/ATTRIBUTES.md.").push("attributes");
        effectStrength = builder
            .comment("How strongly attributes act in play. 1.0 = as designed: at 99 an attribute",
                "gives e.g. +20% melee damage (Strength), -20% energy drain (Stamina), -30% arrow",
                "spread (Dexterity), -20% morale loss (Spirit), +25% search range (Perception),",
                "-12% craft time (Focus), +10 points persuasion (Presence), and the job's primary",
                "and secondary attribute cut its work time by up to 15%. Effects follow a",
                "square-root curve, so newcomers (1-15) already differ. 0.0 = these effects off",
                "(rank ladders, the effort pool and learning speed still use attributes).",
                "Range 0.0-2.0; every effect also has its own hard cap.")
            .defineInRange("effectStrength", DEFAULT_STRENGTH, 0.0D, AttributeEffects.MAX_STRENGTH);
        builder.pop();
    }

    /** The multiplier in force on this server (0 on a GameTest server unless overridden). */
    public static double strength(@Nullable MinecraftServer server) {
        if (server instanceof GameTestServer) {
            Double override = testOverride;
            return override == null ? 0.0D : AttributeEffects.clampStrength(override);
        }
        return configured();
    }

    /**
     * The configured value without the GameTest rule. Server config is synced
     * to clients, so the Skills tab shows the same numbers the server uses.
     */
    public static double configured() {
        if (effectStrength == null || HearthsteadServerConfig.SPEC == null
            || !HearthsteadServerConfig.SPEC.isLoaded()) {
            return DEFAULT_STRENGTH;
        }
        try {
            return AttributeEffects.clampStrength(effectStrength.get());
        } catch (IllegalStateException notLoaded) {
            return DEFAULT_STRENGTH;
        }
    }

    /** Server-side shortcut for a settler: the strength of its world. */
    public static double strength(SettlerEntity settler) {
        return settler == null || settler.level().isClientSide()
            ? configured() : strength(settler.level().getServer());
    }
}
