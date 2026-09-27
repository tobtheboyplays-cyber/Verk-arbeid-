package com.hearthstead.settlement.builder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What one placed block state costs, in real items -- pure string rules so
 * the whole table is covered by plain JUnit.
 *
 * <p>Chest truth: a Builder never conjures a block, and he never charges
 * twice for one. The rules below exist because a block and the item that
 * places it are not always one-to-one:
 * <ul>
 *   <li>two-block things (doors, beds, tall plants) cost one item, charged on
 *       the half that the item actually places (door LOWER, bed FOOT);</li>
 *   <li>a double slab is two slab items;</li>
 *   <li>wall variants are placed by the floor item (wall torch by torch,
 *       wall sign by sign, wall banner by banner);</li>
 *   <li>fluids, fire and air cost nothing and are never placed as blocks;</li>
 *   <li>soil variants the Builder cannot pick up with an ordinary tool
 *       (grass block, dirt path, farmland) are laid as plain dirt and left to
 *       the world to green.</li>
 * </ul>
 */
public final class MaterialRules {

    /** One item cost. {@link #NONE} for blocks that cost nothing. */
    public record Cost(String itemId, int count) {
        public static final Cost NONE = new Cost("", 0);

        public boolean free() {
            return count <= 0 || itemId.isEmpty();
        }
    }

    private static final Set<String> FREE = Set.of(
        "minecraft:air", "minecraft:cave_air", "minecraft:void_air",
        "minecraft:water", "minecraft:lava", "minecraft:fire",
        "minecraft:soul_fire", "minecraft:bubble_column",
        "minecraft:structure_void", "minecraft:barrier",
        "minecraft:moving_piston", "minecraft:piston_head");

    /** Blocks laid as another item (soil the Builder lays as dirt, etc.). */
    private static final Map<String, String> SUBSTITUTE = Map.of(
        "minecraft:grass_block", "minecraft:dirt",
        "minecraft:dirt_path", "minecraft:dirt",
        "minecraft:farmland", "minecraft:dirt",
        "minecraft:mycelium", "minecraft:dirt",
        "minecraft:podzol", "minecraft:dirt",
        "minecraft:redstone_wire", "minecraft:redstone",
        "minecraft:tripwire", "minecraft:string",
        "minecraft:attached_melon_stem", "minecraft:melon_seeds",
        "minecraft:attached_pumpkin_stem", "minecraft:pumpkin_seeds");

    private MaterialRules() {
    }

    /**
     * The item and count that places {@code blockId} in the state described
     * by {@code properties} (vanilla property names and values).
     */
    public static Cost costOf(String blockId, Map<String, String> properties) {
        if (blockId == null || blockId.isEmpty() || FREE.contains(blockId)) {
            return Cost.NONE;
        }
        Map<String, String> props = properties == null ? Map.of() : properties;
        // Two-block things: only the half the item places is charged.
        String half = props.get("half");
        if (half != null && ("upper".equals(half))
            && (blockId.endsWith("_door") || isTallPlant(blockId))) {
            return Cost.NONE;
        }
        if (blockId.endsWith("_bed") && "head".equals(props.get("part"))) {
            return Cost.NONE;
        }
        if ("minecraft:piston_head".equals(blockId)) {
            return Cost.NONE;
        }
        String item = SUBSTITUTE.getOrDefault(blockId, blockId);
        item = wallToFloor(item);
        int count = 1;
        if (blockId.endsWith("_slab") && "double".equals(props.get("type"))) {
            count = 2;
        }
        // Stacked items (candles, sea pickles, turtle eggs) cost their count.
        String stacked = props.get("candles");
        if (stacked == null) stacked = props.get("pickles");
        if (stacked == null) stacked = props.get("eggs");
        if (stacked != null) {
            try {
                count = Math.max(1, Integer.parseInt(stacked));
            } catch (NumberFormatException ignored) {
                count = 1;
            }
        }
        // Snow layers are placed one layer per snowball-free snow item.
        if ("minecraft:snow".equals(blockId) && props.containsKey("layers")) {
            try {
                count = Math.max(1, Integer.parseInt(props.get("layers")));
            } catch (NumberFormatException ignored) {
                count = 1;
            }
        }
        return new Cost(item, count);
    }

    /**
     * Every item a block state costs. Almost always one; a potted plant is
     * two (the flower pot and the plant), because that is how a player
     * makes one.
     */
    public static List<Cost> costsOf(String blockId, Map<String, String> properties) {
        if (blockId != null && blockId.startsWith("minecraft:potted_")) {
            String plant = "minecraft:" + blockId.substring("minecraft:potted_".length());
            // potted_azalea_bush / potted_flowering_azalea_bush hold the azalea item.
            if (plant.endsWith("_bush")) {
                plant = plant.substring(0, plant.length() - "_bush".length());
            }
            return List.of(new Cost("minecraft:flower_pot", 1), new Cost(plant, 1));
        }
        Cost single = costOf(blockId, properties);
        return single.free() ? List.of() : List.of(single);
    }

    /** "minecraft:oak_wall_sign" -> "minecraft:oak_sign", etc. */
    static String wallToFloor(String id) {
        if (id.endsWith("_wall_torch")) {
            return id.replace("_wall_torch", "_torch");
        }
        if ("minecraft:wall_torch".equals(id)) {
            return "minecraft:torch";
        }
        if (id.endsWith("_wall_hanging_sign")) {
            return id.replace("_wall_hanging_sign", "_hanging_sign");
        }
        if (id.endsWith("_wall_sign")) {
            return id.replace("_wall_sign", "_sign");
        }
        if (id.endsWith("_wall_banner")) {
            return id.replace("_wall_banner", "_banner");
        }
        if (id.endsWith("_wall_head") || id.endsWith("_wall_skull")) {
            return id.replace("_wall_head", "_head").replace("_wall_skull", "_skull");
        }
        if (id.endsWith("_wall_fan")) {
            return id.replace("_wall_fan", "_fan");
        }
        return id;
    }

    private static boolean isTallPlant(String id) {
        return id.equals("minecraft:tall_grass") || id.equals("minecraft:large_fern")
            || id.equals("minecraft:sunflower") || id.equals("minecraft:lilac")
            || id.equals("minecraft:rose_bush") || id.equals("minecraft:peony")
            || id.equals("minecraft:pitcher_plant") || id.equals("minecraft:small_dripleaf");
    }

    /** One block of a blueprint, for {@link #total}. */
    public record Entry(String blockId, Map<String, String> properties) {
    }

    /**
     * The summed bill of materials of many blocks, in first-seen order so a
     * list shown to the player follows the build order rather than the
     * alphabet.
     */
    public static Map<String, Integer> total(List<Entry> blocks) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (Entry entry : blocks) {
            for (Cost cost : costsOf(entry.blockId(), entry.properties())) {
                totals.merge(cost.itemId(), cost.count(), Integer::sum);
            }
        }
        return totals;
    }

    /**
     * Whether a block already standing in the world satisfies a planned
     * state well enough that the Builder must not replace it (and must not
     * charge for it). Equivalent soil counts as satisfied, so grass creeping
     * onto a dirt floor can never keep a job from finishing.
     */
    public static boolean equivalent(String plannedId, String presentId) {
        if (plannedId.equals(presentId)) {
            return true;
        }
        return isSoil(plannedId) && isSoil(presentId);
    }

    /** How a block already standing compares with the planned state. */
    public enum Match {
        /** Satisfied: never replaced, never charged. */
        SAME,
        /** Same block and same material, only oriented differently: turned for free. */
        REORIENT,
        /** Different block or different material (a single slab for a double one). */
        DIFFERENT
    }

    /** Properties that carry material: changing them changes what the block costs. */
    private static final Set<String> MATERIAL_PROPS = Set.of("type", "candles", "pickles", "eggs", "layers",
        "bites", "charges", "flower_amount", "segment_amount");

    /**
     * Properties the world changes by itself (connections, water, power,
     * light, growth, computed stair shapes): never a reason to rebuild.
     */
    private static final Set<String> DYNAMIC_PROPS = Set.of("north", "east", "south", "west", "up", "down",
        "waterlogged", "powered", "open", "lit", "shape", "snowy", "distance", "persistent", "occupied",
        "triggered", "attached", "disarmed", "has_book", "has_record", "in_wall", "moisture", "age", "stage",
        "note", "instrument", "signal_fire", "power", "level", "enabled", "extended", "locked", "delay",
        "mode", "unstable", "drag", "bottom", "honey_level", "berries", "hatch", "tilt", "sculk_sensor_phase",
        "has_bottle_0", "has_bottle_1", "has_bottle_2", "conditional", "inverted", "short", "leaves");

    /**
     * State-aware satisfaction (Codex T3b P2): an id match alone never
     * satisfies a plan. Material-bearing properties must be equal (a single
     * slab is not a double slab, one candle is not four); orientation may be
     * corrected for free; world-driven properties are ignored. Soil variants
     * stay equivalent so creeping grass can never keep a job open.
     */
    public static Match compare(String plannedId, Map<String, String> planned,
                                String presentId, Map<String, String> present) {
        if (!plannedId.equals(presentId)) {
            return isSoil(plannedId) && isSoil(presentId) ? Match.SAME : Match.DIFFERENT;
        }
        boolean orientation = false;
        for (Map.Entry<String, String> e : planned.entrySet()) {
            String key = e.getKey();
            if (DYNAMIC_PROPS.contains(key)) {
                continue;
            }
            String have = present.get(key);
            if (e.getValue().equals(have)) {
                continue;
            }
            if (MATERIAL_PROPS.contains(key)) {
                return Match.DIFFERENT;
            }
            orientation = true;
        }
        return orientation ? Match.REORIENT : Match.SAME;
    }

    public static boolean isSoil(String id) {
        return id.equals("minecraft:dirt") || id.equals("minecraft:grass_block")
            || id.equals("minecraft:dirt_path") || id.equals("minecraft:podzol")
            || id.equals("minecraft:coarse_dirt") || id.equals("minecraft:rooted_dirt")
            || id.equals("minecraft:mycelium");
    }
}
