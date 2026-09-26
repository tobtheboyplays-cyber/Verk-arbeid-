package com.hearthstead.block;

import com.hearthstead.building.PlaqueState;
import com.hearthstead.item.BlessingSealItem;
import com.hearthstead.item.BuildPlanItem;
import com.hearthstead.item.WorkScepterItem;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.development.Development;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
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
 * synced {@link Glow} so the world can show red/amber/green (or dark, while
 * blank) at a glance, and defers every decision to {@link PlaqueBlockEntity}.
 * Survey results, links and occupancy are server truth and live in the
 * settlement.
 *
 * <p>D-006: a plaque is placed blank ({@link PlaqueState#EMPTY}) and does
 * nothing until a Build Plan item is fitted into it. {@link #useItemOn} fits
 * one; {@link #useWithoutItem} opens the screen once a plan is fitted, or
 * extracts it on an empty-hand sneak-use.
 */
public class PlaqueBlock extends BaseEntityBlock {

    public static final MapCodec<PlaqueBlock> CODEC = simpleCodec(PlaqueBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<Glow> GLOW = EnumProperty.create("glow", Glow.class);
    /** Registration truth is independent from the multiplexed lamp colour. */
    public static final BooleanProperty REGISTERED = BooleanProperty.create("registered");

    /**
     * What the plaque signals across the village square. Amber specifically
     * means "some of it is there" — the difference between a player who knows
     * to add the second lantern and one who is guessing. {@code EMPTY} is a
     * dark, unlit lamp: a blank board must never glow a warning colour at a
     * player who simply has not slipped a plan in yet (W6 refined).
     */
    public enum Glow implements StringRepresentable {
        EMPTY, RED, AMBER, GREEN;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public static Glow forState(PlaqueState state, boolean anyProgress) {
            if (state == PlaqueState.EMPTY) {
                return EMPTY;
            }
            if (state == PlaqueState.LINKED_VALID) {
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
            .setValue(GLOW, Glow.EMPTY)
            .setValue(REGISTERED, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, GLOW, REGISTERED);
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

    /**
     * Fits a Build Plan when the plaque is blank (D-006, W4). A sneaking
     * Blessing Seal explicitly skips the plaque's default empty-hand path so
     * Minecraft continues to {@link BlessingSealItem#useOn}; otherwise the
     * plaque would open its sheet and consume the click before the physical
     * seal can bind. Other items fall through to {@link #useWithoutItem}.
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                              BlockPos pos, Player player, InteractionHand hand,
                                              BlockHitResult hit) {
        // Work Scepter selection is routed from the client right-click action.
        // Consume any late fallback before the normal plaque sheet can open.
        if (stack.getItem() instanceof WorkScepterItem) {
            return level.isClientSide
                ? ItemInteractionResult.SUCCESS : ItemInteractionResult.CONSUME;
        }
        if (player.isShiftKeyDown()
            && stack.getItem() instanceof BlessingSealItem) {
            return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        }
        if (player.isShiftKeyDown() && hand == InteractionHand.MAIN_HAND
            && player.getOffhandItem().getItem()
                instanceof BlessingSealItem offhandSeal) {
            if (level.getBlockEntity(pos) instanceof PlaqueBlockEntity plaque) {
                offhandSeal.bindToPlaque(player.getOffhandItem(), player, plaque);
                return level.isClientSide
                    ? ItemInteractionResult.SUCCESS : ItemInteractionResult.CONSUME;
            }
            return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide) {
            return ItemInteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof PlaqueBlockEntity plaque)
            || !(player instanceof ServerPlayer serverPlayer)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (plaque.state() != PlaqueState.EMPTY || !(stack.getItem() instanceof BuildPlanItem)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        // Resolve through the plaque's own deterministic city authority.
        // A new plan chooses the nearest registered same-dimension city and
        // persists that UUID; an already fitted plan reuses its UUID.  Using
        // SettlementManager.at here would retain the old radius gate and
        // could select an arbitrary overlapping settlement by map iteration.
        // One resolver therefore owns both the knowledge check and link.
        Settlement settlement = plaque.settlementFor((ServerLevel) level);
        com.hearthstead.building.BuildingType planned = PlaqueItemData.buildingType(stack);
        if (settlement == null
            || !Development.isBuildingUnlocked((ServerLevel) level, settlement, planned)) {
            // Name the tech node that opens this plan, when there is one.
            Component source = settlement == null ? null
                : com.hearthstead.settlement.techtree.effects.CommonsEffects.planSource(planned);
            serverPlayer.displayClientMessage(source != null
                ? Component.translatableWithFallback("hearthstead.development.plan_locked_node",
                    "%s needs the tech node %s. Open Tech Tree at the Banner and learn it first.",
                    planned.displayName(), source)
                : Component.translatable("hearthstead.development.plan_locked", planned.displayName()), true);
            return ItemInteractionResult.CONSUME;
        }
        if (!plaque.insertPlan((ServerLevel) level, stack.copyWithCount(1))) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        plaque.openScreen(serverPlayer);
        return ItemInteractionResult.CONSUME;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        // Empty-main-hand is evaluated before offhand. Do not extract a plan
        // or open the sheet while a sneaking offhand seal is waiting for its
        // own item pass.
        if (player.isShiftKeyDown()
            && player.getOffhandItem().getItem()
                instanceof BlessingSealItem offhandSeal) {
            if (level.getBlockEntity(pos) instanceof PlaqueBlockEntity plaque) {
                return offhandSeal.bindToPlaque(player.getOffhandItem(), player,
                    plaque);
            }
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof PlaqueBlockEntity plaque)
            || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        // D-006: a blank plaque opens no screen at all — just a hint of what
        // it is waiting for, said once per click.
        if (plaque.state() == PlaqueState.EMPTY) {
            serverPlayer.displayClientMessage(
                Component.translatable("hearthstead.plaque.needs_plan"), true);
            return InteractionResult.CONSUME;
        }
        // Sneak-use with an empty hand pulls the fitted plan back out (W5),
        // returning the exact item and dissolving whatever it declared.
        if (player.isSecondaryUseActive() && player.getMainHandItem().isEmpty()) {
            ItemStack extracted = plaque.extractPlan((ServerLevel) level, player);
            if (!extracted.isEmpty()) {
                giveBack(player, extracted);
            }
            return InteractionResult.CONSUME;
        }
        // Re-survey on open so the player always sees the room as it is now,
        // not as it was when the last block changed.
        plaque.survey((ServerLevel) level);
        plaque.openScreen(serverPlayer);
        return InteractionResult.CONSUME;
    }

    /** Puts an extracted plan back in the player's hand, or their pack, or the ground. */
    private static void giveBack(Player player, ItemStack stack) {
        if (player.getMainHandItem().isEmpty()) {
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        } else if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    /**
     * Breaking the plaque dissolves the building it declared, and returns any
     * fitted plan to the ground rather than voiding it (INV-3).
     */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state,
                                        Player player) {
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level,
                                       BlockPos pos, Player player,
                                       boolean willHarvest,
                                       FluidState fluid) {
        ItemStack plan = ItemStack.EMPTY;
        if (!level.isClientSide
            && level.getBlockEntity(pos) instanceof PlaqueBlockEntity plaque) {
            plan = plaque.insertedPlan();
            if (!plaque.dissolveBuilding((ServerLevel) level, player)) {
                return false;
            }
        }
        boolean removed = super.onDestroyedByPlayer(state, level, pos, player,
            willHarvest, fluid);
        if (removed && !level.isClientSide && !plan.isEmpty()) {
            com.hearthstead.util.ItemSpill.conserve((net.minecraft.server.level.ServerLevel) level, pos, plan);
        }
        return removed;
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
