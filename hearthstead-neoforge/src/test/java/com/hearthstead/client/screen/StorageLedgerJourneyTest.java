package com.hearthstead.client.screen;

import com.hearthstead.network.HearthMayorAction;
import com.hearthstead.settlement.journey.JourneyDefinition;
import com.hearthstead.settlement.journey.JourneyEvent;
import com.hearthstead.settlement.journey.JourneyIds;
import com.hearthstead.settlement.journey.JourneyStep;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Tasks page is gone, so the Storage page must carry the Journey from
 * fj_230 ("Check the Open Requests") to fj_240: opening Storage sends the one
 * action the server turns into REQUEST_LEDGER_VIEW_OPENED (server side
 * covered end to end by FirstRaidReadinessGameTests#viewRequestLedger).
 */
class StorageLedgerJourneyTest {

    @Test
    void openingStorageSendsTheLedgerActionTheServerAccepts() {
        BlockPos pos = new BlockPos(110, 72, -115);
        UUID settlement = UUID.randomUUID();
        HearthMayorAction action = HearthScreen.storageLedgerRequest(pos, settlement, 7);
        assertEquals(HearthMayorAction.Kind.OPEN_REQUEST_LEDGER, action.kind());
        // HearthNetwork.openRequestLedger ignores any other target or revision.
        assertEquals(HearthMayorAction.NO_ID, action.target());
        assertEquals(0, action.revision());
        assertEquals(pos, action.hearthPos());
        assertEquals(settlement, action.settlementId());
        assertEquals(7, action.containerId());
    }

    @Test
    void onlyTheOpeningTransitionAsks() {
        assertTrue(HearthScreen.storageOpenRequestsLedger(true, false));
        assertFalse(HearthScreen.storageOpenRequestsLedger(true, true));
        assertFalse(HearthScreen.storageOpenRequestsLedger(false, true));
        assertFalse(HearthScreen.storageOpenRequestsLedger(false, false));
    }

    @Test
    void theLedgerViewCompletesFj230WhichUnblocksFj240() {
        JourneyStep fj230 = JourneyDefinition.CURRENT.step(JourneyIds.FJ_230_OPEN_REQUEST_LEDGER).orElseThrow();
        JourneyStep fj240 = JourneyDefinition.CURRENT.step(JourneyIds.FJ_240_REQUEST_FIRST_PICKUP).orElseThrow();
        assertTrue(fj230.requiredEvents().contains(JourneyEvent.REQUEST_LEDGER_VIEW_OPENED));
        assertTrue(fj240.prerequisites().contains(JourneyIds.FJ_230_OPEN_REQUEST_LEDGER));
    }
}
