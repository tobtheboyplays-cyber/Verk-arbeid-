package com.hearthstead.conversation;

import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * One data-driven dialog: nodes of spoken lines and numbered replies. Pure
 * data (no Minecraft types) so {@link GraphValidator} and the JSON reader are
 * unit-testable. Text is always a lang key; costs name items by registry id.
 *
 * <p>Build one in code with {@link Conversation#graph}, or ship JSON at
 * {@code data/<ns>/conversations/<path>.json} (id {@code <ns>:<path>}).
 */
public record ConversationGraph(String id, String start, Map<String, Node> nodes, Encounter encounter) {
    /** At most nine replies per node: keys 1-9. */
    public static final int MAX_OPTIONS = 9;
    public static final int MAX_LINES = 6;

    public ConversationGraph {
        nodes = Map.copyOf(nodes);
        encounter = encounter == null ? Encounter.DEFAULT : encounter;
    }

    @Nullable
    public Node node(String nodeId) {
        return nodeId == null ? null : nodes.get(nodeId);
    }

    /**
     * Shadow-of-War pull-in: walking within {@code radius} blocks (line of
     * sight, not fighting) opens this conversation with an intro cinematic.
     * {@code style} frames the camera: "low" (brutes, captains) or "eye".
     * {@code introLine} is the lang key the NPC opens with (else the start
     * node's first line).
     */
    public record Encounter(boolean approach, int radius, String style, @Nullable String introLine) {
        public static final Encounter DEFAULT = new Encounter(true, 7, "eye", null);
        public static final Encounter NONE = new Encounter(false, 0, "eye", null);

        public Encounter {
            radius = Math.max(2, Math.min(16, radius <= 0 ? 7 : radius));
            style = "low".equals(style) ? "low" : "eye";
        }
    }

    public record Node(String id, List<String> lines, List<Option> options) {
        public Node {
            lines = List.copyOf(lines);
            options = List.copyOf(options);
        }
    }

    /**
     * One reply. {@code condition} is a registered condition id ("!" negates)
     * that hides the reply when false; {@code check} makes it a persuasion
     * attempt whose {@code failure} outcome runs on a failed roll; a
     * {@code barterStock} opens the barter table on that stock id.
     */
    public record Option(String id, String text, @Nullable String condition, List<CostSpec> costs,
                         @Nullable Check check, Outcome outcome, @Nullable Outcome failure,
                         @Nullable String barterStock) {
        public Option {
            costs = List.copyOf(costs);
            outcome = outcome == null ? Outcome.END : outcome;
        }
    }

    /** Persuasion: base chance and the player skill that helps ("charisma", "intimidation", "trade"). */
    public record Check(int base, String skill) {
    }

    /**
     * What a reply does: relation/reputation deltas, registered action ids run
     * in order, the next node (null ends the talk), an optional line the NPC
     * says in reply, and an optional memory the NPC keeps of this choice
     * ("Remembers you: you refused him food").
     */
    public record Outcome(int relation, int reputation, List<String> actions, @Nullable String next,
                          @Nullable String reply, @Nullable String memory) {
        public static final Outcome END = new Outcome(0, 0, List.of(), null, null, null);

        public Outcome {
            actions = List.copyOf(actions);
        }
    }

    /**
     * A price: {@code kind} ITEM (with {@code item} registry id), FOOD (any
     * food, counted by item) or COINS. The amount is fixed, or read from the
     * binding's variables when {@code amountVar} is set.
     */
    public record CostSpec(Kind kind, @Nullable String item, int amount, @Nullable String amountVar) {
        public enum Kind { ITEM, FOOD, COINS }

        public int resolve(Map<String, Integer> vars) {
            if (amountVar != null && vars != null && vars.containsKey(amountVar)) {
                return Math.max(0, vars.get(amountVar));
            }
            return Math.max(0, amount);
        }
    }
}
