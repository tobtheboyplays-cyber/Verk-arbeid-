package com.hearthstead.settlement.work;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.TavernSeatEntity;
import com.hearthstead.entity.TavernServingEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Schedule;
import com.hearthstead.settlement.ReadyFood;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.TavernSeating;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.Set;
import java.util.UUID;

/** Loaded physical contact and bounded job selection. Items live in one serving entity. */
public final class TavernHostService {
    public static final String SESSION_KEY = "TavernServingSession";
    public static final int MAX_SCAN_CELLS = 2048;
    public static final String LOCATION_KEY = "TavernServingLocation";
    public static final String REACQUIRE_KEY = "TavernServingReacquireAfter";
    public static final String ORDER_KEY = "TavernRestaurantOrder";
    private static final String ORDER_LOCATION_KEY = "TavernRestaurantOrderLocation";
    private static final String ORDER_RECOVER_AFTER_KEY = "TavernRestaurantRecoverAfter";

    /** A Tavern uses finite physical stores, so logistics replenishes only a bounded service reserve. */
    public static final int READY_MEAL_RESERVE = 8;
    public static final int GLASS_BOTTLE_RESERVE = 4;

    public static final int TAP_WHEAT_RESERVE = 24;
    public enum RestockKind { READY_MEAL, GLASS_BOTTLE, WHEAT }

    /** Read-only deficit for the one real Tavern container that can serve both props. */
    public record RestockNeed(BlockPos container, RestockKind kind, int target, int present) {
        public int deficit() { return Math.max(0, target - present); }
    }

    /** Only existing edible goods and reusable glass can be sent to a staffed Tavern. */
    public static RestockKind restockKind(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        if (stack.is(Items.GLASS_BOTTLE)) return RestockKind.GLASS_BOTTLE;
        if (stack.is(Items.WHEAT) && stack.getComponentsPatch().isEmpty()) return RestockKind.WHEAT;
        return ReadyFood.isReadyMeal(stack) ? RestockKind.READY_MEAL : null;
    }

    public static boolean sameRestockKind(ItemStack first, ItemStack second) {
        RestockKind a = restockKind(first), b = restockKind(second);
        return a != null && a == b;
    }

    /**
     * Chooses one stable chest/barrel that can hold both ingredients. Serving
     * already requires food and bottle in the same container; counting a
     * settlement-wide aggregate here would create a permanently unservable split.
     */
    private static BlockPos serviceContainer(ServerLevel level, Building tavern, ItemStack candidate) {
        for (BlockPos pos : WarehouseIndex.containers(level, tavern)) {
            var be = level.getBlockEntity(pos);
            if (!(be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity)) continue;
            Container container = (Container) be;
            ItemStack foodPrototype = restockKind(candidate) == RestockKind.READY_MEAL
                ? candidate.copyWithCount(1) : firstReadyFoodPrototype(container);
            if (foodPrototype.isEmpty()) foodPrototype = new ItemStack(Items.BREAD);
            if (canHoldFullReserve(container, foodPrototype)) return pos;
        }
        return null;
    }

    private static int countKind(Container container, RestockKind kind) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack held = container.getItem(slot);
            if ((kind == RestockKind.GLASS_BOTTLE && held.is(Items.GLASS_BOTTLE))
                || (kind == RestockKind.READY_MEAL && ReadyFood.isReadyMeal(held))) count += held.getCount();
        }
        return count;
    }

    /**
     * Runs the exact two-class insertion against a private copy. This models
     * shared empty slots once and preserves the item components and stack caps
     * of the food prototype; it never mutates a live Tavern container.
     */
    private static boolean canHoldFullReserve(Container container, ItemStack foodPrototype) {
        SimpleContainer simulated = new SimpleContainer(container.getContainerSize());
        for (int slot = 0; slot < container.getContainerSize(); slot++)
            simulated.setItem(slot, container.getItem(slot).copy());
        int missingFood = Math.max(0, READY_MEAL_RESERVE
            - countKind(container, RestockKind.READY_MEAL));
        if (missingFood > 0 && !simulated.addItem(foodPrototype.copyWithCount(missingFood)).isEmpty()) return false;
        int missingBottles = Math.max(0, GLASS_BOTTLE_RESERVE
            - countKind(container, RestockKind.GLASS_BOTTLE));
        return missingBottles == 0
            || simulated.addItem(new ItemStack(Items.GLASS_BOTTLE, missingBottles)).isEmpty();
    }

    private static ItemStack firstReadyFoodPrototype(Container container) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack held = container.getItem(slot);
            if (ReadyFood.isReadyMeal(held)) return held.copyWithCount(1);
        }
        return ItemStack.EMPTY;
    }

    /** Publishes one loaded service-container deficit without moving or reserving anything. */
    public static RestockNeed restockNeed(ServerLevel level, Settlement settlement,
                                          Building tavern, ItemStack candidate) {
        RestockKind kind = restockKind(candidate);
        if (kind == null || level == null || settlement == null || tavern == null
            || !TavernSeating.staffed(level, settlement, tavern)
            || kind == RestockKind.WHEAT && !WorkerStorageAuthority.withinScanBudget(tavern.bounds)
            || !WarehouseIndex.fullyLoaded(level, tavern)) return null;
        if (kind == RestockKind.WHEAT) {
            for (BlockPos pos : WarehouseIndex.containers(level, tavern)) {
                if (!(level.getBlockEntity(pos) instanceof BarrelBlockEntity barrel)
                    || AleTapService.resolveTapForBarrel(level, tavern, pos) == null) continue;
                int present = 0;
                SimpleContainer simulated = new SimpleContainer(barrel.getContainerSize());
                for (int slot = 0; slot < barrel.getContainerSize(); slot++) {
                    ItemStack held = barrel.getItem(slot);
                    simulated.setItem(slot, held.copy());
                    if (restockKind(held) == RestockKind.WHEAT) present += held.getCount();
                }
                int deficit = Math.max(0, TAP_WHEAT_RESERVE - present);
                if (deficit == 0 || simulated.addItem(new ItemStack(Items.WHEAT, deficit)).isEmpty())
                    return new RestockNeed(pos, kind, TAP_WHEAT_RESERVE, Math.min(TAP_WHEAT_RESERVE, present));
            }
            return null;
        }
        BlockPos source = serviceContainer(level, tavern, candidate);
        if (source == null) return null;
        Container container = (Container) level.getBlockEntity(source);
        int target = kind == RestockKind.READY_MEAL ? READY_MEAL_RESERVE : GLASS_BOTTLE_RESERVE;
        int present = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack held = container.getItem(slot);
            if ((kind == RestockKind.GLASS_BOTTLE && held.is(Items.GLASS_BOTTLE))
                || (kind == RestockKind.READY_MEAL && ReadyFood.isReadyMeal(held))) present += held.getCount();
        }
        return new RestockNeed(source, kind, target, Math.min(target, present));
    }
    public static void noteLocation(SettlerEntity host, TavernServingEntity serving) {
        if (hasSession(host) && serving.getUUID().equals(host.getPersistentData().getUUID(SESSION_KEY)))
            host.getPersistentData().putLong(LOCATION_KEY, serving.blockPosition().asLong());
        if (serving.isRestaurantOrder() && host.level() instanceof ServerLevel level
            && level.getEntity(serving.guestId()) instanceof SettlerEntity guest
            && hasOrderForGuest(guest)
            && serving.getUUID().equals(guest.getPersistentData().getUUID(ORDER_KEY)))
            guest.getPersistentData().putLong(ORDER_LOCATION_KEY, serving.blockPosition().asLong());
    }
    /** No force load or null-lookup release: take one bounded loaded step toward the last exact location. */
    public static void reacquire(SettlerEntity host) {
        if (!(host.level() instanceof ServerLevel level) || !hasSession(host)
            || !host.getPersistentData().contains(LOCATION_KEY, 4)) return;
        long now = level.getGameTime();
        if (now < host.getPersistentData().getLong(REACQUIRE_KEY)) return;
        host.getPersistentData().putLong(REACQUIRE_KEY, now + 100);
        BlockPos last = BlockPos.of(host.getPersistentData().getLong(LOCATION_KEY));
        java.util.Set<BlockPos> targets = new java.util.LinkedHashSet<>();
        // At most 9*9*5 loaded standing cells, within eight blocks of the worker.
        // Pick useful local progress even when the final chunk is not loaded.
        double before = host.blockPosition().distSqr(last);
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) for (int y = -2; y <= 2; y++) {
            BlockPos p = host.blockPosition().offset(x, y, z);
            if (p.distSqr(last) + .25 < before && TavernSeating.clearStand(level, host, p)) targets.add(p);
        }
        if (!targets.isEmpty()) ContainerApproach.startPathToStandTargets(level, host, targets, 1.0);
        // The persisted reservation remains authoritative while access is absent.
        // Repeated unsuccessful searches yield 100 ticks, never start a new job.
    }
    public static void releaseTableClaim(ServerLevel level, TavernServingEntity serving) {
        if (serving.hasCargo() || serving.site() == null || serving.settlementId() == null) return;
        Building b = TavernSeating.building(com.hearthstead.settlement.SettlementManager.byId(level,
            serving.settlementId()), serving.site().tavernId());
        if (b != null && b.tavernServingClaims.release(serving.site().table(), serving.getUUID()))
            com.hearthstead.settlement.SettlementSavedData.get(level).setDirty();
    }
    private TavernHostService() {}

    public static boolean authorizedHost(SettlerEntity host, UUID buildingId) {
        if (!(host.level() instanceof ServerLevel level) || !host.isAlive()
            || host.getProfession() != Profession.INNKEEPER || host.getTarget() != null
            || host.hurtTime > 0 || host.isOnFire()) return false;
        var s = host.settlement();
        Building b = s == null ? null : Employment.employerOf(s, host.getUUID());
        if (b == null || !b.id.equals(buildingId) || b.type != BuildingType.TAVERN
            || !TavernSeating.staffed(level, s, b) || s.pendingRaid != null
            || s.alertUntilGameTime > level.getGameTime()) return false;
        var phase = host.dayPhase();
        if (phase.work() || phase.meal() || phase.social()) return !Schedule.shouldSleep(s, host, phase);
        // An Innkeeper finishes the order already in hand before bed, instead
        // of cancelling a seated guest's meal the moment the evening ends.
        return hasSession(host);
    }
    public static boolean hasSession(SettlerEntity host) {
        if (!host.getPersistentData().hasUUID(SESSION_KEY)) return false;
        if (host.level() instanceof ServerLevel level
            && level.getEntity(host.getPersistentData().getUUID(SESSION_KEY)) instanceof TavernServingEntity order
            && order.isRestaurantOrder() && order.isTerminalOrder() && !order.hasCargo()) {
            clearSession(host, order.getUUID());
            return false;
        }
        return true;
    }
    public static boolean hasOrderForGuest(SettlerEntity guest) {
        return guest != null && guest.getPersistentData().hasUUID(ORDER_KEY);
    }
    /** Never clear this pointer because an order entity is temporarily unloaded. */
    public static TavernServingEntity orderForGuest(SettlerEntity guest) {
        if (!(guest.level() instanceof ServerLevel level) || !guest.getPersistentData().hasUUID(ORDER_KEY)) return null;
        var entity = level.getEntity(guest.getPersistentData().getUUID(ORDER_KEY));
        return entity instanceof TavernServingEntity order && order.isRestaurantOrder()
            && guest.getUUID().equals(order.guestId()) ? order : null;
    }
    /** Departure recovery loads the exact saved owner chunk at most once per 100 ticks. */
    public static TavernServingEntity recoverOrderForDepartingGuest(SettlerEntity guest) {
        TavernServingEntity order = orderForGuest(guest);
        if (order != null || !(guest.level() instanceof ServerLevel level)
            || !hasOrderForGuest(guest)
            || !guest.getPersistentData().contains(ORDER_LOCATION_KEY, 4)
            || level.getGameTime() < guest.getPersistentData().getLong(ORDER_RECOVER_AFTER_KEY)) return order;
        guest.getPersistentData().putLong(ORDER_RECOVER_AFTER_KEY, level.getGameTime() + 100);
        BlockPos last = BlockPos.of(guest.getPersistentData().getLong(ORDER_LOCATION_KEY));
        if (level.getWorldBorder().isWithinBounds(last)) level.getChunkAt(last);
        return orderForGuest(guest);
    }
    public static String orderStatus(TavernServingEntity order) {
        return order == null ? "NONE" : order.restaurantStage().name();
    }
    public static int quotedPrice(TavernServingEntity order) { return order == null ? 0 : order.orderPrice(); }
    public static void cancelReason(TavernServingEntity order, String reason) {
        if (order != null) order.cancelOrder(reason);
    }
    /** Physical consent occurs beside the same employed host after the quote is visible. */
    public static boolean orderContact(SettlerEntity host, SettlerEntity guest, TavernServingEntity order) {
        if (host == null || guest == null || order == null || !(host.level() instanceof ServerLevel level)
            || host.level() != guest.level() || host.isRemoved() || guest.isRemoved()
            || !host.getUUID().equals(order.hostId()) || !guest.getUUID().equals(order.guestId())
            || host.settlement() != TavernSeating.visitSettlement(guest)
            || !authorizedHost(host, order.site().tavernId())
            || host.distanceToSqr(guest) > 9.0
            || !visible(guest, host.getEyePosition())) return false;
        var seatEntity = level.getEntity(order.reservedSeatId());
        return seatEntity instanceof TavernSeatEntity seat && seat.owns(guest)
            && order.site().equals(seat.site()) && !seat.isClosing();
    }
    private static BlockPos orderSource(SettlerEntity host, Building tavern) {
        if (!(host.level() instanceof ServerLevel level) || tavern == null) return null;
        for (BlockPos pos : WarehouseIndex.containers(level, tavern)) {
            var be = level.getBlockEntity(pos);
            if (!(be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity)) continue;
            Container c = (Container) be;
            boolean glass = glassSlot(c) >= 0, raw = false;
            int wheat = 0;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack held = c.getItem(i);
                if (held.is(Items.WHEAT) && held.getComponentsPatch().isEmpty()) wheat += held.getCount();
            }
            raw = wheat >= 3;
            if (glass && (foodSlot(c, host) >= 0 || raw)
                && ContainerApproach.canPlanContact(level, host, pos)) return pos.immutable();
        }
        return null;
    }
    public static boolean canOfferAle(SettlerEntity host, SettlerEntity guest) {
        if (!(host.level() instanceof ServerLevel level) || host.settlement() == null
            || !TavernGuestPayment.canPayOrder(guest, TavernGuestPayment.payer(guest),
                TavernGuestPayment.quotedPrice(guest, true))) return false;
        Building tavern = Employment.employerOf(host.settlement(), host.getUUID());
        if (tavern == null || !WorkerStorageAuthority.withinScanBudget(tavern.bounds)) return false;
        for (BlockPos source : WarehouseIndex.containers(level, tavern)) {
            BlockPos tap = AleTapService.resolveTapForBarrel(level, tavern, source);
            if (tap == null || !(level.getBlockEntity(source) instanceof BarrelBlockEntity barrel)) continue;
            for (int slot = 0; slot < barrel.getContainerSize(); slot++) {
                ItemStack held = barrel.getItem(slot);
                if (held.is(com.hearthstead.registry.ModItems.ALE.get())
                    && held.getComponentsPatch().isEmpty()) return true;
            }
        }
        return false;
    }
    /** Source transfer is made only at real contact and into the one durable order owner. */
    public static boolean takeOrderStock(SettlerEntity host, TavernServingEntity order) {
        if (!(host.level() instanceof ServerLevel level) || order == null || !order.isOrderAccepted()
            || order.restaurantStage() != TavernServingEntity.RestaurantStage.ACCEPTED
            || !host.getUUID().equals(order.hostId()) || order.source() == null
            || !authorizedHost(host, order.site().tavernId())) return false;
        Building tavern = Employment.employerOf(host.settlement(), host.getUUID());
        if (tavern == null || !tavern.contains(order.source())) return false;
        if (!ContainerApproach.inspect(level, host, order.source()).canInteract()) {
            if (order.pathAttemptDue(level.getGameTime()))
                ContainerApproach.moveToContact(level, host, order.source(), 1.0);
            return false;
        }
        var be = level.getBlockEntity(order.source());
        if (!(be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity)) return false;
        Container c = (Container) be;
        int bottleSlot = glassSlot(c);
        if (bottleSlot < 0) return false;
        int readySlot = foodSlot(c, host);
        if (readySlot >= 0) {
            ItemStack meal = c.getItem(readySlot).split(1);
            ItemStack bottle = c.getItem(bottleSlot).split(1);
            order.takeOrderIngredients(meal, bottle, ItemStack.EMPTY, 20);
            order.applyHostPreparationSkill(host);
            c.setChanged();
            return true;
        }
        int wheat = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack held = c.getItem(i);
            if (held.is(Items.WHEAT) && held.getComponentsPatch().isEmpty()) wheat += held.getCount();
        }
        if (wheat < 3) return false;
        ItemStack bottle = c.getItem(bottleSlot).split(1);
        int left = 3;
        for (int i = 0; i < c.getContainerSize() && left > 0; i++) {
            ItemStack held = c.getItem(i);
            if (!held.is(Items.WHEAT) || !held.getComponentsPatch().isEmpty()) continue;
            int amount = Math.min(left, held.getCount());
            held.shrink(amount); left -= amount;
        }
        order.takeOrderIngredients(ItemStack.EMPTY, bottle, new ItemStack(Items.WHEAT, 3), 80);
        order.applyHostPreparationSkill(host);
        c.setChanged();
        return true;
    }
    public static TavernServingEntity reserveQuote(SettlerEntity host, SettlerEntity guest,
            TavernSeatEntity seat, boolean includeAle) {
        if (!(host.level() instanceof ServerLevel level) || host.level() != guest.level()
            || seat == null || !seat.owns(guest) || seat.site() == null || seat.isClosing()
            || hasSession(host) || guest.getPersistentData().hasUUID(ORDER_KEY)
            || host.settlement() != TavernSeating.visitSettlement(guest)) return null;
        Building tavern = Employment.employerOf(host.settlement(), host.getUUID());
        if (tavern == null || !authorizedHost(host, tavern.id)
            || !tavern.id.equals(seat.site().tavernId())
            || !TavernSeating.sameSite(level, tavern, seat.site())
            || includeAle && !canOfferAle(host, guest)) return null;
        BlockPos stock = orderSource(host, tavern);
        if (stock == null) return null;
        TavernServingEntity order = ModEntities.TAVERN_SERVING.get().create(level);
        if (order == null) return null;
        order.configureOrder(host, guest, seat.site(), seat.getUUID(), stock, includeAle,
            level.getGameTime() + 1800);
        if (!tavern.tavernServingClaims.claim(seat.site().table(), order.getUUID())) return null;
        SettlementSavedData.get(level).setDirty();
        if (!level.addFreshEntity(order)) {
            tavern.tavernServingClaims.release(seat.site().table(), order.getUUID());
            SettlementSavedData.get(level).setDirty();
            return null;
        }
        host.getPersistentData().putUUID(SESSION_KEY, order.getUUID());
        guest.getPersistentData().putUUID(ORDER_KEY, order.getUUID());
        guest.getPersistentData().putLong(ORDER_LOCATION_KEY, order.blockPosition().asLong());
        noteLocation(host, order);
        return order;
    }
    public static boolean quoteAtHostContact(SettlerEntity host, SettlerEntity guest, UUID orderId) {
        TavernServingEntity order = orderForGuest(guest);
        return order != null && order.getUUID().equals(orderId)
            && order.beginQuoteAtContact((ServerLevel) host.level(), host, guest);
    }
    public static boolean acceptAtHostContact(SettlerEntity host, SettlerEntity guest, UUID orderId) {
        TavernServingEntity order = orderForGuest(guest);
        return order != null && order.getUUID().equals(orderId)
            && order.acceptOrder((ServerLevel) host.level(), host, guest);
    }
    /** Closing may finish custody cleanup, never admit a new guest or wake a sleeping host. */
    public static boolean canReturnCargo(SettlerEntity host, TavernServingEntity serving) {
        if (!(host.level() instanceof ServerLevel level) || serving == null || serving.isRemoved()
            || serving.site() == null || !host.getUUID().equals(serving.hostId()) || !host.isAlive()
            || host.isSleeping() || host.getTarget() != null || host.hurtTime > 0 || host.isOnFire()
            || host.getProfession() != Profession.INNKEEPER) return false;
        if (serving.phase() != TavernServingEntity.Phase.RETURN_TABLE
            && serving.phase() != TavernServingEntity.Phase.PICKING_UP
            && serving.phase() != TavernServingEntity.Phase.RETURNING) return false;
        Settlement settlement = host.settlement();
        Building employer = settlement == null ? null : Employment.employerOf(settlement, host.getUUID());
        return employer != null && employer.valid && employer.type == BuildingType.TAVERN
            && employer.id.equals(serving.site().tavernId()) && TavernSeating.staffed(level, settlement, employer)
            && settlement.pendingRaid == null && settlement.alertUntilGameTime <= level.getGameTime();
    }
    public static TavernServingEntity current(SettlerEntity host) {
        if (!(host.level() instanceof ServerLevel level) || !hasSession(host)) return null;
        var entity = level.getEntity(host.getPersistentData().getUUID(SESSION_KEY));
        return entity instanceof TavernServingEntity serving && host.getUUID().equals(serving.hostId()) ? serving : null;
    }
    public static void clearSession(SettlerEntity host, UUID exactId) {
        if (host.getPersistentData().hasUUID(SESSION_KEY)
            && exactId.equals(host.getPersistentData().getUUID(SESSION_KEY))) {
            host.getPersistentData().remove(SESSION_KEY);
            host.getPersistentData().remove(LOCATION_KEY);
            host.getPersistentData().remove(REACQUIRE_KEY);
        }
    }
    public static void clearOrder(SettlerEntity guest, UUID exactId) {
        if (hasOrderForGuest(guest) && exactId.equals(guest.getPersistentData().getUUID(ORDER_KEY))) {
            guest.getPersistentData().remove(ORDER_KEY);
            guest.getPersistentData().remove(ORDER_LOCATION_KEY);
            guest.getPersistentData().remove(ORDER_RECOVER_AFTER_KEY);
        }
    }
    public static boolean validGuest(SettlerEntity host, SettlerEntity guest, TavernSeating.SeatSite site) {
        return guest != null && guest.isAlive() && site != null
            && host.getSettlementId() != null && host.settlement() == TavernSeating.visitSettlement(guest)
            && guest.getVehicle() instanceof TavernSeatEntity seat && seat.owns(guest)
            && seat.hasPassenger(guest) && seat.isSettled() && !seat.isClosing() && site.equals(seat.site())
            && host.level() instanceof ServerLevel level && TavernSeating.valid(level, guest, site);
    }
    public static SettlerEntity guest(SettlerEntity host, Building b) {
        if (!(host.level() instanceof ServerLevel level) || b.bounds == null) return null;
        AABB bounds = new AABB(b.bounds.minX(), b.bounds.minY() - 1, b.bounds.minZ(),
            b.bounds.maxX() + 1, b.bounds.maxY() + 1, b.bounds.maxZ() + 1);
        int checked = 0;
        for (SettlerEntity guest : level.getEntitiesOfClass(SettlerEntity.class, bounds)) {
            if (++checked > 64) break;
            var site = TavernSeating.currentSite(guest);
            if (!guest.isBound() || guest.isTraveler()
                || guest.getPersistentData().hasUUID(ORDER_KEY)
                || !validGuest(host, guest, site) || !b.id.equals(site.tavernId())
                || guest.hasMeal() || guest.getHunger() >= 75
                || !TavernGuestPayment.canOrder(host, guest)) continue;
            if (!b.tavernServingClaims.occupied(site.table())) return guest;
        }
        return null;
    }
    /** Food and the reusable empty bottle must both exist in this real service store. */
    public static BlockPos source(SettlerEntity host, Building b) {
        return source(host, b, Set.of());
    }

    /** Skip only stores whose actual contact route exhausted this host's current pass. */
    public static BlockPos source(SettlerEntity host, Building b, Set<BlockPos> failedSources) {
        if (!(host.level() instanceof ServerLevel level) || b == null || b.bounds == null) return null;
        int cells = 0;
        for (BlockPos p : BlockPos.betweenClosed(b.bounds.minX(), b.bounds.minY(), b.bounds.minZ(),
                b.bounds.maxX(), b.bounds.maxY(), b.bounds.maxZ())) {
            if (++cells > MAX_SCAN_CELLS) break;
            if (!level.hasChunkAt(p) || failedSources.contains(p)) continue;
            var be = level.getBlockEntity(p);
            if (!(be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity)) continue;
            Container c = (Container) be;
            if (foodSlot(c, host) >= 0 && glassSlot(c) >= 0) return p.immutable();
        }
        return null;
    }
    private static int foodSlot(Container c, SettlerEntity host) {
        int result = -1, nutrition = -1;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack stack = c.getItem(i);
            var food = com.hearthstead.settlement.ReadyFood.isReadyMeal(stack) ? stack.getFoodProperties(host) : null;
            if (food != null && food.nutrition() > nutrition) { result = i; nutrition = food.nutrition(); }
        }
        return result;
    }
    private static int glassSlot(Container c) {
        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(Items.GLASS_BOTTLE)) return i;
        return -1;
    }
    /** Read only: the same exact live stock predicate as pickup, without reserving anything. */
    public static boolean mealStockAvailable(SettlerEntity host, Building tavern, BlockPos source) {
        if (!(host.level() instanceof ServerLevel level) || tavern == null || source == null
            || !tavern.contains(source) || !level.hasChunkAt(source)) return false;
        var be = level.getBlockEntity(source);
        return (be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity)
            && foodSlot((Container) be, host) >= 0 && glassSlot((Container) be) >= 0;
    }
    public static TavernServingEntity pickup(SettlerEntity host, SettlerEntity guest, Building b, BlockPos source) {
        if (!(host.level() instanceof ServerLevel level) || hasSession(host)
            || hasOrderForGuest(guest)
            || !authorizedHost(host, b.id) || !b.contains(source)
            || !ContainerApproach.inspect(level, host, source).canInteract()) return null;
        var site = TavernSeating.currentSite(guest);
        if (!validGuest(host, guest, site) || !site.tavernId().equals(b.id)
            || guest.hasMeal() || guest.getHunger() >= 75
            || !TavernGuestPayment.canOrder(host, guest)) return null;
        if (b.tavernServingClaims.occupied(site.table())) return null;
        var be = level.getBlockEntity(source);
        if (!(be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity)) return null;
        Container c = (Container) be;
        // Food and glass leave this source only. Optional Ale has its own
        // subsequent physical tap contact and cannot come free from this chest.
        int f = foodSlot(c, host), g = glassSlot(c);
        if (f < 0 || g < 0 || f == g) return null;
        TavernServingEntity serving = ModEntities.TAVERN_SERVING.get().create(level);
        if (serving == null) return null;
        serving.configure(host, guest, site, source);
        ItemStack expectedFood = c.getItem(f).copyWithCount(1), expectedGlass = c.getItem(g).copyWithCount(1);
        if (!b.tavernServingClaims.claim(site.table(), serving.getUUID())) return null;
        com.hearthstead.settlement.SettlementSavedData.get(level).setDirty();
        if (!level.addFreshEntity(serving)) {
            b.tavernServingClaims.release(site.table(), serving.getUUID());
            com.hearthstead.settlement.SettlementSavedData.get(level).setDirty();
            return null; // Source still unchanged; release only this failed join's claim.
        }
        // EntityJoinLevelEvent is extensible: re-prove source/contact/guest
        // after the join callback, before the first real item leaves storage.
        if (!authorizedHost(host, b.id) || !validGuest(host, guest, site)
            || !ContainerApproach.inspect(level, host, source).canInteract()
            || level.getBlockEntity(source) != be || !b.tavernServingClaims.owns(site.table(), serving.getUUID())
            || c.getItem(f).isEmpty() || c.getItem(g).isEmpty()
            || !ItemStack.isSameItemSameComponents(c.getItem(f), expectedFood)
            || !ItemStack.isSameItemSameComponents(c.getItem(g), expectedGlass)) {
            b.tavernServingClaims.release(site.table(), serving.getUUID());
            com.hearthstead.settlement.SettlementSavedData.get(level).setDirty();
            serving.discard(); return null;
        }
        serving.takeFrom(c.getItem(f), c.getItem(g));
        c.setChanged();
        host.getPersistentData().putUUID(SESSION_KEY, serving.getUUID());
        noteLocation(host, serving);
        return serving;
    }
    /** One bounded read-only offer; a later exact tap contact alone transfers stock. */
    public static BlockPos optionalAleTap(SettlerEntity host, SettlerEntity guest, Building tavern) {
        return optionalAleTap(host, guest, tavern, false);
    }
    /** Read-only restaurant eligibility, also used by seating before a guest walks in. */
    public static boolean orderStockAvailable(SettlerEntity host, Building tavern, BlockPos source) {
        if (!(host.level() instanceof ServerLevel level) || tavern == null || source == null
            || !tavern.contains(source) || !level.hasChunkAt(source)) return false;
        var be = level.getBlockEntity(source);
        if (!(be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity)) return false;
        Container c = (Container) be;
        if (glassSlot(c) < 0) return false;
        if (foodSlot(c, host) >= 0) return true;
        int wheat = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack held = c.getItem(i);
            if (held.is(Items.WHEAT) && held.getComponentsPatch().isEmpty()) wheat += held.getCount();
        }
        return wheat >= 3;
    }
    public static BlockPos optionalAleTap(SettlerEntity host, SettlerEntity guest, Building tavern,
                                           boolean alreadyQuoted) {
        if (!(host.level() instanceof ServerLevel level) || tavern == null
            || !WorkerStorageAuthority.withinScanBudget(tavern.bounds)
            || !alreadyQuoted && !TavernGuestPayment.canAffordAle(guest, true)) return null;
        for (BlockPos pos : WarehouseIndex.containers(level, tavern)) {
            BlockPos tap = AleTapService.resolveTapForBarrel(level, tavern, pos);
            if (tap == null || !(level.getBlockEntity(pos) instanceof BarrelBlockEntity barrel)) continue;
            for (int slot = 0; slot < barrel.getContainerSize(); slot++) {
                ItemStack held = barrel.getItem(slot);
                if (held.is(com.hearthstead.registry.ModItems.ALE.get()) && held.getComponentsPatch().isEmpty()) return tap;
            }
        }
        return null;
    }
    /** Walk to a real clear front stand; the tap service rechecks actual collider ray/reach. */
    public static void moveToTap(SettlerEntity host, BlockPos tap) {
        if (!(host.level() instanceof ServerLevel level) || tap == null || !level.hasChunkAt(tap)) return;
        var state = level.getBlockState(tap);
        if (!(state.getBlock() instanceof com.hearthstead.block.AleTapBlock)) return;
        BlockPos front = tap.relative(state.getValue(com.hearthstead.block.AleTapBlock.FACING));
        Set<BlockPos> targets = new java.util.LinkedHashSet<>();
        for (BlockPos stand : new BlockPos[]{front.below(), front})
            if (TavernSeating.clearStand(level, host, stand)) targets.add(stand);
        if (!targets.isEmpty()) ContainerApproach.startPathToStandTargets(level, host, targets, 1.0);
        Vec3 point = AleTapService.interactionPoint(level, tap);
        if (point != null) host.getLookControl().setLookAt(point.x, point.y, point.z, 25, 20);
    }
    public static boolean tableContact(SettlerEntity host, TavernServingEntity serving) {
        var site = serving.site();
        if (site == null || !(host.level() instanceof ServerLevel level)
            || !level.hasChunkAt(site.table()) || !level.hasChunkAt(site.table().above())
            || horizontalDistanceSqr(host.position(), Vec3.atBottomCenterOf(site.hostApproach())) > .0009
            || Math.abs(host.getY() - site.hostApproach().getY()) > .01
            || !level.noCollision(host)
            || !TavernSeating.clearStand(level, host, host.blockPosition())) return false;
        return visible(host, site.tabletopCenter(level).add(0, .02, 0));
    }
    /** The host goal owns MOVE/LOOK: settle its real hands toward the table before transfer motion. */
    public static boolean settleTableFacing(SettlerEntity host, TavernServingEntity serving) {
        if (!tableContact(host, serving)) return false;
        // The host may serve from any side or corner: face hostApproach -> table.
        var look = serving.site().hostLook();
        float desired = serving.site().hostYaw();
        float turn = 12.0F;
        host.getNavigation().stop();
        // Cancel a final-position steer that could otherwise turn the host
        // sideways again after the same goal tick. This does not move it.
        host.getMoveControl().setWantedPosition(host.getX(), host.getY(), host.getZ(), 0);
        host.setYRot(net.minecraft.util.Mth.approachDegrees(host.getYRot(), desired, turn));
        host.setYBodyRot(net.minecraft.util.Mth.approachDegrees(host.yBodyRot, desired, turn));
        host.setYHeadRot(net.minecraft.util.Mth.approachDegrees(host.getYHeadRot(), desired, turn));
        host.getLookControl().setLookAt(host.getX() + look.x * 2.0,
            serving.site().tabletopCenter(host.level()).y + .02,
            host.getZ() + look.z * 2.0, turn, turn);
        return Math.abs(net.minecraft.util.Mth.wrapDegrees(host.getYRot() - desired)) <= 1.0F
            && Math.abs(net.minecraft.util.Mth.wrapDegrees(host.yBodyRot - desired)) <= 1.0F
            && Math.abs(net.minecraft.util.Mth.wrapDegrees(host.getYHeadRot() - desired)) <= 1.0F;
    }
    public static boolean guestReturnContact(SettlerEntity host, SettlerEntity guest, TavernServingEntity serving) {
        return validGuest(host, guest, serving.site()) && !guest.hasMeal()
            && TavernServingEntity.handAnchor(guest).distanceToSqr(serving.glassPosition(0)) <= .625 * .625
            && visible(guest, serving.glassPosition(0));
    }
    /** Physical contact with every still-owned prop, never just the table centre. */
    public static boolean pickupContact(SettlerEntity host, TavernServingEntity serving) {
        if (!(host.level() instanceof ServerLevel level) || !level.noCollision(host)
            || !TavernSeating.clearStand(level, host, host.blockPosition())) return false;
        double progress = serving.phase() == TavernServingEntity.Phase.PICKING_UP
            ? Math.min(1, serving.transitionTicks() / 12.0) : 0;
        double lean = 1 - progress * progress * (3 - 2 * progress);
        return pickupReach(host, serving, host.position(), host.yBodyRot, lean)
            && (serving.displayFood().isEmpty() || visible(host, serving.foodPosition(0)))
            && (serving.displayGlass().isEmpty() || visible(host, serving.glassPosition(0)))
            && (serving.displayAle().isEmpty() || visible(host, serving.alePosition(0)));
    }
    /** Authored shoulder pivots (+/-6,-10,0), torso (0,-12,0), root (0,24,0).
     * The host service hinge is 30 degrees with .06-block forward translation.
     * Reserve .035 of the real .625 arm for residual idle/root interpolation;
     * the client still rejects any exact 4+6-pixel IK solution outside 2..10.
     */
    private static boolean pickupReach(SettlerEntity host, TavernServingEntity serving,
                                       Vec3 feet, float yawDegrees, double lean) {
        double scale = host.getScale(), yaw = Math.toRadians(yawDegrees), angle = Math.toRadians(30 * lean);
        if (!Double.isFinite(scale) || scale <= 0) return false;
        Vec3 forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3 lateral = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
        Vec3 middle = feet.add(forward.scale((10 * Math.sin(angle) / 16 + .06 * lean) * scale))
            .add(0, (1.501 - (12 - 10 * Math.cos(angle)) / 16) * scale, 0);
        return (serving.displayFood().isEmpty() || armReach(middle.subtract(lateral.scale(.375 * scale)),
                serving.foodPosition(0), scale))
            && (serving.displayGlass().isEmpty() || armReach(middle.add(lateral.scale(.375 * scale)),
                serving.glassPosition(0), scale));
    }
    private static boolean armReach(Vec3 shoulder, Vec3 prop, double scale) {
        double distance = shoulder.distanceTo(prop) / scale;
        return Double.isFinite(distance) && distance >= .16 && distance <= .59;
    }
    /** At most three loaded, supported approach cells; never use the occupied chair. */
    public static Vec3 pickupStand(SettlerEntity host, TavernServingEntity serving) {
        if (!(host.level() instanceof ServerLevel level) || serving.site() == null) return null;
        var site = serving.site();
        var building = TavernSeating.building(host.settlement(), site.tavernId());
        if (building == null) return null;
        Vec3 best = null;
        double bestDistance = Double.MAX_VALUE;
        for (var direction : new net.minecraft.core.Direction[]{site.dinerFacing(),
                site.dinerFacing().getClockWise(), site.dinerFacing().getCounterClockWise()}) {
            BlockPos cell = site.table().relative(direction);
            // Keep the close flank for compact layouts, then try a body-cleared
            // flank so a guest-edge glass cannot make the host overlap the chair.
            double[] offsets = direction == site.dinerFacing() ? new double[]{.8D}
                : new double[]{.75D, .5D + host.getBbWidth() / 2D + .01D};
            for (double offset : offsets) {
                Vec3 stand = Vec3.atBottomCenterOf(site.table()).add(
                    direction.getStepX() * offset, 0, direction.getStepZ() * offset);
                Vec3 anchor = serving.pickupAnchor();
                stand = direction.getStepX() == 0 ? new Vec3(anchor.x, stand.y, stand.z)
                    : new Vec3(stand.x, stand.y, anchor.z);
                float yaw = (float) (Math.toDegrees(Math.atan2(anchor.z - stand.z,
                    anchor.x - stand.x)) - 90);
                if (!building.contains(cell) || !TavernSeating.clearStand(level, host, cell)
                    || !BlockPos.containing(stand).equals(cell)
                    || !level.noCollision(host, host.getDimensions(host.getPose()).makeBoundingBox(stand))
                    || !pickupReach(host, serving, stand, yaw, 1)) continue;
                double distance = stand.distanceToSqr(serving.pickupAnchor());
                if (distance < bestDistance) { bestDistance = distance; best = stand; }
            }
        }
        return best;
    }
    public static void moveToPickup(SettlerEntity host, TavernServingEntity serving) {
        if (!(host.level() instanceof ServerLevel level)) return;
        Vec3 stand = pickupStand(host, serving);
        if (stand == null) { host.getNavigation().stop(); return; }
        if (horizontalDistanceSqr(host.position(), stand) < 1.69 && Math.abs(host.getY() - stand.y) < .1
            && level.noCollision(host, host.getBoundingBox().expandTowards(stand.subtract(host.position())))) {
            host.getNavigation().stop();
            double speed = net.minecraft.util.Mth.clamp(Math.sqrt(horizontalDistanceSqr(host.position(), stand)) * 2, .04, .5);
            host.getMoveControl().setWantedPosition(stand.x, stand.y, stand.z, speed);
        } else if (serving.pathAttemptDue(level.getGameTime())) {
            ContainerApproach.startPathToStandTargets(level, host, Set.of(BlockPos.containing(stand)), 1.0);
        }
    }
    public static boolean atPickupStand(SettlerEntity host, TavernServingEntity serving) {
        Vec3 stand = pickupStand(host, serving);
        return stand != null && horizontalDistanceSqr(host.position(), stand) <= .0009
            && Math.abs(host.getY() - stand.y) <= .01;
    }
    public static boolean settlePickupFacing(SettlerEntity host, TavernServingEntity serving) {
        if (!atPickupStand(host, serving)) return false;
        Vec3 target = serving.pickupAnchor();
        float yaw = (float) (Math.toDegrees(Math.atan2(target.z - host.getZ(), target.x - host.getX())) - 90);
        host.getLookControl().setLookAt(target.x, target.y, target.z, 12, 12);
        return holdPickupFacing(host, yaw);
    }
    /** Re-aiming at a prop already travelling into the hand would induce a spin. */
    public static boolean holdPickupFacing(SettlerEntity host, float yaw) {
        host.getNavigation().stop();
        host.getMoveControl().setWantedPosition(host.getX(), host.getY(), host.getZ(), 0);
        host.setYRot(net.minecraft.util.Mth.approachDegrees(host.getYRot(), yaw, 12));
        host.setYBodyRot(net.minecraft.util.Mth.approachDegrees(host.yBodyRot, yaw, 12));
        host.setYHeadRot(net.minecraft.util.Mth.approachDegrees(host.getYHeadRot(), yaw, 12));
        return Math.abs(net.minecraft.util.Mth.wrapDegrees(host.getYRot() - yaw)) <= 1
            && Math.abs(net.minecraft.util.Mth.wrapDegrees(host.yBodyRot - yaw)) <= 1
            && Math.abs(net.minecraft.util.Mth.wrapDegrees(host.getYHeadRot() - yaw)) <= 1;
    }

    public static boolean receivingContact(SettlerEntity guest, TavernServingEntity serving) {
        return guest != null && serving.phase() == TavernServingEntity.Phase.READY
            && guest.position().distanceToSqr(serving.foodPosition(0)) <= 6.25
            && visible(guest, serving.foodPosition(0));
    }
    /** The drink uses the same seated reach and line-of-sight proof as the meal. */
    public static boolean drinkingContact(SettlerEntity guest, TavernServingEntity serving) {
        return guest != null && serving.phase() == TavernServingEntity.Phase.DRINKING
            && guest.position().distanceToSqr(serving.alePosition(0)) <= 6.25
            && visible(guest, serving.alePosition(0));
    }
    private static boolean visible(SettlerEntity actor, Vec3 target) {
        if (!(actor.level() instanceof ServerLevel level)
            || actor.getEyePosition().distanceToSqr(target) > 9) return false;
        for (BlockPos p : BlockPos.betweenClosed(BlockPos.containing(actor.getEyePosition()), BlockPos.containing(target)))
            if (!level.hasChunkAt(p)) return false;
        return level.clip(new ClipContext(actor.getEyePosition(), target,
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, actor)).getType() == HitResult.Type.MISS;
    }
    private static double horizontalDistanceSqr(Vec3 a, Vec3 b) {
        double x = a.x - b.x, z = a.z - b.z; return x*x + z*z;
    }
    public static void moveToTable(SettlerEntity host, TavernServingEntity serving) {
        if (!(host.level() instanceof ServerLevel level) || serving.site() == null) return;
        BlockPos stand = serving.site().hostApproach();
        if (!TavernSeating.clearStand(level, host, stand)) return;
        Vec3 exact = Vec3.atBottomCenterOf(stand);
        if (horizontalDistanceSqr(host.position(), exact) < 1.69 && Math.abs(host.getY() - exact.y) < .1
            && level.noCollision(host, host.getBoundingBox().expandTowards(exact.subtract(host.position())))) {
            // Final short steer inside already-loaded collision-free space:
            // vanilla path completion slack is larger than hand/table contact.
            host.getNavigation().stop();
            double speed = net.minecraft.util.Mth.clamp(Math.sqrt(horizontalDistanceSqr(host.position(), exact)) * 2, .04, .5);
            host.getMoveControl().setWantedPosition(exact.x, exact.y, exact.z, speed);
            return;
        }
        if (serving.pathAttemptDue(level.getGameTime()))
            ContainerApproach.startPathToStandTargets(level, host, Set.of(stand), 1.0);
    }
    public static boolean returnAtSource(SettlerEntity host, TavernServingEntity serving,
                                         ItemStack food, ItemStack glass, ItemStack ale) {
        if (!(host.level() instanceof ServerLevel level) || serving.source() == null) return false;
        Building b = TavernSeating.building(host.settlement(), serving.site().tavernId());
        if (b == null || !b.contains(serving.source())
            || !authorizedHost(host, b.id) && !canReturnCargo(host, serving)) return false;
        if (!ContainerApproach.inspect(level, host, serving.source()).canInteract()) {
            if (serving.pathAttemptDue(level.getGameTime())) ContainerApproach.moveToContact(level, host, serving.source(), 1.0); return false;
        }
        var be = level.getBlockEntity(serving.source());
        if (!(be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity)) return false;
        Container c = (Container) be;
        boolean changed = insert(c, food) | insert(c, glass) | insert(c, ale);
        if (changed) c.setChanged();
        return changed;
    }
    public static boolean returnOrderIngredients(SettlerEntity host, TavernServingEntity serving, ItemStack owned) {
        if (owned.isEmpty()) return true;
        if (!(host.level() instanceof ServerLevel level) || serving.source() == null) return false;
        if (!ContainerApproach.inspect(level, host, serving.source()).canInteract()) {
            if (serving.pathAttemptDue(level.getGameTime()))
                ContainerApproach.moveToContact(level, host, serving.source(), 1.0);
            return false;
        }
        var be = level.getBlockEntity(serving.source());
        if (!(be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity)) return false;
        Container c = (Container) be;
        boolean changed = insert(c, owned);
        if (changed) c.setChanged();
        return owned.isEmpty();
    }
    private static boolean insert(Container c, ItemStack owned) {
        if (owned.isEmpty()) return false;
        for (int slot = 0; slot < c.getContainerSize(); slot++) {
            if (!c.canPlaceItem(slot, owned)) continue;
            ItemStack existing = c.getItem(slot);
            if (existing.isEmpty()) { c.setItem(slot, owned.split(1)); return true; }
            if (ItemStack.isSameItemSameComponents(existing, owned)
                && existing.getCount() < Math.min(c.getMaxStackSize(), existing.getMaxStackSize())) {
                existing.grow(1); owned.shrink(1); return true;
            }
        }
        return false;
    }
}
