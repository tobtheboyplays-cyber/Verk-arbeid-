package com.hearthstead.conversation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hearthstead.conversation.ConversationGraph.Check;
import com.hearthstead.conversation.ConversationGraph.CostSpec;
import com.hearthstead.conversation.ConversationGraph.Encounter;
import com.hearthstead.conversation.ConversationGraph.Node;
import com.hearthstead.conversation.ConversationGraph.Option;
import com.hearthstead.conversation.ConversationGraph.Outcome;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a conversation graph from JSON (pure; Gson only). Format:
 * <pre>{@code
 * {
 *   "start": "start",
 *   "encounter": {"approach": true, "radius": 7, "style": "low", "intro": "lang.key"},
 *   "nodes": {
 *     "start": {
 *       "lines": ["lang.key"],
 *       "options": [
 *         {"id": "pay", "text": "lang.key", "when": "condition_id",
 *          "costs": [{"food": 8}, {"coins": "toll"}, {"item": "minecraft:bread", "count": 4}],
 *          "persuade": {"base": 30, "skill": "charisma"},
 *          "barter": "stock_id",
 *          "relation": 5, "reputation": 1, "actions": ["action_id"], "next": "node_id",
 *          "reply": "lang.key", "memory": "lang.key",
 *          "failure": {"relation": -10, "actions": [], "next": null}}
 *       ]
 *     }
 *   }
 * }
 * }</pre>
 * A number cost is fixed; a string names a binding variable.
 */
public final class GraphJson {
    private GraphJson() {
    }

    public static ConversationGraph read(String id, JsonObject json) {
        Map<String, Node> nodes = new LinkedHashMap<>();
        JsonObject nodeJson = json.has("nodes") ? json.getAsJsonObject("nodes") : new JsonObject();
        String firstNode = null;
        for (Map.Entry<String, JsonElement> entry : nodeJson.entrySet()) {
            if (firstNode == null) firstNode = entry.getKey();
            nodes.put(entry.getKey(), readNode(entry.getKey(), entry.getValue().getAsJsonObject()));
        }
        String start = string(json, "start", firstNode);
        Encounter encounter = Encounter.DEFAULT;
        if (json.has("encounter") && json.get("encounter").isJsonObject()) {
            JsonObject e = json.getAsJsonObject("encounter");
            encounter = new Encounter(bool(e, "approach", true), integer(e, "radius", 7),
                string(e, "style", "eye"), string(e, "intro", null));
        } else if (json.has("encounter") && json.get("encounter").isJsonPrimitive()
            && !json.get("encounter").getAsBoolean()) {
            encounter = Encounter.NONE;
        }
        return new ConversationGraph(id, start, nodes, encounter);
    }

    private static Node readNode(String id, JsonObject json) {
        List<String> lines = new ArrayList<>();
        if (json.has("lines")) for (JsonElement line : json.getAsJsonArray("lines")) lines.add(line.getAsString());
        List<Option> options = new ArrayList<>();
        if (json.has("options")) {
            for (JsonElement element : json.getAsJsonArray("options")) options.add(readOption(element.getAsJsonObject()));
        }
        return new Node(id, lines, options);
    }

    private static Option readOption(JsonObject json) {
        String id = string(json, "id", "");
        List<CostSpec> costs = new ArrayList<>();
        if (json.has("costs")) {
            for (JsonElement element : json.getAsJsonArray("costs")) costs.add(readCost(element.getAsJsonObject()));
        }
        Check check = null;
        if (json.has("persuade")) {
            JsonObject p = json.getAsJsonObject("persuade");
            check = new Check(integer(p, "base", 30), string(p, "skill", "charisma"));
        }
        Outcome failure = json.has("failure") ? readOutcome(json.getAsJsonObject("failure")) : null;
        return new Option(id, string(json, "text", "conversation.hearthstead.option." + id),
            string(json, "when", null), costs, check, readOutcome(json), failure, string(json, "barter", null));
    }

    private static Outcome readOutcome(JsonObject json) {
        List<String> actions = new ArrayList<>();
        if (json.has("actions")) {
            JsonArray array = json.getAsJsonArray("actions");
            for (JsonElement element : array) actions.add(element.getAsString());
        }
        return new Outcome(integer(json, "relation", 0), integer(json, "reputation", 0), actions,
            string(json, "next", null), string(json, "reply", null), string(json, "memory", null));
    }

    private static CostSpec readCost(JsonObject json) {
        if (json.has("food")) return amount(CostSpec.Kind.FOOD, null, json.get("food"));
        if (json.has("coins")) return amount(CostSpec.Kind.COINS, null, json.get("coins"));
        String item = string(json, "item", null);
        return amount(CostSpec.Kind.ITEM, item, json.has("count") ? json.get("count") : null);
    }

    private static CostSpec amount(CostSpec.Kind kind, String item, JsonElement value) {
        if (value == null || value.isJsonNull()) return new CostSpec(kind, item, 1, null);
        if (value.getAsJsonPrimitive().isNumber()) return new CostSpec(kind, item, value.getAsInt(), null);
        return new CostSpec(kind, item, 0, value.getAsString());
    }

    private static String string(JsonObject json, String key, String fallback) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }

    private static int integer(JsonObject json, String key, int fallback) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsInt();
    }

    private static boolean bool(JsonObject json, String key, boolean fallback) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsBoolean();
    }
}
