package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.network.DevelopmentNetwork;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Diegetic entry to the physical Job Emblem catalogue.
 *
 * <p>An empty-hand normal right-click on the living, currently appointed
 * Mayor opens the shop. The ordinary Hearth never does. Sneak-right-click is
 * deliberately left alone for the settler inventory contract.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class MayorEmblemShopEvents {
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMayorInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getHand() != InteractionHand.MAIN_HAND
            || event.getEntity().isShiftKeyDown()
            || !event.getEntity().getMainHandItem().isEmpty()
            || !(event.getEntity() instanceof ServerPlayer player)
            || !(event.getTarget() instanceof SettlerEntity mayor)
            || mayor.getSettlementId() == null) {
            return;
        }
        Settlement settlement = SettlementManager.byId(player.serverLevel(),
            mayor.getSettlementId());
        if (settlement == null || settlement.mayorId == null
            || !settlement.mayorId.equals(mayor.getUUID())
            || !(player.serverLevel().getBlockEntity(settlement.center)
                instanceof HearthBlockEntity hearth)
            || !settlement.id.equals(hearth.getSettlementId())) {
            return;
        }
        DevelopmentNetwork.openEmblemShop(player, mayor, settlement, hearth);
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.CONSUME);
    }

    private MayorEmblemShopEvents() {
    }
}
