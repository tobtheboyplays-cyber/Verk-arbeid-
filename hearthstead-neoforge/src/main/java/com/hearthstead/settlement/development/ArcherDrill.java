package com.hearthstead.settlement.development;

import com.hearthstead.settlement.Settlement;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;

/**
 * Server-side effect of {@link PostRaidUpgrade#ARCHER_LONGBOW_DRILL}.
 *
 * <p>Settlement-aware accessors for the ordinary (unposted) Archer volley:
 * with the drill the shot range grows by 2 blocks (18 to 20) and the draw
 * is 4 ticks shorter (20 to 16). Tower Post range, arrow speed, damage and
 * ammunition are unchanged; arrows are still physical.
 */
public final class ArcherDrill {
    private ArcherDrill() {
    }

    public static boolean owned(@Nullable ServerLevel level,
                                @Nullable Settlement settlement) {
        return level != null && settlement != null && Development.hasUpgrade(
            level, settlement, PostRaidUpgrade.ARCHER_LONGBOW_DRILL);
    }

    /** Ordinary shot range for this settlement's Archers. */
    public static double normalShotRange(@Nullable ServerLevel level,
                                         @Nullable Settlement settlement,
                                         double baseRange) {
        return owned(level, settlement)
            ? baseRange + PostRaidUpgrade.LONGBOW_RANGE_BONUS : baseRange;
    }

    /** Ordinary draw ticks for this settlement's Archers (never below 1). */
    public static int ordinaryDrawTicks(@Nullable ServerLevel level,
                                        @Nullable Settlement settlement,
                                        int baseTicks) {
        return owned(level, settlement)
            ? Math.max(1, baseTicks - PostRaidUpgrade.LONGBOW_DRAW_TICKS_SAVED)
            : baseTicks;
    }
}
