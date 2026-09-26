package com.hearthstead.entity.ai;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;

import java.util.EnumSet;
import java.util.List;

/**
 * Gets a Miner out of a pit it can no longer walk out of.
 *
 * <p>{@link MinerWorkGoal} now only cuts staircases ({@link MineShaft}), but
 * a mine dug by an older build, a gravel slide or a player's own digging can
 * still leave a Miner at the bottom of a vertical shaft, where it used to
 * starve and sleep rough forever (soak 2026-09-25). This goal carves steps
 * out of the pit wall toward the nearest exit: each step removes the two
 * blocks above the next tread, exactly as a player would climb out. Every cut
 * block is real mining: its drops go into the mine's containers, or onto the
 * floor, never into the void.
 *
 * <p>It outranks eating and sleep because neither can happen from inside the
 * pit, and it never runs for a Miner who can already walk out.
 */
public class MinerEscapeGoal extends Goal {
    private static final int CHECK_INTERVAL = 100;
    private static final int TICKS_PER_CUT = 40;
    private static final int GIVE_UP_TICKS = 2_400;
    /** Ticks to walk the last stretch out once the pit is no longer a trap. */
    private static final int WALK_OUT_TICKS = 300;
    private static final double STEP_SPEED = 0.9;

    private final SettlerEntity settler;
    private int cooldown;
    private Building mine;
    private BlockPos cut;
    private BlockPos stepTo;
    private int cutTicks;
    private int runTicks;
    private int stepTicks;
    private boolean walkingOut;
    private static final int ROUTE_SEARCH_NODES = 6_000;
    private int walkTicks;
    /** Why the last trappedIn() answered as it did (diagnostics). */
    private String lastCheck = "none";

    public MinerEscapeGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (settler.getProfession() != Profession.MINER || !settler.isBound()
            || settler.getTarget() != null) {
            return false;
        }
        if (--cooldown > 0) {
            return false;
        }
        cooldown = CHECK_INTERVAL;
        mine = trappedIn();
        return mine != null;
    }

    /** The Miner's own mine when it stands in that mine's pit with no way out. */
    private Building trappedIn() {
        if (!(settler.level() instanceof ServerLevel level)) {
            return null;
        }
        Settlement settlement = settler.settlement();
        Building building = settlement == null ? null
            : Employment.employerOf(settlement, settler.getUUID());
        if (building == null || !building.valid || building.anchor == null) {
            lastCheck = "no_mine";
            return null;
        }
        BlockPos anchor = building.anchor;
        BlockPos feet = settler.blockPosition();
        int column = MineShaft.columnOf(anchor, feet);
        if (column < 0 || feet.getY() >= anchor.getY()) {
            lastCheck = "outside anchor=" + anchor.toShortString() + " feet=" + feet.toShortString();
            return null;
        }
        MineShaft.Model model = MineShaft.survey(level, anchor, 24);
        boolean[] reach = MineShaft.reachable(model);
        if (reach[column] && model.feet(column) == feet.getY()) {
            lastCheck = "model_reachable feet=" + feet.toShortString();
            return null;
        }
        // The model is conservative; ask the real pathfinder before digging.
        Path out = routeOut(anchor);
        if (out != null) {
            lastCheck = "path_reaches end=" + out.getEndNode();
            return null;
        }
        lastCheck = "trapped feet=" + feet.toShortString();
        return building;
    }

    @Override
    public boolean canContinueToUse() {
        return mine != null && runTicks < GIVE_UP_TICKS && settler.getTarget() == null
            && settler.blockPosition().getY() < mine.anchor.getY();
    }

    @Override
    public void start() {
        cut = null;
        stepTo = null;
        cutTicks = 0;
        runTicks = 0;
        stepTicks = 0;
        walkingOut = false;
        walkTicks = 0;
        settler.getNavigation().stop();
        settler.recordRouteFailure("miner_trapped_carving_steps");
    }

    @Override
    public void stop() {
        settler.setActivity(SettlerActivity.IDLE);
        if (runTicks >= GIVE_UP_TICKS) {
            settler.recordRouteFailure("miner_escape_gave_up");
        }
        mine = null;
        cut = null;
        stepTo = null;
        walkingOut = false;
        cooldown = CHECK_INTERVAL;
    }

    /** For test messages and live inspection. */
    public String debugState() {
        return "mine=" + (mine != null) + " cut=" + cut + " stepTo=" + stepTo + " walkingOut=" + walkingOut
            + " run=" + runTicks + " step=" + stepTicks + " walk=" + walkTicks + " check=" + lastCheck;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (mine == null || !(settler.level() instanceof ServerLevel level)) {
            return;
        }
        runTicks++;
        if (walkingOut) {
            walkOut();
            return;
        }
        if (stepTo != null) {
            // Onto the tread just carved: one block over and one up, so the body
            // is driven straight at it every tick. A route search here could
            // return nothing (asked mid-jump, or satisfied one block short) and
            // leave the Miner standing at the bottom of its own stair.
            settler.getMoveControl().setWantedPosition(stepTo.getX() + 0.5D, stepTo.getY(),
                stepTo.getZ() + 0.5D, STEP_SPEED);
            boolean arrived = settler.onGround() && settler.blockPosition().equals(stepTo);
            if (arrived || ++stepTicks > 60) {
                stepTo = null;
                stepTicks = 0;
                // Out of the trap: walk the rest of the way up, else carve the next step.
                if (trappedIn() == null) {
                    walkingOut = true;
                    walkTicks = 0;
                }
            }
            return;
        }
        if (cut == null) {
            planStep(level);
            if (cut == null && stepTo == null) {
                runTicks = GIVE_UP_TICKS; // nothing diggable: give up for now
            }
            return;
        }
        settler.getLookControl().setLookAt(cut.getX() + 0.5, cut.getY() + 0.5, cut.getZ() + 0.5);
        settler.setActivity(SettlerActivity.WORK_MINE);
        if (++cutTicks < TICKS_PER_CUT) {
            return;
        }
        cutTicks = 0;
        BlockState state = level.getBlockState(cut);
        if (canCut(level, cut)) {
            List<ItemStack> drops = MinerWorkGoal.minedDrops(level, cut, state);
            level.destroyBlock(cut, false);
            store(level, drops);
        }
        cut = null;
    }

    /**
     * Next block to remove, or the neighbouring cell to step onto, on the
     * cheapest dig route out of the pit.
     *
     * <p>A small Dijkstra over the cells a two-tall settler can stand in,
     * bounded to the mine's square and to below the mine's floor: a move to a
     * neighbouring cell (level, one up or one down, onto a solid floor) costs
     * one, and each rock or earth block that must be cut out of the way costs
     * three more. The first action on that route is returned; the goal
     * replans after every cut or step. The earlier "carve into the adjacent
     * wall" rule stalled for 2.7 hours in the captain1 soak (Thyra): the old
     * pit was a wide two-deep sheet whose edges were one-high crawlspaces
     * under the grass, so no neighbour was ever a wall; this route cuts the
     * crawlspace ceiling it needs and walks out through it.
     */
    private void planStep(ServerLevel level) {
        BlockPos feet = settler.blockPosition();
        BlockPos anchor = mine.anchor;
        java.util.Map<BlockPos, Integer> dist = new java.util.HashMap<>();
        java.util.Map<BlockPos, BlockPos> prev = new java.util.HashMap<>();
        java.util.Map<BlockPos, BlockPos> firstCut = new java.util.HashMap<>();
        java.util.PriorityQueue<long[]> queue = new java.util.PriorityQueue<>(
            java.util.Comparator.comparingLong(e -> e[0]));
        java.util.Map<Long, BlockPos> byKey = new java.util.HashMap<>();
        BlockPos start = feet.immutable();
        dist.put(start, 0);
        queue.add(new long[] {0, start.asLong()});
        byKey.put(start.asLong(), start);
        BlockPos goal = null;
        int expanded = 0;
        while (!queue.isEmpty() && expanded++ < ROUTE_SEARCH_NODES) {
            long[] top = queue.poll();
            BlockPos c = byKey.get(top[1]);
            if (top[0] > dist.getOrDefault(c, Integer.MAX_VALUE)) {
                continue;
            }
            if (c.getY() >= anchor.getY()) {
                goal = c;
                break;
            }
            for (Direction d : Direction.Plane.HORIZONTAL) {
                for (int dy = 0; dy >= -1 && dy <= 1; dy = dy == 0 ? 1 : dy == 1 ? -1 : 2) {
                    BlockPos n = c.relative(d).above(dy).immutable();
                    if (Math.abs(n.getX() - anchor.getX()) > MineShaft.RADIUS
                        || Math.abs(n.getZ() - anchor.getZ()) > MineShaft.RADIUS
                        || n.getY() < anchor.getY() - 16 || MineShaft.passable(level, n.below())) {
                        continue;
                    }
                    BlockPos[] need = dy == 1 ? new BlockPos[] {n, n.above(), c.above(2)}
                        : dy == -1 ? new BlockPos[] {n, n.above(), n.above(2)}
                        : new BlockPos[] {n, n.above()};
                    int cuts = 0;
                    BlockPos cutFirst = null;
                    boolean possible = true;
                    for (BlockPos p : need) {
                        if (MineShaft.passable(level, p)) {
                            continue;
                        }
                        if (!routeCuttable(level, p, anchor)) {
                            possible = false;
                            break;
                        }
                        cuts++;
                        if (cutFirst == null) {
                            cutFirst = p.immutable();
                        }
                    }
                    if (!possible) {
                        continue;
                    }
                    int nd = dist.get(c) + 1 + 3 * cuts;
                    if (nd < dist.getOrDefault(n, Integer.MAX_VALUE)) {
                        dist.put(n, nd);
                        prev.put(n, c);
                        if (cutFirst != null) {
                            firstCut.put(n, cutFirst);
                        } else {
                            firstCut.remove(n);
                        }
                        byKey.put(n.asLong(), n);
                        queue.add(new long[] {nd, n.asLong()});
                    }
                }
            }
        }
        if (goal == null) {
            return;
        }
        BlockPos n = goal;
        while (!start.equals(prev.get(n))) {
            n = prev.get(n);
            if (n == null) {
                return;
            }
        }
        BlockPos cutNeeded = firstCut.get(n);
        if (cutNeeded != null) {
            cut = cutNeeded;
        } else {
            stepTo = n;
            stepTicks = 0;
            settler.getNavigation().stop();
        }
    }

    /** Rock or earth inside the mine's own dig square, below its floor: never walls or neighbours. */
    private boolean routeCuttable(ServerLevel level, BlockPos p, BlockPos anchor) {
        return Math.abs(p.getX() - anchor.getX()) <= MineShaft.DIG_RADIUS
            && Math.abs(p.getZ() - anchor.getZ()) <= MineShaft.DIG_RADIUS
            && p.getY() < anchor.getY() && canCut(level, p);
    }

    /**
     * The pit is no longer a trap (a staircase leads out), but the Miner is
     * still below the mine: walk up to it. At night nothing else would, and a
     * Miner left on the top tread of its own escape is still in the pit.
     */
    private void walkOut() {
        if (++walkTicks > WALK_OUT_TICKS) {
            mine = null; // the way out exists; the work goal takes it from here
            return;
        }
        if (settler.getNavigation().isDone() && walkTicks % 20 == 1) {
            Path route = routeOut(mine.anchor);
            if (route == null) {
                // No real route up after all: back to carving steps, never a
                // walk to wherever a partial route ends (the bottom of the pit).
                walkingOut = false;
                return;
            }
            settler.getNavigation().moveTo(route, STEP_SPEED);
        }
    }

    /**
     * A route that really ends at the mine, or null. W6 caught the live
     * navigator answering "canReach" with a path whose end node was the
     * bottom of the pit (it can hand back an installed path, and the road
     * navigator's detour cache can too), so the end node itself must be next
     * to the anchor before the Miner counts as free.
     */
    private Path routeOut(BlockPos anchor) {
        Path out = settler.getNavigation().createPath(anchor, 1);
        if (out == null || !out.canReach()) {
            return null;
        }
        net.minecraft.world.level.pathfinder.Node end = out.getEndNode();
        return end != null && end.asBlockPos().distManhattan(anchor) <= 1 ? out : null;
    }

    private boolean canCut(ServerLevel level, BlockPos pos) {
        // Rock and earth only. The old "any soft block" rule let the escape
        // cut the mine's own oak-plank wall (captain1: 627,70,573), and a
        // plank wall or a door is never the way out of a pit.
        return MineShaft.diggable(level, pos);
    }


    private void store(ServerLevel level, List<ItemStack> drops) {
        for (ItemStack drop : drops) {
            ItemStack left = drop.copy();
            for (BlockPos pos : WarehouseIndex.containers(level, mine)) {
                BlockEntity be = level.getBlockEntity(pos);
                if (!(be instanceof Container container) || left.isEmpty()) {
                    continue;
                }
                for (int slot = 0; slot < container.getContainerSize() && !left.isEmpty(); slot++) {
                    ItemStack in = container.getItem(slot);
                    if (in.isEmpty()) {
                        container.setItem(slot, left.copy());
                        left = ItemStack.EMPTY;
                    } else if (ItemStack.isSameItemSameComponents(in, left)
                        && in.getCount() < in.getMaxStackSize()) {
                        int move = Math.min(left.getCount(), in.getMaxStackSize() - in.getCount());
                        in.grow(move);
                        left.shrink(move);
                        container.setChanged();
                    }
                }
            }
            if (!left.isEmpty()) {
                com.hearthstead.util.ItemSpill.conserve(level, settler.blockPosition(), left);
            }
        }
    }
}
