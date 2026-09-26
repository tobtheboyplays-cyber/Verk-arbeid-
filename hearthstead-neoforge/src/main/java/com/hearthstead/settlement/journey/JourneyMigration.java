package com.hearthstead.settlement.journey;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Explicit legacy FoundingJourney v1/v2 to Journey schema-3 migration.
 *
 * <p>No item, unlock, worker or historical transaction is inferred here.
 * Callers may provide only independently corroborated MIGRATION evidence.
 */
public final class JourneyMigration {
    public enum Disposition {
        ACTIVE_MIGRATED,
        DEFINITION_MIGRATED,
        LEGACY_PRESENTATION_SKIPPED,
        RESUMED,
        QUARANTINED
    }

    /**
     * Validates an exact schema-3/definition-2 ledger before replaying its
     * immutable evidence into definition 3. No new First Watch evidence is
     * inferred: even a formerly complete FJ-560 chain becomes ACTIVE until
     * the eleven appended objectives are proven in the live world.
     */
    public static Result migrateDefinitionV2(CompoundTag definitionTwoTag,
                                             UUID settlementId) {
        Objects.requireNonNull(settlementId, "settlementId");
        JourneyState validated = JourneyState.readExactNbt(definitionTwoTag,
            settlementId, JourneyDefinition.LEGACY_V2, 2);
        if (validated.mode() == JourneyPresentationMode.QUARANTINED) {
            return quarantined(settlementId,
                "definition_two_" + validated.quarantineReason());
        }
        if (validated.mode() == JourneyPresentationMode.SKIPPED) {
            return new Result(JourneyState.skipped(settlementId),
                Disposition.DEFINITION_MIGRATED,
                "definition_two_skipped_preserved", false);
        }

        JourneyState migrated = JourneyState.fresh(settlementId);
        for (JourneyEvidence evidence : validated.evidence()) {
            JourneyApplyResult applied = migrated.record(evidence,
                JourneyDefinition.CURRENT);
            if (applied == JourneyApplyResult.QUARANTINED) {
                return quarantined(settlementId,
                    "definition_two_evidence_rejected");
            }
        }
        return new Result(migrated, Disposition.DEFINITION_MIGRATED,
            "definition_two_evidence_preserved_new_watch_required", false);
    }

    public record Result(JourneyState state, Disposition disposition,
                         String reportCode, boolean resumeAllowed) {
        public Result {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(disposition, "disposition");
            Objects.requireNonNull(reportCode, "reportCode");
        }
    }

    public static Result migrateLegacy(CompoundTag legacyTag,
                                       UUID settlementId,
                                       boolean explicitResume,
                                       List<JourneyEvidence> approvedEvidence) {
        Objects.requireNonNull(settlementId, "settlementId");
        List<JourneyEvidence> supplied = approvedEvidence == null
            ? List.of() : List.copyOf(approvedEvidence);
        LegacyPhase phase = decodeLegacy(legacyTag);
        if (phase == null || phase == LegacyPhase.QUARANTINED) {
            return quarantined(settlementId, "legacy_journey_invalid");
        }

        boolean establishedPresentation = phase == LegacyPhase.COMPLETE
            || phase == LegacyPhase.SKIPPED;
        if (establishedPresentation && !explicitResume) {
            return new Result(JourneyState.skipped(settlementId),
                Disposition.LEGACY_PRESENTATION_SKIPPED,
                "legacy_presentation_preserved", true);
        }

        JourneyState migrated = JourneyState.fresh(settlementId);
        Set<JourneyEvidence.EvidenceKey> keys = new HashSet<>();
        if (supplied.size() > JourneyIds.ALL_STEPS.size()) {
            return quarantined(settlementId, "migration_evidence_unbounded");
        }
        for (JourneyEvidence evidence : supplied) {
            if (evidence == null || evidence.source() != JourneySource.MIGRATION
                || !settlementId.equals(evidence.settlementId())
                || !keys.add(evidence.key())) {
                return quarantined(settlementId, "migration_evidence_invalid");
            }
            JourneyApplyResult applied = migrated.record(evidence,
                JourneyDefinition.CURRENT);
            if (applied == JourneyApplyResult.QUARANTINED) {
                return quarantined(settlementId, "migration_evidence_rejected");
            }
        }
        return new Result(migrated,
            establishedPresentation ? Disposition.RESUMED : Disposition.ACTIVE_MIGRATED,
            establishedPresentation ? "legacy_journey_resumed" : "legacy_active_migrated",
            establishedPresentation);
    }

    private static LegacyPhase decodeLegacy(CompoundTag tag) {
        if (tag == null
            || !tag.contains("DataVersion", Tag.TAG_INT)
            || !tag.contains("PhaseWireId", Tag.TAG_INT)
            || !tag.contains("Phase", Tag.TAG_STRING)
            || !tag.contains("Revision", Tag.TAG_INT)) {
            return null;
        }
        int version = tag.getInt("DataVersion");
        int wire = tag.getInt("PhaseWireId");
        int revision = tag.getInt("Revision");
        String id = tag.getString("Phase");
        if (version == 1) {
            return switch (wire) {
                case 0 -> exact(id, "build_lumber_camp", revision, 0)
                    ? LegacyPhase.BUILD_LUMBER_CAMP : null;
                case 1 -> exact(id, "hire_lumberer", revision, 1)
                    ? LegacyPhase.HIRE_LUMBERER : null;
                case 2 -> exact(id, "deliver_first_log", revision, 2)
                    ? LegacyPhase.DELIVER_FIRST_LOG : null;
                case 3 -> exact(id, "complete", revision, 3)
                    ? LegacyPhase.COMPLETE : null;
                case 4 -> "skipped".equals(id) && revision >= 0 && revision <= 3
                    ? LegacyPhase.SKIPPED : null;
                default -> null;
            };
        }
        if (version != 2) {
            return null;
        }
        return switch (wire) {
            case 0 -> exact(id, "build_lumber_camp", revision, 0)
                ? LegacyPhase.BUILD_LUMBER_CAMP : null;
            case 1 -> exact(id, "hire_lumberer", revision, 1)
                ? LegacyPhase.HIRE_LUMBERER : null;
            case 5 -> exact(id, "set_lumber_zone", revision, 2)
                ? LegacyPhase.SET_LUMBER_ZONE : null;
            case 2 -> exact(id, "deliver_first_log", revision, 3)
                ? LegacyPhase.DELIVER_FIRST_LOG : null;
            case 3 -> exact(id, "complete", revision, 4)
                ? LegacyPhase.COMPLETE : null;
            case 4 -> "skipped".equals(id) && revision >= 0 && revision <= 4
                ? LegacyPhase.SKIPPED : null;
            case -1 -> exact(id, "quarantined", revision, 0)
                ? LegacyPhase.QUARANTINED : null;
            default -> null;
        };
    }

    private static boolean exact(String actual, String expected,
                                 int revision, int expectedRevision) {
        return expected.equals(actual) && revision == expectedRevision;
    }

    private static Result quarantined(UUID settlementId, String reason) {
        return new Result(JourneyState.quarantined(settlementId, reason),
            Disposition.QUARANTINED, reason, false);
    }

    private enum LegacyPhase {
        BUILD_LUMBER_CAMP,
        HIRE_LUMBERER,
        SET_LUMBER_ZONE,
        DELIVER_FIRST_LOG,
        COMPLETE,
        SKIPPED,
        QUARANTINED
    }

    private JourneyMigration() {
    }
}
