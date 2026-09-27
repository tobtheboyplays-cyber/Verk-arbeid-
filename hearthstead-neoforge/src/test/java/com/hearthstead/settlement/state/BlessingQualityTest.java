package com.hearthstead.settlement.state;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlessingQualityTest {
    @Test
    void rulesOneGoldenVectorsAndRoundTripAreIndependentOfViewerAndRevision() {
        var policy = new BlessingQualityPolicy(0x123456789ABCDEFL, 1, 0);
        assertEquals(BlessingQuality.COMMON, policy.qualityFor(1, BlessingId.HEARTHWARD));
        assertEquals(BlessingQuality.RARE, policy.qualityFor(10, BlessingId.HEARTHWARD));
        assertEquals(BlessingQuality.COMMON, policy.qualityFor(10, BlessingId.WARDEN_OATH));
        assertEquals(BlessingQuality.RARE, policy.qualityFor(100, BlessingId.WARDEN_OATH));
        assertEquals(BlessingQuality.RARE, policy.qualityFor(100, BlessingId.HEARTHWARD));
        assertEquals(BlessingQuality.COMMON, policy.qualityFor(100, BlessingId.THORNED_ROADS));
        var state = new BlessingState();
        for (int i = 0; i < 10; i++) assertTrue(state.grantOffer());
        CompoundTag tag = state.writeNbt();
        tag.put("QualityPolicy", policy.writeNbt());
        state = BlessingState.readNbt(tag);
        assertFalse(state.quarantined());
        assertEquals(BlessingQuality.RARE, state.qualityFor(10, BlessingId.HEARTHWARD));
        assertTrue(state.grantOffer());
        var reload = BlessingState.readNbt(state.writeNbt());
        assertEquals(state.writeNbt(), reload.writeNbt());
        for (BlessingId id : BlessingId.values()) {
            assertEquals(policy.qualityFor(10, id), reload.qualityFor(10, id));
        }
    }

    @Test
    void allLegacyEarnedOffersRemainCommonAndMalformedPolicyNeverRerolls() {
        var original = new BlessingState();
        for (int i = 0; i < 12; i++) original.grantOffer();
        CompoundTag legacy = original.writeNbt();
        legacy.putInt("DataVersion", 3);
        legacy.remove("QualityPolicy");
        var migrated = BlessingState.readNbt(legacy);
        assertFalse(migrated.quarantined());
        assertEquals(12, migrated.writeNbt().getCompound("QualityPolicy").getInt("LegacyThroughSerial"));
        for (int serial = 1; serial <= 12; serial++) for (BlessingId id : BlessingId.values()) {
            assertEquals(BlessingQuality.COMMON, migrated.qualityFor(serial, id));
        }
        assertEquals(migrated.writeNbt(), BlessingState.readNbt(migrated.writeNbt()).writeNbt());
        CompoundTag corrupt = migrated.writeNbt();
        corrupt.getCompound("QualityPolicy").putInt("RulesVersion", 2);
        var rejected = BlessingState.readNbt(corrupt);
        assertTrue(rejected.quarantined());
        assertFalse(rejected.grantOffer());
        assertThrows(IllegalStateException.class, () -> rejected.qualityFor(1, BlessingId.WARDEN_OATH));
        corrupt = migrated.writeNbt();
        corrupt.remove("QualityPolicy");
        assertTrue(BlessingState.readNbt(corrupt).quarantined());
        corrupt = migrated.writeNbt();
        corrupt.getCompound("QualityPolicy").putInt("LegacyThroughSerial", 13);
        assertTrue(BlessingState.readNbt(corrupt).quarantined());
    }

    @Test
    void rareUnitsApplyAtomicallyAndInsufficientCapacityKeepsExactLedger() {
        for (BlessingId id : BlessingId.values()) {
            var state = new TargetBlessingState();
            assertEquals(TargetBlessingState.ApplyResult.APPLIED, state.apply(id, 2));
            assertEquals(2, state.rank(id));
            CompoundTag before = state.writeNbt();
            assertEquals(TargetBlessingState.ApplyResult.INSUFFICIENT_CAPACITY, state.apply(id, 2));
            assertEquals(before, state.writeNbt());
            assertEquals(TargetBlessingState.ApplyResult.INVALID, state.apply(id, 3));
            assertEquals(before, state.writeNbt());
            assertEquals(TargetBlessingState.ApplyResult.APPLIED, state.apply(id));
            assertEquals(3, state.rank(id));
            var one = new TargetBlessingState();
            one.apply(id);
            assertEquals(TargetBlessingState.ApplyResult.APPLIED, one.apply(id, 2));
            assertEquals(3, one.rank(id));
        }
    }
}
