package com.hearthstead.block;

import com.hearthstead.menu.HearthMenu;
import com.hearthstead.registry.ModBlockEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

public class HearthBlockEntity extends BlockEntity implements net.minecraft.world.MenuProvider {
    public static final int INVENTORY_SIZE = 24;

    private final ItemStackHandler inventory = new ItemStackHandler(INVENTORY_SIZE) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };
    private LazyOptional<IItemHandler> itemCap = LazyOptional.of(() -> inventory);

    @Nullable
    private UUID settlementId;
    private int tickCount;
    private int foundingCooldown;

    public HearthBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.HEARTH.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  HearthBlockEntity hearth) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        hearth.tickCount++;
        if (hearth.tickCount % 20 != 0) {
            return;
        }

        if (hearth.settlementId == null) {
            if (hearth.foundingCooldown > 0) {
                hearth.foundingCooldown--;
                return;
            }
            Settlement founded = SettlementManager.tryFound(serverLevel, pos);
            if (founded != null) {
                hearth.settlementId = founded.id;
                hearth.setChanged();
            } else {
                hearth.foundingCooldown = 10; // seconds between retries
            }
            return;
        }

        Settlement s = SettlementManager.byId(serverLevel, hearth.settlementId);
        if (s == null) {
            hearth.settlementId = null;
            hearth.setChanged();
            return;
        }
        s.foodCache = hearth.countFoodUnits();
        SettlementManager.tickRecruitment(serverLevel, s);
    }

    // ------------------------------------------------------------ food ---

    /** Number of edible items in communal storage. */
    public int countFoodUnits() {
        int units = 0;
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (!stack.isEmpty() && stack.getFoodProperties(null) != null) {
                units += stack.getCount();
            }
        }
        return units;
    }

    /**
     * Removes and returns one food item, preferring the most nourishing.
     * Returns EMPTY when the larder is bare.
     */
    public ItemStack extractBestFood() {
        int bestSlot = -1;
        int bestNutrition = -1;
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            FoodProperties food = stack.isEmpty() ? null : stack.getFoodProperties(null);
            if (food != null && food.getNutrition() > bestNutrition) {
                bestNutrition = food.getNutrition();
                bestSlot = i;
            }
        }
        if (bestSlot < 0) {
            return ItemStack.EMPTY;
        }
        return inventory.extractItem(bestSlot, 1, false);
    }

    /** Burns up to {@code count} food items (recruitment cost). */
    public void consumeFood(int count) {
        for (int n = 0; n < count; n++) {
            if (extractBestFood().isEmpty()) {
                return;
            }
        }
    }

    /** Deposits a stack into communal storage; returns whatever did not fit. */
    public ItemStack insertGoods(ItemStack stack) {
        return ItemHandlerHelper.insertItemStacked(inventory, stack, false);
    }

    public ItemStackHandler getInventory() {
        return inventory;
    }

    @Nullable
    public UUID getSettlementId() {
        return settlementId;
    }

    /** Direct binding for tests and admin tools; skips the founding flow. */
    public void bindSettlement(@Nullable UUID id) {
        this.settlementId = id;
        setChanged();
    }

    public String settlementNameForMenu() {
        if (level instanceof ServerLevel serverLevel && settlementId != null) {
            Settlement s = SettlementManager.byId(serverLevel, settlementId);
            if (s != null) {
                return s.name;
            }
        }
        return "";
    }

    public void dropContents() {
        if (level == null) {
            return;
        }
        SimpleContainer drops = new SimpleContainer(inventory.getSlots());
        for (int i = 0; i < inventory.getSlots(); i++) {
            drops.setItem(i, inventory.getStackInSlot(i));
        }
        Containers.dropContents(level, worldPosition, drops);
    }

    // ------------------------------------------------------------ menu ---

    private final ContainerData menuData = new ContainerData() {
        @Override
        public int get(int index) {
            if (!(level instanceof ServerLevel serverLevel)) {
                return 0;
            }
            Settlement s = SettlementManager.byId(serverLevel, settlementId);
            if (s == null) {
                return 0;
            }
            return switch (index) {
                case HearthMenu.DATA_POPULATION -> s.population();
                case HearthMenu.DATA_CAPACITY -> s.capacity();
                case HearthMenu.DATA_EMPLOYED -> s.employed();
                case HearthMenu.DATA_FOOD -> Math.min(s.foodCache, 9999);
                case HearthMenu.DATA_MORALE -> s.moraleCache;
                case HearthMenu.DATA_RADIUS -> s.radius;
                case HearthMenu.DATA_ALERT -> s.alertActive(serverLevel.getGameTime()) ? 1 : 0;
                case HearthMenu.DATA_RECRUIT ->
                    s.recruitTarget > 0 ? Math.min(100, s.recruitProgress * 100 / s.recruitTarget) : 0;
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
        }

        @Override
        public int getCount() {
            return HearthMenu.DATA_COUNT;
        }
    };

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.hearthstead.hearth");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int windowId, Inventory playerInventory, Player player) {
        return new HearthMenu(windowId, playerInventory, this, menuData, settlementNameForMenu());
    }

    // ------------------------------------------------------ persistence ---

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("Inventory", inventory.serializeNBT());
        if (settlementId != null) {
            tag.putUUID("SettlementId", settlementId);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        inventory.deserializeNBT(tag.getCompound("Inventory"));
        settlementId = tag.hasUUID("SettlementId") ? tag.getUUID("SettlementId") : null;
    }

    @Override
    @Nonnull
    public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.ITEM_HANDLER) {
            return itemCap.cast();
        }
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        itemCap.invalidate();
    }

    @Override
    public void reviveCaps() {
        super.reviveCaps();
        itemCap = LazyOptional.of(() -> inventory);
    }
}
