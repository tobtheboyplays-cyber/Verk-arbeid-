package com.hearthstead.settlement.work;

import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * TRADER lane (owner, 26 Sep: "the Trader can have a shop"): the Trading Post's
 * counter, read from the real blocks the plaque already counts ("counter" =
 * cartography table or scaffolding).
 *
 * <p>A usable counter has a standable cell on BOTH faces of one horizontal
 * axis, at the same floor height, with air above the counter so the two can
 * see (and reach) each other across it. The Trader's side is the face nearer
 * the post's own storage (the goods live behind the counter); the visiting
 * merchant stands on the other face. When no counter qualifies the Trader
 * keeps the old behaviour and walks out to the merchant (villages without a
 * shop, or a counter built into a wall with no free face).
 *
 * <p>Read-only: it never places, breaks or reserves a block. Results are cached
 * per post for {@link #CACHE_TICKS} so a waiting Trader does not rescan every
 * tick.
 */
public final class TraderCounter {
    /** How long one scan result is reused. */
    static final long CACHE_TICKS = 40L;
    /** A counter further than this (blocks, horizontal) from every post container is not the post's. */
    static final int STORAGE_REACH = 8;

    /** One usable counter: the counter block and the two standing cells across it. */
    public record Spot(BlockPos counter, BlockPos traderCell, BlockPos merchantCell) {
        /** Direction from the Trader's cell toward the merchant's. */
        public Direction towardMerchant() {
            return Direction.getNearest(merchantCell.getX() - traderCell.getX(), 0,
                merchantCell.getZ() - traderCell.getZ());
        }

        public Vec3 traderStand() {
            return Vec3.atBottomCenterOf(traderCell);
        }

        public Vec3 merchantStand() {
            return Vec3.atBottomCenterOf(merchantCell);
        }

        /** Top centre of the counter block: where the goods are laid out. */
        public Vec3 counterTop() {
            return Vec3.atBottomCenterOf(counter.above());
        }
    }

    private record Cached(long tick, @Nullable Spot spot) {
    }

    private static final Map<Building, Cached> CACHE = new WeakHashMap<>();

    private TraderCounter() {
    }

    public static boolean isCounter(BlockState state) {
        return state.is(Blocks.CARTOGRAPHY_TABLE) || state.is(Blocks.SCAFFOLDING);
    }

    /** The post's usable counter, or null (then the Trader meets the merchant face to face). */
    @Nullable
    public static Spot find(ServerLevel level, Building post) {
        if (post == null || post.bounds == null || !post.valid) {
            return null;
        }
        long now = level.getGameTime();
        Cached cached = CACHE.get(post);
        if (cached != null && now - cached.tick() < CACHE_TICKS && now >= cached.tick()) {
            return cached.spot();
        }
        Spot spot = scan(level, post);
        CACHE.put(post, new Cached(now, spot));
        return spot;
    }

    /** Drops the cached scan (tests, or after the post was rebuilt). */
    public static void forget(Building post) {
        CACHE.remove(post);
    }

    private static Spot scan(ServerLevel level, Building post) {
        List<BlockPos> storage = WarehouseIndex.containers(level, post);
        var b = post.bounds;
        // The counter may sit IN the front wall line, one cell outside the
        // surveyed interior, so the box is widened by one horizontally.
        Spot best = null;
        double bestScore = Double.MAX_VALUE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = b.minY() - 1; y <= b.maxY(); y++) {
            for (int x = b.minX() - 1; x <= b.maxX() + 1; x++) {
                for (int z = b.minZ() - 1; z <= b.maxZ() + 1; z++) {
                    cursor.set(x, y, z);
                    if (!level.hasChunkAt(cursor) || !isCounter(level.getBlockState(cursor))) {
                        continue;
                    }
                    BlockPos counter = cursor.immutable();
                    Spot spot = spotAt(level, counter, storage);
                    if (spot == null) {
                        continue;
                    }
                    double score = nearest(spot.traderCell(), storage);
                    if (score < bestScore) {
                        bestScore = score;
                        best = spot;
                    }
                }
            }
        }
        return best;
    }

    /** The spot this counter block offers, or null when no axis has two free faces. */
    @Nullable
    static Spot spotAt(ServerLevel level, BlockPos counter, List<BlockPos> storage) {
        if (!clear(level, counter.above())) {
            return null;
        }
        Spot best = null;
        double bestScore = Double.MAX_VALUE;
        for (Direction axis : new Direction[] {Direction.NORTH, Direction.EAST}) {
            BlockPos a = counter.relative(axis);
            BlockPos c = counter.relative(axis.getOpposite());
            if (!standable(level, a) || !standable(level, c)) {
                continue;
            }
            double da = nearest(a, storage);
            double dc = nearest(c, storage);
            if (Math.min(da, dc) > (double) STORAGE_REACH * STORAGE_REACH) {
                continue;
            }
            boolean aIsTrader = chooseTraderSide(da, dc);
            Spot spot = aIsTrader ? new Spot(counter, a, c) : new Spot(counter, c, a);
            double score = Math.min(da, dc);
            if (score < bestScore) {
                bestScore = score;
                best = spot;
            }
        }
        return best;
    }

    /**
     * Pure rule, JUnit-tested: the Trader stands on the face nearer the
     * storage (squared distances); a tie keeps the first face.
     */
    static boolean chooseTraderSide(double firstFaceToStorageSq, double secondFaceToStorageSq) {
        return firstFaceToStorageSq <= secondFaceToStorageSq;
    }

    private static double nearest(BlockPos cell, List<BlockPos> storage) {
        double best = Double.MAX_VALUE;
        for (BlockPos pos : storage) {
            double dx = pos.getX() - cell.getX();
            double dz = pos.getZ() - cell.getZ();
            double dy = pos.getY() - cell.getY();
            best = Math.min(best, dx * dx + dz * dz + dy * dy * 4.0);
        }
        return best;
    }

    /** Two cells of room for a body, on something solid. */
    static boolean standable(ServerLevel level, BlockPos feet) {
        if (!level.hasChunkAt(feet) || !level.hasChunkAt(feet.above())) {
            return false;
        }
        BlockPos floor = feet.below();
        BlockState ground = level.getBlockState(floor);
        return !ground.getCollisionShape(level, floor).isEmpty()
            && ground.getFluidState().isEmpty()
            && clear(level, feet) && clear(level, feet.above());
    }

    private static boolean clear(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        return state.getBlock() instanceof CarpetBlock || state.getCollisionShape(level, pos).isEmpty();
    }

    /** Horizontal tolerance for "standing at" a counter cell. */
    public static final double AT_CELL = 0.75;

    /** The entity stands on this cell (horizontal distance to its centre, same floor). */
    public static boolean standingAt(net.minecraft.world.entity.Entity entity, BlockPos cell) {
        double dx = entity.getX() - (cell.getX() + 0.5);
        double dz = entity.getZ() - (cell.getZ() + 0.5);
        return dx * dx + dz * dz <= AT_CELL * AT_CELL && Math.abs(entity.getY() - cell.getY()) < 0.75;
    }

    /** Every counter block in the post's widened box (diagnostics / handbook facts). */
    public static List<BlockPos> counters(ServerLevel level, Building post) {
        List<BlockPos> out = new ArrayList<>();
        if (post == null || post.bounds == null) {
            return out;
        }
        var b = post.bounds;
        for (BlockPos pos : BlockPos.betweenClosed(b.minX() - 1, b.minY() - 1, b.minZ() - 1,
            b.maxX() + 1, b.maxY(), b.maxZ() + 1)) {
            if (level.hasChunkAt(pos) && isCounter(level.getBlockState(pos))) {
                out.add(pos.immutable());
            }
        }
        return out;
    }
}
