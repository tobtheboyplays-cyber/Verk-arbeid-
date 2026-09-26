package com.hearthstead.network;

import com.hearthstead.entity.SettlerActivity;

/**
 * What a settler marker on the realm map says about its settler, in one word.
 *
 * <p>Classified on the server from the synced activity, the live navigation
 * state and a cheap no-progress timer, so the map never guesses. The wire id
 * is the ordinal of this append-only list; unknown ids decode to IDLE.
 */
public enum RealmMapStatus {
    WORKING("working"),
    WALKING("walking"),
    IDLE("idle"),
    SLEEPING("sleeping"),
    FLEEING("fleeing"),
    STUCK("stuck"),
    FIGHTING("fighting");

    private static final RealmMapStatus[] BY_ID = values();
    private final String key;

    RealmMapStatus(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public int wireId() {
        return ordinal();
    }

    public static RealmMapStatus byWireId(int id) {
        return id >= 0 && id < BY_ID.length ? BY_ID[id] : IDLE;
    }

    /** True for states the owner should look at (paired with an alert glyph). */
    public boolean needsAttention() {
        return this == STUCK || this == FLEEING;
    }

    /**
     * Pure classification. {@code navigating} is "the navigator holds a live
     * path"; {@code stalled} is "navigating, yet no real progress for the
     * stall window" (or vanilla's own stuck detection fired).
     */
    public static RealmMapStatus classify(SettlerActivity activity, boolean navigating, boolean stalled) {
        SettlerActivity a = activity == null ? SettlerActivity.IDLE : activity;
        switch (a) {
            case FLEEING, RETREATING -> {
                return FLEEING;
            }
            case COMBAT -> {
                return FIGHTING;
            }
            case OUT_OF_AMMO -> {
                return STUCK;
            }
            default -> {
            }
        }
        if (stalled && navigating) {
            return STUCK;
        }
        return switch (a) {
            case SLEEPING, RESTING -> SLEEPING;
            case TRAVELING, CARRYING, HAULING_LOG -> WALKING;
            case IDLE, EATING, CELEBRATING, SOCIALIZING -> navigating ? WALKING : IDLE;
            default -> WORKING;
        };
    }
}
