package com.hearthstead.settlement.builder;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Builder ground (owner, 27 Sep): a building's ground layer sinks into the
 * terrain surface, and the soil already there satisfies it -- nothing
 * placed, nothing charged.
 */
class BlueprintGroundTest {

    @Test
    void buildingsSinkOneIntoTheSurfaceDefenseLinesDoNot() {
        assertTrue(BlueprintGround.sinks(BlueprintMeta.Kind.BUILDING, null, "house"));
        assertTrue(BlueprintGround.sinks(BlueprintMeta.Kind.DECORATION, null, null));
        assertTrue(BlueprintGround.sinks(BlueprintMeta.Kind.DEFENSE, null, "watchtower"), "a watchtower");
        assertTrue(BlueprintGround.sinks(BlueprintMeta.Kind.DEFENSE, "gate", "watchtower"), "the stone gatehouse");
        assertFalse(BlueprintGround.sinks(BlueprintMeta.Kind.DEFENSE, "palisade", null), "a wall segment");
        assertFalse(BlueprintGround.sinks(BlueprintMeta.Kind.DEFENSE, "gate", null), "a wall gate");
        assertFalse(BlueprintGround.sinks(BlueprintMeta.Kind.BARRICADE, "barricade", null));

        assertEquals(1, BlueprintGround.placementDepth(0, true), "a cottage: ground layer in the surface");
        assertEquals(6, BlueprintGround.placementDepth(5, true), "a mine: shaft below, ground layer in the surface");
        assertEquals(0, BlueprintGround.placementDepth(0, false), "a palisade segment stands on the surface");
        assertEquals(0, BlueprintGround.placementDepth(-3, false), "a negative ground level is clamped");
    }

    @Test
    void aGroundLayerDirtStepIsSatisfiedByNaturalSoil() {
        for (String planned : List.of("minecraft:dirt", "minecraft:grass_block")) {
            for (String present : List.of("minecraft:grass_block", "minecraft:dirt", "minecraft:coarse_dirt",
                "minecraft:podzol", "minecraft:rooted_dirt", "minecraft:mycelium", "minecraft:dirt_path")) {
                assertEquals(MaterialRules.Match.SAME,
                    MaterialRules.compare(planned, Map.of(), present, Map.of("snowy", "false")),
                    planned + " on " + present + " is already there: never placed, never charged");
            }
            assertEquals(MaterialRules.Match.DIFFERENT,
                MaterialRules.compare(planned, Map.of(), "minecraft:sand", Map.of()), "sand is not soil");
            assertEquals(MaterialRules.Match.DIFFERENT,
                MaterialRules.compare(planned, Map.of(), "minecraft:stone", Map.of()), "stone is not soil");
            assertEquals(MaterialRules.Match.DIFFERENT,
                MaterialRules.compare(planned, Map.of(), "minecraft:farmland", Map.of()),
                "farmland only satisfies a farmland cell");
        }
        assertEquals(MaterialRules.Match.SAME, MaterialRules.compare("minecraft:farmland", Map.of("moisture", "0"),
            "minecraft:farmland", Map.of("moisture", "7")), "a farmland cell on farmland");
        assertEquals(MaterialRules.Match.DIFFERENT, MaterialRules.compare("minecraft:farmland", Map.of(),
            "minecraft:grass_block", Map.of()), "a farmland cell is still tilled on grass");
    }

    @Test
    void onlyTheGroundLayerSoilOfASunkBlueprintLeavesTheBill() {
        assertTrue(BlueprintGround.groundSoil("minecraft:grass_block", 0, 0, true));
        assertTrue(BlueprintGround.groundSoil("minecraft:dirt_path", 5, 5, true), "a mine's ground layer");
        assertFalse(BlueprintGround.groundSoil("minecraft:grass_block", 1, 0, true), "a raised planter stays");
        assertFalse(BlueprintGround.groundSoil("minecraft:grass_block", 0, 0, false), "an unsunk piece pays");
        assertFalse(BlueprintGround.groundSoil("minecraft:farmland", 0, 0, true), "farmland is laid on purpose");
        assertFalse(BlueprintGround.groundSoil("minecraft:oak_planks", 0, 0, true), "the floor is paid");
    }

    @Test
    void theCottageCardNoLongerAsksForAHundredDirt() throws IOException {
        Map<String, Integer> all = cardBill("house_cottage", 0, false);
        Map<String, Integer> sunk = cardBill("house_cottage", 0, true);
        assertTrue(all.getOrDefault("minecraft:dirt", 0) >= 100, "one block up it was ~108 dirt: " + all);
        assertEquals(0, sunk.getOrDefault("minecraft:dirt", 0), "sunk: the grass is already there: " + sunk);
        assertEquals(0, sunk.getOrDefault("minecraft:coarse_dirt", 0), "coarse dirt too: " + sunk);
        assertEquals(all.get("minecraft:oak_planks"), sunk.get("minecraft:oak_planks"), "the floor is still paid");
        assertEquals(all.get("minecraft:cobblestone"), sunk.get("minecraft:cobblestone"), "the footing too");
    }

    /** The catalog card's bill (BuilderNetwork.catalog) of a shipped blueprint, from its NBT. */
    private static Map<String, Integer> cardBill(String id, int groundLevel, boolean sinks) throws IOException {
        try (InputStream in = BlueprintGroundTest.class.getResourceAsStream(
            "/data/hearthstead/structure/blueprints/" + id + ".nbt")) {
            assertNotNull(in, id + ".nbt ships");
            CompoundTag root = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
            ListTag palette = root.getList("palette", Tag.TAG_COMPOUND);
            ListTag blocks = root.getList("blocks", Tag.TAG_COMPOUND);
            List<MaterialRules.Entry> entries = new ArrayList<>();
            for (int i = 0; i < blocks.size(); i++) {
                CompoundTag b = blocks.getCompound(i);
                CompoundTag state = palette.getCompound(b.getInt("state"));
                String name = state.getString("Name");
                int y = b.getList("pos", Tag.TAG_INT).getInt(1);
                if (BlueprintGround.groundSoil(name, y, groundLevel, sinks)) {
                    continue;
                }
                Map<String, String> props = new HashMap<>();
                CompoundTag p = state.getCompound("Properties");
                for (String key : p.getAllKeys()) {
                    props.put(key, p.getString(key));
                }
                entries.add(new MaterialRules.Entry(name, props));
            }
            return MaterialRules.total(entries);
        }
    }
}
