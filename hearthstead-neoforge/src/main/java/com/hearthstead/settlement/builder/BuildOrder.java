package com.hearthstead.settlement.builder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which phase a planned block belongs to, and the order a Builder works a
 * site in -- pure string/integer rules, covered by plain JUnit.
 *
 * <p>MineColonies' order (clear, solids, weak solids, decorations) is right
 * in spirit but reads as "random blocks appearing". This one reads as a
 * house being raised: the ground is cleared, the foundation laid, the walls
 * go up course by course, the roof closes above the eave, and only then do
 * doors, glass, lights and furniture arrive, with redstone last.
 */
public final class BuildOrder {

    /** A planned block for classification: template-local position + id. */
    public record Planned(int x, int y, int z, String blockId) {
    }

    private static final Set<String> REDSTONE_EXACT = Set.of(
        "minecraft:redstone_wire", "minecraft:repeater", "minecraft:comparator",
        "minecraft:lever", "minecraft:tripwire", "minecraft:tripwire_hook",
        "minecraft:observer", "minecraft:piston", "minecraft:sticky_piston",
        "minecraft:daylight_detector", "minecraft:redstone_torch",
        "minecraft:redstone_wall_torch", "minecraft:target",
        "minecraft:dispenser", "minecraft:dropper", "minecraft:hopper",
        "minecraft:redstone_lamp", "minecraft:note_block", "minecraft:sculk_sensor");

    /**
     * Id fragments of blocks that attach to something, break, or are
     * furniture. Ladders are deliberately NOT here: like stairs they are how
     * a Builder reaches the next floor, so they rise with the structure
     * (their wall is placed first in the same course; an unsupported step
     * is deferred, never forced).
     */
    private static final String[] INTERIOR_FRAGMENTS = {
        "torch", "lantern", "_door", "glass", "_bed", "carpet", "_sign",
        "trapdoor", "flower_pot", "potted_", "chest", "barrel", "crafting_table",
        "furnace", "smoker", "lectern", "bookshelf", "composter", "cauldron",
        "anvil", "grindstone", "loom", "stonecutter", "campfire", "banner", "candle",
        "bell", "cartography_table", "fletching_table", "smithing_table",
        "brewing_stand", "jukebox", "flower", "sapling", "button", "pressure_plate",
        "item_frame", "painting", "head", "skull", "chain", "hay_block",
        "plaque", "ale_tap", "fish_rack", "fishers_chair", "butchering_table",
        "tulip", "poppy", "dandelion", "orchid", "allium", "azure", "daisy",
        "cornflower", "lily_of_the_valley", "fern", "short_grass", "tall_grass"
    };

    private static final Set<String> FALLING = Set.of(
        "minecraft:sand", "minecraft:red_sand", "minecraft:gravel",
        "minecraft:suspicious_sand", "minecraft:suspicious_gravel",
        "minecraft:anvil", "minecraft:chipped_anvil", "minecraft:damaged_anvil",
        "minecraft:pointed_dripstone", "minecraft:dragon_egg", "minecraft:scaffolding");

    private BuildOrder() {
    }

    public static boolean isRedstone(String id) {
        return REDSTONE_EXACT.contains(id) || id.endsWith("_button")
            || id.endsWith("_pressure_plate") || id.endsWith("_rail")
            || id.equals("minecraft:rail");
    }

    /** Blocks that must wait for their surroundings: fragile, attached, furniture. */
    public static boolean isInterior(String id) {
        if (isRedstone(id)) {
            return false;
        }
        for (String fragment : INTERIOR_FRAGMENTS) {
            if (id.contains(fragment)) {
                // "glass" also matches tinted/stained glass blocks and panes:
                // all of them are fragile and go in after the frame.
                return true;
            }
        }
        return false;
    }

    /** Blocks gravity pulls down: placed only on a sturdy cell below. */
    public static boolean isFalling(String id) {
        return FALLING.contains(id) || id.endsWith("_concrete_powder");
    }

    /** Stairs and slabs: the roof-shaped blocks eave detection ignores. */
    static boolean roofShaped(String id) {
        return id.endsWith("_stairs") || id.endsWith("_slab");
    }

    /**
     * The eave line: the highest template y whose non-roof-shaped solid
     * blocks close a ring. For each layer the bounding box of its
     * structural blocks (not stairs/slabs, not interior blocks) is taken;
     * the layer counts as walls when at least 60% of that box's perimeter
     * is filled and the box is at least 3x3. A gable triangle above the
     * eave is a thin row whose ring is mostly empty, so it never qualifies.
     *
     * @return the eave y, or {@code Integer.MAX_VALUE} when no layer rings
     *     (a flat thing like a wall segment: nothing is "roof")
     */
    public static int eaveY(List<Planned> blocks) {
        Map<Integer, List<Planned>> byY = new HashMap<>();
        for (Planned p : blocks) {
            if (isAir(p.blockId()) || roofShaped(p.blockId()) || isInterior(p.blockId())
                || isRedstone(p.blockId())) {
                continue;
            }
            byY.computeIfAbsent(p.y(), y -> new ArrayList<>()).add(p);
        }
        int eave = Integer.MIN_VALUE;
        for (Map.Entry<Integer, List<Planned>> layer : byY.entrySet()) {
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            Set<Long> cells = new HashSet<>();
            for (Planned p : layer.getValue()) {
                minX = Math.min(minX, p.x());
                maxX = Math.max(maxX, p.x());
                minZ = Math.min(minZ, p.z());
                maxZ = Math.max(maxZ, p.z());
                cells.add(key(p.x(), p.z()));
            }
            int w = maxX - minX + 1;
            int d = maxZ - minZ + 1;
            if (w < 3 || d < 3) {
                continue;
            }
            int perimeter = 2 * (w + d) - 4;
            int filled = 0;
            for (int x = minX; x <= maxX; x++) {
                if (cells.contains(key(x, minZ))) filled++;
                if (cells.contains(key(x, maxZ))) filled++;
            }
            for (int z = minZ + 1; z < maxZ; z++) {
                if (cells.contains(key(minX, z))) filled++;
                if (cells.contains(key(maxX, z))) filled++;
            }
            if (filled * 10 >= perimeter * 6) {
                eave = Math.max(eave, layer.getKey());
            }
        }
        return eave == Integer.MIN_VALUE ? Integer.MAX_VALUE : eave;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    public static boolean isAir(String id) {
        return id.equals("minecraft:air") || id.equals("minecraft:cave_air")
            || id.equals("minecraft:void_air") || id.equals("minecraft:structure_void");
    }

    /**
     * The phase of one planned (non-air) block. Redstone beats interior,
     * interior beats roof: a lantern hanging under the roof still goes in
     * with the other lights, after the roof it hangs from.
     */
    public static BuildPhase classify(String blockId, int y, int groundLevel, int eaveY) {
        if (isRedstone(blockId)) {
            return BuildPhase.REDSTONE;
        }
        if (isInterior(blockId)) {
            return BuildPhase.INTERIOR;
        }
        if (y <= groundLevel) {
            return BuildPhase.FOUNDATION;
        }
        if (y > eaveY) {
            return BuildPhase.ROOF;
        }
        return BuildPhase.STRUCTURE;
    }

    /** One step's sort data. {@code x, y, z} are world or local, consistently. */
    public record Key(BuildPhase phase, int x, int y, int z) {
    }

    /**
     * The work order. Phase first; CLEAR and DISMANTLE top-down (so a sand
     * overhang or a roof comes off before what it rests on), everything
     * else bottom-up; within one layer a row-by-row snake (even rows +x,
     * odd rows -x) so the Builder walks along a wall instead of hopping.
     */
    public static final Comparator<Key> ORDER = (a, b) -> {
        int byPhase = Integer.compare(a.phase().ordinal(), b.phase().ordinal());
        if (byPhase != 0) {
            return byPhase;
        }
        int byY = a.phase().removes()
            ? Integer.compare(b.y(), a.y())
            : Integer.compare(a.y(), b.y());
        if (byY != 0) {
            return byY;
        }
        int byZ = Integer.compare(a.z(), b.z());
        if (byZ != 0) {
            return byZ;
        }
        boolean even = (a.z() & 1) == 0;
        return even ? Integer.compare(a.x(), b.x()) : Integer.compare(b.x(), a.x());
    };
}
