package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import com.hearthstead.settlement.development.TechTree;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Once a second per level: tech study clocks and self-stamping charters. */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class TechTreeEvents {
    private TechTreeEvents() {
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level && level.getGameTime() % 20L == 7L) {
            try {
                TechTree.tick(level);
            } catch (RuntimeException failure) {
                Hearthstead.LOGGER.error("Tech tree tick failed", failure);
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        com.hearthstead.network.TechTreeNetwork.forget(event.getEntity().getUUID());
        com.hearthstead.settlement.development.TechKnowledgeSync.forget(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            try {
                com.hearthstead.settlement.development.TechKnowledgeSync.sync(player);
            } catch (RuntimeException failure) {
                Hearthstead.LOGGER.error("Tech knowledge sync failed", failure);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player
            && player.tickCount % 40 == 13) {
            try {
                com.hearthstead.settlement.development.TechKnowledgeSync.tick(player);
            } catch (RuntimeException failure) {
                Hearthstead.LOGGER.error("Tech knowledge tick failed", failure);
            }
        }
    }
}
