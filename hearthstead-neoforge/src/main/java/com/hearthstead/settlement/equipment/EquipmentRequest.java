package com.hearthstead.settlement.equipment;

import com.hearthstead.entity.Profession;
import com.hearthstead.settlement.request.RequestBlocker;
import com.hearthstead.settlement.request.RequestItemFingerprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Persistent intent to move one real tool to one real worker's workplace.
 * Item contents never live here: chests, courier bags and entity equipment
 * remain the only item authorities.
 */
public final class EquipmentRequest {

    public static final int TRACE_SCHEMA = 1;
    private static final int MAX_COUNT = 64;
    private static final int MAX_TRACE_NBT_CHARS = 12_288;
    private static final int MAX_HORIZONTAL_COORDINATE = 30_000_000;
    private static final int MAX_VERTICAL_COORDINATE = 4_096;

    public enum Priority {
        NORMAL,
        HIGH,
        URGENT
    }

    public enum Reason {
        MISSING,
        WRONG_TOOL,
        WORN
    }

    public enum Status {
        OPEN,
        CLAIMED,
        DELIVERED
    }

    /**
     * Last physically committed location for the one real requested item.
     * NONE is the safe representation for legacy rows and for any malformed
     * trace: it never guesses an owner or a route.
     */
    public enum TraceStage {
        NONE,
        SOURCE,
        COURIER_BAG,
        TARGET
    }

    private final UUID id;
    private final UUID requesterId;
    private final UUID destinationBuildingId;
    private final Profession profession;
    private final EquipmentRequirement requirement;
    private final int count;
    private Priority priority;
    private Reason reason;
    private Status status;
    @Nullable
    private UUID claimedBy;
    private long leaseUntilTick;
    private long deliveredAtTick;
    private final long createdAtTick;

    private TraceStage traceStage = TraceStage.NONE;
    @Nullable
    private UUID traceCourierId;
    @Nullable
    private UUID sourceBuildingId;
    @Nullable
    private BlockPos sourceContainer;
    private int sourceSlot = -1;
    private int sourceCountBefore;
    @Nullable
    private BlockPos targetContainer;
    private int targetCountBefore;
    @Nullable
    private CompoundTag traceFingerprintTag;
    private int movedCount;
    private int deliveredCount;
    private int returnedCount;
    private RequestBlocker traceBlocker = RequestBlocker.NONE;
    private boolean traceQuarantined;
    /**
     * The request no longer represents a worker need, but its one real tool
     * is still proved to be in the claimed Courier bag. The row therefore
     * remains authoritative until an exact physical return is committed.
     */
    private boolean cancelPending;
    /** A pre-trace claimed row loaded from disk: locked rather than duplicated. */
    private boolean legacyClaimLocked;

    public EquipmentRequest(UUID requesterId, UUID destinationBuildingId,
                            Profession profession,
                            EquipmentRequirement requirement, int count,
                            Priority priority, Reason reason) {
        this(UUID.randomUUID(), requesterId, destinationBuildingId, profession,
            requirement, Math.max(1, count), priority, reason, Status.OPEN,
            null, 0L, 0L, -1L);
    }

    /** New requests record their server creation tick; legacy callers remain valid. */
    public EquipmentRequest(UUID requesterId, UUID destinationBuildingId,
                            Profession profession,
                            EquipmentRequirement requirement, int count,
                            Priority priority, Reason reason,
                            long createdAtTick) {
        this(UUID.randomUUID(), requesterId, destinationBuildingId, profession,
            requirement, count, priority, reason, Status.OPEN, null, 0L, 0L,
            Math.max(0L, createdAtTick));
    }

    private EquipmentRequest(UUID id, UUID requesterId,
                             UUID destinationBuildingId, Profession profession,
                             EquipmentRequirement requirement, int count,
                             Priority priority, Reason reason, Status status,
                             @Nullable UUID claimedBy, long leaseUntilTick,
                             long deliveredAtTick, long createdAtTick) {
        this.id = id;
        this.requesterId = requesterId;
        this.destinationBuildingId = destinationBuildingId;
        this.profession = profession;
        this.requirement = requirement;
        this.count = Math.min(MAX_COUNT, Math.max(1, count));
        this.priority = priority;
        this.reason = reason;
        this.status = status;
        this.claimedBy = claimedBy;
        this.leaseUntilTick = Math.max(0L, leaseUntilTick);
        this.deliveredAtTick = Math.max(0L, deliveredAtTick);
        this.createdAtTick = createdAtTick < 0L ? -1L : createdAtTick;
    }

    public UUID id() { return id; }
    public UUID requesterId() { return requesterId; }
    public UUID destinationBuildingId() { return destinationBuildingId; }
    public Profession profession() { return profession; }
    public EquipmentRequirement requirement() { return requirement; }
    public int count() { return count; }
    public Priority priority() { return priority; }
    public Reason reason() { return reason; }
    public Status status() { return status; }
    @Nullable public UUID claimedBy() { return claimedBy; }
    public long leaseUntilTick() { return leaseUntilTick; }
    public long deliveredAtTick() { return deliveredAtTick; }
    public long createdAtTick() { return createdAtTick; }
    public TraceStage traceStage() { return traceStage; }
    @Nullable public UUID traceCourierId() { return traceCourierId; }
    @Nullable public UUID sourceBuildingId() { return sourceBuildingId; }
    @Nullable public BlockPos sourceContainer() { return sourceContainer; }
    public int sourceSlot() { return sourceSlot; }
    public int sourceCountBefore() { return sourceCountBefore; }
    @Nullable public BlockPos targetContainer() { return targetContainer; }
    public int targetCountBefore() { return targetCountBefore; }
    public int movedCount() { return movedCount; }
    public int deliveredCount() { return deliveredCount; }
    public int returnedCount() { return returnedCount; }
    public RequestBlocker traceBlocker() { return traceBlocker; }
    public boolean traceQuarantined() { return traceQuarantined; }
    public boolean cancelPending() { return cancelPending; }
    public boolean hasTransportTrace() { return traceStage != TraceStage.NONE; }

    /** Read-only presentation fact; never asserts stock or physical ownership. */
    public boolean awaitingSource() {
        return status == Status.OPEN && claimedBy == null
            && !traceQuarantined && !legacyClaimLocked && !cancelPending
            && traceStage == TraceStage.NONE && traceCourierId == null
            && sourceBuildingId == null && sourceContainer == null
            && sourceSlot == -1 && sourceCountBefore == 0
            && targetContainer == null && targetCountBefore == 0
            && traceFingerprintTag == null && movedCount == 0
            && deliveredCount == 0 && returnedCount == 0
            && traceBlocker == RequestBlocker.NONE;
    }
    /** Decodes the exact selected stack only against the live world's registries. */
    @Nullable
    public RequestItemFingerprint traceFingerprint(
            HolderLookup.Provider registries) {
        return traceFingerprintTag == null ? null
            : RequestItemFingerprint.readNbt(traceFingerprintTag, registries);
    }

    /** Updates the explanation without creating a duplicate request. */
    public boolean updateNeed(Priority priority, Reason reason) {
        if (cancelPending) {
            return false;
        }
        if (this.priority == priority && this.reason == reason) {
            return false;
        }
        this.priority = priority;
        this.reason = reason;
        return true;
    }

    public boolean claim(UUID courierId, long now, long ttlTicks) {
        if (traceQuarantined || cancelPending || !validUuid(courierId)) {
            return false;
        }
        reopenExpiredClaim(now);
        if (status != Status.OPEN) {
            return status == Status.CLAIMED && courierId.equals(claimedBy);
        }
        status = Status.CLAIMED;
        claimedBy = courierId;
        leaseUntilTick = now + Math.max(1L, ttlTicks);
        deliveredAtTick = 0L;
        return true;
    }

    /**
     * Binds a claim to one loaded, validated route. This method stores proof
     * metadata only; no ItemStack is created, removed or owned here.
     */
    public boolean bindRoute(UUID courierId, UUID sourceBuildingId,
                             BlockPos sourceContainer, int sourceSlot,
                             int sourceCountBefore, BlockPos targetContainer,
                             int targetCountBefore,
                             RequestItemFingerprint fingerprint) {
        if (traceQuarantined || legacyClaimLocked || cancelPending
            || traceStage == TraceStage.COURIER_BAG
            || status != Status.CLAIMED
            || !courierId.equals(claimedBy)
            || count != 1 || !validUuid(sourceBuildingId)
            || !validPos(sourceContainer) || !validPos(targetContainer)
            || sourceSlot < 0 || sourceSlot > 255
            || sourceCountBefore <= 0 || sourceCountBefore > MAX_COUNT
            || targetCountBefore < 0 || targetCountBefore > MAX_COUNT
            || fingerprint == null || fingerprint.count() != 1) {
            return false;
        }
        CompoundTag encoded = fingerprint.writeNbt();
        if (!wellFormedFingerprintTag(encoded)) {
            return false;
        }
        this.traceStage = TraceStage.SOURCE;
        this.traceCourierId = courierId;
        this.sourceBuildingId = sourceBuildingId;
        this.sourceContainer = sourceContainer.immutable();
        this.sourceSlot = sourceSlot;
        this.sourceCountBefore = sourceCountBefore;
        this.targetContainer = targetContainer.immutable();
        this.targetCountBefore = targetCountBefore;
        this.traceFingerprintTag = encoded.copy();
        this.movedCount = 0;
        this.deliveredCount = 0;
        this.returnedCount = 0;
        this.traceBlocker = RequestBlocker.NONE;
        this.traceQuarantined = false;
        return true;
    }

    /** Commits only after the real stack has left the source and entered the bag. */
    public boolean markPickedUp(UUID courierId) {
        if (traceQuarantined || legacyClaimLocked
            || status != Status.CLAIMED
            || !courierId.equals(claimedBy)
            || traceStage != TraceStage.SOURCE || movedCount != 0
            || deliveredCount != 0 || returnedCount != 0) {
            return false;
        }
        traceStage = TraceStage.COURIER_BAG;
        movedCount = 1;
        traceBlocker = RequestBlocker.NONE;
        return true;
    }

    public boolean renew(UUID courierId, long now, long ttlTicks) {
        if (traceQuarantined || status != Status.CLAIMED
            || !courierId.equals(claimedBy)) {
            return false;
        }
        leaseUntilTick = now + Math.max(1L, ttlTicks);
        return true;
    }

    /**
     * Converts an in-transit cancellation into a persisted return order.
     * Nothing is reopened and no second claim can be created while the real
     * stack remains in the Courier bag.
     */
    public boolean requestCancellation() {
        if (cancelPending) {
            return false;
        }
        if (traceQuarantined || status != Status.CLAIMED
            || traceStage != TraceStage.COURIER_BAG || claimedBy == null
            || movedCount != 1 || deliveredCount != 0 || returnedCount != 0) {
            return false;
        }
        cancelPending = true;
        traceBlocker = RequestBlocker.RETURNED_TO_SOURCE;
        return true;
    }

    /** A returned cancellation row is safe to remove from persistent intent. */
    public boolean cancellationReadyForRemoval() {
        return cancelPending && status == Status.OPEN
            && traceStage == TraceStage.SOURCE && movedCount == 1
            && deliveredCount == 0 && returnedCount == 1;
    }

    public boolean release(UUID courierId) {
        if (traceQuarantined || legacyClaimLocked
            || status != Status.CLAIMED
            || !courierId.equals(claimedBy)) {
            return false;
        }
        // Once the real item is in a Courier bag, reopening would authorize a
        // second physical withdrawal. The claim survives until delivery or a
        // proved return to the exact source container.
        if (traceStage == TraceStage.COURIER_BAG) {
            return false;
        }
        reopenClaimOnly();
        return true;
    }

    /** Commits only after the exact target container owns the real item. */
    boolean markDeliveredTraced(UUID courierId, long now) {
        if (cancelPending || status != Status.CLAIMED
            || !courierId.equals(claimedBy)
            || traceStage != TraceStage.COURIER_BAG || movedCount != 1
            || deliveredCount != 0 || returnedCount != 0) {
            return false;
        }
        traceStage = TraceStage.TARGET;
        deliveredCount = 1;
        traceBlocker = RequestBlocker.NONE;
        status = Status.DELIVERED;
        claimedBy = null;
        leaseUntilTick = 0L;
        deliveredAtTick = Math.max(0L, now);
        return true;
    }

    /** Commits only after the bag is empty and the exact source owns the item again. */
    public boolean markReturned(UUID courierId) {
        if (status != Status.CLAIMED || !courierId.equals(claimedBy)
            || traceStage != TraceStage.COURIER_BAG || movedCount != 1
            || deliveredCount != 0 || returnedCount != 0) {
            return false;
        }
        traceStage = TraceStage.SOURCE;
        returnedCount = 1;
        traceBlocker = RequestBlocker.RETURNED_TO_SOURCE;
        reopenClaimOnly();
        return true;
    }

    /**
     * Clears only stale target availability observations. Callers must first
     * prove the complete route, exact physical owner and writable target.
     */
    boolean clearRevalidatedTargetBlocker(UUID courierId) {
        if (cancelPending || status != Status.CLAIMED
            || !courierId.equals(claimedBy)
            || traceStage == TraceStage.NONE
            || traceBlocker != RequestBlocker.TARGET_UNLOADED
                && traceBlocker != RequestBlocker.TARGET_INVALID
                && traceBlocker != RequestBlocker.TARGET_FULL) {
            return false;
        }
        traceBlocker = RequestBlocker.NONE;
        return true;
    }

    public boolean block(UUID courierId, RequestBlocker blocker) {
        if (cancelPending || status != Status.CLAIMED
            || !courierId.equals(claimedBy)
            || traceStage == TraceStage.NONE || blocker == null
            || blocker == RequestBlocker.NONE
            || blocker == RequestBlocker.EQUIPMENT_ADAPTER_LIMITED) {
            return false;
        }
        if (traceBlocker == blocker) {
            return false;
        }
        traceBlocker = blocker;
        return true;
    }

    public boolean reopenExpiredClaim(long now) {
        if (!traceQuarantined && !legacyClaimLocked
            && status == Status.CLAIMED
            && leaseUntilTick <= now) {
            if (traceStage == TraceStage.COURIER_BAG) {
                // Unknown/unloaded is safer than a second authorized pickup.
                return false;
            }
            reopenClaimOnly();
            if (traceStage == TraceStage.SOURCE) {
                traceBlocker = RequestBlocker.LEASE_EXPIRED;
            }
            return true;
        }
        return false;
    }

    public void reopen() {
        if (cancelPending) {
            return;
        }
        reopenClaimOnly();
        clearTrace();
    }

    private void reopenClaimOnly() {
        status = Status.OPEN;
        claimedBy = null;
        leaseUntilTick = 0L;
        deliveredAtTick = 0L;
    }

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putUUID("Requester", requesterId);
        tag.putUUID("Destination", destinationBuildingId);
        tag.putString("Profession", profession.key());
        tag.put("Requirement", requirement.writeNbt());
        tag.putInt("Count", count);
        tag.putString("Priority", priority.name());
        tag.putString("Reason", reason.name());
        tag.putString("Status", status.name());
        if (claimedBy != null) {
            tag.putUUID("ClaimedBy", claimedBy);
        }
        tag.putLong("LeaseUntil", leaseUntilTick);
        tag.putLong("DeliveredAt", deliveredAtTick);
        tag.putLong("CreatedAt", createdAtTick);
        if (traceQuarantined) {
            tag.putBoolean("TraceQuarantined", true);
        }
        if (cancelPending) {
            tag.putBoolean("CancelPending", true);
        }
        if (legacyClaimLocked) {
            tag.putBoolean("LegacyClaimLocked", true);
        }
        if (traceStage != TraceStage.NONE) {
            CompoundTag trace = new CompoundTag();
            trace.putInt("Schema", TRACE_SCHEMA);
            trace.putString("Stage", traceStage.name());
            trace.putUUID("Courier", traceCourierId);
            trace.putUUID("SourceBuilding", sourceBuildingId);
            trace.put("SourceContainer", NbtUtils.writeBlockPos(sourceContainer));
            trace.putInt("SourceSlot", sourceSlot);
            trace.putInt("SourceCountBefore", sourceCountBefore);
            trace.put("TargetContainer", NbtUtils.writeBlockPos(targetContainer));
            trace.putInt("TargetCountBefore", targetCountBefore);
            trace.put("Fingerprint", traceFingerprintTag.copy());
            trace.putInt("Moved", movedCount);
            trace.putInt("Delivered", deliveredCount);
            trace.putInt("Returned", returnedCount);
            trace.putString("Blocker", traceBlocker.name());
            tag.put("Trace", trace);
        }
        return tag;
    }

    @Nullable
    public static EquipmentRequest readNbt(CompoundTag tag) {
        if (!tag.hasUUID("Id") || !tag.hasUUID("Requester")
            || !tag.hasUUID("Destination")
            || !(tag.get("Requirement") instanceof CompoundTag requirementTag)) {
            return null;
        }
        UUID id = tag.getUUID("Id");
        UUID requester = tag.getUUID("Requester");
        UUID destination = tag.getUUID("Destination");
        if (!validUuid(id) || !validUuid(requester)
            || !validUuid(destination)) {
            return null;
        }
        EquipmentRequirement requirement = EquipmentRequirement.readNbt(requirementTag);
        Profession profession = professionByKey(tag.getString("Profession"));
        Priority priority = enumValue(Priority.class, tag.getString("Priority"));
        Reason reason = enumValue(Reason.class, tag.getString("Reason"));
        Status status = enumValue(Status.class, tag.getString("Status"));
        if (requirement == null || profession == Profession.NONE || priority == null
            || reason == null || status == null || tag.getInt("Count") <= 0
            || tag.getInt("Count") > MAX_COUNT) {
            return null;
        }
        UUID claimedBy = tag.hasUUID("ClaimedBy") ? tag.getUUID("ClaimedBy") : null;
        if (status == Status.CLAIMED && !validUuid(claimedBy)) {
            return null;
        }
        if (status != Status.CLAIMED) {
            claimedBy = null;
        }
        EquipmentRequest request = new EquipmentRequest(id, requester,
            destination, profession, requirement,
            tag.getInt("Count"), priority, reason, status, claimedBy,
            tag.getLong("LeaseUntil"), tag.getLong("DeliveredAt"),
            tag.contains("CreatedAt", Tag.TAG_LONG) ? tag.getLong("CreatedAt") : -1L);
        if (tag.get("Trace") instanceof CompoundTag trace) {
            request.restoreTrace(trace);
        } else if (tag.getBoolean("TraceQuarantined")) {
            request.traceQuarantined = true;
        } else if (status == Status.CLAIMED) {
            // Old saves cannot say whether the claimed tool is still at the
            // source or already in a Courier bag. Keep the single claim; do
            // not authorize a second pickup on lease expiry.
            request.legacyClaimLocked = true;
        }
        if (tag.getBoolean("CancelPending")) {
            request.cancelPending = true;
            boolean recoverableBag = request.status == Status.CLAIMED
                && request.traceStage == TraceStage.COURIER_BAG
                && request.movedCount == 1 && request.deliveredCount == 0
                && request.returnedCount == 0
                && validUuid(request.claimedBy)
                && request.claimedBy.equals(request.traceCourierId)
                && !request.traceQuarantined;
            boolean completedReturn = request.status == Status.OPEN
                && request.traceStage == TraceStage.SOURCE
                && request.movedCount == 1 && request.deliveredCount == 0
                && request.returnedCount == 1
                && !request.traceQuarantined;
            if (!recoverableBag && !completedReturn) {
                // Never let a corrupt cancellation row authorize a second
                // withdrawal. It stays visible and locked for manual/native
                // recovery instead of being silently discarded.
                request.traceQuarantined = true;
                request.legacyClaimLocked = true;
            }
        }
        request.legacyClaimLocked |= tag.getBoolean("LegacyClaimLocked");
        return request;
    }

    /** Invalid new trace data degrades to the same honest state as an old save. */
    private void restoreTrace(CompoundTag trace) {
        TraceStage stage = enumValue(TraceStage.class, trace.getString("Stage"));
        RequestBlocker blocker = enumValue(RequestBlocker.class,
            trace.getString("Blocker"));
        BlockPos source = NbtUtils.readBlockPos(trace, "SourceContainer")
            .orElse(null);
        BlockPos target = NbtUtils.readBlockPos(trace, "TargetContainer")
            .orElse(null);
        CompoundTag fingerprint = trace.get("Fingerprint") instanceof CompoundTag value
            ? value : null;
        UUID courier = trace.hasUUID("Courier") ? trace.getUUID("Courier") : null;
        UUID sourceBuilding = trace.hasUUID("SourceBuilding")
            ? trace.getUUID("SourceBuilding") : null;
        int slot = trace.getInt("SourceSlot");
        int sourceBefore = trace.getInt("SourceCountBefore");
        int targetBefore = trace.getInt("TargetCountBefore");
        int moved = trace.getInt("Moved");
        int delivered = trace.getInt("Delivered");
        int returned = trace.getInt("Returned");
        boolean countInvariant = moved >= 0 && moved <= count
            && delivered >= 0 && returned >= 0
            && delivered + returned <= moved;
        boolean stageInvariant = stage == TraceStage.SOURCE
                && (status == Status.OPEN || status == Status.CLAIMED)
                && delivered == 0 && (moved == 0 && returned == 0
                    || moved == 1 && returned == 1)
            || stage == TraceStage.COURIER_BAG && status == Status.CLAIMED
                && moved == 1 && delivered == 0 && returned == 0
            || stage == TraceStage.TARGET && status == Status.DELIVERED
                && moved == 1 && delivered == 1 && returned == 0;
        if (trace.getInt("Schema") != TRACE_SCHEMA || stage == null
            || stage == TraceStage.NONE || blocker == null
            || !validUuid(courier) || !validUuid(sourceBuilding)
            || !validPos(source) || !validPos(target)
            || slot < 0 || slot > 255 || sourceBefore <= 0
            || sourceBefore > MAX_COUNT || targetBefore < 0
            || targetBefore > MAX_COUNT || !wellFormedFingerprintTag(fingerprint)
            || !countInvariant || !stageInvariant
            || status == Status.CLAIMED && !courier.equals(claimedBy)) {
            clearTrace();
            traceQuarantined = true;
            return;
        }
        traceStage = stage;
        traceCourierId = courier;
        sourceBuildingId = sourceBuilding;
        sourceContainer = source.immutable();
        sourceSlot = slot;
        sourceCountBefore = sourceBefore;
        targetContainer = target.immutable();
        targetCountBefore = targetBefore;
        traceFingerprintTag = fingerprint.copy();
        movedCount = moved;
        deliveredCount = delivered;
        returnedCount = returned;
        traceBlocker = blocker;
        traceQuarantined = false;
        legacyClaimLocked = false;
    }

    private void clearTrace() {
        traceStage = TraceStage.NONE;
        traceCourierId = null;
        sourceBuildingId = null;
        sourceContainer = null;
        sourceSlot = -1;
        sourceCountBefore = 0;
        targetContainer = null;
        targetCountBefore = 0;
        traceFingerprintTag = null;
        movedCount = 0;
        deliveredCount = 0;
        returnedCount = 0;
        traceBlocker = RequestBlocker.NONE;
        traceQuarantined = false;
        cancelPending = false;
        legacyClaimLocked = false;
    }

    private static boolean wellFormedFingerprintTag(@Nullable CompoundTag tag) {
        if (tag == null || tag.toString().length() > MAX_TRACE_NBT_CHARS
            || !tag.contains("Item", Tag.TAG_STRING)
            || !(tag.get("Prototype") instanceof CompoundTag)
            || !tag.contains("Digest", Tag.TAG_STRING)
            || tag.getInt("Count") != 1
            || net.minecraft.resources.ResourceLocation.tryParse(
                tag.getString("Item")) == null) {
            return false;
        }
        String digest = tag.getString("Digest");
        if (digest.length() != 64) {
            return false;
        }
        for (int i = 0; i < digest.length(); i++) {
            char c = digest.charAt(i);
            if (!Character.isDigit(c) && (c < 'a' || c > 'f')) {
                return false;
            }
        }
        return true;
    }

    private static boolean validPos(@Nullable BlockPos pos) {
        return pos != null
            && Math.abs((long) pos.getX()) <= MAX_HORIZONTAL_COORDINATE
            && Math.abs((long) pos.getZ()) <= MAX_HORIZONTAL_COORDINATE
            && Math.abs((long) pos.getY()) <= MAX_VERTICAL_COORDINATE;
    }

    private static boolean validUuid(@Nullable UUID id) {
        return id != null && (id.getMostSignificantBits() != 0L
            || id.getLeastSignificantBits() != 0L);
    }

    private static Profession professionByKey(String key) {
        for (Profession profession : Profession.values()) {
            if (profession.key().equals(key)) {
                return profession;
            }
        }
        return Profession.NONE;
    }

    @Nullable
    private static <E extends Enum<E>> E enumValue(Class<E> type, String name) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
