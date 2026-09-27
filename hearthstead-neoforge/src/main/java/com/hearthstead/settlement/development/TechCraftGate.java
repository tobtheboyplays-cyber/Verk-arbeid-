package com.hearthstead.settlement.development;

import com.hearthstead.building.BuildingType;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.techtree.TechRecipeGates;
import com.hearthstead.settlement.techtree.TechTreeConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Server authority for the owner rule "nothing is craftable before its tech
 * node is learned" ({@code [techtree] gateCrafting}). Players craft for the
 * settlement they stand in (else the nearest one within
 * {@link #PLAYER_RADIUS}); in co-op that shared tree applies to everyone
 * there. No settlement: gated recipes are not craftable. Workshops check the
 * settlement that owns the building.
 */
public final class TechCraftGate {
    /** Blocks from a Banner within which a player outside the borders still crafts for it. */
    public static final int PLAYER_RADIUS = 160;
    /** The autocrafter being evaluated on this thread (set by the Crafter mixins), or null. */
    public static final ThreadLocal<net.minecraft.core.BlockPos> CRAFTER_CONTEXT = new ThreadLocal<>();

    private TechCraftGate() {
    }

    /** The settlement a player crafts for, or null. */
    @Nullable
    public static Settlement settlementFor(ServerLevel level, Player player) {
        return settlementAt(level, player.blockPosition());
    }

    /** The settlement whose claim holds {@code pos}, else the nearest Banner within the radius. */
    @Nullable
    public static Settlement settlementAt(ServerLevel level, net.minecraft.core.BlockPos pos) {
        // The claim with the nearest Banner wins (claims may overlap in
        // GameTest plots; SettlementManager.at returns the first match).
        Settlement inside = null;
        double insideD = Double.MAX_VALUE;
        for (Settlement s : SettlementSavedData.get(level).settlements.values()) {
            double d = pos.distSqr(s.center);
            if (s.inside(pos) && d < insideD) {
                insideD = d;
                inside = s;
            }
        }
        if (inside != null) {
            return inside;
        }
        Settlement best = null;
        double bestD = (double) PLAYER_RADIUS * PLAYER_RADIUS;
        for (Settlement s : SettlementSavedData.get(level).settlements.values()) {
            double d = pos.distSqr(s.center);
            if (d <= bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    /** May this player craft this recipe right now? */
    public static boolean allowed(ServerLevel level, @Nullable Player player,
                                  @Nullable ResourceLocation recipeId, @Nullable Item output) {
        if (!TechTreeConfig.gateCrafting()) {
            return true;
        }
        List<String> nodes = TechRecipeGates.nodesFor(recipeId, output);
        if (nodes.isEmpty()) {
            return true;
        }
        Settlement settlement = player == null ? null : settlementFor(level, player);
        return unlocked(level, settlement, recipeId, nodes);
    }

    /** May an autocrafter at {@code pos} make this recipe? */
    public static boolean allowedAt(ServerLevel level, net.minecraft.core.BlockPos pos,
                                    @Nullable ResourceLocation recipeId, @Nullable Item output) {
        if (!TechTreeConfig.gateCrafting()) {
            return true;
        }
        List<String> nodes = TechRecipeGates.nodesFor(recipeId, output);
        return nodes.isEmpty() || unlocked(level, settlementAt(level, pos), recipeId, nodes);
    }

    /** May this settlement's workshop produce {@code output}? */
    public static boolean workshopAllowed(ServerLevel level, Building building, Item output) {
        if (!TechTreeConfig.gateCrafting()) {
            return true;
        }
        List<String> nodes = TechRecipeGates.workshopNodesFor(output);
        if (nodes.isEmpty()) {
            return true;
        }
        // The building's own node already stood behind raising it (a Mill's
        // flour, a Weaver's bolts): only a different node needs checking.
        String own = building.type == null ? null : TechRecipeGates.nodeForBuilding(building.type);
        if (own != null && nodes.contains(own)) {
            return true;
        }
        Settlement owner = null;
        for (Settlement s : SettlementSavedData.get(level).settlements.values()) {
            if (s.buildings.contains(building)) {
                owner = s;
                break;
            }
        }
        return unlocked(level, owner, null, nodes);
    }

    static boolean unlocked(ServerLevel level, @Nullable Settlement settlement,
                            @Nullable ResourceLocation recipeId, List<String> nodes) {
        if (settlement == null) {
            return false;
        }
        BuildingType plan = TechRecipeGates.buildingFor(recipeId);
        if (plan != null) {
            return Development.isBuildingUnlocked(level, settlement, plan);
        }
        for (String node : nodes) {
            if (TechTree.has(level, settlement, node)) {
                return true;
            }
        }
        return false;
    }
}
