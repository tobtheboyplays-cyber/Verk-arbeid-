package com.hearthstead.entity;

/**
 * Short, server-authored social presentation. These are not needs, jobs, or
 * inventory state: the activity that owns a moment may disappear at any time
 * when ordinary village work becomes more important.
 */
public final class VillageSocial {
    public static final int NONE = 0;
    public static final int WELCOME = 1;
    /** The speaker's small hand-led contribution to a two-settler exchange. */
    public static final int CHAT = 2;
    /** The companion's listening pose: eye contact and a short nod, not a mirrored speech loop. */
    public static final int LISTEN = 3;
    /**
     * A shared talking pair (SocialPair): one synced clock, the two settlers
     * take turns (speaker CHAT / partner LISTEN, swapping every turn). Its
     * length lives in the cue itself ("Turns" x "TurnTicks").
     */
    public static final int PAIR = 4;

    public static final int WELCOME_TICKS = 32;
    public static final int CHAT_TICKS = 96;

    private VillageSocial() {
    }

    public static int duration(int mode) {
        return switch (mode) {
            case WELCOME -> WELCOME_TICKS;
            case CHAT, LISTEN -> CHAT_TICKS;
            default -> 0;
        };
    }
}
