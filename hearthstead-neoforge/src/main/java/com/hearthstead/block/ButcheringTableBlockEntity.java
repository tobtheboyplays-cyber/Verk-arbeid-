package com.hearthstead.block;

import com.hearthstead.item.CarcassItem;
import com.hearthstead.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One carcass lying on a Butchering Table. The stored stack is the sole item
 * authority while it is here: a Hunter lays it down from his shoulders, the
 * butchering commit removes it in the same tick its yield enters his bag, a
 * player may take it back by hand, and breaking the table drops it.
 *
 * <p>Deliberately NOT a {@link net.minecraft.world.Container}: couriers,
 * hoppers and the warehouse index must never treat an unbutchered body as
 * storage or as collectable Lodge output.
 */
public final class ButcheringTableBlockEntity extends BlockEntity {
    private static final String CARCASS = "Carcass";
    private ItemStack carcass = ItemStack.EMPTY;

    public ButcheringTableBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.BUTCHERING_TABLE.get(), pos, state);
    }

    public ItemStack carcass() {
        return carcass;
    }

    public boolean hasCarcass() {
        return !carcass.isEmpty();
    }

    /** Lays exactly one carcass down; false (and no change) when occupied. */
    public boolean place(ItemStack stack) {
        if (hasCarcass() || !CarcassItem.isCarcass(stack)) {
            return false;
        }
        carcass = stack.copyWithCount(1);
        changed();
        return true;
    }

    /** Removes and returns the carcass (empty when none). */
    public ItemStack take() {
        ItemStack out = carcass;
        carcass = ItemStack.EMPTY;
        if (!out.isEmpty()) {
            changed();
        }
        return out;
    }

    private void changed() {
        setChanged();
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!carcass.isEmpty()) {
            tag.put(CARCASS, carcass.save(registries));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        carcass = tag.contains(CARCASS)
            ? ItemStack.parse(registries, tag.getCompound(CARCASS)).orElse(ItemStack.EMPTY)
            : ItemStack.EMPTY;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
