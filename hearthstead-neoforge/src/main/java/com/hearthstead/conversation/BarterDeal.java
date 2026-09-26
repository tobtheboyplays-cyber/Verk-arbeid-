package com.hearthstead.conversation;

import com.hearthstead.conversation.net.ConvActionPayload.Line;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * One barter offer, rebuilt on the server from what is really in the
 * player's slots and the partner's stock at accept time (the client's view is
 * never trusted). Hostile lines -- duplicate rows, zero, negative or
 * oversized counts -- refuse the whole offer ({@link BarterMath#validLines});
 * values are summed in longs. {@link #execute} moves items exactly once: the
 * partner's stock is taken atomically first, then the player's slots; any
 * refusal rolls back both sides.
 */
public record BarterDeal(Map<Integer, Integer> give, List<ItemStack> taken, long givenValue, long takenValue) {
    public static final int MAIN_SLOTS = 36;

    @Nullable
    public static BarterDeal build(Inventory inventory, BarterStock stock, List<Line> giveLines, List<Line> takeLines) {
        if (giveLines.size() > MAIN_SLOTS || takeLines.size() > MAIN_SLOTS) return null;
        int[] mineAvailable = new int[MAIN_SLOTS];
        for (int slot = 0; slot < MAIN_SLOTS; slot++) mineAvailable[slot] = inventory.getItem(slot).getCount();
        if (!BarterMath.validLines(indexes(giveLines), counts(giveLines), mineAvailable)) return null;
        List<ItemStack> goods = stock.items();
        int[] theirAvailable = new int[goods.size()];
        for (int i = 0; i < goods.size(); i++) theirAvailable[i] = goods.get(i).getCount();
        if (!BarterMath.validLines(indexes(takeLines), counts(takeLines), theirAvailable)) return null;

        Map<Integer, Integer> give = new LinkedHashMap<>();
        long givenValue = 0;
        for (Line line : giveLines) {
            ItemStack stack = inventory.getItem(line.index());
            if (stack.isEmpty()) return null;
            give.put(line.index(), line.count());
            givenValue += (long) Math.max(0, ConversationService.unitValue(stock, stack)) * line.count();
        }
        List<ItemStack> taken = new ArrayList<>();
        long takenValue = 0;
        for (Line line : takeLines) {
            ItemStack have = goods.get(line.index());
            if (have.isEmpty()) return null;
            taken.add(have.copyWithCount(line.count()));
            takenValue += (long) Math.max(0, ConversationService.unitValue(stock, have)) * line.count();
        }
        if (give.isEmpty() && taken.isEmpty()) return null;
        return new BarterDeal(give, taken, givenValue, takenValue);
    }

    private static int[] indexes(List<Line> lines) {
        int[] out = new int[lines.size()];
        for (int i = 0; i < out.length; i++) out[i] = lines.get(i).index();
        return out;
    }

    private static int[] counts(List<Line> lines) {
        int[] out = new int[lines.size()];
        for (int i = 0; i < out.length; i++) out[i] = lines.get(i).count();
        return out;
    }

    /** Moves everything or nothing. */
    public boolean execute(ServerPlayer player, BarterStock stock) {
        Inventory inventory = player.getInventory();
        for (Map.Entry<Integer, Integer> entry : give.entrySet()) {
            if (inventory.getItem(entry.getKey()).getCount() < entry.getValue()) return false;
        }
        if (!taken.isEmpty() && !stock.remove(taken)) return false;
        List<ItemStack> given = new ArrayList<>();
        Map<Integer, ItemStack> before = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> entry : give.entrySet()) {
            before.put(entry.getKey(), inventory.getItem(entry.getKey()).copy());
            ItemStack removed = inventory.removeItem(entry.getKey(), entry.getValue());
            if (removed.getCount() != entry.getValue()) {
                before.forEach(inventory::setItem);
                if (!taken.isEmpty()) stock.add(taken);
                return false;
            }
            given.add(removed);
        }
        if (!given.isEmpty()) stock.add(given);
        for (ItemStack stack : taken) {
            ItemStack copy = stack.copy();
            while (!copy.isEmpty()) {
                ItemStack part = copy.split(Math.max(1, copy.getMaxStackSize()));
                if (!inventory.add(part) && !part.isEmpty()) player.drop(part, false);
            }
        }
        inventory.setChanged();
        return true;
    }
}
