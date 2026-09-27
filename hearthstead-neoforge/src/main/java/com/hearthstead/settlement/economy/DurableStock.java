package com.hearthstead.settlement.economy;

import com.hearthstead.building.BuildingType;
import com.hearthstead.building.Production;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.builder.BuilderStock;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * [economy] durable goods are made to demand, not to a pile
 * (plan/ECONOMY.md, reliability-lane heads-up 26 Sep).
 *
 * <p>Finished tools, weapons and armour leave a workshop with a keep-back of
 * 0, so every such output ties at zero bench stock and the need-aware
 * selector always picked the FIRST listed one: an idle smithy turned every
 * iron ingot into axes nobody asked for, starving the armoury, the tech
 * nodes and the upgrades that need iron. Rule: a recipe whose output is
 * damageable (a tool, weapon or armour piece) runs without an order only
 * while the settlement holds fewer than {@code toolStockTarget} of that item
 * (Warehouse + this workshop). An open crafting order for that item always
 * runs. Intermediates (ingots, leather, bolts) are never gated.
 *
 * <p>Neutral (never gates) on the GameTest server unless a test opts in.
 */
public final class DurableStock {

    private DurableStock() {
    }

    /** Whether {@code recipe} may run at {@code building} right now. */
    public static boolean allows(ServerLevel level, Building building, Production.Recipe recipe,
                                 @Nullable Item preferred) {
        int target = EconomyConfig.toolStockTarget(level.getServer());
        if (target <= 0 || recipe == null) {
            return true;
        }
        Item output = recipe.output();
        if (output == preferred || !new ItemStack(output).isDamageableItem()) {
            return true;
        }
        Settlement owner = ownerOf(level, building);
        if (owner == null) {
            return true;
        }
        int held = BuilderStock.warehouseCount(level, owner, output)
            + BuilderStock.count(BuilderStock.hutContainers(level, building), output);
        return held < target;
    }

    @Nullable
    private static Settlement ownerOf(ServerLevel level, Building building) {
        if (building == null || building.type == BuildingType.WAREHOUSE) {
            return null;
        }
        for (Settlement s : SettlementSavedData.get(level).settlements.values()) {
            if (s.buildings.contains(building)) {
                return s;
            }
        }
        return null;
    }
}
