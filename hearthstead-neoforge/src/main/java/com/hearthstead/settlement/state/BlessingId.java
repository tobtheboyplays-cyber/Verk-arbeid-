package com.hearthstead.settlement.state;

import java.util.Optional;

/** Stable ids for permanent, physical-target raid Blessings. */
public enum BlessingId {
    WARDEN_OATH(0, "warden_oath"),
    HEARTHWARD(1, "hearthward"),
    THORNED_ROADS(2, "thorned_roads");

    private final int wireId;
    private final String id;

    BlessingId(int wireId, String id) {
        this.wireId = wireId;
        this.id = id;
    }

    public int wireId() {
        return wireId;
    }

    public String id() {
        return id;
    }

    public static Optional<BlessingId> tryFromWireId(int wireId) {
        for (BlessingId blessing : values()) {
            if (blessing.wireId == wireId) {
                return Optional.of(blessing);
            }
        }
        return Optional.empty();
    }

    public static Optional<BlessingId> tryFromId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        for (BlessingId blessing : values()) {
            if (blessing.id.equals(id)) {
                return Optional.of(blessing);
            }
        }
        return Optional.empty();
    }
}
