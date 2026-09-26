package com.hearthstead.settlement.journey;

import com.hearthstead.entity.Profession;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JourneyEmblemProvenanceTest {

    @Test
    void physicalStackCopyRetainsTheExactServerSaleIdentity() {
        ItemStack emblem = new ItemStack(Items.PAPER);
        UUID settlement = JourneyStateTest.uuid(31);
        UUID transaction = JourneyStateTest.uuid(32);
        CustomData.update(DataComponents.CUSTOM_DATA, emblem,
            tag -> tag.putString("UnrelatedOwnerData", "preserved"));

        assertTrue(JourneyEmblemProvenance.stamp(emblem, settlement,
            transaction, Profession.LUMBERER));
        JourneyEmblemProvenance.Provenance decoded =
            JourneyEmblemProvenance.read(emblem.copy()).orElseThrow();

        assertEquals(settlement, decoded.settlementId());
        assertEquals(transaction, decoded.transactionId());
        assertEquals(Profession.LUMBERER, decoded.profession());
        assertEquals("preserved", emblem.get(DataComponents.CUSTOM_DATA)
            .copyTag().getString("UnrelatedOwnerData"));
    }

    @Test
    void nilOrMalformedIdentityNeverBecomesBindingAuthority() {
        ItemStack emblem = new ItemStack(Items.PAPER);
        UUID settlement = JourneyStateTest.uuid(41);
        assertFalse(JourneyEmblemProvenance.stamp(emblem,
            new UUID(0L, 0L), JourneyStateTest.uuid(42), Profession.GUARD));
        assertFalse(JourneyEmblemProvenance.stamp(emblem, settlement,
            new UUID(0L, 0L), Profession.GUARD));
        assertFalse(JourneyEmblemProvenance.stamp(emblem, settlement,
            JourneyStateTest.uuid(43), Profession.NONE));
        assertTrue(JourneyEmblemProvenance.read(emblem).isEmpty());

        CustomData.update(DataComponents.CUSTOM_DATA, emblem, root -> {
            var forged = new net.minecraft.nbt.CompoundTag();
            forged.putInt("DataVersion", 99);
            forged.putUUID("Settlement", settlement);
            forged.putUUID("Transaction", JourneyStateTest.uuid(44));
            forged.putString("Profession", Profession.GUARD.key());
            root.put("HearthsteadJourneyEmblemV1", forged);
        });
        assertTrue(JourneyEmblemProvenance.read(emblem).isEmpty());
    }
}
