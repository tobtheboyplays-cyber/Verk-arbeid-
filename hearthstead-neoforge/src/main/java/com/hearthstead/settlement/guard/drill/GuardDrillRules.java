package com.hearthstead.settlement.guard.drill;

import com.hearthstead.entity.GuardExperience;

import java.util.ArrayList;
import java.util.List;

/**
 * The morning drill's rules, pure so JUnit can pin them (Guard Drill, Watch &amp; Defense ring 1).
 *
 * <p><b>When.</b> 07:00-08:48 on the village clock (day ticks {@value #WINDOW_START} to
 * {@value #WINDOW_END}): the night watch has just handed over and had breakfast. Nobody new
 * joins after {@value #JOIN_UNTIL}; each guard drills at most {@value #SESSION_MAX_TICKS} ticks
 * (80 s, 1.6 in-game hours). Never in rain, never while anything threatens the settlement.
 *
 * <p><b>Who.</b> Guards who are off watch at that hour (the night watch, relieved at 07:00).
 * A day-watch guard is drafted only to give an off-watch guard a partner, or so a garrison
 * without a night watch still drills: at most two, and never the last guard on watch
 * ({@link #roster}).
 *
 * <p><b>What it pays.</b> Every blow a guard strikes or answers trains Strength like a patrol
 * waypoint does (their rank), capped per session; a finished session adds a small combat XP
 * award that stops at the Trained tier, so the yard alone never makes a veteran. The +15% on
 * real kills is the node's other half and unchanged.
 */
public final class GuardDrillRules {
    /** 07:00: the night watch hands over. */
    public static final long WINDOW_START = 1000L;
    /** 08:00: nobody new walks to the yard after this. */
    public static final long JOIN_UNTIL = 2000L;
    /** 08:48: the yard empties, whatever happened. */
    public static final long WINDOW_END = 2800L;
    /** One guard's session cap: 80 s real time, 1.6 in-game hours. */
    public static final int SESSION_MAX_TICKS = 1600;
    /** A session only pays its XP after this much time actually sparring in the yard. */
    public static final int MIN_SPAR_TICKS = 400;
    /** Walking to the yard gives up after this; the guard goes back to its day. */
    public static final int WALK_LIMIT_TICKS = 400;

    /** Breakfast first: below this a guard eats instead of joining. */
    public static final float MIN_HUNGER = 40.0F;
    /** Leaves the yard below this. */
    public static final float KEEP_HUNGER = 25.0F;
    /**
     * A night-watch guard has ~40-50 energy after the shift; only one below this (exhausted)
     * goes straight to bed. A sleeping guard above it is woken for the drill.
     */
    public static final float MIN_ENERGY = 15.0F;
    public static final float KEEP_ENERGY = 12.0F;
    /** Leaves the yard below this fraction of full health (the recovery goal owns it then)... */
    public static final float MIN_HEALTH_FRACTION = 0.7F;
    /** ...or below this many hit points, whichever is lower: a ranked guard's larger health bar
     *  (rank and attribute bonuses) fills slowly, and a fresh 24-of-40 guard is not wounded. */
    public static final float MIN_HEALTH_POINTS = 16.0F;

    /** Hit points a guard needs to drill. */
    public static float minHealth(float maxHealth) {
        return Math.min(maxHealth * MIN_HEALTH_FRACTION, MIN_HEALTH_POINTS);
    }

    /** Partners stand this many blocks apart (block centres): real spacing, blade tips just meet. */
    public static final int PARTNER_GAP = 2;
    /** Between pairs, outwards from the Barracks wall. */
    public static final int ROW_GAP = 4;
    /** First row's distance from the Barracks wall. */
    public static final int WALL_GAP = 3;

    /** Combat XP for a finished session. */
    public static final int SESSION_XP = 4;
    /** Drill XP never lifts a guard past this: the Trained tier. */
    public static final int XP_CEILING = GuardExperience.Tier.TRAINED.threshold();
    /** Strength reps per blow struck or answered (a patrol waypoint is one rep of TRAIN_DRILL). */
    public static final float REP_PER_BLOW = 0.5F;
    /** At most this many reps' worth of Strength per session. */
    public static final float REPS_PER_SESSION = 8.0F;
    /** Most day-watch guards the yard may draft at once. */
    public static final int MAX_DRAFTED = 2;

    private GuardDrillRules() {
    }

    /** Day ticks (0-23999) of a raw day time. */
    public static long timeOfDay(long dayTime) {
        return Math.floorMod(dayTime, 24000L);
    }

    /** Whether the yard is open at all. */
    public static boolean open(long dayTime) {
        long t = timeOfDay(dayTime);
        return t >= WINDOW_START && t < WINDOW_END;
    }

    /** Whether a guard may still walk to the yard. */
    public static boolean joinable(long dayTime) {
        long t = timeOfDay(dayTime);
        return t >= WINDOW_START && t < JOIN_UNTIL;
    }

    /** The day a session belongs to (one session per guard per day). */
    public static long day(long dayTime) {
        return Math.floorDiv(dayTime, 24000L);
    }

    /**
     * Who drills, from the eligible guards of one Barracks.
     *
     * @param offWatch  eligible guards who are off watch now, in a stable order
     * @param draftable eligible day-watch guards who are on watch now (no route, order or post)
     * @param onWatch   every guard of this Barracks standing its watch now and NOT already in the
     *                  yard (draftable or not)
     * @param inYard    how many are already in today's yard (sticky members)
     * @return the new members to admit, in order: every off-watch guard, then at most
     *         {@link #MAX_DRAFTED} day-watch guards, only to make an even number, and never
     *         the last one standing watch
     */
    public static <T> List<T> roster(List<T> offWatch, List<T> draftable, int onWatch, int inYard) {
        List<T> out = new ArrayList<>(offWatch);
        int total = inYard + out.size();
        int want = 0;
        if (total % 2 == 1) {
            want = 1;
        } else if (total == 0) {
            want = 2;
        }
        want = Math.min(want, Math.min(MAX_DRAFTED, draftable.size()));
        // Keep at least one guard on watch at all times.
        want = Math.min(want, Math.max(0, onWatch - 1));
        if (total == 0 && want < 2) {
            // Drafting a single guard to drill alone would take them off the walls for nothing.
            want = 0;
        }
        for (int i = 0; i < want; i++) {
            out.add(draftable.get(i));
        }
        return out;
    }

    /** Partner of a member index: 0-1, 2-3, ... (join order). */
    public static int partnerIndex(int member) {
        return member ^ 1;
    }

    /**
     * Where member {@code index} stands, as (lateral, outward) whole blocks from the yard origin
     * on the Barracks wall: partners side by side along the wall, facing each other across
     * {@link #PARTNER_GAP} blocks; each pair one row further out.
     */
    public static int[] slot(int index) {
        int row = index / 2;
        int lateral = (index & 1) == 0 ? -1 : PARTNER_GAP - 1;
        return new int[] {lateral, WALL_GAP + row * ROW_GAP};
    }

    /**
     * Which face of the Barracks the yard lies on: the one looking at the settlement's centre.
     * Returns {axis, sign}: axis 0 = X, 1 = Z; sign +1/-1. A Barracks at the centre uses +Z.
     */
    public static int[] face(double barracksX, double barracksZ, double centreX, double centreZ) {
        double dx = centreX - barracksX;
        double dz = centreZ - barracksZ;
        if (Math.abs(dx) < 1.0E-6 && Math.abs(dz) < 1.0E-6) {
            return new int[] {1, 1};
        }
        if (Math.abs(dx) > Math.abs(dz)) {
            return new int[] {0, dx > 0 ? 1 : -1};
        }
        return new int[] {1, dz > 0 ? 1 : -1};
    }

    /** Combat XP a finished session pays a guard at {@code current} XP. */
    public static int sessionXp(int current) {
        return Math.max(0, Math.min(SESSION_XP, XP_CEILING - current));
    }

    /** Strength units for {@code blows} more blows, given {@code paid} reps already paid. */
    public static float reps(float paid, int blows) {
        return Math.max(0.0F, Math.min(REPS_PER_SESSION - paid, blows * REP_PER_BLOW));
    }
}
