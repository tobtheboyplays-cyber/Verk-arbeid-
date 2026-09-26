package com.hearthstead.settlement.journey;

import java.util.Locale;
import java.util.Optional;

/** Stable presentation modes for Journey definition v2. */
public enum JourneyPresentationMode {
    ACTIVE(0),
    SKIPPED(1),
    COMPLETE(2),
    QUARANTINED(-1);

    private final int wireId;

    JourneyPresentationMode(int wireId) {
        this.wireId = wireId;
    }

    public int wireId() {
        return wireId;
    }

    public static Optional<JourneyPresentationMode> tryFromWireId(int wireId) {
        for (JourneyPresentationMode mode : values()) {
            if (mode.wireId == wireId) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<JourneyPresentationMode> tryFromId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        for (JourneyPresentationMode mode : values()) {
            if (mode.id().equals(id)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }
}
