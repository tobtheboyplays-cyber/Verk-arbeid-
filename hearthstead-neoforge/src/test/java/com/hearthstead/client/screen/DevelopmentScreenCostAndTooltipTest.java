package com.hearthstead.client.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevelopmentScreenCostAndTooltipTest {
    @Test
    void learnLabelShowsOnlyTheCoinLineNotCoinsPlusGoods() {
        // A node priced 2 Coins + goods used to read "Learn - 8 Coins".
        assertEquals("Learn - 2 Coins +", DevelopmentScreen.actionLabel(false, false, 2, 2));
        assertEquals("Learn - 6 Coins", DevelopmentScreen.actionLabel(false, false, 6, 0));
        assertEquals("Learn - 1 Coin", DevelopmentScreen.actionLabel(false, false, 1, 0));
        assertEquals("Learn - Free", DevelopmentScreen.actionLabel(false, false, 0, 0));
        assertEquals("Learn - Goods", DevelopmentScreen.actionLabel(false, false, 0, 3));
        assertEquals("Learned", DevelopmentScreen.actionLabel(false, true, 2, 2));
        assertEquals("Buy - 8 Coins +", DevelopmentScreen.actionLabel(true, false, 8, 1));
        assertEquals("Owned", DevelopmentScreen.actionLabel(true, true, 8, 1));
    }

    @Test
    void tooltipWrapWidthAlwaysFitsBesideTheCursor() {
        for (int screenWidth : new int[] {200, 320, 427, 480, 640, 960, 1920}) {
            int wrap = DevelopmentScreen.tooltipWrapWidth(screenWidth);
            assertTrue(wrap >= 40 && wrap <= 240);
            if (screenWidth >= 112) {
                // Vanilla tooltip box adds ~8px of frame; one half of the
                // screen (minus the 12/24px cursor offsets) must hold it.
                assertTrue(wrap + 8 <= screenWidth / 2 - 8,
                    "tooltip must fit on one side at width " + screenWidth);
            }
        }
    }
}
