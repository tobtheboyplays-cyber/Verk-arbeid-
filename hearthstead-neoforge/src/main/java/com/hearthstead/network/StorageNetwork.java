package com.hearthstead.network;

import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import com.hearthstead.settlement.warehouse.WarehouseLevelService;
import com.hearthstead.settlement.warehouse.WarehouseStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** Server side of the settlement-owned, read-only Storage view. */
public final class StorageNetwork {

    /** The request endpoint retains player-bound settlement authorization. */
    public static void handleRequest(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        // The Banner the player has open wins; only a refresh from the Stores screen itself
        // (no Banner open any more) falls back to the settlement the player stands in.
        Settlement settlement = openBannerSettlement(level, player);
        if (settlement == null) settlement = nearestSettlement(level, player.blockPosition());
        send(player, snapshot(level, settlement));
    }

    /**
     * Read-only physical projection seam. Package visibility is intentional:
     * network GameTests can prove aggregation without manufacturing a player
     * or bypassing the request endpoint's settlement selection.
     */
    static StorageIndexPayload snapshot(ServerLevel level, Settlement settlement) {
        if (settlement == null) return empty();
        Map<Item, List<StockAggregate>> byItem = new HashMap<>();
        int total = 0;
        int warehouses = 0;
        int loadedWarehouses = 0;
        int loadedContainers = 0;
        Set<BlockPos> countedContainers = new HashSet<>();
        List<StorageIndexPayload.WarehouseRow> warehouseRows = new ArrayList<>();
        for (Building building : settlement.buildings) {
            if (building.type != com.hearthstead.building.BuildingType.WAREHOUSE
                    || !building.valid) continue;
            warehouses++;
            if (WarehouseIndex.enabled()
                    && warehouseRows.size() < StorageIndexPayload.MAX_WAREHOUSE_ROWS) {
                warehouseRows.add(warehouseRow(level, settlement, building));
            }
            if (!WarehouseIndex.fullyLoaded(level, building)) continue;
            loadedWarehouses++;
            WarehouseStorage storage = WarehouseStorage.refreshed(level, building);
            for (BlockPos pos : storage.containers()) {
                // A physical container may lie in two valid warehouse bounds;
                // read it once, retaining the first deterministic plaque row.
                if (!level.hasChunkAt(pos) || !countedContainers.add(pos.immutable())) continue;
                BlockEntity entity = level.getBlockEntity(pos);
                if (!(entity instanceof Container container)) continue;
                loadedContainers++;
                Map<StockAggregate, Integer> atWarehouse = new HashMap<>();
                for (int slot = 0; slot < container.getContainerSize(); slot++) {
                    ItemStack stack = container.getItem(slot);
                    if (stack.isEmpty()) continue;
                    StockAggregate aggregate = aggregateFor(byItem, stack);
                    int count = stack.getCount();
                    aggregate.total += count;
                    atWarehouse.merge(aggregate, count, Integer::sum);
                    total += count;
                }
                for (Map.Entry<StockAggregate, Integer> entry : atWarehouse.entrySet()) {
                    entry.getKey().addLocation(building.plaquePos, entry.getValue());
                }
            }
        }

        if (warehouses == 0) return noWarehouse();
        List<StockAggregate> ordered = byItem.values().stream()
            .flatMap(List::stream)
            .sorted(Comparator.comparingInt((StockAggregate row) -> row.total).reversed()
                .thenComparing(row -> BuiltInRegistries.ITEM.getKey(row.sample.getItem()).toString()))
            .toList();
        int distinct = ordered.size();
        int totalLocations = ordered.stream().mapToInt(row -> row.locations.size()).sum();
        int locationBudget = StorageIndexPayload.MAX_LOCATION_ROWS;
        List<StorageIndexPayload.StockRow> stocks = new ArrayList<>();
        for (StockAggregate row : ordered) {
            if (stocks.size() >= StorageIndexPayload.MAX_LISTED) break;
            int take = Math.min(locationBudget, row.locations.size());
            List<StorageIndexPayload.LocationRow> visible = List.copyOf(row.locations.subList(0, take));
            locationBudget -= take;
            ItemStack display = row.sample.copy();
            display.setCount(Math.min(row.total, 9999));
            stocks.add(new StorageIndexPayload.StockRow(display, row.total,
                row.locations.size(), visible));
        }
        int listedLocations = stocks.stream().mapToInt(row -> row.locations().size()).sum();
        return new StorageIndexPayload(settlement.name, distinct, total,
            warehouses, loadedWarehouses, loadedContainers, listedLocations,
            totalLocations, stocks, warehouseRows);
    }

    /**
     * Level card for one warehouse. Read-only; it never loads a chunk (the
     * container index only walks loaded chunks).
     */
    static StorageIndexPayload.WarehouseRow warehouseRow(ServerLevel level,
                                                        Settlement settlement,
                                                        Building building) {
        WarehouseLevelService.Status status =
            WarehouseLevelService.status(level, settlement, building);
        return new StorageIndexPayload.WarehouseRow(building.plaquePos,
            status.level(), status.builtLevel(), status.techMax(),
            status.managed(), status.capacity(), status.unmanaged(),
            Math.max(0, status.nextLevel()), status.nextCapacity(),
            status.nextGate() == null ? -1 : status.nextGate().wireId(),
            status.nextGateOwned(), gapRows(building));
    }

    /** "Next level: add ..." lines for checklist level builtLevel + 1. */
    private static List<StorageIndexPayload.GapRow> gapRows(Building building) {
        List<StorageIndexPayload.GapRow> rows = new ArrayList<>();
        if (building.nextLevelGap == null) {
            return rows;
        }
        for (var gap : building.nextLevelGap) {
            if (rows.size() >= StorageIndexPayload.MAX_GAP_ROWS) {
                break;
            }
            rows.add(new StorageIndexPayload.GapRow(gap.langKey(),
                gap.have(), gap.needed()));
        }
        return rows;
    }

    private static StockAggregate aggregateFor(Map<Item, List<StockAggregate>> byItem,
                                               ItemStack stack) {
        List<StockAggregate> variants = byItem.computeIfAbsent(stack.getItem(), ignored -> new ArrayList<>());
        for (StockAggregate candidate : variants) {
            if (ItemStack.isSameItemSameComponents(candidate.sample, stack)) return candidate;
        }
        ItemStack sample = stack.copy();
        sample.setCount(1);
        StockAggregate created = new StockAggregate(sample);
        variants.add(created);
        return created;
    }

    private static StorageIndexPayload noWarehouse() {
        return new StorageIndexPayload("", -1, 0, 0, 0, 0, 0, 0, List.of());
    }
    private static StorageIndexPayload empty() {
        return new StorageIndexPayload("", 0, 0, 0, 0, 0, 0, 0, List.of());
    }

    private static Settlement openBannerSettlement(ServerLevel level, ServerPlayer player) {
        if (!(player.containerMenu instanceof com.hearthstead.menu.HearthMenu menu) || !menu.stillValid(player)) {
            return null;
        }
        Settlement settlement = SettlementSavedData.get(level).settlements.get(menu.getSettlementId());
        return settlement != null && settlement.center.equals(menu.getHearthPos()) ? settlement : null;
    }

    private static Settlement nearestSettlement(ServerLevel level, BlockPos pos) {
        Settlement best = null;
        double bestDist = Double.MAX_VALUE;
        for (Settlement settlement : SettlementSavedData.get(level).settlements.values()) {
            double dist = settlement.center.distSqr(pos);
            if (dist < bestDist && dist <= (double) settlement.radius * settlement.radius) {
                best = settlement;
                bestDist = dist;
            }
        }
        return best;
    }

    private static void send(ServerPlayer player, StorageIndexPayload payload) {
        com.hearthstead.network.PayloadSend.toPlayer(player, payload);
    }

    private static final class StockAggregate {
        private final ItemStack sample;
        private final List<StorageIndexPayload.LocationRow> locations = new ArrayList<>();
        private int total;

        private StockAggregate(ItemStack sample) {
            this.sample = sample;
        }

        private void addLocation(BlockPos warehousePlaque, int count) {
            for (int i = 0; i < locations.size(); i++) {
                StorageIndexPayload.LocationRow existing = locations.get(i);
                if (existing.warehousePlaque().equals(warehousePlaque)) {
                    locations.set(i, new StorageIndexPayload.LocationRow(warehousePlaque,
                        existing.count() + count));
                    return;
                }
            }
            locations.add(new StorageIndexPayload.LocationRow(warehousePlaque, count));
        }
    }

    private StorageNetwork() {
    }
}
