package com.hearthstead.event.worldevent;

import java.util.List;
import javax.annotation.Nullable;

/**
 * Who is due to visit, from pure facts (unit-tested). Every character has
 * its own cooldown and visit cap; progress visitors come for a real
 * milestone; threats only once the young-village grace is over and never
 * two ladders at once.
 */
public final class StoryRules {
    private StoryRules() {
    }

    /** World facts beyond the village snapshot that a trigger needs. */
    public record Context(long daysSinceFounding, boolean merchantSeen, long lastHeldNight, int lastRaidHurt,
                          long lastRaidNight, boolean hostileReady, int lordRelation, int raidNumber) {
        public static final Context NONE = new Context(0, false, -1, 0, -1, false, 0, 1);
    }

    /** Friendly visitors, most timely first (a song about last night's raid beats a social call). */
    static final List<StoryCharacter> VISIT_ORDER = List.of(StoryCharacter.WENNA, StoryCharacter.GERD,
        StoryCharacter.PELL_ROOK, StoryCharacter.THANKS, StoryCharacter.BRISKS, StoryCharacter.HOLLINS,
        StoryCharacter.BRANNOC, StoryCharacter.ODO, StoryCharacter.ANSELM, StoryCharacter.HILDE);

    public static final String LADDER_VARG = "varg";
    public static final String LADDER_HAMON = "hamon";

    /** Milestones a settler thanks the players for, in order; each once. */
    public static final List<String> MILESTONES = List.of("first_raid_held", "day10", "pop10", "town");

    public static boolean offCooldown(StoryCharacter c, VisitorMemory.Book book, long day) {
        long since = book.daysSince(c.id(), day);
        if (since >= 0 && since < c.cooldownDays()) return false;
        return c.maxVisits() <= 0 || book.visits(c.id()) < c.maxVisits();
    }

    /** The first milestone reached and not thanked yet, or null. */
    @Nullable
    public static String dueMilestone(StoryFacts f, Context ctx, VisitorMemory.Book book) {
        for (String m : MILESTONES) {
            boolean reached = switch (m) {
                case "first_raid_held" -> f.raidsHeld() >= 1;
                case "day10" -> ctx.daysSinceFounding() >= 10;
                case "pop10" -> f.population() >= 10;
                case "town" -> f.rank() >= 2;
                default -> false;
            };
            if (reached && !book.flag("thanks:" + m)) return m;
        }
        return null;
    }

    /** Whether friendly character {@code c} has a reason to come today. */
    public static boolean due(StoryCharacter c, StoryFacts f, Context ctx, VisitorMemory.Book book) {
        if (c.threat() || !offCooldown(c, book, f.day())) return false;
        VisitorMemory.Person prev = book.person(c.id());
        StoryFacts seen = prev == null ? null : prev.lastSeen;
        boolean first = prev == null || prev.visits == 0;
        return switch (c) {
            case HOLLINS -> f.population() >= 5 && f.houses() >= 1 && ctx.daysSinceFounding() >= 2
                && (first || grew(f, seen));
            case PELL_ROOK -> f.rank() >= 1 && !book.flag("letter:" + f.rank());
            case ODO -> ctx.merchantSeen() && ctx.daysSinceFounding() >= 5 && f.population() >= 3;
            case THANKS -> f.population() >= 2 && dueMilestone(f, ctx, book) != null;
            case WENNA -> ctx.lastHeldNight() >= 0 && !book.flag("sung:" + ctx.lastHeldNight());
            case BRISKS -> (f.raidsHeld() >= 1 || f.rank() >= 1) && f.freeBeds() >= 3
                // A second visit only after the family was turned away once.
                && (first || book.lastMood(c.id()) == VisitorMemory.DISPLEASED);
            case ANSELM -> ctx.daysSinceFounding() >= 3 && f.population() >= 4 && (first || grew(f, seen));
            case GERD -> ctx.lastRaidHurt() > 0 && ctx.lastRaidNight() >= 0
                && !book.flag("healed:" + ctx.lastRaidNight());
            case BRANNOC -> f.guards() >= 1 && (first || seen == null || f.guards() > seen.guards()
                || f.raidsHeld() > seen.raidsHeld());
            case HILDE -> f.tavern() && ctx.daysSinceFounding() >= 6 && f.population() >= 4
                && (first || seen == null || f.raidsHeld() > seen.raidsHeld() || f.rank() > seen.rank()
                    || f.population() >= seen.population() + 3);
            default -> false;
        };
    }

    /** Grew enough since the last look to be worth remarking on. */
    static boolean grew(StoryFacts now, @Nullable StoryFacts then) {
        return then == null || now.population() > then.population() || now.buildings() > then.buildings()
            || now.rank() > then.rank();
    }

    /** The friendly visitor due today, or null. */
    @Nullable
    public static StoryCharacter nextVisitor(StoryFacts f, Context ctx, VisitorMemory.Book book) {
        for (StoryCharacter c : VISIT_ORDER) if (due(c, f, ctx, book)) return c;
        return null;
    }

    /** Every friendly visitor due today, most timely first. */
    public static List<StoryCharacter> dueVisitors(StoryFacts f, Context ctx, VisitorMemory.Book book) {
        List<StoryCharacter> out = new java.util.ArrayList<>();
        for (StoryCharacter c : VISIT_ORDER) if (due(c, f, ctx, book)) out.add(c);
        return out;
    }

    /** A ladder is open once its first warning was defied (or its captain is sworn). */
    public static boolean open(@Nullable VisitorMemory.Ladder ladder) {
        return ladder != null && !ladder.over()
            && (ladder.step >= 2 || VisitorMemory.Ladder.SWORN.equals(ladder.status));
    }

    /** Whether ladder {@code id} may play its next step today. */
    public static boolean ladderDue(String id, StoryFacts f, Context ctx, VisitorMemory.Book book) {
        if (!ctx.hostileReady()) return false;
        VisitorMemory.Ladder ladder = book.existingLadder(id);
        if (ladder != null && (ladder.over() || VisitorMemory.Ladder.SWORN.equals(ladder.status))) return false;
        if (ladder != null && ladder.notBeforeDay != WorldEventSchedule.NO_DAY && f.day() < ladder.notBeforeDay) {
            return false;
        }
        String other = LADDER_VARG.equals(id) ? LADDER_HAMON : LADDER_VARG;
        boolean mineOpen = open(ladder);
        if (!mineOpen && open(book.existingLadder(other))) return false; // one ladder at a time
        if (mineOpen) return true;
        return switch (id) {
            case LADDER_VARG -> ctx.raidNumber() >= 2 && f.population() >= 5 && ctx.daysSinceFounding() >= 5;
            case LADDER_HAMON -> ctx.lordRelation() <= -20 && f.rank() >= 1;
            default -> false;
        };
    }

    /** The threat herald due today, or null. An open ladder goes first. */
    @Nullable
    public static StoryCharacter nextThreat(StoryFacts f, Context ctx, VisitorMemory.Book book) {
        boolean vargOpen = open(book.existingLadder(LADDER_VARG));
        boolean hamonOpen = open(book.existingLadder(LADDER_HAMON));
        if (hamonOpen && !vargOpen && ladderDue(LADDER_HAMON, f, ctx, book)) return StoryCharacter.HAMON;
        if (ladderDue(LADDER_VARG, f, ctx, book)) return StoryCharacter.SIGRUN;
        if (ladderDue(LADDER_HAMON, f, ctx, book)) return StoryCharacter.HAMON;
        return null;
    }

    public static String ladderOf(StoryCharacter herald) {
        return herald == StoryCharacter.HAMON ? LADDER_HAMON : LADDER_VARG;
    }

    // ------------------------------------------------------ tribute sizes --

    /** First-warning tribute: 6 Coins plus 1 per 2 settlers, capped at 16 and at half the stored Coins (min 3). */
    public static int tribute(int population, int storedCoins, int step) {
        int want = Math.min(16, 6 + population / 2) * (step >= 2 ? 2 : 1);
        return Math.max(3, Math.min(want, Math.max(3, storedCoins / 2)));
    }
}
