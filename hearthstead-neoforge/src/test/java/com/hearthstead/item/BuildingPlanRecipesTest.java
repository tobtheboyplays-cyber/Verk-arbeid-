package com.hearthstead.item;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hearthstead.building.BuildingType;
import com.hearthstead.registry.ModComponents;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.techtree.TechRecipeGates;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Building Plans (building-plan lane, 26 Sep): every building the Builder has
 * style presets for gets exactly one plan recipe, each recipe is gated by the
 * node that unlocks its building (the same gate as the plaque plan), and the
 * plan's building type survives on the stack.
 */
class BuildingPlanRecipesTest {

    private static JsonObject json(String path) throws Exception {
        try (InputStream in = BuildingPlanRecipesTest.class.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(r).getAsJsonObject();
            }
        }
    }

    /** Building types with at least one "building" preset in data/hearthstead/blueprints. */
    private static Set<String> typesWithPresets() throws Exception {
        URL anchor = BuildingPlanRecipesTest.class.getResource("/data/hearthstead/blueprints/house_timber.json");
        assertNotNull(anchor, "blueprints on the test classpath");
        Set<String> out = new TreeSet<>();
        try (Stream<Path> files = Files.list(Path.of(anchor.toURI()).getParent())) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                JsonObject bp = json("/data/hearthstead/blueprints/" + f.getFileName());
                if (bp != null && bp.has("building_type") && !bp.get("building_type").isJsonNull()
                    && "building".equals(bp.has("kind") ? bp.get("kind").getAsString() : "")) {
                    out.add(bp.get("building_type").getAsString());
                }
            }
        }
        return out;
    }

    @Test
    void everyBuildableTypeHasExactlyOnePlanRecipe() throws Exception {
        Set<String> presets = typesWithPresets();
        assertEquals(presets, new TreeSet<>(BuildingPlans.TYPE_IDS),
            "BuildingPlans.TYPE_IDS must list exactly the building types with Builder presets");
        assertEquals(BuildingPlans.TYPE_IDS.size(), BuildingPlans.types().size(), "every plan id is a BuildingType");
        List<String> failures = new ArrayList<>();
        for (String id : BuildingPlans.TYPE_IDS) {
            JsonObject recipe = json("/data/hearthstead/recipe/" + BuildingPlans.RECIPE_PREFIX + id + ".json");
            if (recipe == null) {
                failures.add(id + ": no recipe");
                continue;
            }
            JsonObject result = recipe.getAsJsonObject("result");
            if (!"hearthstead:building_plan".equals(result.get("id").getAsString())) {
                failures.add(id + ": result is not a building_plan");
            }
            JsonObject components = result.getAsJsonObject("components");
            if (components == null || !id.equals(components.get("hearthstead:building_type").getAsString())) {
                failures.add(id + ": result carries the wrong building type");
            }
            if (recipe.getAsJsonArray("ingredients").size() != 3) {
                failures.add(id + ": expected paper + ink + token");
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void eachPlanIsGatedByTheNodeThatUnlocksItsBuilding() {
        for (BuildingType type : BuildingPlans.types()) {
            ResourceLocation plan = ResourceLocation.fromNamespaceAndPath("hearthstead",
                BuildingPlans.RECIPE_PREFIX + type.id());
            assertEquals(type, TechRecipeGates.buildingFor(plan), type.id());
            String node = TechRecipeGates.nodeForBuilding(type);
            if (node != null) {
                assertEquals(List.of(node), TechRecipeGates.nodesFor(plan, null), type.id());
            }
            // Same gate as the plaque's plan for the same building.
            ResourceLocation plaque = ResourceLocation.fromNamespaceAndPath("hearthstead", "build_plan_" + type.id());
            assertEquals(TechRecipeGates.nodesFor(plaque, null), TechRecipeGates.nodesFor(plan, null), type.id());
        }
        assertNull(TechRecipeGates.buildingFor(ResourceLocation.fromNamespaceAndPath("hearthstead",
            "building_plan_not_a_building")));
    }

    @Test
    void planComponentRoundTrips() {
        for (BuildingType type : BuildingPlans.types()) {
            ItemStack stack = BuildingPlanItem.of(type);
            assertTrue(stack.is(ModItems.BUILDING_PLAN.get()));
            assertEquals(type.id(), stack.get(ModComponents.BUILDING_TYPE.get()));
            assertEquals(type, BuildingPlanItem.typeOf(stack));
            assertEquals(type, BuildingPlanItem.typeOf(stack.copy()));
        }
        assertNull(BuildingPlanItem.typeOf(new ItemStack(ModItems.BUILDING_PLAN.get())), "a blank plan has no type");
    }
}
