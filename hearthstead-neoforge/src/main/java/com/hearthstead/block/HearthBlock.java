package com.hearthstead.block;

import com.hearthstead.network.BlessingNetwork;
import com.hearthstead.registry.ModBlockEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import com.mojang.serialization.MapCodec;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.Map;

/**
 * The settlement Banner (registry id {@code hearthstead:hearth}, kept for
 * saves). The block itself is the low ledger stand players right-click; the
 * pole, crossbar and cloth rising behind it are drawn by
 * {@code SettlementBannerRenderer} and need two clear blocks above.
 */
public class HearthBlock extends BaseEntityBlock {
    public static final MapCodec<HearthBlock> CODEC = simpleCodec(HearthBlock::new);

    /** The way the stand's front (ledger side) faces; the pole stands behind. */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** Blocks above the stand that the pole and cloth occupy. */
    public static final int CLEARANCE = 2;

    /** Stand boxes authored facing north (counter at the front, pole back left). */
    private static final double[][] STAND_BOXES = {
        {0.5, 0, 0.5, 11.5, 11.5, 10},   // ledger counter
        {6.5, 11.5, 2.5, 10.5, 12.75, 7}, // ledger
        {1, 11.5, 4, 3.5, 16, 6.5},       // lantern
        {10.5, 0, 8.5, 16, 6.5, 15},     // stepped plinth
        {11.5, 6.5, 9.5, 15.5, 16, 13.5}, // pole
    };
    private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Direction.class);

    static {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            VoxelShape shape = Shapes.empty();
            for (double[] b : STAND_BOXES) {
                shape = Shapes.or(shape, rotatedBox(facing, b));
            }
            SHAPES.put(facing, shape.optimize());
        }
    }

    private static VoxelShape rotatedBox(Direction facing, double[] b) {
        double x1 = b[0], y1 = b[1], z1 = b[2], x2 = b[3], y2 = b[4], z2 = b[5];
        return switch (facing) {
            case SOUTH -> box(16 - x2, y1, 16 - z2, 16 - x1, y2, 16 - z1);
            case EAST -> box(16 - z2, y1, x1, 16 - z1, y2, x2);
            case WEST -> box(z1, y1, 16 - x2, z2, y2, 16 - x1);
            default -> box(x1, y1, z1, x2, y2, z2);
        };
    }

    public HearthBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /** True when the pole and cloth have room: {@link #CLEARANCE} replaceable blocks above. */
    public static boolean hasClearance(Level level, BlockPos pos) {
        for (int dy = 1; dy <= CLEARANCE; dy++) {
            BlockPos above = pos.above(dy);
            if (above.getY() >= level.getMaxBuildHeight()
                || !level.getBlockState(above).canBeReplaced()) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        if (!hasClearance(context.getLevel(), context.getClickedPos())) {
            return null;
        }
        // The stand faces whoever places it; the banner rises behind.
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide) {
            level.playSound(null, pos.above(), SettlementBannerInteraction.raiseSound(),
                SoundSource.BLOCKS, 0.9F, 1.0F);
            // The placer designs the banner; founding still happens on the tick.
            if (placer instanceof ServerPlayer player
                && level.getBlockEntity(pos) instanceof HearthBlockEntity hearth) {
                com.hearthstead.heraldry.BannerDesignNetwork.offerOnPlacement(player, hearth);
            }
        }
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                                  CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
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
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                              BlockPos pos, Player player, InteractionHand hand,
                                              BlockHitResult hit) {
        if (!SettlementHeraldry.isBanner(stack)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide) {
            return ItemInteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer
            && level.getBlockEntity(pos) instanceof HearthBlockEntity hearth) {
            SettlementBannerInteraction.hangColours(serverPlayer, hand, hearth);
        }
        return ItemInteractionResult.CONSUME;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer
            && level instanceof ServerLevel serverLevel
            && level.getBlockEntity(pos) instanceof HearthBlockEntity hearth) {
            openFromUse(serverPlayer, serverLevel, hearth);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * What a click on the Banner opens: the stand block, and (QA U9) its pole
     * and cloth through {@code BannerOpenPayload}.
     */
    public static void openFromUse(ServerPlayer serverPlayer, ServerLevel serverLevel, HearthBlockEntity hearth) {
            // A fresh Banner founds on first use, so its menu opens with the real identity
            // rather than NO_SETTLEMENT, which the Tech Tree would refuse (QA-UI-03).
            hearth.foundNow(serverLevel);
            Settlement settlement = hearth.getSettlementId() == null ? null
                : SettlementManager.byId(serverLevel, hearth.getSettlementId());
            // Town chat: whoever uses the Banner is a member and hears the
            // settlement's news (raids, deaths, buildings, research, trade).
            if (settlement != null && settlement.addMember(serverPlayer.getUUID())) {
                com.hearthstead.settlement.SettlementSavedData.get(serverLevel).setDirty();
            }
            // A reward is a deliberate Hearth interaction, never a combat
            // modal. Sneak-use remains an explicit storage bypass, so an
            // offer the player wants to consider later cannot lock them out
            // of communal goods.
            if (!serverPlayer.isShiftKeyDown()
                && BlessingNetwork.openFor(serverPlayer, settlement)) {
                return;
            }
            openMenu(serverPlayer, hearth);
    }

    /** Opens the Banner menu with the Banner's current identity (also used to refresh a stale one). */
    public static void openMenu(ServerPlayer player, HearthBlockEntity hearth) {
        BlockPos pos = hearth.getBlockPos();
        player.openMenu(hearth, buf -> {
            buf.writeBlockPos(pos);
            buf.writeUUID(hearth.getSettlementId() == null
                ? com.hearthstead.menu.HearthMenu.NO_SETTLEMENT
                : hearth.getSettlementId());
            buf.writeUtf(hearth.settlementNameForMenu());
        });
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
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
        // No fire or smoke: the Banner's only ambience is its cloth in the
        // wind, a little more often in rain. The lantern's light is the
        // block's own light level.
        int odds = level.isRaining() ? 40 : 60;
        if (random.nextInt(odds) == 0) {
            level.playLocalSound(pos.getX() + 0.5, pos.getY() + 1.8, pos.getZ() + 0.5,
                SettlementBannerInteraction.flutterSound(), SoundSource.BLOCKS,
                0.35F + random.nextFloat() * 0.25F, 0.9F + random.nextFloat() * 0.2F, false);
        }
    }
}
