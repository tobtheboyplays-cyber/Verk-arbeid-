package com.hearthstead.settlement.work;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorkerStackProvenanceTest {

    @Test
    void transitRoundTripStripsOnlyWorkerRowAndRestoresNormalStacking() {
        ItemStack log = new ItemStack(Items.OAK_LOG, 4);
        CustomData.update(DataComponents.CUSTOM_DATA, log,
            root -> root.putString("Foreign", "preserved"));
        UUID action = UUID.randomUUID();
        UUID settlement = UUID.randomUUID();
        UUID building = UUID.randomUUID();
        UUID worker = UUID.randomUUID();
        ResourceLocation dimension = ResourceLocation.withDefaultNamespace(
            "overworld");

        assertTrue(WorkerStackProvenance.stampTransit(log, action,
            WorkerStackProvenance.TransitKind.LUMBER_LOG, settlement,
            building, worker, dimension, 123L));
        var decoded = WorkerStackProvenance.readTransit(log).orElseThrow();
        assertEquals(action, decoded.actionId());
        assertEquals(building, decoded.buildingId());
        assertEquals(123L, decoded.sourcePos());

        assertTrue(WorkerStackProvenance.clearTransit(log));
        assertTrue(WorkerStackProvenance.readTransit(log).isEmpty());
        assertEquals("preserved", log.get(DataComponents.CUSTOM_DATA)
            .copyTag().getString("Foreign"));
    }

    @Test
    void transitRemovalDropsEmptyCustomComponentSoPlainGoodsCanStack() {
        ItemStack stamped = new ItemStack(Items.WHEAT, 2);
        ItemStack plain = new ItemStack(Items.WHEAT, 2);
        assertTrue(WorkerStackProvenance.stampTransit(stamped,
            UUID.randomUUID(), WorkerStackProvenance.TransitKind.FARM_CROP,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            ResourceLocation.withDefaultNamespace("overworld"), 1L));
        assertFalse(ItemStack.isSameItemSameComponents(stamped, plain));
        assertTrue(WorkerStackProvenance.clearTransit(stamped));
        assertTrue(ItemStack.isSameItemSameComponents(stamped, plain));
    }

    @Test
    void malformedAndNilTransitFailClosed() {
        ItemStack crop = new ItemStack(Items.CARROT);
        assertFalse(WorkerStackProvenance.stampTransit(crop,
            new UUID(0L, 0L), WorkerStackProvenance.TransitKind.FARM_CROP,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            ResourceLocation.withDefaultNamespace("overworld"), 0L));
        CompoundTag malformed = new CompoundTag();
        malformed.putString("Kind", "FARM_CROP");
        CustomData.update(DataComponents.CUSTOM_DATA, crop,
            root -> root.put("HearthsteadWorkerTransitV1", malformed));
        assertTrue(WorkerStackProvenance.hasTransitMarker(crop));
        assertTrue(WorkerStackProvenance.readTransit(crop).isEmpty());
        assertFalse(WorkerStackProvenance.clearTransit(crop));
        assertFalse(WorkerStackProvenance.stampTransit(crop,
            UUID.randomUUID(), WorkerStackProvenance.TransitKind.FARM_CROP,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            ResourceLocation.withDefaultNamespace("overworld"), 0L),
            "malformed ownership may never be overwritten into valid authority");
    }

    @Test
    void toolReceiptPinsExactActionOperationAndOneDamagePoint() {
        ItemStack axe = new ItemStack(Items.IRON_AXE);
        UUID action = UUID.randomUUID();
        ResourceLocation item = ResourceLocation.withDefaultNamespace(
            "iron_axe");
        assertTrue(WorkerStackProvenance.stampToolUse(axe, action, 42L,
            item, 7, 8));
        var receipt = WorkerStackProvenance.readToolUse(axe).orElseThrow();
        assertEquals(action, receipt.actionId());
        assertEquals(42L, receipt.operationPos());
        assertEquals(7, receipt.damageBefore());
        assertEquals(8, receipt.damageAfter());
        assertTrue(WorkerStackProvenance.hasToolUseMarker(axe));
        assertFalse(WorkerStackProvenance.stampToolUse(axe, action, 43L,
            item, 8, 9), "an existing physical receipt may not be overwritten");
        assertFalse(WorkerStackProvenance.stampToolUse(axe, action, 43L,
            item, 8, 10));
        assertFalse(WorkerStackProvenance.clearToolUse(axe, action, 43L));
        assertTrue(WorkerStackProvenance.clearToolUse(axe, action, 42L));
        assertTrue(WorkerStackProvenance.readToolUse(axe).isEmpty());
        assertNull(axe.get(DataComponents.CUSTOM_DATA),
            "terminal tool receipt must not leave an empty custom component");
    }

    @Test
    void malformedToolOwnershipCannotBeLaundered() {
        ItemStack hoe = new ItemStack(Items.IRON_HOE);
        CompoundTag malformed = new CompoundTag();
        malformed.putInt("DataVersion", 1);
        CustomData.update(DataComponents.CUSTOM_DATA, hoe,
            root -> root.put("HearthsteadWorkerToolUseV1", malformed));
        assertTrue(WorkerStackProvenance.hasToolUseMarker(hoe));
        assertTrue(WorkerStackProvenance.readToolUse(hoe).isEmpty());
        assertFalse(WorkerStackProvenance.stampToolUse(hoe, UUID.randomUUID(),
            1L, ResourceLocation.withDefaultNamespace("iron_hoe"), 0, 1));
    }
}
