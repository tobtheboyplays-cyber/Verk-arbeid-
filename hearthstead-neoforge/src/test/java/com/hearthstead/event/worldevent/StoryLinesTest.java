package com.hearthstead.event.worldevent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** Condition-aware line picking and the "who is due" rules (owner rule: only true claims). */
class StoryLinesTest {
    //                                         day pop bld houses guards def held rank beds tavern food
    private static final StoryFacts BARE = new StoryFacts(4, 5, 3, 1, 0, 0, 0, 0, 3, false, 5);
    private static final StoryFacts GUARDED = new StoryFacts(12, 9, 8, 3, 3, 0, 2, 1, 4, true, 40);
    private static final StoryFacts TOWER_ONLY = new StoryFacts(12, 9, 8, 3, 0, 1, 0, 1, 4, true, 40);

    private static VisitorMemory.Person seen(StoryFacts then, int mood, String choice) {
        VisitorMemory.Book book = new VisitorMemory.Book();
        book.recordVisit("x", then);
        book.remember("x", "X", choice, mood, then.day());
        return book.person("x");
    }

    @Test
    void aFirstVisitGreetsAndNeverRemembers() {
        StoryLines.Lines lines = StoryLines.pick(StoryCharacter.HOLLINS, BARE, null, StoryLines.Extra.NONE);
        assertTrue(lines.keys().get(0).endsWith(".greet"));
        assertFalse(lines.keys().stream().anyMatch(k -> k.contains(".memory_")));
        assertTrue(lines.keys().get(lines.keys().size() - 1).endsWith(".wish"));
    }

    @Test
    void aReturnComparesWithWhatTheyLastSaw() {
        StoryFacts later = new StoryFacts(10, 11, 5, 3, 0, 0, 0, 0, 3, false, 5);
        StoryLines.Lines lines = StoryLines.pick(StoryCharacter.HOLLINS, later, seen(BARE, 0, "thank"),
            StoryLines.Extra.NONE);
        assertTrue(lines.keys().contains(StoryLines.PREFIX + "hollins.greet_again"));
        assertTrue(lines.keys().contains(StoryLines.PREFIX + "hollins.memory_grew"));
        Map<String, Integer> v = lines.vars();
        assertEquals(11, v.get("a"));
        assertEquals(5, v.get("b"));
        assertEquals(6, v.get("c"));
        assertEquals(2, v.get("d"));
    }

    @Test
    void anUpsetReturnIsColder() {
        StoryLines.Lines lines = StoryLines.pick(StoryCharacter.BRISKS, BARE,
            seen(BARE, VisitorMemory.DISPLEASED, "decline"), StoryLines.Extra.NONE);
        assertTrue(lines.keys().contains(StoryLines.PREFIX + "brisks.greet_upset"));
    }

    @Test
    void aRankUpIsWhatTheHeraldNotices() {
        StoryLines.Lines lines = StoryLines.pick(StoryCharacter.PELL_ROOK, GUARDED, seen(BARE, 0, "receive"),
            new StoryLines.Extra(6, 12, 15, 1));
        assertTrue(lines.keys().contains(StoryLines.PREFIX + "pell_rook.memory_rank_village"));
        assertTrue(lines.keys().contains(StoryLines.PREFIX + "pell_rook.fact_warm"));
    }

    @Test
    void guardsAndWallsAreOnlyClaimedWhenTheyExist() {
        for (StoryCharacter c : StoryCharacter.values()) {
            List<String> bare = StoryLines.pick(c, BARE, null, StoryLines.Extra.NONE).keys();
            assertFalse(bare.stream().anyMatch(k -> k.endsWith("fact_guards") || k.endsWith("fact_guarded")
                || k.endsWith("fact_towers") || k.endsWith("fact_blooded") || k.endsWith("fact_heard_raid")
                || k.endsWith("fact_history_raids") || k.endsWith("fact_many_songs")),
                c.id() + " claims defenders or raids a bare village does not have: " + bare);
        }
        List<String> threat = StoryLines.pick(StoryCharacter.SIGRUN, GUARDED, null, StoryLines.Extra.NONE).keys();
        assertTrue(threat.contains(StoryLines.PREFIX + "sigrun.fact_guards"));
        List<String> tower = StoryLines.pick(StoryCharacter.SIGRUN, TOWER_ONLY, null, StoryLines.Extra.NONE).keys();
        assertTrue(tower.contains(StoryLines.PREFIX + "sigrun.fact_towers"), "a tower without guards is not 'guards'");
        List<String> open = StoryLines.pick(StoryCharacter.SIGRUN, BARE, null, StoryLines.Extra.NONE).keys();
        assertTrue(open.contains(StoryLines.PREFIX + "sigrun.fact_open"));
    }

    @Test
    void theLastWarningRemembersTheFirstAnswer() {
        List<String> keys = StoryLines.pick(StoryCharacter.SIGRUN, BARE, seen(BARE, -1, "defy"),
            new StoryLines.Extra(10, 5, 0, 2)).keys();
        assertEquals(StoryLines.PREFIX + "sigrun.last_warning", keys.get(0));
        assertTrue(keys.contains(StoryLines.PREFIX + "sigrun.memory_defied"));
        assertTrue(keys.contains(StoryLines.PREFIX + "sigrun.ultimatum_final"));
    }

    @Test
    void everyPickableLineAndOptionHasEnglishText() throws Exception {
        JsonObject lang;
        try (var in = StoryLinesTest.class.getResourceAsStream("/assets/hearthstead/lang/en_us.json")) {
            lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        for (StoryCharacter c : StoryCharacter.values()) {
            for (String key : StoryLines.allKeys(c)) assertTrue(lang.has(key), "missing lang " + key);
            for (int step = 1; step <= 2; step++) {
                for (StoryOptions.Spec spec : StoryOptions.of(c, step)) {
                    String key = StoryLines.PREFIX + c.id() + ".opt." + spec.id();
                    assertTrue(lang.has(key), "missing option lang " + key);
                }
            }
            if (c != StoryCharacter.THANKS) {
                assertTrue(lang.has("conversation.hearthstead.title.story_" + c.id()), "missing title " + c.id());
            }
        }
        for (String m : StoryRules.MILESTONES) assertTrue(lang.has("hearthstead.story.thanks.line." + m));
        assertTrue(lang.has("hearthstead.story.remember.displeased"));
    }

    // -------------------------------------------------------------- rules --

    private static StoryRules.Context ctx(long age) {
        return new StoryRules.Context(age, false, -1, 0, -1, false, 0, 1);
    }

    @Test
    void theNeighboursComeOnceThereIsAHouseAndThenOnlyAfterGrowth() {
        VisitorMemory.Book book = new VisitorMemory.Book();
        assertTrue(StoryRules.due(StoryCharacter.HOLLINS, BARE, ctx(3), book));
        assertFalse(StoryRules.due(StoryCharacter.HOLLINS, BARE, ctx(1), book), "not on day one");
        book.recordVisit("hollins", BARE);
        StoryFacts sameLater = new StoryFacts(20, 5, 3, 1, 0, 0, 0, 0, 3, false, 5);
        assertFalse(StoryRules.due(StoryCharacter.HOLLINS, sameLater, ctx(20), book), "nothing new to see");
        StoryFacts grown = new StoryFacts(20, 7, 3, 1, 0, 0, 0, 0, 3, false, 5);
        assertTrue(StoryRules.due(StoryCharacter.HOLLINS, grown, ctx(20), book));
        StoryFacts tooSoon = new StoryFacts(6, 7, 3, 1, 0, 0, 0, 0, 3, false, 5);
        assertFalse(StoryRules.due(StoryCharacter.HOLLINS, tooSoon, ctx(6), book), "cooldown");
    }

    @Test
    void theBardSingsOnceForEachHeldRaid() {
        VisitorMemory.Book book = new VisitorMemory.Book();
        StoryRules.Context held = new StoryRules.Context(8, false, 7, 0, 7, true, 0, 2);
        assertEquals(StoryCharacter.WENNA, StoryRules.nextVisitor(GUARDED, held, book));
        book.setFlag("sung:7");
        book.recordVisit("wenna", GUARDED);
        assertFalse(StoryRules.due(StoryCharacter.WENNA, GUARDED, held, book));
    }

    @Test
    void settlersThankEachMilestoneOnce() {
        VisitorMemory.Book book = new VisitorMemory.Book();
        StoryRules.Context old = ctx(11);
        assertEquals("first_raid_held", StoryRules.dueMilestone(GUARDED, old, book));
        book.setFlag("thanks:first_raid_held");
        assertEquals("day10", StoryRules.dueMilestone(GUARDED, old, book));
        book.setFlag("thanks:day10");
        assertNull(StoryRules.dueMilestone(GUARDED, old, book));
    }

    @Test
    void newcomersNeedRealBedsAndComeBackOnlyAfterBeingTurnedAway() {
        VisitorMemory.Book book = new VisitorMemory.Book();
        StoryFacts noBeds = new StoryFacts(12, 9, 8, 3, 3, 0, 2, 1, 1, true, 40);
        assertFalse(StoryRules.due(StoryCharacter.BRISKS, noBeds, ctx(12), book));
        assertTrue(StoryRules.due(StoryCharacter.BRISKS, GUARDED, ctx(12), book));
        book.recordVisit("brisks", GUARDED);
        book.remember("brisks", "Aldo Brisk", "feed", VisitorMemory.PLEASED, 12);
        StoryFacts later = new StoryFacts(30, 9, 8, 3, 3, 0, 2, 1, 4, true, 40);
        assertFalse(StoryRules.due(StoryCharacter.BRISKS, later, ctx(30), book));
        book.remember("brisks", "Aldo Brisk", "decline", VisitorMemory.DISPLEASED, 12);
        assertTrue(StoryRules.due(StoryCharacter.BRISKS, later, ctx(30), book));
    }

    @Test
    void threatsWaitForTheGraceAndPlayOneLadderAtATime() {
        VisitorMemory.Book book = new VisitorMemory.Book();
        StoryRules.Context young = new StoryRules.Context(8, false, -1, 0, -1, false, -30, 3);
        assertNull(StoryRules.nextThreat(GUARDED, young, book), "grace: never before hostileReady");
        StoryRules.Context ready = new StoryRules.Context(8, false, -1, 0, -1, true, -30, 3);
        assertEquals(StoryCharacter.SIGRUN, StoryRules.nextThreat(GUARDED, ready, book));
        VisitorMemory.Ladder varg = book.ladder(StoryRules.LADDER_VARG);
        varg.step = 2;
        varg.status = VisitorMemory.Ladder.ARMED;
        varg.notBeforeDay = 20;
        assertNull(StoryRules.nextThreat(GUARDED, ready, book), "Varg's ladder is open: no bailiff, and not before day 20");
        StoryFacts day20 = new StoryFacts(20, 9, 8, 3, 3, 0, 2, 1, 4, true, 40);
        assertEquals(StoryCharacter.SIGRUN, StoryRules.nextThreat(day20, ready, book));
        varg.status = VisitorMemory.Ladder.PAID;
        assertEquals(StoryCharacter.HAMON, StoryRules.nextThreat(day20, ready, book), "enemy of the lord: the bailiff");
    }

    @Test
    void theTributeIsNeverMoreThanHalfTheStoredCoins() {
        assertEquals(3, StoryRules.tribute(10, 0, 1));
        assertEquals(5, StoryRules.tribute(10, 10, 1));
        assertEquals(11, StoryRules.tribute(10, 100, 1));
        assertEquals(22, StoryRules.tribute(10, 100, 2));
        assertTrue(StoryRules.tribute(40, 1000, 2) <= 32);
    }

    @Test
    void displeasingAnswersAreTheOnesThatAreRemembered() {
        assertEquals(VisitorMemory.DISPLEASED, StoryOptions.mood(StoryCharacter.BRISKS, "decline"));
        assertEquals(VisitorMemory.DISPLEASED, StoryOptions.mood(StoryCharacter.SIGRUN, "defy"));
        assertEquals(VisitorMemory.PLEASED, StoryOptions.mood(StoryCharacter.HOLLINS, "give_back"));
        assertEquals(VisitorMemory.NEUTRAL, StoryOptions.mood(StoryCharacter.HOLLINS, "thank"));
        assertTrue(StoryTalk.OUTCOMES.containsAll(StoryTalk.actions(StoryCharacter.SIGRUN, 1)));
        for (StoryCharacter c : StoryCharacter.values()) {
            for (int step = 1; step <= 2; step++) {
                assertTrue(StoryTalk.OUTCOMES.containsAll(StoryTalk.actions(c, step)), c.id() + " action registered");
            }
        }
    }

    @Test
    void everyVisitGraphPassesTheValidator() {
        for (StoryCharacter c : StoryCharacter.values()) {
            if (c == StoryCharacter.THANKS) continue;
            for (int step = 1; step <= 2; step++) {
                StoryLines.Lines lines = StoryLines.pick(c, GUARDED, null, new StoryLines.Extra(5, 9, 0, step));
                var graph = StoryTalk.build(StoryTalk.graphId(c, step, lines), c, step, lines.keys());
                assertTrue(com.hearthstead.conversation.GraphValidator.problems(graph).isEmpty(),
                    c.id() + " step " + step + ": " + com.hearthstead.conversation.GraphValidator.problems(graph));
            }
        }
    }
}
