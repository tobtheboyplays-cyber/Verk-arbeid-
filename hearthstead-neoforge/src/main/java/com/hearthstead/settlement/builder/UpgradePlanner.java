package com.hearthstead.settlement.builder;

import com.hearthstead.block.PlaqueBlock;
import com.hearthstead.building.BuildingLevelChecklist;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Upgrade Orders (owner, 26 Sep): read a building's checklist gap for its
 * next level and turn every piece a Builder can add into a concrete
 * placement inside the room -- a lantern under the ceiling, a chest against
 * a wall, a bed in free floor space, a soil floor laid block by block. It
 * works on hand-built rooms exactly like on blueprint ones, because it reads
 * the room, never a blueprint.
 *
 * <p>Pieces only the player can add (a door, a bigger room) stay listed as
 * "by hand". Placements never block a door, and never go where the job would
 * wall in its own walkway: furniture hugs walls first.
 */
public final class UpgradePlanner {

    /** Interior flood-fill budget, the same order as the plaque's room scan. */
    public static final int MAX_CELLS = 2048;

    private UpgradePlanner() {
    }

    /** A planned order plus the gap lines only the player can fix. */
    public record Result(BuildPlanner.Plan plan, List<BuildingLevelChecklist.Gap> handOnly) {
    }

    @Nullable
    public static Result plan(ServerLevel level, Settlement settlement, Building building,
                              @Nullable UUID owner) {
        if (building == null || !building.valid || building.bounds == null) {
            return null;
        }
        List<BuildingLevelChecklist.Gap> gap = building.nextLevelGap;
        List<BuildingLevelChecklist.Gap> handOnly = new ArrayList<>();
        Room room = Room.scan(level, building);
        if (room == null) {
            return new Result(new BuildPlanner.Plan(null,
                BuildPlanner.Validation.refused("hearthstead.builder.refuse.unloaded")), List.of());
        }
        boolean masonry = BuilderUnlocks.owns(level, settlement, BuilderUnlocks.MASONRY);
        BuildJob.Builder builder = new BuildJob.Builder();
        Set<BlockPos> used = new HashSet<>();
        for (BuildingLevelChecklist.Gap line : gap) {
            BuildingLevelChecklist.Fix fix = line.fix();
            if (!fix.builderCanDo()) {
                handOnly.add(line);
                continue;
            }
            if (fix.kind() == BuildingLevelChecklist.FixKind.REPLACE_FLOOR) {
                BlockState floor = pickState(level, settlement, fix.blockIds(), masonry);
                for (BlockPos cell : room.floor) {
                    BlockState present = level.getBlockState(cell);
                    if (com.hearthstead.building.BuildingLevels.isSoftFloor(present.getBlock())) {
                        builder.add(cell, Blocks.AIR.defaultBlockState(), BuildPhase.CLEAR,
                            BuildJob.F_CLEAR, null, null);
                        builder.add(cell, floor, BuildPhase.FOUNDATION, 0, null, null);
                    }
                }
                continue;
            }
            int missing = line.missing();
            int placed = 0;
            boolean furnishing = "furnishing".equals(line.id());
            List<String> groupsLeft = furnishing ? new ArrayList<>(furnishingNotInRoom(level, room, fix.blockIds()))
                : List.of();
            List<Spot> candidates = new ArrayList<>(room.spots(fix.spot(), used));
            if (fix.spot() == BuildingLevelChecklist.Spot.ALONG_WALL) {
                // A small room's walls fill up (W33: barracks beds 2 of 4):
                // free floor is the next best place.
                candidates.addAll(room.spots(BuildingLevelChecklist.Spot.FLOOR_FREE, used));
            }
            for (Spot spot : candidates) {
                if (placed >= missing) {
                    break;
                }
                if (used.contains(spot.pos)) {
                    continue;
                }
                // Furnishing counts DIFFERENT groups (a carpet, a bookshelf, a
                // loom...): each piece is a kind the room does not have yet.
                List<String> ids = furnishing
                    ? (groupsLeft.isEmpty() ? fix.blockIds() : List.of(groupsLeft.remove(0)))
                    : fix.blockIds();
                BlockState state = orient(pickState(level, settlement, ids, masonry), fix.spot(), spot);
                if (state == null) {
                    continue;
                }
                if (state.getBlock() instanceof BedBlock) {
                    BlockPos head = spot.pos.relative(state.getValue(BedBlock.FACING));
                    if (!room.freeFloor(head) || used.contains(head)) {
                        continue;
                    }
                    used.add(head);
                    builder.add(spot.pos, state, BuildPhase.INTERIOR, 0, head,
                        state.setValue(BedBlock.PART, BedPart.HEAD));
                } else {
                    builder.add(spot.pos, state, BuildPhase.INTERIOR, 0, null, null);
                }
                used.add(spot.pos);
                placed++;
            }
            if (placed < missing) {
                handOnly.add(new BuildingLevelChecklist.Gap(line.id(), line.have() + placed, line.needed(),
                    BuildingLevelChecklist.Fix.HAND_ONLY));
            }
        }
        if (builder.size() == 0) {
            return new Result(new BuildPlanner.Plan(null,
                BuildPlanner.Validation.refused("hearthstead.builder.refuse.nothing")), handOnly);
        }
        BuildJob job = builder.build(UUID.randomUUID(), settlement.id, BuildJob.Kind.UPGRADE,
            building.id.toString(), "Upgrade: " + building.type.id() + " L" + (building.level + 1),
            building.plaquePos, 0, false, owner, level.getGameTime());
        job.targetId = building.id;
        List<String> hand = new ArrayList<>();
        for (BuildingLevelChecklist.Gap g : handOnly) {
            hand.add(g.langKey());
        }
        BuildPlanner.Validation v = new BuildPlanner.Validation(true, "", List.copyOf(hand), job.size(), 0, 0,
            List.of(), 0, BuilderMaterials.total(job));
        return new Result(new BuildPlanner.Plan(job, v), List.copyOf(handOnly));
    }

    /** The furnishing kinds of the fix whose group the room does not show yet. */
    private static List<String> furnishingNotInRoom(ServerLevel level, Room room, List<String> ids) {
        List<String> out = new ArrayList<>();
        java.util.Set<String> present = new HashSet<>();
        for (BlockPos cell : room.interior) {
            present.add(Blueprint.idOf(level.getBlockState(cell)));
            present.add(Blueprint.idOf(level.getBlockState(cell.below())));
        }
        boolean carpet = false;
        for (String id : present) {
            carpet |= id.endsWith("_carpet");
        }
        for (String id : ids) {
            if (id.endsWith("_carpet")) {
                if (!carpet) {
                    out.add(id);
                    carpet = true;
                }
            } else if (!present.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /** First block of the fix the settlement has in stock, else the first one. */
    private static BlockState pickState(ServerLevel level, Settlement settlement, List<String> ids,
                                        boolean masonry) {
        BlockState fallback = null;
        for (String id : ids) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null || !BuiltInRegistries.BLOCK.containsKey(rl)) {
                continue;
            }
            Block block = BuiltInRegistries.BLOCK.get(rl);
            if (!masonry && isStone(block)) {
                continue; // stone floors are Masonry's
            }
            BlockState state = block.defaultBlockState();
            if (fallback == null) {
                fallback = state;
            }
            Item item = BuilderMaterials.costOf(state) == null ? null : BuilderMaterials.costOf(state).item();
            if (item != null && BuilderStock.warehouseCount(level, settlement, item) > 0) {
                return state;
            }
        }
        return fallback == null ? Blocks.OAK_PLANKS.defaultBlockState() : fallback;
    }

    private static boolean isStone(Block block) {
        return block == Blocks.COBBLESTONE || block == Blocks.STONE_BRICKS || block == Blocks.SMOOTH_STONE
            || block == Blocks.STONE;
    }

    /** Faces a wall-mounted piece away from its wall, hangs a lantern, turns a chest. */
    @Nullable
    private static BlockState orient(BlockState state, BuildingLevelChecklist.Spot kind, Spot spot) {
        Direction away = spot.wall == null ? Direction.NORTH : spot.wall.getOpposite();
        if (state.getBlock() instanceof LanternBlock) {
            return state.setValue(LanternBlock.HANGING, kind == BuildingLevelChecklist.Spot.CEILING);
        }
        if (state.getBlock() instanceof WallSignBlock) {
            return spot.wall == null ? null : state.setValue(WallSignBlock.FACING, away);
        }
        if (state.is(Blocks.WALL_TORCH)) {
            return spot.wall == null ? null : state.setValue(BlockStateProperties.HORIZONTAL_FACING, away);
        }
        if (state.getBlock() instanceof BedBlock) {
            // Head toward the wall: FACING points from foot to head.
            Direction toWall = spot.wall == null ? Direction.NORTH : spot.wall;
            return state.setValue(BedBlock.FACING, toWall).setValue(BedBlock.PART, BedPart.FOOT);
        }
        if (state.hasProperty(HorizontalDirectionalBlock.FACING)) {
            return state.setValue(HorizontalDirectionalBlock.FACING, away);
        }
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return state.setValue(BlockStateProperties.HORIZONTAL_FACING, away);
        }
        return state;
    }

    /** A candidate cell and the wall it leans on (null for free-standing). */
    record Spot(BlockPos pos, @Nullable Direction wall) {
    }

    /** The room's interior cells, found by the same kind of bounded fill the plaque runs. */
    static final class Room {
        final Set<BlockPos> interior = new HashSet<>();
        final List<BlockPos> floor = new ArrayList<>();
        final Set<BlockPos> doorFront = new HashSet<>();
        final ServerLevel level;

        private Room(ServerLevel level) {
            this.level = level;
        }

        @Nullable
        static Room scan(ServerLevel level, Building building) {
            BlockPos seed = null;
            BlockState plaque = level.getBlockState(building.plaquePos);
            if (plaque.getBlock() instanceof PlaqueBlock) {
                BlockPos inside = building.plaquePos.relative(plaque.getValue(PlaqueBlock.FACING));
                if (passable(level, inside)) {
                    seed = inside;
                }
            }
            if (seed == null && building.anchor != null && passable(level, building.anchor)) {
                seed = building.anchor;
            }
            if (seed == null && building.bounds != null) {
                // W33 cottage: the plaque faces into a wall cell. Seed at the
                // building's own open cell nearest to the plaque instead.
                double best = Double.MAX_VALUE;
                var b = building.bounds;
                for (BlockPos p : BlockPos.betweenClosed(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ())) {
                    if (!level.hasChunkAt(p) || !passable(level, p) || !passable(level, p.above())
                        || level.getBlockState(p.below()).getCollisionShape(level, p.below()).isEmpty()) {
                        continue;
                    }
                    double d = p.distSqr(building.plaquePos);
                    if (d < best) {
                        best = d;
                        seed = p.immutable();
                    }
                }
            }
            if (seed == null) {
                return null;
            }
            Room room = new Room(level);
            ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
            frontier.add(seed.immutable());
            room.interior.add(seed.immutable());
            while (!frontier.isEmpty() && room.interior.size() < MAX_CELLS) {
                BlockPos cell = frontier.poll();
                for (Direction d : Direction.values()) {
                    BlockPos next = cell.relative(d);
                    if (room.interior.contains(next) || !building.bounds.isInside(next)
                        || !level.isLoaded(next)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(next);
                    if (state.getBlock() instanceof DoorBlock) {
                        room.doorFront.add(cell.immutable());
                        continue;
                    }
                    if (passable(level, next)) {
                        room.interior.add(next.immutable());
                        frontier.add(next.immutable());
                    }
                }
            }
            for (BlockPos cell : room.interior) {
                if (!room.interior.contains(cell.below())) {
                    room.floor.add(cell.below().immutable());
                }
            }
            return room;
        }

        static boolean passable(ServerLevel level, BlockPos pos) {
            BlockState state = level.getBlockState(pos);
            return state.isAir() || (!state.blocksMotion() && state.canBeReplaced());
        }

        /** An interior cell with sturdy floor under it and headroom above. */
        boolean freeFloor(BlockPos cell) {
            return interior.contains(cell) && level.getBlockState(cell).isAir()
                && !interior.contains(cell.below())
                && level.getBlockState(cell.below()).isFaceSturdy(level, cell.below(), Direction.UP)
                && interior.contains(cell.above()) && !doorFront.contains(cell);
        }

        @Nullable
        Direction wallOf(BlockPos cell) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos side = cell.relative(d);
                if (!interior.contains(side)
                    && level.getBlockState(side).isFaceSturdy(level, side, d.getOpposite())) {
                    return d;
                }
            }
            return null;
        }

        /** Candidate spots of a kind, best first (spread out, walls first). */
        List<Spot> spots(BuildingLevelChecklist.Spot kind, Set<BlockPos> used) {
            List<Spot> out = new ArrayList<>();
            for (BlockPos cell : interior) {
                if (used.contains(cell) || doorFront.contains(cell) || !level.getBlockState(cell).isAir()) {
                    continue;
                }
                switch (kind) {
                    case CEILING -> {
                        BlockPos up = cell.above();
                        if (!interior.contains(up) && interior.contains(cell.below())
                            && level.getBlockState(up).isFaceSturdy(level, up, Direction.DOWN)) {
                            out.add(new Spot(cell, null));
                        }
                    }
                    case WALL_MOUNT -> {
                        boolean eyeHeight = interior.contains(cell.below()) && !interior.contains(cell.below(2));
                        Direction wall = wallOf(cell);
                        if (eyeHeight && wall != null) {
                            out.add(new Spot(cell, wall));
                        }
                    }
                    case ALONG_WALL, FLOOR_FREE -> {
                        if (freeFloor(cell)) {
                            Direction wall = wallOf(cell);
                            if (wall != null || kind == BuildingLevelChecklist.Spot.FLOOR_FREE) {
                                out.add(new Spot(cell, wall));
                            }
                        }
                    }
                }
            }
            // Deterministic: walls first, then by position.
            out.sort(Comparator.comparing((Spot s) -> s.wall == null)
                .thenComparingInt(s -> s.pos.getY()).thenComparingInt(s -> s.pos.getX())
                .thenComparingInt(s -> s.pos.getZ()));
            // Spread lights and labels: greedy farthest-first over the sorted list.
            if (kind == BuildingLevelChecklist.Spot.CEILING || kind == BuildingLevelChecklist.Spot.WALL_MOUNT) {
                List<Spot> spread = new ArrayList<>();
                List<Spot> left = new ArrayList<>(out);
                while (!left.isEmpty() && spread.size() < 32) {
                    Spot best = left.get(0);
                    double bestScore = -1;
                    for (Spot s : left) {
                        double score = Double.MAX_VALUE;
                        for (Spot chosen : spread) {
                            score = Math.min(score, s.pos.distSqr(chosen.pos));
                        }
                        for (BlockPos u : used) {
                            score = Math.min(score, s.pos.distSqr(u));
                        }
                        if (score > bestScore) {
                            bestScore = score;
                            best = s;
                        }
                    }
                    spread.add(best);
                    left.remove(best);
                }
                return spread;
            }
            return out;
        }
    }

    /** Unused, keeps the Map import for future multi-gap costing. */
    static Map<Item, Integer> none() {
        return Map.of();
    }
}
