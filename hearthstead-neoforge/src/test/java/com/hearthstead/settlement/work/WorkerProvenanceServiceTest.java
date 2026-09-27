package com.hearthstead.settlement.work;

import com.hearthstead.settlement.equipment.EquipmentRequirement;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerProvenanceServiceTest {

    @Test
    void activeActionMaySpendStartReserveButNeverBreakItsTool() {
        EquipmentRequirement axe = new EquipmentRequirement(
            Items.IRON_AXE, null, 8);
        ItemStack belowStartReserve = new ItemStack(Items.IRON_AXE);
        belowStartReserve.setDamageValue(belowStartReserve.getMaxDamage() - 7);

        assertFalse(axe.serviceable(belowStartReserve),
            "seven uses remaining must not admit a new work action");
        assertTrue(WorkerProvenanceService.activeOperationCanUseTool(
                axe, belowStartReserve),
            "an already-authorised action may finish with the physical reserve");

        ItemStack breakingUse = new ItemStack(Items.IRON_AXE);
        breakingUse.setDamageValue(breakingUse.getMaxDamage() - 1);
        assertFalse(WorkerProvenanceService.activeOperationCanUseTool(
                axe, breakingUse),
            "the active action must stop before destroying its receipted tool");
        assertFalse(WorkerProvenanceService.activeOperationCanUseTool(
                axe, new ItemStack(Items.STICK)),
            "an active action never authorises a wrong tool");
    }
}
