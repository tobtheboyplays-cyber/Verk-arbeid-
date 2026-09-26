package com.hearthstead.settlement.raid;

import com.hearthstead.settlement.journey.JourneyReadinessGate;
import com.hearthstead.settlement.journey.JourneyState;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FirstRaidReadinessServiceTest {

    @Test
    void blockerProtocolIsExplicitContiguousAndRoundTrips() {
        var values = FirstRaidReadinessService.Blocker.values();
        assertTrue(values.length <= FirstRaidReadinessService.MAX_BLOCKERS);
        for (int index = 0; index < values.length; index++) {
            assertEquals(index, values[index].wireId());
            assertFalse(values[index].id().isBlank());
            assertEquals(values[index],
                FirstRaidReadinessService.Blocker.fromWireId(index));
        }
        assertEquals(null,
            FirstRaidReadinessService.Blocker.fromWireId(-1));
        assertEquals(null,
            FirstRaidReadinessService.Blocker.fromWireId(values.length));
    }

    @Test
    void allBlockersAreUniqueBoundedAndInStableProtocolOrder() {
        FirstRaidReadinessService.Report report =
            FirstRaidReadinessService.evaluateForTest(
                new FirstRaidReadinessService.Facts());
        assertFalse(report.ready());
        assertTrue(report.blockers().size()
            <= FirstRaidReadinessService.MAX_BLOCKERS);
        assertEquals(report.blockers().size(),
            new HashSet<>(report.blockers()).size());
        List<Integer> ids = report.blockers().stream()
            .map(FirstRaidReadinessService.Blocker::wireId).toList();
        List<Integer> sorted = new ArrayList<>(ids);
        sorted.sort(Integer::compareTo);
        assertEquals(sorted, ids);
        assertTrue(report.blockedBy(
            FirstRaidReadinessService.Blocker.AUTHORITY_INVALID));
        assertTrue(report.blockedBy(
            FirstRaidReadinessService.Blocker.HEARTH_INVALID));
        assertTrue(report.blockedBy(
            FirstRaidReadinessService.Blocker.GUARD_UNARMED));
        assertTrue(report.blockedBy(
            FirstRaidReadinessService.Blocker.ARCHER_UNARMED));
        assertTrue(report.blockedBy(
            FirstRaidReadinessService.Blocker.JOURNEY_NOT_READY));
        assertFalse(report.blockedBy(
            FirstRaidReadinessService.Blocker.SESSION_MISMATCH));
    }

    @TestFactory
    Stream<DynamicTest> everyDomainFailureHasItsOwnBlocker() {
        return Stream.of(
            caseOf("authority", f -> f.authorityValid = false,
                FirstRaidReadinessService.Blocker.AUTHORITY_INVALID),
            caseOf("limit", f -> f.limitsSafe = false,
                FirstRaidReadinessService.Blocker.SNAPSHOT_LIMIT_EXCEEDED),
            caseOf("roster", f -> f.rosterSafe = false,
                FirstRaidReadinessService.Blocker.ROSTER_CORRUPT),
            caseOf("buildings", f -> f.buildingsSafe = false,
                FirstRaidReadinessService.Blocker.BUILDING_REGISTRY_CORRUPT),
            caseOf("legacy proof", f -> f.legacyProofValid = false,
                FirstRaidReadinessService.Blocker.LEGACY_PRODUCTION_PROOF_MISSING),
            caseOf("hearth", f -> f.hearthValid = false,
                FirstRaidReadinessService.Blocker.HEARTH_INVALID),
            caseOf("camp", f -> f.lumberCampValid = false,
                FirstRaidReadinessService.Blocker.LUMBER_CAMP_INVALID),
            caseOf("warehouse", f -> f.warehouseValid = false,
                FirstRaidReadinessService.Blocker.WAREHOUSE_INVALID),
            caseOf("farmhouse", f -> f.farmhouseValid = false,
                FirstRaidReadinessService.Blocker.FARMHOUSE_INVALID),
            caseOf("housing", f -> f.housingPresent = false,
                FirstRaidReadinessService.Blocker.HOUSING_MISSING),
            caseOf("housing capacity", f -> f.housingSufficient = false,
                FirstRaidReadinessService.Blocker.HOUSING_INSUFFICIENT),
            caseOf("tavern", f -> f.tavernValid = false,
                FirstRaidReadinessService.Blocker.TAVERN_INVALID),
            caseOf("barracks", f -> f.barracksValid = false,
                FirstRaidReadinessService.Blocker.BARRACKS_INVALID),
            caseOf("lumberer", f -> f.lumbererValid = false,
                FirstRaidReadinessService.Blocker.LUMBERER_INVALID),
            caseOf("courier", f -> f.courierValid = false,
                FirstRaidReadinessService.Blocker.COURIER_INVALID),
            caseOf("farmer", f -> f.farmerValid = false,
                FirstRaidReadinessService.Blocker.FARMER_INVALID),
            caseOf("guard", f -> f.guardValid = false,
                FirstRaidReadinessService.Blocker.GUARD_INVALID),
            caseOf("lumber zone", f -> f.lumberZoneCommitted = false,
                FirstRaidReadinessService.Blocker.LUMBER_ZONE_MISSING),
            caseOf("farm zone", f -> f.farmZoneCommitted = false,
                FirstRaidReadinessService.Blocker.FARM_ZONE_MISSING),
            caseOf("lumber provenance", f -> f.lumberProvenance = false,
                FirstRaidReadinessService.Blocker.LUMBER_PROVENANCE_MISSING),
            caseOf("farm provenance", f -> f.farmProvenance = false,
                FirstRaidReadinessService.Blocker.FARM_PROVENANCE_MISSING),
            caseOf("resident observation", f -> f.workerStacksObserved = false,
                FirstRaidReadinessService.Blocker.WORKER_STACKS_UNOBSERVED),
            caseOf("worker transit", f -> f.workerTransitUnresolved = true,
                FirstRaidReadinessService.Blocker.WORKER_TRANSIT_UNRESOLVED),
            caseOf("worker ownership", f -> f.workerStackConflict = true,
                FirstRaidReadinessService.Blocker.WORKER_STACK_CONFLICT),
            caseOf("warehouse storage", f -> f.warehouseStorageAvailable = false,
                FirstRaidReadinessService.Blocker.WAREHOUSE_STORAGE_UNAVAILABLE),
            caseOf("request quarantine", f -> f.requestQuarantined = true,
                FirstRaidReadinessService.Blocker.REQUEST_LEDGER_QUARANTINED),
            caseOf("request conflict", f -> f.requestCriticalConflict = true,
                FirstRaidReadinessService.Blocker.REQUEST_CRITICAL_CONFLICT),
            caseOf("request transit", f -> f.requestTransitUnresolved = true,
                FirstRaidReadinessService.Blocker.REQUEST_IN_TRANSIT_UNRESOLVED),
            caseOf("guard weapon", f -> f.guardArmed = false,
                FirstRaidReadinessService.Blocker.GUARD_UNARMED),
            caseOf("guard order", f -> f.guardOrderValid = false,
                FirstRaidReadinessService.Blocker.GUARD_ORDER_INVALID),
            caseOf("food reservation", f -> f.foodReservationsValid = false,
                FirstRaidReadinessService.Blocker.FOOD_RESERVATION_INVALID),
            caseOf("food reserve", f -> f.foodReserveSufficient = false,
                FirstRaidReadinessService.Blocker.FOOD_RESERVE_INSUFFICIENT),
            caseOf("raid integrity", f -> f.raidLifecycleHealthy = false,
                FirstRaidReadinessService.Blocker.RAID_LIFECYCLE_INVALID),
            caseOf("raid state", f -> f.raidPreparing = false,
                FirstRaidReadinessService.Blocker.RAID_STATE_NOT_PREPARING),
            caseOf("journey", f -> f.journeyReady = false,
                FirstRaidReadinessService.Blocker.JOURNEY_NOT_READY),
            caseOf("watchtower", f -> f.watchtowerValid = false,
                FirstRaidReadinessService.Blocker.WATCHTOWER_INVALID),
            caseOf("archer", f -> f.archerValid = false,
                FirstRaidReadinessService.Blocker.ARCHER_INVALID),
            caseOf("archer bow", f -> f.archerArmed = false,
                FirstRaidReadinessService.Blocker.ARCHER_UNARMED),
            caseOf("archer arrows", f -> f.archerHasArrows = false,
                FirstRaidReadinessService.Blocker.ARCHER_NO_ARROWS),
            caseOf("archer order", f -> f.archerOrderValid = false,
                FirstRaidReadinessService.Blocker.ARCHER_ORDER_INVALID),
            caseOf("fifth settler", f -> f.settlerRosterSufficient = false,
                FirstRaidReadinessService.Blocker.SETTLER_ROSTER_INSUFFICIENT),
            caseOf("distinct defenders", f -> f.defendersDistinct = false,
                FirstRaidReadinessService.Blocker.DEFENDERS_NOT_DISTINCT)
        ).map(testCase -> DynamicTest.dynamicTest(testCase.name(), () -> {
            FirstRaidReadinessService.Facts facts = readyFacts();
            testCase.mutation().accept(facts);
            FirstRaidReadinessService.Report report =
                FirstRaidReadinessService.evaluateForTest(facts);
            assertEquals(List.of(testCase.blocker()), report.blockers());
        }));
    }

    @Test
    void unavailableSubsystemsDoNotInventLowerLevelDiagnosis() {
        FirstRaidReadinessService.Facts provenance = readyFacts();
        provenance.provenanceAuthoritative = false;
        provenance.lumberZoneCommitted = false;
        provenance.farmZoneCommitted = false;
        provenance.lumberProvenance = false;
        provenance.farmProvenance = false;
        assertEquals(List.of(
                FirstRaidReadinessService.Blocker.WORK_PROVENANCE_UNAVAILABLE),
            FirstRaidReadinessService.evaluateForTest(provenance).blockers());

        FirstRaidReadinessService.Facts requests = readyFacts();
        requests.requestAvailable = false;
        requests.requestQuarantined = true;
        requests.requestCriticalConflict = true;
        requests.requestTransitUnresolved = true;
        assertEquals(List.of(
                FirstRaidReadinessService.Blocker.REQUEST_LEDGER_UNAVAILABLE),
            FirstRaidReadinessService.evaluateForTest(requests).blockers());
    }

    @Test
    void evaluationIsPureAndRevisionChangesOnlyWithObservedFacts() {
        FirstRaidReadinessService.Facts facts = readyFacts();
        long identityBefore = facts.identityHash;
        FirstRaidReadinessService.Report first =
            FirstRaidReadinessService.evaluateForTest(facts);
        FirstRaidReadinessService.Report replay =
            FirstRaidReadinessService.evaluateForTest(facts);
        assertTrue(first.ready());
        assertEquals(first, replay);
        assertEquals(identityBefore, facts.identityHash);

        facts.readyMeals++;
        FirstRaidReadinessService.Report changed =
            FirstRaidReadinessService.evaluateForTest(facts);
        assertTrue(changed.ready());
        assertNotEquals(first.domainRevision(), changed.domainRevision());
    }

    @Test
    void explicitSkippedJourneyPassesPresentationRuleWithoutEvidence() {
        UUID settlementId = UUID.randomUUID();
        JourneyState skipped = JourneyState.skipped(settlementId);
        assertTrue(JourneyReadinessGate.canRecordDeclaration(skipped));
        assertFalse(JourneyReadinessGate.prerequisitesThroughFirstWatch(skipped));
    }

    @Test
    void zeroOneAndTwoDefenderSnapshotsFailClosedAtTheExactBoundary() {
        FirstRaidReadinessService.Facts zero = readyFacts();
        zero.guardValid = false;
        zero.guardArmed = false;
        zero.guardOrderValid = false;
        zero.archerValid = false;
        zero.archerArmed = false;
        zero.archerHasArrows = false;
        zero.archerOrderValid = false;
        var zeroReport = FirstRaidReadinessService.evaluateForTest(zero);
        assertTrue(zeroReport.blockedBy(
            FirstRaidReadinessService.Blocker.GUARD_INVALID));
        assertTrue(zeroReport.blockedBy(
            FirstRaidReadinessService.Blocker.ARCHER_INVALID));

        FirstRaidReadinessService.Facts guardOnly = readyFacts();
        guardOnly.archerValid = false;
        guardOnly.archerArmed = false;
        guardOnly.archerHasArrows = false;
        guardOnly.archerOrderValid = false;
        var guardOnlyReport = FirstRaidReadinessService.evaluateForTest(
            guardOnly);
        assertFalse(guardOnlyReport.blockedBy(
            FirstRaidReadinessService.Blocker.GUARD_INVALID));
        assertTrue(guardOnlyReport.blockedBy(
            FirstRaidReadinessService.Blocker.ARCHER_INVALID));

        FirstRaidReadinessService.Facts both = readyFacts();
        assertTrue(FirstRaidReadinessService.evaluateForTest(both).ready());

        both.rosterSafe = false;
        both.defendersDistinct = false;
        var duplicate = FirstRaidReadinessService.evaluateForTest(both);
        assertTrue(duplicate.blockedBy(
            FirstRaidReadinessService.Blocker.ROSTER_CORRUPT));
        assertTrue(duplicate.blockedBy(
            FirstRaidReadinessService.Blocker.DEFENDERS_NOT_DISTINCT));
    }

    @Test
    void declarationScheduleBridgeJourneyExecutionSequenceIsClosed() {
        FirstRaidReadinessService.Facts facts = readyFacts();
        assertTrue(FirstRaidReadinessService.evaluateForTest(facts).ready());
        assertEquals(List.of(
                FirstRaidReadinessService.Blocker.RAID_STATE_NOT_SCHEDULED),
            FirstRaidReadinessService
                .evaluateScheduledCommitBridgeForTest(facts).blockers());
        assertEquals(List.of(
                FirstRaidReadinessService.Blocker.JOURNEY_NOT_READY,
                FirstRaidReadinessService.Blocker.RAID_STATE_NOT_SCHEDULED),
            FirstRaidReadinessService.evaluateExecutionForTest(facts)
                .blockers());

        // scheduleAfterReadiness crosses only the persisted raid-calendar
        // boundary. FJ-560 is intentionally still recordable at this point.
        facts.raidPreparing = false;
        facts.raidScheduled = true;
        assertEquals(List.of(
                FirstRaidReadinessService.Blocker.RAID_STATE_NOT_PREPARING),
            FirstRaidReadinessService.evaluateForTest(facts).blockers());
        assertTrue(FirstRaidReadinessService
            .evaluateScheduledCommitBridgeForTest(facts).ready());
        assertEquals(List.of(
                FirstRaidReadinessService.Blocker.JOURNEY_NOT_READY),
            FirstRaidReadinessService.evaluateExecutionForTest(facts)
                .blockers());

        // The Journey hook records FJ-560. That closes the one-purpose bridge
        // and opens only the warning/attack execution phase.
        facts.journeyReady = false;
        facts.journeyDeclared = true;
        assertEquals(List.of(
                FirstRaidReadinessService.Blocker.RAID_STATE_NOT_PREPARING,
                FirstRaidReadinessService.Blocker.JOURNEY_NOT_READY),
            FirstRaidReadinessService.evaluateForTest(facts).blockers());
        assertEquals(List.of(
                FirstRaidReadinessService.Blocker.JOURNEY_NOT_READY),
            FirstRaidReadinessService
                .evaluateScheduledCommitBridgeForTest(facts).blockers());
        assertTrue(FirstRaidReadinessService
            .evaluateExecutionForTest(facts).ready());

        facts.farmerValid = false;
        assertEquals(List.of(
                FirstRaidReadinessService.Blocker.FARMER_INVALID),
            FirstRaidReadinessService.evaluateExecutionForTest(facts)
                .blockers());
    }

    private static TestCase caseOf(String name,
                                   Consumer<FirstRaidReadinessService.Facts> mutation,
                                   FirstRaidReadinessService.Blocker blocker) {
        return new TestCase(name, mutation, blocker);
    }

    private static FirstRaidReadinessService.Facts readyFacts() {
        FirstRaidReadinessService.Facts facts =
            new FirstRaidReadinessService.Facts();
        facts.authorityValid = true;
        facts.limitsSafe = true;
        facts.rosterSafe = true;
        facts.buildingsSafe = true;
        facts.legacyProofValid = true;
        facts.hearthValid = true;
        facts.lumberCampValid = true;
        facts.warehouseValid = true;
        facts.farmhouseValid = true;
        facts.housingPresent = true;
        facts.housingSufficient = true;
        facts.tavernValid = true;
        facts.barracksValid = true;
        facts.lumbererValid = true;
        facts.courierValid = true;
        facts.farmerValid = true;
        facts.guardValid = true;
        facts.provenanceAuthoritative = true;
        facts.lumberZoneCommitted = true;
        facts.farmZoneCommitted = true;
        facts.lumberProvenance = true;
        facts.farmProvenance = true;
        facts.workerStacksObserved = true;
        facts.workerTransitUnresolved = false;
        facts.workerStackConflict = false;
        facts.warehouseStorageAvailable = true;
        facts.requestAvailable = true;
        facts.requestQuarantined = false;
        facts.requestCriticalConflict = false;
        facts.requestTransitUnresolved = false;
        facts.guardArmed = true;
        facts.guardOrderValid = true;
        facts.watchtowerValid = true;
        facts.archerValid = true;
        facts.archerArmed = true;
        facts.archerHasArrows = true;
        facts.archerOrderValid = true;
        facts.settlerRosterSufficient = true;
        facts.defendersDistinct = true;
        facts.foodReservationsValid = true;
        facts.foodReserveSufficient = true;
        facts.raidLifecycleHealthy = true;
        facts.raidPreparing = true;
        facts.raidScheduled = false;
        facts.journeyReady = true;
        facts.journeyDeclared = false;
        facts.readyMeals = 32;
        facts.availableReadyMeals = 32;
        facts.requiredReadyMeals = 32;
        facts.identityHash = 0x1234_5678_9abcl;
        return facts;
    }

    private record TestCase(String name,
                            Consumer<FirstRaidReadinessService.Facts> mutation,
                            FirstRaidReadinessService.Blocker blocker) {
    }
}
