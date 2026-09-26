package com.hearthstead.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ConversationMathTest {
    // ------------------------------------------------------ persuasion ---

    @Test
    void chanceIsBasePlusRelationReputationAndSkill() {
        // 30 + 40/4 + 25/5 + 7 = 52
        assertEquals(52, Persuasion.chance(30, 40, 25, 7));
        assertEquals(30, Persuasion.chance(30, 0, 0, 0));
        // Negative relation lowers it: 30 - 60/4 - 20/5 = 11
        assertEquals(11, Persuasion.chance(30, -60, -20, 0));
    }

    @Test
    void chanceIsClampedFiveToNinetyFive() {
        assertEquals(95, Persuasion.chance(100, 100, 100, 20));
        assertEquals(5, Persuasion.chance(0, -100, -100, -20));
        assertEquals(95, Persuasion.chance(Integer.MAX_VALUE, 0, 0, 0));
        assertEquals(5, Persuasion.chance(Integer.MIN_VALUE, 0, 0, 0));
    }

    @Test
    void outOfRangeInputsAreBounded() {
        // Relation/reputation clamp to +-100, skill to +-20.
        assertEquals(Persuasion.chance(10, 100, 100, 20), Persuasion.chance(10, 9999, 9999, 9999));
    }

    @Test
    void rollSucceedsStrictlyUnderTheChance() {
        assertTrue(Persuasion.succeeds(62, 0));
        assertTrue(Persuasion.succeeds(62, 61));
        assertFalse(Persuasion.succeeds(62, 62));
        assertFalse(Persuasion.succeeds(62, 99));
        // Never certain either way.
        assertFalse(Persuasion.succeeds(100, 95));
        assertTrue(Persuasion.succeeds(0, 4));
        assertFalse(Persuasion.succeeds(50, -1));
    }

    @Test
    void skillsComeFromLevelArmourAndRenown() {
        assertEquals(0, Persuasion.skill("charisma", 0, 20, 0));
        assertEquals(10, Persuasion.skill("charisma", 20, 0, 0));
        assertEquals(15, Persuasion.skill("charisma", 200, 0, 0));
        assertEquals(20, Persuasion.skill("charisma", 200, 0, 9));
        assertEquals(10, Persuasion.skill("intimidation", 0, 20, 0));
        assertEquals(0, Persuasion.skill("unknown", 30, 30, 5));
    }

    // -------------------------------------------------------- relations ---

    @Test
    void relationClampsAndAppliesWithoutOverflow() {
        assertEquals(100, Relations.apply(90, 50));
        assertEquals(-100, Relations.apply(-90, -50));
        assertEquals(100, Relations.apply(0, Integer.MAX_VALUE));
        assertEquals(-100, Relations.apply(0, Integer.MIN_VALUE));
        assertEquals(15, Relations.apply(10, 5));
        assertEquals(100, Relations.apply(5000, 0));
    }

    @Test
    void tiersMatchTheBar() {
        assertEquals(Relations.Tier.HOSTILE, Relations.tier(-100));
        assertEquals(Relations.Tier.HOSTILE, Relations.tier(-50));
        assertEquals(Relations.Tier.WARY, Relations.tier(-49));
        assertEquals(Relations.Tier.WARY, Relations.tier(-10));
        assertEquals(Relations.Tier.NEUTRAL, Relations.tier(-9));
        assertEquals(Relations.Tier.NEUTRAL, Relations.tier(24));
        assertEquals(Relations.Tier.FRIENDLY, Relations.tier(25));
        assertEquals(Relations.Tier.FRIENDLY, Relations.tier(69));
        assertEquals(Relations.Tier.LOYAL, Relations.tier(70));
        assertEquals(0.5F, Relations.barFraction(0), 1.0E-6F);
        assertEquals(1.0F, Relations.barFraction(300), 1.0E-6F);
    }

    @Test
    void askPercentFollowsRelation() {
        assertEquals(115, Relations.askPercent(0));
        assertEquals(90, Relations.askPercent(100));
        assertEquals(140, Relations.askPercent(-100));
    }

    // ----------------------------------------------------------- barter ---

    @Test
    void neededIsMarkedUpByRelationAndRoundedUp() {
        assertEquals(115, BarterMath.needed(100, 0));
        assertEquals(90, BarterMath.needed(100, 100));
        assertEquals(140, BarterMath.needed(100, -100));
        assertEquals(2, BarterMath.needed(1, 0)); // 1.15 rounds up
        assertEquals(0, BarterMath.needed(0, 0));
    }

    @Test
    void acceptanceNeedsEnoughAndSomething() {
        assertFalse(BarterMath.acceptable(0, 0, 0));
        assertTrue(BarterMath.acceptable(5, 0, 0)); // a gift
        assertFalse(BarterMath.acceptable(114, 100, 0));
        assertTrue(BarterMath.acceptable(115, 100, 0));
        assertTrue(BarterMath.acceptable(90, 100, 100));
        assertFalse(BarterMath.acceptable(139, 100, -100));
    }

    @Test
    void satisfactionBarAndGoodwill() {
        assertEquals(1.0F, BarterMath.satisfaction(115, 100, 0), 1.0E-6F);
        assertEquals(0.0F, BarterMath.satisfaction(0, 100, 0), 1.0E-6F);
        assertEquals(2.0F, BarterMath.satisfaction(10_000, 100, 0), 1.0E-6F);
        assertEquals(2.0F, BarterMath.satisfaction(1, 0, 0), 1.0E-6F);
        assertEquals(0, BarterMath.goodwill(150, 100, 0));
        assertEquals(1, BarterMath.goodwill(215, 100, 0));
        assertEquals(5, BarterMath.goodwill(100_000, 100, 0));
    }

    @Test
    void valuesAreAnchoredToTheCoinQuotes() {
        assertEquals(100, BarterMath.unitValue(facts("hearthstead:gold_coin", 0, 0, true)));
        // 8 logs, 16 field goods or 8 fish per coin.
        assertEquals(12, BarterMath.unitValue(facts("minecraft:oak_log", 0, 0, false)));
        assertEquals(6, BarterMath.unitValue(facts("minecraft:wheat", 0, 0, false)));
        assertEquals(12, BarterMath.unitValue(facts("minecraft:cod", 0, 0, false)));
        // Fallbacks: rarity + food.
        assertEquals(4, BarterMath.unitValue(facts("mod:thing", 0, 0, false)));
        assertEquals(25 + 8, BarterMath.unitValue(facts("mod:thing", 1, 4, false)));
        assertEquals(0, BarterMath.unitValue(null));
    }

    @Test
    void wearAndEnchantmentChangeValue() {
        var sword = new BarterMath.ItemFacts("minecraft:iron_sword", 0, 0, false, 0, 250, false);
        var worn = new BarterMath.ItemFacts("minecraft:iron_sword", 0, 0, false, 125, 250, false);
        var enchanted = new BarterMath.ItemFacts("minecraft:iron_sword", 0, 0, true, 0, 250, false);
        assertEquals(4, BarterMath.unitValue(sword));
        assertEquals(2, BarterMath.unitValue(worn));
        assertEquals(6, BarterMath.unitValue(enchanted));
    }

    @Test
    void coinLabel() {
        assertEquals("12", BarterMath.label(1200));
        assertEquals("1.05", BarterMath.label(105));
        assertEquals("0.50", BarterMath.label(50));
    }

    @Test
    void offerLinesRefuseDuplicatesAndBadCounts() {
        int[] available = {64, 3, 0};
        assertTrue(BarterMath.validLines(new int[] {0, 1}, new int[] {10, 3}, available));
        assertTrue(BarterMath.validLines(new int[0], new int[0], available));
        // Duplicate rows (the Integer.MAX_VALUE + 1 overflow trick) are refused, never summed.
        assertFalse(BarterMath.validLines(new int[] {1, 1, 0}, new int[] {Integer.MAX_VALUE, 1, 1}, available));
        assertFalse(BarterMath.validLines(new int[] {0, 0}, new int[] {1, 1}, available));
        assertFalse(BarterMath.validLines(new int[] {0}, new int[] {0}, available));
        assertFalse(BarterMath.validLines(new int[] {0}, new int[] {-5}, available));
        assertFalse(BarterMath.validLines(new int[] {0}, new int[] {65}, available));
        assertFalse(BarterMath.validLines(new int[] {2}, new int[] {1}, available));
        assertFalse(BarterMath.validLines(new int[] {3}, new int[] {1}, available));
        assertFalse(BarterMath.validLines(new int[] {-1}, new int[] {1}, available));
        assertFalse(BarterMath.validLines(new int[] {0, 1}, new int[] {1}, available));
        assertFalse(BarterMath.validLines(null, new int[0], available));
    }

    @Test
    void hugeTakenValuesNeverGoNegative() {
        long huge = (long) Integer.MAX_VALUE * 400L;
        assertTrue(BarterMath.needed(huge, 0) > huge);
        assertFalse(BarterMath.acceptable(1, huge, 100));
        assertEquals(0, BarterMath.needed(-5, 0));
    }

    private static BarterMath.ItemFacts facts(String id, int rarity, int nutrition, boolean coin) {
        return new BarterMath.ItemFacts(id, rarity, nutrition, false, 0, 0, coin);
    }
}
