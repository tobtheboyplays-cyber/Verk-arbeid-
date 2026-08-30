package com.hearthstead.settlement.state;

import java.util.Optional;

/** Stable lifecycle states for the settlement's first authored raid. */
public enum FirstRaidState {
    UNINITIALIZED(0, "uninitialized"),
    SCHEDULED(1, "scheduled"),
    ACTIVE(2, "active"),
    COMPLETED(3, "completed"),
    /**
     * A fresh settlement whose one founding roll is persisted, but whose
     * player-confirmed readiness has not armed the warning calendar yet.
     *
     * <p>Wire id 4 is intentionally appended. Existing ids are save data and
     * must never be renumbered when enum source order changes.
     */
    PREPARING(4, "preparing");

    private final int wireId;
    private final String id;

    FirstRaidState(int wireId, String id) {
        this.wireId = wireId;
        this.id = id;
    }

    public int wireId() {
        return wireId;
    }

    public String id() {
        return id;
    }

    public static Optional<FirstRaidState> tryFromWireId(int wireId) {
        for (FirstRaidState state : values()) {
            if (state.wireId == wireId) {
                return Optional.of(state);
            }
        }
        return Optional.empty();
    }

    public static Optional<FirstRaidState> tryFromId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        for (FirstRaidState state : values()) {
            if (state.id.equals(id)) {
                return Optional.of(state);
            }
        }
        return Optional.empty();
    }
}
