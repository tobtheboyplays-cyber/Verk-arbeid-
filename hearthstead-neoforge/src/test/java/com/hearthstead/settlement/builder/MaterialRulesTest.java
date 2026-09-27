package com.hearthstead.settlement.builder;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialRulesTest {

    private static MaterialRules.Cost cost(String id, String... props) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        for (int i = 0; i + 1 < props.length; i += 2) {
            map.put(props[i], props[i + 1]);
        }
        return MaterialRules.costOf(id, map);
    }

    @Test
    void plainBlocksCostThemselves() {
        assertEquals(new MaterialRules.Cost("minecraft:oak_planks", 1), cost("minecraft:oak_planks"));
        assertEquals(new MaterialRules.Cost("minecraft:oak_stairs", 1),
            cost("minecraft:oak_stairs", "facing", "north", "half", "bottom", "shape", "straight"));
    }

    @Test
    void doorsAndBedsAreChargedOnceOnTheHalfTheItemPlaces() {
        assertEquals(1, cost("minecraft:oak_door", "half", "lower").count());
        assertTrue(cost("minecraft:oak_door", "half", "upper").free());
        assertEquals(1, cost("minecraft:red_bed", "part", "foot").count());
        assertTrue(cost("minecraft:red_bed", "part", "head").free());
        assertTrue(cost("minecraft:tall_grass", "half", "upper").free());
        assertEquals(1, cost("minecraft:tall_grass", "half", "lower").count());
        // A stair's "half" is top/bottom, never "upper": always charged.
        assertEquals(1, cost("minecraft:oak_stairs", "half", "top").count());
        // A trapdoor too.
        assertEquals(1, cost("minecraft:oak_trapdoor", "half", "top").count());
    }

    @Test
    void doubleSlabsCostTwo() {
        assertEquals(2, cost("minecraft:oak_slab", "type", "double").count());
        assertEquals(1, cost("minecraft:oak_slab", "type", "bottom").count());
        assertEquals(1, cost("minecraft:oak_slab", "type", "top").count());
    }

    @Test
    void wallVariantsArePlacedByTheirFloorItem() {
        assertEquals("minecraft:torch", cost("minecraft:wall_torch", "facing", "east").itemId());
        assertEquals("minecraft:soul_torch", cost("minecraft:soul_wall_torch").itemId());
        assertEquals("minecraft:oak_sign", cost("minecraft:oak_wall_sign").itemId());
        assertEquals("minecraft:oak_hanging_sign", cost("minecraft:oak_wall_hanging_sign").itemId());
        assertEquals("minecraft:white_banner", cost("minecraft:white_wall_banner").itemId());
        assertEquals("minecraft:skeleton_skull", cost("minecraft:skeleton_wall_skull").itemId());
        assertEquals("minecraft:redstone_torch", cost("minecraft:redstone_wall_torch").itemId());
    }

    @Test
    void fluidsFireAndAirAreFree() {
        for (String id : List.of("minecraft:air", "minecraft:cave_air", "minecraft:water", "minecraft:lava",
            "minecraft:fire", "minecraft:structure_void", "minecraft:piston_head")) {
            assertTrue(cost(id).free(), id);
        }
        assertTrue(MaterialRules.costsOf("minecraft:water", Map.of()).isEmpty());
    }

    @Test
    void soilIsLaidAsDirtAndWireAsRedstone() {
        assertEquals("minecraft:dirt", cost("minecraft:grass_block", "snowy", "false").itemId());
        assertEquals("minecraft:dirt", cost("minecraft:dirt_path").itemId());
        assertEquals("minecraft:dirt", cost("minecraft:farmland", "moisture", "7").itemId());
        assertEquals("minecraft:redstone", cost("minecraft:redstone_wire").itemId());
        assertEquals("minecraft:string", cost("minecraft:tripwire").itemId());
    }

    @Test
    void stackedBlocksCostTheirCount() {
        assertEquals(3, cost("minecraft:candle", "candles", "3").count());
        assertEquals(4, cost("minecraft:sea_pickle", "pickles", "4").count());
        assertEquals(5, cost("minecraft:snow", "layers", "5").count());
    }

    @Test
    void pottedPlantsCostThePotAndThePlant() {
        List<MaterialRules.Cost> costs = MaterialRules.costsOf("minecraft:potted_poppy", Map.of());
        assertEquals(List.of(new MaterialRules.Cost("minecraft:flower_pot", 1),
            new MaterialRules.Cost("minecraft:poppy", 1)), costs);
        assertEquals("minecraft:azalea",
            MaterialRules.costsOf("minecraft:potted_azalea_bush", Map.of()).get(1).itemId());
    }

    @Test
    void totalsFollowFirstSeenOrderAndSum() {
        Map<String, Integer> total = MaterialRules.total(List.of(
            new MaterialRules.Entry("minecraft:cobblestone", Map.of()),
            new MaterialRules.Entry("minecraft:oak_planks", Map.of()),
            new MaterialRules.Entry("minecraft:cobblestone", Map.of()),
            new MaterialRules.Entry("minecraft:oak_door", Map.of("half", "lower")),
            new MaterialRules.Entry("minecraft:oak_door", Map.of("half", "upper")),
            new MaterialRules.Entry("minecraft:oak_slab", Map.of("type", "double")),
            new MaterialRules.Entry("minecraft:air", Map.of())));
        assertEquals(List.of("minecraft:cobblestone", "minecraft:oak_planks", "minecraft:oak_door",
            "minecraft:oak_slab"), List.copyOf(total.keySet()));
        assertEquals(2, total.get("minecraft:cobblestone"));
        assertEquals(1, total.get("minecraft:oak_door"));
        assertEquals(2, total.get("minecraft:oak_slab"));
    }

    // Codex T3b P2: an id match alone never satisfies a plan.
    @Test
    void materialBearingPropertiesMustMatch() {
        assertEquals(MaterialRules.Match.DIFFERENT, MaterialRules.compare(
            "minecraft:oak_slab", Map.of("type", "double"), "minecraft:oak_slab", Map.of("type", "bottom")));
        assertEquals(MaterialRules.Match.DIFFERENT, MaterialRules.compare(
            "minecraft:candle", Map.of("candles", "4"), "minecraft:candle", Map.of("candles", "1")));
        assertEquals(MaterialRules.Match.DIFFERENT, MaterialRules.compare(
            "minecraft:snow", Map.of("layers", "8"), "minecraft:snow", Map.of("layers", "2")));
        assertEquals(MaterialRules.Match.DIFFERENT, MaterialRules.compare(
            "minecraft:oak_planks", Map.of(), "minecraft:spruce_planks", Map.of()));
    }

    @Test
    void orientationIsTurnedForFreeAndWorldPropertiesAreIgnored() {
        assertEquals(MaterialRules.Match.REORIENT, MaterialRules.compare(
            "minecraft:oak_stairs", Map.of("facing", "north", "half", "bottom", "shape", "straight"),
            "minecraft:oak_stairs", Map.of("facing", "east", "half", "bottom", "shape", "outer_left")));
        assertEquals(MaterialRules.Match.REORIENT, MaterialRules.compare(
            "minecraft:oak_log", Map.of("axis", "y"), "minecraft:oak_log", Map.of("axis", "x")));
        assertEquals(MaterialRules.Match.SAME, MaterialRules.compare(
            "minecraft:oak_fence", Map.of("north", "false", "waterlogged", "false"),
            "minecraft:oak_fence", Map.of("north", "true", "waterlogged", "true")));
        assertEquals(MaterialRules.Match.SAME, MaterialRules.compare(
            "minecraft:oak_slab", Map.of("type", "double", "waterlogged", "false"),
            "minecraft:oak_slab", Map.of("type", "double", "waterlogged", "true")));
        assertEquals(MaterialRules.Match.SAME, MaterialRules.compare(
            "minecraft:dirt", Map.of(), "minecraft:grass_block", Map.of("snowy", "false")));
    }

    @Test
    void soilIsEquivalentSoANeverFinishingJobIsImpossible() {
        assertTrue(MaterialRules.equivalent("minecraft:dirt", "minecraft:grass_block"));
        assertTrue(MaterialRules.equivalent("minecraft:grass_block", "minecraft:dirt_path"));
        assertTrue(MaterialRules.equivalent("minecraft:oak_planks", "minecraft:oak_planks"));
        assertFalse(MaterialRules.equivalent("minecraft:oak_planks", "minecraft:spruce_planks"));
        assertFalse(MaterialRules.equivalent("minecraft:dirt", "minecraft:stone"));
    }
}
