package com.hearthstead.settlement.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What the Builder may clear on his own, and what only the player may give up.
 *
 * <p>MineColonies' worst habit is deleting whatever stands in a footprint --
 * signs, lanterns, a storage system. Here the rule is narrow and explicit:
 * natural terrain (soil, sand, gravel, natural stone, plants, snow, leaves,
 * fluids) is cleared automatically and its drops salvaged to the hut; any
 * other block is a PLAYER block and is never touched without the player's
 * explicit "allow overwrite" -- and a block with a block entity (a chest,
 * a furnace, a plaque) is never touched at all.
 */
public final class BuilderTerrain {

    private BuilderTerrain() {
    }

    /** Nothing there that needs removing: air, or a replaceable plant/snow layer. */
    public static boolean empty(BlockState state) {
        return state.isAir();
    }

    public static boolean fluid(BlockState state) {
        return state.getBlock() instanceof LiquidBlock || state.is(Blocks.BUBBLE_COLUMN);
    }

    /** Natural terrain the Builder clears without asking. */
    public static boolean natural(BlockState state) {
        if (state.isAir() || fluid(state)) {
            return true;
        }
        if (state.hasBlockEntity()) {
            return false;
        }
        return state.canBeReplaced()
            || state.is(BlockTags.DIRT) || state.is(BlockTags.SAND)
            || state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY)
            || state.is(BlockTags.BASE_STONE_OVERWORLD)
            || state.is(BlockTags.LEAVES) || state.is(BlockTags.FLOWERS)
            || state.is(BlockTags.SAPLINGS) || state.is(BlockTags.SNOW)
            || state.is(Blocks.ICE) || state.is(Blocks.SNOW_BLOCK)
            || state.is(Blocks.MUD) || state.is(Blocks.MOSS_BLOCK)
            || state.is(Blocks.SUGAR_CANE) || state.is(Blocks.CACTUS)
            || state.is(Blocks.PUMPKIN) || state.is(Blocks.MELON)
            || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.BAMBOO)
            || state.is(BlockTags.COAL_ORES) || state.is(BlockTags.COPPER_ORES)
            || state.is(BlockTags.IRON_ORES)
            || state.is(Blocks.DIRT_PATH) || state.is(Blocks.FARMLAND);
    }

    /** A block only the player may give up (and only when not a block entity). */
    public static boolean playerBlock(BlockState state) {
        return !natural(state);
    }

    /** Whether "allow overwrite" can ever remove it (block entities: never). */
    public static boolean overwritable(ServerLevel level, BlockPos pos, BlockState state) {
        return !state.hasBlockEntity() && level.getBlockEntity(pos) == null
            && state.getDestroySpeed(level, pos) >= 0.0F;
    }

    /** Solid ground a foundation may rest on. */
    public static boolean supports(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && !fluid(state) && !state.canBeReplaced()
            && state.isFaceSturdy(level, pos, net.minecraft.core.Direction.UP);
    }
}
