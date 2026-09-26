package com.hearthstead.settlement.builder;

import com.hearthstead.settlement.Settlement;
import net.minecraft.server.level.ServerLevel;

/**
 * "Match town style" hook for the Builder's Plan. The blueprint lane's town
 * palette scanner installs a {@link Styler} at setup; until one is installed
 * (or when the player switches the toggle off) blueprints are used exactly as
 * authored. The Builder lane only ever calls {@link #apply}; the styler owns
 * the palette rules and must return a blueprint with the same cells, only
 * other block states (the Builder re-derives cost, order and phases from it).
 */
public final class BlueprintStyles {

    /** Re-skins one blueprint to a settlement's own materials. */
    @FunctionalInterface
    public interface Styler {
        Blueprint style(ServerLevel level, Settlement settlement, Blueprint blueprint);
    }

    private static volatile Styler styler;

    private BlueprintStyles() {
    }

    /** Called once by the palette scanner (blueprint lane). */
    public static void install(Styler installed) {
        styler = installed;
    }

    public static boolean available() {
        return styler != null;
    }

    /** The blueprint in the town's style, or unchanged when off / not available / failing. */
    public static Blueprint apply(ServerLevel level, Settlement settlement, Blueprint blueprint, boolean match) {
        Styler current = styler;
        if (!match || current == null || blueprint == null || settlement == null) {
            return blueprint;
        }
        try {
            Blueprint styled = current.style(level, settlement, blueprint);
            return styled == null ? blueprint : styled;
        } catch (RuntimeException e) {
            com.hearthstead.Hearthstead.LOGGER.warn("Builder: town style failed for {}: {}", blueprint.id(), e.toString());
            return blueprint;
        }
    }
}
