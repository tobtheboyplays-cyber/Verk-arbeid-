package com.hearthstead.settlement.journey;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the Handbook to the physical Emblem, request and equipment loops. */
class HandbookLanguageContractTest {

    private static final String JOBS = "hearthstead.guide.jobs.body";
    private static final String LOGISTICS = "hearthstead.guide.logistics.body";
    private static final String WATCH = "hearthstead.guide.watch.body";
    private static final String WATCH_TWO = "hearthstead.guide.watch.body2";

    @Test
    void handbookTeachesCurrentEnglishPlayerFlow() throws Exception {
        JsonObject english = language("en_us");

        assertContains(english, JOBS, "Job Emblem", "main hand", "right-click",
            "without sneaking", "automatically", "no separate Hire button",
            "starts with no job equipment", "Hearth → Requests",
            "Shift-right-click");
        assertOmits(english, JOBS, "open the Hire tab", "Press Hire");

        assertContains(english, LOGISTICS, "workplace chest", "Hearth → Requests",
            "source-to-target route", "assigned Courier", "physical owner",
            "Stop reason", "Courier bag", "Warehouse");

        assertContains(english, WATCH, "starts without a weapon or armour",
            "Warehouse", "Courier", "Barracks", "Guard Orders", "Patrol",
            "Tower Post", "physical weapon");
        assertOmits(english, WATCH, "carries only their sword");
        assertContains(english, WATCH_TWO, "credited hostile kill", "combat XP",
            "never conjures equipment", "persisted patrol or tower order");
    }

    @Test
    void norwegianHandbookCarriesTheSameGameplayContract() throws Exception {
        JsonObject norwegian = language("nb_no");

        assertContains(norwegian, JOBS, "jobb-emblemer", "hovedhånden",
            "høyreklikk", "uten å snike", "automatisk",
            "ingen egen Ansett-knapp", "starter uten jobbutstyr",
            "Hearth → Requests", "Shift-høyreklikk");
        assertOmits(norwegian, JOBS, "åpne Ansett-fanen", "Trykk Ansett");

        assertContains(norwegian, LOGISTICS, "arbeidskisten",
            "Hearth → Requests", "kilde til mål", "tildelt Courier",
            "fysisk eier", "Stoppårsak", "Courier-sekk", "Warehouse");

        assertContains(norwegian, WATCH, "starter uten våpen eller rustning",
            "Warehouse", "Courier", "Barracks", "Guard Orders", "patrulje",
            "Tower Post", "fysiske våpenet");
        assertOmits(norwegian, WATCH, "bærer bare sverdet sitt");
        assertContains(norwegian, WATCH_TWO, "godskrevet drap", "combat XP",
            "skaper aldri utstyr", "lagret patrulje- eller tårnordre");
    }

    private static JsonObject language(String locale) throws Exception {
        String path = "assets/hearthstead/lang/" + locale + ".json";
        var stream = HandbookLanguageContractTest.class.getClassLoader()
            .getResourceAsStream(path);
        assertNotNull(stream, path);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static void assertContains(JsonObject language, String key,
                                       String... fragments) {
        assertTrue(language.has(key), "missing language key " + key);
        String text = language.get(key).getAsString();
        for (String fragment : fragments) {
            assertTrue(text.contains(fragment), () -> key + " must contain '"
                + fragment + "' but was: " + text);
        }
    }

    private static void assertOmits(JsonObject language, String key,
                                    String... fragments) {
        String text = language.get(key).getAsString();
        for (String fragment : fragments) {
            assertFalse(text.contains(fragment), () -> key + " must not contain '"
                + fragment + "' but was: " + text);
        }
    }
}
