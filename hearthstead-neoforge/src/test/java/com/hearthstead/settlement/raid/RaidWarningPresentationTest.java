package com.hearthstead.settlement.raid;

import com.hearthstead.saga.Captain;
import com.hearthstead.settlement.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaidWarningPresentationTest {

    @Test
    void warningReadsExactQueuedPlanAndSagaDisplayNameWithoutRerolling() {
        UUID captainId = UUID.randomUUID();
        Settlement settlement = new Settlement(UUID.randomUUID(), "Ashford",
            BlockPos.ZERO);
        settlement.raidCaptains.add(raidCaptain(captainId,
            "internal name must not leak"));
        settlement.sagaRoster.add(sagaCaptain(captainId, "Grimr",
            "the Torch"));
        assertTrue(settlement.raidLifecycle.initializeAtFounding(2L, 4, 2));
        RaidPlan persisted = new RaidPlan(captainId, RaidObjective.BRANN,
            -135.0F, 6L);
        assertTrue(settlement.raidLifecycle.queueFirstPlan(persisted));

        RaidPresentation.WarningView view = RaidPresentation
            .warningView(settlement).orElseThrow();

        assertEquals(6L, view.attackNight());
        assertEquals("Grimr the Torch", view.captainName());
        assertEquals(view.captainName(), RaidDirector.leaderNameOf(
            settlement, captainId).orElseThrow(),
            "warning must consume the same canonical name as field/arrival/Aftermath");
        assertEquals(RaidObjective.BRANN, view.objective());
        assertEquals(RaidPresentation.Compass.NORTH_EAST, view.approach());
        assertSame(persisted,
            settlement.raidLifecycle.queuedPlan().orElseThrow(),
            "presentation must read, never replace, the persisted plan");
    }

    @Test
    void compassSectorsMatchTheActualMinecraftFormUpGeometry() {
        assertEquals(RaidPresentation.Compass.SOUTH,
            RaidPresentation.Compass.fromApproachDegrees(0.0F));
        assertEquals(RaidPresentation.Compass.SOUTH_WEST,
            RaidPresentation.Compass.fromApproachDegrees(45.0F));
        assertEquals(RaidPresentation.Compass.WEST,
            RaidPresentation.Compass.fromApproachDegrees(90.0F));
        assertEquals(RaidPresentation.Compass.NORTH_WEST,
            RaidPresentation.Compass.fromApproachDegrees(135.0F));
        assertEquals(RaidPresentation.Compass.NORTH,
            RaidPresentation.Compass.fromApproachDegrees(-180.0F));
        assertEquals(RaidPresentation.Compass.NORTH_EAST,
            RaidPresentation.Compass.fromApproachDegrees(-135.0F));
        assertEquals(RaidPresentation.Compass.EAST,
            RaidPresentation.Compass.fromApproachDegrees(-90.0F));
        assertEquals(RaidPresentation.Compass.SOUTH_EAST,
            RaidPresentation.Compass.fromApproachDegrees(-45.0F));

        BlockPos center = new BlockPos(100, 64, 100);
        assertTrue(RaidDirector.formUpAt(center, 0.0F, 30).getZ()
            > center.getZ(), "zero degrees must truthfully render south (+Z)");
        assertTrue(RaidDirector.formUpAt(center, -90.0F, 30).getX()
            > center.getX(), "-90 degrees must truthfully render east (+X)");
        assertTrue(RaidDirector.formUpAt(center, -180.0F, 30).getZ()
            < center.getZ(), "-180 degrees must truthfully render north (-Z)");
    }

    @Test
    void missingExactCaptainFailsClosed() {
        Settlement settlement = new Settlement(UUID.randomUUID(), "Ashford",
            BlockPos.ZERO);
        assertTrue(settlement.raidLifecycle.initializeAtFounding(2L, 4, 2));
        assertTrue(settlement.raidLifecycle.queueFirstPlan(new RaidPlan(
            UUID.randomUUID(), RaidObjective.BLOD, 0.0F, 6L)));

        assertTrue(RaidPresentation.warningView(settlement).isEmpty());
    }

    @Test
    void corruptWarningQuarantinesPlanBeforeItCanBecomeAnAttack() {
        UUID captainId = UUID.randomUUID();
        Settlement settlement = new Settlement(UUID.randomUUID(), "Ashford",
            BlockPos.ZERO);
        settlement.raidCaptains.add(raidCaptain(captainId, "?"));
        assertTrue(settlement.raidLifecycle.initializeAtFounding(2L, 4, 2));
        RaidPlan plan = new RaidPlan(captainId, RaidObjective.BLOD, 0.0F, 6L);
        assertTrue(settlement.raidLifecycle.queueFirstPlan(plan));

        boolean presented = RaidPresentation.warningView(settlement).isPresent();
        assertFalse(RaidDirector.applyFirstWarningPresentationResult(
            settlement.raidLifecycle, presented));
        assertTrue(settlement.raidLifecycle.integrityLost());
        assertFalse(settlement.raidLifecycle.beginFirstRaid(plan),
            "an unpresentable warning must never become an active attack");
        assertSame(plan, settlement.raidLifecycle.queuedPlan().orElseThrow(),
            "quarantine must retain the exact persisted plan for diagnosis");
    }

    @Test
    void canonicalLeaderIdentityRejectsControlNamesAndDuplicateIds() {
        assertFalse(RaidDirector.isValidLeaderName(" Hrafn the Ashen"),
            "leading/trailing whitespace must not become canonical identity");
        assertFalse(RaidDirector.isValidLeaderName("Hrafn?"),
            "a question-mark placeholder must not hide inside a visible name");
        assertFalse(RaidDirector.isValidLeaderName("Hrafn\u2028forged"),
            "Unicode line separators must fail like ordinary control newlines");

        UUID captainId = UUID.randomUUID();
        Settlement controlName = new Settlement(UUID.randomUUID(), "Ashford",
            BlockPos.ZERO);
        controlName.raidCaptains.add(raidCaptain(captainId,
            "Hrafn\nfrom forged data"));
        assertTrue(RaidDirector.leaderNameOf(controlName, captainId).isEmpty(),
            "a control-bearing name must never reach warning, entity or report");

        Settlement duplicate = new Settlement(UUID.randomUUID(), "Ashford",
            BlockPos.ZERO);
        duplicate.raidCaptains.add(raidCaptain(captainId, "Hrafn the Ashen"));
        duplicate.raidCaptains.add(raidCaptain(captainId, "Ulf Coldhand"));
        assertTrue(RaidDirector.leaderNameOf(duplicate, captainId).isEmpty(),
            "two persisted identities for one plan id are ambiguous and must fail closed");

        UUID nil = new UUID(0L, 0L);
        Settlement nilIdentity = new Settlement(UUID.randomUUID(), "Ashford",
            BlockPos.ZERO);
        nilIdentity.raidCaptains.add(raidCaptain(nil, "Hrafn the Ashen"));
        assertTrue(RaidDirector.leaderNameOf(nilIdentity, nil).isEmpty(),
            "the nil UUID is missing authority, not a usable captain identity");
    }

    private static RaidCaptain raidCaptain(UUID id, String name) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putString("Name", name);
        tag.putInt("Victories", 0);
        tag.putInt("Defeats", 0);
        return RaidCaptain.readNbt(tag);
    }

    private static Captain sagaCaptain(UUID id, String first,
                                       String epithet) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putString("FirstName", first);
        tag.putString("Epithet", epithet);
        return Captain.readNbt(tag);
    }
}
