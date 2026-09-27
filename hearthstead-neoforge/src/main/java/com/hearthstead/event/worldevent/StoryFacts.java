package com.hearthstead.event.worldevent;

import com.hearthstead.building.BuildingType;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.raid.RaidLogEntry;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/**
 * What a visitor can truly see of a village at one moment (owner rule: never
 * claim walls, guards or buildings the village does not have). Saved with
 * every visit in {@link VisitorMemory} so a returning visitor can compare
 * ("last time there was only a banner here").
 *
 * @param day          in-game day of the snapshot
 * @param population   settlers
 * @param buildings    valid registered buildings
 * @param houses       valid Houses
 * @param guards       martial settlers
 * @param defenses     Barracks and Watchtowers
 * @param raidsHeld    raids repelled (raid log)
 * @param rank         0 Hamlet, 1 Village, 2 Town, 3 Castle, 4 Kingdom
 * @param freeBeds     capacity minus population (never negative)
 * @param tavern       a valid Tavern stands
 * @param food         food in the stores (settlement cache)
 */
public record StoryFacts(long day, int population, int buildings, int houses, int guards, int defenses,
                         int raidsHeld, int rank, int freeBeds, boolean tavern, int food) {
    public static final StoryFacts NONE = new StoryFacts(0, 0, 0, 0, 0, 0, 0, 0, 0, false, 0);
    public static final String[] RANKS = {"hamlet", "village", "town", "castle", "kingdom"};

    public boolean defended() {
        return guards >= 2 || defenses >= 1;
    }

    public String rankId() {
        return RANKS[Math.max(0, Math.min(RANKS.length - 1, rank))];
    }

    public CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Day", day);
        tag.putInt("Pop", population);
        tag.putInt("Bld", buildings);
        tag.putInt("Houses", houses);
        tag.putInt("Guards", guards);
        tag.putInt("Def", defenses);
        tag.putInt("Held", raidsHeld);
        tag.putInt("Rank", rank);
        tag.putInt("Beds", freeBeds);
        tag.putBoolean("Tavern", tavern);
        tag.putInt("Food", food);
        return tag;
    }

    public static StoryFacts read(@Nullable CompoundTag tag) {
        if (tag == null || tag.isEmpty()) return null;
        return new StoryFacts(tag.getLong("Day"), Math.max(0, tag.getInt("Pop")), Math.max(0, tag.getInt("Bld")),
            Math.max(0, tag.getInt("Houses")), Math.max(0, tag.getInt("Guards")), Math.max(0, tag.getInt("Def")),
            Math.max(0, tag.getInt("Held")), Math.max(0, Math.min(4, tag.getInt("Rank"))),
            Math.max(0, tag.getInt("Beds")), tag.getBoolean("Tavern"), Math.max(0, tag.getInt("Food")));
    }

    /** Reads the live settlement (never loads chunks). */
    public static StoryFacts of(ServerLevel level, Settlement settlement) {
        int buildings = 0, houses = 0, defenses = 0;
        boolean tavern = false;
        for (Building b : settlement.buildings) {
            if (!b.valid) continue;
            buildings++;
            if (b.type == BuildingType.HOUSE) houses++;
            if (b.type == BuildingType.BARRACKS || b.type == BuildingType.WATCHTOWER) defenses++;
            if (b.type == BuildingType.TAVERN) tavern = true;
        }
        int guards = 0;
        for (Settlement.SettlerRecord record : settlement.settlers) {
            if (record.profession != null && record.profession.martial()) guards++;
        }
        int held = 0;
        for (RaidLogEntry entry : settlement.raidLog) if (entry != null && entry.held()) held++;
        int rank = 0;
        try {
            if (has(level, settlement, "kingdom_crown")) rank = 4;
            else if (has(level, settlement, "castle_charter")) rank = 3;
            else if (has(level, settlement, "town_charter")) rank = 2;
            else if (has(level, settlement, "first_raid_aftermath")) rank = 1;
        } catch (RuntimeException ignored) {
            rank = 0;
        }
        int free = Math.max(0, settlement.capacity() - settlement.population());
        return new StoryFacts(WorldEventSchedule.dayOf(level.getDayTime()), settlement.population(), buildings, houses,
            guards, defenses, held, rank, free, tavern, Math.max(0, settlement.foodCache));
    }

    private static boolean has(ServerLevel level, Settlement settlement, String node) {
        return com.hearthstead.settlement.development.Development.has(level, settlement, node);
    }
}
