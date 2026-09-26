package com.hearthstead.settlement.work;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HuntingGroundsPolicyTest {

    @Test
    void intervalAllowsTheFirstSpawnThenWaits() {
        assertTrue(HuntingGroundsPolicy.due(100L, Long.MIN_VALUE, 3000));
        assertFalse(HuntingGroundsPolicy.due(2999L, 0L, 3000));
        assertTrue(HuntingGroundsPolicy.due(3000L, 0L, 3000));
    }

    @Test
    void capStopsAtTheLimitAndZeroDisables() {
        assertTrue(HuntingGroundsPolicy.belowCap(7, 8));
        assertFalse(HuntingGroundsPolicy.belowCap(8, 8));
        assertFalse(HuntingGroundsPolicy.belowCap(0, 0));
    }

    @Test
    void speciesTopsUpTheMostNumerousHerdSoTheFloorCanBeCrossed() {
        List<String> eligible = List.of("sheep", "pig", "chicken", "cow");
        Map<String, Integer> counts = Map.of("cow", 4, "sheep", 1);
        assertEquals("cow", HuntingGroundsPolicy.chooseSpecies(eligible, counts, 99, 0));
        assertEquals("sheep", HuntingGroundsPolicy.chooseSpecies(eligible, Map.of(), 50, 3),
            "with no herd yet the biome's first species starts one");
    }

    @Test
    void varietyRollPicksAnyEligibleSpecies() {
        List<String> eligible = List.of("sheep", "pig");
        assertEquals("pig", HuntingGroundsPolicy.chooseSpecies(eligible, Map.of("sheep", 9), 0, 1));
        assertNull(HuntingGroundsPolicy.chooseSpecies(List.<String>of(), Map.of(), 99, 0));
    }

    @Test
    void coreAndBuildingMarginsExclude() {
        assertTrue(HuntingGroundsPolicy.insideCore(10, 10, 0, 0, 16));
        assertFalse(HuntingGroundsPolicy.insideCore(20, 0, 0, 0, 16));
        assertTrue(HuntingGroundsPolicy.nearBox(13, 64, 5, 0, 60, 0, 9, 66, 9, 4));
        assertFalse(HuntingGroundsPolicy.nearBox(14, 64, 5, 0, 60, 0, 9, 66, 9, 4));
    }

    @Test
    void playersWithinTheSightRadiusBlockASpawn() {
        List<double[]> players = List.of(new double[] {0, 64, 0});
        assertFalse(HuntingGroundsPolicy.outOfSight(10, 64, 10, players, 24));
        assertTrue(HuntingGroundsPolicy.outOfSight(30, 64, 0, players, 24));
        assertTrue(HuntingGroundsPolicy.outOfSight(1, 64, 1, List.of(), 24));
    }

    /** captain1 soak: 3 cows + 3 pigs + 3 rabbits = 9 over the cap of 8, none huntable. */
    @Test
    void anEvenSplitOverTheCapStillTopsUpUntilOneHerdIsHuntable() {
        Map<String, Integer> split = Map.of("cow", 3, "pig", 3, "rabbit", 3);
        assertTrue(HuntingGroundsPolicy.mayAdd(split, 9, 8, 4),
            "cap and floor together must not deadlock the hunter");
        Map<String, Integer> huntable = Map.of("cow", 5, "pig", 3, "rabbit", 3);
        assertFalse(HuntingGroundsPolicy.mayAdd(huntable, 11, 8, 4),
            "once one herd is huntable the cap applies again");
        assertTrue(HuntingGroundsPolicy.mayAdd(huntable, 7, 8, 4), "below the cap it always may");
        assertFalse(HuntingGroundsPolicy.mayAdd(split, 0, 0, 4), "cap 0 disables");
    }

    /** Topping up past the cap is bounded: at most floor+1 extra animals. */
    @Test
    void topUpPastTheCapIsBounded() {
        java.util.HashMap<String, Integer> counts = new java.util.HashMap<>(Map.of("cow", 3, "pig", 3, "rabbit", 3));
        int live = 9;
        int added = 0;
        List<String> eligible = List.of("cow", "pig", "rabbit");
        while (HuntingGroundsPolicy.mayAdd(counts, live, 8, 4) && added < 100) {
            String species = HuntingGroundsPolicy.chooseSpecies(eligible, counts, 100, 0);
            counts.merge(species, 1, Integer::sum);
            live++;
            added++;
        }
        assertTrue(added <= 5, "added " + added);
        assertTrue(counts.values().stream().anyMatch(n -> n > 4), "one herd must become huntable");
    }
}
