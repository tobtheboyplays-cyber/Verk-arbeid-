package com.hearthstead.client.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Later action must outlive the native observer's two-frame barrier. */
class BlessingScreenTransitionContractTest {

    @Test
    void savedForLaterReceiptStaysAlivePastTheAckBarrierThenCloses() {
        int remaining = BlessingScreen.LATER_RECEIPT_TICKS;
        assertTrue(remaining >= 120,
            "30 FPS needs six seconds to produce the mandatory 180 frames");
        remaining = BlessingScreen.advanceLaterReceipt(remaining);
        remaining = BlessingScreen.advanceLaterReceipt(remaining);
        assertTrue(remaining > 0,
            "the explicit blessing_later state must survive the ACK barrier");

        for (int tick = 0; tick < BlessingScreen.LATER_RECEIPT_TICKS; tick++) {
            remaining = BlessingScreen.advanceLaterReceipt(remaining);
        }
        assertEquals(0, remaining);
        assertEquals(0, BlessingScreen.advanceLaterReceipt(0));
    }
}
