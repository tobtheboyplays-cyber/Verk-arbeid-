package com.hearthstead.gametest;

import net.minecraft.gametest.framework.GameTestHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Tick scheduling for GameTests that does not flake when a test starts late.
 *
 * <p>Vanilla {@code runAtTickTime(t)} is absolute from the batch start, and it
 * keys its table by runnable in a hash map. When a slow batch spawn starts the
 * test function at tick k &gt; 1, every entry with t &lt;= k fires together in
 * that first tick, in hash order (W8a battle_roles_mage_frost). {@link #at}
 * counts from the tick it is called at (the test's setup tick), and steps that
 * share a tick run in the order they were scheduled, as one step.
 */
public final class GameTestTicks {
    private static final Map<GameTestHelper, Map<Long, List<Runnable>>> PLAN = new WeakHashMap<>();

    private GameTestTicks() {
    }

    /** Runs {@code step} {@code t} ticks after now; same-tick steps keep their call order. */
    public static void at(GameTestHelper helper, long t, Runnable step) {
        long tick = helper.getTick() + t;
        Map<Long, List<Runnable>> plan = PLAN.computeIfAbsent(helper, h -> new HashMap<>());
        List<Runnable> steps = plan.get(tick);
        if (steps == null) {
            List<Runnable> created = new ArrayList<>();
            plan.put(tick, created);
            helper.runAtTickTime(tick, () -> {
                for (Runnable r : List.copyOf(created)) {
                    r.run();
                }
            });
            steps = created;
        }
        steps.add(step);
    }
}
