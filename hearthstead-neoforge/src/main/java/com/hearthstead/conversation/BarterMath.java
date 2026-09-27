package com.hearthstead.conversation;

/**
 * Pure barter valuation (JUnit-covered). All values are in copper:
 * 100 copper = 1 Gold Coin, the same coin the merchant tables pay.
 *
 * <p>Bannerlord rule: the partner is satisfied when what the player gives is
 * worth at least what the player takes, marked up by how the partner feels
 * about the player ({@link Relations#askPercent}). A pure gift (take nothing)
 * always satisfies; an empty deal never does.
 */
public final class BarterMath {
    public static final int COPPER_PER_COIN = 100;

    private BarterMath() {
    }

    /** What the partner wants in return for {@code takenValue}, rounded up. */
    public static long needed(long takenValue, int relation) {
        if (takenValue <= 0) return 0;
        return (Math.max(0, takenValue) * Relations.askPercent(relation) + 99) / 100;
    }

    public static boolean acceptable(long givenValue, long takenValue, int relation) {
        if (givenValue <= 0 && takenValue <= 0) return false;
        return Math.max(0, givenValue) >= needed(takenValue, relation);
    }

    /**
     * Satisfaction shown on the balance bar: 0 = nothing offered, 1 = exactly
     * enough, capped at 2. A gift with nothing taken shows full.
     */
    public static float satisfaction(long givenValue, long takenValue, int relation) {
        long need = needed(takenValue, relation);
        if (need <= 0) return givenValue > 0 ? 2.0F : 0.0F;
        return (float) Math.min(2.0D, Math.max(0L, givenValue) / (double) need);
    }

    /** Relation earned by overpaying: +1 per whole coin of surplus, at most +5. */
    public static int goodwill(long givenValue, long takenValue, int relation) {
        long surplus = Math.max(0, givenValue) - needed(takenValue, relation);
        if (surplus < COPPER_PER_COIN) return 0;
        return (int) Math.min(5L, surplus / COPPER_PER_COIN);
    }

    /**
     * Hostile-input check for one side of an offer: every line names a
     * distinct index inside {@code available}, and asks for 1..available
     * items of it. Duplicate rows, zero, negative or oversized counts all
     * refuse the whole offer (never summed, so nothing can overflow).
     */
    public static boolean validLines(int[] indexes, int[] counts, int[] available) {
        if (indexes == null || counts == null || available == null || indexes.length != counts.length) return false;
        boolean[] seen = new boolean[available.length];
        for (int i = 0; i < indexes.length; i++) {
            int index = indexes[i];
            if (index < 0 || index >= available.length || seen[index]) return false;
            seen[index] = true;
            if (counts[i] < 1 || counts[i] > available[index]) return false;
        }
        return true;
    }

    /** Facts about one item that its value depends on (read by the game adapter). */
    public record ItemFacts(String id, int rarity, int nutrition, boolean enchanted,
                            int damage, int maxDamage, boolean coin) {
    }

    /**
     * Copper value of ONE item. Anchored to the Gold Coin merchant quotes
     * (8 logs or 16 field goods or 8 fish per coin); everything else falls
     * back to rarity plus food value, and worn tools lose value with wear.
     */
    public static int unitValue(ItemFacts facts) {
        if (facts == null) return 0;
        if (facts.coin()) return COPPER_PER_COIN;
        int base = switch (facts.id() == null ? "" : facts.id()) {
            case "minecraft:oak_log", "minecraft:spruce_log", "minecraft:birch_log", "minecraft:jungle_log",
                 "minecraft:acacia_log", "minecraft:dark_oak_log", "minecraft:mangrove_log",
                 "minecraft:cherry_log" -> 12;
            case "minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks" -> 3;
            case "minecraft:wheat", "minecraft:carrot", "minecraft:potato", "minecraft:beetroot",
                 "minecraft:pumpkin" -> 6;
            case "minecraft:cod", "minecraft:salmon", "minecraft:iron_ingot", "minecraft:honey_bottle" -> 12;
            case "minecraft:gold_ingot" -> 60;
            case "minecraft:emerald" -> 100;
            case "minecraft:diamond" -> 400;
            case "minecraft:bread" -> 18;
            case "minecraft:cooked_beef", "minecraft:cooked_porkchop", "minecraft:cooked_mutton",
                 "minecraft:cooked_chicken", "minecraft:cooked_cod", "minecraft:cooked_salmon" -> 20;
            case "minecraft:leather", "minecraft:book" -> 15;
            case "minecraft:white_wool", "minecraft:apple" -> 8;
            case "minecraft:string", "minecraft:feather", "minecraft:coal" -> 4;
            case "minecraft:stick", "minecraft:cobblestone", "minecraft:dirt", "minecraft:gravel",
                 "minecraft:sand" -> 1;
            default -> -1;
        };
        if (base < 0) {
            base = switch (Math.max(0, Math.min(3, facts.rarity()))) {
                case 1 -> 25;
                case 2 -> 80;
                case 3 -> 200;
                default -> 4;
            };
            base += Math.max(0, facts.nutrition()) * 2;
        }
        if (facts.enchanted()) base = base * 3 / 2;
        if (facts.maxDamage() > 0 && facts.damage() > 0) {
            float left = 1.0F - Math.min(1.0F, facts.damage() / (float) facts.maxDamage());
            base = Math.max(1, Math.round(base * left));
        }
        return Math.max(0, base);
    }

    /** Whole coins, rounded down, for labels ("12 c"). */
    public static String label(long copper) {
        long coins = copper / COPPER_PER_COIN;
        long rest = Math.abs(copper % COPPER_PER_COIN);
        return rest == 0 ? coins + "" : coins + "." + (rest < 10 ? "0" : "") + rest;
    }
}
