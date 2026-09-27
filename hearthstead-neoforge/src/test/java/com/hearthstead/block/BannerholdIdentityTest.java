package com.hearthstead.block;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Players see "Bannerhold" and "the Banner"; the old names survive only as
 * ids. Keeps new strings from quietly reintroducing the retired wording while
 * pinning the save-compatible ids that must never change.
 */
class BannerholdIdentityTest {
    private static final Pattern RETIRED = Pattern.compile("(?i)\\bhearth(stead)?\\b|hearthward");
    /** Real config file paths keep the unchanged mod id; they are not the retired display name. */
    private static final Pattern CONFIG_FILE = Pattern.compile("hearthstead-(server|client|common)\\.toml");

    @Test
    void englishTextNeverShowsTheRetiredNames() throws IOException {
        JsonObject english = json("/assets/hearthstead/lang/en_us.json").getAsJsonObject();
        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : english.entrySet()) {
            String value = CONFIG_FILE.matcher(entry.getValue().getAsString()).replaceAll("");
            if (RETIRED.matcher(value).find()) {
                offenders.add(entry.getKey() + " = " + value);
            }
        }
        assertTrue(offenders.isEmpty(), "player-facing text still says Hearth/Hearthstead: " + offenders);
        assertEquals("Settlement Banner", english.get("block.hearthstead.hearth").getAsString());
        assertEquals("Settlement Banner", english.get("container.hearthstead.hearth").getAsString());
        assertEquals("Bannerhold", english.get("itemGroup.hearthstead").getAsString());
        assertEquals("Commons & Household",
            english.get("hearthstead.development.direction.hearth").getAsString());
    }

    @Test
    void modListNameIsBannerholdButTheModIdIsUnchanged() throws IOException {
        String toml = text("/META-INF/neoforge.mods.toml");
        assertTrue(toml.contains("displayName=\"Bannerhold\""), "mod list must show Bannerhold");
        assertTrue(toml.contains("modId=\"hearthstead\""), "the mod id must stay hearthstead for saves");
    }

    @Test
    void theBannerKeepsItsSaveCompatibleIdsAndFacesItsPlacer() throws IOException {
        JsonObject states = json("/assets/hearthstead/blockstates/hearth.json").getAsJsonObject()
            .getAsJsonObject("variants");
        for (String facing : new String[] {"north", "east", "south", "west"}) {
            assertNotNull(states.get("facing=" + facing), "missing Banner facing " + facing);
        }
        JsonObject recipe = json("/data/hearthstead/recipe/hearth.json").getAsJsonObject();
        assertEquals("hearthstead:hearth",
            recipe.getAsJsonObject("result").get("id").getAsString(),
            "the Banner is still crafted as hearthstead:hearth");
    }

    private static JsonElement json(String path) throws IOException {
        return JsonParser.parseString(text(path));
    }

    private static String text(String path) throws IOException {
        try (InputStream in = BannerholdIdentityTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
