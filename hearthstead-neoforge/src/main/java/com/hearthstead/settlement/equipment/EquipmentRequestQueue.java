package com.hearthstead.settlement.equipment;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Persistent, server-owned delivery order for one settlement.
 *
 * <p>The queue stores request identities only; the request rows and every
 * physical item remain owned by workplaces, chests, Courier bags and worker
 * hands. A monotonic persisted revision makes one drag commit atomic and
 * makes delayed/replayed client packets harmless.
 */
public final class EquipmentRequestQueue {

    /** Corrupt NBT may not allocate an unbounded ordering ledger. */
    public static final int MAX_ORDERED_REQUESTS = 4_096;
    private static final UUID NO_REQUEST = new UUID(0L, 0L);

    public enum MoveResult {
        APPLIED,
        STALE,
        INVALID
    }

    private List<UUID> order = new ArrayList<>();
    private long revision = 1L;

    public long revision() {
        return revision;
    }

    /** Bumps the exact snapshot revision after a visible row field changes. */
    public void noteRowChanged() {
        bumpRevision();
    }

    /**
     * Reconciles membership without disturbing an existing manual order.
     * New automatic requests enter by urgency, after existing rows of equal
     * or greater priority. Legacy/unordered rows use UUID as a stable tie.
     */
    public boolean reconcile(List<EquipmentRequest> activeRequests) {
        Map<UUID, EquipmentRequest> active = uniqueActive(activeRequests);
        List<UUID> rebuilt = retainedOrder(active);
        List<EquipmentRequest> missing = missing(active, rebuilt,
            MAX_ORDERED_REQUESTS - rebuilt.size());
        for (EquipmentRequest request : missing) {
            insertByDefaultPolicy(rebuilt, active, request);
        }
        if (rebuilt.equals(order)) {
            return false;
        }
        order = rebuilt;
        bumpRevision();
        return true;
    }

    /**
     * Returns the exact order the Courier consumes. This method is pure: the
     * level-aware EquipmentRequests entry point performs reconciliation and
     * persistence before production callers reach it.
     */
    public List<EquipmentRequest> ordered(List<EquipmentRequest> activeRequests) {
        Map<UUID, EquipmentRequest> active = uniqueActive(activeRequests);
        List<UUID> projected = retainedOrder(active);
        for (EquipmentRequest request : missing(active, projected,
                Integer.MAX_VALUE)) {
            insertByDefaultPolicy(projected, active, request);
        }
        List<EquipmentRequest> result = new ArrayList<>(projected.size());
        for (UUID id : projected) {
            EquipmentRequest request = active.get(id);
            if (request != null) {
                result.add(request);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Moves one active request immediately before another active request;
     * {@code null}/nil means the end. The expected revision must be the exact
     * snapshot the drag began from, so stale and replayed drops cannot win.
     */
    public MoveResult move(List<EquipmentRequest> activeRequests,
                           UUID movedRequestId,
                           @Nullable UUID beforeRequestId,
                           long expectedRevision) {
        if (expectedRevision != revision) {
            return MoveResult.STALE;
        }
        Map<UUID, EquipmentRequest> active = uniqueActive(activeRequests);
        if (active.size() > MAX_ORDERED_REQUESTS
            || active.size() != activeRequests.size()
            || !sameMembership(active.keySet(), order)
            || movedRequestId == null || !active.containsKey(movedRequestId)) {
            return MoveResult.INVALID;
        }
        UUID before = beforeRequestId == null || NO_REQUEST.equals(beforeRequestId)
            ? null : beforeRequestId;
        if (before != null && (!active.containsKey(before)
            || before.equals(movedRequestId))) {
            return MoveResult.INVALID;
        }

        List<UUID> moved = new ArrayList<>(order);
        if (!moved.remove(movedRequestId)) {
            return MoveResult.INVALID;
        }
        int destination = before == null ? moved.size() : moved.indexOf(before);
        if (destination < 0) {
            return MoveResult.INVALID;
        }
        moved.add(destination, movedRequestId);
        if (moved.equals(order)) {
            return MoveResult.INVALID;
        }
        order = moved;
        bumpRevision();
        return MoveResult.APPLIED;
    }

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Revision", revision);
        ListTag rows = new ListTag();
        int limit = Math.min(order.size(), MAX_ORDERED_REQUESTS);
        for (int i = 0; i < limit; i++) {
            CompoundTag row = new CompoundTag();
            row.putUUID("Id", order.get(i));
            rows.add(row);
        }
        tag.put("Order", rows);
        return tag;
    }

    public static EquipmentRequestQueue readNbt(CompoundTag tag) {
        EquipmentRequestQueue queue = new EquipmentRequestQueue();
        queue.revision = Math.max(1L, tag.getLong("Revision"));
        ListTag rows = tag.getList("Order", Tag.TAG_COMPOUND);
        Set<UUID> seen = new HashSet<>();
        int limit = Math.min(rows.size(), MAX_ORDERED_REQUESTS);
        for (int i = 0; i < limit; i++) {
            CompoundTag row = rows.getCompound(i);
            if (!row.hasUUID("Id")) {
                continue;
            }
            UUID id = row.getUUID("Id");
            if (!NO_REQUEST.equals(id) && seen.add(id)) {
                queue.order.add(id);
            }
        }
        return queue;
    }

    private List<UUID> retainedOrder(Map<UUID, EquipmentRequest> active) {
        List<UUID> retained = new ArrayList<>(Math.min(active.size(),
            MAX_ORDERED_REQUESTS));
        Set<UUID> seen = new HashSet<>();
        for (UUID id : order) {
            if (retained.size() >= MAX_ORDERED_REQUESTS) {
                break;
            }
            if (active.containsKey(id) && seen.add(id)) {
                retained.add(id);
            }
        }
        return retained;
    }

    private static List<EquipmentRequest> missing(
            Map<UUID, EquipmentRequest> active, List<UUID> present,
            int maximum) {
        Set<UUID> known = new HashSet<>(present);
        List<EquipmentRequest> missing = new ArrayList<>();
        for (EquipmentRequest request : active.values()) {
            if (missing.size() >= maximum) {
                break;
            }
            if (known.add(request.id())) {
                missing.add(request);
            }
        }
        missing.sort(Comparator
            .comparing(EquipmentRequest::priority).reversed()
            .thenComparing(request -> request.id().toString()));
        return missing;
    }

    private static void insertByDefaultPolicy(
            List<UUID> projected, Map<UUID, EquipmentRequest> active,
            EquipmentRequest inserted) {
        int index = 0;
        while (index < projected.size()) {
            EquipmentRequest existing = active.get(projected.get(index));
            if (existing != null
                && existing.priority().ordinal() < inserted.priority().ordinal()) {
                break;
            }
            index++;
        }
        projected.add(index, inserted.id());
    }

    private static Map<UUID, EquipmentRequest> uniqueActive(
            List<EquipmentRequest> activeRequests) {
        Map<UUID, EquipmentRequest> unique = new LinkedHashMap<>();
        if (activeRequests == null) {
            return unique;
        }
        for (EquipmentRequest request : activeRequests) {
            if (request != null) {
                unique.putIfAbsent(request.id(), request);
            }
        }
        return unique;
    }

    private static boolean sameMembership(Set<UUID> active, List<UUID> ordered) {
        return active.size() == ordered.size()
            && active.equals(new HashSet<>(ordered));
    }

    private void bumpRevision() {
        revision = revision == Long.MAX_VALUE ? 1L : revision + 1L;
    }
}
