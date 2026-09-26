package com.hearthstead.settlement.defense;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.List;

/**
 * Applies vanilla's own despawn rule to stray monsters inside a settlement
 * while the level has no players at all.
 *
 * <p>Vanilla despawns an ordinary (non-persistent) monster the moment it is
 * more than 128 blocks from the nearest player, but only evaluates that rule
 * when a player exists: with nobody online it never runs, so the monsters a
 * player left behind stand in the village forever and every civilian spends
 * its day in {@code SettlerPanicGoal} (reliability soak 2026-09-25: Elmfield
 * with no players, 15-22% of the workday fleeing from leftover zombies and
 * skeletons). With no player in the level every player is "farther than
 * 128 blocks", so removing exactly the monsters vanilla would remove is the
 * same rule, applied.
 *
 * <p>Never touches raiders, named or otherwise persistent mobs, or anything
 * outside a settlement's radius; never loads chunks.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class EmptyLevelMonsterSweep {
    private static final int INTERVAL = 200;
    private static final int MARGIN = 16;

    private EmptyLevelMonsterSweep() {
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)
            || level.getGameTime() % INTERVAL != 0 || !level.players().isEmpty()) {
            return;
        }
        SettlementSavedData data = SettlementSavedData.existing(level);
        if (data == null) {
            return;
        }
        for (Settlement settlement : data.settlements.values()) {
            if (settlement.center == null || !level.isLoaded(settlement.center)) {
                continue;
            }
            double r = settlement.radius + MARGIN;
            AABB box = new AABB(settlement.center).inflate(r, 48.0D, r);
            List<Monster> strays = level.getEntitiesOfClass(Monster.class, box,
                mob -> mob.isAlive() && !(mob instanceof RaiderEntity)
                    && !mob.isPersistenceRequired() && !mob.requiresCustomPersistence()
                    && !mob.hasCustomName() && mob.removeWhenFarAway(Double.MAX_VALUE));
            for (Monster mob : strays) {
                mob.discard();
            }
        }
    }
}
