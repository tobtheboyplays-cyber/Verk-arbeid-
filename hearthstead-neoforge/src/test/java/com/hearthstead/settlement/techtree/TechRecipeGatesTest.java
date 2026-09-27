package com.hearthstead.settlement.techtree;

import com.hearthstead.building.BuildingType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tech-gated crafting data: every gate names a real node and a real recipe
 * or item; the day-one items stay craftable; every Build Plan is gated by the
 * node that unlocks its building.
 */
class TechRecipeGatesTest {

    @Test
    void everyGatedIdExistsAndEveryNodeIsReal() {
        Map<String, List<String>> index = TechRecipeGates.index();
        assertTrue(!index.isEmpty(), "the seed gates load");
        for (Map.Entry<String, List<String>> e : index.entrySet()) {
            ResourceLocation id = ResourceLocation.parse(e.getKey());
            boolean recipeFile = TechRecipeGates.class.getResource(
                "/data/" + id.getNamespace() + "/recipe/" + id.getPath() + ".json") != null;
            boolean item = BuiltInRegistries.ITEM.containsKey(id)
                && BuiltInRegistries.ITEM.get(id) != Items.AIR;
            assertTrue(recipeFile || item, e.getKey() + " is neither a recipe nor an item");
            for (String node : e.getValue()) {
                assertNotNull(TechTreeData.get().node(node), e.getKey() + " gated by unknown node " + node);
            }
        }
    }

    @Test
    void everyGateAlsoBindsWorkshopsExceptVanillaOutputs() {
        for (Map.Entry<String, List<String>> e : TechRecipeGates.index().entrySet()) {
            ResourceLocation id = ResourceLocation.parse(e.getKey());
            String item = BuiltInRegistries.ITEM.containsKey(id) ? e.getKey() : TechRecipeGates.recipeOutput(id);
            assertNotNull(item, e.getKey() + " resolves to no output");
            if (item.startsWith("minecraft:")) {
                continue;
            }
            List<String> workshop = TechRecipeGates.workshopNodesFor(
                BuiltInRegistries.ITEM.get(ResourceLocation.parse(item)));
            assertTrue(workshop.containsAll(e.getValue()), e.getKey() + " does not bind workshops making " + item);
        }
        assertTrue(TechRecipeGates.workshopNodesFor(Items.BREAD).isEmpty(), "vanilla bread stays ungated");
    }

    @Test
    void firstTenMinutesItemsAreNeverGated() {
        for (String id : List.of("hearth", "handbook", "plaque", "bell")) {
            ResourceLocation rl = ResourceLocation.fromNamespaceAndPath("hearthstead", id);
            assertTrue(TechRecipeGates.nodesFor(rl, null).isEmpty(), id + " must stay craftable on day one");
        }
    }

    @Test
    void buildPlansFollowTheirBuildingNode() {
        for (BuildingType type : BuildingType.values()) {
            ResourceLocation rl = ResourceLocation.fromNamespaceAndPath("hearthstead", "build_plan_" + type.id());
            if (TechRecipeGates.class.getResource("/data/hearthstead/recipe/build_plan_" + type.id() + ".json") == null) {
                continue;
            }
            assertEquals(type, TechRecipeGates.buildingFor(rl));
            String node = TechRecipeGates.nodeForBuilding(type);
            if (node != null) {
                assertEquals(List.of(node), TechRecipeGates.nodesFor(rl, null), type.id());
            }
        }
    }
}
