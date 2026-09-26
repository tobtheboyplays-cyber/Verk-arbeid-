package com.hearthstead.client.ui2.handbook;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** QA nits (c) and (d), 26 Sep: familiar ingredients first; title matches rank first. */
class HandbookSearchTest {

    @Test
    void titleMatchesBeatBodyAndDetailMatches() {
        List<String> pages = List.of("detail-only", "body", "title", "heading");
        List<String> ranked = HandbookSearch.rank(pages, new String[] {"raid"},
            List.of("welcome", "coins", "raids", "watch"),
            List.of("start here", "coins", "defence", "raids & the alarm bell"),
            List.of("", "winning a raid pays", "", ""),
            List.of("a raid is coming", "", "", ""), 10);
        assertEquals(List.of("title", "heading", "body", "detail-only"), ranked);
    }

    @Test
    void everyTermMustMatchSomewhere() {
        List<String> ranked = HandbookSearch.rank(List.of("a", "b"), new String[] {"raid", "bell"},
            List.of("raids", "raids"), List.of("", "alarm bell"), List.of("", ""), List.of("", ""), 10);
        assertEquals(List.of("b"), ranked);
    }

    @Test
    void recipeGridsRestOnFamiliarIngredients() {
        assertTrue(HandbookSearch.commonRank("minecraft:oak_log") < HandbookSearch.commonRank("minecraft:crimson_stem"));
        assertTrue(HandbookSearch.commonRank("minecraft:cobblestone")
            < HandbookSearch.commonRank("minecraft:cobbled_deepslate"));
        assertTrue(HandbookSearch.commonRank("minecraft:oak_planks")
            < HandbookSearch.commonRank("minecraft:dark_oak_planks"));
    }
}
