package com.hearthstead.event;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class FisherDemandTest {
    @Test void rareFishDemandSurvivesReloadWithoutAnotherVisit() {
        var cod = ResourceLocation.withDefaultNamespace("cod");
        var salmon = ResourceLocation.withDefaultNamespace("salmon");
        assertTrue(MerchantDemandSavedData.supports(cod));
        assertTrue(MerchantDemandSavedData.supports(salmon));
        var trout = ResourceLocation.parse("hearthstead:brown_trout");
        var pike = ResourceLocation.parse("hearthstead:silver_pike");
        var charFish = ResourceLocation.parse("hearthstead:golden_char");
        assertTrue(MerchantDemandSavedData.supports(trout));
        assertTrue(MerchantDemandSavedData.supports(pike));
        assertTrue(MerchantDemandSavedData.supports(charFish));
        assertFalse(MerchantDemandSavedData.supports(ResourceLocation.parse("hearthstead:river_perch")));
        var id = UUID.randomUUID();
        var data = new MerchantDemandSavedData();
        var first = data.beginVisit(id, List.of(trout, pike, charFish), 100, 3);
        assertNotNull(first);
        assertEquals(0, first.sequence());
        var restored = MerchantDemandSavedData.load(data.save(new CompoundTag(), null), null);
        assertTrue(restored.hasVisit(id));
        var second = restored.beginVisit(id, List.of(trout, pike, charFish), 24100, 3);
        assertNotNull(second);
        assertEquals(1, second.sequence());
        assertEquals(3, second.wanted().size());
    }
}
