package com.hearthstead.settlement.journey;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable proof emitted after one ordinary server-domain transaction.
 *
 * <p>Evidence contains identities, never display text, filesystem paths, chat,
 * player names or client-authored claims. Strict decoding makes malformed save
 * data non-progressing rather than guessing.
 */
public record JourneyEvidence(
    ResourceLocation stepId,
    JourneyEvent event,
    UUID transactionId,
    long gameTime,
    UUID settlementId,
    Optional<UUID> actorPlayerId,
    Optional<UUID> subjectEntityId,
    Optional<UUID> buildingId,
    Optional<UUID> requestId,
    Optional<String> stackFingerprint,
    JourneySource source,
    JourneyOutcome outcome
) {
    public static final int MAX_FINGERPRINT_LENGTH = 160;

    public JourneyEvidence {
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(settlementId, "settlementId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(outcome, "outcome");
        actorPlayerId = normalize(actorPlayerId);
        subjectEntityId = normalize(subjectEntityId);
        buildingId = normalize(buildingId);
        requestId = normalize(requestId);
        stackFingerprint = normalizeFingerprint(stackFingerprint);
        if (!"hearthstead".equals(stepId.getNamespace())
            || !stepId.getPath().startsWith("journey/fj_")
            || isNil(transactionId) || isNil(settlementId) || gameTime < 0L) {
            throw new IllegalArgumentException("Malformed Journey evidence identity");
        }
        if (event.terminalOutcomeRequired() != outcome.terminal()) {
            throw new IllegalArgumentException("Journey outcome does not match event semantics");
        }
        if (source == JourneySource.SURVIVAL) {
            require(event.actorRequired(), actorPlayerId, "actor");
            require(event.subjectRequired(), subjectEntityId, "subject");
            require(event.buildingRequired(), buildingId, "building");
            require(event.requestRequired(), requestId, "request");
        }
    }

    public EvidenceKey key() {
        return new EvidenceKey(event.id(), transactionId);
    }

    public boolean progressEligible(boolean migrationAllowed) {
        return source == JourneySource.SURVIVAL
            || source == JourneySource.MIGRATION && migrationAllowed;
    }

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Step", stepId.toString());
        tag.putString("Event", event.id().toString());
        tag.putUUID("Transaction", transactionId);
        tag.putLong("GameTime", gameTime);
        tag.putUUID("Settlement", settlementId);
        actorPlayerId.ifPresent(value -> tag.putUUID("Actor", value));
        subjectEntityId.ifPresent(value -> tag.putUUID("Subject", value));
        buildingId.ifPresent(value -> tag.putUUID("Building", value));
        requestId.ifPresent(value -> tag.putUUID("Request", value));
        stackFingerprint.ifPresent(value -> tag.putString("Stack", value));
        tag.putString("Source", source.id());
        tag.putString("Outcome", outcome.id());
        return tag;
    }

    public static Optional<JourneyEvidence> tryReadNbt(CompoundTag tag) {
        if (tag == null
            || !tag.contains("Step", Tag.TAG_STRING)
            || !tag.contains("Event", Tag.TAG_STRING)
            || !tag.hasUUID("Transaction")
            || !tag.contains("GameTime", Tag.TAG_LONG)
            || !tag.hasUUID("Settlement")
            || !tag.contains("Source", Tag.TAG_STRING)
            || !tag.contains("Outcome", Tag.TAG_STRING)
            || malformedOptionalUuid(tag, "Actor")
            || malformedOptionalUuid(tag, "Subject")
            || malformedOptionalUuid(tag, "Building")
            || malformedOptionalUuid(tag, "Request")
            || tag.contains("Stack") && !tag.contains("Stack", Tag.TAG_STRING)) {
            return Optional.empty();
        }
        ResourceLocation stepId = ResourceLocation.tryParse(tag.getString("Step"));
        ResourceLocation eventId = ResourceLocation.tryParse(tag.getString("Event"));
        Optional<JourneyEvent> event = JourneyEvent.tryFromId(eventId);
        Optional<JourneySource> source = JourneySource.tryFromId(tag.getString("Source"));
        Optional<JourneyOutcome> outcome = JourneyOutcome.tryFromId(tag.getString("Outcome"));
        if (stepId == null || event.isEmpty() || source.isEmpty() || outcome.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new JourneyEvidence(
                stepId,
                event.get(),
                tag.getUUID("Transaction"),
                tag.getLong("GameTime"),
                tag.getUUID("Settlement"),
                optionalUuid(tag, "Actor"),
                optionalUuid(tag, "Subject"),
                optionalUuid(tag, "Building"),
                optionalUuid(tag, "Request"),
                tag.contains("Stack", Tag.TAG_STRING)
                    ? Optional.of(tag.getString("Stack")) : Optional.empty(),
                source.get(),
                outcome.get()));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    private static Optional<UUID> normalize(Optional<UUID> value) {
        Optional<UUID> normalized = value == null ? Optional.empty() : value;
        if (normalized.isPresent() && isNil(normalized.get())) {
            throw new IllegalArgumentException("Nil UUID is not a stable identity");
        }
        return normalized;
    }

    private static Optional<String> normalizeFingerprint(Optional<String> value) {
        Optional<String> normalized = value == null ? Optional.empty() : value;
        if (normalized.isEmpty()) {
            return normalized;
        }
        String fingerprint = normalized.get();
        if (fingerprint.isBlank() || fingerprint.length() > MAX_FINGERPRINT_LENGTH) {
            throw new IllegalArgumentException("Unbounded or empty stack fingerprint");
        }
        for (int i = 0; i < fingerprint.length(); i++) {
            if (Character.isISOControl(fingerprint.charAt(i))) {
                throw new IllegalArgumentException("Control character in stack fingerprint");
            }
        }
        return Optional.of(fingerprint);
    }

    private static boolean malformedOptionalUuid(CompoundTag tag, String key) {
        return tag.contains(key) && !tag.hasUUID(key);
    }

    private static Optional<UUID> optionalUuid(CompoundTag tag, String key) {
        return tag.hasUUID(key) ? Optional.of(tag.getUUID(key)) : Optional.empty();
    }

    private static void require(boolean required, Optional<?> value, String label) {
        if (required && value.isEmpty()) {
            throw new IllegalArgumentException("Missing required " + label + " identity");
        }
    }

    private static boolean isNil(UUID value) {
        return value.getMostSignificantBits() == 0L
            && value.getLeastSignificantBits() == 0L;
    }

    /** Globally idempotent event transaction key. */
    public record EvidenceKey(ResourceLocation eventId, UUID transactionId) {
        public EvidenceKey {
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(transactionId, "transactionId");
        }
    }
}
