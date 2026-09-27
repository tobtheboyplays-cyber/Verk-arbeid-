package com.hearthstead.network;

import com.hearthstead.block.PlaqueBlock;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.building.PlaqueState;
import com.hearthstead.building.Requirement;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.BuildingManager;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.Summons;
import com.hearthstead.settlement.PendingPlayerDeliveryLedger;
import com.hearthstead.settlement.journey.JourneyTransactionIds;
import com.hearthstead.settlement.state.BlessingId;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server-side rules for the plaque screen: what a player is shown, and what
 * they are allowed to change.
 *
 * <p>Every mutation re-checks the world from scratch — the plaque still
 * exists, the player is close enough and in the same dimension, the building
 * is still valid, the settler is still real and still eligible. None of that
 * is taken from the packet, because the packet comes from a client.
 */
public final class PlaqueNetwork {

    /** How far a player may stand from a plaque and still manage it. */
    private static final double REACH_SQUARED = 8.0 * 8.0;

    public static UUID openFor(ServerPlayer player, PlaqueBlockEntity plaque) {
        UUID sessionId = InspectionViewers.openPlaque(player, plaque);
        send(player, snapshot(player, plaque, sessionId,
            PlaqueSnapshot.Delivery.OPEN));
        return sessionId;
    }

    public static void handle(ServerPlayer player, PlaqueAction action) {
        if (action == null || action.kind() == null
            || action.kind() == PlaqueAction.Kind.UNKNOWN) {
            return;
        }
        if (action.kind() == PlaqueAction.Kind.CLOSE) {
            InspectionViewers.closePlaque(player, action.pos(),
                action.buildingId(), action.sessionId());
            return;
        }
        ServerLevel level = player.serverLevel();
        if (!level.isLoaded(action.pos())
            || !(level.getBlockEntity(action.pos()) instanceof PlaqueBlockEntity plaque)) {
            InspectionViewers.closePlaque(player, action.pos(),
                action.buildingId(), action.sessionId());
            return; // the plaque is gone; the screen will close itself
        }
        InspectionViewers.PlaqueAuthorization authorization =
            InspectionViewers.authorizePlaque(player, plaque,
                action.buildingId(), action.sessionId());
        if (authorization == InspectionViewers.PlaqueAuthorization.INVALID) {
            return;
        }
        if (authorization == InspectionViewers.PlaqueAuthorization.STALE) {
            deny(player, "hearthstead.plaque.stale");
            send(player, snapshot(player, plaque, action.sessionId(),
                PlaqueSnapshot.Delivery.UPDATE));
            return;
        }
        if (player.distanceToSqr(action.pos().getX() + 0.5, action.pos().getY() + 0.5,
            action.pos().getZ() + 0.5) > REACH_SQUARED) {
            InspectionViewers.closePlaque(player, action.pos(),
                action.buildingId(), action.sessionId());
            deny(player, "hearthstead.plaque.too_far");
            return;
        }
        if (action.revision() != plaque.revision()) {
            // Someone changed this building while the screen was open.
            deny(player, "hearthstead.plaque.stale");
            send(player, snapshot(player, plaque, action.sessionId(),
                PlaqueSnapshot.Delivery.UPDATE));
            return;
        }

        // Staffing changes need a player who may build here (not a spectator
        // or adventure-mode visitor); REFRESH stays open to every viewer.
        if (action.kind() != PlaqueAction.Kind.REFRESH
            && (player.isSpectator() || !player.mayBuild())) {
            send(player, snapshot(player, plaque, action.sessionId(),
                PlaqueSnapshot.Delivery.UPDATE));
            return;
        }
        switch (action.kind()) {
            case ASSIGN -> assign(player, level, plaque, action.target());
            case EVICT -> evict(player, level, plaque, action.target());
            case REFRESH -> plaque.survey(level);
            case SUMMON -> summon(player, level, plaque, action.target());
            case FIRE -> fire(player, level, plaque, action.target(),
                action.employmentRevision());
            case CLOSE -> { } // handled before world resolution
            case UNKNOWN -> { } // rejected before world resolution
        }
        if (!InspectionViewers.updatePlaqueIdentityAfterAuthorizedMutation(
                player, plaque, action.sessionId())) {
            return;
        }
        send(player, snapshot(player, plaque, action.sessionId(),
            PlaqueSnapshot.Delivery.UPDATE));
    }

    // ------------------------------------------------------------ actions --

    private static void assign(ServerPlayer player, ServerLevel level,
                               PlaqueBlockEntity plaque, UUID settlerId) {
        Building building = plaque.building(level);
        Settlement settlement = plaque.settlementFor(level);
        if (building == null || settlement == null || !building.valid
            || !plaque.getBlockPos().equals(building.plaquePos)
            || !settlement.buildings.contains(building)) {
            deny(player, "hearthstead.plaque.not_ready");
            return;
        }
        // A workplace plaque is an inspection surface, not a second hiring
        // surface. Job Emblems are deliberately given to the settler; the
        // employment service then chooses a compatible active workplace. Keep
        // this server-side refusal even though the current client no longer
        // offers the button: an old or stale client must not retain the former
        // candidate-list shortcut.
        if (plaque.type().employsWorkers() && !plaque.type().housesResidents()) {
            deny(player, "hearthstead.plaque.assign.work_direct");
            return;
        }
        SettlerEntity settler = findSettler(level, settlement, settlerId);
        if (settler == null) {
            deny(player, "hearthstead.plaque.settler_gone");
            return;
        }
        BlockPos bed = freeBed(level, settlement, building);
        // House bed cap (tech tree: 4, Townhouses 6, Manors 8).
        if (bed != null && !com.hearthstead.settlement.techtree.effects.CommonsEffects.hasRoom(
                settlement, building, com.hearthstead.settlement.ResidentBedOccupancy.read(level, settlement).claims().keySet())) {
            bed = null;
        }
        if (bed == null) {
            deny(player, "hearthstead.plaque.no_room");
            return;
        }
        // Housing ASSIGN is intentionally unchanged: moving house releases the
        // old bed explicitly so the previous home's count remains correct.
        settler.releaseBed();
        settler.claimBed(bed);
        SettlementSavedData.get(level).setDirty();
    }

    private static void evict(ServerPlayer player, ServerLevel level,
                              PlaqueBlockEntity plaque, UUID settlerId) {
        Building building = plaque.building(level);
        Settlement settlement = plaque.settlementFor(level);
        if (building == null || settlement == null) {
            deny(player, "hearthstead.plaque.not_ready");
            return;
        }
        SettlerEntity settler = findSettler(level, settlement, settlerId);
        if (settler != null && building.workers.contains(settlerId)) {
            // Dismissal has weight: morale, and they walk out. Employment owns
            // that so it happens the same way wherever it is triggered from.
            Employment.dismiss(level, settlement, settler);
        } else {
            building.workers.remove(settlerId);
        }
        if (settler != null && settler.getClaimedBed() != null
            && building.beds.contains(settler.getClaimedBed())) {
            // Eviction takes the bed and nothing else: no profession change,
            // no equipment loss, no teleport.
            settler.releaseBed();
            settler.addMorale(-4.0F);
        }
        SettlementSavedData.get(level).setDirty();
    }

    /**
     * "Come here." Same eligibility check as every other plaque action — the
     * building must be real, and the target must actually be one of ITS
     * workers, not merely a settler who exists somewhere — but unlike ASSIGN
     * and EVICT this changes nothing about who works where. It never bumps
     * the revision or marks the settlement dirty: the call is not settlement
     * state, it is a live request that {@code RespondToSummonsGoal} consumes
     * and forgets.
     */
    private static void summon(ServerPlayer player, ServerLevel level,
                               PlaqueBlockEntity plaque, UUID settlerId) {
        Building building = plaque.building(level);
        Settlement settlement = plaque.settlementFor(level);
        if (building == null || settlement == null || !building.valid) {
            deny(player, "hearthstead.plaque.not_ready");
            return;
        }
        SettlerEntity settler = findSettler(level, settlement, settlerId);
        if (settler == null) {
            deny(player, "hearthstead.plaque.summon.not_loaded");
            return;
        }
        if (!building.workers.contains(settlerId)) {
            deny(player, "hearthstead.plaque.summon.not_employed");
            return;
        }
        Direction facing = plaque.getBlockState().getValue(PlaqueBlock.FACING);
        Summons.call(settler, plaque.getBlockPos().relative(facing), level);
    }

    /**
     * Releases exactly the worker shown on this workplace sheet. The return
     * is reserved before dismissal, then dismissed through Employment so
     * arrows, requests, morale and the work walk-out keep their existing
     * lifecycle. A rejection at either boundary leaves both job and emblem
     * untouched; the deterministic reservation id makes a second click inert.
     */
    private static void fire(ServerPlayer player, ServerLevel level,
                             PlaqueBlockEntity plaque, UUID settlerId,
                             long expectedEmploymentRevision) {
        Building building = plaque.building(level);
        Settlement settlement = plaque.settlementFor(level);
        if (building == null || settlement == null || !building.valid
            || !plaque.getBlockPos().equals(building.plaquePos)
            || !settlement.buildings.contains(building)) {
            deny(player, "hearthstead.plaque.not_ready");
            return;
        }
        SettlerEntity settler = findSettler(level, settlement, settlerId);
        if (settler == null || !building.workers.contains(settlerId)
            || Employment.employerOf(settlement, settlerId) != building) {
            deny(player, "hearthstead.plaque.fire.not_employed");
            return;
        }
        if (settlerId.equals(settlement.mayorId)) {
            deny(player, "hearthstead.plaque.fire.mayor");
            return;
        }
        long currentEmploymentRevision = employmentRevision(settlement, building,
            settlerId);
        if (currentEmploymentRevision == 0L) {
            // A pre-ledger/admin employment has no proof that an emblem was
            // ever spent. Keep that worker employed rather than minting one.
            deny(player, "hearthstead.plaque.fire.authorization_required");
            return;
        }
        if (expectedEmploymentRevision != currentEmploymentRevision) {
            deny(player, "hearthstead.plaque.stale");
            return;
        }
        UUID deliveryId = JourneyTransactionIds.forRevision("employment_fire",
            settlement.id, settlerId, currentEmploymentRevision);
        Employment.FireResult result = Employment.fireWithEmblem(level,
            settlement, building, settler, player, deliveryId,
            currentEmploymentRevision);
        if (!result.applied()) {
            deny(player, result.messageKey());
            return;
        }
        PendingPlayerDeliveryLedger.DeliveryResult delivery =
            settlement.employmentReturns.deliver(level, player, deliveryId);
        SettlementManager.data(level).setDirty();
        player.displayClientMessage(Component.translatable(
            "hearthstead.plaque.fire.complete", settler.getDisplayName(),
            delivery.outcome().id()), true);
    }

    private static long employmentRevision(Settlement settlement, Building building,
                                           UUID settlerId) {
        var receipt = settlement.employmentAuthorizations.receipt(settlerId);
        Profession profession = Employment.tradeOf(building.type);
        return receipt != null
            && settlement.employmentAuthorizations.matches(settlement.id, settlerId,
                building.id, profession) ? receipt.revision() : 0L;
    }
    // ----------------------------------------------------------- snapshot --

    private static PlaqueSnapshot snapshot(ServerPlayer player, PlaqueBlockEntity plaque,
                                           UUID sessionId,
                                           PlaqueSnapshot.Delivery delivery) {
        return snapshot(player, plaque, sessionId, delivery, null);
    }

    /**
     * Authors one complete sheet from one stable member collection.
     *
     * <p>{@code probe} is an instance-scoped GameTest seam, never retained and
     * never installed globally. It lets the multiplayer regression count the
     * exact expensive queries made by each real snapshot without a timing
     * assertion or production telemetry.
     */
    private static PlaqueSnapshot snapshot(ServerPlayer player,
                                           PlaqueBlockEntity plaque,
                                           UUID sessionId,
                                           PlaqueSnapshot.Delivery delivery,
                                           SnapshotProbe probe) {
        ServerLevel level = player.serverLevel();
        Building building = plaque.building(level);
        Settlement settlement = plaque.settlementFor(level);

        List<PlaqueSnapshot.RequirementLine> requirements = new ArrayList<>();
        for (Requirement.Status status : plaque.lastSurvey()) {
            requirements.add(new PlaqueSnapshot.RequirementLine(
                status.requirement().id(), status.have(), status.needed()));
        }
        addLevelLines(level, settlement, building, requirements);
        // House bed cap: say how many beds count and what raises the cap.
        String capLine = com.hearthstead.settlement.techtree.effects.CommonsEffects
            .bedCapLineId(settlement, building);
        if (capLine != null) {
            requirements.add(new PlaqueSnapshot.RequirementLine(capLine,
                settlement.countedBeds(building), building.beds.size()));
        }

        List<PlaqueSnapshot.Occupant> occupants = new ArrayList<>();
        List<PlaqueSnapshot.Candidate> candidates = new ArrayList<>();
        int capacity = 0;

        if (settlement != null && building != null) {
            // Tech tree (Townhouses / Manors): House places 4 -> 6 -> 8.
            capacity = com.hearthstead.settlement.techtree.effects.CommonsEffects
                .capacityOf(level, settlement, plaque.type(), building);
            List<SettlerEntity> members = SettlementManager.loadedMembers(level, settlement);
            if (probe != null) {
                probe.loadedMemberCollection();
            }
            // Housing capacity is a property of this snapshot's one building,
            // not of each candidate. Resolve it lazily: occupant-only sheets,
            // non-housing plans and not-ready plaques never need the AABB
            // query at all. The first real, ready housing candidate resolves
            // one scalar which every later candidate reuses in wire order.
            int housedCount = 0;
            boolean housedCountResolved = false;
            for (SettlerEntity settler : members) {
                boolean worker = building.workers.contains(settler.getUUID());
                if (com.hearthstead.settlement.BuildingManager
                        .livesOrWorksIn(building, settler)) {
                    occupants.add(new PlaqueSnapshot.Occupant(settler.getUUID(),
                        settler.getSettlerName(), settler.getProfession().name(),
                        settler.getHealth(), settler.getMaxHealth(),
                        Math.round(settler.getMorale()), worker,
                        worker ? employmentRevision(settlement, building,
                            settler.getUUID()) : 0L));
                } else if (plaque.type().housesResidents()) {
                    // Only homes expose move-in candidates. Work assignment is
                    // initiated at the settler with a Job Emblem, so authoring
                    // workplace candidates here would preserve a second path
                    // in the wire model even after its buttons disappeared.
                    if (plaque.state() == PlaqueState.LINKED_VALID) {
                        if (probe != null) {
                            probe.housingCandidateDecision();
                        }
                        if (!housedCountResolved) {
                            housedCount = countHoused(building, level, probe);
                            housedCountResolved = true;
                        }
                    }
                    Employment.Cost cost = Employment.costOfHiring(settlement, settler);
                    candidates.add(new PlaqueSnapshot.Candidate(settler.getUUID(),
                        settler.getSettlerName(), settler.getProfession().name(),
                        settler.getClaimedBed() != null,
                        (int) Math.sqrt(settler.blockPosition().distSqr(plaque.getBlockPos())),
                        blockedReason(plaque, settlement, building, housedCount),
                        Employment.fitness(settlement, settler, building),
                        cost.loses() == null ? "hearthstead.employ.cost.none"
                            : cost.leavesEmpty() ? "hearthstead.employ.cost.leaves_empty"
                            : "hearthstead.employ.cost.moves",
                        cost.loses() == null ? ""
                            : "hearthstead.building." + cost.loses().type.id()));
                }
            }
        }

        // Reuse the exact building already resolved above instead of making
        // PlaqueBlockEntity scan the settlement three more times. The same
        // valid + physical-plaque identity gate as blessingRank() keeps an
        // orphan/invalid link fail-closed; the three reads after it are direct
        // EnumMap lookups. This runs only for the existing open/update event.
        Building blessingBuilding = building != null && building.valid
            && plaque.getBlockPos().equals(building.plaquePos) ? building : null;
        int wardenOathBlessingRank = blessingBuilding == null ? 0
            : blessingBuilding.blessingRank(BlessingId.WARDEN_OATH);
        int hearthwardBlessingRank = blessingBuilding == null ? 0
            : blessingBuilding.blessingRank(BlessingId.HEARTHWARD);
        int thornedRoadsBlessingRank = blessingBuilding == null ? 0
            : blessingBuilding.blessingRank(BlessingId.THORNED_ROADS);

        return new PlaqueSnapshot(plaque.getBlockPos(),
            plaque.buildingId() == null ? PlaqueAction.NO_BUILDING
                : plaque.buildingId(),
            sessionId, plaque.type().id(), plaque.state().id(), plaque.revision(),
            building == null ? 1 : building.level,
            List.copyOf(requirements), List.copyOf(occupants), List.copyOf(candidates),
            capacity, mayManage(player, settlement), wardenOathBlessingRank,
            hearthwardBlessingRank, thornedRoadsBlessingRank,
            delivery, java.util.Optional.ofNullable(plaque.lastScanReason()));
    }

    /**
     * Builder lane (checklist levels): the building's level, what the next
     * level still needs ("next.<id>" lines, the Upgrade Order's gap), and --
     * for a warehouse whose room is ahead of the tree -- which Logistics
     * upgrade recognises it. Extra RequirementLines, so the payload layout
     * is unchanged; display only, never part of registration.
     */
    private static void addLevelLines(ServerLevel level, Settlement settlement, Building building,
                                      List<PlaqueSnapshot.RequirementLine> lines) {
        if (building == null || !building.valid
            || com.hearthstead.building.BuildingLevels.maxLevel(building.type) <= 1) {
            return;
        }
        boolean warehouse = building.type == com.hearthstead.building.BuildingType.WAREHOUSE;
        if (warehouse && !com.hearthstead.HearthsteadServerConfig.warehouseLevelsEnabled()) {
            return;
        }
        lines.add(new PlaqueSnapshot.RequirementLine("level", building.level, building.level));
        if (building.type == com.hearthstead.building.BuildingType.HOUSE) {
            lines.addAll(homeTierLines(level, settlement, building));
        }
        if (warehouse) {
            var status = com.hearthstead.settlement.warehouse.WarehouseLevelService.status(level, settlement, building);
            if (status.techLocked() && status.nextGate() != null) {
                lines.add(new PlaqueSnapshot.RequirementLine("level_locked." + status.nextGate().id(),
                    status.builtLevel(), status.builtLevel() + 1));
            }
        }
        int shown = 0;
        for (com.hearthstead.building.BuildingLevelChecklist.Gap gap : building.nextLevelGap) {
            if (shown++ >= 8) {
                break;
            }
            lines.add(new PlaqueSnapshot.RequirementLine("next." + gap.id(), gap.have(), gap.needed()));
        }
    }

    /**
     * Home tiers (owner, 26 Sep): a House plaque names what the home is
     * (Hut, Cottage, Townhouse, Manor) and the next tier's goal: build its
     * level, learn its Commons node, or both. Display only; the level itself
     * is the checklist's, and the "next." lines below list what to build.
     */
    public static List<PlaqueSnapshot.RequirementLine> homeTierLines(ServerLevel level, Settlement settlement,
                                                                     Building building) {
        List<PlaqueSnapshot.RequirementLine> lines = new java.util.ArrayList<>(2);
        if (building == null || building.type != com.hearthstead.building.BuildingType.HOUSE) {
            return lines;
        }
        java.util.function.Predicate<String> learned = id ->
            com.hearthstead.settlement.development.TechTree.has(level, settlement, id);
        com.hearthstead.building.HomeTier tier = com.hearthstead.building.HomeTier.of(building.level, learned);
        if (tier == null) {
            return lines;
        }
        lines.add(new PlaqueSnapshot.RequirementLine("home_tier." + tier.id(), building.level, tier.level()));
        com.hearthstead.building.HomeTier next = tier.next();
        if (next == null) {
            return lines;
        }
        com.hearthstead.building.HomeTier.Goal goal =
            com.hearthstead.building.HomeTier.goal(next, building.level, learned);
        String key = switch (goal) {
            case BUILD -> "home_tier_next.";
            case BUILD_AND_LEARN -> "home_tier_next_learn.";
            case LEARN -> "home_tier_learn.";
        };
        // A goal is never "met": the learn-only line still has one step left.
        int needed = goal == com.hearthstead.building.HomeTier.Goal.LEARN ? building.level + 1 : next.level();
        lines.add(new PlaqueSnapshot.RequirementLine(key + next.id(), building.level, needed));
        return lines;
    }

    /** Empty when the settler could move in; otherwise why they cannot. */
    private static String blockedReason(PlaqueBlockEntity plaque, Settlement settlement,
                                        Building building, int housedCount) {
        if (plaque.state() != PlaqueState.LINKED_VALID) {
            return "hearthstead.plaque.blocked.not_ready";
        }
        if (plaque.type().housesResidents()) {
            // House bed cap: beds beyond the cap do not take residents.
            return settlement.countedBeds(building) <= housedCount
                ? "hearthstead.plaque.blocked.full" : "";
        }
        if (!Employment.teaches(plaque.type())) {
            return "hearthstead.employ.refused.no_trade";
        }
        return building.workers.size() >= building.workerCapacity()
            ? "hearthstead.plaque.blocked.full" : "";
    }

    private static int countHoused(Building building,
                                   net.minecraft.world.level.Level level,
                                   SnapshotProbe probe) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return 0;
        }
        if (probe != null) {
            probe.housedEntityQuery();
        }
        int housed = 0;
        for (SettlerEntity settler : serverLevel.getEntitiesOfClass(SettlerEntity.class,
            new net.minecraft.world.phys.AABB(building.bounds.minX() - 32,
                building.bounds.minY() - 16, building.bounds.minZ() - 32,
                building.bounds.maxX() + 32, building.bounds.maxY() + 16,
                building.bounds.maxZ() + 32))) {
            if (settler.getClaimedBed() != null
                && building.beds.contains(settler.getClaimedBed())) {
                housed++;
            }
        }
        return housed;
    }

    // -------------------------------------------------------------- utils --

    private static BlockPos freeBed(ServerLevel level, Settlement settlement,
                                    Building building) {
        BlockPos free = BuildingManager.findFreeBed(level, settlement);
        return free != null && building.beds.contains(free) ? free : firstUnclaimed(level,
            settlement, building);
    }

    private static BlockPos firstUnclaimed(ServerLevel level, Settlement settlement,
                                           Building building) {
        List<BlockPos> claimed = new ArrayList<>();
        for (SettlerEntity settler : SettlementManager.loadedMembers(level, settlement)) {
            if (settler.getClaimedBed() != null) {
                claimed.add(settler.getClaimedBed());
            }
        }
        for (BlockPos bed : building.beds) {
            if (!claimed.contains(bed)) {
                return bed;
            }
        }
        return null;
    }

    private static SettlerEntity findSettler(ServerLevel level, Settlement settlement,
                                             UUID id) {
        for (SettlerEntity settler : SettlementManager.loadedMembers(level, settlement)) {
            if (settler.getUUID().equals(id)) {
                return settler;
            }
        }
        return null;
    }

    /**
     * Who may change this building. Single-player and co-op villages share
     * one settlement, so anyone who can reach the plaque may manage it; the
     * hook exists so a future permission model has one place to live.
     */
    private static boolean mayManage(ServerPlayer player, Settlement settlement) {
        return settlement != null;
    }

    private static void deny(ServerPlayer player, String key) {
        player.displayClientMessage(Component.translatable(key), true);
    }

    static void sendUpdate(ServerPlayer player, PlaqueBlockEntity plaque,
                           UUID sessionId) {
        send(player, snapshot(player, plaque, sessionId,
            PlaqueSnapshot.Delivery.UPDATE));
    }

    /** Package-local multiplayer instrumentation; production always passes null. */
    static void sendUpdate(ServerPlayer player, PlaqueBlockEntity plaque,
                           UUID sessionId,
                           SnapshotProbe probe) {
        send(player, snapshot(player, plaque, sessionId,
            PlaqueSnapshot.Delivery.UPDATE, probe));
    }

    /**
     * Per-invocation test counter. Holding this object is the caller's choice;
     * PlaqueNetwork never stores it, so parallel servers and later snapshots
     * cannot inherit test state.
     */
    static final class SnapshotProbe {
        private int loadedMemberCollections;
        private int housedEntityQueries;
        private int housingCandidateDecisions;

        private void loadedMemberCollection() {
            loadedMemberCollections++;
        }

        private void housedEntityQuery() {
            housedEntityQueries++;
        }

        private void housingCandidateDecision() {
            housingCandidateDecisions++;
        }

        int loadedMemberCollections() {
            return loadedMemberCollections;
        }

        int housedEntityQueries() {
            return housedEntityQueries;
        }

        int housingCandidateDecisions() {
            return housingCandidateDecisions;
        }
    }

    private static void send(ServerPlayer player, PlaqueSnapshot snapshot) {
        com.hearthstead.network.PayloadSend.toPlayer(player, snapshot);
    }

    private PlaqueNetwork() {
    }
}
