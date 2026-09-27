package com.hearthstead.entity.path;

import com.hearthstead.settlement.BlessingEffects;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.builder.BuildSiteSavedData;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gates settlers must not open while planning a route (pathing, Builder
 * lane). Since 08:32 settlers path through closed fence gates; during a raid
 * that would open the palisade for the raiders. So:
 * <ul>
 * <li>while a settlement has a raid or an alarm on, every gate its
 *     defense works recorded (walls, palisades, gatehouses) is sealed;</li>
 * <li>a QA fixture or the raid director may mark a gate as controlled:
 *     sealed until released (the "breach gate" a raid opens itself).</li>
 * </ul>
 * Outside those cases a closed gate stays an ordinary, dearer passage.
 * Refreshed on the Builder upkeep (every 40 ticks); read per path node.
 */
public final class SealedGates {

    private static final Map<ResourceKey<Level>, LongSet> ALARM = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, LongSet> CONTROLLED = new ConcurrentHashMap<>();

    private SealedGates() {
    }

    public static boolean sealed(Level level, BlockPos pos) {
        if (level == null) {
            return false;
        }
        long key = pos.asLong();
        LongSet alarm = ALARM.get(level.dimension());
        if (alarm != null && alarm.contains(key)) {
            return true;
        }
        LongSet controlled = CONTROLLED.get(level.dimension());
        return controlled != null && controlled.contains(key);
    }

    /** A gate settlers never open until {@link #release} (a raid's own breach gate). */
    public static void control(Level level, BlockPos pos) {
        LongSet set = CONTROLLED.computeIfAbsent(level.dimension(), d -> new LongOpenHashSet());
        synchronized (set) {
            set.add(pos.asLong());
            set.add(pos.above().asLong());
        }
    }

    public static void release(Level level, BlockPos pos) {
        LongSet set = CONTROLLED.get(level.dimension());
        if (set != null) {
            synchronized (set) {
                set.remove(pos.asLong());
                set.remove(pos.above().asLong());
            }
        }
    }

    /** Rebuilds the raid/alarm seals of one level from its settlements' defense works. */
    public static void refresh(ServerLevel level) {
        LongOpenHashSet next = new LongOpenHashSet();
        SettlementSavedData data = SettlementSavedData.existing(level);
        BuildSiteSavedData sites = BuildSiteSavedData.existing(level);
        if (data != null && sites != null) {
            long now = level.getGameTime();
            for (Settlement settlement : data.settlements.values()) {
                if (!BlessingEffects.raidActive(settlement) && !settlement.alertActive(now)) {
                    continue;
                }
                for (BuildSiteSavedData.DefenseWork work : sites.defenseWorks(settlement.id)) {
                    for (BlockPos gate : work.gates()) {
                        next.add(gate.asLong());
                        next.add(gate.above().asLong()); // a gatehouse door's upper half
                    }
                }
            }
        }
        if (next.isEmpty()) {
            ALARM.remove(level.dimension());
        } else {
            ALARM.put(level.dimension(), next);
        }
    }

    /** Test seam. */
    public static void clearAll() {
        ALARM.clear();
        CONTROLLED.clear();
    }
}
