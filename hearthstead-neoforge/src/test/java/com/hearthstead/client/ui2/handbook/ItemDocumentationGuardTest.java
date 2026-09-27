package com.hearthstead.client.ui2.handbook;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hearthstead.Hearthstead;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.time.LocalDate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Owner rule (26 Sep): nothing ships undocumented. Every item the mod
 * REGISTERS (read from the live registry, so a new item is caught the moment
 * it is added) must have:
 * <ol>
 *   <li>a way to get it: a crafting recipe in {@code data/hearthstead/recipe}
 *       or an {@code obtain} line in the item catalog;</li>
 *   <li>a how-to-use tooltip: a {@code use} line and 1-4 {@code steps};</li>
 *   <li>a handbook page that exists.</li>
 * </ol>
 * Display-only props are exempt only with a written {@code internal} reason.
 * Every mod recipe must also be discoverable in the vanilla recipe book
 * (some recipe advancement rewards it).
 *
 * <p>To fix a failure: add the item to
 * {@code assets/hearthstead/handbook/items.json} (a family with use, steps,
 * page, and obtain when it has no recipe) and the lang keys to en_us.json.
 */
class ItemDocumentationGuardTest {
    /**
     * TEMPORARY, dated (26 Sep, handbook lane): items being catalogued right
     * now. Empty this list as items.json fills; after the date it no longer
     * excuses anything.
     */
    private static final LocalDate PENDING_UNTIL = LocalDate.of(2026, 9, 27);
    private static final Set<String> PENDING = Set.of();

    @Test
    void everyRegisteredItemIsDocumented() throws Exception {
        HandbookItems catalog = HandbookItems.parse(HandbookTestData.json(HandbookTestData.ASSETS + HandbookItems.PATH));
        HandbookBook book = HandbookTestData.book();
        JsonObject en = HandbookTestData.english();
        Map<String, List<String>> recipesByResult = recipesByResult();
        Set<String> registered = new TreeSet<>();
        for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
            if (Hearthstead.MODID.equals(id.getNamespace())) registered.add(id.toString());
        }
        assertTrue(registered.size() > 40, "the mod's items are registered in the unit-test game: " + registered.size());

        List<String> failures = new ArrayList<>();
        for (String item : registered) {
            HandbookItems.Family f = catalog.familyOf(item);
            if (f == null && (PENDING.contains(item) || PENDING.contains("hearthstead:*"))
                && !LocalDate.now().isAfter(PENDING_UNTIL)) continue;
            if (f == null) {
                failures.add(item + ": not in items.json (catalog it: family with use/steps/page/obtain)");
                continue;
            }
            if (f.isInternal()) continue;
            boolean craftable = recipesByResult.containsKey(item);
            if (!craftable && (f.obtainKey() == null || !en.has(f.obtainKey()))) {
                failures.add(item + ": no recipe and no 'obtain' line (family " + f.id() + ")");
            }
            if (f.useKey() == null || !en.has(f.useKey())) {
                failures.add(item + ": no how-to-use tooltip line (family " + f.id() + ")");
            }
            if (f.steps().isEmpty() || f.steps().size() > 4) {
                failures.add(item + ": needs 1-4 Shift-tooltip steps (family " + f.id() + ")");
            }
            for (String step : f.steps()) {
                if (!en.has(step)) failures.add(item + ": missing step text " + step);
                else if (en.get(step).getAsString().length() > 90) failures.add(item + ": step too long " + step);
            }
            if (f.page() == null || book.page(f.page()) == null) {
                failures.add(item + ": handbook page '" + f.page() + "' does not exist");
            }
        }
        for (String item : catalog.items().keySet()) {
            if (!registered.contains(item)) failures.add(item + ": catalogued but not registered (stale entry)");
        }
        for (HandbookItems.Family f : catalog.families().values()) {
            if (f.isInternal() && f.internal().length() < 10) failures.add(f.id() + ": internal needs a real reason");
        }
        assertTrue(failures.isEmpty(), failures.size() + " undocumented:\n" + String.join("\n", failures));
    }

    @Test
    void everyModRecipeIsInTheRecipeBook() throws Exception {
        Set<String> rewarded = new HashSet<>();
        URL dir = getClass().getClassLoader().getResource("data/hearthstead/advancement/recipes");
        assertNotNull(dir, "recipe advancements");
        try (Stream<Path> files = Files.walk(Path.of(dir.toURI()))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                String rel = Path.of(dir.toURI()).relativize(f).toString().replace('\\', '/');
                JsonObject adv = HandbookTestData.json("data/hearthstead/advancement/recipes/" + rel);
                if (adv != null && adv.has("rewards") && adv.getAsJsonObject("rewards").has("recipes")) {
                    for (JsonElement r : adv.getAsJsonObject("rewards").getAsJsonArray("recipes")) {
                        rewarded.add(r.getAsString());
                    }
                }
            }
        }
        List<String> missing = new ArrayList<>();
        for (List<String> ids : recipesByResult().values()) {
            for (String id : ids) {
                // Building plans are granted by the Tech Tree when their node is learned
                // (settlement/development/DevelopmentRecipeBook), not by an advancement.
                // The Work Scepter joins them on Timber Rights (same class, deliberately gated).
                if (id.startsWith("hearthstead:build_plan_") || id.startsWith("hearthstead:building_plan_")
                    || id.equals("hearthstead:work_scepter")) continue;
                if (!rewarded.contains(id)) missing.add(id);
            }
        }
        assertTrue(missing.isEmpty(), "recipes no advancement unlocks (invisible in the recipe book): " + missing);
    }

    /** result item id -> recipe ids, from the shipped data/hearthstead/recipe JSON. */
    static Map<String, List<String>> recipesByResult() throws Exception {
        Map<String, List<String>> out = new HashMap<>();
        URL dir = ItemDocumentationGuardTest.class.getClassLoader().getResource("data/hearthstead/recipe");
        assertNotNull(dir, "recipes");
        try (Stream<Path> files = Files.list(Path.of(dir.toURI()))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                String name = f.getFileName().toString().replace(".json", "");
                JsonObject r = HandbookTestData.json("data/hearthstead/recipe/" + name + ".json");
                if (r == null || !r.has("result")) continue;
                JsonElement result = r.get("result");
                String item = result.isJsonObject()
                    ? (result.getAsJsonObject().has("id") ? result.getAsJsonObject().get("id").getAsString()
                        : result.getAsJsonObject().get("item").getAsString())
                    : result.getAsString();
                out.computeIfAbsent(item, k -> new ArrayList<>()).add("hearthstead:" + name);
            }
        }
        return out;
    }
}
