package com.hearthstead.building;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Building levels by checklist -- the generic, pure core (owner, 26 Sep).
 *
 * <p>A building's level is what its room actually contains, measured by the
 * same plaque scan that registers it, with the same rules for a hand-built
 * room and one a Builder raised from a blueprint. Levels are cumulative:
 * level N is reached when every item of levels 1..N is met. Level 1 is
 * always exactly the building type's registration requirements, so a
 * registered building is never below level 1.
 *
 * <p>Upgrading is building what is missing. {@link #gap} names the unmet
 * items of the next level, and each item carries a {@link Fix} telling a
 * Builder how to add it (place a lantern on a wall, lay a solid floor) --
 * or that only the player can (a bigger room).
 *
 * <p>Generic over the measured thing {@code R} so the math is JUnit-tested
 * without a Minecraft world; {@link BuildingLevels} instantiates it with
 * {@code RoomScanner.Result}.
 */
public final class BuildingLevelChecklist {

    private BuildingLevelChecklist() {
    }

    /** Where a Builder puts a missing piece. */
    public enum Spot {
        /** On a wall, facing into the room (wall torch, wall sign). */
        WALL_MOUNT,
        /** Free floor cell with headroom, not in a walkway. */
        FLOOR_FREE,
        /** Free floor cell against a wall (chests, barrels, beds). */
        ALONG_WALL,
        /** Hanging from the ceiling (lantern). */
        CEILING
    }

    public enum FixKind {
        PLACE,
        REPLACE_FLOOR,
        HAND_ONLY
    }

    /**
     * How the missing piece can be added. Block ids are strings so this stays
     * pure; the Builder resolves them through the block registry and takes
     * the first one he actually has in stock.
     */
    public record Fix(FixKind kind, List<String> blockIds, Spot spot) {
        public static final Fix HAND_ONLY = new Fix(FixKind.HAND_ONLY, List.of(), null);

        public static Fix place(Spot spot, String... blockIds) {
            return new Fix(FixKind.PLACE, List.of(blockIds), spot);
        }

        public static Fix replaceFloor(String... blockIds) {
            return new Fix(FixKind.REPLACE_FLOOR, List.of(blockIds), null);
        }

        public boolean builderCanDo() {
            return kind != FixKind.HAND_ONLY && !blockIds.isEmpty();
        }
    }

    /**
     * One checklist line. {@code needed} is a function too, because some
     * items scale with the room (a solid floor needs every floor cell).
     */
    public record Item<R>(String id, ToIntFunction<R> have, ToIntFunction<R> needed, Fix fix) {

        public static <R> Item<R> atLeast(String id, int n, ToIntFunction<R> have, Fix fix) {
            return new Item<>(id, have, r -> n, fix);
        }

        public Gap measure(R result) {
            int h = Math.max(0, have.applyAsInt(result));
            int n = Math.max(0, needed.applyAsInt(result));
            return new Gap(id, h, n, fix);
        }
    }

    /** One level: its full item list (levels are cumulative by construction). */
    public record Level<R>(int level, String nameKey, List<Item<R>> items) {
    }

    /** A measured item: what is there, what is needed, how to fix it. */
    public record Gap(String id, int have, int needed, Fix fix) {
        public boolean met() {
            return have >= needed;
        }

        /** How many more pieces are missing (0 when met). */
        public int missing() {
            return Math.max(0, needed - have);
        }

        /** Lang key for the line, e.g. "hearthstead.requirement.lights". */
        public String langKey() {
            return "hearthstead.requirement." + id;
        }
    }

    /**
     * The highest level whose items (and all lower levels' items) are met,
     * or 0 when even level 1 is not -- an unregistered room.
     */
    public static <R> int levelOf(List<Level<R>> levels, R result) {
        int reached = 0;
        for (Level<R> level : levels) {
            for (Item<R> item : level.items()) {
                if (!item.measure(result).met()) {
                    return reached;
                }
            }
            reached = level.level();
        }
        return reached;
    }

    /**
     * The unmet items of the level after {@code current}; empty at the top
     * level. Only items not already satisfied are returned, in the table's
     * order, so the plaque can print "Next level: add ..." directly.
     */
    public static <R> List<Gap> gap(List<Level<R>> levels, R result, int current) {
        for (Level<R> level : levels) {
            if (level.level() == current + 1) {
                List<Gap> missing = new ArrayList<>();
                for (Item<R> item : level.items()) {
                    Gap g = item.measure(result);
                    if (!g.met()) {
                        missing.add(g);
                    }
                }
                return List.copyOf(missing);
            }
        }
        return List.of();
    }

    /** The highest level a table defines (1 when only registration exists). */
    public static <R> int maxLevel(List<Level<R>> levels) {
        int max = 0;
        for (Level<R> level : levels) {
            max = Math.max(max, level.level());
        }
        return max;
    }
}
