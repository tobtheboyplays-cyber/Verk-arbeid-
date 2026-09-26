package com.hearthstead.settlement.journey;

import java.util.Objects;

/**
 * Small server-side facade. There is intentionally no public generic packet
 * or client-assertion entry point; specific domain hooks author evidence only
 * after their authoritative commit.
 */
public final class JourneyProgressService {
    private final JourneyState state;

    public JourneyProgressService(JourneyState state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    public JourneySnapshot snapshot() {
        return JourneySnapshot.from(state);
    }

    public JourneyState state() {
        return state;
    }

    JourneyApplyResult recordCommitted(JourneyEvidence evidence) {
        return state.record(evidence, JourneyDefinition.CURRENT);
    }
}
