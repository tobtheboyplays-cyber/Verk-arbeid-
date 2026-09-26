package com.hearthstead.entity;

/** Presentation of a server-confirmed restaurant step, never transaction authority. */
public enum TavernCue {
    NONE, LOOKING, ORDERING, QUOTED, PAYING, WAITING, COOKING, SERVING,
    EATING, DRINKING, DONE, FULL, NO_STOCK, NO_COINS, BLOCKED, CANCELLED, REFUND_PENDING;

    public String translationKey() { return "hearthstead.tavern.cue." + name().toLowerCase(java.util.Locale.ROOT); }
    public static TavernCue decode(String name) {
        try { return valueOf(name); } catch (IllegalArgumentException ex) { return NONE; }
    }
}
