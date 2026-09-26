package com.hearthstead.network;

import com.hearthstead.menu.SettlerInventoryMenu;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SettlerSheetReachTest {

    /**
     * The client sheet stays open while a working settler walks up to 24
     * blocks away. Every server gate behind its buttons (action reach, viewer
     * refresh, and the inventory menu's stillValid) must accept that same
     * window, or the Inventory button is silently refused.
     */
    @Test
    void sheetActionsAndInventoryMenuShareTheSheetsLiveWindow() {
        assertEquals(24.0 * 24.0, SettlerNetwork.SHEET_REACH_SQUARED);
        assertEquals(SettlerNetwork.SHEET_REACH_SQUARED, SettlerInventoryMenu.REACH_SQUARED);
    }
}
