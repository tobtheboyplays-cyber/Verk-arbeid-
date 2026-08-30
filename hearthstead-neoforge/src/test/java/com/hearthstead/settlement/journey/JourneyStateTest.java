package com.hearthstead.settlement.journey;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JourneyStateTest {
    private static final UUID SETTLEMENT = uuid(1);
    private static final UUID ACTOR = uuid(2);
    private static final UUID SUBJECT = uuid(3);
    private static final UUID BUILDING = uuid(4);
    private static final UUID REQUEST = uuid(5);

    @Test
    void outOfOrderEvidenceSurvivesAndCompletesByBoundedFixpoint() {
        JourneyState state = JourneyState.fresh(SETTLEMENT);
        JourneyEvidence opened = evidence(JourneyIds.FJ_020_OPEN_JOURNEY,
            JourneyEvent.JOURNEY_VIEW_OPENED, uuid(20), JourneySource.SURVIVAL,
            JourneyOutcome.NONE);
        assertEquals(JourneyApplyResult.APPLIED,
            state.record(opened, JourneyDefinition.V2));
        assertFalse(state.isCompleted(JourneyIds.FJ_020_OPEN_JOURNEY));
        assertEquals(1, state.evidence().size());

        assertEquals(JourneyApplyResult.APPLIED, state.record(evidence(
            JourneyIds.FJ_010_FOUND_HEARTH,
            JourneyEvent.SETTLEMENT_FOUNDED_COMMITTED, uuid(10),
            JourneySource.SURVIVAL, JourneyOutcome.NONE), JourneyDefinition.V2));

        assertTrue(state.isCompleted(JourneyIds.FJ_010_FOUND_HEARTH));
        assertTrue(state.isCompleted(JourneyIds.FJ_020_OPEN_JOURNEY));
        assertEquals(JourneyIds.FJ_030_APPOINT_MAYOR,
            state.currentStep().orElseThrow().id());
        assertEquals(2, state.revision());
    }

    @Test
    void adminAndTestEvidenceAreAuditableButNeverNormalProgress() {
        JourneyState state = JourneyState.fresh(SETTLEMENT);
        assertEquals(JourneyApplyResult.RECORDED_NON_PROGRESS,
            state.record(evidence(JourneyIds.FJ_010_FOUND_HEARTH,
                JourneyEvent.SETTLEMENT_FOUNDED_COMMITTED, uuid(11),
                JourneySource.ADMIN, JourneyOutcome.NONE), JourneyDefinition.V2));
        assertEquals(JourneyApplyResult.RECORDED_NON_PROGRESS,
            state.record(evidence(JourneyIds.FJ_010_FOUND_HEARTH,
                JourneyEvent.SETTLEMENT_FOUNDED_COMMITTED, uuid(12),
                JourneySource.TEST, JourneyOutcome.NONE), JourneyDefinition.V2));
        assertFalse(state.isCompleted(JourneyIds.FJ_010_FOUND_HEARTH));
        assertEquals(2, state.evidence().size());

        state.record(evidence(JourneyIds.FJ_010_FOUND_HEARTH,
            JourneyEvent.SETTLEMENT_FOUNDED_COMMITTED, uuid(13),
            JourneySource.SURVIVAL, JourneyOutcome.NONE), JourneyDefinition.V2);
        assertTrue(state.isCompleted(JourneyIds.FJ_010_FOUND_HEARTH));
    }

    @Test
    void exactDuplicateIsIdempotentAndCollisionQuarantines() {
        JourneyState state = JourneyState.fresh(SETTLEMENT);
        JourneyEvidence first = evidence(JourneyIds.FJ_020_OPEN_JOURNEY,
            JourneyEvent.JOURNEY_VIEW_OPENED, uuid(21), JourneySource.SURVIVAL,
            JourneyOutcome.NONE);
        assertEquals(JourneyApplyResult.APPLIED,
            state.record(first, JourneyDefinition.V2));
        assertEquals(JourneyApplyResult.DUPLICATE,
            state.record(first, JourneyDefinition.V2));
        assertEquals(1, state.revision());

        JourneyEvidence conflicting = new JourneyEvidence(first.stepId(), first.event(),
            first.transactionId(), first.gameTime() + 1L, first.settlementId(),
            first.actorPlayerId(), first.subjectEntityId(), first.buildingId(),
            first.requestId(), first.stackFingerprint(), first.source(), first.outcome());
        assertEquals(JourneyApplyResult.QUARANTINED,
            state.record(conflicting, JourneyDefinition.V2));
        assertEquals(JourneyPresentationMode.QUARANTINED, state.mode());
        assertTrue(state.evidence().isEmpty());
    }

    @Test
    void staffMilestoneRequiresTradeAndBindingFromOneTransaction() {
        JourneyState state = JourneyState.fresh(SETTLEMENT);
        recordThrough(state, 4, JourneySource.SURVIVAL);
        UUID firstTrade = uuid(120);
        UUID unrelatedBinding = uuid(121);
        state.record(evidence(JourneyIds.FJ_120_STAFF_LUMBER_CAMP,
            JourneyEvent.EMBLEM_TRADE_COMMITTED, firstTrade,
            JourneySource.SURVIVAL, JourneyOutcome.NONE), JourneyDefinition.V2);
        state.record(evidence(JourneyIds.FJ_120_STAFF_LUMBER_CAMP,
            JourneyEvent.JOB_EMBLEM_BOUND_COMMITTED, unrelatedBinding,
            JourneySource.SURVIVAL, JourneyOutcome.NONE), JourneyDefinition.V2);
        assertFalse(state.isCompleted(JourneyIds.FJ_120_STAFF_LUMBER_CAMP));

        state.record(evidence(JourneyIds.FJ_120_STAFF_LUMBER_CAMP,
            JourneyEvent.JOB_EMBLEM_BOUND_COMMITTED, firstTrade,
            JourneySource.SURVIVAL, JourneyOutcome.NONE), JourneyDefinition.V2);
        assertTrue(state.isCompleted(JourneyIds.FJ_120_STAFF_LUMBER_CAMP));
    }

    @Test
    void evidenceLedgerIsStrictlyBounded() {
        JourneyState state = JourneyState.fresh(SETTLEMENT);
        for (int i = 0; i < JourneyState.MAX_EVIDENCE; i++) {
            assertEquals(JourneyApplyResult.APPLIED, state.record(evidence(
                JourneyIds.FJ_120_STAFF_LUMBER_CAMP,
                JourneyEvent.EMBLEM_TRADE_COMMITTED, uuid(1_000 + i),
                JourneySource.SURVIVAL, JourneyOutcome.NONE), JourneyDefinition.V2));
        }
        assertEquals(JourneyApplyResult.QUARANTINED, state.record(evidence(
            JourneyIds.FJ_120_STAFF_LUMBER_CAMP,
            JourneyEvent.EMBLEM_TRADE_COMMITTED, uuid(2_000),
            JourneySource.SURVIVAL, JourneyOutcome.NONE), JourneyDefinition.V2));
        assertEquals(JourneyPresentationMode.QUARANTINED, state.mode());
    }

    @Test
    void stackFingerprintRejectsNewlineControlAndUnboundedInput() {
        assertThrows(IllegalArgumentException.class,
            () -> evidenceWithStack("minecraft:oak_log\nforged"));
        assertThrows(IllegalArgumentException.class,
            () -> evidenceWithStack("minecraft:oak_log\u0000forged"));
        assertThrows(IllegalArgumentException.class,
            () -> evidenceWithStack("x".repeat(
                JourneyEvidence.MAX_FINGERPRINT_LENGTH + 1)));
        assertEquals("minecraft:oak_log#count=1",
            evidenceWithStack("minecraft:oak_log#count=1")
                .stackFingerprint().orElseThrow());
    }

    @Test
    void restartRoundTripPreservesPendingEvidenceAndLaterUnblocksIt() {
        JourneyState before = JourneyState.fresh(SETTLEMENT);
        before.record(evidence(JourneyIds.FJ_020_OPEN_JOURNEY,
            JourneyEvent.JOURNEY_VIEW_OPENED, uuid(220), JourneySource.SURVIVAL,
            JourneyOutcome.NONE), JourneyDefinition.V2);

        JourneyState after = JourneyState.readNbt(before.writeNbt(), SETTLEMENT);
        assertEquals(JourneyPresentationMode.ACTIVE, after.mode());
        assertEquals(1, after.evidence().size());
        assertFalse(after.isCompleted(JourneyIds.FJ_020_OPEN_JOURNEY));
        after.record(evidence(JourneyIds.FJ_010_FOUND_HEARTH,
            JourneyEvent.SETTLEMENT_FOUNDED_COMMITTED, uuid(210),
            JourneySource.SURVIVAL, JourneyOutcome.NONE), JourneyDefinition.V2);
        assertTrue(after.isCompleted(JourneyIds.FJ_020_OPEN_JOURNEY));
    }

    @Test
    void derivedFieldsAndDuplicateKeysFailClosedOnLoad() {
        JourneyState state = JourneyState.fresh(SETTLEMENT);
        state.record(evidence(JourneyIds.FJ_010_FOUND_HEARTH,
            JourneyEvent.SETTLEMENT_FOUNDED_COMMITTED, uuid(310),
            JourneySource.SURVIVAL, JourneyOutcome.NONE), JourneyDefinition.V2);

        CompoundTag wrongCompleted = state.writeNbt();
        wrongCompleted.put("Completed", new ListTag());
        assertEquals(JourneyPresentationMode.QUARANTINED,
            JourneyState.readNbt(wrongCompleted, SETTLEMENT).mode());

        CompoundTag wrongChapter = state.writeNbt();
        wrongChapter.putString("CurrentChapter", JourneyIds.CHAPTER_FIRST_RAID.toString());
        assertEquals(JourneyPresentationMode.QUARANTINED,
            JourneyState.readNbt(wrongChapter, SETTLEMENT).mode());

        CompoundTag duplicate = state.writeNbt();
        ListTag evidence = duplicate.getList("Evidence", 10);
        evidence.add(evidence.getCompound(0).copy());
        duplicate.putInt("Revision", 2);
        assertEquals(JourneyPresentationMode.QUARANTINED,
            JourneyState.readNbt(duplicate, SETTLEMENT).mode());

        CompoundTag forgedCompleted = state.writeNbt();
        ListTag completed = new ListTag();
        completed.add(StringTag.valueOf(JourneyIds.FJ_010_FOUND_HEARTH.toString()));
        completed.add(StringTag.valueOf(JourneyIds.FJ_010_FOUND_HEARTH.toString()));
        forgedCompleted.put("Completed", completed);
        assertEquals(JourneyPresentationMode.QUARANTINED,
            JourneyState.readNbt(forgedCompleted, SETTLEMENT).mode());
    }

    @Test
    void firstRaidOutcomePersistsAndAConflictingResolutionQuarantines() {
        JourneyState state = JourneyState.fresh(SETTLEMENT);
        JourneyEvidence held = evidence(JourneyIds.FJ_610_FIRST_RAID_RESOLVED,
            JourneyEvent.FIRST_RAID_RESOLVED_COMMITTED, uuid(610),
            JourneySource.SURVIVAL, JourneyOutcome.HELD);
        state.record(held, JourneyDefinition.V2);
        assertEquals(JourneyOutcome.HELD, state.outcome());
        assertEquals(JourneyOutcome.HELD,
            JourneyState.readNbt(state.writeNbt(), SETTLEMENT).outcome());

        JourneyEvidence lost = evidence(JourneyIds.FJ_610_FIRST_RAID_RESOLVED,
            JourneyEvent.FIRST_RAID_RESOLVED_COMMITTED, uuid(611),
            JourneySource.SURVIVAL, JourneyOutcome.SETTLEMENT_LOST);
        assertEquals(JourneyApplyResult.QUARANTINED,
            state.record(lost, JourneyDefinition.V2));
    }

    @Test
    void viewEvidenceRequiresTheServerSessionActorAndSubjectWhenApplicable() {
        assertThrows(IllegalArgumentException.class, () -> new JourneyEvidence(
            JourneyIds.FJ_020_OPEN_JOURNEY, JourneyEvent.JOURNEY_VIEW_OPENED,
            uuid(900), 0L, SETTLEMENT, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(),
            JourneySource.SURVIVAL, JourneyOutcome.NONE));
        assertThrows(IllegalArgumentException.class, () -> new JourneyEvidence(
            JourneyIds.FJ_130_OPEN_LUMBERER_INVENTORY,
            JourneyEvent.SETTLER_INVENTORY_VIEW_OPENED,
            uuid(901), 0L, SETTLEMENT, Optional.of(ACTOR), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(),
            JourneySource.SURVIVAL, JourneyOutcome.NONE));
    }

    @Test
    void snapshotRoutesFromGuardThroughFullArcherChainBeforeReadiness() {
        JourneyState state = JourneyState.fresh(SETTLEMENT);
        JourneySnapshot initial = JourneySnapshot.from(state);
        assertEquals(2, initial.objectives().size());
        assertEquals(JourneyIds.FJ_010_FOUND_HEARTH,
            initial.objectives().getFirst().stepId());
        assertEquals(JourneyIds.FJ_020_OPEN_JOURNEY,
            initial.objectives().getLast().stepId());

        recordThrough(state, 40, JourneySource.SURVIVAL);
        assertEquals(JourneyPresentationMode.ACTIVE, state.mode());
        assertFalse(JourneyReadinessGate.prerequisitesThroughFirstWatch(state));
        assertFalse(JourneyReadinessGate.canRecordDeclaration(state));
        JourneySnapshot afterGuard = JourneySnapshot.from(state);
        assertEquals(JourneyIds.FJ_551_UNLOCK_ARM_THE_WATCH,
            afterGuard.objectives().getFirst().stepId());
        assertEquals(JourneyIds.FJ_552_ADD_FIFTH_BED,
            afterGuard.objectives().getLast().stepId());

        for (ResourceLocation id : JourneyIds.V3_APPENDED_STEPS) {
            JourneyStep step = JourneyDefinition.CURRENT.step(id).orElseThrow();
            UUID transaction = uuid(50_000 + step.ordinal());
            for (JourneyEvent event : step.requiredEvents()) {
                state.record(evidence(step.id(), event, transaction,
                    JourneySource.SURVIVAL, JourneyOutcome.NONE),
                    JourneyDefinition.CURRENT);
            }
        }
        assertTrue(JourneyReadinessGate.prerequisitesThroughFirstWatch(state));
        assertTrue(JourneyReadinessGate.canRecordDeclaration(state));
        assertFalse(state.isCompleted(JourneyIds.FJ_560_DECLARE_RAID_READY));
        assertFalse(state.isCompleted(JourneyIds.FJ_620_REVIEW_AFTERMATH));
    }

    @Test
    void towerPostEvidenceCannotBypassMissingPhysicalAmmoEvidence() {
        JourneyState state = JourneyState.fresh(SETTLEMENT);
        recordThrough(state, 40, JourneySource.SURVIVAL);
        for (ResourceLocation id : JourneyIds.V3_APPENDED_STEPS) {
            if (id.equals(JourneyIds.FJ_559A_SUPPLY_ARCHER_AMMUNITION)
                || id.equals(JourneyIds.FJ_559B_SET_TOWER_POST)) {
                continue;
            }
            JourneyStep step = JourneyDefinition.CURRENT.step(id).orElseThrow();
            UUID transaction = uuid(60_000 + step.ordinal());
            for (JourneyEvent event : step.requiredEvents()) {
                state.record(evidence(step.id(), event, transaction,
                    JourneySource.SURVIVAL, JourneyOutcome.NONE),
                    JourneyDefinition.CURRENT);
            }
        }

        state.record(evidence(JourneyIds.FJ_559B_SET_TOWER_POST,
            JourneyEvent.GUARD_ASSIGNMENT_COMMITTED, uuid(60_559),
            JourneySource.SURVIVAL, JourneyOutcome.NONE),
            JourneyDefinition.CURRENT);
        assertFalse(state.isCompleted(JourneyIds.FJ_559B_SET_TOWER_POST));
        assertFalse(JourneyReadinessGate.canRecordDeclaration(state));

        state.record(evidence(JourneyIds.FJ_559A_SUPPLY_ARCHER_AMMUNITION,
            JourneyEvent.ARCHER_AMMUNITION_STORED_COMMITTED, uuid(60_558),
            JourneySource.SURVIVAL, JourneyOutcome.NONE),
            JourneyDefinition.CURRENT);
        assertTrue(state.isCompleted(
            JourneyIds.FJ_559A_SUPPLY_ARCHER_AMMUNITION));
        assertTrue(state.isCompleted(JourneyIds.FJ_559B_SET_TOWER_POST));
        assertTrue(JourneyReadinessGate.canRecordDeclaration(state));
    }

    @Test
    void skippedPresentationCanUseLiveReadinessWithoutFabricatedEvidence() {
        JourneyState state = JourneyState.fresh(SETTLEMENT);
        state.record(evidence(JourneyIds.FJ_010_FOUND_HEARTH,
            JourneyEvent.SETTLEMENT_FOUNDED_COMMITTED, uuid(701),
            JourneySource.SURVIVAL, JourneyOutcome.NONE), JourneyDefinition.V2);
        assertTrue(state.skipPresentation(state.revision()));

        assertEquals(JourneyPresentationMode.SKIPPED, state.mode());
        assertTrue(state.evidence().isEmpty());
        assertEquals(0, state.completedCount());
        assertFalse(JourneyReadinessGate.prerequisitesThroughFirstWatch(state));
        assertTrue(JourneyReadinessGate.canRecordDeclaration(state));
    }

    static void recordThrough(JourneyState state, int finalOrdinal,
                              JourneySource source) {
        for (int i = 0; i <= finalOrdinal; i++) {
            JourneyStep step = JourneyDefinition.V2.stepAt(i);
            UUID transaction = uuid(10_000 + i);
            for (JourneyEvent event : step.requiredEvents()) {
                JourneyOutcome outcome = event.terminalOutcomeRequired()
                    ? JourneyOutcome.HELD : JourneyOutcome.NONE;
                state.record(evidence(step.id(), event, transaction, source, outcome),
                    JourneyDefinition.V2);
            }
        }
    }

    static JourneyEvidence evidence(ResourceLocation stepId, JourneyEvent event,
                                    UUID transaction, JourneySource source,
                                    JourneyOutcome outcome) {
        return new JourneyEvidence(stepId, event, transaction, 40L, SETTLEMENT,
            Optional.of(ACTOR), Optional.of(SUBJECT), Optional.of(BUILDING),
            Optional.of(REQUEST), Optional.of("minecraft:oak_log#count=1"),
            source, outcome);
    }

    private static JourneyEvidence evidenceWithStack(String stack) {
        return new JourneyEvidence(JourneyIds.FJ_010_FOUND_HEARTH,
            JourneyEvent.SETTLEMENT_FOUNDED_COMMITTED, uuid(800), 0L, SETTLEMENT,
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.of(stack), JourneySource.SURVIVAL, JourneyOutcome.NONE);
    }

    static UUID uuid(long value) {
        return new UUID(0x1a2b3c4dL, value);
    }
}
