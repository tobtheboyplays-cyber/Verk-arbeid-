package com.hearthstead.settlement.journey;

import java.util.Locale;
import java.util.Optional;

/** Provenance of one server-authored Journey fact. */
public enum JourneySource {
    SURVIVAL,
    MIGRATION,
    TEST,
    ADMIN;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean normalProgressSource() {
        return this == SURVIVAL;
    }

    public static Optional<JourneySource> tryFromId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        for (JourneySource source : values()) {
            if (source.id().equals(id)) {
                return Optional.of(source);
            }
        }
        return Optional.empty();
    }
}
