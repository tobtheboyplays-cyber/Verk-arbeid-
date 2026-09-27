package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import com.hearthstead.registry.ModComponents;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.work.GoodsQuality;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.connection.ConnectionType;

import java.util.UUID;

/** Focused proof for the server-authored, read-only Storage projection. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class StorageNetworkGameTests {

    @GameTest(batch = "storage_index", template = "empty16", timeoutTicks = 100)
    public void overlappingContainerCountsOnceAndComponentsStaySeparate(GameTestHelper helper) {
        BlockPos chestRelative = new BlockPos(4, 1, 4);
        helper.setBlock(chestRelative, Blocks.CHEST);
        BlockEntity entity = helper.getLevel().getBlockEntity(helper.absolutePos(chestRelative));
        helper.assertTrue(entity instanceof Container, "storage proof needs a real chest");
        Container chest = (Container) entity;
        ItemStack basic = new ItemStack(Items.OAK_LOG, 5);
        ItemStack fine = new ItemStack(Items.OAK_LOG, 7);
        fine.set(ModComponents.GOODS_QUALITY.get(), GoodsQuality.FINE);
        chest.setItem(0, basic);
        chest.setItem(1, fine);

        Settlement settlement = new Settlement(UUID.randomUUID(), "Store proof",
            helper.absolutePos(new BlockPos(4, 1, 4)));
        Building first = warehouse(helper, new BlockPos(1, 1, 1), new BlockPos(8, 3, 8),
            new BlockPos(1, 2, 1));
        Building overlap = warehouse(helper, new BlockPos(3, 1, 1), new BlockPos(10, 3, 8),
            new BlockPos(10, 2, 1));
        Building invalid = warehouse(helper, new BlockPos(1, 1, 9), new BlockPos(8, 3, 14),
            new BlockPos(1, 2, 14));
        invalid.valid = false;
        settlement.buildings.add(first);
        settlement.buildings.add(overlap);
        settlement.buildings.add(invalid);

        StorageIndexPayload snapshot = StorageNetwork.snapshot(helper.getLevel(), settlement);
        helper.assertTrue(snapshot.warehouseCount() == 2 && snapshot.loadedWarehouseCount() == 2,
            "only valid warehouses belong in coverage, and both loaded rooms must be reported");
        helper.assertTrue(snapshot.loadedContainers() == 1 && snapshot.totalItems() == 12,
            "one chest in overlapping bounds must be read once, never double-counted");
        helper.assertTrue(snapshot.distinctTypes() == 2 && snapshot.stocks().size() == 2,
            "same item with different components must remain separate store rows");
        StorageIndexPayload.StockRow basicRow = matching(snapshot, basic);
        StorageIndexPayload.StockRow fineRow = matching(snapshot, fine);
        helper.assertTrue(basicRow != null && basicRow.total() == 5
                && basicRow.locationCount() == 1 && basicRow.locations().size() == 1,
            "basic component variant needs its exact count and one physical location");
        helper.assertTrue(fineRow != null && fineRow.total() == 7
                && fineRow.locationCount() == 1 && fineRow.locations().size() == 1
                && GoodsQuality.of(fineRow.stack()) == GoodsQuality.FINE,
            "Fine goods need their exact component, count and one physical location");
        helper.assertTrue(snapshot.listedLocationRows() == 2 && snapshot.totalLocationRows() == 2,
            "coverage must state the exact transmitted and total location rows");
        StorageIndexPayload decoded = throughRealRegistryWire(helper, snapshot);
        StorageIndexPayload.StockRow decodedFine = matching(decoded, fine);
        helper.assertTrue(decoded.totalItems() == 12 && decodedFine != null
                && GoodsQuality.of(decodedFine.stack()) == GoodsQuality.FINE
                && decodedFine.locations().getFirst().count() == 7,
            "the real-registry payload codec must retain Fine goods, count and location");
        helper.succeed();
    }

    private static StorageIndexPayload throughRealRegistryWire(GameTestHelper helper,
                                                               StorageIndexPayload source) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
            helper.getLevel().registryAccess(), ConnectionType.NEOFORGE);
        try {
            StorageIndexPayload.CODEC.encode(buffer, source);
            StorageIndexPayload decoded = StorageIndexPayload.CODEC.decode(buffer);
            helper.assertTrue(buffer.readableBytes() == 0,
                "Storage payload codec must consume the exact real-registry wire payload");
            return decoded;
        } finally {
            buffer.release();
        }
    }

    private static StorageIndexPayload.StockRow matching(StorageIndexPayload snapshot, ItemStack expected) {
        return snapshot.stocks().stream()
            .filter(row -> ItemStack.isSameItemSameComponents(row.stack(), expected))
            .findFirst().orElse(null);
    }

    private static Building warehouse(GameTestHelper helper, BlockPos minRelative, BlockPos maxRelative,
                                      BlockPos plaqueRelative) {
        Building building = new Building(UUID.randomUUID(),
            com.hearthstead.building.BuildingType.WAREHOUSE, helper.absolutePos(plaqueRelative),
            helper.absolutePos(minRelative), BoundingBox.fromCorners(helper.absolutePos(minRelative),
                helper.absolutePos(maxRelative)));
        building.valid = true;
        return building;
    }

    public StorageNetworkGameTests() { }
}
