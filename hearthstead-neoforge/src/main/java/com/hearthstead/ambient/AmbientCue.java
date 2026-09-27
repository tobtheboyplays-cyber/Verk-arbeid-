package com.hearthstead.ambient;

/**
 * A short, display-only body cue a settler plays for a moment of village
 * life (living-village lane). The server only decides WHEN; the client
 * plays the authored overlay clip {@code settler/<id>} when the animation
 * lane has shipped it, and a small procedural fallback until then.
 *
 * <p>Wire format: the ordinal travels in {@code AmbientCuePayload}, so the
 * order is append-only.
 */
public enum AmbientCue {
    NONE("none", 0),
    /** A raised hand to a player or a newcomer. */
    WAVE("wave", 40),
    /** Two small nods: a greeting that does not stop the hands. */
    NOD("nod", 24),
    /** Morning stretch and yawn (the bed wake already plays WAKE_STRETCH). */
    STRETCH_YAWN("stretch_yawn", 52),
    /** Arms up after a won raid. */
    CHEER("cheer", 50),
    /** Bowed head and folded hands after a settler dies. */
    MOURN("mourn", 100),
    /** Shoulders up, head down, walking through rain. */
    RAIN_HUNCH("rain_hunch", 60),
    /** Arms hugged in, a quick shiver, in snowy biomes. */
    SHIVER("shiver", 50);

    private static final AmbientCue[] BY_ID = values();

    private final String id;
    private final int durationTicks;

    AmbientCue(String id, int durationTicks) {
        this.id = id;
        this.durationTicks = durationTicks;
    }

    public String id() {
        return id;
    }

    /** Library key of the authored overlay clip, e.g. "settler/wave". */
    public String clipKey() {
        return "settler/" + id;
    }

    public int durationTicks() {
        return durationTicks;
    }

    public static AmbientCue byId(int ordinal) {
        return ordinal >= 0 && ordinal < BY_ID.length ? BY_ID[ordinal] : NONE;
    }
}
