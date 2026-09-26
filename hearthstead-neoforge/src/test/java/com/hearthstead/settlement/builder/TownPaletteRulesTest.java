package com.hearthstead.settlement.builder;

import com.hearthstead.settlement.builder.TownPaletteRules.Role;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** "Match town style": the pure role rules (blueprint-artist lane). */
class TownPaletteRulesTest {

    @Test
    void placedBlocksAreBucketedByRole() {
        assertEquals(Role.WALL, TownPaletteRules.roleOfPlaced("minecraft:stone_bricks", false));
        assertEquals(Role.FLOOR, TownPaletteRules.roleOfPlaced("minecraft:spruce_planks", true));
        assertEquals(Role.WALL, TownPaletteRules.roleOfPlaced("minecraft:spruce_planks", false));
        assertEquals(Role.FRAME, TownPaletteRules.roleOfPlaced("minecraft:spruce_log", false));
        assertEquals(Role.FRAME, TownPaletteRules.roleOfPlaced("minecraft:stripped_dark_oak_log", false));
        assertEquals(Role.ROOF, TownPaletteRules.roleOfPlaced("minecraft:stone_brick_stairs", false));
        assertEquals(Role.ROOF, TownPaletteRules.roleOfPlaced("minecraft:spruce_slab", false));
        assertEquals(Role.TRIM, TownPaletteRules.roleOfPlaced("minecraft:spruce_fence", false));
        assertEquals(Role.TRIM, TownPaletteRules.roleOfPlaced("minecraft:dark_oak_door", false));
        assertEquals(Role.GLASS, TownPaletteRules.roleOfPlaced("minecraft:glass_pane", false));
    }

    @Test
    void terrainFurnitureAndOresAreNeverStyle() {
        assertNull(TownPaletteRules.roleOfPlaced("minecraft:stone", false));
        assertNull(TownPaletteRules.roleOfPlaced("minecraft:dirt", true));
        assertNull(TownPaletteRules.roleOfPlaced("minecraft:sand", false));
        assertNull(TownPaletteRules.roleOfPlaced("minecraft:gravel", false));
        assertNull(TownPaletteRules.roleOfPlaced("minecraft:oak_leaves", false));
        assertNull(TownPaletteRules.roleOfPlaced("minecraft:iron_ore", false));
        assertNull(TownPaletteRules.roleOfPlaced("minecraft:chest", false));
        assertNull(TownPaletteRules.roleOfPlaced("minecraft:crafting_table", false));
        assertNull(TownPaletteRules.roleOfPlaced("minecraft:torch", false));
    }

    @Test
    void blueprintCellsHaveAuthoredRoles() {
        int ground = 0;
        int eave = 4;
        assertEquals(Role.FLOOR, TownPaletteRules.roleOfCell("minecraft:oak_planks", 0, ground, eave));
        assertEquals(Role.WALL, TownPaletteRules.roleOfCell("minecraft:oak_planks", 2, ground, eave));
        assertEquals(Role.FRAME, TownPaletteRules.roleOfCell("minecraft:oak_log", 2, ground, eave));
        assertEquals(Role.ROOF, TownPaletteRules.roleOfCell("minecraft:oak_stairs", 5, ground, eave));
        assertEquals(Role.TRIM, TownPaletteRules.roleOfCell("minecraft:oak_stairs", 1, ground, eave), "a chair is trim, not roof");
        assertEquals(Role.TRIM, TownPaletteRules.roleOfCell("minecraft:oak_door", 1, ground, eave));
        assertNull(TownPaletteRules.roleOfCell("minecraft:cobblestone", 0, ground, eave), "the plinth is kept");
        assertEquals(Role.WALL, TownPaletteRules.roleOfCell("minecraft:cobblestone", 2, ground, eave));
        assertNull(TownPaletteRules.roleOfCell("minecraft:stone_bricks", 0, ground, eave), "dressed stone a checklist counts is kept");
        assertNull(TownPaletteRules.roleOfCell("hearthstead:plaque", 2, ground, eave));
        assertNull(TownPaletteRules.roleOfCell("minecraft:chest", 1, ground, eave));
    }

    @Test
    void substitutionKeepsTheShape() {
        assertEquals("minecraft:stone_bricks", TownPaletteRules.substitute(Role.WALL, "minecraft:oak_planks", "minecraft:stone_bricks"));
        assertEquals("minecraft:spruce_log", TownPaletteRules.substitute(Role.FRAME, "minecraft:oak_log", "minecraft:spruce_log"));
        assertEquals("minecraft:stone_brick_stairs", TownPaletteRules.substitute(Role.ROOF, "minecraft:oak_stairs", "minecraft:stone_brick_slab"));
        assertEquals("minecraft:spruce_slab", TownPaletteRules.substitute(Role.ROOF, "minecraft:oak_slab", "minecraft:spruce_stairs"));
        assertEquals("minecraft:spruce_fence_gate", TownPaletteRules.substitute(Role.TRIM, "minecraft:oak_fence_gate", "minecraft:spruce_fence"));
        assertEquals("minecraft:dark_oak_door", TownPaletteRules.substitute(Role.TRIM, "minecraft:oak_door", "minecraft:dark_oak_trapdoor"));
        assertEquals("minecraft:red_stained_glass_pane", TownPaletteRules.substitute(Role.GLASS, "minecraft:glass_pane", "minecraft:red_stained_glass"));
        assertNull(TownPaletteRules.substitute(Role.GLASS, "minecraft:glass_pane", "minecraft:glass_pane"));
        assertNull(TownPaletteRules.substitute(Role.WALL, "minecraft:oak_planks", null));
    }

    @Test
    void theTownsTopValidBlockWins() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("minecraft:sand", 900);           // pretend a gravity block slipped in
        counts.put("minecraft:stone_bricks", 300);
        counts.put("minecraft:spruce_planks", 120);
        assertEquals("minecraft:stone_bricks",
            TownPaletteRules.top(counts, id -> !id.equals("minecraft:sand")));
        assertEquals("minecraft:sand", TownPaletteRules.top(counts, id -> true));
        assertNull(TownPaletteRules.top(Map.of(), id -> true));
    }

    @Test
    void woodFamilies() {
        assertEquals("dark_oak", TownPaletteRules.woodFamily("minecraft:dark_oak_fence_gate"));
        assertEquals("spruce", TownPaletteRules.woodFamily("minecraft:stripped_spruce_log"));
        assertNull(TownPaletteRules.woodFamily("minecraft:stone_bricks"));
        assertEquals("stone_brick", TownPaletteRules.stairFamily("minecraft:stone_brick_stairs"));
    }
}
