package com.hearthstead.block;

import com.hearthstead.settlement.work.AleTapService;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A wall tap has no hidden tank: the adjacent backing barrel owns all stock. */
public final class AleTapBlock extends Block {
    public static final MapCodec<AleTapBlock> CODEC = simpleCodec(AleTapBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty POURING = BooleanProperty.create("pouring");
    private static final VoxelShape NORTH = box(4, 3, 5, 12, 15, 16);
    private static final VoxelShape SOUTH = box(4, 3, 0, 12, 15, 11);
    private static final VoxelShape EAST = box(0, 3, 4, 11, 15, 12);
    private static final VoxelShape WEST = box(5, 3, 4, 16, 15, 12);

    public AleTapBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(POURING, false));
    }
    @Override public MapCodec<AleTapBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, POURING);
    }
    @Override public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> SOUTH; case EAST -> EAST; case WEST -> WEST; default -> NORTH;
        };
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction face = context.getClickedFace();
        if (face.getAxis().isVertical()) return null;
        BlockState state = defaultBlockState().setValue(FACING, face);
        return state.canSurvive(context.getLevel(), context.getClickedPos()) ? state : null;
    }
    @Override public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction face = state.getValue(FACING);
        BlockPos support = pos.relative(face.getOpposite());
        return level.getBlockState(support).isFaceSturdy(level, support, face);
    }
    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbour,
            LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        return direction == state.getValue(FACING).getOpposite() && !state.canSurvive(level, pos)
            ? Blocks.AIR.defaultBlockState() : state;
    }
    @Override protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
            BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer) {
            var result = AleTapService.buy(serverPlayer, bottleHand(player, hand), pos, state.getValue(FACING));
            serverPlayer.displayClientMessage(result.message(), true);
        }
        return level.isClientSide ? ItemInteractionResult.SUCCESS : ItemInteractionResult.CONSUME;
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
            Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer) {
            var result = AleTapService.buy(serverPlayer, bottleHand(player, InteractionHand.MAIN_HAND), pos, state.getValue(FACING));
            serverPlayer.displayClientMessage(result.message(), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    private static InteractionHand bottleHand(Player player, InteractionHand requested) {
        return !player.getItemInHand(requested).is(Items.GLASS_BOTTLE)
                && player.getOffhandItem().is(Items.GLASS_BOTTLE)
            ? InteractionHand.OFF_HAND : requested;
    }
    /** A short handle movement is emitted only by a committed physical pour. */
    public static void pulse(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof AleTapBlock)) return;
        level.setBlock(pos, state.setValue(POURING, true), 3);
        level.scheduleTick(pos, state.getBlock(), 6);
    }
    @Override protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (state.getValue(POURING)) level.setBlock(pos, state.setValue(POURING, false), 3);
    }
}
