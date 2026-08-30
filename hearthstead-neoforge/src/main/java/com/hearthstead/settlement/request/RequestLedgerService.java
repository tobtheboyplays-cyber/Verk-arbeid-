package com.hearthstead.settlement.request;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.logistics.Weight;
import com.hearthstead.menu.HearthMenu;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.journey.JourneyServerHooks;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import com.hearthstead.settlement.warehouse.WarehouseStorage;
import com.hearthstead.util.AuthorityTelemetry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

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
        if (!owned.isEmpty()) {
            return block(level, settlement, request,
                RequestBlocker.COURIER_UNAVAILABLE, "courier_already_owns_request");
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
        AuthorityTelemetry.emit(level,
            AuthorityTelemetry.Event.OUTPUT_PICKUP_RESERVED,
            AuthorityTelemetry.Result.COMMITTED,
            AuthorityTelemetry.Fields.items(settlement.id,
                "request:" + request.id(), revisionBefore, ledger.revision(),
                ledger.active().size(), ledger.active().size(),
                request.fingerprint().itemId().toString(), 0, 0, 0,
                "courier:" + courier.getUUID()));
        JourneyServerHooks.noteOutputPickupReserved(level, settlement,
            courier, request);
        return new Decision(Outcome.COMMITTED, request, RequestBlocker.NONE);
    }

    /**
     * Finds the oldest/most urgent claimable OUTPUT row and reserves it in
     * the same synchronous call. No request identity comes from the client.
     */
    public static Decision claimNextOutput(ServerLevel level,
                                           Settlement settlement,
                                           SettlerEntity courier) {
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
        if (already.size() == 1) {
            return new Decision(Outcome.ALREADY_OWNED, already.getFirst(),
                already.getFirst().blocker());
        }
        if (already.size() > 1) {
            return quarantine(level, saved, ledger, already.getFirst(),
                "courier_double_ownership");
        }
        List<RequestRecord> candidates = new ArrayList<>();
        for (RequestRecord request : ledger.active()) {
            if (request.type() == RequestType.OUTPUT_PICKUP
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

    /** Reconstructs one route from persisted ledger plus the real bag. */
    public static Route routeForCourier(ServerLevel level,
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
        List<RequestRecord> owned = ledger.activeForCourier(courier.getUUID());
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
            block(level, settlement, request, endpoints,
                "recover_" + endpoints.id());
            return new Route(Outcome.BLOCKED, RoutePhase.BLOCKED, request,
                source, target, endpoints);
        }

        RequestState effective = request.effectiveState();
        if (request.state() == RequestState.BLOCKED) {
            RequestBlocker unresolved = effective == RequestState.IN_TRANSIT
                ? validateInTransit(level, request, courier)
                : validateForReservation(level, settlement, request, courier);
            if (unresolved != RequestBlocker.NONE) {
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
            if (bag == request.fingerprint().count()
                && sourceCount == request.sourceCountBefore()
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
            } else if (bag != 0 || sourceCount != request.sourceCountBefore()) {
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
            RequestBlocker bagFailure = validateInTransit(level, request, courier);
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

    /** Exact source-slot to real Courier bag commit. */
    public static Decision pickup(ServerLevel level, Settlement settlement,
                                  UUID requestId, SettlerEntity courier) {
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
        if (request.state() != RequestState.RESERVED
            && request.state() != RequestState.PICKUP) {
            return rejected(level, settlement, requestId,
                RequestBlocker.MALFORMED, "pickup_wrong_state");
        }
        if (!withinContainerReach(courier, request.sourceContainer())) {
            return block(level, settlement, request, RequestBlocker.NO_PATH,
                "pickup_not_at_source");
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
        ItemStack removed = source.removeItem(request.sourceSlot(),
            request.fingerprint().count());
        if (removed.getCount() != request.fingerprint().count()
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
        if (!request.markInTransit(courier.getUUID(), inserted,
                level.getGameTime()) || bagAfter - bagBefore != inserted
            || !commit(saved, ledger, request)) {
            ItemStack recovered = removeMatchingFromBag(level, courier,
                request.fingerprint(), inserted);
            giveBack(source, request.sourceSlot(), recovered);
            return quarantine(level, saved, ledger, request,
                "pickup_terminal_commit_failed");
        }
        source.setChanged();
        AuthorityTelemetry.emit(level,
            AuthorityTelemetry.Event.OUTPUT_PICKUP_PICKED_UP,
            AuthorityTelemetry.Result.COMMITTED,
            AuthorityTelemetry.Fields.items(settlement.id,
                "courier:" + courier.getUUID(), request.revision() - 2L,
                request.revision(), bagBefore, bagAfter,
                request.fingerprint().itemId().toString(), bagBefore, bagAfter,
                inserted, "request:" + request.id()));
        return new Decision(Outcome.COMMITTED, request, RequestBlocker.NONE);
    }

    /** Real Courier bag to exact Warehouse container commit. */
    public static Decision deliver(ServerLevel level, Settlement settlement,
                                   UUID requestId, SettlerEntity courier) {
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
        if (request.state() == RequestState.BLOCKED
            && request.blockedFrom() == RequestState.IN_TRANSIT) {
            RequestBlocker ready = validateInTransit(level, request, courier);
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
        offered.setCount(remaining);
        int targetBefore = matchingCount(level, target, request.fingerprint());
        ItemStack leftover = WarehouseStorage.of(level, targetBuilding)
            .insertAt(level, targetBuilding, request.targetContainer(), offered);
        int inserted = remaining - leftover.getCount();
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
        if (!complete && !leftover.isEmpty()) {
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
        AuthorityTelemetry.emit(level,
            AuthorityTelemetry.Event.OUTPUT_PICKUP_DELIVERED,
            AuthorityTelemetry.Result.COMMITTED,
            AuthorityTelemetry.Fields.items(settlement.id,
                "request:" + request.id(), revisionBefore, ledger.revision(),
                bagBefore, bagAfter, request.fingerprint().itemId().toString(),
                bagBefore, bagAfter, -inserted,
                "target:" + targetBuilding.id));
        if (complete) {
            noteSatisfied(level, settlement, sourceBuilding, targetBuilding,
                courier, request, ledger.revision());
            return new Decision(Outcome.SATISFIED, request,
                RequestBlocker.NONE);
        }
        AuthorityTelemetry.emit(level,
            AuthorityTelemetry.Event.OUTPUT_PICKUP_BLOCKED,
            AuthorityTelemetry.Result.COMMITTED,
            AuthorityTelemetry.Fields.state(settlement.id,
                "request:" + request.id(), revisionBefore, ledger.revision(),
                ledger.active().size(), ledger.active().size(),
                RequestBlocker.TARGET_FULL.id()));
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
        AuthorityTelemetry.emit(level,
            AuthorityTelemetry.Event.OUTPUT_PICKUP_BLOCKED,
            AuthorityTelemetry.Result.COMMITTED,
            AuthorityTelemetry.Fields.state(settlement.id,
                "request:" + request.id(), before, ledger.revision(),
                ledger.active().size(), ledger.active().size(), detail));
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
        if (sourceStack.getCount() != request.sourceCountBefore()) {
            return sourceStack.getCount() < request.fingerprint().count()
                ? RequestBlocker.NO_STOCK : RequestBlocker.FINGERPRINT_MISMATCH;
        }
        if (!bagEmpty(courier)
            || bagRoomFor(level, courier,
                request.fingerprint().prototype(level.registryAccess()))
                < request.fingerprint().count()) {
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
                                                    RequestRecord request,
                                                    SettlerEntity courier) {
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
        return validateEndpoints(level, settlement, source,
            request.sourceContainer(), target, request.targetContainer());
    }

    private static RequestBlocker validateEndpoints(ServerLevel level,
                                                    Settlement settlement,
                                                    @Nullable Building source,
                                                    BlockPos sourcePos,
                                                    @Nullable Building target,
                                                    BlockPos targetPos) {
        if (!exactRegistered(settlement, source) || source == null || !source.valid
            || source.type == BuildingType.WAREHOUSE
            || !exactRegistered(settlement, target) || target == null
            || !target.valid || target.type != BuildingType.WAREHOUSE
            || source.id.equals(target.id)) {
            return source == null || !exactRegistered(settlement, source)
                ? RequestBlocker.SOURCE_INVALID : RequestBlocker.TARGET_INVALID;
        }
        if (sourcePos == null || !settlement.inside(sourcePos)
            || !source.contains(sourcePos)) {
            return RequestBlocker.SOURCE_INVALID;
        }
        if (targetPos == null || !settlement.inside(targetPos)
            || !target.contains(targetPos)) {
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
            || courier.getProfession() != Profession.COURIER
            || !settlement.id.equals(courier.getSettlementId())
            || settlement.record(courier.getUUID()) == null
            || Employment.professionOf(settlement, courier.getUUID())
                != Profession.COURIER) {
            return false;
        }
        Building employer = Employment.employerOf(settlement,
            courier.getUUID());
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
        return blockEntity instanceof Container container ? container : null;
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
        int byCount = Math.max(0, courier.getCarryCapacity() - itemCount);
        int unitWeight = Weight.of(stack);
        int byWeight = unitWeight <= 0 ? byCount
            : Math.max(0, Weight.BAG_BUDGET - weight) / unitWeight;
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
        return target != null && matchingCount(level, target,
            request.fingerprint()) >= request.targetCountBefore()
                + request.fingerprint().count();
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
