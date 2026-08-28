package com.hearthstead.menu;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;

public class HearthMenu extends AbstractContainerMenu {
    public static final int DATA_POPULATION = 0;
    public static final int DATA_CAPACITY = 1;
    public static final int DATA_EMPLOYED = 2;
    public static final int DATA_FOOD = 3;
    public static final int DATA_MORALE = 4;
    public static final int DATA_RADIUS = 5;
    public static final int DATA_ALERT = 6;
    public static final int DATA_RECRUIT = 7;
    public static final int DATA_COUNT = 8;

    public static final int COMMUNAL_SLOTS = HearthBlockEntity.INVENTORY_SIZE;
    public static final int COMMUNAL_X = 104;
    public static final int COMMUNAL_Y = 30;
    public static final int PLAYER_INV_X = 29;
    public static final int PLAYER_INV_Y = 140;

    private final ContainerData data;
    private final ContainerLevelAccess access;
    private final String settlementName;

    /** Client constructor: pos + settlement name arrive in the open buffer. */
    public HearthMenu(int windowId, Inventory playerInventory, FriendlyByteBuf buf) {
        this(windowId, playerInventory,
            resolveHearth(playerInventory, buf.readBlockPos()),
            new SimpleContainerData(DATA_COUNT), buf.readUtf());
    }

    public HearthMenu(int windowId, Inventory playerInventory, HearthBlockEntity hearth,
                      ContainerData data, String settlementName) {
        super(ModMenus.HEARTH.get(), windowId);
        this.data = data;
        this.settlementName = settlementName;
        this.access = hearth != null
            ? ContainerLevelAccess.create(hearth.getLevel(), hearth.getBlockPos())
            : ContainerLevelAccess.NULL;

        IItemHandler communal = hearth != null ? hearth.getInventory()
            : new ItemStackHandler(COMMUNAL_SLOTS);

        // Communal storage, 6x4.
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 6; col++) {
                addSlot(new SlotItemHandler(communal, col + row * 6,
                    COMMUNAL_X + col * 18 + 1, COMMUNAL_Y + row * 18 + 1));
            }
        }
        // Player inventory.
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9,
                    PLAYER_INV_X + col * 18 + 1, PLAYER_INV_Y + row * 18 + 1));
            }
        }
        // Hotbar.
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col,
                PLAYER_INV_X + col * 18 + 1, PLAYER_INV_Y + 58 + 1));
        }

        addDataSlots(data);
    }

    private static HearthBlockEntity resolveHearth(Inventory playerInventory, BlockPos pos) {
        return playerInventory.player.level().getBlockEntity(pos) instanceof HearthBlockEntity hearth
            ? hearth : null;
    }

    public String getSettlementName() {
        return settlementName;
    }

    public int get(int index) {
        return data.get(index);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        ItemStack moved = ItemStack.EMPTY;
        Slot slot = slots.get(slotIndex);
        if (slot.hasItem()) {
            ItemStack stack = slot.getItem();
            moved = stack.copy();
            if (slotIndex < COMMUNAL_SLOTS) {
                // Communal -> player.
                if (!moveItemStackTo(stack, COMMUNAL_SLOTS, slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                // Player -> communal.
                if (!moveItemStackTo(stack, 0, COMMUNAL_SLOTS, false)) {
                    return ItemStack.EMPTY;
                }
            }
            if (stack.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
            if (stack.getCount() == moved.getCount()) {
                return ItemStack.EMPTY;
            }
            slot.onTake(player, stack);
        }
        return moved;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, ModBlocks.HEARTH.get());
    }
}
