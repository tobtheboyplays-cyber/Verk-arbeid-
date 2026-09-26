package com.hearthstead.event.worldevent;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Durable state of the small world events, one row per settlement: the
 * day's plan, per-event last days (cooldowns) and the one active event with
 * the entities it spawned. An active event's budget only counts eligible
 * ticks (someone near), so nothing advances while nobody is on.
 *
 * <p>A malformed file quarantines: no new event is planned, and the stored
 * rows are written back unchanged, never deleted.
 */
public final class WorldEventSavedData extends SavedData {
    private static final String NAME = "hearthstead_world_events";
    private static final int FORMAT_VERSION = 1;
    private static final int CAP = 256;
    private static final int MAX_ACTORS = 16;
    private static final Factory<WorldEventSavedData> FACTORY =
        new Factory<>(WorldEventSavedData::new, WorldEventSavedData::load, null);

    private final Map<UUID, Row> rows = new LinkedHashMap<>();
    private boolean quarantined;
    private CompoundTag quarantinedRaw;

    /** The one running event of a settlement. */
    public static final class Active {
        public final WorldEventType type;
        public final UUID id;
        public final long startedGameTime;
        public final long startedDay;
        public int eligibleTicks;
        public boolean forced;
        public final List<UUID> actors = new ArrayList<>();
        public CompoundTag state = new CompoundTag();

        public Active(WorldEventType type, UUID id, long startedGameTime, long startedDay) {
            this.type = type;
            this.id = id;
            this.startedGameTime = startedGameTime;
            this.startedDay = startedDay;
        }

        public boolean addActor(UUID actor) {
            if (actor == null || actors.size() >= MAX_ACTORS || actors.contains(actor)) return false;
            actors.add(actor);
            return true;
        }
    }

    public static final class Row {
        public long plannedDay = WorldEventSchedule.NO_DAY;
        public WorldEventType plannedType;
        public int plannedStart = -1;
        public long lastEventDay = WorldEventSchedule.NO_DAY;
        public final Map<WorldEventType, Long> lastDayByType = new EnumMap<>(WorldEventType.class);
        public Active active;
        public String lastOutcome = "none";
        /** Brute toll paid with food/coins: one fed brute may warn of the next raid. */
        public boolean bruteFavour;
        /** The settlement's one adopted village dog (stray-dog event), or null. */
        public UUID villageDog;

        public WorldEventSchedule.Plan plan() {
            return plannedType == null ? WorldEventSchedule.Plan.quiet(plannedDay)
                : new WorldEventSchedule.Plan(plannedDay, plannedType, plannedStart);
        }
    }

    public static WorldEventSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public static WorldEventSavedData existing(ServerLevel level) {
        return level.getDataStorage().get(FACTORY, NAME);
    }

    public boolean quarantined() {
        return quarantined;
    }

    public Row row(UUID settlementId) {
        return rows.get(settlementId);
    }

    /** Creates the row on first use; null when quarantined or full. */
    public Row rowOrCreate(UUID settlementId) {
        if (quarantined || settlementId == null) return null;
        Row row = rows.get(settlementId);
        if (row == null) {
            if (rows.size() >= CAP) return null;
            row = new Row();
            rows.put(settlementId, row);
            setDirty();
        }
        return row;
    }

    public Iterable<Map.Entry<UUID, Row>> entries() {
        return rows.entrySet();
    }

    /** Finds the active event owning {@code eventId}, or null. */
    public Active activeById(UUID eventId) {
        if (eventId == null) return null;
        for (Row row : rows.values()) {
            if (row.active != null && eventId.equals(row.active.id)) return row.active;
        }
        return null;
    }

    public UUID settlementOfEvent(UUID eventId) {
        if (eventId == null) return null;
        for (Map.Entry<UUID, Row> entry : rows.entrySet()) {
            Active active = entry.getValue().active;
            if (active != null && eventId.equals(active.id)) return entry.getKey();
        }
        return null;
    }

    public void markChanged() {
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (quarantined && quarantinedRaw != null) {
            // Keep the unreadable original intact for a human to inspect.
            tag.merge(quarantinedRaw.copy());
            tag.putBoolean("Quarantined", true);
            return tag;
        }
        tag.putInt("Version", FORMAT_VERSION);
        ListTag list = new ListTag();
        rows.forEach((id, row) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Settlement", id);
            entry.putLong("PlannedDay", row.plannedDay);
            if (row.plannedType != null) entry.putString("PlannedType", row.plannedType.id());
            entry.putInt("PlannedStart", row.plannedStart);
            entry.putLong("LastEventDay", row.lastEventDay);
            entry.putString("LastOutcome", row.lastOutcome == null ? "none" : row.lastOutcome);
            entry.putBoolean("BruteFavour", row.bruteFavour);
            if (row.villageDog != null) entry.putUUID("VillageDog", row.villageDog);
            CompoundTag last = new CompoundTag();
            row.lastDayByType.forEach((type, day) -> last.putLong(type.id(), day));
            entry.put("LastByType", last);
            if (row.active != null) {
                Active a = row.active;
                CompoundTag active = new CompoundTag();
                active.putString("Type", a.type.id());
                active.putUUID("Id", a.id);
                active.putLong("Started", a.startedGameTime);
                active.putLong("StartedDay", a.startedDay);
                active.putInt("Eligible", a.eligibleTicks);
                active.putBoolean("Forced", a.forced);
                ListTag actors = new ListTag();
                for (UUID actor : a.actors) actors.add(NbtUtils.createUUID(actor));
                active.put("Actors", actors);
                active.put("State", a.state.copy());
                entry.put("Active", active);
            }
            list.add(entry);
        });
        tag.put("Rows", list);
        return tag;
    }

    public static WorldEventSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        WorldEventSavedData data = new WorldEventSavedData();
        try {
            if (tag.getBoolean("Quarantined") || tag.getInt("Version") != FORMAT_VERSION
                || !(tag.get("Rows") instanceof ListTag list) || list.size() > CAP
                || (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)) {
                return data.quarantine(tag);
            }
            for (Tag raw : list) {
                CompoundTag entry = (CompoundTag) raw;
                if (!entry.hasUUID("Settlement")) return data.quarantine(tag);
                Row row = new Row();
                row.plannedDay = entry.getLong("PlannedDay");
                row.plannedType = WorldEventType.byId(entry.getString("PlannedType"));
                row.plannedStart = entry.getInt("PlannedStart");
                row.lastEventDay = entry.getLong("LastEventDay");
                row.lastOutcome = entry.getString("LastOutcome");
                row.bruteFavour = entry.getBoolean("BruteFavour");
                if (entry.hasUUID("VillageDog")) row.villageDog = entry.getUUID("VillageDog");
                CompoundTag last = entry.getCompound("LastByType");
                for (String key : last.getAllKeys()) {
                    WorldEventType type = WorldEventType.byId(key);
                    if (type != null) row.lastDayByType.put(type, last.getLong(key));
                }
                if (entry.contains("Active", Tag.TAG_COMPOUND)) {
                    CompoundTag active = entry.getCompound("Active");
                    WorldEventType type = WorldEventType.byId(active.getString("Type"));
                    if (type == null || !active.hasUUID("Id")) return data.quarantine(tag);
                    Active a = new Active(type, active.getUUID("Id"), active.getLong("Started"),
                        active.getLong("StartedDay"));
                    a.eligibleTicks = Math.max(0, active.getInt("Eligible"));
                    a.forced = active.getBoolean("Forced");
                    ListTag actors = active.getList("Actors", Tag.TAG_INT_ARRAY);
                    for (Tag actor : actors) {
                        if (a.actors.size() >= MAX_ACTORS) break;
                        a.actors.add(NbtUtils.loadUUID(actor));
                    }
                    a.state = active.getCompound("State").copy();
                    row.active = a;
                }
                if (data.rows.putIfAbsent(entry.getUUID("Settlement"), row) != null) {
                    return data.quarantine(tag);
                }
            }
        } catch (RuntimeException malformed) {
            return data.quarantine(tag);
        }
        return data;
    }

    private WorldEventSavedData quarantine(CompoundTag raw) {
        rows.clear();
        quarantined = true;
        quarantinedRaw = raw.copy();
        return this;
    }
}
