package com.hearthstead.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * MINE V2 (owner 27 Sep: "a mine entrance like MineColonies"): the Miner's
 * next move in a ladder-shaft mine, worked out from the blocks alone.
 *
 * <p>The shaft is two columns wide. The <b>ladder column</b> starts at the
 * ladder standing at the Mine's shaft mouth; its ladders hang on the
 * <b>wall</b> behind it. The <b>dig column</b> is the cell in front of the
 * ladder. The shaft goes down one block at a time, in this order:
 * <ol>
 *   <li>standing in the dig column, he cuts the ladder-column block beside
 *       and below his feet and hangs a ladder in it at once;</li>
 *   <li>he climbs down that ladder and, from the bottom rung, cuts the
 *       dig-column block he stood on a moment ago;</li>
 *   <li>he steps off the ladder onto the new, lower floor.</li>
 * </ol>
 * So he never cuts the block he stands on, never cuts a ladder or the wall it
 * hangs on, and the ladder always reaches down to his level: the way up is
 * always a ladder, never a drop.
 *
 * <p>At the level floor he digs ONE lane (1 wide, 2 high) straight out from
 * the dig column, then stands at its end and works the <b>face</b>: the block
 * after the lane's last cell, which is never broken (each cut rolls ore from a
 * table instead, {@link MineFaceTable}). With the Deep Mine tech the shaft
 * continues to level 2 and a second lane is dug there.
 *
 * <p>Safety, checked before every cut: a block next to water or lava is never
 * opened; the fluid is sealed with a placed block first. Holes under the lane
 * floor are filled. Cells outside the claim or under another building are
 * never dug (the probe says so).
 *
 * <p>Pure: the world is read through {@link Probe}, so it is unit tested
 * without a level (MineShaftPlanTest). Stateless: it is asked again after
 * every action, so an interrupted Miner (night, a raid, a reload) simply
 * carries on.
 */
public final class MineShaftPlan {
    /** How far down a column is followed: bounds the work per question. */
    private static final int COLUMN_SCAN = 96;

    public enum Cell {
        /** Air, a torch, plants: a settler can stand in it. */
        OPEN,
        LADDER,
        /** Stone, ore, dirt, gravel: the Miner's to cut. */
        ROCK,
        /** Water or lava a placed block can replace. */
        FLUID,
        /** Solid and not his to cut: building blocks, bedrock, containers. */
        HARD,
        /** Holds fluid but is not replaceable (a waterlogged stair): never opened next to. */
        WET
    }

    public interface Probe {
        Cell at(BlockPos pos);

        /** Inside the claim, not under another building, above the depth limit. */
        boolean mayDig(BlockPos pos);

        /** A torch already stands here (optional decoration). */
        default boolean torch(BlockPos pos) {
            return false;
        }
    }

    /** The shaft: the ladder at the mouth (feet level of the ground) and the way it faces. */
    public record Site(BlockPos mouth, Direction facing) {
        public BlockPos ladder(int y) {
            return new BlockPos(mouth.getX(), y, mouth.getZ());
        }

        public BlockPos dig(int y) {
            return ladder(y).relative(facing);
        }

        public BlockPos wall(int y) {
            return ladder(y).relative(facing.getOpposite());
        }
    }

    public enum Kind {
        /** Cut {@code target}; if {@code ladderAfter}, hang a ladder in it at once. */
        DIG,
        /** Hang a ladder in the open {@code target}. */
        LADDER,
        /** Place a plain block (cobblestone) in {@code target}: seals fluid, fills a hole. */
        FILL,
        /** Place a torch in {@code target} (only offered when torches are in stock). */
        TORCH,
        /** Work the face block {@code target} forever; {@code level} picks the ore table. */
        FACE,
        /** Nothing safe to do; {@code why} says what. */
        BLOCKED
    }

    public record Step(Kind kind, BlockPos target, BlockPos stand, boolean ladderAfter, int level, String why) {
        static Step dig(BlockPos target, BlockPos stand, boolean ladderAfter) {
            return new Step(Kind.DIG, target, stand, ladderAfter, 0, "");
        }

        static Step of(Kind kind, BlockPos target, BlockPos stand) {
            return new Step(kind, target, stand, false, 0, "");
        }

        static Step blocked(String why) {
            return new Step(Kind.BLOCKED, null, null, false, 0, why);
        }

        /** The Miner needs a ladder in the chest before starting this step. */
        public boolean needsLadder() {
            return kind == Kind.LADDER || (kind == Kind.DIG && ladderAfter);
        }
    }

    private MineShaftPlan() {
    }

    /**
     * The next step. {@code floorOne} is the feet level of the level-1 lane;
     * {@code floorTwo} that of level 2, or {@code null} while Deep Mine is not
     * learned. {@code torches}: torches are in the Mine's chests.
     */
    public static Step next(Site site, Probe probe, int floorOne, Integer floorTwo, int lane, boolean torches) {
        int top = site.mouth().getY();
        if (floorOne > top - 3) {
            return Step.blocked("no room for a shaft above the depth limit");
        }
        if (probe.at(site.mouth()) != Cell.LADDER) {
            return Step.blocked("no ladder at the shaft mouth");
        }
        Cell mouthFront = probe.at(site.dig(top));
        if (mouthFront != Cell.OPEN) {
            return Step.blocked("the shaft mouth is covered");
        }
        int bottomL = bottom(probe, site.ladder(top));
        int bottomD = bottom(probe, site.dig(top));
        // 1. Every open cell of the ladder column holds a ladder, top down.
        for (int y = top; y >= bottomL; y--) {
            if (probe.at(site.ladder(y)) == Cell.OPEN) {
                Cell wall = probe.at(site.wall(y));
                BlockPos stand = standFor(site, bottomL, bottomD, y);
                if (wall == Cell.OPEN || wall == Cell.FLUID) {
                    return Step.of(Kind.FILL, site.wall(y), stand);
                }
                if (wall != Cell.ROCK && wall != Cell.HARD) {
                    return Step.blocked("no wall to hang a ladder on");
                }
                return Step.of(Kind.LADDER, site.ladder(y), stand);
            }
        }
        boolean two = floorTwo != null && floorTwo < floorOne;
        if (two && (bottomL < floorOne || bottomD < floorOne || laneDone(site, probe, floorOne, lane))) {
            Step s = shaft(site, probe, floorTwo, bottomL, bottomD);
            if (s != null) {
                return s;
            }
            return lane(site, probe, floorTwo, lane, torches, 2);
        }
        Step s = shaft(site, probe, floorOne, bottomL, bottomD);
        if (s != null) {
            return s;
        }
        return lane(site, probe, floorOne, lane, torches, 1);
    }

    /** Lowest cell of the open (air or ladder) run going down from {@code from}. */
    static int bottom(Probe probe, BlockPos from) {
        int y = from.getY();
        for (int i = 0; i < COLUMN_SCAN; i++) {
            Cell below = probe.at(new BlockPos(from.getX(), y - 1, from.getZ()));
            if (below != Cell.OPEN && below != Cell.LADDER) {
                break;
            }
            y--;
        }
        return y;
    }

    private static BlockPos standFor(Site site, int bottomL, int bottomD, int y) {
        return bottomD <= bottomL ? site.dig(bottomD) : site.ladder(bottomL);
    }

    /** The next shaft step toward {@code floor}, or null once both columns reach it. */
    private static Step shaft(Site site, Probe probe, int floor, int bottomL, int bottomD) {
        if (bottomL <= floor && bottomD <= floor) {
            // Arrived: the dig column must stand on something before the lane starts.
            BlockPos under = site.dig(floor - 1);
            Cell c = probe.at(under);
            if (bottomD == floor && (c == Cell.OPEN || c == Cell.FLUID)) {
                return Step.of(Kind.FILL, under, site.ladder(bottomL));
            }
            return null;
        }
        BlockPos target;
        BlockPos stand;
        boolean ladder;
        if (bottomL <= bottomD && bottomL < bottomD) {
            // The ladder column leads: from its bottom rung, cut the dig column down to it.
            target = site.dig(bottomD - 1);
            stand = site.ladder(bottomL);
            ladder = false;
            if (bottomD - 1 - bottomL > 2) {
                return Step.blocked("the dig column is out of reach");
            }
        } else {
            // Level (or the dig column leads): from the dig column, open the
            // next ladder cell beside and below him and hang its ladder.
            target = site.ladder(bottomL - 1);
            stand = site.dig(bottomD);
            ladder = true;
            if (bottomL - 1 - bottomD > 2) {
                return Step.blocked("the ladder column is out of reach");
            }
            // The new rung needs its wall: a cave behind it is walled up first.
            Cell wall = probe.at(site.wall(bottomL - 1));
            if (wall == Cell.OPEN || wall == Cell.FLUID) {
                return Step.of(Kind.FILL, site.wall(bottomL - 1), stand);
            }
            if (wall != Cell.ROCK && wall != Cell.HARD) {
                return Step.blocked("no wall to hang the next ladder on");
            }
        }
        return cut(probe, target, stand, ladder, "the shaft");
    }

    /** Dig {@code target} safely: fluid in it or next to it is sealed first. */
    private static Step cut(Probe probe, BlockPos target, BlockPos stand, boolean ladder, String what) {
        Cell c = probe.at(target);
        if (c == Cell.FLUID) {
            return Step.of(Kind.FILL, target, stand);
        }
        if (c != Cell.ROCK) {
            return Step.blocked(what + " runs into a block the Miner may not cut");
        }
        if (!probe.mayDig(target)) {
            return Step.blocked(what + " reaches the claim edge or another building");
        }
        for (Direction d : Direction.values()) {
            BlockPos n = target.relative(d);
            Cell nc = probe.at(n);
            if (nc == Cell.FLUID) {
                return Step.of(Kind.FILL, n, stand);
            }
            if (nc == Cell.WET) {
                return Step.blocked(what + " would open next to water");
            }
        }
        return Step.dig(target, stand, ladder);
    }

    static Direction[] laneDirections(Site site) {
        Direction f = site.facing();
        return new Direction[] {f, f.getClockWise(), f.getCounterClockWise()};
    }

    /** The lane direction: the one already started, else the first with room for all of it. */
    static Direction laneDirection(Site site, Probe probe, int floor, int lane) {
        BlockPos start = site.dig(floor);
        for (Direction d : laneDirections(site)) {
            BlockPos first = start.relative(d);
            if (probe.at(first) == Cell.OPEN && probe.at(first.above()) == Cell.OPEN) {
                return d;
            }
        }
        for (Direction d : laneDirections(site)) {
            boolean room = true;
            for (int i = 1; i <= lane + 1 && room; i++) {
                BlockPos c = start.relative(d, i);
                room = probe.mayDig(c) && probe.mayDig(c.above()) && probe.mayDig(c.below());
            }
            if (room) {
                return d;
            }
        }
        return null;
    }

    static boolean laneDone(Site site, Probe probe, int floor, int lane) {
        Direction d = laneDirection(site, probe, floor, lane);
        if (d == null) {
            return false;
        }
        BlockPos start = site.dig(floor);
        for (int i = 1; i <= lane; i++) {
            BlockPos c = start.relative(d, i);
            if (probe.at(c) != Cell.OPEN || probe.at(c.above()) != Cell.OPEN) {
                return false;
            }
        }
        return true;
    }

    private static Step lane(Site site, Probe probe, int floor, int lane, boolean torches, int level) {
        Direction d = laneDirection(site, probe, floor, lane);
        if (d == null) {
            return Step.blocked("no room for a lane at level " + level);
        }
        BlockPos start = site.dig(floor);
        for (int i = 1; i <= lane; i++) {
            BlockPos cell = start.relative(d, i);
            BlockPos stand = start.relative(d, i - 1);
            for (BlockPos part : new BlockPos[] {cell.above(), cell}) {
                Cell c = probe.at(part);
                if (c == Cell.OPEN) {
                    continue;
                }
                if (c == Cell.LADDER || c == Cell.HARD || c == Cell.WET) {
                    return Step.blocked("the lane runs into a block the Miner may not cut");
                }
                return cut(probe, part, stand, false, "the lane");
            }
            Cell floorCell = probe.at(cell.below());
            if (floorCell == Cell.OPEN || floorCell == Cell.FLUID) {
                return Step.of(Kind.FILL, cell.below(), stand);
            }
        }
        BlockPos end = start.relative(d, lane);
        BlockPos face = start.relative(d, lane + 1);
        Cell fc = probe.at(face);
        if (fc == Cell.OPEN || fc == Cell.FLUID) {
            return Step.of(Kind.FILL, face, end);
        }
        if (torches) {
            BlockPos first = start.relative(d, 1);
            if (!probe.torch(first) && probe.at(first) == Cell.OPEN) {
                return Step.of(Kind.TORCH, first, lane > 1 ? start.relative(d, 2) : start);
            }
            if (lane > 2 && !probe.torch(end.relative(d.getOpposite())) && probe.at(end.relative(d.getOpposite())) == Cell.OPEN) {
                return Step.of(Kind.TORCH, end.relative(d.getOpposite()), end);
            }
        }
        return new Step(Kind.FACE, face, end, false, level, "");
    }
}
