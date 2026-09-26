package com.hearthstead.entity.combat.role;

import com.hearthstead.building.BuildingType;
import com.hearthstead.settlement.development.DevelopmentNode;

import javax.annotation.Nullable;

/**
 * Which of TODAY's Development nodes opens each battle-role hall's plan
 * (plan/BATTLE-ROLES.md section 5). Kept outside {@link DevelopmentNode}'s own
 * building lists on purpose: those lists are the locked tutorial contract
 * (DevelopmentStateTest) and the tech-tree implementation will move these
 * halls onto the new tree's own nodes (spearmen, longswords, battle_healer,
 * rune_mage). The Infirmary had no reachable node before this (its node,
 * hall_and_learning, is not implemented).
 */
public final class RoleUnlocks {
    private RoleUnlocks() {
    }

    @Nullable
    public static DevelopmentNode buildingNode(BuildingType type) {
        return switch (type) {
            case PIKE_YARD, SWORD_HALL -> DevelopmentNode.SHIELD_DOCTRINE;
            case INFIRMARY -> DevelopmentNode.FIRST_RAID_AFTERMATH;
            case RUNE_HALL -> DevelopmentNode.HEARTH_DOCTRINE;
            default -> null;
        };
    }
}
