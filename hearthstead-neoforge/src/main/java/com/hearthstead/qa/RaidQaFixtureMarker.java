package com.hearthstead.qa;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Minimal persisted identity for restart-safe permission-two raid fixtures. */
final class RaidQaFixtureMarker extends SavedData {
    static final int DATA_VERSION = 2;
    private static final int MAX_ENTRIES = 32;
    private static final String DATA_NAME = "hearthstead_raidqa_fixture";

    enum Mode {
        ISOLATED,
        GROUNDED_ASSISTED
    }

    record Entry(UUID actorId, UUID settlementId, BlockPos origin, Mode mode) {
        Entry {
            if (!valid(actorId) || !valid(settlementId) || origin == null) {
                throw new IllegalArgumentException("invalid raidqa fixture marker");
            }
            origin = origin.immutable();
            if (mode == null) {
                throw new IllegalArgumentException("missing raidqa fixture mode");
            }
        }
    }

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private boolean quarantined;

    private static final Factory<RaidQaFixtureMarker> FACTORY = new Factory<>(
        RaidQaFixtureMarker::new, RaidQaFixtureMarker::load, null);

    static RaidQaFixtureMarker get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    @Nullable
    Entry entry(UUID actorId) {
        return entries.get(actorId);
    }

    boolean quarantined() {
        return quarantined;
    }

    /** First binding wins. A changed origin/settlement is never rewritten. */
    boolean bind(UUID actorId, UUID settlementId, BlockPos origin) {
        return bind(actorId, settlementId, origin, Mode.ISOLATED);
    }

    boolean bind(UUID actorId, UUID settlementId, BlockPos origin, Mode mode) {
        if (quarantined || !valid(actorId) || !valid(settlementId)
            || origin == null || mode == null) {
            return false;
        }
        Entry candidate;
        try {
            candidate = new Entry(actorId, settlementId, origin, mode);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        Entry existing = entries.get(actorId);
        if (existing != null) {
            return existing.equals(candidate);
        }
        if (entries.size() >= MAX_ENTRIES) {
            quarantined = true;
            setDirty();
            return false;
        }
        entries.put(actorId, candidate);
        setDirty();
        return true;
    }

    static RaidQaFixtureMarker load(CompoundTag tag,
                                     HolderLookup.Provider registries) {
        RaidQaFixtureMarker marker = new RaidQaFixtureMarker();
        if (tag == null || !tag.contains("DataVersion", Tag.TAG_INT)
            || (tag.getInt("DataVersion") != 1 && tag.getInt("DataVersion") != DATA_VERSION)
            || !tag.contains("Quarantined", Tag.TAG_BYTE)
            || !tag.contains("Entries", Tag.TAG_LIST)) {
            marker.quarantined = true;
            return marker;
        }
        int version = tag.getInt("DataVersion");
        ListTag rows = tag.getList("Entries", Tag.TAG_COMPOUND);
        if (tag.getBoolean("Quarantined") || rows.size() > MAX_ENTRIES) {
            marker.quarantined = true;
            return marker;
        }
        for (int index = 0; index < rows.size(); index++) {
            CompoundTag row = rows.getCompound(index);
            BlockPos origin = NbtUtils.readBlockPos(row, "Origin").orElse(null);
            Mode mode = version == 1 ? Mode.ISOLATED : parseMode(row);
            if (!row.hasUUID("Actor") || !row.hasUUID("Settlement")
                || origin == null || mode == null) {
                marker.entries.clear();
                marker.quarantined = true;
                return marker;
            }
            try {
                Entry entry = new Entry(row.getUUID("Actor"),
                    row.getUUID("Settlement"), origin, mode);
                if (marker.entries.putIfAbsent(entry.actorId(), entry) != null) {
                    marker.entries.clear();
                    marker.quarantined = true;
                    return marker;
                }
            } catch (IllegalArgumentException malformed) {
                marker.entries.clear();
                marker.quarantined = true;
                return marker;
            }
        }
        return marker;
    }

    @Override
    public CompoundTag save(CompoundTag tag,
                            HolderLookup.Provider registries) {
        tag.putInt("DataVersion", DATA_VERSION);
        tag.putBoolean("Quarantined", quarantined);
        ListTag rows = new ListTag();
        entries.values().stream()
            .sorted(Comparator.comparing(entry -> entry.actorId().toString()))
            .forEach(entry -> {
                CompoundTag row = new CompoundTag();
                row.putUUID("Actor", entry.actorId());
                row.putUUID("Settlement", entry.settlementId());
                row.put("Origin", NbtUtils.writeBlockPos(entry.origin()));
                row.putString("Mode", entry.mode().name());
                rows.add(row);
            });
        tag.put("Entries", rows);
        return tag;
    }

    private static boolean valid(UUID id) {
        return id != null && (id.getMostSignificantBits() != 0L
            || id.getLeastSignificantBits() != 0L);
    }

    @Nullable
    private static Mode parseMode(CompoundTag row) {
        if (!row.contains("Mode", Tag.TAG_STRING)) {
            return null;
        }
        try {
            return Mode.valueOf(row.getString("Mode"));
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}
