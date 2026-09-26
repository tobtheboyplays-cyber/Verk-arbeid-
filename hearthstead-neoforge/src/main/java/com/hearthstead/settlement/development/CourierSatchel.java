package com.hearthstead.settlement.development;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Settlement;
import net.minecraft.server.level.ServerLevel;

/**
 * Server-side effect of {@link PostRaidUpgrade#COURIER_SATCHEL}.
 *
 * <p>The Courier's trip budget stays the single entity-owned number
 * ({@link SettlerEntity#getCarryCapacity()}), which is synced to clients and
 * persisted with the settler. Once the settlement owns the Satchel, a Courier
 * at or above the base budget is raised to base + bonus (8 to 12 by default).
 * Deliberately reduced budgets (tests, admin tools) and larger budgets are
 * left alone, so the upgrade never lowers anything and never stacks.
 *
 * <p>Sack tiers and the {@link PostRaidUpgrade#HAND_CART} are computed by
 * {@link HaulGear}: Satchel 12, Leather Pack 16, Frame Pack 20, and the cart
 * adds +200% of base on top (Satchel + cart = 28, Frame Pack + cart = 36).
 */
public final class CourierSatchel {
    private CourierSatchel() {
    }

    /** Items per trip a Courier of a Satchel-owning settlement is raised to. */
    public static int satchelCapacity() {
        return HaulGear.courierCapacity(1, false);
    }

    /** Items per trip with the Satchel and the Hand Cart (28 by default). */
    public static int handCartCapacity() {
        return HaulGear.courierCapacity(1, true);
    }

    /**
     * Budget the settlement's owned courier upgrades grant, or the base
     * budget when none is owned. A Hand Cart without its Satchel (only
     * possible through a quarantined save, which owns nothing) grants nothing.
     */
    public static int targetCapacity(ServerLevel level, Settlement settlement) {
        // Stout Straps (a pre-raid Logistics bonus) stacks under every tier.
        int straps = DevelopmentBonuses.courierStrapBonus(level, settlement);
        int tier = HaulGear.sackTier(level, settlement);
        // Tech tree v3: Coster's/Mule Cart raise the cart percent, the
        // Porters' Guild the whole trip (LogisticsEffects; 0 when not learned).
        return straps + HaulGear.courierCapacity(tier,
            tier >= 1 && HaulGear.cartOwned(level, settlement)
                ? HaulGear.cartPercent(level, settlement) : 0,
            com.hearthstead.settlement.techtree.effects.LogisticsEffects
                .courierLoadPercent(level, settlement));
    }

    /** Applies the Satchel/Hand Cart to one Courier and returns its resulting budget. */
    public static int apply(ServerLevel level, Settlement settlement,
                            SettlerEntity settler) {
        if (level == null || settlement == null || settler == null) {
            return settler == null ? 0 : settler.getCarryCapacity();
        }
        int current = settler.getCarryCapacity();
        HaulGear.publish(level, settlement, settler);
        if (settler.getProfession() != Profession.COURIER) {
            return current;
        }
        // Trade skill (primary, Strength): +1 per 3 levels above 1, cap +3,
        // layered on top of Straps / Satchel / Hand Cart. 0 at level 1-3.
        int target = targetCapacity(level, settlement)
            + com.hearthstead.entity.SkillLevels.carryBonus(settler)
            // Strength: +0..4 items per trip (AttributeRuntime.haulBonus, plan/ATTRIBUTES.md).
            + com.hearthstead.entity.AttributeRuntime.haulBonus(settler);
        if (current >= SettlerEntity.BASE_CARRY_CAPACITY && current < target) {
            settler.setCarryCapacity(target);
        }
        return settler.getCarryCapacity();
    }
}
