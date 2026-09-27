package com.hearthstead.entity.ai;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * [mine]: how the Miner works (MINE V2, owner 27 Sep: "a mine entrance like
 * MineColonies"). {@code style = "shaft"} (default): he digs a ladder shaft
 * down from the ladder at the Mine's shaft mouth, one short lane out at the
 * bottom, then works the rock face at the lane's end. {@code "quarry"}: the
 * old open terraced pit. A Mine without a shaft ladder (an old or
 * player-built mine) always falls back to the quarry.
 */
public final class MineConfig {
    public static final String SHAFT = "shaft";
    public static final String QUARRY = "quarry";
    public static final int DEFAULT_LEVEL_ONE_DEPTH = 8;
    public static final int DEFAULT_LEVEL_TWO_DEPTH = 16;
    public static final int DEFAULT_LANE_LENGTH = 8;
    public static final int DEFAULT_MIN_Y = -50;

    private static ModConfigSpec.ConfigValue<String> style;
    private static ModConfigSpec.IntValue levelOneDepth;
    private static ModConfigSpec.IntValue levelTwoDepth;
    private static ModConfigSpec.IntValue laneLength;
    private static ModConfigSpec.IntValue minY;

    /** GameTest overrides; null = use the config. */
    private static volatile String testStyle;
    private static volatile Integer testLevelOneDepth;
    private static volatile Integer testLevelTwoDepth;
    private static volatile Integer testLaneLength;
    private static volatile Integer testMinY;

    public static void define(ModConfigSpec.Builder builder) {
        builder.comment("The Miner (MINE V2).").push("mine");
        style = builder
            .comment("\"shaft\": the Miner digs a ladder shaft down from the ladder at the Mine's shaft",
                "mouth, one lane out at the bottom, then works the rock face at the lane's end (each cut",
                "rolls ore from data/hearthstead/mining/level_N.json). \"quarry\": the old open terraced pit.",
                "A Mine without a shaft ladder always uses the quarry.")
            .define("style", SHAFT, v -> SHAFT.equals(v) || QUARRY.equals(v));
        levelOneDepth = builder
            .comment("Blocks the shaft goes below the surface before the level-1 lane.")
            .defineInRange("levelOneDepth", DEFAULT_LEVEL_ONE_DEPTH, 3, 32);
        levelTwoDepth = builder
            .comment("With the Deep Mine tech: blocks the shaft continues below level 1 to the level-2 lane.")
            .defineInRange("levelTwoDepth", DEFAULT_LEVEL_TWO_DEPTH, 3, 48);
        laneLength = builder
            .comment("Length of the lane dug out from the shaft bottom to the rock face.")
            .defineInRange("laneLength", DEFAULT_LANE_LENGTH, 1, 16);
        minY = builder
            .comment("The shaft never goes below this height (bedrock is never dug either).")
            .defineInRange("minY", DEFAULT_MIN_Y, -58, 200);
        builder.pop();
    }

    public static boolean shaftStyle() {
        String s = testStyle;
        if (s == null) {
            s = get(style, SHAFT);
        }
        return !QUARRY.equals(s);
    }

    public static int levelOneDepth() {
        Integer o = testLevelOneDepth;
        return o != null ? o : get(levelOneDepth, DEFAULT_LEVEL_ONE_DEPTH);
    }

    public static int levelTwoDepth() {
        Integer o = testLevelTwoDepth;
        return o != null ? o : get(levelTwoDepth, DEFAULT_LEVEL_TWO_DEPTH);
    }

    public static int laneLength() {
        Integer o = testLaneLength;
        return o != null ? o : get(laneLength, DEFAULT_LANE_LENGTH);
    }

    public static int minY() {
        Integer o = testMinY;
        return o != null ? o : get(minY, DEFAULT_MIN_Y);
    }

    /** GameTest hook: test worlds stand near the bottom of the world; null clears. */
    public static void overrideMinYForTests(Integer y) {
        testMinY = y;
    }

    /** GameTest hook: shorter shafts and lanes, or the quarry; nulls clear. */
    public static void overrideForTests(String styleOrNull, Integer levelOne, Integer levelTwo, Integer lane) {
        testStyle = styleOrNull;
        testLevelOneDepth = levelOne;
        testLevelTwoDepth = levelTwo;
        testLaneLength = lane;
    }

    private static <T> T get(ModConfigSpec.ConfigValue<T> value, T fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return value.get();
        } catch (IllegalStateException notLoaded) {
            return fallback;
        }
    }

    private MineConfig() {
    }
}
