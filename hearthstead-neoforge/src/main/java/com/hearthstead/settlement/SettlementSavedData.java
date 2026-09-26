package com.hearthstead.settlement;

import com.hearthstead.util.AuthorityTelemetry;
import com.hearthstead.settlement.guard.GuardAssignmentService;
import com.hearthstead.settlement.state.GuardOrderBook;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;

/** Per-dimension registry of settlements, persisted with the world save. */
public class SettlementSavedData extends SavedData {
    /** Root save schema. Missing means the pre-M1 v0 format. */
    public static final int CURRENT_DATA_VERSION = 8;
    private static final String DATA_NAME = "hearthstead_settlements";

    public final Map<UUID, Settlement> settlements = new HashMap<>();
    /** Transient scan/revalidation driver; state rebuilds from events. */
    public final BuildingManager buildingManager = new BuildingManager();

    private static final Factory<SettlementSavedData> FACTORY =
        new Factory<>(SettlementSavedData::new, SettlementSavedData::load, null);
    /** One transient observation per actual level/data identity after load. */
    private static final Map<ServerLevel, SettlementSavedData> OBSERVED_LOADS =
        new WeakHashMap<>();

    public static SettlementSavedData get(ServerLevel level) {
        SettlementSavedData data = level.getDataStorage()
            .computeIfAbsent(FACTORY, DATA_NAME);
        boolean changed = false;
        for (Settlement settlement : data.settlements.values()) {
            changed |= settlement.quarantineWorkZonesForDimension(
                level.dimension().location());
            GuardOrderBook.ReconcileResult guardMigration =
                GuardAssignmentService.reconcileLegacy(level, settlement);
            changed |= guardMigration == GuardOrderBook.ReconcileResult.MIGRATED
                || guardMigration
                    == GuardOrderBook.ReconcileResult.QUARANTINED_AMBIGUOUS
                || guardMigration
                    == GuardOrderBook.ReconcileResult.QUARANTINED_INVALID;
        }
        if (changed) {
            data.setDirty();
        }
        observeLoadOnce(level, data);
        return data;
    }

    public SettlementSavedData() {
    }

    /** Controlled fail-closed signal for malformed or unsupported saves. */
    public static final class DataVersionException extends IllegalArgumentException {
        public DataVersionException(String message) {
            super(message);
        }
    }

    public static SettlementSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        SettlementSavedData data = new SettlementSavedData();
        int sourceVersion = readSourceVersion(tag);
        Tag rawSettlements = tag.get("Settlements");
        if (rawSettlements == null) {
            throw new DataVersionException(
                "Hearthstead settlement save is missing Settlements");
        }
        if (!(rawSettlements instanceof ListTag list)) {
            throw new DataVersionException(
                "Hearthstead Settlements must be a list of compounds");
        }
        if (sourceVersion == 0 && containsVersionedSettlementState(list)) {
            throw new DataVersionException(
                "Hearthstead versioned settlement state has no valid DataVersion");
        }
        for (int i = 0; i < list.size(); i++) {
            Tag rawSettlement = list.get(i);
            if (!(rawSettlement instanceof CompoundTag settlementTag)) {
                throw new DataVersionException(
                    "Hearthstead Settlements entry " + i + " is not a compound");
            }
            if (!settlementTag.hasUUID("Id")) {
                throw new DataVersionException(
                    "Hearthstead Settlements entry " + i + " has no valid id");
            }
            Settlement s = Settlement.readNbt(settlementTag, sourceVersion);
            if (data.settlements.putIfAbsent(s.id, s) != null) {
                throw new DataVersionException(
                    "Hearthstead Settlements contains duplicate id " + s.id);
            }
        }
        quarantineDuplicateRecruitmentIdentities(data);
        if (sourceVersion < CURRENT_DATA_VERSION) {
            // Ensure an otherwise-idle upgraded world eventually writes the
            // explicit current schema instead of depending on unrelated gameplay
            // to dirty the SavedData after migration.
            data.setDirty();
        }
        return data;
    }

    /** Read-only registry lookup; never creates, reconciles or dirties data. */
    @Nullable
    public static SettlementSavedData existing(ServerLevel level) {
        return level == null ? null
            : level.getDataStorage().get(FACTORY, DATA_NAME);
    }

    /**
     * Immutable, fail-closed lookup used by Journey/telemetry commit seams.
     * A duplicated id returns empty even before the load quarantine has been
     * flushed, so no observer can turn ambiguous evidence into progression.
     */
    public Optional<RecruitmentTransaction> transaction(UUID transactionId) {
        if (transactionId == null) {
            return Optional.empty();
        }
        RecruitmentTransaction found = null;
        for (Settlement settlement : settlements.values()) {
            RecruitmentTransaction candidate = settlement.recruitment;
            if (candidate != null && transactionId.equals(candidate.transactionId())) {
                if (found != null) {
                    return Optional.empty();
                }
                found = candidate;
            }
        }
        return Optional.ofNullable(found);
    }

    private static void quarantineDuplicateRecruitmentIdentities(
            SettlementSavedData data) {
        Map<UUID, Settlement> transactionOwners = new HashMap<>();
        Map<UUID, Settlement> travelerOwners = new HashMap<>();
        java.util.Set<Settlement> duplicateOwners = new java.util.HashSet<>();
        for (Settlement settlement : data.settlements.values()) {
            RecruitmentTransaction recruitment = settlement.recruitment;
            if (recruitment == null) {
                continue;
            }
            UUID transactionId = recruitment.transactionId();
            if (transactionId != null) {
                Settlement previous = transactionOwners.putIfAbsent(
                    transactionId, settlement);
                if (previous != null && previous != settlement) {
                    duplicateOwners.add(previous);
                    duplicateOwners.add(settlement);
                }
            }
            UUID travelerId = recruitment.travelerId();
            if (travelerId != null) {
                Settlement previous = travelerOwners.putIfAbsent(travelerId,
                    settlement);
                if (previous != null && previous != settlement) {
                    duplicateOwners.add(previous);
                    duplicateOwners.add(settlement);
                }
            }
        }
        for (Settlement owner : duplicateOwners) {
            owner.applyRecruitment(owner.recruitment.quarantine(
                RecruitmentTransaction.TerminalReason.DUPLICATE_TRAVELER));
        }
        if (!duplicateOwners.isEmpty()) {
            data.setDirty();
        }
    }

    private static boolean containsVersionedSettlementState(ListTag settlements) {
        for (int i = 0; i < settlements.size(); i++) {
            Tag raw = settlements.get(i);
            if (raw instanceof CompoundTag settlement
                && (settlement.contains("RaidProfileWireId")
                    || settlement.contains("RaidProfile")
                    || settlement.contains("RaidLifecycle")
                    || settlement.contains("BlessingState")
                    || settlement.contains("GuardOrder")
                    || settlement.contains("GuardOrders")
                    || settlement.contains("FoundingJourney")
                    || settlement.contains("JourneyV3")
                    || settlement.contains("FirstRaidReadiness")
                    || settlement.contains("RecruitmentTransaction")
                    || containsSubstantiveRecurringState(settlement))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Old migration GameTests construct v0 by stripping every then-versioned
     * key from a freshly written settlement tag. A pristine v3 recurring
     * record carries no progression and is therefore safe to ignore there;
     * any non-empty, malformed, active or serial-bearing record is substantive
     * versioned state and prevents a missing DataVersion from resetting it.
     */
    private static boolean containsSubstantiveRecurringState(CompoundTag settlement) {
        Tag raw = settlement.get("RecurringRaidRun");
        if (raw == null) {
            return false;
        }
        if (!(raw instanceof CompoundTag recurring)) {
            return true;
        }
        // Ignore only the exact freshly-written NONE shape. A partial record
        // that happens to carry zero counters is still versioned/malformed
        // evidence and must make a missing root DataVersion fail closed.
        return recurring.getAllKeys().size() != 10
            || !recurring.contains("StageWireId", Tag.TAG_INT)
            || recurring.getInt("StageWireId") != 0
            || !recurring.contains("Stage", Tag.TAG_STRING)
            || !"none".equals(recurring.getString("Stage"))
            || !recurring.contains("LastIssuedSerial", Tag.TAG_LONG)
            || recurring.getLong("LastIssuedSerial") != 0L
            || !recurring.contains("LastResolvedSerial", Tag.TAG_LONG)
            || recurring.getLong("LastResolvedSerial") != 0L
            || !recurring.contains("LastRewardProcessedSerial", Tag.TAG_LONG)
            || recurring.getLong("LastRewardProcessedSerial") != 0L
            || !recurring.contains("ActiveSerial", Tag.TAG_LONG)
            || recurring.getLong("ActiveSerial") != 0L
            || !recurring.contains("ParticipantsSealed", Tag.TAG_BYTE)
            || recurring.getBoolean("ParticipantsSealed")
            || !recurring.contains("IntegrityLost", Tag.TAG_BYTE)
            || recurring.getBoolean("IntegrityLost")
            || recurring.contains("Plan")
            || !(recurring.get("Participants") instanceof ListTag participants)
            || !participants.isEmpty()
            || !(recurring.get("TerminalParticipants") instanceof ListTag terminal)
            || !terminal.isEmpty();
    }

    private static int readSourceVersion(CompoundTag tag) {
        if (!tag.contains("DataVersion")) {
            return 0;
        }
        if (!tag.contains("DataVersion", Tag.TAG_INT)) {
            throw new DataVersionException(
                "Hearthstead settlement DataVersion must be an int");
        }
        int sourceVersion = tag.getInt("DataVersion");
        if (sourceVersion < 0) {
            throw new DataVersionException(
                "Hearthstead settlement DataVersion cannot be negative: "
                    + sourceVersion);
        }
        if (sourceVersion > CURRENT_DATA_VERSION) {
            throw new DataVersionException(
                "Hearthstead settlement save is newer than this mod: "
                    + sourceVersion + " > " + CURRENT_DATA_VERSION);
        }
        return sourceVersion;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("DataVersion", CURRENT_DATA_VERSION);
        ListTag list = new ListTag();
        for (Settlement s : settlements.values()) {
            list.add(s.writeNbt());
        }
        tag.put("Settlements", list);
        return tag;
    }

    private static void observeLoadOnce(ServerLevel level,
                                        SettlementSavedData data) {
        if (!level.getServer().isSameThread()
            || OBSERVED_LOADS.get(level) == data) {
            return;
        }
        int buildings = 0;
        int settlers = 0;
        for (Settlement settlement : data.settlements.values()) {
            buildings += settlement.buildings.size();
            settlers += settlement.population();
        }
        if (AuthorityTelemetry.emit(level,
                AuthorityTelemetry.Event.STATE_LOAD_SUMMARY,
                AuthorityTelemetry.Result.OBSERVED,
                AuthorityTelemetry.Fields.state(null,
                    "dimension:" + level.dimension().location(),
                    CURRENT_DATA_VERSION, CURRENT_DATA_VERSION,
                    data.settlements.size(), data.settlements.size(),
                    "buildings_" + buildings + "_settlers_" + settlers))) {
            OBSERVED_LOADS.put(level, data);
        }
    }
}
