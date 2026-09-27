package com.hearthstead.client.screen;

import com.google.gson.JsonObject;
import com.hearthstead.client.ui2.handbook.HandbookBook;
import com.hearthstead.client.ui2.handbook.HandbookTestData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every Handbook page has text, and no written page is unreachable.
 *
 * <p>Since the visual rework (26 Sep) the book's structure is data
 * ({@code assets/hearthstead/handbook/}); this reads the same files the
 * screen does. Keys under {@code hearthstead.guide.ui.*} are the screen's
 * own chrome, not chapter text.
 */
class HandbookChapterLanguageTest {

    private static final Set<String> NON_CHAPTER_KEYS = Set.of(
        "hearthstead.guide.title", "hearthstead.guide.nav.first",
        "hearthstead.guide.nav.last", "hearthstead.guide.narration.current");

    @Test
    void everyChapterPageHasTextAndEveryWrittenPageIsInTheBook() {
        JsonObject english = HandbookTestData.english();
        HandbookBook book = HandbookTestData.book();
        List<String> keys = book.langKeys();
        for (String key : keys) {
            assertTrue(english.has(key), "missing Handbook key " + key);
            assertFalse(english.get(key).getAsString().isBlank(), "blank Handbook key " + key);
        }
        for (String key : english.keySet()) {
            if (key.startsWith("hearthstead.guide.") && !NON_CHAPTER_KEYS.contains(key)
                && !key.startsWith("hearthstead.guide.ui.")) {
                assertTrue(keys.contains(key),
                    key + " is written but no Handbook chapter shows it");
            }
        }
        for (String chapter : List.of("coins", "work_zones", "tech_tree", "trade_levels",
                "crafting_orders", "tavern", "raids", "start_here", "builder", "command",
                "finisher_revive")) {
            assertTrue(keys.contains("hearthstead.guide." + chapter + ".title"),
                "new-player chapter missing: " + chapter);
        }
        // Every page of the pre-rework book is still in it, as "More detail" text.
        for (String legacy : List.of("founding.body", "coins.body", "coins.body2", "plaque.body",
                "plaque.body2", "work_zones.body", "work_zones.body2", "jobs.body", "tech_tree.body",
                "tech_tree.body2", "logistics.body", "crafting_orders.body", "summons.body",
                "recruiting.body", "tavern.body", "tavern.body2", "attributes.body", "attributes.body2",
                "trade_levels.body", "trade_levels.body2", "day.body", "dagsverk.body", "dagsverk.body2",
                "research.body", "research.body2", "guildmaster.body", "watch.body", "watch.body2",
                "threat.body", "threat.body2", "raids.body", "raids.body2", "saga.body")) {
            assertTrue(keys.contains("hearthstead.guide." + legacy), "legacy page dropped: " + legacy);
        }
    }
}
