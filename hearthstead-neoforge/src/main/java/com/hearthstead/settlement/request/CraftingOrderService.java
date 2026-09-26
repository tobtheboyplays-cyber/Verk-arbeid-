package com.hearthstead.settlement.request;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.building.Fuel;
import com.hearthstead.building.Production;
import com.hearthstead.entity.Profession;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.equipment.EquipmentRequest;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import com.hearthstead.settlement.work.TavernHostService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * M1 "Crafting orders" — the first resolver chain, server-authoritative.
 *
 * <p>Chain per need: requester's own stock (the need exists only while the
 * requester is short) → Warehouse (if it holds enough, the existing courier
 * restock paths serve it and no order is opened) → a STAFFED workshop whose
 * {@link Production#of} table makes the item (a CRAFT order the workshop
 * then prefers) → the player (a NEEDS_PLAYER order, shown in Tasks).
 *
 * <p>Orders never block work (D-007): a crafter with other inputs still
 * crafts; an order only changes which ready recipe it prefers
 * ({@link #preferredOutput}), and its output leaves through the existing
 * courier output-collection chain. An order closes exactly once: FULFILLED
 * when the need is gone after the workshop made something for it, otherwise
 * CANCELLED. Counts are physically observed batches ({@link #noteCrafted}),
 * never timers.
 */
public final class CraftingOrderService {
    /** Budgeted: each settlement is re-examined once per this many ticks. */
    public static final int SCAN_INTERVAL = 100;
    /** Matches the courier's material restock target (4 batches). */
    static final int MATERIAL_BATCHES = 4;
    /** Four batches of fire, in charcoal (2 units each, 2 units/batch). */
    static final int FUEL_CHARCOAL = 4;

    /** One live, physically observed shortfall at one requester. */
    public record Need(UUID requesterId, @Nullable Building requester, Item item,
                       int deficit, CraftingOrderBook.Source source,
                       Predicate<ItemStack> warehouseMatch) {
        ResourceLocation itemId() {
            return BuiltInRegistries.ITEM.getKey(item);
        }
    }

    public static void tick(ServerLevel level) {
        if (level.getServer() == null) return;
        long time = level.getGameTime();
        SettlementSavedData data = SettlementSavedData.get(level);
        for (Settlement settlement : List.copyOf(data.settlements.values())) {
            if (Math.floorMod(time + settlement.id.hashCode(), SCAN_INTERVAL) != 0) continue;
            try {
                scan(level, settlement);
            } catch (RuntimeException failure) {
                Hearthstead.LOGGER.warn("Crafting order scan failed for settlement {}",
                    settlement.id, failure);
            }
        }
    }

    /**
     * Re-derives every need from live containers, opens missing orders and
     * closes orders whose need is gone. Idempotent: running it twice in a row
     * changes nothing the second time.
     */
    public static void scan(ServerLevel level, Settlement settlement) {
        if (level == null || settlement == null) return;
        CraftingOrderSavedData saved = CraftingOrderSavedData.get(level);
        CraftingOrderBook book = saved.book(settlement.id);
        long now = level.getGameTime();
        boolean dirty = false;
        Map<String, Need> needs = new LinkedHashMap<>();
        for (Need need : gatherNeeds(level, settlement)) {
            String key = need.requesterId() + "|" + need.itemId();
            Need prior = needs.get(key);
            if (prior == null || need.deficit() > prior.deficit()) needs.put(key, need);
        }
        // Close first, so a freed slot is available to a new need this scan.
        for (CraftingOrderBook.Order order : book.active()) {
            Need need = needs.get(order.requesterId() + "|" + order.itemId());
            if (need == null || warehouseCount(level, settlement, need.warehouseMatch()) >= need.deficit()) {
                dirty |= book.close(order, order.made() > 0, now);
                continue;
            }
            Building current = order.workshopId() == null ? null
                : buildingById(settlement, order.workshopId());
            if (current == null || !canCraft(current, need.item(), need.requester())) {
                Building workshop = resolveWorkshop(settlement, need.item(), need.requester());
                dirty |= book.reassign(order, workshop == null ? null : workshop.id, now);
            }
        }
        for (Need need : needs.values()) {
            int stocked = warehouseCount(level, settlement, need.warehouseMatch());
            if (stocked >= need.deficit()) continue;
            CraftingOrderBook.OpenDecision decision = open(settlement, book, need.requesterId(),
                need.requester(), need.item(), need.deficit() - stocked, need.source(), now);
            dirty |= decision.result() == CraftingOrderBook.OpenResult.CREATED;
        }
        if (dirty) saved.setDirty();
    }

    /**
     * Direct resolver entry: asks the chain for {@code count} of {@code item}
     * on behalf of {@code requester}. Returns DUPLICATE for a live order on
     * the same (requester, item) and CAPPED past {@link CraftingOrderBook#MAX_ACTIVE}.
     */
    public static CraftingOrderBook.OpenDecision requestCraft(ServerLevel level, Settlement settlement,
                                                               Building requester, Item item, int count,
                                                               CraftingOrderBook.Source source) {
        if (level == null || settlement == null || requester == null || item == null
            || level.getServer() == null || !level.getServer().isSameThread()) {
            return new CraftingOrderBook.OpenDecision(CraftingOrderBook.OpenResult.INVALID, null);
        }
        CraftingOrderSavedData saved = CraftingOrderSavedData.get(level);
        CraftingOrderBook.OpenDecision decision = open(settlement, saved.book(settlement.id),
            requester.id, requester, item, count, source, level.getGameTime());
        if (decision.result() == CraftingOrderBook.OpenResult.CREATED) saved.setDirty();
        return decision;
    }

    private static CraftingOrderBook.OpenDecision open(Settlement settlement, CraftingOrderBook book,
                                                       UUID requesterId, @Nullable Building requester,
                                                       Item item, int count,
                                                       CraftingOrderBook.Source source, long now) {
        Building workshop = resolveWorkshop(settlement, item, requester);
        return book.open(requesterId, BuiltInRegistries.ITEM.getKey(item),
            Math.min(CraftingOrderBook.MAX_COUNT, count), source,
            workshop == null ? null : workshop.id, now);
    }

    /** Item a workshop's crafter should prefer, or null. Pure read, never creates data. */
    @Nullable
    public static Item preferredOutput(ServerLevel level, @Nullable Settlement settlement,
                                       @Nullable Building building) {
        if (level == null || settlement == null || building == null) return null;
        CraftingOrderSavedData saved = CraftingOrderSavedData.existing(level);
        CraftingOrderBook book = saved == null ? null : saved.existingBook(settlement.id);
        ResourceLocation id = book == null ? null : book.preferredAt(building.id);
        if (id == null) return null;
        return BuiltInRegistries.ITEM.getOptional(id).orElse(null);
    }

    /** Credits one completed batch (called on the tick Production.run succeeded). */
    public static void noteCrafted(ServerLevel level, @Nullable Settlement settlement,
                                   @Nullable Building building, @Nullable Production.Recipe recipe) {
        if (level == null || settlement == null || building == null || recipe == null) return;
        CraftingOrderSavedData saved = CraftingOrderSavedData.existing(level);
        CraftingOrderBook book = saved == null ? null : saved.existingBook(settlement.id);
        if (book == null) return;
        if (book.noteMade(building.id, BuiltInRegistries.ITEM.getKey(recipe.output()),
                recipe.outputCount(), level.getGameTime()) != null) {
            saved.setDirty();
        }
    }

    /** Read-only view for tests and UI. */
    public static List<CraftingOrderBook.Order> orders(ServerLevel level, Settlement settlement) {
        CraftingOrderSavedData saved = CraftingOrderSavedData.existing(level);
        CraftingOrderBook book = saved == null ? null : saved.existingBook(settlement.id);
        return book == null ? List.of() : book.all();
    }

    // ------------------------------------------------------------ needs ---

    static List<Need> gatherNeeds(ServerLevel level, Settlement settlement) {
        List<Need> out = new ArrayList<>();
        for (Building building : List.copyOf(settlement.buildings)) {
            if (building == null || !building.valid || building.bounds == null) continue;
            try {
                materialAndFuelNeeds(level, settlement, building, out);
                if (building.type == BuildingType.TAVERN) tavernNeeds(level, settlement, building, out);
                if (RequestLedgerService.ammunitionTarget(building)) {
                    int deficit = RequestLedgerService.ammunitionDeficit(level, settlement, building);
                    if (deficit > 0) {
                        out.add(new Need(building.id, building, Items.ARROW, deficit,
                            CraftingOrderBook.Source.AMMUNITION, s -> s.is(Items.ARROW)));
                    }
                }
            } catch (RuntimeException failure) {
                Hearthstead.LOGGER.debug("Crafting order need skipped for {}", building.id, failure);
            }
        }
        try {
            for (EquipmentRequest request : EquipmentRequests.list(settlement)) {
                if (!request.awaitingSource() || request.destinationBuildingId() == null) continue;
                Item item = request.requirement().preferredItem();
                if (item == null || item == Items.AIR) continue;
                Building target = buildingById(settlement, request.destinationBuildingId());
                out.add(new Need(request.destinationBuildingId(), target, item,
                    Math.max(1, request.count()), CraftingOrderBook.Source.EQUIPMENT,
                    s -> request.requirement().matches(s)));
            }
            int food = RequestLedgerService.foodDeficit(level, settlement, new ItemStack(Items.BREAD));
            if (food > 0) {
                out.add(new Need(settlement.id, null, Items.BREAD, food,
                    CraftingOrderBook.Source.FOOD, CraftingOrderService::readyMeal));
            }
        } catch (RuntimeException failure) {
            Hearthstead.LOGGER.debug("Crafting order settlement needs skipped", failure);
        }
        return out;
    }

    private static void materialAndFuelNeeds(ServerLevel level, Settlement settlement,
                                             Building building, List<Need> out) {
        if (!Production.produces(building.type) || building.workers.isEmpty()
            || Production.ready(level, building) != null) {
            return;
        }
        if (Production.starvedForFuel(level, building)) {
            out.add(new Need(building.id, building, Items.CHARCOAL, FUEL_CHARCOAL,
                CraftingOrderBook.Source.FUEL, CraftingOrderService::charcoalEquivalent));
            return;
        }
        // Idle for want of inputs. Prefer an input some OTHER workshop can
        // make (the fed path); otherwise the last-listed (rough) path's input
        // is what the player is asked for.
        List<Production.Recipe> recipes = Production.of(building.type);
        Production.Recipe chosen = null;
        Item chosenItem = null;
        for (Production.Recipe recipe : recipes) {
            Item input = representative(recipe);
            if (input != null && resolveWorkshop(settlement, input, building) != null) {
                chosen = recipe;
                chosenItem = input;
                break;
            }
        }
        if (chosen == null && !recipes.isEmpty()) {
            chosen = recipes.get(recipes.size() - 1);
            chosenItem = representative(chosen);
        }
        if (chosen == null || chosenItem == null) return;
        int have = countIn(level, building, chosen.input()::test);
        int deficit = Math.min(CraftingOrderBook.MAX_COUNT,
            MATERIAL_BATCHES * chosen.inputCount() - have);
        if (deficit <= 0) return;
        Production.Recipe matched = chosen;
        out.add(new Need(building.id, building, chosenItem, deficit,
            CraftingOrderBook.Source.MATERIAL, s -> matched.input().test(s)));
    }

    private static void tavernNeeds(ServerLevel level, Settlement settlement, Building tavern,
                                    List<Need> out) {
        TavernHostService.RestockNeed meals = TavernHostService.restockNeed(level, settlement,
            tavern, new ItemStack(Items.BREAD));
        if (meals != null && meals.deficit() > 0) {
            out.add(new Need(tavern.id, tavern, Items.BREAD, meals.deficit(),
                CraftingOrderBook.Source.TAVERN, CraftingOrderService::readyMeal));
        }
        TavernHostService.RestockNeed bottles = TavernHostService.restockNeed(level, settlement,
            tavern, new ItemStack(Items.GLASS_BOTTLE));
        if (bottles != null && bottles.deficit() > 0) {
            out.add(new Need(tavern.id, tavern, Items.GLASS_BOTTLE, bottles.deficit(),
                CraftingOrderBook.Source.TAVERN, s -> s.is(Items.GLASS_BOTTLE)));
        }
    }

    private static boolean readyMeal(ItemStack stack) {
        return TavernHostService.restockKind(stack) == TavernHostService.RestockKind.READY_MEAL;
    }

    /** Warehouse fuel counted in charcoal equivalents, so logs also serve a fuel need. */
    private static boolean charcoalEquivalent(ItemStack stack) {
        return Fuel.isFuel(stack);
    }

    @Nullable
    private static Item representative(Production.Recipe recipe) {
        ItemStack[] options = recipe.input().getItems();
        for (ItemStack option : options) {
            if (!option.isEmpty()) return option.getItem();
        }
        return null;
    }

    // --------------------------------------------------------- resolvers ---

    @Nullable
    static Building resolveWorkshop(Settlement settlement, Item item, @Nullable Building requester) {
        for (Building building : settlement.buildings) {
            if (canCraft(building, item, requester)) return building;
        }
        return null;
    }

    /** Valid, staffed, not the requester itself (cycle guard), and knows a recipe for it. */
    private static boolean canCraft(@Nullable Building building, Item item, @Nullable Building requester) {
        if (building == null || !building.valid || building.bounds == null
            || building.workers.isEmpty()
            || requester != null && building.id.equals(requester.id)) {
            return false;
        }
        for (Production.Recipe recipe : Production.of(building.type)) {
            if (recipe.output() == item && !recipe.input().test(new ItemStack(item))) return true;
        }
        return false;
    }

    static int warehouseCount(ServerLevel level, Settlement settlement, Predicate<ItemStack> match) {
        int total = 0;
        for (Building building : settlement.buildings) {
            if (building != null && building.valid && building.type == BuildingType.WAREHOUSE
                && building.bounds != null) {
                total += countIn(level, building, match);
            }
        }
        return total;
    }

    private static int countIn(ServerLevel level, Building building, Predicate<ItemStack> match) {
        int total = 0;
        for (BlockPos pos : WarehouseIndex.containers(level, building)) {
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof Container container)) continue;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty() && match.test(stack)) total += stack.getCount();
            }
        }
        return total;
    }

    @Nullable
    private static Building buildingById(Settlement settlement, UUID id) {
        if (id == null) return null;
        for (Building building : settlement.buildings) {
            if (building != null && id.equals(building.id)) return building;
        }
        return null;
    }

    // ------------------------------------------------------ Tasks rows ---

    /** Existing-style Tasks rows: live orders first, then recent history. */
    public static List<RequestLedgerSnapshot.Row> snapshotRows(ServerLevel level, Settlement settlement, int limit) {
        List<RequestLedgerSnapshot.Row> rows = new ArrayList<>();
        if (limit <= 0) return rows;
        List<CraftingOrderBook.Order> ordered = new ArrayList<>();
        for (CraftingOrderBook.Order order : orders(level, settlement)) {
            if (!order.status().terminal()) ordered.add(order);
        }
        for (CraftingOrderBook.Order order : orders(level, settlement)) {
            if (order.status().terminal()) ordered.add(order);
        }
        for (CraftingOrderBook.Order order : ordered) {
            if (rows.size() >= limit) break;
            try {
                rows.add(row(level, settlement, order));
            } catch (IllegalArgumentException malformed) {
                // A row the snapshot contract refuses is skipped, never shown half-true.
            }
        }
        return rows;
    }

    private static RequestLedgerSnapshot.Row row(ServerLevel level, Settlement settlement,
                                                 CraftingOrderBook.Order order) {
        Building workshop = buildingById(settlement, order.workshopId());
        Building requester = buildingById(settlement, order.requesterId());
        int count = Math.max(1, Math.min(64, order.count()));
        int made = Math.min(count, order.made());
        RequestState state;
        RequestBlocker blocker;
        RequestSnapshotOwner owner = RequestSnapshotOwner.UNKNOWN;
        switch (order.status()) {
            case OPEN -> { state = RequestState.OPEN; blocker = RequestBlocker.AWAITING_CRAFT; }
            case CRAFTED -> { state = RequestState.PICKUP; blocker = RequestBlocker.NONE;
                owner = RequestSnapshotOwner.SOURCE; }
            case NEEDS_PLAYER -> { state = RequestState.BLOCKED; blocker = RequestBlocker.NEEDS_PLAYER; }
            case FULFILLED -> { state = RequestState.SATISFIED; blocker = RequestBlocker.NONE;
                owner = RequestSnapshotOwner.TARGET; }
            default -> { state = RequestState.CANCELLED; blocker = RequestBlocker.NONE; }
        }
        Profession profession = workshop == null ? Profession.NONE : Employment.tradeOf(workshop.type);
        return new RequestLedgerSnapshot.Row(order.id(), RequestType.CRAFT_ORDER, state,
            order.status() == CraftingOrderBook.Status.NEEDS_PLAYER ? RequestPriority.HIGH
                : RequestPriority.NORMAL,
            order.requesterId(), profession,
            workshop == null ? null : workshop.id, workshop == null ? null : workshop.anchor,
            requester == null ? null : requester.id, requester == null ? null : requester.anchor,
            null, order.itemId().toString(), "craft_order:" + order.source().id(), count,
            made, order.status() == CraftingOrderBook.Status.FULFILLED ? made : 0,
            Math.max(0L, level.getGameTime() - order.createdTick()), -1,
            order.status() == CraftingOrderBook.Status.CRAFTED, blocker, owner.value,
            false, false, false, null, false);
    }

    /** Local alias keeping the switch above readable. */
    private enum RequestSnapshotOwner {
        SOURCE(RequestLedgerSnapshot.PhysicalOwner.SOURCE),
        TARGET(RequestLedgerSnapshot.PhysicalOwner.TARGET),
        UNKNOWN(RequestLedgerSnapshot.PhysicalOwner.UNKNOWN);

        final RequestLedgerSnapshot.PhysicalOwner value;

        RequestSnapshotOwner(RequestLedgerSnapshot.PhysicalOwner value) {
            this.value = value;
        }
    }

    private CraftingOrderService() {
    }
}
