package com.hearthstead.settlement.techtree;

import net.neoforged.neoforge.common.ModConfigSpec;

import javax.annotation.Nullable;

/**
 * The {@code [techtree]} server config section (tech tree v3 framework lane).
 *
 * <p>{@code enabled=false} is the kill switch: the Banner opens the old
 * Development screen, no study clock runs and no node stamps itself. Nothing
 * learned is ever removed, and effects of learned nodes keep working.
 */
public final class TechTreeConfig {
    public static final boolean DEFAULT_ENABLED = true;
    public static final double DEFAULT_STUDY_TIME_SCALE = 1.0D;

    private static ModConfigSpec.BooleanValue enabled;
    private static ModConfigSpec.DoubleValue studyTimeScale;
    private static ModConfigSpec.BooleanValue gateCrafting;
    /** GameTest/JUnit override for gateCrafting; null = config value. */
    @Nullable
    public static volatile Boolean gateCraftingOverride;

    /** GameTest/JUnit overrides; null = config value. */
    @Nullable
    public static volatile Boolean enabledOverride;
    @Nullable
    public static volatile Double studyScaleOverride;

    private TechTreeConfig() {
    }

    /** Called once from HearthsteadServerConfig's builder. */
    public static void define(ModConfigSpec.Builder builder) {
        builder.comment("Tech tree v3 (84 nodes, 5 branches). See plan/techtree/IMPLEMENTATION.md.")
            .push("techtree");
        enabled = builder
            .comment("The v3 tech tree screen at the Banner, pick-one choices, study time and",
                "self-stamping charters. false = the old Development screen; learned nodes and",
                "their effects are kept.")
            .define("enabled", DEFAULT_ENABLED);
        studyTimeScale = builder
            .comment("Multiplier on study time for Town+ nodes (1 in-game day per day of study at",
                "1.0). 0 = learning is instant.")
            .defineInRange("studyTimeScale", DEFAULT_STUDY_TIME_SCALE, 0.0D, 10.0D);
        gateCrafting = builder
            .comment("Owner rule: a recipe listed by a tech node (recipe_gates.json, unlocks_recipes,",
                "build plans) cannot be crafted by a player or a workshop until that node is learned.",
                "false = everything craftable as before.")
            .define("gateCrafting", true);
        builder.pop();
    }

    public static boolean enabled() {
        Boolean override = enabledOverride;
        if (override != null) {
            return override;
        }
        try {
            return enabled == null || com.hearthstead.HearthsteadServerConfig.SPEC == null
                || !com.hearthstead.HearthsteadServerConfig.SPEC.isLoaded() ? DEFAULT_ENABLED : enabled.get();
        } catch (RuntimeException notLoaded) {
            return DEFAULT_ENABLED;
        }
    }

    public static boolean gateCrafting() {
        Boolean override = gateCraftingOverride;
        if (override != null) {
            return override;
        }
        try {
            return gateCrafting == null || com.hearthstead.HearthsteadServerConfig.SPEC == null
                || !com.hearthstead.HearthsteadServerConfig.SPEC.isLoaded() || gateCrafting.get();
        } catch (RuntimeException notLoaded) {
            return true;
        }
    }

    public static double studyTimeScale() {
        Double override = studyScaleOverride;
        if (override != null) {
            return override;
        }
        try {
            return studyTimeScale == null || com.hearthstead.HearthsteadServerConfig.SPEC == null
                || !com.hearthstead.HearthsteadServerConfig.SPEC.isLoaded()
                ? DEFAULT_STUDY_TIME_SCALE : studyTimeScale.get();
        } catch (RuntimeException notLoaded) {
            return DEFAULT_STUDY_TIME_SCALE;
        }
    }
}
