package com.hearthstead.settlement.journey;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JourneyLanguageContractTest {

    @Test
    void englishCoversEveryFrozenStepAndUiKey() throws Exception {
        JsonObject english = language("en_us");
        for (JourneyStep step : JourneyDefinition.V2.orderedSteps()) {
            assertText(english, step.titleKey());
            assertText(english, step.descriptionKey());
        }
        for (var chapter : JourneyDefinition.V2.chapters()) {
            String leaf = chapter.getPath().substring(
                chapter.getPath().lastIndexOf('/') + 1);
            String key = "journey.hearthstead.chapter." + leaf + ".title";
            assertText(english, key);
        }
        Set<String> sharedUi = Set.of("journey.hearthstead.progress",
            "journey.hearthstead.active_bounded",
            "journey.hearthstead.complete.none",
            "journey.hearthstead.complete.held",
            "journey.hearthstead.complete.hit",
            "journey.hearthstead.complete.settlement_lost");
        sharedUi.forEach(key -> {
            assertText(english, key);
        });

        assertEquals(125, countJourneyKeys(english));
    }

    private static JsonObject language(String locale) throws Exception {
        String path = "assets/hearthstead/lang/" + locale + ".json";
        var stream = JourneyLanguageContractTest.class.getClassLoader()
            .getResourceAsStream(path);
        assertNotNull(stream, path);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static int countJourneyKeys(JsonObject language) {
        int count = 0;
        for (String key : language.keySet()) {
            if (key.startsWith("journey.hearthstead.")) {
                count++;
            }
        }
        return count;
    }

    private static void assertText(JsonObject language, String key) {
        assertTrue(language.has(key), "missing language key " + key);
        assertTrue(!language.get(key).getAsString().isBlank(),
            "blank language key " + key);
    }
}
