package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.RecruitmentTransaction;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.storage.ServerLevelData;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.UUID;

/**
 * SLICE RECRUIT-1 — the A2 recruiting chain: a tavern draws travelers in,
 * they wait as guests rather than joining on the spot, and joining costs the
 * settlement a real price paid out of the hearth's own stores.
 *
 * <p>PLAN_TAVERN_GATE.md (D-TAVERN-1/2) strapped that chain shut at the
 * front door: {@code SettlementManager.tickRecruitment}'s attractive-check
 * now requires a valid tavern before it grows {@code recruitProgress} or
 * spawns a traveler at all -- (d), (e) and (f) below are that gate's own
 * tests. Attraction, physical arrival and deliberate Hearth admission are
 * separate persisted steps; the tests below now exercise those real
 * transactions instead of writing the old scalar mirrors directly.
 *
 * <p>Each test calls {@link SettlementManager}'s recruitment methods directly
 * rather than waiting out real game-time (a guest's patience is measured in
 * game <em>days</em>, which no GameTest budget could ever tick through) —
 * the same shape {@code EmploymentGameTests} uses throughout: the manager
 * layer is deterministic and callable on its own, so the AI goal
 * ({@code TravelerJoinGoal}) only ever needs to be trusted to get a guest to
 * the right doorstep, not to also decide when they join.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class RecruitGameTests {

    /** Only isolated single-test batches may own this temporary world clock. */
    private static void useIsolatedEveningArrivalWindow(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long previousDayTime = level.getDayTime();
        boolean[] restored = {false};
        Runnable restore = () -> {
            if (restored[0]) return;
            restored[0] = true;
            level.setDayTime(previousDayTime);
        };
        // Register restoration BEFORE changing the shared clock, including
        // setup failures and reruns. No global gamerule or gameTime changes.
        helper.testInfo.addListener(new GameTestListener() {
            @Override
            public void testStructureLoaded(GameTestInfo info) {
            }

            @Override
            public void testPassed(GameTestInfo info, GameTestRunner runner) {
                restore.run();
            }

            @Override
            public void testFailed(GameTestInfo info, GameTestRunner runner) {
                restore.run();
            }

            @Override
            public void testAddedForRerun(GameTestInfo original,
                                          GameTestInfo rerun,
                                          GameTestRunner runner) {
                restore.run();
            }
        });
        // Begin at11000 on the same day. Existing200/600tick test limits
        // remain well before the real15000 departure cutoff.
        level.setDayTime(Math.floorDiv(previousDayTime, 24_000L) * 24_000L
            + SettlementManager.TAVERN_VISITOR_ARRIVAL_TIME);
    }
    private static void floor(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                // The empty16 template contains test-marker terrain above
                // its nominal floor in some generated arenas. Clear that
                // layer before constructing a physical Tavern so edge-spawn
                // selection and the plaque doorstep share the same feet
                // elevation instead of creating an invisible one-block drop.
                helper.setBlock(new BlockPos(x, 1, z), Blocks.AIR);
                helper.setBlock(new BlockPos(x, 2, z), Blocks.AIR);
            }
        }
        // Keep every accepted exterior route inside this owned test arena.
        // The separate long-route fixture is tracked at y+40, above this wall.
        for (int edge = 0; edge < size; edge++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(edge, y, 0), Blocks.STONE_BRICKS);
                helper.setBlock(new BlockPos(edge, y, size - 1), Blocks.STONE_BRICKS);
                helper.setBlock(new BlockPos(0, y, edge), Blocks.STONE_BRICKS);
                helper.setBlock(new BlockPos(size - 1, y, edge), Blocks.STONE_BRICKS);
            }
        }
    }

    /**
     * A settlement the entity layer can actually find, with a real hearth
     * block standing at its center — RECRUIT-1's price is paid out of that
     * block's own inventory, so (unlike {@code EmploymentGameTests}'
     * bookkeeping-only fixture) a physical {@code HearthBlockEntity} has to
     * exist for these tests to have anything to pay from.
     */
    private static Settlement settlement(GameTestHelper helper, BlockPos centerRel) {
        helper.setBlock(centerRel, ModBlocks.HEARTH.get());
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.values().removeIf(old -> helper.getBounds().contains(
            old.center.getX() + 0.5D, old.center.getY() + 0.5D,
            old.center.getZ() + 0.5D));
        Settlement s = new Settlement(UUID.randomUUID(), "Gjestgiveriet",
            helper.absolutePos(centerRel));
        // Small on purpose -- see EmploymentGameTests' settlement() for why:
        // GameTest arenas sit close together and a generous radius answers
        // for a neighbour's hearth.
        s.radius = 6;
        data.settlements.put(s.id, s);
        data.setDirty();
        if (helper.getLevel().getBlockEntity(helper.absolutePos(centerRel))
            instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(s.id);
        }
        return s;
    }

    private static Building building(GameTestHelper helper, Settlement s,
                                     BuildingType type, int x, int z) {
        // Delegates to the one place that places the plaque a building
        // needs to survive BuildingManager's sweep -- see GameTestFixtures
        // (KF-021 / FLAKE-2, 2026-08-26).
        Building building = GameTestFixtures.register(helper, s, type, x, z);
        if (type == BuildingType.TAVERN) {
            if (!(helper.getLevel().getBlockEntity(building.plaquePos)
                instanceof PlaqueBlockEntity plaque)) {
                throw new IllegalStateException("fixture Tavern plaque missing");
            }
            try {
                var field = PlaqueBlockEntity.class.getDeclaredField("buildingId");
                field.setAccessible(true);
                field.set(plaque, building.id);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("cannot bind fixture Tavern plaque",
                    failure);
            }
        }
        return building;
    }

    private static SettlerEntity settler(GameTestHelper helper, Settlement s,
                                         String name, int x, int z) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(x, 1, z));
        settler.setSettlerName(name);
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), name, Profession.NONE);
        return settler;
    }

    /** Drives the exact persisted attraction -> spawn -> Tavern-arrival path. */
    private static SettlerEntity waitingTraveler(GameTestHelper helper, Settlement s,
                                                 String name) {
        helper.assertTrue(SettlementManager.primeRecruitment(helper.getLevel(), s),
            "fixture must prime one eligible persisted recruitment transaction");
        SettlementManager.tickRecruitment(helper.getLevel(), s);
        UUID travelerId = s.recruitment.travelerId();
        Entity entity = travelerId == null ? null
            : helper.getLevel().getEntity(travelerId);
        helper.assertTrue(entity instanceof SettlerEntity,
            "primed recruitment must publish one physical traveler");
        SettlerEntity traveler = (SettlerEntity) entity;
        traveler.setSettlerName(name);
        BlockPos anchor = s.recruitment.tavernAnchor();
        traveler.moveTo(anchor.getX() + 0.5D, anchor.getY(),
            anchor.getZ() + 0.5D, 0.0F, 0.0F);
        SettlementManager.tickRecruitment(helper.getLevel(), s);
        helper.assertTrue(s.recruitment.status()
                == RecruitmentTransaction.Status.WAITING_ADMISSION,
            "only physical arrival at the locked Tavern may open admission");
        return traveler;
    }

    /** Builds one ordinary persisted candidate without using the admin prime path. */
    private static void naturalReady(GameTestHelper helper, Settlement settlement,
                                     Building tavern) {
        RecruitmentTransaction next = settlement.recruitment.beginQualification(
            UUID.randomUUID(), helper.getLevel().getGameTime(), tavern.id,
            tavern.plaquePos, tavern.anchor, helper.getLevel().dimension().location());
        for (int ticks = 0; next.status() != RecruitmentTransaction.Status.READY_TO_SPAWN
                && ticks < 512; ticks++) {
            next = next.advanceQualification();
        }
        helper.assertTrue(next.status() == RecruitmentTransaction.Status.READY_TO_SPAWN
                && next.survivalAuthored(),
            "fixture must create a natural, persisted READY_TO_SPAWN candidate");
        settlement.applyRecruitment(next);
    }

    /** Controlled payment stock derived from the real already-frozen guest quote. */
    private static void stockQuotedAdmission(GameTestHelper helper, Settlement settlement,
                                               HearthBlockEntity hearth, Item planks) {
        admissionBed(helper, settlement);
        var quote = settlement.recruitment.quote();
        helper.assertTrue(quote != null && !quote.legacyPending(),
            "the real spawned guest must own a frozen quote before stock is prepared");
        if (quote.version() == 2) {
            int reserve = com.hearthstead.settlement.RecruitmentPolicy.requiredReserve(settlement.population() + 1);
            int missingMeals = Math.max(0, reserve - countInHearth(hearth, Items.BREAD));
            if (missingMeals > 0) helper.assertTrue(hearth.insertGoods(new ItemStack(Items.BREAD, missingMeals)).isEmpty(), "seed missing reserve meals");
            int missingCoins = quote.coins() - countInHearth(hearth, com.hearthstead.registry.ModItems.GOLD_COIN.get());
            helper.assertTrue(missingCoins >= 0, "controlled fixture must not contain excess payment coins");
            if (missingCoins > 0) helper.assertTrue(hearth.insertGoods(new ItemStack(com.hearthstead.registry.ModItems.GOLD_COIN.get(), missingCoins)).isEmpty(), "stock actual frozen coins");
            helper.assertTrue(countInHearth(hearth, com.hearthstead.registry.ModItems.GOLD_COIN.get()) == quote.coins(), "exact v2 coins are funded; unrelated goods are retained");
            return;
        }
        int breadTarget = quote.bread()
            + com.hearthstead.settlement.RecruitmentPolicy.requiredReserve(settlement.population() + 1);
        int breadMissing = breadTarget - countInHearth(hearth, Items.BREAD);
        int planksMissing = quote.planks() - countInHearth(hearth, planks);
        // Fixture setup only: qualification uses the base price before the
        // guest exists; a frozen discount can leave excess seeded payment goods.
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            ItemStack existing = hearth.getInventory().getStackInSlot(slot);
            if (breadMissing < 0 && existing.is(Items.BREAD)) {
                breadMissing += hearth.getInventory().extractItem(slot, -breadMissing, false).getCount();
            } else if (planksMissing < 0 && existing.is(planks)) {
                planksMissing += hearth.getInventory().extractItem(slot, -planksMissing, false).getCount();
            }
        }
        if (breadMissing > 0) hearth.insertGoods(new ItemStack(Items.BREAD, breadMissing));
        if (planksMissing > 0) hearth.insertGoods(new ItemStack(planks, planksMissing));
        helper.assertTrue(countInHearth(hearth, Items.BREAD) == breadTarget
                && countInHearth(hearth, planks) == quote.planks(),
            "fixture must hold exactly the frozen price plus the unchanged meal reserve");
    }
    private static int countInHearth(HearthBlockEntity hearth, Item item) {
        int total = 0;
        for (int i = 0; i < hearth.getInventory().getSlots(); i++) {
            ItemStack stack = hearth.getInventory().getStackInSlot(i);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** A safe destination behind solid walls must not create a fallback guest. */
    @GameTest(batch = "recruit", template = "empty32", timeoutTicks = 40)
    public void anUnreachableTavernDoesNotSpawnAtTheHearth(GameTestHelper helper) {
        floor(helper, 32);
        ServerLevel level = helper.getLevel();
        BlockPos hearthRel = new BlockPos(6, 1, 6);
        BlockPos tavernRel = new BlockPos(10, 1, 10);
        Settlement s = settlement(helper, hearthRel);
        Building tavern = building(helper, s, BuildingType.TAVERN, 10, 10);
        tavern.anchor = tavern.plaquePos;
        BlockPos approach = tavern.plaquePos.north().below();
        level.setBlockAndUpdate(approach, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(approach.above(), Blocks.AIR.defaultBlockState());
        for (BlockPos wall : java.util.List.of(approach.north(), approach.west(),
                approach.east())) {
            level.setBlockAndUpdate(wall, Blocks.STONE_BRICKS.defaultBlockState());
            level.setBlockAndUpdate(wall.above(), Blocks.STONE_BRICKS.defaultBlockState());
            level.setBlockAndUpdate(wall.above(2), Blocks.STONE_BRICKS.defaultBlockState());
        }
        // Close below/above the south plaque without replacing that identity.
        level.setBlockAndUpdate(approach.south(), Blocks.STONE_BRICKS.defaultBlockState());
        level.setBlockAndUpdate(approach.south().above(2), Blocks.STONE_BRICKS.defaultBlockState());
        // Cap the cell so no jump/roof route can enter it.
        level.setBlockAndUpdate(approach.above(2), Blocks.STONE_BRICKS.defaultBlockState());
        HearthBlockEntity hearth = (HearthBlockEntity) level.getBlockEntity(s.center);
        hearth.insertGoods(new ItemStack(Items.BREAD, 12));
        hearth.insertGoods(new ItemStack(Items.OAK_PLANKS, 8));
        helper.assertTrue(SettlementManager.primeRecruitment(level, s),
            "valid stocked Tavern must prime before probing the blocked approach");
        RecruitmentTransaction ready = s.recruitment;
        for (int attempt = 0; attempt < 3; attempt++) {
            helper.assertTrue(SettlementManager.spawnSettler(level, s, true) == null,
                "unreachable arrival must fail closed, including immediate retries");
        }
        helper.assertTrue(s.recruitment == ready && s.recruitment.travelerId() == null,
            "a failed route must not commit a guest, transition or fallback identity");
        helper.succeed();
    }

    /** The production radius must work on simple open ground with the real node budget. */
    @GameTest(batch = "recruit", template = "empty64", timeoutTicks = 40)
    public void aDefaultRadiusSettlementFindsACompleteOutsideArrivalRoute(GameTestHelper helper) {
        floor(helper, 64);
        ServerLevel level = helper.getLevel();
        Settlement s = settlement(helper, new BlockPos(6, 1, 6));
        s.radius = 48;
        Building tavern = building(helper, s, BuildingType.TAVERN, 10, 10);
        tavern.anchor = tavern.plaquePos;
        HearthBlockEntity hearth = (HearthBlockEntity) level.getBlockEntity(s.center);
        hearth.insertGoods(new ItemStack(Items.BREAD, 12));
        hearth.insertGoods(new ItemStack(Items.OAK_PLANKS, 8));
        helper.assertTrue(SettlementManager.primeRecruitment(level, s),
            "default-radius settlement must prime an ordinary trip");
        SettlementManager.tickRecruitment(level, s);
        Entity entity = s.recruitment.travelerId() == null ? null
            : level.getEntity(s.recruitment.travelerId());
        helper.assertTrue(entity instanceof SettlerEntity,
            "default-radius open ground must admit a complete bounded path");
        SettlerEntity traveler = (SettlerEntity) entity;
        double dx = traveler.getX() - (s.center.getX() + 0.5D);
        double dz = traveler.getZ() - (s.center.getZ() + 0.5D);
        helper.assertTrue(dx * dx + dz * dz > 48.0D * 48.0D,
            "real traveler must originate beyond the default settlement boundary");
        BlockPos approach = SettlementManager.travelerTavernApproach(level, traveler);
        helper.assertTrue(approach != null && traveler.blockPosition().distSqr(approach) >= 144,
            "spawn must leave at least twelve blocks of real Tavern approach");
        helper.assertTrue(s.recruitment.status() == RecruitmentTransaction.Status.TRAVELING,
            "route publication must not pretend the traveler has arrived");
        traveler.discard();
        helper.succeed();
    }

    // ------------------------------------------------------------ (a) ---

    /**
     * The whole payable loop, end to end: a guest waiting at the tavern, a
     * hearth that can afford them, and exactly the price gone afterwards —
     * chest truth (INV-3), never a silent extra charge and never a discount.
     */
    @GameTest(batch = "recruit", template = "empty32", timeoutTicks = 200)
    public void aPayableGuestJoinsAndThePriceIsExact(GameTestHelper helper) {
        floor(helper, 32);
        ServerLevel level = helper.getLevel();
        BlockPos hearthRel = new BlockPos(6, 1, 6);
        BlockPos tavernRel = new BlockPos(10, 1, 10);
        Settlement s = settlement(helper, hearthRel);
        building(helper, s, BuildingType.TAVERN, tavernRel.getX(), tavernRel.getZ());

        HearthBlockEntity hearth = (HearthBlockEntity) level
            .getBlockEntity(helper.absolutePos(hearthRel));
        // Exact price plus the post-payment two-day reserve. The reserve
        // survives; only the four-bread price leaves.
        hearth.insertGoods(new ItemStack(Items.BREAD, 12));
        hearth.insertGoods(new ItemStack(Items.OAK_PLANKS, 8));
        hearth.insertGoods(new ItemStack(Items.IRON_INGOT, 5));

        // Spawned right at the tavern's anchor: this test is about payment,
        // not pathing -- TravelerJoinGoal (untested here) owns getting them
        // there for real.
        SettlerEntity guest = waitingTraveler(helper, s, "Gjest");
        stockQuotedAdmission(helper, s, hearth, Items.OAK_PLANKS);
        helper.assertTrue(guest.isTraveler(), "sanity: starts as a traveler, not a settler");

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        helper.assertTrue(SettlementManager.admitWaitingTraveler(player, s,
                guest.getUUID(), s.recruitment.revision())
                == SettlementManager.AdmissionResult.COMMITTED,
            "the exact waiting guest must join only through deliberate admission");

        helper.assertFalse(guest.isTraveler(),
            "a guest the settlement can pay for must join");
        helper.assertTrue(guest.isBound(), "...and become a bound settler");
        helper.assertTrue(s.record(guest.getUUID()) != null,
            "the settlement roster must gain them");
        helper.assertTrue(s.recruitment.status()
                == RecruitmentTransaction.Status.ADMITTED,
            "admission must persist its terminal receipt before the next cycle");

        helper.assertTrue(countInHearth(hearth, Items.BREAD) == 12,
            "coin payment retains all twelve seeded meals, found "
                + countInHearth(hearth, Items.BREAD));
        helper.assertTrue(countInHearth(hearth, Items.OAK_PLANKS) == 8,
            "coin payment retains all unrelated planks, found " + countInHearth(hearth, Items.OAK_PLANKS));
        helper.assertTrue(countInHearth(hearth, Items.IRON_INGOT) == 5,
            "only the price may be spent -- the unrelated good must be untouched, found "
                + countInHearth(hearth, Items.IRON_INGOT));
        helper.assertTrue(countInHearth(hearth, com.hearthstead.registry.ModItems.GOLD_COIN.get()) == 0
                && s.recruitment.admissionReceipt().removedItemCount() == s.recruitment.quote().coins(), "exact frozen coin payment");
        helper.succeed();
    }

    /**
     * A Tavern may legitimately use its wall-hung Plaque as its surveyed
     * anchor (for example, when it has no bed to supply an interior anchor).
     * That immutable anchor remains the admission identity, but is not a
     * valid mob destination. The traveler must resolve the plaque's actual
     * facing to its physical front doorstep and arrive there through the
     * ordinary production goal -- no test teleport or state shortcut.
     */
    @GameTest(batch = "recruit_wall_plaque_arrival_window", template = "empty32", timeoutTicks = 200)
    public void aTravelerUsesTheWallPlaqueDoorstepWithoutChangingAdmissionAnchor(
            GameTestHelper helper) {
        useIsolatedEveningArrivalWindow(helper);
        floor(helper, 32);
        ServerLevel level = helper.getLevel();
        BlockPos hearthRel = new BlockPos(6, 1, 6);
        BlockPos tavernRel = new BlockPos(10, 1, 10);
        Settlement s = settlement(helper, hearthRel);
        Building tavern = building(helper, s, BuildingType.TAVERN,
            tavernRel.getX(), tavernRel.getZ());
        // Deliberately reproduce the real no-bed Tavern survey case: the
        // persisted, identity-bearing anchor is the Plaque itself.
        tavern.anchor = tavern.plaquePos;
        // `empty16` reserves a small internal marker near this coordinate.
        // A real wall-plaque entrance needs its physical front cell clear,
        // so make that fixture fact explicit rather than silently testing a
        // blocked doorstep.
        helper.setBlock(tavernRel.north(), Blocks.AIR);
        helper.setBlock(tavernRel.north().above(), Blocks.AIR);

        HearthBlockEntity hearth = (HearthBlockEntity) level
            .getBlockEntity(helper.absolutePos(hearthRel));
        hearth.insertGoods(new ItemStack(Items.BREAD, 12));
        hearth.insertGoods(new ItemStack(Items.OAK_PLANKS, 8));

        helper.assertTrue(SettlementManager.primeRecruitment(level, s),
            "the valid Tavern must create one ordinary recruitment journey");
        SettlementManager.tickRecruitment(level, s);
        Entity entity = level.getEntity(s.recruitment.travelerId());
        helper.assertTrue(entity instanceof SettlerEntity,
            "the real recruitment transaction must spawn one traveler");
        SettlerEntity traveler = (SettlerEntity) entity;
        BlockPos immutableAnchor = s.recruitment.tavernAnchor();
        BlockPos expectedDoorstep = immutableAnchor.north().below();
        double originX = traveler.getX() - (s.center.getX() + 0.5D);
        double originZ = traveler.getZ() - (s.center.getZ() + 0.5D);
        helper.assertTrue(originX * originX + originZ * originZ > s.radius * s.radius,
            "ordinary recruitment must publish the traveler outside the settlement");
        helper.assertTrue(traveler.blockPosition().distSqr(expectedDoorstep) >= 144,
            "the guest must walk to the Tavern, not appear in its arrival radius");
        helper.assertTrue(immutableAnchor.equals(tavern.plaquePos),
            "the regression fixture must preserve the Plaque as admission identity");
        BlockPos resolvedDoorstep = SettlementManager.travelerTavernApproach(
            level, traveler);
        helper.assertTrue(expectedDoorstep.equals(resolvedDoorstep),
            "a north-facing wall Plaque must resolve to its standable front "
                + "doorstep; expected=" + expectedDoorstep + "; actual="
                + resolvedDoorstep + "; plaque="
                + level.getBlockState(immutableAnchor) + "; feet="
                + level.getBlockState(expectedDoorstep) + "; below="
                + level.getBlockState(expectedDoorstep.below()) + "; above="
                + level.getBlockState(expectedDoorstep.above()));

        // Test-local observer only: log transitions, never every movement tick.
        // Separate previous/current snapshots expose changes from the Hearth's
        // own tick as well as this test's explicit recruitment tick.
        List<String> observedTransitions = new ArrayList<>();
        String[] observedState = {""};
        int[] observationCount = {0};
        java.util.function.Consumer<String> observeTransition = phase -> {
            RecruitmentTransaction transaction = s.recruitment;
            String state = transaction.transactionId() + "/" + transaction.status()
                + "/" + transaction.terminalReason() + "/" + transaction.travelerId()
                + "/" + traveler.isAlive() + "/" + traveler.isRemoved()
                + "/" + s.lastTavernVisitorDay + "/" + s.tavernVisitorDepartureOrigin;
            if (state.equals(observedState[0])) return;
            String previous = observedState[0];
            observedState[0] = state;
            if (observationCount[0]++ >= 16) return;
            observedTransitions.add("phase=" + phase + " testTick=" + helper.getTick()
                + " gameTime=" + level.getGameTime() + " dayTime=" + level.getDayTime()
                + " settlement=" + s.id + " originalGuest=" + traveler.getUUID()
                + " previous=" + previous + " current=" + state
                + " spawnedTick=" + transaction.spawnedTick() + " arrivedTick=" + transaction.arrivedTick()
                + " position=" + traveler.position() + " approach=" + resolvedDoorstep
                + " departureOrigin=" + s.tavernVisitorDepartureOrigin
                + " lastVisitorDay=" + s.lastTavernVisitorDay
                + " navTarget=" + traveler.getNavigation().getTargetPos()
                + " navDone=" + traveler.getNavigation().isDone());
            Hearthstead.LOGGER.info(
                "HSQA_TAVERN_DOORSTEP_TRANSITION phase={} testTick={} gameTime={} dayTime={} settlement={} originalGuest={} previous={} current={} spawnedTick={} arrivedTick={} alive={} removed={} position={} approach={} departureOrigin={} lastVisitorDay={} navTarget={} navDone={}",
                phase, helper.getTick(), level.getGameTime(), level.getDayTime(),
                s.id, traveler.getUUID(), previous, state, transaction.spawnedTick(),
                transaction.arrivedTick(), traveler.isAlive(), traveler.isRemoved(),
                traveler.position(), resolvedDoorstep, s.tavernVisitorDepartureOrigin,
                s.lastTavernVisitorDay, traveler.getNavigation().getTargetPos(),
                traveler.getNavigation().isDone());
        };
        observeTransition.accept("initial");
        helper.onEachTick(() -> {
            observeTransition.accept("before_tickRecruitment");
            SettlementManager.tickRecruitment(level, s);
            observeTransition.accept("after_tickRecruitment");
        });
        helper.runAfterDelay(40, () -> {
            var path = traveler.getNavigation().createPath(resolvedDoorstep, 0);
            helper.assertTrue(traveler.getNavigation().getTargetPos() != null,
                "the ordinary TravelerJoinGoal must own navigation for a "
                    + "TRAVELING guest; status=" + s.recruitment.status()
                    + "; activity=" + traveler.getActivity() + "; pos="
                    + traveler.blockPosition() + "; approach=" + resolvedDoorstep
                    + "; navDone=" + traveler.getNavigation().isDone()
                    + "; directPath=" + (path == null ? "null" : "nodes="
                        + path.getNodeCount() + ";reachable=" + path.canReach()));
        });
        helper.runAfterDelay(160, () -> {
            helper.assertTrue(s.recruitment.status()
                    == RecruitmentTransaction.Status.WAITING_ADMISSION,
                "ordinary AI travel to the doorstep must unlock waiting admission; "
                    + "status=" + s.recruitment.status() + "; pos="
                    + traveler.blockPosition() + "; approach=" + resolvedDoorstep
                    + "; navTarget=" + traveler.getNavigation().getTargetPos()
                    + "; navDone=" + traveler.getNavigation().isDone()
                    + "; transitions=" + observedTransitions);
            helper.assertTrue(s.recruitment.tavernAnchor().equals(immutableAnchor),
                "resolving a doorstep must never mutate the locked Tavern anchor");
            helper.assertTrue(s.lastTavernVisitorDay >= 0,
                "published visitor must retain its actual visit day");
            long originalDayTime = level.getDayTime();
            try {
                level.setDayTime(s.lastTavernVisitorDay * 24_000L + 11_500L);
                helper.assertTrue(com.hearthstead.settlement.TavernSeating.mayVisit(traveler),
                    "the exact waiting visitor may sit at its valid Tavern without an Innkeeper; "
                        + "food and admission remain separately owned transactions");
            } finally {
                level.setDayTime(originalDayTime);
            }
            helper.assertTrue(traveler.blockPosition().distSqr(resolvedDoorstep) <= 9,
                "the real traveler must arrive at the derived physical doorstep; pos="
                    + traveler.position() + "; approach=" + resolvedDoorstep
                    + "; activity=" + traveler.getActivity()
                    + "; navTarget=" + traveler.getNavigation().getTargetPos());
            helper.succeed();
        });
    }

    /** Natural visitors publish once at evening and retain the daily lock across a restart. */
    @GameTest(batch = "recruit", template = "empty32", timeoutTicks = 120)
    public void ordinaryTavernVisitorPublishesOncePerEveningAndPersistsTheDayLock(
            GameTestHelper helper) {
        floor(helper, 32);
        ServerLevel level = helper.getLevel();
        BlockPos hearthRel = new BlockPos(6, 1, 6);
        Settlement settlement = settlement(helper, hearthRel);
        Building tavern = building(helper, settlement, BuildingType.TAVERN, 10, 10);
        HearthBlockEntity hearth = (HearthBlockEntity) level
            .getBlockEntity(helper.absolutePos(hearthRel));
        hearth.insertGoods(new ItemStack(Items.BREAD, 12));
        naturalReady(helper, settlement, tavern);

        long originalDayTime = level.getDayTime();
        try {
            long day = 7L;
            level.setDayTime(day * 24_000L + SettlementManager.TAVERN_VISITOR_ARRIVAL_TIME - 1L);
            SettlementManager.tickRecruitment(level, settlement);
            helper.assertTrue(settlement.recruitment.status()
                    == RecruitmentTransaction.Status.READY_TO_SPAWN,
                "a natural candidate must wait for the daily evening batch");

            level.setDayTime(day * 24_000L + SettlementManager.TAVERN_VISITOR_ARRIVAL_TIME);
            SettlementManager.tickRecruitment(level, settlement);
            helper.assertTrue(settlement.recruitment.status()
                    == RecruitmentTransaction.Status.TRAVELING
                    && settlement.recruitment.travelerId() != null,
                "the evening batch must publish one physically routed traveler");
            helper.assertTrue(settlement.lastTavernVisitorDay == day,
                "only a published visitor owns the daily lock");
            BlockPos origin = settlement.tavernVisitorDepartureOrigin;
            helper.assertTrue(origin != null && origin.distSqr(settlement.center)
                    > (double) settlement.radius * settlement.radius,
                "the published visitor must retain the real exterior feet cell for its night return");

            Settlement restarted = Settlement.readNbt(settlement.writeNbt());
            helper.assertTrue(restarted.lastTavernVisitorDay == day
                    && origin.equals(restarted.tavernVisitorDepartureOrigin)
                    && !SettlementManager.tavernVisitorArrivalDue(level, restarted),
                "the saved day lock and exterior route must prevent a duplicate batch after restart");
        } finally {
            level.setDayTime(originalDayTime);
        }
        helper.succeed();
    }

    /**
     * The settlement edge is wider than a settler's 32-block FOLLOW_RANGE.
     * A legal distant guest therefore needs ordinary bounded navigation legs;
     * complete or partial, every installed physical prefix must close on the
     * same locked doorstep and the sequence must actually arrive.
     */
    @GameTest(batch = "recruit_long_route_arrival_window", template = "empty32", timeoutTicks = 600)
    public void aTravelerBeyondFollowRangeUsesBoundedLegsToTheExactDoorstep(
            GameTestHelper helper) {
        useIsolatedEveningArrivalWindow(helper);
        ServerLevel level = helper.getLevel();
        BlockPos hearthRel = new BlockPos(6, 41, 6);
        BlockPos tavernRel = new BlockPos(10, 41, 10);
        BlockPos approach = helper.absolutePos(tavernRel.north());
        BlockPos start = approach.north(36);
        BlockPos hearthAbsolute = helper.absolutePos(hearthRel);
        BlockPos plaqueAbsolute = helper.absolutePos(tavernRel.above());
        BlockPos plaqueSupportAbsolute = plaqueAbsolute.south();
        BlockPos door = start.south(18);

        Set<BlockPos> controlledPositions = new LinkedHashSet<>();
        for (int z = start.getZ(); z <= approach.getZ(); z++) {
            for (int x = approach.getX() - 1; x <= approach.getX() + 1; x++) {
                BlockPos feet = new BlockPos(x, approach.getY(), z);
                controlledPositions.add(feet.below());
                controlledPositions.add(feet);
                controlledPositions.add(feet.above());
            }
        }
        controlledPositions.add(hearthAbsolute);
        controlledPositions.add(plaqueAbsolute);
        controlledPositions.add(plaqueSupportAbsolute);

        Set<Long> inheritedTickets = new LinkedHashSet<>();
        Set<Long> ownedTickets = new LinkedHashSet<>();
        Map<BlockPos, BlockState> priorStates = new LinkedHashMap<>();
        Settlement[] settlementRef = {null};
        SettlerEntity[] travelerRef = {null};
        HearthBlockEntity[] hearthRef = {null};
        boolean[] cleaned = {false};

        Consumer<BlockPos> rememberRelative = relative -> {
            BlockPos absolute = helper.absolutePos(relative).immutable();
            priorStates.putIfAbsent(absolute, level.getBlockState(absolute));
        };
        BiConsumer<BlockPos, BlockState> setTracked = (absolute, state) -> {
            BlockPos immutable = absolute.immutable();
            priorStates.putIfAbsent(immutable, level.getBlockState(immutable));
            level.setBlockAndUpdate(immutable, state);
        };
        Runnable cleanup = () -> {
            if (cleaned[0]) {
                return;
            }
            cleaned[0] = true;

            SettlerEntity spawned = travelerRef[0];
            Settlement created = settlementRef[0];
            if (spawned == null && created != null
                    && created.recruitment.travelerId() != null
                    && level.getEntity(created.recruitment.travelerId())
                        instanceof SettlerEntity exactTraveler) {
                spawned = exactTraveler;
            }
            if (spawned != null) {
                spawned.discard();
            }

            SettlementSavedData data = SettlementSavedData.get(level);
            if (created != null) {
                data.settlements.remove(created.id);
                data.setDirty();
            }

            HearthBlockEntity createdHearth = hearthRef[0];
            if (created != null && createdHearth != null
                    && level.getBlockEntity(hearthAbsolute) == createdHearth
                    && created.id.equals(createdHearth.getSettlementId())) {
                for (int slot = 0;
                        slot < createdHearth.getInventory().getSlots(); slot++) {
                    createdHearth.getInventory().setStackInSlot(slot,
                        ItemStack.EMPTY);
                }
            }
            List<BlockPos> restore = new ArrayList<>(priorStates.keySet());
            for (int i = restore.size() - 1; i >= 0; i--) {
                BlockPos pos = restore.get(i);
                level.setBlockAndUpdate(pos, priorStates.get(pos));
            }
            for (long key : ownedTickets) {
                ChunkPos chunk = new ChunkPos(key);
                level.setChunkForced(chunk.x, chunk.z, false);
            }
        };

        // Register cleanup before the first chunk acquisition or world
        // mutation. Setup exceptions, timeout/failure, pass and rerun all pass
        // through one idempotent restoration path.
        helper.testInfo.addListener(new GameTestListener() {
            @Override
            public void testStructureLoaded(GameTestInfo info) {
            }

            @Override
            public void testPassed(GameTestInfo info, GameTestRunner runner) {
                cleanup.run();
            }

            @Override
            public void testFailed(GameTestInfo info, GameTestRunner runner) {
                cleanup.run();
            }

            @Override
            public void testAddedForRerun(GameTestInfo original,
                                          GameTestInfo rerun,
                                          GameTestRunner runner) {
                cleanup.run();
            }
        });

        int minBlockX = Math.min(start.getX() - 1,
            Math.min(hearthAbsolute.getX(), plaqueSupportAbsolute.getX()));
        int maxBlockX = Math.max(approach.getX() + 1,
            Math.max(hearthAbsolute.getX(), plaqueSupportAbsolute.getX()));
        int minBlockZ = Math.min(start.getZ(),
            Math.min(hearthAbsolute.getZ(), plaqueSupportAbsolute.getZ()));
        int maxBlockZ = Math.max(approach.getZ(),
            Math.max(hearthAbsolute.getZ(), plaqueSupportAbsolute.getZ()));
        for (int chunkX = minBlockX >> 4; chunkX <= maxBlockX >> 4; chunkX++) {
            for (int chunkZ = minBlockZ >> 4; chunkZ <= maxBlockZ >> 4; chunkZ++) {
                long key = ChunkPos.asLong(chunkX, chunkZ);
                if (level.getForcedChunks().contains(key)) {
                    inheritedTickets.add(key);
                } else {
                    level.setChunkForced(chunkX, chunkZ, true);
                    ownedTickets.add(key);
                }
                level.getChunk(chunkX, chunkZ);
            }
        }

        // This test owns no pre-existing state above the template. Reject an
        // occupied cell before the first block mutation rather than restoring
        // only its BlockState and silently erasing foreign block-entity NBT.
        for (BlockPos pos : controlledPositions) {
            helper.assertTrue(level.getBlockState(pos).isAir(),
                "isolated long-route fixture requires an empty cell: " + pos);
            helper.assertTrue(level.getBlockEntity(pos) == null,
                "isolated long-route fixture cannot replace a block entity: " + pos);
        }

        floor(helper, 32);

        // This route extends beyond empty16, so every out-of-bounds block is
        // tracked and kept at the established y+40 isolation height.
        for (int z = start.getZ(); z <= approach.getZ(); z++) {
            for (int x = approach.getX() - 1; x <= approach.getX() + 1; x++) {
                BlockPos feet = new BlockPos(x, approach.getY(), z);
                setTracked.accept(feet.below(),
                    Blocks.STONE_BRICKS.defaultBlockState());
                setTracked.accept(feet, Blocks.AIR.defaultBlockState());
                setTracked.accept(feet.above(), Blocks.AIR.defaultBlockState());
            }
        }
        // The exact stoop is physically standable before recruitment or the
        // first travelerTavernApproach resolution can observe it.
        helper.assertTrue(level.getBlockState(approach.below())
                .is(Blocks.STONE_BRICKS),
            "isolated Tavern stoop must have its physical landing first");

        for (int side : new int[] {-1, 1}) {
            BlockPos wall = door.offset(side, 0, 0);
            setTracked.accept(wall, Blocks.STONE_BRICKS.defaultBlockState());
            setTracked.accept(wall.above(), Blocks.STONE_BRICKS.defaultBlockState());
        }
        var lowerDoor = Blocks.OAK_DOOR.defaultBlockState()
            .setValue(DoorBlock.FACING, net.minecraft.core.Direction.NORTH)
            .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT)
            .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
            .setValue(DoorBlock.OPEN, false);
        setTracked.accept(door, lowerDoor);
        setTracked.accept(door.above(), lowerDoor
            .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));

        rememberRelative.accept(hearthRel);
        Settlement s = settlement(helper, hearthRel);
        settlementRef[0] = s;
        rememberRelative.accept(tavernRel.above().south());
        rememberRelative.accept(tavernRel.above());
        Building tavern = GameTestFixtures.registerWithBounds(helper, s,
            BuildingType.TAVERN, tavernRel, tavernRel.above(),
            BoundingBox.fromCorners(helper.absolutePos(tavernRel),
                helper.absolutePos(tavernRel).offset(3, 2, 3)));
        if (!(level.getBlockEntity(tavern.plaquePos)
                instanceof PlaqueBlockEntity plaque)) {
            throw new IllegalStateException("isolated fixture Tavern plaque missing");
        }
        try {
            var field = PlaqueBlockEntity.class.getDeclaredField("buildingId");
            field.setAccessible(true);
            field.set(plaque, tavern.id);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("cannot bind isolated fixture Tavern plaque",
                failure);
        }
        tavern.anchor = tavern.plaquePos;

        HearthBlockEntity hearth = (HearthBlockEntity) level
            .getBlockEntity(hearthAbsolute);
        hearthRef[0] = hearth;
        hearth.insertGoods(new ItemStack(Items.BREAD, 12));
        hearth.insertGoods(new ItemStack(Items.OAK_PLANKS, 8));
        helper.assertTrue(SettlementManager.primeRecruitment(level, s),
            "the valid Tavern must prime a physical long-range traveler");
        SettlementManager.tickRecruitment(level, s);
        Entity entity = level.getEntity(s.recruitment.travelerId());
        helper.assertTrue(entity instanceof SettlerEntity,
            "the real recruitment transaction must spawn one traveler");
        SettlerEntity traveler = (SettlerEntity) entity;
        travelerRef[0] = traveler;
        BlockPos immutableAnchor = s.recruitment.tavernAnchor();
        BlockPos resolvedApproach = SettlementManager.travelerTavernApproach(
            level, traveler);
        helper.assertTrue(approach.equals(resolvedApproach),
            "the locked wall Plaque must resolve the prepared exact doorstep; "
                + "expected=" + approach + "; actual=" + resolvedApproach);

        traveler.moveTo(start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
            0.0F, 0.0F);
        traveler.getNavigation().stop();
        double initialDistance = traveler.position().distanceToSqr(
            net.minecraft.world.phys.Vec3.atBottomCenterOf(approach));
        helper.assertTrue(initialDistance > 32.0D * 32.0D,
            "fixture must begin beyond the real 32-block FOLLOW_RANGE");

        BlockPos[] firstClosingEnd = {null};
        BlockPos[] firstClosingRequest = {null};
        helper.onEachTick(() -> {
            SettlementManager.tickRecruitment(level, s);
            Path path = traveler.getNavigation().getPath();
            if (firstClosingEnd[0] == null && path != null
                    && path.getEndNode() != null
                    && !path.getTarget().equals(approach)) {
                BlockPos end = path.getEndNode().asBlockPos();
                double endToApproach = net.minecraft.world.phys.Vec3
                    .atBottomCenterOf(end).distanceToSqr(
                        net.minecraft.world.phys.Vec3.atBottomCenterOf(approach));
                double startToEnd = net.minecraft.world.phys.Vec3
                    .atBottomCenterOf(end).distanceToSqr(
                        net.minecraft.world.phys.Vec3.atBottomCenterOf(start));
                if (endToApproach < initialDistance
                        && startToEnd <= 32.0D * 32.0D) {
                    firstClosingEnd[0] = end.immutable();
                    firstClosingRequest[0] = path.getTarget().immutable();
                }
            }
        });
        helper.runAfterDelay(30, () -> helper.assertTrue(
            firstClosingEnd[0] != null,
            "ordinary AI must install a bounded physical path whose actual end "
                + "node closes on the exact doorstep; start=" + start
                + "; approach=" + approach + "; requested="
                + firstClosingRequest[0] + "; end=" + firstClosingEnd[0]
                + "; path=" + traveler.getNavigation().getPath()
                + "; navDone=" + traveler.getNavigation().isDone()));
        helper.runAfterDelay(500, () -> {
            helper.assertTrue(s.recruitment.status()
                    == RecruitmentTransaction.Status.WAITING_ADMISSION
                    && s.recruitment.tavernAnchor().equals(immutableAnchor)
                    && traveler.blockPosition().distSqr(approach) <= 9.0D,
                "bounded vanilla legs must reach the unchanged locked Tavern; status="
                    + s.recruitment.status() + "; anchor="
                    + s.recruitment.tavernAnchor() + "; expectedAnchor="
                    + immutableAnchor + "; position=" + traveler.blockPosition()
                    + "; approach=" + approach);
            helper.assertTrue(level.getBlockState(door).is(Blocks.OAK_DOOR),
                "physical route must retain its ordinary visitor door");
            for (long key : inheritedTickets) {
                helper.assertTrue(level.getForcedChunks().contains(key),
                    "test must preserve every inherited chunk ticket: "
                        + new ChunkPos(key));
            }
            cleanup.run();
            helper.succeed();
        });
    }
    @GameTest(batch = "recruit", template = "empty32", timeoutTicks = 100)
    public void aDoorstepEdgeArrivalUsesTheSameAdmissionRadius(GameTestHelper helper) {
        floor(helper, 32);
        ServerLevel level = helper.getLevel();
        BlockPos hearthRel = new BlockPos(6, 1, 6);
        Settlement s = settlement(helper, hearthRel);
        Building tavern = building(helper, s, BuildingType.TAVERN, 10, 10);
        tavern.anchor = tavern.plaquePos;
        BlockPos approach = tavern.anchor.north().below();
        level.setBlockAndUpdate(approach, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(approach.above(), Blocks.AIR.defaultBlockState());
        HearthBlockEntity hearth = (HearthBlockEntity) level.getBlockEntity(
            helper.absolutePos(hearthRel));
        hearth.insertGoods(new ItemStack(Items.BREAD, 12));
        hearth.insertGoods(new ItemStack(Items.OAK_PLANKS, 8));
        helper.assertTrue(SettlementManager.primeRecruitment(level, s), "prime lawful visit");
        SettlementManager.tickRecruitment(level, s);
        SettlerEntity guest = (SettlerEntity) level.getEntity(s.recruitment.travelerId());
        stockQuotedAdmission(helper, s, hearth, Items.OAK_PLANKS);
        int breadBefore = countInHearth(hearth, Items.BREAD);
        int planksBefore = countInHearth(hearth, Items.OAK_PLANKS);
        BlockPos edge = approach.north(3);
        // This focused authority test positions the entity at the geometric boundary;
        // the separate ordinary-goal test proves actual walking to this destination.
        guest.moveTo(edge.getX() + 0.5D, edge.getY(), edge.getZ() + 0.5D, 0, 0);
        helper.assertTrue(guest.blockPosition().distSqr(tavern.anchor) > 9
                && guest.blockPosition().distSqr(approach) == 9,
            "fixture must distinguish plaque radius from physical doorstep radius");
        SettlementManager.tickRecruitment(level, s);
        helper.assertTrue(s.recruitment.status() == RecruitmentTransaction.Status.WAITING_ADMISSION,
            "doorstep edge must be a lawful physical arrival");
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        // One cell outside BOTH radii still fails closed and spends nothing.
        guest.moveTo(edge.getX() + 0.5D, edge.getY(), edge.getZ() - 0.5D, 0, 0);
        helper.assertFalse(SettlementManager.candidateMayAdmit(level, s),
            "Hearth admission button must reject the outside position");
        helper.assertTrue(SettlementManager.admitWaitingTraveler(player, s,
                guest.getUUID(), s.recruitment.revision()) == SettlementManager.AdmissionResult.INVALID_TRAVELER
                && countInHearth(hearth, Items.OAK_PLANKS) == planksBefore
                && countInHearth(hearth, Items.BREAD) == breadBefore,
            "a guest outside both radii cannot pay or join");
        guest.moveTo(edge.getX() + 0.5D, edge.getY(), edge.getZ() + 0.5D, 0, 0);
        helper.assertTrue(SettlementManager.candidateMayAdmit(level, s),
            "Hearth admission button must accept the same lawful doorstep edge");
        helper.assertTrue(SettlementManager.admitWaitingTraveler(player, s,
                guest.getUUID(), s.recruitment.revision()) == SettlementManager.AdmissionResult.COMMITTED,
            "the same lawful arrival must admit without changing its locked anchor");
        helper.assertTrue(s.recruitment.tavernAnchor().equals(tavern.anchor)
                && s.record(guest.getUUID()) != null && !guest.isTraveler()
                && countInHearth(hearth, Items.BREAD) == breadBefore
                && countInHearth(hearth, Items.OAK_PLANKS) == planksBefore,
            "doorstep admission must preserve identity and charge exactly once");
        helper.assertTrue(countInHearth(hearth, com.hearthstead.registry.ModItems.GOLD_COIN.get()) == 0
                && s.recruitment.admissionReceipt().removedItemCount() == s.recruitment.quote().coins(), "exact coin amount and no duplicate payment");
        helper.succeed();
    }

    // ------------------------------------------------------------ (b) ---

    /**
     * Expired patience ends the invitation through the same physical return as
     * nightfall. A meal already paid into the visitor remains durable until it
     * is consumed, and the manager can only terminally remove the guest after
     * the ordinary departure goal has reached its saved exterior destination.
     */
    @GameTest(batch = "recruit", template = "empty32", timeoutTicks = 200)
    public void anUnpayableGuestWalksAwayInsteadOfJoining(GameTestHelper helper) {
        floor(helper, 32);
        ServerLevel level = helper.getLevel();
        BlockPos hearthRel = new BlockPos(8, 1, 8);
        BlockPos tavernRel = new BlockPos(10, 1, 10);
        Settlement s = settlement(helper, hearthRel);
        Building tavern = building(helper, s, BuildingType.TAVERN, tavernRel.getX(), tavernRel.getZ());
        HearthBlockEntity hearth = (HearthBlockEntity) level.getBlockEntity(helper.absolutePos(hearthRel));
        naturalReady(helper, s, tavern);

        long originalDayTime = level.getDayTime();
        long originalGameTime = level.getGameTime();
        ServerLevelData clock = (ServerLevelData) level.getLevelData();
        try {
            level.setDayTime(7L * 24_000L + SettlementManager.TAVERN_VISITOR_ARRIVAL_TIME);
            SettlementManager.tickRecruitment(level, s);
            Entity entity = s.recruitment.travelerId() == null ? null
                : level.getEntity(s.recruitment.travelerId());
            helper.assertTrue(entity instanceof SettlerEntity,
                "the real evening batch must publish the visitor whose saved exit is tested");
            SettlerEntity guest = (SettlerEntity) entity;
            guest.moveTo(s.recruitment.tavernAnchor().getX() + .5D,
                s.recruitment.tavernAnchor().getY(), s.recruitment.tavernAnchor().getZ() + .5D, 0, 0);
            SettlementManager.tickRecruitment(level, s);
            BlockPos exit = s.tavernVisitorDepartureOrigin;
            helper.assertTrue(s.recruitment.status() == RecruitmentTransaction.Status.WAITING_ADMISSION
                    && exit != null,
                "a real published visitor must retain its exact exterior return before patience can expire");
            stockQuotedAdmission(helper, s, hearth, Items.OAK_PLANKS);
            for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
                ItemStack stack = hearth.getInventory().getStackInSlot(slot);
                if (stack.is(com.hearthstead.registry.ModItems.GOLD_COIN.get())) {
                    hearth.getInventory().setStackInSlot(slot, ItemStack.EMPTY);
                }
            }
            ServerPlayer player = helper.makeMockServerPlayerInLevel();
            helper.assertTrue(SettlementManager.admitWaitingTraveler(player, s,
                    guest.getUUID(), s.recruitment.revision())
                    == SettlementManager.AdmissionResult.BLOCKED_POLICY
                    && s.record(guest.getUUID()) == null,
                "an unpayable guest must remain a visitor before the separate meal/exit path");

            guest.bag.setItem(0, new ItemStack(Items.BREAD));
            helper.assertTrue(guest.beginMeal(guest.bag.getItem(0)) && guest.bag.isEmpty(),
                "one paid physical bread must become the visitor's durable meal");
            guest.setActivity(SettlerActivity.EATING);
            long expired = SettlementManager.candidatePatienceUntil(level, s) + 1L;
            clock.setGameTime(expired);
            SettlementManager.tickRecruitment(level, s);
            helper.assertTrue(guest.isAlive() && guest.hasMeal()
                    && s.recruitment.status() == RecruitmentTransaction.Status.WAITING_ADMISSION,
                "expired patience must retain the paid meal and never discard the guest at the Tavern");

            for (int tick = 1; tick <= 40; tick++) {
                clock.setGameTime(expired + tick);
                guest.tickMeal();
            }
            helper.assertTrue(!guest.hasMeal(), "the exact paid meal must finish before departure can begin");
            SettlementManager.tickRecruitment(level, s);
            helper.assertTrue(guest.isAlive() && s.recruitment.status() == RecruitmentTransaction.Status.DEPARTING
                    && exit.equals(SettlementManager.travelerRouteDestination(level, guest)),
                "expiry must hand off one real guest to its persisted exterior route, without rerolling identity");

            guest.moveTo(exit.getX() + .5D, exit.getY(), exit.getZ() + .5D, 0, 0);
            SettlementManager.tickRecruitment(level, s);
            helper.assertTrue(!guest.isAlive() && s.recruitment.status() == RecruitmentTransaction.Status.LEFT,
                "only the physical exterior arrival may complete the expired visitor's departure");
        } finally {
            level.setDayTime(originalDayTime);
            clock.setGameTime(originalGameTime);
        }
        helper.succeed();
    }
    // ------------------------------------------------------------ (c) ---

    /**
     * PLAN_TAVERN_GATE.md's gate (D-TAVERN-1) turned this test's own "bare"
     * fixture into a demonstration of the gate itself: a Tavern-less
     * settlement never starts qualification. A valid Tavern opens one exact
     * persisted clock. Hiring an innkeeper may alter the eventual price, but
     * must not reroll or accelerate that clock.
     */
    @GameTest(batch = "recruit", template = "empty32", timeoutTicks = 200)
    public void anInnkeeperDiscountDoesNotCompressTheRecruitClock(GameTestHelper helper) {
        floor(helper, 32);
        ServerLevel level = helper.getLevel();
        BlockPos hearthRel = new BlockPos(6, 1, 6);
        Settlement s = settlement(helper, hearthRel);
        Building tavern = building(helper, s, BuildingType.TAVERN, 10, 10);
        HearthBlockEntity hearth = (HearthBlockEntity) level
            .getBlockEntity(helper.absolutePos(hearthRel));
        hearth.insertGoods(new ItemStack(Items.BREAD, 48));
        hearth.insertGoods(new ItemStack(Items.OAK_PLANKS, 48));
        s.moraleCache = 80;

        // beginQualification both locks the exact Tavern and counts this
        // first eligible second atomically.
        SettlementManager.tickRecruitment(level, s);
        helper.assertTrue(s.recruitProgress == 1 && s.recruitQualifiedSeconds == 1,
            "an eligible settlement must gain exactly one qualified second");
        int lockedTarget = s.recruitTarget;

        SettlerEntity keeper = settler(helper, s, "Kroverten", 8, 10);
        helper.assertTrue(Employment.hire(level, s, tavern, keeper).ok(),
            "a tavern must be able to take an innkeeper");
        helper.assertTrue(keeper.getProfession() == Profession.INNKEEPER,
            "hired into a tavern, they keep it");
        SettlementManager.tickRecruitment(level, s);
        helper.assertTrue(s.recruitProgress == 2 && s.recruitQualifiedSeconds == 2,
            "an innkeeper may discount goods but must not turn one second into "
                + "multiple clock seconds");
        helper.assertTrue(s.recruitTarget == lockedTarget,
            "a price discount must not reroll or compress the locked recruit clock");
        helper.succeed();
    }

    // ------------------------------------------------------------ (d) ---

    /**
     * PLAN_TAVERN_GATE.md D-TAVERN-1, byggherre-krav 3/6: the gate reads
     * building-level validity in {@code tickRecruitment}'s own
     * attractive-check, so a Tavern-less settlement that is otherwise fully
     * attractive (fed, morale high, room free) must never create a persisted
     * qualification lock or advance either authoritative clock.
     */
    @GameTest(batch = "recruit", template = "empty32", timeoutTicks = 200)
    public void noTavernMeansTheGaugeNeverFills(GameTestHelper helper) {
        floor(helper, 32);
        ServerLevel level = helper.getLevel();
        BlockPos hearthRel = new BlockPos(6, 1, 6);
        Settlement s = settlement(helper, hearthRel);
        // Attractive on every OTHER axis -- physically able to pay while
        // retaining the exact eight-meal reserve, high morale, and room --
        // so a missing Tavern is the only blocker in scope.
        HearthBlockEntity hearth = (HearthBlockEntity) level
            .getBlockEntity(helper.absolutePos(hearthRel));
        hearth.insertGoods(new ItemStack(Items.BREAD, 12));
        hearth.insertGoods(new ItemStack(Items.OAK_PLANKS, 8));
        s.moraleCache = 80;
        SettlementManager.tickRecruitment(level, s);

        helper.assertTrue(s.recruitment.status()
                == RecruitmentTransaction.Status.ATTRACTING
                && s.recruitProgress == 0 && s.recruitQualifiedSeconds == 0,
            "without a Tavern the authoritative transaction must never begin qualification");
        helper.assertTrue(s.travelerId == null,
            "without a Tavern the settlement must never spawn a traveler");
        helper.succeed();
    }

    // ------------------------------------------------------------ (e) ---

    /**
     * The other half of (d): the same idle settlement begins one exact
     * qualification transaction the instant a valid, physically linked
     * Tavern exists. The first eligible tick locks its identity and counts
     * second one; the next tick advances both clocks to exactly two.
     */
    @GameTest(batch = "recruit", template = "empty32", timeoutTicks = 200)
    public void aValidTavernReopensTheGate(GameTestHelper helper) {
        floor(helper, 32);
        ServerLevel level = helper.getLevel();
        BlockPos hearthRel = new BlockPos(6, 1, 6);
        BlockPos tavernRel = new BlockPos(10, 1, 10);
        Settlement s = settlement(helper, hearthRel);
        s.moraleCache = 80;
        HearthBlockEntity hearth = (HearthBlockEntity) level
            .getBlockEntity(helper.absolutePos(hearthRel));
        hearth.insertGoods(new ItemStack(Items.BREAD, 12));
        hearth.insertGoods(new ItemStack(Items.OAK_PLANKS, 8));

        // Gate shut: no tavern yet, the canonical transaction stays idle.
        SettlementManager.tickRecruitment(level, s);
        helper.assertTrue(s.recruitment.status()
                == RecruitmentTransaction.Status.ATTRACTING
                && s.recruitProgress == 0,
            "sanity: without a Tavern qualification must remain closed");

        // The gate opens the moment a valid tavern exists -- same
        // settlement, same tick loop, nothing else changed.
        building(helper, s, BuildingType.TAVERN, tavernRel.getX(), tavernRel.getZ());
        SettlementManager.tickRecruitment(level, s);
        helper.assertTrue(s.recruitment.status()
                == RecruitmentTransaction.Status.QUALIFYING
                && s.recruitProgress == 1 && s.recruitQualifiedSeconds == 1,
            "the first eligible tick must persist the exact Tavern and one second");
        SettlementManager.tickRecruitment(level, s);

        helper.assertTrue(s.recruitProgress == 2 && s.recruitQualifiedSeconds == 2,
            "a valid Tavern must reopen the gate at exactly one second per tick");
        helper.succeed();
    }

    // ------------------------------------------------------------ (f) ---

    /**
     * PLAN_TAVERN_GATE.md's grandfather clause (D-TAVERN-2): a guest who
     * already reached the exact Tavern survives temporary invalidation in
     * WAITING_ADMISSION, without automatic payment or a Hearth fallback.
     * Admission remains fail-closed until that same locked Tavern becomes
     * valid again, then the deliberate action pays exactly once.
     */
    @GameTest(batch = "recruit", template = "empty32", timeoutTicks = 200)
    public void aWaitingGuestSurvivesTavernInvalidation(GameTestHelper helper) {
        floor(helper, 32);
        ServerLevel level = helper.getLevel();
        BlockPos hearthRel = new BlockPos(6, 1, 6);
        BlockPos tavernRel = new BlockPos(10, 1, 10);
        Settlement s = settlement(helper, hearthRel);
        Building tavern = building(helper, s, BuildingType.TAVERN,
            tavernRel.getX(), tavernRel.getZ());

        HearthBlockEntity hearth = (HearthBlockEntity) level
            .getBlockEntity(helper.absolutePos(hearthRel));
        hearth.insertGoods(new ItemStack(Items.BREAD, 12));
        hearth.insertGoods(new ItemStack(Items.OAK_PLANKS, 8));

        SettlerEntity guest = waitingTraveler(helper, s, "Etterlatt");
        stockQuotedAdmission(helper, s, hearth, Items.OAK_PLANKS);
        int breadBefore = countInHearth(hearth, Items.BREAD);
        int planksBefore = countInHearth(hearth, Items.OAK_PLANKS);

        // The room stops meeting its requirements after the exact guest has
        // arrived. Waiting is preserved, but invalid authority cannot admit.
        tavern.valid = false;

        SettlementManager.tickRecruitment(level, s);

        helper.assertTrue(guest.isTraveler() && guest.isAlive()
                && s.recruitment.status()
                    == RecruitmentTransaction.Status.WAITING_ADMISSION,
            "an arrived guest must survive a temporary Tavern invalidation");
        helper.assertTrue(countInHearth(hearth, Items.BREAD) == breadBefore
                && countInHearth(hearth, Items.OAK_PLANKS) == planksBefore,
            "waiting through invalidation must never auto-pay");

        tavern.valid = true;
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        helper.assertTrue(SettlementManager.admitWaitingTraveler(player, s,
                guest.getUUID(), s.recruitment.revision())
                == SettlementManager.AdmissionResult.COMMITTED,
            "restoring the exact locked Tavern must make the same guest admissible");
        helper.assertFalse(guest.isTraveler(),
            "the recovered explicit admission must bind the waiting guest");
        helper.assertTrue(guest.isBound(), "...and must actually join the settlement");
        helper.assertTrue(countInHearth(hearth, Items.BREAD) == 12,
            "coin payment must retain the seeded meals, found "
                + countInHearth(hearth, Items.BREAD));
        helper.assertTrue(countInHearth(hearth, Items.OAK_PLANKS) == 8,
            "coin payment must retain the seeded planks, found " + countInHearth(hearth, Items.OAK_PLANKS));
        helper.assertTrue(countInHearth(hearth, com.hearthstead.registry.ModItems.GOLD_COIN.get()) == 0
                && s.recruitment.admissionReceipt().removedItemCount() == s.recruitment.quote().coins(), "same recovered guest spends its frozen coins once");
        helper.succeed();
    }

    /** Payment tests still require a real, free bed; abstract founder capacity is insufficient. */
    private static void admissionBed(GameTestHelper helper, Settlement settlement) {
        if (com.hearthstead.settlement.BuildingManager.findFreeBed(helper.getLevel(), settlement) != null) return;
        Building home = GameTestFixtures.register(helper, settlement, BuildingType.HOUSE, 2, 7);
        BlockPos foot = new BlockPos(3, 1, 8);
        BlockPos head = foot.south();
        var state = Blocks.RED_BED.defaultBlockState()
            .setValue(net.minecraft.world.level.block.BedBlock.FACING, net.minecraft.core.Direction.SOUTH);
        helper.setBlock(foot, state.setValue(net.minecraft.world.level.block.BedBlock.PART,
            net.minecraft.world.level.block.state.properties.BedPart.FOOT));
        helper.setBlock(head, state.setValue(net.minecraft.world.level.block.BedBlock.PART,
            net.minecraft.world.level.block.state.properties.BedPart.HEAD));
        home.beds.add(helper.absolutePos(head));
        helper.assertTrue(com.hearthstead.settlement.BuildingManager.findFreeBed(helper.getLevel(), settlement)
            .equals(helper.absolutePos(head)), "payment fixture must expose the real unclaimed bed head");
    }
}
