package com.hearthstead.settlement.development;

import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostRaidUpgradeContractTest {
    @Test
    void wireIdsAndSavedIdsStayStableAndUnique() {
        String[] ids = {"courier_satchel", "hand_cart", "worker_packs", "guard_arms_iron",
            "archer_longbow_drill", "warm_hearth", "sturdy_beds", "feather_quilts",
            "sharpened_axes", "fishers_nets", "stout_straps", "guard_drill",
            "leather_pack", "frame_pack", "paved_roads", "swift_couriers",
            "warehouse_racks", "great_storehouse", "royal_storehouse",
            // Builder lane (plan/BUILDER.md): wire 19, 20.
            "defense_plans", "masonry"};
        Set<String> seen = new HashSet<>();
        assertEquals(ids.length, PostRaidUpgrade.values().length);
        for (int wireId = 0; wireId < ids.length; wireId++) {
            PostRaidUpgrade upgrade = PostRaidUpgrade.byWireId(wireId);
            assertEquals(ids[wireId], upgrade.id());
            assertEquals(wireId, upgrade.wireId());
            assertSame(upgrade, PostRaidUpgrade.byId(ids[wireId]));
            assertTrue(seen.add(upgrade.id()));
        }
        assertNull(PostRaidUpgrade.byWireId(-1));
        assertNull(PostRaidUpgrade.byWireId(ids.length));
        assertNull(PostRaidUpgrade.byId("unknown"));
    }

    @Test
    void legacyCoinPricesAndNewPhysicalRequirementsStayExplicit() {
        int[] coins = {4, 8, 6, 8, 6, 3, 4, 6, 2, 3, 2, 3, 6, 10, 4, 4, 5, 12, 24, 3, 6};
        // Hand Cart became a real cart (planks, logs, iron); the sack tiers
        // cost leather, wool, string, sticks and iron.
        int[] materials = {0, 3, 0, 0, 0, 2, 2, 2, 2, 2, 2, 2, 3, 4, 2, 2, 2, 2, 3, 2, 2};
        for (int wireId = 0; wireId < coins.length; wireId++) {
            PostRaidUpgrade upgrade = PostRaidUpgrade.byWireId(wireId);
            assertEquals(coins[wireId], upgrade.coinCost(), upgrade.id());
            assertEquals(materials[wireId], upgrade.materialCosts().size(), upgrade.id());
        }
        assertSame(PostRaidUpgrade.COURIER_SATCHEL, PostRaidUpgrade.LEATHER_PACK.requiresUpgrade());
        assertSame(PostRaidUpgrade.LEATHER_PACK, PostRaidUpgrade.FRAME_PACK.requiresUpgrade());
        assertSame(PostRaidUpgrade.STOUT_STRAPS, PostRaidUpgrade.SWIFT_COURIERS.requiresUpgrade());
        assertNull(PostRaidUpgrade.WAREHOUSE_RACKS.requiresUpgrade());
        assertSame(PostRaidUpgrade.WAREHOUSE_RACKS, PostRaidUpgrade.GREAT_STOREHOUSE.requiresUpgrade());
        assertSame(PostRaidUpgrade.GREAT_STOREHOUSE, PostRaidUpgrade.ROYAL_STOREHOUSE.requiresUpgrade());
        assertNull(PostRaidUpgrade.DEFENSE_PLANS.requiresUpgrade());
        assertSame(PostRaidUpgrade.DEFENSE_PLANS, PostRaidUpgrade.MASONRY.requiresUpgrade());
        assertSame(PostRaidUpgrade.COURIER_SATCHEL, PostRaidUpgrade.HAND_CART.requiresUpgrade());
        assertSame(PostRaidUpgrade.WARM_HEARTH, PostRaidUpgrade.STURDY_BEDS.requiresUpgrade());
        var warmHearth = PostRaidUpgrade.WARM_HEARTH.materialCosts();
        assertSame(Items.COBBLESTONE, warmHearth.get(0).item());
        assertEquals(16, warmHearth.get(0).count());
        assertEquals(8, warmHearth.get(1).count());
    }
}
