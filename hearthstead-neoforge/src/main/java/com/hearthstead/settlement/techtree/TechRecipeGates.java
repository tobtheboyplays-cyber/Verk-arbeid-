package com.hearthstead.settlement.techtree;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hearthstead.building.BuildingType;
import com.hearthstead.settlement.development.DevelopmentNode;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Which tech node unlocks which crafting recipe (owner rule, 26 Sep: nothing
 * is craftable before its node is learned). Pure and static: used by the
 * server gate, the client padlock, the recipe book sync and the handbook.
 *
 * <p>Sources, merged: {@code data/hearthstead/techtree/recipe_gates.json}
 * (framework seed) and each branch file's {@code nodes[].unlocks_recipes}.
 * An entry is a recipe id or an item id (the recipe's output). Build Plan
 * recipes ({@code hearthstead:build_plan_<type>}) are gated automatically by
 * the node that unlocks that building (see {@link #buildingFor}).
 */
public final class TechRecipeGates {
    private static final String PLAN_PREFIX = "build_plan_";
    private static volatile Map<String, List<String>> index;
    private static volatile Map<String, List<String>> workshopIndex;

    private TechRecipeGates() {
    }

    /** id (recipe or item) -> nodes unlocking it. */
    public static Map<String, List<String>> index() {
        Map<String, List<String>> loaded = index;
        if (loaded == null) {
            synchronized (TechRecipeGates.class) {
                loaded = index;
                if (loaded == null) {
                    loaded = load();
                    index = loaded;
                }
            }
        }
        return loaded;
    }

    /**
     * Nodes gating a WORKSHOP output: explicit item entries plus the outputs
     * of recipe-id entries (read from the recipe file's result), so every
     * gate also binds settlers. Vanilla outputs are left out: Kitchen bread is
     * not "bread_from_flour", and vanilla items keep their vanilla recipes.
     */
    public static List<String> workshopNodesFor(@Nullable Item output) {
        if (output == null) {
            return List.of();
        }
        Map<String, List<String>> w = workshopIndex;
        if (w == null) {
            synchronized (TechRecipeGates.class) {
                w = workshopIndex;
                if (w == null) {
                    w = buildWorkshopIndex();
                    workshopIndex = w;
                }
            }
        }
        return w.getOrDefault(BuiltInRegistries.ITEM.getKey(output).toString(), List.of());
    }

    /** The output item id a recipe file declares (result.id, result.item or a string), or null. */
    @Nullable
    public static String recipeOutput(ResourceLocation recipeId) {
        try (InputStream in = TechRecipeGates.class.getResourceAsStream(
                "/data/" + recipeId.getNamespace() + "/recipe/" + recipeId.getPath() + ".json")) {
            if (in == null) {
                return null;
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                if (!root.has("result")) {
                    return null;
                }
                JsonElement result = root.get("result");
                if (result.isJsonPrimitive()) {
                    return result.getAsString();
                }
                JsonObject o = result.getAsJsonObject();
                return o.has("id") ? o.get("id").getAsString()
                    : o.has("item") ? o.get("item").getAsString() : null;
            }
        } catch (Exception badRecipe) {
            return null;
        }
    }

    private static Map<String, List<String>> buildWorkshopIndex() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : index().entrySet()) {
            ResourceLocation id = ResourceLocation.parse(e.getKey());
            String item = BuiltInRegistries.ITEM.containsKey(id) ? e.getKey() : recipeOutput(id);
            if (item == null || item.startsWith("minecraft:")) {
                continue;
            }
            for (String node : e.getValue()) {
                add(out, item, node);
            }
        }
        Map<String, List<String>> frozen = new LinkedHashMap<>();
        out.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
        return Collections.unmodifiableMap(frozen);
    }

    /** All nodes that unlock this recipe (any one learned is enough); empty = ungated. */
    public static List<String> nodesFor(@Nullable ResourceLocation recipeId, @Nullable Item output) {
        List<String> out = new ArrayList<>();
        if (recipeId != null) {
            out.addAll(index().getOrDefault(recipeId.toString(), List.of()));
        }
        if (output != null) {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(output);
            for (String node : index().getOrDefault(key.toString(), List.of())) {
                if (!out.contains(node)) {
                    out.add(node);
                }
            }
        }
        BuildingType plan = buildingFor(recipeId);
        if (plan != null && out.isEmpty()) {
            String node = nodeForBuilding(plan);
            if (node != null) {
                out.add(node);
            }
        }
        return out;
    }

    public static Optional<String> nodeFor(@Nullable ResourceLocation recipeId, @Nullable Item output) {
        List<String> nodes = nodesFor(recipeId, output);
        return nodes.isEmpty() ? Optional.empty() : Optional.of(nodes.getFirst());
    }

    public static boolean gated(@Nullable ResourceLocation recipeId, @Nullable Item output) {
        return !nodesFor(recipeId, output).isEmpty();
    }

    /** The building a Build Plan recipe makes, or null. */
    @Nullable
    public static BuildingType buildingFor(@Nullable ResourceLocation recipeId) {
        if (recipeId == null || !"hearthstead".equals(recipeId.getNamespace())
            || !recipeId.getPath().startsWith(PLAN_PREFIX)) {
            return null;
        }
        String id = recipeId.getPath().substring(PLAN_PREFIX.length());
        for (BuildingType type : BuildingType.values()) {
            if (type.id().equals(id)) {
                return type;
            }
        }
        return null;
    }

    /** The node that unlocks a building plan: a v3 claim, else the legacy node listing it. */
    @Nullable
    public static String nodeForBuilding(BuildingType type) {
        List<String> claimants = EffectRegistry.get().buildingClaimants(type);
        if (!claimants.isEmpty()) {
            return claimants.getFirst();
        }
        for (DevelopmentNode node : DevelopmentNode.PRESENTATION_ORDER) {
            if (node.buildings().contains(type)) {
                return node.id();
            }
        }
        DevelopmentNode role = com.hearthstead.entity.combat.role.RoleUnlocks.buildingNode(type);
        return role == null ? null : role.id();
    }

    private static Map<String, List<String>> load() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        try (InputStream in = TechRecipeGates.class.getResourceAsStream(
                "/data/hearthstead/techtree/recipe_gates.json")) {
            if (in != null) {
                try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    if (root.has("gates")) {
                        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("gates").entrySet()) {
                            for (JsonElement id : e.getValue().getAsJsonArray()) {
                                add(out, id.getAsString(), e.getKey());
                            }
                        }
                    }
                }
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Bad tech tree recipe_gates.json", failure);
        }
        for (TechNodeDef def : TechTreeData.get().nodes()) {
            for (String id : def.unlocksRecipes()) {
                add(out, id, def.id());
            }
        }
        Map<String, List<String>> frozen = new LinkedHashMap<>();
        out.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
        return Collections.unmodifiableMap(frozen);
    }

    private static void add(Map<String, List<String>> out, String id, String node) {
        String key = id.contains(":") ? id : "hearthstead:" + id;
        List<String> nodes = out.computeIfAbsent(key, ignored -> new ArrayList<>());
        if (!nodes.contains(node)) {
            nodes.add(node);
        }
    }
}
