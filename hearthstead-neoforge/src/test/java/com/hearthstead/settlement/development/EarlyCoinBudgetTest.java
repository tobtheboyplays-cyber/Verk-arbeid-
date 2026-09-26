package com.hearthstead.settlement.development;

import com.hearthstead.entity.Profession;
import com.hearthstead.event.GoldCoinTrades;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Early-Coin balance, 26 Sep ("you fix it"). A QA playthrough measured about
 * 31 Coins of early nodes and emblems against an 8-Coin first purse, then 12
 * per merchant visit (one every 20 real minutes). Chosen numbers:
 * first purse 12, Lumber Camp and Home 1 Coin each, Guard and Archer Emblems
 * 3 Coins each. No new Coin source, no tax (postponed by the owner).
 */
class EarlyCoinBudgetTest {

    private static int node(DevelopmentNode node) {
        return node.coinCost();
    }

    private static int emblem(Profession profession) {
        return JobEmblemCatalog.forProfession(profession).coinPrice();
    }

    @Test
    void chosenNumbersArePinned() {
        assertEquals(12, GoldCoinTrades.FIRST_VISIT_PURSE);
        assertEquals(12, GoldCoinTrades.RETURNING_VISIT_PURSE);
        assertEquals(1, node(DevelopmentNode.TIMBER_RIGHTS));
        assertEquals(1, node(DevelopmentNode.HOME));
        assertEquals(2, node(DevelopmentNode.STORES_AND_ROADS));
        assertEquals(4, node(DevelopmentNode.HOSPITALITY));
        assertEquals(4, node(DevelopmentNode.FIRST_WATCH));
        assertEquals(1, emblem(Profession.LUMBERER));
        assertEquals(3, emblem(Profession.GUARD));
        assertEquals(3, emblem(Profession.ARCHER));
        assertEquals(2, emblem(Profession.HUNTER), "post-raid emblems are unchanged");
    }

    @Test
    void oneBasicLogShipmentFundsTheFirstSteps() {
        // A brand-new player only has Basic logs: 4 uses x 8 logs = 4 Coins.
        int firstSteps = node(DevelopmentNode.TIMBER_RIGHTS) + emblem(Profession.LUMBERER)
            + node(DevelopmentNode.HOME);
        assertTrue(firstSteps <= GoldCoinTrades.BASIC_MAX_USES,
            "Lumber Camp, Lumberer and Home must fit one Basic log row: " + firstSteps);
    }

    @Test
    void preFirstRaidTechAndEmblemsFitThreeMerchantVisits() {
        int nodes = node(DevelopmentNode.TIMBER_RIGHTS) + node(DevelopmentNode.STORES_AND_ROADS)
            + node(DevelopmentNode.CULTIVATED_GROUND) + node(DevelopmentNode.HOME)
            + node(DevelopmentNode.HOSPITALITY) + node(DevelopmentNode.FIRST_WATCH);
        int emblems = emblem(Profession.LUMBERER) + emblem(Profession.COURIER)
            + emblem(Profession.FARMER) + emblem(Profession.INNKEEPER)
            + emblem(Profession.GUARD) + emblem(Profession.ARCHER);
        assertEquals(27, nodes + emblems, "was 31 before the 26 Sep balance");
        // Visits arrive at 0, 20 and 40 real minutes: the whole early set is
        // reachable inside the first ~40-60 minutes, before two 4-8 Coin
        // recruits, and the first raid is 100-120 minutes in.
        int threeVisits = GoldCoinTrades.FIRST_VISIT_PURSE + 2 * GoldCoinTrades.RETURNING_VISIT_PURSE;
        assertTrue(nodes + emblems <= threeVisits,
            "early set " + (nodes + emblems) + " must fit three purses " + threeVisits);
        assertTrue(nodes + emblems > GoldCoinTrades.FIRST_VISIT_PURSE + GoldCoinTrades.RETURNING_VISIT_PURSE,
            "not trivial: two visits must not buy the whole early set");
    }
}
