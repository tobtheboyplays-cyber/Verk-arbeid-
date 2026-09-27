package com.hearthstead.event.worldevent;

import com.hearthstead.HearthsteadServerConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * {@code [chat]} keys of the story lane. The story events themselves are
 * switched with the normal event switches ({@code [events] story_visit},
 * {@code [events] story_threat}) under the master {@code [features] worldEvents}.
 * Safe before the config loads (defaults on).
 */
public final class StoryConfig {
    private static ModConfigSpec.BooleanValue rememberCues;
    private static ModConfigSpec.BooleanValue arrivals;
    private static volatile Boolean rememberOverride;

    private StoryConfig() {
    }

    /** Called once from {@link HearthsteadServerConfig}'s static builder. */
    public static void define(ModConfigSpec.Builder builder) {
        builder.comment("Town chat lines of the named story visitors.").push("chat");
        rememberCues = builder
            .comment("\"<Name> will remember this.\" after a choice that upsets (or pleases) a named character.",
                "The choice is remembered either way; this only hides the chat cue.")
            .define("rememberCues", true);
        arrivals = builder
            .comment("One short [Town] line when a named visitor arrives or leaves.")
            .define("storyArrivals", true);
        builder.pop();
    }

    public static boolean rememberCues() {
        Boolean o = rememberOverride;
        if (o != null) return o;
        return read(rememberCues);
    }

    public static boolean arrivals() {
        return read(arrivals);
    }

    public static void overrideRememberForTests(Boolean value) {
        rememberOverride = value;
    }

    private static boolean read(ModConfigSpec.BooleanValue value) {
        if (value == null || !HearthsteadServerConfig.SPEC.isLoaded()) return true;
        try {
            return value.get();
        } catch (IllegalStateException notLoaded) {
            return true;
        }
    }
}
