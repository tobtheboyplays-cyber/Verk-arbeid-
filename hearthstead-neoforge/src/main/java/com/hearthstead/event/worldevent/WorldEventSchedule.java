package com.hearthstead.event.worldevent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * Pure scheduling rules for the small world events. Nothing here reads the
 * world or the config, so every rule is directly JUnit-testable:
 *
 * <ul>
 *   <li>At most one small event per settlement per in-game day, and many
 *       days have nothing ({@link #dailyChance}).</li>
 *   <li>Never during a raid, never once a raid warning is out, and never on
 *       the day before or the day of a scheduled attack
 *       ({@link #raidQuiet}).</li>
 *   <li>Only events whose requirements exist (no fields, no fox; no tavern,
 *       no minstrels or brawl) are candidates; the caller passes that set.</li>
 *   <li>The same event does not come back before its own gap in days.</li>
 *   <li>Deterministic per world seed, settlement and day, so a GameTest (or a
 *       player reloading) sees the same plan.</li>
 * </ul>
 *
 * <p>A day is planned only when a player is actually near the settlement on
 * that day (the director calls {@link #plan} at the first eligible look).
 * Days nobody visits are never planned, so nothing piles up offline.
 */
public final class WorldEventSchedule {
    /** Chance that a planned day has an event at frequency 1.0. */
    public static final double BASE_DAILY_CHANCE = 0.55D;
    /** Even at a high multiplier some days stay quiet. */
    public static final double MAX_DAILY_CHANCE = 0.9D;
    public static final long NO_DAY = -1L;

    /** One day's decision. {@code type == null} means a quiet day. */
    public record Plan(long day, WorldEventType type, int startTimeOfDay) {
        public boolean quiet() { return type == null; }
        public static Plan quiet(long day) { return new Plan(day, null, -1); }
    }

    private WorldEventSchedule() {
    }

    /** Daily event chance for a frequency multiplier; 0 or less disables. */
    public static double dailyChance(double frequency) {
        if (!(frequency > 0.0D)) return 0.0D;
        return Math.min(MAX_DAILY_CHANCE, BASE_DAILY_CHANCE * frequency);
    }

    /**
     * Raid gate. {@code nextAttackNight} is the raid-night index of the next
     * scheduled attack or a negative value when none is known.
     * {@code currentNight} is the raid-night index whose dusk most recently
     * passed. Blocks the whole stretch from the warning dusk (the day before
     * the attack) through the attack itself.
     */
    public static boolean raidQuiet(boolean raidActive, boolean warningOut,
                                    long currentNight, long nextAttackNight) {
        if (raidActive || warningOut) return false;
        return nextAttackNight < 0L || nextAttackNight - currentNight > 1L
            || nextAttackNight < currentNight;
    }

    /** Whether {@code type}'s own gap since it last ran has passed on {@code day}. */
    public static boolean offCooldown(WorldEventType type, long day, Map<WorldEventType, Long> lastDayByType) {
        Long last = lastDayByType == null ? null : lastDayByType.get(type);
        return last == null || last < 0L || day - last >= type.gapDays();
    }

    /** Stable per-(world, settlement, day) seed. */
    public static long seed(long worldSeed, UUID settlementId, long day) {
        long h = worldSeed * 0x9E3779B97F4A7C15L;
        if (settlementId != null) {
            h ^= settlementId.getMostSignificantBits() * 0xC2B2AE3D27D4EB4FL;
            h ^= settlementId.getLeastSignificantBits() * 0x165667B19E3779F9L;
        }
        h ^= day * 0xD6E8FEB86659FD93L;
        return h ^ (h >>> 31);
    }

    /**
     * Plans {@code day}. {@code available} holds only events whose world
     * requirements are met and which are enabled; {@code lastEventDay} is the
     * day any event last began here.
     */
    public static Plan plan(long worldSeed, UUID settlementId, long day,
                            Collection<WorldEventType> available,
                            Map<WorldEventType, Long> lastDayByType,
                            long lastEventDay, double frequency, boolean raidQuiet) {
        return plan(worldSeed, settlementId, day, available, lastDayByType, lastEventDay,
            frequency, raidQuiet, null, null);
    }

    /**
     * As above, with the settlement's tech tree folded in:
     * {@code weightScale} multiplies an event's base weight (null = 1), and a
     * {@code due} event (e.g. a caravan with Caravan Routes) is planned for
     * the day without the daily roll or its own gap, when it is available.
     * One event a day and the raid-quiet rule still hold.
     */
    public static Plan plan(long worldSeed, UUID settlementId, long day,
                            Collection<WorldEventType> available,
                            Map<WorldEventType, Long> lastDayByType,
                            long lastEventDay, double frequency, boolean raidQuiet,
                            java.util.function.ToDoubleFunction<WorldEventType> weightScale,
                            WorldEventType due) {
        if (day < 0L || !raidQuiet || available == null || available.isEmpty()
            || lastEventDay == day) {
            return Plan.quiet(day);
        }
        SplittableRandom random = new SplittableRandom(seed(worldSeed, settlementId, day));
        boolean eventDay = random.nextDouble() < dailyChance(frequency);
        if (due != null && available.contains(due) && dailyChance(frequency) > 0.0D) {
            int span = due.startTo() - due.startFrom() + 1;
            return new Plan(day, due, due.startFrom() + random.nextInt(Math.max(1, span)));
        }
        if (!eventDay) return Plan.quiet(day);
        List<WorldEventType> candidates = new ArrayList<>();
        double total = 0.0D;
        for (WorldEventType type : WorldEventType.values()) {
            if (!available.contains(type) || !offCooldown(type, day, lastDayByType)) continue;
            candidates.add(type);
            total += weightOf(type, weightScale);
        }
        if (candidates.isEmpty() || total <= 0.0D) return Plan.quiet(day);
        double roll = random.nextDouble() * total;
        WorldEventType chosen = candidates.get(candidates.size() - 1);
        for (WorldEventType type : candidates) {
            roll -= weightOf(type, weightScale);
            if (roll < 0.0D) { chosen = type; break; }
        }
        int span = chosen.startTo() - chosen.startFrom() + 1;
        int start = chosen.startFrom() + random.nextInt(Math.max(1, span));
        return new Plan(day, chosen, start);
    }

    /** Base weight times the settlement's scale (never negative). */
    public static double weightOf(WorldEventType type,
                                  java.util.function.ToDoubleFunction<WorldEventType> weightScale) {
        double scale = weightScale == null ? 1.0D : weightScale.applyAsDouble(type);
        return type.weight() * Math.max(0.0D, scale);
    }

    /** Whether a planned event may begin at this time of day. */
    public static boolean inStartWindow(Plan plan, long timeOfDay) {
        return plan != null && !plan.quiet()
            && timeOfDay >= plan.startTimeOfDay() && timeOfDay <= plan.type().startTo();
    }

    /** The in-game day index of an absolute day-time. */
    public static long dayOf(long dayTime) {
        return Math.floorDiv(dayTime, 24_000L);
    }

    /** Time of day 0..23999 of an absolute day-time. */
    public static long timeOfDay(long dayTime) {
        return Math.floorMod(dayTime, 24_000L);
    }
}
