package com.hearthstead.settlement.warehouse;

import com.hearthstead.Hearthstead;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Keeps {@link WarehouseIndex} incremental: block changes inside indexed
 * bounds mark positions dirty, and each level tick spends a small, fixed
 * budget re-validating positions. Warehouse tech caps are re-synced every
 * {@link #LEVEL_SYNC_INTERVAL} ticks.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class WarehouseIndexEvents {

    public static final int LEVEL_SYNC_INTERVAL = 100;

    private WarehouseIndexEvents() {
    }

    /**
     * Fired server-side for every block change that updates neighbours:
     * placing, breaking, pistons, explosions, /setblock, GameTest setBlock.
     */
    @SubscribeEvent
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            WarehouseIndex.onBlockChanged(level, event.getPos());
        }
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            WarehouseIndex.tick(level);
            if (level.getGameTime() % LEVEL_SYNC_INTERVAL == 0L) {
                WarehouseLevelService.syncAll(level);
            }
        }
    }
}
