package com.hearthstead.settlement.work;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Quality lane (plan/QUALITY.md): roll distribution, stat tiers, merchant prices. */
class CraftedQualityTest {

    private static final int N = 200_000;

    private static double[] sample(int level, int attribute, int workshop, int tool, long seed) {
        Random random = new Random(seed);
        double[] freq = new double[6];
        for (int i = 0; i < N; i++) {
            freq[CraftedQuality.roll(level, attribute, workshop, tool, random.nextDouble(), random.nextDouble())]++;
        }
        for (int t = 0; t < 6; t++) freq[t] /= N;
        return freq;
    }

    @Test void seededRollMatchesTheAnalyticOddsAtEverySkill() {
        int[][] cases = {{1, 10, 1, 0}, {3, 20, 1, 0}, {5, 30, 1, 0}, {7, 40, 3, 2},
            {9, 50, 1, 0}, {10, 60, 3, 2}, {10, 99, 3, 4}};
        for (int[] c : cases) {
            double[] odds = CraftedQuality.odds(c[0], c[1], c[2], c[3]);
            double total = 0.0D;
            for (double p : odds) total += p;
            assertEquals(1.0D, total, 1.0E-9, "odds sum to 1");
            double[] seen = sample(c[0], c[1], c[2], c[3], 1234L + c[0]);
            for (int t = 0; t < 6; t++) {
                assertEquals(odds[t], seen[t], 0.01D,
                    "tier " + t + " at level " + c[0] + " attr " + c[1] + " workshop " + c[2] + " tool " + c[3]);
            }
        }
    }

    @Test void theSameSeedGivesTheSameGrades() {
        Random a = new Random(99L);
        Random b = new Random(99L);
        for (int i = 0; i < 1000; i++) {
            assertEquals(CraftedQuality.roll(6, 35, 2, 1, a.nextDouble(), a.nextDouble()),
                CraftedQuality.roll(6, 35, 2, 1, b.nextDouble(), b.nextDouble()));
        }
    }

    @Test void earlyCraftsAreBasicOrFineAndTheTopGradesNeedHighSkill() {
        double[] fresh = CraftedQuality.odds(1, 10, 1, 0);
        assertTrue(fresh[GoodsQuality.BASIC] >= 0.80D, "a new crafter makes mostly Basic");
        assertEquals(1.0D, fresh[0] + fresh[1], 1.0E-9, "a new crafter makes only Basic or Fine");
        double[] third = CraftedQuality.odds(3, 20, 1, 0);
        assertTrue(third[0] + third[1] >= 0.90D, "level 3 is still mostly Basic/Fine");
        // Gates: however good the workshop and tool, no Masterwork below level 7,
        // no Legendary below level 9, even on the luckiest possible roll.
        for (int level = 1; level <= 10; level++) {
            int best = CraftedQuality.roll(level, 99, 5, 4, 0.999999D, 0.999999D);
            if (level < CraftedQuality.MASTERWORK_MIN_LEVEL) assertTrue(best <= GoodsQuality.EXCEPTIONAL);
            if (level < CraftedQuality.LEGENDARY_MIN_LEVEL) assertTrue(best <= GoodsQuality.MASTERWORK);
        }
        double[] master = CraftedQuality.odds(10, 99, 3, 4);
        assertTrue(master[GoodsQuality.LEGENDARY] > 0.0D && master[GoodsQuality.LEGENDARY] < 0.20D,
            "Legendary stays rare even for a maxed crafter: " + master[GoodsQuality.LEGENDARY]);
        double[] plainMaster = CraftedQuality.odds(10, 60, 1, 0);
        assertEquals(0.0D, plainMaster[GoodsQuality.LEGENDARY], 1.0E-12,
            "Legendary needs a better workshop or tool on top of skill");
        // Skill is the main factor: the mean climbs by more per trade level
        // than per workshop level or tool tier.
        assertTrue(CraftedQuality.PER_TRADE_LEVEL > CraftedQuality.PER_WORKSHOP_LEVEL
            && CraftedQuality.PER_TRADE_LEVEL > CraftedQuality.PER_TOOL_TIER);
        for (int level = 1; level < 10; level++) {
            assertTrue(CraftedQuality.mean(level + 1, 30, 1, 0) > CraftedQuality.mean(level, 30, 1, 0));
        }
    }

    @Test void usualRangeNamesTheLikelyGrades() {
        assertArrayEquals(new int[] {0, 1}, CraftedQuality.usualRange(1, 10, 1, 0));
        int[] mid = CraftedQuality.usualRange(5, 30, 1, 0);
        assertTrue(mid[0] <= mid[1] && mid[0] >= GoodsQuality.BASIC && mid[1] <= GoodsQuality.SUPERIOR);
    }

    @Test void tierMultipliersAreModestAndMonotonic() {
        int[] durability = {0, 10, 20, 35, 50, 75};
        double[] attack = {0, 0.5, 1, 1.5, 2, 3};
        double[] toughness = {0, 0.25, 0.5, 0.75, 1.0, 1.5};
        for (int q = 0; q <= GoodsQuality.HIGHEST; q++) {
            assertEquals(durability[q], CraftedQuality.durabilityPercent(q));
            assertEquals(attack[q], CraftedQuality.attackBonus(q), 1.0E-9);
            assertEquals(toughness[q], CraftedQuality.toughnessBonus(q), 1.0E-9);
        }
        // An iron sword (250 uses).
        int[] ironSword = {250, 275, 300, 338, 375, 438};
        for (int q = 0; q <= GoodsQuality.HIGHEST; q++) {
            assertEquals(ironSword[q], CraftedQuality.bonusMaxDamage(250, q));
        }
        for (int q = 1; q <= GoodsQuality.HIGHEST; q++) {
            assertTrue(CraftedQuality.saturationPercent(q) > CraftedQuality.saturationPercent(q - 1));
            assertTrue(CraftedQuality.priceMultiplier(q) > CraftedQuality.priceMultiplier(q - 1));
        }
        assertEquals(0, CraftedQuality.bonusMaxDamage(0, GoodsQuality.LEGENDARY));
        assertEquals(250, CraftedQuality.bonusMaxDamage(250, -3), "out-of-range grades clamp to Basic");
    }

    @Test void merchantPriceFollowsTheMultiplierAndIsCapped() {
        double[] multiplier = {1.0, 1.25, 1.5, 2.0, 3.0, 5.0};
        for (int q = 0; q <= GoodsQuality.HIGHEST; q++) {
            assertEquals(multiplier[q], CraftedQuality.priceMultiplier(q), 1.0E-9);
        }
        // Bread, 4 per Coin at Basic.
        int[][] bread = {{4, 1}, {3, 1}, {5, 2}, {2, 1}, {4, 3}, {3, 4}};
        for (int q = 0; q <= GoodsQuality.HIGHEST; q++) {
            assertArrayEquals(bread[q], CraftedQuality.salePrice(4, 64, q), "bread at grade " + q);
        }
        // An unstackable iron sword is always ONE sword, for 1/1/2/2/3/5 Coins.
        int[] swordCoins = {1, 1, 2, 2, 3, 5};
        for (int q = 0; q <= GoodsQuality.HIGHEST; q++) {
            int[] price = CraftedQuality.salePrice(3, 1, q);
            assertEquals(1, price[0], "one sword per sale");
            assertEquals(swordCoins[q], price[1], "sword Coins at grade " + q);
        }
        // Every crafted quote the merchant uses: never more than 5 Coins a sale,
        // never more items than a stack, never cheaper than the grade below.
        int[][] quotes = {{4, 64}, {8, 64}, {6, 64}, {3, 64}, {16, 64}, {32, 64}, {2, 64}, {2, 1}, {3, 1}, {4, 16}};
        for (int[] quote : quotes) {
            double previous = 0.0D;
            for (int q = 0; q <= GoodsQuality.HIGHEST; q++) {
                int[] price = CraftedQuality.salePrice(quote[0], quote[1], q);
                assertTrue(price[1] >= 1 && price[1] <= CraftedQuality.MAX_COINS_PER_SALE);
                assertTrue(price[0] >= 1 && price[0] <= quote[1]);
                double perItem = price[1] / (double) price[0];
                assertTrue(perItem >= previous - 1.0E-9,
                    "quote " + quote[0] + "/" + quote[1] + " grade " + q + " pays less than the grade below");
                previous = perItem;
            }
            // Stackable goods land within 10% of the exact multiplier.
            if (quote[1] >= 16) {
                for (int q = 0; q <= GoodsQuality.HIGHEST; q++) {
                    int[] price = CraftedQuality.salePrice(quote[0], quote[1], q);
                    double target = multiplier[q] / Math.min(quote[0], quote[1]);
                    assertEquals(target, price[1] / (double) price[0], target * 0.10D + 1.0E-9,
                        "quote " + quote[0] + " grade " + q);
                }
            }
        }
    }
}
