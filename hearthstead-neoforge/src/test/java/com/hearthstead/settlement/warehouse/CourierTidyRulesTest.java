package com.hearthstead.settlement.warehouse;

import com.hearthstead.building.BuildingType;
import com.hearthstead.settlement.Building;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Courier tidying and Warehouse staff (owner, 26 Sep): the one-way merge
 * rule that keeps two same-group chests from trading stacks for ever, and
 * the Courier posts per Warehouse level (L1 = 2, L2 and up = 4).
 */
class CourierTidyRulesTest {

    @Test
    void mergeOnlyFlowsTowardTheChestHoldingMore() {
        assertTrue(WarehouseSorting.mergeInto(10, 5L, 30, 9L), "toward the fuller chest");
        assertFalse(WarehouseSorting.mergeInto(30, 9L, 10, 5L), "never away from it");
        assertTrue(WarehouseSorting.mergeInto(20, 9L, 20, 5L), "a tie goes to the lower position");
        assertFalse(WarehouseSorting.mergeInto(20, 5L, 20, 9L), "and never back");
        // Antisymmetric: for two different chests exactly one direction is allowed.
        for (int a = 0; a <= 64; a += 8) {
            for (int b = 0; b <= 64; b += 8) {
                boolean ab = WarehouseSorting.mergeInto(a, 1L, b, 2L);
                boolean ba = WarehouseSorting.mergeInto(b, 2L, a, 1L);
                assertTrue(ab != ba, "exactly one direction for " + a + " vs " + b);
            }
        }
    }

    @Test
    void warehouseCourierPostsFollowItsLevel() {
        assertEquals(2, WarehouseLevels.courierSlots(1), "level 1 keeps the old two Couriers");
        assertEquals(4, WarehouseLevels.courierSlots(2));
        assertEquals(4, WarehouseLevels.courierSlots(3));
        assertEquals(4, WarehouseLevels.courierSlots(5));
        assertEquals(2, WarehouseLevels.courierSlots(0), "an unsurveyed room counts as level 1");
        Building warehouse = building(BuildingType.WAREHOUSE);
        warehouse.level = 1;
        assertEquals(2, warehouse.workerCapacity());
        warehouse.level = 2;
        assertEquals(4, warehouse.workerCapacity());
        assertEquals(4, Building.maxWorkerCapacity(BuildingType.WAREHOUSE),
            "roster checks allow the most a Warehouse can ever hold");
    }

    @Test
    void everyOtherBuildingKeepsItsFixedStaff() {
        for (BuildingType type : BuildingType.values()) {
            if (type == BuildingType.WAREHOUSE || type == BuildingType.LUMBER_CAMP
                || type == BuildingType.FARMHOUSE) {
                continue;
            }
            Building b = building(type);
            b.level = 3;
            assertEquals(type.workerCapacity(), b.workerCapacity(), type.id());
            assertEquals(type.workerCapacity(), Building.maxWorkerCapacity(type), type.id());
        }
    }

    /** Owner, 26 Sep: Lumber Camp and Farmhouse take a third worker from level 2. */
    @Test
    void lumberCampAndFarmhouseTakeOneMoreWorkerFromLevelTwo() {
        for (BuildingType type : new BuildingType[]{BuildingType.LUMBER_CAMP, BuildingType.FARMHOUSE}) {
            Building b = building(type);
            b.level = 1;
            assertEquals(2, b.workerCapacity(), type.id() + " L1");
            b.level = 2;
            assertEquals(3, b.workerCapacity(), type.id() + " L2");
            b.level = 3;
            assertEquals(3, b.workerCapacity(), type.id() + " L3");
            assertEquals(3, Building.maxWorkerCapacity(type), type.id() + " max");
        }
    }

    private static Building building(BuildingType type) {
        BlockPos anchor = new BlockPos(0, 64, 0);
        return new Building(UUID.randomUUID(), type, anchor.above(), anchor,
            BoundingBox.fromCorners(anchor, anchor.offset(4, 3, 4)));
    }
}
