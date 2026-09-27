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
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

import java.util.UUID;

public class HearthMenu extends AbstractContainerMenu {
    public static final UUID NO_SETTLEMENT = new UUID(0L, 0L);
    public static final int DATA_POPULATION = 0;
    public static final int DATA_CAPACITY = 1;
    public static final int DATA_EMPLOYED = 2;
    public static final int DATA_FOOD = 3;
    public static final int DATA_MORALE = 4;
    public static final int DATA_RADIUS = 5;
    public static final int DATA_ALERT = 6;
    public static final int DATA_RECRUIT = 7;
    /** 1 when a valid tavern exists, 0 otherwise -- PLAN_TAVERN_GATE.md
     *  krav 1/5: the recruit stripe must be able to show the tavern
     *  blocker BEFORE the progress bar, so it needs its own synced slot
     *  rather than inferring the gate from DATA_RECRUIT alone. */
    public static final int DATA_TAVERN = 8;
    /** Stable {@link com.hearthstead.settlement.RecruitmentPolicy.Blocker} wire id. */
    public static final int DATA_RECRUIT_BLOCKER = 9;
    public static final int DATA_READY_AFTER_PRICE = 10;
    public static final int DATA_REQUIRED_RESERVE = 11;
    public static final int DATA_MISSING_RESERVE = 12;
    /** Stable {@link com.hearthstead.settlement.RecruitmentPolicy.Stage} wire id. */
    public static final int DATA_RECRUIT_STAGE = 13;
    /** Stable {@link com.hearthstead.settlement.state.FoundingJourney.Phase} wire id. */
    public static final int DATA_JOURNEY_PHASE = 14;
    /** Optimistic-lock revision for the journey's one deliberate skip action. */
    public static final int DATA_JOURNEY_REVISION = 15;
    /** Server-authoritative permission bit; the client never infers skip eligibility. */
    public static final int DATA_JOURNEY_CAN_SKIP = 16;
    /** Stable Journey schema-3 presentation mode wire id. */
    public static final int DATA_JOURNEY_V3_MODE = 17;
    /** Optimistic-lock revision for schema-3 presentation actions. */
    public static final int DATA_JOURNEY_V3_REVISION = 18;
    public static final int DATA_JOURNEY_V3_CAN_SKIP = 19;
    /** Frozen definition-v2 ordinal of the current objective, or -1. */
    public static final int DATA_JOURNEY_V3_CURRENT = 20;
    public static final int DATA_JOURNEY_V3_COMPLETED = 21;
    public static final int DATA_JOURNEY_V3_OUTCOME = 22;
    /** Index into JourneyDefinition.v2 chapters, or -1. */
    public static final int DATA_JOURNEY_V3_CHAPTER = 23;
    /** Exact optimistic-lock revision of the persisted recruitment transaction. */
    public static final int DATA_RECRUIT_REVISION = 24;
    /** Stable RecruitmentTransaction.Status wire id. */
    public static final int DATA_RECRUIT_TRANSACTION_STATUS = 25;
    public static final int DATA_COUNT = 26;

    public static final int COMMUNAL_SLOTS = HearthBlockEntity.INVENTORY_SIZE;
    public static final int COMMUNAL_X = 104;
    public static final int COMMUNAL_Y = 30;
    public static final int PLAYER_INV_X = 29;
    public static final int PLAYER_INV_Y = 140;

    private final ContainerData data;
    private final ContainerLevelAccess access;
    private final String settlementName;
    private final BlockPos hearthPos;
    private final UUID settlementId;

    /** Client constructor: exact menu identity arrives in the open buffer. */
    public HearthMenu(int windowId, Inventory playerInventory, FriendlyByteBuf buf) {
        this(windowId, playerInventory, readOpenData(buf));
    }

    private HearthMenu(int windowId, Inventory playerInventory, OpenData openData) {
        this(windowId, playerInventory,
            resolveHearth(playerInventory, openData.hearthPos),
            new SimpleContainerData(DATA_COUNT), openData.settlementName,
            openData.hearthPos, openData.settlementId);
    }

    public HearthMenu(int windowId, Inventory playerInventory, HearthBlockEntity hearth,
                      ContainerData data, String settlementName) {
        this(windowId, playerInventory, hearth, data, settlementName,
            hearth == null ? BlockPos.ZERO : hearth.getBlockPos(),
            hearth == null || hearth.getSettlementId() == null
                ? NO_SETTLEMENT : hearth.getSettlementId());
    }

    private HearthMenu(int windowId, Inventory playerInventory, HearthBlockEntity hearth,
                       ContainerData data, String settlementName, BlockPos hearthPos,
                       UUID settlementId) {
        super(ModMenus.HEARTH.get(), windowId);
        this.data = data;
        this.settlementName = settlementName;
        this.hearthPos = hearthPos == null ? BlockPos.ZERO : hearthPos.immutable();
        this.settlementId = settlementId == null ? NO_SETTLEMENT : settlementId;
        this.access = hearth != null
            ? ContainerLevelAccess.create(hearth.getLevel(), hearth.getBlockPos())
            : ContainerLevelAccess.NULL;

        IItemHandler communal = hearth != null ? hearth.getInventory()
            : new ItemStackHandler(COMMUNAL_SLOTS);

        // Communal storage, 6x4.
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 6; col++) {
                addSlot(new SlotItemHandler(communal, col + row * 6,
                    COMMUNAL_X + col * 18 + 1, COMMUNAL_Y + row * 18 + 1) {
                    @Override
                    public void setChanged() {
                        super.setChanged();
                        // A merge (moveItemStackTo) or a partial shift-click changes the live
                        // stack in place, which the handler never sees (QA-UI-05).
                        if (hearth != null) {
                            hearth.noteContentsChanged();
                        }
                    }
                });
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

    private static OpenData readOpenData(FriendlyByteBuf buf) {
        return new OpenData(buf.readBlockPos(), buf.readUUID(), buf.readUtf());
    }

    private static HearthBlockEntity resolveHearth(Inventory playerInventory, BlockPos pos) {
        return playerInventory.player.level().getBlockEntity(pos) instanceof HearthBlockEntity hearth
            ? hearth : null;
    }

    public String getSettlementName() {
        return settlementName;
    }

    public BlockPos getHearthPos() {
        return hearthPos;
    }

    public UUID getSettlementId() {
        return settlementId;
    }

    public int getContainerId() {
        return containerId;
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

    private record OpenData(BlockPos hearthPos, UUID settlementId,
                            String settlementName) {
    }
}
