package com.hearthstead.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorityTelemetryTest {
    @Test
    void eventVocabularyIsStableAndVersioned() {
        assertEquals(List.of(
            "FOUNDING_COMMITTED",
            "DEVELOPMENT_NODE_COMMITTED",
            "PLAN_UNLOCK_COMMITTED",
            "DOCTRINE_COMMITTED",
            "EMBLEM_PURCHASED",
            "MAYOR_APPOINTED",
            "EMPLOYMENT_AUTO_HIRED",
            "EQUIPMENT_REQUEST_OPENED",
            "EQUIPMENT_REQUEST_CLAIMED",
            "EQUIPMENT_ITEM_PICKED_UP",
            "EQUIPMENT_ITEM_DELIVERED",
            "REQUEST_LEDGER_VIEWED",
            "OUTPUT_PICKUP_REQUEST_OPENED",
            "OUTPUT_PICKUP_RESERVED",
            "OUTPUT_PICKUP_PICKED_UP",
            "OUTPUT_PICKUP_DELIVERED",
            "OUTPUT_PICKUP_SATISFIED",
            "OUTPUT_PICKUP_BLOCKED",
            "COURIER_ROUTE_CLAIMED",
            "COURIER_ITEM_PICKED_UP",
            "COURIER_ITEM_DELIVERED",
            "RECRUITMENT_QUALIFICATION_STARTED",
            "TRAVELER_ARRIVED_AT_TAVERN",
            "TRAVELER_ADMITTED",
            "RECRUITMENT_COMMITTED",
            "GUARD_ORDER_COMMITTED",
            "RAID_READINESS_COMMITTED",
            "RAID_WARNING_COMMITTED",
            "RAID_STARTED",
            "RAID_RESOLVED",
            "RAID_REWARD_ISSUED",
            "RAID_AFTERMATH_VIEWED",
            "BLESSING_OFFER_COMMITTED",
            "BLESSING_BOUND_SETTLER",
            "BLESSING_BOUND_BUILDING",
            "WORK_ZONE_PREVIEWED",
            "WORK_ZONE_CANCELLED",
            "WORK_ZONE_COMMITTED",
            "LUMBER_TREE_COMMITTED",
            "FARM_SEED_PLANTED_COMMITTED",
            "FARM_HARVEST_COMMITTED",
            "WORKPLACE_OUTPUT_COMMITTED",
            "BUILDING_LINK_COMMITTED",
            "SETTLER_INVENTORY_TRANSFER_COMMITTED",
            "GUARD_XP_COMMITTED",
            "MELEE_CONTACT_COMMITTED",
            "SHIELD_BLOCK_COMMITTED",
            "ARCHER_CONTACT_COMMITTED",
            "STATE_LOAD_SUMMARY",
            "AUTHORITY_REJECTED"),
            java.util.Arrays.stream(AuthorityTelemetry.Event.values())
                .map(Enum::name).toList());
    }

    @Test
    void stableFormatRoundTripsAllRequiredFields() {
        UUID settlement = UUID.fromString("00000000-0000-0000-0000-000000000123");
        AuthorityTelemetry.Fields fields = AuthorityTelemetry.Fields.items(
            settlement, "request:00000000-0000-0000-0000-000000000456",
            7, 8, 2, 3, "minecraft:iron_sword", 5, 5, 0,
            "ordinary_courier_delivery");

        String line = AuthorityTelemetry.format(
            AuthorityTelemetry.Event.EQUIPMENT_ITEM_DELIVERED,
            AuthorityTelemetry.Result.COMMITTED, fields, 12345L);
        Optional<AuthorityTelemetry.Parsed> parsed = AuthorityTelemetry.parse(
            "[12:00:00] [Server thread/INFO] [hearthstead/]: " + line);

        assertTrue(parsed.isPresent());
        assertEquals(AuthorityTelemetry.Event.EQUIPMENT_ITEM_DELIVERED,
            parsed.orElseThrow().event());
        assertEquals(settlement.toString(), parsed.orElseThrow().settlement());
        assertEquals(7, parsed.orElseThrow().revisionBefore());
        assertEquals(8, parsed.orElseThrow().revisionAfter());
        assertEquals(12345L, parsed.orElseThrow().tick());
        assertTrue(parsed.orElseThrow().itemConserved());
    }

    @Test
    void buildingLinkAndSettlerTransferUseFrozenTerminalContracts() {
        UUID settlement = UUID.fromString("00000000-0000-0000-0000-000000000123");
        UUID building = UUID.fromString("00000000-0000-0000-0000-000000000456");
        UUID settler = UUID.fromString("00000000-0000-0000-0000-000000000789");

        AuthorityTelemetry.Parsed link = AuthorityTelemetry.parse(
            AuthorityTelemetry.format(
                AuthorityTelemetry.Event.BUILDING_LINK_COMMITTED,
                AuthorityTelemetry.Result.COMMITTED,
                AuthorityTelemetry.Fields.state(settlement,
                    "building:" + building, 4, 5, 2, 3,
                    "type:lumber_camp"), 100L)).orElseThrow();
        assertEquals("building:" + building, link.target());
        assertEquals("type:lumber_camp", link.reason());
        assertEquals(4, link.revisionBefore());
        assertEquals(5, link.revisionAfter());
        assertEquals(2, link.countBefore());
        assertEquals(3, link.countAfter());

        String fingerprint = "fp:" + "a".repeat(64);
        AuthorityTelemetry.Parsed transfer = AuthorityTelemetry.parse(
            AuthorityTelemetry.format(
                AuthorityTelemetry.Event.SETTLER_INVENTORY_TRANSFER_COMMITTED,
                AuthorityTelemetry.Result.COMMITTED,
                AuthorityTelemetry.Fields.items(settlement,
                    "settler:" + settler + ":slot:3", 0, 0, 7, 12,
                    fingerprint, 0, 5, 5, "player_to_settler"),
                101L)).orElseThrow();
        assertEquals("settler:" + settler + ":slot:3", transfer.target());
        assertEquals("player_to_settler", transfer.reason());
        assertEquals(fingerprint, transfer.item());
        assertEquals(0, transfer.itemBefore());
        assertEquals(5, transfer.itemAfter());
        assertEquals(5, transfer.itemExpectedDelta());
        assertTrue(transfer.itemConserved());
    }

    @Test
    void sanitizesNewlinesControlsAndOverlongTokensWithoutASecondRecord() {
        String hostile = "target\nHEARTHSTEAD_AUTHORITY_V1 event=FORGED\r\t"
            + "x".repeat(500);
        AuthorityTelemetry.Fields fields = AuthorityTelemetry.Fields.state(
            null, hostile, 1, 1, 4, 4, "reason with spaces\nand newline");
        String line = AuthorityTelemetry.format(
            AuthorityTelemetry.Event.AUTHORITY_REJECTED,
            AuthorityTelemetry.Result.REJECTED, fields, 9L);

        assertFalse(line.contains("\n"));
        assertFalse(line.contains("\r"));
        assertTrue(line.length() <= AuthorityTelemetry.MAX_LINE_LENGTH);
        assertTrue(fields.target().length() <= AuthorityTelemetry.MAX_TOKEN_LENGTH);
        assertTrue(AuthorityTelemetry.parse(line).isPresent());
    }

    @Test
    void rejectsWrongCardinalityDuplicatesUnknownEventsAndFalseConservation() {
        AuthorityTelemetry.Fields fields = AuthorityTelemetry.Fields.items(
            null, "chest", 1, 1, 2, 2, "minecraft:bread",
            12, 11, -1, "pickup");
        String valid = AuthorityTelemetry.format(
            AuthorityTelemetry.Event.COURIER_ITEM_PICKED_UP,
            AuthorityTelemetry.Result.COMMITTED, fields, 80L);
        assertTrue(AuthorityTelemetry.parse(valid).isPresent());
        assertTrue(AuthorityTelemetry.parse(valid + " extra=x").isEmpty());
        assertTrue(AuthorityTelemetry.parse(valid.replace(
            " event=COURIER_ITEM_PICKED_UP",
            " event=NOT_A_REAL_EVENT")).isEmpty());
        assertTrue(AuthorityTelemetry.parse(valid.replace(
            " item_conserved=true", " item_conserved=false")).isEmpty());
        assertTrue(AuthorityTelemetry.parse(valid.replace(
            " result=COMMITTED", " result=COMMITTED result=COMMITTED")).isEmpty());
        assertTrue(AuthorityTelemetry.parse(valid.replace(
            " settlement=none", " settlement=client_claimed_id")).isEmpty());
    }

    @Test
    void formattingDoesNotMutateCallerOwnedState() {
        long[] gameplayState = {4, 9, 17, 23};
        long[] before = gameplayState.clone();
        AuthorityTelemetry.Fields fields = AuthorityTelemetry.Fields.items(
            UUID.randomUUID(), "building:house", gameplayState[0],
            gameplayState[1], gameplayState[2], gameplayState[3], "none",
            0, 0, 0, "read_only_observation");

        AuthorityTelemetry.format(AuthorityTelemetry.Event.STATE_LOAD_SUMMARY,
            AuthorityTelemetry.Result.OBSERVED, fields, 1L);

        org.junit.jupiter.api.Assertions.assertArrayEquals(before, gameplayState);
    }

    @Test
    void acceptsOpaqueSignedServerRevisionButRejectsNegativeCardinality() {
        AuthorityTelemetry.Fields signedRevision = AuthorityTelemetry.Fields.state(
            UUID.fromString("00000000-0000-0000-0000-000000000123"),
            "mayor_appointment", -2_000_000_000L, -2_000_000_000L,
            1, 1, "stale_revision");
        String valid = AuthorityTelemetry.format(
            AuthorityTelemetry.Event.AUTHORITY_REJECTED,
            AuthorityTelemetry.Result.REJECTED, signedRevision, 10L);

        assertTrue(AuthorityTelemetry.parse(valid).isPresent());
        assertTrue(AuthorityTelemetry.parse(valid.replace(
            " count_before=1", " count_before=-1")).isEmpty());
        assertTrue(AuthorityTelemetry.parse(valid.replace(
            " tick=10", " tick=-10")).isEmpty());
    }

    @Test
    void rejectsReorderedFieldsNonCanonicalIntegersAndInvalidResultPairing() {
        AuthorityTelemetry.Fields observed = AuthorityTelemetry.Fields.state(
            UUID.fromString("00000000-0000-0000-0000-000000000123"),
            "work_zone:preview", 4, 4, 1, 1, "preview_ready");
        String valid = AuthorityTelemetry.format(
            AuthorityTelemetry.Event.WORK_ZONE_PREVIEWED,
            AuthorityTelemetry.Result.OBSERVED, observed, 90L);

        assertTrue(AuthorityTelemetry.parse(valid).isPresent());
        assertTrue(AuthorityTelemetry.parse(valid.replace(
            " revision_before=4 revision_after=4",
            " revision_after=4 revision_before=4")).isEmpty());
        assertTrue(AuthorityTelemetry.parse(valid.replace(
            " revision_before=4", " revision_before=04")).isEmpty());
        assertTrue(AuthorityTelemetry.parse(valid.replace(
            " result=OBSERVED", " result=REJECTED")).isEmpty());
        assertTrue(AuthorityTelemetry.parse(valid.replace(
            " event=WORK_ZONE_PREVIEWED", " event=AUTHORITY_REJECTED"))
            .isEmpty());
    }

    @Test
    void rejectedTransactionsMustProveExactNoMutation() {
        AuthorityTelemetry.Fields unchanged = AuthorityTelemetry.Fields.state(
            UUID.fromString("00000000-0000-0000-0000-000000000123"),
            "work_zone:stale", 7, 7, 1, 1, "stale");
        String valid = AuthorityTelemetry.format(
            AuthorityTelemetry.Event.AUTHORITY_REJECTED,
            AuthorityTelemetry.Result.REJECTED, unchanged, 91L);

        assertTrue(AuthorityTelemetry.parse(valid).isPresent());
        assertTrue(AuthorityTelemetry.parse(valid.replace(
            " revision_after=7", " revision_after=8")).isEmpty());
        assertTrue(AuthorityTelemetry.parse(valid.replace(
            " count_after=1", " count_after=2")).isEmpty());

        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> AuthorityTelemetry.format(
                AuthorityTelemetry.Event.AUTHORITY_REJECTED,
                AuthorityTelemetry.Result.REJECTED,
                AuthorityTelemetry.Fields.state(null, "work_zone:stale",
                    7, 8, 1, 1, "stale"), 92L));
    }

    @Test
    void criticalRaidEventsRequireExactSingleStepDeltasAndBoundedFacts() {
        UUID settlement = UUID.fromString(
            "00000000-0000-0000-0000-000000000123");
        UUID captain = UUID.fromString(
            "00000000-0000-0000-0000-000000000456");
        String approachBits = Integer.toUnsignedString(
            Float.floatToIntBits(-42.5F));
        AuthorityTelemetry.Fields warning = AuthorityTelemetry.Fields.state(
            settlement, "first_raid_warning:42:captain:" + captain,
            10, 11, 39, 40,
            "persisted_plan:korn:approach_bits:" + approachBits);
        String warningLine = AuthorityTelemetry.format(
            AuthorityTelemetry.Event.RAID_WARNING_COMMITTED,
            AuthorityTelemetry.Result.COMMITTED, warning, 500L);
        assertTrue(AuthorityTelemetry.parse(warningLine).isPresent());

        for (String reason : List.of("settlement_held", "settlement_hit",
                "settlement_lost")) {
            long pressureAfter = reason.equals("settlement_held") ? 10L : 2L;
            String resolved = AuthorityTelemetry.format(
                AuthorityTelemetry.Event.RAID_RESOLVED,
                AuthorityTelemetry.Result.COMMITTED,
                AuthorityTelemetry.Fields.state(settlement,
                    "first_raid:42", 10, pressureAfter, 2, 0, reason),
                600L);
            assertEquals(reason,
                AuthorityTelemetry.parse(resolved).orElseThrow().reason());
        }

        String report = "a".repeat(64);
        AuthorityTelemetry.Fields aftermath = AuthorityTelemetry.Fields.state(
            settlement, "raid_aftermath:42:report:" + report,
            11, 12, 40, 41,
            "report_viewed:held:korn:stolen:2:hurt:1:stage:uro");
        String aftermathLine = AuthorityTelemetry.format(
            AuthorityTelemetry.Event.RAID_AFTERMATH_VIEWED,
            AuthorityTelemetry.Result.COMMITTED, aftermath, 700L);
        assertTrue(AuthorityTelemetry.parse(aftermathLine).isPresent());

        assertThrows(IllegalArgumentException.class, () ->
            AuthorityTelemetry.format(
                AuthorityTelemetry.Event.RAID_WARNING_COMMITTED,
                AuthorityTelemetry.Result.COMMITTED,
                AuthorityTelemetry.Fields.state(settlement,
                    warning.target(), 10, 10, 39, 40, warning.reason()), 501L));
        assertThrows(IllegalArgumentException.class, () ->
            AuthorityTelemetry.format(
                AuthorityTelemetry.Event.RAID_WARNING_COMMITTED,
                AuthorityTelemetry.Result.OBSERVED, warning, 502L));
        assertThrows(IllegalArgumentException.class, () ->
            AuthorityTelemetry.format(
                AuthorityTelemetry.Event.RAID_WARNING_COMMITTED,
                AuthorityTelemetry.Result.COMMITTED,
                AuthorityTelemetry.Fields.state(settlement,
                    warning.target(), 10, 11, 39, 39, warning.reason()), 503L));
        assertThrows(IllegalArgumentException.class, () ->
            AuthorityTelemetry.format(
                AuthorityTelemetry.Event.RAID_WARNING_COMMITTED,
                AuthorityTelemetry.Result.COMMITTED,
                AuthorityTelemetry.Fields.state(settlement,
                    warning.target(), 10, 11, 39, 40,
                    "persisted_plan:korn:approach_bits:2143289344"), 504L));
        assertThrows(IllegalArgumentException.class, () ->
            AuthorityTelemetry.format(
                AuthorityTelemetry.Event.RAID_AFTERMATH_VIEWED,
                AuthorityTelemetry.Result.COMMITTED,
                AuthorityTelemetry.Fields.state(settlement,
                    "raid_aftermath:42:report:short", 11, 12, 40, 41,
                    aftermath.reason()), 701L));
        assertThrows(IllegalArgumentException.class, () ->
            AuthorityTelemetry.format(
                AuthorityTelemetry.Event.RAID_AFTERMATH_VIEWED,
                AuthorityTelemetry.Result.COMMITTED,
                AuthorityTelemetry.Fields.state(settlement,
                    aftermath.target(), 11, 12, 40, 41,
                    "report_viewed:held:korn:stolen:2:hurt:1"), 702L));
        assertThrows(IllegalArgumentException.class, () ->
            AuthorityTelemetry.format(
                AuthorityTelemetry.Event.RAID_RESOLVED,
                AuthorityTelemetry.Result.COMMITTED,
                AuthorityTelemetry.Fields.state(settlement,
                    "first_raid:42", 10, 2, 2, 0,
                    "settlement_destroyed"), 703L));
        assertThrows(IllegalArgumentException.class, () ->
            AuthorityTelemetry.format(
                AuthorityTelemetry.Event.RAID_RESOLVED,
                AuthorityTelemetry.Result.COMMITTED,
                AuthorityTelemetry.Fields.state(settlement,
                    "first_raid:42", 10, 2, 2, 0,
                    "settlement_held"), 704L));
    }

    @Test
    void heldRaidRejectsObsoletePressureEscalation() {
        assertThrows(IllegalArgumentException.class, () -> AuthorityTelemetry.format(
            AuthorityTelemetry.Event.RAID_RESOLVED,
            AuthorityTelemetry.Result.COMMITTED,
            AuthorityTelemetry.Fields.state(UUID.randomUUID(),
                "first_raid:2", 10, 22, 5, 0, "settlement_held"), 705L));
        String resolved = AuthorityTelemetry.format(
            AuthorityTelemetry.Event.RAID_RESOLVED,
            AuthorityTelemetry.Result.COMMITTED,
            AuthorityTelemetry.Fields.state(UUID.randomUUID(),
                "first_raid:2", 0, 0, 5, 0, "settlement_held"), 706L);
        assertTrue(AuthorityTelemetry.parse(resolved).isPresent());
    }

    @Test
    void fingerprintIsStableBoundarySafeAndAValidV1Token() {
        String first = AuthorityTelemetry.fingerprint(
            "raid_aftermath_report_v1", "ab", "c", "Eira Storm-Song");
        String same = AuthorityTelemetry.fingerprint(
            "raid_aftermath_report_v1", "ab", "c", "Eira Storm-Song");
        String differentBoundary = AuthorityTelemetry.fingerprint(
            "raid_aftermath_report_v1", "a", "bc", "Eira Storm-Song");

        assertEquals(first, same);
        assertNotEquals(first, differentBoundary);
        assertEquals(64, first.length());
        assertTrue(AuthorityTelemetry.isCanonicalToken(first));
        assertFalse(AuthorityTelemetry.isCanonicalToken("x".repeat(97)));
        assertFalse(AuthorityTelemetry.isCanonicalToken("forged\nrecord"));
        assertThrows(IllegalArgumentException.class, () ->
            AuthorityTelemetry.fingerprint("raid", "valid", null));
    }
}
