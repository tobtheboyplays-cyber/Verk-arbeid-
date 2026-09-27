package com.hearthstead.item;

import com.hearthstead.building.BuildingType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Which buildings have a craftable Building Plan: every building type the
 * Builder has style presets for ({@code data/hearthstead/blueprints}, kind
 * "building"). Defense pieces (walls, gates, towers) stay in the Builder's
 * Plan. Recipes are {@code data/hearthstead/recipe/building_plan_<type>.json}
 * and are gated like the plaque plans: by the node that unlocks the building
 * ({@link com.hearthstead.settlement.techtree.TechRecipeGates#buildingFor}).
 * {@code BuildingPlanRecipesTest} keeps this list, the recipes and the
 * blueprint presets in step.
 */
public final class BuildingPlans {

    public static final String RECIPE_PREFIX = "building_plan_";

    /** Type ids, in the order the creative tab shows them. */
    public static final List<String> TYPE_IDS = List.of(
        "house", "builders_hut", "lumber_camp", "farmhouse", "warehouse", "well", "kitchen", "bakery",
        "dining_hall", "tavern", "lodging", "market", "trading_post", "fishery", "pasture", "butcher",
        "hunters_lodge", "tannery", "weaver", "carpenter", "sawmill", "mason", "mill", "mine", "smelter",
        "smithy", "armoury", "fletcher", "barracks", "sword_hall", "pike_yard", "infirmary", "brewery",
        "library", "school", "architects_study", "rune_hall");

    private BuildingPlans() {
    }

    /** Exact lookup (BuildingType.byId falls back to HOUSE). */
    @Nullable
    public static BuildingType byId(@Nullable String id) {
        if (id == null) {
            return null;
        }
        for (BuildingType type : BuildingType.values()) {
            if (type.id().equals(id)) {
                return type;
            }
        }
        return null;
    }

    public static List<BuildingType> types() {
        List<BuildingType> out = new ArrayList<>();
        for (String id : TYPE_IDS) {
            BuildingType type = byId(id);
            if (type != null) {
                out.add(type);
            }
        }
        return Collections.unmodifiableList(out);
    }
}
