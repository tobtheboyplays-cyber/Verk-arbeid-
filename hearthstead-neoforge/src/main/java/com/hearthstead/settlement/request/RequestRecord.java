package com.hearthstead.settlement.request;

import com.hearthstead.entity.Profession;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One persisted request intent and its bounded state trace.
 *
 * <p>Physical ownership is never stored here. OPEN/RESERVED point at the
 * exact source stack; PICKUP may span its exact remainder and the named
 * Courier's real bag; IN_TRANSIT points at that bag;
 * DELIVERED/SATISFIED point at the exact target container. Callers must
 * re-observe that physical truth before invoking any state edge.
 */
public final class RequestRecord {
    public static final int DATA_VERSION = 2;
    public static final int MAX_TRANSITIONS = 2 * RequestItemFingerprint.MAX_COUNT + 32;
    public static final long MAX_REVISION = 1_000_000_000L;
    public static final int MAX_SOURCE_SLOT = 1_024;
    public static final int MAX_OBSERVED_CONTAINER_COUNT = 1_000_000;

    private final UUID id;
    private final UUID settlementId;
    private final RequestType type;
    private final RequestPriority priority;
    private final UUID requesterId;
    private final Profession requesterProfession;
    private final ResourceLocation dimensionId;
    private final UUID sourceBuildingId;
    private final BlockPos sourceContainer;
    private final int sourceSlot;
    private final UUID targetBuildingId;
    private final BlockPos targetContainer;
    private final RequestItemFingerprint fingerprint;
    /** Exact source-slot count observed when the request opened. */
    private final int sourceCountBefore;
    /** Exact-fingerprint count in the named target container at open. */
    private final int targetCountBefore;
    private final long createdAt;
    private final List<RequestTransition> transitions;

    private RequestState state;
    @Nullable
    private RequestState blockedFrom;
    private RequestBlocker blocker;
    @Nullable
    private UUID courierId;
    private long leaseUntil;
    private long updatedAt;
    private long revision;
    private int movedCount;
    private int deliveredCount;

    private RequestRecord(UUID id, UUID settlementId, RequestType type,
                          RequestPriority priority, UUID requesterId,
                          Profession requesterProfession,
                          ResourceLocation dimensionId,
                          UUID sourceBuildingId, BlockPos sourceContainer,
                          int sourceSlot, UUID targetBuildingId,
                          BlockPos targetContainer,
                          RequestItemFingerprint fingerprint,
                          int sourceCountBefore, int targetCountBefore,
                          long createdAt, RequestState state,
                          @Nullable RequestState blockedFrom,
                          RequestBlocker blocker, @Nullable UUID courierId,
                          long leaseUntil, long updatedAt, long revision,
                          int movedCount, int deliveredCount,
                          List<RequestTransition> transitions) {
        this.id = requireUuid(id, "id");
        this.settlementId = requireUuid(settlementId, "settlement");
        this.type = Objects.requireNonNull(type, "type");
        this.priority = Objects.requireNonNull(priority, "priority");
        this.requesterId = requireUuid(requesterId, "requester");
        this.requesterProfession = Objects.requireNonNull(requesterProfession,
            "requesterProfession");
        this.dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
        this.sourceBuildingId = requireUuid(sourceBuildingId, "sourceBuilding");
        this.sourceContainer = Objects.requireNonNull(sourceContainer,
            "sourceContainer").immutable();
        if (sourceSlot < 0 || sourceSlot > MAX_SOURCE_SLOT) {
            throw new IllegalArgumentException("source slot out of bounds");
        }
        this.sourceSlot = sourceSlot;
        this.targetBuildingId = requireUuid(targetBuildingId, "targetBuilding");
        this.targetContainer = Objects.requireNonNull(targetContainer,
            "targetContainer").immutable();
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        this.sourceCountBefore = sourceCountBefore;
        this.targetCountBefore = targetCountBefore;
        this.createdAt = createdAt;
        this.state = Objects.requireNonNull(state, "state");
        this.blockedFrom = blockedFrom;
        this.blocker = Objects.requireNonNull(blocker, "blocker");
        this.courierId = normalizeUuid(courierId);
        this.leaseUntil = leaseUntil;
        this.updatedAt = updatedAt;
        this.revision = revision;
        this.movedCount = movedCount;
        this.deliveredCount = deliveredCount;
        this.transitions = new ArrayList<>(Objects.requireNonNull(transitions,
            "transitions"));
        validateStatic();
        validateTraceAndState();
    }

    public static RequestRecord openOutput(UUID settlementId,
                                           RequestPriority priority,
                                           ResourceLocation dimensionId,
                                           UUID sourceBuildingId,
                                           BlockPos sourceContainer,
                                           int sourceSlot,
                                           UUID targetBuildingId,
                                           BlockPos targetContainer,
                                           RequestItemFingerprint fingerprint,
                                           int sourceCountBefore,
                                           int targetCountBefore,
                                           long gameTime) {
        return new RequestRecord(UUID.randomUUID(), settlementId,
            RequestType.OUTPUT_PICKUP, priority, sourceBuildingId,
            Profession.NONE, dimensionId, sourceBuildingId, sourceContainer,
            sourceSlot, targetBuildingId, targetContainer, fingerprint,
            sourceCountBefore, targetCountBefore, gameTime,
            RequestState.OPEN, null, RequestBlocker.NONE, null,
            0L, gameTime, 1L, 0, 0, List.of());
    }

    /** FOOD uses the settlement identity for its exact Hearth endpoint, not a synthetic Building. */
    public static RequestRecord openFood(UUID settlementId,
                                        ResourceLocation dimensionId,
                                        UUID sourceBuildingId, BlockPos sourceContainer,
                                        int sourceSlot, BlockPos hearth,
                                        RequestItemFingerprint fingerprint,
                                        int sourceCountBefore, int targetCountBefore,
                                        long gameTime) {
        return new RequestRecord(UUID.randomUUID(), settlementId,
            RequestType.FOOD, RequestPriority.HIGH, sourceBuildingId,
            Profession.NONE, dimensionId, sourceBuildingId, sourceContainer,
            sourceSlot, settlementId, hearth, fingerprint,
            sourceCountBefore, targetCountBefore, gameTime,
            RequestState.OPEN, null, RequestBlocker.NONE, null,
            0L, gameTime, 1L, 0, 0, List.of());
    }

    /** MATERIAL_INPUT carries one permitted Warehouse service item to one staffed Tavern. */
    public static RequestRecord openMaterialInput(UUID settlementId,
                                                  ResourceLocation dimensionId,
                                                  UUID sourceBuildingId,
                                                  BlockPos sourceContainer,
                                                  int sourceSlot,
                                                  UUID targetBuildingId,
                                                  BlockPos targetContainer,
                                                  RequestItemFingerprint fingerprint,
                                                  int sourceCountBefore,
                                                  int targetCountBefore,
                                                  long gameTime) {
        return new RequestRecord(UUID.randomUUID(), settlementId,
            RequestType.MATERIAL_INPUT, RequestPriority.NORMAL, sourceBuildingId,
            Profession.NONE, dimensionId, sourceBuildingId, sourceContainer,
            sourceSlot, targetBuildingId, targetContainer, fingerprint,
            sourceCountBefore, targetCountBefore, gameTime,
            RequestState.OPEN, null, RequestBlocker.NONE, null,
            0L, gameTime, 1L, 0, 0, List.of());
    }
    /** AMMUNITION carries one exact Warehouse Arrow stack to one exact Hunter Lodge. */
    public static RequestRecord openAmmunition(UUID settlementId,
                                               ResourceLocation dimensionId,
                                               UUID sourceBuildingId,
                                               BlockPos sourceContainer,
                                               int sourceSlot,
                                               UUID targetBuildingId,
                                               BlockPos targetContainer,
                                               RequestItemFingerprint fingerprint,
                                               int sourceCountBefore,
                                               int targetCountBefore,
                                               long gameTime) {
        return new RequestRecord(UUID.randomUUID(), settlementId,
            RequestType.AMMUNITION, RequestPriority.NORMAL, sourceBuildingId,
            Profession.NONE, dimensionId, sourceBuildingId, sourceContainer,
            sourceSlot, targetBuildingId, targetContainer, fingerprint,
            sourceCountBefore, targetCountBefore, gameTime,
            RequestState.OPEN, null, RequestBlocker.NONE, null,
            0L, gameTime, 1L, 0, 0, List.of());
    }

    /** Package-visible deterministic constructor for pure tests/migration. */
    static RequestRecord openOutput(UUID requestId, UUID settlementId,
                                    RequestPriority priority,
                                    ResourceLocation dimensionId,
                                    UUID sourceBuildingId,
                                    BlockPos sourceContainer, int sourceSlot,
                                    UUID targetBuildingId,
                                    BlockPos targetContainer,
                                    RequestItemFingerprint fingerprint,
                                    int sourceCountBefore,
                                    int targetCountBefore,
                                    long gameTime) {
        return new RequestRecord(requestId, settlementId,
            RequestType.OUTPUT_PICKUP, priority, sourceBuildingId,
            Profession.NONE, dimensionId, sourceBuildingId, sourceContainer,
            sourceSlot, targetBuildingId, targetContainer, fingerprint,
            sourceCountBefore, targetCountBefore, gameTime,
            RequestState.OPEN, null, RequestBlocker.NONE, null,
            0L, gameTime, 1L, 0, 0, List.of());
    }

    public UUID id() { return id; }
    public UUID settlementId() { return settlementId; }
    public RequestType type() { return type; }
    public RequestPriority priority() { return priority; }
    public UUID requesterId() { return requesterId; }
    public Profession requesterProfession() { return requesterProfession; }
    public ResourceLocation dimensionId() { return dimensionId; }
    public UUID sourceBuildingId() { return sourceBuildingId; }
    public BlockPos sourceContainer() { return sourceContainer; }
    public int sourceSlot() { return sourceSlot; }
    public UUID targetBuildingId() { return targetBuildingId; }
    public BlockPos targetContainer() { return targetContainer; }
    public RequestItemFingerprint fingerprint() { return fingerprint; }
    public int sourceCountBefore() { return sourceCountBefore; }
    public int targetCountBefore() { return targetCountBefore; }
    public long createdAt() { return createdAt; }
    public RequestState state() { return state; }
    @Nullable public RequestState blockedFrom() { return blockedFrom; }
    public RequestBlocker blocker() { return blocker; }
    @Nullable public UUID courierId() { return courierId; }
    public long leaseUntil() { return leaseUntil; }
    public long updatedAt() { return updatedAt; }
    public long revision() { return revision; }
    public int movedCount() { return movedCount; }
    public int deliveredCount() { return deliveredCount; }
    public int remainingCount() { return fingerprint.count() - deliveredCount; }
    public List<RequestTransition> transitions() { return List.copyOf(transitions); }

    public RequestState effectiveState() {
        return state == RequestState.BLOCKED && blockedFrom != null
            ? blockedFrom : state;
    }

    public String duplicateKey() {
        // One physical source slot can back at most one active intent. Item,
        // count or target changes while that row is live must reconcile or
        // block the existing request, never open a second reservation over
        // the same inventory ownership boundary.
        // Building identity is intentionally NOT part of this key. Two
        // malformed/overlapping building records must never reserve the same
        // physical container slot twice merely because their UUIDs differ.
        return dimensionId + ":" + sourceContainer.asLong() + ":"
            + sourceSlot;
    }

    boolean reserve(UUID courier, long now, long ttlTicks) {
        UUID nextCourier = normalizeUuid(courier);
        if (state != RequestState.OPEN || nextCourier == null
            || now < 0L || ttlTicks <= 0L
            || !canTransition(RequestState.RESERVED, now)) {
            return false;
        }
        long nextLease = saturatingAdd(now, ttlTicks);
        courierId = nextCourier;
        leaseUntil = nextLease;
        applyTransition(RequestState.RESERVED, RequestBlocker.NONE, now);
        return true;
    }

    boolean renew(UUID courier, long now, long ttlTicks) {
        if (effectiveState() != RequestState.RESERVED
            || courierId == null || !courierId.equals(courier)
            || ttlTicks <= 0L || now < 0L) {
            return false;
        }
        long candidate = saturatingAdd(now, ttlTicks);
        if (candidate <= leaseUntil) {
            return false;
        }
        if (!canTouch(now)) {
            return false;
        }
        leaseUntil = candidate;
        applyTouch(now);
        return true;
    }

    boolean releaseToOpen(UUID courier, long now) {
        boolean blockedReservation = state == RequestState.BLOCKED
            && blockedFrom == RequestState.RESERVED;
        if ((!blockedReservation && state != RequestState.RESERVED)
            || courierId == null || !courierId.equals(courier)
            || movedCount != 0) {
            return false;
        }
        if (blockedReservation) {
            if (transitions.size() > MAX_TRANSITIONS - 2
                || revision > MAX_REVISION - 2 || now < updatedAt
                || !canTransition(RequestState.RESERVED, now)) {
                return false;
            }
            applyTransition(RequestState.RESERVED, RequestBlocker.NONE, now);
            blockedFrom = null;
            blocker = RequestBlocker.NONE;
        }
        if (!canTransition(RequestState.OPEN, now)) {
            return false;
        }
        applyTransition(RequestState.OPEN, RequestBlocker.NONE, now);
        courierId = null;
        leaseUntil = 0L;
        return true;
    }

    boolean markPickup(UUID courier, long now) {
        if (state != RequestState.RESERVED || courierId == null
            || !courierId.equals(courier) || movedCount != 0
            || !canTransition(RequestState.PICKUP, now)) {
            return false;
        }
        applyTransition(RequestState.PICKUP, RequestBlocker.NONE, now);
        return true;
    }

    /** Preflight all remaining unit pickup/delivery evidence before touching cargo. */
    boolean canNotePickup(UUID courier, int expectedMoved, int amount, long now) {
        int remaining = fingerprint.count() - movedCount;
        int completionEdges = remaining + fingerprint.count() + 2;
        return state == RequestState.PICKUP && Objects.equals(courierId, courier)
            && courier != null && movedCount == expectedMoved && amount > 0
            && amount <= remaining && now >= updatedAt
            && transitions.size() <= MAX_TRANSITIONS - completionEdges
            && revision <= MAX_REVISION - completionEdges;
    }

    /** A PICKUP self-edge is one real unit; the final unit enters IN_TRANSIT. */
    boolean notePickedUp(UUID courier, int expectedMoved, int amount, long now) {
        if (!canNotePickup(courier, expectedMoved, amount, now)
            || amount != 1 && amount != fingerprint.count() - movedCount) {
            return false;
        }
        int next = movedCount + amount;
        if (next == fingerprint.count()) {
            return markInTransit(courier, next, now);
        }
        movedCount = next;
        applyTransition(RequestState.PICKUP, RequestBlocker.NONE, now);
        return true;
    }

    boolean markInTransit(UUID courier, int physicallyMoved, long now) {
        if (state != RequestState.PICKUP || courierId == null
            || !courierId.equals(courier)
            || physicallyMoved != fingerprint.count()
            || !canTransition(RequestState.IN_TRANSIT, now)) {
            return false;
        }
        movedCount = physicallyMoved;
        leaseUntil = 0L;
        applyTransition(RequestState.IN_TRANSIT, RequestBlocker.NONE, now);
        return true;
    }

    boolean noteDelivered(UUID courier, int physicallyInserted, long now) {
        if (state != RequestState.IN_TRANSIT || courierId == null
            || !courierId.equals(courier) || physicallyInserted <= 0
            || physicallyInserted > remainingCount()) {
            return false;
        }
        int nextDelivered = deliveredCount + physicallyInserted;
        int edges = nextDelivered == fingerprint.count() ? 2 : 1;
        if (transitions.size() > MAX_TRANSITIONS - edges
            || revision > MAX_REVISION - edges || now < updatedAt) {
            return false;
        }
        deliveredCount = nextDelivered;
        // A persisted self-edge records every partial destination commit, so
        // load validation never has to trust mutable counts that are absent
        // from the trace.
        applyTransition(RequestState.IN_TRANSIT, RequestBlocker.NONE, now);
        if (nextDelivered == fingerprint.count()) {
            applyTransition(RequestState.DELIVERED, RequestBlocker.NONE, now);
        }
        return true;
    }

    boolean markSatisfied(UUID courier, long now) {
        if (state != RequestState.DELIVERED || courierId == null
            || !courierId.equals(courier)
            || movedCount != fingerprint.count()
            || deliveredCount != fingerprint.count()) {
            return false;
        }
        if (!canTransition(RequestState.SATISFIED, now)) {
            return false;
        }
        applyTransition(RequestState.SATISFIED, RequestBlocker.NONE, now);
        leaseUntil = 0L;
        return true;
    }

    boolean block(RequestBlocker reason, long now) {
        if (reason == null || reason == RequestBlocker.NONE
            || state.terminal() || state == RequestState.DELIVERED) {
            return false;
        }
        if (state == RequestState.BLOCKED) {
            return false;
        }
        RequestState interrupted = state;
        if (!canTransition(RequestState.BLOCKED, now)) {
            return false;
        }
        applyTransition(RequestState.BLOCKED, reason, now);
        blockedFrom = interrupted;
        return true;
    }

    boolean resume(long now) {
        if (state != RequestState.BLOCKED || blockedFrom == null) {
            return false;
        }
        RequestState resume = blockedFrom;
        if (!canTransition(resume, now)) {
            return false;
        }
        applyTransition(resume, RequestBlocker.NONE, now);
        blockedFrom = null;
        blocker = RequestBlocker.NONE;
        return true;
    }

    boolean cancelAfterPhysicalReturn(UUID courier, long now) {
        RequestState effective = effectiveState();
        if (effective != RequestState.IN_TRANSIT || courierId == null
            || !courierId.equals(courier)
            || movedCount - deliveredCount != fingerprint.count() - deliveredCount) {
            return false;
        }
        if (!canTransition(RequestState.CANCELLED, now)) {
            return false;
        }
        applyTransition(RequestState.CANCELLED, RequestBlocker.NONE, now);
        leaseUntil = 0L;
        return true;
    }

    /** Only the service may call this after proving bag empty and source intact. */
    boolean cancelUnstartedPickup(UUID courier, long now) {
        if (effectiveState() != RequestState.PICKUP || courierId == null
            || !courierId.equals(courier) || movedCount != 0 || deliveredCount != 0
            || !canTransition(RequestState.CANCELLED, now)) {
            return false;
        }
        applyTransition(RequestState.CANCELLED, RequestBlocker.NONE, now);
        leaseUntil = 0L;
        blockedFrom = null;
        return true;
    }

    boolean expire(long now) {
        RequestState effective = effectiveState();
        if (effective != RequestState.OPEN && effective != RequestState.RESERVED) {
            return false;
        }
        if (!canTransition(RequestState.EXPIRED, now)) {
            return false;
        }
        applyTransition(RequestState.EXPIRED, RequestBlocker.NONE, now);
        leaseUntil = 0L;
        blockedFrom = null;
        return true;
    }

    public boolean hasFullTransportTrace() {
        int start = 0;
        for (int index = 0; index < transitions.size(); index++) {
            if (transitions.get(index).to() == RequestState.OPEN) {
                start = index + 1;
            }
        }
        int phase = 0;
        UUID traceCourier = null;
        for (int index = start; index < transitions.size(); index++) {
            RequestTransition edge = transitions.get(index);
            if (edge.to() == RequestState.RESERVED && phase == 0) {
                traceCourier = edge.courierId();
                phase = 1;
            } else if (edge.to() == RequestState.PICKUP && phase == 1
                    && edge.from() != RequestState.BLOCKED) {
                phase = 2;
            } else if (edge.to() == RequestState.IN_TRANSIT && phase == 2
                    && edge.from() == RequestState.PICKUP) {
                phase = 3;
            } else if (edge.to() == RequestState.DELIVERED && phase == 3) {
                phase = 4;
            } else if (edge.to() == RequestState.SATISFIED && phase == 4) {
                phase = 5;
            }
        }
        return phase == 5 && traceCourier != null
            && traceCourier.equals(courierId)
            && state == RequestState.SATISFIED
            && movedCount == fingerprint.count()
            && deliveredCount == fingerprint.count();
    }

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", DATA_VERSION);
        tag.putUUID("Id", id);
        tag.putUUID("Settlement", settlementId);
        tag.putInt("Type", type.wireId());
        tag.putInt("Priority", priority.wireId());
        tag.putUUID("Requester", requesterId);
        tag.putString("Profession", requesterProfession.key());
        tag.putString("Dimension", dimensionId.toString());
        tag.putUUID("SourceBuilding", sourceBuildingId);
        tag.putLong("SourceContainer", sourceContainer.asLong());
        tag.putInt("SourceSlot", sourceSlot);
        tag.putUUID("TargetBuilding", targetBuildingId);
        tag.putLong("TargetContainer", targetContainer.asLong());
        tag.put("Fingerprint", fingerprint.writeNbt());
        tag.putInt("SourceCountBefore", sourceCountBefore);
        tag.putInt("TargetCountBefore", targetCountBefore);
        tag.putLong("CreatedAt", createdAt);
        tag.putInt("State", state.wireId());
        if (blockedFrom != null) {
            tag.putInt("BlockedFrom", blockedFrom.wireId());
        }
        tag.putInt("Blocker", blocker.wireId());
        if (courierId != null) {
            tag.putUUID("Courier", courierId);
        }
        tag.putLong("LeaseUntil", leaseUntil);
        tag.putLong("UpdatedAt", updatedAt);
        tag.putLong("Revision", revision);
        tag.putInt("Moved", movedCount);
        tag.putInt("Delivered", deliveredCount);
        ListTag history = new ListTag();
        for (RequestTransition transition : transitions) {
            history.add(transition.writeNbt());
        }
        tag.put("Transitions", history);
        return tag;
    }

    @Nullable
    public static RequestRecord readNbt(CompoundTag tag,
                                        HolderLookup.Provider registries,
                                        UUID expectedSettlementId) {
        return readNbt(tag, registries, expectedSettlementId, false);
    }

    /** Ledger-load seam: only returns a terminal EXPIRED legacy row. */
    @Nullable
    static RequestRecord readLegacyZeroCargoOutputAsExpiredNbt(
            CompoundTag tag, HolderLookup.Provider registries,
            UUID expectedSettlementId) {
        if (!isLegacyZeroCargoOutputDigest(tag, registries)) {
            return null;
        }
        RequestRecord row = readNbt(tag, registries, expectedSettlementId, true);
        return row != null && row.expire(row.updatedAt()) ? row : null;
    }

    @Nullable
    static RequestRecord readLegacyTerminalNbt(CompoundTag tag,
                                               HolderLookup.Provider registries,
                                               UUID expectedSettlementId) {
        if (tag == null || registries == null || expectedSettlementId == null
            || tag.getInt("DataVersion") != DATA_VERSION
            || !tag.contains("State", Tag.TAG_INT)
            || !RequestState.fromWireId(tag.getInt("State"))
                .map(RequestState::terminal).orElse(false)
            || !(tag.get("Fingerprint") instanceof CompoundTag fingerprintTag)) return null;
        boolean legacyDigest = RequestItemFingerprint.readNbt(fingerprintTag, registries) == null;
        if (legacyDigest && RequestItemFingerprint.readLegacyZeroCargoOutputNbt(
                fingerprintTag, registries) == null) return null;
        CompoundTag normalized = tag;
        // Historical expiry closed the trace but left its zero-cargo reservation
        // marker behind. Normalize only this terminal shape, then validate all
        // identities, counts and transition evidence through the constructor.
        ListTag transitions = tag.getList("Transitions", Tag.TAG_COMPOUND);
        if (tag.getInt("State") == RequestState.EXPIRED.wireId()
            && tag.contains("BlockedFrom", Tag.TAG_INT)
            && tag.getInt("BlockedFrom") == RequestState.RESERVED.wireId()
            && tag.getInt("Moved") == 0 && tag.getInt("Delivered") == 0
            && !transitions.isEmpty()) {
            CompoundTag last = transitions.getCompound(transitions.size() - 1);
            if (last.getInt("From") == RequestState.BLOCKED.wireId()
                && last.getInt("To") == RequestState.EXPIRED.wireId()
                && last.getInt("Moved") == 0 && last.getInt("Delivered") == 0
                && last.getInt("Blocker") == RequestBlocker.NONE.wireId()) {
                normalized = tag.copy();
                normalized.remove("BlockedFrom");
            }
        }
        // Marker migration is independent of fingerprint format. A canonical
        // fingerprint must retain strict validation; only existing legacy history
        // uses the historical checksum path. No active row enters this method.
        if (!legacyDigest && normalized == tag) return null;
        RequestRecord row = readNbt(normalized, registries, expectedSettlementId, legacyDigest);
        return row != null && row.state().terminal() ? row : null;
    }

    @Nullable
    private static RequestRecord readNbt(CompoundTag tag,
                                         HolderLookup.Provider registries,
                                         UUID expectedSettlementId,
                                         boolean allowLegacyZeroCargoDigest) {
        if (tag == null || registries == null || expectedSettlementId == null
            || !tag.contains("DataVersion", Tag.TAG_INT)
            || (tag.getInt("DataVersion") != 1
                && tag.getInt("DataVersion") != DATA_VERSION)
            || !tag.hasUUID("Id") || !tag.hasUUID("Settlement")
            || !expectedSettlementId.equals(tag.getUUID("Settlement"))
            || !tag.contains("Type", Tag.TAG_INT)
            || !tag.contains("Priority", Tag.TAG_INT)
            || !tag.hasUUID("Requester")
            || !tag.contains("Profession", Tag.TAG_STRING)
            || !tag.contains("Dimension", Tag.TAG_STRING)
            || !tag.hasUUID("SourceBuilding")
            || !tag.contains("SourceContainer", Tag.TAG_LONG)
            || !tag.contains("SourceSlot", Tag.TAG_INT)
            || !tag.hasUUID("TargetBuilding")
            || !tag.contains("TargetContainer", Tag.TAG_LONG)
            || !(tag.get("Fingerprint") instanceof CompoundTag fingerprintTag)
            || !tag.contains("SourceCountBefore", Tag.TAG_INT)
            || !tag.contains("TargetCountBefore", Tag.TAG_INT)
            || !tag.contains("CreatedAt", Tag.TAG_LONG)
            || !tag.contains("State", Tag.TAG_INT)
            || !tag.contains("Blocker", Tag.TAG_INT)
            || tag.contains("Courier") && !tag.hasUUID("Courier")
            || !tag.contains("LeaseUntil", Tag.TAG_LONG)
            || !tag.contains("UpdatedAt", Tag.TAG_LONG)
            || !tag.contains("Revision", Tag.TAG_LONG)
            || !tag.contains("Moved", Tag.TAG_INT)
            || !tag.contains("Delivered", Tag.TAG_INT)
            || !tag.contains("Transitions", Tag.TAG_LIST)) {
            return null;
        }
        var type = RequestType.fromWireId(tag.getInt("Type"));
        var priority = RequestPriority.fromWireId(tag.getInt("Priority"));
        var state = RequestState.fromWireId(tag.getInt("State"));
        var blocker = RequestBlocker.fromWireId(tag.getInt("Blocker"));
        RequestState blockedFrom = null;
        if (tag.contains("BlockedFrom", Tag.TAG_INT)) {
            blockedFrom = RequestState.fromWireId(tag.getInt("BlockedFrom"))
                .orElse(null);
        }
        Profession profession = profession(tag.getString("Profession"));
        ResourceLocation dimension = ResourceLocation.tryParse(
            tag.getString("Dimension"));
        // The former presentation checksum encoded the same parsed stack
        // differently. Only a zero-cargo OUTPUT row already blocked from its
        // reservation may reconstruct that checksum: recovery can then expire
        // its stale intent but can never revive or move an item.
        RequestItemFingerprint fingerprint = allowLegacyZeroCargoDigest
            ? RequestItemFingerprint.readLegacyZeroCargoOutputNbt(
                fingerprintTag, registries)
            : RequestItemFingerprint.readNbt(fingerprintTag, registries);
        if (type.isEmpty() || priority.isEmpty() || state.isEmpty()
            || blocker.isEmpty() || profession == null || dimension == null
            || fingerprint == null) {
            return null;
        }
        ListTag savedTransitions = tag.getList("Transitions", Tag.TAG_COMPOUND);
        if (savedTransitions.size() > (tag.getInt("DataVersion") == 1
                ? 32 : MAX_TRANSITIONS)) {
            return null;
        }
        List<RequestTransition> transitions = new ArrayList<>(savedTransitions.size());
        for (int i = 0; i < savedTransitions.size(); i++) {
            RequestTransition transition = RequestTransition.readNbt(
                savedTransitions.getCompound(i));
            if (transition == null || tag.getInt("DataVersion") == 1
                    && transition.to() == RequestState.PICKUP
                    && (transition.movedCount() != 0
                        || transition.from() == RequestState.PICKUP)) {
                return null;
            }
            transitions.add(transition);
        }
        try {
            return new RequestRecord(tag.getUUID("Id"),
                tag.getUUID("Settlement"), type.get(), priority.get(),
                tag.getUUID("Requester"), profession, dimension,
                tag.getUUID("SourceBuilding"),
                BlockPos.of(tag.getLong("SourceContainer")),
                tag.getInt("SourceSlot"), tag.getUUID("TargetBuilding"),
                BlockPos.of(tag.getLong("TargetContainer")), fingerprint,
                tag.getInt("SourceCountBefore"),
                tag.getInt("TargetCountBefore"), tag.getLong("CreatedAt"),
                state.get(), blockedFrom,
                blocker.get(), tag.hasUUID("Courier")
                    ? tag.getUUID("Courier") : null,
                tag.getLong("LeaseUntil"), tag.getLong("UpdatedAt"),
                tag.getLong("Revision"), tag.getInt("Moved"),
                tag.getInt("Delivered"), transitions);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /**
     * Only the historic no-cargo output shape may be migrated, and only into
     * terminal history. A changed digest never becomes authority to reserve or
     * pick up its prototype again.
     */
    static boolean isLegacyZeroCargoOutputDigest(CompoundTag tag,
                                                  HolderLookup.Provider registries) {
        if (tag == null || registries == null
            || tag.getInt("DataVersion") != DATA_VERSION
            || tag.getInt("Type") != RequestType.OUTPUT_PICKUP.wireId()
            || tag.getInt("State") != RequestState.BLOCKED.wireId()
            || !tag.contains("BlockedFrom", Tag.TAG_INT)
            || tag.getInt("BlockedFrom") != RequestState.RESERVED.wireId()
            || tag.getInt("Blocker") != RequestBlocker.FINGERPRINT_MISMATCH.wireId()
            || !tag.hasUUID("Courier") || tag.getInt("Moved") != 0
            || tag.getInt("Delivered") != 0
            || !(tag.get("Fingerprint") instanceof CompoundTag fingerprintTag)) {
            return false;
        }
        return RequestItemFingerprint.readNbt(fingerprintTag, registries) == null
            && RequestItemFingerprint.readLegacyZeroCargoOutputNbt(
                fingerprintTag, registries) != null;
    }

    private boolean canTransition(RequestState to, long now) {
        return to != null && now >= 0L && transitions.size() < MAX_TRANSITIONS
            && now >= updatedAt && revision < MAX_REVISION
            && legal(state, to, blockedFrom);
    }

    /** Applies an already-validated edge without a possible partial mutation. */
    private void applyTransition(RequestState to, RequestBlocker reason,
                                 long now) {
        RequestState from = state;
        RequestBlocker edgeBlocker = to == RequestState.BLOCKED
            ? reason : RequestBlocker.NONE;
        RequestTransition edge = new RequestTransition(transitions.size() + 1,
            from, to, now, courierId, edgeBlocker, movedCount, deliveredCount);
        state = to;
        blocker = edgeBlocker;
        updatedAt = now;
        revision++;
        transitions.add(edge);
    }

    private boolean canTouch(long now) {
        return now >= 0L && now >= updatedAt && revision < MAX_REVISION;
    }

    private void applyTouch(long now) {
        updatedAt = now;
        revision++;
    }

    private void validateStatic() {
        if ((type != RequestType.OUTPUT_PICKUP && type != RequestType.FOOD
                && type != RequestType.AMMUNITION && type != RequestType.MATERIAL_INPUT)
            || (type == RequestType.FOOD && !targetBuildingId.equals(settlementId))
            || (type == RequestType.AMMUNITION
                && !fingerprint.itemId().equals(
                    ResourceLocation.withDefaultNamespace("arrow")))
            || !requesterId.equals(sourceBuildingId)
            || requesterProfession != Profession.NONE
            || sourceBuildingId.equals(targetBuildingId)
                && (type != RequestType.OUTPUT_PICKUP || sourceContainer.equals(targetContainer))
            || createdAt < 0L || updatedAt < createdAt
            || sourceCountBefore < fingerprint.count()
            || sourceCountBefore > MAX_OBSERVED_CONTAINER_COUNT
            || targetCountBefore < 0
            || targetCountBefore > MAX_OBSERVED_CONTAINER_COUNT
            || revision <= 0L || revision > MAX_REVISION
            || leaseUntil < 0L || movedCount < 0
            || movedCount > fingerprint.count() || deliveredCount < 0
            || deliveredCount > movedCount
            || transitions.size() > MAX_TRANSITIONS) {
            throw new IllegalArgumentException("invalid request record bounds");
        }
    }

    private void validateTraceAndState() {
        RequestState replay = RequestState.OPEN;
        RequestState replayBlockedFrom = null;
        UUID replayCourier = null;
        int replayMoved = 0;
        int replayDelivered = 0;
        long previousTime = createdAt;
        int expectedSequence = 1;
        for (RequestTransition transition : transitions) {
            if (transition.sequence() != expectedSequence++
                || transition.from() != replay
                || transition.gameTime() < previousTime
                || !legal(replay, transition.to(), replayBlockedFrom)
                || transition.movedCount() < replayMoved
                || transition.deliveredCount() < replayDelivered
                || transition.deliveredCount() > transition.movedCount()) {
                throw new IllegalArgumentException("invalid request transition trace");
            }
            if (transition.to() == RequestState.RESERVED) {
                if (transition.movedCount() != 0
                    || transition.deliveredCount() != 0
                    || transition.courierId() == null
                    || replayCourier != null
                        && !replayCourier.equals(transition.courierId())) {
                    throw new IllegalArgumentException("invalid reservation evidence");
                }
                replayCourier = transition.courierId();
            }
            if (transition.to() == RequestState.PICKUP
                && (transition.movedCount() >= fingerprint.count()
                    || transition.from() == RequestState.PICKUP
                        && transition.movedCount() != replayMoved + 1
                    || transition.from() == RequestState.RESERVED
                        && transition.movedCount() != 0
                    || transition.deliveredCount() != 0
                    || replayCourier == null
                    || !replayCourier.equals(transition.courierId()))) {
                throw new IllegalArgumentException("invalid pickup evidence");
            }
            if (transition.to() == RequestState.IN_TRANSIT
                && (transition.movedCount() != fingerprint.count()
                    || transition.courierId() == null
                    || replayCourier == null
                    || !replayCourier.equals(transition.courierId())
                    || transition.from() == RequestState.IN_TRANSIT
                        && transition.deliveredCount() <= replayDelivered)) {
                throw new IllegalArgumentException("invalid transit evidence");
            }
            if ((transition.to() == RequestState.DELIVERED
                    || transition.to() == RequestState.SATISFIED)
                && (transition.movedCount() != fingerprint.count()
                    || transition.deliveredCount() != fingerprint.count()
                    || replayCourier == null
                    || !replayCourier.equals(transition.courierId()))) {
                    throw new IllegalArgumentException("invalid delivery evidence");
            }
            if (transition.to() != RequestState.RESERVED
                && !java.util.Objects.equals(replayCourier,
                    transition.courierId())) {
                throw new IllegalArgumentException("courier identity changed in trace");
            }
            if ((transition.to() == RequestState.BLOCKED
                    || transition.from() == RequestState.BLOCKED
                    || transition.to() == RequestState.OPEN
                    || transition.to() == RequestState.CANCELLED
                    || transition.to() == RequestState.EXPIRED)
                && (transition.movedCount() != replayMoved
                    || transition.deliveredCount() != replayDelivered)) {
                throw new IllegalArgumentException(
                    "non-transfer edge changed physical counts");
            }
            previousTime = transition.gameTime();
            if (transition.to() == RequestState.BLOCKED) {
                replayBlockedFrom = replay;
            } else if (replay == RequestState.BLOCKED
                && (transition.to() == replayBlockedFrom || transition.to().terminal())) {
                replayBlockedFrom = null;
            }
            replayMoved = transition.movedCount();
            replayDelivered = transition.deliveredCount();
            replay = transition.to();
            if (replay == RequestState.OPEN) {
                replayCourier = null;
            }
        }
        if (replay != state || replayBlockedFrom != blockedFrom
            || replayMoved != movedCount || replayDelivered != deliveredCount
            || updatedAt < previousTime
            || revision < 1L + transitions.size()
            || (state == RequestState.BLOCKED
                ? blockedFrom == null || blocker == RequestBlocker.NONE
                : blockedFrom != null || blocker != RequestBlocker.NONE)) {
            throw new IllegalArgumentException("request state disagrees with trace");
        }
        RequestState effective = effectiveState();
        if ((replayCourier == null) != (courierId == null)
            || replayCourier != null && !replayCourier.equals(courierId)
            || (effective == RequestState.OPEN
                && (courierId != null || movedCount != 0 || leaseUntil != 0L))
            || (effective == RequestState.RESERVED
                && (courierId == null || movedCount != 0 || leaseUntil <= 0L))
            || (effective == RequestState.PICKUP
                && (courierId == null || movedCount >= fingerprint.count()
                    || deliveredCount != 0 || leaseUntil <= 0L))
            || (effective == RequestState.IN_TRANSIT
                && (courierId == null || movedCount != fingerprint.count()
                    || deliveredCount >= fingerprint.count()
                    || leaseUntil != 0L))
            || (state == RequestState.DELIVERED
                && (courierId == null || movedCount != fingerprint.count()
                    || deliveredCount != fingerprint.count()
                    || leaseUntil != 0L))
            || (state == RequestState.SATISFIED
                && (movedCount != fingerprint.count()
                    || deliveredCount != fingerprint.count()
                    || leaseUntil != 0L
                    || !hasFullTransportTrace()))
            || ((state == RequestState.CANCELLED
                    || state == RequestState.EXPIRED)
                && leaseUntil != 0L)) {
            throw new IllegalArgumentException("request physical-owner invariant failed");
        }
    }

    private static boolean legal(RequestState from, RequestState to,
                                 @Nullable RequestState blockedFrom) {
        if (from == RequestState.BLOCKED) {
            return blockedFrom != null && (to == blockedFrom
                || to == RequestState.CANCELLED || to == RequestState.EXPIRED);
        }
        return switch (from) {
            case OPEN -> to == RequestState.RESERVED
                || to == RequestState.BLOCKED || to == RequestState.CANCELLED
                || to == RequestState.EXPIRED;
            case RESERVED -> to == RequestState.PICKUP
                || to == RequestState.OPEN || to == RequestState.BLOCKED
                || to == RequestState.CANCELLED || to == RequestState.EXPIRED;
            case PICKUP -> to == RequestState.PICKUP
                || to == RequestState.IN_TRANSIT
                || to == RequestState.BLOCKED || to == RequestState.CANCELLED;
            case IN_TRANSIT -> to == RequestState.IN_TRANSIT
                || to == RequestState.DELIVERED
                || to == RequestState.BLOCKED || to == RequestState.CANCELLED;
            case DELIVERED -> to == RequestState.SATISFIED;
            case SATISFIED, CANCELLED, EXPIRED, BLOCKED -> false;
        };
    }

    @Nullable
    private static Profession profession(String key) {
        for (Profession value : Profession.values()) {
            if (value.key().equals(key)) {
                return value;
            }
        }
        return null;
    }

    private static UUID requireUuid(UUID value, String label) {
        UUID normalized = normalizeUuid(value);
        if (normalized == null) {
            throw new IllegalArgumentException("missing " + label + " UUID");
        }
        return normalized;
    }

    @Nullable
    private static UUID normalizeUuid(@Nullable UUID value) {
        return value == null || value.getMostSignificantBits() == 0L
            && value.getLeastSignificantBits() == 0L ? null : value;
    }

    private static long saturatingAdd(long left, long right) {
        if (left < 0L || right <= 0L) {
            throw new IllegalArgumentException("negative request clock");
        }
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
