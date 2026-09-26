package com.hearthstead.building;

import com.hearthstead.building.BuildingLevelChecklist.Fix;
import com.hearthstead.building.BuildingLevelChecklist.Item;
import com.hearthstead.building.BuildingLevelChecklist.Level;
import com.hearthstead.building.BuildingLevelChecklist.Spot;
import com.hearthstead.settlement.RoomScanner;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * The per-type level tables for {@link BuildingLevelChecklist}, measured on a
 * plaque's {@link RoomScanner.Result}.
 *
 * <p>Level 1 of every type is exactly {@link BuildingType#requirements()} --
 * registration and level 1 are the same test, so no existing building ever
 * changes state because this table exists. Higher levels are cumulative: each
 * list is the full list for that level. WAREHOUSE numbers are the Warehouse
 * lane's (26 Sep): each level's container count equals the previous level's
 * managed capacity, so "fill your warehouse, then furnish it" unlocks more.
 *
 * <p>What a level DOES belongs to each building's own lane (the warehouse
 * reads it for capacity). This table only says what a level IS.
 */
public final class BuildingLevels {

    private static volatile Map<BuildingType, List<Level<RoomScanner.Result>>> tables;

    private BuildingLevels() {
    }

    /** The level table of a type (at least level 1). */
    public static List<Level<RoomScanner.Result>> of(BuildingType type) {
        Map<BuildingType, List<Level<RoomScanner.Result>>> t = tables;
        if (t == null) {
            t = build();
            tables = t;
        }
        return t.getOrDefault(type, List.of());
    }

    /** Checklist level of a scanned room for its type (0 = not registered). */
    public static int levelOf(BuildingType type, RoomScanner.Result result) {
        return result == null ? 0 : BuildingLevelChecklist.levelOf(of(type), result);
    }

    public static List<BuildingLevelChecklist.Gap> gap(BuildingType type,
                                                       RoomScanner.Result result,
                                                       int current) {
        return result == null ? List.of()
            : BuildingLevelChecklist.gap(of(type), result, current);
    }

    public static int maxLevel(BuildingType type) {
        return BuildingLevelChecklist.maxLevel(of(type));
    }

    // ------------------------------------------------------------ tables ---

    private static Map<BuildingType, List<Level<RoomScanner.Result>>> build() {
        Map<BuildingType, List<Level<RoomScanner.Result>>> map = new EnumMap<>(BuildingType.class);
        for (BuildingType type : BuildingType.values()) {
            List<Level<RoomScanner.Result>> levels = new ArrayList<>();
            List<Item<RoomScanner.Result>> l1 = levelOne(type);
            levels.add(new Level<>(1, "hearthstead.level.1", l1));
            switch (type) {
                case WAREHOUSE -> {
                    levels.add(new Level<>(2, "hearthstead.level.warehouse.2", List.of(
                        storage(16), ledgerDesk(1), lights(4), doors(1), floorSpace(25), solidFloor())));
                    levels.add(new Level<>(3, "hearthstead.level.warehouse.3", List.of(
                        storage(32), ledgerDesk(1), labels(4), lights(6), doors(1), floorSpace(100), solidFloor())));
                    levels.add(new Level<>(4, "hearthstead.level.warehouse.4", List.of(
                        storage(64), ledgerDesk(1), labels(8), lights(10), doors(2), floorSpace(200), solidFloor())));
                    levels.add(new Level<>(5, "hearthstead.level.warehouse.5", List.of(
                        storage(128), ledgerDesk(2), labels(16), lights(16), doors(2), floorSpace(320), solidFloor())));
                }
                case HOUSE -> {
                    levels.add(new Level<>(2, "hearthstead.level.2", concat(l1,
                        lights(2), storage(1), solidFloor())));
                    levels.add(new Level<>(3, "hearthstead.level.3", concat(l1,
                        lights(3), storage(1), solidFloor(), furnishing(3), workbench(1))));
                }
                case LODGING -> {
                    levels.add(new Level<>(2, "hearthstead.level.2", concat(l1,
                        lights(4), storage(2), solidFloor())));
                    levels.add(new Level<>(3, "hearthstead.level.3", concat(l1,
                        beds(6), lights(6), storage(2), solidFloor())));
                }
                case TAVERN -> levels.add(new Level<>(2, "hearthstead.level.2", concat(l1,
                    lights(5), seats(4), solidFloor())));
                case BARRACKS -> levels.add(new Level<>(2, "hearthstead.level.2", concat(l1,
                    beds(4), lights(4), solidFloor())));
                // Builder lane: a better-furnished hut takes on bigger work
                // (BuilderUnlocks.maxFootprint) and Upgrade Orders (L2).
                case BUILDERS_HUT -> {
                    levels.add(new Level<>(2, "hearthstead.level.2", concat(l1,
                        storage(4), lights(2), floorSpace(25), solidFloor())));
                    levels.add(new Level<>(3, "hearthstead.level.3", concat(l1,
                        storage(8), lights(4), floorSpace(36), solidFloor(), workbench(2))));
                }
                case WATCHTOWER -> levels.add(new Level<>(2, "hearthstead.level.2", concat(l1,
                    count("ladder", 6, Fix.HAND_ONLY, Blocks.LADDER), lights(6))));
                default -> {
                }
            }
            map.put(type, List.copyOf(levels));
        }
        return map;
    }

    @SafeVarargs
    private static List<Item<RoomScanner.Result>> concat(List<Item<RoomScanner.Result>> base,
                                                         Item<RoomScanner.Result>... more) {
        // A later item with the same id replaces the base line (lights(1) at
        // level 1 becomes lights(3) at level 3), keeping the base's order.
        List<Item<RoomScanner.Result>> out = new ArrayList<>(base);
        for (Item<RoomScanner.Result> item : more) {
            boolean replaced = false;
            for (int i = 0; i < out.size(); i++) {
                if (out.get(i).id().equals(item.id())) {
                    out.set(i, item);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                out.add(item);
            }
        }
        return List.copyOf(out);
    }

    /** Level 1 = the registration requirements, each with a Builder fix. */
    private static List<Item<RoomScanner.Result>> levelOne(BuildingType type) {
        List<Item<RoomScanner.Result>> items = new ArrayList<>();
        for (Requirement requirement : type.requirements()) {
            Requirement r = requirement;
            items.add(new Item<>(r.id(), result -> r.counter().applyAsInt(result),
                result -> r.needed(), fixFor(r.id())));
        }
        return List.copyOf(items);
    }

    /** How a Builder adds one of a level-1 requirement's pieces. */
    static Fix fixFor(String requirementId) {
        return switch (requirementId) {
            case "beds" -> Fix.place(Spot.ALONG_WALL, "minecraft:white_bed", "minecraft:red_bed",
                "minecraft:brown_bed", "minecraft:light_gray_bed");
            case "lights" -> Fix.place(Spot.CEILING, "minecraft:lantern");
            case "storage", "rod_barrel" -> Fix.place(Spot.ALONG_WALL, "minecraft:chest", "minecraft:barrel");
            case "workbench" -> Fix.place(Spot.ALONG_WALL, "minecraft:crafting_table");
            case "lectern" -> Fix.place(Spot.FLOOR_FREE, "minecraft:lectern");
            case "bookshelf" -> Fix.place(Spot.ALONG_WALL, "minecraft:bookshelf");
            case "composter" -> Fix.place(Spot.ALONG_WALL, "minecraft:composter");
            case "cauldron" -> Fix.place(Spot.ALONG_WALL, "minecraft:cauldron");
            case "grindstone" -> Fix.place(Spot.FLOOR_FREE, "minecraft:grindstone");
            case "oven", "forge" -> Fix.place(Spot.ALONG_WALL, "minecraft:furnace", "minecraft:smoker");
            case "smoker" -> Fix.place(Spot.ALONG_WALL, "minecraft:smoker");
            case "loom" -> Fix.place(Spot.ALONG_WALL, "minecraft:loom");
            case "fletching" -> Fix.place(Spot.ALONG_WALL, "minecraft:fletching_table");
            case "smithing_table" -> Fix.place(Spot.ALONG_WALL, "minecraft:smithing_table");
            case "anvil" -> Fix.place(Spot.FLOOR_FREE, "minecraft:anvil");
            case "sawbench" -> Fix.place(Spot.ALONG_WALL, "minecraft:stonecutter");
            case "hay" -> Fix.place(Spot.ALONG_WALL, "minecraft:hay_block");
            case "bell" -> Fix.place(Spot.FLOOR_FREE, "minecraft:bell");
            case "brewing_stand" -> Fix.place(Spot.FLOOR_FREE, "minecraft:brewing_stand");
            case "hearth_fire" -> Fix.place(Spot.FLOOR_FREE, "minecraft:campfire");
            case "dressed_stone" -> Fix.replaceFloor("minecraft:stone_bricks", "minecraft:smooth_stone");
            case "counter" -> Fix.place(Spot.ALONG_WALL, "minecraft:cartography_table");
            // Doors need a wall opening, room size needs walls moved, water
            // and ladders need geometry the Builder must not guess: by hand.
            default -> Fix.HAND_ONLY;
        };
    }

    // ------------------------------------------------------------- items ---

    private static Item<RoomScanner.Result> storage(int n) {
        return count("storage", n, fixFor("storage"), Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL);
    }

    private static Item<RoomScanner.Result> ledgerDesk(int n) {
        return count("ledger_desk", n, Fix.place(Spot.FLOOR_FREE, "minecraft:lectern",
            "minecraft:cartography_table"), Blocks.LECTERN, Blocks.CARTOGRAPHY_TABLE);
    }

    private static Item<RoomScanner.Result> workbench(int n) {
        return count("workbench", n, fixFor("workbench"), Blocks.CRAFTING_TABLE);
    }

    private static Item<RoomScanner.Result> lights(int n) {
        return Item.atLeast("lights", n, RoomScanner.Result::lights, fixFor("lights"));
    }

    private static Item<RoomScanner.Result> doors(int n) {
        return Item.atLeast("doors", n, RoomScanner.Result::doors, Fix.HAND_ONLY);
    }

    private static Item<RoomScanner.Result> beds(int n) {
        return Item.atLeast("beds", n, r -> r.beds().size(), fixFor("beds"));
    }

    private static Item<RoomScanner.Result> floorSpace(int n) {
        return Item.atLeast("floor_space", n, RoomScanner.Result::volume, Fix.HAND_ONLY);
    }

    private static Item<RoomScanner.Result> furnishing(int n) {
        return Item.atLeast("furnishing", n, RoomScanner.Result::furnishingScore,
            Fix.place(Spot.FLOOR_FREE, "minecraft:red_carpet", "minecraft:brown_carpet",
                "minecraft:white_carpet", "minecraft:bookshelf", "minecraft:loom"));
    }

    /** Signs of any kind (wall, standing, hanging) via the vanilla tag. */
    private static Item<RoomScanner.Result> labels(int n) {
        return Item.atLeast("labels", n, r -> countTag(r, BlockTags.ALL_SIGNS),
            Fix.place(Spot.WALL_MOUNT, "minecraft:oak_wall_sign", "minecraft:spruce_wall_sign"));
    }

    /** Stairs used as seats. */
    private static Item<RoomScanner.Result> seats(int n) {
        return Item.atLeast("seats", n, r -> countTag(r, BlockTags.STAIRS),
            Fix.place(Spot.FLOOR_FREE, "minecraft:oak_stairs", "minecraft:spruce_stairs"));
    }

    /**
     * Every floor cell built, none left as bare soil or loose ground. A
     * synthetic scan without floor data (0 cells) counts as met.
     */
    private static Item<RoomScanner.Result> solidFloor() {
        ToIntFunction<RoomScanner.Result> total = RoomScanner.Result::floorCells;
        ToIntFunction<RoomScanner.Result> solid = r -> r.floorCells() - softFloorCells(r);
        return new Item<>("solid_floor", solid, total,
            Fix.replaceFloor("minecraft:oak_planks", "minecraft:spruce_planks",
                "minecraft:cobblestone", "minecraft:stone_bricks"));
    }

    static int softFloorCells(RoomScanner.Result r) {
        int soft = 0;
        for (Map.Entry<Block, Integer> e : r.floorCounts().entrySet()) {
            if (isSoftFloor(e.getKey())) {
                soft += e.getValue();
            }
        }
        return soft;
    }

    public static boolean isSoftFloor(Block block) {
        return block == Blocks.DIRT || block == Blocks.GRASS_BLOCK || block == Blocks.COARSE_DIRT
            || block == Blocks.PODZOL || block == Blocks.ROOTED_DIRT || block == Blocks.MUD
            || block == Blocks.SAND || block == Blocks.RED_SAND || block == Blocks.GRAVEL
            || block == Blocks.DIRT_PATH || block == Blocks.MYCELIUM;
    }

    private static int countTag(RoomScanner.Result r,
                                net.minecraft.tags.TagKey<Block> tag) {
        int total = 0;
        for (Map.Entry<Block, Integer> e : r.blockCounts().entrySet()) {
            if (e.getKey().defaultBlockState().is(tag)) {
                total += e.getValue();
            }
        }
        return total;
    }

    private static Item<RoomScanner.Result> count(String id, int n, Fix fix, Block... kinds) {
        List<Block> accepted = List.of(kinds);
        return Item.atLeast(id, n, r -> r.countBlocks(accepted), fix);
    }
}
