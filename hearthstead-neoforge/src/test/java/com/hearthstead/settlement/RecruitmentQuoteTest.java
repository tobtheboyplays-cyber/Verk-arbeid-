package com.hearthstead.settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RecruitmentQuoteTest {
    private static final UUID TX = new UUID(1, 2), GUEST = new UUID(3, 4), SETTLEMENT = new UUID(5, 6);
    private static final List<Costs.Discount> DISCOUNT = List.of(new Costs.Discount("fixture", 25, "fixture"));

    @Test void thresholdsUseActualStrongestTwoWithStableTieBreakAndDiscountOnce() {
        var base = quote(12, 11, 0);
        var middle = quote(12, 12, 0);
        var high = quote(15, 13, 25);
        assertEquals(0, base.premium()); assertEquals(4, base.coins()); assertEquals(0, base.bread()); assertEquals(0, base.planks());
        assertEquals(1, middle.premium()); assertEquals(6, middle.coins());
        assertEquals(2, high.premium()); assertEquals(Costs.discounted(8, 25), high.coins());
        assertEquals(0, high.bread()); assertEquals(0, high.planks());
        assertEquals(0, middle.firstAttribute()); assertEquals(1, middle.secondAttribute());
        assertSame(high, high.freezeLegacy(List.of(new Costs.Discount("changed", 50, "changed"))));
        assertEquals(high, RecruitmentQuote.readStrict(high.writeNbt()));
        assertThrows(IllegalArgumentException.class,
            () -> RecruitmentQuote.fromValues(TX, GUEST, new int[]{16, 1, 1, 1, 1, 1, 1, 1}, 0));
    }

    @Test void exactQuoteSurvivesTravelArrivalAdmissionAndRestartWithoutRepricing() {
        var committed = quote(15, 14, 25);
        var traveling = ready().travelerSpawned(GUEST, "Guest", 200, committed);
        var waiting = reload(traveling).arrived(240);
        assertEquals(committed, waiting.quote());
        assertSame(waiting, waiting.freezeLegacyQuote(List.of()));
        var receipt = new RecruitmentTransaction.AdmissionReceipt(new UUID(7, 8), "fixture:payment",
            committed.coins(), 100, 200);
        var admitted = reload(waiting).admitted(receipt);
        assertEquals(committed, reload(admitted).quote());
        assertNull(admitted.nextCycle(SETTLEMENT).quote());
        assertEquals(committed, reload(waiting.left(RecruitmentTransaction.TerminalReason.PLAYER_REJECTED)).quote());
    }

    @Test void schemaOneGuestMigratesLegacyBaseOnlyOnceWithoutReroll() {
        var original = ready().travelerSpawned(GUEST, "Guest", 200, quote(15, 15, 0)).arrived(240);
        CompoundTag legacy = original.writeNbt(); legacy.putInt("SchemaVersion", 1); legacy.remove("Quote");
        // Use an exact timing target that existed in schema v1; new ordinary ranges did not.
        legacy.remove("TimingProfileWireId");
        legacy.putInt("LockedTarget", com.hearthstead.settlement.RecruitmentPolicy.callToArmsTargetFor(SETTLEMENT, legacy.getInt("Cycle")));
        var migrated = RecruitmentTransaction.readOrQuarantine(legacy, SETTLEMENT);
        assertTrue(migrated.quote().legacyPending());
        var frozen = migrated.freezeLegacyQuote(DISCOUNT);
        assertFalse(frozen.quote().legacyPending()); assertEquals(0, frozen.quote().version());
        assertEquals(3, frozen.quote().bread()); assertEquals(6, frozen.quote().planks());
        assertEquals(original.travelerId(), frozen.travelerId());
        assertEquals(original.travelerName(), frozen.travelerName());
        assertEquals(original.revision(), frozen.revision());
        assertSame(frozen, frozen.freezeLegacyQuote(List.of()));
        assertEquals(frozen, reload(frozen));
    }

    @Test void newMissingMalformedOrCrossTripQuoteQuarantinesInsteadOfRepricing() {
        CompoundTag tag = ready().travelerSpawned(GUEST, "Guest", 200, quote(15, 14, 0)).writeNbt();
        CompoundTag missing = tag.copy(); missing.remove("Quote");
        assertQuarantined(missing);
        CompoundTag wrongCost = tag.copy(); wrongCost.getCompound("Quote").putInt("Bread", 1);
        assertQuarantined(wrongCost);
        CompoundTag wrongTraveler = tag.copy(); wrongTraveler.getCompound("Quote").putUUID("Traveler", UUID.randomUUID());
        assertQuarantined(wrongTraveler);
        CompoundTag wrongVersion = tag.copy(); wrongVersion.getCompound("Quote").putInt("Version", 3);
        assertQuarantined(wrongVersion);
        CompoundTag wrongType = tag.copy(); wrongType.putString("Quote", "invalid");
        assertQuarantined(wrongType);
    }

    @Test void savedVersionOneBarterQuoteRemainsExactlyBarter() {
        var old = new RecruitmentQuote(1, TX, GUEST, 0, 15, 1, 14, 2, 25, 4, 9, false);
        assertEquals(old, RecruitmentQuote.readStrict(old.writeNbt()));
        assertEquals(4, old.bread()); assertEquals(9, old.planks()); assertEquals(0, old.coins());
        assertSame(old, old.freezeLegacy(DISCOUNT));
        CompoundTag contaminated=old.writeNbt(); contaminated.putInt("Coins",4);
        assertThrows(IllegalArgumentException.class,()->RecruitmentQuote.readStrict(contaminated));
    }
    @Test void coinQuoteRejectsMissingAndContradictoryAmount() {
        CompoundTag missing=quote(12,12,0).writeNbt(); missing.remove("Coins");
        assertThrows(IllegalArgumentException.class,()->RecruitmentQuote.readStrict(missing));
        CompoundTag forged=quote(12,12,0).writeNbt(); forged.putInt("Coins",1);
        assertThrows(IllegalArgumentException.class,()->RecruitmentQuote.readStrict(forged));
    }

    private static RecruitmentQuote quote(int first, int second, int discount) {
        return RecruitmentQuote.fromValues(TX, GUEST, new int[]{first, second, 1, 1, 1, 1, 1, 1}, discount);
    }
    private static RecruitmentTransaction ready() {
        return RecruitmentTransaction.fresh(SETTLEMENT).beginQualification(TX, 100,
            new UUID(9, 10), new BlockPos(1, 64, 1), new BlockPos(2, 64, 2),
            ResourceLocation.fromNamespaceAndPath("minecraft", "overworld")).readyForSpawnForTest();
    }
    private static RecruitmentTransaction reload(RecruitmentTransaction transaction) {
        return RecruitmentTransaction.readOrQuarantine(transaction.writeNbt(), SETTLEMENT);
    }
    private static void assertQuarantined(CompoundTag tag) {
        assertEquals(RecruitmentTransaction.Status.QUARANTINED,
            RecruitmentTransaction.readOrQuarantine(tag, SETTLEMENT).status());
    }
}
