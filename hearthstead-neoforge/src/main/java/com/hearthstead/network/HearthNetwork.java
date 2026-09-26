package com.hearthstead.network;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.Profession;
import com.hearthstead.menu.HearthMenu;
import com.hearthstead.settlement.Costs;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Mayor;
import com.hearthstead.settlement.RecruitmentPolicy;
import com.hearthstead.settlement.RecruitmentTransaction;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.journey.JourneyIds;
import com.hearthstead.settlement.journey.JourneyServerHooks;
import com.hearthstead.settlement.raid.FirstRaidReadinessService;
import com.hearthstead.settlement.raid.RaidDirector;
import com.hearthstead.settlement.raid.RaidLogEntry;
import com.hearthstead.settlement.raid.RaidPlan;
import com.hearthstead.settlement.state.FirstRaidState;
import com.hearthstead.settlement.state.RaidLifecycle;
import com.hearthstead.settlement.state.RecurringRaidRun;
import com.hearthstead.settlement.request.RequestLedgerService;
import com.hearthstead.settlement.request.RequestLedgerSnapshot;
import com.hearthstead.util.AuthorityTelemetry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Server-side rules for the hearth screen's Mayor tab: who could take the
 * seat, what a settler would bring, and what appointing one costs.
 *
 * <p>Mirrors {@link PlaqueNetwork}'s discipline and goes one step further for
 * this real container screen: every request must echo the exact open
 * {@link HearthMenu}'s id, hearth position and settlement UUID. The live
 * block entity and settlement are then resolved again. Proximity by itself
 * is never permission to appoint a mayor.
 */
public final class HearthNetwork {
    private static final long READINESS_SESSION_TTL_TICKS = 600L;
    private static final int MAX_READINESS_SESSIONS = 256;
    private static final int MAX_READINESS_PLAYERS = 1_024;
    /**
     * Ephemeral, server-owned leases only. Values deliberately contain no
     * MinecraftServer reference, so a stopped integrated server cannot be
     * retained by the weak key. Every access occurs on that server's thread.
     */
    private static final Map<MinecraftServer,
        LinkedHashMap<UUID, ReadinessLease>> READINESS_SESSIONS =
        new WeakHashMap<>();
    private static final Map<MinecraftServer, LinkedHashMap<UUID, Long>>
        READINESS_GENERATIONS = new WeakHashMap<>();

    /**
     * The hearth screen opened, refreshed its Mayor panel, appointed a Mayor,
     * or deliberately skipped its Founding Journey. The exact open-menu
     * identity is common authority; each mutating kind then checks the
     * revision of the state it owns.
     */
    public static void handle(ServerPlayer player, HearthMayorAction action) {
        if (action == null || action.kind() == HearthMayorAction.Kind.UNKNOWN) {
            return;
        }
        ServerLevel level = player.serverLevel();
        Settlement settlement = settlementOfExactOpenMenu(player, action);
        if (settlement == null) {
            AuthorityTelemetry.emit(level,
                AuthorityTelemetry.Event.AUTHORITY_REJECTED,
                AuthorityTelemetry.Result.REJECTED,
                AuthorityTelemetry.Fields.state(null, "hearth_menu", 0, 0,
                    0, 0, "invalid_open_menu_authority"));
            return; // closed, copied, stale or otherwise unauthorised packet
        }
        switch (action.kind()) {
            case REFRESH -> send(player, snapshot(level, settlement, player));
            case APPOINT -> {
                if (action.revision() != revisionOf(settlement)) {
                    int revision = revisionOf(settlement);
                    AuthorityTelemetry.emit(level,
                        AuthorityTelemetry.Event.AUTHORITY_REJECTED,
                        AuthorityTelemetry.Result.REJECTED,
                        AuthorityTelemetry.Fields.state(settlement.id,
                            "mayor_appointment", revision, revision,
                            settlement.mayorId == null ? 0 : 1,
                            settlement.mayorId == null ? 0 : 1,
                            "stale_revision"));
                    deny(player, "hearthstead.mayor.stale");
                } else {
                    appoint(player, level, settlement, action.target());
                }
                send(player, snapshot(level, settlement, player));
            }
            case SKIP_JOURNEY -> skipJourney(player, level, settlement, action);
            case OPEN_DEVELOPMENT -> {
                if (!HearthMayorAction.NO_ID.equals(action.target())) {
                    return;
                }
                if (!(level.getBlockEntity(action.hearthPos())
                    instanceof HearthBlockEntity hearth)) {
                    return;
                }
                DevelopmentNetwork.openTech(player, settlement, hearth);
            }
            case OPEN_JOURNEY -> {
                if (!HearthMayorAction.NO_ID.equals(action.target())) {
                    return;
                }
                // A Journey open after FJ-610 is also the FJ-620 review
                // transaction. Send the exact immutable report first and
                // only then allow the existing hook to commit the view event.
                // A missing/malformed newest entry leaves FJ-620 blocked
                // instead of completing a report the client never received.
                HearthMayorSnapshot opened = snapshot(level, settlement, player);
                send(player, opened);
                boolean raidResolved = settlement.journeyState.isCompleted(
                    JourneyIds.FJ_610_FIRST_RAID_RESOLVED);
                if (!raidResolved || opened.aftermath().present()) {
                    JourneyServerHooks.noteJourneyViewOpened(player, settlement);
                }
            }
            case ADMIT_TRAVELER -> admitTraveler(player, settlement, action);
            case REJECT_TRAVELER -> rejectTraveler(player, settlement, action);
            case OPEN_REQUEST_LEDGER -> openRequestLedger(player, settlement,
                action);
            case OPEN_RAID_READINESS -> openRaidReadiness(player, settlement,
                action);
            case CONFIRM_RAID_READINESS -> confirmRaidReadiness(player,
                settlement, action);
            case OPEN_PEOPLE -> {
                if (HearthMayorAction.NO_ID.equals(action.target())) {
                    send(player, snapshot(level, settlement, player));
                }
            }
            case VIEW_SETTLER -> {
                if (HearthMayorAction.NO_ID.equals(action.target())) {
                    return;
                }
                boolean recordedMember = settlement.settlers.stream()
                    .anyMatch(record -> record.entityId.equals(action.target()));
                if (recordedMember
                    && level.getEntity(action.target()) instanceof SettlerEntity settler
                    && settler.isAlive() && !settler.isRemoved()
                    && player.distanceToSqr(settler) <= 64.0D
                    && settlement.id.equals(settler.getSettlementId())) {
                    com.hearthstead.network.PayloadSend.toPlayer(player,
                        new OpenSettlerScreenPayload(settler.getId()));
                    SettlerNetwork.openFor(player, settler);
                }
            }
            case UNKNOWN -> {
                // Rejected before settlement resolution; retained for an
                // exhaustive switch if another enum value lands later.
            }
        }
    }

    // ------------------------------------------------------------ actions --

    private static void appoint(ServerPlayer player, ServerLevel level,
                                Settlement settlement, UUID target) {
        SettlerEntity settler = findSettler(level, settlement, target);
        if (settler == null) {
            deny(player, "hearthstead.mayor.refused.nobody");
            return;
        }
        // Mayor.appoint re-checks mourning and "already the mayor" itself --
        // the revision check above is the outer, coarser guard; this is the
        // one that cannot be bypassed by any packet.
        Component refusal = Mayor.appoint(level, settlement, settler);
        if (refusal != null) {
            player.displayClientMessage(refusal, true);
        } else {
            JourneyServerHooks.noteMayorAppointed(player, settlement, settler);
        }
    }

    private static void admitTraveler(ServerPlayer player,
                                      Settlement settlement,
                                      HearthMayorAction action) {
        SettlementManager.AdmissionResult result =
            SettlementManager.admitWaitingTraveler(player, settlement,
                action.target(), action.revision());
        if (result != SettlementManager.AdmissionResult.COMMITTED) {
            deny(player, switch (result) {
                case STALE_REVISION -> "hearthstead.recruit.refused.stale";
                case NOT_WAITING -> "hearthstead.recruit.refused.not_waiting";
                case WRONG_TRAVELER -> "hearthstead.recruit.refused.wrong_traveler";
                case TRAVELER_UNLOADED -> "hearthstead.recruit.refused.unloaded";
                case INVALID_TRAVELER -> "hearthstead.recruit.refused.invalid_traveler";
                case INVALID_TAVERN -> "hearthstead.recruit.refused.tavern";
                case BLOCKED_POLICY -> "hearthstead.recruit.refused.blocked";
                case REVISION_SATURATED -> "hearthstead.recruit.refused.saturated";
                case INTERNAL_ROLLBACK -> "hearthstead.recruit.refused.rollback";
                case COMMITTED -> throw new IllegalStateException(
                    "committed admission cannot be refused");
            });
        }
        send(player, snapshot(player.serverLevel(), settlement, player));
    }

    private static void rejectTraveler(ServerPlayer player,
                                       Settlement settlement,
                                       HearthMayorAction action) {
        SettlementManager.RejectionResult result =
            SettlementManager.rejectWaitingTraveler(player, settlement,
                action.target(), action.revision());
        if (result != SettlementManager.RejectionResult.COMMITTED) {
            deny(player, switch (result) {
                case STALE_REVISION -> "hearthstead.recruit.refused.stale";
                case NOT_WAITING -> "hearthstead.recruit.refused.not_waiting";
                case WRONG_TRAVELER -> "hearthstead.recruit.refused.wrong_traveler";
                case TRAVELER_UNLOADED -> "hearthstead.recruit.refused.unloaded";
                case INVALID_TRAVELER -> "hearthstead.recruit.refused.invalid_traveler";
                case REVISION_SATURATED -> "hearthstead.recruit.refused.saturated";
                case INTERNAL_FAILURE -> "hearthstead.recruit.refused.rollback";
                case COMMITTED -> throw new IllegalStateException(
                    "committed rejection cannot be refused");
            });
        }
        send(player, snapshot(player.serverLevel(), settlement, player));
    }

    /**
     * Opens a read-only Request Ledger through the exact live Hearth menu.
     * The Founding Journey observation is recorded only after the bounded
     * payload has been handed to this player's connection.
     */
    private static void openRequestLedger(ServerPlayer player,
                                          Settlement settlement,
                                          HearthMayorAction action) {
        if (!HearthMayorAction.NO_ID.equals(action.target())
            || action.revision() != 0) {
            return;
        }
        RequestLedgerSnapshot ledger = RequestLedgerService
            .snapshotForHearth(player, settlement).orElse(null);
        if (ledger == null) {
            deny(player, "hearthstead.request.ledger.unavailable");
            return;
        }
        HearthMayorSnapshot base = snapshot(player.serverLevel(), settlement, player);
        HearthMayorSnapshot.RequestView view = requestView(settlement, ledger,
            action.containerId());
        send(player, withRequests(base, view));
        RequestLedgerService.noteSnapshotSent(player, settlement, ledger);
    }

    /**
     * Opens one exact, bounded declaration generation. The only permitted
     * mutation is recovery of an already-persisted SCHEDULED/FJ-560
     * half-commit; opening can never create or reroll a raid calendar.
     */
    private static void openRaidReadiness(ServerPlayer player,
                                          Settlement settlement,
                                          HearthMayorAction action) {
        if (!HearthMayorAction.NO_ID.equals(action.target())
            || action.revision() != 0) {
            return;
        }
        FirstRaidReadinessService.Report execution = currentRaidExecution(
            player.serverLevel(), settlement);
        if (execution.ready()) {
            sendCommittedReadinessReceipt(player, settlement, execution);
            return;
        }
        sendReadinessGeneration(player, settlement,
            FirstRaidReadinessService.assessDomain(player.serverLevel(),
                settlement));
    }

    /**
     * Consumes a server-created declaration lease exactly once. The client
     * echoes only its opaque UUID and action revision; every gameplay fact is
     * re-observed on the server before the calendar is armed.
     */
    private static void confirmRaidReadiness(ServerPlayer player,
                                             Settlement settlement,
                                             HearthMayorAction action) {
        ServerLevel level = player.serverLevel();
        LinkedHashMap<UUID, ReadinessLease> sessions = readinessSessions(level);
        purgeReadinessSessions(sessions, level.getGameTime());
        ReadinessLease lease = HearthMayorAction.NO_ID.equals(action.target())
            ? null : sessions.remove(action.target());
        if (lease == null || lease.containerId() != action.containerId()
            || lease.actionRevision() != action.revision()) {
            FirstRaidReadinessService.Report execution =
                currentRaidExecution(level, settlement);
            if (execution.ready()) {
                sendCommittedReadinessReceipt(player, settlement, execution);
                return;
            }
            deny(player, "hearthstead.raid.readiness.session_expired");
            sendReadinessGeneration(player, settlement,
                FirstRaidReadinessService.assessDomain(level, settlement));
            return;
        }

        FirstRaidReadinessService.Report declaration =
            FirstRaidReadinessService.assessDeclaration(player, settlement,
                lease.proof());
        SettlementSavedData saved = SettlementSavedData.existing(level);
        boolean exactAuthority = saved != null
            && saved.settlements.get(settlement.id) == settlement;
        if (!declaration.ready() || !exactAuthority
            || !RaidDirector.commitFirstRaidReadiness(level, settlement)) {
            FirstRaidReadinessService.Report execution =
                currentRaidExecution(level, settlement);
            if (execution.ready()) {
                sendCommittedReadinessReceipt(player, settlement, execution);
                return;
            }
            deny(player, "hearthstead.raid.readiness.blocked");
            sendReadinessGeneration(player, settlement,
                FirstRaidReadinessService.assessDomain(level, settlement));
            return;
        }

        FirstRaidReadinessService.Report execution =
            currentRaidExecution(level, settlement);
        if (!execution.ready()) {
            deny(player, "hearthstead.raid.readiness.blocked");
            sendReadinessGeneration(player, settlement,
                FirstRaidReadinessService.assessDomain(level, settlement));
            return;
        }
        sendCommittedReadinessReceipt(player, settlement, execution);
        player.displayClientMessage(Component.translatable(
            "hearthstead.raid.readiness.committed"), true);
    }

    private static FirstRaidReadinessService.Report currentRaidExecution(
            ServerLevel level, Settlement settlement) {
        FirstRaidReadinessService.Report execution =
            FirstRaidReadinessService.assessExecution(level, settlement);
        if (!execution.ready()
            && RaidDirector.recoverFirstRaidReadinessCommit(level,
                settlement)) {
            execution = FirstRaidReadinessService.assessExecution(level,
                settlement);
        }
        return execution;
    }

    private static void sendCommittedReadinessReceipt(
            ServerPlayer player, Settlement settlement,
            FirstRaidReadinessService.Report execution) {
        ServerLevel level = player.serverLevel();
        send(player, withReadiness(snapshot(level, settlement, player),
            readinessView(nextReadinessGeneration(level, player.getUUID()),
                execution, HearthMayorAction.NO_ID, 0, true)));
    }

    private static void sendReadinessGeneration(
            ServerPlayer player, Settlement settlement,
            FirstRaidReadinessService.Report report) {
        ServerLevel level = player.serverLevel();
        LinkedHashMap<UUID, ReadinessLease> sessions = readinessSessions(level);
        long now = Math.max(0L, level.getGameTime());
        purgeReadinessSessions(sessions, now);
        sessions.entrySet().removeIf(entry ->
            player.getUUID().equals(entry.getValue().proof().playerId()));
        while (sessions.size() >= MAX_READINESS_SESSIONS) {
            UUID eldest = sessions.keySet().iterator().next();
            sessions.remove(eldest);
        }
        UUID sessionId;
        do {
            sessionId = UUID.randomUUID();
        } while (HearthMayorAction.NO_ID.equals(sessionId)
            || sessions.containsKey(sessionId));
        long expires = now > Long.MAX_VALUE - READINESS_SESSION_TTL_TICKS
            ? Long.MAX_VALUE : now + READINESS_SESSION_TTL_TICKS;
        FirstRaidReadinessService.DeclarationSession proof =
            new FirstRaidReadinessService.DeclarationSession(sessionId,
                player.getUUID(), settlement.id, level.dimension().location(),
                settlement.center, report.domainRevision(), now, expires, true);
        int actionRevision = Objects.hash(sessionId, report.domainRevision(),
            player.containerMenu.containerId);
        sessions.put(sessionId, new ReadinessLease(proof,
            player.containerMenu.containerId, actionRevision));
        send(player, withReadiness(snapshot(level, settlement, player),
            readinessView(nextReadinessGeneration(level, player.getUUID()),
                report, sessionId, actionRevision, false)));
    }

    private static HearthMayorSnapshot.ReadinessView readinessView(
            long generation, FirstRaidReadinessService.Report report,
            UUID sessionId, int actionRevision, boolean committed) {
        FirstRaidReadinessService.Metrics metrics = report.metrics();
        List<Integer> blockers = report.blockers().stream()
            .map(FirstRaidReadinessService.Blocker::wireId).toList();
        return new HearthMayorSnapshot.ReadinessView(true,
            generation, sessionId, actionRevision,
            report.domainRevision(), report.ready(), committed,
            metrics.inspectedSettlers(), metrics.housingCapacity(),
            metrics.warehouseContainers(), metrics.availableReadyMeals(),
            metrics.requiredReadyMeals(), metrics.requestActiveRows(),
            metrics.requestBlockedRows(), blockers);
    }

    private static LinkedHashMap<UUID, ReadinessLease> readinessSessions(
            ServerLevel level) {
        return READINESS_SESSIONS.computeIfAbsent(level.getServer(),
            ignored -> new LinkedHashMap<>());
    }

    private static void purgeReadinessSessions(
            LinkedHashMap<UUID, ReadinessLease> sessions, long now) {
        sessions.entrySet().removeIf(entry -> {
            FirstRaidReadinessService.DeclarationSession proof =
                entry.getValue().proof();
            return proof == null || !proof.active()
                || now < proof.openedAtTick() || now > proof.expiresAtTick();
        });
    }

    private static long nextReadinessGeneration(ServerLevel level,
                                                UUID playerId) {
        LinkedHashMap<UUID, Long> generations = READINESS_GENERATIONS
            .computeIfAbsent(level.getServer(),
                ignored -> new LinkedHashMap<>(16, 0.75F, true));
        if (!generations.containsKey(playerId)) {
            while (generations.size() >= MAX_READINESS_PLAYERS) {
                generations.remove(generations.keySet().iterator().next());
            }
        }
        long previous = generations.getOrDefault(playerId, 0L);
        long next = previous == Long.MAX_VALUE ? Long.MAX_VALUE : previous + 1L;
        generations.put(playerId, next);
        return next;
    }

    // ----------------------------------------------------------- snapshot --

    /**
     * Builds the server-authored Hearth view in settlement resident order.
     * Package visibility lets the native Mayor GameTest exercise this exact
     * production projection instead of duplicating its ordering logic.
     */
    static HearthMayorSnapshot snapshot(ServerLevel level, Settlement settlement) {
        return snapshot(level, settlement, null);
    }

    static HearthMayorSnapshot snapshot(ServerLevel level, Settlement settlement, ServerPlayer player) {
        SettlerEntity mayor = Mayor.find(level, settlement);
        List<HearthMayorSnapshot.Resident> residents = new ArrayList<>();
        for (Settlement.SettlerRecord record : settlement.settlers) {
            if (residents.size() >= HearthMayorSnapshot.MAX_RESIDENTS) {
                break;
            }
            if (level.getEntity(record.entityId) instanceof SettlerEntity settler
                && settler.isAlive() && !settler.isRemoved()
                && settlement.id.equals(settler.getSettlementId())) {
                residents.add(new HearthMayorSnapshot.Resident(record.entityId, settler.getId(),
                    settler.getSettlerName(), settler.getProfession().name(),
                    settler.getActivity().key(), true));
            } else {
                Profession profession = record.profession == null ? Profession.NONE
                    : record.profession;
                residents.add(new HearthMayorSnapshot.Resident(record.entityId, -1,
                    record.name == null ? "" : record.name, profession.name(),
                    "unloaded", false));
            }
        }
        List<HearthMayorSnapshot.Candidate> candidates = new ArrayList<>();
        for (SettlerEntity settler : Mayor.candidates(level, settlement)) {
            Mayor.Boon boon = Mayor.boonOf(settler);
            candidates.add(new HearthMayorSnapshot.Candidate(settler.getUUID(),
                settler.getSettlerName(), settler.getProfession().name(),
                boon.key(), settler.attributes().get(boon.from())));
        }
        // Preserve the settlement's own resident order. Mayor appointment is
        // a player decision: the server supplies facts but does not rank,
        // recommend or silently reorder people by an invented fitness score.

        return new HearthMayorSnapshot(revisionOf(settlement),
            mayor != null, mayor != null ? mayor.getUUID() : new UUID(0, 0),
            mayor != null ? mayor.getSettlerName() : "",
            mayor != null ? Mayor.boonOf(mayor).key() : "",
            settlement.mayorSince,
            Mayor.mourning(level, settlement), settlement.mourningUntil,
            List.copyOf(candidates), List.copyOf(residents), settlement.settlers.size(), true,
            recruitmentCard(level, settlement, player),
            HearthMayorSnapshot.RequestView.closed(),
            HearthMayorSnapshot.ReadinessView.closed(),
            recurringStatusView(level, settlement),
            aftermathView(settlement));
    }

    private static HearthMayorSnapshot withRequests(
            HearthMayorSnapshot base, HearthMayorSnapshot.RequestView requests) {
        return new HearthMayorSnapshot(base.revision(), base.hasMayor(),
            base.mayorId(), base.mayorName(), base.boonKey(), base.mayorSince(),
            base.mourning(), base.mourningUntil(), base.candidates(),
            base.residents(), base.residentTotal(), base.mayManage(), base.recruitment(), requests, base.readiness(),
            base.recurringStatus(), base.aftermath());
    }

    private static HearthMayorSnapshot withReadiness(
            HearthMayorSnapshot base,
            HearthMayorSnapshot.ReadinessView readiness) {
        return new HearthMayorSnapshot(base.revision(), base.hasMayor(),
            base.mayorId(), base.mayorName(), base.boonKey(), base.mayorSince(),
            base.mourning(), base.mourningUntil(), base.candidates(),
            base.residents(), base.residentTotal(), base.mayManage(), base.recruitment(), base.requests(), readiness,
            base.recurringStatus(), base.aftermath());
    }

    /** One server-tick projection of the persisted recurring raid authority. */
    static HearthMayorSnapshot.RecurringStatusView recurringStatusView(
            ServerLevel level, Settlement settlement) {
        return recurringStatusFor(settlement == null ? null
            : settlement.raidLifecycle, settlement == null ? null
                : settlement.recurringRaidRun, level == null ? -1L
                    : level.getGameTime());
    }

    /**
     * Uses only the lifecycle and recurring-run records. Callers provide the
     * server game time exactly once so the transmitted cooldown is a measured
     * remaining value, never a client-side countdown.
     */
    static HearthMayorSnapshot.RecurringStatusView recurringStatusFor(
            RaidLifecycle lifecycle, RecurringRaidRun run, long gameTime) {
        if (lifecycle == null || run == null || gameTime < 0L
            || lifecycle.firstState() != FirstRaidState.COMPLETED) {
            return HearthMayorSnapshot.RecurringStatusView.closed();
        }
        if (run.isActive() || run.isLegacyBridgeActive()) {
            return recurringPlanStatus(
                HearthMayorSnapshot.RecurringStatusView.Status.ACTIVE,
                run.plan().orElse(null));
        }
        if (run.isQueued()) {
            return recurringPlanStatus(
                HearthMayorSnapshot.RecurringStatusView.Status.QUEUED,
                run.plan().orElse(null));
        }
        if (run.isBlocked() || lifecycle.recurringScheduleBlocked()) {
            return new HearthMayorSnapshot.RecurringStatusView(
                HearthMayorSnapshot.RecurringStatusView.Status.BLOCKED.wireId(),
                -1L, 0L);
        }
        RaidPlan warned = lifecycle.recurringWarnedPlan().orElse(null);
        if (warned != null) {
            return recurringPlanStatus(
                HearthMayorSnapshot.RecurringStatusView.Status.WARNED, warned);
        }
        if (lifecycle.recurringCoolingDown(gameTime)) {
            long remaining = lifecycle.recurringCooldownUntilGameTime()
                - gameTime;
            return new HearthMayorSnapshot.RecurringStatusView(
                HearthMayorSnapshot.RecurringStatusView.Status.RECOVERING.wireId(),
                -1L, remaining);
        }
        return HearthMayorSnapshot.RecurringStatusView.closed();
    }

    private static HearthMayorSnapshot.RecurringStatusView recurringPlanStatus(
            HearthMayorSnapshot.RecurringStatusView.Status status,
            RaidPlan plan) {
        if (!RaidPlan.isValid(plan)) {
            return new HearthMayorSnapshot.RecurringStatusView(
                HearthMayorSnapshot.RecurringStatusView.Status.BLOCKED.wireId(),
                -1L, 0L);
        }
        return new HearthMayorSnapshot.RecurringStatusView(status.wireId(),
            plan.night(), 0L);
    }

    /**
     * Builds one constant-size report from the newest exact log entry. This
     * method never copies or walks raid history; corrupt newest data closes
     * the projection rather than silently falling back to an older raid.
     */
    public static HearthMayorSnapshot.AftermathView aftermathView(
            Settlement settlement) {
        if (settlement == null || settlement.raidLog.isEmpty()
            || settlement.raidLog.size() > RaidDirector.MAX_RAID_LOG
            || settlement.blessingState == null) {
            return HearthMayorSnapshot.AftermathView.closed();
        }
        RaidLogEntry entry = settlement.raidLog.getLast();
        if (!RaidLogEntry.isValid(entry)) {
            return HearthMayorSnapshot.AftermathView.closed();
        }

        HearthMayorSnapshot.AftermathView.RewardStatus reward;
        int offerSerial = 0;
        if (settlement.blessingState.quarantined()) {
            reward = HearthMayorSnapshot.AftermathView.RewardStatus.UNAVAILABLE;
        } else {
            offerSerial = settlement.blessingState.offerSerial();
            if (offerSerial > 0) {
                reward = HearthMayorSnapshot.AftermathView.RewardStatus.OFFER_PENDING;
            } else if (settlement.blessingState.earned() > 0
                    && settlement.blessingState.spent()
                        == settlement.blessingState.earned()) {
                reward = HearthMayorSnapshot.AftermathView.RewardStatus.ALL_CLAIMED;
            } else {
                reward = HearthMayorSnapshot.AftermathView.RewardStatus.NO_OFFER;
            }
        }
        HearthMayorSnapshot.AftermathView.RoadAhead road =
            HearthMayorSnapshot.AftermathView.expectedRoad(entry.held(), reward);
        return new HearthMayorSnapshot.AftermathView(true, entry.held(),
            entry.night(), entry.captainName(), entry.objectiveId(),
            entry.itemsStolen(), entry.settlersHurt(), entry.stageAfterId(),
            reward.wireId(), offerSerial, road.wireId());
    }

    private static HearthMayorSnapshot.RequestView requestView(
            Settlement settlement, RequestLedgerSnapshot snapshot,
            int containerId) {
        int count = Math.min(HearthMayorSnapshot.RequestView.MAX_ROWS,
            snapshot.rows().size());
        List<HearthMayorSnapshot.RequestRow> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            RequestLedgerSnapshot.Row row = snapshot.rows().get(i);
            rows.add(new HearthMayorSnapshot.RequestRow(row.requestId(),
                row.type().wireId(), row.state().wireId(),
                row.priority().wireId(), requesterName(settlement, row),
                row.profession().name().toLowerCase(java.util.Locale.ROOT),
                buildingNameKey(settlement, row.sourceBuildingId()),
                positionOrZero(row.pickup()),
                buildingNameKey(settlement, row.targetBuildingId()),
                positionOrZero(row.target()),
                row.courierId() == null ? HearthMayorAction.NO_ID
                    : row.courierId(),
                settlerName(settlement, row.courierId()), row.itemId(),
                row.requestedCount(), row.movedCount(), row.deliveredCount(),
                row.ageTicks(), row.blocker().wireId(),
                row.physicalOwner().ordinal(), row.stockAvailable(),
                row.targetExact(), row.fullTransportTrace(),
                row.equipmentAdapter(), row.equipmentReason() == null ? -1
                    : row.equipmentReason().ordinal(), row.awaitingSource()));
        }
        return new HearthMayorSnapshot.RequestView(true, settlement.id,
            containerId, snapshot.generatedTick(), snapshot.typedRevision(),
            snapshot.equipmentRevision(), snapshot.quarantined(),
            snapshot.quarantineReason(),
            snapshot.truncated() || snapshot.rows().size() > count,
            List.copyOf(rows));
    }

    private static String requesterName(Settlement settlement,
                                        RequestLedgerSnapshot.Row row) {
        String member = settlerName(settlement, row.requesterId());
        if (!member.isEmpty()) {
            return member;
        }
        Building source = buildingById(settlement, row.sourceBuildingId());
        if (source != null) {
            return source.type.id();
        }
        return shortId(row.requesterId());
    }

    private static String settlerName(Settlement settlement, UUID id) {
        if (id == null) {
            return "";
        }
        var record = settlement.record(id);
        return record == null ? "" : record.name;
    }

    private static String buildingNameKey(Settlement settlement, UUID id) {
        Building building = buildingById(settlement, id);
        return building == null ? "hearthstead.request.location.unknown"
            : "hearthstead.building." + building.type.id();
    }

    @Nullable
    private static Building buildingById(Settlement settlement, UUID id) {
        if (id == null) {
            return null;
        }
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

    private static BlockPos positionOrZero(@Nullable BlockPos pos) {
        return pos == null ? BlockPos.ZERO : pos;
    }

    private static String shortId(UUID id) {
        String value = id == null ? "unknown" : id.toString();
        return value.substring(0, Math.min(8, value.length()));
    }

    private static HearthMayorSnapshot.RecruitmentCard recruitmentCard(
            ServerLevel level, Settlement settlement, ServerPlayer player) {
        SettlementManager.recruitPrice(level, settlement); // Persist legacy quote before projecting its identity.
        RecruitmentTransaction transaction = settlement.recruitment;
        if (transaction == null || !transaction.hasCandidate()
            || (transaction.status() != RecruitmentTransaction.Status.TRAVELING
                && transaction.status()
                    != RecruitmentTransaction.Status.WAITING_ADMISSION)) {
            return HearthMayorSnapshot.RecruitmentCard.empty();
        }

        RecruitmentPolicy.Stage stage = transaction.status()
            == RecruitmentTransaction.Status.WAITING_ADMISSION
                ? RecruitmentPolicy.Stage.WAITING_ADMISSION
                : RecruitmentPolicy.Stage.TRAVELING;
        RecruitmentPolicy.Assessment assessment = RecruitmentPolicy.assess(
            level, settlement, stage, player);
        RecruitmentPolicy.Blocker blocker = SettlementManager.candidateBlocker(
            level, settlement, player);
        List<HearthMayorSnapshot.CostLine> costs = new ArrayList<>();
        for (Costs.Line line : assessment.price().lines()) {
            String key;
            if (line.exact() != null) {
                key = line.exact().getDescriptionId();
            } else if (ItemTags.PLANKS.equals(line.tag())) {
                key = "hearthstead.cost.planks";
            } else {
                key = "hearthstead.cost.material";
            }
            costs.add(new HearthMayorSnapshot.CostLine(key, line.count()));
        }
        int firstAttribute = transaction.quote().firstAttribute(), firstValue = transaction.quote().firstValue();
        int secondAttribute = transaction.quote().secondAttribute(), secondValue = transaction.quote().secondValue();
        if (transaction.quote().version() == 0
                && level.getEntity(transaction.travelerId()) instanceof SettlerEntity existingGuest) {
            // Legacy price never uses these stats; show the actual living guest without inventing a starting basis.
            var strongest = java.util.Arrays.stream(com.hearthstead.entity.Attribute.ALL)
                .sorted(java.util.Comparator.<com.hearthstead.entity.Attribute>comparingInt(
                    attribute -> existingGuest.attributes().get(attribute)).reversed()
                    .thenComparingInt(Enum::ordinal)).limit(2).toList();
            firstAttribute = strongest.get(0).ordinal(); firstValue = existingGuest.attributes().get(strongest.get(0));
            secondAttribute = strongest.get(1).ordinal(); secondValue = existingGuest.attributes().get(strongest.get(1));
        }
        return new HearthMayorSnapshot.RecruitmentCard(true,
            transaction.travelerId(), transaction.travelerName(),
            transaction.revision(), transaction.status().wireId(),
            blocker.wireId(), Math.max(0,
                settlement.capacity() - settlement.population()),
            assessment.readyFoodAfterPrice(), assessment.requiredReadyFood(),
            SettlementManager.candidatePatienceUntil(level, settlement),
            List.copyOf(costs),
            SettlementManager.candidateMayAdmit(level, settlement, player),
            SettlementManager.candidateMayDismiss(level, settlement),
            transaction.quote().version(), firstAttribute, firstValue,
            secondAttribute, secondValue, transaction.quote().premium(), transaction.quote().discountPercent());
    }

    private static SettlerEntity findSettler(ServerLevel level, Settlement settlement, UUID id) {
        for (SettlerEntity settler : SettlementManager.loadedMembers(level, settlement)) {
            if (settler.getUUID().equals(id)) {
                return settler;
            }
        }
        return null;
    }

    @Nullable
    private static Settlement settlementOfExactOpenMenu(ServerPlayer player,
                                                        HearthMayorAction action) {
        if (!(player.containerMenu instanceof HearthMenu menu)
            || menu.getContainerId() != action.containerId()
            || !menu.getHearthPos().equals(action.hearthPos())
            || !menu.getSettlementId().equals(action.settlementId())
            || !menu.stillValid(player)) {
            return null;
        }
        ServerLevel level = player.serverLevel();
        if (!(level.getBlockEntity(action.hearthPos())
            instanceof HearthBlockEntity hearth)) {
            return null;
        }
        UUID liveSettlementId = hearth.getSettlementId();
        if (liveSettlementId == null
            || !liveSettlementId.equals(action.settlementId())) {
            return null;
        }
        Settlement settlement = SettlementManager.byId(level, liveSettlementId);
        return settlement != null && settlement.center.equals(action.hearthPos())
            ? settlement : null;
    }

    private static void skipJourney(ServerPlayer player, ServerLevel level,
                                    Settlement settlement,
                                    HearthMayorAction action) {
        // A journey action has no entity target. Requiring the canonical
        // sentinel prevents a differently-shaped/replayed Mayor request from
        // gaining a second meaning merely because its kind byte was changed.
        if (!HearthMayorAction.NO_ID.equals(action.target())) {
            return;
        }
        if (action.revision() != settlement.journeyState.revision()) {
            deny(player, "hearthstead.journey.skip.stale");
            return;
        }
        if (!settlement.journeyState.skipPresentation(action.revision())) {
            deny(player, "hearthstead.journey.skip.unavailable");
            return;
        }
        SettlementManager.data(level).setDirty();
        player.displayClientMessage(
            Component.translatable("hearthstead.journey.skip.done"), true);
    }

    /**
     * A synthetic revision hashed from the settlement's own mayoral fields.
     * The plaque keeps a real counter it owns on its block entity; a
     * settlement's mayoral state has no such counter, and adding one is not
     * this worker's file to touch ({@code Settlement.java}), so the three
     * fields that change on every appointment or death -- who holds the
     * seat, since when, and mourning's end -- stand in for one. Not a
     * cryptographic guarantee, exactly like the plaque's own revision is
     * not; good enough to refuse a click made against a seat that has since
     * changed.
     */
    private static int revisionOf(Settlement settlement) {
        return Objects.hash(settlement.mayorId, settlement.mayorSince, settlement.mourningUntil);
    }

    private static void deny(ServerPlayer player, String key) {
        player.displayClientMessage(Component.translatable(key), true);
    }

    private static void send(ServerPlayer player, HearthMayorSnapshot snapshot) {
        com.hearthstead.network.PayloadSend.toPlayer(player, snapshot);
    }

    private record ReadinessLease(
            FirstRaidReadinessService.DeclarationSession proof,
            int containerId, int actionRevision) {
    }

    private HearthNetwork() {
    }
}
