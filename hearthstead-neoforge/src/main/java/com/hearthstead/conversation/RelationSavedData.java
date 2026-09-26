package com.hearthstead.conversation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Who remembers whom. One row per settlement (a nil UUID for talks outside
 * any settlement): per named person a relation -100..+100, their name, how
 * often they were met and the last thing they will remember you for; per
 * visitor kind a settlement-wide reputation. Kept on the overworld so every
 * dimension shares it.
 */
public final class RelationSavedData extends SavedData {
    private static final String NAME = "hearthstead_relations";
    private static final int MAX_SETTLEMENTS = 256;
    private static final int MAX_PEOPLE = 512;
    private static final int MAX_KINDS = 32;
    public static final UUID WILDS = new UUID(0L, 0L);
    private static final Factory<RelationSavedData> FACTORY =
        new Factory<>(RelationSavedData::new, RelationSavedData::load, null);

    public static final class Person {
        public int relation;
        public String name = "";
        public int met;
        public long lastMetGameTime;
        /** Lang key of the one thing they remember ("you refused him food"); may be empty. */
        public String memory = "";
    }

    private static final class Row {
        final Map<UUID, Person> people = new LinkedHashMap<>();
        final Map<String, Integer> reputation = new LinkedHashMap<>();
        long lastParleySerial = -1L;
    }

    private final Map<UUID, Row> rows = new LinkedHashMap<>();

    public static RelationSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    private Row row(@Nullable UUID settlement, boolean create) {
        UUID key = settlement == null ? WILDS : settlement;
        Row row = rows.get(key);
        if (row == null && create && rows.size() < MAX_SETTLEMENTS) {
            row = new Row();
            rows.put(key, row);
        }
        return row;
    }

    public int relation(@Nullable UUID settlement, UUID identity) {
        Row row = row(settlement, false);
        Person person = row == null ? null : row.people.get(identity);
        return person == null ? 0 : person.relation;
    }

    @Nullable
    public Person person(@Nullable UUID settlement, UUID identity) {
        Row row = row(settlement, false);
        return row == null ? null : row.people.get(identity);
    }

    public int reputation(@Nullable UUID settlement, String kind) {
        Row row = row(settlement, false);
        return row == null ? 0 : row.reputation.getOrDefault(kind, 0);
    }

    /** Applies deltas, records a meeting and returns the new relation. */
    public int change(@Nullable UUID settlement, SpeakerProfile who, int relationDelta, int reputationDelta,
                      @Nullable String memory, long gameTime) {
        Row row = row(settlement, true);
        if (row == null) return 0;
        Person person = row.people.get(who.identity());
        if (person == null) {
            if (row.people.size() >= MAX_PEOPLE) {
                UUID oldest = row.people.keySet().iterator().next();
                row.people.remove(oldest);
            }
            person = new Person();
            row.people.put(who.identity(), person);
        }
        person.relation = Relations.apply(person.relation, relationDelta);
        if (!who.name().isBlank()) person.name = who.name();
        if (memory != null && !memory.isBlank()) person.memory = memory;
        person.lastMetGameTime = gameTime;
        if (reputationDelta != 0 && (row.reputation.containsKey(who.kind()) || row.reputation.size() < MAX_KINDS)) {
            row.reputation.put(who.kind(), Relations.apply(row.reputation.getOrDefault(who.kind(), 0), reputationDelta));
        }
        setDirty();
        return person.relation;
    }

    /** Counts one conversation opened with this person. */
    public void met(@Nullable UUID settlement, SpeakerProfile who, long gameTime) {
        Row row = row(settlement, true);
        if (row == null) return;
        Person person = row.people.computeIfAbsent(who.identity(), id -> new Person());
        person.met = Math.min(9999, person.met + 1);
        if (!who.name().isBlank()) person.name = who.name();
        person.lastMetGameTime = gameTime;
        setDirty();
    }

    /** The raid serial a parley already happened for (never parley twice per raid). */
    public long lastParleySerial(UUID settlement) {
        Row row = row(settlement, false);
        return row == null ? -1L : row.lastParleySerial;
    }

    public void markParley(UUID settlement, long serial) {
        Row row = row(settlement, true);
        if (row == null) return;
        row.lastParleySerial = serial;
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Format", 1);
        ListTag list = new ListTag();
        rows.forEach((id, row) -> {
            CompoundTag r = new CompoundTag();
            r.putUUID("Settlement", id);
            r.putLong("Parley", row.lastParleySerial);
            ListTag people = new ListTag();
            row.people.forEach((pid, p) -> {
                CompoundTag t = new CompoundTag();
                t.putUUID("Id", pid);
                t.putInt("Relation", p.relation);
                t.putString("Name", p.name);
                t.putInt("Met", p.met);
                t.putLong("Last", p.lastMetGameTime);
                t.putString("Memory", p.memory);
                people.add(t);
            });
            r.put("People", people);
            CompoundTag rep = new CompoundTag();
            row.reputation.forEach(rep::putInt);
            r.put("Reputation", rep);
            list.add(r);
        });
        tag.put("Rows", list);
        return tag;
    }

    public static RelationSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        RelationSavedData data = new RelationSavedData();
        for (Tag raw : tag.getList("Rows", Tag.TAG_COMPOUND)) {
            CompoundTag r = (CompoundTag) raw;
            if (!r.hasUUID("Settlement") || data.rows.size() >= MAX_SETTLEMENTS) continue;
            Row row = new Row();
            row.lastParleySerial = r.contains("Parley") ? r.getLong("Parley") : -1L;
            for (Tag rawPerson : r.getList("People", Tag.TAG_COMPOUND)) {
                CompoundTag t = (CompoundTag) rawPerson;
                if (!t.hasUUID("Id") || row.people.size() >= MAX_PEOPLE) continue;
                Person p = new Person();
                p.relation = Relations.clamp(t.getInt("Relation"));
                p.name = t.getString("Name");
                p.met = Math.max(0, t.getInt("Met"));
                p.lastMetGameTime = t.getLong("Last");
                p.memory = t.getString("Memory");
                row.people.put(t.getUUID("Id"), p);
            }
            CompoundTag rep = r.getCompound("Reputation");
            for (String kind : rep.getAllKeys()) {
                if (row.reputation.size() < MAX_KINDS) row.reputation.put(kind, Relations.clamp(rep.getInt(kind)));
            }
            data.rows.put(r.getUUID("Settlement"), row);
        }
        return data;
    }
}
