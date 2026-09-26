package com.hearthstead.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GraphValidatorTest {
    private static ConversationGraph bruteToll() {
        return Conversation.graph("hearthstead:test_toll")
            .encounter(7, "low", "k.intro")
            .node("start", n -> n.line("k.demand")
                .option("pay", o -> o.text("k.pay").cost(Conversation.food("toll")).relation(5).action("toll.paid").end())
                .option("talk", o -> o.text("k.talk").persuade(35, "intimidation")
                    .success(s -> s.relation(10).goTo("bye"))
                    .failure(f -> f.relation(-15).action("toll.fight").end()))
                .option("refuse", o -> o.text("k.refuse").memory("k.memory").end()))
            .node("bye", n -> n.line("k.bye").option("leave", o -> o.text("k.leave").end()))
            .build();
    }

    @Test
    void builderGraphIsValid() {
        ConversationGraph graph = bruteToll();
        assertEquals(List.of(), GraphValidator.problems(graph));
        assertEquals("start", graph.start());
        assertEquals("low", graph.encounter().style());
        assertEquals(7, graph.encounter().radius());
        var talk = graph.node("start").options().get(1);
        assertEquals(35, talk.check().base());
        assertEquals("bye", talk.outcome().next());
        assertEquals(-15, talk.failure().relation());
        assertNull(talk.failure().next());
        assertEquals(12, graph.node("start").options().get(0).costs().get(0).resolve(Map.of("toll", 12)));
    }

    @Test
    void danglingGotoMissingStartAndDeadEndsAreRefused() {
        ConversationGraph dangling = Conversation.graph("hearthstead:bad")
            .node("start", n -> n.line("a").option("go", o -> o.text("b").goTo("nowhere")))
            .build();
        assertTrue(GraphValidator.problems(dangling).stream().anyMatch(p -> p.contains("missing node 'nowhere'")));

        ConversationGraph deadEnd = Conversation.graph("hearthstead:bad2")
            .node("start", n -> n.line("a"))
            .build();
        assertTrue(GraphValidator.problems(deadEnd).stream().anyMatch(p -> p.contains("no replies")));

        ConversationGraph noStart = Conversation.graph("hearthstead:bad3").start("x")
            .node("start", n -> n.line("a").option("leave", o -> o.text("b").end()))
            .build();
        assertFalse(GraphValidator.valid(noStart));
    }

    @Test
    void unreachableNodesDuplicatesAndTooManyRepliesAreRefused() {
        ConversationGraph unreachable = Conversation.graph("hearthstead:bad4")
            .node("start", n -> n.line("a").option("leave", o -> o.text("b").end()))
            .node("island", n -> n.line("c").option("leave", o -> o.text("d").end()))
            .build();
        assertTrue(GraphValidator.problems(unreachable).stream().anyMatch(p -> p.contains("unreachable")));

        ConversationGraph duplicate = Conversation.graph("hearthstead:bad5")
            .node("start", n -> n.line("a").option("x", o -> o.text("b").end()).option("x", o -> o.text("c").end()))
            .build();
        assertTrue(GraphValidator.problems(duplicate).stream().anyMatch(p -> p.contains("duplicate")));

        ConversationGraph tooMany = Conversation.graph("hearthstead:bad6")
            .node("start", n -> {
                n.line("a");
                for (int i = 0; i < 10; i++) {
                    String id = "o" + i;
                    n.option(id, o -> o.text("t").end());
                }
            })
            .build();
        assertTrue(GraphValidator.problems(tooMany).stream().anyMatch(p -> p.contains("more than 9")));

        ConversationGraph badId = Conversation.graph("No Namespace")
            .node("start", n -> n.line("a").option("leave", o -> o.text("b").end()))
            .build();
        assertFalse(GraphValidator.valid(badId));
    }

    @Test
    void jsonReadsTheSameShape() {
        String json = """
            {
              "start": "start",
              "encounter": {"approach": true, "radius": 6, "style": "eye", "intro": "k.intro"},
              "nodes": {
                "start": {
                  "lines": ["k.hello"],
                  "options": [
                    {"id": "bread", "text": "k.bread", "costs": [{"item": "minecraft:bread", "count": 3}, {"coins": "fee"}],
                     "relation": 4, "actions": ["a.one"], "next": "end"},
                    {"id": "sway", "text": "k.sway", "persuade": {"base": 40, "skill": "charisma"},
                     "relation": 6, "failure": {"relation": -8}},
                    {"id": "trade", "text": "k.trade", "barter": "stock.peddler", "next": "end"}
                  ]
                },
                "end": {"lines": ["k.bye"], "options": [{"id": "leave", "text": "k.leave"}]}
              }
            }""";
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        ConversationGraph graph = GraphJson.read("hearthstead:json_test", object);
        assertEquals(List.of(), GraphValidator.problems(graph));
        var bread = graph.node("start").options().get(0);
        assertEquals(ConversationGraph.CostSpec.Kind.ITEM, bread.costs().get(0).kind());
        assertEquals(3, bread.costs().get(0).amount());
        assertEquals(9, bread.costs().get(1).resolve(Map.of("fee", 9)));
        assertEquals(List.of("a.one"), bread.outcome().actions());
        var sway = graph.node("start").options().get(1);
        assertEquals(40, sway.check().base());
        assertEquals(-8, sway.failure().relation());
        assertEquals("stock.peddler", graph.node("start").options().get(2).barterStock());
        assertEquals(6, graph.encounter().radius());
    }

    @Test
    void shippedDataGraphsAreValid() throws Exception {
        for (String name : List.of("traveller")) {
            var stream = getClass().getResourceAsStream("/data/hearthstead/conversations/" + name + ".json");
            assertNotNull(stream, name);
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                ConversationGraph graph = GraphJson.read("hearthstead:" + name, JsonParser.parseReader(reader).getAsJsonObject());
                assertEquals(List.of(), GraphValidator.problems(graph), name);
            }
        }
    }
}
