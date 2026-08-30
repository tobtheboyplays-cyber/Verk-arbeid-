package com.hearthstead.settlement.journey;

import com.hearthstead.Hearthstead;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Runtime codec and dependency proofs for Journey schema 3 / definition 3. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class JourneyV3GameTests {
    private static final UUID SETTLEMENT = uuid(1);
    private static final UUID ACTOR = uuid(2);
    private static final UUID SUBJECT = uuid(3);
    private static final UUID BUILDING = uuid(4);
    private static final UUID REQUEST = uuid(5);

    @GameTest(template = "empty16", timeoutTicks = 40,
        batch = "journey_v3_reverse_evidence_restart_fixpoint")
    public void reverseEvidenceSurvivesRestartAndClosesAllFiftySix(
            GameTestHelper helper) {
        JourneyState beforeRestart = JourneyState.fresh(SETTLEMENT);
        List<JourneyEvidence> authored = allEvidence();
        Collections.reverse(authored);
        int split = authored.size() / 2;
        for (int i = 0; i < split; i++) {
            beforeRestart.record(authored.get(i), JourneyDefinition.CURRENT);
        }

        JourneyState restored = JourneyState.readNbt(
            beforeRestart.writeNbt(), SETTLEMENT);
        helper.assertTrue(restored.mode() == JourneyPresentationMode.ACTIVE,
            "pending out-of-order evidence must survive a strict restart round-trip");
        for (int i = split; i < authored.size(); i++) {
            restored.record(authored.get(i), JourneyDefinition.CURRENT);
        }

        helper.assertTrue(restored.completedCount() == 56,
            "bounded dependency closure must complete all exact 56 steps");
        helper.assertTrue(restored.mode() == JourneyPresentationMode.COMPLETE,
            "FJ-620, not readiness, owns overall Journey completion");
        helper.assertTrue(restored.outcome() == JourneyOutcome.HELD,
            "the first-raid outcome must survive out-of-order evidence and restart");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 40,
        batch = "journey_v3_admin_test_non_authority")
    public void adminAndTestEvidenceNeverCompleteAGameplayStep(
            GameTestHelper helper) {
        JourneyState state = JourneyState.fresh(SETTLEMENT);
        JourneyEvidence survivalShape = evidence(
            JourneyDefinition.CURRENT.stepAt(0),
            JourneyEvent.SETTLEMENT_FOUNDED_COMMITTED, uuid(100),
            JourneySource.ADMIN, JourneyOutcome.NONE);
        state.record(survivalShape, JourneyDefinition.CURRENT);
        state.record(new JourneyEvidence(survivalShape.stepId(),
            survivalShape.event(), uuid(101), survivalShape.gameTime(),
            survivalShape.settlementId(), survivalShape.actorPlayerId(),
            survivalShape.subjectEntityId(), survivalShape.buildingId(),
            survivalShape.requestId(), survivalShape.stackFingerprint(),
            JourneySource.TEST, survivalShape.outcome()),
            JourneyDefinition.CURRENT);

        helper.assertTrue(state.completedCount() == 0,
            "ADMIN and TEST rows may be auditable but cannot advance survival");
        helper.assertTrue(state.currentStep().orElseThrow().id().equals(
                JourneyIds.FJ_010_FOUND_HEARTH),
            "the first real survival objective must remain current");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 40,
        batch = "journey_v3_readiness_before_overall_complete")
    public void readinessClosureRequiresTheFullDualDefenderJourney(
            GameTestHelper helper) {
        JourneyState state = JourneyState.fresh(SETTLEMENT);
        for (int ordinal = 0; ordinal <= JourneyDefinition.CURRENT.step(
                JourneyIds.FJ_550_SET_GUARD_ORDER).orElseThrow().ordinal(); ordinal++) {
            recordStep(state, JourneyDefinition.CURRENT.stepAt(ordinal),
                1_000 + ordinal);
        }

        helper.assertTrue(!JourneyReadinessGate.prerequisitesThroughFirstWatch(state)
                && !JourneyReadinessGate.canRecordDeclaration(state),
            "a single equipped Guard must not satisfy first-raid readiness");
        helper.assertTrue(state.currentStep().orElseThrow().id().equals(
                JourneyIds.FJ_551_UNLOCK_ARM_THE_WATCH),
            "FJ-551 must visibly follow the Guard order despite its appended ordinal");

        for (JourneyStep step : JourneyIds.V3_APPENDED_STEPS.stream()
                .map(id -> JourneyDefinition.CURRENT.step(id).orElseThrow())
                .toList()) {
            recordStep(state, step, 2_000 + step.ordinal());
        }

        helper.assertTrue(JourneyReadinessGate.prerequisitesThroughFirstWatch(state),
            "readiness requires the fifth settler, Archer, bow, physical ammunition and Tower Post");
        helper.assertTrue(JourneyReadinessGate.canRecordDeclaration(state),
            "FJ-560 must be recordable before the raid and aftermath exist");
        helper.assertTrue(state.currentStep().orElseThrow().id().equals(
                JourneyIds.FJ_560_DECLARE_RAID_READY),
            "FJ-560 must visibly follow FJ-559B after the appended path closes");
        helper.assertTrue(state.mode() == JourneyPresentationMode.ACTIVE
                && !state.isCompleted(JourneyIds.FJ_560_DECLARE_RAID_READY)
                && !state.isCompleted(JourneyIds.FJ_620_REVIEW_AFTERMATH),
            "readiness must never depend on overall Journey COMPLETE");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 40,
        batch = "journey_v3_tower_post_ammunition_then_order")
    public void towerPostConfirmationRequiresBothPhysicalAmmoAndOrderEvidence(
            GameTestHelper helper) {
        JourneyState state = JourneyState.fresh(SETTLEMENT);
        for (JourneyStep step : JourneyDefinition.CURRENT.orderedSteps()) {
            if (step.id().equals(JourneyIds.FJ_559A_SUPPLY_ARCHER_AMMUNITION)) {
                break;
            }
            if (step.ordinal() <= JourneyDefinition.CURRENT.step(
                    JourneyIds.FJ_550_SET_GUARD_ORDER).orElseThrow().ordinal()
                || JourneyIds.V3_APPENDED_STEPS.contains(step.id())) {
                recordStep(state, step, 3_000 + step.ordinal());
            }
        }

        JourneyStep ammo = JourneyDefinition.CURRENT.step(
            JourneyIds.FJ_559A_SUPPLY_ARCHER_AMMUNITION).orElseThrow();
        JourneyStep order = JourneyDefinition.CURRENT.step(
            JourneyIds.FJ_559B_SET_TOWER_POST).orElseThrow();
        UUID confirmation = uuid(4_000);

        state.record(evidence(ammo, ammo.requiredEvents().getFirst(), confirmation,
            JourneySource.SURVIVAL, JourneyOutcome.NONE), JourneyDefinition.CURRENT);
        helper.assertTrue(state.isCompleted(ammo.id())
                && !state.isCompleted(order.id())
                && !JourneyReadinessGate.canRecordDeclaration(state),
            "physical arrow observation alone must not invent the Tower Post order");

        state.record(evidence(order, order.requiredEvents().getFirst(), confirmation,
            JourneySource.SURVIVAL, JourneyOutcome.NONE), JourneyDefinition.CURRENT);
        helper.assertTrue(state.isCompleted(order.id())
                && JourneyReadinessGate.canRecordDeclaration(state),
            "the same confirmation flow may close only after both physical ammo and order evidence exist");
        helper.succeed();
    }

    private static List<JourneyEvidence> allEvidence() {
        ArrayList<JourneyEvidence> authored = new ArrayList<>();
        for (JourneyStep step : JourneyDefinition.CURRENT.orderedSteps()) {
            UUID transaction = uuid(10_000 + step.ordinal());
            for (JourneyEvent event : step.requiredEvents()) {
                authored.add(evidence(step, event, transaction,
                    JourneySource.SURVIVAL, event.terminalOutcomeRequired()
                        ? JourneyOutcome.HELD : JourneyOutcome.NONE));
            }
        }
        return authored;
    }

    private static void recordStep(JourneyState state, JourneyStep step,
                                   long transactionValue) {
        UUID transaction = uuid(transactionValue);
        for (JourneyEvent event : step.requiredEvents()) {
            state.record(evidence(step, event, transaction,
                JourneySource.SURVIVAL, event.terminalOutcomeRequired()
                    ? JourneyOutcome.HELD : JourneyOutcome.NONE),
                JourneyDefinition.CURRENT);
        }
    }

    private static JourneyEvidence evidence(JourneyStep step,
                                            JourneyEvent event,
                                            UUID transaction,
                                            JourneySource source,
                                            JourneyOutcome outcome) {
        return new JourneyEvidence(step.id(), event, transaction,
            20L + step.ordinal(), SETTLEMENT, Optional.of(ACTOR),
            Optional.of(SUBJECT), Optional.of(BUILDING), Optional.of(REQUEST),
            Optional.of("minecraft:oak_log#count=1"), source, outcome);
    }

    private static UUID uuid(long value) {
        return new UUID(0x481ea75L, value);
    }

    public JourneyV3GameTests() {
    }
}
