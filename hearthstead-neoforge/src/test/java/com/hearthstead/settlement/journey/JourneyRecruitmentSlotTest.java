package com.hearthstead.settlement.journey;

import com.hearthstead.settlement.RecruitmentTransaction;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JourneyRecruitmentSlotTest {
    private static final UUID SETTLEMENT = uuid(1);
    private static final UUID ACTOR = uuid(2);
    private static final UUID SUBJECT = uuid(3);
    private static final UUID BUILDING = uuid(4);
    private static final ResourceLocation OVERWORLD =
        ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

    @Test
    void firstAdmissionSlotSurvivesDismissEntityLossAndTavernRetry() {
        JourneyState state = through(JourneyIds.FJ_430_LINK_TAVERN);
        RecruitmentTransaction first = qualifying(0, 1_000L, false);
        applyMapped(state, first,
            JourneyEvent.RECRUITMENT_QUALIFICATION_STARTED_COMMITTED);
        applyMapped(state, first,
            JourneyEvent.TRAVELER_ARRIVED_AT_TAVERN_COMMITTED);

        RecruitmentTransaction dismissed = failed(first,
            RecruitmentTransaction.TerminalReason.PLAYER_REJECTED, true);
        state = restart(state);
        dismissed = restart(dismissed);
        RecruitmentTransaction entityRetry = retry(dismissed, 1_100L, false);
        assertNotEquals(first.transactionId(), entityRetry.transactionId());
        assertEquals(JourneyIds.FJ_460_ADMIT_TRAVELER,
            JourneyServerHooks.recruitmentStep(state, entityRetry,
                JourneyEvent.TRAVELER_ADMITTED_COMMITTED));

        RecruitmentTransaction entityGone = failed(entityRetry,
            RecruitmentTransaction.TerminalReason.ENTITY_GONE, false);
        RecruitmentTransaction tavernRetry = retry(entityGone, 1_200L, false);
        RecruitmentTransaction tavernInvalid = failed(tavernRetry,
            RecruitmentTransaction.TerminalReason.TAVERN_INVALIDATED, false);
        RecruitmentTransaction success = admitted(retry(tavernInvalid,
            1_300L, false));
        applyMapped(state, success, JourneyEvent.TRAVELER_ADMITTED_COMMITTED);

        assertTrue(state.isCompleted(JourneyIds.FJ_460_ADMIT_TRAVELER));
        assertEquals(1, evidenceCount(state,
            JourneyIds.FJ_460_ADMIT_TRAVELER,
            JourneyEvent.TRAVELER_ADMITTED_COMMITTED));
    }

    @Test
    void watchAdmissionSlotSurvivesTimeoutAndRetriesAtCyclesTwoThroughFive() {
        JourneyState state = through(JourneyIds.FJ_552_ADD_FIFTH_BED);
        RecruitmentTransaction cycleTwo = qualifying(2, 2_000L, true);
        applyMapped(state, cycleTwo,
            JourneyEvent.RECRUITMENT_QUALIFICATION_STARTED_COMMITTED);
        applyMapped(state, cycleTwo,
            JourneyEvent.TRAVELER_ARRIVED_AT_TAVERN_COMMITTED);

        RecruitmentTransaction timeout = failed(cycleTwo,
            RecruitmentTransaction.TerminalReason.PATIENCE_EXPIRED, true);
        state = restart(state);
        timeout = restart(timeout);
        RecruitmentTransaction cycleThree = retry(timeout, 2_100L, true);
        RecruitmentTransaction tavernInvalid = failed(cycleThree,
            RecruitmentTransaction.TerminalReason.TAVERN_INVALIDATED, false);
        RecruitmentTransaction cycleFour = retry(tavernInvalid, 2_200L, true);
        RecruitmentTransaction entityGone = failed(cycleFour,
            RecruitmentTransaction.TerminalReason.ENTITY_GONE, false);
        RecruitmentTransaction cycleFive = retry(entityGone, 2_300L, true);

        // Routing deliberately has no population equality. A death/replacement
        // at 3->4 and mature rosters at 5 or 8 still use the same reachable
        // post-FJ-552 slot; live first-raid readiness separately requires five.
        assertEquals(5, cycleFive.cycle());
        assertEquals(JourneyIds.FJ_555_ADMIT_FIFTH_SETTLER,
            JourneyServerHooks.recruitmentStep(state, cycleFive,
                JourneyEvent.TRAVELER_ADMITTED_COMMITTED));
        RecruitmentTransaction success = admitted(cycleFive);
        applyMapped(state, success, JourneyEvent.TRAVELER_ADMITTED_COMMITTED);

        assertTrue(state.isCompleted(JourneyIds.FJ_555_ADMIT_FIFTH_SETTLER));
        assertEquals(1, evidenceCount(state,
            JourneyIds.FJ_555_ADMIT_FIFTH_SETTLER,
            JourneyEvent.TRAVELER_ADMITTED_COMMITTED));
    }

    @Test
    void transactionStartedBeforeFifthBedIsAdoptedWithoutAForcedSixthCycle() {
        JourneyState state = through(JourneyIds.FJ_552_ADD_FIFTH_BED);
        RecruitmentTransaction preGate = qualifying(5, 1L, true);
        assertEquals(JourneyIds.FJ_553_SECOND_RECRUITMENT_WINDOW,
            JourneyServerHooks.recruitmentStep(state, preGate,
                JourneyEvent.RECRUITMENT_QUALIFICATION_STARTED_COMMITTED));
        applyMapped(state, preGate,
            JourneyEvent.RECRUITMENT_QUALIFICATION_STARTED_COMMITTED);

        JourneyState restored = restart(state);
        RecruitmentTransaction sameTransaction = restart(preGate);
        assertTrue(restored.isCompleted(
            JourneyIds.FJ_553_SECOND_RECRUITMENT_WINDOW));

        RecruitmentTransaction waiting = sameTransaction.readyForSpawnForTest()
            .travelerSpawned(uuid(55_555L), "Fifth Recruit", 10L)
            .arrived(20L);
        applyMapped(restored, waiting,
            JourneyEvent.TRAVELER_ARRIVED_AT_TAVERN_COMMITTED);
        RecruitmentTransaction admitted = waiting.admitted(
            new RecruitmentTransaction.AdmissionReceipt(ACTOR,
                "0@minecraft:bread#8-4", 4, 1L, 2L));
        applyMapped(restored, admitted,
            JourneyEvent.TRAVELER_ADMITTED_COMMITTED);

        JourneyState finalRestart = restart(restored);
        assertTrue(finalRestart.isCompleted(
            JourneyIds.FJ_555_ADMIT_FIFTH_SETTLER));
        assertEquals(JourneyIds.FJ_556_LINK_WATCHTOWER,
            finalRestart.currentStep().orElseThrow().id());
        assertEquals(1, evidenceCount(finalRestart,
            JourneyIds.FJ_555_ADMIT_FIFTH_SETTLER,
            JourneyEvent.TRAVELER_ADMITTED_COMMITTED));
        assertEquals(preGate.transactionId(), admitted.transactionId(),
            "adoption must keep the actual fifth traveler's transaction");
    }

    @Test
    void firstRecruitCannotFillWatchSlotBeforeItsTerminalCycleRollsOver() {
        JourneyState state = through(JourneyIds.FJ_430_LINK_TAVERN);
        RecruitmentTransaction first = qualifying(0, 1_000L, false);
        applyMapped(state, first,
            JourneyEvent.RECRUITMENT_QUALIFICATION_STARTED_COMMITTED);
        applyMapped(state, first, JourneyEvent.TRAVELER_ARRIVED_AT_TAVERN_COMMITTED);
        RecruitmentTransaction terminal = admitted(first);
        applyMapped(state, terminal, JourneyEvent.TRAVELER_ADMITTED_COMMITTED);
        advanceThrough(state, JourneyIds.FJ_552_ADD_FIFTH_BED, 1_000L);
        state = restart(state);
        terminal = restart(terminal);
        int revision = state.revision();
        for (JourneyEvent event : new JourneyEvent[] {
                JourneyEvent.RECRUITMENT_QUALIFICATION_STARTED_COMMITTED,
                JourneyEvent.TRAVELER_ARRIVED_AT_TAVERN_COMMITTED,
                JourneyEvent.TRAVELER_ADMITTED_COMMITTED}) {
            assertNull(JourneyServerHooks.recruitmentStep(state, terminal, event),
                "first-slot transaction identity must never be reused for Watch evidence");
        }
        assertEquals(revision, state.revision());
        assertEquals(JourneyPresentationMode.ACTIVE, state.mode());
        assertFalse(state.isCompleted(JourneyIds.FJ_553_SECOND_RECRUITMENT_WINDOW));
        RecruitmentTransaction next = retry(terminal, 2_000L, true);
        assertEquals(JourneyIds.FJ_553_SECOND_RECRUITMENT_WINDOW,
            JourneyServerHooks.recruitmentStep(state, next,
                JourneyEvent.RECRUITMENT_QUALIFICATION_STARTED_COMMITTED),
            "the next genuine transaction must remain eligible");
    }

    @Test
    void callToArmsAdoptionKeepsIdentityAndPersistsBoundedTarget() {
        RecruitmentTransaction ordinary = qualifying(7, 7_000L, false);
        UUID transactionId = ordinary.transactionId();
        int ordinaryTarget = ordinary.lockedTarget();

        RecruitmentTransaction adopted = ordinary.adoptCallToArmsWindow();
        assertEquals(transactionId, adopted.transactionId());
        assertEquals(ordinary.qualificationStartedTick(),
            adopted.qualificationStartedTick());
        assertTrue(adopted.lockedTarget() >= 240
            && adopted.lockedTarget() <= 480);
        assertNotEquals(ordinaryTarget, adopted.lockedTarget());
        assertTrue(adopted.revision() > ordinary.revision());

        RecruitmentTransaction restarted = restart(adopted);
        assertEquals(adopted, restarted);
        assertEquals(restarted, restarted.adoptCallToArmsWindow(),
            "replaying adoption after restart must not advance revision");
    }

    private static JourneyState through(ResourceLocation target) {
        JourneyState state = JourneyState.fresh(SETTLEMENT);
        advanceThrough(state, target);
        return state;
    }

    private static void advanceThrough(JourneyState state, ResourceLocation target) {
        advanceThrough(state, target, -1L);
    }

    private static void advanceThrough(JourneyState state, ResourceLocation target,
                                       long fixedGameTime) {
        int sequence = state.revision();
        while (!state.isCompleted(target)) {
            JourneyStep step = state.currentStep().orElseThrow();
            UUID transaction = uuid(10_000L + sequence++);
            for (JourneyEvent event : step.requiredEvents()) {
                state.record(evidence(step, event, transaction,
                    fixedGameTime >= 0L ? fixedGameTime : 100L + sequence),
                    JourneyDefinition.CURRENT);
            }
        }
    }

    private static void applyMapped(JourneyState state,
                                    RecruitmentTransaction transaction,
                                    JourneyEvent event) {
        ResourceLocation stepId = JourneyServerHooks.recruitmentStep(state,
            transaction, event);
        JourneyStep step = JourneyDefinition.CURRENT.step(stepId).orElseThrow();
        state.record(evidence(step, event, transaction.transactionId(),
            Math.max(0L, transaction.qualificationStartedTick()) + event.ordinal()),
            JourneyDefinition.CURRENT);
    }

    private static RecruitmentTransaction qualifying(int cycle, long started,
                                                      boolean callToArms) {
        return RecruitmentTransaction.fresh(SETTLEMENT, cycle, cycle * 10)
            .beginQualification(uuid(20_000L + cycle), started, BUILDING,
                BlockPos.ZERO, BlockPos.ZERO.above(), OVERWORLD, callToArms);
    }

    private static RecruitmentTransaction retry(RecruitmentTransaction failed,
                                                long started,
                                                boolean callToArms) {
        RecruitmentTransaction fresh = failed.nextCycle(SETTLEMENT);
        return fresh.beginQualification(uuid(30_000L + fresh.cycle()), started,
            BUILDING, BlockPos.ZERO, BlockPos.ZERO.above(), OVERWORLD,
            callToArms);
    }

    private static RecruitmentTransaction failed(RecruitmentTransaction value,
                                                 RecruitmentTransaction.TerminalReason reason,
                                                 boolean afterArrival) {
        RecruitmentTransaction traveling = value.readyForSpawnForTest()
            .travelerSpawned(uuid(40_000L + value.cycle()), "Traveler", 10L);
        RecruitmentTransaction terminal = afterArrival
            ? traveling.arrived(20L) : traveling;
        return terminal.left(reason);
    }

    private static RecruitmentTransaction admitted(RecruitmentTransaction value) {
        RecruitmentTransaction waiting = value.readyForSpawnForTest()
            .travelerSpawned(uuid(50_000L + value.cycle()), "Recruit", 10L)
            .arrived(20L);
        return waiting.admitted(new RecruitmentTransaction.AdmissionReceipt(
            ACTOR, "0@minecraft:bread#8-4", 4, 1L, 2L));
    }

    private static JourneyState restart(JourneyState state) {
        return JourneyState.readNbt(state.writeNbt(), SETTLEMENT);
    }

    private static RecruitmentTransaction restart(
            RecruitmentTransaction transaction) {
        return RecruitmentTransaction.readOrQuarantine(transaction.writeNbt(),
            SETTLEMENT);
    }

    private static long evidenceCount(JourneyState state,
                                      ResourceLocation step,
                                      JourneyEvent event) {
        return state.evidence().stream()
            .filter(item -> item.stepId().equals(step) && item.event() == event)
            .count();
    }

    private static JourneyEvidence evidence(JourneyStep step,
                                            JourneyEvent event,
                                            UUID transaction,
                                            long gameTime) {
        return new JourneyEvidence(step.id(), event, transaction, gameTime,
            SETTLEMENT, Optional.of(ACTOR), Optional.of(SUBJECT),
            Optional.of(BUILDING), Optional.of(uuid(60_000L)),
            Optional.of("physical:verified"), JourneySource.SURVIVAL,
            event.terminalOutcomeRequired()
                ? JourneyOutcome.HELD : JourneyOutcome.NONE);
    }

    private static UUID uuid(long value) {
        return new UUID(0x481ea75L, value);
    }
}
