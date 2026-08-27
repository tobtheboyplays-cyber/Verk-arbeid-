package com.hearthstead.settlement;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Per-dimension registry of settlements, persisted with the world save. */
public class SettlementSavedData extends SavedData {
    /** Root save schema. Missing means the pre-M1 v0 format. */
    public static final int CURRENT_DATA_VERSION = 1;
    private static final String DATA_NAME = "hearthstead_settlements";

    public final Map<UUID, Settlement> settlements = new HashMap<>();
    /** Transient scan/revalidation driver; state rebuilds from events. */
    public final BuildingManager buildingManager = new BuildingManager();

    private static final Factory<SettlementSavedData> FACTORY =
        new Factory<>(SettlementSavedData::new, SettlementSavedData::load, null);

    public static SettlementSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
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
        if (sourceVersion < CURRENT_DATA_VERSION) {
            // Ensure an otherwise-idle upgraded world eventually writes the
            // explicit v1 schema instead of depending on unrelated gameplay
            // to dirty the SavedData after migration.
            data.setDirty();
        }
        return data;
    }

    private static boolean containsVersionedSettlementState(ListTag settlements) {
        for (int i = 0; i < settlements.size(); i++) {
            Tag raw = settlements.get(i);
            if (raw instanceof CompoundTag settlement
                && (settlement.contains("RaidProfileWireId")
                    || settlement.contains("RaidProfile")
                    || settlement.contains("RaidLifecycle")
                    || settlement.contains("BlessingState")
                    || settlement.contains("GuardOrder"))) {
                return true;
            }
        }
        return false;
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
}
