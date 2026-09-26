package com.hearthstead.settlement.request;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.equipment.EquipmentRequest;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable, bounded server snapshot shared by future Hearth/Warehouse/Tingbok
 * views. It contains no action endpoint and never changes request or inventory
 * state while it is built.
 *
 * <p>Old/unclaimed equipment rows remain deliberately labelled as limited
 * adapters. Once a Courier has committed an exact physical route, equipment
 * rows use the same source/bag/target proof rules as typed transport rows.
 * No owner is shown merely because a phase string says it should exist.
 */
public record RequestLedgerSnapshot(UUID settlementId, long generatedTick,
                                    long typedRevision,
                                    long equipmentRevision,
                                    boolean quarantined,
                                    String quarantineReason,
                                    boolean truncated, List<Row> rows) {
    public static final int MAX_ROWS = 512;
    public static final long UNKNOWN_AGE = -1L;

    public RequestLedgerSnapshot {
        settlementId = requireUuid(settlementId);
        generatedTick = Math.max(0L, generatedTick);
        typedRevision = Math.max(0L, typedRevision);
        equipmentRevision = Math.max(0L, equipmentRevision);
        quarantineReason = boundedToken(quarantineReason, "none");
        rows = List.copyOf(rows == null ? List.of() : rows);
        if (rows.size() > MAX_ROWS) {
            throw new IllegalArgumentException("request snapshot cardinality");
        }
    }

    /** Physical owner implied by a committed state, never a second item store. */
    public enum PhysicalOwner {
        SOURCE,
        COURIER_BAG,
        TARGET,
        UNKNOWN
    }

    public record Row(UUID requestId, RequestType type, RequestState state,
                      RequestPriority priority, UUID requesterId,
                      Profession profession, @Nullable UUID sourceBuildingId,
                      @Nullable BlockPos pickup, @Nullable UUID targetBuildingId,
                      @Nullable BlockPos target, @Nullable UUID courierId,
                      String itemId, String fingerprint, int requestedCount,
                      int movedCount, int deliveredCount, long ageTicks,
                      int distanceBlocks, boolean stockAvailable,
                      RequestBlocker blocker, PhysicalOwner physicalOwner,
                      boolean targetExact, boolean fullTransportTrace,
                      boolean equipmentAdapter,
                      @Nullable EquipmentRequest.Reason equipmentReason,
                      boolean awaitingSource) {
        public Row {
            requestId = requireUuid(requestId);
            type = java.util.Objects.requireNonNull(type, "type");
            state = java.util.Objects.requireNonNull(state, "state");
            priority = java.util.Objects.requireNonNull(priority, "priority");
            requesterId = requireUuid(requesterId);
            profession = java.util.Objects.requireNonNull(profession,
                "profession");
            sourceBuildingId = normalizeUuid(sourceBuildingId);
            pickup = pickup == null ? null : pickup.immutable();
            targetBuildingId = normalizeUuid(targetBuildingId);
            target = target == null ? null : target.immutable();
            courierId = normalizeUuid(courierId);
            itemId = boundedToken(itemId, "none");
            fingerprint = boundedToken(fingerprint, "none");
            blocker = java.util.Objects.requireNonNull(blocker, "blocker");
            physicalOwner = java.util.Objects.requireNonNull(physicalOwner,
                "physicalOwner");
            if (requestedCount <= 0 || requestedCount > 64
                || movedCount < 0 || movedCount > requestedCount
                || deliveredCount < 0 || deliveredCount > movedCount
                || ageTicks < UNKNOWN_AGE || distanceBlocks < -1) {
                throw new IllegalArgumentException("request snapshot row bounds");
            }
            if (equipmentAdapter && (fullTransportTrace || targetExact
                    || !"preferred_item_only".equals(fingerprint))) {
                throw new IllegalArgumentException(
                    "equipment adapter cannot claim output trace");
            }
        }
    }

    /** Returns empty off-thread or for a detached settlement identity. */
    public static Optional<RequestLedgerSnapshot> create(ServerLevel level,
                                                          Settlement settlement) {
        if (level == null || settlement == null || level.getServer() == null
            || !level.getServer().isSameThread()
            || SettlementManager.byId(level, settlement.id) != settlement) {
            return Optional.empty();
        }
        RequestLedgerSavedData saved = RequestLedgerSavedData.get(level);
        RequestLedger ledger = saved.existing(settlement.id);
        List<Row> rows = new ArrayList<>(MAX_ROWS);
        boolean truncated = false;

        if (ledger != null) {
            List<RequestRecord> active = new ArrayList<>(ledger.active());
            active.sort(Comparator
                .comparing(RequestRecord::priority).reversed()
                .thenComparingLong(RequestRecord::createdAt)
                .thenComparing(row -> row.id().toString()));
            for (RequestRecord record : active) {
                if (rows.size() >= MAX_ROWS) {
                    truncated = true;
                    break;
                }
                rows.add(fromTyped(level, record));
            }
        }

        for (EquipmentRequest request : EquipmentRequests.list(settlement)) {
            if (rows.size() >= MAX_ROWS) {
                truncated = true;
                break;
            }
            rows.add(fromEquipment(level, settlement, request));
        }

        // Logistics M1: crafting orders and "needs you" rows, in the same
        // Row shape the Tasks page already renders (type craft_order).
        if (rows.size() < MAX_ROWS) {
            rows.addAll(CraftingOrderService.snapshotRows(level, settlement,
                MAX_ROWS - rows.size()));
        }

        if (ledger != null && rows.size() < MAX_ROWS) {
            List<RequestRecord> terminal = new ArrayList<>(ledger.terminalHistory());
            terminal.sort(Comparator.comparingLong(RequestRecord::updatedAt)
                .reversed().thenComparing(row -> row.id().toString()));
            for (RequestRecord record : terminal) {
                if (rows.size() >= MAX_ROWS) {
                    truncated = true;
                    break;
                }
                rows.add(fromTyped(level, record));
            }
        } else if (ledger != null && !ledger.terminalHistory().isEmpty()) {
            truncated = true;
        }

        return Optional.of(new RequestLedgerSnapshot(settlement.id,
            level.getGameTime(), ledger == null ? 0L : ledger.revision(),
            settlement.equipmentRequestQueue.revision(),
            saved.rootQuarantined() || ledger != null && ledger.quarantined(),
            saved.rootQuarantined() ? saved.quarantineReason()
                : ledger == null ? "none" : ledger.quarantineReason(),
            truncated, rows));
    }

    private static Row fromTyped(ServerLevel level, RequestRecord record) {
        RequestState effective = record.effectiveState();
        PhysicalOwner owner = switch (effective) {
            case OPEN, RESERVED -> PhysicalOwner.SOURCE;
            case PICKUP -> pickupOwner(level, record);
            case IN_TRANSIT -> PhysicalOwner.COURIER_BAG;
            case DELIVERED, SATISFIED -> PhysicalOwner.TARGET;
            default -> PhysicalOwner.UNKNOWN;
        };
        boolean available = switch (owner) {
            case SOURCE -> sourceStillOwns(level, record);
            case COURIER_BAG -> courierStillOwns(level, record);
            case TARGET -> targetOwnsDelivered(level, record);
            case UNKNOWN -> false;
        };
        RequestBlocker blocker = record.blocker();
        if (blocker == RequestBlocker.NONE && !available
            && !record.state().terminal()) {
            blocker = observationalBlocker(level, record, owner);
        }
        return new Row(record.id(), record.type(), record.state(),
            record.priority(), record.requesterId(), record.requesterProfession(),
            record.sourceBuildingId(), record.sourceContainer(),
            record.targetBuildingId(), record.targetContainer(),
            record.courierId(), record.fingerprint().itemId().toString(),
            record.fingerprint().digest(), record.fingerprint().count(),
            record.movedCount(), record.deliveredCount(),
            Math.max(0L, level.getGameTime() - record.createdAt()),
            distance(record.sourceContainer(), record.targetContainer()),
            available, blocker, owner, true, record.hasFullTransportTrace(),
            false, null, false);
    }

    private static Row fromEquipment(ServerLevel level, Settlement settlement,
                                     EquipmentRequest request) {
        RequestItemFingerprint fingerprint = request.traceFingerprint(
            level.registryAccess());
        Building source = buildingById(settlement, request.sourceBuildingId());
        Building target = buildingById(settlement,
            request.destinationBuildingId());
        boolean exactRoute = request.hasTransportTrace() && fingerprint != null
            && source != null && source.type == BuildingType.WAREHOUSE
            && target != null && request.sourceContainer() != null
            && request.targetContainer() != null
            && source.contains(request.sourceContainer())
            && target.contains(request.targetContainer());
        if (!exactRoute) {
            return legacyEquipmentRow(level, request, target);
        }

        RequestState state = switch (request.status()) {
            case OPEN -> RequestState.OPEN;
            case CLAIMED -> RequestState.RESERVED;
            case DELIVERED -> RequestState.DELIVERED;
        };
        if (request.traceStage() == EquipmentRequest.TraceStage.COURIER_BAG) {
            state = RequestState.IN_TRANSIT;
        }
        RequestPriority priority = switch (request.priority()) {
            case NORMAL -> RequestPriority.NORMAL;
            case HIGH -> RequestPriority.HIGH;
            case URGENT -> RequestPriority.URGENT;
        };
        PhysicalOwner owner = equipmentOwner(level, settlement, request,
            fingerprint);
        boolean available = owner != PhysicalOwner.UNKNOWN;
        RequestBlocker blocker = request.traceBlocker();
        if (blocker == RequestBlocker.NONE) {
            blocker = observationalEquipmentBlocker(level, settlement,
                request, fingerprint, owner);
        }
        UUID courier = request.status() == EquipmentRequest.Status.CLAIMED
            ? request.claimedBy()
            : request.traceStage() == EquipmentRequest.TraceStage.TARGET
                ? request.traceCourierId() : null;
        return new Row(request.id(), RequestType.EQUIPMENT, state, priority,
            request.requesterId(), request.profession(),
            request.sourceBuildingId(), request.sourceContainer(),
            request.destinationBuildingId(), request.targetContainer(), courier,
            fingerprint.itemId().toString(), fingerprint.digest(),
            request.count(), request.movedCount(), request.deliveredCount(),
            request.createdAtTick() < 0L ? UNKNOWN_AGE
                : Math.max(0L, level.getGameTime() - request.createdAtTick()),
            distance(request.sourceContainer(), request.targetContainer()),
            available, blocker, owner, true, true, false, request.reason(), false);
    }

    private static Row legacyEquipmentRow(ServerLevel level,
                                          EquipmentRequest request,
                                          @Nullable Building target) {
        RequestState state = switch (request.status()) {
            case OPEN -> RequestState.OPEN;
            case CLAIMED -> RequestState.RESERVED;
            case DELIVERED -> RequestState.DELIVERED;
        };
        RequestPriority priority = switch (request.priority()) {
            case NORMAL -> RequestPriority.NORMAL;
            case HIGH -> RequestPriority.HIGH;
            case URGENT -> RequestPriority.URGENT;
        };
        return new Row(request.id(), RequestType.EQUIPMENT, state, priority,
            request.requesterId(), request.profession(), null, null,
            request.destinationBuildingId(),
            target == null ? null : target.plaquePos, request.claimedBy(),
            BuiltInRegistries.ITEM.getKey(
                request.requirement().preferredItem()).toString(),
            "preferred_item_only", request.count(), 0, 0,
            request.createdAtTick() < 0L ? UNKNOWN_AGE
                : Math.max(0L, level.getGameTime() - request.createdAtTick()),
            -1, false, request.traceQuarantined()
                ? RequestBlocker.MALFORMED
                : RequestBlocker.EQUIPMENT_ADAPTER_LIMITED,
            PhysicalOwner.UNKNOWN,
            false, false, true, request.reason(), request.awaitingSource());
    }

    private static PhysicalOwner equipmentOwner(
            ServerLevel level, Settlement settlement, EquipmentRequest request,
            RequestItemFingerprint fingerprint) {
        return switch (request.traceStage()) {
            case SOURCE -> equipmentSourceOwns(level, request, fingerprint)
                ? PhysicalOwner.SOURCE : PhysicalOwner.UNKNOWN;
            case COURIER_BAG -> equipmentCourierOwns(level, settlement,
                request, fingerprint) ? PhysicalOwner.COURIER_BAG
                    : PhysicalOwner.UNKNOWN;
            case TARGET -> equipmentTargetOwns(level, request, fingerprint)
                ? PhysicalOwner.TARGET : PhysicalOwner.UNKNOWN;
            case NONE -> PhysicalOwner.UNKNOWN;
        };
    }

    private static RequestBlocker observationalEquipmentBlocker(
            ServerLevel level, Settlement settlement, EquipmentRequest request,
            RequestItemFingerprint fingerprint, PhysicalOwner owner) {
        if (owner == PhysicalOwner.UNKNOWN) {
            return switch (request.traceStage()) {
                case SOURCE -> !level.hasChunkAt(request.sourceContainer())
                    ? RequestBlocker.SOURCE_UNLOADED
                    : containerAtLoaded(level, request.sourceContainer()) == null
                        ? RequestBlocker.SOURCE_INVALID
                        : RequestBlocker.NO_STOCK;
                case COURIER_BAG -> equipmentCourierBlocker(level, settlement,
                    request);
                case TARGET -> !level.hasChunkAt(request.targetContainer())
                    ? RequestBlocker.TARGET_UNLOADED
                    : containerAtLoaded(level, request.targetContainer()) == null
                        ? RequestBlocker.TARGET_INVALID
                        : RequestBlocker.FINGERPRINT_MISMATCH;
                case NONE -> RequestBlocker.EQUIPMENT_ADAPTER_LIMITED;
            };
        }
        if (request.traceStage() != EquipmentRequest.TraceStage.TARGET) {
            BlockPos target = request.targetContainer();
            if (!level.hasChunkAt(target)) {
                return RequestBlocker.TARGET_UNLOADED;
            }
            Container container = containerAtLoaded(level, target);
            if (container == null) {
                return RequestBlocker.TARGET_INVALID;
            }
            ItemStack exact = fingerprint.prototype(level.registryAccess());
            if (exact.isEmpty() || !hasRoomForExact(container, exact)) {
                return RequestBlocker.TARGET_FULL;
            }
        }
        return RequestBlocker.NONE;
    }

    private static boolean equipmentSourceOwns(
            ServerLevel level, EquipmentRequest request,
            RequestItemFingerprint fingerprint) {
        Container source = containerAtLoaded(level, request.sourceContainer());
        if (source == null || request.sourceSlot() < 0
            || request.sourceSlot() >= source.getContainerSize()) {
            return false;
        }
        ItemStack exactSlot = source.getItem(request.sourceSlot());
        int expectedCount = request.sourceCountBefore() - request.movedCount()
            + request.returnedCount();
        return exactSourceSlotOwnership(request.sourceSlot(),
            source.getContainerSize(),
            fingerprint.matches(level.registryAccess(), exactSlot),
            exactSlot.getCount(), expectedCount);
    }

    private static boolean equipmentCourierOwns(
            ServerLevel level, Settlement settlement, EquipmentRequest request,
            RequestItemFingerprint fingerprint) {
        if (request.claimedBy() == null
            || !(level.getEntity(request.claimedBy()) instanceof SettlerEntity courier)
            || !courier.isBound()
            || courier.getProfession() != Profession.COURIER
            || courier.settlement() != settlement) {
            return false;
        }
        int outstanding = request.movedCount() - request.deliveredCount()
            - request.returnedCount();
        return exactCourierBagOwnership(true,
            request.claimedBy().equals(request.traceCourierId()),
            bagItemCount(courier), matchingCount(level, courier.bag,
                fingerprint), outstanding);
    }

    private static RequestBlocker equipmentCourierBlocker(
            ServerLevel level, Settlement settlement,
            EquipmentRequest request) {
        if (request.claimedBy() == null) {
            return RequestBlocker.COURIER_UNAVAILABLE;
        }
        Entity entity = level.getEntity(request.claimedBy());
        if (!(entity instanceof SettlerEntity courier) || !courier.isBound()
            || courier.getProfession() != Profession.COURIER) {
            return RequestBlocker.COURIER_UNAVAILABLE;
        }
        if (courier.settlement() != settlement) {
            return RequestBlocker.CROSS_SETTLEMENT;
        }
        return RequestBlocker.FINGERPRINT_MISMATCH;
    }

    /** Pure truth table used by the snapshot and restart regression tests. */
    static boolean exactSourceSlotOwnership(int slot, int containerSize,
                                            boolean fingerprintMatches,
                                            int actualCount,
                                            int expectedCount) {
        return slot >= 0 && slot < containerSize && fingerprintMatches
            && expectedCount > 0 && actualCount >= expectedCount;
    }

    /** A tool route owns the whole one-item Courier bag, never a loose match. */
    static boolean exactCourierBagOwnership(boolean validBoundCourier,
                                            boolean traceCourierMatches,
                                            int totalBagCount,
                                            int matchingCount,
                                            int outstandingCount) {
        return validBoundCourier && traceCourierMatches
            && outstandingCount == 1 && totalBagCount == 1
            && matchingCount == 1;
    }

    private static boolean equipmentTargetOwns(
            ServerLevel level, EquipmentRequest request,
            RequestItemFingerprint fingerprint) {
        Container target = containerAtLoaded(level, request.targetContainer());
        return target != null && matchingCount(level, target, fingerprint)
            >= request.targetCountBefore() + request.deliveredCount();
    }

    private static boolean hasRoomForExact(Container container,
                                           ItemStack incoming) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty()) {
                return true;
            }
            if (ItemStack.isSameItemSameComponents(existing, incoming)
                && existing.getCount() < Math.min(container.getMaxStackSize(),
                    existing.getMaxStackSize())) {
                return true;
            }
        }
        return false;
    }

    private static RequestBlocker observationalBlocker(ServerLevel level,
                                                       RequestRecord record,
                                                       PhysicalOwner owner) {
        return switch (owner) {
            case SOURCE -> !level.hasChunkAt(record.sourceContainer())
                ? RequestBlocker.SOURCE_UNLOADED : RequestBlocker.NO_STOCK;
            case COURIER_BAG -> RequestBlocker.COURIER_UNAVAILABLE;
            case TARGET -> !level.hasChunkAt(record.targetContainer())
                ? RequestBlocker.TARGET_UNLOADED : RequestBlocker.TARGET_INVALID;
            case UNKNOWN -> RequestBlocker.MALFORMED;
        };
    }

    private static boolean sourceStillOwns(ServerLevel level,
                                           RequestRecord record) {
        Container container = containerAtLoaded(level, record.sourceContainer());
        if (container == null || record.sourceSlot() >= container.getContainerSize()) {
            return false;
        }
        ItemStack stack = container.getItem(record.sourceSlot());
        return record.fingerprint().matches(level.registryAccess(), stack)
            && stack.getCount() >= record.fingerprint().count();
    }

    private static boolean courierStillOwns(ServerLevel level,
                                            RequestRecord record) {
        if (record.courierId() == null) {
            return false;
        }
        Entity entity = level.getEntity(record.courierId());
        if (!(entity instanceof SettlerEntity courier)) {
            return false;
        }
        int count = 0;
        for (int slot = 0; slot < courier.bag.getContainerSize(); slot++) {
            ItemStack stack = courier.bag.getItem(slot);
            if (record.fingerprint().matches(level.registryAccess(), stack)) {
                count += stack.getCount();
            }
        }
        return count >= record.remainingCount();
    }

    private static PhysicalOwner pickupOwner(ServerLevel level,
                                             RequestRecord record) {
        boolean source = sourceStillOwns(level, record);
        boolean courier = courierStillOwns(level, record);
        if (source == courier) {
            // Both true means duplicated ownership; both false means missing
            // ownership. Neither ambiguity may be presented as authoritative.
            return PhysicalOwner.UNKNOWN;
        }
        return source ? PhysicalOwner.SOURCE : PhysicalOwner.COURIER_BAG;
    }

    private static boolean targetOwnsDelivered(ServerLevel level,
                                               RequestRecord record) {
        if (record.type() == RequestType.FOOD) {
            Settlement settlement = SettlementManager.byId(level, record.settlementId());
            if (settlement == null || !record.targetBuildingId().equals(settlement.id)
                || !record.targetContainer().equals(settlement.center)
                || !level.hasChunkAt(record.targetContainer())
                || !(level.getBlockEntity(record.targetContainer()) instanceof HearthBlockEntity hearth)
                || !record.settlementId().equals(hearth.getSettlementId())) {
                return false;
            }
            long count = 0;
            var inventory = hearth.getInventory();
            for (int slot = 0; slot < inventory.getSlots(); slot++) {
                ItemStack stack = inventory.getStackInSlot(slot);
                if (record.fingerprint().matches(level.registryAccess(), stack)) {
                    count += stack.getCount();
                }
            }
            // This is live stock availability, not the delivery receipt. Meals
            // eaten later may make it false without invalidating SATISFIED.
            return count >= (long) record.targetCountBefore() + record.deliveredCount();
        }
        Container container = containerAtLoaded(level, record.targetContainer());
        if (container == null) {
            return false;
        }
        return matchingCount(level, container, record.fingerprint())
            >= record.targetCountBefore() + record.deliveredCount();
    }

    private static int matchingCount(ServerLevel level, Container container,
                                     RequestItemFingerprint fingerprint) {
        long count = 0L;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (fingerprint.matches(level.registryAccess(), stack)) {
                count += stack.getCount();
                if (count >= Integer.MAX_VALUE) {
                    return Integer.MAX_VALUE;
                }
            }
        }
        return (int) count;
    }

    private static int bagItemCount(SettlerEntity courier) {
        long count = 0L;
        for (int slot = 0; slot < courier.bag.getContainerSize(); slot++) {
            count += courier.bag.getItem(slot).getCount();
            if (count > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return (int) count;
    }

    @Nullable
    private static Container containerAtLoaded(ServerLevel level, BlockPos pos) {
        if (pos == null || !level.hasChunkAt(pos)) {
            return null;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof Container container ? container : null;
    }

    @Nullable
    private static Building buildingById(Settlement settlement, UUID id) {
        Building found = null;
        for (Building building : settlement.buildings) {
            if (building.id.equals(id)) {
                if (found != null) {
                    return null;
                }
                found = building;
            }
        }
        return found;
    }

    private static int distance(BlockPos from, BlockPos to) {
        if (from == null || to == null) {
            return -1;
        }
        return (int) Math.min(Integer.MAX_VALUE,
            Math.ceil(Math.sqrt(from.distSqr(to))));
    }

    private static String boundedToken(String value, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value;
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < normalized.length() && safe.length() < 96; i++) {
            char c = normalized.charAt(i);
            safe.append(Character.isLetterOrDigit(c) || c == ':' || c == '_'
                || c == '-' || c == '.' ? c : '_');
        }
        return safe.isEmpty() ? fallback : safe.toString();
    }

    private static UUID requireUuid(UUID id) {
        UUID normalized = normalizeUuid(id);
        if (normalized == null) {
            throw new IllegalArgumentException("request snapshot UUID");
        }
        return normalized;
    }

    @Nullable
    private static UUID normalizeUuid(@Nullable UUID id) {
        return id == null || id.getMostSignificantBits() == 0L
            && id.getLeastSignificantBits() == 0L ? null : id;
    }
}
