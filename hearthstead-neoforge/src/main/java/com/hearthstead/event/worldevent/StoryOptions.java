package com.hearthstead.event.worldevent;

import java.util.List;
import javax.annotation.Nullable;

/**
 * The answers each story character accepts (pure data, shared by the talk
 * UI graph and the chat fallback) and how each answer makes them feel. A
 * DISPLEASED answer posts "<Name> will remember this." and is written to
 * {@link VisitorMemory}; later visits and threats read it back.
 */
public final class StoryOptions {
    private StoryOptions() {
    }

    public enum Style { PRIMARY, SECONDARY, DANGER }

    /**
     * One answer. {@code coinsVar} names a conversation variable holding the
     * price (the tribute, "i"); a persuasion answer resolves to
     * {@code success} or {@code failure}.
     */
    public record Spec(String id, @Nullable String condition, int food, int coins, @Nullable String coinsVar,
                       int persuade, String skill, String success, String failure, int relation, Style style) {
        static Spec plain(String id, int relation, Style style) {
            return new Spec(id, null, 0, 0, null, 0, "", id, id, relation, style);
        }

        static Spec food(String id, int food, int relation) {
            return new Spec(id, null, food, 0, null, 0, "", id, id, relation, Style.SECONDARY);
        }

        static Spec coins(String id, int coins, int relation) {
            return new Spec(id, null, 0, coins, null, 0, "", id, id, relation, Style.SECONDARY);
        }

        static Spec tribute(String id, int relation) {
            return new Spec(id, null, 0, 0, "i", 0, "", id, id, relation, Style.SECONDARY);
        }

        static Spec talk(String id, String condition, int chance, String skill) {
            return new Spec(id, condition, 0, 0, null, chance, skill, "talked_down", "talk_failed", 5, Style.SECONDARY);
        }
    }

    public static final int HOLLINS_GIVE_BACK_FOOD = 4;
    public static final int PELL_REPLY_COINS = 2;
    public static final int ODO_ROUND_COINS = 2;
    public static final int TIP_COINS = 3;
    public static final int BRISKS_FEED_FOOD = 6;
    public static final int ANSELM_DONATE_COINS = 2;
    public static final int GERD_POTION_COINS = 3;
    public static final int BRANNOC_DRINK_FOOD = 2;
    public static final int HILDE_TIP_COINS = 2;
    public static final int HAMON_AMENDS_FOOD = 12;
    /** Persuasion odds of the "talk them down" answer: a threat only with real defenders, else an honest appeal. */
    public static final int TALK_THREAT_CHANCE = 35;
    public static final int TALK_HONEST_CHANCE = 30;

    public static List<Spec> of(StoryCharacter c, int step) {
        return switch (c) {
            case HOLLINS -> List.of(Spec.plain("thank", 10, Style.PRIMARY),
                Spec.food("give_back", HOLLINS_GIVE_BACK_FOOD, 15), Spec.plain("refuse", -15, Style.DANGER));
            case PELL_ROOK -> List.of(Spec.plain("receive", 5, Style.PRIMARY),
                Spec.coins("reply_warm", PELL_REPLY_COINS, 10), Spec.plain("tear", -15, Style.DANGER));
            case ODO -> List.of(Spec.plain("thank", 10, Style.PRIMARY),
                Spec.coins("buy_round", ODO_ROUND_COINS, 15), Spec.plain("brush_off", -10, Style.DANGER));
            case WENNA -> List.of(Spec.plain("listen", 5, Style.PRIMARY),
                Spec.coins("tip", TIP_COINS, 15), Spec.plain("hush", -15, Style.DANGER));
            case BRISKS -> List.of(Spec.plain("take_in", 15, Style.PRIMARY),
                Spec.food("feed", BRISKS_FEED_FOOD, 8), Spec.plain("decline", -15, Style.DANGER));
            case ANSELM -> List.of(Spec.plain("blessing", 5, Style.PRIMARY),
                Spec.coins("donate", ANSELM_DONATE_COINS, 15), Spec.plain("send_away", -15, Style.DANGER));
            case GERD -> List.of(Spec.plain("heal", 10, Style.PRIMARY),
                Spec.coins("buy_potions", GERD_POTION_COINS, 10), Spec.plain("send_away", -15, Style.DANGER));
            case BRANNOC -> List.of(Spec.plain("inspect", 10, Style.PRIMARY),
                Spec.food("drink", BRANNOC_DRINK_FOOD, 15), Spec.plain("dismiss", -10, Style.DANGER));
            case HILDE -> List.of(Spec.plain("listen", 5, Style.PRIMARY),
                Spec.coins("tip", HILDE_TIP_COINS, 15), Spec.plain("hush", -15, Style.DANGER));
            case SIGRUN -> step >= 2
                ? List.of(Spec.tribute("pay", 0), Spec.plain("defy", -20, Style.DANGER),
                    Spec.plain("attack", -30, Style.DANGER))
                : List.of(Spec.tribute("pay", 0),
                    Spec.talk("talk_walls", "town.defended", TALK_THREAT_CHANCE, "intimidation"),
                    Spec.talk("talk_honest", "!town.defended", TALK_HONEST_CHANCE, "charisma"),
                    Spec.plain("defy", -20, Style.DANGER));
            case HAMON -> step >= 2
                ? List.of(Spec.tribute("pay_tax", 10), Spec.plain("defy", -20, Style.DANGER),
                    Spec.plain("attack", -30, Style.DANGER))
                : List.of(Spec.tribute("pay_tax", 15), Spec.food("make_amends", HAMON_AMENDS_FOOD, 10),
                    Spec.plain("defy", -20, Style.DANGER));
            case THANKS -> List.of();
        };
    }

    /** How the character feels about an outcome (drives the chat cue and the memory mood). */
    public static int mood(StoryCharacter c, String outcome) {
        return switch (outcome) {
            case "refuse", "tear", "brush_off", "hush", "decline", "send_away", "dismiss", "defy", "attack",
                 "talk_failed", "ignored", "herald_killed" -> VisitorMemory.DISPLEASED;
            // Positive cues stay rarer than negative ones (owner): only a real extra kindness.
            case "give_back", "reply_warm", "buy_round", "tip", "feed", "donate", "drink", "take_in" ->
                VisitorMemory.PLEASED;
            default -> VisitorMemory.NEUTRAL;
        };
    }
}
