package com.hearthstead.settlement.journey;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.settlement.Building;
import com.hearthstead.building.BuildingType;
import com.hearthstead.settlement.RecruitmentTransaction;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
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
        batch = "journey_v3_housing_observation_replay")
    public void linkedLodgingBedUpdatePreservesFirstReceiptAfterReload(GameTestHelper helper) {
        Settlement settlement = new Settlement(UUID.randomUUID(), "Housing Replay",
            helper.absolutePos(new BlockPos(2, 1, 2)));
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Building home = new Building(UUID.randomUUID(), BuildingType.LODGING,
            helper.absolutePos(new BlockPos(4, 1, 4)),
            helper.absolutePos(new BlockPos(5, 1, 5)),
            BoundingBox.fromCorners(helper.absolutePos(new BlockPos(3, 0, 3)),
                helper.absolutePos(new BlockPos(12, 4, 12))));
        // Controlled registered-building fixture, not an earned survival claim.
        // A real blank plaque avoids unrelated periodic lost-plaque removal.
        helper.setBlock(new BlockPos(4, 1, 5), Blocks.STONE);
        helper.setBlock(new BlockPos(4, 1, 4), ModBlocks.PLAQUE.get());
        for (int index = 0; index < 4; index++) {
            addPhysicalHousingBed(helper, home, index);
        }
        home.valid = true;
        settlement.buildings.add(home);
        settlement.journeyState = JourneyState.fresh(settlement.id);
        seedBeforeHousingStep(settlement, home, JourneyIds.FJ_410_LINK_FIRST_HOME,
            helper.getLevel().getGameTime());
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        try {
            helper.assertTrue(JourneyServerHooks.noteBuildingLinked(helper.getLevel(), settlement, home),
                "first public house-link hook must author the real deterministic receipt");
            CompoundTag original = settlement.journeyState.writeNbt();
            JourneyEvidence first = settlement.journeyState.evidence().stream()
                .filter(item -> item.stepId().equals(JourneyIds.FJ_410_LINK_FIRST_HOME))
                .findFirst().orElseThrow();
            settlement.journeyState = JourneyState.readNbt(original, settlement.id);
            helper.runAfterDelay(2, () -> {
                try {
                    helper.assertTrue(helper.getLevel().getGameTime() > first.gameTime(),
                        "reobservation must occur at a later tick after codec reload");
                    addPhysicalHousingBed(helper, home, 4);
                    // Same public hook called by PlaqueBlockEntity after bedsChanged.
                    JourneyServerHooks.noteBuildingLinked(helper.getLevel(), settlement, home);
                    JourneyServerHooks.noteBuildingLinked(helper.getLevel(), settlement, home);
                    helper.assertTrue(original.equals(settlement.journeyState.writeNbt())
                            && settlement.journeyState.mode() == JourneyPresentationMode.ACTIVE
                            && !settlement.journeyState.isCompleted(JourneyIds.FJ_552_ADD_FIFTH_BED),
                        "extra beds and exact repeated observation must preserve the first receipt "
                            + "and must not invent watch progress");
                    seedBeforeHousingStep(settlement, home, JourneyIds.FJ_552_ADD_FIFTH_BED,
                        helper.getLevel().getGameTime());
                    JourneyServerHooks.noteBuildingLinked(helper.getLevel(), settlement, home);
                    helper.assertTrue(settlement.journeyState.isCompleted(JourneyIds.FJ_552_ADD_FIFTH_BED)
                            && settlement.journeyState.evidence().contains(first)
                            && settlement.journeyState.mode() == JourneyPresentationMode.ACTIVE,
                        "reused first-link receipt must still reconcile actual fifth-bed capacity "
                            + "once the real prerequisite receipt is present");
                    CompoundTag withCapacity = settlement.journeyState.writeNbt();
                    JourneyServerHooks.noteBuildingLinked(helper.getLevel(), settlement, home);
                    helper.assertTrue(withCapacity.equals(settlement.journeyState.writeNbt()),
                        "capacity and first-link receipts must remain idempotent together");
                    // Same key/time but conflicting payload must retain strict rejection.
                    JourneyState conflict = JourneyState.readNbt(withCapacity, settlement.id);
                    JourneyEvidence forged = new JourneyEvidence(first.stepId(), first.event(),
                        first.transactionId(), first.gameTime(), first.settlementId(),
                        Optional.of(ACTOR), first.subjectEntityId(), first.buildingId(),
                        first.requestId(), first.stackFingerprint(), first.source(), first.outcome());
                    helper.assertTrue(conflict.record(forged, JourneyDefinition.CURRENT)
                            == JourneyApplyResult.QUARANTINED
                            && conflict.quarantineReason().equals("transaction_identity_collision"),
                        "completed first-link step must not bypass conflicting same-key payload");
                    helper.succeed();
                } finally {
                    data.settlements.remove(settlement.id, settlement);
                    data.setDirty();
                }
            });
        } catch (RuntimeException | Error failure) {
            data.settlements.remove(settlement.id, settlement);
            data.setDirty();
            throw failure;
        }
    }

    private static void addPhysicalHousingBed(GameTestHelper helper, Building home, int index) {
        BlockPos foot = new BlockPos(6 + index, 1, 7);
        BlockPos head = foot.south();
        helper.setBlock(foot.below(), Blocks.STONE);
        helper.setBlock(head.below(), Blocks.STONE);
        var bed = Blocks.WHITE_BED.defaultBlockState().setValue(
            net.minecraft.world.level.block.BedBlock.FACING, net.minecraft.core.Direction.SOUTH);
        helper.setBlock(foot, bed.setValue(net.minecraft.world.level.block.BedBlock.PART,
            net.minecraft.world.level.block.state.properties.BedPart.FOOT));
        helper.setBlock(head, bed.setValue(net.minecraft.world.level.block.BedBlock.PART,
            net.minecraft.world.level.block.state.properties.BedPart.HEAD));
        home.beds.add(helper.absolutePos(head));
    }
    private static void seedBeforeHousingStep(Settlement settlement, Building home,
                                              net.minecraft.resources.ResourceLocation target,
                                              long gameTime) {
        // Fixture-only prerequisites stop BEFORE the actual first-link receipt.
        for (int count = 0; count < 56; count++) {
            JourneyStep step = settlement.journeyState.currentStep().orElseThrow();
            if (step.id().equals(target)) {
                return;
            }
            UUID transaction = UUID.randomUUID();
            for (JourneyEvent event : step.requiredEvents()) {
                settlement.journeyState.record(new JourneyEvidence(step.id(), event,
                    transaction, gameTime, settlement.id, Optional.of(ACTOR),
                    Optional.of(SUBJECT), Optional.of(home.id), Optional.of(REQUEST),
                    Optional.of("fixture:prerequisite"), JourneySource.SURVIVAL,
                    JourneyOutcome.NONE), JourneyDefinition.CURRENT);
            }
        }
        throw new AssertionError("fixture failed to reach expected prerequisite boundary");
    }
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

    @GameTest(template = "empty16", timeoutTicks = 40,
        batch = "journey_v3_watch_observation_replay")
    public void waitingWatchObservationSurvivesRestartAndLaterTickReplay(
            GameTestHelper helper) {
        // An authenticated saved-observation fixture. Prerequisite evidence is
        // seeded explicitly; this does not claim physical recruitment travel.
        Settlement settlement = new Settlement(UUID.randomUUID(), "Watch Replay",
            helper.absolutePos(new BlockPos(2, 1, 2)));
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Building tavern = new Building(UUID.randomUUID(), BuildingType.TAVERN,
            helper.absolutePos(new BlockPos(4, 1, 4)),
            helper.absolutePos(new BlockPos(5, 1, 5)),
            BoundingBox.fromCorners(helper.absolutePos(new BlockPos(3, 0, 3)),
                helper.absolutePos(new BlockPos(8, 4, 8))));
        // Keep the real periodic lost-plaque sweep truthful. The plaque is
        // deliberately blank: survey() is inert until a plan is fitted. No
        // Hearth means no recruitment heartbeat in this observation fixture.
        helper.setBlock(new BlockPos(4, 1, 5), Blocks.STONE);
        helper.setBlock(new BlockPos(4, 1, 4), ModBlocks.PLAQUE.get());
        tavern.valid = true;
        settlement.buildings.add(tavern);
        settlement.journeyState = JourneyState.fresh(settlement.id);
        while (!settlement.journeyState.isCompleted(JourneyIds.FJ_552_ADD_FIFTH_BED)) {
            JourneyStep step = settlement.journeyState.currentStep().orElseThrow();
            UUID transaction = UUID.randomUUID();
            for (JourneyEvent event : step.requiredEvents()) {
                settlement.journeyState.record(new JourneyEvidence(step.id(), event,
                    transaction, helper.getLevel().getGameTime(), settlement.id,
                    Optional.of(ACTOR), Optional.of(SUBJECT), Optional.of(tavern.id),
                    Optional.of(REQUEST), Optional.of("fixture:prerequisite"),
                    JourneySource.SURVIVAL, JourneyOutcome.NONE), JourneyDefinition.CURRENT);
            }
        }
        SettlerEntity traveler = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(5, 1, 5));
        traveler.markTraveler(settlement.id, settlement.center);
        long firstTick = helper.getLevel().getGameTime();
        settlement.applyRecruitment(RecruitmentTransaction.fresh(settlement.id, 1, 0)
            .beginQualification(UUID.randomUUID(), firstTick, tavern.id,
                tavern.plaquePos, tavern.anchor, helper.getLevel().dimension().location(), false)
            .readyForSpawnForTest()
            .travelerSpawned(traveler.getUUID(), "Waiting Recruit", firstTick)
            .arrived(firstTick)
            .acknowledgeEvidence(RecruitmentTransaction.EVIDENCE_QUALIFICATION
                | RecruitmentTransaction.EVIDENCE_ARRIVAL));
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        try {
            helper.assertTrue(JourneyServerHooks.noteBuildingLinked(helper.getLevel(),
                    settlement, tavern)
                    && settlement.journeyState.isCompleted(
                        JourneyIds.FJ_553_SECOND_RECRUITMENT_WINDOW)
                    && settlement.journeyState.isCompleted(
                        JourneyIds.FJ_554_SECOND_TRAVELER_ARRIVES)
                    && !settlement.journeyState.isCompleted(
                        JourneyIds.FJ_555_ADMIT_FIFTH_SETTLER),
                "the public registered-building observation must adopt this waiting transaction");
            CompoundTag savedJourney = settlement.journeyState.writeNbt();
            CompoundTag savedRecruitment = settlement.recruitment.writeNbt();
            settlement.journeyState = JourneyState.readNbt(savedJourney, settlement.id);
            settlement.applyRecruitment(RecruitmentTransaction.readOrQuarantine(
                savedRecruitment, settlement.id));
            helper.runAfterDelay(2, () -> {
                try {
                    helper.assertTrue(helper.getLevel().getGameTime() >= firstTick + 2,
                        "replay must occur at a genuinely later server tick");
                    JourneyServerHooks.noteBuildingLinked(helper.getLevel(), settlement, tavern);
                    helper.assertTrue(settlement.journeyState.mode() == JourneyPresentationMode.ACTIVE
                            && savedJourney.equals(settlement.journeyState.writeNbt())
                            && savedRecruitment.equals(settlement.recruitment.writeNbt())
                            && settlement.buildings.contains(tavern) && tavern.valid
                            && helper.getLevel().getBlockState(tavern.plaquePos)
                                .is(ModBlocks.PLAQUE.get())
                            && settlement.population() == 0,
                        "re-observation must preserve exact evidence, revision and transaction "
                            + "without quarantine or invented admission");
                    helper.succeed();
                } finally {
                    data.settlements.remove(settlement.id, settlement);
                    data.setDirty();
                    traveler.discard();
                }
            });
        } catch (RuntimeException | Error failure) {
            data.settlements.remove(settlement.id, settlement);
            data.setDirty();
            traveler.discard();
            throw failure;
        }
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
