package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server side of the item pickup notices. Ground pickups are observed through
 * NeoForge's post-pickup event (fired only after the inventory really took the
 * items, with the exact taken amount recoverable); Hearthstead code that hands
 * items straight into a player's inventory calls {@link #notify} itself.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class PickupNoticeNetwork {

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onItemPickup(ItemEntityPickupEvent.Post event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack original = event.getOriginalStack();
        ItemStack remaining = event.getCurrentStack();
        int taken = original.getCount() - (remaining.isEmpty() ? 0 : remaining.getCount());
        notify(player, original, taken);
    }

    /** Tells {@code player}'s HUD that {@code count} of {@code stack} arrived. */
    public static void notify(ServerPlayer player, ItemStack stack, int count) {
        if (player == null || player instanceof FakePlayer || player.connection == null
            || stack == null || stack.isEmpty() || count <= 0) {
            return;
        }
        int clamped = Math.min(count, PickupNoticePayload.MAX_COUNT);
        PayloadSend.toPlayer(player,
            new PickupNoticePayload(stack.copyWithCount(1), clamped));
    }

    private PickupNoticeNetwork() {
    }
}
