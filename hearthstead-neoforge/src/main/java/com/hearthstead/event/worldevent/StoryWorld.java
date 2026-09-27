package com.hearthstead.event.worldevent;

import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.raid.RaidLogEntry;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/** World reads and small shared actions of the story events. */
final class StoryWorld {
    /** Role tag prefix of every story actor (the director's event tag role). */
    static final String ROLE_PREFIX = "story_";

    private StoryWorld() {
    }

    static StoryRules.Context context(ServerLevel level, Settlement settlement) {
        long founded = settlement.raidLifecycle.foundedNight();
        long today = WorldEventSchedule.dayOf(level.getDayTime());
        // An unknown founding day (old saves) counts as an old village.
        long age = founded < 0L ? 30L : Math.max(0L, today - founded);
        WorldEventSavedData data = WorldEventSavedData.existing(level);
        WorldEventSavedData.Row row = data == null ? null : data.row(settlement.id);
        boolean merchant = row != null && (row.lastDayByType.containsKey(WorldEventType.PEDDLER)
            || row.lastDayByType.containsKey(WorldEventType.CARAVAN));
        long heldNight = -1L, hurtNight = -1L;
        int hurt = 0;
        List<RaidLogEntry> log = settlement.raidLog;
        if (!log.isEmpty()) {
            RaidLogEntry last = log.get(log.size() - 1);
            if (last != null && RaidLogEntry.isValid(last)) {
                if (last.held()) heldNight = last.night();
                if (last.settlersHurt() > 0) {
                    hurt = last.settlersHurt();
                    hurtNight = last.night();
                }
            }
        }
        int relation;
        try {
            relation = RivalEnvoyEvent.relation(level, settlement);
        } catch (RuntimeException e) {
            relation = 0;
        }
        return new StoryRules.Context(age, merchant, heldNight, hurt, hurtNight,
            WorldEventDirector.hostileReady(level, settlement), relation,
            com.hearthstead.settlement.raid.RaidEscalation.raidNumber(settlement));
    }

    /** The newest held raid's captain, or "" (the bard's song names him). */
    static String lastHeldCaptain(Settlement settlement) {
        List<RaidLogEntry> log = settlement.raidLog;
        for (int i = log.size() - 1; i >= 0; i--) {
            RaidLogEntry e = log.get(i);
            if (e != null && e.held() && RaidLogEntry.isValid(e)) {
                return e.captainName() == null ? "" : e.captainName();
            }
        }
        return "";
    }

    /** A named story visitor: a settler body with a fixed name, face and costume. */
    @Nullable
    static SettlerEntity spawn(ServerLevel level, Settlement settlement, WorldEventSavedData.Active active,
                               BlockPos feet, String role, String name, String costume, int seed, double scale) {
        SettlerEntity visitor = WorldEventActors.spawnVisitor(level, settlement, active, feet, role, scale);
        if (visitor == null) return null;
        visitor.setSettlerName(name);
        visitor.getPersistentData().getCompound(WorldEventDirector.TAG).putString("Name", name);
        visitor.setCustomName(Component.literal(name));
        if (seed != 0) visitor.setAppearanceSeed(seed);
        visitor.setLookCostume(StoryLooks.costumeId(costume));
        return visitor;
    }

    static void give(ServerPlayer player, ItemStack stack) {
        // BH-28: placeItemBackInInventory drops what does not fit (never lost, never doubled).
        if (!stack.isEmpty()) player.getInventory().placeItemBackInInventory(stack.copy());
    }

    static void moraleAll(ServerLevel level, Settlement settlement, float amount) {
        for (SettlerEntity settler : WorldEventActors.members(level, settlement, s -> true)) settler.addMorale(amount);
    }

    /** Where leavers walk to: out past the claim edge, away from the Banner through their spot. */
    static BlockPos leavePos(Settlement settlement, BlockPos spot) {
        double dx = spot.getX() - settlement.center.getX(), dz = spot.getZ() - settlement.center.getZ();
        double len = Math.max(1.0D, Math.sqrt(dx * dx + dz * dz));
        if (len < 1.5D) {
            dx = 1.0D;
            dz = 0.0D;
            len = 1.0D;
        }
        return BlockPos.containing(settlement.center.getX() + dx / len * (settlement.radius + 24),
            spot.getY(), settlement.center.getZ() + dz / len * (settlement.radius + 24));
    }

    /** HOLD at the stop (spread a little), or WALK_OFF once leaving; re-issued every second. */
    static void holdRoles(ServerLevel level, WorldEventSavedData.Active active) {
        if (!active.state.contains("Spot")) return;
        BlockPos spot = BlockPos.of(active.state.getLong("Spot"));
        boolean leaving = active.state.getBoolean("Leaving");
        BlockPos away = active.state.contains("LeavePos") ? BlockPos.of(active.state.getLong("LeavePos")) : spot;
        int i = 0;
        for (Entity actor : WorldEventActors.actors(level, active)) {
            if (actor instanceof SettlerEntity settler) {
                WorldEventDirector.assign(settler, new WorldEventDirector.Role(
                    leaving ? WorldEventDirector.RoleKind.WALK_OFF : WorldEventDirector.RoleKind.HOLD,
                    active.id, null, leaving ? away : spot.offset(i % 2 == 0 ? i : -i, 0, i / 2),
                    level.getGameTime() + 60, null, 0.6D));
                i++;
            }
        }
    }

    static Map<String, Integer> readVars(net.minecraft.nbt.CompoundTag tag) {
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (String k : tag.getAllKeys()) out.put(k, tag.getInt(k));
        return out;
    }

    static net.minecraft.nbt.CompoundTag writeVars(Map<String, Integer> vars) {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        vars.forEach(tag::putInt);
        return tag;
    }

    static List<String> readKeys(net.minecraft.nbt.CompoundTag state) {
        List<String> out = new java.util.ArrayList<>();
        for (net.minecraft.nbt.Tag t : state.getList("Lines", net.minecraft.nbt.Tag.TAG_STRING)) out.add(t.getAsString());
        return out;
    }

    static net.minecraft.nbt.ListTag writeKeys(List<String> keys) {
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        for (String k : keys) list.add(net.minecraft.nbt.StringTag.valueOf(k));
        return list;
    }
}
