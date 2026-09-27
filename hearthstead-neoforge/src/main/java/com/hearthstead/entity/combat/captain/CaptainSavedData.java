package com.hearthstead.entity.combat.captain;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Settlements whose Captain's Commission field promotion has already been
 * spent (once per settlement, ever: later successors earn Sergeant normally).
 */
public final class CaptainSavedData extends SavedData {
    public static final String DATA_NAME = "hearthstead_captain";
    private static final Factory<CaptainSavedData> FACTORY =
        new Factory<>(CaptainSavedData::new, CaptainSavedData::load, null);

    private final Set<UUID> promoted = new HashSet<>();

    public static CaptainSavedData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public boolean promoted(UUID settlement) {
        return promoted.contains(settlement);
    }

    public void markPromoted(UUID settlement) {
        if (promoted.add(settlement)) {
            setDirty();
        }
    }

    public static CaptainSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        CaptainSavedData data = new CaptainSavedData();
        for (Tag t : tag.getList("Promoted", Tag.TAG_INT_ARRAY)) {
            data.promoted.add(NbtUtils.loadUUID(t));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (UUID id : promoted) {
            list.add(NbtUtils.createUUID(id));
        }
        tag.put("Promoted", list);
        return tag;
    }
}
