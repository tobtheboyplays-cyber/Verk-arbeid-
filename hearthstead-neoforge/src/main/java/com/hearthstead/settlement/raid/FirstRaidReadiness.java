package com.hearthstead.settlement.raid;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.building.PlaqueState;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.development.Development;
import com.hearthstead.settlement.development.DevelopmentNode;
import com.hearthstead.settlement.development.DevelopmentState;
import com.hearthstead.settlement.equipment.EquipmentRequirement;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.journey.JourneyReadinessGate;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Durable, server-authored proof that a settlement earned its first raid.
 *
 * <p>The ledger records the two one-time founding commits: a Lumberer entering
 * the real Lumber Camp after the matching physical emblem was consumed, and
 * that same worker later inserting a real log into that workplace's storage.
 * The remaining Journey-v2 proof already has durable server authority in the
 * Development ledger: Cultivated Ground can only precede a real Farmer crop
 * deposit, Hospitality can only precede three real Courier deliveries, First
 * Watch can only precede four housed settlers, and Arm the Watch can only
 * precede a real Guard weapon delivered by a Warehouse-employed Courier into
 * the Guard's Barracks. Immediately before warning and arrival,
 * {@link #assess} therefore revalidates both ledgers plus every live building,
 * worker and the Guard's physical weapon.
 * Client packets cannot set any field in this class.
 *
 * <p>Missing or malformed current authority is quarantined. Successful
 * founding and explicit pre-feature migration receive only an empty
 * {@link #fresh()} ledger: legacy state never invents either proof, and its
 * separate Journey migration decides whether honest requalification is
 * possible. Directly constructed fixtures remain quarantined by default.
 */
public final class FirstRaidReadiness {
    public static final int DATA_VERSION = 1;
    public static final int MAX_REVISION = 1_000_000;
    private static final int MAX_STORED_LOG_COUNT = 64;

    public enum Stage {
        PENDING(0, "pending"),
        EMBLEM_HIRED(1, "emblem_hired"),
        PRODUCTION_STORED(2, "production_stored"),
        QUARANTINED(-1, "quarantined");

        private final int wireId;
        private final String id;

        Stage(int wireId, String id) {
            this.wireId = wireId;
            this.id = id;
        }

        int wireId() {
            return wireId;
        }

        String id() {
            return id;
        }

        @Nullable
        static Stage fromWireId(int wireId) {
            return switch (wireId) {
                case 0 -> PENDING;
                case 1 -> EMBLEM_HIRED;
                case 2 -> PRODUCTION_STORED;
                case -1 -> QUARANTINED;
                default -> null;
            };
        }
    }

    /** Stable diagnostic reasons used by focused GameTests and server logs. */
    public enum Assessment {
        READY,
        EVIDENCE_MISSING,
        AUTHORITY_INVALID,
        JOURNEY_INCOMPLETE,
        HEARTH_INVALID,
        KNOWLEDGE_MISSING,
        ROSTER_CORRUPT,
        HOUSE_MISSING,
        HOUSING_INSUFFICIENT,
        LUMBER_CAMP_INVALID,
        MAYOR_INVALID,
        WORKER_INVALID,
        FARMHOUSE_INVALID,
        FARMER_INVALID,
        WAREHOUSE_INVALID,
        COURIER_INVALID,
        TAVERN_INVALID,
        BARRACKS_INVALID,
        GUARD_INVALID,
        GUARD_UNARMED,
        WATCHTOWER_INVALID,
        ARCHER_INVALID,
        ARCHER_UNARMED,
        ARCHER_NO_ARROWS,
        DEFENDER_ORDER_INVALID,
        LIVE_READINESS_INCOMPLETE
    }

    private Stage stage;
    private int revision;
    @Nullable
    private UUID workerId;
    @Nullable
    private UUID lumberCampId;
    private int storedLogCount;
    private long storedAtGameTime;

    /** Fixture/migration-safe default. Real founding replaces it with fresh. */
    public FirstRaidReadiness() {
        this(Stage.QUARANTINED, 0, null, null, 0, -1L);
    }

    private FirstRaidReadiness(Stage stage, int revision,
                               @Nullable UUID workerId,
                               @Nullable UUID lumberCampId,
                               int storedLogCount, long storedAtGameTime) {
        this.stage = stage;
        this.revision = revision;
        this.workerId = workerId;
        this.lumberCampId = lumberCampId;
        this.storedLogCount = storedLogCount;
        this.storedAtGameTime = storedAtGameTime;
    }

    /** Empty evidence owned by a newly and successfully founded settlement. */
    public static FirstRaidReadiness fresh() {
        return new FirstRaidReadiness(Stage.PENDING, 0,
            null, null, 0, -1L);
    }

    /** Sticky fail-closed authority for legacy, missing or malformed data. */
    public static FirstRaidReadiness quarantined() {
        return new FirstRaidReadiness();
    }

    public synchronized Stage stage() {
        return stage;
    }

    public synchronized int revision() {
        return revision;
    }

    @Nullable
    public synchronized UUID workerId() {
        return workerId;
    }

    @Nullable
    public synchronized UUID lumberCampId() {
        return lumberCampId;
    }

    public synchronized int storedLogCount() {
        return storedLogCount;
    }

    public synchronized long storedAtGameTime() {
        return storedAtGameTime;
    }

    /**
     * Records only the charged ordinary-player hire path. The caller invokes
     * this after the exact held emblem has successfully shrunk by one; the raw
     * permission-level-2/GameTest hire seam never calls it.
     */
    public synchronized boolean noteConsumedLumbererEmblemHire(
            ServerLevel level, Settlement settlement, Building building,
            SettlerEntity settler) {
        if (stage == Stage.PRODUCTION_STORED || stage == Stage.QUARANTINED
            || revision >= MAX_REVISION
            || !liveLumbererAtCamp(level, settlement, building, settler)
            ) {
            return false;
        }
        // A Lumber Camp has two slots. A later legitimate hire must not
        // silently replace the first worker's outstanding production proof:
        // whichever worker stores a log first could otherwise complete the
        // Journey while the ledger still names the other one, permanently
        // locking the first raid. Replacement is allowed only after the old
        // recorded employment is no longer a live valid fact (dismissal,
        // reassignment, death, or building loss).
        if (stage == Stage.EMBLEM_HIRED && recordedHireStillLive(level, settlement)) {
            return false;
        }
        stage = Stage.EMBLEM_HIRED;
        workerId = settler.getUUID();
        lumberCampId = building.id;
        storedLogCount = 0;
        storedAtGameTime = -1L;
        revision++;
        return true;
    }


    /**
     * Seals production proof immediately before the one-way Journey
     * transition commits. The log may later be collected by a courier;
     * readiness remembers the validated insertion event instead of demanding
     * that the item remain in one chest.
     */
    public synchronized boolean noteStoredProduction(
            ServerLevel level, Settlement settlement, Building building,
            SettlerEntity settler, ItemStack offeredStack, int insertedCount) {
        if (stage != Stage.EMBLEM_HIRED || revision >= MAX_REVISION
            || workerId == null || lumberCampId == null
            || !workerId.equals(settler == null ? null : settler.getUUID())
            || !lumberCampId.equals(building == null ? null : building.id)
            || offeredStack == null || offeredStack.isEmpty()
            || !offeredStack.is(ItemTags.LOGS)
            || insertedCount <= 0 || insertedCount > offeredStack.getCount()
            || insertedCount > MAX_STORED_LOG_COUNT
            || settlement == null
            || !liveLumbererAtCamp(level, settlement, building, settler)) {
            return false;
        }
        stage = Stage.PRODUCTION_STORED;
        storedLogCount = insertedCount;
        storedAtGameTime = Math.max(0L, level.getGameTime());
        revision++;
        return true;
    }

    /**
     * Revalidates every mutable prerequisite at the two raid authority gates.
     * This scans only this settlement's persisted lists and exact known block
     * positions; it performs no area/entity/world scan.
     */
    public synchronized Assessment assess(ServerLevel level,
                                          Settlement settlement) {
        if (stage != Stage.PRODUCTION_STORED || workerId == null
            || lumberCampId == null || storedLogCount <= 0
            || storedLogCount > MAX_STORED_LOG_COUNT
            || storedAtGameTime < 0L
            || (level != null && storedAtGameTime > level.getGameTime())) {
            return Assessment.EVIDENCE_MISSING;
        }
        if (level == null || settlement == null || settlement.id == null
            || settlement.center == null
            || SettlementManager.byId(level, settlement.id) != settlement) {
            return Assessment.AUTHORITY_INVALID;
        }
        if (!JourneyReadinessGate.prerequisitesThroughFirstWatch(
                settlement.journeyState)) {
            // Readiness closes only through the pre-raid FJ-559B chain.
            // Overall COMPLETE is
            // impossible until the raid and its aftermath have happened.
            return Assessment.JOURNEY_INCOMPLETE;
        }
        if (!(level.getBlockEntity(settlement.center) instanceof HearthBlockEntity hearth)
            || hearth.isRemoved() || hearth.getLevel() != level
            || !Objects.equals(hearth.getSettlementId(), settlement.id)) {
            return Assessment.HEARTH_INVALID;
        }

        DevelopmentState development = Development.existing(level, settlement.id);
        if (development == null || !development.initialized()
            || development.quarantined()
            || !development.unlocked(DevelopmentNode.SETTLEMENT_CHARTER)
            || !development.unlocked(DevelopmentNode.SHELTER)
            || !development.unlocked(DevelopmentNode.TIMBER_RIGHTS)
            || !development.unlocked(DevelopmentNode.CULTIVATED_GROUND)
            || !development.unlocked(DevelopmentNode.STORES_AND_ROADS)
            || !development.unlocked(DevelopmentNode.HOSPITALITY)
            || !development.unlocked(DevelopmentNode.FIRST_WATCH)
            || !development.unlocked(DevelopmentNode.ARM_THE_WATCH)) {
            return Assessment.KNOWLEDGE_MISSING;
        }

        Map<UUID, Settlement.SettlerRecord> records = new HashMap<>();
        for (Settlement.SettlerRecord record : settlement.settlers) {
            if (record == null || record.entityId == null || record.profession == null
                || records.putIfAbsent(record.entityId, record) != null) {
                return Assessment.ROSTER_CORRUPT;
            }
        }

        Set<UUID> buildingIds = new HashSet<>();
        Set<UUID> employed = new HashSet<>();
        Building evidenceCamp = null;
        boolean validHouse = false;
        int physicalHousingBeds = 0;
        Building farmhouse = null;
        Building warehouse = null;
        Building tavern = null;
        Building barracks = null;
        for (Building building : settlement.buildings) {
            if (building == null || building.id == null || building.type == null
                || building.plaquePos == null || !buildingIds.add(building.id)
                || building.workers.size() > building.type.workerCapacity()) {
                return Assessment.ROSTER_CORRUPT;
            }
            Set<UUID> localWorkers = new HashSet<>();
            Profession trade = Employment.tradeOf(building.type);
            for (UUID assigned : building.workers) {
                Settlement.SettlerRecord record = records.get(assigned);
                if (assigned == null || !localWorkers.add(assigned)
                    || !employed.add(assigned) || record == null
                    || trade == Profession.NONE || record.profession != trade) {
                    return Assessment.ROSTER_CORRUPT;
                }
            }
            if (building.id.equals(lumberCampId)) {
                evidenceCamp = building;
            }
            if (building.type == BuildingType.HOUSE && building.valid
                && exactPhysicalPlaque(level, settlement, building)) {
                validHouse = true;
            }
            if (building.valid && building.type.housesResidents()
                && exactPhysicalPlaque(level, settlement, building)) {
                physicalHousingBeds += Math.min(building.type.residentCapacity(),
                    building.beds.size());
            }
            if (building.valid && exactPhysicalPlaque(level, settlement, building)) {
                if (building.type == BuildingType.FARMHOUSE
                    && storageHealthy(level, building)) {
                    farmhouse = building;
                } else if (building.type == BuildingType.WAREHOUSE
                    && storageHealthy(level, building)) {
                    warehouse = building;
                } else if (building.type == BuildingType.TAVERN
                    && storageHealthy(level, building)) {
                    tavern = building;
                } else if (building.type == BuildingType.BARRACKS
                    && storageHealthy(level, building)) {
                    barracks = building;
                }
            }
        }
        for (Map.Entry<UUID, Settlement.SettlerRecord> entry : records.entrySet()) {
            if (!employed.contains(entry.getKey())
                && entry.getValue().profession != Profession.NONE) {
                return Assessment.ROSTER_CORRUPT;
            }
        }
        if (!validHouse) {
            return Assessment.HOUSE_MISSING;
        }
        if (physicalHousingBeds < settlement.population()) {
            return Assessment.HOUSING_INSUFFICIENT;
        }
        if (evidenceCamp == null || !evidenceCamp.valid
            || evidenceCamp.type != BuildingType.LUMBER_CAMP
            || !exactPhysicalPlaque(level, settlement, evidenceCamp)) {
            return Assessment.LUMBER_CAMP_INVALID;
        }

        SettlerEntity mayor = liveMember(level, settlement, settlement.mayorId);
        if (mayor == null || records.get(mayor.getUUID()) == null) {
            return Assessment.MAYOR_INVALID;
        }

        Settlement.SettlerRecord workerRecord = records.get(workerId);
        SettlerEntity worker = liveMember(level, settlement, workerId);
        if (workerRecord == null || workerRecord.profession != Profession.LUMBERER
            || worker == null || worker.getProfession() != Profession.LUMBERER
            || !evidenceCamp.workers.contains(workerId)
            || !exactEmployerIs(settlement, workerId, evidenceCamp)) {
            return Assessment.WORKER_INVALID;
        }
        if (farmhouse == null) {
            return Assessment.FARMHOUSE_INVALID;
        }
        if (!hasLiveWorker(level, settlement, records, farmhouse,
                Profession.FARMER)) {
            return Assessment.FARMER_INVALID;
        }
        if (warehouse == null) {
            return Assessment.WAREHOUSE_INVALID;
        }
        if (!hasLiveWorker(level, settlement, records, warehouse,
                Profession.COURIER)) {
            return Assessment.COURIER_INVALID;
        }
        if (tavern == null) {
            return Assessment.TAVERN_INVALID;
        }
        if (barracks == null) {
            return Assessment.BARRACKS_INVALID;
        }
        SettlerEntity armedGuard = null;
        boolean hasGuard = false;
        EquipmentRequirement guardWeapon = EquipmentRequests.requirementFor(
            Profession.GUARD);
        for (UUID assigned : barracks.workers) {
            Settlement.SettlerRecord record = records.get(assigned);
            SettlerEntity guard = liveMember(level, settlement, assigned);
            if (record == null || record.profession != Profession.GUARD
                || guard == null || guard.getProfession() != Profession.GUARD
                || !exactEmployerIs(settlement, assigned, barracks)) {
                continue;
            }
            hasGuard = true;
            if (guardWeapon != null && guardWeapon.serviceable(
                    guard.getMainHandItem())) {
                armedGuard = guard;
                break;
            }
        }
        if (!hasGuard) {
            return Assessment.GUARD_INVALID;
        }
        if (armedGuard == null) {
            return Assessment.GUARD_UNARMED;
        }
        // Compatibility callers must never report a Guard-only READY after
        // the canonical live gate grew to Guard + Archer. The service reads
        // this ledger's persisted fields but never calls assess(), so this
        // final projection is recursion-free and shares declaration truth.
        FirstRaidReadinessService.Report live =
            FirstRaidReadinessService.assessDomain(level, settlement);
        if (live.ready()) return Assessment.READY;
        if (live.blockedBy(FirstRaidReadinessService.Blocker.WATCHTOWER_INVALID)) {
            return Assessment.WATCHTOWER_INVALID;
        }
        if (live.blockedBy(FirstRaidReadinessService.Blocker.ARCHER_INVALID)
            || live.blockedBy(FirstRaidReadinessService.Blocker
                .DEFENDERS_NOT_DISTINCT)) {
            return Assessment.ARCHER_INVALID;
        }
        if (live.blockedBy(FirstRaidReadinessService.Blocker.ARCHER_NO_ARROWS)) {
            return Assessment.ARCHER_NO_ARROWS;
        }
        if (live.blockedBy(FirstRaidReadinessService.Blocker.ARCHER_UNARMED)) {
            return Assessment.ARCHER_UNARMED;
        }
        if (live.blockedBy(FirstRaidReadinessService.Blocker.GUARD_ORDER_INVALID)
            || live.blockedBy(FirstRaidReadinessService.Blocker
                .ARCHER_ORDER_INVALID)) {
            return Assessment.DEFENDER_ORDER_INVALID;
        }
        return Assessment.LIVE_READINESS_INCOMPLETE;
    }

    public synchronized boolean ready(ServerLevel level, Settlement settlement) {
        return assess(level, settlement) == Assessment.READY;
    }

    private static boolean liveLumbererAtCamp(ServerLevel level,
                                               Settlement settlement,
                                               Building building,
                                               SettlerEntity settler) {
        return level != null && settlement != null && building != null
            && settler != null
            && SettlementManager.byId(level, settlement.id) == settlement
            && exactRegisteredBuilding(settlement, building)
            && building.valid && building.type == BuildingType.LUMBER_CAMP
            && building.workers.contains(settler.getUUID())
            && exactEmployerIs(settlement, settler.getUUID(), building)
            && liveMember(level, settlement, settler.getUUID()) == settler
            && settler.getProfession() == Profession.LUMBERER;
    }

    private boolean recordedHireStillLive(ServerLevel level,
                                           Settlement settlement) {
        if (workerId == null || lumberCampId == null) {
            return false;
        }
        Building recordedCamp = null;
        for (Building candidate : settlement.buildings) {
            if (candidate != null && Objects.equals(candidate.id, lumberCampId)) {
                if (recordedCamp != null) {
                    return false;
                }
                recordedCamp = candidate;
            }
        }
        SettlerEntity recordedWorker = liveMember(level, settlement, workerId);
        return recordedCamp != null && recordedWorker != null
            && liveLumbererAtCamp(level, settlement, recordedCamp, recordedWorker);
    }

    /** Exactly one persisted employment row, at exactly the expected post. */
    private static boolean exactEmployerIs(Settlement settlement, UUID workerId,
                                           Building expected) {
        int matches = 0;
        for (Building candidate : settlement.buildings) {
            if (candidate == null) {
                return false;
            }
            for (UUID assigned : candidate.workers) {
                if (assigned == null) {
                    return false;
                }
                if (assigned.equals(workerId)) {
                    if (candidate != expected || ++matches > 1) {
                        return false;
                    }
                }
            }
        }
        return matches == 1;
    }

    @Nullable
    private static SettlerEntity liveMember(ServerLevel level,
                                             Settlement settlement,
                                             @Nullable UUID entityId) {
        if (level == null || settlement == null || entityId == null
            || !hasOneSafeMemberRecord(settlement, entityId)
            || !(level.getEntity(entityId) instanceof SettlerEntity settler)
            || !settler.isAlive() || settler.isRemoved()
            || settler.level() != level || settler.isTraveler()
            || !Objects.equals(settler.getSettlementId(), settlement.id)
            || !Objects.equals(settler.getHearthPos(), settlement.center)) {
            return null;
        }
        return settler;
    }

    /** Corrupt/null/duplicate member rows are refusals, never exceptions. */
    private static boolean hasOneSafeMemberRecord(Settlement settlement,
                                                  UUID entityId) {
        boolean found = false;
        for (Settlement.SettlerRecord record : settlement.settlers) {
            if (record == null || record.entityId == null) {
                return false;
            }
            if (record.entityId.equals(entityId)) {
                if (found) {
                    return false;
                }
                found = true;
            }
        }
        return found;
    }

    private static boolean exactRegisteredBuilding(Settlement settlement,
                                                    Building building) {
        if (settlement == null || building == null || building.id == null) {
            return false;
        }
        for (Building registered : settlement.buildings) {
            if (registered == building
                && Objects.equals(registered.id, building.id)) {
                return true;
            }
        }
        return false;
    }

    private static boolean exactPhysicalPlaque(ServerLevel level,
                                               Settlement settlement,
                                               Building building) {
        if (!settlement.inside(building.plaquePos)
            || !(level.getBlockEntity(building.plaquePos)
                instanceof PlaqueBlockEntity plaque)
            || plaque.isRemoved() || plaque.getLevel() != level
            || plaque.state() != PlaqueState.LINKED_VALID
            || plaque.type() != building.type
            || !Objects.equals(plaque.buildingId(), building.id)) {
            return false;
        }
        return plaque.building(level) == building;
    }

    /** The live container count must still satisfy this room's own plan. */
    private static boolean storageHealthy(ServerLevel level, Building building) {
        var storage = building.type.requirementById("storage");
        return storage == null
            || WarehouseIndex.containers(level, building).size() >= storage.needed();
    }

    private static boolean hasLiveWorker(ServerLevel level,
                                         Settlement settlement,
                                         Map<UUID, Settlement.SettlerRecord> records,
                                         Building workplace,
                                         Profession profession) {
        for (UUID assigned : workplace.workers) {
            Settlement.SettlerRecord record = records.get(assigned);
            SettlerEntity worker = liveMember(level, settlement, assigned);
            if (record != null && record.profession == profession
                && worker != null && worker.getProfession() == profession
                && exactEmployerIs(settlement, assigned, workplace)) {
                return true;
            }
        }
        return false;
    }

    public synchronized CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", DATA_VERSION);
        tag.putInt("StageWireId", stage.wireId());
        tag.putString("Stage", stage.id());
        tag.putInt("Revision", revision);
        if (workerId != null) {
            tag.putUUID("WorkerId", workerId);
        }
        if (lumberCampId != null) {
            tag.putUUID("LumberCampId", lumberCampId);
        }
        tag.putInt("StoredLogCount", storedLogCount);
        tag.putLong("StoredAtGameTime", storedAtGameTime);
        return tag;
    }

    /** Strict decoder; missing, wrong-typed, impossible or future data blocks. */
    public static FirstRaidReadiness readNbt(@Nullable CompoundTag tag) {
        if (tag == null
            || !tag.contains("DataVersion", Tag.TAG_INT)
            || tag.getInt("DataVersion") != DATA_VERSION
            || !tag.contains("StageWireId", Tag.TAG_INT)
            || !tag.contains("Stage", Tag.TAG_STRING)
            || !tag.contains("Revision", Tag.TAG_INT)
            || !tag.contains("StoredLogCount", Tag.TAG_INT)
            || !tag.contains("StoredAtGameTime", Tag.TAG_LONG)) {
            return quarantined();
        }
        Stage decoded = Stage.fromWireId(tag.getInt("StageWireId"));
        int decodedRevision = tag.getInt("Revision");
        int decodedCount = tag.getInt("StoredLogCount");
        long decodedTime = tag.getLong("StoredAtGameTime");
        if (decoded == null || !decoded.id().equals(tag.getString("Stage"))
            || decodedRevision < 0 || decodedRevision > MAX_REVISION) {
            return quarantined();
        }

        boolean hasWorker = tag.hasUUID("WorkerId");
        boolean hasCamp = tag.hasUUID("LumberCampId");
        if (decoded == Stage.QUARANTINED) {
            return quarantined();
        }
        if (decoded == Stage.PENDING) {
            return decodedRevision == 0 && !hasWorker && !hasCamp
                && decodedCount == 0 && decodedTime == -1L
                ? fresh() : quarantined();
        }
        if (!hasWorker || !hasCamp) {
            return quarantined();
        }
        UUID decodedWorker = tag.getUUID("WorkerId");
        UUID decodedCamp = tag.getUUID("LumberCampId");
        if (decoded == Stage.EMBLEM_HIRED) {
            return decodedRevision >= 1 && decodedCount == 0 && decodedTime == -1L
                ? new FirstRaidReadiness(decoded, decodedRevision,
                    decodedWorker, decodedCamp, 0, -1L)
                : quarantined();
        }
        return decodedRevision >= 2
            && decodedCount > 0 && decodedCount <= MAX_STORED_LOG_COUNT
            && decodedTime >= 0L
            ? new FirstRaidReadiness(decoded, decodedRevision,
                decodedWorker, decodedCamp, decodedCount, decodedTime)
            : quarantined();
    }
}
