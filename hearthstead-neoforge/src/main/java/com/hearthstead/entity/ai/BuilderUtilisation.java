package com.hearthstead.entity.ai;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.builder.BuildJob;
import com.hearthstead.settlement.builder.BuildSiteSavedData;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How a Builder spends his working hours (owner, film take 2: "she takes a
 * lot of breaks"). Every server tick of working hours in which his settlement
 * has a site to work on is counted once, in one bucket:
 *
 * <ul>
 * <li>{@link Kind#PLACE} -- at the target, setting, clearing or filling;</li>
 * <li>{@link Kind#MOVE_SITE} -- walking between work spots with a load;</li>
 * <li>{@link Kind#CARRY} -- walking to or from the hut / warehouse for
 *     materials, or bringing leftovers home;</li>
 * <li>{@link Kind#SCAFFOLD} -- hanging or taking down a ladder column;</li>
 * <li>{@link Kind#WAIT_MATERIAL} -- waiting at the hut because the
 *     settlement truly lacks an item (not the Builder's fault, and not
 *     counted against him);</li>
 * <li>{@link Kind#STALL} -- out of reach and not moving, a failed route,
 *     only resting steps left;</li>
 * <li>{@link Kind#OTHER_GOAL} -- the Builder goal is not running (eating,
 *     strolling, chatting...), with the goal named.</li>
 * </ul>
 *
 * <p>Utilisation = (PLACE + MOVE_SITE + CARRY + SCAFFOLD) / (all - WAIT_MATERIAL):
 * the share of time he is visibly working while materials exist. The owner's
 * target is at least 70 %. The longest unproductive streak is kept too, so a
 * single long pause cannot hide inside a good average.
 */
public final class BuilderUtilisation {

    public enum Kind {
        PLACE, MOVE_SITE, CARRY, SCAFFOLD, WAIT_MATERIAL, STALL, OTHER_GOAL;

        public boolean productive() {
            return this == PLACE || this == MOVE_SITE || this == CARRY || this == SCAFFOLD;
        }
    }

    /** Running totals for one Builder. */
    public static final class Stats {
        private final EnumMap<Kind, Long> ticks = new EnumMap<>(Kind.class);
        private final Map<String, Long> otherGoals = new LinkedHashMap<>();
        private long streak;
        private long longestIdle;
        private Kind longestIdleKind;

        void add(Kind kind, String other) {
            ticks.merge(kind, 1L, Long::sum);
            if (kind == Kind.OTHER_GOAL) {
                otherGoals.merge(other, 1L, Long::sum);
            }
            if (kind.productive() || kind == Kind.WAIT_MATERIAL) {
                streak = 0;
            } else {
                streak++;
                if (streak > longestIdle) {
                    longestIdle = streak;
                    longestIdleKind = kind;
                }
            }
        }

        public long ticks(Kind kind) {
            return ticks.getOrDefault(kind, 0L);
        }

        public long total() {
            long sum = 0;
            for (long t : ticks.values()) {
                sum += t;
            }
            return sum;
        }

        /** Productive share of the ticks in which materials existed (0..1). */
        public double utilisation() {
            long counted = total() - ticks(Kind.WAIT_MATERIAL);
            if (counted <= 0) {
                return 0.0D;
            }
            long productive = 0;
            for (Kind kind : Kind.values()) {
                if (kind.productive()) {
                    productive += ticks(kind);
                }
            }
            return productive / (double) counted;
        }

        public long longestIdle() {
            return longestIdle;
        }

        public String describe() {
            long total = Math.max(1L, total());
            StringBuilder out = new StringBuilder(String.format(Locale.ROOT,
                "utilisation %.0f%% over %d ticks;", utilisation() * 100.0D, total()));
            for (Kind kind : Kind.values()) {
                long t = ticks(kind);
                if (t > 0) {
                    out.append(String.format(Locale.ROOT, " %s %.0f%%", kind.name().toLowerCase(Locale.ROOT),
                        100.0D * t / total));
                }
            }
            out.append("; longest pause ").append(longestIdle).append(" ticks");
            if (longestIdleKind != null) {
                out.append(" (").append(longestIdleKind.name().toLowerCase(Locale.ROOT)).append(')');
            }
            if (!otherGoals.isEmpty()) {
                out.append("; other goals ").append(otherGoals);
            }
            return out.toString();
        }
    }

    private static final Map<UUID, Stats> STATS = new ConcurrentHashMap<>();
    /** Every Builder goal alive, weakly: the sampler asks each one what it did this tick. */
    private static final Map<SettlerEntity, BuilderWorkGoal> GOALS =
        Collections.synchronizedMap(new WeakHashMap<>());

    private BuilderUtilisation() {
    }

    /** Diagnostics for tests: what the Builder goal of this settler is doing, or "no goal". */
    public static String debug(SettlerEntity settler) {
        BuilderWorkGoal goal;
        synchronized (GOALS) {
            goal = GOALS.get(settler);
        }
        return goal == null ? "no goal" : goal.debugState();
    }

    static void register(SettlerEntity settler, BuilderWorkGoal goal) {
        GOALS.put(settler, goal);
    }

    public static Stats stats(UUID settler) {
        return STATS.computeIfAbsent(settler, id -> new Stats());
    }

    public static void reset(UUID settler) {
        STATS.remove(settler);
    }

    public static Map<UUID, Stats> all() {
        return Collections.unmodifiableMap(STATS);
    }

    /**
     * Called once per server level tick (after entities ticked). Counts only
     * working hours of a hired Builder whose settlement has a site to work.
     */
    public static void sample(ServerLevel level) {
        List<Map.Entry<SettlerEntity, BuilderWorkGoal>> entries;
        synchronized (GOALS) {
            if (GOALS.isEmpty()) {
                return;
            }
            entries = new ArrayList<>(GOALS.entrySet());
        }
        long now = level.getGameTime();
        for (Map.Entry<SettlerEntity, BuilderWorkGoal> entry : entries) {
            SettlerEntity settler = entry.getKey();
            if (settler == null || settler.level() != level || !settler.isAlive()
                || settler.getProfession() != Profession.BUILDER || !settler.isBound()
                || !settler.dayPhase().work()) {
                continue;
            }
            BuilderWorkGoal goal = entry.getValue();
            Kind kind = goal.kindAt(now);
            String other = "";
            if (kind == null) {
                if (!hasSite(level, settler)) {
                    continue;
                }
                kind = Kind.OTHER_GOAL;
                other = runningGoal(settler);
            }
            stats(settler.getUUID()).add(kind, other);
        }
    }

    private static boolean hasSite(ServerLevel level, SettlerEntity settler) {
        Settlement settlement = settler.settlement();
        if (settlement == null) {
            return false;
        }
        for (BuildJob job : BuildSiteSavedData.get(level).jobs(settlement.id)) {
            if (job.workable()) {
                return true;
            }
        }
        return false;
    }

    private static String runningGoal(SettlerEntity settler) {
        StringBuilder names = new StringBuilder();
        settler.goalSelector.getAvailableGoals().forEach(wrapped -> {
            if (wrapped.isRunning() && !(wrapped.getGoal() instanceof net.minecraft.world.entity.ai.goal.LookAtPlayerGoal)
                && !(wrapped.getGoal() instanceof net.minecraft.world.entity.ai.goal.RandomLookAroundGoal)) {
                if (names.length() > 0) {
                    names.append('+');
                }
                names.append(wrapped.getGoal().getClass().getSimpleName());
            }
        });
        return names.length() == 0 ? "none" : names.toString();
    }
}
