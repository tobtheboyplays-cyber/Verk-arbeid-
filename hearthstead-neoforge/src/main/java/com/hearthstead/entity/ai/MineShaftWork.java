package com.hearthstead.entity.ai;

import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.TownChat;
import com.hearthstead.settlement.builder.BuilderStock;
import com.hearthstead.settlement.builder.BuilderSupply;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The world side of the MINE V2 ladder shaft ({@link MineShaftPlan}): finds
 * the shaft ladder of a Mine, reads blocks for the planner, and handles the
 * Mine's stock of ladders, torches and filler stone.
 */
public final class MineShaftWork {
    /** Plain stone the Miner may place to seal fluid or fill a hole. */
    private static final Set<Item> FILL = Set.of(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.STONE,
        Items.DEEPSLATE, Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.TUFF, Items.DIRT);
    /** "Needs Ladders" at most this often per Mine (ticks). */
    private static final long NEEDS_LINE_INTERVAL = 6_000L;
    private static final Map<UUID, Long> LAST_NEEDS_LINE = new ConcurrentHashMap<>();

    private MineShaftWork() {
    }

    // ------------------------------------------------------------ site ---

    /**
     * The shaft ladder of this Mine: a ladder at ground level (a settler can
     * stand beside it on a real floor), with open air in front of it and rock
     * (or the shaft already dug) under both. Several candidates: the highest
     * rung of each column, nearest the plaque. Null for a Mine without one
     * (an old or player-built mine), which then uses the quarry.
     */
    @Nullable
    public static MineShaftPlan.Site find(ServerLevel level, Building mine) {
        if (mine == null || mine.anchor == null) {
            return null;
        }
        BoundingBox b = mine.bounds;
        BlockPos a = mine.anchor;
        int x0 = b != null ? b.minX() - 2 : a.getX() - 8;
        int x1 = b != null ? b.maxX() + 2 : a.getX() + 8;
        int z0 = b != null ? b.minZ() - 2 : a.getZ() - 8;
        int z1 = b != null ? b.maxZ() + 2 : a.getZ() + 8;
        int y0 = (b != null ? b.minY() : a.getY()) - 4;
        int y1 = (b != null ? Math.min(b.maxY(), b.minY() + 6) : a.getY() + 3);
        if ((long) (x1 - x0 + 1) * (z1 - z0 + 1) * (y1 - y0 + 1) > 12_000) {
            return null; // bounded: never a scan of a huge yard
        }
        MineShaftPlan.Site best = null;
        long bestScore = Long.MAX_VALUE;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                // highest qualifying rung of this column
                for (int y = y1; y >= y0; y--) {
                    p.set(x, y, z);
                    if (!level.isLoaded(p)) {
                        break;
                    }
                    BlockState s = level.getBlockState(p);
                    if (!s.is(Blocks.LADDER)) {
                        continue;
                    }
                    Direction facing = s.getValue(LadderBlock.FACING);
                    BlockPos m = p.immutable();
                    if (!mouth(level, m, facing)) {
                        continue;
                    }
                    long score = (long) Math.abs(x - a.getX()) + Math.abs(z - a.getZ());
                    if (score < bestScore) {
                        bestScore = score;
                        best = new MineShaftPlan.Site(m, facing);
                    }
                    break;
                }
            }
        }
        return best;
    }

    private static boolean mouth(ServerLevel level, BlockPos m, Direction facing) {
        BlockPos front = m.relative(facing);
        if (cell(level, front) != MineShaftPlan.Cell.OPEN) {
            return false;
        }
        MineShaftPlan.Cell underL = cell(level, m.below());
        MineShaftPlan.Cell underD = cell(level, front.below());
        if (!(underL == MineShaftPlan.Cell.ROCK || underL == MineShaftPlan.Cell.LADDER)
            || !(underD == MineShaftPlan.Cell.ROCK || underD == MineShaftPlan.Cell.OPEN
                || underD == MineShaftPlan.Cell.LADDER)) {
            return false;
        }
        // A real floor beside the ladder, not the wall and not the shaft's own
        // dig column (a hole once digging starts), so the answer stays the
        // same while the shaft goes down.
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (d == facing.getOpposite() || d == facing) {
                continue;
            }
            BlockPos n = m.relative(d);
            if (standable(level, n)) {
                return true;
            }
        }
        return false;
    }

    /** Two clear cells on a real floor (not a ladder). */
    public static boolean standable(ServerLevel level, BlockPos n) {
        if (!MineShaft.passable(level, n) || !MineShaft.passable(level, n.above())) {
            return false;
        }
        BlockState floor = level.getBlockState(n.below());
        return !floor.is(Blocks.LADDER) && !floor.getCollisionShape(level, n.below()).isEmpty();
    }

    /** A ground cell beside the mouth to climb out onto (not the open shaft). */
    @Nullable
    public static BlockPos exit(ServerLevel level, MineShaftPlan.Site site) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (d == site.facing().getOpposite() || d == site.facing()) {
                continue;
            }
            BlockPos n = site.mouth().relative(d);
            if (standable(level, n)) {
                return n;
            }
        }
        return null;
    }

    // ----------------------------------------------------------- probe ---

    public static MineShaftPlan.Cell cell(ServerLevel level, BlockPos p) {
        if (!level.isLoaded(p) || p.getY() <= level.getMinBuildHeight()) {
            return MineShaftPlan.Cell.HARD;
        }
        BlockState s = level.getBlockState(p);
        if (s.is(Blocks.LADDER)) {
            return MineShaftPlan.Cell.LADDER;
        }
        if (!s.getFluidState().isEmpty()) {
            return s.getBlock() instanceof LiquidBlock || s.canBeReplaced()
                ? MineShaftPlan.Cell.FLUID : MineShaftPlan.Cell.WET;
        }
        if (MineShaft.passable(level, p)) {
            return MineShaftPlan.Cell.OPEN;
        }
        return MineShaft.diggable(level, p) ? MineShaftPlan.Cell.ROCK : MineShaftPlan.Cell.HARD;
    }

    public static MineShaftPlan.Probe probe(ServerLevel level, Settlement settlement, Building mine) {
        int limit = floorLimit(level) - 1;
        return new MineShaftPlan.Probe() {
            @Override
            public MineShaftPlan.Cell at(BlockPos pos) {
                return cell(level, pos);
            }

            @Override
            public boolean mayDig(BlockPos pos) {
                if (pos.getY() < limit || settlement == null || settlement.center == null
                    || !settlement.inside(pos)) {
                    return false;
                }
                for (Building other : settlement.buildings) {
                    BoundingBox ob = other.bounds;
                    if (other == mine || ob == null || !other.valid) {
                        continue;
                    }
                    if (pos.getX() >= ob.minX() && pos.getX() <= ob.maxX()
                        && pos.getZ() >= ob.minZ() && pos.getZ() <= ob.maxZ()) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public boolean torch(BlockPos pos) {
                return level.getBlockState(pos).is(Blocks.TORCH);
            }
        };
    }

    /** Level-1 lane floor (feet level). */
    public static int floorOne(ServerLevel level, MineShaftPlan.Site site) {
        return Math.max(site.mouth().getY() - MineConfig.levelOneDepth(), floorLimit(level));
    }

    /** The lowest lane floor allowed: [mine] minY, and well clear of bedrock. */
    public static int floorLimit(ServerLevel level) {
        return Math.max(MineConfig.minY(), level.getMinBuildHeight() + 5);
    }

    /** Level-2 lane floor, or null without the Deep Mine tech (or no room above the depth limit). */
    @Nullable
    public static Integer floorTwo(ServerLevel level, Settlement settlement, MineShaftPlan.Site site) {
        if (!deepMine(level, settlement)) {
            return null;
        }
        int limit = floorLimit(level);
        int one = floorOne(level, site);
        int two = Math.max(one - MineConfig.levelTwoDepth(), limit);
        return two < one ? two : null;
    }

    /** Deep Mine learned: the same bonus source that deepens the quarry (CraftEffects.mineReach). */
    public static boolean deepMine(ServerLevel level, @Nullable Settlement settlement) {
        return com.hearthstead.settlement.techtree.effects.CraftEffects.mineReach(level, settlement, 0) > 0;
    }

    // ----------------------------------------------------------- stock ---

    public static int count(List<Container> containers, Item item) {
        return BuilderStock.count(containers, item);
    }

    /** Removes one {@code item} from the Mine's chests; false if there is none. */
    public static boolean takeOne(List<Container> containers, Item item) {
        for (Container c : containers) {
            for (int slot = 0; slot < c.getContainerSize(); slot++) {
                ItemStack stack = c.getItem(slot);
                if (!stack.isEmpty() && stack.is(item) && stack.getComponentsPatch().isEmpty()) {
                    stack.shrink(1);
                    if (stack.isEmpty()) {
                        c.setItem(slot, ItemStack.EMPTY);
                    }
                    c.setChanged();
                    return true;
                }
            }
        }
        return false;
    }

    /** Takes one plain stone block for sealing or filling; its state, or null if none. */
    @Nullable
    public static BlockState takeFill(List<Container> containers) {
        for (Item item : FILL) {
            if (count(containers, item) > 0 && takeOne(containers, item) && item instanceof BlockItem block) {
                return block.getBlock().defaultBlockState();
            }
        }
        return null;
    }

    public static boolean hasFill(List<Container> containers) {
        for (Item item : FILL) {
            if (count(containers, item) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * A ladder is in the Mine's chests, else the village's own ways are tried:
     * ladders made at the Mine from planks in its chests (the Builder's rule),
     * then a Courier request to the warehouse. Still none: a rate-limited
     * "needs Ladders" line in town chat, and the Miner waits (he never digs
     * without a ladder above him).
     */
    public static boolean ladderReady(ServerLevel level, Settlement settlement, Building mine,
                                      List<Container> containers, SettlerEntity miner) {
        if (count(containers, Items.LADDER) > 0) {
            return true;
        }
        if (BuilderStock.craftLadders(containers, 3) > 0 && count(containers, Items.LADDER) > 0) {
            return true;
        }
        BuilderSupply.requestItem(level, settlement, mine, Items.LADDER, 8);
        needsLine(level, settlement, mine, miner, "hearthstead.mine.needs_ladders");
        return false;
    }

    /** Filler stone for sealing fluid: the Mine's chests, else a request. */
    public static boolean fillReady(ServerLevel level, Settlement settlement, Building mine,
                                    List<Container> containers, SettlerEntity miner) {
        if (hasFill(containers)) {
            return true;
        }
        BuilderSupply.requestItem(level, settlement, mine, Items.COBBLESTONE, 8);
        needsLine(level, settlement, mine, miner, "hearthstead.mine.needs_cobblestone");
        return false;
    }

    private static void needsLine(ServerLevel level, Settlement settlement, Building mine,
                                  SettlerEntity miner, String key) {
        long now = level.getGameTime();
        Long last = LAST_NEEDS_LINE.get(mine.id);
        if (last != null && now - last < NEEDS_LINE_INTERVAL && now >= last) {
            return;
        }
        LAST_NEEDS_LINE.put(mine.id, now);
        TownChat.send(level, settlement, TownChat.Kind.BUILDING,
            Component.translatable(key, miner.getSettlerName()));
    }

    /** GameTest hook: forget the chat rate limit. */
    public static void resetNeedsLinesForTests() {
        LAST_NEEDS_LINE.clear();
    }
}
