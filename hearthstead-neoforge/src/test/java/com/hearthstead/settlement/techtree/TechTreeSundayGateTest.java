package com.hearthstead.settlement.techtree;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE SUNDAY GATE: every node of the tree (84 in the v3 design) does something in game.
 *
 * <ul>
 *   <li>Every node has at least one registered effect (EffectRegistry).</li>
 *   <li>A node whose data {@code impl} class is B, C or D (changed or new)
 *       also has an effect beyond the automatic legacy one: the branch lane
 *       wrote its handler.</li>
 *   <li>Every node's {@code impl} class is filled in (no "?").</li>
 * </ul>
 *
 * <p>The ordinary suite only reports progress (skipped with the missing list)
 * so the build stays green while branch lanes work. The gate run fails hard:
 * {@code HEARTHSTEAD_SUNDAY_GATE=1 ./gradlew test --tests '*TechTreeSundayGateTest'}.
 */
class TechTreeSundayGateTest {

    @Test
    void everyNodeHasARealEffect() {
        EffectRegistry registry = EffectRegistry.get();
        List<String> missing = new ArrayList<>();
        int done = 0;
        for (TechNodeDef node : TechTreeData.get().nodes()) {
            boolean ok = registry.implemented(node.id())
                && !"?".equals(node.impl())
                && ("A".equals(node.impl()) || registry.hasNewEffect(node.id()));
            if (ok) {
                done++;
            } else {
                missing.add(node.branch() + ":" + node.id() + "[" + node.impl() + "]");
            }
        }
        String report = done + "/" + TechTreeData.get().nodes().size() + " nodes done. Missing: " + String.join(", ", missing);
        System.out.println("[techtree sunday gate] " + report);
        if (!"1".equals(System.getenv("HEARTHSTEAD_SUNDAY_GATE"))) {
            Assumptions.assumeTrue(missing.isEmpty(), report);
        }
        assertTrue(missing.isEmpty(), report);
    }
}
