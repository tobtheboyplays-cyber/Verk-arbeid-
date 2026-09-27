package com.hearthstead.settlement.development;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.settlement.Settlement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Mirrors learned Build Plan recipes into one player's vanilla recipe book.
 *
 * <p>The recipe book is presentation only: it belongs to the player, not to
 * a settlement, and therefore remains visible if that player later visits a
 * settlement which has not learned the same plan. Server-side plan use still
 * consults {@link Development#isBuildingUnlocked} for the settlement at the
 * plaque. This class deliberately never treats vanilla recipe discovery as
 * settlement authority or removes a recipe learned while visiting elsewhere.
 */
public final class DevelopmentRecipeBook {
    public static void syncPlayerHints(ServerPlayer player, Settlement settlement) {
        if (player == null || settlement == null) {
            return;
        }
        DevelopmentState state = Development.of(player.serverLevel(), settlement);
        if (state.quarantined()) {
            return;
        }
        // One authority: exactly the plans the plaque would accept here
        // (legacy node lists, RoleUnlocks and v3 tech-tree claims).
        EnumSet<BuildingType> known = EnumSet.noneOf(BuildingType.class);
        for (BuildingType type : BuildingType.values()) {
            if (Development.isBuildingUnlocked(player.serverLevel(), settlement, type)) {
                known.add(type);
            }
        }
        if (state.legacyHouseEntitlement()) {
            known.add(BuildingType.HOUSE);
        }
        ArrayList<ResourceLocation> recipeIds = new ArrayList<>(known.size() + 1);
        for (BuildingType type : known) {
            recipeIds.add(Hearthstead.id("build_plan_" + type.id()));
            // The Builder's plan for the same building (unknown ids are skipped by the recipe book).
            recipeIds.add(Hearthstead.id("building_plan_" + type.id()));
        }
        // Player recipe knowledge is only a hint. The Work Scepter's target,
        // corner and commit endpoints still verify this exact settlement's
        // Timber Rights state every time.
        // Founding trades are independent (Option 2): the Lumber Camp and the
        // Farm each need a Work Zone, so either one teaches the Scepter.
        if (state.unlocked(DevelopmentNode.TIMBER_RIGHTS)
            || state.unlocked(DevelopmentNode.CULTIVATED_GROUND)) {
            recipeIds.add(Hearthstead.id("work_scepter"));
        }
        if (recipeIds.isEmpty()) {
            return;
        }
        player.awardRecipesByKey(List.copyOf(recipeIds));
    }

    private DevelopmentRecipeBook() {
    }
}
