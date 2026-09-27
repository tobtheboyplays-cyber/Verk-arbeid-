package com.hearthstead.settlement.guildmaster;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-dimension link from a settlement (its Banner) to the one Guildmaster
 * entity that sits there. Kept outside {@code Settlement} so the Guildmaster
 * never touches the settlement roster, population or its save format.
 *
 * <p>The UUID link is what makes "exactly one per Banner" survive save/load
 * and chunk unload: the service only spawns when this entry is missing or the
 * linked entity is provably absent from a fully loaded, entity-ticking area,
 * and a loaded Guildmaster whose UUID is not the linked one removes itself.
 */
public final class GuildmasterRegistry extends SavedData {
    private static final String DATA_NAME = "hearthstead_guildmasters";
    private static final Factory<GuildmasterRegistry> FACTORY =
        new Factory<>(GuildmasterRegistry::new, GuildmasterRegistry::load, null);

    private final Map<UUID, UUID> bySettlement = new HashMap<>();

    public static GuildmasterRegistry get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    @Nullable
    public UUID idFor(UUID settlementId) {
        return settlementId == null ? null : bySettlement.get(settlementId);
    }

    public void link(UUID settlementId, UUID guildmaster) {
        if (settlementId == null || guildmaster == null) {
            return;
        }
        if (!guildmaster.equals(bySettlement.put(settlementId, guildmaster))) {
            setDirty();
        }
    }

    public void unlink(UUID settlementId) {
        if (settlementId != null && bySettlement.remove(settlementId) != null) {
            setDirty();
        }
    }

    /** Settlement ids that currently have a link (a copy, safe to iterate). */
    public java.util.Set<UUID> linkedSettlements() {
        return java.util.Set.copyOf(bySettlement.keySet());
    }

    public static GuildmasterRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        GuildmasterRegistry data = new GuildmasterRegistry();
        ListTag list = tag.getList("Links", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag row = list.getCompound(i);
            if (row.hasUUID("Settlement") && row.hasUUID("Guildmaster")) {
                data.bySettlement.put(row.getUUID("Settlement"), row.getUUID("Guildmaster"));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, UUID> entry : bySettlement.entrySet()) {
            CompoundTag row = new CompoundTag();
            row.putUUID("Settlement", entry.getKey());
            row.putUUID("Guildmaster", entry.getValue());
            list.add(row);
        }
        tag.put("Links", list);
        return tag;
    }
}
