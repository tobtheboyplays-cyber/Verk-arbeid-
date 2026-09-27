package com.hearthstead.settlement.development;

import com.hearthstead.entity.GuardRank;
import com.hearthstead.settlement.Settlement;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;

/**
 * Server-side effect of {@link PostRaidUpgrade#GUARD_ARMS_IRON}.
 *
 * <p>Armour stays earned by experience: Recruits and Spearmen are dressed
 * exactly as before. From Veteran up, a settlement with the drill dresses a
 * guard in the next rank's kit (Veteran: iron chest and legs; Sergeant: full
 * iron). Rank abilities still read raw Strength, and every piece is still
 * withdrawn from the armoury, warehouse or hearth by
 * {@link GuardRank#applyEquipment}; nothing is conjured and a missing piece
 * simply waits.
 */
public final class GuardArms {
    private GuardArms() {
    }

    public static boolean owned(@Nullable ServerLevel level,
                                @Nullable Settlement settlement) {
        return level != null && settlement != null && Development.hasUpgrade(
            level, settlement, PostRaidUpgrade.GUARD_ARMS_IRON);
    }

    /** The rank whose armour kit a guard of {@code earned} rank is dressed in. */
    public static GuardRank kitRank(@Nullable ServerLevel level,
                                    @Nullable Settlement settlement,
                                    GuardRank earned) {
        if (earned == null || !earned.atLeast(GuardRank.VETERAN)
            || earned == GuardRank.CAPTAIN || !owned(level, settlement)) {
            return earned;
        }
        return GuardRank.values()[earned.ordinal() + 1];
    }
}
