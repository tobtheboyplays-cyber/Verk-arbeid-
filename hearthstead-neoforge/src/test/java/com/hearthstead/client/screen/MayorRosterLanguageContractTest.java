package com.hearthstead.client.screen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MayorRosterLanguageContractTest {

    private static final List<String> ROSTER_KEYS = List.of(
        "hearthstead.mayor.choice_rule",
        "hearthstead.mayor.knack.unknown",
        "hearthstead.mayor.knack.value",
        "hearthstead.mayor.page",
        "hearthstead.mayor.page.next",
        "hearthstead.mayor.page.next.tip",
        "hearthstead.mayor.page.previous",
        "hearthstead.mayor.page.previous.tip",
        "hearthstead.mayor.roster.title"
    );

    @Test
    void rosterHasEnglishAndNorwegianCopy() throws Exception {
        JsonObject english = language("en_us");
        JsonObject norwegian = language("nb_no");
        for (String key : ROSTER_KEYS) {
            assertText(english, key);
            assertText(norwegian, key);
        }
    }

    private static JsonObject language(String locale) throws Exception {
        String path = "assets/hearthstead/lang/" + locale + ".json";
        var stream = MayorRosterLanguageContractTest.class.getClassLoader()
            .getResourceAsStream(path);
        assertNotNull(stream, path);
        try (var reader = new InputStreamReader(stream,
            StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static void assertText(JsonObject language, String key) {
        assertTrue(language.has(key), "missing language key " + key);
        assertTrue(!language.get(key).getAsString().isBlank(),
            "blank language key " + key);
    }
}
