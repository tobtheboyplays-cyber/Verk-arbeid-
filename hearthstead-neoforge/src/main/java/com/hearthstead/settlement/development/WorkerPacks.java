package com.hearthstead.settlement.development;

import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.JobEffects;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.Trait;
import com.hearthstead.settlement.Settlement;
import net.minecraft.server.level.ServerLevel;

/**
 * Server-side effect of {@link PostRaidUpgrade#WORKER_PACKS}.
 *
 * <p>Lumberers and Farmers gather into the same entity-owned trip budget
 * ({@link SettlerEntity#getCarryCapacity()}) the Courier Satchel raises.
 * With the packs a gatherer's natural budget (8 at base, a Lumberer's
 * Strength budget if larger) is multiplied by 3/2, so a base worker carries
 * 12 items per trip instead of 8. Like the Satchel it never lowers a budget,
 * never stacks and leaves deliberately reduced budgets alone. Once the
 * settlement owns the Leather Pack or Frame Pack, packed gatherers follow
 * that sack tier too (8 to 16, 8 to 20).
 */
public final class WorkerPacks {
    private WorkerPacks() {
    }

    /** True for the professions the packs apply to. */
    public static boolean appliesTo(Profession profession) {
        return profession == Profession.LUMBERER || profession == Profession.FARMER;
    }

    /** The packed budget for a natural budget at the Satchel tier (8 to 12). */
    public static int packCapacity(int natural) {
        return natural * PostRaidUpgrade.WORKER_PACKS_NUMERATOR
            / PostRaidUpgrade.WORKER_PACKS_DENOMINATOR;
    }

    /**
     * The packed budget at the settlement's sack tier: the Satchel's +50%
     * at least (identical to {@link #packCapacity}), +100% with the Leather
     * Pack and +150% with the Frame Pack.
     */
    public static int packCapacity(int natural, int sackTier) {
        return Math.max(packCapacity(natural), HaulGear.gathererCapacity(natural, sackTier));
    }

    private static int tierOf(ServerLevel level, Settlement settlement) {
        return HaulGear.sackTier(level, settlement);
    }

    /** Budget without the packs: base, or a Lumberer's larger Strength budget. */
    public static int naturalCapacity(SettlerEntity settler) {
        // Strength: +0..4 items per trip (AttributeRuntime.haulBonus, plan/ATTRIBUTES.md).
        int natural = SettlerEntity.BASE_CARRY_CAPACITY
            + com.hearthstead.entity.AttributeRuntime.haulBonus(settler);
        if (settler.getProfession() == Profession.LUMBERER) {
            natural = Math.max(natural, JobEffects.carryItems(
                settler.attribute(Attribute.STRENGTH), Trait.carry(settler.traits())));
        }
        return natural;
    }

    /** Applies the packs to one gatherer and returns its resulting budget. */
    public static int apply(ServerLevel level, Settlement settlement,
                            SettlerEntity settler) {
        if (level == null || settlement == null || settler == null) {
            return settler == null ? 0 : settler.getCarryCapacity();
        }
        int current = settler.getCarryCapacity();
        HaulGear.publish(level, settlement, settler);
        if (!appliesTo(settler.getProfession()) || !HaulGear.enabled()
            || !Development.hasUpgrade(level, settlement, PostRaidUpgrade.WORKER_PACKS)) {
            return current;
        }
        int target = packCapacity(naturalCapacity(settler), tierOf(level, settlement));
        if (current >= SettlerEntity.BASE_CARRY_CAPACITY && current < target) {
            settler.setCarryCapacity(target);
        }
        return settler.getCarryCapacity();
    }

    /**
     * The Farmer's "bag is full, walk to storage" trigger. Exactly
     * {@code baseTrigger} (8) unless the settlement owns the packs; then it
     * follows the raised budget up to the packed size (12), never below the
     * base trigger, so reduced budgets keep the old behaviour.
     */
    public static int farmerBagTrigger(SettlerEntity settler, int baseTrigger) {
        if (!(settler.level() instanceof ServerLevel level) || !HaulGear.enabled()
            || !Development.hasUpgrade(level, settler.settlement(),
                PostRaidUpgrade.WORKER_PACKS)) {
            return baseTrigger;
        }
        return Math.max(baseTrigger, Math.min(settler.getCarryCapacity(),
            packCapacity(baseTrigger, tierOf(level, settler.settlement()))));
    }
}
