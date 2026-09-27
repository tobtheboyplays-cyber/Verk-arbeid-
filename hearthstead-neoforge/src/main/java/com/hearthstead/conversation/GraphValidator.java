package com.hearthstead.conversation;

import com.hearthstead.conversation.ConversationGraph.Node;
import com.hearthstead.conversation.ConversationGraph.Option;
import com.hearthstead.conversation.ConversationGraph.Outcome;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure structural check of a {@link ConversationGraph}. A graph with any
 * problem is refused at registration/reload (logged, never half-loaded):
 * missing start, dangling goTo, more than nine replies, a node without a
 * way out, unreachable nodes, blank text, negative costs.
 */
public final class GraphValidator {
    private GraphValidator() {
    }

    public static List<String> problems(ConversationGraph graph) {
        List<String> out = new ArrayList<>();
        if (graph == null) {
            out.add("graph is null");
            return out;
        }
        if (graph.id() == null || !graph.id().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            out.add("bad id '" + graph.id() + "'");
        }
        if (graph.nodes().isEmpty()) {
            out.add("no nodes");
            return out;
        }
        if (graph.node(graph.start()) == null) {
            out.add("start node '" + graph.start() + "' missing");
        }
        for (Node node : graph.nodes().values()) {
            String at = "node '" + node.id() + "': ";
            if (node.lines().size() > ConversationGraph.MAX_LINES) out.add(at + "more than 6 lines");
            for (String line : node.lines()) if (line == null || line.isBlank()) out.add(at + "blank line key");
            if (node.options().isEmpty()) out.add(at + "no replies (a talk must always have a way out)");
            if (node.options().size() > ConversationGraph.MAX_OPTIONS) out.add(at + "more than 9 replies");
            Set<String> seen = new HashSet<>();
            for (Option option : node.options()) {
                String o = at + "reply '" + option.id() + "': ";
                if (option.id() == null || option.id().isBlank()) out.add(at + "reply without id");
                else if (!seen.add(option.id())) out.add(o + "duplicate id");
                if (option.text() == null || option.text().isBlank()) out.add(o + "blank text");
                for (var cost : option.costs()) {
                    if (cost.amount() < 0) out.add(o + "negative cost");
                    if (cost.kind() == ConversationGraph.CostSpec.Kind.ITEM
                        && (cost.item() == null || !cost.item().contains(":"))) out.add(o + "item cost without item id");
                }
                if (option.check() != null && (option.check().base() < 0 || option.check().base() > 100)) {
                    out.add(o + "persuasion base outside 0..100");
                }
                if (option.check() == null && option.failure() != null) out.add(o + "failure outcome without a check");
                checkNext(graph, option.outcome(), o, out);
                if (option.failure() != null) checkNext(graph, option.failure(), o + "failure: ", out);
            }
        }
        for (String unreachable : unreachable(graph)) out.add("node '" + unreachable + "' is unreachable");
        return out;
    }

    public static boolean valid(ConversationGraph graph) {
        return problems(graph).isEmpty();
    }

    private static void checkNext(ConversationGraph graph, Outcome outcome, String at, List<String> out) {
        if (outcome.next() != null && graph.node(outcome.next()) == null) {
            out.add(at + "goes to missing node '" + outcome.next() + "'");
        }
        for (String action : outcome.actions()) {
            if (action == null || action.isBlank()) out.add(at + "blank action id");
        }
    }

    private static List<String> unreachable(ConversationGraph graph) {
        Set<String> reached = new HashSet<>();
        Deque<String> todo = new ArrayDeque<>();
        if (graph.node(graph.start()) != null) todo.add(graph.start());
        while (!todo.isEmpty()) {
            String id = todo.poll();
            if (!reached.add(id)) continue;
            Node node = graph.node(id);
            if (node == null) continue;
            for (Option option : node.options()) {
                if (option.outcome().next() != null) todo.add(option.outcome().next());
                if (option.failure() != null && option.failure().next() != null) todo.add(option.failure().next());
            }
        }
        List<String> out = new ArrayList<>();
        for (String id : graph.nodes().keySet()) if (!reached.contains(id)) out.add(id);
        return out;
    }
}
