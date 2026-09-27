package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-authored, read-only view of loaded warehouse stock. Physical
 * containers remain the authority; this payload is never a transfer endpoint.
 */
public record StorageIndexPayload(String settlementName, int distinctTypes,
                                  int totalItems, int warehouseCount,
                                  int loadedWarehouseCount, int loadedContainers,
                                  int listedLocationRows, int totalLocationRows,
                                  List<StockRow> stocks,
                                  List<WarehouseRow> warehouses)
    implements CustomPacketPayload {

    /** Bounded client catalogue; distinctTypes tells the truth when it is full. */
    public static final int MAX_LISTED = 128;
    /** Bounds the network detail payload independently of storage capacity. */
    public static final int MAX_LOCATION_ROWS = 512;
    /** Warehouse level rows sent at most (plaque order, first 16). */
    public static final int MAX_WAREHOUSE_ROWS = 16;
    /** Next-level checklist lines per warehouse row. */
    public static final int MAX_GAP_ROWS = 12;

    /** Pre-level shape: no warehouse rows. */
    public StorageIndexPayload(String settlementName, int distinctTypes,
                               int totalItems, int warehouseCount,
                               int loadedWarehouseCount, int loadedContainers,
                               int listedLocationRows, int totalLocationRows,
                               List<StockRow> stocks) {
        this(settlementName, distinctTypes, totalItems, warehouseCount,
            loadedWarehouseCount, loadedContainers, listedLocationRows,
            totalLocationRows, stocks, List.of());
    }

    public StorageIndexPayload {
        settlementName = settlementName == null ? "" : settlementName;
        if (distinctTypes < -1) throw new IllegalArgumentException("invalid distinct types");
        totalItems = Math.max(0, totalItems);
        warehouseCount = Math.max(0, warehouseCount);
        loadedWarehouseCount = Math.max(0, Math.min(warehouseCount, loadedWarehouseCount));
        loadedContainers = Math.max(0, loadedContainers);
        totalLocationRows = Math.max(0, totalLocationRows);
        listedLocationRows = Math.max(0, Math.min(totalLocationRows, listedLocationRows));
        stocks = List.copyOf(stocks == null ? List.of() : stocks);
        if (stocks.size() > MAX_LISTED) {
            throw new IllegalArgumentException("too many listed stock rows");
        }
        warehouses = List.copyOf(warehouses == null ? List.of() : warehouses);
        if (warehouses.size() > MAX_WAREHOUSE_ROWS) {
            throw new IllegalArgumentException("too many warehouse rows");
        }
    }

    /**
     * One warehouse's level card: "Warehouse L2 - 27/32 containers".
     *
     * @param plaque         the warehouse plaque (identity)
     * @param level          effective level (what capacity follows)
     * @param builtLevel     checklist level the room meets (plaque scan)
     * @param techMax        highest level the Logistics tree recognises
     * @param managed        containers managed now
     * @param capacity       managed-container capacity of {@code level}
     * @param unmanaged      containers present but "not managed (warehouse full)"
     * @param nextLevel      next level, or 0 at L5
     * @param nextCapacity   capacity of the next level, or 0
     * @param nextGateWireId PostRaidUpgrade wire id that must be owned for the
     *                       next level, or -1 when none is needed
     * @param nextGateOwned  whether that gate is already owned
     * @param nextGap        what the room still lacks for the next level
     */
    public record WarehouseRow(BlockPos plaque, int level, int builtLevel,
                               int techMax, int managed, int capacity,
                               int unmanaged, int nextLevel, int nextCapacity,
                               int nextGateWireId, boolean nextGateOwned,
                               List<GapRow> nextGap) {
        public WarehouseRow {
            plaque = plaque == null ? BlockPos.ZERO : plaque.immutable();
            level = Math.max(1, level);
            builtLevel = Math.max(1, builtLevel);
            techMax = Math.max(0, techMax);
            managed = Math.max(0, managed);
            capacity = Math.max(0, capacity);
            unmanaged = Math.max(0, unmanaged);
            nextLevel = Math.max(0, nextLevel);
            nextCapacity = Math.max(0, nextCapacity);
            nextGateWireId = Math.max(-1, nextGateWireId);
            nextGap = List.copyOf(nextGap == null ? List.of() : nextGap);
            if (nextGap.size() > MAX_GAP_ROWS) {
                throw new IllegalArgumentException("too many gap rows");
            }
        }
    }

    /** One missing checklist item: translation key (args: have, needed). */
    public record GapRow(String langKey, int have, int needed) {
        public GapRow {
            langKey = langKey == null ? "" : langKey;
            have = Math.max(0, have);
            needed = Math.max(0, needed);
        }
    }

    /** One exact item+component variant, aggregated only after component equality. */
    public record StockRow(ItemStack stack, int total, int locationCount,
                           List<LocationRow> locations) {
        public StockRow {
            stack = stack == null ? ItemStack.EMPTY : stack.copy();
            total = Math.max(0, total);
            locationCount = Math.max(0, locationCount);
            locations = List.copyOf(locations == null ? List.of() : locations);
            if (locations.size() > locationCount) {
                throw new IllegalArgumentException("listed locations exceed exact count");
            }
        }
    }

    /** Exact warehouse plaque location and aggregate of this variant there. */
    public record LocationRow(BlockPos warehousePlaque, int count) {
        public LocationRow {
            warehousePlaque = warehousePlaque == null ? BlockPos.ZERO : warehousePlaque.immutable();
            count = Math.max(0, count);
        }
    }

    /** Compatibility view for existing stock list renderers. */
    public List<ItemStack> top() {
        return stocks.stream().map(StockRow::stack).toList();
    }

    public static final Type<StorageIndexPayload> TYPE =
        new Type<>(Hearthstead.id("storage_index"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StorageIndexPayload> CODEC =
        StreamCodec.of((buf, value) -> {
            buf.writeUtf(value.settlementName());
            buf.writeVarInt(value.distinctTypes());
            buf.writeVarInt(value.totalItems());
            buf.writeVarInt(value.warehouseCount());
            buf.writeVarInt(value.loadedWarehouseCount());
            buf.writeVarInt(value.loadedContainers());
            buf.writeVarInt(value.listedLocationRows());
            buf.writeVarInt(value.totalLocationRows());
            buf.writeVarInt(value.stocks().size());
            for (StockRow stock : value.stocks()) {
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stock.stack());
                buf.writeVarInt(stock.total());
                buf.writeVarInt(stock.locationCount());
                buf.writeVarInt(stock.locations().size());
                for (LocationRow location : stock.locations()) {
                    BlockPos.STREAM_CODEC.encode(buf, location.warehousePlaque());
                    buf.writeVarInt(location.count());
                }
            }
            // Appended with warehouse levels (26 Sep); stable field order.
            buf.writeVarInt(value.warehouses().size());
            for (WarehouseRow row : value.warehouses()) {
                BlockPos.STREAM_CODEC.encode(buf, row.plaque());
                buf.writeVarInt(row.level());
                buf.writeVarInt(row.builtLevel());
                buf.writeVarInt(row.techMax());
                buf.writeVarInt(row.managed());
                buf.writeVarInt(row.capacity());
                buf.writeVarInt(row.unmanaged());
                buf.writeVarInt(row.nextLevel());
                buf.writeVarInt(row.nextCapacity());
                buf.writeVarInt(row.nextGateWireId() + 1);
                buf.writeBoolean(row.nextGateOwned());
                buf.writeVarInt(row.nextGap().size());
                for (GapRow gap : row.nextGap()) {
                    buf.writeUtf(gap.langKey(), 128);
                    buf.writeVarInt(gap.have());
                    buf.writeVarInt(gap.needed());
                }
            }
        }, buf -> {
            String name = buf.readUtf();
            int distinct = buf.readVarInt();
            if (distinct < -1) throw new IllegalArgumentException("invalid distinct types");
            int total = nonNegative(buf.readVarInt(), "total items");
            int warehouses = nonNegative(buf.readVarInt(), "warehouses");
            int loadedWarehouses = nonNegative(buf.readVarInt(), "loaded warehouses");
            int loadedContainers = nonNegative(buf.readVarInt(), "loaded containers");
            int listedLocations = nonNegative(buf.readVarInt(), "listed locations");
            int totalLocations = nonNegative(buf.readVarInt(), "total locations");
            int stockCount = bounded(buf.readVarInt(), MAX_LISTED, "stock rows");
            List<StockRow> stocks = new ArrayList<>(stockCount);
            int receivedLocations = 0;
            for (int i = 0; i < stockCount; i++) {
                ItemStack stack = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                int count = nonNegative(buf.readVarInt(), "stock count");
                int locationCount = nonNegative(buf.readVarInt(), "location count");
                int listed = bounded(buf.readVarInt(), MAX_LOCATION_ROWS - receivedLocations,
                    "listed locations");
                receivedLocations += listed;
                List<LocationRow> locations = new ArrayList<>(listed);
                for (int location = 0; location < listed; location++) {
                    locations.add(new LocationRow(BlockPos.STREAM_CODEC.decode(buf),
                        nonNegative(buf.readVarInt(), "location item count")));
                }
                stocks.add(new StockRow(stack, count, locationCount, locations));
            }
            if (listedLocations != receivedLocations || totalLocations < listedLocations) {
                throw new IllegalArgumentException("invalid storage location coverage");
            }
            int rowCount = bounded(buf.readVarInt(), MAX_WAREHOUSE_ROWS, "warehouse rows");
            List<WarehouseRow> rows = new ArrayList<>(rowCount);
            for (int i = 0; i < rowCount; i++) {
                BlockPos plaque = BlockPos.STREAM_CODEC.decode(buf);
                int level = nonNegative(buf.readVarInt(), "warehouse level");
                int built = nonNegative(buf.readVarInt(), "built level");
                int techMax = nonNegative(buf.readVarInt(), "tech max");
                int managed = nonNegative(buf.readVarInt(), "managed");
                int capacity = nonNegative(buf.readVarInt(), "capacity");
                int unmanaged = nonNegative(buf.readVarInt(), "unmanaged");
                int nextLevel = nonNegative(buf.readVarInt(), "next level");
                int nextCapacity = nonNegative(buf.readVarInt(), "next capacity");
                int gateWire = nonNegative(buf.readVarInt(), "gate wire") - 1;
                boolean gateOwned = buf.readBoolean();
                int gapCount = bounded(buf.readVarInt(), MAX_GAP_ROWS, "gap rows");
                List<GapRow> gaps = new ArrayList<>(gapCount);
                for (int g = 0; g < gapCount; g++) {
                    gaps.add(new GapRow(buf.readUtf(128),
                        nonNegative(buf.readVarInt(), "gap have"),
                        nonNegative(buf.readVarInt(), "gap needed")));
                }
                rows.add(new WarehouseRow(plaque, level, built, techMax, managed,
                    capacity, unmanaged, nextLevel, nextCapacity, gateWire,
                    gateOwned, gaps));
            }
            return new StorageIndexPayload(name, distinct, total, warehouses,
                loadedWarehouses, loadedContainers, listedLocations, totalLocations, stocks,
                rows);
        });

    private static int nonNegative(int value, String field) {
        if (value < 0) throw new IllegalArgumentException("negative " + field);
        return value;
    }

    private static int bounded(int value, int maximum, String field) {
        if (value < 0 || value > Math.max(0, maximum)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}