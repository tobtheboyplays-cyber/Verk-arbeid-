package com.hearthstead.settlement.guildmaster;

import com.hearthstead.settlement.development.DevelopmentNode;
import com.hearthstead.settlement.development.JobEmblemCatalog;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure trade arithmetic for the Guildmaster's Professions & Emblems screen.
 *
 * <p>Prices are never invented here: one emblem costs exactly its
 * {@link JobEmblemCatalog.Entry#costs()} (Coins first, then goods), and a
 * quantity costs that list multiplied. The quantity rides on the existing
 * Development action's {@code targetWireId} (profession id in the low byte,
 * quantity-1 in the next byte), so the wire format of the shop is unchanged
 * and an old single-emblem action (quantity byte 0) still means one.
 */
public final class GuildmasterTrade {
    /** At most one emblem stack per purchase (emblems stack to 16). */
    public static final int MAX_QUANTITY = 16;
    /** Reach from the player to the seated Guildmaster, in blocks. */
    public static final double REACH = 6.0D;
    public static final double REACH_SQUARED = REACH * REACH;

    public static int encode(int professionId, int quantity) {
        int q = clampQuantity(quantity);
        return (professionId & 0xFF) | ((q - 1) << 8);
    }

    public static int professionIdOf(int encoded) {
        return encoded & 0xFF;
    }

    /**
     * The requested quantity, or 0 when the packet asks for something the
     * server never offers (negative id, above {@link #MAX_QUANTITY}, stray
     * high bits). A 0 is refused without any state change.
     */
    public static int quantityOf(int encoded) {
        if (encoded < 0 || (encoded >>> 16) != 0) {
            return 0;
        }
        int q = ((encoded >>> 8) & 0xFF) + 1;
        return q > MAX_QUANTITY ? 0 : q;
    }

    public static int clampQuantity(int quantity) {
        return Math.max(1, Math.min(MAX_QUANTITY, quantity));
    }

    /** The whole price of {@code quantity} emblems: every line multiplied. */
    public static List<DevelopmentNode.Cost> total(JobEmblemCatalog.Entry entry, int quantity) {
        if (entry == null || quantity <= 0) {
            return List.of();
        }
        List<DevelopmentNode.Cost> unit = entry.costs();
        ArrayList<DevelopmentNode.Cost> out = new ArrayList<>(unit.size());
        for (DevelopmentNode.Cost cost : unit) {
            out.add(new DevelopmentNode.Cost(cost.item(),
                Math.multiplyExact(cost.count(), quantity), cost.acceptedTag(),
                cost.displayKey()));
        }
        return List.copyOf(out);
    }

    /** Coins for {@code quantity} emblems (the first cost line). */
    public static int totalCoins(JobEmblemCatalog.Entry entry, int quantity) {
        return entry == null || quantity <= 0 ? 0 : Math.multiplyExact(entry.coinPrice(), quantity);
    }

    /**
     * Room for one exact purchased stack: the stack's own components matter
     * (each emblem carries its purchase provenance), so a fresh emblem never
     * merges with an older one. Each bought emblem therefore needs its own
     * empty slot; this counts empty slots only.
     */
    public static int emptySlots(Inventory inventory) {
        if (inventory == null) {
            return 0;
        }
        int empty = 0;
        for (ItemStack slot : inventory.items) {
            if (slot.isEmpty()) {
                empty++;
            }
        }
        return empty;
    }

    private GuildmasterTrade() {
    }
}
