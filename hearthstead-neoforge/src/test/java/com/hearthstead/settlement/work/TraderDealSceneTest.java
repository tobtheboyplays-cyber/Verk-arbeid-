package com.hearthstead.settlement.work;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TRADER lane: the deal's timing contract and the counter-side rule. */
final class TraderDealSceneTest {

    @Test
    void beatsSitInsideTheSceneInStoryOrder() {
        assertEquals(200, TraderDealScene.DEAL_TICKS, "the clip is 10.0 s");
        assertEquals(130, TraderDealScene.COMMIT_TICK, "the sale is the 6.5 s handover");
        for (int t : TraderDealScene.COUNT_TICKS) {
            assertTrue(t > 0 && t < TraderDealScene.COMMIT_TICK, "coins are counted before the handover: " + t);
            assertTrue(TraderDealScene.tallyAt(t));
        }
        for (int t : TraderDealScene.WRITE_TICKS) {
            assertTrue(t > TraderDealScene.COMMIT_TICK && t < TraderDealScene.DEAL_TICKS,
                "the ledger is written after the sale: " + t);
            assertTrue(TraderDealScene.tallyAt(t));
        }
        assertFalse(TraderDealScene.tallyAt(TraderDealScene.COMMIT_TICK), "no tally on the handover itself");
        assertFalse(TraderDealScene.tallyAt(0));
        int tallies = 0;
        for (int t = 0; t <= TraderDealScene.DEAL_TICKS; t++) {
            if (TraderDealScene.tallyAt(t)) tallies++;
        }
        assertEquals(6, tallies, "three coins + three strokes, nothing else");
    }

    @Test
    void theTraderStandsOnTheStorageSideOfTheCounter() {
        assertTrue(TraderCounter.chooseTraderSide(2.0, 30.0));
        assertFalse(TraderCounter.chooseTraderSide(30.0, 2.0));
        assertTrue(TraderCounter.chooseTraderSide(5.0, 5.0), "a tie keeps the first face");
    }

    @Test
    void theMerchantLeaseIsShortAndTheWaitIsBounded() {
        assertTrue(MerchantCounterCall.LEASE_TICKS <= 60, "a lapsed call frees the merchant within 3 s");
        assertTrue(MerchantCounterCall.atCell(10.5, 64.0, 20.5, new net.minecraft.core.BlockPos(10, 64, 20)));
        assertFalse(MerchantCounterCall.atCell(11.6, 64.0, 20.5, new net.minecraft.core.BlockPos(10, 64, 20)));
        assertFalse(MerchantCounterCall.atCell(10.5, 65.0, 20.5, new net.minecraft.core.BlockPos(10, 64, 20)));
    }
}
