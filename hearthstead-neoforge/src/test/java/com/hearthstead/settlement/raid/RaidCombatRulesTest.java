package com.hearthstead.settlement.raid;

import com.hearthstead.entity.RaiderEntity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import com.hearthstead.settlement.state.RaidParticipantRecord;

import static org.junit.jupiter.api.Assertions.*;

class RaidCombatRulesTest {
    @Test void exactClassAndChannelCapsArePinned() {
        assertEquals(1, RaidThreatBoard.capacityFor(false,
            RaiderEntity.Variant.SKIRMISHER, RaidThreatBoard.Channel.MELEE, false));
        assertEquals(1, RaidThreatBoard.capacityFor(false,
            RaiderEntity.Variant.SKIRMISHER, RaidThreatBoard.Channel.RANGED, false));
        assertEquals(2, RaidThreatBoard.capacityFor(false,
            RaiderEntity.Variant.BRUTE, RaidThreatBoard.Channel.MELEE, false));
        assertEquals(1, RaidThreatBoard.capacityFor(false,
            RaiderEntity.Variant.BRUTE, RaidThreatBoard.Channel.RANGED, false));
        assertEquals(1, RaidThreatBoard.capacityFor(true,
            RaiderEntity.Variant.SKIRMISHER, RaidThreatBoard.Channel.MELEE, false));
        assertEquals(2, RaidThreatBoard.capacityFor(true,
            RaiderEntity.Variant.SKIRMISHER, RaidThreatBoard.Channel.RANGED, false));
        assertEquals(3, RaidThreatBoard.capacityFor(false,
            RaiderEntity.Variant.BRUTE, RaidThreatBoard.Channel.MELEE, true));
    }

    @Test void uuidPriorityIsUnsignedAndInputOrderIndependent() {
        UUID low = new UUID(0L, 1L);
        UUID high = new UUID(-1L, -1L);
        List<UUID> forward = new ArrayList<>(List.of(low, high));
        List<UUID> reverse = new ArrayList<>(List.of(high, low));
        forward.sort(RaidThreatBoard::compareUuid);
        reverse.sort(RaidThreatBoard::compareUuid);
        assertEquals(forward, reverse);
        assertEquals(List.of(low, high), forward);
    }

    @Test void leashAndUrgentOverrideHaveExactEdges() {
        assertTrue(RaidThreatBoard.leashAllows(64.0, 8.0, false));
        assertFalse(RaidThreatBoard.leashAllows(64.01, 8.0, false));
        assertTrue(RaidThreatBoard.leashAllows(144.0, 8.0, true));
        assertFalse(RaidThreatBoard.leashAllows(144.01, 8.0, true));
    }

    @Test void rallyRefreshAndFinalStandAreStrictlyBounded() {
        assertTrue(CaptainRallyRules.shouldRefresh(80, 100, 40));
        assertEquals(20, CaptainRallyRules.refreshDuration(80, 100));
        assertFalse(CaptainRallyRules.shouldRefresh(100, 100, 40));
        assertEquals(0, CaptainRallyRules.refreshDuration(101, 100));
        assertTrue(CaptainRallyRules.mayStartFinalStand(false, true));
        assertFalse(CaptainRallyRules.mayStartFinalStand(true, true));
        assertFalse(CaptainRallyRules.mayStartFinalStand(false, false));
    }

    @Test void bossbarCountersComeOnlyFromSealedNonterminalRoster() {
        UUID captain = UUID.randomUUID();
        UUID brute = UUID.randomUUID();
        UUID skirmisher = UUID.randomUUID();
        List<RaidParticipantRecord> roster = List.of(
            new RaidParticipantRecord(captain,
                RaidParticipantRecord.Build.SKIRMISHER, true),
            new RaidParticipantRecord(brute,
                RaidParticipantRecord.Build.BRUTE, false),
            new RaidParticipantRecord(skirmisher,
                RaidParticipantRecord.Build.SKIRMISHER, false));
        assertEquals(new RaidBossBarService.RosterCounts(1, 1),
            RaidBossBarService.rosterCounts(roster, Set.of()));
        assertEquals(new RaidBossBarService.RosterCounts(0, 1),
            RaidBossBarService.rosterCounts(roster, Set.of(brute)));
        assertEquals(new RaidBossBarService.RosterCounts(0, 0),
            RaidBossBarService.rosterCounts(roster, Set.of(brute, skirmisher)));
    }
}
