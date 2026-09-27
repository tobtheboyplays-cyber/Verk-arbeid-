package com.hearthstead.settlement.builder;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.logistics.Weight;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.request.RequestLedger;
import com.hearthstead.settlement.request.RequestLedgerSavedData;
import com.hearthstead.settlement.request.RequestLedgerService;
import com.hearthstead.settlement.request.RequestRecord;
import com.hearthstead.settlement.request.RequestType;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a site's materials stand, and asking for the missing ones.
 *
 * <p>"We need a way to see what he's missing" (owner): {@link #missing}
 * answers per item how many the job still needs beyond what the hut and the
 * Builder's bag hold, and how many the warehouse has and Couriers are
 * already carrying. {@link #request} turns the next batch's shortfall into
 * real {@code MATERIAL_INPUT} parcels Warehouse -> Hut; nothing is ever
 * conjured, and with no Courier employed the Builder fetches himself.
 */
public final class BuilderSupply {

    /** How far ahead (in item units) a Builder asks for materials. */
    public static final int BATCH_UNITS = 128;
    /** One status line / site row per item, capped for the payload. */
    public static final int MAX_LINES = 12;

    private BuilderSupply() {
    }

    /** One missing-material line. */
    public record Line(Item item, int needed, int inHut, int inWarehouse, int onTheWay) {
        /** What nobody has yet: needed minus hut/bag minus parcels on the way. */
        public int shortfall() {
            return Math.max(0, needed - inHut - onTheWay);
        }

        /** Nothing anywhere: the player must gather or buy it. */
        public boolean nobodyHasIt() {
            return shortfall() > 0 && inWarehouse <= 0;
        }
    }

    /**
     * Per item of the job's remaining bill: needed, in the hut (+ the
     * Builder's bag), in the warehouses, on the way. Only items still short
     * are returned, biggest shortfall first after build order.
     */
    public static List<Line> missing(ServerLevel level, Settlement settlement, BuildJob job,
                                     @Nullable Building hut, @Nullable Container bag) {
        Map<Item, Integer> remaining = BuilderMaterials.remaining(job);
        if (remaining.isEmpty()) {
            return List.of();
        }
        List<Container> hutStock = BuilderStock.hutContainers(level, hut);
        Map<Item, Integer> onTheWay = inFlight(level, settlement, hut);
        List<Line> out = new ArrayList<>();
        for (Map.Entry<Item, Integer> entry : remaining.entrySet()) {
            Item item = entry.getKey();
            int have = BuilderStock.count(hutStock, item) + (bag == null ? 0 : BuilderStock.bagCount(bag, item));
            int flying = onTheWay.getOrDefault(item, 0);
            if (have + flying >= entry.getValue()) {
                continue;
            }
            out.add(new Line(item, entry.getValue(), have,
                BuilderStock.warehouseCount(level, settlement, item), flying));
            if (out.size() >= MAX_LINES) {
                break;
            }
        }
        return out;
    }

    /** One Builder shortfall a workshop should make (supply-chain audit, 26 Sep). */
    public record CraftNeed(Building hut, Item item, int deficit) {
    }

    /**
     * Every active job's shortfall, per Builder's Hut: what the Builder still
     * needs beyond the hut, his bag and the parcels on the way. The crafting
     * order scan (CraftingOrderService, every 100 ticks per settlement) turns
     * each into an order at the workshop that makes it -- or, when none can,
     * into a request the player sees. The scan itself skips what a Warehouse
     * already holds (the Couriers bring that), and a decoration the planner
     * leaves out ({@link VillageSupply#shouldSkip}) is never asked for.
     */
    public static List<CraftNeed> craftNeeds(ServerLevel level, Settlement settlement) {
        List<CraftNeed> out = new ArrayList<>();
        if (level == null || settlement == null) {
            return out;
        }
        for (BuildJob job : BuildSiteSavedData.get(level).activeJobs(settlement.id)) {
            Building hut = BuildJobs.anyHut(settlement);
            Container bag = null;
            if (job.claimant != null && level.getEntity(job.claimant) instanceof SettlerEntity builder) {
                bag = builder.bag;
                Building own = BuildJobs.hutOf(settlement, builder);
                if (own != null) {
                    hut = own;
                }
            }
            if (hut == null) {
                continue;
            }
            for (Line line : missing(level, settlement, job, hut, bag)) {
                if (line.shortfall() > 0 && !VillageSupply.shouldSkip(level, settlement, line.item())) {
                    out.add(new CraftNeed(hut, line.item(), line.shortfall()));
                }
            }
        }
        return out;
    }

    /**
     * Open crafting-order units for this hut and item (a workshop is making
     * it). Read-only; the Builder may wait on it instead of reporting the
     * item as missing. Never folded into {@link #missing}: the order's own
     * need is computed from that shortfall.
     */
    public static int onOrder(ServerLevel level, Settlement settlement, @Nullable Building hut, Item item) {
        if (level == null || settlement == null || hut == null || item == null) {
            return 0;
        }
        net.minecraft.resources.ResourceLocation key =
            net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
        int total = 0;
        for (com.hearthstead.settlement.request.CraftingOrderBook.Order order
                : com.hearthstead.settlement.request.CraftingOrderService.orders(level, settlement)) {
            if (order.status() != com.hearthstead.settlement.request.CraftingOrderBook.Status.OPEN
                || !hut.id.equals(order.requesterId()) || !key.equals(order.itemId())) {
                continue;
            }
            // A player request or a stale assignment to a locked/unmanned
            // workshop is not incoming stock. Leave its receipts untouched;
            // actual Warehouse stock and parcels are counted separately.
            for (Building workshop : settlement.buildings) {
                if (workshop.id.equals(order.workshopId())
                    && com.hearthstead.settlement.request.CraftingOrderService.canCraft(
                        level, settlement, workshop, item, hut)) {
                    total += Math.max(0, order.count() - order.made());
                    break;
                }
            }
        }
        return total;
    }

    /** Items already travelling Warehouse -> this hut in live parcels. */
    public static Map<Item, Integer> inFlight(ServerLevel level, Settlement settlement, @Nullable Building hut) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        if (hut == null) {
            return out;
        }
        RequestLedgerSavedData data = RequestLedgerSavedData.existing(level);
        RequestLedger ledger = data == null ? null : data.existing(settlement.id);
        if (ledger == null) {
            return out;
        }
        for (RequestRecord row : ledger.active()) {
            if (row.type() != RequestType.MATERIAL_INPUT || !hut.id.equals(row.targetBuildingId())) {
                continue;
            }
            ItemStack proto = row.fingerprint().prototype(level.registryAccess());
            if (!proto.isEmpty()) {
                out.merge(proto.getItem(), row.remainingCount(), Integer::sum);
            }
        }
        return out;
    }

    /**
     * What the next {@link #BATCH_UNITS} item units of unfinished steps
     * need, in build order. This is what gets requested: the Builder asks
     * for the next layers, not the whole house, so a half-stocked warehouse
     * still makes visible progress.
     */
    public static Map<Item, Integer> nextBatch(BuildJob job, int units) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        int total = 0;
        for (int i = job.cursor(); i < job.size() && total < units; i++) {
            if (job.isDone(i)) {
                continue;
            }
            for (BuilderMaterials.ItemCount c : BuilderMaterials.costsOfStep(job, i)) {
                out.merge(c.item(), c.count(), Integer::sum);
                total += c.count();
            }
        }
        return out;
    }

    /** Whether any Courier of the settlement can take a parcel right now. */
    public static int parcelCapacity(ServerLevel level, Settlement settlement, ItemStack sample) {
        int best = 0;
        for (SettlerEntity courier : SettlementManager.loadedMembers(level, settlement)) {
            if (RequestLedgerService.validCourier(level, settlement, courier)) {
                int cap = courier.getCarryCapacity();
                int byWeight = Weight.budgetFor(cap) / Math.max(1, Weight.of(sample));
                best = Math.max(best, Math.min(cap, byWeight));
            }
        }
        return best;
    }

    /**
     * Opens Warehouse -> Hut parcels for the next batch's shortfall. At most
     * one row per item is live at a time (the ledger refuses duplicates), each
     * sized to what one Courier can carry. Returns the number of rows opened.
     */
    public static int request(ServerLevel level, Settlement settlement, Building hut, BuildJob job,
                              @Nullable Container bag) {
        if (hut == null || !hut.valid || hut.type != BuildingType.BUILDERS_HUT) {
            return 0;
        }
        List<Container> hutStock = BuilderStock.hutContainers(level, hut);
        List<BlockPos> hutPositions = WarehouseIndex.containers(level, hut);
        if (hutPositions.isEmpty()) {
            return 0;
        }
        Map<Item, Integer> onTheWay = inFlight(level, settlement, hut);
        int opened = 0;
        for (Map.Entry<Item, Integer> need : nextBatch(job, BATCH_UNITS).entrySet()) {
            Item item = need.getKey();
            int have = BuilderStock.count(hutStock, item) + (bag == null ? 0 : BuilderStock.bagCount(bag, item));
            int deficit = need.getValue() - have - onTheWay.getOrDefault(item, 0);
            if (deficit <= 0 || onTheWay.containsKey(item)) {
                continue;
            }
            if (openOne(level, settlement, hut, hutPositions, item, deficit)) {
                opened++;
            }
        }
        return opened;
    }

    /** Asks the warehouse for {@code count} of one item for the hut (ladders for a scaffold). */
    public static boolean requestItem(ServerLevel level, Settlement settlement, Building hut, Item item, int count) {
        if (hut == null || count <= 0 || inFlight(level, settlement, hut).containsKey(item)) {
            return false;
        }
        List<BlockPos> hutPositions = WarehouseIndex.containers(level, hut);
        return !hutPositions.isEmpty() && openOne(level, settlement, hut, hutPositions, item, count);
    }

    private static boolean openOne(ServerLevel level, Settlement settlement, Building hut,
                                   List<BlockPos> hutPositions, Item item, int deficit) {
        for (Building warehouse : settlement.buildings) {
            if (!warehouse.valid || warehouse.type != BuildingType.WAREHOUSE || warehouse.bounds == null) {
                continue;
            }
            for (BlockPos source : WarehouseIndex.containers(level, warehouse)) {
                if (!level.hasChunkAt(source) || !(level.getBlockEntity(source) instanceof Container chest)) {
                    continue;
                }
                for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                    ItemStack stack = chest.getItem(slot);
                    if (stack.isEmpty() || !stack.is(item) || !BuilderStock.plain(stack)) {
                        continue;
                    }
                    int parcel = parcelCapacity(level, settlement, stack);
                    if (parcel <= 0) {
                        return false; // no Courier can carry it: the Builder self-fetches
                    }
                    int count = Math.min(deficit, Math.min(parcel, stack.getCount()));
                    for (BlockPos target : hutPositions) {
                        RequestLedgerService.Decision decision = RequestLedgerService.openBuilderMaterial(
                            level, settlement, warehouse, source, slot, hut, target, stack, count);
                        if (decision.accepted()) {
                            return true;
                        }
                    }
                    return false;
                }
            }
        }
        return false;
    }

    /** Whether any valid Courier exists (else the Builder fetches himself). */
    public static boolean courierAvailable(ServerLevel level, Settlement settlement) {
        for (SettlerEntity member : SettlementManager.loadedMembers(level, settlement)) {
            if (RequestLedgerService.validCourier(level, settlement, member)) {
                return true;
            }
        }
        return false;
    }
}
