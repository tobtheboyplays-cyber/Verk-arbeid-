package com.hearthstead.event.worldevent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The world remembers who came by (owner, 26 Sep). One {@link Book} per
 * settlement: every named visitor's visits (when, and what the village
 * looked like then), the last choice the players made with them and whether
 * it pleased or upset them, one-time flags (a milestone already thanked, a
 * raid already sung about) and the threat ladders.
 *
 * <p>Bounded everywhere (settlements, people, flags, log) so a long save
 * never grows without limit. A malformed book is dropped alone, never the
 * whole file.
 */
public final class VisitorMemory extends SavedData {
    private static final String NAME = "hearthstead_visitor_memory";
    private static final int VERSION = 1;
    public static final int MAX_BOOKS = 256;
    public static final int MAX_PEOPLE = 48;
    public static final int MAX_FLAGS = 160;
    public static final int MAX_LOG = 40;
    private static final Factory<VisitorMemory> FACTORY = new Factory<>(VisitorMemory::new, VisitorMemory::load, null);

    private final Map<UUID, Book> books = new LinkedHashMap<>();

    public static VisitorMemory get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    @Nullable
    public static VisitorMemory existing(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().get(FACTORY, NAME);
    }

    /** The settlement's book, created on first use (null only when full). */
    @Nullable
    public Book book(UUID settlementId) {
        if (settlementId == null) return null;
        Book book = books.get(settlementId);
        if (book == null) {
            if (books.size() >= MAX_BOOKS) return null;
            book = new Book();
            books.put(settlementId, book);
            setDirty();
        }
        return book;
    }

    @Nullable
    public Book existingBook(UUID settlementId) {
        return settlementId == null ? null : books.get(settlementId);
    }

    public void forget(UUID settlementId) {
        if (books.remove(settlementId) != null) setDirty();
    }

    public void changed() {
        setDirty();
    }

    // ------------------------------------------------------------ model --

    /** Mood of a remembered choice: upset, neutral or pleased. */
    public static final int DISPLEASED = -1, NEUTRAL = 0, PLEASED = 1;

    /** One person (or group) the settlement has met. */
    public static final class Person {
        public int visits;
        public long lastDay = WorldEventSchedule.NO_DAY;
        @Nullable public StoryFacts lastSeen;
        public String lastChoice = "";
        public int lastMood;
        public int displeased;
        public int pleased;

        CompoundTag write() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("Visits", visits);
            tag.putLong("LastDay", lastDay);
            if (lastSeen != null) tag.put("Seen", lastSeen.write());
            tag.putString("Choice", lastChoice);
            tag.putInt("Mood", lastMood);
            tag.putInt("Bad", displeased);
            tag.putInt("Good", pleased);
            return tag;
        }

        static Person read(CompoundTag tag) {
            Person p = new Person();
            p.visits = Math.max(0, tag.getInt("Visits"));
            p.lastDay = tag.contains("LastDay") ? tag.getLong("LastDay") : WorldEventSchedule.NO_DAY;
            p.lastSeen = tag.contains("Seen", Tag.TAG_COMPOUND) ? StoryFacts.read(tag.getCompound("Seen")) : null;
            p.lastChoice = tag.getString("Choice");
            p.lastMood = Math.max(-1, Math.min(1, tag.getInt("Mood")));
            p.displeased = Math.max(0, tag.getInt("Bad"));
            p.pleased = Math.max(0, tag.getInt("Good"));
            return p;
        }
    }

    /** One remembered choice, newest last. */
    public record Entry(long day, String who, String name, String choice, int mood) {
        CompoundTag write() {
            CompoundTag tag = new CompoundTag();
            tag.putLong("Day", day);
            tag.putString("Who", who);
            tag.putString("Name", name);
            tag.putString("Choice", choice);
            tag.putInt("Mood", mood);
            return tag;
        }

        static Entry read(CompoundTag tag) {
            return new Entry(tag.getLong("Day"), tag.getString("Who"), tag.getString("Name"),
                tag.getString("Choice"), Math.max(-1, Math.min(1, tag.getInt("Mood"))));
        }
    }

    /** A threat ladder ("You cannot stay here" then "Last warning" then the consequence). */
    public static final class Ladder {
        public static final String IDLE = "idle", ARMED = "armed", SWORN = "sworn", PAID = "paid",
            BEATEN = "beaten", WON = "won";
        /** Next step to play: 1 first warning, 2 last warning. */
        public int step = 1;
        public String status = IDLE;
        public long notBeforeDay = WorldEventSchedule.NO_DAY;
        public int payments;
        @Nullable public UUID captain;
        public int defeatsAt;
        public int victoriesAt;
        /** The sworn captain was handed to a raid plan (the next raid is his). */
        public boolean planned;

        public boolean over() {
            return PAID.equals(status) || BEATEN.equals(status);
        }

        CompoundTag write() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("Step", step);
            tag.putString("Status", status);
            tag.putLong("NotBefore", notBeforeDay);
            tag.putInt("Paid", payments);
            if (captain != null) tag.putUUID("Captain", captain);
            tag.putInt("DefeatsAt", defeatsAt);
            tag.putInt("VictoriesAt", victoriesAt);
            tag.putBoolean("Planned", planned);
            return tag;
        }

        static Ladder read(CompoundTag tag) {
            Ladder l = new Ladder();
            l.step = Math.max(1, Math.min(2, tag.getInt("Step")));
            l.status = tag.getString("Status").isEmpty() ? IDLE : tag.getString("Status");
            l.notBeforeDay = tag.contains("NotBefore") ? tag.getLong("NotBefore") : WorldEventSchedule.NO_DAY;
            l.payments = Math.max(0, tag.getInt("Paid"));
            l.captain = tag.hasUUID("Captain") ? tag.getUUID("Captain") : null;
            l.defeatsAt = tag.getInt("DefeatsAt");
            l.victoriesAt = tag.getInt("VictoriesAt");
            l.planned = tag.getBoolean("Planned");
            return l;
        }
    }

    /** Everything one settlement remembers. Pure: unit-testable without a world. */
    public static final class Book {
        private final Map<String, Person> people = new LinkedHashMap<>();
        private final Set<String> flags = new LinkedHashSet<>();
        private final List<Entry> log = new ArrayList<>();
        private final Map<String, Ladder> ladders = new LinkedHashMap<>();

        @Nullable
        public Person person(String who) {
            return people.get(who);
        }

        public Person personOrCreate(String who) {
            Person p = people.get(who);
            if (p == null) {
                while (people.size() >= MAX_PEOPLE) people.remove(people.keySet().iterator().next());
                p = new Person();
                people.put(who, p);
            }
            return p;
        }

        public int visits(String who) {
            Person p = people.get(who);
            return p == null ? 0 : p.visits;
        }

        /** Whole days since {@code who} last came, or -1 when never. */
        public long daysSince(String who, long today) {
            Person p = people.get(who);
            return p == null || p.lastDay == WorldEventSchedule.NO_DAY ? -1L : Math.max(0L, today - p.lastDay);
        }

        /** A visit happened: count it and keep what the village looked like. */
        public void recordVisit(String who, StoryFacts seen) {
            Person p = personOrCreate(who);
            p.visits = Math.min(999, p.visits + 1);
            p.lastDay = seen.day();
            p.lastSeen = seen;
        }

        /**
         * A choice that {@code who} will remember. The one entry point behind
         * every "will remember this" cue: the cue is only ever shown after
         * this record exists.
         */
        public Entry remember(String who, String name, String choice, int mood, long day) {
            Person p = personOrCreate(who);
            p.lastChoice = choice == null ? "" : choice;
            p.lastMood = Math.max(-1, Math.min(1, mood));
            if (mood < 0) p.displeased = Math.min(999, p.displeased + 1);
            if (mood > 0) p.pleased = Math.min(999, p.pleased + 1);
            Entry entry = new Entry(day, who, name == null ? "" : name, p.lastChoice, p.lastMood);
            log.add(entry);
            while (log.size() > MAX_LOG) log.remove(0);
            return entry;
        }

        public int lastMood(String who) {
            Person p = people.get(who);
            return p == null ? NEUTRAL : p.lastMood;
        }

        public String lastChoice(String who) {
            Person p = people.get(who);
            return p == null ? "" : p.lastChoice;
        }

        public boolean flag(String flag) {
            return flags.contains(flag);
        }

        /** Sets a one-time flag; false when it was already set. */
        public boolean setFlag(String flag) {
            if (flags.contains(flag)) return false;
            while (flags.size() >= MAX_FLAGS) flags.remove(flags.iterator().next());
            flags.add(flag);
            return true;
        }

        public List<Entry> log() {
            return List.copyOf(log);
        }

        public Ladder ladder(String id) {
            return ladders.computeIfAbsent(id, k -> new Ladder());
        }

        @Nullable
        public Ladder existingLadder(String id) {
            return ladders.get(id);
        }

        public CompoundTag write() {
            CompoundTag tag = new CompoundTag();
            CompoundTag ppl = new CompoundTag();
            people.forEach((who, p) -> ppl.put(who, p.write()));
            tag.put("People", ppl);
            ListTag fl = new ListTag();
            for (String f : flags) fl.add(StringTag.valueOf(f));
            tag.put("Flags", fl);
            ListTag lg = new ListTag();
            for (Entry e : log) lg.add(e.write());
            tag.put("Log", lg);
            CompoundTag lad = new CompoundTag();
            ladders.forEach((id, l) -> lad.put(id, l.write()));
            tag.put("Ladders", lad);
            return tag;
        }

        public static Book read(CompoundTag tag) {
            Book b = new Book();
            CompoundTag ppl = tag.getCompound("People");
            for (String who : ppl.getAllKeys()) {
                if (b.people.size() >= MAX_PEOPLE) break;
                b.people.put(who, Person.read(ppl.getCompound(who)));
            }
            for (Tag raw : tag.getList("Flags", Tag.TAG_STRING)) {
                if (b.flags.size() >= MAX_FLAGS) break;
                b.flags.add(raw.getAsString());
            }
            for (Tag raw : tag.getList("Log", Tag.TAG_COMPOUND)) {
                b.log.add(Entry.read((CompoundTag) raw));
            }
            while (b.log.size() > MAX_LOG) b.log.remove(0);
            CompoundTag lad = tag.getCompound("Ladders");
            for (String id : lad.getAllKeys()) b.ladders.put(id, Ladder.read(lad.getCompound(id)));
            return b;
        }
    }

    // --------------------------------------------------------------- io --

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Version", VERSION);
        ListTag list = new ListTag();
        books.forEach((id, book) -> {
            CompoundTag entry = book.write();
            entry.putUUID("Settlement", id);
            list.add(entry);
        });
        tag.put("Books", list);
        return tag;
    }

    public static VisitorMemory load(CompoundTag tag, HolderLookup.Provider registries) {
        VisitorMemory memory = new VisitorMemory();
        for (Tag raw : tag.getList("Books", Tag.TAG_COMPOUND)) {
            if (memory.books.size() >= MAX_BOOKS) break;
            try {
                CompoundTag entry = (CompoundTag) raw;
                if (!entry.hasUUID("Settlement")) continue;
                memory.books.put(entry.getUUID("Settlement"), Book.read(entry));
            } catch (RuntimeException malformed) {
                // One bad book is dropped alone; the rest of the memory stays.
            }
        }
        return memory;
    }
}
