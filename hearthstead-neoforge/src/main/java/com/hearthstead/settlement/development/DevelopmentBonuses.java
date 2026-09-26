package com.hearthstead.settlement.development;

import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Settlement;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;

/**
 * Server-side effects of the small Development bonuses (upgrades appended at
 * wire id 5+ and the Shield Doctrine node).
 *
 * <p>Every accessor is side-effect free and returns the untouched base value
 * when the settlement does not own the bonus, when the call is client-side,
 * or when the settler has no settlement: behaviour without the bonus is
 * exactly the old behaviour.
 */
public final class DevelopmentBonuses {
    private DevelopmentBonuses() {
    }

    public static boolean owned(@Nullable ServerLevel level,
                                @Nullable Settlement settlement,
                                PostRaidUpgrade upgrade) {
        return level != null && settlement != null
            && Development.hasUpgrade(level, settlement, upgrade);
    }

    @Nullable
    private static Settlement settlementOf(SettlerEntity settler) {
        return settler != null && settler.level() instanceof ServerLevel
            ? settler.settlement() : null;
    }

    @Nullable
    private static ServerLevel levelOf(SettlerEntity settler) {
        return settler != null && settler.level() instanceof ServerLevel level
            ? level : null;
    }

    private static boolean owned(SettlerEntity settler, PostRaidUpgrade upgrade) {
        ServerLevel level = levelOf(settler);
        return level != null && owned(level, settlementOf(settler), upgrade);
    }

    /** Warm Hearth: hunger drain multiplier (0.9 at night when owned). */
    public static float hungerDrainScale(SettlerEntity settler) {
        ServerLevel level = levelOf(settler);
        if (level == null || !level.isNight()
            || !owned(settler, PostRaidUpgrade.WARM_HEARTH)) {
            return 1.0F;
        }
        return (100 - PostRaidUpgrade.WARM_HEARTH_PERCENT) / 100.0F;
    }

    /** Sturdy Beds: extra morale target for a settler with a claimed bed. */
    public static int bedMorale(SettlerEntity settler) {
        return owned(settler, PostRaidUpgrade.STURDY_BEDS)
            ? PostRaidUpgrade.STURDY_BEDS_MORALE : 0;
    }

    /** Feather Quilts: sleeping energy regain multiplier (1.2 when owned). */
    public static float sleepEnergyScale(SettlerEntity settler) {
        return owned(settler, PostRaidUpgrade.FEATHER_QUILTS)
            ? (100 + PostRaidUpgrade.FEATHER_QUILTS_PERCENT) / 100.0F : 1.0F;
    }

    /** Sharpened Axes: felling ticks for one tree (10% fewer, never below 1). */
    public static int fellingTicks(@Nullable ServerLevel level,
                                   @Nullable Settlement settlement, int baseTicks) {
        if (!owned(level, settlement, PostRaidUpgrade.SHARPENED_AXES)) {
            return baseTicks;
        }
        return Math.max(1, baseTicks * (100 - PostRaidUpgrade.SHARPENED_AXES_PERCENT) / 100);
    }

    /** Fisher's Nets: ticks in one cast cycle (10% fewer, never below 1). */
    public static int fisherCycleTicks(SettlerEntity settler, int baseTicks) {
        if (!owned(settler, PostRaidUpgrade.FISHERS_NETS)) {
            return baseTicks;
        }
        return Math.max(1, baseTicks * (100 - PostRaidUpgrade.FISHERS_NETS_PERCENT) / 100);
    }

    /** Stout Straps: extra Courier carry budget (0 when not owned). */
    public static int courierStrapBonus(@Nullable ServerLevel level,
                                        @Nullable Settlement settlement) {
        return owned(level, settlement, PostRaidUpgrade.STOUT_STRAPS)
            ? PostRaidUpgrade.STOUT_STRAPS_BONUS : 0;
    }

    /**
     * Guard Drill (+15%) and Shield Doctrine (+25%) add up on a real combat
     * award. Rounded down, but a positive award never shrinks.
     */
    public static int guardCombatXp(@Nullable ServerLevel level,
                                    @Nullable Settlement settlement, int amount) {
        if (amount <= 0 || level == null || settlement == null) {
            return amount;
        }
        int percent = 0;
        if (Development.hasUpgrade(level, settlement, PostRaidUpgrade.GUARD_DRILL)) {
            percent += PostRaidUpgrade.GUARD_DRILL_PERCENT;
        }
        if (Development.hasNode(level, settlement, DevelopmentNode.SHIELD_DOCTRINE)) {
            percent += PostRaidUpgrade.SHIELD_DOCTRINE_XP_PERCENT;
        }
        return amount + amount * percent / 100;
    }

    /**
     * Highest warehouse level the settlement's Logistics tree recognises:
     * L2 with nothing bought, +1 per consecutive owned gate (Warehouse Racks,
     * Great Storehouse, Royal Storehouse). Capped at L5.
     */
    public static int warehouseTechMaxLevel(@Nullable ServerLevel level,
                                            @Nullable Settlement settlement) {
        int owned = 0;
        for (PostRaidUpgrade gate : WAREHOUSE_LEVEL_GATES) {
            if (!owned(level, settlement, gate)) {
                break;
            }
            owned++;
        }
        return com.hearthstead.settlement.warehouse.WarehouseLevels
            .techMaxLevel(owned);
    }

    /** Gate nodes in order: index 0 unlocks L3, 1 unlocks L4, 2 unlocks L5. */
    public static final java.util.List<PostRaidUpgrade> WAREHOUSE_LEVEL_GATES =
        java.util.List.of(PostRaidUpgrade.WAREHOUSE_RACKS,
            PostRaidUpgrade.GREAT_STOREHOUSE, PostRaidUpgrade.ROYAL_STOREHOUSE);

    /** The node that unlocks recognition of {@code level}, or null (L1-L2, or past L5). */
    @Nullable
    public static PostRaidUpgrade warehouseGateFor(int level) {
        int index = level
            - com.hearthstead.settlement.warehouse.WarehouseLevels.BASE_TECH_LEVEL - 1;
        return index >= 0 && index < WAREHOUSE_LEVEL_GATES.size()
            ? WAREHOUSE_LEVEL_GATES.get(index) : null;
    }

    public static int guardCombatXp(SettlerEntity settler, int amount) {
        return guardCombatXp(levelOf(settler), settlementOf(settler), amount);
    }
}
