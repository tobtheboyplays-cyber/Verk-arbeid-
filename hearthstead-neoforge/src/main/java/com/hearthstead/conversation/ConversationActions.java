package com.hearthstead.conversation;

import com.hearthstead.Hearthstead;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Named hooks that event content registers: actions run when a reply is
 * chosen, conditions hide replies. Ids are free-form ("brute_toll.paid").
 * Register once at mod construction or common setup; re-registering an id
 * replaces it.
 */
public final class ConversationActions {
    private static final Map<String, Consumer<ConversationContext>> ACTIONS = new ConcurrentHashMap<>();
    private static final Map<String, Predicate<ConversationContext>> CONDITIONS = new ConcurrentHashMap<>();

    private ConversationActions() {
    }

    public static void register(String id, Consumer<ConversationContext> action) {
        ACTIONS.put(id, action);
    }

    public static void condition(String id, Predicate<ConversationContext> condition) {
        CONDITIONS.put(id, condition);
    }

    public static boolean hasAction(String id) {
        return ACTIONS.containsKey(id);
    }

    static void run(String id, ConversationContext ctx) {
        Consumer<ConversationContext> action = ACTIONS.get(id);
        if (action == null) {
            Hearthstead.LOGGER.warn("Conversation {} reply {} names unknown action '{}'", ctx.graphId(), ctx.optionId(), id);
            return;
        }
        try {
            action.accept(ctx);
        } catch (RuntimeException failure) {
            Hearthstead.LOGGER.error("Conversation action '{}' failed", id, failure);
        }
    }

    /** Unknown conditions are false (the reply stays hidden) and logged once per lookup site. */
    static boolean test(String expression, ConversationContext ctx) {
        if (expression == null || expression.isBlank()) return true;
        boolean negate = expression.startsWith("!");
        String id = negate ? expression.substring(1) : expression;
        Predicate<ConversationContext> condition = CONDITIONS.get(id);
        if (condition == null) return negate;
        boolean value;
        try {
            value = condition.test(ctx);
        } catch (RuntimeException failure) {
            Hearthstead.LOGGER.error("Conversation condition '{}' failed", id, failure);
            value = false;
        }
        return negate != value;
    }
}
