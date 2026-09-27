package com.hearthstead.settlement.request;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.gametest.GameTestFixtures;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.development.Development;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Stout Straps two-request batching (batch {@code courier_batching}) through
 * the real ledger authority and a real Courier entity: two requests in one
 * trip, reload mid-trip, death holding two, one pickup failing while the
 * other completes, a batch across two Warehouses, the safety switch turned
 * off mid-trip, and the real CourierWorkGoal forming the batch.
 *
 * <p>Every test checks conservation: oak (request A) and birch (request B)
 * units are counted across source chests, bag, stow, targets and dropped
 * items, and never change in total.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class CourierBatchingGameTests {
    private static final String BATCH = "courier_batching";

    public CourierBatchingGameTests() {
    }

    @GameTest(template = "empty16", timeoutTicks = 60, batch = BATCH)
    public void twoRequestsRideOneTripPartnerFirst(GameTestHelper helper) {
        Fixture f = fixture(helper);
        try {
            Pair p = formBatch(helper, f, f.targetPos);
            RequestLedgerService.Route route = RequestLedgerService.routeForCourier(helper.getLevel(), f.settlement, f.courier);
            helper.assertTrue(route.request() != null && route.request().id().equals(p.b)
                && route.phase() == RequestLedgerService.RoutePhase.TO_SOURCE, "the partner is routed while A waits stowed");
            pickupB(helper, f, p);
            deliver(helper, f, p.b, f.targetPos);
            route = RequestLedgerService.routeForCourier(helper.getLevel(), f.settlement, f.courier);
            helper.assertTrue(route.request() != null && route.request().id().equals(p.a)
                && route.phase() == RequestLedgerService.RoutePhase.TO_TARGET, "A resumes after B");
            helper.assertTrue(f.courier.batchStow.isEmpty() && count(f.courier.bag, Items.OAK_LOG) == 2,
                "A's load is back in the bag, exactly");
            deliver(helper, f, p.a, f.targetPos);
            helper.assertTrue(count(f.target, Items.OAK_LOG) == 2 && count(f.target, Items.BIRCH_LOG) == 2,
                "both loads arrived");
            assertConserved(helper, f);
            assertNotQuarantined(helper, f);
        } finally {
            CourierBatching.overrideForTests(null);
        }
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 60, batch = BATCH)
    public void batchedTripSurvivesReloadMidway(GameTestHelper helper) {
        Fixture f = fixture(helper);
        try {
            Pair p = formBatch(helper, f, f.targetPos);
            pickupB(helper, f, p);
            // Save and restore the Courier and the whole ledger.
            CompoundTag actor = new CompoundTag();
            f.courier.saveWithoutId(actor);
            f.courier.bag.clearContent();
            f.courier.batchStow.clearContent();
            f.courier.load(actor);
            var saved = RequestLedgerSavedData.get(helper.getLevel());
            var restored = RequestLedgerSavedData.load(saved.save(new CompoundTag(),
                helper.getLevel().registryAccess()), helper.getLevel().registryAccess());
            helper.getLevel().getDataStorage().set("hearthstead_request_ledger", restored);
            helper.assertTrue(count(f.courier.batchStow, Items.OAK_LOG) == 2
                && count(f.courier.bag, Items.BIRCH_LOG) == 2, "stow and bag survive the reload");
            RequestLedgerService.Route route = RequestLedgerService.routeForCourier(helper.getLevel(), f.settlement, f.courier);
            helper.assertTrue(route.request() != null && route.request().id().equals(p.b)
                && route.phase() == RequestLedgerService.RoutePhase.TO_TARGET, "recovery routes B, not the stowed A");
            deliver(helper, f, p.b, f.targetPos);
            deliver(helper, f, p.a, f.targetPos);
            assertConserved(helper, f);
            assertNotQuarantined(helper, f);
        } finally {
            CourierBatching.overrideForTests(null);
        }
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 200, batch = BATCH)
    public void courierDyingWithTwoRequestsDropsBothLoads(GameTestHelper helper) {
        Fixture f = fixture(helper);
        Pair p = formBatch(helper, f, f.targetPos);
        pickupB(helper, f, p);
        CourierBatching.overrideForTests(null);
        f.courier.kill();
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(f.courier.bag.isEmpty() && f.courier.batchStow.isEmpty(),
                "a dead Courier keeps nothing");
            int oak = count(f.sourceA, Items.OAK_LOG) + count(f.target, Items.OAK_LOG) + dropped(helper, Items.OAK_LOG);
            int birch = count(f.sourceB, Items.BIRCH_LOG) + count(f.target, Items.BIRCH_LOG) + dropped(helper, Items.BIRCH_LOG);
            helper.assertTrue(oak == 2 && birch == 2, "both loads lie on the ground: oak " + oak + " birch " + birch);
            assertNotQuarantined(helper, f);
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 60, batch = BATCH)
    public void partnerSourceEmptiedReleasesOnlyThePartner(GameTestHelper helper) {
        Fixture f = fixture(helper);
        try {
            Pair p = formBatch(helper, f, f.targetPos);
            ItemStack birch = f.sourceB.removeItemNoUpdate(0);
            f.sourceB.setChanged();
            RequestLedgerService.Decision failed = RequestLedgerService.pickup(helper.getLevel(), f.settlement, p.b, f.courier);
            helper.assertTrue(!failed.accepted(), "an emptied source cannot be lifted");
            RequestLedgerService.Route route = RequestLedgerService.routeForCourier(helper.getLevel(), f.settlement, f.courier);
            helper.assertTrue(route.request() != null && route.request().id().equals(p.a)
                && route.phase() == RequestLedgerService.RoutePhase.TO_TARGET, "A goes on alone: " + route);
            RequestLedger ledger = RequestLedgerSavedData.get(helper.getLevel()).existing(f.settlement.id);
            RequestRecord partner = ledger.any(p.b);
            helper.assertTrue(partner == null || !f.courier.getUUID().equals(partner.courierId())
                    || partner.state().terminal(), "only the partner was released");
            deliver(helper, f, p.a, f.targetPos);
            f.sourceB.setItem(0, birch);
            assertConserved(helper, f);
            assertNotQuarantined(helper, f);
        } finally {
            CourierBatching.overrideForTests(null);
        }
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 60, batch = BATCH)
    public void batchCrossesTwoWarehouses(GameTestHelper helper) {
        Fixture f = fixture(helper);
        try {
            Pair p = formBatch(helper, f, f.target2Pos);
            pickupB(helper, f, p);
            deliver(helper, f, p.b, f.target2Pos);
            deliver(helper, f, p.a, f.targetPos);
            helper.assertTrue(count(f.target, Items.OAK_LOG) == 2 && count(f.target2, Items.BIRCH_LOG) == 2,
                "each load reaches its own Warehouse");
            assertConserved(helper, f);
            assertNotQuarantined(helper, f);
        } finally {
            CourierBatching.overrideForTests(null);
        }
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 60, batch = BATCH)
    public void switchOffMidTripFinishesWithoutQuarantine(GameTestHelper helper) {
        Fixture f = fixture(helper);
        try {
            Pair p = formBatch(helper, f, f.targetPos);
            CourierBatching.overrideForTests(false);
            pickupB(helper, f, p);
            deliver(helper, f, p.b, f.targetPos);
            RequestLedgerService.Route route = RequestLedgerService.routeForCourier(helper.getLevel(), f.settlement, f.courier);
            helper.assertTrue(route.request() != null && route.request().id().equals(p.a), "A still resumes with the switch off");
            deliver(helper, f, p.a, f.targetPos);
            assertConserved(helper, f);
            assertNotQuarantined(helper, f);
            // And with the switch off no new batch forms: exactly the single-request trip.
            f.sourceA.setItem(0, new ItemStack(Items.OAK_LOG, 2));
            f.sourceB.setItem(0, new ItemStack(Items.BIRCH_LOG, 2));
            UUID a2 = openAndLift(helper, f, f.sourceAPos, f.targetPos);
            open(helper, f, f.sourceBPos, f.targetPos);
            RequestLedgerService.Decision none = RequestLedgerService.reserveBatchPartner(helper.getLevel(),
                f.settlement, f.courier, f.courier.blockPosition());
            helper.assertTrue(none.outcome() != RequestLedgerService.Outcome.COMMITTED
                    && f.courier.batchStow.isEmpty() && count(f.courier.bag, Items.OAK_LOG) == 2,
                "switch off: no batch, bag untouched");
            deliver(helper, f, a2, f.targetPos);
        } finally {
            CourierBatching.overrideForTests(null);
        }
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 60, batch = BATCH)
    public void withoutStoutStrapsNoBatchForms(GameTestHelper helper) {
        Fixture f = fixture(helper, false, Items.BIRCH_LOG);
        try {
            CourierBatching.overrideForTests(true);
            UUID a = openAndLift(helper, f, f.sourceAPos, f.targetPos);
            open(helper, f, f.sourceBPos, f.targetPos);
            RequestLedgerService.Decision none = RequestLedgerService.reserveBatchPartner(helper.getLevel(),
                f.settlement, f.courier, f.courier.blockPosition());
            helper.assertTrue(none.outcome() != RequestLedgerService.Outcome.COMMITTED
                    && f.courier.batchStow.isEmpty() && count(f.courier.bag, Items.OAK_LOG) == 2,
                "Stout Straps not learned: one request per trip, bag untouched");
            deliver(helper, f, a, f.targetPos);
            assertNotQuarantined(helper, f);
        } finally {
            CourierBatching.overrideForTests(null);
        }
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 60, batch = BATCH)
    public void theSameItemNeverSharesABatch(GameTestHelper helper) {
        Fixture f = fixture(helper, true, Items.OAK_LOG);
        try {
            CourierBatching.overrideForTests(true);
            UUID a = openAndLift(helper, f, f.sourceAPos, f.targetPos);
            open(helper, f, f.sourceBPos, f.targetPos);
            RequestLedgerService.Decision same = RequestLedgerService.reserveBatchPartner(helper.getLevel(),
                f.settlement, f.courier, f.courier.blockPosition());
            helper.assertTrue(same.outcome() != RequestLedgerService.Outcome.COMMITTED
                    && f.courier.batchStow.isEmpty() && count(f.courier.bag, Items.OAK_LOG) == 2,
                "an oak partner for an oak load is refused, so every bag item has one owner");
            deliver(helper, f, a, f.targetPos);
            assertNotQuarantined(helper, f);
        } finally {
            CourierBatching.overrideForTests(null);
        }
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 700, batch = BATCH)
    public void realCourierGoalFormsTheBatchAtTheEndOfItsLift(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000L);
        CourierBatching.overrideForTests(true);
        // Same contact geometry as RequestLedgerGameTests' real source session:
        // solid floor and the Courier standing square in front of chest A.
        // Solid ground under the whole arena (the template floor layer is air);
        // Clear terrain before fixtures so their containers and plaque supports survive.
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        Fixture f = fixture(helper);
        f.courier.setPos(net.minecraft.world.phys.Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 1, 4))));
        f.courier.setNoAi(true);
        UUID a = open(helper, f, f.sourceAPos, f.targetPos);
        UUID b = open(helper, f, f.sourceBPos, f.targetPos);
        helper.assertTrue(RequestLedgerService.reserve(helper.getLevel(), f.settlement, a, f.courier).accepted(),
            "A reserved");
        RequestRecord aRow = RequestLedgerSavedData.get(helper.getLevel()).existing(f.settlement.id).active(a);
        var session = new com.hearthstead.entity.ai.CourierSourceBagSession(f.courier);
        session.begin(helper.getLevel(), aRow);
        if (!session.active()) {
            // Say exactly why the session found no floor cell beside chest A.
            StringBuilder cells = new StringBuilder();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    BlockPos p = f.sourceAPos.offset(dx, 0, dz);
                    boolean sturdy = helper.getLevel().getBlockState(p.below())
                        .isFaceSturdy(helper.getLevel(), p.below(), net.minecraft.core.Direction.UP);
                    boolean clear = helper.getLevel().noCollision(f.courier, f.courier.getBoundingBox().move(
                        net.minecraft.world.phys.Vec3.atBottomCenterOf(p).subtract(f.courier.position())));
                    cells.append(dx).append(',').append(dz).append(sturdy ? " floor" : " nofloor")
                        .append(clear ? "/clear " : "/blocked ");
                }
            }
            helper.fail("A's real source session did not begin: courier abs=" + f.courier.blockPosition()
                + " expected abs=" + helper.absolutePos(new BlockPos(3, 1, 4)) + " chest abs=" + f.sourceAPos
                + " A=" + aRow.effectiveState() + " cells: " + cells);
        }
        var goal = new com.hearthstead.entity.ai.CourierWorkGoal(f.courier);
        helper.assertTrue(goal.canUse(), "the saved source session selects the goal");
        goal.start();
        helper.onEachTick(() -> {
            // Other tests of this batch reset the shared switch override when
            // they finish; this long test re-asserts it on its own ticks.
            CourierBatching.overrideForTests(true);
            goal.tick();
            RequestLedger ledger = RequestLedgerSavedData.get(helper.getLevel()).existing(f.settlement.id);
            RequestRecord partner = ledger.active(b);
            if (helper.getTick() >= 680) {
                RequestRecord first = ledger.any(a);
                helper.fail("no batch formed: A=" + (first == null ? "gone" : first.state() + " moved "
                    + first.movedCount()) + " session=" + session.active() + " bag=" + count(f.courier.bag, Items.OAK_LOG)
                    + " stow=" + count(f.courier.batchStow, Items.OAK_LOG) + " B=" + (partner == null ? "gone"
                    : partner.state() + " owner " + partner.courierId()) + " courier="
                    + helper.relativePos(f.courier.blockPosition()) + " straps="
                    + CourierBatching.allowed(helper.getLevel(), f.settlement));
            }
            if (partner != null && f.courier.getUUID().equals(partner.courierId())) {
                helper.assertTrue(count(f.courier.batchStow, Items.OAK_LOG) == 2 && f.courier.bag.isEmpty(),
                    "the goal stowed A and reserved B");
                goal.stop();
                CourierBatching.overrideForTests(null);
                helper.succeed();
            }
        });
    }

    // ------------------------------------------------------------ helpers

    /** Opens A and B, lifts A, forms the batch (A stowed, B reserved). */
    private static Pair formBatch(GameTestHelper helper, Fixture f, BlockPos partnerTarget) {
        CourierBatching.overrideForTests(true);
        UUID a = openAndLift(helper, f, f.sourceAPos, f.targetPos);
        UUID b = open(helper, f, f.sourceBPos, partnerTarget);
        RequestLedgerService.Decision batch = RequestLedgerService.reserveBatchPartner(helper.getLevel(),
            f.settlement, f.courier, f.courier.blockPosition());
        helper.assertTrue(batch.outcome() == RequestLedgerService.Outcome.COMMITTED
                && batch.request() != null && batch.request().id().equals(b),
            "B joins the batch: " + batch);
        helper.assertTrue(count(f.courier.batchStow, Items.OAK_LOG) == 2 && f.courier.bag.isEmpty(),
            "A's two logs wait in the stow; the bag is free for B");
        return new Pair(a, b);
    }

    private static void pickupB(GameTestHelper helper, Fixture f, Pair p) {
        RequestLedgerService.Decision lifted = RequestLedgerService.pickup(helper.getLevel(), f.settlement, p.b, f.courier);
        helper.assertTrue(lifted.accepted() && count(f.courier.bag, Items.BIRCH_LOG) == 2
            && count(f.courier.batchStow, Items.OAK_LOG) == 2, "B lifted beside the stowed A: " + lifted);
    }

    private static UUID openAndLift(GameTestHelper helper, Fixture f, BlockPos source, BlockPos target) {
        UUID id = open(helper, f, source, target);
        helper.assertTrue(RequestLedgerService.reserve(helper.getLevel(), f.settlement, id, f.courier).accepted(),
            "reserve " + source);
        helper.assertTrue(RequestLedgerService.pickup(helper.getLevel(), f.settlement, id, f.courier).accepted(),
            "lift " + source);
        return id;
    }

    private static UUID open(GameTestHelper helper, Fixture f, BlockPos source, BlockPos target) {
        Building targetBuilding = target.equals(f.target2Pos) ? f.warehouse2 : f.warehouse;
        RequestLedgerService.Decision opened = RequestLedgerService.openOutputPickup(helper.getLevel(),
            f.settlement, f.camp, source, 0, targetBuilding, target, 2, RequestPriority.NORMAL);
        helper.assertTrue(opened.accepted() && opened.request() != null, "open " + source + ": " + opened);
        return opened.request().id();
    }

    private static void deliver(GameTestHelper helper, Fixture f, UUID id, BlockPos target) {
        BlockPos stand = f.courier.blockPosition();
        f.courier.teleportTo(target.getX() + 1.5D, target.getY(), target.getZ() + 0.5D);
        RequestLedgerService.Route route = RequestLedgerService.routeForCourier(helper.getLevel(), f.settlement, f.courier);
        helper.assertTrue(route.request() != null && route.request().id().equals(id), "route before delivery: " + route);
        RequestLedgerService.Decision done = RequestLedgerService.deliver(helper.getLevel(), f.settlement, id, f.courier);
        helper.assertTrue(done.outcome() == RequestLedgerService.Outcome.SATISFIED, "deliver " + id + ": " + done);
        f.courier.teleportTo(stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D);
    }

    private static void assertConserved(GameTestHelper helper, Fixture f) {
        int oak = count(f.sourceA, Items.OAK_LOG) + count(f.courier.bag, Items.OAK_LOG)
            + count(f.courier.batchStow, Items.OAK_LOG) + count(f.target, Items.OAK_LOG) + count(f.target2, Items.OAK_LOG);
        int birch = count(f.sourceB, Items.BIRCH_LOG) + count(f.courier.bag, Items.BIRCH_LOG)
            + count(f.courier.batchStow, Items.BIRCH_LOG) + count(f.target, Items.BIRCH_LOG) + count(f.target2, Items.BIRCH_LOG);
        helper.assertTrue(oak == 2 && birch == 2, "conservation: oak " + oak + ", birch " + birch);
        helper.assertTrue(f.courier.bag.isEmpty() && f.courier.batchStow.isEmpty(), "nothing left behind in the Courier");
    }

    private static void assertNotQuarantined(GameTestHelper helper, Fixture f) {
        RequestLedger ledger = RequestLedgerSavedData.get(helper.getLevel()).existing(f.settlement.id);
        helper.assertTrue(ledger != null && !ledger.quarantined(),
            "ledger healthy: " + (ledger == null ? "missing" : ledger.quarantineReason()));
    }

    private static int dropped(GameTestHelper helper, Item item) {
        int n = 0;
        AABB box = helper.getBounds().inflate(2.0D);
        for (ItemEntity e : helper.getLevel().getEntitiesOfClass(ItemEntity.class, box)) {
            if (e.getItem().is(item)) {
                n += e.getItem().getCount();
            }
        }
        return n;
    }

    private static int count(Container container, Item item) {
        int n = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) {
                n += stack.getCount();
            }
        }
        return n;
    }

    private static Fixture fixture(GameTestHelper helper) {
        return fixture(helper, true, Items.BIRCH_LOG);
    }

    private static Fixture fixture(GameTestHelper helper, boolean straps, Item partnerItem) {
        var data = SettlementManager.data(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(), "Batchford",
            helper.absolutePos(new BlockPos(7, 1, 7)));
        settlement.radius = 24;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        Building camp = GameTestFixtures.register(helper, settlement, BuildingType.LUMBER_CAMP, 2, 2);
        Building warehouse = GameTestFixtures.register(helper, settlement, BuildingType.WAREHOUSE, 9, 2);
        Building warehouse2 = GameTestFixtures.register(helper, settlement, BuildingType.WAREHOUSE, 9, 9);
        BlockPos aRel = new BlockPos(3, 1, 3);
        BlockPos bRel = new BlockPos(5, 1, 3);
        BlockPos tRel = new BlockPos(10, 1, 3);
        BlockPos t2Rel = new BlockPos(10, 1, 10);
        for (BlockPos rel : new BlockPos[]{aRel, bRel, tRel, t2Rel}) {
            helper.setBlock(rel, Blocks.CHEST);
        }
        Container sourceA = container(helper, helper.absolutePos(aRel));
        Container sourceB = container(helper, helper.absolutePos(bRel));
        Container target = container(helper, helper.absolutePos(tRel));
        Container target2 = container(helper, helper.absolutePos(t2Rel));
        sourceA.setItem(0, new ItemStack(Items.OAK_LOG, 2));
        sourceB.setItem(0, new ItemStack(partnerItem, 2));
        if (straps) {
            com.hearthstead.settlement.development.TechTreeTestGrants.grant(
                Development.of(helper.getLevel(), settlement), CourierBatching.NODE);
        }
        SettlerEntity courier = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(4, 1, 4));
        courier.setSettlerName("Batcher");
        courier.bindTo(settlement.id, settlement.center);
        settlement.putRecord(courier.getUUID(), "Batcher", Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement, warehouse, courier).ok(),
            "fixture Warehouse must hire the Courier");
        courier.setCarryCapacity(16);
        return new Fixture(settlement, camp, warehouse, warehouse2, courier,
            helper.absolutePos(aRel), helper.absolutePos(bRel), helper.absolutePos(tRel),
            helper.absolutePos(t2Rel), sourceA, sourceB, target, target2);
    }

    private static Container container(GameTestHelper helper, BlockPos absolute) {
        BlockEntity blockEntity = helper.getLevel().getBlockEntity(absolute);
        return blockEntity instanceof Container value ? value : null;
    }

    private record Pair(UUID a, UUID b) {
    }

    private record Fixture(Settlement settlement, Building camp, Building warehouse, Building warehouse2,
                           SettlerEntity courier, BlockPos sourceAPos, BlockPos sourceBPos,
                           BlockPos targetPos, BlockPos target2Pos, Container sourceA, Container sourceB,
                           Container target, Container target2) {
    }
}
