package com.hearthstead.block;

import com.hearthstead.building.BuildingType;
import com.hearthstead.building.PlaqueState;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.util.StringRepresentable;

import javax.annotation.Nullable;

/**
 * The wall-hung plaque that declares a building.
 *
 * <p>The block itself is deliberately thin logic: it holds facing and a
 * synced {@link Glow} so the world can show red/amber/green at a glance, and
 * defers every decision to {@link PlaqueBlockEntity}. Survey results, links
 * and occupancy are server truth and live in the settlement.
 */
public class PlaqueBlock extends BaseEntityBlock {

    public static final MapCodec<PlaqueBlock> CODEC = simpleCodec(PlaqueBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<Glow> GLOW = EnumProperty.create("glow", Glow.class);

    /**
     * What the plaque signals across the village square. Amber specifically
     * means "some of it is there" — the difference between a player who knows
     * to add the second lantern and one who is guessing.
     */
    public enum Glow implements StringRepresentable {
        RED, AMBER, GREEN;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public static Glow forState(PlaqueState state, boolean anyProgress) {
            if (state == PlaqueState.LINKED) {
                return GREEN;
            }
            return anyProgress ? AMBER : RED;
        }
    }

    private static final VoxelShape NORTH = Block.box(2, 2, 14, 14, 14, 16);
    private static final VoxelShape SOUTH = Block.box(2, 2, 0, 14, 14, 2);
    private static final VoxelShape WEST = Block.box(14, 2, 2, 16, 14, 14);
    private static final VoxelShape EAST = Block.box(0, 2, 2, 2, 14, 14);

    public PlaqueBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
            .setValue(FACING, Direction.NORTH)
            .setValue(GLOW, Glow.RED));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, GLOW);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                               CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            case EAST -> EAST;
            default -> NORTH;
        };
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction face = context.getClickedFace();
        if (face.getAxis().isVertical()) {
            return null; // a plaque hangs on a wall, not on floors or ceilings
        }
        BlockState state = defaultBlockState().setValue(FACING, face);
        return state.canSurvive(context.getLevel(), context.getClickedPos()) ? state : null;
    }

    /** A plaque needs the wall behind it; losing that wall drops it. */
    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction facing = state.getValue(FACING);
        BlockPos support = pos.relative(facing.getOpposite());
        return level.getBlockState(support).isFaceSturdy(level, support, facing);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction,
                                     BlockState neighbour, LevelAccessor level,
                                     BlockPos pos, BlockPos neighbourPos) {
        if (direction == state.getValue(FACING).getOpposite()
            && !state.canSurvive(level, pos)) {
            return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        }
        return state;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable net.minecraft.world.entity.LivingEntity placer,
                            ItemStack stack) {
        if (level.isClientSide
            || !(level.getBlockEntity(pos) instanceof PlaqueBlockEntity plaque)) {
            return;
        }
        plaque.setType(BuildingType.byId(PlaqueItemData.typeOf(stack)));
        plaque.survey((ServerLevel) level);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof PlaqueBlockEntity plaque)
            || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        // Re-survey on open so the player always sees the room as it is now,
        // not as it was when the last block changed.
        plaque.survey((ServerLevel) level);
        plaque.openScreen(serverPlayer);
        return InteractionResult.CONSUME;
    }

    /** Breaking the plaque dissolves the building it declared. */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state,
                                        Player player) {
        if (!level.isClientSide
            && level.getBlockEntity(pos) instanceof PlaqueBlockEntity plaque) {
            plaque.dissolveBuilding((ServerLevel) level, player);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PlaqueBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                 BlockEntityType<T> type) {
        return level.isClientSide ? null
            : createTickerHelper(type, com.hearthstead.registry.ModBlockEntities.PLAQUE.get(),
                PlaqueBlockEntity::serverTick);
    }
}
