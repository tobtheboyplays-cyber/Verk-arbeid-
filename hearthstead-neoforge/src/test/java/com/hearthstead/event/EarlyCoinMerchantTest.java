package com.hearthstead.event;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class EarlyCoinMerchantTest {
    @Test void savedCadenceCannotResetOnReloadOrRebuildingTheSameSite() {
        var receipts = new EarlyCoinMerchant.Receipts();
        BlockPos site = new BlockPos(10,64,20);
        assertTrue(receipts.available(site,100));
        receipts.commit(site,UUID.randomUUID(),100);
        var loaded = EarlyCoinMerchant.Receipts.load(receipts.save(new CompoundTag(),null),null);
        assertFalse(loaded.available(site,100));
        assertFalse(loaded.available(site,99+EarlyCoinMerchant.VISIT_TICKS));
        assertFalse(loaded.available(site,99+EarlyCoinMerchant.PERIOD_TICKS));
        assertTrue(loaded.available(site,100+EarlyCoinMerchant.PERIOD_TICKS));
        loaded.commit(site,UUID.randomUUID(),100+EarlyCoinMerchant.PERIOD_TICKS);
        assertFalse(loaded.available(site,100+EarlyCoinMerchant.PERIOD_TICKS));
    }
    @Test void malformedVisitCannotBecomeFreshUnlimitedStockAuthority() {
        var receipts = new EarlyCoinMerchant.Receipts();
        BlockPos site = new BlockPos(10,64,20);
        receipts.commit(site,UUID.randomUUID(),100);
        CompoundTag saved=receipts.save(new CompoundTag(),null);
        ListTag wrong = new ListTag(); wrong.add(StringTag.valueOf("not a receipt"));
        saved.put("Visits",wrong);
        assertFalse(EarlyCoinMerchant.Receipts.load(saved,null).available(site,999999));
        saved=receipts.save(new CompoundTag(),null); saved.remove("Quarantined");
        assertFalse(EarlyCoinMerchant.Receipts.load(saved,null).available(site,999999));
        saved=receipts.save(new CompoundTag(),null);
        saved.getList("Visits",10).getCompound(0).putLong("Next",1);
        assertFalse(EarlyCoinMerchant.Receipts.load(saved,null).available(site,999999));
    }
    @Test void exactHistoricSixThousandGapMigratesWithoutChangingItsMerchantOrPublishingEarly() {
        BlockPos site = new BlockPos(10,64,20);
        UUID merchant = UUID.randomUUID();
        CompoundTag saved = new CompoundTag();
        saved.putInt("Version", 1); saved.putBoolean("Quarantined", false);
        ListTag visits = new ListTag(); CompoundTag legacy = new CompoundTag();
        legacy.putLong("Site", site.asLong()); legacy.putUUID("Merchant", merchant);
        legacy.putLong("Expires", 531_400L); legacy.putLong("Next", 537_400L); // exact former 30k/24k cadence
        visits.add(legacy); saved.put("Visits", visits);

        var loaded = EarlyCoinMerchant.Receipts.load(saved, null);
        long migratedNext = 537_400L;
        assertFalse(loaded.available(site, migratedNext - 1), "migration must never create an early restock");
        assertTrue(loaded.available(site, migratedNext), "the same site becomes available only after its prior committed boundary");
        CompoundTag persisted = loaded.save(new CompoundTag(), null);
        CompoundTag row = persisted.getList("Visits", 10).getCompound(0);
        assertFalse(persisted.getBoolean("Quarantined"));
        assertEquals(site.asLong(), row.getLong("Site"));
        assertEquals(merchant, row.getUUID("Merchant"));
        assertEquals(531_400L, row.getLong("Expires"));
        assertEquals(migratedNext, row.getLong("Next"));

        row.putLong("Next", 537_401L); // Unknown near-match must stay fail-closed.
        assertFalse(EarlyCoinMerchant.Receipts.load(persisted, null).available(site, Long.MAX_VALUE));
    }
}
