package com.hearthstead.settlement.state;

import java.util.Optional;

/** Stable physical potency, not an issued count or a target's current rank. */
public enum BlessingQuality {
    COMMON(1), RARE(2);
    private final int rankUnits;
    BlessingQuality(int rankUnits) { this.rankUnits = rankUnits; }
    public int rankUnits() { return rankUnits; }
    public static Optional<BlessingQuality> fromRankUnits(int units) {
        return units == 1 ? Optional.of(COMMON) : units == 2 ? Optional.of(RARE) : Optional.empty();
    }
}
