package com.hearthstead.settlement;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeferredItemMaterializationSavedDataTest {

    @Test
    void exactStacksAndBoundedEntityOptionsRoundTrip() {
        UUID rowId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        CompoundTag metadata = new CompoundTag();
        metadata.putUUID("HearthsteadGroundCollectionOwner", owner);
        metadata.putLong("HearthsteadGroundCollectionLeaseExpiry", 900L);

        CompoundTag row = row(rowId, new ItemStack(Items.ARROW, 3));
        row.put("PersistentData", metadata);
        row.putInt("PickupDelay", 100);
        row.putUUID("Target", owner);
        row.putBoolean("ExtendedLifetime", true);
        CompoundTag root = root(row);

        DeferredItemMaterializationSavedData loaded =
            DeferredItemMaterializationSavedData.load(root,
                RegistryAccess.EMPTY);
        assertFalse(loaded.quarantined());
        assertEquals(1, loaded.pendingRows());
        assertEquals(3, loaded.pendingItems(RegistryAccess.EMPTY,
            Items.ARROW));

        CompoundTag saved = loaded.save(new CompoundTag(),
            RegistryAccess.EMPTY);
        assertEquals(row, saved.getList("Pending", Tag.TAG_COMPOUND)
            .getCompound(0));
        DeferredItemMaterializationSavedData restarted =
            DeferredItemMaterializationSavedData.load(saved,
                RegistryAccess.EMPTY);
        assertFalse(restarted.quarantined());
        assertEquals(3, restarted.pendingItems(RegistryAccess.EMPTY,
            Items.ARROW));
    }

    @Test
    void oversizedLedgerQuarantinesAsOneBoundedStickyState() {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion",
            DeferredItemMaterializationSavedData.DATA_VERSION);
        root.putString("Dimension", "minecraft:overworld");
        ListTag rows = new ListTag();
        for (int i = 0;
             i <= DeferredItemMaterializationSavedData.MAX_PENDING_ROWS; i++) {
            rows.add(new CompoundTag());
        }
        root.put("Pending", rows);

        DeferredItemMaterializationSavedData loaded =
            DeferredItemMaterializationSavedData.load(root,
                RegistryAccess.EMPTY);
        assertTrue(loaded.quarantined());
        assertEquals(0, loaded.pendingRows());
        assertTrue(loaded.quarantineReason().length() <= 96);

        CompoundTag marker = loaded.save(new CompoundTag(),
            RegistryAccess.EMPTY);
        assertTrue(marker.getBoolean("Quarantined"));
        assertEquals(0, marker.getList("Pending", Tag.TAG_COMPOUND).size());
        DeferredItemMaterializationSavedData restarted =
            DeferredItemMaterializationSavedData.load(marker,
                RegistryAccess.EMPTY);
        assertTrue(restarted.quarantined(),
            "quarantine must remain fail-closed after restart");

        CompoundTag oversizedReceiptsRoot = root();
        ListTag oversizedReceipts = new ListTag();
        for (int i = 0;
             i <= DeferredItemMaterializationSavedData.MAX_COMPLETED_RECEIPTS;
             i++) {
            oversizedReceipts.add(new CompoundTag());
        }
        oversizedReceiptsRoot.put("Completed", oversizedReceipts);
        DeferredItemMaterializationSavedData oversizedReceiptLoad =
            DeferredItemMaterializationSavedData.load(oversizedReceiptsRoot,
                RegistryAccess.EMPTY);
        assertTrue(oversizedReceiptLoad.quarantined());
        assertEquals(0, oversizedReceiptLoad.completedReceipts());
    }

    @Test
    void exactLiveCapRefusesBeforeSourceMutation() {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion",
            DeferredItemMaterializationSavedData.DATA_VERSION);
        root.putString("Dimension", "minecraft:overworld");
        ListTag rows = new ListTag();
        for (int i = 0;
             i < DeferredItemMaterializationSavedData.MAX_PENDING_ROWS; i++) {
            rows.add(row(new UUID(1L, i + 1L),
                new ItemStack(Items.ARROW)));
        }
        root.put("Pending", rows);

        DeferredItemMaterializationSavedData full =
            DeferredItemMaterializationSavedData.load(root,
                RegistryAccess.EMPTY);
        assertFalse(full.quarantined());
        assertEquals(DeferredItemMaterializationSavedData.MAX_PENDING_ROWS,
            full.pendingRows());
        assertNull(full.queue(null, 0.5D, 1.0D, 0.5D,
            new ItemStack(Items.BREAD)),
            "a full live ledger must refuse before the caller clears source");
        assertEquals(DeferredItemMaterializationSavedData.MAX_PENDING_ROWS,
            full.pendingRows());
    }

    @Test
    void stickyQuarantineCannotBeMutatedByRollback() {
        UUID rowId = UUID.randomUUID();
        CompoundTag root = root(row(rowId, new ItemStack(Items.BREAD)));
        root.putBoolean("Quarantined", true);
        root.putString("QuarantineReason", "operator_review_required");

        DeferredItemMaterializationSavedData quarantined =
            DeferredItemMaterializationSavedData.load(root,
                RegistryAccess.EMPTY);
        assertTrue(quarantined.quarantined());
        assertEquals(1, quarantined.pendingRows());
        assertFalse(quarantined.cancel(rowId));
        assertEquals(1, quarantined.pendingRows(),
            "sticky quarantine must retain every loaded authority row");
    }

    @Test
    void unloadedCompletionReceiptProvesExactDeliveryAcrossRestart() {
        UUID deliveryId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        ItemStack exact = new ItemStack(Items.BREAD, 2);
        CompoundTag receipt = row(deliveryId, exact);
        receipt.putInt("PickupDelay", 10);
        receipt.putUUID("Target", playerId);
        receipt.putBoolean("ExtendedLifetime", true);
        receipt.putBoolean("RetainCompletionReceipt", true);
        CompoundTag root = root();
        ListTag completed = new ListTag();
        completed.add(receipt);
        root.put("Completed", completed);
        DeferredItemMaterializationSavedData.ItemEntityOptions options =
            new DeferredItemMaterializationSavedData.ItemEntityOptions(
                new CompoundTag(), 10, playerId, true, true);
        ResourceLocation overworld = ResourceLocation.fromNamespaceAndPath(
            "minecraft", "overworld");

        DeferredItemMaterializationSavedData loaded =
            DeferredItemMaterializationSavedData.load(root,
                RegistryAccess.EMPTY);
        assertFalse(loaded.quarantined());
        assertEquals(1, loaded.completedReceipts());
        assertTrue(loaded.completedExact(overworld, RegistryAccess.EMPTY,
            deliveryId, 4.5D, 2.3D, 7.5D, exact, options));
        assertFalse(loaded.completedExact(overworld, RegistryAccess.EMPTY,
            deliveryId, 4.5D, 2.3D, 7.5D,
            new ItemStack(Items.BREAD), options));

        CompoundTag saved = loaded.save(new CompoundTag(),
            RegistryAccess.EMPTY);
        DeferredItemMaterializationSavedData restarted =
            DeferredItemMaterializationSavedData.load(saved,
                RegistryAccess.EMPTY);
        assertTrue(restarted.completedExact(overworld, RegistryAccess.EMPTY,
            deliveryId, 4.5D, 2.3D, 7.5D, exact, options),
            "an unloaded physical entity must remain exactly-once proven by "
                + "the no-eviction receipt after restart");
    }

    @Test
    void completedReceiptCapRefusesBeforeExternalSourceMutation() {
        CompoundTag root = root();
        ListTag completed = new ListTag();
        for (int i = 0;
             i < DeferredItemMaterializationSavedData.MAX_COMPLETED_RECEIPTS;
             i++) {
            CompoundTag receipt = row(new UUID(2L, i + 1L),
                new ItemStack(Items.ARROW));
            receipt.putBoolean("RetainCompletionReceipt", true);
            completed.add(receipt);
        }
        root.put("Completed", completed);
        DeferredItemMaterializationSavedData full =
            DeferredItemMaterializationSavedData.load(root,
                RegistryAccess.EMPTY);
        DeferredItemMaterializationSavedData.ItemEntityOptions retained =
            new DeferredItemMaterializationSavedData.ItemEntityOptions(
                new CompoundTag(), -1, null, false, true);

        assertFalse(full.quarantined());
        assertEquals(
            DeferredItemMaterializationSavedData.MAX_COMPLETED_RECEIPTS,
            full.completedReceipts());
        assertEquals(DeferredItemMaterializationSavedData.QueueResult.CAPACITY,
            full.queue(null, new UUID(3L, 1L), 4.5D, 2.3D, 7.5D,
                new ItemStack(Items.BREAD), retained),
            "a full no-eviction receipt ledger must refuse before sale source mutation");
        assertEquals(
            DeferredItemMaterializationSavedData.MAX_COMPLETED_RECEIPTS,
            full.completedReceipts());
    }

    @Test
    void duplicateIdentityAcrossPendingAndCompletedQuarantines() {
        UUID duplicate = UUID.randomUUID();
        CompoundTag pending = row(duplicate, new ItemStack(Items.BREAD));
        pending.putBoolean("RetainCompletionReceipt", true);
        CompoundTag receipt = pending.copy();
        CompoundTag root = root(pending);
        ListTag completed = new ListTag();
        completed.add(receipt);
        root.put("Completed", completed);

        DeferredItemMaterializationSavedData loaded =
            DeferredItemMaterializationSavedData.load(root,
                RegistryAccess.EMPTY);
        assertTrue(loaded.quarantined());
        assertEquals(0, loaded.pendingRows());
        assertEquals(0, loaded.completedReceipts());
    }

    @Test
    void duplicateIdentityAndOversizedMetadataNeverBecomeSpawnAuthority() {
        UUID duplicate = UUID.randomUUID();
        CompoundTag first = row(duplicate, new ItemStack(Items.BREAD, 2));
        CompoundTag second = row(duplicate, new ItemStack(Items.ARROW, 2));
        CompoundTag duplicateRoot = root(first, second);
        DeferredItemMaterializationSavedData duplicateLoaded =
            DeferredItemMaterializationSavedData.load(duplicateRoot,
                RegistryAccess.EMPTY);
        assertTrue(duplicateLoaded.quarantined());
        assertEquals(0, duplicateLoaded.pendingRows());

        CompoundTag huge = row(UUID.randomUUID(),
            new ItemStack(Items.BREAD));
        CompoundTag metadata = new CompoundTag();
        metadata.putString("oversized", "x".repeat(
            DeferredItemMaterializationSavedData.MAX_ENTITY_METADATA_CHARS
                + 1));
        huge.put("PersistentData", metadata);
        DeferredItemMaterializationSavedData hugeLoaded =
            DeferredItemMaterializationSavedData.load(root(huge),
                RegistryAccess.EMPTY);
        assertTrue(hugeLoaded.quarantined());
        assertEquals(0, hugeLoaded.pendingRows());
    }

    private static CompoundTag root(CompoundTag... rows) {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion",
            DeferredItemMaterializationSavedData.DATA_VERSION);
        root.putString("Dimension", "minecraft:overworld");
        ListTag pending = new ListTag();
        for (CompoundTag row : rows) {
            pending.add(row);
        }
        root.put("Pending", pending);
        return root;
    }

    private static CompoundTag row(UUID id, ItemStack stack) {
        CompoundTag row = new CompoundTag();
        row.putUUID("Id", id);
        row.putDouble("X", 4.5D);
        row.putDouble("Y", 2.3D);
        row.putDouble("Z", 7.5D);
        row.put("Stack", stack.saveOptional(RegistryAccess.EMPTY));
        return row;
    }
}
