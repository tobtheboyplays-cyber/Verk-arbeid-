package com.hearthstead.building;

import com.hearthstead.building.BuildingLevelChecklist.Fix;
import com.hearthstead.building.BuildingLevelChecklist.Gap;
import com.hearthstead.building.BuildingLevelChecklist.Item;
import com.hearthstead.building.BuildingLevelChecklist.Level;
import com.hearthstead.building.BuildingLevelChecklist.Spot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildingLevelChecklistTest {

    /** A room measured as plain counts. */
    private record Room(Map<String, Integer> counts) {
        int get(String key) {
            return counts.getOrDefault(key, 0);
        }
    }

    private static final Fix LANTERN = Fix.place(Spot.CEILING, "minecraft:lantern");
    private static final Fix CHEST = Fix.place(Spot.ALONG_WALL, "minecraft:chest");

    private static List<Level<Room>> house() {
        Item<Room> bed1 = Item.atLeast("beds", 1, r -> r.get("beds"), Fix.place(Spot.ALONG_WALL, "minecraft:red_bed"));
        Item<Room> light1 = Item.atLeast("lights", 1, r -> r.get("lights"), LANTERN);
        Item<Room> light3 = Item.atLeast("lights", 3, r -> r.get("lights"), LANTERN);
        Item<Room> storage = Item.atLeast("storage", 1, r -> r.get("storage"), CHEST);
        Item<Room> floor = new Item<>("solid_floor", r -> r.get("solidFloor"), r -> r.get("floor"),
            Fix.replaceFloor("minecraft:oak_planks"));
        Item<Room> bigger = Item.atLeast("floor_space", 40, r -> r.get("volume"), Fix.HAND_ONLY);
        return List.of(
            new Level<>(1, "l1", List.of(bed1, light1)),
            new Level<>(2, "l2", List.of(bed1, light3, storage, floor)),
            new Level<>(3, "l3", List.of(bed1, light3, storage, floor, bigger)));
    }

    @Test
    void levelIsTheHighestFullyMetCumulativeLevel() {
        Room none = new Room(Map.of());
        Room l1 = new Room(Map.of("beds", 1, "lights", 1));
        Room l2 = new Room(Map.of("beds", 1, "lights", 3, "storage", 1, "floor", 20, "solidFloor", 20));
        Room l2only = new Room(Map.of("beds", 1, "lights", 3, "storage", 1, "floor", 20, "solidFloor", 20,
            "volume", 12));
        assertEquals(0, BuildingLevelChecklist.levelOf(house(), none));
        assertEquals(1, BuildingLevelChecklist.levelOf(house(), l1));
        assertEquals(2, BuildingLevelChecklist.levelOf(house(), l2));
        assertEquals(2, BuildingLevelChecklist.levelOf(house(), l2only));
    }

    @Test
    void aHigherLevelNeverCountsWhileALowerOneIsUnmet() {
        // Everything of L2/L3 but no bed: still level 0, never "level 3 without a bed".
        Room noBed = new Room(Map.of("lights", 5, "storage", 2, "floor", 10, "solidFloor", 10, "volume", 99));
        assertEquals(0, BuildingLevelChecklist.levelOf(house(), noBed));
    }

    @Test
    void gapNamesOnlyTheMissingPiecesOfTheNextLevel() {
        Room l1 = new Room(Map.of("beds", 1, "lights", 1, "floor", 20, "solidFloor", 12));
        List<Gap> gap = BuildingLevelChecklist.gap(house(), l1, 1);
        assertEquals(List.of("lights", "storage", "solid_floor"),
            gap.stream().map(Gap::id).toList());
        Gap lights = gap.get(0);
        assertEquals(1, lights.have());
        assertEquals(3, lights.needed());
        assertEquals(2, lights.missing());
        assertEquals("hearthstead.requirement.lights", lights.langKey());
        assertTrue(lights.fix().builderCanDo());
        Gap floor = gap.get(2);
        assertEquals(12, floor.have());
        assertEquals(20, floor.needed());
    }

    @Test
    void handOnlyItemsAreFlaggedAndTopLevelHasNoGap() {
        Room l2 = new Room(Map.of("beds", 1, "lights", 3, "storage", 1, "floor", 20, "solidFloor", 20,
            "volume", 12));
        List<Gap> gap = BuildingLevelChecklist.gap(house(), l2, 2);
        assertEquals(1, gap.size());
        assertFalse(gap.get(0).fix().builderCanDo());
        assertEquals(List.of(), BuildingLevelChecklist.gap(house(), l2, 3));
        assertEquals(3, BuildingLevelChecklist.maxLevel(house()));
    }

    @Test
    void aRoomWithoutFloorDataCountsItsFloorAsMet() {
        Item<Room> floor = new Item<>("solid_floor", r -> r.get("solidFloor"), r -> r.get("floor"),
            Fix.replaceFloor("minecraft:oak_planks"));
        assertTrue(floor.measure(new Room(Map.of())).met());
    }
}
