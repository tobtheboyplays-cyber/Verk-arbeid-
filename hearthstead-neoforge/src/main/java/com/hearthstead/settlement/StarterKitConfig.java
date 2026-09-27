package com.hearthstead.settlement;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * [start]: the one-time start kit a player gets on their first join to a world
 * (the Settler's Handbook, plus optional food). Delivered once per player per
 * world by {@link StarterHandbookDelivery}; never again on relog, death or a
 * dimension change.
 */
public final class StarterKitConfig {
    /**
     * Owner, 27 Sep: the bread now comes from the Guildmaster's welcome (16 per player,
     * with the iron tools), so the first-join kit is the Handbook only by default.
     * The key stays for server owners who want extra food on join.
     */
    public static final int DEFAULT_KIT_BREAD = 0;
    public static final int MAX_KIT_BREAD = 64;
    /**
     * Owner, 27 Sep: the town's first Coins now come from the Guildmaster's welcome gift,
     * so the Banner founding gift is off by default. The key stays for server owners.
     */
    public static final int DEFAULT_START_COINS = 0;
    private static ModConfigSpec.IntValue startCoins;

    private static ModConfigSpec.IntValue kitBread;
    /** GameTest override; null = use the config. */
    private static volatile Integer testOverride;
    /** GameTest override for the founding Coin gift; null = use the config. */
    private static volatile Integer startCoinsOverride;

    public static void define(ModConfigSpec.Builder builder) {
        builder.comment("The start kit a player gets on their first join to this world.").push("start");
        kitBread = builder
            .comment("Extra bread given once, together with the Settler's Handbook, on a player's first join.",
                "Never given again on relog, death or a dimension change. 0 = no food, handbook only (default:",
                "the Guildmaster's welcome gives each player 16 bread with their iron tools).")
            .defineInRange("kitBread", DEFAULT_KIT_BREAD, 0, MAX_KIT_BREAD);
        startCoins = builder
            .comment("Coins placed in a new village's Banner stores once after successful founding.",
                "Reopening, rebinding or reloading an existing village grants nothing. 0 disables the gift.")
            .defineInRange("startCoins", DEFAULT_START_COINS, 0, 64);
        builder.pop();
    }

    /** Bread in the start kit; safe before the world's server config has loaded (the default). */
    public static int kitBread() {
        Integer o = testOverride;
        if (o != null) return clamp(o);
        if (kitBread == null) return DEFAULT_KIT_BREAD;
        try {
            return clamp(kitBread.get());
        } catch (IllegalStateException notLoaded) {
            return DEFAULT_KIT_BREAD;
        }
    }

    public static int startCoins() {
        Integer o = startCoinsOverride;
        if (o != null) return Math.max(0, Math.min(64, o));
        if (startCoins == null) return DEFAULT_START_COINS;
        try {
            return Math.max(0, Math.min(64, startCoins.get()));
        } catch (IllegalStateException notLoaded) {
            return DEFAULT_START_COINS;
        }
    }

    public static void overrideForTests(Integer bread) {
        testOverride = bread;
    }

    public static void overrideStartCoinsForTests(Integer coins) {
        startCoinsOverride = coins;
    }

    static int clamp(int bread) {
        return Math.max(0, Math.min(MAX_KIT_BREAD, bread));
    }

    private StarterKitConfig() {
    }
}
