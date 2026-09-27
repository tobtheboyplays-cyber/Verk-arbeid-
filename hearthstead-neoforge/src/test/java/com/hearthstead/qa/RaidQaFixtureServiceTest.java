package com.hearthstead.qa;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaidQaFixtureServiceTest {
    @Test
    void readinessStageCannotBeForcedByExecutionAlone() {
        assertEquals(RaidQaFixtureService.Stage.FOUNDED_PHYSICAL_SHELLS,
            RaidQaFixtureService.readinessStage(false, true));
        assertEquals(RaidQaFixtureService.Stage.FOUNDED_PHYSICAL_SHELLS,
            RaidQaFixtureService.readinessStage(false, false));
    }

    @Test
    void dualReadinessIsTheOnlyTerminalStage() {
        assertEquals(RaidQaFixtureService.Stage.DOMAIN_READY,
            RaidQaFixtureService.readinessStage(true, false));
        assertEquals(RaidQaFixtureService.Stage.READY_BEFORE_FIRST_RAID,
            RaidQaFixtureService.readinessStage(true, true));
    }

    @Test
    void naturalCropWaitIsNeverReportedReady() {
        RaidQaFixtureService.Result waiting = new RaidQaFixtureService.Result(
            RaidQaFixtureService.Stage.WAITING_NATURAL_CROP,
            BlockPos.ZERO, null, 0, 0, null, null, "none", "waiting");

        assertFalse(waiting.ready());
    }

    @Test
    void fixtureMarkerIsPersistedAndFirstBindingWins() {
        UUID actor = UUID.randomUUID();
        UUID settlement = UUID.randomUUID();
        BlockPos origin = new BlockPos(17, 64, -9);
        RaidQaFixtureMarker marker = new RaidQaFixtureMarker();

        assertTrue(marker.bind(actor, settlement, origin));
        assertTrue(marker.bind(actor, settlement, origin));
        assertFalse(marker.bind(actor, UUID.randomUUID(), origin));
        assertFalse(marker.bind(actor, settlement, origin.east()));

        RaidQaFixtureMarker restored = RaidQaFixtureMarker.load(
            marker.save(new CompoundTag(), null), null);
        RaidQaFixtureMarker.Entry entry = restored.entry(actor);
        assertNotNull(entry);
        assertEquals(settlement, entry.settlementId());
        assertEquals(origin, entry.origin());
        assertEquals(RaidQaFixtureMarker.Mode.ISOLATED, entry.mode());
        assertFalse(restored.quarantined());
    }

    @Test
    void groundedMarkerModeIsPersistedAndCannotBecomeIsolated() {
        UUID actor = UUID.randomUUID();
        UUID settlement = UUID.randomUUID();
        BlockPos origin = new BlockPos(17, 64, -9);
        RaidQaFixtureMarker marker = new RaidQaFixtureMarker();

        assertTrue(marker.bind(actor, settlement, origin,
            RaidQaFixtureMarker.Mode.GROUNDED_ASSISTED));
        assertFalse(marker.bind(actor, settlement, origin,
            RaidQaFixtureMarker.Mode.ISOLATED));

        RaidQaFixtureMarker restored = RaidQaFixtureMarker.load(
            marker.save(new CompoundTag(), null), null);
        assertEquals(RaidQaFixtureMarker.Mode.GROUNDED_ASSISTED,
            restored.entry(actor).mode());
    }

    @Test
    void malformedFixtureMarkerIsQuarantined() {
        RaidQaFixtureMarker restored = RaidQaFixtureMarker.load(
            new CompoundTag(), null);

        assertTrue(restored.quarantined());
        assertFalse(restored.bind(UUID.randomUUID(), UUID.randomUUID(),
            BlockPos.ZERO));
    }
}
