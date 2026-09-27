package com.hearthstead.settlement.work;

import com.hearthstead.registry.ModComponents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Quality of settler-CRAFTED goods (quality lane, 26 Sep; plan/QUALITY.md).
 *
 * <p>Gathered goods (logs, crops, fish) already roll their grade in
 * {@link GoodsQuality#forWork} and {@link FisherProgression}. This class is
 * the workshop side: every finished good a crafter makes at a bench rolls one
 * {@link GoodsQuality} tier, stored in the same {@code hearthstead:goods_quality}
 * component (Basic = no component, so Basic crafted goods still stack with
 * vanilla ones).
 *
 * <h2>The roll</h2>
 * <pre>
 * mean = 0.33 x (tradeLevel - 1)          main factor, trade level 1..10
 *      + 0.005 x attribute                the trade's primary attribute, 0..99
 *      + 0.30 x (workshopLevel - 1)       checklist level of the bench's building
 *      + 0.12 x toolTier                  GearTiers of the crafter's held tool, 0..4
 *      + 0.20
 * value = mean + 1.5 x (u1 + u2 - 1)      triangular spread, u1/u2 uniform [0,1)
 * tier  = thresholds 1 / 2 / 3 / 4.25 / 5.5 for Fine .. Legendary
 * gates: Masterwork needs trade level 7+, Legendary trade level 9+
 * </pre>
 * All pure and deterministic given (u1, u2), so JUnit pins the exact
 * distribution with a seeded RNG and {@link #odds} gives the analytic one.
 *
 * <h2>What quality does (modest, vanilla-safe)</h2>
 * Durability (baked into {@code minecraft:max_damage} at craft time), attack
 * damage for weapons and toughness for armour (added live through
 * {@code ItemAttributeModifierEvent}, so the item's own vanilla modifiers are
 * never replaced), a little extra saturation for food (baked into
 * {@code minecraft:food}), and the merchant pays more ({@link #salePrice}).
 */
public final class CraftedQuality {

    public static final double PER_TRADE_LEVEL = 0.33D;
    public static final double PER_ATTRIBUTE_POINT = 0.005D;
    public static final double PER_WORKSHOP_LEVEL = 0.30D;
    public static final double PER_TOOL_TIER = 0.12D;
    public static final double OFFSET = 0.20D;
    public static final double SPREAD = 1.5D;
    /** Value needed for Fine, Superior, Exceptional, Masterwork, Legendary. */
    private static final double[] THRESHOLD = {1.0D, 2.0D, 3.0D, 4.25D, 5.5D};
    public static final int MASTERWORK_MIN_LEVEL = 7;
    public static final int LEGENDARY_MIN_LEVEL = 9;

    /** Index = tier (Basic..Legendary). */
    private static final int[] DURABILITY_PERCENT = {0, 10, 20, 35, 50, 75};
    private static final double[] ATTACK_BONUS = {0.0D, 0.5D, 1.0D, 1.5D, 2.0D, 3.0D};
    private static final double[] TOUGHNESS_BONUS = {0.0D, 0.25D, 0.5D, 0.75D, 1.0D, 1.5D};
    private static final int[] SATURATION_PERCENT = {0, 10, 20, 30, 40, 50};
    private static final double[] PRICE_MULTIPLIER = {1.0D, 1.25D, 1.5D, 2.0D, 3.0D, 5.0D};
    /** No single sale pays more than this; the shared purse caps the rest. */
    public static final int MAX_COINS_PER_SALE = 5;
    /** A quoted price may miss the exact multiplier by at most this much. */
    private static final double PRICE_TOLERANCE = 0.10D;

    private CraftedQuality() {
    }

    // ------------------------------------------------------------- roll ---

    public static double mean(int tradeLevel, int attribute, int workshopLevel, int toolTier) {
        int level = Math.max(1, Math.min(10, tradeLevel));
        int attr = Math.max(0, Math.min(99, attribute));
        int workshop = Math.max(1, workshopLevel);
        int tool = Math.max(0, Math.min(4, toolTier));
        return PER_TRADE_LEVEL * (level - 1) + PER_ATTRIBUTE_POINT * attr
            + PER_WORKSHOP_LEVEL * (workshop - 1) + PER_TOOL_TIER * tool + OFFSET;
    }

    /** Deterministic core: {@code u1}, {@code u2} are two uniform draws in [0,1). */
    public static int roll(int tradeLevel, int attribute, int workshopLevel, int toolTier,
                           double u1, double u2) {
        return roll(tradeLevel, attribute, workshopLevel, toolTier, 0.0D, u1, u2);
    }

    /**
     * {@link #roll} with {@code extraMean} added to the mean (tech tree Guild
     * Halls, techtree-craft lane: CraftEffects.qualityMeanBonus). The
     * Masterwork/Legendary gates still read the real trade level.
     */
    public static int roll(int tradeLevel, int attribute, int workshopLevel, int toolTier,
                           double extraMean, double u1, double u2) {
        double value = mean(tradeLevel, attribute, workshopLevel, toolTier) + Math.max(0.0D, extraMean)
            + SPREAD * (clamp01(u1) + clamp01(u2) - 1.0D);
        int tier = GoodsQuality.BASIC;
        while (tier < GoodsQuality.HIGHEST && value >= THRESHOLD[tier]) {
            tier++;
        }
        return gate(tradeLevel, tier);
    }

    public static int roll(int tradeLevel, int attribute, int workshopLevel, int toolTier,
                           RandomSource random) {
        return roll(tradeLevel, attribute, workshopLevel, toolTier,
            random.nextDouble(), random.nextDouble());
    }

    public static int roll(int tradeLevel, int attribute, int workshopLevel, int toolTier,
                           double extraMean, RandomSource random) {
        return roll(tradeLevel, attribute, workshopLevel, toolTier, extraMean,
            random.nextDouble(), random.nextDouble());
    }

    private static int gate(int tradeLevel, int tier) {
        if (tier >= GoodsQuality.LEGENDARY && tradeLevel < LEGENDARY_MIN_LEVEL) {
            tier = GoodsQuality.MASTERWORK;
        }
        if (tier >= GoodsQuality.MASTERWORK && tradeLevel < MASTERWORK_MIN_LEVEL) {
            tier = GoodsQuality.EXCEPTIONAL;
        }
        return tier;
    }

    /** Exact probability of each tier (index Basic..Legendary); sums to 1. */
    public static double[] odds(int tradeLevel, int attribute, int workshopLevel, int toolTier) {
        double mean = mean(tradeLevel, attribute, workshopLevel, toolTier);
        double[] p = new double[GoodsQuality.HIGHEST + 1];
        double below = 0.0D;
        for (int tier = GoodsQuality.BASIC; tier <= GoodsQuality.HIGHEST; tier++) {
            double upTo = tier < GoodsQuality.HIGHEST
                ? triangularCdf((THRESHOLD[tier] - mean) / SPREAD) : 1.0D;
            p[tier] = upTo - below;
            below = upTo;
        }
        if (tradeLevel < LEGENDARY_MIN_LEVEL) {
            p[GoodsQuality.MASTERWORK] += p[GoodsQuality.LEGENDARY];
            p[GoodsQuality.LEGENDARY] = 0.0D;
        }
        if (tradeLevel < MASTERWORK_MIN_LEVEL) {
            p[GoodsQuality.EXCEPTIONAL] += p[GoodsQuality.MASTERWORK];
            p[GoodsQuality.MASTERWORK] = 0.0D;
        }
        return p;
    }

    /**
     * The usual range for a sheet line: the lowest and highest tier each at
     * least 10% likely (falls back to the single most likely tier).
     */
    public static int[] usualRange(int tradeLevel, int attribute, int workshopLevel, int toolTier) {
        double[] p = odds(tradeLevel, attribute, workshopLevel, toolTier);
        int low = -1;
        int high = -1;
        int best = 0;
        for (int tier = 0; tier < p.length; tier++) {
            if (p[tier] > p[best]) best = tier;
            if (p[tier] >= 0.10D) {
                if (low < 0) low = tier;
                high = tier;
            }
        }
        return low < 0 ? new int[] {best, best} : new int[] {low, high};
    }

    /** P(s &lt; x) for s = u1 + u2 - 1, triangular on (-1, 1). */
    static double triangularCdf(double x) {
        if (x <= -1.0D) return 0.0D;
        if (x >= 1.0D) return 1.0D;
        return x < 0.0D ? (x + 1.0D) * (x + 1.0D) / 2.0D : 1.0D - (1.0D - x) * (1.0D - x) / 2.0D;
    }

    private static double clamp01(double u) {
        return u < 0.0D ? 0.0D : u >= 1.0D ? Math.nextDown(1.0D) : u;
    }

    // ---------------------------------------------------------- effects ---

    private static int tier(int quality) {
        return Math.max(GoodsQuality.BASIC, Math.min(GoodsQuality.HIGHEST, quality));
    }

    public static int durabilityPercent(int quality) {
        return DURABILITY_PERCENT[tier(quality)];
    }

    /** Max durability of a {@code base}-durability item crafted at this quality. */
    public static int bonusMaxDamage(int base, int quality) {
        if (base <= 0) return base;
        return base + (int) Math.round(base * DURABILITY_PERCENT[tier(quality)] / 100.0D);
    }

    public static double attackBonus(int quality) {
        return ATTACK_BONUS[tier(quality)];
    }

    public static double toughnessBonus(int quality) {
        return TOUGHNESS_BONUS[tier(quality)];
    }

    public static int saturationPercent(int quality) {
        return SATURATION_PERCENT[tier(quality)];
    }

    public static double priceMultiplier(int quality) {
        return PRICE_MULTIPLIER[tier(quality)];
    }

    /**
     * Merchant quote at {@code quality} for a good the merchant buys at
     * {@code basicItemsPerCoin} (Basic). Returns {items, coins}: the fewest
     * Coins per sale (1..{@link #MAX_COINS_PER_SALE}) whose rate is within 10%
     * of {@code multiplier / basicItemsPerCoin}; the closest one otherwise.
     * Items never exceed the good's stack size, so an unstackable sword is
     * always one sword for 1-5 Coins.
     */
    public static int[] salePrice(int basicItemsPerCoin, int maxStackSize, int quality) {
        int stack = Math.max(1, Math.min(64, maxStackSize));
        int basic = Math.max(1, Math.min(basicItemsPerCoin, stack));
        double target = priceMultiplier(quality) / basic; // Coins per item
        int[] best = null;
        double bestError = Double.MAX_VALUE;
        for (int coins = 1; coins <= MAX_COINS_PER_SALE; coins++) {
            int items = (int) Math.max(1L, Math.min(stack, Math.round(coins / target)));
            double error = Math.abs(coins / (double) items - target) / target;
            if (error <= PRICE_TOLERANCE + 1.0E-9) {
                return new int[] {items, coins};
            }
            // A tie rounds half up: a Superior sword (1.5 Coins) pays 2, not 1.
            if (error <= bestError + 1.0E-9) {
                bestError = error;
                best = new int[] {items, coins};
            }
        }
        return best;
    }

    // ------------------------------------------------------------ items ---

    /**
     * Finished goods that carry a grade: equipment (anything with
     * durability: tools, weapons, armour, bows, shields), food, arrows, and
     * the finished materials a workshop sells. Intermediates (flour, malt,
     * ingots, sticks, planks, charcoal) never do -- a grade there would only
     * split chest stacks, because the next recipe ignores it.
     */
    public static boolean bearsQuality(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Item item = stack.getItem();
        if (item.components().has(DataComponents.MAX_DAMAGE)
            || item.components().has(DataComponents.FOOD)) return true;
        return stack.is(ItemTags.ARROWS) || item == Items.BARREL || item == Items.STONE_BRICKS
            || item == Items.LEATHER || item == Items.WHITE_BANNER || finishedModGood(item);
    }

    private static boolean finishedModGood(Item item) {
        try {
            return item == com.hearthstead.registry.ModItems.WOOL_BOLT.get()
                || item == com.hearthstead.registry.ModItems.TIMBER_BEAM.get()
                || item == com.hearthstead.registry.ModItems.ALE.get();
        } catch (NullPointerException | IllegalStateException unbound) {
            return false;
        }
    }

    /**
     * Writes {@code quality} onto a freshly made stack: the grade component
     * plus the baked durability and food bonuses. Basic writes nothing, so a
     * Basic crafted good is byte-identical to a vanilla one.
     */
    public static ItemStack stamp(ItemStack stack, int quality) {
        if (stack.isEmpty() || quality <= GoodsQuality.BASIC || quality > GoodsQuality.HIGHEST) {
            return stack;
        }
        stack.set(ModComponents.GOODS_QUALITY.get(), quality);
        Integer base = stack.getItem().components().get(DataComponents.MAX_DAMAGE);
        if (base != null && base > 0) {
            stack.set(DataComponents.MAX_DAMAGE, bonusMaxDamage(base, quality));
        }
        FoodProperties food = stack.getItem().components().get(DataComponents.FOOD);
        if (food != null && saturationPercent(quality) > 0) {
            float saturation = food.saturation() * (1.0F + saturationPercent(quality) / 100.0F);
            stack.set(DataComponents.FOOD, new FoodProperties(food.nutrition(), saturation,
                food.canAlwaysEat(), food.eatSeconds(), food.usingConvertsTo(), food.effects()));
        }
        return stack;
    }

    /** Percent of extra max durability this stack really has over its item's default. */
    public static int realDurabilityBonusPercent(ItemStack stack) {
        Integer base = stack.getItem().components().get(DataComponents.MAX_DAMAGE);
        if (base == null || base <= 0 || !stack.isDamageableItem()) return 0;
        return (int) Math.round((stack.getMaxDamage() - base) * 100.0D / base);
    }

    /** Percent of extra saturation this stack really has over its item's default. */
    public static int realSaturationBonusPercent(ItemStack stack) {
        FoodProperties base = stack.getItem().components().get(DataComponents.FOOD);
        FoodProperties now = stack.get(DataComponents.FOOD);
        if (base == null || now == null || base.saturation() <= 0.0F) return 0;
        return Math.round((now.saturation() - base.saturation()) * 100.0F / base.saturation());
    }
}
