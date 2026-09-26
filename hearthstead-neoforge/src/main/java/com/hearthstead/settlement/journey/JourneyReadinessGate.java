package com.hearthstead.settlement.journey;

/** Pure Journey portion of the First Raid readiness assessment. */
public final class JourneyReadinessGate {
    /**
     * Readiness depends on authoritative closure through FJ-559B, never on the
     * overall Journey COMPLETE state (which is only possible after FJ-620).
     */
    public static boolean prerequisitesThroughFirstWatch(JourneyState state) {
        return state != null
            && state.mode() != JourneyPresentationMode.SKIPPED
            && state.mode() != JourneyPresentationMode.QUARANTINED
            && state.completedThrough(JourneyIds.FJ_559B_SET_TOWER_POST);
    }

    /**
     * Active guidance requires Journey closure. A deliberately skipped or
     * legacy-preserved presentation may still pass the ordinary live-domain
     * readiness assessment without inventing any Journey evidence.
     */
    public static boolean canRecordDeclaration(JourneyState state) {
        return state != null && (state.mode() == JourneyPresentationMode.SKIPPED
            || prerequisitesThroughFirstWatch(state)
                && !state.isCompleted(JourneyIds.FJ_560_DECLARE_RAID_READY));
    }

    private JourneyReadinessGate() {
    }
}
