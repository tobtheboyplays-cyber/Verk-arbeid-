package com.hearthstead.event;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Durable cadence only. A village moment owns no goods, Coins, employment or
 * player task. An interrupted in-world scene is intentionally not resumed;
 * the persisted next tick prevents a reload from turning it into a burst.
 */
public final class VillageMomentSavedData extends SavedData {
    public static final long COOLDOWN_TICKS = 12_000L;
    private static final int FORMAT_VERSION = 1;
    private static final int CAP = 256;
    private static final Factory<VillageMomentSavedData> FACTORY = new Factory<>(
        VillageMomentSavedData::new, VillageMomentSavedData::load, null);

    private final Map<UUID, Long> nextBySettlement = new LinkedHashMap<>();
    private boolean quarantined;

    public static VillageMomentSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, "hearthstead_village_moments");
    }

    public static VillageMomentSavedData existing(ServerLevel level) {
        return level.getDataStorage().get(FACTORY, "hearthstead_village_moments");
    }

    /** Claims one sparse scene for this settlement. False means no scene was started. */
    public boolean claim(UUID settlementId, long now) {
        if (quarantined || settlementId == null || now < 0L) {
            return false;
        }
        Long next = nextBySettlement.get(settlementId);
        if (next != null && now < next) {
            return false;
        }
        if (next == null && nextBySettlement.size() >= CAP) {
            return false;
        }
        nextBySettlement.put(settlementId, after(now, COOLDOWN_TICKS));
        setDirty();
        return true;
    }

    public long nextEligible(UUID settlementId) {
        return nextBySettlement.getOrDefault(settlementId, 0L);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Version", FORMAT_VERSION);
        tag.putBoolean("Quarantined", quarantined);
        ListTag rows = new ListTag();
        nextBySettlement.forEach((settlementId, next) -> {
            CompoundTag row = new CompoundTag();
            row.putUUID("Settlement", settlementId);
            row.putLong("Next", next);
            rows.add(row);
        });
        tag.put("Rows", rows);
        return tag;
    }

    public static VillageMomentSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        VillageMomentSavedData data = new VillageMomentSavedData();
        if (tag.getInt("Version") != FORMAT_VERSION
            || !(tag.get("Rows") instanceof ListTag rows)
            || rows.size() > CAP
            || (!rows.isEmpty() && rows.getElementType() != Tag.TAG_COMPOUND)) {
            data.quarantined = true;
            return data;
        }
        data.quarantined = tag.getBoolean("Quarantined");
        for (Tag raw : rows) {
            CompoundTag row = (CompoundTag) raw;
            if (!row.hasUUID("Settlement") || !row.contains("Next", Tag.TAG_LONG)) {
                data.quarantined = true;
                break;
            }
            long next = row.getLong("Next");
            if (next < 0L || data.nextBySettlement.putIfAbsent(row.getUUID("Settlement"), next) != null) {
                data.quarantined = true;
                break;
            }
        }
        return data;
    }

    private static long after(long now, long duration) {
        return now >= Long.MAX_VALUE - duration ? Long.MAX_VALUE : now + duration;
    }
}
