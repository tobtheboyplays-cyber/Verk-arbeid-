package com.hearthstead.settlement.work;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.CoinTreasury;
import com.hearthstead.settlement.TavernSeating;
import com.hearthstead.settlement.Settlement;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/** The existing saved bag owns a visitor's finite physical travel money. */
public final class TavernGuestPayment {
    private static final String ISSUED = "HearthsteadTravelMoneyIssued";
    private TavernGuestPayment() {}
    /** A price is an integer count of the one real Coin item. No second balance exists. */
    public enum OrderPayer { TRAVELER_BAG, FREE_RESIDENT }
    private static boolean payingVisitor(SettlerEntity guest) { return guest.isTraveler() && !guest.isBound(); }
    public static int mealPrice(SettlerEntity guest) { return payingVisitor(guest) ? 2 : 0; }
    public static int quotedPrice(SettlerEntity guest, boolean includeAle) {
        return mealPrice(guest) + (includeAle && payingVisitor(guest) ? 1 : 0);
    }
    public static OrderPayer payer(SettlerEntity guest) {
        return payingVisitor(guest) ? OrderPayer.TRAVELER_BAG : OrderPayer.FREE_RESIDENT;
    }
    private static IItemHandlerModifiable payerInventory(ServerLevel level, Settlement settlement,
                                                    SettlerEntity guest, OrderPayer payer) {
        return payer == OrderPayer.TRAVELER_BAG
            ? new net.neoforged.neoforge.items.wrapper.InvWrapper(guest.bag) : null;
    }
    private static boolean plainCoin(ItemStack stack) {
        return stack.is(ModItems.GOLD_COIN.get()) && stack.getComponentsPatch().isEmpty();
    }
    public static boolean canPayOrder(SettlerEntity guest, OrderPayer payer, int price) {
        if (payer == OrderPayer.FREE_RESIDENT) return !payingVisitor(guest) && price == 0;
        if (!(guest.level() instanceof ServerLevel level) || price < 1 || price > 3
            || TavernSeating.visitSettlement(guest) == null) return false;
        IItemHandlerModifiable inventory = payerInventory(level, TavernSeating.visitSettlement(guest), guest, payer);
        if (inventory == null) return false;
        int available = 0;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (plainCoin(stack)) available += stack.getCount();
        }
        return available >= price;
    }
    /** Preflight, then move exact physical Coins into the serving entity's saved escrow. */
    public static ItemStack debitOrder(SettlerEntity guest, OrderPayer payer, int price) {
        if (payer == OrderPayer.FREE_RESIDENT && canPayOrder(guest, payer, price)) return ItemStack.EMPTY;
        if (!canPayOrder(guest, payer, price)) return ItemStack.EMPTY;
        int remaining = price;
        for (int slot = 0; slot < guest.bag.getContainerSize() && remaining > 0; slot++) {
            ItemStack held = guest.bag.getItem(slot);
            if (!plainCoin(held)) continue;
            int amount = Math.min(remaining, held.getCount());
            held.shrink(amount);
            remaining -= amount;
        }
        if (remaining != 0) throw new IllegalStateException("Preflighted Coin debit incomplete");
        guest.bag.setChanged();
        return new ItemStack(ModItems.GOLD_COIN.get(), price);
    }
    /** Retry-safe: on no capacity the same serving keeps the exact escrow stack. */
    public static boolean refundOrder(SettlerEntity guest, OrderPayer payer, ItemStack escrow) {
        if (escrow.isEmpty()) return true;
        if (payer == OrderPayer.FREE_RESIDENT) return false;
        if (!(guest.level() instanceof ServerLevel level) || !plainCoin(escrow)
            || !guest.isAlive() || payer != OrderPayer.TRAVELER_BAG) return false;
        // The quoted payer is the saved guest UUID and its finite bag. Admission
        // status may already be DEPARTING; visitSettlement is not refund authority.
        IItemHandlerModifiable inventory = payerInventory(level, null, guest, payer);
        if (inventory == null) return false;
        int slot = depositSlot(inventory, escrow);
        if (slot < 0) return false;
        if (!inventory.insertItem(slot, escrow.copy(), false).isEmpty()) return false;
        escrow.setCount(0);
        return true;
    }
    private static int depositSlot(IItemHandlerModifiable inventory, ItemStack offered) {
        for (int i = 0; i < inventory.getSlots(); i++)
            if (inventory.insertItem(i, offered.copy(), true).isEmpty()) return i;
        return -1;
    }
    /** Tavern till: the same physical service store must fit the earned meal Coins. */
    public static boolean canSettleMeal(Container store, int count) {
        if (count == 0) return true;
        if (store == null || count < 1 || count > 2) return false;
        ItemStack coin = new ItemStack(ModItems.GOLD_COIN.get(), count);
        for (int i = 0; i < store.getContainerSize(); i++) {
            ItemStack held = store.getItem(i);
            if (!store.canPlaceItem(i, coin)) continue;
            if (held.isEmpty() || ItemStack.isSameItemSameComponents(held, coin)
                && held.getCount() + count <= Math.min(store.getMaxStackSize(), held.getMaxStackSize())) return true;
        }
        return false;
    }
    /** One container slot is enough for the whole quoted meal line; no partial deposit. */
    public static boolean settleMeal(Container store, ItemStack escrow, int count) {
        if (count == 0) return true;
        if (!plainCoin(escrow) || escrow.getCount() < count || !canSettleMeal(store, count)) return false;
        ItemStack coin = new ItemStack(ModItems.GOLD_COIN.get(), count);
        for (int i = 0; i < store.getContainerSize(); i++) {
            ItemStack held = store.getItem(i);
            if (!store.canPlaceItem(i, coin)) continue;
            if (held.isEmpty()) store.setItem(i, coin);
            else if (ItemStack.isSameItemSameComponents(held, coin)
                && held.getCount() + count <= Math.min(store.getMaxStackSize(), held.getMaxStackSize())) {
                held.grow(count); store.setChanged();
            } else continue;
            escrow.shrink(count);
            return true;
        }
        return false;
    }

    /** Actual creation only: never called from load, seating, meals or recurring ticks. */
    public static boolean initializeTraveler(SettlerEntity guest) {
        if (!(guest.level() instanceof ServerLevel) || !guest.isTraveler() || guest.isBound()
            || guest.getPersistentData().contains(ISSUED) || !guest.bag.isEmpty()) return false;
        guest.bag.setItem(0, new ItemStack(ModItems.GOLD_COIN.get(), 3));
        guest.getPersistentData().putBoolean(ISSUED, true);
        return true;
    }
    public static boolean bagAllowsVisit(SettlerEntity actor) {
        if (actor.bag.isEmpty()) return true;
        for (int i = 0; i < actor.bag.getContainerSize(); i++) {
            ItemStack item = actor.bag.getItem(i);
            if (!item.isEmpty() && !item.is(ModItems.GOLD_COIN.get())) return false;
        }
        return true;
    }
    private static int coinSlot(SettlerEntity guest) {
        for (int i = 0; i < guest.bag.getContainerSize(); i++)
            if (guest.bag.getItem(i).is(ModItems.GOLD_COIN.get())) return i;
        return -1;
    }
    /** Reserve the visitor's separate food coin before committing to an optional tap trip. */
    public static boolean canAffordAle(SettlerEntity guest, boolean reserveFoodPrice) {
        int coins = 0;
        for (int i = 0; i < guest.bag.getContainerSize(); i++) {
            ItemStack stack = guest.bag.getItem(i);
            if (stack.is(ModItems.GOLD_COIN.get()) && stack.getComponentsPatch().isEmpty()) coins += stack.getCount();
        }
        return coins >= 1 + (reserveFoodPrice && guest.isTraveler() ? 1 : 0);
    }
    /** Caller is the live serving's exact sip contact. Returned coin immediately enters its saved cargo. */
    public static ItemStack takeAleCoin(SettlerEntity guest) {
        if (!(guest.level() instanceof ServerLevel) || !guest.isAlive()) return ItemStack.EMPTY;
        for (int i = 0; i < guest.bag.getContainerSize(); i++) {
            ItemStack stack = guest.bag.getItem(i);
            if (stack.is(ModItems.GOLD_COIN.get()) && stack.getComponentsPatch().isEmpty()) {
                ItemStack paid = stack.split(1); guest.bag.setChanged(); return paid;
            }
        }
        return ItemStack.EMPTY;
    }
    private record Destination(ItemStackHandler inventory, int slot) {}
    private static Destination space(ItemStackHandler inventory, ItemStack coin) {
        for (int i = 0; i < inventory.getSlots(); i++)
            if (inventory.insertItem(i, coin.copyWithCount(1), true).isEmpty()) return new Destination(inventory, i);
        return null;
    }
    private static Destination destination(SettlerEntity host, ItemStack coin) {
        if (!(host.level() instanceof ServerLevel level) || host.settlement() == null) return null;
        var settlement = host.settlement();
        Destination warehouse = space(CoinTreasury.open(level, settlement, null, null), coin);
        if (warehouse != null) return warehouse;
        // A staffed early Tavern can earn before the first Warehouse exists.
        if (!level.hasChunkAt(settlement.center)
            || !(level.getBlockEntity(settlement.center) instanceof HearthBlockEntity hearth)
            || !settlement.id.equals(hearth.getSettlementId())) return null;
        return space(hearth.getInventory(), coin);
    }
    public static boolean canOrder(SettlerEntity host, SettlerEntity guest) {
        if (!guest.isTraveler()) return true;
        int slot = coinSlot(guest);
        return slot >= 0 && destination(host, guest.bag.getItem(slot)) != null;
    }
    /** Invoked only by the real eight-tick receiving contact, before DINING commitment. */
    public static boolean receive(SettlerEntity host, SettlerEntity guest, ItemStack food, boolean paid) {
        if (!paid && !guest.isTraveler()) return guest.beginMeal(food);
        if (!(host.level() instanceof ServerLevel) || host.level() != guest.level()
            || host.settlement() == null || host.settlement() != TavernSeating.visitSettlement(guest)
            || !guest.isAlive() || guest.hasMeal() || food.isEmpty()
            || food.getFoodProperties(guest) == null) return false;
        int slot = coinSlot(guest);
        if (slot < 0) return false;
        ItemStack money = guest.bag.getItem(slot);
        Destination target = destination(host, money);
        if (target == null) return false;
        ItemStack before = target.inventory().getStackInSlot(target.slot()).copy();
        ItemStack remainder = target.inventory().insertItem(target.slot(), money.copyWithCount(1), false);
        if (!remainder.isEmpty()) return false;
        // ResidentMeal.begin has no callbacks: these physical mutations are one server call.
        if (!guest.beginMeal(food)) {
            target.inventory().setStackInSlot(target.slot(), before);
            return false;
        }
        money.shrink(1);
        guest.bag.setChanged();
        // Tech tree (Hall of Revels): the house matches every Coin paid.
        if (host.level() instanceof ServerLevel level) {
            com.hearthstead.settlement.techtree.effects.CommonsEffects.payTavernBonus(level, host.settlement(), 1);
        }
        return true;
    }
}
