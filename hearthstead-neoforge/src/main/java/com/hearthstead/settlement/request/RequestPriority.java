package com.hearthstead.settlement.request;

import java.util.Optional;

/** Stable player/server priority, ordered from ordinary to urgent. */
public enum RequestPriority {
    NORMAL(0),
    HIGH(1),
    URGENT(2);

    private final int wireId;

    RequestPriority(int wireId) {
        this.wireId = wireId;
    }

    public int wireId() {
        return wireId;
    }

    public static Optional<RequestPriority> fromWireId(int wireId) {
        for (RequestPriority value : values()) {
            if (value.wireId == wireId) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }
}
