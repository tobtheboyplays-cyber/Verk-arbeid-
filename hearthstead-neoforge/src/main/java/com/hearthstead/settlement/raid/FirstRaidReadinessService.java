package com.hearthstead.settlement.raid;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.building.PlaqueState;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.ReadyFood;
import com.hearthstead.settlement.RecruitmentPolicy;
import com.hearthstead.settlement.RoomScanner;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.equipment.EquipmentRequirement;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.guard.GuardAssignmentService;
import com.hearthstead.settlement.journey.JourneyDefinition;
import com.hearthstead.settlement.journey.JourneyEvent;
import com.hearthstead.settlement.journey.JourneyEvidence;
import com.hearthstead.settlement.journey.JourneyIds;
import com.hearthstead.settlement.journey.JourneyPresentationMode;
import com.hearthstead.settlement.journey.JourneyReadinessGate;
import com.hearthstead.settlement.journey.JourneyState;
import com.hearthstead.settlement.request.RequestLedger;
import com.hearthstead.settlement.request.RequestLedgerSavedData;
import com.hearthstead.settlement.request.RequestLedgerService;
import com.hearthstead.settlement.request.RequestRecord;
import com.hearthstead.settlement.request.RequestType;
import com.hearthstead.settlement.state.FirstRaidState;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import com.hearthstead.settlement.work.WorkerProvenanceService;
import com.hearthstead.settlement.work.WorkerProvenanceSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One bounded, read-only authority projection for the first-raid checklist.
 *
 * <p>The domain assessment deliberately has no player or menu dependency. It
 * is therefore the same live truth that declaration, warning and attack gates
 * can re-observe. {@link #assessDeclaration} adds only the session rule:
 * the exact short-lived, server-owned player/session/revision proof.
 *
 * <p>No method in this class creates SavedData, refreshes a request, surveys a
 * room, rebuilds a warehouse cache, loads a chunk or dirties a save. Physical
 * observations are limited to already-indexed positions in already-loaded
 * chunks. A malformed or over-cap collection is rejected rather than partly
 * trusted. Every blocker is returned in stable protocol order.
 */
public final class FirstRaidReadinessService {
    public static final int MAX_BUILDINGS = 256;
    public static final int MAX_SETTLERS = 256;
    public static final int MAX_BLOCKERS = 48;
    /** One volley is not raid readiness; this funds a real opening exchange. */
    public static final int MIN_FIRST_RAID_ARROWS = 8;
    public static final long MAX_DECLARATION_SESSION_TTL_TICKS = 1_200L;
    private static final int MIN_FIRST_RAID_SETTLERS = 5;
    private static final int MIN_FIRST_RAID_BEDS = 5;
    private static final int MAX_BEDS_PER_BUILDING = RoomScanner.MAX_VOLUME;
    private static final int MAX_LEGACY_STORED_LOG_COUNT = 64;
    private static final long MAX_WAREHOUSE_BOUNDS_VOLUME =
        (long) RoomScanner.MAX_VOLUME * 4L;
    private static final double MAX_DECLARATION_DISTANCE_SQR = 64.0D;
    private static final long HASH_OFFSET = 0xcbf29ce484222325L;
    private static final long HASH_PRIME = 0x100000001b3L;

    /** Stable protocol ids. Source order is presentation order, never wire. */
    public enum Blocker {
        AUTHORITY_INVALID(0, "authority_invalid"),
        SNAPSHOT_LIMIT_EXCEEDED(1, "snapshot_limit_exceeded"),
        ROSTER_CORRUPT(2, "roster_corrupt"),
        BUILDING_REGISTRY_CORRUPT(3, "building_registry_corrupt"),
        LEGACY_PRODUCTION_PROOF_MISSING(4, "legacy_production_proof_missing"),
        HEARTH_INVALID(5, "hearth_invalid"),
        LUMBER_CAMP_INVALID(6, "lumber_camp_invalid"),
        WAREHOUSE_INVALID(7, "warehouse_invalid"),
        FARMHOUSE_INVALID(8, "farmhouse_invalid"),
        HOUSING_MISSING(9, "housing_missing"),
        HOUSING_INSUFFICIENT(10, "housing_insufficient"),
        TAVERN_INVALID(11, "tavern_invalid"),
        BARRACKS_INVALID(12, "barracks_invalid"),
        LUMBERER_INVALID(13, "lumberer_invalid"),
        COURIER_INVALID(14, "courier_invalid"),
        FARMER_INVALID(15, "farmer_invalid"),
        GUARD_INVALID(16, "guard_invalid"),
        LUMBER_ZONE_MISSING(17, "lumber_zone_missing"),
        FARM_ZONE_MISSING(18, "farm_zone_missing"),
        WORK_PROVENANCE_UNAVAILABLE(19, "work_provenance_unavailable"),
        LUMBER_PROVENANCE_MISSING(20, "lumber_provenance_missing"),
        FARM_PROVENANCE_MISSING(21, "farm_provenance_missing"),
        WORKER_STACKS_UNOBSERVED(22, "worker_stacks_unobserved"),
        WORKER_TRANSIT_UNRESOLVED(23, "worker_transit_unresolved"),
        WORKER_STACK_CONFLICT(24, "worker_stack_conflict"),
        WAREHOUSE_STORAGE_UNAVAILABLE(25, "warehouse_storage_unavailable"),
        REQUEST_LEDGER_UNAVAILABLE(26, "request_ledger_unavailable"),
        REQUEST_LEDGER_QUARANTINED(27, "request_ledger_quarantined"),
        REQUEST_CRITICAL_CONFLICT(28, "request_critical_conflict"),
        REQUEST_IN_TRANSIT_UNRESOLVED(29, "request_in_transit_unresolved"),
        GUARD_UNARMED(30, "guard_unarmed"),
        GUARD_ORDER_INVALID(31, "guard_order_invalid"),
        FOOD_RESERVATION_INVALID(32, "food_reservation_invalid"),
        FOOD_RESERVE_INSUFFICIENT(33, "food_reserve_insufficient"),
        RAID_LIFECYCLE_INVALID(34, "raid_lifecycle_invalid"),
        RAID_STATE_NOT_PREPARING(35, "raid_state_not_preparing"),
        JOURNEY_NOT_READY(36, "journey_not_ready"),
        SESSION_MISMATCH(37, "session_mismatch"),
        RAID_STATE_NOT_SCHEDULED(38, "raid_state_not_scheduled"),
        WATCHTOWER_INVALID(39, "watchtower_invalid"),
        ARCHER_INVALID(40, "archer_invalid"),
        ARCHER_UNARMED(41, "archer_unarmed"),
        ARCHER_NO_ARROWS(42, "archer_no_arrows"),
        ARCHER_ORDER_INVALID(43, "archer_order_invalid"),
        SETTLER_ROSTER_INSUFFICIENT(44, "settler_roster_insufficient"),
        DEFENDERS_NOT_DISTINCT(45, "defenders_not_distinct");

        private final int wireId;
        private final String id;

        Blocker(int wireId, String id) {
            this.wireId = wireId;
            this.id = id;
        }

        public int wireId() {
            return wireId;
        }

        public String id() {
            return id;
        }

        @Nullable
        public static Blocker fromWireId(int wireId) {
            for (Blocker blocker : values()) {
                if (blocker.wireId == wireId) {
                    return blocker;
                }
            }
            return null;
        }
    }

    /** Bounded numbers needed by the future readiness card and QA. */
    public record Metrics(int inspectedBuildings, int inspectedSettlers,
                          int housingCapacity, int warehouseContainers,
                          int readyMeals,
                          int reservedReadyMeals, int availableReadyMeals,
                          int requiredReadyMeals, int provenanceActions,
                          int provenanceReceipts, int requestActiveRows,
                          int requestBlockedRows) {
        public Metrics {
            inspectedBuildings = clamp(inspectedBuildings, MAX_BUILDINGS);
            inspectedSettlers = clamp(inspectedSettlers, MAX_SETTLERS);
            housingCapacity = clamp(housingCapacity,
                MAX_SETTLERS * BuildingType.LODGING.residentCapacity());
            warehouseContainers = clamp(warehouseContainers,
                com.hearthstead.settlement.warehouse.WarehouseLevels.ABSOLUTE_MAX);
            int maxMeals = RequestLedger.MAX_ACTIVE
                * com.hearthstead.settlement.request.RequestItemFingerprint.MAX_COUNT;
            readyMeals = clamp(readyMeals, maxMeals);
            reservedReadyMeals = clamp(reservedReadyMeals, maxMeals);
            availableReadyMeals = clamp(availableReadyMeals, maxMeals);
            requiredReadyMeals = clamp(requiredReadyMeals, maxMeals);
            provenanceActions = clamp(provenanceActions,
                WorkerProvenanceSavedData.MAX_ACTIONS);
            provenanceReceipts = clamp(provenanceReceipts,
                WorkerProvenanceSavedData.MAX_RECEIPTS);
            requestActiveRows = clamp(requestActiveRows,
                RequestLedger.MAX_ACTIVE);
            requestBlockedRows = clamp(requestBlockedRows,
                requestActiveRows);
        }

        private static int clamp(int value, int max) {
            return Math.max(0, Math.min(Math.max(0, max), value));
        }

    }

    /** Immutable result; an empty blocker list is the only ready state. */
    public record Report(List<Blocker> blockers, Metrics metrics,
                         long domainRevision) {
        public Report {
            EnumSet<Blocker> ordered = EnumSet.noneOf(Blocker.class);
            if (blockers != null) {
                for (Blocker blocker : blockers) {
                    if (blocker != null && ordered.size() < MAX_BLOCKERS) {
                        ordered.add(blocker);
                    }
                }
            }
            blockers = List.copyOf(ordered);
            metrics = metrics == null
                ? new Metrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
                : metrics;
            domainRevision = normalizeRevision(domainRevision);
        }

        public boolean ready() {
            return blockers.isEmpty();
        }

        public boolean blockedBy(Blocker blocker) {
            return blocker != null && blockers.contains(blocker);
        }
    }

    /**
     * Exact server-owned declaration proof selected by session UUID before
     * this service is called. The revision is the domain revision shown when
     * the readiness card opened, never a client-authored gameplay fact.
     */
    public record DeclarationSession(UUID sessionId, UUID playerId,
                                     UUID settlementId,
                                     ResourceLocation dimension,
                                     BlockPos hearthPos,
                                     long expectedDomainRevision,
                                     long openedAtTick, long expiresAtTick,
                                     boolean active) {
        public DeclarationSession {
            hearthPos = hearthPos == null ? null : hearthPos.immutable();
        }
    }

    /** Live/persisted gameplay rules with no UI-session dependency. */
    public static Report assessDomain(ServerLevel level,
                                      Settlement settlement) {
        return evaluate(collect(level, settlement), Phase.DECLARATION);
    }

    /**
     * Post-declaration warning/attack gate. It re-observes the same physical
     * gameplay truth, but requires the successful declaration phase
     * (SCHEDULED and FJ-560, or the explicit SKIPPED presentation contract)
     * instead of the mutually exclusive PREPARING/declaration-ready state.
     */
    public static Report assessExecution(ServerLevel level,
                                         Settlement settlement) {
        return evaluate(collect(level, settlement), Phase.EXECUTION);
    }

    /**
     * Narrow half-commit bridge used only after the one-shot raid calendar
     * crossed PREPARING to SCHEDULED and before FJ-560 is authored.
     *
     * <p>This does not weaken warning/attack authority: those paths must use
     * {@link #assessExecution}, which rejects an active Journey until FJ-560
     * exists. The bridge instead requires the mutually exclusive
     * {@link JourneyReadinessGate#canRecordDeclaration} state, so an active
     * Journey closes this path as soon as the hook records FJ-560. SKIPPED
     * remains the documented presentation-only exception and authors no fake
     * evidence.
     */
    public static Report assessScheduledCommitBridge(ServerLevel level,
                                                      Settlement settlement) {
        return evaluate(collect(level, settlement), Phase.COMMIT_BRIDGE);
    }

    /** Domain rules plus exact player/session/settlement/revision rule 20. */
    public static Report assessDeclaration(@Nullable ServerPlayer player,
                                           Settlement settlement,
                                           @Nullable DeclarationSession session) {
        ServerLevel level = player == null ? null : player.serverLevel();
        Report domain = assessDomain(level, settlement);
        if (declarationMatches(player, settlement, session, domain)) {
            return domain;
        }
        EnumSet<Blocker> blockers = domain.blockers().isEmpty()
            ? EnumSet.noneOf(Blocker.class)
            : EnumSet.copyOf(domain.blockers());
        blockers.add(Blocker.SESSION_MISMATCH);
        return new Report(List.copyOf(blockers), domain.metrics(),
            domain.domainRevision());
    }

    private static Facts collect(ServerLevel level, Settlement settlement) {
        Facts facts = new Facts();
        if (level == null || settlement == null || level.getServer() == null
            || !level.getServer().isSameThread() || settlement.id == null
            || isNil(settlement.id) || settlement.center == null) {
            return facts;
        }
        SettlementSavedData saved = SettlementSavedData.existing(level);
        if (saved == null || saved.settlements.get(settlement.id) != settlement) {
            return facts;
        }
        facts.authorityValid = true;
        facts.mix(settlement.id.getMostSignificantBits());
        facts.mix(settlement.id.getLeastSignificantBits());
        facts.mix(settlement.center.asLong());
        facts.mix(settlement.radius);

        facts.hearth = exactHearth(level, settlement);
        facts.hearthValid = facts.hearth != null;

        int buildingCount = settlement.buildings.size();
        int settlerCount = settlement.settlers.size();
        facts.inspectedBuildings = Math.min(buildingCount, MAX_BUILDINGS);
        facts.inspectedSettlers = Math.min(settlerCount, MAX_SETTLERS);
        facts.limitsSafe = buildingCount <= MAX_BUILDINGS
            && settlerCount <= MAX_SETTLERS;
        facts.mix(buildingCount);
        facts.mix(settlerCount);
        if (!facts.limitsSafe) {
            // A prefix of an oversized authority list is never a settlement
            // snapshot. Stop before world/entity queries can bless it.
            facts.rosterSafe = false;
            facts.buildingsSafe = false;
            finishScalarFacts(level, settlement, facts);
            return facts;
        }

        Map<UUID, Integer> recordCounts = new HashMap<>();
        Map<UUID, Settlement.SettlerRecord> records = new LinkedHashMap<>();
        for (Settlement.SettlerRecord record : settlement.settlers) {
            if (record == null || record.entityId == null
                || isNil(record.entityId) || record.profession == null
                || record.name == null) {
                facts.rosterSafe = false;
                continue;
            }
            int count = recordCounts.merge(record.entityId, 1, Integer::sum);
            if (count == 1) {
                records.put(record.entityId, record);
            } else {
                records.remove(record.entityId);
                facts.rosterSafe = false;
            }
            facts.mix(record.entityId.getMostSignificantBits());
            facts.mix(record.entityId.getLeastSignificantBits());
            facts.mix(record.profession.id());
        }

        Map<UUID, Integer> buildingIdCounts = new HashMap<>();
        Map<BlockPos, Integer> plaqueCounts = new HashMap<>();
        for (Building building : settlement.buildings) {
            if (building == null || building.id == null || isNil(building.id)) {
                facts.buildingsSafe = false;
                continue;
            }
            buildingIdCounts.merge(building.id, 1, Integer::sum);
            if (building.plaquePos != null) {
                plaqueCounts.merge(building.plaquePos, 1, Integer::sum);
            }
        }

        Map<UUID, Building> uniqueBuildings = new LinkedHashMap<>();
        Set<Building> linked = new LinkedHashSet<>();
        for (Building building : settlement.buildings) {
            if (!structurallySafeBuilding(building)
                || buildingIdCounts.getOrDefault(building.id, 0) != 1
                || plaqueCounts.getOrDefault(building.plaquePos, 0) != 1) {
                facts.buildingsSafe = false;
                continue;
            }
            uniqueBuildings.put(building.id, building);
            facts.mix(building.id.getMostSignificantBits());
            facts.mix(building.id.getLeastSignificantBits());
            facts.mix(building.type.ordinal());
            facts.mix(building.plaquePos.asLong());
            facts.mix(building.valid ? 1 : 0);
            facts.mix(building.workZoneRevision());
            if (exactLinkedPlaque(level, settlement, building)) {
                linked.add(building);
            }
        }

        Map<UUID, Integer> employmentCounts = new HashMap<>();
        Map<UUID, Building> employers = new HashMap<>();
        Set<UUID> ambiguousEmployment = new HashSet<>();
        for (Building building : settlement.buildings) {
            if (!structurallySafeBuilding(building)) {
                facts.rosterSafe = false;
                continue;
            }
            if (building.workers.size() > building.type.workerCapacity()) {
                facts.rosterSafe = false;
            }
            Set<UUID> local = new HashSet<>();
            Profession trade = Employment.tradeOf(building.type);
            for (UUID workerId : building.workers) {
                if (workerId == null || isNil(workerId)
                    || !local.add(workerId)) {
                    facts.rosterSafe = false;
                    continue;
                }
                int count = employmentCounts.merge(workerId, 1, Integer::sum);
                if (count == 1) {
                    employers.put(workerId, building);
                } else {
                    ambiguousEmployment.add(workerId);
                    employers.remove(workerId);
                    facts.rosterSafe = false;
                }
                Settlement.SettlerRecord record = records.get(workerId);
                if (record == null || trade == Profession.NONE
                    || record.profession != trade) {
                    facts.rosterSafe = false;
                }
                facts.mix(workerId.getMostSignificantBits());
                facts.mix(workerId.getLeastSignificantBits());
            }
        }
        for (Map.Entry<UUID, Settlement.SettlerRecord> row : records.entrySet()) {
            int jobs = employmentCounts.getOrDefault(row.getKey(), 0);
            boolean mayor = row.getKey().equals(settlement.mayorId);
            Profession profession = row.getValue().profession;
            boolean invalid = mayor ? profession != Profession.MAYOR || jobs != 0
                : profession == Profession.MAYOR
                    || (profession == Profession.NONE ? jobs != 0
                        : jobs != 1 || ambiguousEmployment.contains(row.getKey()));
            if (invalid) {
                facts.rosterSafe = false;
            }
        }
        Map<UUID, SettlerEntity> live = new HashMap<>();
        for (Map.Entry<UUID, Settlement.SettlerRecord> row : records.entrySet()) {
            SettlerEntity member = liveMember(level, settlement, row.getKey());
            if (member != null) {
                live.put(row.getKey(), member);
            }
        }
        facts.mix(live.size());

        Building proofCamp = null;
        List<SettlerEntity> guardCandidates = new ArrayList<>();
        List<DefenderCandidate> archerCandidates = new ArrayList<>();
        Map<UUID, ArrowObservation> watchtowerArrows = new HashMap<>();
        Set<BlockPos> physicalHousingBeds = new HashSet<>();
        // Raid readiness is deliberately stricter than day-one settlement
        // capacity: every first-raid member needs one re-observed physical
        // bed. The Hearth's three-founder bootstrap is not a combat shelter.
        int housingCapacity = 0;
        for (Building building : linked) {
            switch (building.type) {
                case LUMBER_CAMP -> facts.lumberCampValid = true;
                case WAREHOUSE -> {
                    facts.warehouseValid = true;
                    WarehouseObservation storage = observeWarehouse(level,
                        settlement, building);
                    facts.warehouseContainers = Math.max(
                        facts.warehouseContainers, storage.containers());
                    facts.warehouseStorageAvailable |= storage.healthy();
                }
                case FARMHOUSE, FISHERY -> facts.farmhouseValid = true;
                case HOUSE, LODGING -> {
                    Set<BlockPos> buildingBeds = physicalBedHeads(level,
                        building);
                    var bedRequirement = building.type.requirementById("beds");
                    if (bedRequirement != null && bedRequirement.needed() > 0
                        && buildingBeds.size() >= bedRequirement.needed()) {
                        facts.housingPresent = true;
                        for (BlockPos bed : buildingBeds) {
                            if (!physicalHousingBeds.add(bed)) {
                                // Two housing authorities may not both own one
                                // physical bed head. Fail closed as registry
                                // corruption instead of double-crediting it.
                                facts.buildingsSafe = false;
                            }
                        }
                    }
                }
                case TAVERN -> facts.tavernValid = true;
                case BARRACKS -> facts.barracksValid = true;
                case WATCHTOWER -> {
                    ArrowObservation arrows = observeWatchtowerArrows(level,
                        settlement, building);
                    watchtowerArrows.put(building.id, arrows);
                    facts.mix(arrows.arrows());
                    facts.mix(arrows.identityHash());
                }
                default -> {
                    // Not part of first-raid readiness.
                }
            }
        }
        housingCapacity = physicalHousingBeds.size();
        facts.housingCapacity = housingCapacity;
        facts.settlerRosterSufficient = live.size()
            >= MIN_FIRST_RAID_SETTLERS;
        facts.housingSufficient = housingCapacity
            >= Math.max(MIN_FIRST_RAID_BEDS, settlerCount);

        for (Map.Entry<UUID, Settlement.SettlerRecord> row : records.entrySet()) {
            Building employer = employers.get(row.getKey());
            SettlerEntity member = live.get(row.getKey());
            if (employer == null || member == null || !linked.contains(employer)
                || member.getProfession() != row.getValue().profession) {
                continue;
            }
            Profession profession = row.getValue().profession;
            if (profession == Profession.LUMBERER
                && employer.type == BuildingType.LUMBER_CAMP
                && hasBoundEmblemProof(settlement, profession, row.getKey(),
                    employer.id)) {
                facts.lumbererValid = true;
            } else if (profession == Profession.COURIER
                && employer.type == BuildingType.WAREHOUSE
                && hasBoundEmblemProof(settlement, profession, row.getKey(),
                    employer.id)) {
                facts.courierValid = true;
            } else if (profession == Profession.FARMER
                && employer.type == BuildingType.FARMHOUSE
                && hasBoundEmblemProof(settlement, profession, row.getKey(),
                    employer.id)) {
                facts.farmerValid = true;
            } else if (profession == Profession.FISHER
                && employer.type == BuildingType.FISHERY
                && hasBoundEmblemProof(settlement, profession, row.getKey(), employer.id)) {
                facts.farmerValid = true;
                facts.fisherValid = true;
            } else if (profession == Profession.GUARD
                && employer.type == BuildingType.BARRACKS
                && hasBoundEmblemProof(settlement, profession, row.getKey(),
                    employer.id)) {
                facts.guardValid = true;
                guardCandidates.add(member);
            } else if (profession == Profession.ARCHER
                && employer.type == BuildingType.WATCHTOWER
                && hasBoundEmblemProof(settlement, profession, row.getKey(),
                    employer.id)) {
                facts.archerValid = true;
                archerCandidates.add(new DefenderCandidate(member, employer));
            }
        }
        Set<UUID> defenderIds = new HashSet<>();
        for (SettlerEntity guard : guardCandidates) {
            defenderIds.add(guard.getUUID());
        }
        for (DefenderCandidate archer : archerCandidates) {
            if (!defenderIds.add(archer.entity().getUUID())) {
                facts.defendersDistinct = false;
            }
        }

        FirstRaidReadiness legacy = settlement.firstRaidReadiness;
        UUID proofWorkerId = legacy == null ? null : legacy.workerId();
        UUID proofCampId = legacy == null ? null : legacy.lumberCampId();
        if (proofCampId != null) {
            proofCamp = uniqueBuildings.get(proofCampId);
        }
        facts.legacyProofValid = legacy != null
            && legacy.stage() == FirstRaidReadiness.Stage.PRODUCTION_STORED
            && legacy.revision() > 0
            && legacy.revision() <= FirstRaidReadiness.MAX_REVISION
            && proofWorkerId != null && proofCampId != null
            && legacy.storedLogCount() > 0
            && legacy.storedLogCount() <= MAX_LEGACY_STORED_LOG_COUNT
            && legacy.storedAtGameTime() >= 0L
            && legacy.storedAtGameTime() <= level.getGameTime()
            && proofCamp != null && linked.contains(proofCamp)
            && proofCamp.type == BuildingType.LUMBER_CAMP
            && records.containsKey(proofWorkerId)
            && records.get(proofWorkerId).profession == Profession.LUMBERER
            && employers.get(proofWorkerId) == proofCamp;
        if (legacy != null) {
            facts.mix(legacy.revision());
            facts.mix(legacy.stage().ordinal());
            facts.mix(legacy.storedLogCount());
            facts.mix(legacy.storedAtGameTime());
        }

        if (facts.limitsSafe && facts.rosterSafe && facts.buildingsSafe) {
            WorkerProvenanceService.ReadinessEvidence provenance =
                WorkerProvenanceService.readinessEvidence(level, settlement);
            facts.provenanceAuthoritative = provenance.authoritative();
            facts.lumberZoneCommitted = provenance.lumberZoneCommitted();
            facts.farmZoneCommitted = provenance.farmZoneCommitted();
            facts.lumberProvenance = provenance.lumberWorkCommitted()
                && provenance.lumberOutputCommitted();
            facts.farmProvenance = provenance.farmSeedPlantedCommitted()
                && provenance.farmHarvestCommitted()
                && provenance.farmOutputCommitted();
            if (facts.fisherValid
                && com.hearthstead.settlement.work.FisherEvidenceSavedData.hasStoredCatch(level, settlement)) {
                facts.farmZoneCommitted = true;
                facts.farmProvenance = true;
            }
            facts.workerStacksObserved = provenance.allResidentStacksObserved();
            facts.workerTransitUnresolved = provenance.unresolvedWorkerTransit();
            facts.workerStackConflict = provenance.conflictingStackOwnership();
            facts.provenanceActions = provenance.actionRows();
            facts.provenanceReceipts = provenance.receiptRows();
        }

        RequestLedgerService.ConflictSummary requests =
            RequestLedgerService.inspectConflicts(level, settlement);
        facts.requestAvailable = requests.available();
        facts.requestQuarantined = requests.quarantined();
        facts.requestCriticalConflict = requests.criticalConflict();
        facts.requestTransitUnresolved = requests.unresolvedInTransit();
        facts.requestActiveRows = requests.activeRows();
        facts.requestBlockedRows = requests.blockedRows();

        ReservationObservation reservations = observeFoodReservations(level,
            settlement);
        facts.foodReservationsValid = reservations.valid();
        facts.reservedReadyMeals = reservations.reserved();
        facts.requestCriticalConflict |= !reservations.valid();

        EquipmentRequirement guardWeapon = EquipmentRequests.requirementFor(
            Profession.GUARD);
        for (SettlerEntity guard : guardCandidates) {
            if (guardWeapon == null
                || !guardWeapon.serviceable(guard.getMainHandItem())) {
                continue;
            }
            facts.guardArmed = true;
            if (GuardAssignmentService.hasValidFirstRaidOrder(level, settlement,
                    guard)) {
                facts.guardOrderValid = true;
            }
        }

        EquipmentRequirement archerWeapon = EquipmentRequests.requirementFor(
            Profession.ARCHER);
        for (DefenderCandidate candidate : archerCandidates) {
            ArrowObservation arrows = watchtowerArrows.get(
                candidate.employer().id);
            if (arrows == null || !arrows.healthy()) {
                continue;
            }
            facts.watchtowerValid = true;
            SettlerEntity archer = candidate.entity();
            int carriedQuiver = archer.archerQuiverCount();
            UUID quiverSource = archer.archerQuiverSourceBuildingId();
            facts.mix(carriedQuiver);
            facts.mix(quiverSource == null ? 0L
                : quiverSource.getMostSignificantBits());
            facts.mix(quiverSource == null ? 0L
                : quiverSource.getLeastSignificantBits());
            int persistedQuiver = archer.archerQuiverOwnedBy(
                candidate.employer().id) ? carriedQuiver : 0;
            if (saturatingAdd(arrows.arrows(), persistedQuiver)
                    < MIN_FIRST_RAID_ARROWS) {
                continue;
            }
            facts.archerHasArrows = true;
            if (archerWeapon == null
                || !archerWeapon.serviceable(archer.getMainHandItem())) {
                continue;
            }
            facts.archerArmed = true;
            if (GuardAssignmentService.hasValidFirstRaidOrder(level, settlement,
                    archer)) {
                facts.archerOrderValid = true;
            }
        }

        finishScalarFacts(level, settlement, facts);
        return facts;
    }

    private static void finishScalarFacts(ServerLevel level,
                                          Settlement settlement,
                                          Facts facts) {
        facts.readyMeals = facts.hearth == null ? 0
            : ReadyFood.count(facts.hearth.getInventory());
        facts.requiredReadyMeals = RecruitmentPolicy.requiredReserve(
            settlement == null ? 0 : settlement.settlers.size());
        facts.availableReadyMeals = Math.max(0,
            facts.readyMeals - facts.reservedReadyMeals);
        facts.foodReserveSufficient = facts.foodReservationsValid
            && facts.availableReadyMeals >= facts.requiredReadyMeals;
        facts.raidLifecycleHealthy = settlement != null
            && settlement.raidLifecycle != null
            && !settlement.raidLifecycle.integrityLost();
        facts.raidPreparing = facts.raidLifecycleHealthy
            && settlement.raidLifecycle.firstState() == FirstRaidState.PREPARING;
        facts.raidScheduled = facts.raidLifecycleHealthy
            && settlement.raidLifecycle.firstState() == FirstRaidState.SCHEDULED;
        facts.journeyReady = settlement != null
            && settlement.journeyState != null
            && Objects.equals(settlement.journeyState.settlementId(),
                settlement.id)
            && JourneyReadinessGate.canRecordDeclaration(
                settlement.journeyState);
        facts.journeyDeclared = settlement != null
            && settlement.journeyState != null
            && Objects.equals(settlement.journeyState.settlementId(),
                settlement.id)
            && (settlement.journeyState.mode()
                    == JourneyPresentationMode.SKIPPED
                || settlement.journeyState.isCompleted(
                    JourneyIds.FJ_560_DECLARE_RAID_READY));
        if (settlement != null && settlement.journeyState != null) {
            facts.mix(settlement.journeyState.revision());
            facts.mix(settlement.journeyState.mode().ordinal());
        }
        if (settlement != null && settlement.raidLifecycle != null) {
            facts.mix(settlement.raidLifecycle.firstState().wireId());
            facts.mix(settlement.raidLifecycle.integrityLost() ? 1 : 0);
        }
        facts.mix(facts.readyMeals);
        facts.mix(facts.reservedReadyMeals);
        facts.mix(facts.requiredReadyMeals);
    }

    private static Report evaluate(Facts facts, Phase phase) {
        EnumSet<Blocker> blockers = EnumSet.noneOf(Blocker.class);
        if (!facts.authorityValid) blockers.add(Blocker.AUTHORITY_INVALID);
        if (!facts.limitsSafe) blockers.add(Blocker.SNAPSHOT_LIMIT_EXCEEDED);
        if (!facts.rosterSafe) blockers.add(Blocker.ROSTER_CORRUPT);
        if (!facts.buildingsSafe) blockers.add(Blocker.BUILDING_REGISTRY_CORRUPT);
        if (!facts.legacyProofValid) {
            blockers.add(Blocker.LEGACY_PRODUCTION_PROOF_MISSING);
        }
        if (!facts.hearthValid) blockers.add(Blocker.HEARTH_INVALID);
        if (!facts.lumberCampValid) blockers.add(Blocker.LUMBER_CAMP_INVALID);
        if (!facts.warehouseValid) blockers.add(Blocker.WAREHOUSE_INVALID);
        if (!facts.farmhouseValid) blockers.add(Blocker.FARMHOUSE_INVALID);
        if (!facts.housingPresent) blockers.add(Blocker.HOUSING_MISSING);
        if (!facts.housingSufficient) blockers.add(Blocker.HOUSING_INSUFFICIENT);
        if (!facts.tavernValid) blockers.add(Blocker.TAVERN_INVALID);
        if (!facts.barracksValid) blockers.add(Blocker.BARRACKS_INVALID);
        if (!facts.lumbererValid) blockers.add(Blocker.LUMBERER_INVALID);
        if (!facts.courierValid) blockers.add(Blocker.COURIER_INVALID);
        if (!facts.farmerValid) blockers.add(Blocker.FARMER_INVALID);
        if (!facts.guardValid) blockers.add(Blocker.GUARD_INVALID);
        if (!facts.provenanceAuthoritative) {
            blockers.add(Blocker.WORK_PROVENANCE_UNAVAILABLE);
        } else {
            if (!facts.lumberZoneCommitted) {
                blockers.add(Blocker.LUMBER_ZONE_MISSING);
            }
            if (!facts.farmZoneCommitted) {
                blockers.add(Blocker.FARM_ZONE_MISSING);
            }
            if (!facts.lumberProvenance) {
                blockers.add(Blocker.LUMBER_PROVENANCE_MISSING);
            }
            if (!facts.farmProvenance) {
                blockers.add(Blocker.FARM_PROVENANCE_MISSING);
            }
            if (!facts.workerStacksObserved) {
                blockers.add(Blocker.WORKER_STACKS_UNOBSERVED);
            }
            if (facts.workerTransitUnresolved) {
                blockers.add(Blocker.WORKER_TRANSIT_UNRESOLVED);
            }
            if (facts.workerStackConflict) {
                blockers.add(Blocker.WORKER_STACK_CONFLICT);
            }
        }
        if (!facts.warehouseStorageAvailable) {
            blockers.add(Blocker.WAREHOUSE_STORAGE_UNAVAILABLE);
        }
        if (!facts.requestAvailable) {
            blockers.add(Blocker.REQUEST_LEDGER_UNAVAILABLE);
        } else {
            if (facts.requestQuarantined) {
                blockers.add(Blocker.REQUEST_LEDGER_QUARANTINED);
            }
            if (facts.requestCriticalConflict) {
                blockers.add(Blocker.REQUEST_CRITICAL_CONFLICT);
            }
            if (facts.requestTransitUnresolved) {
                blockers.add(Blocker.REQUEST_IN_TRANSIT_UNRESOLVED);
            }
        }
        if (!facts.guardArmed) blockers.add(Blocker.GUARD_UNARMED);
        if (!facts.guardOrderValid) blockers.add(Blocker.GUARD_ORDER_INVALID);
        if (!facts.foodReservationsValid) {
            blockers.add(Blocker.FOOD_RESERVATION_INVALID);
        }
        if (!facts.foodReserveSufficient) {
            blockers.add(Blocker.FOOD_RESERVE_INSUFFICIENT);
        }
        if (!facts.raidLifecycleHealthy) {
            blockers.add(Blocker.RAID_LIFECYCLE_INVALID);
        }
        if (!facts.watchtowerValid) blockers.add(Blocker.WATCHTOWER_INVALID);
        if (!facts.archerValid) blockers.add(Blocker.ARCHER_INVALID);
        if (!facts.archerArmed) blockers.add(Blocker.ARCHER_UNARMED);
        if (!facts.archerHasArrows) blockers.add(Blocker.ARCHER_NO_ARROWS);
        if (!facts.archerOrderValid) {
            blockers.add(Blocker.ARCHER_ORDER_INVALID);
        }
        if (!facts.settlerRosterSufficient) {
            blockers.add(Blocker.SETTLER_ROSTER_INSUFFICIENT);
        }
        if (!facts.defendersDistinct) {
            blockers.add(Blocker.DEFENDERS_NOT_DISTINCT);
        }
        if (phase == Phase.EXECUTION) {
            if (!facts.raidScheduled) {
                blockers.add(Blocker.RAID_STATE_NOT_SCHEDULED);
            }
            if (!facts.journeyDeclared) {
                blockers.add(Blocker.JOURNEY_NOT_READY);
            }
        } else if (phase == Phase.COMMIT_BRIDGE) {
            if (!facts.raidScheduled) {
                blockers.add(Blocker.RAID_STATE_NOT_SCHEDULED);
            }
            if (!facts.journeyReady) {
                blockers.add(Blocker.JOURNEY_NOT_READY);
            }
        } else {
            if (!facts.raidPreparing) {
                blockers.add(Blocker.RAID_STATE_NOT_PREPARING);
            }
            if (!facts.journeyReady) blockers.add(Blocker.JOURNEY_NOT_READY);
        }

        Metrics metrics = new Metrics(facts.inspectedBuildings,
            facts.inspectedSettlers, facts.housingCapacity,
            facts.warehouseContainers,
            facts.readyMeals, facts.reservedReadyMeals,
            facts.availableReadyMeals, facts.requiredReadyMeals,
            facts.provenanceActions, facts.provenanceReceipts,
            facts.requestActiveRows, facts.requestBlockedRows);
        long revision = facts.identityHash;
        for (Blocker blocker : blockers) {
            revision = mix(revision, blocker.wireId());
        }
        revision = mix(revision, facts.readyMeals);
        revision = mix(revision, facts.reservedReadyMeals);
        revision = mix(revision, facts.requiredReadyMeals);
        revision = mix(revision, facts.housingCapacity);
        revision = mix(revision, facts.provenanceActions);
        revision = mix(revision, facts.provenanceReceipts);
        revision = mix(revision, facts.requestActiveRows);
        revision = mix(revision, facts.requestBlockedRows);
        revision = mix(revision, phase.ordinal());
        return new Report(List.copyOf(blockers), metrics, revision);
    }

    /** Package-only pure evaluator for exhaustive deterministic unit tests. */
    static Report evaluateForTest(Facts facts) {
        return evaluate(facts == null ? new Facts() : facts,
            Phase.DECLARATION);
    }

    /** Package-only execution-phase seam for deterministic transition tests. */
    static Report evaluateExecutionForTest(Facts facts) {
        return evaluate(facts == null ? new Facts() : facts,
            Phase.EXECUTION);
    }

    /** Package-only half-commit seam for the atomic phase sequence test. */
    static Report evaluateScheduledCommitBridgeForTest(Facts facts) {
        return evaluate(facts == null ? new Facts() : facts,
            Phase.COMMIT_BRIDGE);
    }

    private static boolean declarationMatches(@Nullable ServerPlayer player,
                                              Settlement settlement,
                                              @Nullable DeclarationSession session,
                                              Report domain) {
        if (player == null || settlement == null || session == null
            || !session.active() || session.sessionId() == null
            || isNil(session.sessionId()) || session.playerId() == null
            || session.settlementId() == null || session.dimension() == null
            || session.hearthPos() == null || player.isRemoved()
            || !player.isAlive() || player.isSpectator()) {
            return false;
        }
        ServerLevel level = player.serverLevel();
        long now = level.getGameTime();
        long ttl = session.expiresAtTick() - session.openedAtTick();
        return session.playerId().equals(player.getUUID())
            && session.settlementId().equals(settlement.id)
            && session.dimension().equals(level.dimension().location())
            && session.hearthPos().equals(settlement.center)
            && session.expectedDomainRevision() == domain.domainRevision()
            && session.expectedDomainRevision() > 0L
            && session.openedAtTick() >= 0L
            && session.expiresAtTick() >= session.openedAtTick()
            && ttl >= 0L && ttl <= MAX_DECLARATION_SESSION_TTL_TICKS
            && now >= session.openedAtTick() && now <= session.expiresAtTick()
            && player.distanceToSqr(settlement.center.getX() + 0.5D,
                settlement.center.getY() + 0.5D,
                settlement.center.getZ() + 0.5D)
                <= MAX_DECLARATION_DISTANCE_SQR;
    }

    @Nullable
    private static HearthBlockEntity exactHearth(ServerLevel level,
                                                  Settlement settlement) {
        BlockPos center = settlement.center;
        if (!level.hasChunkAt(center)) {
            return null;
        }
        BlockEntity found = level.getBlockEntity(center);
        if (!(found instanceof HearthBlockEntity hearth)
            || hearth.isRemoved() || hearth.getLevel() != level
            || !center.equals(hearth.getBlockPos())
            || !Objects.equals(hearth.getSettlementId(), settlement.id)) {
            return null;
        }
        return hearth;
    }

    private static boolean structurallySafeBuilding(@Nullable Building building) {
        if (building == null || building.id == null || isNil(building.id)
            || building.type == null || building.plaquePos == null
            || building.anchor == null || building.bounds == null
            || building.interiorVolume <= 0
            || building.interiorVolume > RoomScanner.MAX_VOLUME
            || building.workers == null || building.beds == null
            || building.beds.size() > MAX_BEDS_PER_BUILDING
            || !boundedRoom(building.bounds)) {
            return false;
        }
        if (building.workers.size() > Math.max(0,
                building.type.workerCapacity())) {
            return false;
        }
        Set<UUID> workers = new HashSet<>();
        for (UUID worker : building.workers) {
            if (worker == null || isNil(worker) || !workers.add(worker)) {
                return false;
            }
        }
        Set<BlockPos> beds = new HashSet<>();
        for (BlockPos bed : building.beds) {
            if (bed == null || !building.bounds.isInside(bed)
                || !beds.add(bed)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Journey staffing is immutable tutorial history, not a current job
     * credential. The charged-emblem ledger is overwritten for a legitimate
     * replacement/rebuild/reassignment and cleared by every free/admin or
     * terminal employment mutation.
     */
    private static boolean hasBoundEmblemProof(Settlement settlement,
                                               Profession profession,
                                               UUID workerId,
                                               UUID buildingId) {
        return settlement != null && settlement.employmentAuthorizations != null
            && settlement.employmentAuthorizations.matches(settlement.id,
                workerId, buildingId, profession);
    }

    private static boolean exactLinkedPlaque(ServerLevel level,
                                             Settlement settlement,
                                             Building building) {
        if (!building.valid || !level.hasChunkAt(building.plaquePos)) {
            return false;
        }
        BlockEntity found = level.getBlockEntity(building.plaquePos);
        return found instanceof PlaqueBlockEntity plaque
            && !plaque.isRemoved() && plaque.getLevel() == level
            && building.plaquePos.equals(plaque.getBlockPos())
            && plaque.state() == PlaqueState.LINKED_VALID
            && plaque.type() == building.type
            && Objects.equals(plaque.buildingId(), building.id)
            && plaque.building(level) == building;
    }

    @Nullable
    private static SettlerEntity liveMember(ServerLevel level,
                                             Settlement settlement,
                                             UUID entityId) {
        Entity found = level.getEntity(entityId);
        if (!(found instanceof SettlerEntity settler)
            || settler.isRemoved() || !settler.isAlive()
            || settler.level() != level
            || level.getEntity(settler.getId()) != settler
            || settler.isTraveler()
            || !Objects.equals(settler.getSettlementId(), settlement.id)
            || !Objects.equals(settler.getHearthPos(), settlement.center)) {
            return null;
        }
        return settler;
    }

    private static Set<BlockPos> physicalBedHeads(ServerLevel level,
                                                  Building building) {
        Set<BlockPos> seen = new HashSet<>();
        for (BlockPos pos : building.beds) {
            if (seen.size() >= building.type.residentCapacity()
                || pos == null || !seen.add(pos) || !building.contains(pos)
                || !level.hasChunkAt(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof BedBlock
                && state.hasProperty(BedBlock.PART)
                && state.getValue(BedBlock.PART) == BedPart.HEAD) {
                // Already present in the returned identity set.
            } else {
                seen.remove(pos);
            }
        }
        return seen;
    }

    /**
     * GameTest seam: whether this one warehouse satisfies the readiness
     * storage check ({@code warehouse_storage_unavailable} is raised when no
     * warehouse does). Read-only.
     */
    public static boolean warehouseStorageHealthy(ServerLevel level,
                                                  Building building) {
        return building != null && building.bounds != null
            && observeWarehouse(level, null, building).healthy();
    }

    private static WarehouseObservation observeWarehouse(ServerLevel level,
                                                          Settlement settlement,
                                                          Building building) {
        BoundingBox bounds = building.bounds;
        long sx = (long) bounds.maxX() - bounds.minX() + 1L;
        long sy = (long) bounds.maxY() - bounds.minY() + 1L;
        long sz = (long) bounds.maxZ() - bounds.minZ() + 1L;
        if (sx <= 0L || sy <= 0L || sz <= 0L
            || sx > RoomScanner.MAX_EXTENT * 2L + 1L
            || sy > RoomScanner.MAX_HEIGHT * 2L + 1L
            || sz > RoomScanner.MAX_EXTENT * 2L + 1L) {
            return WarehouseObservation.UNAVAILABLE;
        }
        long volume = sx * sy * sz;
        if (volume <= 0L || volume > MAX_WAREHOUSE_BOUNDS_VOLUME
            || building.interiorVolume <= 0
            || building.interiorVolume > RoomScanner.MAX_VOLUME
            || !allChunksLoaded(level, bounds)) {
            return WarehouseObservation.UNAVAILABLE;
        }
        if (!indexMatchesPhysical(level, building, bounds)) {
            return WarehouseObservation.UNAVAILABLE;
        }
        // Only MANAGED containers count. Containers beyond the warehouse
        // level's capacity are "not managed (warehouse full)": never a
        // blocker, never a truncated-prefix failure (owner, 26 Sep).
        List<BlockPos> indexed = WarehouseIndex.containers(level, building);
        int physical = 0;
        Set<BlockPos> seen = new HashSet<>();
        for (BlockPos pos : indexed) {
            if (pos == null || !seen.add(pos) || !bounds.isInside(pos)
                || !level.hasChunkAt(pos)) {
                return WarehouseObservation.UNAVAILABLE;
            }
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (!(blockEntity instanceof ChestBlockEntity
                    || blockEntity instanceof BarrelBlockEntity)
                || blockEntity.isRemoved() || blockEntity.getLevel() != level) {
                return WarehouseObservation.UNAVAILABLE;
            }
            physical++;
        }
        var storage = building.type.requirementById("storage");
        boolean healthy = storage != null && storage.needed() > 0
            && physical >= storage.needed();
        return new WarehouseObservation(healthy, physical);
    }

    /**
     * Re-observes the exact physical arrow rack used by ArcherAttackGoal.
     * Requests, projected deliveries and cached counts are intentionally not
     * ammunition: only vanilla arrows presently stored in the Watchtower's
     * already-indexed, already-loaded chest/barrel inventory count.
     */
    private static ArrowObservation observeWatchtowerArrows(
            ServerLevel level, Settlement settlement, Building building) {
        if (building == null || building.type != BuildingType.WATCHTOWER) {
            return ArrowObservation.UNAVAILABLE;
        }
        BoundingBox bounds = building.bounds;
        long sx = (long) bounds.maxX() - bounds.minX() + 1L;
        long sy = (long) bounds.maxY() - bounds.minY() + 1L;
        long sz = (long) bounds.maxZ() - bounds.minZ() + 1L;
        if (sx <= 0L || sy <= 0L || sz <= 0L
            || sx > RoomScanner.MAX_EXTENT * 2L + 1L
            || sy > RoomScanner.MAX_HEIGHT * 2L + 1L
            || sz > RoomScanner.MAX_EXTENT * 2L + 1L
            || sx * sy * sz > MAX_WAREHOUSE_BOUNDS_VOLUME
            || building.interiorVolume <= 0
            || building.interiorVolume > RoomScanner.MAX_VOLUME
            || !allChunksLoaded(level, bounds)) {
            return ArrowObservation.UNAVAILABLE;
        }
        if (!indexMatchesPhysical(level, building, bounds)) {
            return ArrowObservation.UNAVAILABLE;
        }
        List<BlockPos> indexed = WarehouseIndex.containers(level, building);
        int arrows = 0;
        long identity = HASH_OFFSET;
        Set<BlockPos> seen = new HashSet<>();
        for (BlockPos pos : indexed) {
            if (pos == null || !seen.add(pos) || !bounds.isInside(pos)
                || !level.hasChunkAt(pos)) {
                return ArrowObservation.UNAVAILABLE;
            }
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (!(blockEntity instanceof ChestBlockEntity
                    || blockEntity instanceof BarrelBlockEntity)
                || !(blockEntity instanceof Container container)
                || blockEntity.isRemoved() || blockEntity.getLevel() != level) {
                return ArrowObservation.UNAVAILABLE;
            }
            identity = mix(identity, pos.asLong());
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.is(Items.ARROW)) {
                    arrows = saturatingAdd(arrows, stack.getCount());
                    identity = mix(identity, slot);
                    identity = mix(identity, stack.getCount());
                }
            }
        }
        var storage = building.type.requirementById("storage");
        boolean healthy = storage != null && storage.needed() > 0
            && indexed.size() >= storage.needed();
        return new ArrowObservation(healthy, arrows, identity);
    }

    /**
     * The cached container index must agree with an exact physical recount
     * of the (already loaded, volume-bounded) room: managed plus not-managed
     * equals every container present. A disagreement forces one rescan
     * before readiness fails closed. Having MORE containers than the
     * warehouse level manages is never a failure.
     */
    private static boolean indexMatchesPhysical(ServerLevel level, Building building,
                                                BoundingBox bounds) {
        int exactPhysical = exactPhysicalContainerCount(level, bounds, building.type);
        if (exactPhysical == WarehouseIndex.knownCount(level, building)) {
            return true;
        }
        WarehouseIndex.rescan(level, building);
        return exactPhysical == WarehouseIndex.knownCount(level, building);
    }

    /** Uncapped: the callers bound the volume before walking it. */
    private static int exactPhysicalContainerCount(ServerLevel level,
                                                   BoundingBox bounds,
                                                   BuildingType type) {
        int found = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    cursor.set(x, y, z);
                    if (WarehouseIndex.isContainer(level.getBlockEntity(cursor), type)) {
                        found++;
                    }
                }
            }
        }
        return found;
    }

    private static boolean allChunksLoaded(ServerLevel level,
                                           BoundingBox bounds) {
        int minChunkX = Math.floorDiv(bounds.minX(), 16);
        int maxChunkX = Math.floorDiv(bounds.maxX(), 16);
        int minChunkZ = Math.floorDiv(bounds.minZ(), 16);
        int maxChunkZ = Math.floorDiv(bounds.maxZ(), 16);
        if ((long) maxChunkX - minChunkX > 8L
            || (long) maxChunkZ - minChunkZ > 8L) {
            return false;
        }
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                BlockPos probe = new BlockPos(chunkX << 4,
                    Math.max(level.getMinBuildHeight(), bounds.minY()),
                    chunkZ << 4);
                if (!level.hasChunkAt(probe)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static ReservationObservation observeFoodReservations(
            ServerLevel level, Settlement settlement) {
        RequestLedgerSavedData saved = RequestLedgerSavedData.existing(level);
        if (saved == null) {
            return ReservationObservation.NONE;
        }
        if (saved.rootQuarantined()) {
            return ReservationObservation.INVALID;
        }
        RequestLedger ledger = saved.existing(settlement.id);
        if (ledger == null) {
            return ReservationObservation.NONE;
        }
        if (ledger.quarantined() || ledger.active().size() > RequestLedger.MAX_ACTIVE) {
            return ReservationObservation.INVALID;
        }
        int reserved = 0;
        for (RequestRecord request : ledger.active()) {
            if (request == null || !settlement.id.equals(request.settlementId())
                || !level.dimension().location().equals(
                    request.dimensionId())) {
                return ReservationObservation.INVALID;
            }
            if (request.type() != RequestType.FOOD) {
                continue;
            }
            ItemStack prototype = request.fingerprint().prototype(
                level.registryAccess());
            int remaining = request.remainingCount();
            if (!ReadyFood.isReadyMeal(prototype) || remaining <= 0
                || remaining > request.fingerprint().count()) {
                return ReservationObservation.INVALID;
            }
            reserved = saturatingAdd(reserved, remaining);
        }
        return new ReservationObservation(true, reserved);
    }

    private static int saturatingAdd(int left, int right) {
        if (left < 0 || right < 0 || left > Integer.MAX_VALUE - right) {
            return Integer.MAX_VALUE;
        }
        return left + right;
    }

    private static boolean boundedRoom(BoundingBox bounds) {
        long sx = (long) bounds.maxX() - bounds.minX() + 1L;
        long sy = (long) bounds.maxY() - bounds.minY() + 1L;
        long sz = (long) bounds.maxZ() - bounds.minZ() + 1L;
        return sx > 0L && sy > 0L && sz > 0L
            && sx <= RoomScanner.MAX_EXTENT * 2L + 1L
            && sy <= RoomScanner.MAX_HEIGHT * 2L + 1L
            && sz <= RoomScanner.MAX_EXTENT * 2L + 1L;
    }

    private static boolean isNil(UUID value) {
        return value.getMostSignificantBits() == 0L
            && value.getLeastSignificantBits() == 0L;
    }

    private static long mix(long hash, long value) {
        long mixed = hash == 0L ? HASH_OFFSET : hash;
        mixed ^= value;
        mixed *= HASH_PRIME;
        mixed ^= value >>> 32;
        mixed *= HASH_PRIME;
        return mixed;
    }

    private static long normalizeRevision(long value) {
        long normalized = value & Long.MAX_VALUE;
        return normalized == 0L ? 1L : normalized;
    }

    private record WarehouseObservation(boolean healthy, int containers) {
        private static final WarehouseObservation UNAVAILABLE =
            new WarehouseObservation(false, 0);
    }

    private record ArrowObservation(boolean healthy, int arrows,
                                    long identityHash) {
        private static final ArrowObservation UNAVAILABLE =
            new ArrowObservation(false, 0, 0L);
    }

    private record DefenderCandidate(SettlerEntity entity, Building employer) {
    }

    private record ReservationObservation(boolean valid, int reserved) {
        private static final ReservationObservation NONE =
            new ReservationObservation(true, 0);
        private static final ReservationObservation INVALID =
            new ReservationObservation(false, 0);
    }

    /** Package-visible mutable facts are an isolated pure-test seam only. */
    static final class Facts {
        boolean authorityValid;
        boolean limitsSafe = true;
        boolean rosterSafe = true;
        boolean buildingsSafe = true;
        boolean legacyProofValid;
        boolean hearthValid;
        @Nullable HearthBlockEntity hearth;
        boolean lumberCampValid;
        boolean warehouseValid;
        boolean farmhouseValid;
        boolean housingPresent;
        boolean housingSufficient;
        boolean tavernValid;
        boolean barracksValid;
        boolean watchtowerValid;
        boolean lumbererValid;
        boolean courierValid;
        boolean farmerValid;
        boolean fisherValid;
        boolean guardValid;
        boolean archerValid;
        boolean provenanceAuthoritative;
        boolean lumberZoneCommitted;
        boolean farmZoneCommitted;
        boolean lumberProvenance;
        boolean farmProvenance;
        boolean workerStacksObserved;
        boolean workerTransitUnresolved;
        boolean workerStackConflict;
        boolean warehouseStorageAvailable;
        boolean requestAvailable;
        boolean requestQuarantined;
        boolean requestCriticalConflict;
        boolean requestTransitUnresolved;
        boolean guardArmed;
        boolean guardOrderValid;
        boolean archerArmed;
        boolean archerHasArrows;
        boolean archerOrderValid;
        boolean settlerRosterSufficient;
        boolean defendersDistinct = true;
        boolean foodReservationsValid = true;
        boolean foodReserveSufficient;
        boolean raidLifecycleHealthy;
        boolean raidPreparing;
        boolean raidScheduled;
        boolean journeyReady;
        boolean journeyDeclared;
        int inspectedBuildings;
        int inspectedSettlers;
        int warehouseContainers;
        int housingCapacity;
        int readyMeals;
        int reservedReadyMeals;
        int availableReadyMeals;
        int requiredReadyMeals;
        int provenanceActions;
        int provenanceReceipts;
        int requestActiveRows;
        int requestBlockedRows;
        long identityHash = HASH_OFFSET;

        void mix(long value) {
            identityHash = FirstRaidReadinessService.mix(identityHash, value);
        }
    }

    private enum Phase {
        DECLARATION,
        COMMIT_BRIDGE,
        EXECUTION
    }

    private FirstRaidReadinessService() {
    }
}
