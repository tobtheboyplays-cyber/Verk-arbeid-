package com.hearthstead.event.worldevent;

import com.hearthstead.HearthsteadServerConfig;
import java.util.EnumMap;
import java.util.Map;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * {@code [events]} in the server config. The master kill-switch is the
 * central {@code [features] worldEvents} ({@link HearthsteadServerConfig#worldEventsEnabled()});
 * this section only tunes frequency and switches single events off.
 * Safe before the world's server config has loaded (defaults).
 */
public final class WorldEventConfig {
    public static final double DEFAULT_FREQUENCY = 1.0D;
    private static ModConfigSpec.DoubleValue frequency;
    private static final Map<WorldEventType, ModConfigSpec.BooleanValue> ENABLED =
        new EnumMap<>(WorldEventType.class);
    /** Test/QA override; null = config. */
    private static volatile Boolean masterOverride;
    /** Test/QA per-event overrides (absent = config). */
    private static final Map<WorldEventType, Boolean> TYPE_OVERRIDES =
        new java.util.concurrent.ConcurrentHashMap<>();

    private WorldEventConfig() {
    }

    /** Called once from {@link HearthsteadServerConfig}'s static builder. */
    public static void define(ModConfigSpec.Builder builder) {
        builder.comment("Small world events (peddler, refugees, minstrels, fox, wolves, boar, brawl).",
            "The master switch is [features] worldEvents.").push("events");
        frequency = builder
            .comment("Multiplier on how often a small event happens (1.0 = about every other day;",
                "0 = never). At most one small event per settlement per in-game day.")
            .defineInRange("frequency", DEFAULT_FREQUENCY, 0.0D, 3.0D);
        for (WorldEventType type : WorldEventType.values()) {
            ENABLED.put(type, builder.define(type.id(), true));
        }
        builder.pop();
    }

    /** Master switch: the central feature kill-switch. */
    public static boolean enabled() {
        Boolean override = masterOverride;
        if (override != null) return override;
        return HearthsteadServerConfig.worldEventsEnabled();
    }

    public static void overrideMasterForTests(Boolean value) {
        masterOverride = value;
    }

    /** One event's switch for tests; null restores the config value, and {@code type} null clears all. */
    public static void overrideTypeForTests(WorldEventType type, Boolean value) {
        if (type == null) {
            TYPE_OVERRIDES.clear();
        } else if (value == null) {
            TYPE_OVERRIDES.remove(type);
        } else {
            TYPE_OVERRIDES.put(type, value);
        }
    }

    public static double frequency() {
        if (frequency == null || !HearthsteadServerConfig.SPEC.isLoaded()) return DEFAULT_FREQUENCY;
        try {
            return frequency.get();
        } catch (IllegalStateException notLoaded) {
            return DEFAULT_FREQUENCY;
        }
    }

    public static boolean enabled(WorldEventType type) {
        Boolean override = TYPE_OVERRIDES.get(type);
        if (override != null) return override;
        ModConfigSpec.BooleanValue value = ENABLED.get(type);
        if (value == null || !HearthsteadServerConfig.SPEC.isLoaded()) return true;
        try {
            return value.get();
        } catch (IllegalStateException notLoaded) {
            return true;
        }
    }
}
