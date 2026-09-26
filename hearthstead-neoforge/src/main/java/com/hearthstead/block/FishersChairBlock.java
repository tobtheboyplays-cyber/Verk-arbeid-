package com.hearthstead.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A low dock chair. Its facing points toward the water, away from its backrest. */
public final class FishersChairBlock extends Block {
    public static final MapCodec<FishersChairBlock> CODEC = simpleCodec(FishersChairBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public FishersChairBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }
    @Override public MapCodec<FishersChairBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }
    @Override public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        VoxelShape seat = box(2, 0, 2, 14, 8, 14);
        return Shapes.or(seat, switch (state.getValue(FACING)) {
            case SOUTH -> box(2, 8, 2, 14, 16, 4);
            case EAST -> box(2, 8, 2, 4, 16, 14);
            case WEST -> box(12, 8, 2, 14, 16, 14);
            default -> box(2, 8, 12, 14, 16, 14);
        });
    }
}
