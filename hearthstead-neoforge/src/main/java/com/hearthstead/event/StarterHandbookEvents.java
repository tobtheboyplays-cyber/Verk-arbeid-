package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import com.hearthstead.registry.ModAttachments;
import com.hearthstead.settlement.StarterHandbookDelivery;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/** Delivers one physical starter handbook, with a bounded full-inventory retry. */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class StarterHandbookEvents {
    private static final int RETRY_INTERVAL_TICKS = 20;

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            deliver(player, true);
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player
            && player.tickCount % RETRY_INTERVAL_TICKS == 0
            && !player.getData(ModAttachments.STARTER_HANDBOOK_DELIVERED)) {
            deliver(player, false);
        }
    }

    private static void deliver(ServerPlayer player, boolean explainWaiting) {
        StarterHandbookDelivery.Outcome outcome =
            StarterHandbookDelivery.deliverIfNeeded(player);
        if (outcome == StarterHandbookDelivery.Outcome.INVENTORY) {
            player.sendSystemMessage(Component.translatable(
                "hearthstead.handbook.starter.received"));
            int bread = com.hearthstead.settlement.StarterKitConfig.kitBread();
            if (bread > 0) {
                player.sendSystemMessage(Component.translatable(
                    "hearthstead.handbook.starter.food", bread));
            }
        } else if (explainWaiting
            && outcome == StarterHandbookDelivery.Outcome.WAITING_FOR_SPACE) {
            player.sendSystemMessage(Component.translatable(
                "hearthstead.handbook.starter.inventory_full"));
        }
    }

    private StarterHandbookEvents() {
    }
}
