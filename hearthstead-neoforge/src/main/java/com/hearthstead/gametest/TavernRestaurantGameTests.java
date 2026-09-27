package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.TavernSeatEntity;
import com.hearthstead.entity.TavernServingEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.TavernSeating;
import com.hearthstead.settlement.work.TavernGuestPayment;
import com.hearthstead.settlement.work.TavernHostService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

/** Proposal only. Use the physical TavernHostGameTests room and contact fixture. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class TavernRestaurantGameTests {
    private static final BlockPos STORE = new BlockPos(3, 1, 3);
    private record Fixture(Settlement settlement, Building tavern, SettlerEntity host,
                           SettlerEntity guest, TavernSeatEntity seat, Container store, BlockPos source) {}

    private static Fixture fixture(GameTestHelper h) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            for (int y = 1; y <= 4; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        h.getLevel().setDayTime(11500);
        Settlement settlement = new Settlement(UUID.randomUUID(), "Restaurant fixture",
            h.absolutePos(new BlockPos(1, 1, 1)));
        SettlementSavedData.get(h.getLevel()).settlements.put(settlement.id, settlement);
        SettlementSavedData.get(h.getLevel()).setDirty();
        Building tavern = GameTestFixtures.registerWithBounds(h, settlement, BuildingType.TAVERN,
            new BlockPos(6, 1, 6), new BlockPos(2, 2, 2),
            BoundingBox.fromCorners(h.absolutePos(new BlockPos(2, 1, 2)),
                h.absolutePos(new BlockPos(12, 4, 12))));
        h.setBlock(new BlockPos(6, 1, 7), Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(StairBlock.FACING, Direction.SOUTH));
        h.setBlock(new BlockPos(6, 1, 6), Blocks.OAK_FENCE);
        h.setBlock(new BlockPos(6, 2, 6), Blocks.OAK_PRESSURE_PLATE);
        h.setBlock(STORE, Blocks.BARREL);
        Container store = (Container) h.getLevel().getBlockEntity(h.absolutePos(STORE));
        store.setItem(0, new ItemStack(Items.BREAD, 2));
        store.setItem(1, new ItemStack(Items.GLASS_BOTTLE, 2));
        SettlerEntity host = resident(h, settlement, new BlockPos(3, 1, 4));
        h.assertTrue(Employment.hire(h.getLevel(), settlement, tavern, host).ok(), "real Innkeeper hire");
        SettlerEntity guest = resident(h, settlement, new BlockPos(10, 1, 10));
        guest.setHunger(20);
        var reservation = TavernSeating.reserveReachable(guest);
        h.assertTrue(reservation.seat() != null, "real seat reservation");
        TavernSeatEntity seat = reservation.seat();
        guest.setPos(Vec3.atBottomCenterOf(seat.site().aisle()));
        h.assertTrue(seat.mount(guest), "real chair entry");
        return new Fixture(settlement, tavern, host, guest, seat, store, h.absolutePos(STORE));
    }

    private static SettlerEntity resident(GameTestHelper h, Settlement settlement, BlockPos pos) {
        SettlerEntity actor = h.spawn(ModEntities.SETTLER.get(), pos);
        actor.bindTo(settlement.id, settlement.center);
        settlement.putRecord(actor.getUUID(), "Restaurant resident", Profession.NONE);
        actor.setNoAi(true);
        actor.setHunger(100);
        actor.setEnergy(100);
        actor.move(net.minecraft.world.entity.MoverType.SELF, new Vec3(0, -.05, 0));
        return actor;
    }

    private static void hostAtGuest(Fixture f) {
        f.host().getNavigation().stop();
        f.host().setPos(Vec3.atBottomCenterOf(f.seat().site().aisle()));
    }

    private static void hostAtStore(Fixture f) {
        f.host().getNavigation().stop();
        f.host().setPos(Vec3.atBottomCenterOf(f.source().south()));
    }

    private static void hostAtTable(Fixture f) {
        f.host().getNavigation().stop();
        f.host().setPos(Vec3.atBottomCenterOf(f.seat().site().hostApproach()));
        f.host().setYRot(f.seat().site().dinerFacing().getOpposite().toYRot());
        f.host().yBodyRot = f.host().getYRot();
    }

    private static void hostAtTap(GameTestHelper h, Fixture f) {
        f.host().getNavigation().stop();
        f.host().setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(10, 1, 3))));
    }

    private static TavernServingEntity quote(GameTestHelper h, Fixture f, boolean ale) {
        if (ale) installTap(h, f);
        TavernServingEntity order = TavernHostService.reserveQuote(f.host(), f.guest(), f.seat(), ale);
        h.assertTrue(order != null, "real seat/table claim and source must create one order");
        hostAtGuest(f);
        h.assertTrue(TavernHostService.quoteAtHostContact(f.host(), f.guest(), order.getUUID()),
            "quote requires actual same-host contact");
        return order;
    }

    private static Container installTap(GameTestHelper h, Fixture f) {
        var plaque = (com.hearthstead.block.PlaqueBlockEntity) h.getLevel().getBlockEntity(f.tavern().plaquePos);
        CompoundTag tag = plaque.saveWithoutMetadata(h.getLevel().registryAccess());
        tag.putUUID("Building", f.tavern().id);
        tag.putString("Type", BuildingType.TAVERN.id());
        tag.putString("State", com.hearthstead.building.PlaqueState.LINKED_VALID.id());
        plaque.loadWithComponents(tag, h.getLevel().registryAccess());
        BlockPos tap = new BlockPos(9, 1, 3), barrelPos = new BlockPos(8, 1, 3);
        h.setBlock(barrelPos, Blocks.BARREL);
        h.setBlock(tap, com.hearthstead.registry.ModBlocks.ALE_TAP.get().defaultBlockState()
            .setValue(com.hearthstead.block.AleTapBlock.FACING, Direction.EAST));
        Container barrel = (Container) h.getLevel().getBlockEntity(h.absolutePos(barrelPos));
        barrel.setItem(0, new ItemStack(ModItems.ALE.get()));
        h.assertTrue(h.absolutePos(tap).equals(com.hearthstead.settlement.work.AleTapService
            .resolveTapForBarrel(h.getLevel(), f.tavern(), h.absolutePos(barrelPos))),
            "real physical Tavern tap is registered");
        return barrel;
    }

    /** Arrival uses the same physical finite pouch as the existing TavernHostGameTests visitor fixture. */
    private static void makeUnboundTraveler(GameTestHelper h, Fixture f, int coins) {
        SettlerEntity guest = f.guest();
        f.settlement().removeRecord(guest.getUUID());
        guest.unbind();
        guest.markTraveler(f.settlement().id, f.settlement().center);
        var plaque = (com.hearthstead.block.PlaqueBlockEntity) h.getLevel().getBlockEntity(f.tavern().plaquePos);
        CompoundTag plaqueTag = plaque.saveWithoutMetadata(h.getLevel().registryAccess());
        plaqueTag.putUUID("Building", f.tavern().id);
        plaqueTag.putString("Type", BuildingType.TAVERN.id());
        plaqueTag.putString("State", com.hearthstead.building.PlaqueState.LINKED_VALID.id());
        plaque.loadWithComponents(plaqueTag, h.getLevel().registryAccess());
        UUID transaction = UUID.randomUUID();
        var quote = com.hearthstead.settlement.RecruitmentQuote.fromStartingAttributes(
            transaction, guest.getUUID(), guest.attributes(), java.util.List.of());
        var traveling = com.hearthstead.settlement.RecruitmentTransaction.fresh(f.settlement().id)
            .adminPrime(transaction, h.getLevel().getGameTime(), f.tavern().id,
                f.tavern().plaquePos, f.tavern().anchor, h.getLevel().dimension().location())
            .travelerSpawned(guest.getUUID(), "Paying visitor", h.getLevel().getGameTime(), quote);
        f.settlement().applyRecruitment(traveling.arrived(h.getLevel().getGameTime()));
        guest.bag.setItem(0, new ItemStack(ModItems.GOLD_COIN.get(), coins));
    }

    @GameTest(batch = "tavern_restaurant_quote", template = "empty16", timeoutTicks = 80)
    public void residentFreeQuoteWaitsThirtyContactTicksAndCannotReset(GameTestHelper h) {
        Fixture f = fixture(h);
        TavernServingEntity order = quote(h, f, true);
        long quotedAt = order.quoteStartedAt();
        h.assertTrue(order.orderPrice() == 0 && order.mealPrice() == 0
            && !order.isOrderPaid() && f.guest().bag.isEmpty(), "bound resident has free food and Ale");
        h.assertTrue(!TavernHostService.acceptAtHostContact(f.host(), f.guest(), order.getUUID()),
            "same-tick acceptance must fail");
        h.runAfterDelay(10, () -> {
            h.assertTrue(TavernHostService.quoteAtHostContact(f.host(), f.guest(), order.getUUID())
                && order.quoteStartedAt() == quotedAt, "replayed quote cannot restart timer");
            h.assertTrue(!TavernHostService.acceptAtHostContact(f.host(), f.guest(), order.getUUID()),
                "accept before thirty ticks still fails");
        });
        h.runAfterDelay(31, () -> {
            h.assertTrue(TavernHostService.acceptAtHostContact(f.host(), f.guest(), order.getUUID())
                && order.isOrderAccepted() && order.orderEscrow().isEmpty(),
                "free resident accepted without fabricated Coin");
            h.assertTrue(TavernHostService.acceptAtHostContact(f.host(), f.guest(), order.getUUID())
                && order.orderEscrow().isEmpty(), "duplicate free acceptance is idempotent");
            h.succeed();
        });
    }

    @GameTest(batch = "tavern_restaurant_payment", template = "empty16", timeoutTicks = 80)
    public void onlyUnboundTravelerPrepaysPhysicalTwoPlusOneOnce(GameTestHelper h) {
        Fixture f = fixture(h);
        makeUnboundTraveler(h, f, 3);
        TavernServingEntity order = quote(h, f, true);
        h.assertTrue(order.orderPrice() == 3 && order.mealPrice() == 2,
            "visitor quote uses food 2 plus optional Ale 1");
        h.runAfterDelay(31, () -> {
            h.assertTrue(TavernHostService.acceptAtHostContact(f.host(), f.guest(), order.getUUID()),
                "contact accepts finite physical quote");
            h.assertTrue(f.guest().bag.isEmpty() && order.orderEscrow().getCount() == 3,
                "exact three Coins move into one saved order escrow");
            h.assertTrue(TavernHostService.acceptAtHostContact(f.host(), f.guest(), order.getUUID())
                && order.orderEscrow().getCount() == 3,
                "replayed acceptance cannot debit twice");
            h.assertTrue(TavernHostService.hasOrderForGuest(f.guest())
                && TavernHostService.pickup(f.host(), f.guest(), f.tavern(), f.source()) == null,
                "paid order cannot fall through to free legacy pickup");
            h.succeed();
        });
    }

    @GameTest(batch = "tavern_restaurant_insufficient", template = "empty16", timeoutTicks = 80)
    public void oneCoinVisitorCannotAcceptOrReceiveLegacyMeal(GameTestHelper h) {
        Fixture f = fixture(h);
        makeUnboundTraveler(h, f, 1);
        TavernServingEntity order = quote(h, f, false);
        h.assertTrue(order.orderPrice() == 2, "meal-only visitor quote is two physical Coins");
        h.runAfterDelay(31, () -> {
            h.assertTrue(!TavernHostService.acceptAtHostContact(f.host(), f.guest(), order.getUUID())
                && f.guest().bag.getItem(0).getCount() == 1
                && order.orderEscrow().isEmpty() && f.store().getItem(0).getCount() == 2,
                "insufficient purse leaves payer, stock and order untouched");
            h.assertTrue(TavernHostService.pickup(f.host(), f.guest(), f.tavern(), f.source()) == null,
                "old direct service cannot bypass the unpaid order");
            h.succeed();
        });
    }

    @GameTest(batch = "tavern_restaurant_reload", template = "empty16", timeoutTicks = 100)
    public void acceptedEscrowAndExactSeatSurviveReloadWithoutSecondDebit(GameTestHelper h) {
        Fixture f = fixture(h);
        makeUnboundTraveler(h, f, 3);
        TavernServingEntity order = quote(h, f, false);
        h.runAfterDelay(31, () -> {
            h.assertTrue(TavernHostService.acceptAtHostContact(f.host(), f.guest(), order.getUUID()),
                "visitor accepts quote");
            CompoundTag saved = new CompoundTag();
            order.saveWithoutId(saved);
            UUID id = order.getUUID(), seat = order.reservedSeatId();
            order.discard();
            TavernServingEntity decoded = ModEntities.TAVERN_SERVING.get().create(h.getLevel());
            decoded.load(saved);
            h.assertTrue(h.getLevel().addFreshEntity(decoded), "saved order rejoins with same UUID");
            h.assertTrue(decoded.getUUID().equals(id) && decoded.reservedSeatId().equals(seat)
                && TavernHostService.orderForGuest(f.guest()) == decoded
                && decoded.orderEscrow().getCount() == 2 && f.guest().bag.getItem(0).getCount() == 1,
                "reload preserves exact seat, identity and two escrow Coins");
            h.assertTrue(TavernHostService.acceptAtHostContact(f.host(), f.guest(), id)
                && decoded.orderEscrow().getCount() == 2,
                "reload replay cannot take another Coin");
            h.succeed();
        });
    }

    @GameTest(batch = "tavern_restaurant_cancel", template = "empty16", timeoutTicks = 90)
    public void cancellationBeforePickupReturnsExactVisitorEscrow(GameTestHelper h) {
        Fixture f = fixture(h);
        makeUnboundTraveler(h, f, 3);
        TavernServingEntity order = quote(h, f, false);
        h.runAfterDelay(31, () -> {
            h.assertTrue(TavernHostService.acceptAtHostContact(f.host(), f.guest(), order.getUUID()),
                "accepted meal debits exactly two Coins");
            h.assertTrue(f.guest().bag.getItem(0).getCount() == 1 && order.orderEscrow().getCount() == 2,
                "payer and saved escrow own all three original Coins");
            order.cancelOrder("guest_cancelled");
        });
        h.runAfterDelay(35, () -> {
            h.assertTrue(order.restaurantStage() == TavernServingEntity.RestaurantStage.CANCELLED
                && f.guest().bag.getItem(0).getCount() == 3 && order.orderEscrow().isEmpty()
                && f.store().getItem(0).getCount() == 2 && f.store().getItem(1).getCount() == 2,
                "cancel before stock transfer refunds exactly and leaves food/bottle untouched");
            h.succeed();
        });
    }

    @GameTest(batch = "tavern_restaurant_stock", template = "empty16", timeoutTicks = 430)
    public void vanishedStockTimesOutAndRefundsWithoutInventingMeal(GameTestHelper h) {
        Fixture f = fixture(h);
        makeUnboundTraveler(h, f, 3);
        TavernServingEntity order = quote(h, f, false);
        h.runAfterDelay(31, () -> {
            f.store().setItem(0, ItemStack.EMPTY);
            h.assertTrue(TavernHostService.acceptAtHostContact(f.host(), f.guest(), order.getUUID()),
                "order can be accepted after a truthful quote even if another actor takes stock");
            hostAtStore(f);
        });
        h.onEachTick(() -> {
            if (!order.isRemoved() && order.restaurantStage() == TavernServingEntity.RestaurantStage.ACCEPTED)
                order.serviceTick(f.host());
        });
        h.runAfterDelay(400, () -> {
            h.assertTrue(order.restaurantStage() == TavernServingEntity.RestaurantStage.CANCELLED
                && f.guest().bag.getItem(0).getCount() == 3
                && f.store().getItem(0).isEmpty() && !f.guest().hasMeal(),
                "stock/route timeout restores exact payer with no fabricated food");
            h.succeed();
        });
    }

    @GameTest(batch = "tavern_restaurant_raw", template = "empty16", timeoutTicks = 180)
    public void threeRealWheatBecomeOneBreadAndCancellationReturnsOnlyBread(GameTestHelper h) {
        Fixture f = fixture(h);
        f.store().setItem(0, new ItemStack(Items.WHEAT, 3));
        TavernServingEntity order = quote(h, f, false);
        boolean[] cooked = {false};
        h.runAfterDelay(31, () -> {
            h.assertTrue(TavernHostService.acceptAtHostContact(f.host(), f.guest(), order.getUUID()),
                "resident accepts free raw meal");
            hostAtStore(f);
        });
        h.onEachTick(() -> {
            if (order.isRemoved() || h.getLevel().getGameTime() - order.quoteStartedAt() < 31) return;
            if (!cooked[0] && order.restaurantStage() == TavernServingEntity.RestaurantStage.WAITING_SEAT) {
                h.assertTrue(f.store().getItem(0).isEmpty() && order.displayFood().is(Items.BREAD),
                    "actual three wheat leave source before one cooked bread exists");
                cooked[0] = true;
                order.cancelOrder("guest_left_after_cooking");
            }
            order.serviceTick(f.host());
        });
        h.runAfterDelay(150, () -> {
            h.assertTrue(cooked[0] && order.restaurantStage() == TavernServingEntity.RestaurantStage.CANCELLED,
                "cooked order was cancelled and settled");
            int wheat = 0, bread = 0, bottles = 0;
            for (int slot = 0; slot < f.store().getContainerSize(); slot++) {
                ItemStack stack = f.store().getItem(slot);
                if (stack.is(Items.WHEAT)) wheat += stack.getCount();
                if (stack.is(Items.BREAD)) bread += stack.getCount();
                if (stack.is(Items.GLASS_BOTTLE)) bottles += stack.getCount();
            }
            h.assertTrue(wheat == 0 && bread == 1 && bottles == 2 && !f.guest().hasMeal(),
                "cancel after cooking returns the one real bread, not original wheat or a second meal");
            h.succeed();
        });
    }

    @GameTest(batch = "tavern_restaurant_claim", template = "empty16", timeoutTicks = 50)
    public void secondGuestCannotStealFirstSeatOrStartSecondHostSession(GameTestHelper h) {
        Fixture f = fixture(h);
        TavernServingEntity first = quote(h, f, false);
        SettlerEntity second = resident(h, f.settlement(), new BlockPos(10, 1, 9));
        second.setHunger(20);
        h.assertTrue(TavernHostService.reserveQuote(f.host(), second, f.seat(), false) == null
            && TavernHostService.orderForGuest(f.guest()) == first
            && !TavernHostService.hasOrderForGuest(second)
            && f.tavern().tavernServingClaims.owns(f.seat().site().table(), first.getUUID()),
            "another guest cannot use the reserved seat, claim, host session or receipt");
        h.succeed();
    }

    @GameTest(batch = "tavern_restaurant_receipt", template = "empty16", timeoutTicks = 300)
    public void physicalMealRevenueMovesOnlyAtEightTickHandover(GameTestHelper h) {
        Fixture f = fixture(h);
        makeUnboundTraveler(h, f, 3);
        TavernServingEntity order = quote(h, f, false);
        h.runAfterDelay(31, () -> {
            h.assertTrue(TavernHostService.acceptAtHostContact(f.host(), f.guest(), order.getUUID()),
                "visitor's two meal Coins enter escrow");
            hostAtStore(f);
        });
        h.onEachTick(() -> {
            if (order.isRemoved() || !order.isOrderAccepted()) return;
            if (order.restaurantStage() == TavernServingEntity.RestaurantStage.ACCEPTED
                || order.restaurantStage() == TavernServingEntity.RestaurantStage.PREPARING) hostAtStore(f);
            else if (order.restaurantStage() == TavernServingEntity.RestaurantStage.SERVING) hostAtTable(f);
            order.serviceTick(f.host());
            int till = 0;
            for (int slot = 0; slot < f.store().getContainerSize(); slot++)
                if (f.store().getItem(slot).is(ModItems.GOLD_COIN.get())) till += f.store().getItem(slot).getCount();
            if (!f.guest().hasMeal())
                h.assertTrue(till == 0 && order.orderEscrow().getCount() == 2,
                    "Coins remain with saved order through stock pickup, prep and table slide");
            else {
                h.assertTrue(till == 2 && order.orderEscrow().isEmpty()
                    && f.guest().bag.getItem(0).getCount() == 1,
                    "only actual eight-tick handover settles exact food revenue");
                h.succeed();
            }
        });
    }

    @GameTest(batch = "tavern_restaurant_ale_receipt", template = "empty16", timeoutTicks = 500)
    public void paidComboKeepsAleCoinSeparateUntilRealSip(GameTestHelper h) {
        Fixture f = fixture(h);
        makeUnboundTraveler(h, f, 3);
        TavernServingEntity order = quote(h, f, true);
        Container tapBarrel = (Container) h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(8, 1, 3)));
        h.runAfterDelay(31, () -> {
            h.assertTrue(TavernHostService.acceptAtHostContact(f.host(), f.guest(), order.getUUID()),
                "full three-Coin combo accepted once");
            hostAtStore(f);
        });
        h.onEachTick(() -> {
            if (order.isRemoved() || !order.isOrderAccepted()) return;
            if (order.restaurantStage() == TavernServingEntity.RestaurantStage.ACCEPTED
                || order.restaurantStage() == TavernServingEntity.RestaurantStage.PREPARING) hostAtStore(f);
            else if (order.restaurantStage() == TavernServingEntity.RestaurantStage.SERVING) {
                if (order.phase() == TavernServingEntity.Phase.CARRYING && order.displayAle().isEmpty()) hostAtTap(h, f);
                else hostAtTable(f);
            }
            if (f.guest().hasMeal()) {
                f.guest().setActivity(SettlerActivity.EATING);
                f.guest().tickMeal();
            }
            order.serviceTick(f.host());
            int till = 0;
            for (int slot = 0; slot < f.store().getContainerSize(); slot++)
                if (f.store().getItem(slot).is(ModItems.GOLD_COIN.get())) till += f.store().getItem(slot).getCount();
            if (order.mealSettled() && order.displayPayment().isEmpty())
                h.assertTrue(till == 2 && order.orderEscrow().getCount() == 1,
                    "meal handover pays only food line; Ale Coin remains in escrow");
            if (order.displayPayment().getCount() == 1) {
                int ale = 0;
                for (int slot = 0; slot < tapBarrel.getContainerSize(); slot++)
                    if (tapBarrel.getItem(slot).is(ModItems.ALE.get())) ale += tapBarrel.getItem(slot).getCount();
                h.assertTrue(till == 2 && ale == 0 && order.orderEscrow().isEmpty(),
                    "real sip alone consumes one Ale and moves its Coin to saved tap-return cargo");
                h.succeed();
            }
        });
    }

    @GameTest(batch = "tavern_restaurant_departure", template = "empty16", timeoutTicks = 90)
    public void departingVisitorStillReceivesExactQuotedPayerRefund(GameTestHelper h) {
        Fixture f = fixture(h);
        makeUnboundTraveler(h, f, 3);
        TavernServingEntity order = quote(h, f, false);
        h.runAfterDelay(31, () -> {
            h.assertTrue(TavernHostService.acceptAtHostContact(f.host(), f.guest(), order.getUUID()),
                "two physical Coins leave finite visitor bag");
            f.settlement().applyRecruitment(f.settlement().recruitment.departing());
            h.assertTrue(TavernSeating.visitSettlement(f.guest()) == null,
                "DEPARTING no longer qualifies for a new Tavern visit");
            order.cancelOrder("visitor_departure");
        });
        h.runAfterDelay(35, () -> {
            h.assertTrue(order.restaurantStage() == TavernServingEntity.RestaurantStage.CANCELLED
                && f.guest().bag.getItem(0).getCount() == 3 && order.orderEscrow().isEmpty(),
                "saved payer bag receives exact refund after visit eligibility ends");
            h.succeed();
        });
    }

    @GameTest(batch = "tavern_restaurant_terminal", template = "empty16", timeoutTicks = 70)
    public void missingGuestReleasesTerminalTableAndWorkerIndependently(GameTestHelper h) {
        Fixture f = fixture(h);
        TavernServingEntity order = quote(h, f, false);
        order.cancelOrder("guest_removed");
        f.guest().discard();
        h.runAfterDelay(5, () -> {
            h.assertTrue(order.restaurantStage() == TavernServingEntity.RestaurantStage.CANCELLED
                && !f.tavern().tavernServingClaims.owns(f.seat().site().table(), order.getUUID())
                && !TavernHostService.hasSession(f.host()),
                "dead/unloaded guest cannot hold the table or Innkeeper after empty terminal receipt");
            h.succeed();
        });
    }

    @GameTest(batch = "tavern_restaurant_nbt", template = "empty16", timeoutTicks = 80)
    public void malformedSavedEscrowQuarantinesInsteadOfBecomingFreeMeal(GameTestHelper h) {
        Fixture f = fixture(h);
        makeUnboundTraveler(h, f, 3);
        TavernServingEntity order = quote(h, f, false);
        h.runAfterDelay(31, () -> {
            h.assertTrue(TavernHostService.acceptAtHostContact(f.host(), f.guest(), order.getUUID()),
                "real escrow exists before save mutation");
            CompoundTag corrupted = new CompoundTag();
            order.saveWithoutId(corrupted);
            corrupted.getCompound("RestaurantOrder").putString("Escrow", "not an ItemStack");
            TavernServingEntity decoded = ModEntities.TAVERN_SERVING.get().create(h.getLevel());
            decoded.load(corrupted);
            CompoundTag after = new CompoundTag();
            decoded.saveWithoutId(after);
            h.assertTrue(after.contains("QuarantinedSession", 10),
                "present but malformed escrow must be quarantined, never decoded empty");
            h.succeed();
        });
    }

    @GameTest(batch = "tavern_restaurant_payer_death", template = "empty16", timeoutTicks = 100)
    public void paidTravelerDeathMaterializesExactUnearnedCoinsOnceAndReleasesClaim(GameTestHelper h) {
        Fixture f = fixture(h);
        makeUnboundTraveler(h, f, 3);
        TavernServingEntity order = quote(h, f, false);
        Vec3 death = f.guest().position();
        h.runAfterDelay(31, () -> {
            h.assertTrue(TavernHostService.acceptAtHostContact(f.host(), f.guest(), order.getUUID())
                && order.orderEscrow().getCount() == 2 && f.guest().bag.getItem(0).getCount() == 1,
                "one Coin remains in payer bag, two are unearned escrow");
            f.guest().kill();
        });
        h.runAfterDelay(55, () -> {
            int dropped = 0;
            var nearby = h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(death, death).inflate(6));
            for (var item : nearby)
                if (item.getItem().is(ModItems.GOLD_COIN.get())) dropped += item.getItem().getCount();
            h.assertTrue(order.deathRefundQueued() && order.orderEscrow().isEmpty()
                && dropped == 3 && !TavernHostService.hasSession(f.host())
                && !f.tavern().tavernServingClaims.owns(f.seat().site().table(), order.getUUID()),
                "dead payer's bag Coin plus stable-ID order refund conserve exact three physical Coins and free service");
            h.succeed();
        });
    }

    @GameTest(batch = "tavern_restaurant_table_cancel", template = "empty16", timeoutTicks = 200)
    public void tabletopCancellationActuallyAdvancesPhysicalPickupBeforeRefund(GameTestHelper h) {
        Fixture f = fixture(h);
        TavernServingEntity order = quote(h, f, false);
        boolean[] cancelled = {false}, sawPickup = {false};
        h.runAfterDelay(31, () -> {
            h.assertTrue(TavernHostService.acceptAtHostContact(f.host(), f.guest(), order.getUUID()),
                "free resident order accepted");
            hostAtStore(f);
        });
        h.onEachTick(() -> {
            if (order.isRemoved()) {
                h.assertTrue(cancelled[0] && sawPickup[0] && f.store().getItem(0).getCount() == 2
                    && !f.guest().hasMeal(),
                    "terminal removal follows physical pickup and source return, never skips them");
                h.succeed(); return;
            }
            if (!order.isOrderAccepted() && !cancelled[0]) return;
            if (order.restaurantStage() == TavernServingEntity.RestaurantStage.ACCEPTED
                || order.restaurantStage() == TavernServingEntity.RestaurantStage.PREPARING) hostAtStore(f);
            else if (cancelled[0] && (order.phase() == TavernServingEntity.Phase.RETURN_TABLE
                || order.phase() == TavernServingEntity.Phase.PICKING_UP)) {
                Vec3 stand = TavernHostService.pickupStand(f.host(), order);
                h.assertTrue(stand != null, "real tabletop has a reachable pickup stand");
                f.host().setPos(stand);
            } else if (order.phase() == TavernServingEntity.Phase.RETURNING) hostAtStore(f);
            else hostAtTable(f);
            if (!cancelled[0] && order.phase() == TavernServingEntity.Phase.SLIDING) {
                order.cancelOrder("guest_cancelled_on_table");
                cancelled[0] = true;
                h.assertTrue(order.phase() == TavernServingEntity.Phase.RETURN_TABLE
                    && !order.displayFood().isEmpty(),
                    "cancellation leaves physical meal on table for pickup");
            }
            order.serviceTick(f.host());
            if (order.phase() == TavernServingEntity.Phase.PICKING_UP) sawPickup[0] = true;
            if (cancelled[0] && order.restaurantStage() == TavernServingEntity.RestaurantStage.CANCELLED) {
                h.assertTrue(sawPickup[0] && !f.guest().hasMeal(),
                    "refund waits for genuine RETURN_TABLE and PICKING_UP contact phases");
                h.succeed();
            }
        });
    }

    @GameTest(batch = "tavern_restaurant_death_receipt", template = "empty16", timeoutTicks = 110)
    public void absentOrderReloadUsesPersistedExactPayerDeathNoticeOnce(GameTestHelper h) {
        Fixture f = fixture(h);
        makeUnboundTraveler(h, f, 3);
        TavernServingEntity order = quote(h, f, false);
        h.runAfterDelay(31, () -> {
            h.assertTrue(TavernHostService.acceptAtHostContact(f.host(), f.guest(), order.getUUID()),
                "accepted order owns two unearned physical Coins");
            CompoundTag orderSave = new CompoundTag();
            order.saveWithoutId(orderSave);
            UUID orderId = order.getUUID(), payerId = f.guest().getUUID();
            order.discard(); // Exact entity lookup absent; this tests the saved receipt layer, not chunk IO.
            f.guest().kill();
            var ledger = com.hearthstead.settlement.work.TavernOrderDeathSavedData.get(h.getLevel());
            CompoundTag ledgerSave = new CompoundTag();
            ledger.save(ledgerSave, h.getLevel().registryAccess());
            var reloadedLedger = com.hearthstead.settlement.work.TavernOrderDeathSavedData.load(
                ledgerSave, h.getLevel().registryAccess());
            h.assertTrue(reloadedLedger.exact(h.getLevel(), orderId, payerId) != null,
                "confirmed death notice survives its own serialized roundtrip while order is absent");
            TavernServingEntity decoded = ModEntities.TAVERN_SERVING.get().create(h.getLevel());
            decoded.load(orderSave);
            h.assertTrue(h.getLevel().addFreshEntity(decoded) && decoded.getUUID().equals(orderId),
                "same saved order UUID reloads after payer died");
        });
        h.runAfterDelay(60, () -> {
            TavernServingEntity decoded = TavernHostService.orderForGuest(f.guest());
            // Terminal cleanup may already have cleared the guest pointer.
            int dropped = 0;
            for (var item : h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(f.guest().position(), f.guest().position()).inflate(6)))
                if (item.getItem().is(ModItems.GOLD_COIN.get())) dropped += item.getItem().getCount();
            h.assertTrue(dropped == 3 && !TavernHostService.hasSession(f.host())
                && !f.tavern().tavernServingClaims.occupied(f.seat().site().table())
                && (decoded == null || decoded.orderEscrow().isEmpty()),
                "exact persisted death receipt releases two escrow Coins once and frees service after owner reload");
            h.succeed();
        });
    }

    @GameTest(batch = "tavern_restaurant_death_nbt", template = "empty16", timeoutTicks = 30)
    public void malformedDeathNoticeListAndPriorQuarantineRetainExactRawNbt(GameTestHelper h) {
        CompoundTag wrongType = new CompoundTag();
        wrongType.putInt("Schema", 1);
        net.minecraft.nbt.ListTag strings = new net.minecraft.nbt.ListTag();
        strings.add(net.minecraft.nbt.StringTag.valueOf("untrusted-death-proof"));
        wrongType.put("Notices", strings);
        var rejected = com.hearthstead.settlement.work.TavernOrderDeathSavedData.load(
            wrongType, h.getLevel().registryAccess());
        CompoundTag savedWrong = rejected.save(new CompoundTag(), h.getLevel().registryAccess());
        h.assertTrue(wrongType.equals(savedWrong)
            && !rejected.record(h.getLevel(), UUID.randomUUID(), UUID.randomUUID(), 1, 2, 3),
            "wrong-element list must remain exact raw quarantine and cannot accept new death proof");

        CompoundTag prior = new CompoundTag();
        prior.putInt("Schema", 1);
        prior.putBoolean("Quarantined", true);
        prior.put("Notices", new net.minecraft.nbt.ListTag());
        prior.putString("OriginalFailure", "preserve this evidence");
        var priorRejected = com.hearthstead.settlement.work.TavernOrderDeathSavedData.load(
            prior, h.getLevel().registryAccess());
        CompoundTag savedPrior = priorRejected.save(new CompoundTag(), h.getLevel().registryAccess());
        h.assertTrue(prior.equals(savedPrior)
            && !priorRejected.record(h.getLevel(), UUID.randomUUID(), UUID.randomUUID(), 1, 2, 3),
            "previously quarantined raw file must survive another save without becoming empty-healthy");
        h.succeed();
    }

    @GameTest(batch = "tavern_restaurant_host_goal", template = "empty16", timeoutTicks = 160)
    public void acceptedSessionMakesOrdinaryInnkeeperGoalTakeRealStock(GameTestHelper h) {
        Fixture f = fixture(h);
        TavernServingEntity order = quote(h, f, false);
        h.runAfterDelay(31, () -> {
            h.assertTrue(TavernHostService.acceptAtHostContact(f.host(), f.guest(), order.getUUID())
                && TavernHostService.hasSession(f.host())
                && TavernHostService.current(f.host()) == order,
                "accepted order remains the exact host session, independent of legacy guest search");
            hostAtStore(f);
            f.host().setNoAi(false); // Actual registered InnkeeperWorkGoal must drive the next transfer.
        });
        h.runAfterDelay(115, () -> {
            h.assertTrue(f.store().getItem(0).getCount() == 1
                && f.store().getItem(1).getCount() == 1
                && (order.restaurantStage() == TavernServingEntity.RestaurantStage.PREPARING
                    || order.restaurantStage() == TavernServingEntity.RestaurantStage.WAITING_SEAT
                    || order.restaurantStage() == TavernServingEntity.RestaurantStage.SERVING),
                "registered host goal physically takes one meal and bottle through saved accepted session");
            h.succeed();
        });
    }
    /** A legal 16x8x15 Tavern must not spend its seat budget while locating late stock. */
    @GameTest(template = "empty16", timeoutTicks = 40)
    public void residentMealSearchSeparatesStockAndSeatScanBudgets(GameTestHelper h) {
        final int width = 16, height = 8, depth = 15;
        for (int x = 0; x < width; x++) for (int z = 0; z < depth; z++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            for (int y = 1; y < height; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        h.getLevel().setDayTime(11500);
        Settlement settlement = new Settlement(UUID.randomUUID(), "Large meal-search fixture",
            h.absolutePos(new BlockPos(1, 1, 1)));
        SettlementSavedData.get(h.getLevel()).settlements.put(settlement.id, settlement);
        Building tavern = GameTestFixtures.registerWithBounds(h, settlement, BuildingType.TAVERN,
            new BlockPos(6, 1, 6), new BlockPos(1, 1, 1),
            BoundingBox.fromCorners(h.absolutePos(new BlockPos(0, 0, 0)),
                h.absolutePos(new BlockPos(width - 1, height - 1, depth - 1))));
        BlockPos sourceRel = new BlockPos(5, 1, 8);
        BlockPos chairRel = new BlockPos(6, 1, 9);
        BlockPos tableRel = chairRel.north();
        h.setBlock(sourceRel, Blocks.BARREL);
        Container source = (Container) h.getLevel().getBlockEntity(h.absolutePos(sourceRel));
        source.setItem(0, new ItemStack(Items.BREAD, 2));
        source.setItem(1, new ItemStack(Items.GLASS_BOTTLE, 2));
        h.setBlock(chairRel, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
        h.setBlock(tableRel, Blocks.OAK_FENCE);
        h.setBlock(tableRel.above(), Blocks.OAK_PRESSURE_PLATE);
        SettlerEntity host = resident(h, settlement, new BlockPos(5, 1, 9));
        h.assertTrue(Employment.hire(h.getLevel(), settlement, tavern, host).ok(), "large room has real Innkeeper authority");
        host.setNoAi(false);
        SettlerEntity guest = resident(h, settlement, new BlockPos(10, 1, 12));
        guest.setNoAi(false);
        guest.setHunger(55);
        guest.setActivity(SettlerActivity.IDLE);
        var candidate = TavernSeating.findReachableMeal(guest);
        int stockCell = 1 + sourceRel.getX() + width * sourceRel.getY()
            + width * height * sourceRel.getZ();
        int chairCell = 1 + chairRel.getX() + width * chairRel.getY()
            + width * height * chairRel.getZ();
        h.assertTrue(stockCell == 1046 && chairCell == 1175
                && stockCell <= TavernSeating.MAX_SCAN_CELLS
                && chairCell <= TavernSeating.MAX_SCAN_CELLS
                && 3 <= TavernSeating.MAX_PATHS,
            "late stock, one host-contact probe and one guest/host route stay inside the separate hard budgets");
        h.assertTrue(candidate != null && candidate.source().equals(h.absolutePos(sourceRel))
                && candidate.site().chair().equals(h.absolutePos(chairRel))
                && candidate.path() != null && candidate.path().canReach(),
            "late physical stock and later physical chair produce one reachable resident meal offer");
        h.succeed();
    }
}
