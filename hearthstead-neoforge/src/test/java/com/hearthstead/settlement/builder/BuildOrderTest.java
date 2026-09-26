package com.hearthstead.settlement.builder;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildOrderTest {

    /** A 7x7 timber cottage: floor at y0, walls y1..y3, a gable roof y4..y6. */
    private static List<BuildOrder.Planned> cottage() {
        List<BuildOrder.Planned> out = new ArrayList<>();
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                out.add(new BuildOrder.Planned(x, 0, z, "minecraft:cobblestone"));
            }
        }
        for (int y = 1; y <= 3; y++) {
            for (int x = 0; x < 7; x++) {
                for (int z = 0; z < 7; z++) {
                    boolean ring = x == 0 || x == 6 || z == 0 || z == 6;
                    if (!ring) {
                        continue;
                    }
                    boolean corner = (x == 0 || x == 6) && (z == 0 || z == 6);
                    boolean window = y == 2 && (x == 3 || z == 3);
                    String id = corner ? "minecraft:oak_log" : window ? "minecraft:glass_pane" : "minecraft:oak_planks";
                    out.add(new BuildOrder.Planned(x, y, z, id));
                }
            }
        }
        // Gable ends (planks, a narrowing triangle) and stair roof slopes.
        for (int y = 4; y <= 6; y++) {
            int inset = y - 4;
            for (int x = inset; x < 7 - inset; x++) {
                out.add(new BuildOrder.Planned(x, y, 0, "minecraft:oak_planks"));
                out.add(new BuildOrder.Planned(x, y, 6, "minecraft:oak_planks"));
            }
            for (int z = 0; z < 7; z++) {
                out.add(new BuildOrder.Planned(inset - 1 < 0 ? 0 : inset - 1, y, z, "minecraft:oak_stairs"));
                out.add(new BuildOrder.Planned(7 - inset, y, z, "minecraft:oak_stairs"));
            }
        }
        out.add(new BuildOrder.Planned(3, 1, 0, "minecraft:oak_door"));
        out.add(new BuildOrder.Planned(3, 3, 3, "minecraft:lantern"));
        out.add(new BuildOrder.Planned(2, 1, 2, "minecraft:red_bed"));
        return out;
    }

    @Test
    void eaveIsTheTopWallCourseNotTheGable() {
        assertEquals(3, BuildOrder.eaveY(cottage()));
    }

    @Test
    void aFlatWallSegmentHasNoRoof() {
        List<BuildOrder.Planned> wall = new ArrayList<>();
        for (int x = 0; x < 10; x++) {
            for (int y = 0; y < 3; y++) {
                wall.add(new BuildOrder.Planned(x, y, 0, "minecraft:spruce_log"));
            }
        }
        assertEquals(Integer.MAX_VALUE, BuildOrder.eaveY(wall));
    }

    @Test
    void classificationFollowsTheHouse() {
        int eave = 3;
        assertEquals(BuildPhase.FOUNDATION, BuildOrder.classify("minecraft:cobblestone", 0, 0, eave));
        assertEquals(BuildPhase.STRUCTURE, BuildOrder.classify("minecraft:oak_planks", 2, 0, eave));
        assertEquals(BuildPhase.ROOF, BuildOrder.classify("minecraft:oak_stairs", 4, 0, eave));
        assertEquals(BuildPhase.ROOF, BuildOrder.classify("minecraft:oak_planks", 5, 0, eave));
        // Fragile / attached / furniture: interior, whatever the height.
        for (String id : List.of("minecraft:oak_door", "minecraft:glass_pane", "minecraft:glass",
            "minecraft:wall_torch", "minecraft:lantern", "minecraft:red_bed", "minecraft:chest",
            "minecraft:crafting_table", "minecraft:white_carpet", "minecraft:oak_wall_sign",
            "minecraft:flower_pot", "minecraft:potted_poppy", "minecraft:oak_trapdoor",
            "hearthstead:plaque", "minecraft:barrel", "minecraft:campfire")) {
            assertEquals(BuildPhase.INTERIOR, BuildOrder.classify(id, 5, 0, eave), id);
        }
        // Ladders and stairs rise with the structure: they reach the next floor.
        assertEquals(BuildPhase.STRUCTURE, BuildOrder.classify("minecraft:ladder", 2, 0, eave));
        assertEquals(BuildPhase.STRUCTURE, BuildOrder.classify("minecraft:oak_stairs", 2, 0, eave));
    }

    @Test
    void redstoneGoesLastAndBeatsInterior() {
        for (String id : List.of("minecraft:redstone_wire", "minecraft:repeater", "minecraft:comparator",
            "minecraft:lever", "minecraft:stone_button", "minecraft:oak_pressure_plate",
            "minecraft:redstone_torch", "minecraft:redstone_wall_torch", "minecraft:piston",
            "minecraft:observer", "minecraft:rail", "minecraft:powered_rail", "minecraft:hopper")) {
            assertTrue(BuildOrder.isRedstone(id), id);
            assertEquals(BuildPhase.REDSTONE, BuildOrder.classify(id, 1, 0, 3), id);
        }
        assertFalse(BuildOrder.isRedstone("minecraft:torch"));
    }

    @Test
    void fallingBlocksAreKnown() {
        assertTrue(BuildOrder.isFalling("minecraft:sand"));
        assertTrue(BuildOrder.isFalling("minecraft:gravel"));
        assertTrue(BuildOrder.isFalling("minecraft:white_concrete_powder"));
        assertTrue(BuildOrder.isFalling("minecraft:anvil"));
        assertFalse(BuildOrder.isFalling("minecraft:sandstone"));
    }

    @Test
    void orderIsPhaseThenBottomUpClearTopDownAndSnakeRows() {
        List<BuildOrder.Key> keys = new ArrayList<>(List.of(
            new BuildOrder.Key(BuildPhase.ROOF, 0, 5, 0),
            new BuildOrder.Key(BuildPhase.STRUCTURE, 0, 2, 0),
            new BuildOrder.Key(BuildPhase.CLEAR, 0, 1, 0),
            new BuildOrder.Key(BuildPhase.CLEAR, 0, 4, 0),
            new BuildOrder.Key(BuildPhase.STRUCTURE, 0, 1, 0),
            new BuildOrder.Key(BuildPhase.INTERIOR, 0, 1, 0),
            new BuildOrder.Key(BuildPhase.FOUNDATION, 0, 0, 0),
            new BuildOrder.Key(BuildPhase.REDSTONE, 0, 0, 0),
            new BuildOrder.Key(BuildPhase.FINISH, 0, 0, 0),
            new BuildOrder.Key(BuildPhase.FILL, 0, -1, 0)));
        keys.sort(BuildOrder.ORDER);
        assertEquals(new BuildOrder.Key(BuildPhase.CLEAR, 0, 4, 0), keys.get(0)); // top-down
        assertEquals(new BuildOrder.Key(BuildPhase.CLEAR, 0, 1, 0), keys.get(1));
        assertEquals(BuildPhase.FILL, keys.get(2).phase());
        assertEquals(BuildPhase.FOUNDATION, keys.get(3).phase());
        assertEquals(new BuildOrder.Key(BuildPhase.STRUCTURE, 0, 1, 0), keys.get(4)); // bottom-up
        assertEquals(new BuildOrder.Key(BuildPhase.STRUCTURE, 0, 2, 0), keys.get(5));
        assertEquals(BuildPhase.ROOF, keys.get(6).phase());
        assertEquals(BuildPhase.INTERIOR, keys.get(7).phase());
        assertEquals(BuildPhase.REDSTONE, keys.get(8).phase());
        assertEquals(BuildPhase.FINISH, keys.get(9).phase());
        // Snake: even z rows run +x, odd rows -x.
        List<BuildOrder.Key> row = new ArrayList<>(List.of(
            new BuildOrder.Key(BuildPhase.STRUCTURE, 2, 1, 1), new BuildOrder.Key(BuildPhase.STRUCTURE, 0, 1, 1),
            new BuildOrder.Key(BuildPhase.STRUCTURE, 2, 1, 0), new BuildOrder.Key(BuildPhase.STRUCTURE, 0, 1, 0)));
        row.sort(BuildOrder.ORDER);
        assertEquals(0, row.get(0).x());
        assertEquals(2, row.get(1).x());
        assertEquals(2, row.get(2).x());
        assertEquals(0, row.get(3).x());
    }

    @Test
    void ordering4096StepsIsCheap() {
        Random random = new Random(7);
        List<BuildOrder.Key> keys = new ArrayList<>();
        List<BuildOrder.Planned> planned = new ArrayList<>();
        BuildPhase[] phases = BuildPhase.values();
        for (int i = 0; i < 4096; i++) {
            int x = random.nextInt(24);
            int y = random.nextInt(16);
            int z = random.nextInt(24);
            keys.add(new BuildOrder.Key(phases[random.nextInt(phases.length)], x, y, z));
            planned.add(new BuildOrder.Planned(x, y, z, "minecraft:oak_planks"));
        }
        long start = System.nanoTime();
        for (int round = 0; round < 10; round++) {
            List<BuildOrder.Key> copy = new ArrayList<>(keys);
            copy.sort(BuildOrder.ORDER);
            BuildOrder.eaveY(planned);
        }
        long perRoundMs = (System.nanoTime() - start) / 10 / 1_000_000L;
        // Planning happens once per order, never per tick; even so it must be
        // far below one 50 ms tick.
        assertTrue(perRoundMs < 50, "ordering 4096 steps took " + perRoundMs + " ms");
    }
}
