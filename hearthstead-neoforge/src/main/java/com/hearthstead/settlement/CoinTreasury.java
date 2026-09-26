package com.hearthstead.settlement;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.building.BuildingType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Ephemeral physical slot view. There is no monetary balance or persisted inventory copy. */
public final class CoinTreasury extends ItemStackHandler {
    private record Slot(IItemHandlerModifiable inventory, int index) {}
    private final List<Slot> slots = new ArrayList<>();
    private CoinTreasury() { super(0); }

    /** Read the same physical slots that payment uses; never persist a second balance. */
    public static int availableCoins(ItemStackHandler inventory) {
        long total = 0;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.is(com.hearthstead.registry.ModItems.GOLD_COIN.get())) {
                total += stack.getCount();
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    public static ItemStackHandler forPrice(ServerLevel level, Settlement settlement,
            HearthBlockEntity hearth, ServerPlayer payer, Costs.Price price) {
        return Costs.isCoinPrice(price) ? open(level, settlement, hearth, payer) : hearth.getInventory();
    }

    public static CoinTreasury open(ServerLevel level, Settlement settlement,
            HearthBlockEntity hearth, ServerPlayer payer) {
        CoinTreasury view = new CoinTreasury();
        if (payer != null && payer.serverLevel() == level) view.add(new InvWrapper(payer.getInventory()));
        if (hearth != null && settlement.id.equals(hearth.getSettlementId())) view.add(hearth.getInventory());
        Set<BlockPos> seen = new HashSet<>();
        int containers = 0;
        for (Building building : settlement.buildings) {
            if (containers >= 64) break;
            if (!building.valid || building.type != BuildingType.WAREHOUSE || building.bounds == null
                    || !level.hasChunkAt(building.plaquePos)
                    || !(level.getBlockEntity(building.plaquePos) instanceof PlaqueBlockEntity plaque)
                    || !building.id.equals(plaque.buildingId()) || plaque.type() != BuildingType.WAREHOUSE
                    || plaque.settlementFor(level) != settlement) continue;
            var b = building.bounds;
            long volume = (long)b.getXSpan() * b.getYSpan() * b.getZSpan();
            if (volume <= 0 || volume > 4096) continue;
            for (BlockPos pos : BlockPos.betweenClosed(b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ())) {
                if (containers >= 64) break;
                if (!level.hasChunkAt(pos) || !seen.add(pos.immutable())) continue;
                var entity = level.getBlockEntity(pos);
                if ((entity instanceof ChestBlockEntity || entity instanceof BarrelBlockEntity)
                        && entity instanceof Container container) {
                    view.add(new InvWrapper(container)); containers++;
                }
            }
        }
        return view;
    }

    private void add(IItemHandlerModifiable inventory) {
        for (int slot=0;slot<inventory.getSlots();slot++) slots.add(new Slot(inventory,slot));
    }
    @Override public int getSlots() { return slots.size(); }
    @Override public ItemStack getStackInSlot(int slot) { var ref=slots.get(slot); return ref.inventory().getStackInSlot(ref.index()); }
    @Override public void setStackInSlot(int slot, ItemStack stack) { var ref=slots.get(slot); ref.inventory().setStackInSlot(ref.index(),stack); }
    @Override public ItemStack extractItem(int slot,int count,boolean simulate) { var ref=slots.get(slot); return ref.inventory().extractItem(ref.index(),count,simulate); }
    @Override public ItemStack insertItem(int slot,ItemStack stack,boolean simulate) { var ref=slots.get(slot); return ref.inventory().insertItem(ref.index(),stack,simulate); }
    @Override public int getSlotLimit(int slot) { var ref=slots.get(slot); return ref.inventory().getSlotLimit(ref.index()); }
    @Override public boolean isItemValid(int slot,ItemStack stack) { var ref=slots.get(slot); return ref.inventory().isItemValid(ref.index(),stack); }

    public static List<ItemStack> snapshot(ItemStackHandler inventory) {
        List<ItemStack> copy=new ArrayList<>(inventory.getSlots());
        for(int i=0;i<inventory.getSlots();i++) copy.add(inventory.getStackInSlot(i).copy());
        return copy;
    }
    public static void restore(ItemStackHandler inventory,List<ItemStack> before) {
        if(inventory.getSlots()!=before.size()) throw new IllegalStateException("Treasury shape changed");
        for(int i=0;i<before.size();i++) inventory.setStackInSlot(i,before.get(i).copy());
    }
}
