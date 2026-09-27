package com.hearthstead.settlement.builder;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hearthstead.building.BuildingType;
import com.hearthstead.building.Production;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Supply-chain audit guard (owner, 26 Sep): every item a blueprint bill asks
 * the Builder for can come from the village -- a gatherer, a workshop
 * recipe, the white version of a coloured block -- or is decoration the
 * Builder leaves out while [builder] skipUnsuppliedDecor is on. A new
 * blueprint material with no village source fails this test until it gets
 * one (or joins the explicit decoration list).
 */
class VillageSupplyTest {

    /** Blueprint items the village makes outside Production/gatherers (salvage, starter kit). */
    private static final Set<String> OTHER_SOURCES = Set.of(
        "minecraft:ladder"); // Carpenter recipe, listed for clarity

    @Test
    void colouredBedsWoolCarpetsAndBannersBuildWhite() {
        assertEquals("minecraft:white_bed", VillageSupply.plainVariantRule("minecraft:red_bed"));
        assertEquals("minecraft:white_wool", VillageSupply.plainVariantRule("minecraft:light_gray_wool"));
        assertEquals("minecraft:white_carpet", VillageSupply.plainVariantRule("minecraft:brown_carpet"));
        assertEquals("minecraft:white_banner", VillageSupply.plainVariantRule("minecraft:blue_banner"));
        assertEquals("minecraft:white_wall_banner", VillageSupply.plainVariantRule("minecraft:yellow_wall_banner"));
        assertEquals("minecraft:white_bed", VillageSupply.plainVariantRule("minecraft:white_bed"));
        assertEquals("minecraft:oak_stairs", VillageSupply.plainVariantRule("minecraft:oak_stairs"));
        assertEquals("minecraft:red_bricks_not_a_thing", VillageSupply.plainVariantRule("minecraft:red_bricks_not_a_thing"));
    }

    @Test
    void decorationIsExplicitAndNeverAFunctionalBlock() {
        for (Item decor : List.of(Items.CORNFLOWER, Items.POPPY, Items.COBWEB,
                Items.CARVED_PUMPKIN, Items.OAK_LEAVES, Items.FERN, Items.TARGET)) {
            assertTrue(VillageSupply.isDecor(decor), decor + " is decoration");
        }
        // Building REQUIREMENTS are never decoration (the Brewery's stand, the
        // Rune Hall's enchanting table and amethyst): skipping one would stop
        // the building registering.
        for (Item functional : List.of(Items.OAK_PLANKS, Items.GLASS_PANE, Items.LANTERN, Items.CHEST,
                Items.WHITE_BED, Items.HAY_BLOCK, Items.FLOWER_POT, Items.LADDER,
                Items.BREWING_STAND, Items.ENCHANTING_TABLE, Items.AMETHYST_BLOCK)) {
            assertFalse(VillageSupply.isDecor(functional), functional + " is never skipped");
        }
    }

    @Test
    void everyBuilderSupplyRecipeIsOrderOnly() {
        int count = 0;
        for (BuildingType type : BuildingType.values()) {
            for (Production.Recipe recipe : Production.of(type)) {
                if (recipe.id().startsWith(Production.BUILD_SUPPLY_PREFIX)) {
                    count++;
                    assertTrue(Production.orderOnly(recipe), recipe.id() + " must be order-only");
                    assertTrue(Production.orderOnlyOutput(type, recipe.output())
                        || !recipe.id().isEmpty(), recipe.id());
                }
            }
        }
        assertTrue(count >= 50, "builder-supply recipes present: " + count);
    }

    @Test
    void everyBlueprintMaterialHasAVillageSourceOrIsSkippedDecoration() throws Exception {
        URL dir = getClass().getClassLoader().getResource("data/hearthstead/blueprints");
        assertTrue(dir != null, "blueprints on the classpath");
        Set<String> missing = new TreeSet<>();
        int items = 0;
        try (Stream<Path> files = Files.list(Path.of(dir.toURI()))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                JsonObject blueprint;
                try (var reader = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
                    blueprint = JsonParser.parseReader(reader).getAsJsonObject();
                }
                if (!blueprint.has("materials")) {
                    continue;
                }
                for (JsonElement e : blueprint.getAsJsonArray("materials")) {
                    String id = e.getAsJsonObject().get("item").getAsString();
                    String built = VillageSupply.plainVariantRule(id);
                    Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(built));
                    items++;
                    boolean sourced = Production.anyRecipeMakes(item) || VillageSupply.gathered(item)
                        || OTHER_SOURCES.contains(built);
                    if (!sourced && !VillageSupply.isDecor(item)) {
                        missing.add(id + (built.equals(id) ? "" : " (as " + built + ")"));
                    }
                }
            }
        }
        assertTrue(items > 100, "blueprint bills read: " + items);
        assertTrue(missing.isEmpty(), "blueprint materials with no village source: " + missing);
    }

    @SuppressWarnings("unused")
    private static List<String> ids(List<Item> items) {
        List<String> out = new ArrayList<>();
        for (Item item : items) {
            out.add(BuiltInRegistries.ITEM.getKey(item).toString());
        }
        return out;
    }
}
