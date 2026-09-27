package com.hearthstead.settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.UUID;

/**
 * The single persisted authority for one settlement's natural recruitment.
 *
 * <p>The old implementation inferred a guest's stage from two loose fields
 * ({@code TravelerId} and {@code TravelerSince}).  That made a loaded entity,
 * a timer and a hearth inventory three competing authorities.  This record
 * makes every stage explicit, gives player actions a monotonic optimistic-lock
 * revision, and binds a candidate to the exact physical tavern that qualified
 * them.  Null identities are legal only in stages whose invariants say so.
 */
public record RecruitmentTransaction(
    int schemaVersion,
    UUID settlementId,
    Status status,
    boolean survivalAuthored,
    RecruitmentPolicy.TimingProfile timingProfile,
    int revision,
    int cycle,
    int lockedTarget,
    int progress,
    int qualifiedSeconds,
    long qualificationStartedTick,
    @Nullable UUID transactionId,
    @Nullable UUID travelerId,
    String travelerName,
    long spawnedTick,
    long arrivedTick,
    @Nullable UUID tavernBuildingId,
    @Nullable BlockPos tavernPlaquePos,
    @Nullable BlockPos tavernAnchor,
    @Nullable ResourceLocation dimension,
    @Nullable AdmissionReceipt admissionReceipt,
    int journeyEvidenceMask,
    TerminalReason terminalReason,
    @Nullable RecruitmentQuote quote
) {
    public static final int CURRENT_SCHEMA_VERSION = 3;
    public static final long NO_TICK = -1L;
    public static final int EVIDENCE_QUALIFICATION = 1;
    public static final int EVIDENCE_ARRIVAL = 2;
    public static final int EVIDENCE_ADMISSION = 4;

    /** Stable ids are save and menu protocol, never enum ordinals. */
    public enum Status {
        ATTRACTING(0),
        QUALIFYING(1),
        READY_TO_SPAWN(2),
        TRAVELING(3),
        WAITING_ADMISSION(4),
        ADMITTED(5),
        LEFT(6),
        QUARANTINED(7),
        DEPARTING(8),
        UNKNOWN(-1);

        private final int wireId;

        Status(int wireId) {
            this.wireId = wireId;
        }

        public int wireId() {
            return wireId;
        }

        public static Status fromWireId(int wireId) {
            return switch (wireId) {
                case 0 -> ATTRACTING;
                case 1 -> QUALIFYING;
                case 2 -> READY_TO_SPAWN;
                case 3 -> TRAVELING;
                case 4 -> WAITING_ADMISSION;
                case 5 -> ADMITTED;
                case 6 -> LEFT;
                case 7 -> QUARANTINED;
                case 8 -> DEPARTING;
                default -> UNKNOWN;
            };
        }
    }

    public enum TerminalReason {
        NONE(0),
        ADMITTED(1),
        PATIENCE_EXPIRED(2),
        ENTITY_GONE(3),
        TAVERN_INVALIDATED(4),
        LEGACY_UNVERIFIABLE(5),
        MALFORMED_SAVE(6),
        DUPLICATE_TRAVELER(7),
        CROSS_SETTLEMENT(8),
        PLAYER_REJECTED(9),
        VISIT_COMPLETE(10),
        UNKNOWN(-1);

        private final int wireId;

        TerminalReason(int wireId) {
            this.wireId = wireId;
        }

        public int wireId() {
            return wireId;
        }

        public static TerminalReason fromWireId(int wireId) {
            return switch (wireId) {
                case 0 -> NONE;
                case 1 -> ADMITTED;
                case 2 -> PATIENCE_EXPIRED;
                case 3 -> ENTITY_GONE;
                case 4 -> TAVERN_INVALIDATED;
                case 5 -> LEGACY_UNVERIFIABLE;
                case 6 -> MALFORMED_SAVE;
                case 7 -> DUPLICATE_TRAVELER;
                case 8 -> CROSS_SETTLEMENT;
                case 9 -> PLAYER_REJECTED;
                case 10 -> VISIT_COMPLETE;
                default -> UNKNOWN;
            };
        }
    }

    /** Persisted proof of the one physical payment accepted by admission. */
    public record AdmissionReceipt(UUID playerId, String paymentFingerprint,
                                   int removedItemCount,
                                   long inventoryBeforeHash,
                                   long inventoryAfterHash) {
        public AdmissionReceipt {
            paymentFingerprint = paymentFingerprint == null
                ? "" : paymentFingerprint;
        }

        public boolean valid() {
            return playerId != null && !paymentFingerprint.isBlank()
                && paymentFingerprint.length() <= 512 && removedItemCount > 0
                && inventoryBeforeHash != inventoryAfterHash;
        }

        CompoundTag writeNbt() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("PlayerId", playerId);
            tag.putString("PaymentFingerprint", paymentFingerprint);
            tag.putInt("RemovedItemCount", removedItemCount);
            tag.putLong("InventoryBeforeHash", inventoryBeforeHash);
            tag.putLong("InventoryAfterHash", inventoryAfterHash);
            return tag;
        }

        static AdmissionReceipt readStrict(CompoundTag tag) {
            require(tag, "RemovedItemCount", Tag.TAG_INT);
            require(tag, "InventoryBeforeHash", Tag.TAG_LONG);
            require(tag, "InventoryAfterHash", Tag.TAG_LONG);
            if (!tag.hasUUID("PlayerId")
                || !tag.contains("PaymentFingerprint", Tag.TAG_STRING)) {
                throw new IllegalArgumentException("invalid admission receipt");
            }
            AdmissionReceipt receipt = new AdmissionReceipt(tag.getUUID("PlayerId"),
                tag.getString("PaymentFingerprint"),
                tag.getInt("RemovedItemCount"),
                tag.getLong("InventoryBeforeHash"),
                tag.getLong("InventoryAfterHash"));
            if (!receipt.valid()) {
                throw new IllegalArgumentException("invalid admission receipt");
            }
            return receipt;
        }
    }

    public RecruitmentTransaction {
        settlementId = Objects.requireNonNull(settlementId, "settlementId");
        status = status == null ? Status.UNKNOWN : status;
        terminalReason = terminalReason == null ? TerminalReason.UNKNOWN : terminalReason;
        travelerName = travelerName == null ? "" : travelerName;
        tavernPlaquePos = tavernPlaquePos == null ? null : tavernPlaquePos.immutable();
        tavernAnchor = tavernAnchor == null ? null : tavernAnchor.immutable();
    }

    public static RecruitmentTransaction fresh(UUID settlementId) {
        return fresh(settlementId, 0, 0);
    }

    public static RecruitmentTransaction fresh(UUID settlementId, int cycle,
                                               int revision) {
        int safeCycle = Math.max(0, cycle);
        return new RecruitmentTransaction(CURRENT_SCHEMA_VERSION, settlementId,
            Status.ATTRACTING, true, RecruitmentPolicy.TimingProfile.ORDINARY_48H,
            Math.max(0, revision), safeCycle,
            RecruitmentPolicy.targetFor(settlementId, safeCycle), 0, 0,
            NO_TICK, null, null, "", NO_TICK, NO_TICK, null, null, null, null,
            null, 0, TerminalReason.NONE, null);
    }

    public static RecruitmentTransaction quarantined(UUID settlementId,
                                                      int cycle,
                                                      int revision,
                                                      @Nullable UUID travelerId,
                                                      TerminalReason reason) {
        int safeCycle = Math.max(0, cycle);
        return new RecruitmentTransaction(CURRENT_SCHEMA_VERSION, settlementId,
            Status.QUARANTINED, false, RecruitmentPolicy.TimingProfile.ORDINARY_48H,
            Math.max(0, revision), safeCycle,
            RecruitmentPolicy.targetFor(settlementId, safeCycle), 0, 0,
            NO_TICK, null, travelerId, "", NO_TICK, NO_TICK, null, null, null, null,
            null, 0, reason == null || reason == TerminalReason.NONE
                ? TerminalReason.MALFORMED_SAVE : reason, null);
    }

    public boolean hasLockedTavern() {
        return tavernBuildingId != null && tavernPlaquePos != null
            && tavernAnchor != null && dimension != null;
    }

    public boolean hasCandidate() {
        return travelerId != null && switch (status) {
            case TRAVELING, WAITING_ADMISSION, DEPARTING, ADMITTED, LEFT, QUARANTINED -> true;
            default -> false;
        };
    }

    public RecruitmentTransaction beginQualification(UUID persistedTransactionId,
                                                      long now, UUID buildingId,
                                                      BlockPos plaquePos,
                                                      BlockPos anchor,
                                                      ResourceLocation dimension) {
        return beginQualification(persistedTransactionId, now, buildingId,
            plaquePos, anchor, dimension, false);
    }

    /**
     * Starts one natural qualification with timing chosen from the live
     * Journey phase. The persisted target remains restart-verifiable even
     * when a rejected traveler has advanced the raw cycle several times.
     */
    public RecruitmentTransaction beginQualification(UUID persistedTransactionId,
                                                      long now, UUID buildingId,
                                                      BlockPos plaquePos,
                                                      BlockPos anchor,
                                                      ResourceLocation dimension,
                                                      boolean callToArms) {
        if (status != Status.ATTRACTING || buildingId == null || plaquePos == null
            || anchor == null || dimension == null || now < 0L
            || persistedTransactionId == null || revisionSaturated()) {
            return this;
        }
        RecruitmentPolicy.TimingProfile selectedProfile = callToArms
            ? RecruitmentPolicy.TimingProfile.CALL_TO_ARMS
            : RecruitmentPolicy.TimingProfile.ORDINARY_48H;
        int selectedTarget = callToArms
            ? RecruitmentPolicy.callToArmsTargetFor(settlementId, cycle)
            : RecruitmentPolicy.targetFor(settlementId, cycle);
        return new RecruitmentTransaction(CURRENT_SCHEMA_VERSION, settlementId,
            Status.QUALIFYING, survivalAuthored, selectedProfile, nextRevision(), cycle,
            selectedTarget, 1, 1,
            now, persistedTransactionId, null, "", NO_TICK, NO_TICK, buildingId, plaquePos, anchor,
            dimension, null, 0, TerminalReason.NONE, null);
    }

    public RecruitmentTransaction advanceQualification() {
        if (status != Status.QUALIFYING || revisionSaturated()) {
            return this;
        }
        int requiredMinimum = requiredMinimum();
        int nextProgress = Math.min(lockedTarget, progress + 1);
        int nextQualified = Math.min(requiredMinimum,
            qualifiedSeconds + 1);
        Status nextStatus = nextProgress >= lockedTarget
            && nextQualified >= requiredMinimum
                ? Status.READY_TO_SPAWN : Status.QUALIFYING;
        return copy(nextStatus, nextRevision(), nextProgress, nextQualified,
            travelerId, spawnedTick, arrivedTick, null, TerminalReason.NONE);
    }

    /**
     * Adopts an already-running ordinary qualification into the bounded
     * post-Arm-the-Watch tutorial window without replacing its transaction,
     * Tavern lock, accumulated time or replay identity.
     */
    public RecruitmentTransaction adoptCallToArmsWindow() {
        if (status != Status.QUALIFYING || !survivalAuthored
            || timingProfile != RecruitmentPolicy.TimingProfile.ORDINARY_48H
            || revisionSaturated()) {
            return this;
        }
        int target = RecruitmentPolicy.callToArmsTargetFor(settlementId, cycle);
        int minimum = RecruitmentPolicy.minimumFor(
            RecruitmentPolicy.TimingProfile.CALL_TO_ARMS, settlementId, cycle, target);
        if (minimum <= 0) {
            return this;
        }
        int nextProgress = Math.min(target, progress);
        int nextQualified = Math.min(minimum, qualifiedSeconds);
        Status nextStatus = nextProgress >= target
            && nextQualified >= minimum
                ? Status.READY_TO_SPAWN : Status.QUALIFYING;
        return new RecruitmentTransaction(CURRENT_SCHEMA_VERSION, settlementId,
            nextStatus, true, RecruitmentPolicy.TimingProfile.CALL_TO_ARMS,
            nextRevision(), cycle, target, nextProgress,
            nextQualified, qualificationStartedTick, transactionId, null, "",
            NO_TICK, NO_TICK, tavernBuildingId, tavernPlaquePos, tavernAnchor,
            dimension, null, journeyEvidenceMask, TerminalReason.NONE, null);
    }

    public RecruitmentTransaction decayQualification() {
        if (status != Status.QUALIFYING || revisionSaturated()) {
            return this;
        }
        return copy(Status.QUALIFYING, nextRevision(),
            Math.max(1, progress - 1), Math.max(1, qualifiedSeconds - 1),
            null, NO_TICK, NO_TICK, null, TerminalReason.NONE);
    }

    public RecruitmentTransaction readyForSpawnForTest() {
        if (status != Status.QUALIFYING || revisionSaturated()) {
            return this;
        }
        return copy(Status.READY_TO_SPAWN, nextRevision(), lockedTarget,
            requiredMinimum(), null, NO_TICK, NO_TICK,
            null, TerminalReason.NONE);
    }

    /** Admin-only prime: physical flow remains real, but Journey ignores it. */
    public RecruitmentTransaction adminPrime(UUID persistedTransactionId,
                                             long now, UUID buildingId,
                                             BlockPos plaquePos, BlockPos anchor,
                                             ResourceLocation dimension) {
        if (status != Status.ATTRACTING || persistedTransactionId == null
            || buildingId == null || plaquePos == null || anchor == null
            || dimension == null || now < 0L || revisionSaturated()) {
            return this;
        }
        return new RecruitmentTransaction(CURRENT_SCHEMA_VERSION, settlementId,
            Status.READY_TO_SPAWN, false, timingProfile, nextRevision(), cycle, lockedTarget,
            lockedTarget, requiredMinimum(), now,
            persistedTransactionId, null, "", NO_TICK, NO_TICK, buildingId,
            plaquePos, anchor, dimension, null, 0, TerminalReason.NONE, null);
    }

    public RecruitmentTransaction travelerSpawned(UUID id, String name, long now) {
        return travelerSpawned(id, name, now,
            transactionId == null || id == null ? null : RecruitmentQuote.legacyPending(transactionId, id));
    }

    public RecruitmentTransaction travelerSpawned(UUID id, String name, long now,
                                                 RecruitmentQuote committedQuote) {
        if (status != Status.READY_TO_SPAWN || id == null || name == null
            || name.isBlank() || name.length() > 64 || now < 0L
            || revisionSaturated() || committedQuote == null
            || !committedQuote.matches(transactionId, id)) {
            return this;
        }
        return new RecruitmentTransaction(CURRENT_SCHEMA_VERSION, settlementId,
            Status.TRAVELING, survivalAuthored, timingProfile, nextRevision(), cycle,
            lockedTarget, 0, 0, qualificationStartedTick, transactionId, id,
            name, now, NO_TICK, tavernBuildingId, tavernPlaquePos, tavernAnchor,
            dimension, null, journeyEvidenceMask, TerminalReason.NONE, committedQuote);
    }

    public RecruitmentTransaction arrived(long now) {
        if (status != Status.TRAVELING || travelerId == null || now < spawnedTick
            || revisionSaturated()) {
            return this;
        }
        return copy(Status.WAITING_ADMISSION, nextRevision(), 0, 0,
            travelerId, spawnedTick, now, null, TerminalReason.NONE);
    }

    public RecruitmentTransaction left(TerminalReason reason) {
        if ((status != Status.WAITING_ADMISSION && status != Status.TRAVELING
                && status != Status.DEPARTING)
            || revisionSaturated()) {
            return this;
        }
        return copy(Status.LEFT, nextRevision(), 0, 0, travelerId,
            spawnedTick, arrivedTick, null, reason);
    }

    /** Starts the physical return trip without altering the locked candidate or quote. */
    public RecruitmentTransaction departing() {
        if ((status != Status.WAITING_ADMISSION && status != Status.TRAVELING)
            || revisionSaturated()) {
            return this;
        }
        return copy(Status.DEPARTING, nextRevision(), 0, 0, travelerId,
            spawnedTick, arrivedTick, null, TerminalReason.NONE);
    }

    public RecruitmentTransaction admitted(AdmissionReceipt receipt) {
        if (status != Status.WAITING_ADMISSION || receipt == null
            || !receipt.valid() || revisionSaturated()) {
            return this;
        }
        return copy(Status.ADMITTED, nextRevision(), 0, 0, travelerId,
            spawnedTick, arrivedTick, receipt, TerminalReason.ADMITTED);
    }

    public RecruitmentTransaction nextCycle(UUID settlementId) {
        if ((status != Status.ADMITTED && status != Status.LEFT)
            || revisionSaturated() || cycle == Integer.MAX_VALUE) {
            return this;
        }
        return fresh(settlementId, cycle + 1, nextRevision());
    }

    /** Abandons an unspawned lock without advancing its cycle or target. */
    public RecruitmentTransaction restartAttraction(UUID settlementId) {
        if ((status != Status.QUALIFYING && status != Status.READY_TO_SPAWN)
            || revisionSaturated()) {
            return this;
        }
        return fresh(settlementId, cycle, nextRevision());
    }

    public RecruitmentTransaction quarantine(TerminalReason reason) {
        if (revisionSaturated()) {
            return this;
        }
        return new RecruitmentTransaction(CURRENT_SCHEMA_VERSION, settlementId,
            Status.QUARANTINED, survivalAuthored, timingProfile, nextRevision(), cycle, lockedTarget, 0, 0,
            qualificationStartedTick, transactionId, travelerId, travelerName,
            spawnedTick, arrivedTick,
            tavernBuildingId, tavernPlaquePos, tavernAnchor, dimension,
            admissionReceipt, journeyEvidenceMask,
            reason == null || reason == TerminalReason.NONE
                ? TerminalReason.MALFORMED_SAVE : reason, quote);
    }

    private int nextRevision() {
        if (revisionSaturated()) {
            throw new IllegalStateException("recruitment revision saturated");
        }
        return revision + 1;
    }

    private int requiredMinimum() {
        int minimum = RecruitmentPolicy.minimumFor(timingProfile, settlementId,
            cycle, lockedTarget);
        if (minimum <= 0) {
            throw new IllegalStateException("invalid persisted recruitment lock");
        }
        return minimum;
    }

    /** Saturated optimistic-lock tokens are inert until explicit repair. */
    public boolean revisionSaturated() {
        return revision == Integer.MAX_VALUE;
    }

    private RecruitmentTransaction copy(Status nextStatus, int nextRevision,
                                        int nextProgress, int nextQualified,
                                        @Nullable UUID nextTraveler,
                                        long nextSpawned, long nextArrived,
                                        @Nullable AdmissionReceipt nextReceipt,
                                        TerminalReason nextReason) {
        return new RecruitmentTransaction(CURRENT_SCHEMA_VERSION, settlementId, nextStatus,
            survivalAuthored, timingProfile, nextRevision, cycle, lockedTarget, nextProgress, nextQualified,
            qualificationStartedTick, transactionId, nextTraveler, travelerName,
            nextSpawned, nextArrived, tavernBuildingId, tavernPlaquePos, tavernAnchor, dimension,
            nextReceipt, journeyEvidenceMask, nextReason, quote);
    }

    public RecruitmentTransaction freezeLegacyQuote(java.util.List<Costs.Discount> discounts) {
        if (quote == null || !quote.legacyPending()) return this;
        return new RecruitmentTransaction(CURRENT_SCHEMA_VERSION, settlementId, status,
            survivalAuthored, timingProfile, revision, cycle, lockedTarget, progress, qualifiedSeconds,
            qualificationStartedTick, transactionId, travelerId, travelerName,
            spawnedTick, arrivedTick, tavernBuildingId, tavernPlaquePos, tavernAnchor,
            dimension, admissionReceipt, journeyEvidenceMask, terminalReason,
            quote.freezeLegacy(discounts));
    }

    public boolean evidenceAcknowledged(int bit) {
        return bit != 0 && (journeyEvidenceMask & bit) == bit;
    }

    /** Marks only derived Journey evidence; gameplay revision does not change. */
    public RecruitmentTransaction acknowledgeEvidence(int bit) {
        int allowed = EVIDENCE_QUALIFICATION | EVIDENCE_ARRIVAL | EVIDENCE_ADMISSION;
        int nextMask = journeyEvidenceMask | (bit & allowed);
        if (nextMask == journeyEvidenceMask) {
            return this;
        }
        return new RecruitmentTransaction(CURRENT_SCHEMA_VERSION, settlementId, status,
            survivalAuthored, timingProfile, revision, cycle, lockedTarget, progress, qualifiedSeconds,
            qualificationStartedTick, transactionId, travelerId, travelerName,
            spawnedTick, arrivedTick, tavernBuildingId, tavernPlaquePos, tavernAnchor,
            dimension, admissionReceipt, nextMask, terminalReason, quote);
    }

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("SchemaVersion", CURRENT_SCHEMA_VERSION);
        if (quote != null) tag.put("Quote", quote.writeNbt());
        tag.putUUID("SettlementId", settlementId);
        tag.putInt("StatusWireId", status.wireId());
        tag.putBoolean("SurvivalAuthored", survivalAuthored);
        tag.putInt("TimingProfileWireId", timingProfile.wireId());
        tag.putInt("Revision", revision);
        tag.putInt("Cycle", cycle);
        tag.putInt("LockedTarget", lockedTarget);
        tag.putInt("Progress", progress);
        tag.putInt("QualifiedSeconds", qualifiedSeconds);
        tag.putLong("QualificationStartedTick", qualificationStartedTick);
        tag.putLong("SpawnedTick", spawnedTick);
        tag.putLong("ArrivedTick", arrivedTick);
        tag.putInt("TerminalReasonWireId", terminalReason.wireId());
        tag.putInt("JourneyEvidenceMask", journeyEvidenceMask);
        if (transactionId != null) {
            tag.putUUID("TransactionId", transactionId);
        }
        if (travelerId != null) {
            tag.putUUID("TravelerId", travelerId);
        }
        tag.putString("TravelerName", travelerName);
        if (tavernBuildingId != null) {
            tag.putUUID("TavernBuildingId", tavernBuildingId);
        }
        if (tavernPlaquePos != null) {
            tag.put("TavernPlaque", NbtUtils.writeBlockPos(tavernPlaquePos));
        }
        if (tavernAnchor != null) {
            tag.put("TavernAnchor", NbtUtils.writeBlockPos(tavernAnchor));
        }
        if (dimension != null) {
            tag.putString("Dimension", dimension.toString());
        }
        if (admissionReceipt != null) {
            tag.put("AdmissionReceipt", admissionReceipt.writeNbt());
        }
        return tag;
    }

    /**
     * Reads only the current nested schema. Any malformed shape is returned as
     * a quarantined transaction by {@link #readOrQuarantine}; it never becomes
     * a recruitable guest.
     */
    public static RecruitmentTransaction readOrQuarantine(CompoundTag tag,
                                                          UUID settlementId) {
        try {
            return readStrict(tag, settlementId);
        } catch (RuntimeException ignored) {
            UUID traveler = tag != null && tag.hasUUID("TravelerId")
                ? tag.getUUID("TravelerId") : null;
            int cycle = tag != null && tag.contains("Cycle", Tag.TAG_INT)
                ? Math.max(0, tag.getInt("Cycle")) : 0;
            int revision = tag != null && tag.contains("Revision", Tag.TAG_INT)
                ? Math.max(0, tag.getInt("Revision")) : 0;
            return quarantined(settlementId, cycle, revision, traveler,
                TerminalReason.MALFORMED_SAVE);
        }
    }

    private static RecruitmentTransaction readStrict(CompoundTag tag,
                                                      UUID settlementId) {
        Objects.requireNonNull(tag, "tag");
        require(tag, "SchemaVersion", Tag.TAG_INT);
        if (!tag.hasUUID("SettlementId")) {
            throw new IllegalArgumentException("missing or wrong SettlementId");
        }
        require(tag, "StatusWireId", Tag.TAG_INT);
        require(tag, "SurvivalAuthored", Tag.TAG_BYTE);
        require(tag, "Revision", Tag.TAG_INT);
        require(tag, "Cycle", Tag.TAG_INT);
        require(tag, "LockedTarget", Tag.TAG_INT);
        require(tag, "Progress", Tag.TAG_INT);
        require(tag, "QualifiedSeconds", Tag.TAG_INT);
        require(tag, "QualificationStartedTick", Tag.TAG_LONG);
        require(tag, "SpawnedTick", Tag.TAG_LONG);
        require(tag, "ArrivedTick", Tag.TAG_LONG);
        require(tag, "TerminalReasonWireId", Tag.TAG_INT);
        require(tag, "JourneyEvidenceMask", Tag.TAG_INT);
        require(tag, "TravelerName", Tag.TAG_STRING);

        int savedSchema = tag.getInt("SchemaVersion");
        if (savedSchema != 1 && savedSchema != 2
            && savedSchema != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported recruitment schema");
        }
        Status status = Status.fromWireId(tag.getInt("StatusWireId"));
        boolean survivalAuthored = tag.getBoolean("SurvivalAuthored");
        TerminalReason reason = TerminalReason.fromWireId(
            tag.getInt("TerminalReasonWireId"));
        int evidenceMask = tag.getInt("JourneyEvidenceMask");
        int revision = tag.getInt("Revision");
        int cycle = tag.getInt("Cycle");
        int target = tag.getInt("LockedTarget");
        RecruitmentPolicy.TimingProfile timingProfile;
        if (savedSchema == CURRENT_SCHEMA_VERSION) {
            require(tag, "TimingProfileWireId", Tag.TAG_INT);
            timingProfile = RecruitmentPolicy.TimingProfile.fromWireId(
                tag.getInt("TimingProfileWireId"));
        } else {
            // v1/v2 targets came from disjoint ranges, so this is exact
            // decoding of the persisted old policy, never a new-range guess.
            timingProfile = RecruitmentPolicy.legacyTimingProfileFor(
                settlementId, cycle, target);
        }
        int progress = tag.getInt("Progress");
        int qualified = tag.getInt("QualifiedSeconds");
        long qualifiedAt = tag.getLong("QualificationStartedTick");
        long spawnedAt = tag.getLong("SpawnedTick");
        long arrivedAt = tag.getLong("ArrivedTick");
        UUID transactionId = optionalUuid(tag, "TransactionId");
        UUID traveler = optionalUuid(tag, "TravelerId");
        String travelerName = tag.getString("TravelerName");
        UUID building = optionalUuid(tag, "TavernBuildingId");
        BlockPos plaque = optionalPos(tag, "TavernPlaque");
        BlockPos anchor = optionalPos(tag, "TavernAnchor");
        ResourceLocation dimension = optionalDimension(tag);
        AdmissionReceipt receipt = tag.get("AdmissionReceipt") instanceof CompoundTag receiptTag
            ? AdmissionReceipt.readStrict(receiptTag) : null;

        RecruitmentQuote decodedQuote = null;
        if (tag.contains("Quote")) {
            if (!tag.contains("Quote", Tag.TAG_COMPOUND)) throw new IllegalArgumentException("malformed quote");
            decodedQuote = RecruitmentQuote.readStrict(tag.getCompound("Quote"));
        } else if (savedSchema == 1 && traveler != null && transactionId != null && spawnedAt >= 0) {
            // Old guests keep their rolled attributes; only legacy base price is frozen on first live use.
            decodedQuote = RecruitmentQuote.legacyPending(transactionId, traveler);
        }
        RecruitmentTransaction candidate = new RecruitmentTransaction(
            CURRENT_SCHEMA_VERSION, tag.getUUID("SettlementId"), status,
            survivalAuthored, timingProfile, revision, cycle, target, progress,
            qualified, qualifiedAt, transactionId, traveler, travelerName,
            spawnedAt, arrivedAt, building,
            plaque, anchor, dimension, receipt, evidenceMask, reason, decodedQuote);
        if (traveler != null && spawnedAt >= 0 && status != Status.QUARANTINED
                && (decodedQuote == null || !decodedQuote.matches(transactionId, traveler))) {
            throw new IllegalArgumentException("missing/mismatched recruitment quote");
        }
        if (decodedQuote != null && !decodedQuote.matches(transactionId, traveler)) {
            throw new IllegalArgumentException("cross-trip recruitment quote");
        }
        candidate.validateStrict(settlementId);
        return candidate;
    }

    private void validateStrict(UUID settlementId) {
        if (!this.settlementId.equals(settlementId)
            || status == Status.UNKNOWN || terminalReason == TerminalReason.UNKNOWN
            || timingProfile == null || timingProfile == RecruitmentPolicy.TimingProfile.UNKNOWN
            || revision < 0 || cycle < 0
            || !RecruitmentPolicy.validLockedTarget(timingProfile, settlementId,
                cycle, lockedTarget)
            || progress < 0 || progress > lockedTarget
            || qualifiedSeconds < 0
            || qualifiedSeconds > requiredMinimum()
            || journeyEvidenceMask < 0 || journeyEvidenceMask > 7
            || travelerName.length() > 64) {
            throw new IllegalArgumentException("invalid recruitment scalars");
        }
        boolean tavern = hasLockedTavern();
        boolean anyTavern = tavernBuildingId != null || tavernPlaquePos != null
            || tavernAnchor != null || dimension != null;
        if (anyTavern != tavern) {
            throw new IllegalArgumentException("partial tavern identity");
        }
        if (status != Status.ADMITTED && admissionReceipt != null) {
            throw new IllegalArgumentException("receipt outside admitted state");
        }
        boolean namedCandidate = travelerId != null && !travelerName.isBlank();
        if (hasCandidate() && status != Status.QUARANTINED && !namedCandidate
            || !hasCandidate() && !travelerName.isEmpty()) {
            throw new IllegalArgumentException("invalid traveler name identity");
        }
        if ((journeyEvidenceMask & EVIDENCE_ARRIVAL) != 0 && arrivedTick < spawnedTick
            || (journeyEvidenceMask & EVIDENCE_ADMISSION) != 0
                && status != Status.ADMITTED) {
            throw new IllegalArgumentException("impossible journey evidence mask");
        }
        switch (status) {
            case ATTRACTING -> {
                if (progress != 0 || qualifiedSeconds != 0
                    || qualificationStartedTick != NO_TICK || transactionId != null
                    || travelerId != null
                    || spawnedTick != NO_TICK || arrivedTick != NO_TICK || anyTavern
                    || terminalReason != TerminalReason.NONE) {
                    throw new IllegalArgumentException("invalid attracting state");
                }
            }
            case QUALIFYING -> {
                if (!tavern || transactionId == null || qualificationStartedTick < 0L || travelerId != null
                    || spawnedTick != NO_TICK || arrivedTick != NO_TICK
                    || progress < 1 || qualifiedSeconds < 1
                    || terminalReason != TerminalReason.NONE) {
                    throw new IllegalArgumentException("invalid qualifying state");
                }
            }
            case READY_TO_SPAWN -> {
                if (!tavern || transactionId == null || qualificationStartedTick < 0L || travelerId != null
                    || spawnedTick != NO_TICK || arrivedTick != NO_TICK
                    || progress != lockedTarget
                    || qualifiedSeconds != requiredMinimum()
                    || terminalReason != TerminalReason.NONE) {
                    throw new IllegalArgumentException("invalid ready state");
                }
            }
            case TRAVELING -> {
                if (!tavern || transactionId == null || travelerId == null || spawnedTick < 0L
                    || arrivedTick != NO_TICK || terminalReason != TerminalReason.NONE) {
                    throw new IllegalArgumentException("invalid traveling state");
                }
            }
            case WAITING_ADMISSION -> {
                if (!tavern || transactionId == null || travelerId == null || spawnedTick < 0L
                    || arrivedTick < spawnedTick || terminalReason != TerminalReason.NONE) {
                    throw new IllegalArgumentException("invalid waiting state");
                }
            }
            case DEPARTING -> {
                boolean validArrival = arrivedTick == NO_TICK || arrivedTick >= spawnedTick;
                if (!tavern || transactionId == null || travelerId == null || spawnedTick < 0L
                    || !validArrival || terminalReason != TerminalReason.NONE) {
                    throw new IllegalArgumentException("invalid departing state");
                }
            }
            case ADMITTED -> {
                if (!tavern || transactionId == null || travelerId == null || arrivedTick < spawnedTick
                    || terminalReason != TerminalReason.ADMITTED
                    || admissionReceipt == null || !admissionReceipt.valid()) {
                    throw new IllegalArgumentException("invalid admitted state");
                }
            }
            case LEFT -> {
                boolean validArrival = arrivedTick == NO_TICK || arrivedTick >= spawnedTick;
                boolean patienceHasArrival = terminalReason
                    != TerminalReason.PATIENCE_EXPIRED || arrivedTick >= spawnedTick;
                if (!tavern || transactionId == null || travelerId == null
                    || spawnedTick < 0L || !validArrival || !patienceHasArrival
                    || (terminalReason != TerminalReason.PATIENCE_EXPIRED
                        && terminalReason != TerminalReason.ENTITY_GONE
                        && terminalReason != TerminalReason.TAVERN_INVALIDATED
                        && terminalReason != TerminalReason.PLAYER_REJECTED
                        && terminalReason != TerminalReason.VISIT_COMPLETE)) {
                    throw new IllegalArgumentException("invalid left state");
                }
            }
            case QUARANTINED -> {
                if (terminalReason == TerminalReason.NONE) {
                    throw new IllegalArgumentException("unreasoned quarantine");
                }
            }
            case UNKNOWN -> throw new IllegalArgumentException("unknown state");
        }
    }

    private static void require(CompoundTag tag, String key, int type) {
        if (!tag.contains(key, type)) {
            throw new IllegalArgumentException("missing or wrong " + key);
        }
    }

    @Nullable
    private static UUID optionalUuid(CompoundTag tag, String key) {
        if (!tag.contains(key)) {
            return null;
        }
        if (!tag.hasUUID(key)) {
            throw new IllegalArgumentException("invalid " + key);
        }
        return tag.getUUID(key);
    }

    @Nullable
    private static BlockPos optionalPos(CompoundTag tag, String key) {
        if (!tag.contains(key)) {
            return null;
        }
        return NbtUtils.readBlockPos(tag, key)
            .orElseThrow(() -> new IllegalArgumentException("invalid " + key));
    }

    @Nullable
    private static ResourceLocation optionalDimension(CompoundTag tag) {
        if (!tag.contains("Dimension")) {
            return null;
        }
        if (!tag.contains("Dimension", Tag.TAG_STRING)) {
            throw new IllegalArgumentException("invalid Dimension");
        }
        ResourceLocation parsed = ResourceLocation.tryParse(tag.getString("Dimension"));
        if (parsed == null) {
            throw new IllegalArgumentException("invalid Dimension");
        }
        return parsed;
    }
}
