package com.hearthstead.settlement.journey;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Schema-3, definition-3 server evidence ledger.
 *
 * <p>The ledger is append-only while active. Completion is always derived by
 * a bounded dependency fixpoint, so valid early evidence survives until its
 * prerequisites arrive. Corrupt or ambiguous state becomes sticky
 * QUARANTINED and never guesses progress.
 */
public final class JourneyState {
    public static final int SCHEMA_VERSION = 3;
    public static final int DEFINITION_VERSION = JourneyDefinition.DEFINITION_VERSION;
    public static final int MAX_EVIDENCE = 128;
    public static final int MAX_REVISION = 1_000_000;
    public static final int MAX_QUARANTINE_REASON_LENGTH = 128;

    private static final String DEFAULT_QUARANTINE_REASON = "invalid_journey_state";

    private final UUID settlementId;
    private JourneyPresentationMode mode;
    private final ArrayList<JourneyEvidence> evidence = new ArrayList<>();
    private final LinkedHashSet<ResourceLocation> completed = new LinkedHashSet<>();
    private int revision;
    private JourneyOutcome outcome = JourneyOutcome.NONE;
    private String quarantineReason = "";

    private JourneyState(UUID settlementId, JourneyPresentationMode mode) {
        this.settlementId = requireIdentity(settlementId);
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    public static JourneyState fresh(UUID settlementId) {
        return new JourneyState(settlementId, JourneyPresentationMode.ACTIVE);
    }

    public static JourneyState skipped(UUID settlementId) {
        return new JourneyState(settlementId, JourneyPresentationMode.SKIPPED);
    }

    public static JourneyState quarantined(UUID settlementId, String reason) {
        JourneyState state = new JourneyState(settlementId,
            JourneyPresentationMode.QUARANTINED);
        state.quarantineReason = sanitizeReason(reason);
        return state;
    }

    public UUID settlementId() {
        return settlementId;
    }

    public JourneyPresentationMode mode() {
        return mode;
    }

    public int revision() {
        return revision;
    }

    public JourneyOutcome outcome() {
        return outcome;
    }

    public String quarantineReason() {
        return quarantineReason;
    }

    public List<JourneyEvidence> evidence() {
        return List.copyOf(evidence);
    }

    public Set<ResourceLocation> completedSteps() {
        return Set.copyOf(completed);
    }

    public int completedCount() {
        return completed.size();
    }

    public boolean isCompleted(ResourceLocation stepId) {
        return stepId != null && completed.contains(stepId);
    }

    /**
     * True when the target and therefore all of its dependency ancestors are
     * complete. Completion closure cannot add a target before its ancestors.
     */
    public boolean completedThrough(ResourceLocation stepId) {
        return JourneyDefinition.CURRENT.step(stepId).isPresent()
            && completed.contains(stepId);
    }

    public Optional<JourneyStep> currentStep() {
        return currentStep(JourneyDefinition.CURRENT);
    }

    private Optional<JourneyStep> currentStep(JourneyDefinition definition) {
        if (mode != JourneyPresentationMode.ACTIVE) {
            return Optional.empty();
        }
        return definition.orderedSteps().stream()
            .filter(step -> !completed.contains(step.id())
                && completed.containsAll(step.prerequisites()))
            .findFirst();
    }

    public Optional<ResourceLocation> currentChapter() {
        return currentChapter(JourneyDefinition.CURRENT);
    }

    private Optional<ResourceLocation> currentChapter(
            JourneyDefinition definition) {
        if (mode == JourneyPresentationMode.SKIPPED
            || mode == JourneyPresentationMode.QUARANTINED) {
            return Optional.empty();
        }
        return currentStep(definition).map(JourneyStep::chapterId)
            .or(() -> Optional.of(JourneyIds.CHAPTER_FIRST_RAID));
    }

    /** Deliberate player presentation choice; clears evidence and grants nothing. */
    public boolean skipPresentation(int expectedRevision) {
        if (mode != JourneyPresentationMode.ACTIVE || revision != expectedRevision) {
            return false;
        }
        evidence.clear();
        completed.clear();
        revision = 0;
        outcome = JourneyOutcome.NONE;
        quarantineReason = "";
        mode = JourneyPresentationMode.SKIPPED;
        return true;
    }

    /** Package-scoped: only server-domain adapters in this package may author evidence. */
    JourneyApplyResult record(JourneyEvidence authored, JourneyDefinition definition) {
        Objects.requireNonNull(authored, "authored");
        Objects.requireNonNull(definition, "definition");
        if (mode == JourneyPresentationMode.QUARANTINED) {
            return JourneyApplyResult.QUARANTINED;
        }
        if (mode == JourneyPresentationMode.SKIPPED) {
            return JourneyApplyResult.IGNORED_PRESENTATION;
        }

        Optional<JourneyStep> decodedStep = definition.step(authored.stepId());
        if (!settlementId.equals(authored.settlementId())
            || decodedStep.isEmpty()
            || !decodedStep.get().accepts(authored.event())
            || authored.source() == JourneySource.MIGRATION
                && !decodedStep.get().migrationAllowed()) {
            quarantineInPlace("invalid_evidence_contract");
            return JourneyApplyResult.QUARANTINED;
        }

        for (JourneyEvidence existing : evidence) {
            if (!existing.key().equals(authored.key())) {
                continue;
            }
            if (existing.equals(authored)) {
                return JourneyApplyResult.DUPLICATE;
            }
            quarantineInPlace("transaction_identity_collision");
            return JourneyApplyResult.QUARANTINED;
        }
        if (mode == JourneyPresentationMode.COMPLETE
            || completed.contains(authored.stepId())) {
            return JourneyApplyResult.ALREADY_COMPLETED;
        }
        if (evidence.size() >= MAX_EVIDENCE || revision >= MAX_REVISION) {
            quarantineInPlace("evidence_capacity_exceeded");
            return JourneyApplyResult.QUARANTINED;
        }
        if (authored.event().terminalOutcomeRequired()
            && outcome != JourneyOutcome.NONE && outcome != authored.outcome()) {
            quarantineInPlace("conflicting_first_raid_outcome");
            return JourneyApplyResult.QUARANTINED;
        }

        evidence.add(authored);
        revision++;
        if (authored.event().terminalOutcomeRequired()) {
            outcome = authored.outcome();
        }
        recomputeClosure(definition);
        if (completed.size() == definition.orderedSteps().size()) {
            mode = JourneyPresentationMode.COMPLETE;
        }
        return authored.progressEligible(decodedStep.get().migrationAllowed())
            ? JourneyApplyResult.APPLIED
            : JourneyApplyResult.RECORDED_NON_PROGRESS;
    }

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("SchemaVersion", SCHEMA_VERSION);
        tag.putInt("DefinitionVersion", DEFINITION_VERSION);
        tag.putUUID("Settlement", settlementId);
        tag.putString("PresentationMode", mode.id());
        tag.putInt("Revision", revision);
        tag.putString("Outcome", outcome.id());
        tag.putString("CurrentChapter",
            currentChapter().map(ResourceLocation::toString).orElse(""));
        ListTag completedTag = new ListTag();
        for (JourneyStep step : JourneyDefinition.CURRENT.orderedSteps()) {
            if (completed.contains(step.id())) {
                completedTag.add(StringTag.valueOf(step.id().toString()));
            }
        }
        tag.put("Completed", completedTag);
        ListTag evidenceTag = new ListTag();
        for (JourneyEvidence item : evidence) {
            evidenceTag.add(item.writeNbt());
        }
        tag.put("Evidence", evidenceTag);
        tag.putString("QuarantineReason", quarantineReason);
        return tag;
    }

    /** Strict current decoder with an explicit definition-2 migration seam. */
    public static JourneyState readNbt(CompoundTag tag, UUID expectedSettlementId) {
        UUID expected = requireIdentity(expectedSettlementId);
        if (tag != null && tag.contains("DefinitionVersion", Tag.TAG_INT)
            && tag.getInt("DefinitionVersion") == 2) {
            return JourneyMigration.migrateDefinitionV2(tag, expected).state();
        }
        return readExactNbt(tag, expected, JourneyDefinition.CURRENT,
            DEFINITION_VERSION);
    }

    /** Package-private exact decoder used by the bounded v2->v3 migrator. */
    static JourneyState readExactNbt(CompoundTag tag, UUID expectedSettlementId,
                                     JourneyDefinition definition,
                                     int expectedDefinitionVersion) {
        UUID expected = requireIdentity(expectedSettlementId);
        if (tag == null
            || !tag.contains("SchemaVersion", Tag.TAG_INT)
            || tag.getInt("SchemaVersion") != SCHEMA_VERSION
            || !tag.contains("DefinitionVersion", Tag.TAG_INT)
            || tag.getInt("DefinitionVersion") != expectedDefinitionVersion
            || !tag.hasUUID("Settlement")
            || !expected.equals(tag.getUUID("Settlement"))
            || !tag.contains("PresentationMode", Tag.TAG_STRING)
            || !tag.contains("Revision", Tag.TAG_INT)
            || !tag.contains("Outcome", Tag.TAG_STRING)
            || !tag.contains("CurrentChapter", Tag.TAG_STRING)
            || !tag.contains("QuarantineReason", Tag.TAG_STRING)) {
            return quarantined(expected, "missing_or_future_schema");
        }
        Optional<JourneyPresentationMode> decodedMode =
            JourneyPresentationMode.tryFromId(tag.getString("PresentationMode"));
        Optional<JourneyOutcome> decodedOutcome =
            JourneyOutcome.tryFromId(tag.getString("Outcome"));
        int decodedRevision = tag.getInt("Revision");
        if (decodedMode.isEmpty() || decodedOutcome.isEmpty()
            || decodedRevision < 0 || decodedRevision > MAX_REVISION) {
            return quarantined(expected, "invalid_header");
        }
        if (decodedMode.get() == JourneyPresentationMode.QUARANTINED) {
            return quarantined(expected, tag.getString("QuarantineReason"));
        }

        List<String> savedCompleted = strictStringList(tag, "Completed",
            definition.orderedSteps().size());
        List<CompoundTag> savedEvidence = strictCompoundList(tag, "Evidence", MAX_EVIDENCE);
        if (savedCompleted == null || savedEvidence == null) {
            return quarantined(expected, "invalid_ledger_shape");
        }
        if (decodedMode.get() == JourneyPresentationMode.SKIPPED) {
            if (decodedRevision != 0 || !savedCompleted.isEmpty()
                || !savedEvidence.isEmpty() || decodedOutcome.get() != JourneyOutcome.NONE
                || !tag.getString("CurrentChapter").isEmpty()) {
                return quarantined(expected, "invalid_skipped_state");
            }
            return skipped(expected);
        }

        JourneyState restored = fresh(expected);
        Set<JourneyEvidence.EvidenceKey> keys = new HashSet<>();
        for (CompoundTag raw : savedEvidence) {
            Optional<JourneyEvidence> decoded = JourneyEvidence.tryReadNbt(raw);
            if (decoded.isEmpty() || !expected.equals(decoded.get().settlementId())
                || definition.step(decoded.get().stepId()).isEmpty()
                || !definition.step(decoded.get().stepId()).orElseThrow()
                    .accepts(decoded.get().event())
                || decoded.get().source() == JourneySource.MIGRATION
                    && !definition.step(decoded.get().stepId()).orElseThrow()
                        .migrationAllowed()
                || !keys.add(decoded.get().key())) {
                return quarantined(expected, "invalid_or_duplicate_evidence");
            }
            restored.evidence.add(decoded.get());
        }
        restored.revision = restored.evidence.size();
        restored.recomputeClosure(definition);
        JourneyOutcome derivedOutcome = deriveOutcome(restored.evidence);
        if (derivedOutcome == null) {
            return quarantined(expected, "conflicting_first_raid_outcome");
        }
        restored.outcome = derivedOutcome;
        if (restored.completed.size() == definition.orderedSteps().size()) {
            restored.mode = JourneyPresentationMode.COMPLETE;
        }

        List<String> canonicalCompleted = definition.orderedSteps().stream()
            .map(JourneyStep::id).filter(restored.completed::contains)
            .map(ResourceLocation::toString).toList();
        String canonicalChapter = restored.currentChapter(definition)
            .map(ResourceLocation::toString).orElse("");
        if (decodedRevision != restored.revision
            || decodedOutcome.get() != restored.outcome
            || decodedMode.get() != restored.mode
            || !savedCompleted.equals(canonicalCompleted)
            || !tag.getString("CurrentChapter").equals(canonicalChapter)) {
            return quarantined(expected, "derived_state_mismatch");
        }
        return restored;
    }

    private void recomputeClosure(JourneyDefinition definition) {
        completed.clear();
        // General bounded fixpoint: safe if definition v3 ever adds a small
        // non-linear dependency while definition v2 remains a frozen chain.
        for (int pass = 0; pass < definition.orderedSteps().size(); pass++) {
            boolean changed = false;
            for (JourneyStep step : definition.orderedSteps()) {
                if (completed.contains(step.id())
                    || !completed.containsAll(step.prerequisites())
                    || !hasSatisfiedEvidence(step)) {
                    continue;
                }
                completed.add(step.id());
                changed = true;
            }
            if (!changed) {
                return;
            }
        }
    }

    private boolean hasSatisfiedEvidence(JourneyStep step) {
        if (!step.sameTransactionRequired()) {
            return step.requiredEvents().stream().allMatch(required -> evidence.stream()
                .anyMatch(item -> item.stepId().equals(step.id())
                    && item.event() == required
                    && item.progressEligible(step.migrationAllowed())));
        }
        Map<UUID, Set<JourneyEvent>> byTransaction = new HashMap<>();
        for (JourneyEvidence item : evidence) {
            if (item.stepId().equals(step.id())
                && item.progressEligible(step.migrationAllowed())
                && step.accepts(item.event())) {
                byTransaction.computeIfAbsent(item.transactionId(), ignored -> new HashSet<>())
                    .add(item.event());
            }
        }
        return byTransaction.values().stream()
            .anyMatch(events -> events.containsAll(step.requiredEvents()));
    }

    private void quarantineInPlace(String reason) {
        mode = JourneyPresentationMode.QUARANTINED;
        evidence.clear();
        completed.clear();
        revision = 0;
        outcome = JourneyOutcome.NONE;
        quarantineReason = sanitizeReason(reason);
    }

    private static JourneyOutcome deriveOutcome(List<JourneyEvidence> evidence) {
        JourneyOutcome resolved = JourneyOutcome.NONE;
        for (JourneyEvidence item : evidence) {
            if (!item.event().terminalOutcomeRequired()) {
                continue;
            }
            if (resolved != JourneyOutcome.NONE && resolved != item.outcome()) {
                return null;
            }
            resolved = item.outcome();
        }
        return resolved;
    }

    private static List<String> strictStringList(CompoundTag tag, String key, int max) {
        Tag raw = tag.get(key);
        if (!(raw instanceof ListTag list) || list.size() > max
            || !list.isEmpty() && list.getElementType() != Tag.TAG_STRING) {
            return null;
        }
        ArrayList<String> values = new ArrayList<>(list.size());
        Set<String> unique = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            String value = list.getString(i);
            if (!unique.add(value)) {
                return null;
            }
            values.add(value);
        }
        return values;
    }

    private static List<CompoundTag> strictCompoundList(CompoundTag tag, String key, int max) {
        Tag raw = tag.get(key);
        if (!(raw instanceof ListTag list) || list.size() > max
            || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) {
            return null;
        }
        ArrayList<CompoundTag> values = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            values.add(list.getCompound(i));
        }
        return values;
    }

    private static UUID requireIdentity(UUID value) {
        if (value == null || value.getMostSignificantBits() == 0L
            && value.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException("Settlement identity is required");
        }
        return value;
    }

    private static String sanitizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return DEFAULT_QUARANTINE_REASON;
        }
        StringBuilder safe = new StringBuilder(
            Math.min(reason.length(), MAX_QUARANTINE_REASON_LENGTH));
        for (int i = 0; i < reason.length()
            && safe.length() < MAX_QUARANTINE_REASON_LENGTH; i++) {
            char value = reason.charAt(i);
            safe.append(Character.isISOControl(value) ? '_' : value);
        }
        return safe.toString();
    }
}
