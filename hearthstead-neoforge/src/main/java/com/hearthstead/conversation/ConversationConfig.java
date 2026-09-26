package com.hearthstead.conversation;

import com.hearthstead.HearthsteadServerConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * {@code [conversations]} tuning in the server config. The master switch is
 * {@code [features] conversations}. Every getter is safe before the world's
 * server config has loaded (defaults).
 */
public final class ConversationConfig {
    public static final boolean DEFAULT_RAID_PARLEY = true;
    public static final int DEFAULT_PARLEY_SECONDS = 45;
    public static final int DEFAULT_TALK_TIMEOUT_SECONDS = 120;
    public static final boolean DEFAULT_ENCOUNTERS = true;

    private static ModConfigSpec.BooleanValue raidParley;
    private static ModConfigSpec.IntValue parleySeconds;
    private static ModConfigSpec.IntValue talkTimeoutSeconds;
    private static ModConfigSpec.BooleanValue encounters;
    /** Test/QA overrides; null = config. */
    private static volatile Boolean masterOverride;
    private static volatile Boolean parleyOverride;

    private ConversationConfig() {
    }

    /** Called once from {@link HearthsteadServerConfig}'s static builder. */
    public static void define(ModConfigSpec.Builder builder) {
        builder.comment("Conversations with visitors and raid captains. Master switch: [features] conversations.")
            .push("conversations");
        raidParley = builder
            .comment("Raid captains halt at the edge before the charge and can be talked to:",
                "pay tribute, persuade a truce, challenge to a duel or refuse.")
            .define("raidParley", DEFAULT_RAID_PARLEY);
        parleySeconds = builder
            .comment("Seconds the captain waits for someone to talk before the raid charges.",
                "Only counts while a player is near the settlement.")
            .defineInRange("parleySeconds", DEFAULT_PARLEY_SECONDS, 15, 120);
        talkTimeoutSeconds = builder
            .comment("An open conversation with no reply for this long closes by itself.")
            .defineInRange("talkTimeoutSeconds", DEFAULT_TALK_TIMEOUT_SECONDS, 20, 600);
        encounters = builder
            .comment("Walking up to a visitor or captain pulls you into the conversation (Shadow of War style).",
                "Off: right-click to talk.")
            .define("encounters", DEFAULT_ENCOUNTERS);
        builder.pop();
    }

    public static boolean enabled() {
        Boolean override = masterOverride;
        if (override != null) return override;
        return HearthsteadServerConfig.conversationsEnabled();
    }

    public static void overrideForTests(Boolean master, Boolean parley) {
        masterOverride = master;
        parleyOverride = parley;
    }

    public static boolean raidParley() {
        Boolean override = parleyOverride;
        if (override != null) return override && enabled();
        return enabled() && bool(raidParley, DEFAULT_RAID_PARLEY);
    }

    public static int parleyTicks() {
        return 20 * integer(parleySeconds, DEFAULT_PARLEY_SECONDS);
    }

    public static int talkTimeoutTicks() {
        return 20 * integer(talkTimeoutSeconds, DEFAULT_TALK_TIMEOUT_SECONDS);
    }

    public static boolean encounters() {
        return enabled() && bool(encounters, DEFAULT_ENCOUNTERS);
    }

    private static boolean bool(ModConfigSpec.BooleanValue value, boolean fallback) {
        if (value == null || !HearthsteadServerConfig.SPEC.isLoaded()) return fallback;
        try {
            return value.get();
        } catch (IllegalStateException notLoaded) {
            return fallback;
        }
    }

    private static int integer(ModConfigSpec.IntValue value, int fallback) {
        if (value == null || !HearthsteadServerConfig.SPEC.isLoaded()) return fallback;
        try {
            return value.get();
        } catch (IllegalStateException notLoaded) {
            return fallback;
        }
    }
}
