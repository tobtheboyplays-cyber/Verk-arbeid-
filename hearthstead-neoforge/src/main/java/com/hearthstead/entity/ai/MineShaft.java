package com.hearthstead.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;

/**
 * The walkable shape of a mine's dig area, so a Miner never cuts the pit it
 * cannot climb out of (soak 2026-09-25: the old layer-by-layer dig left a
 * 5x5 shaft with vertical walls; the miner then starved at the bottom).
 *
 * <p>The area is a square of columns around the mine anchor. Each column is
 * described by how many blocks have been cut out of it, counted down from
 * the layer just below the anchor. A Settler can step between neighbouring
 * columns whose floors differ by at most one block and that have head room;
 * a column is <i>reachable</i> when such steps connect it to an untouched,
 * open surface column. A cut is allowed only when it keeps every column that
 * was reachable before reachable afterwards, which by construction leaves a
 * staircase out of every pit.
 *
 * <p>The model half is plain arrays so it can be unit tested without a world.
 */
public final class MineShaft {
    /** Dig radius (columns either side of the anchor) — the Miner's REACH_OUT. */
    public static final int DIG_RADIUS = 6;
    /** Surveyed radius: one ring beyond the dig area supplies the exits. */
    public static final int RADIUS = DIG_RADIUS + 1;
    public static final int SIZE = 2 * RADIUS + 1;

    private MineShaft() {
    }

    /** One survey of the area. {@code depth} is the cut depth of each column. */
    public static final class Model {
        /** Y of the layer just below the anchor (cut depth 1 removes this layer). */
        public final int surfaceY;
        public final int[] depth = new int[SIZE * SIZE];
        /** The two cells above the surface layer are clear (room air, a door). */
        public final boolean[] open = new boolean[SIZE * SIZE];

        public Model(int surfaceY) {
            this.surfaceY = surfaceY;
        }

        public int feet(int column) {
            return surfaceY - depth[column] + 1;
        }

        boolean clear(int column, int y) {
            if (y <= surfaceY) {
                return y > surfaceY - depth[column];
            }
            if (y <= surfaceY + 2) {
                return open[column];
            }
            return true;
        }
    }

    public static int index(int dx, int dz) {
        return (dx + RADIUS) * SIZE + (dz + RADIUS);
    }

    public static int dx(int index) {
        return index / SIZE - RADIUS;
    }

    public static int dz(int index) {
        return index % SIZE - RADIUS;
    }

    public static boolean inDigArea(int index) {
        return Math.abs(dx(index)) <= DIG_RADIUS && Math.abs(dz(index)) <= DIG_RADIUS;
    }

    /** A settler (two blocks tall) can step between these adjacent columns. */
    static boolean canStep(Model m, int a, int b) {
        int fa = m.feet(a);
        int fb = m.feet(b);
        if (Math.abs(fa - fb) > 1) {
            return false;
        }
        int top = Math.max(fa, fb) + 1;
        for (int y = fa; y <= top; y++) {
            if (!m.clear(a, y)) {
                return false;
            }
        }
        for (int y = fb; y <= top; y++) {
            if (!m.clear(b, y)) {
                return false;
            }
        }
        return true;
    }

    /** Columns connected by legal steps to an open, uncut column. */
    public static boolean[] reachable(Model m) {
        boolean[] seen = new boolean[SIZE * SIZE];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < seen.length; i++) {
            if (m.depth[i] == 0 && m.open[i]) {
                seen[i] = true;
                queue.add(i);
            }
        }
        while (!queue.isEmpty()) {
            int a = queue.poll();
            int ax = a / SIZE;
            int az = a % SIZE;
            for (int d = 0; d < 4; d++) {
                int bx = ax + (d == 0 ? 1 : d == 1 ? -1 : 0);
                int bz = az + (d == 2 ? 1 : d == 3 ? -1 : 0);
                if (bx < 0 || bz < 0 || bx >= SIZE || bz >= SIZE) {
                    continue;
                }
                int b = bx * SIZE + bz;
                if (!seen[b] && canStep(m, a, b)) {
                    seen[b] = true;
                    queue.add(b);
                }
            }
        }
        return seen;
    }

    /**
     * Whether deepening {@code column} to {@code newDepth} keeps the column
     * itself and every column reachable in {@code before} reachable.
     */
    public static boolean safeToDeepen(Model m, int column, int newDepth, boolean[] before) {
        int old = m.depth[column];
        m.depth[column] = newDepth;
        boolean[] after = reachable(m);
        m.depth[column] = old;
        if (!after[column]) {
            return false;
        }
        for (int i = 0; i < before.length; i++) {
            if (before[i] && !after[i]) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------ world ---

    /** A settler can occupy this block (air, plants, an openable door). */
    public static boolean passable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof DoorBlock || state.getBlock() instanceof FenceGateBlock) {
            return true;
        }
        return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
    }

    /** A block the Miner may cut: stone-like, or loose cover over it. Never a container or bedrock. */
    public static boolean diggable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.hasBlockEntity() || state.getDestroySpeed(level, pos) < 0
            || !state.getFluidState().isEmpty()
            || state.getBlock().asItem() == net.minecraft.world.item.Items.AIR) {
            return false;
        }
        return state.is(BlockTags.MINEABLE_WITH_PICKAXE) || state.is(BlockTags.MINEABLE_WITH_SHOVEL);
    }

    /** Reads the area around {@code anchor}. Bounded: SIZE^2 x (maxDepth + 3) block reads, loaded chunks only. */
    public static Model survey(ServerLevel level, BlockPos anchor, int maxDepth) {
        Model m = new Model(anchor.getY() - 1);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int i = 0; i < SIZE * SIZE; i++) {
            int x = anchor.getX() + dx(i);
            int z = anchor.getZ() + dz(i);
            p.set(x, anchor.getY(), z);
            if (!level.isLoaded(p)) {
                m.open[i] = false;
                continue;
            }
            m.open[i] = passable(level, p) && passable(level, p.setY(anchor.getY() + 1));
            int d = 0;
            while (d <= maxDepth + 1 && passable(level, p.setY(m.surfaceY - d))) {
                d++;
            }
            m.depth[i] = d;
        }
        return m;
    }

    /** The cut depth this column would have after its top solid block is removed. */
    public static int depthAfterCut(ServerLevel level, Model m, BlockPos cut, int maxDepth) {
        int d = m.surfaceY - cut.getY() + 1;
        BlockPos.MutableBlockPos p = cut.mutable();
        while (d <= maxDepth + 1 && passable(level, p.move(0, -1, 0))) {
            d++;
        }
        return d;
    }

    /** Column index of a world position, or -1 outside the surveyed square. */
    public static int columnOf(BlockPos anchor, BlockPos pos) {
        int dx = pos.getX() - anchor.getX();
        int dz = pos.getZ() - anchor.getZ();
        if (Math.abs(dx) > RADIUS || Math.abs(dz) > RADIUS) {
            return -1;
        }
        return index(dx, dz);
    }
}
