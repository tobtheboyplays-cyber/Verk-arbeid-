package com.hearthstead.entity.path;

import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Settlers keep to the road.
 *
 * <p>A village where everyone cuts diagonally across the wheat looks like a
 * crowd of pathfinders; a village where they follow the path the player dug
 * with a shovel looks like a village. This makes the second one true, and it
 * costs one block lookup per candidate step.
 *
 * <p>The road is <b>{@link Blocks#DIRT_PATH}</b> — what a shovel makes out of
 * grass, and nothing else. That was the owner's choice and it is the right one
 * for a mod with no blocks of its own to teach: the player already knows how to
 * build one, it works in a world they started before installing this, and it
 * needs no recipe, no research and no explanation.
 *
 * <h2>How the preference is expressed</h2>
 *
 * <p>Vanilla's pathfinder scores a step by {@code costMalus}, so a preference
 * for roads is really a <b>penalty for everything else</b>. Every candidate
 * step that does not land on a path gets {@link #OFF_ROAD_MALUS} added, which
 * makes the pathfinder happily walk a good deal further to stay on the path
 * and still cut across when the detour is genuinely absurd. It bends the route;
 * it does not put a wall around it.
 *
 * <h2>Guards chasing something are exempt</h2>
 *
 * <p>Nobody follows the road while a raider is in the wheat. When
 * {@link SettlerEntity#prefersRoads()} is false — a guard with a target — the
 * penalty is not applied at all and they take the straight line, which is what
 * you would do.
 */
public class RoadNodeEvaluator extends WalkNodeEvaluator {

    /**
     * How much a step off the road costs, in the pathfinder's own units.
     *
     * <p>One is roughly the cost of a single ordinary step, so at 1.5 a settler
     * will accept a detour of about half again as many steps to stay on a path
     * — visible without being obsessive. Larger values start producing settlers
     * who walk three sides of a square to avoid one metre of grass.
     */
    public static final float OFF_ROAD_MALUS = 1.5F;

    // Vanilla reuses nodes and preserves their maximum terrain cost. Charge
    // this preference once per node, not once per neighboring expansion.
    private final Set<Node> penalizedNodes =
        Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * A fall longer than this (in blocks of floor height) hurts: vanilla's
     * safe fall distance is three. Vanilla plans drops of three whole cells,
     * which lands a settler 3.5 blocks down on a stair and costs health.
     */
    static final double MAX_SAFE_DROP = 3.0D;

    /**
     * Extra cost of passing a closed fence gate, in ordinary steps. Gates
     * are opened when they are the practical way in (a pasture, a yard), but
     * any real door within about a dozen extra steps is preferred, so a gate
     * in a settlement's defensive wall is never the everyday shortcut.
     */
    static final float CLOSED_GATE_MALUS = 12.0F;
    private final Set<Node> gatePenalized =
        Collections.newSetFromMap(new IdentityHashMap<>());

    // Per-search cache of this evaluator's own refinements; vanilla caches
    // its part per position already.
    private final Long2ObjectMap<PathType> refinedTypes = new Long2ObjectOpenHashMap<>();

    @Override
    public void prepare(PathNavigationRegion region, Mob mob) {
        penalizedNodes.clear();
        gatePenalized.clear();
        refinedTypes.clear();
        super.prepare(region, mob);
    }

    @Override
    public void done() {
        try {
            super.done();
        } finally {
            penalizedNodes.clear();
            gatePenalized.clear();
            refinedTypes.clear();
        }
    }

    @Override
    public Node getStart() {
        BlockPos feet = mob.blockPosition();
        // Ground navigation's normal airborne scan descends through every
        // pathfindable ladder rung until it reaches the supporting floor. A
        // route refresh halfway up a ladder would therefore restart its
        // search at the bottom rung, although the settler is physically much
        // higher. Keep a refresh anchored to the real rung being climbed.
        if (ladderCell(feet)) {
            return getStartNode(feet);
        }
        // Balcony corner (captain1 soak, lumberer Dunstan): the feet cell is
        // no start (a fence post), and among the body's corner cells vanilla
        // picks a beam top that leads nowhere -- while the body already
        // overlaps the open cell above a ladder's top rung, the only way down.
        // Start on that top rung so the route descends the ladder.
        if (!canStartAt(feet)) {
            BlockPos ladderTop = ladderEntryUnderBody(feet.getY());
            if (ladderTop != null) {
                return getStartNode(ladderTop);
            }
        }
        Node start = super.getStart();
        // At a shallow-water edge the bounding box can still touch water
        // while its center is dry. Vanilla's floating scan then steps down
        // immediately and can return the solid floor as its start node.
        if (!mob.onGround() || !canFloat() || !mob.isInWater()) {
            return start;
        }
        if (start.y != feet.getY() - 1 || start.type != PathType.BLOCKED
                || start.costMalus >= 0.0F
                || !currentContext.getBlockState(feet).isAir()
                || !currentContext.getBlockState(feet).getFluidState().isEmpty()
                || !currentContext.getBlockState(feet.below()).isFaceSturdy(
                    currentContext.level(), feet.below(), Direction.UP)) {
            return start;
        }
        PathType type = getCachedPathType(feet.getX(), feet.getY(), feet.getZ());
        // WATER_BORDER is vanilla's ordinary supported walkable cell beside
        // water. Keep its existing cost; do not admit fluid or open-air starts.
        if ((type == PathType.WALKABLE || type == PathType.WATER_BORDER)
                && mob.getPathfindingMalus(type) >= 0.0F) {
            return getStartNode(feet);
        }
        return start;
    }

    @Override
    protected boolean canStartAt(BlockPos pos) {
        if (!super.canStartAt(pos)) return false;
        if (currentContext == null || mob == null || !mob.onGround() || mob.onClimbable()
            || mob.isInWater() || !currentContext.getBlockState(mob.blockPosition()).getFluidState().isEmpty()) return true;
        double supportLift = raisedSupportLift(pos);
        if (supportLift <= 0.0D) return true;
        double rise = pos.getY() + supportLift - mob.getY();
        if (rise <= 1.0E-4D) return true;
        // A corner of a shelf-standing body may overlap the AIR cell above a
        // taller fence. That is not an attained start when lifting the actual
        // body to its floor hits the eave. Let vanilla try its other corners.
        return currentContext.level().noCollision(mob,
            mob.getBoundingBox().expandTowards(0.0D, rise, 0.0D).deflate(1.0E-4D));
    }

    @Override
    public int getNeighbors(Node[] outputArray, Node node) {
        int found = super.getNeighbors(outputArray, node);
        // A Hearth's partial collision shape can be admitted in its base cell.
        // From a lower terrace that adds its height to an already full-block
        // step, producing a route the settler cannot jump. Reject only that
        // incoming rise; same-level access for meals and descent stay legal.
        int retained = 0;
        for (int i = 0; i < found; i++) {
            Node candidate = outputArray[i];
            if (candidate.y > node.y && currentContext.getBlockState(
                    candidate.asBlockPos()).is(ModBlocks.HEARTH.get())) continue;
            outputArray[retained++] = candidate;
        }
        for (int i = retained; i < found; i++) outputArray[i] = null;
        found = retained;
        // A planned drop is a real fall. Keep every drop the settler survives
        // unhurt; never plan one that costs health (landing on a stair or a
        // lower floor three and a half blocks down).
        int safe = 0;
        double hereFloor = Double.NaN;
        for (int i = 0; i < found; i++) {
            Node candidate = outputArray[i];
            if (candidate.y < node.y - 1 && !ladderCell(candidate.asBlockPos())) {
                if (Double.isNaN(hereFloor)) hereFloor = getFloorLevel(node.asBlockPos());
                if (hereFloor - getFloorLevel(candidate.asBlockPos()) > MAX_SAFE_DROP) continue;
            }
            outputArray[safe++] = candidate;
        }
        for (int i = safe; i < found; i++) outputArray[i] = null;
        found = safe;
        // While stepping between two cells the body straddles both, so it
        // must fit under the lower ceiling at the higher floor covering. A
        // carpet laid right behind a two-high doorway fails this for a
        // 1.95-tall settler (carpet 1/16 + 1.95 > 2): plan around it or
        // report it unreachable instead of wedging on the sill.
        int fits = 0;
        for (int i = 0; i < found; i++) {
            Node candidate = outputArray[i];
            if (candidate.x != node.x && candidate.z != node.z && candidate.y > node.y) {
                double fromFloor = getFloorLevel(node.asBlockPos());
                double toFloor = getFloorLevel(candidate.asBlockPos());
                if (toFloor - fromFloor > mob.maxUpStep()
                    && obstructedRaisedTransition(mob.level(), mob,
                        new net.minecraft.world.phys.Vec3(node.x + .5D, fromFloor, node.z + .5D),
                        new net.minecraft.world.phys.Vec3(candidate.x + .5D, toFloor, candidate.z + .5D))) continue;
            }
            if (candidate.y != node.y || fitsBetween(node.asBlockPos(), candidate.asBlockPos())) {
                outputArray[fits++] = candidate;
            }
        }
        for (int i = fits; i < found; i++) outputArray[i] = null;
        found = fits;
        for (int i = 0; i < found; i++) {
            Node candidate = outputArray[i];
            if (candidate.type == PathType.WALKABLE_DOOR && !gatePenalized.contains(candidate)) {
                BlockState gate = currentContext.getBlockState(candidate.asBlockPos());
                if (gate.getBlock() instanceof FenceGateBlock && !gate.getValue(FenceGateBlock.OPEN)) {
                    gatePenalized.add(candidate);
                    candidate.costMalus += CLOSED_GATE_MALUS;
                }
            }
        }
        BlockPos here = node.asBlockPos();
        if (ladderCell(here)) {
            // Vanilla may connect a rung diagonally to the floor several
            // blocks below. Keep descent on the continuous ladder instead;
            // same-height supported deck exits remain ordinary neighbors.
            int kept = 0;
            for (int i = 0; i < found; i++) {
                Node candidate = outputArray[i];
                if (candidate.y < node.y && !ladderCell(candidate.asBlockPos())) continue;
                outputArray[kept++] = candidate;
            }
            for (int i = kept; i < found; i++) outputArray[i] = null;
            found = kept;
            for (int dy : new int[]{-1, 1}) {
                BlockPos next = here.offset(0, dy, 0);
                if (found < outputArray.length && ladderCell(next)) {
                    Node candidate = getNode(next.getX(), next.getY(), next.getZ());
                    if (!candidate.closed) {
                        candidate.type = PathType.WALKABLE;
                        candidate.costMalus = Math.max(0, candidate.costMalus);
                        outputArray[found++] = candidate;
                    }
                }
            }
        }
        if (!wantsRoads()) {
            return found;
        }
        for (int i = 0; i < found; i++) {
            Node candidate = outputArray[i];
            if (candidate != null && !onRoad(candidate)
                    && penalizedNodes.add(candidate)) {
                candidate.costMalus += OFF_ROAD_MALUS;
            }
        }
        return found;
    }

    // Treat an actual clear climbable column as supported, so vanilla's
    // horizontal entry/exit search does not collapse its nodes down to the
    // ground floor. The open cell above a hatch is air to fall into, not a
    // floor to stand on, so a descending route enters the shaft. A floor
    // covering (carpet, snow) that leaves no headroom is not walkable.
    @Override
    protected PathType getCachedPathType(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        if (ladderCell(pos)) return PathType.WALKABLE;
        PathType type = super.getCachedPathType(x, y, z);
        if (currentContext == null || mob == null) return type;
        long key = pos.asLong();
        PathType refined = refinedTypes.get(key);
        if (refined != null) return refined;
        refined = refine(pos, type);
        refinedTypes.put(key, refined);
        return refined;
    }

    private PathType refine(BlockPos pos, PathType type) {
        if (!mob.level().hasChunkAt(pos) || !mob.level().hasChunkAt(pos.above(2))) return type;
        BlockState feet = currentContext.getBlockState(pos);
        BlockPos below = pos.below();
        BlockState belowState = currentContext.getBlockState(below);
        if (feet.getCollisionShape(currentContext.level(), pos).isEmpty()
            && IndoorBlocks.hatchOverLadder(currentContext.level(), below, belowState)
            && type != PathType.BLOCKED) {
            return PathType.OPEN;
        }
        if (type == PathType.BLOCKED || type == PathType.OPEN || mob.getPathfindingMalus(type) < 0.0F) {
            return type;
        }
        double cover = IndoorBlocks.lowCoverTop(currentContext.level(), pos, feet);
        if (cover > 0.0D && !IndoorBlocks.modelledSpecially(feet)) {
            BlockPos ceilingPos = pos.above(2);
            VoxelShape ceiling = currentContext.getBlockState(ceilingPos)
                .getCollisionShape(currentContext.level(), ceilingPos);
            if (!ceiling.isEmpty()
                && 2.0D + ceiling.min(Direction.Axis.Y) < cover + mob.getBbHeight() - 1.0E-4D) {
                return PathType.BLOCKED;
            }
        }
        if (!headroomClear(pos, feet)) return PathType.BLOCKED;
        return type;
    }

    /**
     * The settler's own body fits standing in this cell.
     *
     * <p>Vanilla sizes a mob into cells by path type alone, and a trapdoor
     * is always {@link PathType#TRAPDOOR}, which costs nothing. A closed top
     * trapdoor used as a window-box shelf 1.8125 above the ground therefore
     * reads as open air to a 1.95-tall settler, who walks along the wall
     * under it and wedges (seen live on 68 of the 77 town blueprints). The
     * same goes for a top slab, a carpet on a shelf or any other partial
     * block overhead.
     *
     * <p>The body tested is the mob's own bounding box, centred in the cell,
     * from its floor (or floor covering) up to its height. Only real
     * collision boxes that reach into that body block the cell, so edge
     * panels stay passable: an open trapdoor standing against a wall, a
     * ladder, a door leaf. Doors and fence gates are passages the door goal
     * opens and are left to the existing door rules; stairs are floors, not
     * overhead, and keep vanilla's step logic.
     */
    private boolean headroomClear(BlockPos pos, BlockState feet) {
        if (!feet.getFluidState().isEmpty()) return true;
        var level = currentContext.level();
        double floor = getFloorLevel(pos);
        double cover = IndoorBlocks.lowCoverTop(level, pos, feet);
        if (cover > 0.0D) floor = Math.max(floor, pos.getY() + cover);
        double half = mob.getBbWidth() / 2.0D;
        double cx = pos.getX() + 0.5D;
        double cz = pos.getZ() + 0.5D;
        AABB body = new AABB(cx - half, floor + 1.0E-3D, cz - half,
            cx + half, floor + mob.getBbHeight() - 1.0E-3D, cz + half);
        int top = Mth.floor(body.maxY);
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int y = pos.getY(); y <= top; y++) {
            at.set(pos.getX(), y, pos.getZ());
            if (!mob.level().hasChunkAt(at)) return true;
            BlockState state = currentContext.getBlockState(at);
            if (state.isAir() || state.getBlock() instanceof DoorBlock
                || state.getBlock() instanceof FenceGateBlock
                || state.is(BlockTags.CLIMBABLE)) continue;
            VoxelShape shape = state.getCollisionShape(level, at);
            if (shape.isEmpty()) continue;
            for (AABB box : shape.toAabbs()) {
                if (box.move(at).intersects(body)) return false;
            }
        }
        return true;
    }

    /**
     * Vanilla trusts {@code isPathfindable}, which many furniture blocks
     * (tables, chairs, hanging lanterns) inherit as "passable" despite a real
     * collision box: routes then run straight into them and wedge. Treat any
     * cell that vanilla calls open but that has a collision box taller than a
     * floor covering as blocked. A closed fence gate is a passage the door
     * goal opens, like a wooden door.
     */
    @Override
    public PathType getPathType(PathfindingContext context, int x, int y, int z) {
        PathType type = super.getPathType(context, x, y, z);
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = context.getBlockState(pos);
        if (type == PathType.FENCE && canOpenDoors() && canPassDoors()
            && state.getBlock() instanceof FenceGateBlock
            && !state.getValue(FenceGateBlock.OPEN)) {
            // A defense gate stays shut during a raid or alarm (and a
            // controlled raid gate always): never plan through it.
            if (mob != null && SealedGates.sealed(mob.level(), pos)) {
                return PathType.BLOCKED;
            }
            return PathType.WALKABLE_DOOR;
        }
        if (type == PathType.DOOR_WOOD_CLOSED && mob != null && SealedGates.sealed(mob.level(), pos)) {
            return PathType.BLOCKED;
        }
        if ((type == PathType.OPEN || type == PathType.WALKABLE || type == PathType.WATER_BORDER)
            && !state.isAir() && !IndoorBlocks.modelledSpecially(state)
            && !state.getCollisionShape(context.level(), pos).isEmpty()
            && IndoorBlocks.lowCoverTop(context.level(), pos, state) < 0.0D) {
            return PathType.BLOCKED;
        }
        return type;
    }

    /** A dry jump's raised body must clear the diagonal corner, not merely its destination cell. */
    static boolean obstructedRaisedTransition(net.minecraft.world.level.Level level, Mob mob,
            net.minecraft.world.phys.Vec3 from, net.minecraft.world.phys.Vec3 to) {
        double half = mob.getBbWidth() / 2.0D - 1.0E-5D;
        double floor = Math.max(from.y, to.y);
        double top = floor + mob.getBbHeight() - 1.0E-5D;
        var start = new net.minecraft.world.phys.Vec3(from.x, floor, from.z);
        var end = new net.minecraft.world.phys.Vec3(to.x, floor, to.z);
        BlockPos min = BlockPos.containing(Math.min(from.x, to.x) - half, Math.min(from.y, to.y) - 1,
            Math.min(from.z, to.z) - half);
        BlockPos max = BlockPos.containing(Math.max(from.x, to.x) + half, top,
            Math.max(from.z, to.z) + half);
        // Operable passages, water and ladders keep their existing specialized semantics.
        for (BlockPos at : BlockPos.betweenClosed(min, max)) {
            if (!level.hasChunkAt(at)) return false;
            BlockState state = level.getBlockState(at);
            if (!state.getFluidState().isEmpty() || state.is(BlockTags.CLIMBABLE)
                || state.getBlock() instanceof DoorBlock || state.getBlock() instanceof FenceGateBlock) return false;
        }
        var context = net.minecraft.world.phys.shapes.CollisionContext.of(mob);
        for (BlockPos at : BlockPos.betweenClosed(min, max)) {
            for (AABB local : level.getBlockState(at).getCollisionShape(level, at, context).toAabbs()) {
                AABB box = local.move(at);
                if (box.maxY <= floor + 1.0E-5D || box.minY >= top) continue;
                // Minkowski expansion gives the exact horizontal sweep, not a filled diagonal rectangle.
                AABB expanded = new AABB(box.minX - half, floor - 1, box.minZ - half,
                    box.maxX + half, floor + 1, box.maxZ + half);
                if (expanded.contains(start) || expanded.contains(end) || expanded.clip(start, end).isPresent()) return true;
            }
        }
        return false;
    }

    private boolean fitsBetween(BlockPos from, BlockPos to) {
        if (currentContext == null || mob == null) return true;
        double lift = Math.max(coverLift(from), coverLift(to));
        if (lift <= 0.0D) return true;
        double need = lift + mob.getBbHeight() - 1.0E-4D;
        return ceiling(from) >= need && ceiling(to) >= need;
    }

    private double coverLift(BlockPos feet) {
        if (!mob.level().hasChunkAt(feet)) return 0.0D;
        BlockState state = currentContext.getBlockState(feet);
        if (IndoorBlocks.modelledSpecially(state)) return 0.0D;
        return Math.max(raisedSupportLift(feet),
            Math.max(0.0D, IndoorBlocks.lowCoverTop(currentContext.level(), feet, state)));
    }

    /** A dry solid support can extend above the integer AIR node (fence: +0.5). */
    private double raisedSupportLift(BlockPos feet) {
        if (currentContext == null || mob == null || !mob.level().hasChunkAt(feet)
            || !mob.level().hasChunkAt(feet.below())) return 0.0D;
        BlockState state = currentContext.getBlockState(feet);
        BlockPos below = feet.below();
        BlockState support = currentContext.getBlockState(below);
        // Preserve water/climbable starts and operable passage semantics.
        if (!state.getFluidState().isEmpty() || !support.getFluidState().isEmpty()
            || state.is(BlockTags.CLIMBABLE) || support.is(BlockTags.CLIMBABLE)
            || state.getBlock() instanceof DoorBlock || support.getBlock() instanceof DoorBlock
            || state.getBlock() instanceof FenceGateBlock || support.getBlock() instanceof FenceGateBlock) return 0.0D;
        VoxelShape shape = support.getCollisionShape(currentContext.level(), below);
        return shape.isEmpty() ? 0.0D : Math.max(0.0D, shape.max(Direction.Axis.Y) - 1.0D);
    }

    /** Clear height above the cell's floor before the first collision two blocks up. */
    private double ceiling(BlockPos feet) {
        BlockPos head = feet.above(2);
        if (!mob.level().hasChunkAt(head)) return 0.0D;
        VoxelShape shape = currentContext.getBlockState(head).getCollisionShape(currentContext.level(), head);
        return shape.isEmpty() ? 3.0D : 2.0D + shape.min(Direction.Axis.Y);
    }

    /**
     * A cell the settler's body already overlaps at feet height that is open
     * (no collision) above a climbable top rung: returns that rung, or null.
     */
    private BlockPos ladderEntryUnderBody(int feetY) {
        if (currentContext == null || mob == null) return null;
        var box = mob.getBoundingBox();
        double[] xs = {box.minX + 1.0E-3D, box.maxX - 1.0E-3D};
        double[] zs = {box.minZ + 1.0E-3D, box.maxZ - 1.0E-3D};
        for (double x : xs) {
            for (double z : zs) {
                BlockPos cell = BlockPos.containing(x, feetY, z);
                if (!mob.level().hasChunkAt(cell)) continue;
                BlockState at = currentContext.getBlockState(cell);
                if (!at.getCollisionShape(currentContext.level(), cell).isEmpty()) continue;
                BlockPos rung = cell.below();
                if (ladderCell(rung)) return rung;
            }
        }
        return null;
    }

    private boolean ladderCell(BlockPos pos) {
        if (currentContext == null || mob == null || !mob.level().hasChunkAt(pos)
                || !mob.level().hasChunkAt(pos.above())) return false;
        var level = currentContext.level();
        if (!IndoorBlocks.climbable(level, pos)) return false;
        BlockPos above = pos.above();
        BlockState aboveState = currentContext.getBlockState(above);
        return IndoorBlocks.climbable(level, above)
            || aboveState.getCollisionShape(level, above).isEmpty();
    }

    private boolean wantsRoads() {
        // `mob` and `currentContext` are the evaluator's own fields, set by
        // prepare() for the duration of one search.
        return mob instanceof SettlerEntity settler && settler.prefersRoads();
    }

    /** A step is on the road when the block it stands on is a dirt path. */
    private boolean onRoad(Node node) {
        if (currentContext == null) {
            return false;
        }
        return currentContext
            .getBlockState(new BlockPos(node.x, node.y - 1, node.z))
            .is(Blocks.DIRT_PATH);
    }
}
