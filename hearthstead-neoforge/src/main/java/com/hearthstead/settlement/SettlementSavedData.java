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

    public static SettlementSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        SettlementSavedData data = new SettlementSavedData();
        ListTag list = tag.getList("Settlements", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            Settlement s = Settlement.readNbt(list.getCompound(i));
            data.settlements.put(s.id, s);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Settlement s : settlements.values()) {
            list.add(s.writeNbt());
        }
        tag.put("Settlements", list);
        return tag;
    }
}
