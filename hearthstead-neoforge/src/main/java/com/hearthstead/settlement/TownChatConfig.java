package com.hearthstead.settlement;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.EnumMap;
import java.util.Map;

/**
 * [chat]: one switch per kind of town chat line ({@link TownChat}). All on by
 * default. Off means that kind of line is not sent to chat at all; the event
 * itself (the raid, the death, the finished building) still happens.
 */
public final class TownChatConfig {
    private static final Map<TownChat.Kind, ModConfigSpec.BooleanValue> SWITCHES =
        new EnumMap<>(TownChat.Kind.class);
    /** GameTest overrides; a missing entry = use the config. */
    private static final Map<TownChat.Kind, Boolean> TEST_OVERRIDES =
        new java.util.concurrent.ConcurrentHashMap<>();

    public static void define(ModConfigSpec.Builder builder) {
        builder.comment("Town chat: short lines about important events, sent only to the",
            "settlement's members (players who have used its Banner). Prefix [Town name].").push("chat");
        SWITCHES.put(TownChat.Kind.RAID, builder
            .comment("Raid warnings, raid arrivals and how a raid ended.")
            .define("raid", true));
        SWITCHES.put(TownChat.Kind.DEATH, builder
            .comment("A settler died (name and cause).")
            .define("death", true));
        SWITCHES.put(TownChat.Kind.BUILDING, builder
            .comment("The Builder finished a building.")
            .define("building", true));
        SWITCHES.put(TownChat.Kind.UPGRADE, builder
            .comment("A building reached a new level, or a home a new tier.")
            .define("upgrade", true));
        SWITCHES.put(TownChat.Kind.RESEARCH, builder
            .comment("New research learned in the Tech Tree.")
            .define("research", true));
        SWITCHES.put(TownChat.Kind.TRADE, builder
            .comment("The Trader's sales and failed trips.")
            .define("trade", true));
        builder.pop();
    }

    /** Safe before the world's server config has loaded (on). */
    public static boolean enabled(TownChat.Kind kind) {
        Boolean o = TEST_OVERRIDES.get(kind);
        if (o != null) {
            return o;
        }
        ModConfigSpec.BooleanValue value = SWITCHES.get(kind);
        if (value == null) {
            return true;
        }
        try {
            return value.get();
        } catch (IllegalStateException notLoaded) {
            return true;
        }
    }

    /** GameTest hook: force a kind on or off; null clears the override. */
    public static void overrideForTests(TownChat.Kind kind, Boolean on) {
        if (on == null) {
            TEST_OVERRIDES.remove(kind);
        } else {
            TEST_OVERRIDES.put(kind, on);
        }
    }

    private TownChatConfig() {
    }
}
