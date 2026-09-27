package com.hearthstead.entity.path;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * [pathing]: the universal settler stuck watchdog ({@link com.hearthstead.entity.ai.SettlerStuckWatchdog}).
 * Owner request 27 Sep: settlers must never stay stuck, and there must be an
 * emergency fallback if they do.
 */
public final class PathingConfig {
    public static final boolean DEFAULT_STUCK_WATCHDOG = true;
    public static final int DEFAULT_RESCUE_AFTER_SECONDS = 90;
    public static final boolean DEFAULT_RESCUE_CHAT = true;
    public static final boolean DEFAULT_ROUTE_HOME = false;

    private static ModConfigSpec.BooleanValue stuckWatchdog;
    private static ModConfigSpec.IntValue rescueAfterSeconds;
    private static ModConfigSpec.BooleanValue rescueChat;
    private static ModConfigSpec.BooleanValue routeHome;

    /** GameTest overrides; null = use the config. */
    private static volatile Boolean testWatchdog;
    private static volatile Integer testRescueAfterSeconds;
    private static volatile Boolean testRescueChat;
    private static volatile Boolean testRouteHome;

    public static void define(ModConfigSpec.Builder builder) {
        builder.comment("Settler pathing safety net: a watchdog notices a settler that wants to walk",
            "but has not moved, re-plans its route, steps it off an awkward spot and, as a last",
            "resort, walks it back to the Banner. It never breaks or places blocks.").push("pathing");
        stuckWatchdog = builder
            .comment("Watch every settler for being stuck (re-plan after 10 s, step off after 20 s,",
                "emergency rescue after rescueAfterSeconds). A settler inside a block is freed at once.",
                "false = no watchdog at all.")
            .define("stuckWatchdog", DEFAULT_STUCK_WATCHDOG);
        rescueAfterSeconds = builder
            .comment("Seconds a settler must be stuck (and shut in with no way out) before the",
                "emergency rescue moves it to the nearest open spot with a way to the Banner.",
                "At most one rescue per settler every 5 minutes.")
            .defineInRange("rescueAfterSeconds", DEFAULT_RESCUE_AFTER_SECONDS, 30, 600);
        rescueChat = builder
            .comment("Post one town chat line when a settler is rescued.")
            .define("rescueChat", DEFAULT_RESCUE_CHAT);
        routeHome = builder
            .comment("After 45 s stuck, interrupt the settler's current task (its goals release",
                "what they hold) and send it towards the Banner. Off by default.")
            .define("routeHome", DEFAULT_ROUTE_HOME);
        builder.pop();
    }

    public static boolean stuckWatchdog() {
        Boolean o = testWatchdog;
        return o != null ? o : get(stuckWatchdog, DEFAULT_STUCK_WATCHDOG);
    }

    public static int rescueAfterSeconds() {
        Integer o = testRescueAfterSeconds;
        if (o != null) {
            return o;
        }
        if (rescueAfterSeconds == null) {
            return DEFAULT_RESCUE_AFTER_SECONDS;
        }
        try {
            return rescueAfterSeconds.get();
        } catch (IllegalStateException notLoaded) {
            return DEFAULT_RESCUE_AFTER_SECONDS;
        }
    }

    public static boolean rescueChat() {
        Boolean o = testRescueChat;
        return o != null ? o : get(rescueChat, DEFAULT_RESCUE_CHAT);
    }

    public static boolean routeHome() {
        Boolean o = testRouteHome;
        return o != null ? o : get(routeHome, DEFAULT_ROUTE_HOME);
    }

    private static boolean get(ModConfigSpec.BooleanValue value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return value.get();
        } catch (IllegalStateException notLoaded) {
            return fallback;
        }
    }

    /** GameTest hooks; pass null to clear. */
    public static void overrideWatchdogForTests(Boolean on) {
        testWatchdog = on;
    }

    public static void overrideRescueAfterSecondsForTests(Integer seconds) {
        testRescueAfterSeconds = seconds;
    }

    public static void overrideRescueChatForTests(Boolean on) {
        testRescueChat = on;
    }

    public static void overrideRouteHomeForTests(Boolean on) {
        testRouteHome = on;
    }

    private PathingConfig() {
    }
}
