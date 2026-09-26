package com.hearthstead.conversation;

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
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * Fluent builder for {@link ConversationGraph}s registered in code.
 *
 * <pre>{@code
 * ConversationGraphs.register(Conversation.graph("hearthstead:brute_toll")
 *     .encounter(7, "low", "conversation.hearthstead.brute_toll.intro")
 *     .node("start", n -> n
 *         .line("conversation.hearthstead.brute_toll.demand")
 *         .option("pay", o -> o.text("conversation.hearthstead.brute_toll.pay")
 *             .cost(Conversation.food("toll")).relation(5).action("brute_toll.paid").end())
 *         .option("talk", o -> o.text("conversation.hearthstead.brute_toll.talk")
 *             .persuade(35, "intimidation")
 *             .success(s -> s.relation(10).action("brute_toll.talked_down").goTo("bye"))
 *             .failure(f -> f.relation(-15).action("brute_toll.fight").end()))
 *         .option("refuse", o -> o.text("...").action("brute_toll.fight").memory("...refused").end()))
 *     .node("bye", n -> n.line("...").option("leave", o -> o.text("...").end()))
 *     .build());
 * }</pre>
 * The first node added is the start node unless {@link GraphBuilder#start} says otherwise.
 */
public final class Conversation {
    private Conversation() {
    }

    public static GraphBuilder graph(String id) {
        return new GraphBuilder(id);
    }

    public static final class GraphBuilder {
        private final String id;
        private String start;
        private final Map<String, Node> nodes = new LinkedHashMap<>();
        private Encounter encounter = Encounter.DEFAULT;

        private GraphBuilder(String id) {
            this.id = id;
        }

        public GraphBuilder start(String nodeId) {
            this.start = nodeId;
            return this;
        }

        /** Walk-up pull-in radius, camera style ("low" or "eye") and intro line key. */
        public GraphBuilder encounter(int radius, String style, @Nullable String introLine) {
            this.encounter = new Encounter(true, radius, style, introLine);
            return this;
        }

        /** Opt out of the walk-up pull-in (right-click still talks). */
        public GraphBuilder noEncounter() {
            this.encounter = Encounter.NONE;
            return this;
        }

        public GraphBuilder node(String nodeId, Consumer<NodeBuilder> body) {
            NodeBuilder builder = new NodeBuilder(nodeId);
            body.accept(builder);
            if (start == null) start = nodeId;
            nodes.put(nodeId, builder.build());
            return this;
        }

        public ConversationGraph build() {
            return new ConversationGraph(id, start, nodes, encounter);
        }
    }

    public static final class NodeBuilder {
        private final String id;
        private final List<String> lines = new ArrayList<>();
        private final List<Option> options = new ArrayList<>();

        private NodeBuilder(String id) {
            this.id = id;
        }

        public NodeBuilder line(String langKey) {
            lines.add(langKey);
            return this;
        }

        public NodeBuilder option(String optionId, Consumer<OptionBuilder> body) {
            OptionBuilder builder = new OptionBuilder(optionId);
            body.accept(builder);
            options.add(builder.build());
            return this;
        }

        Node build() {
            return new Node(id, lines, options);
        }
    }

    public static final class OptionBuilder {
        private final String id;
        private String text;
        private String condition;
        private final List<CostSpec> costs = new ArrayList<>();
        private Check check;
        private final OutcomeBuilder success = new OutcomeBuilder();
        private OutcomeBuilder failure;
        private String barterStock;

        private OptionBuilder(String id) {
            this.id = id;
        }

        public OptionBuilder text(String langKey) {
            this.text = langKey;
            return this;
        }

        /** Registered condition id; prefix "!" to negate. False hides the reply. */
        public OptionBuilder when(String conditionId) {
            this.condition = conditionId;
            return this;
        }

        public OptionBuilder cost(CostSpec cost) {
            if (cost != null) costs.add(cost);
            return this;
        }

        public OptionBuilder persuade(int base, String skill) {
            this.check = new Check(base, skill);
            return this;
        }

        /** Opens the barter table on a stock registered with {@link BarterStocks}. */
        public OptionBuilder barter(String stockId) {
            this.barterStock = stockId;
            return this;
        }

        // Shorthands for the (success) outcome.
        public OptionBuilder relation(int delta) { success.relation(delta); return this; }
        public OptionBuilder reputation(int delta) { success.reputation(delta); return this; }
        public OptionBuilder action(String actionId) { success.action(actionId); return this; }
        public OptionBuilder reply(String langKey) { success.reply(langKey); return this; }
        public OptionBuilder memory(String langKey) { success.memory(langKey); return this; }
        public OptionBuilder goTo(String nodeId) { success.goTo(nodeId); return this; }
        public OptionBuilder end() { success.end(); return this; }

        public OptionBuilder success(Consumer<OutcomeBuilder> body) {
            body.accept(success);
            return this;
        }

        public OptionBuilder failure(Consumer<OutcomeBuilder> body) {
            failure = new OutcomeBuilder();
            body.accept(failure);
            return this;
        }

        Option build() {
            return new Option(id, text == null ? "conversation.hearthstead.option." + id : text,
                condition, costs, check, success.build(), failure == null ? null : failure.build(), barterStock);
        }
    }

    public static final class OutcomeBuilder {
        private int relation;
        private int reputation;
        private final List<String> actions = new ArrayList<>();
        private String next;
        private String reply;
        private String memory;

        public OutcomeBuilder relation(int delta) { relation += delta; return this; }
        public OutcomeBuilder reputation(int delta) { reputation += delta; return this; }
        public OutcomeBuilder action(String actionId) { actions.add(actionId); return this; }
        public OutcomeBuilder reply(String langKey) { reply = langKey; return this; }
        public OutcomeBuilder memory(String langKey) { memory = langKey; return this; }
        public OutcomeBuilder goTo(String nodeId) { next = nodeId; return this; }
        public OutcomeBuilder end() { next = null; return this; }

        Outcome build() {
            return new Outcome(relation, reputation, actions, next, reply, memory);
        }
    }

    // --------------------------------------------------------------- costs ---

    /** N of any food (counted by item), paid from inventory then the stores. */
    public static CostSpec food(int amount) {
        return new CostSpec(CostSpec.Kind.FOOD, null, amount, null);
    }

    /** Food amount read from the binding variable {@code var} at open time. */
    public static CostSpec food(String var) {
        return new CostSpec(CostSpec.Kind.FOOD, null, 0, var);
    }

    public static CostSpec coins(int amount) {
        return new CostSpec(CostSpec.Kind.COINS, null, amount, null);
    }

    public static CostSpec coins(String var) {
        return new CostSpec(CostSpec.Kind.COINS, null, 0, var);
    }

    /** Exact item by registry id, e.g. "minecraft:bread". */
    public static CostSpec item(String itemId, int amount) {
        return new CostSpec(CostSpec.Kind.ITEM, itemId, amount, null);
    }
}
