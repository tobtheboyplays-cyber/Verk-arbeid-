package com.hearthstead.settlement;

import com.hearthstead.block.AleTapBlock;
import com.hearthstead.entity.path.IndoorBlocks;
import com.hearthstead.entity.path.StairHeadroom;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Seeded flood-fill room detection — the surveying half of building
 * registration. A room is valid when the fill stays bounded (enclosed by
 * collision-solid blocks and closed doors), is roofed, and its contents
 * satisfy the building type's requirements.
 *
 * <p>The scan is seeded by a plaque: the player hangs one, and it surveys the
 * room it hangs in. This class answers only "what is in this room"; the
 * plaque's type decides whether that is enough.
 *
 * All scans are hard-capped: max {@value #MAX_VOLUME} interior cells and
 * {@value #MAX_EXTENT} blocks of horizontal extent from the seed, so a scan
 * can never run away in an open world.
 */
public final class RoomScanner {
    /** Hard scan budget — a scan may never touch more cells than this. */
    public static final int MAX_VOLUME = 2048;
    public static final int MAX_EXTENT = 24;
    public static final int MAX_HEIGHT = 12;
    /**
     * A dwelling must be a ROOM, not a space. 512 cells is already a 16x16
     * hall with a 2-high ceiling — far beyond any real cottage — so a fill
     * bigger than this has escaped the room it was seeded in and is filling a
     * cave, a courtyard or the outdoors. This is the rule that makes a
     * breached house stop counting as a home even where the fill still ends
     * up enclosed and roofed by distant geometry.
     */
    public static final int MAX_HOME_VOLUME = 512;

    /** Distinct furnishing block groups; each present group adds one point. */
    private static final List<java.util.function.Predicate<BlockState>> FURNISHING_GROUPS =
        List.of(
            s -> s.is(Blocks.CRAFTING_TABLE),
            s -> s.is(Blocks.CHEST) || s.is(Blocks.BARREL),
            s -> s.is(Blocks.FURNACE) || s.is(Blocks.SMOKER)
                || s.is(Blocks.BLAST_FURNACE),
            s -> s.getBlock() instanceof CampfireBlock,
            s -> s.getBlock() instanceof FlowerPotBlock
                && s.getBlock() != Blocks.FLOWER_POT,
            s -> s.getBlock() instanceof CarpetBlock,
            s -> s.is(Blocks.BOOKSHELF) || s.is(Blocks.CHISELED_BOOKSHELF)
                || s.is(Blocks.LECTERN),
            s -> s.is(Blocks.LOOM) || s.is(Blocks.SMITHING_TABLE)
                || s.is(Blocks.FLETCHING_TABLE) || s.is(Blocks.CARTOGRAPHY_TABLE),
            s -> s.is(Blocks.BREWING_STAND) || s.is(Blocks.CAULDRON),
            s -> s.is(Blocks.JUKEBOX) || s.is(Blocks.NOTE_BLOCK)
        );

    /** The result of one scan. {@code null} when the seed had no interior. */
    public record Result(BoundingBox bounds, int volume, List<BlockPos> beds,
                         int doors, int lights, int furnishingScore,
                         boolean enclosed, boolean skyLeak,
                         java.util.Map<Block, Integer> blockCounts,
                         @Nullable BlockPos leakPos, @Nullable BlockPos skyLeakPos,
                         int connectedAleTaps,
                         java.util.Map<Block, Integer> floorCounts,
                         @Nullable BlockPos lowStairStep) {

        /**
         * Headroom lane: {@code lowStairStep} is the step (floor or tread) a
         * settler would climb from onto a stair in this room without the
         * three clear blocks above it that the climb needs, or null. It is a
         * warning only and never fails a requirement. Older callers pass none.
         */
        public Result(BoundingBox bounds, int volume, List<BlockPos> beds,
                      int doors, int lights, int furnishingScore,
                      boolean enclosed, boolean skyLeak,
                      java.util.Map<Block, Integer> blockCounts,
                      @Nullable BlockPos leakPos, @Nullable BlockPos skyLeakPos,
                      int connectedAleTaps,
                      java.util.Map<Block, Integer> floorCounts) {
            this(bounds, volume, beds, doors, lights, furnishingScore, enclosed,
                skyLeak, blockCounts, leakPos, skyLeakPos, connectedAleTaps,
                floorCounts, null);
        }

        /**
         * The non-blocking stair warning for the plaque, pointing at the step
         * that lacks headroom, or null when every stair here is climbable.
         */
        @Nullable
        public net.minecraft.network.chat.Component stairWarning() {
            return lowStairStep == null ? null
                : net.minecraft.network.chat.Component.translatable(
                    "hearthstead.plaque.scan.stair_headroom",
                    lowStairStep.getX(), lowStairStep.getY(), lowStairStep.getZ());
        }

        /**
         * Builder lane (checklist levels): {@code floorCounts} tallies the
         * block directly under every interior cell that has no interior cell
         * below it -- the room's floor -- so a level can ask for a solid
         * floor. Older callers that never measured a floor get an empty map.
         */
        public Result(BoundingBox bounds, int volume, List<BlockPos> beds,
                      int doors, int lights, int furnishingScore,
                      boolean enclosed, boolean skyLeak,
                      java.util.Map<Block, Integer> blockCounts,
                      @Nullable BlockPos leakPos, @Nullable BlockPos skyLeakPos,
                      int connectedAleTaps) {
            this(bounds, volume, beds, doors, lights, furnishingScore, enclosed,
                skyLeak, blockCounts, leakPos, skyLeakPos, connectedAleTaps,
                java.util.Map.of());
        }

        /** Number of floor cells measured (0 for synthetic scans). */
        public int floorCells() {
            int total = 0;
            for (int n : floorCounts.values()) {
                total += n;
            }
            return total;
        }

        /** Older synthetic scans contain no demonstrated connected tap. */
        public Result(BoundingBox bounds, int volume, List<BlockPos> beds,
                      int doors, int lights, int furnishingScore,
                      boolean enclosed, boolean skyLeak,
                      java.util.Map<Block, Integer> blockCounts,
                      @Nullable BlockPos leakPos, @Nullable BlockPos skyLeakPos) {
            this(bounds, volume, beds, doors, lights, furnishingScore, enclosed,
                skyLeak, blockCounts, leakPos, skyLeakPos, 0);
        }

        /**
         * How many blocks of the given kinds stand in this room. Building
         * types are defined in terms of their furniture — storage, a
         * workbench, a lectern — so requirements read the tally the scan
         * already collected rather than walking the room again.
         */
        public int countBlocks(List<Block> kinds) {
            int total = 0;
            for (Block block : kinds) {
                total += blockCounts.getOrDefault(block, 0);
            }
            return total;
        }

        /**
         * A room must be enclosed AND roofed, and hold the home essentials.
         * {@code skyLeak} is measured geometrically (see
         * {@link #hasCoverAbove}) rather than with {@code canSeeSky}: the
         * heightmap behind that call is settled asynchronously by the light
         * engine, so a freshly finished house read as open to the sky for a
         * tick or two — and a glass roof read as open forever.
         */
        public boolean validHome() {
            return enclosed && !skyLeak && volume <= MAX_HOME_VOLUME
                && !beds.isEmpty() && doors > 0 && lights > 0;
        }

        /**
         * A player-facing explanation of WHY the geometric scan itself
         * failed — {@code null} when the room passed every geometric check
         * (enclosed, roofed, within {@link #MAX_HOME_VOLUME}), in which case
         * any remaining failure is a per-type requirement (a bed, a door)
         * and already named by the checklist in {@link #missing()} and by
         * the per-requirement lines the plaque shows.
         *
         * <p>Unlike {@link #missing()} (a terse, unused-by-any-caller debug
         * string), this names WHERE using the exact cell the flood fill or
         * the roof test recorded the break at ({@link #leakPos},
         * {@link #skyLeakPos}) — recorded during the ONE scan that already
         * ran, never a second look at the world — so "no room found" becomes
         * a place a player can walk to and fix instead of a dead end.
         */
        @Nullable
        public net.minecraft.network.chat.Component geometryFailure() {
            if (!enclosed) {
                return leakPos != null
                    ? net.minecraft.network.chat.Component.translatable(
                        "hearthstead.plaque.scan.leak",
                        leakPos.getX(), leakPos.getY(), leakPos.getZ())
                    : net.minecraft.network.chat.Component.translatable(
                        "hearthstead.plaque.scan.leak_unknown");
            }
            if (skyLeak) {
                return skyLeakPos != null
                    ? net.minecraft.network.chat.Component.translatable(
                        "hearthstead.plaque.scan.sky_leak",
                        skyLeakPos.getX(), skyLeakPos.getY(), skyLeakPos.getZ())
                    : net.minecraft.network.chat.Component.translatable(
                        "hearthstead.plaque.scan.sky_leak_unknown");
            }
            if (volume > MAX_HOME_VOLUME) {
                return net.minecraft.network.chat.Component.translatable(
                    "hearthstead.plaque.scan.oversized", volume, MAX_HOME_VOLUME);
            }
            return null;
        }

        /** Player-facing reason a room is not a home yet (empty when valid). */
        public String missing() {
            if (validHome()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            if (!enclosed) {
                sb.append("not enclosed; ");
            }
            if (skyLeak) {
                sb.append("open to the sky; ");
            }
            if (volume > MAX_HOME_VOLUME) {
                sb.append("not a closed room (the space runs on); ");
            }
            if (beds.isEmpty()) {
                sb.append("no bed; ");
            }
            if (doors == 0) {
                sb.append("no door; ");
            }
            if (lights == 0) {
                sb.append("no light; ");
            }
            return sb.toString().trim();
        }
    }

    /**
     * Scans from a seed position. The seed may be a bed, a door, or any
     * interior position; the scanner normalizes to a nearby interior cell.
     */
    @Nullable
    public static Result scan(ServerLevel level, BlockPos seed) {
        BlockPos interior = findInteriorSeed(level, seed);
        if (interior == null) {
            return null;
        }

        Set<BlockPos> filled = new HashSet<>();
        Set<BlockPos> boundarySeen = new HashSet<>();
        Set<BlockPos> bedBlocks = new HashSet<>();
        Set<BlockPos> doorBlocks = new HashSet<>();
        List<BlockPos> beds = new ArrayList<>();
        Set<Integer> furnishingHits = new HashSet<>();
        java.util.Map<Block, Integer> blockCounts = new java.util.HashMap<>();
        Set<BlockPos> counted = new HashSet<>();
        int lights = 0;
        boolean enclosed = true;
        boolean skyLeak = false;
        // The break position the fill itself recorded — the cell that
        // tripped the extent/height/volume cap below, or the interior cell
        // the roof test found bare overhead. Recorded, not hunted for: both
        // are cells the scan already visits, so this adds no extra world
        // reads and never widens the flood fill.
        //
        // A single leaked cell can trip these caps far from the actual
        // breach: once a 1-block roof gap lets the fill out, it does not
        // stay in that column — it also spreads SIDEWAYS above the roof
        // line, and in an open world that sideways sprawl has vastly more
        // room to grow than the narrow vertical path back down through the
        // gap, so it is very often the sprawl (not the climb) that trips
        // MAX_VOLUME first. Recording "whichever cell was being processed
        // at that moment" then names some arbitrary point out in the
        // sprawl, not the hole. So instead of the first trip, both track
        // the CLOSEST trip to `interior`: the gap itself, being adjacent to
        // the room, is always nearer to the seed than anything reached by
        // first passing through the gap and then spreading out from there.
        BlockPos leakPos = null;
        BlockPos skyLeakPos = null;

        Deque<BlockPos> frontier = new ArrayDeque<>();
        frontier.add(interior);
        filled.add(interior);

        int minX = interior.getX(), maxX = interior.getX();
        int minY = interior.getY(), maxY = interior.getY();
        int minZ = interior.getZ(), maxZ = interior.getZ();

        while (!frontier.isEmpty()) {
            BlockPos current = frontier.poll();
            if (filled.size() > MAX_VOLUME) {
                enclosed = false;
                if (leakPos == null
                    || interior.distSqr(current) < interior.distSqr(leakPos)) {
                    leakPos = current.immutable();
                }
                break;
            }
            BlockState currentState = level.getBlockState(current);
            classifyContents(level, current, currentState, bedBlocks, beds,
                furnishingHits, blockCounts, counted);
            if (currentState.getLightEmission() > 7) {
                lights++;
            }

            for (Direction dir : Direction.values()) {
                BlockPos next = current.relative(dir);
                if (filled.contains(next)) {
                    continue;
                }
                if (Math.abs(next.getX() - interior.getX()) > MAX_EXTENT
                    || Math.abs(next.getZ() - interior.getZ()) > MAX_EXTENT
                    || Math.abs(next.getY() - interior.getY()) > MAX_HEIGHT) {
                    enclosed = false;
                    if (leakPos == null
                        || interior.distSqr(next) < interior.distSqr(leakPos)) {
                        leakPos = next.immutable();
                    }
                    continue;
                }
                BlockState state = level.getBlockState(next);
                if (state.getBlock() instanceof DoorBlock) {
                    doorBlocks.add(next); // doors are boundary — fill never passes
                    continue;
                }
                if (isPassable(level, next, state)) {
                    filled.add(next);
                    frontier.add(next);
                    minX = Math.min(minX, next.getX());
                    maxX = Math.max(maxX, next.getX());
                    minY = Math.min(minY, next.getY());
                    maxY = Math.max(maxY, next.getY());
                    minZ = Math.min(minZ, next.getZ());
                    maxZ = Math.max(maxZ, next.getZ());
                } else if (boundarySeen.add(next.immutable())) {
                    // Walls, floors and roofs still contribute contents:
                    // wall lanterns, floor chests, embedded furnaces, pots.
                    classifyContents(level, next, state, bedBlocks, beds,
                        furnishingHits, blockCounts, counted);
                    if (state.getLightEmission() > 7) {
                        lights++;
                    }
                }
            }
        }

        // Roof test. Every cell at the top of its column must have something
        // solid overhead — that is the roof. Done geometrically so the answer
        // never depends on how far the light engine has caught up, and so a
        // glass or slab roof counts exactly like a stone one.
        //
        // Scans every uncovered top-of-column cell rather than stopping at
        // the first one `filled` happens to iterate (a HashSet, so that
        // order is arbitrary) and keeps the one closest to `interior` — for
        // the same reason `leakPos` above does: a sprawl above the roof can
        // put far more uncovered cells out in the open than the single
        // column the room actually leaks through, and the closest one is
        // reliably that column, not an arbitrary point in the sprawl.
        for (BlockPos cell : filled) {
            if (filled.contains(cell.above())) {
                continue; // not the top of its column
            }
            if (!hasCoverAbove(level, cell)) {
                skyLeak = true;
                if (skyLeakPos == null
                    || interior.distSqr(cell) < interior.distSqr(skyLeakPos)) {
                    skyLeakPos = cell.immutable();
                }
            }
        }

        // Count distinct doors by their lower-half position.
        Set<BlockPos> distinctDoors = new HashSet<>();
        for (BlockPos doorPos : doorBlocks) {
            BlockState state = level.getBlockState(doorPos);
            if (state.getBlock() instanceof DoorBlock) {
                BlockPos lower = state.getValue(DoorBlock.HALF)
                    == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER
                    ? doorPos.below() : doorPos;
                distinctDoors.add(lower);
            }
        }

        BoundingBox bounds = new BoundingBox(minX - 1, minY - 1, minZ - 1,
            maxX + 1, maxY + 1, maxZ + 1);
        // Builder lane: the floor is the block under each bottom interior
        // cell. Read from cells the fill already visited (bounded by the same
        // MAX_VOLUME), never a second walk of the room.
        java.util.Map<Block, Integer> floorCounts = new java.util.HashMap<>();
        for (BlockPos cell : filled) {
            BlockPos below = cell.below();
            if (!filled.contains(below) && level.hasChunkAt(below)) {
                floorCounts.merge(level.getBlockState(below).getBlock(), 1, Integer::sum);
            }
        }
        return new Result(bounds, filled.size(), beds, distinctDoors.size(), lights,
            Math.min(8, furnishingHits.size()), enclosed, skyLeak,
            java.util.Map.copyOf(blockCounts), leakPos, skyLeakPos,
            countConnectedAleTaps(level, bounds, filled, boundarySeen),
            java.util.Map.copyOf(floorCounts),
            lowStairStep(level, interior, filled, boundarySeen));
    }

    /**
     * The step nearest the seed from which a settler cannot climb onto a
     * stair of this room for lack of headroom ({@link StairHeadroom}), or
     * null. Only bottom-half stairs the fill already met as boundary are
     * examined, and only when the cell in front of their low side is an
     * interior cell with a floor under it: somewhere a settler stands.
     */
    @Nullable
    static BlockPos lowStairStep(ServerLevel level, BlockPos interior,
                                 Set<BlockPos> filled, Set<BlockPos> boundary) {
        BlockPos best = null;
        for (BlockPos stair : boundary) {
            if (!level.hasChunkAt(stair)) continue;
            BlockState state = level.getBlockState(stair);
            if (!(state.getBlock() instanceof StairBlock)
                || state.getValue(StairBlock.HALF) != Half.BOTTOM) continue;
            Direction up = state.getValue(StairBlock.FACING);
            BlockPos approach = stair.relative(up.getOpposite());
            if (!filled.contains(approach) || filled.contains(approach.below())) continue;
            StairHeadroom.Cell blocker = StairHeadroom.blocker(stair.getX(), stair.getY(), stair.getZ(),
                up.getStepX(), up.getStepZ(), (x, y, z) -> clearForBody(level, new BlockPos(x, y, z), approach));
            if (blocker == null) continue;
            BlockPos step = approach.below();
            if (best == null || interior.distSqr(step) < interior.distSqr(best)) best = step.immutable();
        }
        return best;
    }

    /**
     * A cell leaves a centred settler body (0.6 wide) free: no collision box
     * reaches into the middle of it. Edge panels (ladders, open trapdoors,
     * door leaves) stay clear. Low coverings are allowed only at the approach floor.
     */
    private static boolean clearForBody(ServerLevel level, BlockPos pos, BlockPos approach) {
        if (!level.hasChunkAt(pos)) return true;
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return true;
        net.minecraft.world.phys.shapes.VoxelShape shape = state.getCollisionShape(level, pos);
        return clearForBody(shape, pos, approach);
    }

    static boolean clearForBody(net.minecraft.world.phys.shapes.VoxelShape shape,
                                BlockPos pos, BlockPos approach) {
        if (shape.isEmpty()) return true;
        if (pos.equals(approach) && shape.max(Direction.Axis.Y) <= IndoorBlocks.LOW_COVER_MAX_Y
            && shape.min(Direction.Axis.Y) <= 0.0D) return true;
        net.minecraft.world.phys.AABB body = new net.minecraft.world.phys.AABB(0.2D, 0.0D, 0.2D, 0.8D, 1.0D, 0.8D);
        for (net.minecraft.world.phys.AABB box : shape.toAabbs()) {
            if (box.intersects(body)) return false;
        }
        return true;
    }

    /**
     * Structural only: an empty barrel still makes a valid Tavern. Both parts
     * must belong to this exact observed room, not merely its enclosing box.
     * The tap's outlet must face an interior cell, so a fixture on the other
     * side of a shared wall cannot satisfy this room's requirement.
     */
    private static int countConnectedAleTaps(ServerLevel level, BoundingBox bounds,
                                             Set<BlockPos> filled, Set<BlockPos> boundary) {
        Set<BlockPos> observed = new HashSet<>(filled);
        observed.addAll(boundary);
        int pairs = 0;
        for (BlockPos tap : observed) {
            if (!bounds.isInside(tap) || !level.hasChunkAt(tap)) continue;
            BlockState state = level.getBlockState(tap);
            if (!(state.getBlock() instanceof AleTapBlock)) continue;
            Direction facing = state.getValue(AleTapBlock.FACING);
            BlockPos barrel = tap.relative(facing.getOpposite());
            if (!filled.contains(tap.relative(facing)) || !observed.contains(barrel)
                    || !bounds.isInside(barrel) || !level.hasChunkAt(barrel)) continue;
            if (level.getBlockState(barrel).is(Blocks.BARREL)
                    && level.getBlockEntity(barrel)
                        instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity) pairs++;
        }
        return pairs;
    }

    private static void classifyContents(ServerLevel level, BlockPos pos,
                                         BlockState state, Set<BlockPos> bedBlocks,
                                         List<BlockPos> beds,
                                         Set<Integer> furnishingHits,
                                         java.util.Map<Block, Integer> blockCounts,
                                         Set<BlockPos> counted) {
        // Interior cells can host carpets/pots; solid furnishing sits in the
        // floor ring below the interior — check both this cell and the floor.
        for (BlockPos p : new BlockPos[]{pos, pos.below()}) {
            BlockState s = p.equals(pos) ? state : level.getBlockState(p);
            if (s.getBlock() instanceof BedBlock && !bedBlocks.contains(p)) {
                bedBlocks.add(p);
                if (s.getValue(BedBlock.PART) == BedPart.HEAD) {
                    beds.add(p.immutable());
                }
            }
            for (int i = 0; i < FURNISHING_GROUPS.size(); i++) {
                if (FURNISHING_GROUPS.get(i).test(s)) {
                    furnishingHits.add(i);
                }
            }
            // Tally the furniture itself so building types can ask for it by
            // name ("four chests", "a lectern"). Positions are deduplicated:
            // a cell is examined both as interior and as the floor beneath
            // the cell above it, and a chest must not count twice.
            if (!s.isAir() && counted.add(p.immutable())) {
                blockCounts.merge(s.getBlock(), 1, Integer::sum);
            }
        }
    }

    /**
     * Interior = a cell the fill may pass through. Anything with a collision
     * shape counts as boundary (so slab and stair roofs enclose properly);
     * beds and carpets are the walkable-furniture exceptions.
     */
    private static boolean isPassable(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof BedBlock
            || state.getBlock() instanceof CarpetBlock
            || state.getBlock() instanceof net.minecraft.world.level.block.LadderBlock
                && state.getFluidState().isEmpty()) {
            return true;
        }
        return state.getFluidState().isEmpty()
            && state.getCollisionShape(level, pos).isEmpty();
    }

    /**
     * True when something solid stands above {@code pos} within roof range.
     * A roof further away than the fill's own height cap is not this room's
     * roof, so the search stops there.
     *
     * <p>A BARRIER is never a roof. Barriers are unobtainable in survival;
     * the one place the scanner meets them is a GameTest arena, whose
     * containment shell encases every test structure in barrier walls and a
     * barrier ceiling. Counting that shell as cover made an 8-block-tall
     * arena a world where no roof hole could ever read as open sky — the
     * scan's answer depended on the arena, not the room. Treating barrier
     * as world-edge (sky) restores the overworld's own semantics inside a
     * test arena and changes nothing a player can ever build.
     */
    private static boolean hasCoverAbove(ServerLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos probe = pos.mutable();
        int top = Math.min(level.getMaxBuildHeight() - 1, pos.getY() + MAX_HEIGHT);
        for (int y = pos.getY() + 1; y <= top; y++) {
            probe.setY(y);
            BlockState state = level.getBlockState(probe);
            if (state.is(Blocks.BARRIER)) {
                return false;
            }
            if (!(state.getBlock() instanceof net.minecraft.world.level.block.LadderBlock)
                && !state.getCollisionShape(level, probe).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Normalizes a seed (bed/door/solid) to an adjacent interior air cell. */
    @Nullable
    private static BlockPos findInteriorSeed(ServerLevel level, BlockPos seed) {
        BlockState state = level.getBlockState(seed);
        if (isPassable(level, seed, state)
            && !(state.getBlock() instanceof DoorBlock)) {
            return seed;
        }
        for (BlockPos candidate : new BlockPos[]{
            seed.above(), seed.north(), seed.south(), seed.east(), seed.west()}) {
            BlockState s = level.getBlockState(candidate);
            if (isPassable(level, candidate, s)
                && !(s.getBlock() instanceof DoorBlock)) {
                return candidate;
            }
        }
        return null;
    }

    private RoomScanner() {
    }
}
