package com.hearthstead.settlement.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SCENARIO lane (26 Sep): the merchant's purse grows with the village as
 * ECONOMY.md sets it (12 Coins + 2 for every 5 settlers, capped at 40) on a
 * real server (tuned mode; only the GameTest server is neutral).
 */
class ScenarioMerchantPurseTest {

    @Test
    void thePurseGrowsTwoCoinsPerFiveSettlersUpToTheCap() {
        assertEquals(12, EconomyConfig.merchantPurse(null, 0, 99), "a new village");
        assertEquals(12, EconomyConfig.merchantPurse(null, 4, 99), "four founders");
        assertEquals(14, EconomyConfig.merchantPurse(null, 5, 99));
        assertEquals(16, EconomyConfig.merchantPurse(null, 10, 99));
        assertEquals(26, EconomyConfig.merchantPurse(null, 35, 99));
        assertEquals(40, EconomyConfig.merchantPurse(null, 70, 99), "cap reached at 70 settlers");
        assertEquals(40, EconomyConfig.merchantPurse(null, 500, 99), "never above the cap");
        assertEquals(12, EconomyConfig.merchantPurse(null, -3, 99), "a broken count never shrinks it below the base");
    }
}
