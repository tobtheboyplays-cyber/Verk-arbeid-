package com.hearthstead.settlement.builder;

import java.util.List;
import java.util.Map;

/**
 * "Match town style" -- the pure half (plain strings, JUnit-tested).
 *
 * <p>A town's style is the blocks its people actually built with, bucketed by
 * ROLE: {@link Role#WALL}, {@link Role#FRAME}, {@link Role#ROOF},
 * {@link Role#FLOOR}, {@link Role#TRIM} and {@link Role#GLASS}. A blueprint
 * authored in the Elmfield palette (oak frame, oak/spruce/birch plank infill,
 * cobblestone, oak stairs and slabs, oak trim, glass panes) is re-skinned
 * role by role to the town's top block for that role.
 *
 * <p>Structure safety is decided by the caller (it has the registry): a
 * WALL/FLOOR/FRAME substitute must be a full, non-falling block without a
 * block entity; ROOF needs the family's stairs AND slab; TRIM needs the wood
 * family's variant. Anything the town has no valid block for stays as
 * authored.
 */
public final class TownPaletteRules {

    public enum Role { WALL, FRAME, ROOF, FLOOR, TRIM, GLASS }

    private static final List<String> NATURAL = List.of(
        "minecraft:stone", "minecraft:dirt", "minecraft:grass_block", "minecraft:coarse_dirt", "minecraft:podzol",
        "minecraft:sand", "minecraft:red_sand", "minecraft:gravel", "minecraft:clay", "minecraft:sandstone",
        "minecraft:deepslate", "minecraft:tuff", "minecraft:granite", "minecraft:diorite", "minecraft:andesite",
        "minecraft:calcite", "minecraft:netherrack", "minecraft:snow_block", "minecraft:ice", "minecraft:mud",
        "minecraft:dirt_path", "minecraft:farmland", "minecraft:water", "minecraft:lava", "minecraft:bedrock");

    private TownPaletteRules() {
    }

    /**
     * The role a PLACED block plays in a town, or null when it is not a
     * building material (terrain, furniture, plants, ores).
     *
     * @param floorLevel true for the block under a building's lowest interior layer
     */
    public static Role roleOfPlaced(String id, boolean floorLevel) {
        if (id == null || NATURAL.contains(id) || id.endsWith("_ore") || id.endsWith("_leaves")) {
            return null;
        }
        String path = path(id);
        if (path.contains("glass")) {
            return Role.GLASS;
        }
        if (path.endsWith("_stairs") || path.endsWith("_slab")) {
            return Role.ROOF;
        }
        if (path.endsWith("_fence") || path.endsWith("_fence_gate") || path.endsWith("_trapdoor")
            || path.endsWith("_door")) {
            return Role.TRIM;
        }
        if (path.endsWith("_log") || path.endsWith("_wood")) {
            return Role.FRAME;
        }
        if (isWallMaterial(path)) {
            return floorLevel ? Role.FLOOR : Role.WALL;
        }
        return null;
    }

    static boolean isWallMaterial(String path) {
        return path.endsWith("_planks") || path.endsWith("bricks") || path.equals("cobblestone")
            || path.equals("mossy_cobblestone") || path.endsWith("terracotta") || path.endsWith("_concrete")
            || path.startsWith("polished_") || path.startsWith("smooth_") || path.startsWith("cut_")
            || path.startsWith("chiseled_") || path.equals("quartz_block") || path.endsWith("_tiles")
            || path.equals("packed_mud") || path.endsWith("_wool");
    }

    /**
     * The role a blueprint CELL plays in the authored (Elmfield) palette, or
     * null when the cell is not re-skinned (furniture, the plaque, lights,
     * plants, special stone like stone bricks the checklist may count).
     */
    public static Role roleOfCell(String id, int y, int groundLevel, int eaveY) {
        String path = path(id);
        if (!id.startsWith("minecraft:")) {
            return null;
        }
        if (path.equals("oak_log") || path.equals("spruce_log")) {
            return Role.FRAME;
        }
        if (path.equals("oak_planks") || path.equals("spruce_planks") || path.equals("birch_planks")) {
            return y <= groundLevel ? Role.FLOOR : Role.WALL;
        }
        if ((path.equals("cobblestone") || path.equals("mossy_cobblestone")) && y > groundLevel) {
            return Role.WALL;
        }
        if ((path.startsWith("oak_") || path.startsWith("spruce_") || path.startsWith("dark_oak_")
                || path.startsWith("cobblestone_"))
            && (path.endsWith("_stairs") || path.endsWith("_slab"))) {
            return y >= eaveY ? Role.ROOF : (path.startsWith("cobblestone_") ? null : Role.TRIM);
        }
        if ((path.startsWith("oak_") || path.startsWith("spruce_"))
            && (path.endsWith("_fence") || path.endsWith("_fence_gate") || path.endsWith("_trapdoor")
                || path.endsWith("_door") || path.endsWith("_pressure_plate"))) {
            return Role.TRIM;
        }
        if (path.equals("glass_pane")) {
            return Role.GLASS;
        }
        return null;
    }

    /** "minecraft:spruce_stairs" -> "spruce"; "minecraft:stone_brick_slab" -> "stone_brick". */
    public static String stairFamily(String id) {
        String path = path(id);
        if (path.endsWith("_stairs")) {
            return path.substring(0, path.length() - "_stairs".length());
        }
        if (path.endsWith("_slab")) {
            return path.substring(0, path.length() - "_slab".length());
        }
        return null;
    }

    /** The wood family of a trim or plank or log id ("minecraft:dark_oak_fence" -> "dark_oak"), or null. */
    public static String woodFamily(String id) {
        String path = path(id);
        for (String suffix : new String[]{"_fence_gate", "_fence", "_trapdoor", "_door", "_pressure_plate",
            "_planks", "_log", "_wood", "_stairs", "_slab"}) {
            if (path.endsWith(suffix)) {
                String fam = path.substring(0, path.length() - suffix.length());
                if (fam.startsWith("stripped_")) {
                    fam = fam.substring("stripped_".length());
                }
                return WOODS.contains(fam) ? fam : null;
            }
        }
        return null;
    }

    public static final List<String> WOODS = List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak",
        "mangrove", "cherry", "bamboo", "crimson", "warped");

    /**
     * The id a cell of role {@code role} becomes in a town whose top block
     * for that role is {@code townBlock} (both "namespace:path"), or null to
     * keep the cell as authored. Only structural shape is decided here; the
     * caller checks that the result exists and is structurally valid.
     */
    public static String substitute(Role role, String cellId, String townBlock) {
        if (townBlock == null) {
            return null;
        }
        String cell = path(cellId);
        String ns = townBlock.substring(0, townBlock.indexOf(':') + 1);
        switch (role) {
            case WALL, FLOOR, FRAME -> {
                return townBlock;
            }
            case ROOF -> {
                String fam = stairFamily(townBlock);
                if (fam == null) {
                    return null;
                }
                return ns + fam + (cell.endsWith("_stairs") ? "_stairs" : "_slab");
            }
            case TRIM -> {
                String wood = woodFamily(townBlock);
                String own = woodFamily(cellId);
                if (wood == null || own == null) {
                    return null;
                }
                return ns + wood + cell.substring(own.length());
            }
            case GLASS -> {
                String t = path(townBlock);
                if (t.endsWith("stained_glass") || t.endsWith("stained_glass_pane")) {
                    String color = t.substring(0, t.indexOf("_stained_glass"));
                    return ns + color + "_stained_glass_pane";
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    /** The most-used id in {@code counts} that {@code valid} accepts, or null. */
    public static String top(Map<String, Integer> counts, java.util.function.Predicate<String> valid) {
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount || (e.getValue() == bestCount && best != null && e.getKey().compareTo(best) < 0)) {
                if (valid.test(e.getKey())) {
                    best = e.getKey();
                    bestCount = e.getValue();
                }
            }
        }
        return best;
    }

    static String path(String id) {
        int i = id.indexOf(':');
        return i < 0 ? id : id.substring(i + 1);
    }
}
