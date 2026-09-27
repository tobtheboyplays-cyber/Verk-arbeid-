package com.hearthstead.event.worldevent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Condition-aware line picking (pure, unit-tested). Every line is a lang key
 * under {@code conversation.hearthstead.story.<character>.}; which key is
 * picked depends only on what is TRUE of the village now and on what the
 * character remembers. A line that mentions guards is only picked when there
 * are guards, walls only with a defensive building, a raid only after one
 * was held (owner rule, and survival QA #4).
 *
 * <p>Numbers reach the lines as conversation variables. The talk UI passes
 * the speaker, the player and the settlement name as %1$s..%3$s and then the
 * variables in key order, so the keys are single letters:
 * <pre>
 *   a %4$s  population now        f %9$s  buildings last visit
 *   b %5$s  population last visit g %10$s guards
 *   c %6$s  days since last visit h %11$s raids held
 *   d %7$s  this visit's number   i %12$s gift or price
 *   e %8$s  buildings now         j %13$s days since the founding
 * </pre>
 */
public final class StoryLines {
    public static final String PREFIX = "conversation.hearthstead.story.";

    private StoryLines() {
    }

    public record Lines(List<String> keys, Map<String, Integer> vars) {
        /** Short stable signature of the chosen keys (part of the talk graph id). */
        public String signature() {
            int h = 17;
            for (String k : keys) h = h * 31 + k.hashCode();
            return Integer.toHexString(h);
        }
    }

    /** Extra facts for the pick that are not part of the village snapshot. */
    public record Extra(int gift, long daysSinceFounding, int lordRelation, int step, boolean turnedAwayFamily) {
        public static final Extra NONE = new Extra(0, 0, 0, 1, false);

        public Extra(int gift, long daysSinceFounding, int lordRelation, int step) {
            this(gift, daysSinceFounding, lordRelation, step, false);
        }
    }

    public static Map<String, Integer> vars(StoryFacts now, @Nullable VisitorMemory.Person prev, Extra extra) {
        StoryFacts seen = prev == null ? null : prev.lastSeen;
        Map<String, Integer> v = new LinkedHashMap<>();
        v.put("a", now.population());
        v.put("b", seen == null ? now.population() : seen.population());
        v.put("c", seen == null ? 0 : (int) Math.max(0, Math.min(9999, now.day() - seen.day())));
        v.put("d", (prev == null ? 0 : prev.visits) + 1);
        v.put("e", now.buildings());
        v.put("f", seen == null ? now.buildings() : seen.buildings());
        v.put("g", now.guards());
        v.put("h", now.raidsHeld());
        v.put("i", Math.max(0, extra.gift()));
        v.put("j", (int) Math.max(0, Math.min(9999, extra.daysSinceFounding())));
        return v;
    }

    /** The lines {@code c} says on arrival, given the village now and what they remember. */
    public static Lines pick(StoryCharacter c, StoryFacts now, @Nullable VisitorMemory.Person prev, Extra extra) {
        String p = PREFIX + c.id() + ".";
        List<String> keys = new ArrayList<>();
        boolean returning = prev != null && prev.visits > 0;
        StoryFacts seen = prev == null ? null : prev.lastSeen;
        if (c.threat()) {
            threatLines(c, p, now, prev, extra, keys);
            return new Lines(List.copyOf(keys), vars(now, prev, extra));
        }
        // 1. Greeting: first meeting, a friendly return, or a cool return after an upset.
        if (!returning) keys.add(p + "greet");
        else if (prev.lastMood == VisitorMemory.DISPLEASED) keys.add(p + "greet_upset");
        else keys.add(p + "greet_again");
        // 2. Memory: how the village changed since they last saw it.
        if (returning && seen != null) {
            if (now.rank() > seen.rank()) keys.add(p + "memory_rank_" + now.rankId());
            else if (now.population() > seen.population()) keys.add(p + "memory_grew");
            else if (now.buildings() > seen.buildings()) keys.add(p + "memory_built");
            else if (now.population() < seen.population()) keys.add(p + "memory_fewer");
            else keys.add(p + "memory_same");
        }
        // 3. One true fact about the village, in the character's own voice.
        String fact = fact(c, now, prev, extra);
        if (fact != null) keys.add(p + fact);
        // 4. Their good wishes.
        keys.add(p + "wish");
        return new Lines(List.copyOf(keys), vars(now, prev, extra));
    }

    @Nullable
    static String fact(StoryCharacter c, StoryFacts now, @Nullable VisitorMemory.Person prev, Extra extra) {
        return switch (c) {
            case HOLLINS -> now.houses() >= 3 ? "fact_roofs" : "fact_first_roof";
            case PELL_ROOK -> extra.lordRelation() >= 10 ? "fact_warm"
                : extra.lordRelation() <= -20 ? "fact_cold" : "fact_polite";
            case ODO -> prev != null && prev.visits > 0 ? "fact_regular" : "fact_first";
            case WENNA -> now.raidsHeld() >= 2 ? "fact_many_songs" : "fact_first_song";
            // The refugees the village once turned away are what the road talks about first.
            case BRISKS -> extra.turnedAwayFamily() ? "fact_heard_turned_away" : now.raidsHeld() >= 1 ? "fact_heard_raid"
                : now.food() >= 20 ? "fact_heard_bread" : "fact_heard_rank";
            case ANSELM -> now.guards() >= 2 ? "fact_guarded" : "fact_unguarded";
            case GERD -> "fact_wounds";
            case BRANNOC -> now.guards() >= 2 ? (now.raidsHeld() >= 1 ? "fact_blooded" : "fact_guards")
                : now.guards() == 1 ? "fact_one_guard" : "fact_no_guards";
            case HILDE -> now.raidsHeld() >= 1 ? "fact_history_raids" : "fact_history_quiet";
            case THANKS -> null;
            default -> null;
        };
    }

    private static void threatLines(StoryCharacter c, String p, StoryFacts now, @Nullable VisitorMemory.Person prev,
                                    Extra extra, List<String> keys) {
        boolean returning = prev != null && prev.visits > 0;
        if (extra.step() >= 2) {
            keys.add(p + "last_warning");
            String choice = prev == null ? "" : prev.lastChoice;
            keys.add(p + switch (choice) {
                case "talked_down", "talk" -> "memory_talked";
                case "paid" -> "memory_paid";
                default -> "memory_defied";
            });
        } else {
            keys.add(p + (returning ? "warning_again" : "warning"));
            if (returning && "paid".equals(prev.lastChoice)) keys.add(p + "memory_paid");
        }
        // Only a threat the village can see through: guards are named only when there are guards.
        keys.add(p + (now.guards() >= 2 ? "fact_guards" : now.defenses() >= 1 ? "fact_towers"
            : now.guards() == 1 ? "fact_one_guard" : "fact_open"));
        keys.add(p + (extra.step() >= 2 ? "ultimatum_final" : "ultimatum"));
    }

    /** Talk-graph line keys that exist for {@code c} (every key {@link #pick} can return). */
    public static List<String> allKeys(StoryCharacter c) {
        String p = PREFIX + c.id() + ".";
        List<String> out = new ArrayList<>();
        if (c.threat()) {
            for (String k : List.of("last_warning", "memory_talked", "memory_paid", "memory_defied", "warning",
                    "warning_again", "fact_guards", "fact_towers", "fact_one_guard", "fact_open", "ultimatum", "ultimatum_final")) {
                out.add(p + k);
            }
            return out;
        }
        if (c == StoryCharacter.THANKS) return out;
        for (String k : List.of("greet", "greet_again", "greet_upset", "memory_grew", "memory_built",
                "memory_fewer", "memory_same", "wish")) {
            out.add(p + k);
        }
        for (String rank : StoryFacts.RANKS) if (!"hamlet".equals(rank)) out.add(p + "memory_rank_" + rank);
        List<String> facts = switch (c) {
            case HOLLINS -> List.of("fact_roofs", "fact_first_roof");
            case PELL_ROOK -> List.of("fact_warm", "fact_cold", "fact_polite");
            case ODO -> List.of("fact_regular", "fact_first");
            case WENNA -> List.of("fact_many_songs", "fact_first_song");
            case BRISKS -> List.of("fact_heard_raid", "fact_heard_bread", "fact_heard_rank", "fact_heard_turned_away");
            case ANSELM -> List.of("fact_guarded", "fact_unguarded");
            case GERD -> List.of("fact_wounds");
            case BRANNOC -> List.of("fact_blooded", "fact_guards", "fact_one_guard", "fact_no_guards");
            case HILDE -> List.of("fact_history_raids", "fact_history_quiet");
            default -> List.of();
        };
        for (String f : facts) out.add(p + f);
        return out;
    }
}
