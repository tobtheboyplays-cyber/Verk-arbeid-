package com.hearthstead.settlement.guildmaster;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-dimension record of the Guildmaster's welcome gift (owner, 27 Sep):
 * whether a settlement has had its 4 Coins, and which player UUIDs have had
 * their iron tool set there. Kept outside {@code Settlement} so the settlement
 * save format is untouched; a settlement with no row (an old save, or a
 * settlement founded before this feature) simply has not been welcomed yet.
 *
 * <p>Every claim is a check-and-set: {@link #claimCoins} and
 * {@link #claimTools} return true exactly once per key, so the gift can never
 * be handed out twice, across relogs, restarts or two players at once (the
 * server thread serialises all callers).
 */
public final class GuildmasterWelcomeData extends SavedData {
    static final String DATA_NAME = "hearthstead_gm_welcome";
    private static final Factory<GuildmasterWelcomeData> FACTORY =
        new Factory<>(GuildmasterWelcomeData::new, GuildmasterWelcomeData::load, null);

    private static final class Row {
        boolean coins;
        final Set<UUID> tools = new LinkedHashSet<>();
    }

    private final Map<UUID, Row> rows = new HashMap<>();

    public static GuildmasterWelcomeData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /** Replaces the live instance (GameTest seam: simulates a save and reload). */
    static void replaceForTest(ServerLevel level, GuildmasterWelcomeData data) {
        level.getDataStorage().set(DATA_NAME, data);
    }

    public boolean coinsGiven(@Nullable UUID settlement) {
        Row row = settlement == null ? null : rows.get(settlement);
        return row != null && row.coins;
    }

    public boolean toolsGiven(@Nullable UUID settlement, @Nullable UUID player) {
        Row row = settlement == null ? null : rows.get(settlement);
        return row != null && player != null && row.tools.contains(player);
    }

    /** True exactly once per settlement: the caller must then deliver the Coins. */
    public boolean claimCoins(@Nullable UUID settlement) {
        if (settlement == null) {
            return false;
        }
        Row row = rows.computeIfAbsent(settlement, id -> new Row());
        if (row.coins) {
            return false;
        }
        row.coins = true;
        setDirty();
        return true;
    }

    /** True exactly once per settlement and player: the caller must then deliver the tools. */
    public boolean claimTools(@Nullable UUID settlement, @Nullable UUID player) {
        if (settlement == null || player == null) {
            return false;
        }
        if (!rows.computeIfAbsent(settlement, id -> new Row()).tools.add(player)) {
            return false;
        }
        setDirty();
        return true;
    }

    /** Players who have had their tools at this settlement (a copy). */
    public Set<UUID> toolsGivenTo(@Nullable UUID settlement) {
        Row row = settlement == null ? null : rows.get(settlement);
        return row == null ? Set.of() : Set.copyOf(row.tools);
    }

    public static GuildmasterWelcomeData load(CompoundTag tag, @Nullable HolderLookup.Provider registries) {
        GuildmasterWelcomeData data = new GuildmasterWelcomeData();
        ListTag list = tag.getList("Welcomes", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.hasUUID("Settlement")) {
                continue; // malformed row: skip, never crash a load
            }
            Row row = new Row();
            row.coins = entry.getBoolean("CoinsGiven");
            ListTag tools = entry.getList("ToolsGiven", Tag.TAG_INT_ARRAY);
            for (Tag t : tools) {
                try {
                    row.tools.add(NbtUtils.loadUUID(t));
                } catch (IllegalArgumentException malformed) {
                    // skip a malformed UUID
                }
            }
            data.rows.put(entry.getUUID("Settlement"), row);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, @Nullable HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Row> e : rows.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Settlement", e.getKey());
            entry.putBoolean("CoinsGiven", e.getValue().coins);
            ListTag tools = new ListTag();
            for (UUID player : e.getValue().tools) {
                tools.add(NbtUtils.createUUID(player));
            }
            entry.put("ToolsGiven", tools);
            list.add(entry);
        }
        tag.put("Welcomes", list);
        return tag;
    }
}
