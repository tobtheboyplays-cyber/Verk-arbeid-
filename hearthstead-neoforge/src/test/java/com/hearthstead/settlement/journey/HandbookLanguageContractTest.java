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
    private static final String ATTRIBUTES = "hearthstead.guide.attributes.body";
    private static final String WORK_PACE = "hearthstead.guide.dagsverk.body";
    private static final String WORK_PACE_TWO = "hearthstead.guide.dagsverk.body2";

    @Test
    void handbookTeachesCurrentEnglishPlayerFlow() throws Exception {
        JsonObject english = language("en_us");
        // The Tasks page is gone (owner decision); open requests live on the
        // Banner screen's Storage page, whose nav label is a literal there.
        String requestsPath = "Banner → Storage";

        assertContains(english, JOBS, "Job Emblem", "main hand", "right-click",
            "without sneaking", "automatically", "no separate Hire button",
            "starts with no job equipment", requestsPath,
            "Shift-right-click");
        assertOmits(english, JOBS, "open the Hire tab", "Press Hire");

        assertContains(english, LOGISTICS, "workplace chest", requestsPath,
            "route", "assigned Courier", "Stop reason", "Courier bag", "Warehouse");

        assertContains(english, WATCH, "starts without a weapon or armour",
            "Warehouse", "Courier", "Barracks", "Guard Orders", "Patrol",
            "Tower Post", "physical weapon");
        assertOmits(english, WATCH, "carries only their sword");
        assertContains(english, WATCH_TWO, "credited hostile kill", "combat XP",
            "never conjures equipment", "persisted patrol or tower order");
        assertContains(english, ATTRIBUTES, "eight numeric attributes", "0 to 100",
            "Strength", "Stamina", "Wits", "Dexterity", "Spirit",
            "Perception", "Focus", "Presence", "never choose the worker");
        assertOmits(english, ATTRIBUTES, "five numbers", "five attributes");
        assertContains(english, WORK_PACE, "Energy", "Work Pace",
            "not a hidden daily-work quota", "more slowly", "do not abruptly stop");
        assertContains(english, WORK_PACE_TWO, "Stamina", "minimum pace",
            "valid task remains valid");
        assertOmits(english, WORK_PACE, "stops taking new work", "twenty units");
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
