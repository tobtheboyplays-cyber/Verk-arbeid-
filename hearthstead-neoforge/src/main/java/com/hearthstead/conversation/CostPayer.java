package com.hearthstead.conversation;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.conversation.ConversationGraph.CostSpec;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.CoinTreasury;
import com.hearthstead.settlement.Settlement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

/**
 * Pays reply costs exactly once: from the player's own inventory first, then
 * the settlement's Hearth and Warehouse containers (the same physical slot
 * view the Coin treasury uses -- no second balance exists). Payment is
 * all-or-nothing: every slot touched is snapshotted and restored if any line
 * comes up short.
 */
public final class CostPayer {
    private CostPayer() {
    }

    /** A cost with its amount resolved for this binding. */
    public record Resolved(CostSpec.Kind kind, @Nullable Item item, int amount) {
        public boolean matches(ItemStack stack) {
            if (stack.isEmpty()) return false;
            return switch (kind) {
                case COINS -> stack.is(ModItems.GOLD_COIN.get());
                case FOOD -> stack.has(DataComponents.FOOD);
                case ITEM -> item != null && stack.is(item);
            };
        }

        /** Icon for the reply row. */
        public ItemStack icon() {
            return switch (kind) {
                case COINS -> new ItemStack(ModItems.GOLD_COIN.get());
                case FOOD -> new ItemStack(Items.BREAD);
                case ITEM -> item == null ? ItemStack.EMPTY : new ItemStack(item);
            };
        }
    }

    public static List<Resolved> resolve(List<CostSpec> specs, Map<String, Integer> vars) {
        List<Resolved> out = new ArrayList<>();
        for (CostSpec spec : specs) {
            int amount = spec.resolve(vars);
            if (amount <= 0) continue;
            Item item = null;
            if (spec.kind() == CostSpec.Kind.ITEM) {
                ResourceLocation id = ResourceLocation.tryParse(spec.item() == null ? "" : spec.item());
                item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
                if (item == null || item == Items.AIR) {
                    // Unknown item: unpayable rather than free.
                    out.add(new Resolved(CostSpec.Kind.ITEM, Items.BARRIER, amount));
                    continue;
                }
            }
            out.add(new Resolved(spec.kind(), item, amount));
        }
        return out;
    }

    /** The slots a player pays from: own inventory, then the settlement stores. */
    public static IItemHandlerModifiable view(ServerPlayer player, @Nullable Settlement settlement) {
        if (settlement != null && player.level().getBlockEntity(settlement.center) instanceof HearthBlockEntity hearth
            && settlement.id.equals(hearth.getSettlementId())) {
            return CoinTreasury.open(player.serverLevel(), settlement, hearth, player);
        }
        return new InvWrapper(player.getInventory());
    }

    /** How many of this cost the view holds (for "12 / 20" tooltips). */
    public static int available(IItemHandlerModifiable view, Resolved cost) {
        long total = 0;
        for (int slot = 0; slot < view.getSlots(); slot++) {
            ItemStack stack = view.getStackInSlot(slot);
            if (cost.matches(stack)) total += stack.getCount();
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /** Whether every line can be paid together (lines that share slots are counted once). */
    public static boolean canPay(IItemHandlerModifiable view, List<Resolved> costs) {
        int[] left = new int[view.getSlots()];
        for (int slot = 0; slot < left.length; slot++) left[slot] = view.getStackInSlot(slot).getCount();
        for (Resolved cost : costs) {
            int need = cost.amount();
            for (int slot = 0; slot < left.length && need > 0; slot++) {
                if (left[slot] <= 0 || !cost.matches(view.getStackInSlot(slot))) continue;
                int take = Math.min(need, left[slot]);
                left[slot] -= take;
                need -= take;
            }
            if (need > 0) return false;
        }
        return true;
    }

    /**
     * Takes every line or nothing. Returns false (and leaves every slot as it
     * was) when anything is short or a container refuses an extraction.
     */
    public static boolean pay(IItemHandlerModifiable view, List<Resolved> costs) {
        if (costs.isEmpty()) return true;
        if (!canPay(view, costs)) return false;
        List<ItemStack> before = new ArrayList<>(view.getSlots());
        for (int slot = 0; slot < view.getSlots(); slot++) before.add(view.getStackInSlot(slot).copy());
        boolean ok = true;
        outer:
        for (Resolved cost : costs) {
            int need = cost.amount();
            for (int slot = 0; slot < view.getSlots() && need > 0; slot++) {
                ItemStack stack = view.getStackInSlot(slot);
                if (!cost.matches(stack)) continue;
                int take = Math.min(need, stack.getCount());
                ItemStack removed = view.extractItem(slot, take, false);
                if (removed.getCount() != take || !cost.matches(removed)) {
                    ok = false;
                    break outer;
                }
                need -= take;
            }
            if (need > 0) {
                ok = false;
                break;
            }
        }
        if (!ok) {
            for (int slot = 0; slot < before.size(); slot++) view.setStackInSlot(slot, before.get(slot));
        }
        return ok;
    }
}
