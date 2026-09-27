package com.hearthstead.settlement.development;

import com.hearthstead.logistics.Weight;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure numbers of the logistics branch: sack tiers, cart, weight, speed. */
class HaulGearTest {
    @Test
    void sackTiersAndCartRaiseTheCourierBudget() {
        assertEquals(8, HaulGear.courierCapacity(0, false));
        assertEquals(12, HaulGear.courierCapacity(1, false));
        assertEquals(16, HaulGear.courierCapacity(2, false));
        assertEquals(20, HaulGear.courierCapacity(3, false));
        assertEquals(28, HaulGear.courierCapacity(1, true));
        assertEquals(36, HaulGear.courierCapacity(3, true));
        // A cart without a sack tier (quarantined save shape) grants nothing.
        assertEquals(8, HaulGear.courierCapacity(0, true));
        assertEquals(12, CourierSatchel.satchelCapacity());
        assertEquals(28, CourierSatchel.handCartCapacity());
    }

    @Test
    void workerPacksFollowTheSackTierButNeverDropBelowTheSatchel() {
        assertEquals(12, WorkerPacks.packCapacity(8, 0));
        assertEquals(12, WorkerPacks.packCapacity(8, 1));
        assertEquals(16, WorkerPacks.packCapacity(8, 2));
        assertEquals(20, WorkerPacks.packCapacity(8, 3));
        assertEquals(WorkerPacks.packCapacity(10), WorkerPacks.packCapacity(10, 1));
    }

    @Test
    void weightBudgetScalesWithCapacityAndNeverShrinks() {
        assertEquals(Weight.BAG_BUDGET, Weight.budgetFor(8));
        assertEquals(Weight.BAG_BUDGET, Weight.budgetFor(3));
        assertEquals(40, Weight.budgetFor(20));
        assertEquals(56, Weight.budgetFor(28));
    }

    @Test
    void gearPacksAndUnpacksForTheClient() {
        for (int tier = 0; tier <= HaulGear.MAX_TIER; tier++) {
            for (boolean cart : new boolean[] {false, true}) {
                int packed = HaulGear.pack(tier, cart);
                assertEquals(tier, HaulGear.unpackTier(packed));
                assertEquals(cart, HaulGear.unpackCart(packed));
            }
        }
        assertEquals(0, HaulGear.pack(0, false));
        assertEquals(1.0F, HaulGear.visualScale(0));
        assertTrue(HaulGear.visualScale(3) > HaulGear.visualScale(2));
        assertEquals("frame_pack", HaulGear.tierKey(3));
    }

    @Test
    void terrainSpeedIsNeutralWithoutUpgrades() {
        assertEquals(0, HaulGear.terrainPercent(true, false, false, false, false));
        assertEquals(0, HaulGear.terrainPercent(false, true, true, false, false));
        assertEquals(15, HaulGear.terrainPercent(true, true, false, false, false));
        assertEquals(10, HaulGear.terrainPercent(false, false, true, true, false));
        // Cart: +10 on a road, -15 on rough ground.
        assertEquals(10, HaulGear.terrainPercent(true, false, true, false, true));
        assertEquals(-15, HaulGear.terrainPercent(false, false, true, false, true));
        assertEquals(35, HaulGear.terrainPercent(true, true, true, true, true));
        assertFalse(HaulGear.terrainPercent(false, true, true, true, true) > 0);
    }
}
