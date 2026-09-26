package com.hearthstead.conversation;

/**
 * Pure rules for NPCs walking away (JUnit-covered). Owner rule: nobody ever
 * vanishes in view. A leaver may be removed only once it has walked off
 * (or given up after the stuck budget) AND no player can see it: each
 * player is either more than 48 blocks away, or more than 24 blocks away
 * with no line of sight.
 */
public final class DepartureRules {
    public static final double FAR = 48.0D;
    public static final double HIDDEN_NEAR = 24.0D;
    public static final double MIN_WALK = 20.0D;
    /** 120 s: after this a stuck leaver may go as soon as it is unseen. */
    public static final long GIVE_UP_TICKS = 20L * 120L;
    /** 20 s without progress: pick another way out. */
    public static final long STUCK_TICKS = 20L * 20L;
    public static final double RETREAT_MIN = 48.0D;
    public static final double RETREAT_MAX = 64.0D;

    private DepartureRules() {
    }

    /** One player's view of a leaver: distance and whether it has line of sight. */
    public record Viewer(double distance, boolean lineOfSight) {
        public boolean sees() {
            return distance <= HIDDEN_NEAR || (distance <= FAR && lineOfSight);
        }
    }

    public static boolean unseen(Iterable<Viewer> viewers) {
        for (Viewer viewer : viewers) {
            if (viewer.sees()) return false;
        }
        return true;
    }

    /** Whether the leaver has done its part: walked far enough, or run out of time. */
    public static boolean walkedOff(double walked, long ageTicks) {
        return walked >= MIN_WALK || ageTicks >= GIVE_UP_TICKS;
    }

    public static boolean mayDespawn(double walked, long ageTicks, Iterable<Viewer> viewers) {
        return walkedOff(walked, ageTicks) && unseen(viewers);
    }

    /** Stuck when it has not moved 2 blocks from its last progress mark for 20 s. */
    public static boolean stuck(double movedSinceMark, long ticksSinceMark) {
        return movedSinceMark < 2.0D && ticksSinceMark >= STUCK_TICKS;
    }
}
