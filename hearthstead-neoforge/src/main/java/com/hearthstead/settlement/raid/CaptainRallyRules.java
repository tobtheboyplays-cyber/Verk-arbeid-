package com.hearthstead.settlement.raid;

/** Allocation-free timing rules used directly by Captain Rally runtime. */
public final class CaptainRallyRules {
    public static boolean shouldRefresh(long now, long deadline, int tickCount) {
        return now < deadline && Math.floorMod(tickCount, 20) == 0;
    }

    public static int refreshDuration(long now, long deadline) {
        return (int) Math.min(40L, Math.max(0L, deadline - now));
    }

    public static boolean mayStartFinalStand(boolean alreadyUsed,
                                             boolean followersTerminal) {
        return !alreadyUsed && followersTerminal;
    }

    private CaptainRallyRules() {}
}
