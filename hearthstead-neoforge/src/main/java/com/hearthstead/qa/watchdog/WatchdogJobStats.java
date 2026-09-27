package com.hearthstead.qa.watchdog;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Per-profession aggregate for one watchdog session. Diagnostic only. */
public final class WatchdogJobStats {
    /** Why an employed settler had no job-shaped goal in a work phase. */
    public enum IdleReason {
        UNBOUND, NO_EMPLOYER, EFFORT_SPENT, NO_TOOL, LOGISTICS_STOP, NOT_AT_POST, NO_INPUT, OTHER;

        public static final IdleReason[] VALUES = values();
    }

    private static final int MAX_CAUSES = 48;

    public final String job;
    /** Distinct settlers seen with this job. */
    public int settlers;
    /** Samples taken while employed and inside a work phase. */
    public long workSamples;
    /** Of those: samples per dominant {@link GoalKind}. */
    public final long[] kindSamples = new long[GoalKind.VALUES.length];
    /** Of those: WORK samples with no stall flag raised. */
    public long productiveSamples;
    /** Of those: samples with STUCK or LOOP raised. */
    public long stalledSamples;
    public final long[] idleReasonSamples = new long[IdleReason.VALUES.length];
    public final int[] episodes = new int[WatchdogFlag.VALUES.length];
    public final long[] episodeTicks = new long[WatchdogFlag.VALUES.length];
    /** Sum of positive effort deltas: a rough output witness. */
    public long effortUnits;
    /** Bounded cause histogram, "FLAG|goal|activity|detail" -> count. */
    public final Map<String, Integer> causes = new LinkedHashMap<>();

    public WatchdogJobStats(String job) {
        this.job = job;
    }

    public void cause(String key) {
        Integer n = causes.get(key);
        if (n != null) {
            causes.put(key, n + 1);
        } else if (causes.size() < MAX_CAUSES) {
            causes.put(key, 1);
        } else {
            causes.merge("(other)", 1, Integer::sum);
        }
    }

    public double percent(long part) {
        return workSamples == 0 ? 0.0D : 100.0D * part / workSamples;
    }

    public String line() {
        StringBuilder b = new StringBuilder();
        b.append(String.format(Locale.ROOT, "%-11s n=%d work%%=%5.1f stalled%%=%5.1f",
            job, settlers, percent(productiveSamples), percent(stalledSamples)));
        for (GoalKind kind : GoalKind.VALUES) {
            long v = kindSamples[kind.ordinal()];
            if (v > 0) {
                b.append(String.format(Locale.ROOT, " %s=%.0f%%",
                    kind.name().toLowerCase(Locale.ROOT), percent(v)));
            }
        }
        for (WatchdogFlag flag : WatchdogFlag.VALUES) {
            int n = episodes[flag.ordinal()];
            if (n > 0) {
                b.append(' ').append(flag.name()).append('=').append(n);
            }
        }
        return b.toString();
    }

    public void appendJson(StringBuilder b) {
        b.append("{\"job\":\"").append(job).append("\",\"settlers\":").append(settlers)
            .append(",\"workSamples\":").append(workSamples)
            .append(",\"productiveSamples\":").append(productiveSamples)
            .append(",\"stalledSamples\":").append(stalledSamples)
            .append(",\"workingPercent\":").append(String.format(Locale.ROOT, "%.2f", percent(productiveSamples)))
            .append(",\"effortUnits\":").append(effortUnits)
            .append(",\"kinds\":{");
        for (GoalKind kind : GoalKind.VALUES) {
            if (kind.ordinal() > 0) {
                b.append(',');
            }
            b.append('"').append(kind.name()).append("\":").append(kindSamples[kind.ordinal()]);
        }
        b.append("},\"idleReasons\":{");
        for (IdleReason reason : IdleReason.VALUES) {
            if (reason.ordinal() > 0) {
                b.append(',');
            }
            b.append('"').append(reason.name()).append("\":").append(idleReasonSamples[reason.ordinal()]);
        }
        b.append("},\"episodes\":{");
        for (WatchdogFlag flag : WatchdogFlag.VALUES) {
            if (flag.ordinal() > 0) {
                b.append(',');
            }
            b.append('"').append(flag.name()).append("\":{\"count\":").append(episodes[flag.ordinal()])
                .append(",\"ticks\":").append(episodeTicks[flag.ordinal()]).append('}');
        }
        b.append("},\"causes\":{");
        boolean first = true;
        for (Map.Entry<String, Integer> e : causes.entrySet()) {
            if (!first) {
                b.append(',');
            }
            first = false;
            b.append('"').append(escape(e.getKey())).append("\":").append(e.getValue());
        }
        b.append("}}");
    }

    static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
