package com.hearthstead.client.render;

import com.hearthstead.entity.SettlerActivity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SettlerRendererHeldItemPresentationTest {
    @Test
    void haulingLogSuppressesAndAllOrdinaryActivitiesRestorePresentation() {
        for (SettlerActivity activity : SettlerActivity.values()) {
            if (activity == SettlerActivity.HAULING_LOG
                // Hunter rework: both hands belong to the carcass (carry) or
                // to the skinning/cleaving props drawn by CarcassCarryLayer.
                || activity == SettlerActivity.HAULING_CARCASS
                || activity == SettlerActivity.WORK_BUTCHER
                || activity == SettlerActivity.WORK_SKIN) {
                assertFalse(renders(activity, false, -1L, false, -1L));
            } else {
                assertTrue(renders(activity, false, -1L, false, -1L),
                    () -> "held item did not restore for " + activity);
            }
        }
    }

    @Test
    void setDownUsesTheRealOneShotClockForTheHaulToToolHandoff() {
        assertFalse(renders(SettlerActivity.GATHERING_LOG, true, 0L, false, -1L));
        assertFalse(renders(SettlerActivity.GATHERING_LOG, true, 1199L, false, -1L));
        assertTrue(renders(SettlerActivity.GATHERING_LOG, true, 1200L, false, -1L));
        assertTrue(renders(SettlerActivity.GATHERING_LOG, true, 1400L, false, -1L));
    }

    @Test
    void pickupUsesTheRealOneShotClockForTheToolToHaulHandoff() {
        assertTrue(renders(SettlerActivity.GATHERING_LOG, false, -1L, true, 0L));
        assertTrue(renders(SettlerActivity.GATHERING_LOG, false, -1L, true, 599L));
        assertFalse(renders(SettlerActivity.GATHERING_LOG, false, -1L, true, 600L));
        assertFalse(renders(SettlerActivity.GATHERING_LOG, false, -1L, true, 1600L));
    }

    @Test
    void haulingLogRemainsSuppressedAcrossTransitionFlags() {
        assertFalse(renders(SettlerActivity.HAULING_LOG, true, 1400L, false, -1L));
        assertFalse(renders(SettlerActivity.HAULING_LOG, false, -1L, true, 0L));
    }

    private static boolean renders(SettlerActivity activity,
                                   boolean down, long downMs,
                                   boolean up, long upMs) {
        return SettlerRenderer.rendersHeldItemsFor(
            activity, down, downMs, up, upMs);
    }
}
