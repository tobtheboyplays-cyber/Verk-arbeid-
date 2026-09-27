package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import com.hearthstead.settlement.raid.RaidSleepPolicy;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.CanContinueSleepingEvent;
import net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent;

/** Server-only raid safety hooks. */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class RaidEvents {
    @SubscribeEvent
    public static void onCanPlayerSleep(CanPlayerSleepEvent event) {
        ServerPlayer player = event.getEntity();
        if (!(player.level() instanceof ServerLevel level)
            || event.getProblem() != null || !RaidSleepPolicy.blocksSleep(level, player)) {
            return;
        }
        event.setProblem(Player.BedSleepingProblem.OTHER_PROBLEM);
        notifyRaidAwake(player);
    }

    @SubscribeEvent
    public static void onCanContinueSleeping(CanContinueSleepingEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
            || !(player.level() instanceof ServerLevel level)
            || !event.mayContinueSleeping()
            || !RaidSleepPolicy.blocksSleep(level, player)) {
            return;
        }
        event.setContinueSleeping(false);
        notifyRaidAwake(player);
    }

    private static void notifyRaidAwake(ServerPlayer player) {
        player.displayClientMessage(Component.translatable(
            "hearthstead.message.raid_sleep_blocked"), true);
    }

    private RaidEvents() {
    }
}
