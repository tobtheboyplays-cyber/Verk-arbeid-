package com.hearthstead.settlement.request;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.logistics.Weight;
import com.hearthstead.menu.HearthMenu;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.ReadyFood;
import com.hearthstead.settlement.RecruitmentPolicy;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.RoomScanner;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.TavernSeating;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.journey.JourneyServerHooks;
import com.hearthstead.settlement.work.ContainerApproach;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import com.hearthstead.settlement.warehouse.WarehouseStorage;
import com.hearthstead.settlement.work.TavernHostService;
import com.hearthstead.util.AuthorityTelemetry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-thread transaction owner for typed physical requests.
 *
 * <p>The ledger records intent and evidence only. Every transfer re-observes
 * the exact loaded source slot, Courier bag and named destination container;
 * it never force-loads a chunk, accepts a client inventory assertion or
 * materialises an ItemStack from ledger data. A failed check either leaves
 * state unchanged or persists one concrete blocker.
 */
public final class RequestLedgerService {
    public static final long RESERVATION_TTL_TICKS = 1_200L;
    private static final long RESERVATION_RENEW_WINDOW_TICKS = 600L;
    private static final double CONTAINER_REACH_SQR = 6.25D;

    /**
     * One physical reach contract for route arrival and the authoritative
     * inventory transaction. Using integer block positions in one place and
     * the entity-to-container-centre distance in another let a Courier begin
     * lifting while the ledger would still reject pickup_not_at_source.
     */
    public static boolean withinContainerReach(@Nullable SettlerEntity courier,
                                               @Nullable BlockPos container) {
        return courier != null && container != null
            && courier.distanceToSqr(container.getX() + 0.5D,
                container.getY() + 0.5D, container.getZ() + 0.5D)
                <= CONTAINER_REACH_SQR;
    }

    /**
     * Exact FOOD-to-Hearth contact contract. The ledger uses the live actor
     * position; route planners may provide a candidate feet position and keep
     * a small navigation margin before a presentation starts.
     */
    public static boolean hasFoodHearthContact(ServerLevel level,
                                                @Nullable SettlerEntity courier,
                                                @Nullable Vec3 feetPosition,
                                                @Nullable BlockPos hearth,
                                                double arrivalMargin) {
        if (level == null || courier == null || feetPosition == null || hearth == null
            || arrivalMargin < 0.0D) {
            return false;
        }
        double remainingReach = Math.sqrt(CONTAINER_REACH_SQR) - arrivalMargin;
        if (remainingReach < 0.0D || feetPosition.distanceToSqr(Vec3.atCenterOf(hearth))
                > remainingReach * remainingReach) {
            return false;
        }
        Vec3 eye = feetPosition.add(0.0D, courier.getEyeHeight(), 0.0D);
        var hit = level.clip(new net.minecraft.world.level.ClipContext(eye,
            Vec3.atCenterOf(hearth), net.minecraft.world.level.ClipContext.Block.COLLIDER,
            net.minecraft.world.level.ClipContext.Fluid.NONE, courier));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS
            || hit.getBlockPos().equals(hearth);
    }

    /** Live-actor form used immediately before every FOOD inventory mutation. */
    public static boolean hasFoodHearthContact(ServerLevel level,
                                                @Nullable SettlerEntity courier,
                                                @Nullable BlockPos hearth) {
        return courier != null && hasFoodHearthContact(level, courier,
            courier.position(), hearth, 0.0D);
    }

    /**
     * Reports a transient physical-contact failure without persisting a
     * blocker. The caller may turn this into normal route recovery, but this
     * transaction boundary must not alter ledger revision/state before any
     * inventory mutation has occurred.
     */
    private static Decision contactUnavailable(RequestRecord request) {
        return new Decision(Outcome.BLOCKED, request, RequestBlocker.NO_PATH);
    }

    public enum Outcome {
        COMMITTED,
        DUPLICATE,
        ALREADY_OWNED,
        SATISFIED,
        BLOCKED,
        NOT_FOUND,
        REJECTED,
        QUARANTINED
    }

    public enum RoutePhase {
        TO_SOURCE,
        TO_TARGET,
        COMPLETE,
        BLOCKED
    }

    public record Decision(Outcome outcome, @Nullable RequestRecord request,
                           RequestBlocker blocker) {
        public Decision {
            outcome = java.util.Objects.requireNonNull(outcome, "outcome");
            blocker = blocker == null ? RequestBlocker.NONE : blocker;
        }

        public boolean accepted() {
            return outcome == Outcome.COMMITTED || outcome == Outcome.DUPLICATE
                || outcome == Outcome.ALREADY_OWNED
                || outcome == Outcome.SATISFIED;
        }
    }

    public record Route(Outcome outcome, RoutePhase phase,
                        @Nullable RequestRecord request,
                        @Nullable Building source,
                        @Nullable Building target,
                        RequestBlocker blocker) {
        public Route {
            outcome = java.util.Objects.requireNonNull(outcome, "outcome");
            phase = java.util.Objects.requireNonNull(phase, "phase");
            blocker = blocker == null ? RequestBlocker.NONE : blocker;
        }

        public static Route none(Outcome outcome, RequestBlocker blocker) {
            return new Route(outcome, RoutePhase.BLOCKED, null, null, null,
                blocker);
        }
    }

    /**
     * Bounded readiness projection. It inspects persisted rows only: no
     * entity/container/chunk scan, no ledger creation and no mutation.
     */
    public record ConflictSummary(boolean available, boolean quarantined,
                                  boolean criticalConflict,
                                  boolean unresolvedInTransit,
                                  int activeRows, int blockedRows,
                                  String reason) {
        public ConflictSummary {
            activeRows = Math.max(0, Math.min(RequestLedger.MAX_ACTIVE,
                activeRows));
            blockedRows = Math.max(0, Math.min(activeRows, blockedRows));
            reason = reason == null || reason.isBlank() ? "none" : reason;
        }
    }

    public static ConflictSummary inspectConflicts(ServerLevel level,
                                                   Settlement settlement) {
        if (!liveReadOnly(level, settlement)) {
            return new ConflictSummary(false, true, true, false, 0, 0,
                "invalid_authority");
        }
        RequestLedgerSavedData saved = RequestLedgerSavedData.existing(level);
        if (saved == null) {
            return new ConflictSummary(true, false, false, false, 0, 0,
                "none");
        }
        RequestLedger ledger = saved.existing(settlement.id);
        if (saved.rootQuarantined()) {
            return new ConflictSummary(true, true, true, false, 0, 0,
                saved.quarantineReason());
        }
        if (ledger == null) {
            return new ConflictSummary(true, false, false, false, 0, 0,
                "none");
        }
        int blocked = 0;
        boolean critical = ledger.quarantined();
        boolean transit = false;
        String reason = ledger.quarantined()
            ? ledger.quarantineReason() : "none";
        for (RequestRecord request : ledger.active()) {
            if (request.state() == RequestState.BLOCKED) {
                blocked++;
                boolean ambiguousPickup = request.blockedFrom()
                    == RequestState.PICKUP;
                if ((criticalBlocker(request.blocker()) || ambiguousPickup)
                    && !critical) {
                    critical = true;
                    reason = request.blocker().id();
                }
            }
            if (request.effectiveState() == RequestState.IN_TRANSIT
                || request.effectiveState() == RequestState.PICKUP) {
                transit = true;
            }
        }
        return new ConflictSummary(true, ledger.quarantined(), critical,
            transit, ledger.active().size(), blocked, reason);
    }

    private static boolean criticalBlocker(RequestBlocker blocker) {
        return blocker == RequestBlocker.MALFORMED
            || blocker == RequestBlocker.CROSS_SETTLEMENT
            || blocker == RequestBlocker.FINGERPRINT_MISMATCH
            || blocker == RequestBlocker.SOURCE_INVALID
            || blocker == RequestBlocker.TARGET_INVALID;
    }

    /**
     * Bounded read-only quantity already promised out of one workplace. Used
     * by the Courier scanner so two source slots cannot collectively reserve
     * the same keep-back buffer. It never opens a ledger or touches inventory.
     */
    public static int reservedOutputCount(ServerLevel level,
                                          Settlement settlement,
                                          Building source, Item item) {
        if (!liveReadOnly(level, settlement)
            || !exactRegistered(settlement, source)
            || item == null) {
            return 0;
        }
        RequestLedgerSavedData saved = RequestLedgerSavedData.existing(level);
        RequestLedger ledger = saved == null ? null
            : saved.existing(settlement.id);
        if (ledger == null || ledger.quarantined()) {
            return 0;
        }
        net.minecraft.resources.ResourceLocation itemId =
            net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
        int total = 0;
        for (RequestRecord request : ledger.active()) {
            if (request.type() == RequestType.OUTPUT_PICKUP
                && request.sourceBuildingId().equals(source.id)
                && request.fingerprint().itemId().equals(itemId)
                && (request.effectiveState() == RequestState.OPEN
                    || request.effectiveState() == RequestState.RESERVED
                    || request.effectiveState() == RequestState.PICKUP)) {
                total = saturatingAdd(total, request.fingerprint().count());
            }
        }
        return total;
    }

    /**
     * Opens one exact OUTPUT_PICKUP intent. The source and destination are
     * both real, loaded containers registered to this settlement.
     */
    public static Decision openOutputPickup(ServerLevel level,
                                            Settlement settlement,
                                            Building source,
                                            BlockPos sourceContainer,
                                            int sourceSlot,
                                            Building target,
                                            BlockPos targetContainer,
                                            int requestedCount,
                                            RequestPriority priority) {
        if (!live(level, settlement) || priority == null) {
            return rejected(level, settlement, null, RequestBlocker.CROSS_SETTLEMENT,
                "open_invalid_authority");
        }
        RequestBlocker endpointFailure = validateEndpoints(level, settlement,
            source, sourceContainer, target, targetContainer);
        if (endpointFailure != RequestBlocker.NONE) {
            return rejected(level, settlement, null, endpointFailure,
                "open_" + endpointFailure.id());
        }
        if (source == target && !com.hearthstead.settlement.warehouse.WarehouseSorting
                .mayTidySource(level, source, sourceContainer)) {
            return rejected(level, settlement, null, RequestBlocker.SOURCE_INVALID,
                "open_personal_sort_source");
        }
        // A container inside both a producer and the Warehouse must never be
        // its own destination: the delivery cannot raise the target count,
        // so its satisfaction proof would quarantine the whole ledger.
        if (sourceContainer.equals(targetContainer)) {
            return rejected(level, settlement, null,
                RequestBlocker.TARGET_INVALID, "open_self_loop");
        }
        // Same wall, different chest: a producer whose bounds overlap the
        // Warehouse sees the Warehouse's own chests as its output. Carrying
        // them from one Warehouse chest to another is not a delivery, and it
        // raced a real delivery into the same chest (soak 2026-09-25, Elmfield
        // farmhouse/warehouse at z=-111): goods already stored stay put.
        if (source != target && target.type == BuildingType.WAREHOUSE
            && source.type != BuildingType.WAREHOUSE
            && target.contains(sourceContainer)) {
            return rejected(level, settlement, null,
                RequestBlocker.TARGET_INVALID, "open_source_already_in_target");
        }
        Container from = containerAtLoaded(level, sourceContainer);
        Container to = containerAtLoaded(level, targetContainer);
        if (from == null || sourceSlot < 0
            || sourceSlot >= from.getContainerSize() || to == null) {
            return rejected(level, settlement, null,
                from == null ? RequestBlocker.SOURCE_INVALID
                    : RequestBlocker.TARGET_INVALID,
                "open_missing_container");
        }
        ItemStack stack = from.getItem(sourceSlot);
        if (source.type == BuildingType.WAREHOUSE
                && (com.hearthstead.settlement.warehouse.WarehouseSorting.groupOf(stack) == null
                || com.hearthstead.settlement.warehouse.WarehouseSorting.groupOf(stack)
                    != com.hearthstead.settlement.warehouse.WarehouseSorting.assignedGroup(level.getBlockEntity(targetContainer)))) {
            return rejected(level, settlement, null, RequestBlocker.TARGET_INVALID, "storage_group_mismatch");
        }
        if (stack.isEmpty() || requestedCount <= 0
            || requestedCount > stack.getCount()
            || requestedCount > Math.min(RequestItemFingerprint.MAX_COUNT,
                stack.getMaxStackSize())) {
            return rejected(level, settlement, null, RequestBlocker.NO_STOCK,
                "open_no_exact_stock");
        }
        RequestItemFingerprint fingerprint;
        try {
            fingerprint = RequestItemFingerprint.capture(level.registryAccess(),
                stack, requestedCount);
        } catch (IllegalArgumentException malformed) {
            return rejected(level, settlement, null,
                RequestBlocker.FINGERPRINT_MISMATCH,
                "open_unserializable_fingerprint");
        }
        int targetBefore = matchingCount(level, to, fingerprint);
        if (roomForExact(to, stack.copyWithCount(requestedCount))
                < requestedCount) {
            return rejected(level, settlement, null, RequestBlocker.TARGET_FULL,
                "open_target_full");
        }

        RequestLedgerSavedData saved = RequestLedgerSavedData.get(level);
        RequestLedger ledger = saved.ledger(settlement.id);
        if (saved.rootQuarantined() || ledger.quarantined()) {
            return rejected(level, settlement, null, RequestBlocker.MALFORMED,
                "open_quarantined");
        }
        RequestRecord candidate;
        try {
            candidate = RequestRecord.openOutput(settlement.id, priority,
                level.dimension().location(), source.id, sourceContainer,
                sourceSlot, target.id, targetContainer, fingerprint,
                stack.getCount(), targetBefore, level.getGameTime());
        } catch (IllegalArgumentException malformed) {
            return rejected(level, settlement, null, RequestBlocker.MALFORMED,
                "open_invalid_record");
        }
        long revisionBefore = ledger.revision();
        int countBefore = ledger.active().size();
        RequestLedger.OpenDecision opened = ledger.open(candidate);
        if (opened.result() == RequestLedger.OpenResult.DUPLICATE) {
            return new Decision(Outcome.DUPLICATE, opened.record(),
                opened.record() == null ? RequestBlocker.MALFORMED
                    : opened.record().blocker());
        }
        if (opened.result() != RequestLedger.OpenResult.CREATED
            || opened.record() == null) {
            if (opened.result() == RequestLedger.OpenResult.QUARANTINED) {
                saved.setDirty();
            }
            return rejected(level, settlement, null,
                opened.result() == RequestLedger.OpenResult.CAPACITY
                    ? RequestBlocker.MALFORMED : RequestBlocker.MALFORMED,
                "open_" + opened.result().name().toLowerCase(
                    java.util.Locale.ROOT));
        }
        saved.setDirty();
        AuthorityTelemetry.emit(level,
            AuthorityTelemetry.Event.OUTPUT_PICKUP_REQUEST_OPENED,
            AuthorityTelemetry.Result.COMMITTED,
            AuthorityTelemetry.Fields.items(settlement.id,
                "request:" + candidate.id(), revisionBefore,
                ledger.revision(), countBefore, ledger.active().size(),
                candidate.fingerprint().itemId().toString(),
                stack.getCount(), stack.getCount(), 0,
                "source:" + source.id));
        JourneyServerHooks.noteOutputPickupRequestOpened(level, settlement,
            source, target, candidate);
        return new Decision(Outcome.COMMITTED, candidate, RequestBlocker.NONE);
    }

    public static Decision openFoodDelivery(ServerLevel level,
                                            Settlement settlement,
                                            Building source,
                                            BlockPos sourceContainer,
                                            int sourceSlot,
                                            int requestedCount) {
        if (!live(level, settlement)) {
            return rejected(level, settlement, null, RequestBlocker.CROSS_SETTLEMENT,
                "open_invalid_authority");
        }
        BlockPos targetContainer = settlement.center;
        RequestBlocker endpointFailure = validateFoodEndpoints(level, settlement,
            source, sourceContainer, targetContainer);
        if (endpointFailure != RequestBlocker.NONE) {
            return rejected(level, settlement, null, endpointFailure,
                "open_" + endpointFailure.id());
        }
        Container from = containerAtLoaded(level, sourceContainer);
        Container to = containerAtLoaded(level, targetContainer);
        if (from == null || sourceSlot < 0
            || sourceSlot >= from.getContainerSize() || to == null) {
            return rejected(level, settlement, null,
                from == null ? RequestBlocker.SOURCE_INVALID
                    : RequestBlocker.TARGET_INVALID,
                "open_missing_container");
        }
        ItemStack stack = from.getItem(sourceSlot);
        if ((!ReadyFood.isReadyMeal(stack)
                && com.hearthstead.settlement.work.FishMeals.portions(stack) == 0) || requestedCount <= 0
            || requestedCount > stack.getCount()
            || requestedCount > Math.min(RequestItemFingerprint.MAX_COUNT,
                stack.getMaxStackSize())) {
            return rejected(level, settlement, null, RequestBlocker.NO_STOCK,
                "open_no_exact_stock");
        }
        RequestItemFingerprint fingerprint;
        try {
            fingerprint = RequestItemFingerprint.capture(level.registryAccess(),
                stack, requestedCount);
        } catch (IllegalArgumentException malformed) {
            return rejected(level, settlement, null,
                RequestBlocker.FINGERPRINT_MISMATCH,
                "open_unserializable_fingerprint");
        }
        int targetBefore = matchingCount(level, to, fingerprint);
        if (roomForExact(to, stack.copyWithCount(requestedCount))
                < requestedCount) {
            return rejected(level, settlement, null, RequestBlocker.TARGET_FULL,
                "open_target_full");
        }

        RequestLedgerSavedData saved = RequestLedgerSavedData.get(level);
        RequestLedger ledger = saved.ledger(settlement.id);
        if (saved.rootQuarantined() || ledger.quarantined()) {
            return rejected(level, settlement, null, RequestBlocker.MALFORMED,
                "open_quarantined");
        }
        if (!reconcileBeforeClaim(level, settlement, saved, ledger)) {
            return new Decision(Outcome.QUARANTINED, null, RequestBlocker.MALFORMED);
        }
        for (RequestRecord existing : ledger.active()) {
            if (existing.type() == RequestType.FOOD
                && existing.fingerprint().itemId().equals(fingerprint.itemId())) {
                return new Decision(Outcome.DUPLICATE, existing, existing.blocker());
            }
        }
        RequestRecord candidate;
        try {
            candidate = RequestRecord.openFood(settlement.id,
                level.dimension().location(), source.id, sourceContainer,
                sourceSlot, targetContainer, fingerprint,
                stack.getCount(), targetBefore, level.getGameTime());
        } catch (IllegalArgumentException malformed) {
            return rejected(level, settlement, null, RequestBlocker.MALFORMED,
                "open_invalid_record");
        }
        RequestLedger.OpenDecision opened = ledger.open(candidate);
        if (opened.result() == RequestLedger.OpenResult.DUPLICATE) {
            return new Decision(Outcome.DUPLICATE, opened.record(),
                opened.record() == null ? RequestBlocker.MALFORMED
                    : opened.record().blocker());
        }
        if (opened.result() != RequestLedger.OpenResult.CREATED
            || opened.record() == null) {
            if (opened.result() == RequestLedger.OpenResult.QUARANTINED) {
                saved.setDirty();
            }
            return rejected(level, settlement, null,
                opened.result() == RequestLedger.OpenResult.CAPACITY
                    ? RequestBlocker.MALFORMED : RequestBlocker.MALFORMED,
                "open_" + opened.result().name().toLowerCase(
                    java.util.Locale.ROOT));
        }
        saved.setDirty();
        return new Decision(Outcome.COMMITTED, candidate, RequestBlocker.NONE);
    }

    /**
     * Opens one exact Warehouse-to-staffed-Tavern service input. The only
     * permitted payloads are ready meals and reusable glass bottles; callers
     * cannot use this route as a generic remote container mover.
     */
    public static Decision openTavernRestock(ServerLevel level,
                                             Settlement settlement,
                                             Building source,
                                             BlockPos sourceContainer,
                                             int sourceSlot,
                                             Building tavern,
                                             BlockPos targetContainer,
                                             int requestedCount) {
        if (!live(level, settlement) || requestedCount <= 0 || tavern == null || tavern.type != BuildingType.TAVERN) {
            return rejected(level, settlement, null, RequestBlocker.CROSS_SETTLEMENT,
                "tavern_restock_open_invalid_authority");
        }
        RequestBlocker endpoints = validateMaterialInputEndpoints(level, settlement,
            source, sourceContainer, tavern, targetContainer);
        if (endpoints != RequestBlocker.NONE) {
            return rejected(level, settlement, null, endpoints,
                "tavern_restock_open_" + endpoints.id());
        }
        Container from = containerAtLoaded(level, sourceContainer);
        Container to = containerAtLoaded(level, targetContainer);
        if (from == null || to == null || sourceSlot < 0
            || sourceSlot >= from.getContainerSize()) {
            return rejected(level, settlement, null,
                from == null ? RequestBlocker.SOURCE_INVALID : RequestBlocker.TARGET_INVALID,
                "tavern_restock_open_missing_container");
        }
        ItemStack stack = from.getItem(sourceSlot);
        if (TavernHostService.restockKind(stack) == null) {
            return rejected(level, settlement, null, RequestBlocker.NO_STOCK,
                "tavern_restock_open_unsupported_item");
        }
        RequestLedgerSavedData saved = RequestLedgerSavedData.get(level);
        RequestLedger ledger = saved.ledger(settlement.id);
        if (saved.rootQuarantined() || ledger.quarantined()) {
            return rejected(level, settlement, null, RequestBlocker.MALFORMED,
                "tavern_restock_open_quarantined");
        }
        if (!reconcileBeforeClaim(level, settlement, saved, ledger)) {
            return new Decision(Outcome.QUARANTINED, null, RequestBlocker.MALFORMED);
        }
        for (RequestRecord active : ledger.active()) {
            if (active.type() != RequestType.MATERIAL_INPUT
                || !active.targetBuildingId().equals(tavern.id)) continue;
            ItemStack pending = active.fingerprint().prototype(level.registryAccess());
            if (TavernHostService.sameRestockKind(stack, pending)) {
                return new Decision(Outcome.DUPLICATE, active, active.blocker());
            }
        }
        TavernHostService.RestockNeed need = TavernHostService.restockNeed(level, settlement, tavern, stack);
        if (need == null || !need.container().equals(targetContainer)) {
            return rejected(level, settlement, null, RequestBlocker.TARGET_INVALID,
                "tavern_restock_open_no_shared_service_container");
        }
        int deficit = need.deficit();
        int count = Math.min(requestedCount, Math.min(stack.getCount(), deficit));
        count = Math.min(count, Math.min(RequestItemFingerprint.MAX_COUNT,
            stack.getMaxStackSize()));
        count = Math.min(count, roomForExact(to, stack.copyWithCount(Math.max(1, count))));
        if (count <= 0) {
            return rejected(level, settlement, null,
                RequestBlocker.TARGET_FULL,
                "tavern_restock_open_no_live_deficit");
        }
        RequestItemFingerprint fingerprint;
        try {
            fingerprint = RequestItemFingerprint.capture(level.registryAccess(), stack, count);
        } catch (IllegalArgumentException malformed) {
            return rejected(level, settlement, null, RequestBlocker.FINGERPRINT_MISMATCH,
                "tavern_restock_open_unserializable_fingerprint");
        }
        RequestRecord candidate;
        try {
            candidate = RequestRecord.openMaterialInput(settlement.id,
                level.dimension().location(), source.id, sourceContainer, sourceSlot,
                tavern.id, targetContainer, fingerprint, stack.getCount(),
                matchingCount(level, to, fingerprint), level.getGameTime());
        } catch (IllegalArgumentException malformed) {
            return rejected(level, settlement, null, RequestBlocker.MALFORMED,
                "tavern_restock_open_invalid_record");
        }
        RequestLedger.OpenDecision opened = ledger.open(candidate);
        if (opened.result() == RequestLedger.OpenResult.DUPLICATE) {
            return new Decision(Outcome.DUPLICATE, opened.record(),
                opened.record() == null ? RequestBlocker.MALFORMED : opened.record().blocker());
        }
        if (opened.result() != RequestLedger.OpenResult.CREATED || opened.record() == null) {
            if (opened.result() == RequestLedger.OpenResult.QUARANTINED) saved.setDirty();
            return rejected(level, settlement, null, RequestBlocker.MALFORMED,
                "tavern_restock_open_" + opened.result().name().toLowerCase(java.util.Locale.ROOT));
        }
        saved.setDirty();
        return new Decision(Outcome.COMMITTED, candidate, RequestBlocker.NONE);
    }

    /** Opens only missing approved commercial stock. Caller computes the missing count;
     * exactSample includes quality and every component. Physical stock and endpoint
     * authority are rechecked by the existing durable parcel transaction. */
    public static Decision openTraderRestock(ServerLevel level,
                                             Settlement settlement,
                                             Building source,
                                             BlockPos sourceContainer,
                                             int sourceSlot,
                                             Building tradingPost,
                                             BlockPos targetContainer,
                                             ItemStack exactSample,
                                             int requestedCount) {
        if (!live(level, settlement) || requestedCount <= 0 || exactSample == null || exactSample.isEmpty()
            || tradingPost == null || tradingPost.type != BuildingType.TRADING_POST) {
            return rejected(level, settlement, null, RequestBlocker.CROSS_SETTLEMENT,
                "trader_restock_open_invalid_authority");
        }
        RequestBlocker endpoints = validateMaterialInputEndpoints(level, settlement,
            source, sourceContainer, tradingPost, targetContainer);
        if (endpoints != RequestBlocker.NONE) {
            return rejected(level, settlement, null, endpoints,
                "trader_restock_open_" + endpoints.id());
        }
        Container from = containerAtLoaded(level, sourceContainer);
        Container to = containerAtLoaded(level, targetContainer);
        if (from == null || to == null || sourceSlot < 0
            || sourceSlot >= from.getContainerSize()) {
            return rejected(level, settlement, null,
                from == null ? RequestBlocker.SOURCE_INVALID : RequestBlocker.TARGET_INVALID,
                "trader_restock_open_missing_container");
        }
        ItemStack stack = from.getItem(sourceSlot);
        if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, exactSample)) {
            return rejected(level, settlement, null, RequestBlocker.NO_STOCK,
                "trader_restock_open_unsupported_item");
        }
        RequestLedgerSavedData saved = RequestLedgerSavedData.get(level);
        RequestLedger ledger = saved.ledger(settlement.id);
        if (saved.rootQuarantined() || ledger.quarantined()) {
            return rejected(level, settlement, null, RequestBlocker.MALFORMED,
                "trader_restock_open_quarantined");
        }
        if (!reconcileBeforeClaim(level, settlement, saved, ledger)) {
            return new Decision(Outcome.QUARANTINED, null, RequestBlocker.MALFORMED);
        }
        for (RequestRecord active : ledger.active()) {
            if (active.type() != RequestType.MATERIAL_INPUT
                || !active.targetBuildingId().equals(tradingPost.id)) continue;
            ItemStack pending = active.fingerprint().prototype(level.registryAccess());
            if (ItemStack.isSameItemSameComponents(stack, pending)) {
                return new Decision(Outcome.DUPLICATE, active, active.blocker());
            }
        }
        int deficit = roomForExact(to, stack);
        int count = Math.min(requestedCount, Math.min(stack.getCount(), deficit));
        count = Math.min(count, Math.min(RequestItemFingerprint.MAX_COUNT,
            stack.getMaxStackSize()));
        count = Math.min(count, roomForExact(to, stack.copyWithCount(Math.max(1, count))));
        if (count <= 0) {
            return rejected(level, settlement, null,
                RequestBlocker.TARGET_FULL,
                "trader_restock_open_no_live_deficit");
        }
        RequestItemFingerprint fingerprint;
        try {
            fingerprint = RequestItemFingerprint.capture(level.registryAccess(), stack, count);
        } catch (IllegalArgumentException malformed) {
            return rejected(level, settlement, null, RequestBlocker.FINGERPRINT_MISMATCH,
                "trader_restock_open_unserializable_fingerprint");
        }
        RequestRecord candidate;
        try {
            candidate = RequestRecord.openMaterialInput(settlement.id,
                level.dimension().location(), source.id, sourceContainer, sourceSlot,
                tradingPost.id, targetContainer, fingerprint, stack.getCount(),
                matchingCount(level, to, fingerprint), level.getGameTime());
        } catch (IllegalArgumentException malformed) {
            return rejected(level, settlement, null, RequestBlocker.MALFORMED,
                "trader_restock_open_invalid_record");
        }
        RequestLedger.OpenDecision opened = ledger.open(candidate);
        if (opened.result() == RequestLedger.OpenResult.DUPLICATE) {
            return new Decision(Outcome.DUPLICATE, opened.record(),
                opened.record() == null ? RequestBlocker.MALFORMED : opened.record().blocker());
        }
        if (opened.result() != RequestLedger.OpenResult.CREATED || opened.record() == null) {
            if (opened.result() == RequestLedger.OpenResult.QUARANTINED) saved.setDirty();
            return rejected(level, settlement, null, RequestBlocker.MALFORMED,
                "trader_restock_open_" + opened.result().name().toLowerCase(java.util.Locale.ROOT));
        }
        saved.setDirty();
        return new Decision(Outcome.COMMITTED, candidate, RequestBlocker.NONE);
    }

    /**
     * BUILDER lane (plan/BUILDER.md): opens one exact Warehouse-to-Builder's-
     * Hut building-material parcel. Same shape as {@link #openTraderRestock}:
     * the caller computes the missing count, the row carries the exact item
     * fingerprint, the live source slot and target room are rechecked here and
     * again by the durable parcel transaction. The Builder only ever takes from
     * his hut's chests, so every block he places arrived physically.
     */
    public static Decision openBuilderMaterial(ServerLevel level,
                                             Settlement settlement,
                                             Building source,
                                             BlockPos sourceContainer,
                                             int sourceSlot,
                                             Building hut,
                                             BlockPos targetContainer,
                                             ItemStack exactSample,
                                             int requestedCount) {
        if (!live(level, settlement) || requestedCount <= 0 || exactSample == null || exactSample.isEmpty()
            || hut == null || hut.type != BuildingType.BUILDERS_HUT) {
            return rejected(level, settlement, null, RequestBlocker.CROSS_SETTLEMENT,
                "builder_material_open_invalid_authority");
        }
        RequestBlocker endpoints = validateMaterialInputEndpoints(level, settlement,
            source, sourceContainer, hut, targetContainer);
        if (endpoints != RequestBlocker.NONE) {
            return rejected(level, settlement, null, endpoints,
                "builder_material_open_" + endpoints.id());
        }
        Container from = containerAtLoaded(level, sourceContainer);
        Container to = containerAtLoaded(level, targetContainer);
        if (from == null || to == null || sourceSlot < 0
            || sourceSlot >= from.getContainerSize()) {
            return rejected(level, settlement, null,
                from == null ? RequestBlocker.SOURCE_INVALID : RequestBlocker.TARGET_INVALID,
                "builder_material_open_missing_container");
        }
        ItemStack stack = from.getItem(sourceSlot);
        if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, exactSample)) {
            return rejected(level, settlement, null, RequestBlocker.NO_STOCK,
                "builder_material_open_unsupported_item");
        }
        RequestLedgerSavedData saved = RequestLedgerSavedData.get(level);
        RequestLedger ledger = saved.ledger(settlement.id);
        if (saved.rootQuarantined() || ledger.quarantined()) {
            return rejected(level, settlement, null, RequestBlocker.MALFORMED,
                "builder_material_open_quarantined");
        }
        if (!reconcileBeforeClaim(level, settlement, saved, ledger)) {
            return new Decision(Outcome.QUARANTINED, null, RequestBlocker.MALFORMED);
        }
        for (RequestRecord active : ledger.active()) {
            if (active.type() != RequestType.MATERIAL_INPUT
                || !active.targetBuildingId().equals(hut.id)) continue;
            ItemStack pending = active.fingerprint().prototype(level.registryAccess());
            if (ItemStack.isSameItemSameComponents(stack, pending)) {
                return new Decision(Outcome.DUPLICATE, active, active.blocker());
            }
        }
        int deficit = roomForExact(to, stack);
        int count = Math.min(requestedCount, Math.min(stack.getCount(), deficit));
        count = Math.min(count, Math.min(RequestItemFingerprint.MAX_COUNT,
            stack.getMaxStackSize()));
        count = Math.min(count, roomForExact(to, stack.copyWithCount(Math.max(1, count))));
        if (count <= 0) {
            return rejected(level, settlement, null,
                RequestBlocker.TARGET_FULL,
                "builder_material_open_no_live_deficit");
        }
        RequestItemFingerprint fingerprint;
        try {
            fingerprint = RequestItemFingerprint.capture(level.registryAccess(), stack, count);
        } catch (IllegalArgumentException malformed) {
            return rejected(level, settlement, null, RequestBlocker.FINGERPRINT_MISMATCH,
                "builder_material_open_unserializable_fingerprint");
        }
        RequestRecord candidate;
        try {
            candidate = RequestRecord.openMaterialInput(settlement.id,
                level.dimension().location(), source.id, sourceContainer, sourceSlot,
                hut.id, targetContainer, fingerprint, stack.getCount(),
                matchingCount(level, to, fingerprint), level.getGameTime());
        } catch (IllegalArgumentException malformed) {
            return rejected(level, settlement, null, RequestBlocker.MALFORMED,
                "builder_material_open_invalid_record");
        }
        RequestLedger.OpenDecision opened = ledger.open(candidate);
        if (opened.result() == RequestLedger.OpenResult.DUPLICATE) {
            return new Decision(Outcome.DUPLICATE, opened.record(),
                opened.record() == null ? RequestBlocker.MALFORMED : opened.record().blocker());
        }
        if (opened.result() != RequestLedger.OpenResult.CREATED || opened.record() == null) {
            if (opened.result() == RequestLedger.OpenResult.QUARANTINED) saved.setDirty();
            return rejected(level, settlement, null, RequestBlocker.MALFORMED,
                "builder_material_open_" + opened.result().name().toLowerCase(java.util.Locale.ROOT));
        }
        saved.setDirty();
        return new Decision(Outcome.COMMITTED, candidate, RequestBlocker.NONE);
    }
    /**
     * Opens one exact Warehouse-to-Hunter-Lodge Arrow intent. The requested
     * count is an upper bound; the committed row is reduced to live source,
     * destination-room and Lodge-deficit truth in this same server tick.
     */
    public static Decision openAmmunitionDelivery(ServerLevel level,
                                                   Settlement settlement,
                                                   Building source,
                                                   BlockPos sourceContainer,
                                                   int sourceSlot,
                                                   Building target,
                                                   BlockPos targetContainer,
                                                   int requestedCount) {
        if (!live(level, settlement) || requestedCount <= 0) {
            return rejected(level, settlement, null,
                RequestBlocker.CROSS_SETTLEMENT, "ammo_open_invalid_authority");
        }
        RequestBlocker endpointFailure = validateAmmunitionEndpoints(level,
            settlement, source, sourceContainer, target, targetContainer);
        if (endpointFailure != RequestBlocker.NONE) {
            return rejected(level, settlement, null, endpointFailure,
                "ammo_open_" + endpointFailure.id());
        }
        Container from = containerAtLoaded(level, sourceContainer);
        Container to = containerAtLoaded(level, targetContainer);
        if (from == null || sourceSlot < 0
            || sourceSlot >= from.getContainerSize() || to == null) {
            return rejected(level, settlement, null,
                from == null ? RequestBlocker.SOURCE_INVALID
                    : RequestBlocker.TARGET_INVALID,
                "ammo_open_missing_container");
        }
        ItemStack stack = from.getItem(sourceSlot);
        if (stack.isEmpty() || !stack.is(Items.ARROW)) {
            return rejected(level, settlement, null, RequestBlocker.NO_STOCK,
                "ammo_open_no_arrows");
        }

        RequestLedgerSavedData saved = RequestLedgerSavedData.get(level);
        RequestLedger ledger = saved.ledger(settlement.id);
        if (saved.rootQuarantined() || ledger.quarantined()) {
            return rejected(level, settlement, null, RequestBlocker.MALFORMED,
                "ammo_open_quarantined");
        }
        if (!reconcileBeforeClaim(level, settlement, saved, ledger)) {
            return new Decision(Outcome.QUARANTINED, null,
                RequestBlocker.MALFORMED);
        }
        for (RequestRecord existing : ledger.active()) {
            if (existing.type() == RequestType.AMMUNITION
                && existing.targetBuildingId().equals(target.id)) {
                return new Decision(Outcome.DUPLICATE, existing,
                    existing.blocker());
            }
        }

        int count = Math.min(requestedCount, Math.min(stack.getCount(),
            Math.min(RequestItemFingerprint.MAX_COUNT,
                stack.getMaxStackSize())));
        count = Math.min(count,
            ammunitionDeficit(level, settlement, target));
        count = Math.min(count, roomForExact(to,
            stack.copyWithCount(Math.max(1, count))));
        if (count <= 0) {
            return rejected(level, settlement, null, RequestBlocker.TARGET_FULL,
                "ammo_open_no_live_deficit");
        }

        RequestItemFingerprint fingerprint;
        try {
            fingerprint = RequestItemFingerprint.capture(level.registryAccess(),
                stack, count);
        } catch (IllegalArgumentException malformed) {
            return rejected(level, settlement, null,
                RequestBlocker.FINGERPRINT_MISMATCH,
                "ammo_open_unserializable_fingerprint");
        }
        int targetBefore = matchingCount(level, to, fingerprint);
        RequestRecord candidate;
        try {
            candidate = RequestRecord.openAmmunition(settlement.id,
                level.dimension().location(), source.id, sourceContainer,
                sourceSlot, target.id, targetContainer, fingerprint,
                stack.getCount(), targetBefore, level.getGameTime());
        } catch (IllegalArgumentException malformed) {
            return rejected(level, settlement, null, RequestBlocker.MALFORMED,
                "ammo_open_invalid_record");
        }
        RequestLedger.OpenDecision opened = ledger.open(candidate);
        if (opened.result() == RequestLedger.OpenResult.DUPLICATE) {
            return new Decision(Outcome.DUPLICATE, opened.record(),
                opened.record() == null ? RequestBlocker.MALFORMED
                    : opened.record().blocker());
        }
        if (opened.result() != RequestLedger.OpenResult.CREATED
            || opened.record() == null) {
            if (opened.result() == RequestLedger.OpenResult.QUARANTINED) {
                saved.setDirty();
            }
            return rejected(level, settlement, null, RequestBlocker.MALFORMED,
                "ammo_open_" + opened.result().name().toLowerCase(
                    java.util.Locale.ROOT));
        }
        saved.setDirty();
        return new Decision(Outcome.COMMITTED, candidate, RequestBlocker.NONE);
    }

    /** Atomic OPEN to RESERVED claim. Same-thread calls give one winner. */
    public static Decision reserve(ServerLevel level, Settlement settlement,
                                   UUID requestId, SettlerEntity courier) {
        RequestLedgerSavedData saved = dataIfLive(level, settlement);
        RequestLedger ledger = saved == null ? null : saved.existing(settlement.id);
        RequestRecord request = ledger == null || requestId == null ? null
            : ledger.active(requestId);
        if (saved == null || ledger == null || request == null) {
            return rejected(level, settlement, requestId,
                RequestBlocker.SOURCE_INVALID, "reserve_not_found");
        }
        if (saved.rootQuarantined() || ledger.quarantined()) {
            return new Decision(Outcome.QUARANTINED, request,
                RequestBlocker.MALFORMED);
        }
        if (!validCourier(level, settlement, courier)) {
            return rejected(level, settlement, requestId,
                RequestBlocker.COURIER_UNAVAILABLE, "reserve_invalid_courier");
        }
        if (request.state() == RequestState.RESERVED
            && courier.getUUID().equals(request.courierId())) {
            renew(level, settlement, request.id(), courier);
            return new Decision(Outcome.ALREADY_OWNED, request,
                RequestBlocker.NONE);
        }
        if (request.state() == RequestState.RESERVED
            || request.state() == RequestState.BLOCKED
                && request.blockedFrom() == RequestState.RESERVED) {
            return rejected(level, settlement, requestId,
                RequestBlocker.RESERVED_BY_OTHER, "reserve_owned_by_other");
        }
        if (request.effectiveState() != RequestState.OPEN) {
            return rejected(level, settlement, requestId,
                RequestBlocker.MALFORMED, "reserve_wrong_state");
        }
        List<RequestRecord> owned = ledger.activeForCourier(courier.getUUID());
        if (!owned.isEmpty() && !batchSlotOpen(level, settlement, courier, owned, request)) {
            return block(level, settlement, request,
                RequestBlocker.COURIER_UNAVAILABLE, "courier_already_owns_request");
        }
        Building ammunitionTarget = request.type() == RequestType.AMMUNITION
            ? registeredById(settlement, request.targetBuildingId()) : null;
        if (request.type() == RequestType.AMMUNITION
            && ammunitionBoundsLoaded(level, ammunitionTarget)
            && ammunitionDeficit(level, settlement, ammunitionTarget)
                < request.fingerprint().count()) {
            if (!request.expire(level.getGameTime())
                || !commit(saved, ledger, request)) {
                return quarantine(level, saved, ledger, request,
                    "ammo_reserve_expiry_failed");
            }
            return new Decision(Outcome.NOT_FOUND, request,
                RequestBlocker.TARGET_FULL);
        }
        RequestBlocker physical = validateForReservation(level, settlement,
            request, courier);
        if (physical != RequestBlocker.NONE) {
            return block(level, settlement, request, physical,
                "reserve_" + physical.id());
        }
        if (request.state() == RequestState.BLOCKED && !request.resume(
                level.getGameTime())) {
            return quarantine(level, saved, ledger, request,
                "resume_open_failed");
        }
        long revisionBefore = ledger.revision();
        if (!request.reserve(courier.getUUID(), level.getGameTime(),
                RESERVATION_TTL_TICKS)
            || !commit(saved, ledger, request)) {
            return quarantine(level, saved, ledger, request,
                "reserve_transition_failed");
        }
        if (request.type() == RequestType.OUTPUT_PICKUP) {
            AuthorityTelemetry.emit(level,
                AuthorityTelemetry.Event.OUTPUT_PICKUP_RESERVED,
                AuthorityTelemetry.Result.COMMITTED,
                AuthorityTelemetry.Fields.items(settlement.id,
                    "request:" + request.id(), revisionBefore, ledger.revision(),
                    ledger.active().size(), ledger.active().size(),
                    request.fingerprint().itemId().toString(), 0, 0, 0,
                    "courier:" + courier.getUUID()));
        }
        if (request.type() == RequestType.OUTPUT_PICKUP) {
            JourneyServerHooks.noteOutputPickupReserved(level, settlement, courier, request);
        }
        return new Decision(Outcome.COMMITTED, request, RequestBlocker.NONE);
    }

    /**
     * Finds the oldest/most urgent claimable OUTPUT row and reserves it in
     * the same synchronous call. No request identity comes from the client.
     */
    public static Decision claimNextOutput(ServerLevel level,
                                           Settlement settlement,
                                           SettlerEntity courier) {
        return claimNext(level, settlement, courier,
            RequestType.OUTPUT_PICKUP);
    }

    /** Claims the oldest live Tavern service-input row. */
    public static Decision claimNextMaterialInput(ServerLevel level,
                                                  Settlement settlement,
                                                  SettlerEntity courier) {
        return claimNext(level, settlement, courier, RequestType.MATERIAL_INPUT);
    }
    /** Claims the oldest live Hunter-Lodge ammunition row. */
    public static Decision claimNextAmmunition(ServerLevel level,
                                                Settlement settlement,
                                                SettlerEntity courier) {
        return claimNext(level, settlement, courier,
            RequestType.AMMUNITION);
    }

    private static Decision claimNext(ServerLevel level,
                                      Settlement settlement,
                                      SettlerEntity courier,
                                      RequestType type) {
        RequestLedgerSavedData saved = dataIfLive(level, settlement);
        RequestLedger ledger = saved == null ? null : saved.existing(settlement.id);
        if (ledger == null || ledger.quarantined() || !validCourier(level,
                settlement, courier)) {
            return new Decision(ledger != null && ledger.quarantined()
                ? Outcome.QUARANTINED : Outcome.NOT_FOUND, null,
                ledger != null && ledger.quarantined()
                    ? RequestBlocker.MALFORMED
                    : RequestBlocker.COURIER_UNAVAILABLE);
        }
        if (!reconcileBeforeClaim(level, settlement, saved, ledger)) {
            return new Decision(Outcome.QUARANTINED, null,
                RequestBlocker.MALFORMED);
        }
        List<RequestRecord> already = ledger.activeForCourier(courier.getUUID());
        if (!CourierBatching.ownershipAllowed(already.size())) {
            return quarantine(level, saved, ledger, already.getFirst(),
                "courier_double_ownership");
        }
        if (!already.isEmpty()) {
            // A batched pair (Stout Straps) reports the request the bag is
            // working on, never the stowed one.
            RequestRecord working = already.getFirst();
            for (RequestRecord row : already) {
                if (!stowHoldsExactly(level, courier, row)) {
                    working = row;
                    break;
                }
            }
            return new Decision(Outcome.ALREADY_OWNED, working, working.blocker());
        }
        List<RequestRecord> candidates = new ArrayList<>();
        for (RequestRecord request : ledger.active()) {
            if (request.type() == type
                && request.effectiveState() == RequestState.OPEN) {
                candidates.add(request);
            }
        }
        candidates.sort(Comparator.comparing(RequestRecord::priority).reversed()
            .thenComparingLong(RequestRecord::createdAt)
            .thenComparing(row -> row.id().toString()));
        RequestBlocker last = RequestBlocker.NONE;
        for (RequestRecord candidate : candidates) {
            Decision decision = reserve(level, settlement, candidate.id(), courier);
            if (decision.accepted()) {
                return decision;
            }
            last = decision.blocker();
        }
        return new Decision(Outcome.NOT_FOUND, null, last);
    }

    /**
     * Bounded recovery performed only when a Courier asks for work. An
     * expired pre-pickup lease can be released iff the exact source still
     * owns the complete stack. Later phases retain their owner and become a
     * visible blocker; stealing an uncertain bag after death/chunk unload
     * would be duplication, not recovery.
     */
    private static boolean reconcileBeforeClaim(ServerLevel level,
                                                Settlement settlement,
                                                RequestLedgerSavedData saved,
                                                RequestLedger ledger) {
        long now = level.getGameTime();
        for (RequestRecord request : ledger.active()) {
            UUID ownerId = request.courierId();
            if (ownerId == null) {
                continue;
            }
            RequestState effective = request.effectiveState();
            if (effective == RequestState.RESERVED
                && request.leaseUntil() <= now) {
                Building source = registeredById(settlement,
                    request.sourceBuildingId());
                Building target = registeredById(settlement,
                    request.targetBuildingId());
                RequestBlocker endpoints = validateRecordEndpoints(level,
                    settlement, request, source, target);
                Container container = endpoints == RequestBlocker.NONE
                    ? containerAtLoaded(level, request.sourceContainer()) : null;
                int sourceCount = exactSourceCount(level, container, request);
                if (endpoints == RequestBlocker.NONE
                    && sourceCount == request.sourceCountBefore()) {
                    if (!request.releaseToOpen(ownerId, now)
                        || !commit(saved, ledger, request)) {
                        quarantine(level, saved, ledger, request,
                            "expired_reservation_release_failed");
                        return false;
                    }
                    continue;
                }
                RequestBlocker reason = endpoints != RequestBlocker.NONE
                    ? endpoints : RequestBlocker.FINGERPRINT_MISMATCH;
                if (request.state() != RequestState.BLOCKED
                    && (!request.block(reason, now)
                        || !commit(saved, ledger, request))) {
                    quarantine(level, saved, ledger, request,
                        "expired_reservation_block_failed");
                    return false;
                }
                continue;
            }
            if (effective != RequestState.RESERVED) {
                net.minecraft.world.entity.Entity owner = level.getEntity(ownerId);
                boolean ownerAvailable = owner instanceof SettlerEntity settler
                    && validCourier(level, settlement, settler);
                if (!ownerAvailable && request.state() != RequestState.BLOCKED
                    && (!request.block(RequestBlocker.COURIER_UNAVAILABLE, now)
                        || !commit(saved, ledger, request))) {
                    quarantine(level, saved, ledger, request,
                        "orphaned_transit_block_failed");
                    return false;
                }
            }
        }
        return !ledger.quarantined();
    }

    /**
     * Reconstructs one route from persisted ledger plus the real bag.
     *
     * <p>Stout Straps batching: while a Courier owns two requests, the one
     * whose cargo is stowed waits and the other is routed. When that one is
     * finished, expired, or blocked before owning any item, a second pass
     * un-stows and routes the waiting request, so the caller never mistakes
     * a finished half of a batch for a free Courier.
     */
    public static Route routeForCourier(ServerLevel level,
                                        Settlement settlement,
                                        SettlerEntity courier) {
        Route first = routeForCourierOnce(level, settlement, courier);
        if (courier == null || first.outcome() == Outcome.QUARANTINED
            || courier.batchStow.isEmpty()) {
            return first;
        }
        RequestRecord routed = first.request();
        if (first.outcome() == Outcome.BLOCKED && routed != null
            && routed.movedCount() == 0 && routed.deliveredCount() == 0
            && matchingBagCount(level, courier, routed.fingerprint()) == 0) {
            // The partner's pickup failed before any item moved: release that
            // request only; the stowed one goes on.
            if (!releasePrePickup(level, settlement, routed.id(), courier)) {
                return first;
            }
        } else if (routed != null && first.phase() != RoutePhase.COMPLETE) {
            return first;
        }
        return routeForCourierOnce(level, settlement, courier);
    }

    private static Route routeForCourierOnce(ServerLevel level,
                                             Settlement settlement,
                                             SettlerEntity courier) {
        RequestLedgerSavedData saved = dataIfLive(level, settlement);
        RequestLedger ledger = saved == null ? null : saved.existing(settlement.id);
        if (saved == null || ledger == null || ledger.quarantined()
            || !validCourier(level, settlement, courier)) {
            return Route.none(ledger != null && ledger.quarantined()
                ? Outcome.QUARANTINED : Outcome.NOT_FOUND,
                ledger != null && ledger.quarantined()
                    ? RequestBlocker.MALFORMED
                    : RequestBlocker.COURIER_UNAVAILABLE);
        }
        List<RequestRecord> owned = settleBatchStow(level, saved, ledger, courier,
            ledger.activeForCourier(courier.getUUID()));
        if (owned == null) {
            return Route.none(Outcome.QUARANTINED, RequestBlocker.MALFORMED);
        }
        if (owned.isEmpty()) {
            return Route.none(Outcome.NOT_FOUND, RequestBlocker.NONE);
        }
        if (owned.size() > 1) {
            quarantine(level, saved, ledger, owned.getFirst(),
                "courier_double_ownership");
            return Route.none(Outcome.QUARANTINED, RequestBlocker.MALFORMED);
        }
        RequestRecord request = owned.getFirst();
        Building source = registeredById(settlement, request.sourceBuildingId());
        Building target = registeredById(settlement, request.targetBuildingId());
        RequestBlocker endpoints = validateRecordEndpoints(level, settlement,
            request, source, target);
        if (endpoints != RequestBlocker.NONE) {
            if (expireStaleZeroCargoOutput(level, settlement, saved, ledger,
                    request, courier, endpoints)) {
                return Route.none(Outcome.NOT_FOUND, RequestBlocker.NONE);
            }
            block(level, settlement, request, endpoints,
                "recover_" + endpoints.id());
            return new Route(Outcome.BLOCKED, RoutePhase.BLOCKED, request,
                source, target, endpoints);
        }

        RequestState effective = request.effectiveState();
        if (request.state() == RequestState.BLOCKED) {
            RequestBlocker unresolved = effective == RequestState.IN_TRANSIT
                ? validateInTransit(level, settlement, request, target, courier)
                : validateForReservation(level, settlement, request, courier);
            if (unresolved != RequestBlocker.NONE) {
                if (expireStaleZeroCargoOutput(level, settlement, saved, ledger,
                        request, courier, unresolved)) {
                    return Route.none(Outcome.NOT_FOUND, RequestBlocker.NONE);
                }
                return new Route(Outcome.BLOCKED, RoutePhase.BLOCKED, request,
                    source, target, request.blocker());
            }
            if (!request.resume(level.getGameTime())
                || !commit(saved, ledger, request)) {
                quarantine(level, saved, ledger, request,
                    "recover_resume_failed");
                return Route.none(Outcome.QUARANTINED, RequestBlocker.MALFORMED);
            }
            effective = request.state();
        }

        if (effective == RequestState.RESERVED) {
            if (request.leaseUntil() - level.getGameTime()
                    <= RESERVATION_RENEW_WINDOW_TICKS) {
                renew(level, settlement, request.id(), courier);
            }
            return new Route(Outcome.ALREADY_OWNED, RoutePhase.TO_SOURCE,
                request, source, target, RequestBlocker.NONE);
        }
        if (effective == RequestState.PICKUP) {
            int bag = matchingBagCount(level, courier, request.fingerprint());
            Container from = containerAtLoaded(level, request.sourceContainer());
            int sourceCount = exactSourceCount(level, from, request);
            if (request.movedCount() == 0
                && totalBagCount(courier) == bag
                && bag == request.fingerprint().count()
                && sourceCount >= request.sourceCountBefore()
                    - request.fingerprint().count()) {
                if (!request.markInTransit(courier.getUUID(), bag,
                        level.getGameTime())
                    || !commit(saved, ledger, request)) {
                    quarantine(level, saved, ledger, request,
                        "recover_pickup_commit_failed");
                    return Route.none(Outcome.QUARANTINED,
                        RequestBlocker.MALFORMED);
                }
                effective = RequestState.IN_TRANSIT;
            } else if (bag != request.movedCount() || totalBagCount(courier) != bag
                // Matching producer growth is surplus; only a shrink makes a resumed pickup ambiguous.
                || sourceCount < request.sourceCountBefore() - request.movedCount()) {
                block(level, settlement, request,
                    RequestBlocker.FINGERPRINT_MISMATCH,
                    "recover_pickup_ambiguous");
                return new Route(Outcome.BLOCKED, RoutePhase.BLOCKED, request,
                    source, target, RequestBlocker.FINGERPRINT_MISMATCH);
            } else {
                return new Route(Outcome.ALREADY_OWNED, RoutePhase.TO_SOURCE,
                    request, source, target, RequestBlocker.NONE);
            }
        }
        if (effective == RequestState.IN_TRANSIT) {
            RequestBlocker bagFailure = validateInTransit(level, settlement,
                request, target, courier);
            if (bagFailure != RequestBlocker.NONE) {
                block(level, settlement, request, bagFailure,
                    "recover_" + bagFailure.id());
                return new Route(Outcome.BLOCKED, RoutePhase.BLOCKED, request,
                    source, target, bagFailure);
            }
            return new Route(Outcome.ALREADY_OWNED, RoutePhase.TO_TARGET,
                request, source, target, RequestBlocker.NONE);
        }
        if (effective == RequestState.DELIVERED) {
            if (!targetOwnsFullDelivery(level, request)
                || !request.markSatisfied(courier.getUUID(),
                    level.getGameTime()) || !commit(saved, ledger, request)) {
                quarantine(level, saved, ledger, request,
                    "recover_delivered_failed");
                return Route.none(Outcome.QUARANTINED, RequestBlocker.MALFORMED);
            }
            noteSatisfied(level, settlement, source, target, courier, request,
                ledger.revision());
            return new Route(Outcome.SATISFIED, RoutePhase.COMPLETE, request,
                source, target, RequestBlocker.NONE);
        }
        return Route.none(Outcome.NOT_FOUND, RequestBlocker.MALFORMED);
    }

    /**
     * Retires one obsolete output reservation before it can hide current work.
     *
     * <p>This is intentionally narrower than ordinary blocked-route recovery:
     * only a pre-pickup {@code OUTPUT_PICKUP} whose owner has an entirely empty
     * bag may expire.  The request therefore owns no physical item.  A removed
     * source, replaced Warehouse id, or changed source stack cannot then leave
     * an old reservation permanently ahead of a fresh, valid route.  Any route
     * with a lifted or delivered unit remains blocked under its existing
     * custody/recovery rules.
     *
     * <p>The same zero-cargo argument holds for the Warehouse-to-consumer
     * hauls (food, crafter material, ammunition): a RESERVED row whose source
     * stack shrank or moved before the first lift owns nothing physical.
     * Leaving it BLOCKED wedged its Courier on the same dead reservation
     * every ~40 ticks and, as a duplicate, stopped any fresh row for that
     * item (soak 2026-09-25: a MATERIAL_INPUT row stuck on
     * fingerprint_mismatch for whole workdays).
     */
    private static boolean expireStaleZeroCargoOutput(ServerLevel level,
                                                      Settlement settlement,
                                                      RequestLedgerSavedData saved,
                                                      RequestLedger ledger,
                                                      RequestRecord request,
                                                      SettlerEntity courier,
                                                      RequestBlocker reason) {
        if (request.type() != RequestType.OUTPUT_PICKUP
                && request.type() != RequestType.MATERIAL_INPUT
                && request.type() != RequestType.FOOD
                && request.type() != RequestType.AMMUNITION
            || request.effectiveState() != RequestState.RESERVED
            || request.movedCount() != 0 || request.deliveredCount() != 0
            || totalBagCount(courier) != 0
            || reason != RequestBlocker.SOURCE_INVALID
                && reason != RequestBlocker.TARGET_INVALID
                && reason != RequestBlocker.NO_STOCK
                && reason != RequestBlocker.FINGERPRINT_MISMATCH) {
            return false;
        }
        if (!request.expire(level.getGameTime()) || !commit(saved, ledger, request)) {
            quarantine(level, saved, ledger, request,
                "stale_zero_cargo_output_expiry_failed");
            return false;
        }
        return true;
    }

    /** Exact source-slot to real Courier bag commit. */
    public static Decision pickup(ServerLevel level, Settlement settlement,
                                  UUID requestId, SettlerEntity courier) {
        return pickupInternal(level, settlement, requestId, courier, -1, false);
    }

    /** One contact, replay-safe against the caller's persisted moved-count receipt. */
    public static Decision pickupOne(ServerLevel level, Settlement settlement,
                                     UUID requestId, SettlerEntity courier,
                                     int expectedMovedCount) {
        if (expectedMovedCount < 0) {
            return rejected(level, settlement, requestId,
                RequestBlocker.MALFORMED, "pickup_invalid_receipt");
        }
        return pickupInternal(level, settlement, requestId, courier,
            expectedMovedCount, true);
    }

    private static Decision pickupInternal(ServerLevel level, Settlement settlement,
                                           UUID requestId, SettlerEntity courier,
                                           int expectedMovedCount, boolean one) {
        RequestLedgerSavedData saved = dataIfLive(level, settlement);
        RequestLedger ledger = saved == null ? null : saved.existing(settlement.id);
        RequestRecord request = ledger == null || requestId == null ? null
            : ledger.active(requestId);
        if (saved == null || ledger == null || request == null
            || !validCourier(level, settlement, courier)
            || !courier.getUUID().equals(request.courierId())) {
            return rejected(level, settlement, requestId,
                RequestBlocker.COURIER_UNAVAILABLE, "pickup_invalid_owner");
        }
        if (one && request.movedCount() != expectedMovedCount) {
            return rejected(level, settlement, requestId,
                RequestBlocker.MALFORMED, "pickup_stale_receipt");
        }
        if (request.state() != RequestState.RESERVED
            && request.state() != RequestState.PICKUP) {
            return rejected(level, settlement, requestId,
                RequestBlocker.MALFORMED, "pickup_wrong_state");
        }
        if (!withinContainerReach(courier, request.sourceContainer())) {
            return block(level, settlement, request, RequestBlocker.NO_PATH,
                "pickup_not_at_source");
        }
        // Reach alone is insufficient: a closed door or wall can change
        // after travel begins. This check deliberately precedes markPickup
        // and every inventory mutation, so a failed contact leaves the
        // request in its exact prior state for the owning route to retry.
        if (!ContainerApproach.inspect(level, courier,
                request.sourceContainer()).canInteract()) {
            return contactUnavailable(request);
        }
        if (request.effectiveState() == RequestState.RESERVED
            && (request.type() == RequestType.FOOD
                && foodDeficit(level, settlement,
                    request.fingerprint().prototype(level.registryAccess()))
                    < request.fingerprint().count()
                || request.type() == RequestType.AMMUNITION
                    && ammunitionBoundsLoaded(level, registeredById(settlement,
                        request.targetBuildingId()))
                    && ammunitionDeficit(level, settlement,
                        registeredById(settlement, request.targetBuildingId()))
                        < request.fingerprint().count())) {
            if (!request.expire(level.getGameTime())
                || !commit(saved, ledger, request)) {
                return quarantine(level, saved, ledger, request,
                    request.type() == RequestType.FOOD
                        ? "food_deficit_expiry_failed"
                        : "ammo_deficit_expiry_failed");
            }
            return new Decision(Outcome.NOT_FOUND, request,
                request.type() == RequestType.FOOD
                    ? RequestBlocker.NO_STOCK : RequestBlocker.TARGET_FULL);
        }
        // A not-yet-started saved tidy route must respect a newly renamed
        // source. Once the physical load has begun, finish that bounded load
        // under its existing custody; do not strand partial bags on a rename.
        if (storageSort(request) && request.movedCount() == 0
            && !com.hearthstead.settlement.warehouse.WarehouseSorting.mayTidySource(
                level, registeredById(settlement, request.sourceBuildingId()),
                request.sourceContainer())) {
            if (expireStaleZeroCargoOutput(level, settlement, saved, ledger,
                    request, courier, RequestBlocker.SOURCE_INVALID)) {
                return new Decision(Outcome.NOT_FOUND, request, RequestBlocker.SOURCE_INVALID);
            }
            // A crash can persist PICKUP before the first inventory move.
            // Retire it only with physical proof that no unit left the source.
            if (request.effectiveState() == RequestState.PICKUP
                && request.deliveredCount() == 0 && totalBagCount(courier) == 0
                && exactSourceCount(level, containerAtLoaded(level, request.sourceContainer()), request)
                    >= request.sourceCountBefore()) {
                if (!request.cancelUnstartedPickup(courier.getUUID(), level.getGameTime())
                    || !commit(saved, ledger, request)) {
                    return quarantine(level, saved, ledger, request,
                        "personal_sort_empty_pickup_cancel_failed");
                }
                return new Decision(Outcome.NOT_FOUND, request, RequestBlocker.SOURCE_INVALID);
            }
            return block(level, settlement, request, RequestBlocker.SOURCE_INVALID,
                "pickup_personal_sort_source");
        }
        RequestBlocker physical = validateForReservation(level, settlement,
            request, courier);
        if (physical != RequestBlocker.NONE) {
            return block(level, settlement, request, physical,
                "pickup_" + physical.id());
        }
        Container source = containerAtLoaded(level, request.sourceContainer());
        if (source == null || request.sourceSlot() >= source.getContainerSize()) {
            return block(level, settlement, request,
                RequestBlocker.SOURCE_INVALID, "pickup_source_missing");
        }
        long pickupRevisionBefore = request.revision();
        boolean beganPickup = false;
        if (request.state() == RequestState.RESERVED) {
            if (!request.markPickup(courier.getUUID(), level.getGameTime())) {
                return quarantine(level, saved, ledger, request,
                    "pickup_begin_failed");
            }
            beganPickup = true;
        }
        // PICKUP is a real persisted recovery boundary. If the server stops
        // after this commit, routeForCourier can prove whether ownership is
        // still at the source or has already moved into the exact Courier bag.
        if (beganPickup && !commit(saved, ledger, request)) {
            return quarantine(level, saved, ledger, request,
                "pickup_boundary_commit_failed");
        }
        int bagBefore = matchingBagCount(level, courier, request.fingerprint());
        int quantity = one ? 1 : request.fingerprint().count() - request.movedCount();
        if (!request.canNotePickup(courier.getUUID(), bagBefore, quantity,
                level.getGameTime())) {
            return rejected(level, settlement, requestId,
                RequestBlocker.MALFORMED, "pickup_trace_budget");
        }
        ItemStack removed = source.removeItem(request.sourceSlot(), quantity);
        if (removed.getCount() != quantity
            || !request.fingerprint().matches(level.registryAccess(), removed)) {
            giveBack(source, request.sourceSlot(), removed);
            return block(level, settlement, request,
                RequestBlocker.FINGERPRINT_MISMATCH,
                "pickup_source_changed_during_commit");
        }
        ItemStack leftover = courier.bag.addItem(removed.copy());
        int inserted = removed.getCount() - leftover.getCount();
        if (inserted != removed.getCount()) {
            ItemStack recovered = removeMatchingFromBag(level, courier,
                request.fingerprint(), inserted);
            if (!leftover.isEmpty()) {
                recovered.grow(leftover.getCount());
            }
            giveBack(source, request.sourceSlot(), recovered);
            return block(level, settlement, request, RequestBlocker.BAG_FULL,
                "pickup_bag_race");
        }
        int bagAfter = matchingBagCount(level, courier, request.fingerprint());
        if (bagAfter - bagBefore != inserted
            || !request.notePickedUp(courier.getUUID(), bagBefore, inserted,
                level.getGameTime())
            || !commit(saved, ledger, request)) {
            ItemStack recovered = removeMatchingFromBag(level, courier,
                request.fingerprint(), inserted);
            giveBack(source, request.sourceSlot(), recovered);
            return quarantine(level, saved, ledger, request,
                "pickup_terminal_commit_failed");
        }
        source.setChanged();
        if (request.type() == RequestType.OUTPUT_PICKUP) {
            AuthorityTelemetry.emit(level,
                AuthorityTelemetry.Event.OUTPUT_PICKUP_PICKED_UP,
                AuthorityTelemetry.Result.COMMITTED,
                AuthorityTelemetry.Fields.items(settlement.id,
                    "courier:" + courier.getUUID(), pickupRevisionBefore,
                    request.revision(), bagBefore, bagAfter,
                    request.fingerprint().itemId().toString(), bagBefore, bagAfter,
                    inserted, "request:" + request.id()));
        }
        return new Decision(Outcome.COMMITTED, request, RequestBlocker.NONE);
    }

    /** Real Courier bag to exact Warehouse container commit. */
    public static Decision deliver(ServerLevel level, Settlement settlement,
                                   UUID requestId, SettlerEntity courier) {
        return deliver(level, settlement, requestId, courier, Integer.MAX_VALUE);
    }

    /**
     * Same atomic delivery, bounded for truthful physical presentations.
     * A visible labelled bundle passes its exact count; existing callers
     * retain the all-that-fits behavior through the overload above.
     */
    public static Decision deliver(ServerLevel level, Settlement settlement,
                                   UUID requestId, SettlerEntity courier,
                                   int maxItems) {
        RequestLedgerSavedData saved = dataIfLive(level, settlement);
        RequestLedger ledger = saved == null ? null : saved.existing(settlement.id);
        RequestRecord request = ledger == null || requestId == null ? null
            : ledger.active(requestId);
        if (saved == null || ledger == null || request == null
            || !validCourier(level, settlement, courier)
            || !courier.getUUID().equals(request.courierId())) {
            return rejected(level, settlement, requestId,
                RequestBlocker.COURIER_UNAVAILABLE, "deliver_invalid_owner");
        }
        if (request.type() == RequestType.FOOD) {
            RequestBlocker foodEndpoints = validateRecordEndpoints(level, settlement, request,
                registeredById(settlement, request.sourceBuildingId()), null);
            if (foodEndpoints != RequestBlocker.NONE) {
                return block(level, settlement, request, foodEndpoints, "food_deliver_" + foodEndpoints.id());
            }
        }
        if (request.state() == RequestState.BLOCKED
            && request.blockedFrom() == RequestState.IN_TRANSIT) {
            if (!withinContainerReach(courier, request.targetContainer())) {
                return block(level, settlement, request, RequestBlocker.NO_PATH,
                    "deliver_not_at_target");
            }
            // resume() itself changes request state. A sealed target must
            // leave this recovery record untouched for the caller to retry.
            if (!targetContact(level, courier, request)) {
                return contactUnavailable(request);
            }
            RequestBlocker ready = validateInTransit(level, settlement, request,
                registeredById(settlement, request.targetBuildingId()), courier);
            if (ready != RequestBlocker.NONE || !request.resume(
                    level.getGameTime())) {
                return new Decision(Outcome.BLOCKED, request,
                    request.blocker());
            }
        }
        if (request.state() != RequestState.IN_TRANSIT) {
            return rejected(level, settlement, requestId,
                RequestBlocker.MALFORMED, "deliver_wrong_state");
        }
        if (!withinContainerReach(courier, request.targetContainer())) {
            return block(level, settlement, request, RequestBlocker.NO_PATH,
                "deliver_not_at_target");
        }
        // Final same-tick guard before the target container can be mutated.
        if (!targetContact(level, courier, request)) {
            return contactUnavailable(request);
        }
        Building sourceBuilding = registeredById(settlement,
            request.sourceBuildingId());
        Building targetBuilding = registeredById(settlement,
            request.targetBuildingId());
        RequestBlocker endpoints = validateRecordEndpoints(level, settlement,
            request, sourceBuilding, targetBuilding);
        if (endpoints != RequestBlocker.NONE) {
            return block(level, settlement, request, endpoints,
                "deliver_" + endpoints.id());
        }
        Container target = containerAtLoaded(level, request.targetContainer());
        if (target == null) {
            return block(level, settlement, request,
                RequestBlocker.TARGET_INVALID, "deliver_target_missing");
        }
        int bagBefore = matchingBagCount(level, courier, request.fingerprint());
        int remaining = request.remainingCount();
        if (bagBefore != remaining) {
            return block(level, settlement, request,
                RequestBlocker.FINGERPRINT_MISMATCH,
                "deliver_bag_fingerprint_mismatch");
        }
        ItemStack offered = request.fingerprint().prototype(level.registryAccess());
        if (offered.isEmpty()) {
            return block(level, settlement, request,
                RequestBlocker.FINGERPRINT_MISMATCH,
                "deliver_prototype_decode_failed");
        }
        int offeredCount = Math.min(remaining, Math.max(1, maxItems));
        int liveAmmunitionDeficit = Integer.MAX_VALUE;
        int liveMaterialInputDeficit = Integer.MAX_VALUE;
        if (request.type() == RequestType.AMMUNITION) {
            liveAmmunitionDeficit = ammunitionDeficit(level, settlement, targetBuilding);
            offeredCount = Math.min(offeredCount, liveAmmunitionDeficit);
            if (offeredCount <= 0) return block(level, settlement, request,
                RequestBlocker.TARGET_FULL, "ammo_deliver_no_live_deficit");
        } else if (request.type() == RequestType.MATERIAL_INPUT) {
            liveMaterialInputDeficit = materialInputDeficit(level, settlement, targetBuilding,
                request.targetContainer(), offered);
            offeredCount = Math.min(offeredCount, liveMaterialInputDeficit);
            if (offeredCount <= 0) return block(level, settlement, request,
                RequestBlocker.TARGET_FULL, "tavern_restock_deliver_no_live_deficit");
        }
        offered.setCount(offeredCount);
        int targetBefore = matchingCount(level, target, request.fingerprint());
        ItemStack leftover = request.type() == RequestType.FOOD
            ? ((HearthBlockEntity) level.getBlockEntity(request.targetContainer())).insertGoods(offered)
            : WarehouseStorage.of(level, targetBuilding)
                .insertAt(level, targetBuilding, request.targetContainer(), offered);
        int inserted = offeredCount - leftover.getCount();
        if (inserted <= 0) {
            return block(level, settlement, request, RequestBlocker.TARGET_FULL,
                "deliver_target_full");
        }
        ItemStack removedFromBag = removeMatchingFromBag(level, courier,
            request.fingerprint(), inserted);
        if (removedFromBag.getCount() != inserted) {
            removeMatchingFromContainer(level, target, request.fingerprint(),
                inserted);
            return quarantine(level, saved, ledger, request,
                "deliver_bag_commit_failed");
        }
        int targetAfter = matchingCount(level, target, request.fingerprint());
        int bagAfter = matchingBagCount(level, courier, request.fingerprint());
        if (targetAfter - targetBefore != inserted
            || bagBefore - bagAfter != inserted
            || !request.noteDelivered(courier.getUUID(), inserted,
                level.getGameTime())) {
            // A target rollback is still possible because exact components
            // are indistinguishable within this one named container.
            ItemStack rolledBack = removeMatchingFromContainer(level, target,
                request.fingerprint(), inserted);
            ItemStack bagRemainder = courier.bag.addItem(rolledBack);
            if (!bagRemainder.isEmpty()) {
                return quarantine(level, saved, ledger, request,
                    "deliver_rollback_failed");
            }
            return quarantine(level, saved, ledger, request,
                "deliver_state_commit_failed");
        }

        boolean complete = request.state() == RequestState.DELIVERED;
        if (complete && (!targetOwnsFullDelivery(level, request)
            || !request.markSatisfied(courier.getUUID(), level.getGameTime()))) {
            return quarantine(level, saved, ledger, request,
                "deliver_satisfaction_proof_failed");
        }
        if (!complete && (!leftover.isEmpty()
                || request.type() == RequestType.AMMUNITION
                    && liveAmmunitionDeficit < remaining
                || request.type() == RequestType.MATERIAL_INPUT
                    && liveMaterialInputDeficit < remaining)) {
            if (!request.block(RequestBlocker.TARGET_FULL,
                    level.getGameTime())) {
                // The items are still conserved between the exact target and
                // Courier bag, but a trace that cannot persist its recovery
                // edge must never continue as an apparently healthy route.
                return quarantine(level, saved, ledger, request,
                    "deliver_partial_block_trace_failed");
            }
        }
        long revisionBefore = ledger.revision();
        if (!commit(saved, ledger, request)) {
            return quarantine(level, saved, ledger, request,
                "deliver_ledger_commit_failed");
        }
        if (request.type() == RequestType.OUTPUT_PICKUP) {
            AuthorityTelemetry.emit(level,
                AuthorityTelemetry.Event.OUTPUT_PICKUP_DELIVERED,
                AuthorityTelemetry.Result.COMMITTED,
                AuthorityTelemetry.Fields.items(settlement.id,
                    "request:" + request.id(), revisionBefore, ledger.revision(),
                    bagBefore, bagAfter, request.fingerprint().itemId().toString(),
                    bagBefore, bagAfter, -inserted,
                    "target:" + request.targetBuildingId()));
        }
        if (complete) {
            noteSatisfied(level, settlement, sourceBuilding, targetBuilding,
                courier, request, ledger.revision());
            return new Decision(Outcome.SATISFIED, request,
                RequestBlocker.NONE);
        }
        // A deliberate contact-sized delivery is healthy while its remaining
        // cargo is still in transit. Only a real capacity/deficit block above
        // may publish TARGET_FULL and ask the Courier to rest.
        if (request.state() == RequestState.IN_TRANSIT) {
            return new Decision(Outcome.COMMITTED, request, RequestBlocker.NONE);
        }
        if (request.type() == RequestType.OUTPUT_PICKUP) {
            AuthorityTelemetry.emit(level,
                AuthorityTelemetry.Event.OUTPUT_PICKUP_BLOCKED,
                AuthorityTelemetry.Result.COMMITTED,
                AuthorityTelemetry.Fields.state(settlement.id,
                    "request:" + request.id(), revisionBefore, ledger.revision(),
                    ledger.active().size(), ledger.active().size(),
                    RequestBlocker.TARGET_FULL.id()));
        }

        return new Decision(Outcome.BLOCKED, request,
            RequestBlocker.TARGET_FULL);
    }

    /** Lease heartbeat; persisted at most twice per TTL. */
    public static boolean renew(ServerLevel level, Settlement settlement,
                                UUID requestId, SettlerEntity courier) {
        RequestLedgerSavedData saved = dataIfLive(level, settlement);
        RequestLedger ledger = saved == null ? null : saved.existing(settlement.id);
        RequestRecord request = ledger == null || requestId == null ? null
            : ledger.active(requestId);
        long now = level == null ? -1L : level.getGameTime();
        if (request == null || courier == null
            || !courier.getUUID().equals(request.courierId())
            || request.effectiveState() != RequestState.RESERVED
            || request.leaseUntil() - now > RESERVATION_RENEW_WINDOW_TICKS) {
            return false;
        }
        if (!request.renew(courier.getUUID(), now, RESERVATION_TTL_TICKS)) {
            return false;
        }
        return commit(saved, ledger, request);
    }

    /** Releases only an empty, pre-pickup reservation. */
    public static boolean releasePrePickup(ServerLevel level,
                                           Settlement settlement,
                                           UUID requestId,
                                           SettlerEntity courier) {
        RequestLedgerSavedData saved = dataIfLive(level, settlement);
        RequestLedger ledger = saved == null ? null : saved.existing(settlement.id);
        RequestRecord request = ledger == null || requestId == null ? null
            : ledger.active(requestId);
        if (request == null || courier == null
            || matchingBagCount(level, courier, request.fingerprint()) != 0
            || !request.releaseToOpen(courier.getUUID(), level.getGameTime())) {
            return false;
        }
        return commit(saved, ledger, request);
    }

    /** Called only after the real bag remainder has returned to source. */
    public static boolean cancelAfterReturn(ServerLevel level,
                                            Settlement settlement,
                                            UUID requestId,
                                            SettlerEntity courier) {
        RequestLedgerSavedData saved = dataIfLive(level, settlement);
        RequestLedger ledger = saved == null ? null : saved.existing(settlement.id);
        RequestRecord request = ledger == null || requestId == null ? null
            : ledger.active(requestId);
        Container source = request == null ? null
            : containerAtLoaded(level, request.sourceContainer());
        int expectedSource = request == null ? -1
            : request.sourceCountBefore() - request.deliveredCount();
        if (request == null || courier == null || source == null
            || matchingBagCount(level, courier, request.fingerprint()) != 0
            || matchingCount(level, source, request.fingerprint()) < expectedSource
            || !request.cancelAfterPhysicalReturn(courier.getUUID(),
                level.getGameTime())) {
            return false;
        }
        return commit(saved, ledger, request);
    }

    /**
     * Atomically returns one AMMUNITION remainder to its recorded source.
     * The source must have room for the complete remainder before either
     * inventory changes; a partial physical return would otherwise sever the
     * persisted request from the smaller bag that still owns it after reload.
     */
    public static Decision returnAmmunitionToSource(
            ServerLevel level, Settlement settlement, UUID requestId,
            SettlerEntity courier) {
        RequestLedgerSavedData saved = dataIfLive(level, settlement);
        RequestLedger ledger = saved == null ? null : saved.existing(settlement.id);
        RequestRecord request = ledger == null || requestId == null ? null
            : ledger.active(requestId);
        if (saved != null && (saved.rootQuarantined()
                || ledger != null && ledger.quarantined())) {
            return new Decision(Outcome.QUARANTINED, request,
                RequestBlocker.MALFORMED);
        }
        if (saved == null || ledger == null || request == null
            || request.type() != RequestType.AMMUNITION
                && request.type() != RequestType.MATERIAL_INPUT || courier == null
            || !validCourier(level, settlement, courier)
            || !courier.getUUID().equals(request.courierId())
            || request.effectiveState() != RequestState.IN_TRANSIT) {
            return rejected(level, settlement, requestId,
                RequestBlocker.COURIER_UNAVAILABLE,
                "ammo_return_invalid_authority");
        }
        Building sourceBuilding = registeredById(settlement,
            request.sourceBuildingId());
        RequestBlocker sourceFailure = validateAmmunitionSource(level,
            settlement, sourceBuilding, request.sourceContainer());
        if (sourceFailure != RequestBlocker.NONE) {
            return new Decision(Outcome.BLOCKED, request, sourceFailure);
        }
        if (!withinContainerReach(courier, request.sourceContainer())
            || !ContainerApproach.inspect(level, courier,
                request.sourceContainer()).canInteract()) {
            return contactUnavailable(request);
        }
        Container source = containerAtLoaded(level, request.sourceContainer());
        int remaining = request.remainingCount();
        int bagBefore = matchingBagCount(level, courier, request.fingerprint());
        if (source == null || remaining <= 0 || bagBefore != remaining
            || totalBagCount(courier) != remaining) {
            return new Decision(Outcome.BLOCKED, request,
                RequestBlocker.FINGERPRINT_MISMATCH);
        }
        ItemStack payload = request.fingerprint().prototype(
            level.registryAccess());
        if (payload.isEmpty()) {
            return new Decision(Outcome.BLOCKED, request,
                RequestBlocker.FINGERPRINT_MISMATCH);
        }
        payload.setCount(remaining);
        if (roomForExact(source, payload) < remaining) {
            return new Decision(Outcome.BLOCKED, request,
                RequestBlocker.TARGET_FULL);
        }

        int sourceBefore = matchingCount(level, source, request.fingerprint());
        ItemStack leftover = WarehouseStorage.of(level, sourceBuilding)
            .insertAt(level, sourceBuilding, request.sourceContainer(), payload);
        int sourceAfterInsert = matchingCount(level, source,
            request.fingerprint());
        int inserted = sourceAfterInsert - sourceBefore;
        if (!leftover.isEmpty() || inserted != remaining) {
            if (inserted > 0) {
                removeMatchingFromContainer(level, source,
                    request.fingerprint(), inserted);
            }
            return new Decision(Outcome.BLOCKED, request,
                RequestBlocker.TARGET_FULL);
        }

        ItemStack removed = removeMatchingFromBag(level, courier,
            request.fingerprint(), remaining);
        int bagAfter = matchingBagCount(level, courier, request.fingerprint());
        if (removed.getCount() != remaining || bagBefore - bagAfter != remaining
            || !request.cancelAfterPhysicalReturn(courier.getUUID(),
                level.getGameTime())) {
            ItemStack rolledBack = removeMatchingFromContainer(level, source,
                request.fingerprint(), remaining);
            ItemStack restore = rolledBack.copyWithCount(removed.getCount());
            ItemStack bagRemainder = courier.bag.addItem(restore);
            if (!bagRemainder.isEmpty()) {
                return quarantine(level, saved, ledger, request,
                    "ammo_return_rollback_failed");
            }
            return quarantine(level, saved, ledger, request,
                "ammo_return_state_commit_failed");
        }
        if (!commit(saved, ledger, request)) {
            // mutationCommitted is the only possible rejection after the
            // record's validated terminal edge; inventory remains conserved,
            // but the ledger can no longer be trusted to advertise ownership.
            return quarantine(level, saved, ledger, request,
                "ammo_return_ledger_commit_failed");
        }
        return new Decision(Outcome.COMMITTED, request, RequestBlocker.NONE);
    }

    /** Persists one route blocker without logging per tick. */
    public static Decision block(ServerLevel level, Settlement settlement,
                                 UUID requestId, SettlerEntity courier,
                                 RequestBlocker reason) {
        RequestLedgerSavedData saved = dataIfLive(level, settlement);
        RequestLedger ledger = saved == null ? null : saved.existing(settlement.id);
        RequestRecord request = ledger == null || requestId == null ? null
            : ledger.active(requestId);
        if (request == null || courier == null
            || request.courierId() != null
                && !courier.getUUID().equals(request.courierId())) {
            return rejected(level, settlement, requestId,
                RequestBlocker.COURIER_UNAVAILABLE, "block_invalid_owner");
        }
        return block(level, settlement, request, reason,
            "route_" + (reason == null ? "unknown" : reason.id()));
    }

    /**
     * Exact Hearth session entry point for the read-only global snapshot.
     * Merely constructing a client-side list can never reach this method.
     */
    public static Optional<RequestLedgerSnapshot> snapshotForHearth(
            ServerPlayer player, Settlement settlement) {
        if (player == null || settlement == null
            || !(player.containerMenu instanceof HearthMenu menu)
            || !menu.getSettlementId().equals(settlement.id)
            || !menu.getHearthPos().equals(settlement.center)
            || !menu.stillValid(player)
            || !(player.serverLevel().getBlockEntity(menu.getHearthPos())
                instanceof HearthBlockEntity hearth)
            || hearth.getSettlementId() == null
            || !hearth.getSettlementId().equals(settlement.id)) {
            return Optional.empty();
        }
        Optional<RequestLedgerSnapshot> snapshot = RequestLedgerSnapshot.create(
            player.serverLevel(), settlement);
        return snapshot;
    }

    /**
     * Called by the network owner only after the exact bounded snapshot has
     * been handed to the player's connection. Building a snapshot alone is
     * observational and cannot author FJ-230.
     */
    public static boolean noteSnapshotSent(ServerPlayer player,
                                           Settlement settlement,
                                           RequestLedgerSnapshot snapshot) {
        if (player == null || settlement == null || snapshot == null
            || !(player.containerMenu instanceof HearthMenu menu)
            || !menu.getSettlementId().equals(settlement.id)
            || !menu.getHearthPos().equals(settlement.center)
            || !menu.stillValid(player)
            || snapshot.generatedTick() != player.serverLevel().getGameTime()) {
            return false;
        }
        boolean recorded = JourneyServerHooks.noteRequestLedgerViewed(player,
            settlement, snapshot);
        AuthorityTelemetry.emit(player.serverLevel(),
            AuthorityTelemetry.Event.REQUEST_LEDGER_VIEWED,
            AuthorityTelemetry.Result.OBSERVED,
            AuthorityTelemetry.Fields.state(settlement.id,
                "hearth:" + menu.getHearthPos().asLong(),
                snapshot.typedRevision(), snapshot.typedRevision(),
                snapshot.rows().size(), snapshot.rows().size(),
                snapshot.truncated() ? "bounded_truncated" : "bounded_full"));
        return recorded;
    }

    private static Decision block(ServerLevel level, Settlement settlement,
                                  RequestRecord request,
                                  RequestBlocker reason, String detail) {
        RequestLedgerSavedData saved = dataIfLive(level, settlement);
        RequestLedger ledger = saved == null ? null : saved.existing(settlement.id);
        if (saved == null || ledger == null || request == null || reason == null
            || reason == RequestBlocker.NONE) {
            return rejected(level, settlement,
                request == null ? null : request.id(), RequestBlocker.MALFORMED,
                detail);
        }
        if (request.state() == RequestState.BLOCKED) {
            return new Decision(Outcome.BLOCKED, request, request.blocker());
        }
        long before = ledger.revision();
        if (!request.block(reason, level.getGameTime())) {
            return rejected(level, settlement, request.id(), reason, detail);
        }
        if (!commit(saved, ledger, request)) {
            return quarantine(level, saved, ledger, request,
                "block_commit_failed");
        }
        if (request.type() == RequestType.OUTPUT_PICKUP) {
            AuthorityTelemetry.emit(level,
                AuthorityTelemetry.Event.OUTPUT_PICKUP_BLOCKED,
                AuthorityTelemetry.Result.COMMITTED,
                AuthorityTelemetry.Fields.state(settlement.id,
                    "request:" + request.id(), before, ledger.revision(),
                    ledger.active().size(), ledger.active().size(), detail));
        }
        return new Decision(Outcome.BLOCKED, request, reason);
    }

    private static RequestBlocker validateForReservation(ServerLevel level,
                                                         Settlement settlement,
                                                         RequestRecord request,
                                                         SettlerEntity courier) {
        Building source = registeredById(settlement, request.sourceBuildingId());
        Building target = registeredById(settlement, request.targetBuildingId());
        RequestBlocker endpoints = validateRecordEndpoints(level, settlement,
            request, source, target);
        if (endpoints != RequestBlocker.NONE) {
            return endpoints;
        }
        Container from = containerAtLoaded(level, request.sourceContainer());
        Container to = containerAtLoaded(level, request.targetContainer());
        if (from == null) {
            return RequestBlocker.SOURCE_INVALID;
        }
        if (to == null) {
            return RequestBlocker.TARGET_INVALID;
        }
        if (request.sourceSlot() >= from.getContainerSize()) {
            return RequestBlocker.SOURCE_INVALID;
        }
        ItemStack sourceStack = from.getItem(request.sourceSlot());
        if (!request.fingerprint().matches(level.registryAccess(), sourceStack)) {
            return sourceStack.isEmpty() ? RequestBlocker.NO_STOCK
                : RequestBlocker.FINGERPRINT_MISMATCH;
        }
        // OUTPUT collection owns its recorded quantity; matching producer growth in the same
        // source slot is surplus, while any shrink below the unclaimed snapshot is unsafe.
        if (sourceStack.getCount() < request.sourceCountBefore() - request.movedCount()) {
            return sourceStack.getCount() < request.fingerprint().count()
                ? RequestBlocker.NO_STOCK : RequestBlocker.FINGERPRINT_MISMATCH;
        }
        if (request.movedCount() == 0 && !bagEmpty(courier)) {
            return RequestBlocker.BAG_FULL;
        }
        if (matchingBagCount(level, courier, request.fingerprint()) != request.movedCount()
            || totalBagCount(courier) != request.movedCount()) {
            return RequestBlocker.FINGERPRINT_MISMATCH;
        }
        if (bagRoomFor(level, courier,
                request.fingerprint().prototype(level.registryAccess()))
                < request.fingerprint().count() - request.movedCount()) {
            return RequestBlocker.BAG_FULL;
        }
        ItemStack prototype = request.fingerprint().prototype(level.registryAccess());
        if (prototype.isEmpty()) {
            return RequestBlocker.FINGERPRINT_MISMATCH;
        }
        prototype.setCount(request.fingerprint().count());
        if (roomForExact(to, prototype) < prototype.getCount()) {
            return RequestBlocker.TARGET_FULL;
        }
        return RequestBlocker.NONE;
    }

    private static RequestBlocker validateInTransit(ServerLevel level,
                                                    Settlement settlement,
                                                    RequestRecord request,
                                                    @Nullable Building targetBuilding,
                                                    SettlerEntity courier) {
        if (request.type() == RequestType.AMMUNITION) {
            if (!ammunitionBoundsLoaded(level, targetBuilding)) return RequestBlocker.TARGET_UNLOADED;
            if (ammunitionDeficit(level, settlement, targetBuilding) < request.remainingCount()) return RequestBlocker.TARGET_FULL;
        } else if (request.type() == RequestType.MATERIAL_INPUT) {
            ItemStack exact = request.fingerprint().prototype(level.registryAccess());
            if (materialInputDeficit(level, settlement, targetBuilding, request.targetContainer(), exact) < request.remainingCount()) return RequestBlocker.TARGET_FULL;
        }
        if (!level.hasChunkAt(request.targetContainer())) {
            return RequestBlocker.TARGET_UNLOADED;
        }
        Container target = containerAtLoaded(level, request.targetContainer());
        if (target == null) {
            return RequestBlocker.TARGET_INVALID;
        }
        if (matchingBagCount(level, courier, request.fingerprint())
                != request.remainingCount()) {
            return RequestBlocker.FINGERPRINT_MISMATCH;
        }
        ItemStack remaining = request.fingerprint().prototype(level.registryAccess());
        if (remaining.isEmpty()) {
            return RequestBlocker.FINGERPRINT_MISMATCH;
        }
        remaining.setCount(request.remainingCount());
        return roomForExact(target, remaining) <= 0
            ? RequestBlocker.TARGET_FULL : RequestBlocker.NONE;
    }

    private static RequestBlocker validateRecordEndpoints(
            ServerLevel level, Settlement settlement, RequestRecord request,
            @Nullable Building source, @Nullable Building target) {
        if (!request.settlementId().equals(settlement.id)
            || !request.dimensionId().equals(level.dimension().location())) {
            return RequestBlocker.CROSS_SETTLEMENT;
        }
        if (request.type() == RequestType.FOOD) {
            return request.targetBuildingId().equals(settlement.id)
                ? validateFoodEndpoints(level, settlement, source,
                    request.sourceContainer(), request.targetContainer())
                : RequestBlocker.TARGET_INVALID;
        }
        if (request.type() == RequestType.AMMUNITION) {
            return validateAmmunitionEndpoints(level, settlement, source,
                request.sourceContainer(), target,
                request.targetContainer());
        }
        if (request.type() == RequestType.MATERIAL_INPUT) {
            return validateMaterialInputEndpoints(level, settlement, source,
                request.sourceContainer(), target, request.targetContainer());
        }
        return validateEndpoints(level, settlement, source,
            request.sourceContainer(), target, request.targetContainer());
    }

    private static RequestBlocker validateFoodEndpoints(ServerLevel level, Settlement settlement,
                                                       Building source, BlockPos sourcePos,
                                                       BlockPos targetPos) {
        if (!exactRegistered(settlement, source) || source == null || !source.valid
            || source.type != BuildingType.WAREHOUSE || sourcePos == null
            || !source.contains(sourcePos)) {
            return RequestBlocker.SOURCE_INVALID;
        }
        if (!settlement.center.equals(targetPos)) return RequestBlocker.TARGET_INVALID;
        if (!level.hasChunkAt(sourcePos)) return RequestBlocker.SOURCE_UNLOADED;
        if (!level.hasChunkAt(targetPos)) return RequestBlocker.TARGET_UNLOADED;
        if (!WarehouseIndex.containers(level, source).contains(sourcePos)) return RequestBlocker.SOURCE_INVALID;
        return level.getBlockEntity(targetPos) instanceof HearthBlockEntity hearth
            && settlement.id.equals(hearth.getSettlementId())
            ? RequestBlocker.NONE : RequestBlocker.TARGET_INVALID;
    }

    private static boolean targetContact(ServerLevel level, SettlerEntity courier, RequestRecord request) {
        if (request.type() != RequestType.FOOD) {
            return ContainerApproach.inspect(level, courier, request.targetContainer()).canInteract();
        }
        // Hearth is not a Container. FOOD routes and the mutation boundary
        // share one exact centre-range plus ray contract.
        return hasFoodHearthContact(level, courier, request.targetContainer());
    }

    /** Live deficit shared by food selection and its final pickup preflight. */
    public static int foodDeficit(ServerLevel level, Settlement settlement, ItemStack exact) {
        if (!(level.getBlockEntity(settlement.center) instanceof HearthBlockEntity hearth)) return 0;
        var assessment = RecruitmentPolicy.assess(level, settlement, RecruitmentPolicy.stageFor(settlement));
        int meals = Math.max(0, assessment.courierReadyFoodTarget() - hearth.countFoodUnits());
        int price = RecruitmentPolicy.missingReadyFoodPrices(hearth.getInventory(), assessment.price()).stream()
            .filter(need -> need.matches(exact)).mapToInt(RecruitmentPolicy.ReadyPriceNeed::missing).max().orElse(0);
        return Math.max(meals, price);
    }

    /**
     * Buildings whose chests the Courier keeps stocked with Arrows: the
     * Hunter's Lodge and the Watchtower an Archer refills from. Without the
     * Watchtower an employed Archer stays "out of arrows" forever unless a
     * player stocks the tower chest by hand.
     */
    public static boolean ammunitionTarget(@Nullable Building building) {
        return building != null && (building.type == BuildingType.HUNTERS_LODGE
            || building.type == BuildingType.WATCHTOWER);
    }

    private static boolean hasArmedArcher(ServerLevel level, Building tower) {
        for (java.util.UUID worker : tower.workers) {
            if (level.getEntity(worker) instanceof SettlerEntity archer
                && archer.isAlive()
                && archer.getProfession() == com.hearthstead.entity.Profession.ARCHER
                && archer.getMainHandItem().is(Items.BOW)) {
                return true;
            }
        }
        return false;
    }

    /** Current physical Arrow deficit across every loaded container in one Lodge. */
    public static int ammunitionDeficit(ServerLevel level,
                                        Settlement settlement,
                                        @Nullable Building lodge) {
        if (!liveReadOnly(level, settlement)
            || !exactRegistered(settlement, lodge) || lodge == null
            || !lodge.valid || !ammunitionTarget(lodge)
            || !ammunitionBoundsLoaded(level, lodge)) {
            return 0;
        }
        // A Watchtower is only stocked for an Archer who can shoot from it.
        // An unstaffed or unarmed tower must not claim the Courier (e.g. in
        // the very tick it registers, ahead of that Archer's own bow).
        if (lodge.type == BuildingType.WATCHTOWER && !hasArmedArcher(level, lodge)) {
            return 0;
        }
        long arrows = 0L;
        for (BlockPos pos : WarehouseIndex.containers(level, lodge)) {
            Container container = containerAtLoaded(level, pos);
            if (container == null) {
                return 0;
            }
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                // The Lodge cap counts every vanilla Arrow, independent of
                // components. The request fingerprint still owns only the
                // exact stack selected at its Warehouse source.
                if (stack.is(Items.ARROW)) {
                    arrows += stack.getCount();
                }
            }
        }
        return Math.max(0, SettlerEntity.ARCHER_QUIVER_CAPACITY
            - (int) Math.min(Integer.MAX_VALUE, arrows));
    }

    /** Current physical deficit for one allowed Tavern service-input class. */
    private static int materialInputDeficit(ServerLevel level, Settlement settlement,
                                            @Nullable Building tavern, BlockPos targetContainer,
                                            ItemStack candidate) {
        if (tavern != null && tavern.type == BuildingType.BUILDERS_HUT) {
            // BUILDER lane: construction stock, same live-room rule as the post.
            if (!staffedBuildersHut(level, settlement, tavern)
                || !WarehouseIndex.containers(level, tavern).contains(targetContainer)) return 0;
            return roomForExact(containerAtLoaded(level, targetContainer), candidate);
        }
        if (tavern != null && tavern.type == BuildingType.TRADING_POST) {
            if (!staffedTradingPost(level, settlement, tavern)
                || !WarehouseIndex.containers(level, tavern).contains(targetContainer)) return 0;
            return roomForExact(containerAtLoaded(level, targetContainer), candidate);
        }
        TavernHostService.RestockNeed need = TavernHostService.restockNeed(level,
            settlement, tavern, candidate);
        // The row names a single persisted service store. Do not reroute an
        // in-flight prop into a newly preferred Tavern barrel.
        return need == null || !need.container().equals(targetContainer) ? 0 : need.deficit();
    }

    /** Warehouse -> currently staffed Tavern only; neither endpoint is generic storage. */
    private static RequestBlocker validateMaterialInputEndpoints(
            ServerLevel level, Settlement settlement, @Nullable Building source,
            BlockPos sourcePos, @Nullable Building tavern, BlockPos targetPos) {
        if (!exactRegistered(settlement, source) || source == null || !source.valid
            || source.type != BuildingType.WAREHOUSE || sourcePos == null
            || !source.contains(sourcePos)) return RequestBlocker.SOURCE_INVALID;
        if (!exactRegistered(settlement, tavern) || tavern == null || !tavern.valid
            || (tavern.type != BuildingType.TAVERN && tavern.type != BuildingType.TRADING_POST
                && tavern.type != BuildingType.BUILDERS_HUT) || source.id.equals(tavern.id)
            || targetPos == null || !tavern.contains(targetPos)) return RequestBlocker.TARGET_INVALID;
        if (!WarehouseIndex.fullyLoaded(level, source)) return RequestBlocker.SOURCE_UNLOADED;
        if (!WarehouseIndex.fullyLoaded(level, tavern)) return RequestBlocker.TARGET_UNLOADED;
        if (!WarehouseIndex.containers(level, source).contains(sourcePos)) return RequestBlocker.SOURCE_INVALID;
        if (!WarehouseIndex.containers(level, tavern).contains(targetPos)) return RequestBlocker.TARGET_INVALID;
        if (tavern.type == BuildingType.BUILDERS_HUT) {
            return staffedBuildersHut(level, settlement, tavern)
                ? RequestBlocker.NONE : RequestBlocker.TARGET_INVALID;
        }
        return (tavern.type == BuildingType.TRADING_POST
            ? staffedTradingPost(level, settlement, tavern) : TavernSeating.staffed(level, settlement, tavern))
            ? RequestBlocker.NONE : RequestBlocker.TARGET_INVALID;
    }
    /** BUILDER lane: a valid hut with its own hired, alive Builder. */
    private static boolean staffedBuildersHut(ServerLevel level, Settlement settlement, Building hut) {
        if (settlement == null || hut == null || !hut.valid || hut.type != BuildingType.BUILDERS_HUT
            || hut.bounds == null) return false;
        for (UUID workerId : hut.workers) {
            if (level.getEntity(workerId) instanceof SettlerEntity builder && builder.isAlive()
                && settlement.id.equals(builder.getSettlementId()) && builder.getProfession() == Profession.BUILDER
                && Employment.employerOf(settlement, workerId) == hut) return true;
        }
        return false;
    }
    private static boolean staffedTradingPost(ServerLevel level, Settlement settlement, Building post) {
        if (settlement == null || post == null || !post.valid || post.type != BuildingType.TRADING_POST
            || post.bounds == null || !level.hasChunkAt(post.plaquePos)
            || !(level.getBlockState(post.plaquePos).getBlock() instanceof com.hearthstead.block.PlaqueBlock)) return false;
        for (UUID workerId : post.workers) {
            if (level.getEntity(workerId) instanceof SettlerEntity trader && trader.isAlive()
                && settlement.id.equals(trader.getSettlementId()) && trader.getProfession() == Profession.TRADER
                && Employment.employerOf(settlement, workerId) == post) return true;
        }
        return false;
    }

    private static RequestBlocker validateAmmunitionEndpoints(
            ServerLevel level, Settlement settlement,
            @Nullable Building source, BlockPos sourcePos,
            @Nullable Building target, BlockPos targetPos) {
        if (!exactRegistered(settlement, source) || source == null
            || !source.valid || source.type != BuildingType.WAREHOUSE) {
            return RequestBlocker.SOURCE_INVALID;
        }
        if (!exactRegistered(settlement, target) || target == null
            || !target.valid || !ammunitionTarget(target)
            || source.id.equals(target.id)) {
            return RequestBlocker.TARGET_INVALID;
        }
        if (sourcePos == null || !source.contains(sourcePos)) {
            return RequestBlocker.SOURCE_INVALID;
        }
        if (targetPos == null || !target.contains(targetPos)) {
            return RequestBlocker.TARGET_INVALID;
        }
        if (!ammunitionBoundsLoaded(level, source)) {
            return RequestBlocker.SOURCE_UNLOADED;
        }
        if (!ammunitionBoundsLoaded(level, target)) {
            return RequestBlocker.TARGET_UNLOADED;
        }
        if (!WarehouseIndex.containers(level, source).contains(sourcePos)) {
            return RequestBlocker.SOURCE_INVALID;
        }
        if (!WarehouseIndex.containers(level, target).contains(targetPos)) {
            return RequestBlocker.TARGET_INVALID;
        }
        return RequestBlocker.NONE;
    }

    private static RequestBlocker validateAmmunitionSource(
            ServerLevel level, Settlement settlement,
            @Nullable Building source, BlockPos sourcePos) {
        if (!exactRegistered(settlement, source) || source == null
            || !source.valid || source.type != BuildingType.WAREHOUSE
            || sourcePos == null || !source.contains(sourcePos)) {
            return RequestBlocker.SOURCE_INVALID;
        }
        if (!ammunitionBoundsLoaded(level, source)) {
            return RequestBlocker.SOURCE_UNLOADED;
        }
        return WarehouseIndex.containers(level, source).contains(sourcePos)
            ? RequestBlocker.NONE : RequestBlocker.SOURCE_INVALID;
    }

    /**
     * AMMUNITION scans are allowed only when every chunk intersecting the
     * bounded room is already resident. This preflight itself never requests
     * a chunk and therefore keeps selection, deficit checks and recovery from
     * force-loading a distant part of a building.
     */
    public static boolean ammunitionBoundsLoaded(ServerLevel level,
                                                  @Nullable Building building) {
        if (level == null || building == null || building.bounds == null) {
            return false;
        }
        long sizeX = (long) building.bounds.maxX() - building.bounds.minX() + 1L;
        long sizeY = (long) building.bounds.maxY() - building.bounds.minY() + 1L;
        long sizeZ = (long) building.bounds.maxZ() - building.bounds.minZ() + 1L;
        if (sizeX <= 0L || sizeY <= 0L || sizeZ <= 0L
            || sizeX > RoomScanner.MAX_EXTENT * 2L + 1L
            || sizeY > RoomScanner.MAX_HEIGHT * 2L + 1L
            || sizeZ > RoomScanner.MAX_EXTENT * 2L + 1L) {
            return false;
        }
        int minChunkX = building.bounds.minX() >> 4;
        int maxChunkX = building.bounds.maxX() >> 4;
        int minChunkZ = building.bounds.minZ() >> 4;
        int maxChunkZ = building.bounds.maxZ() >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static RequestBlocker validateEndpoints(ServerLevel level,
                                                    Settlement settlement,
                                                    @Nullable Building source,
                                                    BlockPos sourcePos,
                                                    @Nullable Building target,
                                                    BlockPos targetPos) {
        // Commercial staging is never an OUTPUT_PICKUP source, including
        // queued/reloaded routes which do not pass through Courier's fresh scan.
        // Blocking leaves any in-flight parcel under its existing recovery owner.
        if (source != null && (source.type == BuildingType.TRADING_POST
                || source.type == BuildingType.BUILDERS_HUT)) {
            // BUILDER lane: delivered construction stock is not output either.
            return RequestBlocker.SOURCE_INVALID;
        }
        boolean storageSort = source != null && target != null && source == target
            && source.type == BuildingType.WAREHOUSE && sourcePos != null && targetPos != null
            && !sourcePos.equals(targetPos)
            && level.hasChunkAt(sourcePos) && level.hasChunkAt(targetPos)
            && com.hearthstead.settlement.warehouse.WarehouseSorting.assignedGroup(level.getBlockEntity(targetPos)) != null;
        if (!exactRegistered(settlement, source) || source == null || !source.valid
            || source.type == BuildingType.WAREHOUSE && !storageSort
            || !exactRegistered(settlement, target) || target == null
            || !target.valid || target.type != BuildingType.WAREHOUSE
            || source.id.equals(target.id) && !storageSort) {
            return source == null || !exactRegistered(settlement, source)
                ? RequestBlocker.SOURCE_INVALID : RequestBlocker.TARGET_INVALID;
        }
        if (sourcePos == null || !source.contains(sourcePos)) {
            return RequestBlocker.SOURCE_INVALID;
        }
        if (targetPos == null || !target.contains(targetPos)) {
            return RequestBlocker.TARGET_INVALID;
        }
        if (!level.hasChunkAt(sourcePos)) {
            return RequestBlocker.SOURCE_UNLOADED;
        }
        if (!level.hasChunkAt(targetPos)) {
            return RequestBlocker.TARGET_UNLOADED;
        }
        if (!WarehouseIndex.containers(level, source).contains(sourcePos)) {
            return RequestBlocker.SOURCE_INVALID;
        }
        if (!WarehouseIndex.containers(level, target).contains(targetPos)) {
            return RequestBlocker.TARGET_INVALID;
        }
        return RequestBlocker.NONE;
    }

    /**
     * Exact authority preflight for code that is about to create a physical
     * Courier intent. Keeping this in the ledger service prevents callers
     * from opening an unclaimable request that would then reserve real stock
     * forever.
     */
    public static boolean validCourier(ServerLevel level,
                                       Settlement settlement,
                                       @Nullable SettlerEntity courier) {
        if (courier == null || !courier.isAlive() || courier.level() != level
            || level.getEntity(courier.getId()) != courier
            || !settlement.id.equals(courier.getSettlementId())
            || settlement.record(courier.getUUID()) == null
            || Employment.courierWorkplace(settlement, courier) == null) {
            return false;
        }
        Building employer = Employment.courierWorkplace(settlement, courier);
        return exactRegistered(settlement, employer) && employer != null
            && employer.valid && employer.type == BuildingType.WAREHOUSE;
    }

    private static boolean commit(RequestLedgerSavedData saved,
                                  RequestLedger ledger,
                                  RequestRecord request) {
        if (saved == null || ledger == null || request == null
            || !ledger.mutationCommitted(request)) {
            return false;
        }
        saved.setDirty();
        return true;
    }

    private static Decision quarantine(ServerLevel level,
                                       RequestLedgerSavedData saved,
                                       RequestLedger ledger,
                                       RequestRecord request,
                                       String reason) {
        if (ledger != null) {
            ledger.quarantine(reason);
        }
        if (saved != null) {
            saved.setDirty();
        }
        return new Decision(Outcome.QUARANTINED, request,
            RequestBlocker.MALFORMED);
    }

    private static Decision rejected(ServerLevel level,
                                     @Nullable Settlement settlement,
                                     @Nullable UUID requestId,
                                     RequestBlocker blocker, String reason) {
        if (level != null && level.getServer() != null
            && level.getServer().isSameThread()) {
            RequestLedgerSavedData saved = settlement == null ? null
                : RequestLedgerSavedData.existing(level);
            RequestLedger ledger = saved == null ? null
                : saved.existing(settlement.id);
            long revision = ledger == null ? 0L : ledger.revision();
            long count = ledger == null ? 0L : ledger.active().size();
            AuthorityTelemetry.emit(level,
                AuthorityTelemetry.Event.AUTHORITY_REJECTED,
                AuthorityTelemetry.Result.REJECTED,
                AuthorityTelemetry.Fields.state(
                    settlement == null ? null : settlement.id,
                    requestId == null ? "request:none" : "request:" + requestId,
                    revision, revision, count, count, reason));
        }
        return new Decision(Outcome.REJECTED, null,
            blocker == null ? RequestBlocker.MALFORMED : blocker);
    }

    private static void noteSatisfied(ServerLevel level, Settlement settlement,
                                      Building source, Building target,
                                      SettlerEntity courier,
                                      RequestRecord request,
                                      long ledgerRevision) {
        if (request.type() != RequestType.OUTPUT_PICKUP) return;
        if (request.type() == RequestType.OUTPUT_PICKUP) {
            AuthorityTelemetry.emit(level,
                AuthorityTelemetry.Event.OUTPUT_PICKUP_SATISFIED,
                AuthorityTelemetry.Result.COMMITTED,
                AuthorityTelemetry.Fields.items(settlement.id,
                    "request:" + request.id(), Math.max(0L, ledgerRevision - 1L),
                    ledgerRevision, request.fingerprint().count(),
                    request.deliveredCount(),
                    request.fingerprint().itemId().toString(),
                    request.fingerprint().count(), request.deliveredCount(), 0,
                    "courier:" + courier.getUUID()));
        }
        if (source.type == BuildingType.WAREHOUSE) return; // Sorting is not new production/progression.
        JourneyServerHooks.noteOutputPickupSatisfied(level, settlement,
            source, target, courier, request);
    }

    @Nullable
    private static RequestLedgerSavedData dataIfLive(ServerLevel level,
                                                     Settlement settlement) {
        return live(level, settlement) ? RequestLedgerSavedData.get(level) : null;
    }

    private static boolean live(ServerLevel level, Settlement settlement) {
        return level != null && settlement != null && level.getServer() != null
            && level.getServer().isSameThread()
            && SettlementManager.byId(level, settlement.id) == settlement;
    }

    /** Exact root lookup for projections that promise zero reconciliation. */
    private static boolean liveReadOnly(ServerLevel level,
                                        Settlement settlement) {
        if (level == null || settlement == null || level.getServer() == null
            || !level.getServer().isSameThread()) {
            return false;
        }
        SettlementSavedData data = SettlementSavedData.existing(level);
        return data != null && settlement.id != null
            && data.settlements.get(settlement.id) == settlement;
    }

    private static boolean exactRegistered(Settlement settlement,
                                           @Nullable Building building) {
        if (settlement == null || building == null) {
            return false;
        }
        int identities = 0;
        boolean same = false;
        for (Building registered : settlement.buildings) {
            if (registered == null || registered.id == null
                || building.id == null) {
                return false;
            }
            if (registered.id.equals(building.id)) {
                identities++;
                same |= registered == building;
            }
        }
        return same && identities == 1;
    }

    @Nullable
    private static Building registeredById(Settlement settlement, UUID id) {
        if (settlement == null || id == null) {
            return null;
        }
        Building found = null;
        for (Building building : settlement.buildings) {
            if (building == null || building.id == null) {
                return null;
            }
            if (building.id.equals(id)) {
                if (found != null) {
                    return null;
                }
                found = building;
            }
        }
        return found;
    }

    @Nullable
    private static Container containerAtLoaded(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null || !level.hasChunkAt(pos)) {
            return null;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof Container container) return container;
        if (!(blockEntity instanceof HearthBlockEntity hearth)) return null;
        // A live view only: all writes reach the original handler and its dirty callback.
        var inventory = hearth.getInventory();
        return new net.minecraft.world.SimpleContainer(inventory.getSlots()) {
            @Override public ItemStack getItem(int slot) { return inventory.getStackInSlot(slot); }
            @Override public void setItem(int slot, ItemStack stack) { inventory.setStackInSlot(slot, stack); }
            @Override public ItemStack removeItem(int slot, int count) { return inventory.extractItem(slot, count, false); }
            @Override public ItemStack removeItemNoUpdate(int slot) { return inventory.extractItem(slot, Integer.MAX_VALUE, false); }
            @Override public void setChanged() { hearth.setChanged(); }
            @Override public boolean isEmpty() {
                for (int slot = 0; slot < inventory.getSlots(); slot++) {
                    if (!inventory.getStackInSlot(slot).isEmpty()) return false;
                }
                return true;
            }
            @Override public void clearContent() {
                for (int slot = 0; slot < inventory.getSlots(); slot++) inventory.setStackInSlot(slot, ItemStack.EMPTY);
            }
        };
    }

    private static int exactSourceCount(ServerLevel level,
                                        @Nullable Container source,
                                        RequestRecord request) {
        if (source == null || request.sourceSlot() < 0
            || request.sourceSlot() >= source.getContainerSize()) {
            return -1;
        }
        ItemStack stack = source.getItem(request.sourceSlot());
        if (stack.isEmpty()) {
            return 0;
        }
        return request.fingerprint().matches(level.registryAccess(), stack)
            ? stack.getCount() : -1;
    }

    private static int matchingCount(ServerLevel level, Container container,
                                     RequestItemFingerprint fingerprint) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (fingerprint.matches(level.registryAccess(), stack)) {
                count = saturatingAdd(count, stack.getCount());
            }
        }
        return count;
    }

    private static int matchingBagCount(ServerLevel level,
                                        SettlerEntity courier,
                                        RequestItemFingerprint fingerprint) {
        int count = 0;
        for (int slot = 0; slot < courier.bag.getContainerSize(); slot++) {
            ItemStack stack = courier.bag.getItem(slot);
            if (fingerprint.matches(level.registryAccess(), stack)) {
                count = saturatingAdd(count, stack.getCount());
            }
        }
        return count;
    }

    private static int totalBagCount(SettlerEntity courier) {
        int count = 0;
        for (int slot = 0; slot < courier.bag.getContainerSize(); slot++) {
            count = saturatingAdd(count, courier.bag.getItem(slot).getCount());
        }
        return count;
    }

    // ------------------------------------------- Stout Straps batching ---

    /**
     * Forms a batch: the Courier has just lifted request A completely (A is
     * IN_TRANSIT and the bag holds exactly A). A's cargo moves to the
     * Courier's {@code batchStow}; then ONE open request B whose source
     * container is within {@link CourierBatching#PARTNER_RADIUS} blocks of
     * {@code near}, of a different item, that fits the remaining trip budget,
     * is reserved through the ordinary {@link #reserve} (all its physical
     * checks; the bag is empty again). If nothing is reserved, A's cargo moves
     * straight back into the bag. Returns COMMITTED with B, or NOT_FOUND with
     * the bag exactly as it was.
     */
    public static Decision reserveBatchPartner(ServerLevel level, Settlement settlement,
                                               SettlerEntity courier, BlockPos near) {
        RequestLedgerSavedData saved = dataIfLive(level, settlement);
        RequestLedger ledger = saved == null ? null : saved.existing(settlement.id);
        if (saved == null || ledger == null || saved.rootQuarantined() || ledger.quarantined()
            || near == null || !CourierBatching.allowed(level, settlement)
            || !validCourier(level, settlement, courier) || !courier.batchStow.isEmpty()) {
            return new Decision(Outcome.NOT_FOUND, null, RequestBlocker.NONE);
        }
        List<RequestRecord> owned = ledger.activeForCourier(courier.getUUID());
        if (owned.size() != 1) {
            return new Decision(Outcome.NOT_FOUND, null, RequestBlocker.NONE);
        }
        RequestRecord held = owned.getFirst();
        int load = held.remainingCount();
        if (!CourierBatching.batchable(held.type()) || storageSort(held)
            || held.state() != RequestState.IN_TRANSIT
            || held.deliveredCount() != 0 || load <= 0
            || matchingBagCount(level, courier, held.fingerprint()) != load
            || totalBagCount(courier) != load
            || courier.getCarryCapacity() - load <= 0) {
            return new Decision(Outcome.NOT_FOUND, null, RequestBlocker.NONE);
        }
        List<RequestRecord> candidates = new ArrayList<>();
        for (RequestRecord row : ledger.active()) {
            if (row.effectiveState() != RequestState.OPEN || !CourierBatching.batchable(row.type())
                || storageSort(row)
                || !row.dimensionId().equals(level.dimension().location())
                || !CourierBatching.near(row.sourceContainer().distSqr(near))
                || !CourierBatching.fits(load, row.fingerprint().count(), courier.getCarryCapacity())) {
                continue;
            }
            ItemStack partner = row.fingerprint().prototype(level.registryAccess());
            if (partner.isEmpty() || held.fingerprint().matches(level.registryAccess(), partner)) {
                continue; // one item kind per request, so every bag item has one owner
            }
            candidates.add(row);
        }
        if (candidates.isEmpty()) {
            return new Decision(Outcome.NOT_FOUND, null, RequestBlocker.NONE);
        }
        candidates.sort(Comparator.comparing(RequestRecord::priority).reversed()
            .thenComparingDouble(row -> row.sourceContainer().distSqr(near))
            .thenComparingLong(RequestRecord::createdAt)
            .thenComparing(row -> row.id().toString()));
        if (!moveAllSlots(courier.bag, courier.batchStow)
            || !stowHoldsExactly(level, courier, held) || totalBagCount(courier) != 0) {
            // Never guess: put everything back exactly where it was.
            moveAllSlots(courier.batchStow, courier.bag);
            return new Decision(Outcome.NOT_FOUND, null, RequestBlocker.NONE);
        }
        int tried = 0;
        for (RequestRecord candidate : candidates) {
            if (tried++ >= 4) {
                break; // bounded: a batch is a bonus, never a scan
            }
            Decision decision = reserve(level, settlement, candidate.id(), courier);
            if (decision.outcome() == Outcome.COMMITTED) {
                return decision;
            }
            if (saved.rootQuarantined() || ledger.quarantined()) {
                break;
            }
        }
        moveAllSlots(courier.batchStow, courier.bag);
        return new Decision(Outcome.NOT_FOUND, null, RequestBlocker.NONE);
    }

    /** The second reservation of a batch: only in the exact stowed state. */
    private static boolean batchSlotOpen(ServerLevel level, Settlement settlement,
                                         SettlerEntity courier, List<RequestRecord> owned,
                                         RequestRecord request) {
        if (owned.size() != 1 || owned.size() >= CourierBatching.maxNewOwned(level, settlement)) {
            return false;
        }
        RequestRecord held = owned.getFirst();
        if (held.id().equals(request.id()) || !CourierBatching.batchable(held.type())
            || !CourierBatching.batchable(request.type()) || storageSort(held) || storageSort(request)
            || held.state() != RequestState.IN_TRANSIT || held.deliveredCount() != 0
            || !stowHoldsExactly(level, courier, held) || totalBagCount(courier) != 0
            || !CourierBatching.fits(held.remainingCount(), request.fingerprint().count(),
                courier.getCarryCapacity())) {
            return false;
        }
        ItemStack partner = request.fingerprint().prototype(level.registryAccess());
        return !partner.isEmpty() && !held.fingerprint().matches(level.registryAccess(), partner);
    }

    /**
     * Recovery's view of a Courier's ownership with the stow applied: returns
     * the requests to route (the stowed one of a pair is held back), moves a
     * stowed load back into an empty bag once it is the only thing left, and
     * returns null after quarantining an impossible state (more than
     * {@link CourierBatching#MAX_OWNED} owned, or a pair without its stow).
     * Without a stow this returns {@code owned} unchanged.
     */
    @Nullable
    private static List<RequestRecord> settleBatchStow(ServerLevel level, RequestLedgerSavedData saved,
                                                       RequestLedger ledger, SettlerEntity courier,
                                                       List<RequestRecord> owned) {
        if (!CourierBatching.ownershipAllowed(owned.size())) {
            quarantine(level, saved, ledger, owned.getFirst(), "courier_double_ownership");
            return null;
        }
        if (courier.batchStow.isEmpty()) {
            if (owned.size() > 1) {
                quarantine(level, saved, ledger, owned.getFirst(), "batch_pair_without_stow");
                return null;
            }
            return owned;
        }
        RequestRecord stowed = null;
        for (RequestRecord row : owned) {
            if (stowHoldsExactly(level, courier, row)) {
                stowed = row;
                break;
            }
        }
        if (owned.size() > 1) {
            if (stowed == null) {
                quarantine(level, saved, ledger, owned.getFirst(), "batch_stow_mismatch");
                return null;
            }
            List<RequestRecord> working = new ArrayList<>(owned);
            working.remove(stowed);
            return List.copyOf(working);
        }
        // One (or no) request left: the waiting load comes back into an empty
        // bag. A stow nobody owns becomes ordinary leftover cargo, exactly like
        // a bag whose request ended; a stow beside unexplained bag items waits.
        if ((stowed != null || owned.isEmpty()) && totalBagCount(courier) == 0) {
            moveAllSlots(courier.batchStow, courier.bag);
        }
        return owned;
    }

    /**
     * A Courier's Warehouse tidy move: one Warehouse is both source and
     * target (the ledger's storage-sort route). Tidying is never batched.
     */
    public static boolean storageSort(RequestRecord row) {
        return row != null && row.sourceBuildingId().equals(row.targetBuildingId());
    }

    /**
     * One tidy lease per chest: true when ANOTHER Courier owns a live tidy
     * move out of or into {@code pos}. Read-only; a bounded pass over the
     * active rows (at most {@link RequestLedger#MAX_ACTIVE}).
     */
    public static boolean tidyLeased(ServerLevel level, Settlement settlement, BlockPos pos,
                                     SettlerEntity courier) {
        RequestLedgerSavedData saved = dataIfLive(level, settlement);
        RequestLedger ledger = saved == null ? null : saved.existing(settlement.id);
        if (ledger == null || pos == null) {
            return false;
        }
        for (RequestRecord row : ledger.active()) {
            UUID owner = row.courierId();
            if (owner != null && (courier == null || !owner.equals(courier.getUUID()))
                && storageSort(row) && !row.state().terminal()
                && (pos.equals(row.sourceContainer()) || pos.equals(row.targetContainer()))) {
                return true;
            }
        }
        return false;
    }

    /** True when the stow holds exactly {@code request}'s remaining cargo and nothing else. */
    private static boolean stowHoldsExactly(ServerLevel level, SettlerEntity courier,
                                            RequestRecord request) {
        int matching = 0;
        int total = 0;
        for (int slot = 0; slot < courier.batchStow.getContainerSize(); slot++) {
            ItemStack stack = courier.batchStow.getItem(slot);
            total = saturatingAdd(total, stack.getCount());
            if (request.fingerprint().matches(level.registryAccess(), stack)) {
                matching = saturatingAdd(matching, stack.getCount());
            }
        }
        return CourierBatching.stowHolds(matching, total, request.remainingCount());
    }

    /**
     * Moves every stack slot-for-slot into an EMPTY container of the same
     * size: an exact, lossless transfer or nothing at all.
     */
    private static boolean moveAllSlots(net.minecraft.world.SimpleContainer from,
                                        net.minecraft.world.SimpleContainer to) {
        if (!to.isEmpty() || from.getContainerSize() != to.getContainerSize()) {
            return false;
        }
        for (int slot = 0; slot < from.getContainerSize(); slot++) {
            ItemStack stack = from.removeItemNoUpdate(slot);
            if (!stack.isEmpty()) {
                to.setItem(slot, stack);
            }
        }
        from.setChanged();
        to.setChanged();
        return true;
    }

    /** Settler sheet / watchdog: how many items wait in the Courier's stow. */
    public static int stowedCount(@Nullable SettlerEntity courier) {
        if (courier == null) {
            return 0;
        }
        int total = 0;
        for (int slot = 0; slot < courier.batchStow.getContainerSize(); slot++) {
            total = saturatingAdd(total, courier.batchStow.getItem(slot).getCount());
        }
        return total;
    }

    private static boolean bagEmpty(SettlerEntity courier) {
        for (int slot = 0; slot < courier.bag.getContainerSize(); slot++) {
            if (!courier.bag.getItem(slot).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static int bagRoomFor(ServerLevel level, SettlerEntity courier,
                                  ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        int itemCount = 0;
        int weight = 0;
        int inventoryRoom = 0;
        for (int slot = 0; slot < courier.bag.getContainerSize(); slot++) {
            ItemStack existing = courier.bag.getItem(slot);
            itemCount = saturatingAdd(itemCount, existing.getCount());
            weight = saturatingAdd(weight,
                Weight.of(existing, existing.getCount()));
            if (existing.isEmpty()) {
                inventoryRoom = saturatingAdd(inventoryRoom,
                    Math.min(courier.bag.getMaxStackSize(), stack.getMaxStackSize()));
            } else if (ItemStack.isSameItemSameComponents(existing, stack)) {
                inventoryRoom = saturatingAdd(inventoryRoom,
                    Math.max(0, Math.min(courier.bag.getMaxStackSize(),
                        existing.getMaxStackSize()) - existing.getCount()));
            }
        }
        // A stowed batch load (Stout Straps) shares this trip's count and
        // weight budget; it never shares bag slots. Empty outside a batch.
        for (int slot = 0; slot < courier.batchStow.getContainerSize(); slot++) {
            ItemStack stowed = courier.batchStow.getItem(slot);
            itemCount = saturatingAdd(itemCount, stowed.getCount());
            weight = saturatingAdd(weight, Weight.of(stowed, stowed.getCount()));
        }
        int byCount = Math.max(0, courier.getCarryCapacity() - itemCount);
        int unitWeight = Weight.of(stack);
        int byWeight = unitWeight <= 0 ? byCount
            : Math.max(0, Weight.budgetFor(courier.getCarryCapacity()) - weight) / unitWeight;
        return Math.min(inventoryRoom, Math.min(byCount, byWeight));
    }

    private static int roomForExact(Container container, ItemStack incoming) {
        if (container == null || incoming == null || incoming.isEmpty()) {
            return 0;
        }
        int room = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty()) {
                room = saturatingAdd(room, Math.min(container.getMaxStackSize(),
                    incoming.getMaxStackSize()));
            } else if (ItemStack.isSameItemSameComponents(existing, incoming)) {
                room = saturatingAdd(room, Math.max(0,
                    Math.min(container.getMaxStackSize(), existing.getMaxStackSize())
                        - existing.getCount()));
            }
        }
        return room;
    }

    private static ItemStack removeMatchingFromBag(ServerLevel level,
                                                   SettlerEntity courier,
                                                   RequestItemFingerprint fingerprint,
                                                   int wanted) {
        ItemStack removed = ItemStack.EMPTY;
        int remaining = Math.max(0, wanted);
        for (int slot = 0; slot < courier.bag.getContainerSize()
                && remaining > 0; slot++) {
            ItemStack stack = courier.bag.getItem(slot);
            if (!fingerprint.matches(level.registryAccess(), stack)) {
                continue;
            }
            ItemStack part = courier.bag.removeItem(slot,
                Math.min(remaining, stack.getCount()));
            if (part.isEmpty()) {
                continue;
            }
            if (removed.isEmpty()) {
                removed = part;
            } else {
                removed.grow(part.getCount());
            }
            remaining -= part.getCount();
        }
        return removed;
    }

    private static ItemStack removeMatchingFromContainer(
            ServerLevel level, Container container,
            RequestItemFingerprint fingerprint, int wanted) {
        ItemStack removed = ItemStack.EMPTY;
        int remaining = Math.max(0, wanted);
        for (int slot = container.getContainerSize() - 1;
                slot >= 0 && remaining > 0; slot--) {
            ItemStack stack = container.getItem(slot);
            if (!fingerprint.matches(level.registryAccess(), stack)) {
                continue;
            }
            ItemStack part = container.removeItem(slot,
                Math.min(remaining, stack.getCount()));
            if (part.isEmpty()) {
                continue;
            }
            if (removed.isEmpty()) {
                removed = part;
            } else {
                removed.grow(part.getCount());
            }
            remaining -= part.getCount();
        }
        container.setChanged();
        return removed;
    }

    private static void giveBack(Container container, int slot,
                                 ItemStack stack) {
        if (container == null || stack == null || stack.isEmpty()
            || slot < 0 || slot >= container.getContainerSize()) {
            return;
        }
        ItemStack existing = container.getItem(slot);
        if (existing.isEmpty()) {
            container.setItem(slot, stack);
        } else if (ItemStack.isSameItemSameComponents(existing, stack)) {
            existing.grow(stack.getCount());
            container.setItem(slot, existing);
        }
        container.setChanged();
    }

    private static boolean targetOwnsFullDelivery(ServerLevel level,
                                                  RequestRecord request) {
        Container target = containerAtLoaded(level, request.targetContainer());
        // Food may have been eaten between partial deposits. Each recorded deposit
        // already proves its exact bag/target delta in one server-thread commit.
        if (request.type() == RequestType.FOOD
            || request.type() == RequestType.AMMUNITION
            || request.type() == RequestType.MATERIAL_INPUT) {
            return target != null
                && request.deliveredCount() == request.fingerprint().count();
        }
        if (request.sourceContainer().equals(request.targetContainer())) {
            // Legacy self-loop row (opened before openOutputPickup refused it):
            // the items went back where they came from, and each deposit
            // already proved its exact bag/target delta.
            return target != null
                && request.deliveredCount() == request.fingerprint().count();
        }
        // Every deposit already proved its exact bag -> target delta in one
        // server-thread commit (deliver). Re-reading the whole target
        // against the count seen when the row OPENED is not a stronger proof:
        // it fails whenever anyone legitimately withdrew from that shared
        // Warehouse chest in between (a food run to the Hearth, a crafter
        // restock), and that failure quarantined the entire settlement ledger
        // and froze every Courier (soak 2026-09-25). Same rule as the food,
        // ammunition and material rows above.
        return target != null
            && request.deliveredCount() == request.fingerprint().count();
    }

    private static int saturatingAdd(int left, int right) {
        if (right <= 0) {
            return Math.max(0, left);
        }
        return left > Integer.MAX_VALUE - right
            ? Integer.MAX_VALUE : left + right;
    }

    private RequestLedgerService() {
    }
}
