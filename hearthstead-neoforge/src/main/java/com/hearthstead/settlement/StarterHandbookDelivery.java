package com.hearthstead.settlement;

import com.hearthstead.registry.ModAttachments;
import com.hearthstead.registry.ModItems;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Server-authoritative, one-time delivery of the physical starter handbook. */
public final class StarterHandbookDelivery {
    public enum Outcome {
        ALREADY_DELIVERED,
        EXISTING_HANDBOOK,
        INVENTORY,
        WAITING_FOR_SPACE
    }

    /**
     * Tries the grant without ever dropping, deleting or duplicating the item.
     *
     * <p>An existing handbook is treated as migration proof. A free inventory
     * slot receives the item directly. When the inventory is full, the
     * persistent flag stays false so the bounded event driver can retry. Only
     * a handbook physically owned by the player closes the one-time flag.
     */
    public static Outcome deliverIfNeeded(ServerPlayer player) {
        if (player.getData(ModAttachments.STARTER_HANDBOOK_DELIVERED)) {
            return Outcome.ALREADY_DELIVERED;
        }

        ItemStack handbook = new ItemStack(ModItems.HANDBOOK.get());
        if (player.getInventory().contains(handbook)) {
            markDelivered(player, Outcome.EXISTING_HANDBOOK);
            return Outcome.EXISTING_HANDBOOK;
        }

        if (player.getInventory().add(handbook)) {
            markDelivered(player, Outcome.INVENTORY);
            return Outcome.INVENTORY;
        }
        return Outcome.WAITING_FOR_SPACE;
    }

    private static void markDelivered(ServerPlayer player, Outcome route) {
        player.setData(ModAttachments.STARTER_HANDBOOK_DELIVERED, true);
        com.hearthstead.Hearthstead.LOGGER.info(
            "Starter handbook committed player={} route={}",
            player.getUUID(), route);
    }

    private StarterHandbookDelivery() {
    }
}
