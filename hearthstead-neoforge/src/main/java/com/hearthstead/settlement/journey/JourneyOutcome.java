package com.hearthstead.settlement.journey;

import java.util.Locale;
import java.util.Optional;

/** Persisted terminal truth for the first authored raid. */
public enum JourneyOutcome {
    NONE(0),
    HELD(1),
    HIT(2),
    SETTLEMENT_LOST(3);

    private final int wireId;

    JourneyOutcome(int wireId) {
        this.wireId = wireId;
    }

    public int wireId() {
        return wireId;
    }

    public static Optional<JourneyOutcome> tryFromWireId(int wireId) {
        for (JourneyOutcome outcome : values()) {
            if (outcome.wireId == wireId) {
                return Optional.of(outcome);
            }
        }
        return Optional.empty();
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean terminal() {
        return this != NONE;
    }

    public static Optional<JourneyOutcome> tryFromId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        for (JourneyOutcome outcome : values()) {
            if (outcome.id().equals(id)) {
                return Optional.of(outcome);
            }
        }
        return Optional.empty();
    }
}
