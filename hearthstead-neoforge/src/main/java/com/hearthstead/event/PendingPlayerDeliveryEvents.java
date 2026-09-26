package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.development.Development;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/** Reliable, bounded retry driver for paid/earned player item outboxes. */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class PendingPlayerDeliveryEvents {
    private static final int RETRY_INTERVAL_TICKS = 20;

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            retry(player, true);
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player
            && player.tickCount % RETRY_INTERVAL_TICKS == 0) {
            retry(player, false);
        }
    }

    private static void retry(ServerPlayer player, boolean forceSettlementLoad) {
        Development.retryPending(player.serverLevel(), player);

        SettlementSavedData data = forceSettlementLoad
            ? SettlementManager.data(player.serverLevel())
            : SettlementSavedData.existing(player.serverLevel());
        if (data == null) {
            return;
        }
        boolean changed = false;
        for (Settlement settlement : data.settlements.values()) {
            changed |= settlement.blessingState.retryPending(
                player.serverLevel(), player) > 0;
            changed |= settlement.employmentReturns.retry(
                player.serverLevel(), player) > 0;
        }
        if (changed) {
            data.setDirty();
        }
    }

    private PendingPlayerDeliveryEvents() {
    }
}
