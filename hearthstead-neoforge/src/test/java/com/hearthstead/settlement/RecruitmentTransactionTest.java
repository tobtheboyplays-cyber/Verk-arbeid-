package com.hearthstead.settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecruitmentTransactionTest {

    private static final ResourceLocation OVERWORLD =
        ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

    @Test
    void exactTavernAndTravelerIdentitySurviveEveryRestartStage() {
        UUID settlementId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        UUID buildingId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        BlockPos plaque = new BlockPos(10, 64, 10);
        BlockPos anchor = new BlockPos(12, 64, 10);

        RecruitmentTransaction qualifying = RecruitmentTransaction
            .fresh(settlementId)
            .beginQualification(transactionId, 100L, buildingId, plaque,
                anchor, OVERWORLD);
        assertRoundTrips(qualifying, settlementId);
        assertEquals(RecruitmentTransaction.Status.QUALIFYING,
            qualifying.status());
        assertEquals(1, qualifying.revision());

        RecruitmentTransaction ready = qualifying.readyForSpawnForTest();
        assertTrue(ready.revision() > qualifying.revision());
        RecruitmentTransaction traveling = ready.travelerSpawned(
            travelerId, "Aldith", 200L);
        assertRoundTrips(traveling, settlementId);
        assertEquals(RecruitmentTransaction.Status.TRAVELING,
            traveling.status());
        assertEquals(buildingId, traveling.tavernBuildingId());
        assertEquals(plaque, traveling.tavernPlaquePos());
        assertEquals(anchor, traveling.tavernAnchor());
        assertEquals(travelerId, traveling.travelerId());
        assertEquals("Aldith", traveling.travelerName());
        assertTrue(traveling.revision() > ready.revision());

        RecruitmentTransaction waiting = traveling.arrived(240L);
        assertRoundTrips(waiting, settlementId);
        assertEquals(RecruitmentTransaction.Status.WAITING_ADMISSION,
            waiting.status());
        assertEquals(240L, waiting.arrivedTick(),
            "patience authority is the actual persisted arrival tick");
        assertTrue(waiting.revision() > traveling.revision());

        RecruitmentTransaction.AdmissionReceipt receipt =
            new RecruitmentTransaction.AdmissionReceipt(UUID.randomUUID(),
                "0@minecraft:bread#7-4;1@minecraft:oak_planks#3-8",
                12, 10L, 11L);
        RecruitmentTransaction admitted = waiting.admitted(receipt);
        assertRoundTrips(admitted, settlementId);
        assertEquals(RecruitmentTransaction.Status.ADMITTED,
            admitted.status());
        assertEquals(receipt, admitted.admissionReceipt());
        assertTrue(admitted.revision() > waiting.revision());
    }

    @Test
    void futureMalformedAndCrossSettlementPayloadsQuarantineFailClosed() {
        UUID settlementId = UUID.randomUUID();
        RecruitmentTransaction valid = waiting(settlementId, 12);

        CompoundTag future = valid.writeNbt();
        future.putInt("SchemaVersion",
            RecruitmentTransaction.CURRENT_SCHEMA_VERSION + 1);
        RecruitmentTransaction futureRead = RecruitmentTransaction
            .readOrQuarantine(future, settlementId);
        assertEquals(RecruitmentTransaction.Status.QUARANTINED,
            futureRead.status());
        assertFalse(futureRead.hasLockedTavern());

        CompoundTag malformed = valid.writeNbt();
        malformed.remove("TavernAnchor");
        assertEquals(RecruitmentTransaction.Status.QUARANTINED,
            RecruitmentTransaction.readOrQuarantine(malformed, settlementId)
                .status());

        RecruitmentTransaction cross = RecruitmentTransaction.readOrQuarantine(
            valid.writeNbt(), UUID.randomUUID());
        assertEquals(RecruitmentTransaction.Status.QUARANTINED,
            cross.status());
        assertNotEquals(valid.transactionId(), cross.transactionId(),
            "cross-settlement state must not remain actionable");
    }

    @Test
    void maxRevisionCannotChangeAnyGameplayStateOrAcceptReplay() {
        UUID settlementId = UUID.randomUUID();
        RecruitmentTransaction ordinary = waiting(settlementId, 30);
        RecruitmentTransaction saturated = copyWithRevision(ordinary,
            Integer.MAX_VALUE);
        assertTrue(saturated.revisionSaturated());

        RecruitmentTransaction.AdmissionReceipt receipt =
            new RecruitmentTransaction.AdmissionReceipt(UUID.randomUUID(),
                "0@minecraft:bread#1-4", 4, 1L, 2L);
        assertSame(saturated, saturated.admitted(receipt));
        assertSame(saturated, saturated.left(
            RecruitmentTransaction.TerminalReason.PLAYER_REJECTED));
        assertSame(saturated, saturated.quarantine(
            RecruitmentTransaction.TerminalReason.MALFORMED_SAVE));

        RecruitmentTransaction qualifying = copyWithRevision(
            RecruitmentTransaction.fresh(settlementId)
                .beginQualification(UUID.randomUUID(), 1L, UUID.randomUUID(),
                    BlockPos.ZERO, BlockPos.ZERO.above(), OVERWORLD),
            Integer.MAX_VALUE);
        assertSame(qualifying, qualifying.advanceQualification());
        assertSame(qualifying, qualifying.decayQualification());
        assertSame(qualifying, qualifying.readyForSpawnForTest());
        assertEquals(Integer.MAX_VALUE, qualifying.revision());
    }

    @Test
    void explicitDismissalIsPersistedWithoutPaymentReceipt() {
        UUID settlementId = UUID.randomUUID();
        RecruitmentTransaction waiting = waiting(settlementId, 50);
        RecruitmentTransaction rejected = waiting.left(
            RecruitmentTransaction.TerminalReason.PLAYER_REJECTED);

        assertEquals(RecruitmentTransaction.Status.LEFT, rejected.status());
        assertEquals(RecruitmentTransaction.TerminalReason.PLAYER_REJECTED,
            rejected.terminalReason());
        assertEquals(null, rejected.admissionReceipt());
        assertEquals(rejected,
            RecruitmentTransaction.readOrQuarantine(rejected.writeNbt(),
                settlementId));
    }

    @Test
    void adminPrimeCannotAuthorSurvivalJourneyEvidence() {
        UUID settlementId = UUID.randomUUID();
        RecruitmentTransaction primed = RecruitmentTransaction.fresh(settlementId)
            .adminPrime(UUID.randomUUID(), 1L, UUID.randomUUID(),
                BlockPos.ZERO, BlockPos.ZERO.above(), OVERWORLD);
        assertEquals(RecruitmentTransaction.Status.READY_TO_SPAWN,
            primed.status());
        assertFalse(primed.survivalAuthored());
        RecruitmentTransaction acknowledged = primed.acknowledgeEvidence(
            RecruitmentTransaction.EVIDENCE_QUALIFICATION);
        assertEquals(primed.revision(), acknowledged.revision(),
            "derived evidence never changes gameplay optimistic locking");
    }

    @Test
    void journeySelectedCallToArmsSurvivesRestartAcrossRetryCycles() {
        UUID settlementId = UUID.randomUUID();
        RecruitmentTransaction freshCycleFive = RecruitmentTransaction.fresh(
            settlementId, 5, 0);
        assertTrue(freshCycleFive.lockedTarget()
                >= RecruitmentPolicy.MIN_QUALIFIED_SECONDS,
            "a raw attempt number must never grant tutorial timing");

        RecruitmentTransaction callToArms = freshCycleFive.beginQualification(
            UUID.randomUUID(), 10L, UUID.randomUUID(), BlockPos.ZERO,
            BlockPos.ZERO.above(), OVERWORLD, true);
        assertTrue(callToArms.lockedTarget()
            >= RecruitmentPolicy.CALL_TO_ARMS_MIN_SECONDS);
        assertTrue(callToArms.lockedTarget()
            <= RecruitmentPolicy.CALL_TO_ARMS_MAX_SECONDS);
        assertEquals(RecruitmentPolicy.CALL_TO_ARMS_MIN_SECONDS,
            RecruitmentPolicy.minimumFor(settlementId, 5,
                callToArms.lockedTarget()));
        assertEquals(callToArms, RecruitmentTransaction.readOrQuarantine(
            callToArms.writeNbt(), settlementId));

        RecruitmentTransaction qualifying = callToArms;
        for (int second = 0; second < qualifying.lockedTarget(); second++) {
            qualifying = qualifying.advanceQualification();
        }
        assertEquals(RecruitmentTransaction.Status.READY_TO_SPAWN,
            qualifying.status());
        assertEquals(RecruitmentPolicy.CALL_TO_ARMS_MIN_SECONDS,
            qualifying.qualifiedSeconds());
        assertRoundTrips(qualifying, settlementId);

        RecruitmentTransaction rejected = qualifying
            .travelerSpawned(UUID.randomUUID(), "Retry", 600L)
            .arrived(620L)
            .left(RecruitmentTransaction.TerminalReason.PLAYER_REJECTED);
        RecruitmentTransaction retry = rejected.nextCycle(settlementId)
            .beginQualification(UUID.randomUUID(), 700L, UUID.randomUUID(),
                BlockPos.ZERO, BlockPos.ZERO.above(), OVERWORLD, true);
        assertEquals(6, retry.cycle());
        assertTrue(retry.lockedTarget()
                <= RecruitmentPolicy.CALL_TO_ARMS_MAX_SECONDS,
            "a rejected Watch candidate must retain bounded timing on a fresh transaction");
        assertNotEquals(callToArms.transactionId(), retry.transactionId());
        assertTrue(retry.revision() > rejected.revision());
        assertRoundTrips(retry, settlementId);
    }

    private static RecruitmentTransaction waiting(UUID settlementId,
                                                   long arrivedAt) {
        return RecruitmentTransaction.fresh(settlementId)
            .beginQualification(UUID.randomUUID(), 1L, UUID.randomUUID(),
                BlockPos.ZERO, BlockPos.ZERO.above(), OVERWORLD)
            .readyForSpawnForTest()
            .travelerSpawned(UUID.randomUUID(), "Aldith", 10L)
            .arrived(arrivedAt);
    }

    private static RecruitmentTransaction copyWithRevision(
            RecruitmentTransaction value, int revision) {
        return new RecruitmentTransaction(value.schemaVersion(),
            value.settlementId(), value.status(), value.survivalAuthored(),
            value.timingProfile(), revision, value.cycle(),
            value.lockedTarget(), value.progress(), value.qualifiedSeconds(),
            value.qualificationStartedTick(), value.transactionId(),
            value.travelerId(), value.travelerName(), value.spawnedTick(),
            value.arrivedTick(), value.tavernBuildingId(),
            value.tavernPlaquePos(), value.tavernAnchor(), value.dimension(),
            value.admissionReceipt(), value.journeyEvidenceMask(),
            value.terminalReason(), value.quote());
    }

    private static void assertRoundTrips(RecruitmentTransaction value,
                                         UUID settlementId) {
        assertEquals(value, RecruitmentTransaction.readOrQuarantine(
            value.writeNbt(), settlementId));
    }
}
