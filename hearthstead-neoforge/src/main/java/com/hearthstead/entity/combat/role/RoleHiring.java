package com.hearthstead.entity.combat.role;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;

/**
 * Hire-time rules for the battle roles (plan/BATTLE-ROLES.md): the kill-switch
 * and the Rune Mage cap. Called from {@code Employment}'s core hire check so
 * every path (plaque, emblem, QA) sees the same honest refusal.
 */
public final class RoleHiring {
    private RoleHiring() {
    }

    /** Null = allowed; otherwise the refusal to show. */
    @Nullable
    public static Component refusal(Settlement settlement, BuildingType type, SettlerEntity settler) {
        Profession trade = Employment.tradeOf(type);
        if (!RoleCombat.isRole(trade)) {
            return null;
        }
        if (!RoleCombat.enabled()) {
            return Component.translatable("hearthstead.employ.refused.roles_disabled");
        }
        if (trade == Profession.RUNE_MAGE) {
            int cap = mageCap(settler.level() instanceof net.minecraft.server.level.ServerLevel server
                ? server : null, settlement);
            if (magesExcept(settlement, settler) >= cap) {
                return Component.translatable("hearthstead.employ.refused.mage_cap", cap);
            }
        }
        return null;
    }

    /** One Rune Mage; two once the settlement has learned High Runes (tech tree). */
    public static int mageCap(@Nullable net.minecraft.server.level.ServerLevel level, Settlement settlement) {
        return RuneMageBrain.mageCap(settlement.settlers.size(),
            com.hearthstead.settlement.techtree.effects.WatchEffects.mageCap(level, settlement) >= 2);
    }

    static int magesExcept(Settlement settlement, SettlerEntity settler) {
        int n = 0;
        for (Building b : settlement.buildings) {
            if (Employment.tradeOf(b.type) != Profession.RUNE_MAGE) {
                continue;
            }
            for (java.util.UUID worker : b.workers) {
                if (!worker.equals(settler.getUUID())) {
                    n++;
                }
            }
        }
        return n;
    }
}
