package com.hearthstead.entity;

import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.DeferredItemMaterializationSavedData;
import com.hearthstead.settlement.TavernSeating;
import com.hearthstead.settlement.work.TavernHostService;
import com.hearthstead.settlement.work.TavernGuestPayment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.util.Optional;
import java.util.UUID;

/** One durable physical service owner; new restaurant orders use this same entity UUID. */
public final class TavernServingEntity extends Entity {
    // Keep old ordinals stable; DRINKING is appended for old-save compatibility.
    public enum Phase { CARRYING, PLACING, SLIDING, READY, DINING, RETURN_TABLE, PICKING_UP, RETURNING, RETURN_SLIDING, DRINKING }
    /** New order progression is separately saved; old Phase ordinals and sessions stay intact. */
    public enum RestaurantStage { NONE, QUOTED, ACCEPTED, PREPARING, WAITING_SEAT, SERVING, COMPLETE, CANCELLED, REFUND_PENDING }
    public static final int SLIDE_TICKS = 20;
    public static final int TRANSITION_TICKS = 12;
    public static final int RECEIVING_TICKS = 8;
    public static final int DRINK_LIFT_TICKS = 8;
    public static final int DRINK_SIP_TICKS = 8;
    public static final int DRINK_LOWER_TICKS = 8;
    public static final int DRINK_TOTAL_TICKS = DRINK_LIFT_TICKS + DRINK_SIP_TICKS + DRINK_LOWER_TICKS;
    private static final EntityDataAccessor<Integer> RETURN_PREPARATION = SynchedEntityData.defineId(TavernServingEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> RECEIVING = SynchedEntityData.defineId(TavernServingEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> TRANSITION = SynchedEntityData.defineId(TavernServingEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> YAW = SynchedEntityData.defineId(TavernServingEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<CompoundTag> PRESENTATION = SynchedEntityData.defineId(TavernServingEntity.class, EntityDataSerializers.COMPOUND_TAG);
    private static final EntityDataAccessor<Boolean> TABLE_ANCHOR = SynchedEntityData.defineId(TavernServingEntity.class, EntityDataSerializers.BOOLEAN);
    private Vec3 transitionStart = Vec3.ZERO;
    private Vec3 transitionEnd = Vec3.ZERO;
    private float transitionStartYaw;
    private float transitionEndYaw;
    private static final EntityDataAccessor<ItemStack> FOOD = SynchedEntityData.defineId(TavernServingEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<ItemStack> GLASS = SynchedEntityData.defineId(TavernServingEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<ItemStack> ALE = SynchedEntityData.defineId(TavernServingEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<ItemStack> PAYMENT = SynchedEntityData.defineId(TavernServingEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<Integer> DRINKING = SynchedEntityData.defineId(TavernServingEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> PHASE = SynchedEntityData.defineId(TavernServingEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> SLIDE = SynchedEntityData.defineId(TavernServingEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> FACING = SynchedEntityData.defineId(TavernServingEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Optional<UUID>> HOST = SynchedEntityData.defineId(TavernServingEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Optional<UUID>> GUEST = SynchedEntityData.defineId(TavernServingEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private ItemStack food = ItemStack.EMPTY;
    private ItemStack glass = ItemStack.EMPTY;
    private ItemStack ale = ItemStack.EMPTY;
    private ItemStack payment = ItemStack.EMPTY;
    private RestaurantStage restaurantStage = RestaurantStage.NONE;
    private UUID reservedSeatId;
    private long orderExpiresAt, quoteStartedAt = -1, acceptedAt;
    private int orderPrice, mealPrice, preparationTicks;
    private boolean orderedAle, mealSettled, aleSettled;
    private String orderCancelReason = "";
    private TavernGuestPayment.OrderPayer orderPayer;
    private ItemStack orderEscrow = ItemStack.EMPTY, ingredients = ItemStack.EMPTY;
    private Vec3 deathRefundPoint;
    private boolean deathRefundQueued;
    private BlockPos tapTarget, tapSource, tapBarrel;
    private boolean tapAttempted, aleDeclined;
    private int tapApproachTicks;
    private UUID settlement;
    private TavernSeating.SeatSite site;
    private BlockPos source;
    private int elapsed;
    private int returnElapsed;
    private int diningTicks;
    // Saved server-authored mouth point keeps the actual glass motion stable over reload.
    private Vec3 drinkMouth = Vec3.ZERO;
    private long lastServiceTick = Long.MIN_VALUE;
    private long lastPathTick = Long.MIN_VALUE;
    public boolean pathAttemptDue(long now) {
        if (lastPathTick != Long.MIN_VALUE && now - lastPathTick < 40) return false;
        lastPathTick = now; return true;
    }
    private CompoundTag quarantine;
    // Client samples the complete world prop points, so changing from hand to
    // table-centre coordinates cannot create a representation-dependent jump.
    private Vec3 previousFoodPoint, currentFoodPoint, previousGlassPoint, currentGlassPoint;
    private Vec3 previousAlePoint, currentAlePoint;
    private float previousPresentationYaw, currentPresentationYaw;
    private CompoundTag previousClockSnapshot, currentClockSnapshot;

    public TavernServingEntity(EntityType<? extends TavernServingEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
    }
    private boolean paidVisitorMeal;
    public UUID hostId() { return entityData.get(HOST).orElse(null); }
    public UUID guestId() { return entityData.get(GUEST).orElse(null); }
    public UUID settlementId() { return settlement; }
    public TavernSeating.SeatSite site() { return site; }
    public BlockPos source() { return source; }
    public Phase phase() { return Phase.values()[entityData.get(PHASE)]; }
    public int slideTicks() { return entityData.get(SLIDE); }
    public int transitionTicks() { return entityData.get(TRANSITION); }
    public int receivingTicks() { return entityData.get(RECEIVING); }
    public int returnPreparationTicks() { return entityData.get(RETURN_PREPARATION); }
    public int returnSlideTicks() { return SLIDE_TICKS - slideTicks(); }
    /** The physical pair centre, retained even when food has already been eaten. */
    public Vec3 pickupAnchor() { return itemAnchor(); }
    public float presentationSlideTicks(float partial) { return presentationClock("Slide", slideTicks(), partial); }
    public float presentationTransitionTicks(float partial) { return presentationClock("Transition", transitionTicks(), partial); }
    public float presentationReceivingTicks(float partial) { return presentationClock("Receiving", receivingTicks(), partial); }
    public float presentationReturnPreparationTicks(float partial) { return presentationClock("Preparation", Math.min(20, diningTicks), partial); }
    private float presentationClock(String key, int serverValue, float partial) {
        return level().isClientSide && currentClockSnapshot != null
            ? net.minecraft.util.Mth.lerp(net.minecraft.util.Mth.clamp(partial, 0, 1),
                (float) previousClockSnapshot.getInt(key), (float) currentClockSnapshot.getInt(key)) : serverValue;
    }
    @Override public boolean isIgnoringBlockTriggers() { return true; }
    public float presentationYaw(float partialTick) {
        return level().isClientSide && currentFoodPoint != null
            ? net.minecraft.util.Mth.rotLerp(net.minecraft.util.Mth.clamp(partialTick, 0, 1),
                previousPresentationYaw, currentPresentationYaw) : entityData.get(YAW);
    }
    public Direction facing() { return Direction.from3DDataValue(entityData.get(FACING)); }
    public ItemStack displayFood() { return entityData.get(FOOD).copy(); }
    public ItemStack displayGlass() { return entityData.get(GLASS).copy(); }
    public ItemStack displayAle() { return entityData.get(ALE).copy(); }
    public ItemStack displayPayment() { return entityData.get(PAYMENT).copy(); }
    public BlockPos tapSource() { return tapSource; }
    public BlockPos tapBarrel() { return tapBarrel; }
    public int drinkingTicks() { return entityData.get(DRINKING); }
    public boolean hasCargo() { return quarantine != null || !food.isEmpty() || !glass.isEmpty() || !ale.isEmpty() || !payment.isEmpty() || !ingredients.isEmpty() || !orderEscrow.isEmpty(); }
    public boolean isRestaurantOrder() { return restaurantStage != RestaurantStage.NONE; }
    public RestaurantStage restaurantStage() { return restaurantStage; }
    public UUID reservedSeatId() { return reservedSeatId; }
    public long orderExpiresAt() { return orderExpiresAt; }
    public boolean isOrderPaid() { return isOrderAccepted(); }
    public boolean isOrderAccepted() { return restaurantStage == RestaurantStage.ACCEPTED
        || restaurantStage == RestaurantStage.PREPARING || restaurantStage == RestaurantStage.WAITING_SEAT
        || restaurantStage == RestaurantStage.SERVING || restaurantStage == RestaurantStage.COMPLETE; }
    public boolean isTerminalOrder() {
        return restaurantStage == RestaurantStage.COMPLETE || restaurantStage == RestaurantStage.CANCELLED;
    }
    public int orderPrice() { return orderPrice; }
    public int mealPrice() { return mealPrice; }
    public boolean orderedAle() { return orderedAle; }
    public long quoteStartedAt() { return quoteStartedAt; }
    public String orderCancelReason() { return orderCancelReason; }
    public boolean mealSettled() { return mealSettled; }
    public ItemStack orderEscrow() { return orderEscrow.copy(); }
    public boolean deathRefundQueued() { return deathRefundQueued; }
    private boolean recoverDeadPayer(ServerLevel level, SettlerEntity guest) {
        if (orderPayer != TavernGuestPayment.OrderPayer.TRAVELER_BAG || orderEscrow.isEmpty()) return true;
        if (deathRefundPoint == null) {
            if (guest == null || guest.isAlive() || !guest.getUUID().equals(guestId())) return false;
            deathRefundPoint = guest.position().add(0, .3, 0);
        }
        UUID stable = UUID.nameUUIDFromBytes(("hearthstead:tavern_death_refund:" + getUUID())
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        CompoundTag metadata = new CompoundTag();
        metadata.putUUID("HearthsteadTavernRefundOrder", getUUID());
        metadata.putUUID("HearthsteadTavernRefundPayer", guestId());
        var options = new DeferredItemMaterializationSavedData.ItemEntityOptions(metadata, 10,
            null, true, true);
        var recovery = DeferredItemMaterializationSavedData.get(level);
        var result = recovery.queue(level, stable, deathRefundPoint.x, deathRefundPoint.y,
            deathRefundPoint.z, orderEscrow.copy(), options);
        if (!result.accepted()) return false; // Source remains the exact owner on capacity/collision.
        orderEscrow = ItemStack.EMPTY;
        deathRefundQueued = true;
        recovery.materialize(level, stable); // Saved queue retries if the chunk rejects the immediate spawn.
        return true;
    }
    public void configureOrder(SettlerEntity host, SettlerEntity guest,
            TavernSeating.SeatSite seat, UUID seatId, BlockPos stock, boolean includeAle,
            long deadline) {
        if (seatId == null || deadline <= level().getGameTime() || deadline - level().getGameTime() > 1800)
            throw new IllegalArgumentException("Invalid bounded Tavern order");
        configure(host, guest, seat, stock);
        reservedSeatId = seatId;
        restaurantStage = RestaurantStage.QUOTED;
        orderExpiresAt = deadline;
        orderedAle = includeAle;
        mealPrice = TavernGuestPayment.mealPrice(guest);
        orderPrice = TavernGuestPayment.quotedPrice(guest, includeAle);
        orderPayer = TavernGuestPayment.payer(guest);
    }
    public boolean beginQuoteAtContact(ServerLevel level, SettlerEntity host, SettlerEntity guest) {
        if (restaurantStage != RestaurantStage.QUOTED
            || !TavernHostService.orderContact(host, guest, this)) return false;
        if (quoteStartedAt >= 0) return true;
        quoteStartedAt = level.getGameTime();
        guest.showTavernCue(com.hearthstead.entity.TavernCue.QUOTED, orderPrice, 60);
        return true;
    }
    public boolean acceptOrder(ServerLevel level, SettlerEntity host, SettlerEntity guest) {
        if (!host.getUUID().equals(hostId()) || !guest.getUUID().equals(guestId())
            || !(guest.level() instanceof ServerLevel) || host.level() != guest.level()) return false;
        if (restaurantStage == RestaurantStage.ACCEPTED || restaurantStage == RestaurantStage.PREPARING
            || restaurantStage == RestaurantStage.WAITING_SEAT || restaurantStage == RestaurantStage.SERVING
            || restaurantStage == RestaurantStage.COMPLETE) return true;
        if (restaurantStage != RestaurantStage.QUOTED || quoteStartedAt < 0
            || level.getGameTime() - quoteStartedAt < 30 || level.getGameTime() >= orderExpiresAt
            || orderPayer != TavernGuestPayment.payer(guest)
            || orderPrice != TavernGuestPayment.quotedPrice(guest, orderedAle)
            || !TavernHostService.orderContact(host, guest, this)) return false;
        if (!TavernGuestPayment.canPayOrder(guest, orderPayer, orderPrice)) {
            guest.showTavernCue(com.hearthstead.entity.TavernCue.NO_COINS, 0, 80);
            cancelOrder("no_coins");
            return false;
        }
        ItemStack paid = orderPrice == 0 ? ItemStack.EMPTY
            : TavernGuestPayment.debitOrder(guest, orderPayer, orderPrice);
        if (orderPrice > 0 && (paid.isEmpty() || paid.getCount() != orderPrice)) return false;
        orderEscrow = paid;
        acceptedAt = level.getGameTime();
        restaurantStage = RestaurantStage.ACCEPTED;
        if (orderPrice > 0) guest.showTavernCue(com.hearthstead.entity.TavernCue.PAYING, orderPrice, 50);
        else guest.showTavernCue(com.hearthstead.entity.TavernCue.WAITING, 0, 50);
        return true;
    }
    public void takeOrderIngredients(ItemStack readyMeal, ItemStack bottle, ItemStack rawWheat, int ticks) {
        if (restaurantStage != RestaurantStage.ACCEPTED || !food.isEmpty() || !glass.isEmpty()
            || !ingredients.isEmpty() || bottle.isEmpty() || bottle.getCount() != 1
            || (readyMeal.isEmpty() == rawWheat.isEmpty()) || ticks != (rawWheat.isEmpty() ? 20 : 80))
            throw new IllegalStateException("Invalid Tavern order source transfer");
        food = readyMeal; glass = bottle; ingredients = rawWheat;
        preparationTicks = ticks;
        restaurantStage = RestaurantStage.PREPARING;
        syncCargo();
    }
    /**
     * Trade skill (Innkeeper primary, Presence): shortens the preparation
     * WAIT at the source between phases; the host's clips, the slide and the
     * guest's receiving/drinking frames are untouched. Identity at level 1.
     */
    public void applyHostPreparationSkill(SettlerEntity host) {
        if (restaurantStage != RestaurantStage.PREPARING || host == null
            || host.getProfession() != com.hearthstead.entity.Profession.INNKEEPER) return;
        preparationTicks = Math.max(1,
            com.hearthstead.entity.SkillLevels.shortenWait(host, preparationTicks));
    }
    /** Test/UI read of the remaining preparation wait. */
    public int preparationTicksLeft() { return preparationTicks; }
    public void cancelOrder(String reason) {
        if (!isRestaurantOrder() || restaurantStage == RestaurantStage.COMPLETE
            || restaurantStage == RestaurantStage.CANCELLED) return;
        orderCancelReason = reason == null ? "cancelled" : reason;
        restaurantStage = RestaurantStage.REFUND_PENDING;
        if (!food.isEmpty() || !glass.isEmpty() || !ale.isEmpty()) beginReturn();
    }

    /** Called only after AleTapService has planned a real adjacent-barrel withdrawal. */
    public boolean acceptTappedAle(ItemStack oneAle, BlockPos tap, BlockPos barrel) {
        if (!(level() instanceof ServerLevel level) || level.getEntity(getUUID()) != this
            || isRemoved() || phase() != Phase.CARRYING || tapSource != null || !ale.isEmpty()
            || glass.isEmpty() || !oneAle.is(ModItems.ALE.get()) || oneAle.getCount() != 1
            || !oneAle.getComponentsPatch().isEmpty() || tap == null || barrel == null || site == null) return false;
        SettlerEntity host = level.getEntity(hostId()) instanceof SettlerEntity actor ? actor : null;
        SettlerEntity guest = level.getEntity(guestId()) instanceof SettlerEntity actor ? actor : null;
        var tavern = host == null ? null : TavernSeating.building(host.settlement(), site.tavernId());
        if (host == null || guest == null || tavern == null
            || !TavernHostService.validGuest(host, guest, site)
            || isRestaurantOrder() && (!orderedAle || restaurantStage != RestaurantStage.SERVING)
            || !isRestaurantOrder() && !com.hearthstead.settlement.work.TavernGuestPayment.canAffordAle(guest, true)
            || !com.hearthstead.settlement.work.AleTapService.hostContact(host, tavern, tap)
            || com.hearthstead.settlement.work.AleTapService.barrel(level, tap) == null
            || !barrel.equals(com.hearthstead.settlement.work.AleTapService.barrel(level, tap).getBlockPos())) return false;
        ale = oneAle.copy(); tapSource = tap.immutable(); tapBarrel = barrel.immutable();
        tapAttempted = true; syncCargo(); return true;
    }
    /** Typed return seams; only an exact planned tap deposit may take these owned stacks. */
    public ItemStack removeTapPayment(int count) {
        if (!(level() instanceof ServerLevel level) || level.getEntity(getUUID()) != this
            || isRemoved() || phase() != Phase.RETURNING || tapSource == null || count <= 0 || count > payment.getCount()) return ItemStack.EMPTY;
        ItemStack removed = payment.split(count); syncCargo(); return removed;
    }
    public ItemStack removeUntastedTapAle(int count) {
        if (!(level() instanceof ServerLevel level) || level.getEntity(getUUID()) != this
            || isRemoved() || phase() != Phase.RETURNING || tapSource == null || count <= 0 || count > ale.getCount()) return ItemStack.EMPTY;
        ItemStack removed = ale.split(count); syncCargo(); return removed;
    }

    /** Empty entity joins successfully before source mutation is allowed. */
    public void configure(SettlerEntity host, SettlerEntity guest, TavernSeating.SeatSite site, BlockPos source) {
        entityData.set(HOST, Optional.of(host.getUUID()));
        entityData.set(GUEST, Optional.of(guest.getUUID()));
        entityData.set(FACING, site.dinerFacing().get3DDataValue());
        paidVisitorMeal = guest.isTraveler();
        settlement = host.getSettlementId(); this.site = site; this.source = source.immutable();
        follow(host);
    }
    /** Package-independent authority seam; caller already proved both source slots/contact. */
    public void takeFrom(ItemStack liveFood, ItemStack liveGlass) {
        takeFrom(liveFood, liveGlass, ItemStack.EMPTY);
    }
    /** Optional ALE stays an exact, persistent serving-owner stack. */
    public void takeFrom(ItemStack liveFood, ItemStack liveGlass, ItemStack liveAle) {
        if (level().isClientSide || hasCargo() || liveFood.isEmpty() || liveFood.getFoodProperties(null) == null
            || !liveGlass.is(net.minecraft.world.item.Items.GLASS_BOTTLE)
            || !liveAle.isEmpty() && !liveAle.is(ModItems.ALE.get()))
            throw new IllegalStateException("Invalid Tavern source transfer");
        food = liveFood.split(1); glass = liveGlass.split(1);
        ale = liveAle.isEmpty() ? ItemStack.EMPTY : liveAle.split(1);
        // Compatibility callers may restore old owned stock, but only a
        // physically sourced tap serving may start a new paid drink.
        aleDeclined = !ale.isEmpty();
        syncCargo();
    }
    private void syncCargo() {
        entityData.set(FOOD, food.copy()); entityData.set(GLASS, glass.copy());
        entityData.set(ALE, ale.copy());
        entityData.set(PAYMENT, payment.copy());
    }
    public void beginReturn() {
        if (phase() == Phase.RETURNING || phase() == Phase.RETURN_TABLE || phase() == Phase.PICKING_UP) return;
        entityData.set(DRINKING, 0); // Interrupted reach can never accumulate across a return/retry.
        entityData.set(PHASE, (phase() == Phase.CARRYING ? Phase.RETURNING : Phase.RETURN_TABLE).ordinal());
    }
    private static Vec3 mouthPoint(SettlerEntity guest) {
        double yaw = Math.toRadians(guest.yBodyRot);
        Vec3 forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3 left = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
        return guest.getEyePosition().add(forward.scale(.10)).add(left.scale(.08)).add(0, -.18, 0);
    }
    public static Vec3 handAnchor(SettlerEntity host) {
        double yaw = Math.toRadians(host.yBodyRot);
        return host.position().add(-Math.sin(yaw) * .35, 1.05, Math.cos(yaw) * .35);
    }
    public void follow(SettlerEntity host) {
        setPos(handAnchor(host)); entityData.set(YAW, host.yBodyRot);
        entityData.set(TABLE_ANCHOR, false);
        syncPresentation();
    }
    private Vec3 tableEdge(ServerLevel level) {
        Direction front = site.dinerFacing();
        return site.tabletopCenter(level).add(front.getStepX() * .30, 0, front.getStepZ() * .30);
    }
    private Vec3 itemAnchor() {
        if (!entityData.get(TABLE_ANCHOR)) return position();
        double along = .30 - .60 * slideTicks() / SLIDE_TICKS;
        return position().add(facing().getStepX() * along, 0, facing().getStepZ() * along);
    }
    public Vec3 foodPosition(float partialTick) {
        return level().isClientSide && currentFoodPoint != null
            ? previousFoodPoint.lerp(currentFoodPoint, net.minecraft.util.Mth.clamp(partialTick, 0, 1))
            : propPosition(partialTick, -.16);
    }
    public Vec3 glassPosition(float partialTick) {
        Vec3 tabletop = level().isClientSide && currentGlassPoint != null
            ? previousGlassPoint.lerp(currentGlassPoint, net.minecraft.util.Mth.clamp(partialTick, 0, 1))
            : propPosition(partialTick, .16);
        if (phase() != Phase.DRINKING) return tabletop;
        // Entity data can reach a client one packet before its presentation target.
        // Hold the real glass on the table for that frame; never interpolate toward Vec3.ZERO.
        if (level().isClientSide && !entityData.get(PRESENTATION).contains("DrinkX")) return tabletop;
        return tabletop.lerp(drinkMouthPosition(), drinkProgress(partialTick));
    }
    /** The filled ale occupies the same owned reusable glass through lift, sip and lower. */
    public Vec3 alePosition(float partialTick) { return glassPosition(partialTick); }
    private Vec3 drinkMouthPosition() {
        CompoundTag snapshot = entityData.get(PRESENTATION);
        if (level().isClientSide && snapshot.contains("DrinkX")) return new Vec3(
            snapshot.getDouble("DrinkX"), snapshot.getDouble("DrinkY"), snapshot.getDouble("DrinkZ"));
        return drinkMouth;
    }
    private float drinkProgress(float partialTick) {
        float t = presentationDrinkingTicks(partialTick);
        if (t <= DRINK_LIFT_TICKS) return smooth(t / DRINK_LIFT_TICKS);
        if (t <= DRINK_LIFT_TICKS + DRINK_SIP_TICKS) return 1;
        return 1 - smooth((t - DRINK_LIFT_TICKS - DRINK_SIP_TICKS) / DRINK_LOWER_TICKS);
    }
    private Vec3 propPosition(float partialTick, double lateral) {
        if (level().isClientSide && entityData.get(PRESENTATION).contains("X")) {
            CompoundTag snapshot = entityData.get(PRESENTATION);
            double yaw = Math.toRadians(snapshot.getFloat("Yaw"));
            return new Vec3(snapshot.getDouble("X") + Math.cos(yaw) * lateral,
                snapshot.getDouble("Y") + .002, snapshot.getDouble("Z") + Math.sin(yaw) * lateral);
        }
        Vec3 anchor = itemAnchor();
        if (entityData.get(TABLE_ANCHOR) && phase() == Phase.SLIDING) {
            double step = Math.min(SLIDE_TICKS - slideTicks(), Math.max(0, Math.min(1, partialTick))) * -.60 / SLIDE_TICKS;
            anchor = anchor.add(facing().getStepX() * step, 0, facing().getStepZ() * step);
        }
        if (entityData.get(TABLE_ANCHOR) && phase() == Phase.RETURN_SLIDING) {
            double step = Math.min(slideTicks(), Math.max(0, Math.min(1, partialTick))) * .60 / SLIDE_TICKS;
            anchor = anchor.add(facing().getStepX() * step, 0, facing().getStepZ() * step);
        }
        double yaw = Math.toRadians(entityData.get(YAW));
        return anchor.add(Math.cos(yaw) * lateral, .002, Math.sin(yaw) * lateral);
    }
    public float presentationDrinkingTicks(float partial) { return presentationClock("Drinking", drinkingTicks(), partial); }
    private static float smooth(float x) {
        x = net.minecraft.util.Mth.clamp(x, 0, 1); return x * x * (3 - 2 * x);
    }
    private void syncPresentation() {
        if (level().isClientSide || quarantine != null) return;
        Vec3 center = itemAnchor();
        if (!Double.isFinite(center.lengthSqr()) || !Float.isFinite(entityData.get(YAW))) return;
        CompoundTag snapshot = new CompoundTag();
        snapshot.putDouble("X", center.x); snapshot.putDouble("Y", center.y); snapshot.putDouble("Z", center.z);
        snapshot.putFloat("Yaw", entityData.get(YAW));
        snapshot.putInt("Slide", slideTicks()); snapshot.putInt("Transition", transitionTicks());
        snapshot.putInt("Receiving", receivingTicks()); snapshot.putInt("Preparation", Math.min(20, diningTicks));
        snapshot.putInt("Drinking", drinkingTicks());
        if (phase() == Phase.DRINKING && Double.isFinite(drinkMouth.lengthSqr())) {
            snapshot.putDouble("DrinkX", drinkMouth.x); snapshot.putDouble("DrinkY", drinkMouth.y); snapshot.putDouble("DrinkZ", drinkMouth.z);
        }
        // A single data entry is independent of vanilla's separate position packet.
        entityData.set(PRESENTATION, snapshot);
    }
    private void beginTransition(Phase phase, Vec3 end, float endYaw) {
        // Convert a table-centre anchor to the exact current item-pair centre
        // before changing phase. No jump to the end of an interrupted slide.
        Vec3 current = itemAnchor(); setPos(current); entityData.set(TABLE_ANCHOR, false);
        transitionStart = current; transitionEnd = end;
        transitionStartYaw = entityData.get(YAW); transitionEndYaw = endYaw;
        entityData.set(TRANSITION, 0); entityData.set(PHASE, phase.ordinal());
    }
    private boolean advanceTransition() {
        int tick = Math.min(TRANSITION_TICKS, transitionTicks() + 1);
        entityData.set(TRANSITION, tick);
        double t = (double) tick / TRANSITION_TICKS;
        setPos(transitionStart.lerp(transitionEnd, t));
        float yaw = transitionStartYaw + net.minecraft.util.Mth.wrapDegrees(transitionEndYaw - transitionStartYaw) * (float)t;
        entityData.set(YAW, yaw);
        return tick == TRANSITION_TICKS;
    }

    /** Called by the actual host goal, not automatically by proximity alone. */
    public void serviceTick(SettlerEntity host) {
        try { serviceTickInternal(host); }
        finally { syncPresentation(); }
    }
    private void serviceTickInternal(SettlerEntity host) {
        if (!(level() instanceof ServerLevel level) || quarantine != null
            || !host.getUUID().equals(hostId()) || lastServiceTick == level.getGameTime()) return;
        lastServiceTick = level.getGameTime();
        if (!TavernHostService.authorizedHost(host, site == null ? null : site.tavernId())
            && !TavernHostService.canReturnCargo(host, this)) {
            if (isRestaurantOrder()) cancelOrder("host_unavailable");
            beginReturn(); return;
        }
        SettlerEntity guest = level.getEntity(guestId()) instanceof SettlerEntity s ? s : null;
        if (isRestaurantOrder() && restaurantStage == RestaurantStage.REFUND_PENDING
            && phase() == Phase.RETURNING && food.isEmpty() && glass.isEmpty()
            && ale.isEmpty() && payment.isEmpty()) {
            if (!TavernHostService.returnOrderIngredients(host, this, ingredients)) return;
            if (guest == null || !TavernGuestPayment.refundOrder(guest, orderPayer, orderEscrow)) return;
            restaurantStage = RestaurantStage.CANCELLED;
            guest.showTavernCue(com.hearthstead.entity.TavernCue.CANCELLED, 0, 60);
            return;
        }
        if (phase() == Phase.RETURNING) {
            follow(host);
            if (tapSource != null && (!ale.isEmpty() || !payment.isEmpty())) {
                var tavern = TavernSeating.building(host.settlement(), site.tavernId());
                if (tavern != null && com.hearthstead.settlement.work.AleTapService
                        .depositServiceCargoAtContact(host, tavern, tapSource, this)) syncCargo();
                else if (pathAttemptDue(level.getGameTime())) TavernHostService.moveToTap(host, tapSource);
                return; // Never redirect tapped stock or physical income into the food store.
            }
            if (TavernHostService.returnAtSource(host, this, food, glass, ale)) syncCargo();
            return;
        }
        if (isRestaurantOrder()) {
            if (level.getGameTime() >= orderExpiresAt && restaurantStage != RestaurantStage.COMPLETE
                && restaurantStage != RestaurantStage.REFUND_PENDING) cancelOrder("expired");
            if (restaurantStage == RestaurantStage.REFUND_PENDING
                && phase() != Phase.RETURN_TABLE && phase() != Phase.PICKING_UP) {
                if (!food.isEmpty() || !glass.isEmpty() || !ale.isEmpty() || !payment.isEmpty()) {
                    beginReturn(); return;
                }
                if (!TavernHostService.returnOrderIngredients(host, this, ingredients)) return;
                if (guest == null || !TavernGuestPayment.refundOrder(guest, orderPayer, orderEscrow)) return;
                restaurantStage = RestaurantStage.CANCELLED;
                guest.showTavernCue(com.hearthstead.entity.TavernCue.CANCELLED, 0, 60);
                return;
            }
            if (restaurantStage == RestaurantStage.QUOTED || restaurantStage == RestaurantStage.COMPLETE
                || restaurantStage == RestaurantStage.CANCELLED) return;
            if (restaurantStage == RestaurantStage.ACCEPTED) {
                if (TavernHostService.takeOrderStock(host, this)) {
                    if (guest != null) guest.showTavernCue(com.hearthstead.entity.TavernCue.COOKING, 0, 80);
                } else if (level.getGameTime() - acceptedAt > 360) {
                    if (guest != null) guest.showTavernCue(com.hearthstead.entity.TavernCue.NO_STOCK, 0, 80);
                    cancelOrder("stock_or_route_unavailable");
                }
                return;
            }
            if (restaurantStage == RestaurantStage.PREPARING) {
                if (!com.hearthstead.settlement.work.ContainerApproach.inspect(level, host, source).canInteract()) {
                    if (pathAttemptDue(level.getGameTime()))
                        com.hearthstead.settlement.work.ContainerApproach.moveToContact(level, host, source, 1.0);
                    return;
                }
                host.getNavigation().stop();
                if (--preparationTicks <= 0) {
                    if (!ingredients.isEmpty()) {
                        if (!ingredients.is(net.minecraft.world.item.Items.WHEAT) || ingredients.getCount() != 3) {
                            cancelOrder("invalid_ingredients"); return;
                        }
                        ingredients = ItemStack.EMPTY;
                        food = new ItemStack(net.minecraft.world.item.Items.BREAD);
                        syncCargo();
                    }
                    restaurantStage = RestaurantStage.WAITING_SEAT;
                    if (guest != null) guest.showTavernCue(com.hearthstead.entity.TavernCue.WAITING, 0, 80);
                }
                return;
            }
            if (restaurantStage == RestaurantStage.WAITING_SEAT) {
                if (guest != null && TavernHostService.validGuest(host, guest, site)) {
                    restaurantStage = RestaurantStage.SERVING;
                    guest.showTavernCue(com.hearthstead.entity.TavernCue.SERVING, 0, 80);
                }
                return;
            }
        }
        if (phase() == Phase.RETURN_TABLE) {
            if (!TavernHostService.atPickupStand(host, this)) {
                TavernHostService.moveToPickup(host, this); return;
            }
            if (!TavernHostService.settlePickupFacing(host, this)) return;
            if (!TavernHostService.pickupContact(host, this)) {
                TavernHostService.moveToPickup(host, this); return;
            }
            beginTransition(Phase.PICKING_UP, handAnchor(host), host.yBodyRot);
            return;
        }
        if (phase() == Phase.PICKING_UP) {
            if (!TavernHostService.pickupContact(host, this)) {
                entityData.set(PHASE, Phase.RETURN_TABLE.ordinal());
                TavernHostService.moveToPickup(host, this); return;
            }
            // Hold the pickup's established yaw while the prop moves into the
            // hand. Re-aiming at that moving prop would spin the actor mid-lift.
            if (!TavernHostService.holdPickupFacing(host, transitionEndYaw)) return;
            if (handAnchor(host).distanceToSqr(transitionEnd) > .01) {
                beginTransition(Phase.PICKING_UP, handAnchor(host), host.yBodyRot); return;
            }
            host.getNavigation().stop();
            if (advanceTransition()) entityData.set(PHASE, Phase.RETURNING.ordinal());
            return;
        }
        if (isRestaurantOrder() && guest == null) return; // Entity lookup can pause on a chunk unload.
        if (!TavernHostService.validGuest(host, guest, site)) {
            if (isRestaurantOrder()) cancelOrder("seat_lost");
            beginReturn(); return;
        }
        if (phase() == Phase.CARRYING) {
            follow(host);
            if (!tapAttempted && ale.isEmpty() && (!isRestaurantOrder() || orderedAle)) {
                var tavern = TavernSeating.building(host.settlement(), site.tavernId());
                if (tapTarget == null) tapTarget = TavernHostService.optionalAleTap(host, guest, tavern, isRestaurantOrder());
                if (tapTarget == null || ++tapApproachTicks > 120
                    || !isRestaurantOrder() && !com.hearthstead.settlement.work.TavernGuestPayment.canAffordAle(guest, true)) {
                    tapAttempted = true;
                } else if (com.hearthstead.settlement.work.AleTapService.hostContact(host, tavern, tapTarget)) {
                    com.hearthstead.settlement.work.AleTapService.takeAleAtContact(host, tavern, tapTarget, this);
                    tapAttempted = true; // Empty/changed tap never starves the already-owned meal.
                } else {
                    if (pathAttemptDue(level.getGameTime())) TavernHostService.moveToTap(host, tapTarget);
                    return;
                }
            }
            if (!TavernHostService.tableContact(host, this)) { TavernHostService.moveToTable(host, this); return; }
            boolean facingReady = TavernHostService.settleTableFacing(host, this);
            follow(host); // Start the transition at the newly aligned physical hand anchor.
            if (!facingReady) return;
            host.getNavigation().stop();
            entityData.set(FACING, site.dinerFacing().get3DDataValue());
            beginTransition(Phase.PLACING, tableEdge(level), site.hostYaw());
            return;
        }
        if (phase() == Phase.PLACING) {
            if (!TavernHostService.tableContact(host, this)) { TavernHostService.moveToTable(host, this); return; }
            if (!TavernHostService.settleTableFacing(host, this)) return;
            host.getNavigation().stop();
            if (advanceTransition()) {
                setPos(site.tabletopCenter(level)); entityData.set(TABLE_ANCHOR, true);
                entityData.set(PHASE, Phase.SLIDING.ordinal()); entityData.set(SLIDE, 0);
                // The real placing transition has reached the tabletop. Phase is
                // committed before playback: ordinary retries and clean reloads
                // in SLIDING/READY/DINING cannot repeat this contact cue.
                Vec3 contact = itemAnchor();
                level.playSound(null, contact.x, contact.y, contact.z,
                    com.hearthstead.registry.ModSounds.MUG_SET.get(),
                    net.minecraft.sounds.SoundSource.NEUTRAL, 1.0F,
                    0.95F + host.getRandom().nextFloat() * 0.10F);
            }
            return;
        }
        if (phase() == Phase.DINING) {
            // Food is already guest-owned at this phase. Give its existing eating
            // pose exclusive use of both hands, then begin the optional drink.
            if (!ale.isEmpty() && !guest.hasMeal() && !aleDeclined) {
                if (!isRestaurantOrder() && tapSource != null && !com.hearthstead.settlement.work.TavernGuestPayment.canAffordAle(guest, false)) {
                    aleDeclined = true; // Return the untouched stock with the glass; food remains served.
                } else {
                drinkMouth = mouthPoint(guest);
                entityData.set(DRINKING, 0);
                entityData.set(PHASE, Phase.DRINKING.ordinal());
                return;
                }
            }
            if (!guest.hasMeal() && food.isEmpty() && !glass.isEmpty()
                && TavernHostService.guestReturnContact(host, guest, this)) {
                diningTicks = Math.min(20, diningTicks + 1);
                entityData.set(RETURN_PREPARATION, diningTicks);
                if (diningTicks == 20) entityData.set(PHASE, Phase.RETURN_SLIDING.ordinal());
            }
            return;
        }
        if (phase() == Phase.DRINKING) {
            // Lift for eight real seated-contact ticks, keep the glass at the
            // saved mouth target for eight, then lower it for eight more.
            if (drinkingTicks() < DRINK_LIFT_TICKS && ale.isEmpty()) {
                entityData.set(DRINKING, 0); entityData.set(PHASE, Phase.DINING.ordinal()); return;
            }
            if (!glass.isEmpty() && TavernHostService.drinkingContact(guest, this)) {
                int drink = Math.min(DRINK_TOTAL_TICKS, drinkingTicks() + 1);
                entityData.set(DRINKING, drink);
                if (drink == DRINK_LIFT_TICKS) {
                    // The sip is the only sale. Its physical coin stays with
                    // this saved serving until the host walks it back to the tap.
                    if (tapSource != null && !aleDeclined) {
                        if (isRestaurantOrder()) {
                            if (orderPayer == TavernGuestPayment.OrderPayer.TRAVELER_BAG) {
                                if (orderEscrow.isEmpty() || !payment.isEmpty()) aleDeclined = true;
                                else { payment = orderEscrow.split(1); aleSettled = true; }
                            } else aleSettled = true;
                        } else {
                            ItemStack coin = payment.isEmpty()
                                ? com.hearthstead.settlement.work.TavernGuestPayment.takeAleCoin(guest) : ItemStack.EMPTY;
                            if (coin.isEmpty()) aleDeclined = true;
                            else payment = coin;
                        }
                    }
                    if (!aleDeclined) {
                    ale.shrink(1);
                    syncCargo();
                    // Presence: the host's welcome lifts it 0..25% (plan/ATTRIBUTES.md).
                    // Tech tree (Alehouse): a pint lifts 3 -> 5.
                    float pint = com.hearthstead.settlement.techtree.effects.CommonsEffects.aleMorale(guest, 3.0F);
                    guest.addMorale(host != null && host.getProfession() == Profession.INNKEEPER
                        ? AttributeRuntime.hospitality(host, pint) : pint);
                    // Presentation only: the walk home sways (TIPSY_WALK) for two minutes.
                    guest.markAleDrunk(level.getGameTime(), 2400);
                    // Evening bard lift: once per resident per day, same Tavern only.
                    com.hearthstead.settlement.TavernBard.onRefreshmentCompleted(guest, site.tavernId());
                    Vec3 pour = glassPosition(0);
                    level.playSound(null, pour.x, pour.y, pour.z,
                        com.hearthstead.registry.ModSounds.TAVERN_DRINK.get(),
                        net.minecraft.sounds.SoundSource.NEUTRAL, .45F,
                        .95F + host.getRandom().nextFloat() * .08F);
                    com.hearthstead.Hearthstead.LOGGER.info(
                        "HEARTHSTEAD_TAVERN_ALE_DRINK host={} guest={} source={} aleConsumed=1 glassRetained={} morale=3",
                        host.getUUID(), guest.getUUID(), source == null ? "none" : source.toShortString(),
                        glass.getCount());
                    if (com.hearthstead.util.QaTrace.ENABLED) com.hearthstead.util.QaTrace.event(host,
                        "tavern_ale_consumed", "guest=" + guest.getUUID() + ";source="
                            + (source == null ? "none" : source.toShortString())
                            + ";aleConsumed=1;glassRetained=" + glass.getCount() + ";morale=3");
                    }
                }
                if (drink == DRINK_TOTAL_TICKS) {
                    entityData.set(DRINKING, 0);
                    entityData.set(PHASE, Phase.DINING.ordinal());
                }
            } else {
                entityData.set(DRINKING, 0); // A broken LOS/reach never accumulates a false sip.
            }
            return;
        }
        if (phase() == Phase.RETURN_SLIDING) {
            // Four real contact ticks push; the remaining ticks coast. A
            // departed/invalid diner was rejected above, freezing this position.
            if (!food.isEmpty() || glass.isEmpty()
                || returnSlideTicks() < 4 && !TavernHostService.guestReturnContact(host, guest, this)) {
                beginReturn(); return;
            }
            entityData.set(SLIDE, Math.max(0, slideTicks() - 1));
            if (slideTicks() == 0) beginReturn();
            return;
        }
        if (!TavernHostService.tableContact(host, this)) { TavernHostService.moveToTable(host, this); return; }
        host.getNavigation().stop();
        if (phase() == Phase.SLIDING) {
            entityData.set(SLIDE, Math.min(SLIDE_TICKS, slideTicks() + 1));
            if (slideTicks() == SLIDE_TICKS) {
                entityData.set(PHASE, Phase.READY.ordinal()); entityData.set(RECEIVING, 0);
            }
            return; // Visible release/receiving boundary is a separate server tick.
        }
        if (phase() == Phase.READY && TavernHostService.receivingContact(guest, this)) {
            entityData.set(RECEIVING, Math.min(RECEIVING_TICKS, receivingTicks() + 1));
            // The diner reaches for eight real contact-valid ticks before
            // ownership leaves the tabletop. Host release/coast uses slideTicks.
            if (receivingTicks() == RECEIVING_TICKS
                && (isRestaurantOrder() ? receiveOrderedMeal(host, guest)
                    : com.hearthstead.settlement.work.TavernGuestPayment.receive(host, guest, food, paidVisitorMeal))) {
                syncCargo(); entityData.set(PHASE, Phase.DINING.ordinal());
                host.train(com.hearthstead.settlement.Employment.trainedBy(com.hearthstead.building.BuildingType.TAVERN), 1.0F);
                com.hearthstead.entity.SkillLevels.completeUnit(host, 1,
                    com.hearthstead.settlement.Employment.trainedBy(com.hearthstead.building.BuildingType.TAVERN));
                // Trade skill (Innkeeper secondary, Spirit, level 5+): a warm
                // table now and then lifts the guest +1 morale, once per meal
                // (this receive commit runs exactly once per served meal).
                if (host.getProfession() == com.hearthstead.entity.Profession.INNKEEPER
                    && com.hearthstead.entity.SkillLevels.rollSide(host)) {
                    guest.addMorale(1.0F);
                }
                // Presence: a served meal comes with a welcome worth 0..25% of
                // the meal's own +2 morale (AttributeRuntime.hospitality).
                if (host.getProfession() == com.hearthstead.entity.Profession.INNKEEPER) {
                    float welcome = AttributeRuntime.hospitality(host, 2.0F) - 2.0F;
                    if (welcome > 0.0F) guest.addMorale(welcome);
                }
            }
        }
    }
    private boolean receiveOrderedMeal(SettlerEntity host, SettlerEntity guest) {
        if (!(level() instanceof ServerLevel level) || mealSettled || food.isEmpty()
            || guest.hasMeal() || food.getFoodProperties(guest) == null || source == null
            || !level.hasChunkAt(source)) return false;
        var be = level.getBlockEntity(source);
        if (!(be instanceof net.minecraft.world.level.block.entity.ChestBlockEntity
            || be instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity)) return false;
        net.minecraft.world.Container till = (net.minecraft.world.Container) be;
        if (mealPrice > 0 && (orderEscrow.isEmpty()
            || !orderEscrow.is(ModItems.GOLD_COIN.get())
            || !orderEscrow.getComponentsPatch().isEmpty()
            || orderEscrow.getCount() < mealPrice)) return false;
        if (!TavernGuestPayment.canSettleMeal(till, mealPrice)) return false;
        if (!guest.beginMeal(food)) return false;
        // Same server tick and no callback between preflight and this deposit.
        if (!TavernGuestPayment.settleMeal(till, orderEscrow, mealPrice))
            throw new IllegalStateException("Preflighted Tavern meal till changed during receipt");
        mealSettled = true;
        // Tech tree (Hall of Revels): the house matches every Coin paid.
        com.hearthstead.settlement.techtree.effects.CommonsEffects.payTavernBonus(level,
            com.hearthstead.settlement.TavernSeating.visitSettlement(guest), mealPrice);
        guest.showTavernCue(com.hearthstead.entity.TavernCue.EATING, 0, 60);
        return true;
    }

    @Override public void tick() {
        super.tick();
        if (level().isClientSide) {
            if (!entityData.get(PRESENTATION).contains("X")) return;
            Vec3 foodPoint = propPosition(0, -.16), glassPoint = propPosition(0, .16),
                alePoint = propPosition(0, .16);
            boolean firstOrTeleported = currentFoodPoint == null || currentFoodPoint.distanceToSqr(foodPoint) > 16;
            previousFoodPoint = firstOrTeleported ? foodPoint : currentFoodPoint;
            previousGlassPoint = firstOrTeleported ? glassPoint : currentGlassPoint;
            previousAlePoint = firstOrTeleported ? alePoint : currentAlePoint;
            float snapshotYaw = entityData.get(PRESENTATION).contains("X")
                ? entityData.get(PRESENTATION).getFloat("Yaw") : entityData.get(YAW);
            previousPresentationYaw = firstOrTeleported ? snapshotYaw : currentPresentationYaw;
            currentFoodPoint = foodPoint; currentGlassPoint = glassPoint;
            currentAlePoint = alePoint;
            currentPresentationYaw = snapshotYaw;
            previousClockSnapshot = firstOrTeleported ? entityData.get(PRESENTATION) : currentClockSnapshot;
            currentClockSnapshot = entityData.get(PRESENTATION);
            return;
        }
        try { tickServer(); }
        finally { syncPresentation(); }
    }
    private void tickServer() {
        if (!(level() instanceof ServerLevel level) || quarantine != null) return;
        SettlerEntity host = hostId() != null && level.getEntity(hostId()) instanceof SettlerEntity s ? s : null;
        if (site == null || source == null || settlement == null) { drain(level); return; }
        if (isRestaurantOrder()) {
            SettlerEntity guest = guestId() != null && level.getEntity(guestId()) instanceof SettlerEntity s ? s : null;
            var deathNotice = com.hearthstead.settlement.work.TavernOrderDeathSavedData.get(level)
                .exact(level, getUUID(), guestId());
            if (deathNotice != null && !isTerminalOrder()) {
                if (deathRefundPoint == null)
                    deathRefundPoint = new Vec3(deathNotice.x(), deathNotice.y(), deathNotice.z());
                cancelOrder("payer_died");
            }
            if (deathRefundPoint != null && !orderEscrow.isEmpty()) recoverDeadPayer(level, guest);
            if (level.getGameTime() >= orderExpiresAt && restaurantStage != RestaurantStage.COMPLETE
                && restaurantStage != RestaurantStage.CANCELLED && restaurantStage != RestaurantStage.REFUND_PENDING)
                cancelOrder("expired");
            if (restaurantStage == RestaurantStage.REFUND_PENDING && food.isEmpty()
                && glass.isEmpty() && ale.isEmpty() && payment.isEmpty() && ingredients.isEmpty()
                && (orderEscrow.isEmpty() || deathRefundPoint != null && recoverDeadPayer(level, guest)
                    || guest != null && guest.isAlive()
                    && TavernGuestPayment.refundOrder(guest, orderPayer, orderEscrow))) {
                restaurantStage = RestaurantStage.CANCELLED;
                if (guest != null) guest.showTavernCue(com.hearthstead.entity.TavernCue.CANCELLED, 0, 60);
            }
            if (restaurantStage == RestaurantStage.SERVING && mealSettled
                && food.isEmpty() && glass.isEmpty() && ale.isEmpty() && payment.isEmpty()
                && phase() == Phase.RETURNING) {
                if (guest != null && TavernGuestPayment.refundOrder(guest, orderPayer, orderEscrow)) {
                    restaurantStage = RestaurantStage.COMPLETE;
                    guest.showTavernCue(com.hearthstead.entity.TavernCue.DONE, 0, 60);
                }
            }
            if (isTerminalOrder() && !hasCargo()) {
                TavernHostService.releaseTableClaim(level, this);
                if (host != null) TavernHostService.clearSession(host, getUUID());
                if (guest != null) TavernHostService.clearOrder(guest, getUUID());
                // Each pointer is acknowledged independently. A loaded counterpart
                // can finish later without holding the table or the worker.
                if (host != null && guest != null) { discard(); return; }
            }
            // A quote and an accepted order have no stock cargo yet; their saved
            // entity and exact pointers must survive chunk unloads and reloads.
            if (restaurantStage == RestaurantStage.QUOTED || restaurantStage == RestaurantStage.ACCEPTED
                || restaurantStage == RestaurantStage.PREPARING || restaurantStage == RestaurantStage.WAITING_SEAT)
                { if (host != null) { noteHost(host); follow(host); } return; }
        }
        if (!hasCargo()) {
            if (isRestaurantOrder()) return; // The exact guest/host receipt may be temporarily unloaded.
            TavernHostService.releaseTableClaim(level, this);
            // Keep the empty receipt until the loaded owner can clear its saved
            // pointer; never turn an unloaded owner into a duplicate new job.
            if (host != null) { TavernHostService.clearSession(host, getUUID()); discard(); }
            return;
        }
        if (host != null) TavernHostService.noteLocation(host, this);
        elapsed++;
        boolean permitted = host != null && TavernHostService.authorizedHost(host, site.tavernId());
        if (!permitted || elapsed > (isRestaurantOrder() ? 1800 : 1200)) {
            if (isRestaurantOrder()) {
                if (host != null || level.getGameTime() >= orderExpiresAt) cancelOrder("host_unavailable");
            } else beginReturn();
        }
        if (phase() == Phase.RETURNING || phase() == Phase.RETURN_TABLE || phase() == Phase.PICKING_UP) returnElapsed++;
        if (phase() == Phase.CARRYING || phase() == Phase.RETURNING) {
            if (host != null && host.isAlive()) follow(host);
        } else if (level.hasChunkAt(site.table()) && level.hasChunkAt(site.table().above())) {
            var building = com.hearthstead.settlement.TavernSeating.building(
                com.hearthstead.settlement.SettlementManager.byId(level, settlement), site.tavernId());
            // Retain the order's own host stand; a blocked side must not reshuffle and drain it.
            if (!TavernSeating.sameSite(level, building, site)) {
                drain(level); return;
            }
        }
        if (host != null && !host.isAlive() || returnElapsed > 400) drain(level);
    }
    private void noteHost(SettlerEntity host) { TavernHostService.noteLocation(host, this); }
    private void drain(ServerLevel level) {
        if (isRestaurantOrder()) cancelOrder("physical_materialization");
        if (isRestaurantOrder() && !orderEscrow.isEmpty()) {
            SettlerEntity guest = level.getEntity(guestId()) instanceof SettlerEntity s ? s : null;
            if (deathRefundPoint != null) {
                if (!recoverDeadPayer(level, guest)) return;
            } else if (guest == null || !TavernGuestPayment.refundOrder(guest, orderPayer, orderEscrow)) return;
        }
        var escrow = DeferredItemMaterializationSavedData.get(level);
        if (!ingredients.isEmpty()) {
            UUID id = escrow.queue(level, getX(), getY(), getZ(), ingredients);
            if (id != null) { ingredients = ItemStack.EMPTY; escrow.materialize(level, id); }
        }
        if (!food.isEmpty()) {
            UUID id = escrow.queue(level, getX(), getY(), getZ(), food);
            if (id != null) { food = ItemStack.EMPTY; syncCargo(); escrow.materialize(level, id); }
        }
        if (!glass.isEmpty()) {
            UUID id = escrow.queue(level, getX(), getY(), getZ(), glass);
            if (id != null) { glass = ItemStack.EMPTY; syncCargo(); escrow.materialize(level, id); }
        }
        if (!ale.isEmpty()) {
            UUID id = escrow.queue(level, getX(), getY(), getZ(), ale);
            if (id != null) { ale = ItemStack.EMPTY; syncCargo(); escrow.materialize(level, id); }
        }
        if (!payment.isEmpty()) {
            UUID id = escrow.queue(level, getX(), getY(), getZ(), payment);
            if (id != null) { payment = ItemStack.EMPTY; syncCargo(); escrow.materialize(level, id); }
        }
        // Rejected/full escrow leaves this saved entity as the item owner.
    }
    @Override public void kill() {
        if (quarantine == null && level() instanceof ServerLevel level) { beginReturn(); drain(level); }
    }
    @Override protected void defineSynchedData(SynchedEntityData.Builder b) {
        b.define(PRESENTATION, new CompoundTag());
        b.define(FOOD, ItemStack.EMPTY); b.define(GLASS, ItemStack.EMPTY); b.define(ALE, ItemStack.EMPTY);
        b.define(PAYMENT, ItemStack.EMPTY);
        b.define(DRINKING, 0);
        b.define(RETURN_PREPARATION, 0);
        b.define(TRANSITION, 0); b.define(RECEIVING, 0); b.define(YAW, 0F); b.define(TABLE_ANCHOR, false);
        b.define(PHASE, 0); b.define(SLIDE, 0); b.define(FACING, Direction.NORTH.get3DDataValue());
        b.define(HOST, Optional.empty()); b.define(GUEST, Optional.empty());
    }
    @Override protected void addAdditionalSaveData(CompoundTag tag) {
        if (quarantine != null) { tag.put("QuarantinedSession", quarantine.copy()); return; }
        tag.putBoolean("PaidVisitorMeal", paidVisitorMeal);
        if (hostId() != null) tag.putUUID("Host", hostId());
        if (guestId() != null) tag.putUUID("Guest", guestId());
        if (settlement != null) tag.putUUID("Settlement", settlement);
        if (site != null) {
            tag.putUUID("Tavern", site.tavernId()); tag.putLong("Chair", site.chair().asLong());
            tag.putLong("Aisle", site.aisle().asLong());
            tag.putInt("SiteFacing", site.dinerFacing().get3DDataValue());
            tag.putLong("HostApproach", site.hostApproach().asLong());
            site.furniture().write(tag);
        }
        if (source != null) tag.putLong("Source", source.asLong());
        if (!food.isEmpty()) tag.put("Food", food.save(registryAccess()));
        if (!glass.isEmpty()) tag.put("Glass", glass.save(registryAccess()));
        if (!ale.isEmpty()) tag.put("Ale", ale.save(registryAccess()));
        if (!payment.isEmpty()) tag.put("TapPayment", payment.save(registryAccess()));
        if (isRestaurantOrder()) {
            CompoundTag order = new CompoundTag();
            order.putInt("Schema", 1);
            order.putString("Stage", restaurantStage.name());
            if (reservedSeatId != null) order.putUUID("Seat", reservedSeatId);
            order.putLong("Expires", orderExpiresAt);
            order.putLong("QuoteStarted", quoteStartedAt);
            order.putLong("Accepted", acceptedAt);
            order.putInt("Price", orderPrice);
            order.putInt("MealPrice", mealPrice);
            order.putInt("Preparation", preparationTicks);
            order.putBoolean("Ale", orderedAle);
            order.putBoolean("MealSettled", mealSettled);
            order.putBoolean("AleSettled", aleSettled);
            order.putString("CancelReason", orderCancelReason);
            order.putString("Payer", orderPayer.name());
            if (!orderEscrow.isEmpty()) order.put("Escrow", orderEscrow.save(registryAccess()));
            if (deathRefundPoint != null) {
                order.putDouble("DeathRefundX", deathRefundPoint.x);
                order.putDouble("DeathRefundY", deathRefundPoint.y);
                order.putDouble("DeathRefundZ", deathRefundPoint.z);
            }
            order.putBoolean("DeathRefundQueued", deathRefundQueued);
            if (!ingredients.isEmpty()) order.put("Ingredients", ingredients.save(registryAccess()));
            tag.put("RestaurantOrder", order);
        }
        tag.putBoolean("TapAttempted", tapAttempted); tag.putBoolean("AleDeclined", aleDeclined);
        tag.putInt("TapApproachTicks", tapApproachTicks);
        if (tapTarget != null) tag.putLong("TapTarget", tapTarget.asLong());
        if (tapSource != null) tag.putLong("TapSource", tapSource.asLong());
        if (tapBarrel != null) tag.putLong("TapBarrel", tapBarrel.asLong());
        tag.putInt("Phase", phase().ordinal()); tag.putInt("Slide", slideTicks());
        tag.putInt("Facing", facing().get3DDataValue()); tag.putInt("Elapsed", elapsed);
        tag.putInt("ReturnElapsed", returnElapsed); tag.putInt("DiningTicks", diningTicks);
        tag.putLong("LastServiceTick", lastServiceTick);
        tag.putInt("Transition", transitionTicks()); tag.putInt("Receiving", receivingTicks());
        tag.putInt("Drinking", drinkingTicks()); tag.putFloat("Yaw", entityData.get(YAW));
        if (Double.isFinite(drinkMouth.lengthSqr())) {
            tag.putDouble("DrinkX", drinkMouth.x); tag.putDouble("DrinkY", drinkMouth.y); tag.putDouble("DrinkZ", drinkMouth.z);
        }
        tag.putBoolean("TableAnchor", entityData.get(TABLE_ANCHOR));
        tag.putDouble("StartX", transitionStart.x); tag.putDouble("StartY", transitionStart.y); tag.putDouble("StartZ", transitionStart.z);
        tag.putDouble("EndX", transitionEnd.x); tag.putDouble("EndY", transitionEnd.y); tag.putDouble("EndZ", transitionEnd.z);
        tag.putFloat("StartYaw", transitionStartYaw); tag.putFloat("EndYaw", transitionEndYaw);
    }
    @Override protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("QuarantinedSession")) { quarantine = tag.getCompound("QuarantinedSession").copy(); return; }
        if (tag.contains("PaidVisitorMeal") && (!tag.contains("PaidVisitorMeal", 1)
            || tag.getByte("PaidVisitorMeal") < 0 || tag.getByte("PaidVisitorMeal") > 1)) {
            quarantine = tag.copy(); return;
        }
        // Legacy resident sessions remain free; old code could not serve unbound visitors.
        paidVisitorMeal = tag.getBoolean("PaidVisitorMeal");
        int phase = tag.getInt("Phase"), facing = tag.getInt("SiteFacing");
        if (tag.contains("AnotherFurniture") && (!tag.contains("AnotherFurniture", 1)
            || tag.getByte("AnotherFurniture") < 0 || tag.getByte("AnotherFurniture") > 1)) {
            quarantine = tag.copy(); return;
        }
        if (!tag.hasUUID("Host") || !tag.hasUUID("Guest") || !tag.hasUUID("Settlement")
            || !tag.hasUUID("Tavern") || phase < 0 || phase >= Phase.values().length || facing < 2 || facing > 5
            || tag.getInt("Facing") < 2 || tag.getInt("Facing") > 5
            || !tag.contains("Source", 4) || !tag.contains("Chair", 4) || !tag.contains("Aisle", 4)) {
            quarantine = tag.copy(); return;
        }
        food = ItemStack.parseOptional(registryAccess(), tag.getCompound("Food"));
        glass = ItemStack.parseOptional(registryAccess(), tag.getCompound("Glass"));
        ale = ItemStack.parseOptional(registryAccess(), tag.getCompound("Ale"));
        payment = ItemStack.parseOptional(registryAccess(), tag.getCompound("TapPayment"));
        if (tag.contains("RestaurantOrder")) {
            if (!tag.contains("RestaurantOrder", 10)) { quarantine = tag.copy(); return; }
            CompoundTag order = tag.getCompound("RestaurantOrder");
            try {
                if (order.getInt("Schema") != 1 || !order.hasUUID("Seat")) throw new IllegalArgumentException();
                if (order.contains("Escrow") && !order.contains("Escrow", 10)
                    || order.contains("Ingredients") && !order.contains("Ingredients", 10))
                    throw new IllegalArgumentException();
                if (order.contains("DeathRefundQueued") && (!order.contains("DeathRefundQueued", 1)
                    || order.getByte("DeathRefundQueued") < 0
                    || order.getByte("DeathRefundQueued") > 1)) throw new IllegalArgumentException();
                restaurantStage = RestaurantStage.valueOf(order.getString("Stage"));
                orderPayer = TavernGuestPayment.OrderPayer.valueOf(order.getString("Payer"));
                reservedSeatId = order.getUUID("Seat");
                orderExpiresAt = order.getLong("Expires");
                quoteStartedAt = order.getLong("QuoteStarted");
                acceptedAt = order.getLong("Accepted");
                orderPrice = order.getInt("Price");
                mealPrice = order.getInt("MealPrice");
                preparationTicks = order.getInt("Preparation");
                orderedAle = order.getBoolean("Ale");
                mealSettled = order.getBoolean("MealSettled");
                aleSettled = order.getBoolean("AleSettled");
                orderCancelReason = order.getString("CancelReason");
                orderEscrow = ItemStack.parseOptional(registryAccess(), order.getCompound("Escrow"));
                if (order.contains("DeathRefundX") || order.contains("DeathRefundY")
                    || order.contains("DeathRefundZ")) {
                    if (!order.contains("DeathRefundX", 6) || !order.contains("DeathRefundY", 6)
                        || !order.contains("DeathRefundZ", 6)) throw new IllegalArgumentException();
                    deathRefundPoint = new Vec3(order.getDouble("DeathRefundX"),
                        order.getDouble("DeathRefundY"), order.getDouble("DeathRefundZ"));
                }
                deathRefundQueued = order.getBoolean("DeathRefundQueued");
                ingredients = ItemStack.parseOptional(registryAccess(), order.getCompound("Ingredients"));
                if (order.contains("Escrow") && orderEscrow.isEmpty()
                    || order.contains("Ingredients") && ingredients.isEmpty())
                    throw new IllegalArgumentException();
                if (restaurantStage == RestaurantStage.NONE || orderExpiresAt <= 0 || orderPrice < 0
                    || orderPrice > 3 || mealPrice < 0 || mealPrice > 2
                    || orderPrice != mealPrice + (orderedAle && orderPayer == TavernGuestPayment.OrderPayer.TRAVELER_BAG ? 1 : 0)
                    || (orderPayer == TavernGuestPayment.OrderPayer.FREE_RESIDENT) != (mealPrice == 0)
                    || preparationTicks < 0 || preparationTicks > 80
                    || !orderEscrow.isEmpty() && (!orderEscrow.is(ModItems.GOLD_COIN.get())
                        || !orderEscrow.getComponentsPatch().isEmpty() || orderEscrow.getCount() > orderPrice)
                    || !ingredients.isEmpty() && (!ingredients.is(net.minecraft.world.item.Items.WHEAT)
                        || !ingredients.getComponentsPatch().isEmpty() || ingredients.getCount() > 3)
                    || deathRefundQueued && (deathRefundPoint == null || !orderEscrow.isEmpty())
                    || deathRefundPoint != null && (!Double.isFinite(deathRefundPoint.lengthSqr())
                        || Math.abs(deathRefundPoint.x) > 30_000_000
                        || Math.abs(deathRefundPoint.z) > 30_000_000)
                    || deathRefundQueued && orderPayer != TavernGuestPayment.OrderPayer.TRAVELER_BAG)
                    throw new IllegalArgumentException();
                if (restaurantStage == RestaurantStage.QUOTED && (acceptedAt != 0
                    || !orderEscrow.isEmpty() || mealSettled || aleSettled || !food.isEmpty()
                    || !glass.isEmpty() || !ingredients.isEmpty())
                    || restaurantStage == RestaurantStage.ACCEPTED && (acceptedAt <= 0
                        || orderEscrow.getCount() != orderPrice || !food.isEmpty()
                        || !glass.isEmpty() || !ingredients.isEmpty())
                    || restaurantStage == RestaurantStage.PREPARING && (acceptedAt <= 0
                        || preparationTicks <= 0 || glass.getCount() != 1
                        || (food.isEmpty() == ingredients.isEmpty()))
                    || restaurantStage == RestaurantStage.WAITING_SEAT && (food.getCount() != 1
                        || glass.getCount() != 1 || !ingredients.isEmpty())
                    || restaurantStage == RestaurantStage.SERVING && !mealSettled
                        && (food.getCount() != 1 || glass.getCount() != 1)
                    || (restaurantStage == RestaurantStage.COMPLETE
                        || restaurantStage == RestaurantStage.CANCELLED)
                        && (!orderEscrow.isEmpty() || !ingredients.isEmpty()))
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalidOrder) { quarantine = tag.copy(); return; }
        }
        tapAttempted = !tag.contains("TapAttempted") || tag.getBoolean("TapAttempted");
        aleDeclined = tag.getBoolean("AleDeclined");
        tapApproachTicks = Math.max(0, Math.min(121, tag.getInt("TapApproachTicks")));
        tapTarget = tag.contains("TapTarget", 4) ? BlockPos.of(tag.getLong("TapTarget")) : null;
        tapSource = tag.contains("TapSource", 4) ? BlockPos.of(tag.getLong("TapSource")) : null;
        tapBarrel = tag.contains("TapBarrel", 4) ? BlockPos.of(tag.getLong("TapBarrel")) : null;
        if (tapSource == null && !ale.isEmpty()) {
            aleDeclined = true; // Pre-tap saved stock returns intact; never a new free sale.
            if (phase == Phase.DRINKING.ordinal()) phase = Phase.DINING.ordinal();
        }
        if (tag.contains("TapTarget") && !tag.contains("TapTarget", 4)
            || tag.contains("TapSource") && !tag.contains("TapSource", 4)
            || tag.contains("TapBarrel") && !tag.contains("TapBarrel", 4)
            || tag.contains("TapAttempted") && (!tag.contains("TapAttempted", 1)
                || tag.getByte("TapAttempted") < 0 || tag.getByte("TapAttempted") > 1)
            || tag.contains("AleDeclined") && (!tag.contains("AleDeclined", 1)
                || tag.getByte("AleDeclined") < 0 || tag.getByte("AleDeclined") > 1)) { quarantine = tag.copy(); return; }
        if (tag.contains("TapPayment") && (payment.isEmpty() || !payment.is(ModItems.GOLD_COIN.get())
                || !payment.getComponentsPatch().isEmpty() || payment.getCount() != 1)
            || (tapSource == null) != (tapBarrel == null)
            || !payment.isEmpty() && (tapSource == null || !ale.isEmpty())) { quarantine = tag.copy(); return; }
        if (tag.contains("Food") && food.isEmpty() || tag.contains("Glass") && glass.isEmpty()
            || tag.contains("Ale") && (ale.isEmpty() || !ale.is(ModItems.ALE.get()))
            || food.getCount() > 1 || glass.getCount() > 1 || ale.getCount() > 1) { quarantine = tag.copy(); return; }
        entityData.set(HOST, Optional.of(tag.getUUID("Host"))); entityData.set(GUEST, Optional.of(tag.getUUID("Guest")));
        settlement = tag.getUUID("Settlement"); Direction front = Direction.from3DDataValue(facing);
        BlockPos chair = BlockPos.of(tag.getLong("Chair")); BlockPos table = chair.relative(front);
        site = new TavernSeating.SeatSite(tag.getUUID("Tavern"), chair, table, front,
            BlockPos.of(tag.getLong("Aisle")), tag.contains("HostApproach", 4)
                ? BlockPos.of(tag.getLong("HostApproach"))
                : TavernSeating.SeatSite.legacyHostApproach(table, front), TavernSeating.Furniture.read(tag));
        source = BlockPos.of(tag.getLong("Source"));
        entityData.set(PHASE, phase); entityData.set(SLIDE, Math.max(0, Math.min(SLIDE_TICKS, tag.getInt("Slide"))));
        entityData.set(FACING, tag.getInt("Facing"));
        elapsed = Math.max(0, Math.min(1601, tag.getInt("Elapsed"))); returnElapsed = Math.max(0, Math.min(401, tag.getInt("ReturnElapsed")));
        diningTicks = Math.max(0, Math.min(20, tag.getInt("DiningTicks")));
        entityData.set(RETURN_PREPARATION, diningTicks);
        lastServiceTick = tag.getLong("LastServiceTick");
        entityData.set(TRANSITION, Math.max(0, Math.min(TRANSITION_TICKS, tag.getInt("Transition"))));
        entityData.set(RECEIVING, Math.max(0, Math.min(RECEIVING_TICKS, tag.getInt("Receiving"))));
        entityData.set(DRINKING, Math.max(0, Math.min(DRINK_TOTAL_TICKS, tag.getInt("Drinking"))));
        boolean savedDrinkMouth = tag.contains("DrinkX") && tag.contains("DrinkY") && tag.contains("DrinkZ");
        drinkMouth = savedDrinkMouth
            ? new Vec3(tag.getDouble("DrinkX"), tag.getDouble("DrinkY"), tag.getDouble("DrinkZ")) : Vec3.ZERO;
        if (phase() == Phase.DRINKING && !savedDrinkMouth) {
            // Pre-lift saves had no motion target; restart safely at the table.
            entityData.set(DRINKING, 0); entityData.set(PHASE, Phase.DINING.ordinal());
        }
        entityData.set(YAW, tag.getFloat("Yaw")); entityData.set(TABLE_ANCHOR, tag.getBoolean("TableAnchor"));
        transitionStart = new Vec3(tag.getDouble("StartX"), tag.getDouble("StartY"), tag.getDouble("StartZ"));
        transitionEnd = new Vec3(tag.getDouble("EndX"), tag.getDouble("EndY"), tag.getDouble("EndZ"));
        transitionStartYaw = tag.getFloat("StartYaw"); transitionEndYaw = tag.getFloat("EndYaw");
        if (!Double.isFinite(drinkMouth.lengthSqr())
            || !Double.isFinite(transitionStart.lengthSqr()) || !Double.isFinite(transitionEnd.lengthSqr())
            || !Float.isFinite(entityData.get(YAW)) || !Float.isFinite(transitionStartYaw) || !Float.isFinite(transitionEndYaw)
            || (phase() == Phase.PLACING || phase() == Phase.PICKING_UP) && transitionStart.distanceToSqr(transitionEnd) > 9) {
            quarantine = tag.copy(); return;
        }
        syncCargo(); syncPresentation();
    }
}
