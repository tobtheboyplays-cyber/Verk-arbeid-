package com.hearthstead.network;

import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.raid.RaidLogEntry;
import com.hearthstead.settlement.state.BlessingState;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HearthAftermathViewTest {

    @Test
    void newestExactEntryAndCurrentOfferRoundTripAsOneBoundedView() {
        Settlement settlement = settlement();
        settlement.raidLog.add(new RaidLogEntry(3L, "Old Captain", "blod",
            false, 9, 2, "uro"));
        settlement.raidLog.add(new RaidLogEntry(7L, "Grimr the Torch",
            "brann", true, 2, 1, "varsel"));
        assertTrue(settlement.blessingState.grantOffer());

        HearthMayorSnapshot.AftermathView view =
            HearthNetwork.aftermathView(settlement);
        assertTrue(view.present());
        assertTrue(view.held());
        assertEquals(7L, view.night());
        assertEquals("Grimr the Torch", view.captainName());
        assertEquals("brann", view.objectiveId());
        assertEquals(2, view.itemsStolen());
        assertEquals(1, view.settlersHurt());
        assertEquals("varsel", view.threatStageId());
        assertEquals(HearthMayorSnapshot.AftermathView.RewardStatus.OFFER_PENDING,
            view.rewardStatus());
        assertEquals(1, view.offerSerial());
        assertEquals(HearthMayorSnapshot.AftermathView.RoadAhead.CLAIM_REWARD,
            view.roadAhead());

        RegistryFriendlyByteBuf buffer = buffer();
        HearthMayorSnapshot.AftermathView.CODEC.encode(buffer, view);
        assertEquals(view,
            HearthMayorSnapshot.AftermathView.CODEC.decode(buffer));
        assertEquals(1, HearthMayorSnapshot.AftermathView.WIRE_VERSION);
    }

    @Test
    void malformedNewestEntryClosesInsteadOfFallingBackToOlderHistory() {
        Settlement settlement = settlement();
        settlement.raidLog.add(new RaidLogEntry(3L, "Known Captain", "blod",
            true, 0, 0, "rolig"));
        settlement.raidLog.add(new RaidLogEntry(4L, "Forged Captain",
            "unknown_objective", true, 0, 0, "rolig"));

        HearthMayorSnapshot.AftermathView view =
            HearthNetwork.aftermathView(settlement);
        assertFalse(view.present());
        assertEquals(HearthMayorSnapshot.AftermathView.closed(), view);
    }

    @Test
    void lostRaidAndQuarantinedRewardProduceServerChosenRoads() {
        Settlement lost = settlement();
        lost.raidLog.add(new RaidLogEntry(9L, "Skarde Ironjaw", "korn",
            false, 12, 3, "beleiring"));
        HearthMayorSnapshot.AftermathView lostView =
            HearthNetwork.aftermathView(lost);
        assertEquals(HearthMayorSnapshot.AftermathView.RewardStatus.NO_OFFER,
            lostView.rewardStatus());
        assertEquals(HearthMayorSnapshot.AftermathView.RoadAhead.RECOVER,
            lostView.roadAhead());

        Settlement quarantined = settlement();
        quarantined.raidLog.add(new RaidLogEntry(10L, "Hrafn", "blod",
            true, 0, 0, "uro"));
        quarantined.blessingState = BlessingState.quarantinedEmpty();
        HearthMayorSnapshot.AftermathView unavailable =
            HearthNetwork.aftermathView(quarantined);
        assertEquals(HearthMayorSnapshot.AftermathView.RewardStatus.UNAVAILABLE,
            unavailable.rewardStatus());
        assertEquals(
            HearthMayorSnapshot.AftermathView.RoadAhead.REWARD_UNAVAILABLE,
            unavailable.roadAhead());
    }

    @Test
    void hostileTextUnknownIdsAndInconsistentStatusFailClosedAtDecodeShape() {
        assertThrows(IllegalArgumentException.class,
            () -> new HearthMayorSnapshot.AftermathView(true, true, 1L,
                "x".repeat(RaidLogEntry.MAX_CAPTAIN_NAME + 1), "blod",
                0, 0, "rolig", 0, 0, 3));
        assertThrows(IllegalArgumentException.class,
            () -> new HearthMayorSnapshot.AftermathView(true, true, 1L,
                "?", "blod", 0, 0, "rolig", 0, 0, 3));
        assertThrows(IllegalArgumentException.class,
            () -> new HearthMayorSnapshot.AftermathView(true, true,
                RaidLogEntry.MAX_REPORTED_NIGHT + 1L, "Captain", "blod",
                0, 0, "rolig", 0, 0, 3));
        assertThrows(IllegalArgumentException.class,
            () -> new HearthMayorSnapshot.AftermathView(true, true, 1L,
                "Captain", "forged", 0, 0, "rolig", 0, 0, 3));
        assertThrows(IllegalArgumentException.class,
            () -> new HearthMayorSnapshot.AftermathView(true, true, 1L,
                "Captain", "blod", 0, 0, "rolig",
                HearthMayorSnapshot.AftermathView.RewardStatus.OFFER_PENDING
                    .wireId(), 0,
                HearthMayorSnapshot.AftermathView.RoadAhead.CLAIM_REWARD
                    .wireId()));
        assertThrows(IllegalArgumentException.class,
            () -> new HearthMayorSnapshot.AftermathView(false, false, 2L,
                "", "", 0, 0, "", -1, 0, -1));
    }

    private static Settlement settlement() {
        return new Settlement(UUID.randomUUID(), "Ashford", BlockPos.ZERO);
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(),
            RegistryAccess.EMPTY, ConnectionType.NEOFORGE);
    }
}
