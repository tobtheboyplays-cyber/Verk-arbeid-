package com.hearthstead.block;

import com.hearthstead.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.network.NetworkHooks;

import javax.annotation.Nullable;

public class HearthBlock extends BaseEntityBlock {
    private static final VoxelShape SHAPE = Shapes.or(
        box(0, 0, 0, 16, 6, 16),
        box(2, 6, 2, 14, 11, 14));

    public HearthBlock(Properties properties) {
        super(properties);
    }

    @Override
    public VoxelShape getShape(BlockState state, net.minecraft.world.level.BlockGetter level,
                               BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new HearthBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        return level.isClientSide ? null
            : createTickerHelper(type, ModBlockEntities.HEARTH.get(), HearthBlockEntity::serverTick);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer
            && level.getBlockEntity(pos) instanceof HearthBlockEntity hearth) {
            NetworkHooks.openScreen(serverPlayer, hearth, buf -> {
                buf.writeBlockPos(pos);
                buf.writeUtf(hearth.settlementNameForMenu());
            });
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
                         boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            if (level.getBlockEntity(pos) instanceof HearthBlockEntity hearth) {
                hearth.dropContents();
            }
            if (level instanceof ServerLevel serverLevel) {
                com.hearthstead.settlement.SettlementManager.disbandAt(serverLevel, pos);
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(4) == 0) {
            double x = pos.getX() + 0.35 + random.nextDouble() * 0.3;
            double y = pos.getY() + 0.65;
            double z = pos.getZ() + 0.35 + random.nextDouble() * 0.3;
            level.addParticle(ParticleTypes.FLAME, x, y, z, 0.0, 0.01, 0.0);
            level.addParticle(ParticleTypes.SMOKE, x, y + 0.1, z, 0.0, 0.02, 0.0);
        }
        if (random.nextInt(2) == 0) {
            level.addParticle(ParticleTypes.SMALL_FLAME,
                pos.getX() + 0.25 + random.nextDouble() * 0.5, pos.getY() + 0.68,
                pos.getZ() + 0.25 + random.nextDouble() * 0.5, 0.0, 0.005, 0.0);
        }
        if (random.nextInt(24) == 0) {
            level.playLocalSound(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                SoundEvents.CAMPFIRE_CRACKLE, SoundSource.BLOCKS,
                0.5F + random.nextFloat() * 0.4F, random.nextFloat() * 0.7F + 0.6F, false);
        }
    }
}
