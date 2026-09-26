package com.hearthstead.settlement.request;

import java.util.Optional;

/** Persisted request state. BLOCKED retains the state it interrupted. */
public enum RequestState {
    OPEN(0, false),
    RESERVED(1, false),
    PICKUP(2, false),
    IN_TRANSIT(3, false),
    DELIVERED(4, false),
    SATISFIED(5, true),
    BLOCKED(6, false),
    CANCELLED(7, true),
    EXPIRED(8, true);

    private final int wireId;
    private final boolean terminal;

    RequestState(int wireId, boolean terminal) {
        this.wireId = wireId;
        this.terminal = terminal;
    }

    public int wireId() {
        return wireId;
    }

    public boolean terminal() {
        return terminal;
    }

    public static Optional<RequestState> fromWireId(int wireId) {
        for (RequestState value : values()) {
            if (value.wireId == wireId) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }
}
