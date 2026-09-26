package com.hearthstead.event.worldevent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BH-08 regression: every world-event creature has a real English name, so
 * death messages, subtitles, /summon and the entity tooltip never show a raw
 * {@code entity.hearthstead.*} key on the friend test.
 */
class WorldEventEntityNamesTest {

    @Test
    void everyWorldEventCreatureHasAnEnglishName() throws IOException {
        JsonObject english;
        try (InputStream in = WorldEventEntityNamesTest.class
                .getResourceAsStream("/assets/hearthstead/lang/en_us.json")) {
            assertNotNull(in, "en_us.json on the test classpath");
            english = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                .getAsJsonObject();
        }
        for (String id : new String[] {WorldEventEntities.PACK_WOLF_ID.getPath(),
                WorldEventEntities.WILD_BOAR_ID.getPath()}) {
            String key = "entity.hearthstead." + id;
            assertTrue(english.has(key), "missing English name " + key);
            assertFalse(english.get(key).getAsString().isBlank(), "blank English name " + key);
        }
    }
}
