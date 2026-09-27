package com.hearthstead.settlement.economy;

import com.hearthstead.HearthsteadServerConfig;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.ModConfigSpec;

import javax.annotation.Nullable;

/**
 * The {@code [quality]} section of the server config (quality lane, 26 Sep;
 * plan/QUALITY.md). Goods crafted by settlers roll a quality grade; the grade
 * adds a little durability, attack damage, toughness or saturation, and the
 * merchant pays more for it.
 *
 * <p>Same GameTest rule as {@link EconomyConfig}: on the GameTest server the
 * crafted roll and the merchant's premium crafted rows are OFF unless a test
 * opts in through {@link #testOverride}, so the rest of the suite keeps
 * seeing exactly the Basic outputs it pins. Stat bonuses on a stack that
 * already carries a grade always apply while {@code statBonuses} is on.
 */
public final class QualityConfig {

    public static final boolean DEFAULT_CRAFTED_QUALITY = true;
    public static final boolean DEFAULT_STAT_BONUSES = true;
    public static final boolean DEFAULT_MERCHANT_PREMIUMS = true;

    private static ModConfigSpec.BooleanValue craftedQuality;
    private static ModConfigSpec.BooleanValue statBonuses;
    private static ModConfigSpec.BooleanValue merchantPremiums;

    /** A GameTest may switch the crafted roll on for itself; null = off in tests. */
    @Nullable
    public static volatile Boolean testOverride;

    private QualityConfig() {
    }

    /** Called once from {@link HearthsteadServerConfig}'s builder. */
    public static void define(ModConfigSpec.Builder builder) {
        builder.comment("Quality of goods your settlers craft (quality lane). See plan/QUALITY.md.").push("quality");
        craftedQuality = builder
            .comment("Workshop goods (tools, weapons, armour, bows, arrows, food, leather, bricks,",
                "barrels, beams, bolts, ale) roll a grade: Basic, Fine, Superior, Exceptional,",
                "Masterwork or Legendary. The crafter's trade level matters most, then the",
                "trade attribute, the workshop's level and the tool in hand. New settlers make",
                "mostly Basic and Fine; Masterwork needs trade level 7 and Legendary level 9.",
                "false = everything crafted is Basic (goods already made keep their grade).")
            .define("craftedQuality", DEFAULT_CRAFTED_QUALITY);
        statBonuses = builder
            .comment("Graded equipment is a little better: max durability +10/20/35/50/75%",
                "(Fine..Legendary, set when made), weapons +0.5/1/1.5/2/3 attack damage, armour",
                "+0.25/0.5/0.75/1/1.5 toughness per piece, food +10..50% saturation.",
                "false = attack and toughness bonuses stop applying (durability and food values",
                "already made stay as they are).")
            .define("statBonuses", DEFAULT_STAT_BONUSES);
        merchantPremiums = builder
            .comment("The visiting merchant also buys graded crafted goods at a better rate:",
                "x1.25 / 1.5 / 2 / 3 / 5 of the Basic price (Fine..Legendary), at most 5 Coins",
                "per sale. Every sale still comes out of his shared purse.")
            .define("merchantPremiums", DEFAULT_MERCHANT_PREMIUMS);
        builder.pop();
    }

    public static boolean craftedQuality(@Nullable MinecraftServer server) {
        return !neutral(server) && boolOr(craftedQuality, DEFAULT_CRAFTED_QUALITY);
    }

    /** Server config is synced to clients, so the tooltip agrees with the server. */
    public static boolean statBonuses() {
        return boolOr(statBonuses, DEFAULT_STAT_BONUSES);
    }

    public static boolean merchantPremiums(@Nullable MinecraftServer server) {
        return !neutral(server) && boolOr(merchantPremiums, DEFAULT_MERCHANT_PREMIUMS);
    }

    private static boolean neutral(@Nullable MinecraftServer server) {
        if (server instanceof GameTestServer) {
            Boolean override = testOverride;
            return override == null || !override;
        }
        return false;
    }

    private static boolean boolOr(ModConfigSpec.BooleanValue value, boolean fallback) {
        if (value == null || HearthsteadServerConfig.SPEC == null || !HearthsteadServerConfig.SPEC.isLoaded()) {
            return fallback;
        }
        try {
            return value.get();
        } catch (IllegalStateException notLoaded) {
            return fallback;
        }
    }
}
