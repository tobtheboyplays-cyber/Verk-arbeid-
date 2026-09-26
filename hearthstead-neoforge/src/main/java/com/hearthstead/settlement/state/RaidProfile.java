package com.hearthstead.settlement.state;

import java.util.Optional;

/**
 * Server-authoritative raid rules selected for one shared settlement.
 *
 * <p>Both identifiers are explicit persistence/network contracts. Never
 * replace them with enum ordinals or derive them from declaration order: saves
 * must remain readable when the declaration is rearranged or extended.
 */
public enum RaidProfile {
    PEACEFUL(0, "peaceful"),
    BALANCED(1, "balanced"),
    IRON_WINTER(2, "iron_winter");

    private final int wireId;
    private final String id;

    RaidProfile(int wireId, String id) {
        this.wireId = wireId;
        this.id = id;
    }

    public int wireId() {
        return wireId;
    }

    public String id() {
        return id;
    }

    public static Optional<RaidProfile> tryFromWireId(int wireId) {
        for (RaidProfile profile : values()) {
            if (profile.wireId == wireId) {
                return Optional.of(profile);
            }
        }
        return Optional.empty();
    }

    public static Optional<RaidProfile> tryFromId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        for (RaidProfile profile : values()) {
            if (profile.id.equals(id)) {
                return Optional.of(profile);
            }
        }
        return Optional.empty();
    }
}
