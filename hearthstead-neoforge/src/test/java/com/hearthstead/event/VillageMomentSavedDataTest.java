package com.hearthstead.event;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class VillageMomentSavedDataTest {
    @Test
    void oneClaimConsumesTheOnlyMomentSlotUntilTheSavedCooldownEnds() {
        VillageMomentSavedData data = new VillageMomentSavedData();
        UUID settlement = UUID.randomUUID();

        assertTrue(data.claim(settlement, 100L));
        assertFalse(data.claim(settlement, 12_099L));
        assertTrue(data.claim(settlement, 12_100L));
    }

    @Test
    void aReloadRetainsTheCooldownInsteadOfReplayingTheMoment() {
        VillageMomentSavedData data = new VillageMomentSavedData();
        UUID settlement = UUID.randomUUID();
        assertTrue(data.claim(settlement, 400L));

        VillageMomentSavedData restored = VillageMomentSavedData.load(
            data.save(new CompoundTag(), null), null);
        assertFalse(restored.claim(settlement, 12_399L));
        assertTrue(restored.claim(settlement, 12_400L));
    }

    @Test
    void malformedSavedStateFailsClosedRatherThanStartingAFreshBurst() {
        CompoundTag invalid = new CompoundTag();
        invalid.putInt("Version", 99);
        VillageMomentSavedData restored = VillageMomentSavedData.load(invalid, null);

        assertFalse(restored.claim(UUID.randomUUID(), 0L));
    }
}
