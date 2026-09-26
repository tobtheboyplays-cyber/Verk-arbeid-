package com.hearthstead.entity.animation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BagToChestAnimationContractTest {
    @Test
    void frozenCandidateIdentityAndContactsStayBound() {
        assertEquals("e91d5ba0f661be9d",
            BagToChestAnimationContract.CANDIDATE_HASH);
        assertEquals(80, BagToChestAnimationContract.DURATION_TICKS);
        assertEquals(12, BagToChestAnimationContract.BAG_WORLD_CONTACT_TICK);
        assertEquals(48, BagToChestAnimationContract.DEPOSIT_COMMIT_TICK);
        assertEquals(64, BagToChestAnimationContract.LID_CLOSED_TICK);
    }

    @Test
    void eachCycleGrantsExactlyOneCommitTicket() {
        List<Integer> grants = new ArrayList<>();
        boolean committed = false;
        for (int tick = 1; tick <= 240; tick++) {
            if (BagToChestAnimationContract.beginsCycle(tick)) {
                committed = false;
            }
            for (int duplicateQuery = 0; duplicateQuery < 3; duplicateQuery++) {
                if (BagToChestAnimationContract.mayCommit(tick, committed)) {
                    committed = true;
                    grants.add(tick);
                }
            }
        }
        assertEquals(List.of(48, 128, 208), grants);
        assertFalse(BagToChestAnimationContract.beginsCycle(0));
        assertTrue(BagToChestAnimationContract.beginsCycle(80));
    }
    @Test
    void threeBundlesStayGroundedAndOnlyFinalBundleReachesPickup() {
        int clock = 0;
        int bundles = 0;
        int pickupFrames = 0;
        boolean committed = false;
        for (int elapsed = 0; elapsed < 200; elapsed++) {
            clock++;
            if (BagToChestAnimationContract.mayCommit(clock, committed)) {
                committed = true;
                bundles++;
                assertFalse(BagToChestAnimationContract.mayCommit(clock, committed));
            }
            boolean finalBundle = bundles == 3;
            if (BagToChestAnimationContract.continuesGroundedSession(clock,
                    committed, finalBundle)) {
                clock = BagToChestAnimationContract.GROUNDED_REPEAT_TICK;
                committed = false;
            }
            if (clock > 64) {
                assertEquals(3, bundles, "pickup must follow the final bundle only");
                pickupFrames++;
            }
            if (clock == 80) break;
        }
        assertEquals(3, bundles);
        assertEquals(16, pickupFrames);
        assertFalse(BagToChestAnimationContract.continuesGroundedSession(64, false, false));
    }
}
