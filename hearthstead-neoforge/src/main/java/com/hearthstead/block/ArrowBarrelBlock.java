package com.hearthstead.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The Arrow Barrel (owner, 27 Sep: "piltonna"): a barrel that holds arrows
 * only. Archers refill from the nearest one in the settlement (the
 * Watchtower's chests are the fallback), and its screen carries the
 * "Call archers to resupply" button.
 */
public final class ArrowBarrelBlock extends Block implements EntityBlock {
    public static final MapCodec<ArrowBarrelBlock> CODEC = simpleCodec(ArrowBarrelBlock::new);

    public ArrowBarrelBlock(Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<ArrowBarrelBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ArrowBarrelBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof ArrowBarrelBlockEntity barrel) {
            player.openMenu(barrel);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement,
                            boolean moving) {
        if (!state.is(replacement.getBlock())) {
            if (level.getBlockEntity(pos) instanceof ArrowBarrelBlockEntity barrel) {
                Containers.dropContents(level, pos, barrel);
                level.updateNeighbourForOutputSignal(pos, this);
            }
            super.onRemove(state, level, pos, replacement, moving);
        }
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof ArrowBarrelBlockEntity barrel
            ? net.minecraft.world.inventory.AbstractContainerMenu.getRedstoneSignalFromContainer(barrel) : 0;
    }
}
