package com.hearthstead.settlement.state;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;

/**
 * Bounded persisted index of exact per-Guard orders for one settlement.
 *
 * <p>Legacy v7 state is kept as pending evidence until runtime can prove one
 * and only one eligible persisted Guard is also the one live Guard. Multiple
 * candidates or malformed/duplicate current entries quarantine the book. No
 * read path creates an order and no quarantine can be cleared by gameplay.
 */
public final class GuardOrderBook {
    public static final int DATA_VERSION = 1;
    public static final int MAX_ORDERS = 256;

    public enum LegacyStatus {
        NONE(0, "none"),
        PENDING(1, "pending"),
        RESOLVED(2, "resolved"),
        QUARANTINED(3, "quarantined");

        private final int wireId;
        private final String id;

        LegacyStatus(int wireId, String id) {
            this.wireId = wireId;
            this.id = id;
        }

        int wireId() { return wireId; }
        String id() { return id; }

        @Nullable
        static LegacyStatus decode(int wireId, String id) {
            for (LegacyStatus status : values()) {
                if (status.wireId == wireId && status.id.equals(id)) {
                    return status;
                }
            }
            return null;
        }
    }

    public enum ReconcileResult {
        NOT_PENDING,
        WAITING_FOR_LIVE_GUARD,
        MIGRATED,
        QUARANTINED_AMBIGUOUS,
        QUARANTINED_INVALID
    }

    /** Exact building identity proven together with one persisted Guard. */
    public record Candidate(UUID guardId, UUID buildingId) {
        public Candidate {
            if (guardId == null || buildingId == null) {
                throw new IllegalArgumentException("Guard migration candidate identity");
            }
        }
    }

    private final Map<UUID, GuardOrder> orders = new LinkedHashMap<>();
    private LegacyStatus legacyStatus = LegacyStatus.NONE;
    private GuardOrder pendingLegacy;
    private boolean quarantined;
    private String quarantineReason = "none";

    public static GuardOrderBook fresh() {
        return new GuardOrderBook();
    }

    public static GuardOrderBook pendingLegacy(@Nullable GuardOrder legacy) {
        GuardOrderBook book = new GuardOrderBook();
        if (legacy != null && legacy.hasLegacyContent()) {
            book.legacyStatus = LegacyStatus.PENDING;
            book.pendingLegacy = legacy;
        }
        return book;
    }

    public static GuardOrderBook quarantined(String reason) {
        GuardOrderBook book = new GuardOrderBook();
        book.quarantine(reason);
        return book;
    }

    public boolean quarantined() { return quarantined; }
    public String quarantineReason() { return quarantineReason; }
    public LegacyStatus legacyStatus() { return legacyStatus; }

    public int size() { return orders.size(); }

    /** Read-only; never inserts an empty order. */
    public Optional<GuardOrder> order(UUID guardId) {
        return quarantined || guardId == null ? Optional.empty()
            : Optional.ofNullable(orders.get(guardId));
    }

    /** Immutable bounded view for diagnostics and exact unit tests. */
    public Collection<GuardOrder> orders() {
        return List.copyOf(orders.values());
    }

    /**
     * Mutation-only creation seam. Pending or quarantined legacy state cannot
     * be bypassed by authoring a new order on top of unresolved authority.
     */
    public Optional<GuardOrder> orderForMutation(UUID settlementId,
                                                 UUID guardId,
                                                 ResourceLocation dimension) {
        if (quarantined || legacyStatus == LegacyStatus.PENDING
            || settlementId == null || guardId == null || dimension == null) {
            return Optional.empty();
        }
        GuardOrder existing = orders.get(guardId);
        if (existing != null) {
            return existing.ownedBy(settlementId, guardId, dimension)
                ? Optional.of(existing) : Optional.empty();
        }
        if (orders.size() >= MAX_ORDERS) {
            // A valid full book is not corrupt. Refuse this new authority
            // without erasing or quarantining the existing Guards' orders.
            return Optional.empty();
        }
        GuardOrder created = GuardOrder.bound(settlementId, guardId, dimension);
        orders.put(guardId, created);
        return Optional.of(created);
    }

    /** Removes an unmodified empty order if a terminal mutation was refused. */
    public boolean discardEmpty(UUID guardId, GuardOrder expected) {
        if (guardId == null || expected == null || expected.revision() != 0
            || expected.mode() != GuardOrder.Mode.NONE
            || !expected.patrolPoints().isEmpty()) {
            return false;
        }
        return orders.remove(guardId, expected);
    }

    /** Terminal member cleanup; never creates or repairs authority. */
    public boolean clear(UUID guardId) {
        return !quarantined && guardId != null
            && orders.remove(guardId) != null;
    }

    /**
     * One-shot v7 reconciliation. The persisted candidates must be exact and
     * unique; the same candidate must also be the only live eligible Guard.
     * Zero live candidates stays pending because chunk loading is not proof of
     * absence. Multiple persisted or live candidates quarantine ambiguity.
     */
    public ReconcileResult reconcileLegacy(UUID settlementId,
                                           ResourceLocation dimension,
                                           List<Candidate> persisted,
                                           List<UUID> liveGuardIds,
                                           long now) {
        if (legacyStatus != LegacyStatus.PENDING) {
            return ReconcileResult.NOT_PENDING;
        }
        if (quarantined || pendingLegacy == null || settlementId == null
            || dimension == null || persisted == null || liveGuardIds == null) {
            quarantine("legacy_invalid_context");
            return ReconcileResult.QUARANTINED_INVALID;
        }
        if (persisted.stream().anyMatch(Objects::isNull)
            || liveGuardIds.stream().anyMatch(Objects::isNull)) {
            quarantine("legacy_null_guard_identity");
            return ReconcileResult.QUARANTINED_INVALID;
        }
        List<Candidate> uniquePersisted = persisted.stream().distinct().toList();
        List<UUID> uniqueLive = liveGuardIds.stream().distinct().toList();
        if (uniquePersisted.size() > 1 || uniqueLive.size() > 1) {
            quarantine("legacy_ambiguous_guards");
            return ReconcileResult.QUARANTINED_AMBIGUOUS;
        }
        if (uniquePersisted.size() != persisted.size()
            || uniqueLive.size() != liveGuardIds.size()) {
            quarantine("legacy_duplicate_guard_identity");
            return ReconcileResult.QUARANTINED_AMBIGUOUS;
        }
        if (uniquePersisted.isEmpty()) {
            quarantine("legacy_no_persisted_guard");
            return ReconcileResult.QUARANTINED_INVALID;
        }
        if (uniqueLive.isEmpty()) {
            return ReconcileResult.WAITING_FOR_LIVE_GUARD;
        }
        Candidate candidate = uniquePersisted.getFirst();
        if (!candidate.guardId().equals(uniqueLive.getFirst())
            || orders.containsKey(candidate.guardId())) {
            quarantine("legacy_live_guard_mismatch");
            return ReconcileResult.QUARANTINED_AMBIGUOUS;
        }
        GuardOrder migrated;
        try {
            migrated = pendingLegacy.migrateTo(settlementId,
                candidate.guardId(), dimension, candidate.buildingId(), now);
        } catch (IllegalArgumentException invalid) {
            quarantine("legacy_migration_invalid");
            return ReconcileResult.QUARANTINED_INVALID;
        }
        orders.put(candidate.guardId(), migrated);
        pendingLegacy = null;
        legacyStatus = LegacyStatus.RESOLVED;
        return ReconcileResult.MIGRATED;
    }

    /** Strict server reconciliation seam for malformed persisted roster data. */
    public ReconcileResult quarantinePendingLegacy(String reason) {
        if (legacyStatus != LegacyStatus.PENDING) {
            return ReconcileResult.NOT_PENDING;
        }
        quarantine(reason);
        return ReconcileResult.QUARANTINED_INVALID;
    }

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", DATA_VERSION);
        tag.putInt("LegacyStatusWireId", legacyStatus.wireId());
        tag.putString("LegacyStatus", legacyStatus.id());
        tag.putBoolean("Quarantined", quarantined);
        tag.putString("QuarantineReason", quarantineReason);
        if (pendingLegacy != null) {
            tag.put("PendingLegacy", pendingLegacy.writeNbt());
        }
        ListTag list = new ListTag();
        for (GuardOrder order : orders.values()) {
            list.add(order.writeNbt());
        }
        tag.put("Orders", list);
        return tag;
    }

    /** Strict current decoder. Any duplicate/malformed entry quarantines all. */
    public static GuardOrderBook readNbt(@Nullable CompoundTag tag) {
        if (tag == null || !tag.contains("DataVersion", Tag.TAG_INT)
            || tag.getInt("DataVersion") != DATA_VERSION
            || !tag.contains("LegacyStatusWireId", Tag.TAG_INT)
            || !tag.contains("LegacyStatus", Tag.TAG_STRING)
            || !tag.contains("Quarantined", Tag.TAG_BYTE)
            || !tag.contains("QuarantineReason", Tag.TAG_STRING)
            || !tag.contains("Orders", Tag.TAG_LIST)) {
            return quarantined("malformed_header");
        }
        LegacyStatus status = LegacyStatus.decode(
            tag.getInt("LegacyStatusWireId"), tag.getString("LegacyStatus"));
        if (status == null) return quarantined("unknown_legacy_status");
        GuardOrderBook book = new GuardOrderBook();
        book.legacyStatus = status;
        book.quarantined = tag.getBoolean("Quarantined");
        book.quarantineReason = tag.getString("QuarantineReason");
        if ((status == LegacyStatus.QUARANTINED) != book.quarantined
            || book.quarantined && (book.quarantineReason.isBlank()
                || "none".equals(book.quarantineReason))
            || !book.quarantined
                && !"none".equals(book.quarantineReason)) {
            return quarantined("legacy_status_mismatch");
        }
        Tag rawPending = tag.get("PendingLegacy");
        if (status == LegacyStatus.PENDING) {
            if (!(rawPending instanceof CompoundTag pendingTag)) {
                return quarantined("missing_pending_legacy");
            }
            GuardOrder legacy = GuardOrder.readNbt(pendingTag);
            if (legacy.bound() || !legacy.hasLegacyContent()) {
                return quarantined("invalid_pending_legacy");
            }
            book.pendingLegacy = legacy;
        } else if (rawPending != null) {
            return quarantined("unexpected_pending_legacy");
        }
        Tag rawOrders = tag.get("Orders");
        if (!(rawOrders instanceof ListTag list)
            || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) {
            return quarantined("malformed_order_list");
        }
        if (list.size() > MAX_ORDERS) return quarantined("order_capacity");
        for (int i = 0; i < list.size(); i++) {
            GuardOrder order = GuardOrder.readBoundNbt(list.getCompound(i));
            if (order == null || order.guardId().isEmpty()) {
                return quarantined("malformed_order");
            }
            UUID guardId = order.guardId().orElseThrow();
            if (book.orders.putIfAbsent(guardId, order) != null) {
                return quarantined("duplicate_guard_id");
            }
        }
        if (status == LegacyStatus.PENDING && !book.orders.isEmpty()) {
            return quarantined("pending_with_current_orders");
        }
        if (status == LegacyStatus.RESOLVED && book.orders.isEmpty()) {
            return quarantined("resolved_without_order");
        }
        if (book.quarantined) {
            book.orders.clear();
            book.pendingLegacy = null;
            book.legacyStatus = LegacyStatus.QUARANTINED;
        }
        return book;
    }

    private void quarantine(String reason) {
        orders.clear();
        pendingLegacy = null;
        quarantined = true;
        legacyStatus = LegacyStatus.QUARANTINED;
        quarantineReason = reason == null || reason.isBlank()
            || "none".equals(reason)
            ? "unknown" : reason;
    }
}
