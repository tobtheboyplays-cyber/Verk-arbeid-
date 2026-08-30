package com.hearthstead.settlement.journey;

import com.hearthstead.settlement.raid.RaidLogEntry;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import com.hearthstead.util.AuthorityTelemetry;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JourneyServerHooksRaidAuthorityTest {

    @Test
    void onlyAnExactAppliedSingleStepMayEmitRaidAuthority() {
        assertTrue(JourneyServerHooks.raidAuthorityTransitionApplied(
            JourneyApplyResult.APPLIED, 7, 8, 39, 40));
        assertFalse(JourneyServerHooks.raidAuthorityTransitionApplied(
            JourneyApplyResult.DUPLICATE, 7, 7, 39, 39));
        assertFalse(JourneyServerHooks.raidAuthorityTransitionApplied(
            JourneyApplyResult.ALREADY_COMPLETED, 7, 7, 39, 39));
        assertFalse(JourneyServerHooks.raidAuthorityTransitionApplied(
            JourneyApplyResult.RECORDED_NON_PROGRESS, 7, 8, 39, 39));
        assertFalse(JourneyServerHooks.raidAuthorityTransitionApplied(
            JourneyApplyResult.APPLIED, 7, 7, 39, 40));
        assertFalse(JourneyServerHooks.raidAuthorityTransitionApplied(
            JourneyApplyResult.APPLIED, 7, 8, 39, 41));
    }

    @Test
    void warningTokenContainsEveryExactPersistedPlanFact() {
        UUID captain = UUID.fromString(
            "00000000-0000-0000-0000-000000000456");
        RaidPlan plan = new RaidPlan(captain, RaidObjective.BRANN, -42.5F, 72L);

        String target = JourneyServerHooks.raidWarningAuthorityTarget(plan);
        String reason = JourneyServerHooks.raidWarningAuthorityReason(plan);

        assertEquals("first_raid_warning:72:captain:" + captain, target);
        assertEquals("persisted_plan:brann:approach_bits:"
            + Integer.toUnsignedString(Float.floatToIntBits(-42.5F)), reason);
        assertTrue(AuthorityTelemetry.isCanonicalToken(target));
        assertTrue(AuthorityTelemetry.isCanonicalToken(reason));
    }

    @Test
    void aftermathDigestBindsTheCompletePersistedReportWithinTokenBounds() {
        RaidLogEntry report = new RaidLogEntry(10_000_000L,
            "Eira Storm-Song", "losepenger", false, 1_000_000,
            999_999, "beleiring");
        RaidLogEntry differentCaptain = new RaidLogEntry(report.night(),
            "Eira Storm-Song II", report.objectiveId(), report.held(),
            report.itemsStolen(), report.settlersHurt(), report.stageAfterId());
        RaidLogEntry differentStage = new RaidLogEntry(report.night(),
            report.captainName(), report.objectiveId(), report.held(),
            report.itemsStolen(), report.settlersHurt(), "varsel");
        RaidLogEntry differentNight = new RaidLogEntry(report.night() - 1L,
            report.captainName(), report.objectiveId(), report.held(),
            report.itemsStolen(), report.settlersHurt(), report.stageAfterId());
        RaidLogEntry differentObjective = new RaidLogEntry(report.night(),
            report.captainName(), "brann", report.held(),
            report.itemsStolen(), report.settlersHurt(), report.stageAfterId());
        RaidLogEntry differentOutcome = new RaidLogEntry(report.night(),
            report.captainName(), report.objectiveId(), true,
            report.itemsStolen(), report.settlersHurt(), report.stageAfterId());
        RaidLogEntry differentStolen = new RaidLogEntry(report.night(),
            report.captainName(), report.objectiveId(), report.held(),
            report.itemsStolen() - 1, report.settlersHurt(),
            report.stageAfterId());
        RaidLogEntry differentHurt = new RaidLogEntry(report.night(),
            report.captainName(), report.objectiveId(), report.held(),
            report.itemsStolen(), report.settlersHurt() - 1,
            report.stageAfterId());

        String target = JourneyServerHooks.raidAftermathAuthorityTarget(report);
        String reason = JourneyServerHooks.raidAftermathAuthorityReason(report);

        assertTrue(target.startsWith("raid_aftermath:10000000:report:"));
        assertEquals(95, target.length(), "maximum valid report target must fit V1");
        assertEquals("report_viewed:lost:losepenger:stolen:1000000:"
            + "hurt:999999:stage:beleiring", reason);
        assertTrue(AuthorityTelemetry.isCanonicalToken(target));
        assertTrue(AuthorityTelemetry.isCanonicalToken(reason));
        assertNotEquals(target,
            JourneyServerHooks.raidAftermathAuthorityTarget(differentCaptain));
        assertNotEquals(target,
            JourneyServerHooks.raidAftermathAuthorityTarget(differentStage));
        assertNotEquals(target,
            JourneyServerHooks.raidAftermathAuthorityTarget(differentNight));
        assertNotEquals(target,
            JourneyServerHooks.raidAftermathAuthorityTarget(differentObjective));
        assertNotEquals(target,
            JourneyServerHooks.raidAftermathAuthorityTarget(differentOutcome));
        assertNotEquals(target,
            JourneyServerHooks.raidAftermathAuthorityTarget(differentStolen));
        assertNotEquals(target,
            JourneyServerHooks.raidAftermathAuthorityTarget(differentHurt));
    }

    @Test
    void terminalTelemetryKeepsHitDistinctFromSettlementLoss() {
        assertEquals("settlement_held",
            JourneyServerHooks.raidResolutionAuthorityReason(
                JourneyOutcome.HELD));
        assertEquals("settlement_hit",
            JourneyServerHooks.raidResolutionAuthorityReason(
                JourneyOutcome.HIT));
        assertEquals("settlement_lost",
            JourneyServerHooks.raidResolutionAuthorityReason(
                JourneyOutcome.SETTLEMENT_LOST));
        assertNotEquals(
            JourneyServerHooks.raidResolutionAuthorityReason(
                JourneyOutcome.HIT),
            JourneyServerHooks.raidResolutionAuthorityReason(
                JourneyOutcome.SETTLEMENT_LOST));
    }
}
