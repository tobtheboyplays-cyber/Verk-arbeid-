package com.hearthstead.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** QA U4: "1 Coin", never "1 Coins". */
class CoinTextTest {
    @Test
    void singularAndPlural() {
        assertEquals("1 Coin", CoinText.coins(1));
        assertEquals("0 Coins", CoinText.coins(0));
        assertEquals("4 Coins", CoinText.coins(4));
        assertEquals("12 Coins", CoinText.coins(12));
    }
}
