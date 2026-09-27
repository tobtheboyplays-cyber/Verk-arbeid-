package com.hearthstead.settlement.work;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FisherProgressionTest {
    @Test void everySkillTierPreservesFoodMajorityAndBoundsRareEconomicOutput() {
        for(int skill : new int[]{0,24,25,49,50,74,75,89,90,99}) {
            int[] counts=new int[6];
            for(int roll=0;roll<10000;roll++) counts[FisherProgression.quality(skill,roll)]++;
            assertEquals(8500,counts[GoodsQuality.BASIC],"85% of catches remain ordinary food");
            assertTrue(counts[GoodsQuality.LEGENDARY]<=5,"legendary output capped at 0.05%");
            if(skill<90) assertEquals(0,counts[GoodsQuality.LEGENDARY]);
            if(skill<75) assertEquals(0,counts[GoodsQuality.MASTERWORK]);
            if(skill<50) assertEquals(0,counts[GoodsQuality.EXCEPTIONAL]);
            if(skill<25) assertEquals(0,counts[GoodsQuality.SUPERIOR]);
        }
    }
    @Test void reachableSkillsImproveYieldWithoutReturningToTwoSecondCatchLoop() {
        int previous=301;
        for(int skill:new int[]{0,25,50,75,90}) {
            int ticks=FisherProgression.cycleTicks(skill);
            assertTrue(ticks<previous && ticks>=160); previous=ticks;
        }
        assertEquals(300,FisherProgression.cycleTicks(5));
        assertEquals(160,FisherProgression.cycleTicks(99));
    }
}
