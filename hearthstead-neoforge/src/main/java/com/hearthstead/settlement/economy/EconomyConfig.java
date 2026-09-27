package com.hearthstead.settlement.economy;

import com.hearthstead.HearthsteadServerConfig;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.ModConfigSpec;

import javax.annotation.Nullable;

/**
 * The {@code [economy]} section of the server config (economy lane, 26 Sep).
 *
 * <p>The 10-day soak showed the same shape for every crafter: a workshop
 * could burn its whole day's raw supply in about an hour and then stand idle
 * waiting for a restock. Crafter capacity at the table's tick counts is 3-20x
 * what the village's gatherers produce (see plan/ECONOMY.md). The levers here
 * close that gap without touching the recipe table itself:
 *
 * <ul>
 *   <li>{@code craftTimeMultiplier}: every crafting batch takes this many
 *       times the table's ticks. In a supply-limited village (the normal
 *       case) this changes no output at all -- the same wheat still becomes
 *       the same bread -- it only spreads the work over the day, so the baker
 *       bakes instead of standing about, and each restock lasts longer, so
 *       couriers make fewer trips.</li>
 *   <li>{@code selfFetch*}: a crafter whose bench has been out of inputs for a
 *       while, with no courier on its way, walks to the warehouse and brings
 *       its own inputs back.</li>
 *   <li>{@code workshopUpkeep*}: with truly nothing to make and nothing to
 *       fetch, a crafter tidies the workshop and studies the trade (a slow,
 *       real trickle of trade XP) instead of wandering off.</li>
 *   <li>{@code starvingWorkshopsFirst}: couriers restock the workshops that
 *       cannot run a single batch before the ones that are merely low.</li>
 *   <li>{@code courierDepositBundle}: items a courier stows per chest motion.</li>
 *   <li>{@code hungerDrainMultiplier}: how fast settlers get hungry.</li>
 *   <li>{@code merchant*}: the merchant also buys crafted goods, and his
 *       purse grows with the village.</li>
 * </ul>
 *
 * <p>Defaults live here as constants so GameTests and a not-yet-loaded
 * config behave identically. On the GameTest server every lever returns its
 * NEUTRAL value (multiplier 1.0, fallbacks off) unless a test opts in through
 * {@link #testOverride}: the trade GameTests pin exact recipe timings and
 * item routes, and must keep measuring the table, not the tuning.
 */
public final class EconomyConfig {

    public static final double DEFAULT_CRAFT_TIME_MULTIPLIER = 3.0D;
    public static final boolean DEFAULT_SELF_FETCH = true;
    public static final int DEFAULT_SELF_FETCH_AFTER_SECONDS = 20;
    /** 96 (was 64): a crafter across a 72-radius town still reaches a Warehouse. */
    public static final int DEFAULT_SELF_FETCH_RADIUS = 96;
    public static final int DEFAULT_SELF_FETCH_MAX_ITEMS = 16;
    public static final boolean DEFAULT_WORKSHOP_UPKEEP = true;
    public static final int DEFAULT_UPKEEP_SECONDS = 30;
    public static final boolean DEFAULT_STARVING_FIRST = true;
    public static final int DEFAULT_COURIER_DEPOSIT_BUNDLE = 4;
    public static final double DEFAULT_HUNGER_DRAIN_MULTIPLIER = 0.75D;
    public static final boolean DEFAULT_MERCHANT_BUYS_CRAFTED = true;
    public static final int DEFAULT_MERCHANT_PURSE_BASE = 12;
    public static final int DEFAULT_MERCHANT_PURSE_PER_FIVE = 2;
    public static final int DEFAULT_MERCHANT_PURSE_CAP = 40;
    public static final int DEFAULT_TOOL_STOCK_TARGET = 2;

    private static ModConfigSpec.DoubleValue craftTimeMultiplier;
    private static ModConfigSpec.BooleanValue selfFetch;
    private static ModConfigSpec.IntValue selfFetchAfterSeconds;
    private static ModConfigSpec.IntValue selfFetchRadius;
    private static ModConfigSpec.IntValue selfFetchMaxItems;
    private static ModConfigSpec.BooleanValue workshopUpkeep;
    private static ModConfigSpec.IntValue upkeepSeconds;
    private static ModConfigSpec.BooleanValue starvingFirst;
    private static ModConfigSpec.IntValue courierDepositBundle;
    private static ModConfigSpec.DoubleValue hungerDrainMultiplier;
    private static ModConfigSpec.BooleanValue merchantBuysCrafted;
    private static ModConfigSpec.IntValue merchantPurseBase;
    private static ModConfigSpec.IntValue merchantPursePerFive;
    private static ModConfigSpec.IntValue merchantPurseCap;
    private static ModConfigSpec.IntValue toolStockTarget;

    /** A GameTest may switch the tuning on for itself; null = neutral in tests. */
    @Nullable
    public static volatile Boolean testOverride;

    private EconomyConfig() {
    }

    /** Called once from {@link HearthsteadServerConfig}'s builder. */
    public static void define(ModConfigSpec.Builder builder) {
        builder.comment("Economy tuning (economy lane). See plan/ECONOMY.md for the numbers behind",
            "each default. Crafting recipes, inputs and outputs are unchanged; these only set",
            "pace and what a crafter does while it waits for materials.").push("economy");
        craftTimeMultiplier = builder
            .comment("Every crafting batch takes this many times its recipe's base time.",
                "A village's gatherers supply far less than its workshops can use, so at 1.0",
                "a workshop uses up a day's supply within about an hour and then idles.",
                "3.0 spreads the same supply over the working day. Output is unchanged",
                "whenever inputs are the limit (the usual case); it only drops when inputs",
                "are plentiful. Research speed bonuses still apply on top.")
            .defineInRange("craftTimeMultiplier", DEFAULT_CRAFT_TIME_MULTIPLIER, 0.25D, 10.0D);
        selfFetch = builder
            .comment("A crafter whose bench cannot run a single batch, with no courier bringing",
                "anything, walks to the Warehouse and fetches its own inputs.")
            .define("selfFetch", DEFAULT_SELF_FETCH);
        selfFetchAfterSeconds = builder
            .comment("How long a bench must stand empty before its crafter fetches for itself,",
                "giving the couriers first claim.")
            .defineInRange("selfFetchAfterSeconds", DEFAULT_SELF_FETCH_AFTER_SECONDS, 0, 600);
        selfFetchRadius = builder
            .comment("A crafter only fetches from a Warehouse within this many blocks of its bench.")
            .defineInRange("selfFetchRadius", DEFAULT_SELF_FETCH_RADIUS, 8, 256);
        selfFetchMaxItems = builder
            .comment("The most items a crafter carries back in one self-fetch trip.")
            .defineInRange("selfFetchMaxItems", DEFAULT_SELF_FETCH_MAX_ITEMS, 1, 64);
        workshopUpkeep = builder
            .comment("With nothing to make and nothing to fetch, a crafter tidies the workshop,",
                "sharpens (mends a little wear on) the tools kept there and studies the trade",
                "(slow trade XP) instead of wandering off.")
            .define("workshopUpkeep", DEFAULT_WORKSHOP_UPKEEP);
        upkeepSeconds = builder
            .comment("Length of one upkeep session. Each finished session gives 1 trade XP",
                "(a crafted batch gives 2), so a whole day of upkeep earns under half of what",
                "a day of crafting does. The bench is re-checked for inputs every second.")
            .defineInRange("upkeepSeconds", DEFAULT_UPKEEP_SECONDS, 5, 120);
        starvingFirst = builder
            .comment("Couriers restock workshops that cannot run a single batch before",
                "workshops that are merely below their four-batch reserve.")
            .define("starvingWorkshopsFirst", DEFAULT_STARVING_FIRST);
        courierDepositBundle = builder
            .comment("Items a Courier sets into a chest per 80-tick stow motion when unloading",
                "her bag at a workshop or warehouse. 1 = one item per motion (about 18 s to",
                "unload a full bag), which capped three couriers at roughly 400 moved items a",
                "day -- less than 35 settlers eat. The motion is the same; the bundle is bigger.")
            .defineInRange("courierDepositBundle", DEFAULT_COURIER_DEPOSIT_BUNDLE, 1, 16);
        hungerDrainMultiplier = builder
            .comment("Scales how fast settlers get hungry. 1.0 = a working settler eats about",
                "3.7 loaves a day; 0.75 = about 2.8. Lower it if your village starves,",
                "raise it for a harsher game.")
            .defineInRange("hungerDrainMultiplier", DEFAULT_HUNGER_DRAIN_MULTIPLIER, 0.1D, 3.0D);
        merchantBuysCrafted = builder
            .comment("The visiting merchant also buys crafted goods (bread, cooked meat, leather,",
                "stone bricks, charcoal, wool, beams, bolts, ale, arrows, barrels, iron tools),",
                "three kinds per visit, chosen from what your village holds.")
            .define("merchantBuysCrafted", DEFAULT_MERCHANT_BUYS_CRAFTED);
        merchantPurseBase = builder
            .comment("Coins the merchant brings on each visit (about two visits a day) before",
                "the village-size bonus below.")
            .defineInRange("merchantPurseBase", DEFAULT_MERCHANT_PURSE_BASE, 0, 64);
        merchantPursePerFive = builder
            .comment("Extra Coins in the merchant's purse for every 5 settlers.")
            .defineInRange("merchantPursePerFiveSettlers", DEFAULT_MERCHANT_PURSE_PER_FIVE, 0, 16);
        merchantPurseCap = builder
            .comment("The merchant's purse never exceeds this many Coins per visit.")
            .defineInRange("merchantPurseCap", DEFAULT_MERCHANT_PURSE_CAP, 1, 64);
        toolStockTarget = builder
            .comment("Tools, weapons and armour are made to demand: without an order, a workshop",
                "only makes one while the village holds fewer than this many of it (Warehouse +",
                "workshop). Orders always run. Keeps an idle smithy from turning every iron",
                "ingot into axes. 0 = no limit.")
            .defineInRange("toolStockTarget", DEFAULT_TOOL_STOCK_TARGET, 0, 64);
        builder.pop();
    }

    // ------------------------------------------------------------ getters --

    public static double craftTimeMultiplier(@Nullable MinecraftServer server) {
        if (neutral(server)) {
            return 1.0D;
        }
        return doubleOr(craftTimeMultiplier, DEFAULT_CRAFT_TIME_MULTIPLIER);
    }

    public static boolean selfFetch(@Nullable MinecraftServer server) {
        return !neutral(server) && boolOr(selfFetch, DEFAULT_SELF_FETCH);
    }

    public static int selfFetchAfterTicks() {
        return 20 * intOr(selfFetchAfterSeconds, DEFAULT_SELF_FETCH_AFTER_SECONDS);
    }

    public static int selfFetchRadius() {
        return intOr(selfFetchRadius, DEFAULT_SELF_FETCH_RADIUS);
    }

    public static int selfFetchMaxItems() {
        return intOr(selfFetchMaxItems, DEFAULT_SELF_FETCH_MAX_ITEMS);
    }

    public static boolean workshopUpkeep(@Nullable MinecraftServer server) {
        return !neutral(server) && boolOr(workshopUpkeep, DEFAULT_WORKSHOP_UPKEEP);
    }

    public static int upkeepTicks() {
        return 20 * intOr(upkeepSeconds, DEFAULT_UPKEEP_SECONDS);
    }

    public static boolean starvingWorkshopsFirst(@Nullable MinecraftServer server) {
        return !neutral(server) && boolOr(starvingFirst, DEFAULT_STARVING_FIRST);
    }

    public static int courierDepositBundle(@Nullable MinecraftServer server) {
        if (neutral(server)) {
            return 1;
        }
        return Math.max(1, intOr(courierDepositBundle, DEFAULT_COURIER_DEPOSIT_BUNDLE));
    }

    public static double hungerDrainMultiplier(@Nullable MinecraftServer server) {
        if (neutral(server)) {
            return 1.0D;
        }
        return doubleOr(hungerDrainMultiplier, DEFAULT_HUNGER_DRAIN_MULTIPLIER);
    }

    public static boolean merchantBuysCrafted(@Nullable MinecraftServer server) {
        return !neutral(server) && boolOr(merchantBuysCrafted, DEFAULT_MERCHANT_BUYS_CRAFTED);
    }

    /**
     * Coins the merchant carries this visit: base + perFive x (population/5),
     * capped. {@code neutralPurse} (the old fixed purse) on the GameTest server.
     */
    public static int merchantPurse(@Nullable MinecraftServer server, int population, int neutralPurse) {
        if (neutral(server)) {
            return neutralPurse;
        }
        int base = intOr(merchantPurseBase, DEFAULT_MERCHANT_PURSE_BASE);
        int perFive = intOr(merchantPursePerFive, DEFAULT_MERCHANT_PURSE_PER_FIVE);
        int cap = intOr(merchantPurseCap, DEFAULT_MERCHANT_PURSE_CAP);
        return Math.max(0, Math.min(cap, base + perFive * (Math.max(0, population) / 5)));
    }

    public static int toolStockTarget(@Nullable MinecraftServer server) {
        if (neutral(server)) {
            return 0;
        }
        return intOr(toolStockTarget, DEFAULT_TOOL_STOCK_TARGET);
    }

    /** GameTest server: neutral unless a test explicitly opted in. */
    private static boolean neutral(@Nullable MinecraftServer server) {
        if (server instanceof GameTestServer) {
            Boolean override = testOverride;
            return override == null || !override;
        }
        return false;
    }

    private static boolean loaded(Object value) {
        return value != null && HearthsteadServerConfig.SPEC != null
            && HearthsteadServerConfig.SPEC.isLoaded();
    }

    private static double doubleOr(ModConfigSpec.DoubleValue value, double fallback) {
        if (!loaded(value)) {
            return fallback;
        }
        try {
            return value.get();
        } catch (IllegalStateException notLoaded) {
            return fallback;
        }
    }

    private static int intOr(ModConfigSpec.IntValue value, int fallback) {
        if (!loaded(value)) {
            return fallback;
        }
        try {
            return value.get();
        } catch (IllegalStateException notLoaded) {
            return fallback;
        }
    }

    private static boolean boolOr(ModConfigSpec.BooleanValue value, boolean fallback) {
        if (!loaded(value)) {
            return fallback;
        }
        try {
            return value.get();
        } catch (IllegalStateException notLoaded) {
            return fallback;
        }
    }
}
