package com.hearthstead.building;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two crafting recipes with the same ingredients make one of them
 * uncraftable: the crafting grid only ever returns the first match. Found by
 * the survival playthrough (26 Sep): the House plan and the Builder's Hut plan
 * were both paper + feather + planks, so a survival player could never get one
 * of them.
 */
class RecipeUniquenessTest {

    @Test
    void noTwoModRecipesShareTheSameIngredients() throws IOException, URISyntaxException {
        URL anchor = RecipeUniquenessTest.class.getResource("/data/hearthstead/recipe/hearth.json");
        assertNotNull(anchor, "recipe folder not on the test classpath");
        Path folder = Path.of(anchor.toURI()).getParent();
        Map<String, String> seen = new HashMap<>();
        List<String> clashes = new ArrayList<>();
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                JsonObject recipe;
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    recipe = JsonParser.parseReader(reader).getAsJsonObject();
                }
                String signature = signature(recipe);
                if (signature == null) {
                    continue;
                }
                String other = seen.putIfAbsent(signature, file.getFileName().toString());
                if (other != null) {
                    clashes.add(other + " == " + file.getFileName());
                }
            }
        }
        assertTrue(clashes.isEmpty(), "recipes with identical ingredients (one is uncraftable): " + clashes);
    }

    /**
     * A survival player finds recipes through the recipe book. A mod recipe
     * that no advancement unlocks never shows up there (survival QA, 26 Sep:
     * the Builder's Plan, Survey Rod, Resource Scroll, Ale Tap and Fishery
     * furniture were invisible). Build plans and the Work Scepter are
     * unlocked by the Tech Tree sync instead ({@code DevelopmentRecipeBook}).
     */
    @Test
    void everyModRecipeIsReachableFromTheRecipeBook() throws IOException, URISyntaxException {
        URL anchor = RecipeUniquenessTest.class.getResource("/data/hearthstead/recipe/hearth.json");
        URL advancements = RecipeUniquenessTest.class.getResource("/data/hearthstead/advancement/recipes/hearth.json");
        assertNotNull(anchor, "recipe folder not on the test classpath");
        assertNotNull(advancements, "recipe advancements not on the test classpath");
        java.util.Set<String> unlocked = new java.util.HashSet<>();
        try (Stream<Path> files = Files.list(Path.of(advancements.toURI()).getParent())) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    JsonObject rewards = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("rewards");
                    if (rewards != null && rewards.has("recipes")) {
                        rewards.getAsJsonArray("recipes").forEach(id -> unlocked.add(id.getAsString()));
                    }
                }
            }
        }
        List<String> hidden = new ArrayList<>();
        try (Stream<Path> files = Files.list(Path.of(anchor.toURI()).getParent())) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                String name = file.getFileName().toString().replace(".json", "");
                if (name.startsWith("build_plan_") || name.equals("work_scepter")) {
                    continue;
                }
                if (!unlocked.contains("hearthstead:" + name)) {
                    hidden.add(name);
                }
            }
        }
        assertTrue(hidden.isEmpty(), "mod recipes no advancement unlocks (invisible in the recipe book): " + hidden);
    }

    private static String signature(JsonObject recipe) {
        String type = recipe.get("type").getAsString();
        if (type.endsWith("crafting_shapeless")) {
            List<String> parts = new ArrayList<>();
            for (JsonElement ingredient : recipe.getAsJsonArray("ingredients")) {
                parts.add(ingredient.toString().replace(" ", ""));
            }
            parts.sort(String::compareTo);
            return "shapeless:" + parts;
        }
        if (type.endsWith("crafting_shaped")) {
            return "shaped:" + recipe.get("pattern") + recipe.get("key").toString().replace(" ", "");
        }
        return null;
    }
}
