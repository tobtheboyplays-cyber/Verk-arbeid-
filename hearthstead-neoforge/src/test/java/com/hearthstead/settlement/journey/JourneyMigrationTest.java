package com.hearthstead.settlement.journey;

import com.hearthstead.settlement.development.DevelopmentNode;
import com.hearthstead.settlement.development.DevelopmentState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JourneyMigrationTest {
    private static final UUID SETTLEMENT = JourneyStateTest.uuid(1);

    @Test
    void activeV1AndV2RetainOnlyApprovedOutOfOrderMigrationEvidence() {
        JourneyEvidence founded = migratedEvidence(JourneyIds.FJ_010_FOUND_HEARTH,
            JourneyEvent.SETTLEMENT_FOUNDED_COMMITTED, 1);
        JourneyEvidence camp = migratedEvidence(JourneyIds.FJ_110_LINK_LUMBER_CAMP,
            JourneyEvent.BUILDING_LINKED_VALID_COMMITTED, 2);

        JourneyMigration.Result v1 = JourneyMigration.migrateLegacy(
            legacy(1, 2, "deliver_first_log", 2), SETTLEMENT, false,
            List.of(camp, founded));
        assertEquals(JourneyMigration.Disposition.ACTIVE_MIGRATED, v1.disposition());
        assertTrue(v1.state().isCompleted(JourneyIds.FJ_010_FOUND_HEARTH));
        assertFalse(v1.state().isCompleted(JourneyIds.FJ_110_LINK_LUMBER_CAMP));
        assertEquals(2, v1.state().evidence().size());

        JourneyMigration.Result v2 = JourneyMigration.migrateLegacy(
            legacy(2, 5, "set_lumber_zone", 2), SETTLEMENT, false,
            List.of(founded));
        assertEquals(JourneyMigration.Disposition.ACTIVE_MIGRATED, v2.disposition());
        assertTrue(v2.state().isCompleted(JourneyIds.FJ_010_FOUND_HEARTH));
    }

    @Test
    void establishedLegacyWorldsStaySkippedUntilExplicitResume() {
        CompoundTag complete = legacy(2, 3, "complete", 4);
        JourneyMigration.Result preserved = JourneyMigration.migrateLegacy(
            complete, SETTLEMENT, false, List.of());
        assertEquals(JourneyPresentationMode.SKIPPED, preserved.state().mode());
        assertTrue(preserved.resumeAllowed());
        assertEquals(JourneyMigration.Disposition.LEGACY_PRESENTATION_SKIPPED,
            preserved.disposition());

        JourneyMigration.Result resumed = JourneyMigration.migrateLegacy(
            complete, SETTLEMENT, true, List.of(migratedEvidence(
                JourneyIds.FJ_010_FOUND_HEARTH,
                JourneyEvent.SETTLEMENT_FOUNDED_COMMITTED, 3)));
        assertEquals(JourneyPresentationMode.ACTIVE, resumed.state().mode());
        assertTrue(resumed.state().isCompleted(JourneyIds.FJ_010_FOUND_HEARTH));
        assertEquals(JourneyMigration.Disposition.RESUMED, resumed.disposition());
    }

    @Test
    void malformedUnknownAndUnapprovedSourcesFailClosed() {
        JourneyMigration.Result future = JourneyMigration.migrateLegacy(
            legacy(99, 0, "build_lumber_camp", 0), SETTLEMENT, false, List.of());
        assertEquals(JourneyPresentationMode.QUARANTINED, future.state().mode());
        assertFalse(future.resumeAllowed());

        JourneyEvidence admin = JourneyStateTest.evidence(
            JourneyIds.FJ_010_FOUND_HEARTH,
            JourneyEvent.SETTLEMENT_FOUNDED_COMMITTED,
            JourneyStateTest.uuid(901), JourneySource.ADMIN, JourneyOutcome.NONE);
        JourneyMigration.Result rejected = JourneyMigration.migrateLegacy(
            legacy(2, 0, "build_lumber_camp", 0), SETTLEMENT, false,
            List.of(admin));
        assertEquals(JourneyPresentationMode.QUARANTINED, rejected.state().mode());
    }

    @Test
    void v1AndV2WireRevisionPairsAreNotInterchangeable() {
        assertEquals(JourneyPresentationMode.QUARANTINED,
            JourneyMigration.migrateLegacy(
                legacy(1, 3, "complete", 4), SETTLEMENT, false, List.of())
                .state().mode());
        assertEquals(JourneyPresentationMode.QUARANTINED,
            JourneyMigration.migrateLegacy(
                legacy(2, 2, "deliver_first_log", 2), SETTLEMENT, false, List.of())
                .state().mode());
    }

    @Test
    void exactDefinitionTwoEvidenceIsPreservedButNewWatchFailsClosed() {
        JourneyState oldComplete = JourneyState.fresh(SETTLEMENT);
        for (JourneyStep step : JourneyDefinition.LEGACY_V2.orderedSteps()) {
            UUID transaction = JourneyStateTest.uuid(30_000 + step.ordinal());
            for (JourneyEvent event : step.requiredEvents()) {
                oldComplete.record(JourneyStateTest.evidence(step.id(), event,
                    transaction, JourneySource.SURVIVAL,
                    event.terminalOutcomeRequired()
                        ? JourneyOutcome.HELD : JourneyOutcome.NONE),
                    JourneyDefinition.LEGACY_V2);
            }
        }
        int oldEvidenceCount = oldComplete.evidence().size();
        CompoundTag definitionTwo = oldComplete.writeNbt();
        definitionTwo.putInt("DefinitionVersion", 2);

        JourneyState migrated = JourneyState.readNbt(definitionTwo, SETTLEMENT);
        assertEquals(JourneyPresentationMode.ACTIVE, migrated.mode());
        assertEquals(oldEvidenceCount, migrated.evidence().size());
        assertTrue(migrated.isCompleted(JourneyIds.FJ_550_SET_GUARD_ORDER));
        assertFalse(migrated.isCompleted(
            JourneyIds.FJ_551_UNLOCK_ARM_THE_WATCH));
        assertFalse(migrated.isCompleted(
            JourneyIds.FJ_560_DECLARE_RAID_READY));
        assertEquals(JourneyIds.FJ_551_UNLOCK_ARM_THE_WATCH,
            migrated.currentStep().orElseThrow().id());
    }

    @Test
    void schemaTwoDevelopmentAndDefinitionTwoJourneyExposeOneExplicitArmClaim() {
        DevelopmentState freshDevelopment = new DevelopmentState();
        CompoundTag developmentTwo = freshDevelopment.writeNbt();
        developmentTwo.putInt("Schema", 2);
        ListTag unlocked = developmentTwo.getList("Unlocked", Tag.TAG_STRING);
        for (DevelopmentNode node : List.of(
            DevelopmentNode.TIMBER_RIGHTS,
            DevelopmentNode.STORES_AND_ROADS,
            DevelopmentNode.CULTIVATED_GROUND,
            DevelopmentNode.HOME,
            DevelopmentNode.HOSPITALITY,
            DevelopmentNode.FIRST_WATCH
        )) {
            unlocked.add(StringTag.valueOf(node.id()));
        }
        developmentTwo.getCompound("QuestCounters")
            .remove("GuardEquipmentDeliveries");
        developmentTwo.remove("SeenGuardEquipmentRequests");

        DevelopmentState migratedDevelopment = DevelopmentState.readNbt(
            developmentTwo);
        assertFalse(migratedDevelopment.quarantined());
        assertTrue(migratedDevelopment.unlocked(DevelopmentNode.FIRST_WATCH));
        assertFalse(migratedDevelopment.unlocked(
            DevelopmentNode.ARM_THE_WATCH));
        assertTrue(DevelopmentNode.ARM_THE_WATCH.costs().isEmpty(),
            "the explicit legacy claim must not charge a newly invented cost");
        assertArmClaimObjectivePersisted(migratedDevelopment.writeNbt());

        DevelopmentState restartedDevelopment = DevelopmentState.readNbt(
            migratedDevelopment.writeNbt());
        assertFalse(restartedDevelopment.quarantined());
        assertFalse(restartedDevelopment.unlocked(
            DevelopmentNode.ARM_THE_WATCH));
        assertArmClaimObjectivePersisted(restartedDevelopment.writeNbt());

        JourneyState oldJourney = JourneyState.fresh(SETTLEMENT);
        for (JourneyStep step : JourneyDefinition.LEGACY_V2.orderedSteps()) {
            UUID transaction = JourneyStateTest.uuid(40_000 + step.ordinal());
            for (JourneyEvent event : step.requiredEvents()) {
                oldJourney.record(JourneyStateTest.evidence(step.id(), event,
                    transaction, JourneySource.SURVIVAL,
                    event.terminalOutcomeRequired()
                        ? JourneyOutcome.HELD : JourneyOutcome.NONE),
                    JourneyDefinition.LEGACY_V2);
            }
            if (step.id().equals(JourneyIds.FJ_550_SET_GUARD_ORDER)) {
                break;
            }
        }
        CompoundTag journeyTwo = oldJourney.writeNbt();
        journeyTwo.putInt("DefinitionVersion", 2);
        JourneyState migratedJourney = JourneyState.readNbt(journeyTwo,
            SETTLEMENT);
        assertEquals(JourneyIds.FJ_551_UNLOCK_ARM_THE_WATCH,
            migratedJourney.currentStep().orElseThrow().id());

        JourneyEvidence explicitClaim = JourneyStateTest.evidence(
            JourneyIds.FJ_551_UNLOCK_ARM_THE_WATCH,
            JourneyEvent.TECH_UNLOCKED_COMMITTED,
            JourneyStateTest.uuid(45_551), JourneySource.SURVIVAL,
            JourneyOutcome.NONE);
        int evidenceBeforeClaim = migratedJourney.evidence().size();
        assertEquals(JourneyApplyResult.APPLIED,
            migratedJourney.record(explicitClaim, JourneyDefinition.CURRENT));
        assertTrue(migratedJourney.isCompleted(
            JourneyIds.FJ_551_UNLOCK_ARM_THE_WATCH));
        assertEquals(JourneyIds.FJ_552_ADD_FIFTH_BED,
            migratedJourney.currentStep().orElseThrow().id());
        assertEquals(evidenceBeforeClaim + 1, migratedJourney.evidence().size());

        JourneyState restartedJourney = JourneyState.readNbt(
            migratedJourney.writeNbt(), SETTLEMENT);
        assertEquals(JourneyApplyResult.DUPLICATE,
            restartedJourney.record(explicitClaim, JourneyDefinition.CURRENT));
        assertEquals(JourneyApplyResult.ALREADY_COMPLETED,
            restartedJourney.record(JourneyStateTest.evidence(
                JourneyIds.FJ_551_UNLOCK_ARM_THE_WATCH,
                JourneyEvent.TECH_UNLOCKED_COMMITTED,
                JourneyStateTest.uuid(45_552), JourneySource.SURVIVAL,
                JourneyOutcome.NONE), JourneyDefinition.CURRENT));
        assertEquals(evidenceBeforeClaim + 1,
            restartedJourney.evidence().size());
    }

    @Test
    void ownedArmSaveTearReconcilesOnceWithoutRevisionOrCostMutation() {
        DevelopmentState available = migratedSchemaTwoFirstWatch();
        CompoundTag committedTag = available.writeNbt();
        committedTag.getList("Unlocked", Tag.TAG_STRING).add(
            StringTag.valueOf(DevelopmentNode.ARM_THE_WATCH.id()));
        committedTag.putInt("Revision", 9);
        DevelopmentState committed = DevelopmentState.readNbt(committedTag);
        assertFalse(committed.quarantined());
        assertTrue(committed.unlocked(DevelopmentNode.ARM_THE_WATCH));

        JourneyState tornJourney = migratedDefinitionTwoAtArmStep();
        UUID receipt = JourneyServerHooks.ownedArmReconciliationTransaction(
            tornJourney, committed).orElseThrow();
        int developmentRevision = committed.revision();
        int evidenceBefore = tornJourney.evidence().size();
        JourneyEvidence reconciliation = JourneyStateTest.evidence(
            JourneyIds.FJ_551_UNLOCK_ARM_THE_WATCH,
            JourneyEvent.TECH_UNLOCKED_COMMITTED, receipt,
            JourneySource.SURVIVAL, JourneyOutcome.NONE);

        assertEquals(JourneyApplyResult.APPLIED,
            tornJourney.record(reconciliation, JourneyDefinition.CURRENT));
        assertEquals(developmentRevision, committed.revision(),
            "Journey healing must not mutate or repay Development");
        assertTrue(DevelopmentNode.ARM_THE_WATCH.costs().isEmpty());
        assertEquals(evidenceBefore + 1, tornJourney.evidence().size());

        DevelopmentState restartedDevelopment = DevelopmentState.readNbt(
            committed.writeNbt());
        JourneyState restartedJourney = JourneyState.readNbt(
            tornJourney.writeNbt(), SETTLEMENT);
        assertTrue(JourneyServerHooks.ownedArmReconciliationTransaction(
            restartedJourney, restartedDevelopment).isEmpty());
        assertEquals(JourneyApplyResult.DUPLICATE,
            restartedJourney.record(reconciliation, JourneyDefinition.CURRENT));
        assertEquals(evidenceBefore + 1, restartedJourney.evidence().size());
        assertEquals(developmentRevision, restartedDevelopment.revision());
    }

    @Test
    void tamperedDefinitionTwoDerivedStateQuarantinesBeforeReplay() {
        JourneyState old = JourneyState.fresh(SETTLEMENT);
        old.record(migratedEvidence(JourneyIds.FJ_010_FOUND_HEARTH,
            JourneyEvent.SETTLEMENT_FOUNDED_COMMITTED, 77),
            JourneyDefinition.LEGACY_V2);
        CompoundTag tag = old.writeNbt();
        tag.putInt("DefinitionVersion", 2);
        ListTag forged = new ListTag();
        forged.add(net.minecraft.nbt.StringTag.valueOf(
            JourneyIds.FJ_020_OPEN_JOURNEY.toString()));
        tag.put("Completed", forged);

        JourneyState rejected = JourneyState.readNbt(tag, SETTLEMENT);
        assertEquals(JourneyPresentationMode.QUARANTINED, rejected.mode());
        assertTrue(rejected.evidence().isEmpty());
    }

    private static JourneyEvidence migratedEvidence(
        net.minecraft.resources.ResourceLocation step, JourneyEvent event,
        long transaction) {
        JourneyEvidence survival = JourneyStateTest.evidence(step, event,
            JourneyStateTest.uuid(2_000 + transaction), JourneySource.MIGRATION,
            JourneyOutcome.NONE);
        return survival;
    }

    private static void assertArmClaimObjectivePersisted(CompoundTag tag) {
        assertEquals(1, tag.getCompound("QuestCounters")
            .getInt("GuardEquipmentDeliveries"));
        ListTag baselines = tag.getList("QuestBaselines", Tag.TAG_COMPOUND);
        boolean found = false;
        for (int i = 0; i < baselines.size(); i++) {
            CompoundTag baseline = baselines.getCompound(i);
            if ("arm_the_watch/guard_equipment_deliveries".equals(
                baseline.getString("Key"))) {
                assertEquals(0, baseline.getInt("Value"));
                found = true;
            }
        }
        assertTrue(found, "Arm the Watch migration baseline must survive restart");
    }

    private static DevelopmentState migratedSchemaTwoFirstWatch() {
        CompoundTag tag = new DevelopmentState().writeNbt();
        tag.putInt("Schema", 2);
        ListTag unlocked = tag.getList("Unlocked", Tag.TAG_STRING);
        for (DevelopmentNode node : List.of(
            DevelopmentNode.TIMBER_RIGHTS,
            DevelopmentNode.STORES_AND_ROADS,
            DevelopmentNode.CULTIVATED_GROUND,
            DevelopmentNode.HOME,
            DevelopmentNode.HOSPITALITY,
            DevelopmentNode.FIRST_WATCH
        )) {
            unlocked.add(StringTag.valueOf(node.id()));
        }
        tag.getCompound("QuestCounters").remove("GuardEquipmentDeliveries");
        tag.remove("SeenGuardEquipmentRequests");
        DevelopmentState migrated = DevelopmentState.readNbt(tag);
        assertFalse(migrated.quarantined());
        return migrated;
    }

    private static JourneyState migratedDefinitionTwoAtArmStep() {
        JourneyState old = JourneyState.fresh(SETTLEMENT);
        for (JourneyStep step : JourneyDefinition.LEGACY_V2.orderedSteps()) {
            UUID transaction = JourneyStateTest.uuid(50_000 + step.ordinal());
            for (JourneyEvent event : step.requiredEvents()) {
                old.record(JourneyStateTest.evidence(step.id(), event,
                    transaction, JourneySource.SURVIVAL,
                    event.terminalOutcomeRequired()
                        ? JourneyOutcome.HELD : JourneyOutcome.NONE),
                    JourneyDefinition.LEGACY_V2);
            }
            if (step.id().equals(JourneyIds.FJ_550_SET_GUARD_ORDER)) {
                break;
            }
        }
        CompoundTag tag = old.writeNbt();
        tag.putInt("DefinitionVersion", 2);
        JourneyState migrated = JourneyState.readNbt(tag, SETTLEMENT);
        assertEquals(JourneyIds.FJ_551_UNLOCK_ARM_THE_WATCH,
            migrated.currentStep().orElseThrow().id());
        return migrated;
    }

    private static CompoundTag legacy(int version, int wire, String phase,
                                      int revision) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", version);
        tag.putInt("PhaseWireId", wire);
        tag.putString("Phase", phase);
        tag.putInt("Revision", revision);
        return tag;
    }
}
