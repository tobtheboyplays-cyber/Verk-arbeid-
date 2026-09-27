package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.TavernServingEntity;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.entity.ai.CourierWorkGoal;
import com.hearthstead.settlement.request.RequestLedgerSavedData;
import com.hearthstead.settlement.request.RequestType;
import com.hearthstead.settlement.work.TavernHostService;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import java.util.function.BooleanSupplier;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.TavernSeatEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.TavernSeating;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

/** Synthetic registered Tavern setup; real stairs, pathfinding, mounts and entity NBT. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class TavernSeatingGameTests {
    /**
     * One real Courier route carries both reusable Tavern inputs through its
     * visible source-bag and target-bag contacts. The control below lowers the
     * live Hearth reserve before route selection: food must then remain a
     * Hearth FOOD request and Tavern MATERIAL_INPUT must not open.
     */
    @GameTest(batch = "courier_tavern_restock", template = "empty16", timeoutTicks = 1800)
    public void courierRestocksOneSharedTavernStoreAfterHearthReserve(GameTestHelper h) {
        Fixture f = fixture(h);
        // fixture(h) keeps the seating suite at EVENING; only this Courier route
        // needs a work shift so its normal canUse gate is exercised.
        h.getLevel().setDayTime(2000);
        BlockPos hearthRel = new BlockPos(1, 1, 1);
        h.setBlock(hearthRel, ModBlocks.HEARTH.get());
        HearthBlockEntity hearth = (HearthBlockEntity) h.getBlockEntity(hearthRel);
        h.assertTrue(hearth != null, "relative Hearth fixture block entity exists");
        hearth.bindSettlement(f.village().id);

        Building warehouse = GameTestFixtures.registerWithBounds(h, f.village(), BuildingType.WAREHOUSE,
            new BlockPos(14, 1, 3), new BlockPos(13, 1, 2),
            BoundingBox.fromCorners(h.absolutePos(new BlockPos(13, 1, 2)), h.absolutePos(new BlockPos(15, 3, 4))));
        BlockPos sourceRel = new BlockPos(14, 1, 3);
        h.setBlock(sourceRel, Blocks.CHEST);
        Container source = (Container) h.getLevel().getBlockEntity(h.absolutePos(sourceRel));
        h.assertTrue(source != null, "physical Warehouse source chest exists");
        source.setItem(0, new ItemStack(Items.BREAD, 8));
        source.setItem(1, new ItemStack(Items.GLASS_BOTTLE, 4));

        BlockPos targetRel = new BlockPos(3, 1, 5);
        h.setBlock(targetRel, Blocks.BARREL);
        Container target = (Container) h.getLevel().getBlockEntity(h.absolutePos(targetRel));
        h.assertTrue(target != null && TavernHostService.restockNeed(h.getLevel(), f.village(), f.tavern(),
            new ItemStack(Items.BREAD)).container().equals(h.absolutePos(targetRel)),
            "one empty Tavern barrel is the shared food-and-bottle service store");

        SettlerEntity courier = h.spawn(ModEntities.SETTLER.get(), new BlockPos(13, 1, 5));
        courier.bindTo(f.village().id, f.village().center);
        f.village().putRecord(courier.getUUID(), "Courier", Profession.NONE);
        h.assertTrue(Employment.hire(h.getLevel(), f.village(), warehouse, courier).ok(),
            "real Warehouse employment owns this Courier");
        int reserve = com.hearthstead.settlement.RecruitmentPolicy.assess(h.getLevel(), f.village(),
            com.hearthstead.settlement.RecruitmentPolicy.stageFor(f.village())).courierReadyFoodTarget();
        hearth.getInventory().setStackInSlot(0, new ItemStack(Items.BREAD, reserve));
        courier.setNoAi(true);
        f.host().setNoAi(true); f.guest().setNoAi(true);
        CourierWorkGoal route = new CourierWorkGoal(courier);
        BlockPos sourceContact = h.absolutePos(sourceRel.south());
        BlockPos targetContact = h.absolutePos(targetRel.south());
        courier.setPos(sourceContact.getX() + .5, sourceContact.getY(), sourceContact.getZ() + .5);
        h.assertTrue(route.canUse(), "Hearth reserve permits the first Tavern restock request");
        route.start();
        boolean[] sawBreadBag = {false}, sawBottleBag = {false}, priorityChecked = {false}, noSplitStore = {false}, sharedService = {false};
        h.succeedWhen(() -> {
            int breadTotal = countIn(source, Items.BREAD) + countIn(target, Items.BREAD) + bagCount(courier, Items.BREAD);
            int bottleTotal = countIn(source, Items.GLASS_BOTTLE) + countIn(target, Items.GLASS_BOTTLE) + bagCount(courier, Items.GLASS_BOTTLE);
            h.assertTrue(breadTotal == 8 && bottleTotal == 4,
                "Warehouse, shared Tavern store and real Courier bag conserve every service item");
            sawBreadBag[0] |= bagCount(courier, Items.BREAD) > 0;
            sawBottleBag[0] |= bagCount(courier, Items.GLASS_BOTTLE) > 0;
            boolean sourceSession = new com.hearthstead.entity.ai.CourierSourceBagSession(courier).active();
            if (!sourceSession && (bagCount(courier, Items.BREAD) > 0 || bagCount(courier, Items.GLASS_BOTTLE) > 0))
                courier.setPos(targetContact.getX() + .5, targetContact.getY(), targetContact.getZ() + .5);
            route.tick();
            long activeMaterialRows = RequestLedgerSavedData.get(h.getLevel()).ledger(f.village().id).active().stream()
                .filter(row -> row.type() == RequestType.MATERIAL_INPUT).count();
            if ((sawBreadBag[0] || sawBottleBag[0]) && activeMaterialRows == 0
                && bagCount(courier, Items.BREAD) == 0 && bagCount(courier, Items.GLASS_BOTTLE) == 0
                && !sourceSession && !courier.bagTransferPresentation().active()
                && (countIn(target, Items.BREAD) < 8 || countIn(target, Items.GLASS_BOTTLE) < 4)) {
                route.stop(); courier.setPos(sourceContact.getX() + .5, sourceContact.getY(), sourceContact.getZ() + .5);
                h.assertTrue(route.canUse(), "the second bounded service deficit selects after first completion");
                route.start();
            }
            if (countIn(target, Items.BREAD) == 8 && countIn(target, Items.GLASS_BOTTLE) == 4
                && bagCount(courier, Items.BREAD) == 0 && bagCount(courier, Items.GLASS_BOTTLE) == 0) {
                route.stop();
                sharedService[0] = h.absolutePos(targetRel).equals(TavernHostService.source(f.host(), f.tavern()));
                long completedRestocks = RequestLedgerSavedData.get(h.getLevel()).ledger(f.village().id).active().stream()
                    .filter(row -> row.type() == RequestType.MATERIAL_INPUT).count();
                h.assertTrue(sharedService[0] && completedRestocks == 0,
                    "both Tavern input rows complete only after their exact shared service store owns them");
                source.setItem(0, new ItemStack(Items.BREAD));
                hearth.getInventory().setStackInSlot(0, new ItemStack(Items.BREAD, Math.max(0, reserve - 1)));
                CourierWorkGoal hungry = new CourierWorkGoal(courier);
                courier.setPos(sourceContact.getX() + .5, sourceContact.getY(), sourceContact.getZ() + .5);
                h.assertTrue(hungry.canUse(), "one-below Hearth reserve opens its higher-priority food route");
                long tavernRows = RequestLedgerSavedData.get(h.getLevel()).ledger(f.village().id).active().stream()
                    .filter(row -> row.type() == RequestType.MATERIAL_INPUT).count();
                long hearthFoodRows = RequestLedgerSavedData.get(h.getLevel()).ledger(f.village().id).active().stream()
                    .filter(row -> row.type() == RequestType.FOOD).count();
                h.assertTrue(tavernRows == 0 && hearthFoodRows == 1 && countIn(source, Items.BREAD) == 1,
                    "hungry Hearth opens its FOOD row, opens no Tavern row, and moves no Warehouse food before source contact");
                BlockPos splitRel = new BlockPos(4, 1, 5);
                BlockPos oneFreeRel = new BlockPos(5, 1, 5);
                BlockPos twoFreeRel = new BlockPos(6, 1, 5);
                h.setBlock(splitRel, Blocks.BARREL);
                h.setBlock(oneFreeRel, Blocks.BARREL);
                Container split = (Container) h.getLevel().getBlockEntity(h.absolutePos(splitRel));
                Container oneFree = (Container) h.getLevel().getBlockEntity(h.absolutePos(oneFreeRel));
                for (int slot = 0; slot < target.getContainerSize(); slot++)
                    target.setItem(slot, new ItemStack(slot == 0 ? Items.BREAD : Items.COBBLESTONE, 64));
                for (int slot = 0; slot < split.getContainerSize(); slot++)
                    split.setItem(slot, new ItemStack(slot == 0 ? Items.GLASS_BOTTLE : Items.COBBLESTONE, 64));
                for (int slot = 0; slot < oneFree.getContainerSize(); slot++)
                    oneFree.setItem(slot, slot == 0 ? ItemStack.EMPTY : new ItemStack(Items.COBBLESTONE, 64));
                noSplitStore[0] = TavernHostService.restockNeed(h.getLevel(), f.village(), f.tavern(),
                    new ItemStack(Items.BREAD)) == null;
                h.assertTrue(noSplitStore[0],
                    "split stores and one free slot cannot falsely reserve separate food and bottle capacity");
                h.setBlock(twoFreeRel, Blocks.BARREL);
                Container twoFree = (Container) h.getLevel().getBlockEntity(h.absolutePos(twoFreeRel));
                for (int slot = 0; slot < twoFree.getContainerSize(); slot++)
                    twoFree.setItem(slot, slot < 2 ? ItemStack.EMPTY : new ItemStack(Items.COBBLESTONE, 64));
                BlockPos twoFreeAbs = h.absolutePos(twoFreeRel);
                h.assertTrue(TavernHostService.restockNeed(h.getLevel(), f.village(), f.tavern(),
                    new ItemStack(Items.BREAD)).container().equals(twoFreeAbs),
                    "25 cobblestone stacks plus two empty slots accepts one complete shared reserve");
                twoFree.setItem(0, new ItemStack(Items.BREAD));
                h.assertTrue(TavernHostService.restockNeed(h.getLevel(), f.village(), f.tavern(),
                    new ItemStack(Items.BREAD)).container().equals(twoFreeAbs),
                    "one landed bread still leaves the same store able to receive the remaining two classes");
                twoFree.setItem(0, new ItemStack(Items.BREAD, 8));
                twoFree.setItem(1, new ItemStack(Items.GLASS_BOTTLE, 4));
                h.assertTrue(twoFreeAbs.equals(TavernHostService.source(f.host(), f.tavern())),
                    "full shared reserve remains an actual Tavern serving source");
                priorityChecked[0] = true;
            }
            h.assertTrue(priorityChecked[0] && noSplitStore[0] && sharedService[0]
                && sawBreadBag[0] && sawBottleBag[0],
                "both physical input classes reach one serviceable Tavern source through Courier bag contacts");
        });
    }

    private static int countIn(Container container, Item item) {
        int total = 0;
        for (int slot = 0; container != null && slot < container.getContainerSize(); slot++)
            if (container.getItem(slot).is(item)) total += container.getItem(slot).getCount();
        return total;
    }

    private static int bagCount(SettlerEntity settler, Item item) {
        int total = 0;
        for (int slot = 0; slot < settler.bag.getContainerSize(); slot++)
            if (settler.bag.getItem(slot).is(item)) total += settler.bag.getItem(slot).getCount();
        return total;
    }
    @GameTest(template = "empty16", timeoutTicks = 40)
    public void exactWaitingTravelerMaySitWithoutBecomingResidentOrBypassingBed(GameTestHelper h) {
        Fixture f = fixture(h);
        var village = f.village();
        var guest = f.guest();
        village.removeRecord(guest.getUUID());
        guest.unbind();
        guest.markTraveler(village.id, village.center);
        guest.setActivity(com.hearthstead.entity.SettlerActivity.IDLE);
        // This synthetic seating fixture registers its room directly; the
        // arrival contract also requires the physical plaque's exact link.
        var plaque = (com.hearthstead.block.PlaqueBlockEntity)
            h.getLevel().getBlockEntity(f.tavern().plaquePos);
        CompoundTag plaqueTag = plaque.saveWithoutMetadata(h.getLevel().registryAccess());
        plaqueTag.putUUID("Building", f.tavern().id);
        plaqueTag.putString("Type", BuildingType.TAVERN.id());
        plaqueTag.putString("State", com.hearthstead.building.PlaqueState.LINKED_VALID.id());
        plaque.loadWithComponents(plaqueTag, h.getLevel().registryAccess());
        h.assertTrue(f.tavern().id.equals(plaque.buildingId()), "exact physical Tavern link");
        UUID transactionId = UUID.randomUUID();
        var quote = com.hearthstead.settlement.RecruitmentQuote.fromStartingAttributes(
            transactionId, guest.getUUID(), guest.attributes(), java.util.List.of());
        var traveling = com.hearthstead.settlement.RecruitmentTransaction.fresh(village.id)
            .adminPrime(transactionId, h.getLevel().getGameTime(), f.tavern().id,
                f.tavern().plaquePos, f.tavern().anchor, h.getLevel().dimension().location())
            .travelerSpawned(guest.getUUID(), "Waiting visitor", h.getLevel().getGameTime(), quote);
        village.applyRecruitment(traveling);
        h.assertTrue(!TavernSeating.mayVisit(guest), "travel must finish before seat authority");
        village.applyRecruitment(traveling.arrived(h.getLevel().getGameTime()));
        CompoundTag before = village.recruitment.writeNbt();
        village.recruitment = com.hearthstead.settlement.RecruitmentTransaction.readOrQuarantine(before, village.id);
        h.getLevel().setDayTime(6000);
        h.assertTrue(!TavernSeating.mayVisit(guest), "paid waiting guests also wait through lunch");
        h.getLevel().setDayTime(11500);
        h.assertTrue(TavernSeating.mayVisit(guest), "exact saved waiting candidate may visit in the evening");
        f.tavern().workers.remove(f.host().getUUID());
        h.assertTrue(TavernSeating.mayVisit(guest)
            && new com.hearthstead.entity.ai.TravelerJoinGoal(guest).canUse(),
            "an unstaffed exact waiting guest remains eligible for the higher-priority chair visit and its ordinary return walk");
        f.tavern().workers.add(f.host().getUUID());
        h.assertTrue(TavernSeating.mayVisit(guest), "restoring host leaves the exact visit authority intact");
        TavernSeatEntity seat = mountAtAisle(h, f);
        h.assertTrue(guest.getVehicle() == seat && village.record(guest.getUUID()) == null
            && guest.getSettlementId() == null && guest.isTraveler(), "physical seat must not grant residency");
        SettlerEntity stranger = h.spawn(ModEntities.SETTLER.get(), new BlockPos(11, 1, 10));
        stranger.markTraveler(village.id, village.center);
        h.assertTrue(!TavernSeating.mayVisit(stranger), "same target settlement is not exact candidate authority");
        h.setBlock(new BlockPos(1, 1, 1), ModBlocks.HEARTH.get());
        HearthBlockEntity hearth = (HearthBlockEntity) h.getBlockEntity(new BlockPos(1, 1, 1));
        hearth.bindSettlement(village.id);
        hearth.getInventory().setStackInSlot(0, new ItemStack(Items.BREAD, 64));
        hearth.getInventory().setStackInSlot(1, new ItemStack(Items.OAK_PLANKS, 64));
        var assessment = com.hearthstead.settlement.RecruitmentPolicy.assess(h.getLevel(), village,
            com.hearthstead.settlement.RecruitmentPolicy.Stage.WAITING_ADMISSION);
        h.assertTrue(assessment.blocker() == com.hearthstead.settlement.RecruitmentPolicy.Blocker.NO_BED,
            "actual free-bed policy still blocks this housed-capacity-zero fixture: " + assessment.blocker());
        var result = com.hearthstead.settlement.SettlementManager.admitWaitingTraveler(
            h.makeMockServerPlayerInLevel(), village, guest.getUUID(), village.recruitment.revision());
        h.assertTrue(result == com.hearthstead.settlement.SettlementManager.AdmissionResult.BLOCKED_POLICY,
            "Recruit must reject before payment without a free bed: " + result);
        h.assertTrue(hearth.getInventory().getStackInSlot(0).getCount() == 64
            && hearth.getInventory().getStackInSlot(1).getCount() == 64
            && before.equals(village.recruitment.writeNbt()) && guest.getVehicle() == seat,
            "refusal preserves price, arrival, seat and every supplied item");
        h.succeed();
    }

    @GameTest(template = "empty16", batch = "traveler_wait_owner", timeoutTicks = 2000)
    public void waitingGuestKeepsAnchorBetweenRealChairVisits(GameTestHelper h) {
        Fixture f = waitingGuestFixture(h);
        Settlement village = f.village();
        SettlerEntity guest = f.guest();
        village.removeRecord(guest.getUUID());
        guest.unbind();
        guest.markTraveler(village.id, village.center);
        var plaque = (com.hearthstead.block.PlaqueBlockEntity)
            h.getLevel().getBlockEntity(f.tavern().plaquePos);
        h.assertTrue(plaque != null && plaque.state()
                == com.hearthstead.building.PlaqueState.LINKED_VALID
                && f.tavern().valid && f.tavern().id.equals(plaque.buildingId()),
            "actual Tavern survey/link must commit a valid exact building before the waiting transaction");
        BlockPos committedAnchor = f.tavern().anchor.immutable();
        UUID transactionId = UUID.randomUUID();
        UUID guestId = guest.getUUID();
        var quote = com.hearthstead.settlement.RecruitmentQuote.fromStartingAttributes(
            transactionId, guestId, guest.attributes(), java.util.List.of());
        village.applyRecruitment(com.hearthstead.settlement.RecruitmentTransaction.fresh(village.id)
            .adminPrime(transactionId, h.getLevel().getGameTime(), f.tavern().id,
                f.tavern().plaquePos, committedAnchor, h.getLevel().dimension().location())
            .travelerSpawned(guestId, "Waiting ownership visitor", h.getLevel().getGameTime(), quote));
        h.assertTrue(committedAnchor.equals(village.recruitment.tavernAnchor()),
            "the transaction must lock the already-surveyed Tavern anchor, never a fixture replacement");
        BlockPos chair = new BlockPos(6, 1, 7);
        var chairState = h.getLevel().getBlockState(h.absolutePos(chair));
        h.setBlock(chair, Blocks.AIR);
        BlockPos approach = com.hearthstead.settlement.SettlementManager.travelerTavernApproach(h.getLevel(), guest);
        h.assertTrue(approach != null && guest.blockPosition().distSqr(approach) > 9,
            "real traveler starts outside the actual waiting radius on the existing supported floor");
        Vec3 initial = guest.position();
        int population = village.population();
        int[] age = {0}, arrivedAt = {-1}, openedAt = {-1}, releasedAt = {-1}, returnedAt = {-1};
        int[] mountedAt = {-1};
        CompoundTag[] arrival = {null};
        boolean[] settled = {false}, exited = {false};
        TavernSeatEntity[] lastVisit = {null};
        String[] priorVisitState = {null};
        String[] recentVisitTransitions = {"-", "-", "-"};
        String[] lastOwnedEntry = {"none"};
        h.onEachTick(() -> {
            int tick = ++age[0];
            // The registered fixture has no ticking Hearth; invoke only the real recruitment
            // service cadence, never a movement goal or an artificial arrived transition.
            if (tick % 20 == 0) com.hearthstead.settlement.SettlementManager.tickRecruitment(h.getLevel(), village);
            h.assertTrue(guest.isAlive() && guest.isTraveler() && guestId.equals(village.recruitment.travelerId())
                    && transactionId.equals(village.recruitment.transactionId())
                    && village.id.equals(guest.getTargetSettlementId()) && guest.getSettlementId() == null
                    && village.record(guestId) == null && village.population() == population
                    && guest.bag.isEmpty() && f.tavern().valid
                    && committedAnchor.equals(f.tavern().anchor)
                    && f.tavern().id.equals(village.recruitment.tavernBuildingId())
                    && f.tavern().plaquePos.equals(village.recruitment.tavernPlaquePos())
                    && committedAnchor.equals(village.recruitment.tavernAnchor())
                    && plaque.state() == com.hearthstead.building.PlaqueState.LINKED_VALID
                    && f.tavern().id.equals(plaque.buildingId()),
                "every ordinary tick preserves guest identity, transaction, roster, cargo and the surveyed Tavern link");
            boolean waiting = village.recruitment.status()
                == com.hearthstead.settlement.RecruitmentTransaction.Status.WAITING_ADMISSION;
            if (arrivedAt[0] < 0) {
                h.assertTrue(tick <= 240, "ordinary traveler must physically reach the existing nearby Tavern");
                if (!waiting) return;
                h.assertTrue(guest.position().distanceTo(initial) > 2,
                    "arrival is witnessed after actual selector movement, not a fabricated arrival");
                arrivedAt[0] = tick;
                arrival[0] = village.recruitment.writeNbt();
                village.recruitment = com.hearthstead.settlement.RecruitmentTransaction.readOrQuarantine(
                    arrival[0], village.id);
            }
            h.assertTrue(waiting && arrival[0].equals(village.recruitment.writeNbt()),
                "waiting quote, arrival clock and revision survive roundtrip and every visit unchanged");
            if (openedAt[0] < 0) {
                h.assertTrue(TavernSeating.mayVisit(guest)
                        && new com.hearthstead.entity.ai.TravelerJoinGoal(guest).canUse(),
                    "broad visit eligibility without a chair must not relinquish the waiting movement owner");
                h.assertTrue(!guest.isPassenger() && guest.blockPosition().distSqr(approach) <= 16,
                    "no-chair visitor stays at the real approach across two 200-tick search cooldowns");
                if (tick - arrivedAt[0] < 450) return;
                h.setBlock(chair, chairState);
                openedAt[0] = tick;
                return;
            }
            if (!settled[0]) {
                captureWaitingVisitTransition(guest, tick, priorVisitState, recentVisitTransitions);
                String entry = entryMotionDiagnostic(h, guest);
                if (!entry.equals("none")) lastOwnedEntry[0] = "t=" + tick + "," + entry;
                TavernSeatEntity seat = guest.getVehicle() instanceof TavernSeatEntity value ? value : null;
                if (seat == null) {
                    if (mountedAt[0] >= 0 || tick - openedAt[0] > 400) {
                        h.fail("available real chair must begin ordinary entry within 400 ticks and retain its passenger"
                        + chairMountDiagnostic(h, f, guest, chair, approach, tick, openedAt[0],
                            recentVisitTransitions, lastOwnedEntry[0]));
                    }
                    return;
                }
                if (mountedAt[0] < 0) {
                    h.assertTrue(tick - openedAt[0] <= 400,
                        "real seat entry must begin within the original 400-tick approach budget");
                    mountedAt[0] = tick;
                    lastVisit[0] = seat;
                }
                h.assertTrue(seat.owns(guest) && TavernSeating.currentTavern(guest) == f.tavern(),
                    "same waiting guest owns the exact locked Tavern chair during entry");
                // Entry owns its fixed motion clock after mounting. Allow one observation
                // tick for GameTest/entity ordering, without extending the approach budget.
                h.assertTrue(seat == lastVisit[0]
                        && tick - mountedAt[0] <= com.hearthstead.entity.TavernSeatMotion.TICKS + 1,
                    "the original seat must finish entry within its real animation duration");
                if (!seat.isSettled()) {
                    h.assertTrue(seat.isTransitioning(), "mounted entry must keep its actual motion phase");
                    return;
                }
                settled[0] = true;
                lastVisit[0] = seat;
                h.assertTrue(seat.release(), "actual seat release must allow a safe physical exit");
                releasedAt[0] = tick;
                return;
            }
            if (!exited[0]) {
                h.assertTrue(tick - releasedAt[0] <= 100, "released visit must finish its actual exit");
                if (guest.isPassenger()) return;
                exited[0] = true;
                h.setBlock(chair, Blocks.AIR);
            }
            if (guest.blockPosition().distSqr(approach) <= 9 && returnedAt[0] < 0) returnedAt[0] = tick;
            h.assertTrue(tick - releasedAt[0] <= 400 || returnedAt[0] >= 0,
                "ended visit must physically return to its immutable approach");
            if (returnedAt[0] >= 0) {
                if (guest.isPassenger() || guest.blockPosition().distSqr(approach) > 16) {
                    var activePath = guest.getNavigation().getPath();
                    var transaction = village.recruitment;
                    var lockedBuilding = village.buildings.stream()
                        .filter(building -> transaction.tavernBuildingId() != null
                            && transaction.tavernBuildingId().equals(building.id))
                        .findFirst().orElse(null);
                    var plaquePos = transaction.tavernPlaquePos();
                    var plaqueEntity = plaquePos == null ? null
                        : h.getLevel().getBlockEntity(plaquePos);
                    var diagnosticPlaque = plaqueEntity instanceof com.hearthstead.block.PlaqueBlockEntity value
                        ? value : null;
                    var anchor = transaction.tavernAnchor();
                    var anchorState = anchor == null ? null : h.getLevel().getBlockState(anchor);
                    var floor = approach.below();
                    var head = approach.above();
                    var floorState = h.getLevel().getBlockState(floor);
                    var approachState = h.getLevel().getBlockState(approach);
                    var headState = h.getLevel().getBlockState(head);
                    var liveApproach = com.hearthstead.settlement.SettlementManager
                        .travelerTavernApproach(h.getLevel(), guest);
                    h.fail("released guest retains the anchor through another full search cooldown"
                        + " tick=" + tick + " arrived=" + arrivedAt[0] + " chairOpened=" + openedAt[0]
                        + " released=" + releasedAt[0] + " returned=" + returnedAt[0]
                        + " actor=" + guestId + " pos=" + guest.position() + " velocity=" + guest.getDeltaMovement()
                        + " approach=" + approach + " liveApproach=" + liveApproach
                        + " distanceSquared=" + guest.blockPosition().distSqr(approach)
                        + " activity=" + guest.getActivity() + " passenger=" + guest.isPassenger()
                        + " vehicle=" + (guest.getVehicle() == null ? "none" : guest.getVehicle().getType())
                        + " moveWanted=" + guest.getMoveControl().hasWanted()
                        + " moveTarget=" + guest.getMoveControl().getWantedX() + ","
                            + guest.getMoveControl().getWantedY() + "," + guest.getMoveControl().getWantedZ()
                        + " moveSpeed=" + guest.getMoveControl().getSpeedModifier()
                        + " lastSeat=" + (lastVisit[0] == null ? "none" : "removed=" + lastVisit[0].isRemoved()
                            + ",closing=" + lastVisit[0].isClosing() + ",transitioning=" + lastVisit[0].isTransitioning()
                            + ",owns=" + lastVisit[0].owns(guest) + ",hasPassenger=" + lastVisit[0].hasPassenger(guest))
                        + " navigationDone=" + guest.getNavigation().isDone()
                        + " path=" + (activePath == null ? "none" : activePath.getNextNodeIndex()
                            + "/" + activePath.getNodeCount() + " target=" + activePath.getTarget()
                            + " end=" + activePath.getEndNode())
                        + " onGround=" + guest.onGround() + " chair=" + h.getLevel().getBlockState(h.absolutePos(chair))
                        + " txStatus=" + transaction.status() + " txId=" + transaction.transactionId()
                        + " txBuilding=" + transaction.tavernBuildingId() + " txPlaque=" + plaquePos
                        + " txAnchor=" + anchor + " txDimension=" + transaction.dimension()
                        + " building=" + (lockedBuilding == null ? "none" : "id=" + lockedBuilding.id
                            + ",valid=" + lockedBuilding.valid + ",type=" + lockedBuilding.type
                            + ",plaque=" + lockedBuilding.plaquePos + ",anchor=" + lockedBuilding.anchor)
                        + " plaque=" + (diagnosticPlaque == null ? "none" : "removed=" + diagnosticPlaque.isRemoved()
                            + ",buildingId=" + diagnosticPlaque.buildingId() + ",state=" + diagnosticPlaque.state())
                        + " anchorState=" + anchorState
                        + " approachLoaded=" + h.getLevel().isLoaded(approach)
                        + " floorLoaded=" + h.getLevel().isLoaded(floor)
                        + " headLoaded=" + h.getLevel().isLoaded(head)
                        + " floor=" + floorState + ",floorSolid=" + floorState.isFaceSturdy(
                            h.getLevel(), floor, net.minecraft.core.Direction.UP)
                        + " approachBlock=" + approachState + ",approachEmpty="
                        + approachState.getCollisionShape(h.getLevel(), approach).isEmpty()
                        + " headBlock=" + headState + ",headEmpty="
                        + headState.getCollisionShape(h.getLevel(), head).isEmpty());
                }
                if (tick - returnedAt[0] >= 225) h.succeed();
            }
        });
    }

    private record Fixture(Settlement village, Building tavern, SettlerEntity host, SettlerEntity guest) {}

    /**
     * Long waiting-owner coverage must survive ordinary plaque surveys. Keep
     * this room private to that scenario so its physical requirements do not
     * turn shared seating fixtures into unrelated building-scan fixtures.
     */
    private static Fixture waitingGuestFixture(GameTestHelper h) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            for (int y = 1; y <= 4; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        h.getLevel().setDayTime(11500);
        Settlement s = new Settlement(UUID.randomUUID(), "Waiting room fixture",
            h.absolutePos(new BlockPos(1, 1, 1)));
        SettlementSavedData.get(h.getLevel()).settlements.put(s.id, s);
        SettlementSavedData.get(h.getLevel()).setDirty();

        BlockPos origin = new BlockPos(2, 0, 2);
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                boolean wall = x == 0 || z == 0 || x == 7 || z == 7;
                for (int y = 1; y <= 3; y++) {
                    h.setBlock(origin.offset(x, y, z),
                        wall ? Blocks.STONE_BRICKS : Blocks.AIR);
                }
                h.setBlock(origin.offset(x, 0, z), Blocks.STONE_BRICKS);
                h.setBlock(origin.offset(x, 4, z), Blocks.STONE_BRICKS);
            }
        }
        h.setBlock(origin.offset(4, 1, 0), Blocks.OAK_DOOR.defaultBlockState());
        h.setBlock(origin.offset(4, 2, 0), Blocks.OAK_DOOR.defaultBlockState()
            .setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));

        // TAVERN requirements: bell 1, storage 2, door 1, lights 3 and
        // floor-space 36. The 6x6x3 interior scans to 108 cells; the linked
        // 8x5x8 building bounds remain below the seating scan's 400-cell
        // fixture budget while retaining a closed, roofed room.
        h.setBlock(origin.offset(2, 1, 2), Blocks.BELL);
        h.setBlock(origin.offset(4, 1, 2), Blocks.BARREL);
        h.setBlock(origin.offset(5, 1, 2), Blocks.BARREL);
        h.setBlock(origin.offset(5, 1, 3), ModBlocks.ALE_TAP.get().defaultBlockState()
            .setValue(com.hearthstead.block.AleTapBlock.FACING, Direction.SOUTH));
        h.setBlock(origin.offset(1, 1, 1), Blocks.TORCH);
        h.setBlock(origin.offset(6, 1, 1), Blocks.TORCH);
        h.setBlock(origin.offset(6, 1, 6), Blocks.TORCH);
        h.setBlock(new BlockPos(6, 1, 7), Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(StairBlock.FACING, Direction.SOUTH));
        h.setBlock(new BlockPos(6, 1, 6), Blocks.OAK_FENCE);
        h.setBlock(new BlockPos(6, 2, 6), Blocks.OAK_PRESSURE_PLATE);

        BlockPos plaqueRel = origin.offset(1, 2, -1);
        h.setBlock(plaqueRel, ModBlocks.PLAQUE.get().defaultBlockState()
            .setValue(com.hearthstead.block.PlaqueBlock.FACING, Direction.NORTH));
        h.assertTrue(h.getLevel().getBlockState(h.absolutePos(plaqueRel))
                .canSurvive(h.getLevel(), h.absolutePos(plaqueRel)),
            "the surveyed Tavern plaque must have its real wall support before the plan is fitted");
        var plaque = (com.hearthstead.block.PlaqueBlockEntity) h.getLevel()
            .getBlockEntity(h.absolutePos(plaqueRel));
        h.assertTrue(plaque != null && plaque.insertPlan(h.getLevel(),
                com.hearthstead.block.PlaqueItemData.stamped(
                    new ItemStack(com.hearthstead.registry.ModItems.BUILD_PLAN.get()),
                    BuildingType.TAVERN)),
            "the physical waiting Tavern must accept one real Tavern plan");
        Building tavern = plaque.building(h.getLevel());
        h.assertTrue(tavern != null && tavern.bounds != null,
            "the real Tavern survey must commit its building and bounded room before fixture use");
        long scannedBounds = (long) (tavern.bounds.maxX() - tavern.bounds.minX() + 1)
            * (tavern.bounds.maxY() - tavern.bounds.minY() + 1)
            * (tavern.bounds.maxZ() - tavern.bounds.minZ() + 1);
        h.assertTrue(plaque.state() == com.hearthstead.building.PlaqueState.LINKED_VALID
                && tavern.valid && s.buildings.contains(tavern)
                && plaque.lastSurvey().stream().allMatch(status -> status.met())
                && scannedBounds <= 400L,
            "actual scan must link one complete closed Tavern within the fixture scan budget");

        SettlerEntity host = resident(h, s, new BlockPos(3, 1, 4));
        h.assertTrue(Employment.hire(h.getLevel(), s, tavern, host).ok(),
            "real fixture Innkeeper employment must follow the physical Tavern link");
        SettlerEntity guest = resident(h, s, new BlockPos(10, 1, 10));
        return new Fixture(s, tavern, host, guest);
    }

    private static Fixture fixture(GameTestHelper h) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            for (int y = 1; y <= 4; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        h.getLevel().setDayTime(11500);
        Settlement s = new Settlement(UUID.randomUUID(), "Seat fixture", h.absolutePos(new BlockPos(1, 1, 1)));
        SettlementSavedData.get(h.getLevel()).settlements.put(s.id, s);
        SettlementSavedData.get(h.getLevel()).setDirty();
        Building tavern = GameTestFixtures.registerWithBounds(h, s, BuildingType.TAVERN,
            new BlockPos(6, 1, 6), new BlockPos(2, 2, 2),
            BoundingBox.fromCorners(h.absolutePos(new BlockPos(2, 1, 2)), h.absolutePos(new BlockPos(12, 4, 12))));
        h.setBlock(new BlockPos(6, 1, 7), Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
        h.setBlock(new BlockPos(6, 1, 6), Blocks.OAK_FENCE);
        h.setBlock(new BlockPos(6, 2, 6), Blocks.OAK_PRESSURE_PLATE);
        SettlerEntity host = resident(h, s, new BlockPos(3, 1, 4));
        h.assertTrue(Employment.hire(h.getLevel(), s, tavern, host).ok(), "real fixture Innkeeper employment must succeed");
        SettlerEntity guest = resident(h, s, new BlockPos(10, 1, 10));
        return new Fixture(s, tavern, host, guest);
    }
    private static SettlerEntity resident(GameTestHelper h, Settlement s, BlockPos p) {
        SettlerEntity e = h.spawn(ModEntities.SETTLER.get(), p);
        // Spawn positions the entity but does not run collision/grounding first.
        // Resolve against the real fixture floor before asking GroundPathNavigation.
        Vec3 spawnPosition = e.position();
        e.move(net.minecraft.world.entity.MoverType.SELF, new Vec3(0.0, -0.05, 0.0));
        h.assertTrue(e.onGround() && e.position().equals(spawnPosition),
            "fixture resident must contact its real floor without displacement");
        e.bindTo(s.id, s.center); s.putRecord(e.getUUID(), "Diner", Profession.NONE);
        e.setHunger(100); e.setEnergy(100);
        return e;
    }
    private static TavernSeatEntity reserve(GameTestHelper h, Fixture f) {
        var result = TavernSeating.reserveReachable(f.guest());
        h.assertTrue(result.seat() != null && result.path() != null && result.path().canReach(), "real reachable stair reservation required");
        return result.seat();
    }
    private static String entryFailure(GameTestHelper h, SettlerEntity guest, TavernSeatEntity seat) {
        String preconditions=" actualPos="+guest.position()+" staging="+com.hearthstead.entity.TavernSeatMotion.staging(seat.site())
            +" atEntry="+seat.atEntry(guest)+" owns="+seat.owns(guest)+" closing="+seat.isClosing()
            +" passenger="+guest.isPassenger()+" mayVisit="+TavernSeating.mayVisit(guest)
            +" validSite="+TavernSeating.valid(h.getLevel(),guest,seat.site())+" site="+seat.site();
        for(double t=0;t<=com.hearthstead.entity.TavernSeatMotion.TICKS;t+=.5) {
            var frame=com.hearthstead.entity.TavernSeatMotion.sample(seat.site(),t);
            StringBuilder reason=new StringBuilder();
            if(!com.hearthstead.entity.TavernSeatMotion.clear(guest,seat.site(),frame,reason::append)) {
                Hearthstead.LOGGER.error("HSQA_SEAT_ENTRY_REFUSAL actor={} {} firstRefusedTick={} reason={} frame={}",
                    guest.getUUID(),preconditions,t,reason,frame);
                return " [firstRefusedTick="+t+"; details: HSQA_SEAT_ENTRY_REFUSAL actor="+guest.getUUID()+"]";
            }
        }
        Hearthstead.LOGGER.error("HSQA_SEAT_ENTRY_REFUSAL actor={} {} allGeometrySamplesClear=true; inspect retry/startRiding",
            guest.getUUID(),preconditions);
        return " [geometry clear; details: HSQA_SEAT_ENTRY_REFUSAL actor="+guest.getUUID()+"]";
    }
    /** Read-only registered-goal history; never calls TavernVisitGoal.canUse(). */
    private static void captureWaitingVisitTransition(SettlerEntity guest, int tick,
                                                       String[] prior, String[] recent) {
        String state = waitingVisitState(guest);
        if (state.equals(prior[0])) return;
        prior[0] = state;
        recent[0] = recent[1];
        recent[1] = recent[2];
        recent[2] = shorten("t=" + tick + ",p=" + compactPos(guest.blockPosition())
            + ",a=" + guest.getActivity() + ",pass=" + guest.isPassenger()
            + ",yaw=" + Math.round(guest.yBodyRot * 10.0F) / 10.0F
            + ",nav=" + compactPath(guest.getNavigation().getPath()) + "," + state, 105);
    }
    private static String waitingVisitState(SettlerEntity guest) {
        String visit = "missing", join = "missing";
        StringBuilder running = new StringBuilder();
        for (WrappedGoal wrapped : guest.goalSelector.getAvailableGoals()) {
            if (wrapped.isRunning()) {
                if (running.length() > 0) running.append('+');
                running.append(wrapped.getGoal().getClass().getSimpleName().replace("Goal", ""));
            }
            if (wrapped.getGoal() instanceof com.hearthstead.entity.ai.TavernVisitGoal goal) {
                visit = (wrapped.isRunning() ? "run/" : "idle/") + goal.diagnosticState();
            } else if (wrapped.getGoal() instanceof com.hearthstead.entity.ai.TravelerJoinGoal) {
                join = wrapped.isRunning() ? "run" : "idle";
            }
        }
        return "visit=" + visit + ",join=" + join + ",goals=" + (running.isEmpty() ? "none" : running);
    }
    /** Failure-only entry snapshot: it reads the owned seat and movement state without probing entry. */
    private static String entryMotionDiagnostic(GameTestHelper h, SettlerEntity guest) {
        TavernSeatEntity seat = h.getLevel().getEntitiesOfClass(TavernSeatEntity.class,
            guest.getBoundingBox().inflate(32.0D), candidate -> !candidate.isRemoved()
                && candidate.owns(guest)).stream().findFirst().orElse(null);
        if (seat == null || seat.site() == null) return "none";
        Vec3 position = guest.position();
        Vec3 staging = com.hearthstead.entity.TavernSeatMotion.staging(seat.site());
        boolean sweptClear = h.getLevel().noCollision(guest,
            guest.getBoundingBox().expandTowards(staging.subtract(position)));
        boolean stagingClear = h.getLevel().noCollision(guest,
            guest.getDimensions(guest.getPose()).makeBoundingBox(staging));
        boolean firstFrameClear = com.hearthstead.entity.TavernSeatMotion.clear(guest,
            seat.site(), com.hearthstead.entity.TavernSeatMotion.sample(seat.site(), 0));
        float facing = seat.site().dinerFacing().toYRot();
        float yawError = net.minecraft.util.Mth.wrapDegrees(guest.yBodyRot - facing);
        return "p=" + precise(position) + ">s=" + precise(staging)
            + ",d2=" + concise(position.distanceToSqr(staging))
            + ",entry=" + seat.atEntry(guest) + ",yaw=" + concise(guest.yBodyRot)
            + ">" + concise(facing) + "/" + concise(yawError)
            + ",move=" + guest.getMoveControl().hasWanted() + "/"
            + precise(new Vec3(guest.getMoveControl().getWantedX(),
                guest.getMoveControl().getWantedY(), guest.getMoveControl().getWantedZ()))
            + "/" + concise(guest.getMoveControl().getSpeedModifier())
            + ",sweep=" + sweptClear + ",stage=" + stagingClear
            + ",motion0=" + firstFrameClear;
    }
    private static double concise(double value) {
        return Math.round(value * 1000.0D) / 1000.0D;
    }
    private static String precise(Vec3 value) {
        return concise(value.x) + "," + concise(value.y) + "," + concise(value.z);
    }

    private static String waitingVisitHistory(String[] recent) {
        StringBuilder out = new StringBuilder();
        for (String value : recent) {
            if (value == null || value.equals("-")) continue;
            if (out.length() > 0) out.append('>');
            out.append(value);
        }
        return out.isEmpty() ? "none" : shorten(out.toString(), 650);
    }
    private static String shorten(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, Math.max(0, limit - 1)) + "…";
    }
    /** Failure-only state snapshot: never reserves a chair or starts a movement goal. */
    private static String chairMountDiagnostic(GameTestHelper h, Fixture f, SettlerEntity guest,
                                               BlockPos relativeChair, BlockPos approach,
                                               int tick, int openedAt, String[] transitions,
                                               String lastOwnedEntry) {
        BlockPos chair = h.absolutePos(relativeChair);
        var state = h.getLevel().getBlockState(chair);
        Direction front = state.getBlock() instanceof StairBlock
            ? state.getValue(StairBlock.FACING).getOpposite() : null;
        BlockPos firstAisle = front == null ? null : chair.relative(front.getClockWise());
        BlockPos secondAisle = front == null ? null : chair.relative(front.getCounterClockWise());
        var firstSite = firstAisle == null ? null : TavernSeating.site(h.getLevel(), f.tavern(), chair, firstAisle);
        var secondSite = secondAisle == null ? null : TavernSeating.site(h.getLevel(), f.tavern(), chair, secondAisle);
        // PathNavigation.createPath updates targetPos even without moveTo.
        // Diagnostic probes must not replace the live visit/return destination.
        var probe = new com.hearthstead.entity.path.RoadNavigation(guest, h.getLevel());
        var firstPath = firstAisle == null ? null : probe.createPath(firstAisle, 0);
        var secondPath = secondAisle == null ? null : probe.createPath(secondAisle, 0);
        var activePath = guest.getNavigation().getPath();
        var host = f.host();
        String snapshot = "HSQA_WAITING_GUEST_CHAIR_DIAGNOSTIC tick=" + tick + " opened=" + openedAt
            + " gameTime=" + h.getLevel().getGameTime() + " dayTime=" + h.getLevel().getDayTime()
            + " guest=" + guest.getUUID() + " pos=" + guest.position()
            + " activity=" + guest.getActivity() + " traveler=" + guest.isTraveler()
            + " dayPhase=" + guest.dayPhase() + " hunger=" + guest.getHunger()
            + " energy=" + guest.getEnergy() + " hasMeal=" + guest.hasMeal()
            + " target=" + (guest.getTarget() == null ? "none" : guest.getTarget().getUUID())
            + " mayVisit=" + TavernSeating.mayVisit(guest)
            + " travelerJoinCanUse=" + new com.hearthstead.entity.ai.TravelerJoinGoal(guest).canUse()
            + " passenger=" + guest.isPassenger() + " approach=" + approach
            + " approachDistanceSq=" + guest.blockPosition().distSqr(approach)
            + " chair=" + chair + " chairState=" + state
            + " occupied=" + TavernSeating.occupied(h.getLevel(), chair)
            + " firstSite=" + firstSite + " firstClear="
                + (firstAisle != null && TavernSeating.clearStand(h.getLevel(), guest, firstAisle))
            + " firstValid=" + (firstSite != null && TavernSeating.valid(h.getLevel(), guest, firstSite))
            + " firstHostClear=" + (firstSite != null && TavernSeating.clearStand(h.getLevel(), guest, firstSite.hostApproach()))
            + " firstHostOverlap=" + (firstSite != null && occupiesStand(guest, host, firstSite.hostApproach()))
            + " firstPath=" + pathSummary(firstPath)
            + " secondSite=" + secondSite + " secondClear="
                + (secondAisle != null && TavernSeating.clearStand(h.getLevel(), guest, secondAisle))
            + " secondValid=" + (secondSite != null && TavernSeating.valid(h.getLevel(), guest, secondSite))
            + " secondHostClear=" + (secondSite != null && TavernSeating.clearStand(h.getLevel(), guest, secondSite.hostApproach()))
            + " secondHostOverlap=" + (secondSite != null && occupiesStand(guest, host, secondSite.hostApproach()))
            + " secondPath=" + pathSummary(secondPath)
            + " activePath=" + pathSummary(activePath)
            + " goalState=" + waitingVisitState(guest)
            + " tavern=id=" + f.tavern().id + ",valid=" + f.tavern().valid
                + ",bounds=" + f.tavern().bounds + ",workers=" + f.tavern().workers
                + ",tableClaimed=" + f.tavern().tavernServingClaims.occupied(chair.relative(front == null ? Direction.NORTH : front))
            + " host=" + host.getUUID() + ",alive=" + host.isAlive()
                + ",pos=" + host.position() + ",activity=" + host.getActivity()
                + ",target=" + (host.getTarget() == null ? "none" : host.getTarget().getUUID())
                + ",noAi=" + host.isNoAi() + ",profession=" + host.getProfession()
                + ",hasSession=" + com.hearthstead.settlement.work.TavernHostService.hasSession(host)
                + ",path=" + pathSummary(host.getNavigation().getPath())
                + ",employerMatches=" + (Employment.employerOf(f.village(), host.getUUID()) == f.tavern())
            + "]";
        Hearthstead.LOGGER.error("{} transitions={}", snapshot, waitingVisitHistory(transitions));
        // The full snapshot was absent from the last retained GameTest logs.
        // Keep the retained assertion itself compact
        // and include only read-only predicates that gate reserveReachable's first chair.
        String compact = "t=" + tick + ",p=" + compactPos(guest.blockPosition())
            + ",a=" + guest.getActivity() + ",phase=" + guest.dayPhase()
            + ",visit=" + TavernSeating.mayVisit(guest)
            + ",pay=" + com.hearthstead.settlement.work.TavernGuestPayment.bagAllowsVisit(guest)
            + ",joinCanUse=" + new com.hearthstead.entity.ai.TravelerJoinGoal(guest).canUse()
            + ",pass=" + guest.isPassenger() + ",approach=" + compactPos(approach)
            + ",d2=" + guest.blockPosition().distSqr(approach)
            + ",chair=" + state.getBlock().getDescriptionId()
            + ",occ=" + TavernSeating.occupied(h.getLevel(), chair)
            + ",claim=" + f.tavern().tavernServingClaims.occupied(
                chair.relative(front == null ? Direction.NORTH : front))
            + ",nav=" + compactPath(activePath)
            + ",goals=" + waitingVisitState(guest);
        // The failed assertion is persisted in a 1024-character GameTest book.
        // Preserve the final fractional movement state and three compact transitions.
        return " [HSQA_WAITING_GUEST_CHAIR_DIAGNOSTIC "
            + shorten(compact, 120) + " lastEntry=" + shorten(lastOwnedEntry, 360)
            + " hist=" + shorten(waitingVisitHistory(transitions), 250) + "]";
    }

    private static String compactSite(GameTestHelper h, SettlerEntity guest, SettlerEntity host,
                                      TavernSeating.SeatSite site,
                                      net.minecraft.world.level.pathfinder.Path path) {
        if (site == null) return "none";
        return "v=" + TavernSeating.valid(h.getLevel(), guest, site)
            + ",a=" + TavernSeating.clearStand(h.getLevel(), guest, site.aisle())
            + ",h=" + TavernSeating.clearStand(h.getLevel(), guest, site.hostApproach())
            + ",ho=" + occupiesStand(guest, host, site.hostApproach())
            + ",p=" + compactPath(path);
    }
    private static String compactHost(SettlerEntity host, Fixture f) {
        if (host == null) return "none";
        return compactPos(host.blockPosition()) + ",a=" + host.getActivity()
            + ",target=" + (host.getTarget() != null) + ",noAi=" + host.isNoAi()
            + ",session=" + com.hearthstead.settlement.work.TavernHostService.hasSession(host)
            + ",employer=" + (Employment.employerOf(f.village(), host.getUUID()) == f.tavern())
            + ",path=" + compactPath(host.getNavigation().getPath());
    }
    private static String compactPos(BlockPos pos) {
        return pos == null ? "none" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
    private static boolean occupiesStand(SettlerEntity guest, SettlerEntity host, BlockPos stand) {
        return host != null && host.getBoundingBox().intersects(guest.getDimensions(guest.getPose())
            .makeBoundingBox(Vec3.atBottomCenterOf(stand)));
    }
    private static String compactPath(net.minecraft.world.level.pathfinder.Path path) {
        return path == null ? "none" : (path.canReach() ? "reach" : "partial")
            + "/" + (path.isDone() ? "done" : "moving")
            + "/" + path.getNextNodeIndex() + "/" + path.getNodeCount();
    }
    private static String pathSummary(net.minecraft.world.level.pathfinder.Path path) {
        return path == null ? "null" : "canReach=" + path.canReach() + ",done=" + path.isDone()
            + ",next=" + path.getNextNodeIndex() + "/" + path.getNodeCount()
            + ",target=" + path.getTarget() + ",end=" + path.getEndNode();
    }
    private static TavernSeatEntity mountAtAisle(GameTestHelper h, Fixture f) {
        TavernSeatEntity seat = reserve(h, f);
        f.guest().setPos(Vec3.atBottomCenterOf(seat.site().aisle()));
        h.assertTrue(seat.mount(f.guest()), "contact at the reserved aisle must mount");
        return seat;
    }
    @GameTest(batch = "tavern_seating", template = "empty16", timeoutTicks = 240)
    public void residentWalksToActualStairAndOccupiesOneSeat(GameTestHelper h) {
        Fixture f = fixture(h);
        Vec3 start = f.guest().position();
        h.succeedWhen(() -> {
            h.assertTrue(TavernSeating.hasTavernSeat(f.guest()), "ordinary registered visit goal must physically arrive and sit");
            h.assertTrue(((TavernSeatEntity)f.guest().getVehicle()).isSettled(), "ordinary goal completes the articulated entry before the visit is ready");
            h.assertTrue(start.distanceTo(f.guest().position()) > 2, "fixture starts away from chair");
            h.assertTrue(f.guest().getVehicle().getPassengers().size() == 1, "exactly one resident occupies chair");
            h.assertTrue(TavernSeating.currentTavern(f.guest()) == f.tavern(), "seat keeps exact workplace identity");
        });
    }

    /** The furniture jar is optional at compile time; installed runtime coverage uses the actual registry blocks. */
    @GameTest(batch = "tavern_compat", template = "empty16", timeoutTicks = 100)
    public void anotherFurnitureChairAndTableReserveAndSeatWhenInstalled(GameTestHelper h) {
        Fixture f = fixture(h);
        Block chair = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("another_furniture", "oak_chair"));
        Block table = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("another_furniture", "oak_table"));
        if (chair == Blocks.AIR || table == Blocks.AIR) { h.succeed(); return; }

        BlockPos chairPos = new BlockPos(6, 1, 7);
        BlockPos tablePos = chairPos.north();
        h.setBlock(chairPos, chair.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        h.setBlock(tablePos, table.defaultBlockState());
        h.setBlock(tablePos.above(), Blocks.AIR);
        var site = TavernSeating.site(h.getLevel(), f.tavern(), h.absolutePos(chairPos), h.absolutePos(chairPos.east()));
        h.assertTrue(site != null && TavernSeating.isAnotherFurnitureChair(h.getLevel().getBlockState(site.chair()))
                && TavernSeating.isAnotherFurnitureTable(h.getLevel().getBlockState(site.table()))
                && site.table().equals(h.absolutePos(tablePos))
                && Math.abs(site.tabletopCenter(h.getLevel()).y - (site.table().getY() + 1)) < .00001,
            "a documented Another Furniture chair/table pair must expose its exact server table and actual tabletop height");

        TavernSeatEntity seat = ModEntities.TAVERN_SEAT.get().create(h.getLevel());
        h.assertTrue(seat != null, "physical Hearthstead seat entity must exist");
        seat.reserve(f.guest(), site);
        h.assertTrue(h.getLevel().addFreshEntity(seat), "one optional-furniture chair must receive the ordinary server reservation");
        Vec3 staging = com.hearthstead.entity.TavernSeatMotion.staging(site);
        f.guest().setPos(staging); f.guest().setYRot(site.dinerFacing().toYRot()); f.guest().setYBodyRot(f.guest().getYRot());
        StringBuilder collision = new StringBuilder();
        boolean clearMotion = true;
        for (double tick = 0; tick <= com.hearthstead.entity.TavernSeatMotion.TICKS; tick += .5) {
            if (!com.hearthstead.entity.TavernSeatMotion.clear(f.guest(), site,
                    com.hearthstead.entity.TavernSeatMotion.sample(site, tick), collision::append)) {
                clearMotion = false; collision.insert(0, "tick=" + tick + " "); break;
            }
        }
        h.assertTrue(TavernSeating.valid(h.getLevel(), f.guest(), site) && clearMotion,
            "installed furniture must retain live site validity and clear every physical entry frame: " + collision);
        h.assertTrue(seat.beginEntry(f.guest()), "the complete physical entry motion must clear the installed chair, table and aisle");
        h.runAfterDelay(com.hearthstead.entity.TavernSeatMotion.TICKS + 2, () -> {
            h.assertTrue(seat.isSettled() && seat.owns(f.guest()) && f.guest().getVehicle() == seat
                    && !f.tavern().tavernServingClaims.occupied(site.table()),
                "the installed furniture keeps the exact chair reservation and leaves its real table available to Tavern service");
            h.assertTrue(f.guest().position().distanceToSqr(com.hearthstead.entity.TavernSeatMotion.seatedAnchor(site)) < .000001,
                "the physical final frame must land on the saved Another Furniture chair anchor without a post-animation snap");
            h.succeed();
        });
    }
    /** A 4.0.2 bench remains one physical SeatBlock cell; this room leaves its straight host lane empty. */
    @GameTest(batch = "tavern_compat", template = "empty16", timeoutTicks = 100)
    public void anotherFurnitureBenchAndTableReserveAndSeatWhenInstalled(GameTestHelper h) {
        Fixture f = fixture(h);
        Block bench = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("another_furniture", "oak_bench"));
        Block table = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("another_furniture", "oak_table"));
        if (bench == Blocks.AIR || table == Blocks.AIR) { h.succeed(); return; }

        BlockPos benchPos = new BlockPos(6, 1, 7);
        BlockPos tablePos = benchPos.north();
        h.setBlock(benchPos, bench.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        h.setBlock(tablePos, table.defaultBlockState());
        h.setBlock(tablePos.above(), Blocks.AIR);
        var site = TavernSeating.site(h.getLevel(), f.tavern(), h.absolutePos(benchPos), h.absolutePos(benchPos.east()));
        h.assertTrue(site != null && TavernSeating.isAnotherFurnitureBench(h.getLevel().getBlockState(site.chair()))
                && site.furniture() == TavernSeating.Furniture.ANOTHER_FURNITURE
                && site.table().equals(h.absolutePos(tablePos)),
            "a documented Another Furniture bench/table pair uses the existing physical seat frame only with a real straight service lane");

        TavernSeatEntity seat = ModEntities.TAVERN_SEAT.get().create(h.getLevel());
        h.assertTrue(seat != null, "physical Hearthstead seat entity must exist");
        seat.reserve(f.guest(), site);
        h.assertTrue(h.getLevel().addFreshEntity(seat), "one bench cell receives the ordinary server reservation");
        Vec3 staging = com.hearthstead.entity.TavernSeatMotion.staging(site);
        f.guest().setPos(staging); f.guest().setYRot(site.dinerFacing().toYRot()); f.guest().setYBodyRot(f.guest().getYRot());
        StringBuilder collision = new StringBuilder();
        for (double tick = 0; tick <= com.hearthstead.entity.TavernSeatMotion.TICKS; tick += .5)
            h.assertTrue(com.hearthstead.entity.TavernSeatMotion.clear(f.guest(), site,
                com.hearthstead.entity.TavernSeatMotion.sample(site, tick), collision::append),
                "bench entry must clear every actual collision frame at tick=" + tick + " " + collision);
        h.assertTrue(TavernSeating.valid(h.getLevel(), f.guest(), site) && seat.beginEntry(f.guest()),
            "the bench reservation enters only through its physical aisle and unchanged straight host lane");
        h.runAfterDelay(com.hearthstead.entity.TavernSeatMotion.TICKS + 2, () -> {
            h.assertTrue(seat.isSettled() && seat.owns(f.guest()) && f.guest().getVehicle() == seat,
                "the bench ends on the durable Another Furniture seat anchor");
            h.assertTrue(f.guest().position().distanceToSqr(com.hearthstead.entity.TavernSeatMotion.seatedAnchor(site)) < .000001,
                "no post-animation snap or synthetic bench height is introduced");
            h.succeed();
        });
    }
    @GameTest(batch = "tavern_seating", template = "empty16")
    public void reservationRejectsSecondResidentAndRemoteMount(GameTestHelper h) {
        Fixture f = fixture(h); TavernSeatEntity seat = reserve(h, f);
        Vec3 before = f.guest().position();
        h.assertTrue(!seat.mount(f.guest()) && before.equals(f.guest().position()), "remote mount must not teleport to chair");
        SettlerEntity second = resident(h, f.village(), new BlockPos(10, 1, 11));
        h.assertTrue(TavernSeating.reserveReachable(second).seat() == null, "claimed chair must not be double booked");
        second.setPos(Vec3.atBottomCenterOf(seat.site().aisle()));
        h.assertTrue(!seat.mount(second), "another resident cannot consume reservation");
        seat.release();
        h.assertTrue(TavernSeating.reserveReachable(second).seat() != null, "released chair becomes available");
        h.succeed();
    }
    @GameTest(batch = "tavern_seating", template = "empty16")
    public void enclosedResidentCannotReserveUnreachableFurniture(GameTestHelper h) {
        Fixture f = fixture(h);
        for (Direction d : Direction.Plane.HORIZONTAL) for (int y = 1; y <= 3; y++)
            h.setBlock(new BlockPos(10, y, 10).relative(d), Blocks.STONE_BRICKS);
        var result = TavernSeating.reserveReachable(f.guest());
        h.assertTrue(result.attemptedPaths() > 0 && result.seat() == null, "actual failed paths must create no reservation");
        h.assertTrue(!TavernSeating.occupied(h.getLevel(), h.absolutePos(new BlockPos(6, 1, 7))), "no ghost seat after path failure");
        h.succeed();
    }
    @GameTest(batch = "tavern_seating", template = "empty16")
    public void furnitureRemovalDismountsIntoClearLocalAisle(GameTestHelper h) {
        Fixture f = fixture(h); TavernSeatEntity seat = mountAtAisle(h, f);
        Vec3 before = f.guest().position();
        h.setBlock(new BlockPos(6, 1, 7), Blocks.AIR);
        seat.tick();
        h.assertTrue(!f.guest().isPassenger() && seat.isRemoved(), "removed furniture must release its actual passenger");
        h.assertTrue(before.distanceTo(f.guest().position()) < 2 && h.getLevel().noCollision(f.guest()), "dismount must remain local and collision-free");
        h.succeed();
    }
    @GameTest(batch = "tavern_seating", template = "empty16")
    public void obstructedChairHeadroomRejectsAndReleasesWithoutConsumingMeal(GameTestHelper h) {
        Fixture f = fixture(h);
        BlockPos overChair = new BlockPos(6, 2, 7);
        h.setBlock(overChair, Blocks.STONE_BRICKS);
        var blocked = TavernSeating.reserveReachable(f.guest());
        h.assertTrue(blocked.seat() == null
            && !TavernSeating.occupied(h.getLevel(), h.absolutePos(new BlockPos(6, 1, 7))),
            "clear aisles do not make a head-obstructed chair safe to reserve");

        h.setBlock(overChair, Blocks.AIR);
        TavernSeatEntity seat = reserve(h, f);
        f.guest().setPos(Vec3.atBottomCenterOf(seat.site().aisle()));
        Vec3 aislePosition = f.guest().position();
        h.setBlock(overChair, Blocks.STONE_BRICKS);
        h.assertTrue(!seat.mount(f.guest()) && !f.guest().isPassenger()
            && f.guest().position().equals(aislePosition),
            "obstruction after reservation must reject mount without moving resident");

        h.setBlock(overChair, Blocks.AIR);
        h.assertTrue(seat.mount(f.guest()), "the intended lower-body/stair overlap must remain allowed");
        f.guest().setHunger(50);
        f.guest().bag.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD));
        h.assertTrue(f.guest().beginMeal(f.guest().bag.getItem(0)) && f.guest().bag.isEmpty(),
            "one real carried bread must enter the durable meal");
        float hungerBefore = f.guest().getHunger();
        float healthBefore = f.guest().getHealth();
        h.setBlock(overChair, Blocks.STONE_BRICKS);
        seat.tick();
        h.assertTrue(!f.guest().isPassenger() && seat.isRemoved()
            && h.getLevel().noCollision(f.guest()),
            "new seated head obstruction must release into the clear local aisle");
        h.assertTrue(f.guest().hasMeal() && f.guest().bag.isEmpty()
            && f.guest().getHunger() == hungerBefore && f.guest().getHealth() == healthBefore,
            "seat invalidation neither consumes/refunds the owned meal nor requires suffocation damage");
        h.succeed();
    }
    @GameTest(batch = "tavern_seating", template = "empty16")
    public void alarmEndsSeatButUnstaffedSocialVisitRemainsAllowed(GameTestHelper h) {
        Fixture f = fixture(h); TavernSeatEntity seat = mountAtAisle(h, f);
        f.village().alertUntilGameTime = h.getLevel().getGameTime() + 100;
        seat.tick();
        h.assertTrue(!f.guest().isPassenger() && seat.isRemoved() && f.guest().bag.isEmpty(), "danger leaves chair without food or item effects");
        f.village().alertUntilGameTime = 0;
        f.tavern().workers.clear();
        h.assertTrue(TavernSeating.reserveReachable(f.guest()).seat() != null,
            "unstaffed Tavern must still allow an ordinary social visit");
        h.succeed();
    }
    @GameTest(batch = "tavern_seating", template = "empty16", timeoutTicks = 40)
    public void savedVehicleRestoresSameResidentAndDoesNotRenewDeadline(GameTestHelper h) {
        Fixture f = fixture(h); TavernSeatEntity seat = mountAtAisle(h, f);
        CompoundTag saved = new CompoundTag();
        h.assertTrue(seat.save(saved) && saved.getList("Passengers", 10).size() == 1, "vehicle save must include real resident NBT");
        UUID seatId = seat.getUUID(), dinerId = f.guest().getUUID();
        long deadline = saved.getLong("Expires");
        f.guest().discard(); seat.discard();
        h.runAfterDelay(2, () -> {
            var loaded = EntityType.loadEntityRecursive(saved, h.getLevel(), e -> e);
            h.assertTrue(loaded instanceof TavernSeatEntity, "registered seat factory must decode saved vehicle");
            h.getLevel().addFreshEntityWithPassengers(loaded);
            TavernSeatEntity restored = (TavernSeatEntity) loaded;
            h.assertTrue(restored.getUUID().equals(seatId) && restored.getPassengers().size() == 1
                && restored.getFirstPassenger().getUUID().equals(dinerId), "loaded exact actor and mount identities must match");
            CompoundTag again = new CompoundTag(); restored.save(again);
            h.assertTrue(again.getLong("Expires") == deadline, "reload cannot restart visit clock");
            restored.tick();
            h.assertTrue(!restored.isRemoved() && restored.getFirstPassenger() instanceof SettlerEntity, "valid loaded mount remains seated");
            h.assertTrue(restored.release() && !restored.isVehicle(), "loaded passenger can safely stand without duplication");
            h.succeed();
        });
    }
    @GameTest(batch = "tavern_seating", template = "empty16")
    public void oversizedSavedRoomStillUsesBoundedDiscovery(GameTestHelper h) {
        Fixture f = fixture(h);
        h.setBlock(new BlockPos(6, 1, 7), Blocks.AIR);
        f.tavern().bounds = BoundingBox.fromCorners(h.absolutePos(new BlockPos(0, 1, 0)), h.absolutePos(new BlockPos(15, 15, 15)));
        var search = TavernSeating.reserveReachable(f.guest());
        h.assertTrue(search.seat() == null && search.scannedCells() == TavernSeating.MAX_SCAN_CELLS
            && search.attemptedPaths() <= TavernSeating.MAX_PATHS, "large/corrupt bounds cannot remove discovery budget");
        h.succeed();
    }
    @GameTest(batch = "tavern_seating", template = "empty16", timeoutTicks = 960)
    public void mealFinishesWhileSeatedAndVisitEventuallyEnds(GameTestHelper h) {
        Fixture f = fixture(h); TavernSeatEntity seat = mountAtAisle(h, f);
        // Pin the visit to the seat's own saved deadline (600 ticks, or x1.5 with a WELCOMING
        // member) instead of a hard-coded tick, so trait rolls cannot move the check.
        CompoundTag saved = new CompoundTag(); seat.save(saved);
        long visit = saved.getLong("Expires") - h.getLevel().getGameTime();
        h.assertTrue(visit == com.hearthstead.settlement.TavernSeating.VISIT_TICKS
            || visit == Math.round(com.hearthstead.settlement.TavernSeating.VISIT_TICKS
                * com.hearthstead.entity.AttributeEffects.WELCOME_TAVERN_STAY), "bounded visit length, got " + visit);
        f.guest().setHunger(50);
        f.guest().bag.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD));
        h.assertTrue(f.guest().beginMeal(f.guest().bag.getItem(0)) && f.guest().bag.isEmpty(), "real owned bread must enter durable meal exactly once");
        h.runAfterDelay(50, () -> {
            h.assertTrue(f.guest().getVehicle() == seat && !f.guest().hasMeal()
                && f.guest().getHunger() > 80, "ordinary mounted goal must complete real meal without leaving chair");
        });
        h.runAfterDelay(visit + 5, () -> {
            h.assertTrue(seat.isRemoved() && !f.guest().isPassenger()
                && f.guest().bag.isEmpty(), "bounded visit ends without inventing or refunding bread"
                + " (seatRemoved=" + seat.isRemoved() + " passenger=" + f.guest().isPassenger()
                + " bagEmpty=" + f.guest().bag.isEmpty() + " visit=" + visit + ")");
            h.succeed();
        });
    }
    /** Tavern lane: an empty store is told plainly ("No food at the Tavern") and never keeps a guest. */
    @GameTest(batch = "tavern_seating", template = "empty16", timeoutTicks = 960)
    public void emptyTavernStoreShowsNoFoodAndTheVisitStillEnds(GameTestHelper h) {
        Fixture f = fixture(h); TavernSeatEntity seat = mountAtAisle(h, f);
        var level = h.getLevel();
        f.guest().setHunger(50);
        h.assertTrue(com.hearthstead.entity.LifeNeed.compute(f.guest(), level, 0L)
            == com.hearthstead.entity.LifeNeed.TAVERN_NO_FOOD, "hungry guest at an empty staffed Tavern: No food at the Tavern");
        f.guest().setHunger(90);
        h.assertTrue(com.hearthstead.entity.LifeNeed.compute(f.guest(), level, 0L)
            != com.hearthstead.entity.LifeNeed.TAVERN_NO_FOOD, "a guest who would not be served is not told");
        f.guest().setHunger(50);
        BlockPos store = new BlockPos(10, 1, 3);
        h.setBlock(store, Blocks.CHEST);
        var chest = (net.minecraft.world.Container) level.getBlockEntity(h.absolutePos(store));
        chest.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD, 4));
        chest.setItem(1, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GLASS_BOTTLE, 2));
        h.assertTrue(com.hearthstead.entity.LifeNeed.compute(f.guest(), level, 0L)
            != com.hearthstead.entity.LifeNeed.TAVERN_NO_FOOD, "a meal and a bottle in one Tavern chest clears the status");
        chest.clearContent();
        CompoundTag saved = new CompoundTag(); seat.save(saved);
        long visit = saved.getLong("Expires") - level.getGameTime();
        h.runAfterDelay(visit + 5, () -> {
            h.assertTrue(seat.isRemoved() && !f.guest().isPassenger(), "no food never keeps a guest in the chair");
            h.succeed();
        });
    }
    /** Tavern lane: two ales in, the seated meal still finishes (drunk cues only fire while walking). */
    @GameTest(batch = "tavern_drunk", template = "empty16", timeoutTicks = 200)
    public void drunkGuestStillFinishesASeatedTavernMeal(GameTestHelper h) {
        Fixture f = fixture(h);
        long now = h.getLevel().getGameTime();
        f.guest().markAleDrunk(now, 2400);
        f.guest().markAleDrunk(now, 2400);
        h.assertTrue(f.guest().drunkLevel() == com.hearthstead.entity.Drunkenness.DRUNK, "two ales: drunk");
        TavernSeatEntity seat = mountAtAisle(h, f);
        f.guest().setHunger(50);
        f.guest().bag.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD));
        h.assertTrue(f.guest().beginMeal(f.guest().bag.getItem(0)), "the drunk guest starts the meal");
        h.runAfterDelay(60, () -> {
            h.assertTrue(f.guest().getVehicle() == seat && !f.guest().hasMeal() && f.guest().getHunger() > 80,
                "the drunk guest finishes the meal in the chair");
            h.assertTrue(f.guest().drunkCueMode() == com.hearthstead.entity.Drunkenness.CUE_NONE,
                "no stumble / fall / lean / sit while seated");
            h.succeed();
        });
    }
    @GameTest(batch = "tavern_seat_motion", template = "empty16", timeoutTicks = 120)
    public void actualEntryReloadAndNormalExitKeepLeaseFeetAndIdentity(GameTestHelper h) {
        Fixture f=fixture(h); f.guest().setNoAi(true); f.host().setNoAi(true);
        BlockPos source=h.absolutePos(new BlockPos(3,1,5));
        h.getLevel().setBlockAndUpdate(source,Blocks.BARREL.defaultBlockState());
        var stock=(net.minecraft.world.Container)h.getLevel().getBlockEntity(source);
        stock.setItem(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD));
        stock.setItem(1,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GLASS_BOTTLE));
        com.hearthstead.entity.TavernServingEntity[] serving={null};
        TavernSeatEntity[] seat={reserve(h,f)};
        Vec3 staging=com.hearthstead.entity.TavernSeatMotion.staging(seat[0].site());
        f.guest().setPos(staging); f.guest().setYBodyRot(seat[0].site().dinerFacing().toYRot());
        f.guest().setYRot(f.guest().yBodyRot);
        var originalSite=seat[0].site();
        var oppositeAisle=originalSite.chair().offset(
            originalSite.chair().getX()-originalSite.aisle().getX(),0,
            originalSite.chair().getZ()-originalSite.aisle().getZ());
        var oppositeSite=new TavernSeating.SeatSite(originalSite.tavernId(),originalSite.chair(),
            originalSite.table(),originalSite.dinerFacing(),oppositeAisle,originalSite.hostApproach());
        for(var site:java.util.List.of(originalSite,oppositeSite)) {
            int samples=0;
            for(double tick=0;tick<=com.hearthstead.entity.TavernSeatMotion.TICKS;tick+=.5) {
                var sampled=com.hearthstead.entity.TavernSeatMotion.sample(site,tick);
                h.assertTrue(com.hearthstead.entity.TavernSeatMotion.clear(f.guest(),site,sampled),
                    "complete real stair/table/floor corridor must clear at half-tick="+tick+" aisle="+site.aisle());
                double floor=site.chair().getY();
                h.assertTrue(Math.min(sampled.rightFoot().y,sampled.leftFoot().y)<=floor+.00001
                    && sampled.rightFoot().y>=floor-.00001 && sampled.leftFoot().y>=floor-.00001,
                    "each sampled placement keeps a real sole on the floor without sinking");
                samples++;
            }
            h.assertTrue(samples==com.hearthstead.entity.TavernSeatMotion.TICKS*2+1,
                "the real clearance predicate covers every half-tick from both aisles");
            var previous=com.hearthstead.entity.TavernSeatMotion.sample(site,0);
            for(int subTick=1;subTick<=com.hearthstead.entity.TavernSeatMotion.TICKS*100;subTick++) {
                var current=com.hearthstead.entity.TavernSeatMotion.sample(site,subTick/100.0);
                h.assertTrue(current!=null,"fine-grained real motion remains reachable");
                for(boolean left: new boolean[]{false,true}) {
                    Vec3 oldKnee=com.hearthstead.entity.TavernSeatMotion.kneePosition(previous,left);
                    Vec3 newKnee=com.hearthstead.entity.TavernSeatMotion.kneePosition(current,left);
                    h.assertTrue(oldKnee.distanceToSqr(newKnee)<.000025,
                        "actual knee cannot switch circle branches at sub-tick="+subTick+" left="+left);
                    Vec3 oldFoot=left?previous.leftFoot():previous.rightFoot();
                    Vec3 newFoot=left?current.leftFoot():current.rightFoot();
                    if(oldFoot.y<=site.chair().getY()+.000001 && newFoot.y<=site.chair().getY()+.000001)
                        h.assertTrue(oldFoot.distanceToSqr(newFoot)<.000001,
                            "the support sole stays planted rather than following pelvis weight transfer");
                }
                previous=current;
            }
        }
        boolean entered=seat[0].beginEntry(f.guest());
        h.assertTrue(entered && !seat[0].isSettled(), "real corridor begins an owned unfinished entry"
            +(entered?"":entryFailure(h,f.guest(),seat[0])));
        h.assertTrue(!com.hearthstead.settlement.work.TavernHostService.validGuest(f.host(),f.guest(),seat[0].site()),
            "host cannot transfer food to an unsettled resident");
        UUID seatId=seat[0].getUUID(), guestId=f.guest().getUUID();
        boolean[] restored={false}, exiting={false}, loading={false};
        int[] ownedMealTicks={0};
        long[] exitRetryAt={-1};
        h.onEachTick(() -> {
            if(loading[0]) return;
            if(seat[0].isRemoved()) {
                h.assertTrue(restored[0] && exiting[0], "normal exit follows real reload and settled entry");
                var guest=(SettlerEntity)h.getLevel().getEntity(guestId);
                h.assertTrue(guest!=null && !guest.isPassenger() && guest.position().distanceToSqr(staging)<.0009
                    && h.getLevel().noCollision(guest), "same resident exits at the actual checked staging floor");
                h.assertTrue(guest.hasMeal() && guest.mealRemainingTicks()==ownedMealTicks[0] && guest.bag.isEmpty(),
                    "normal exit preserves the one owned unfinished meal without a second item or consumption");
                h.assertTrue(serving[0]!=null && !serving[0].isRemoved() && serving[0].hasCargo()
                    && serving[0].displayFood().is(net.minecraft.world.item.Items.BREAD) && serving[0].displayFood().getCount()==1
                    && serving[0].displayGlass().is(net.minecraft.world.item.Items.GLASS_BOTTLE) && serving[0].displayGlass().getCount()==1
                    && stock.isEmpty() && com.hearthstead.settlement.work.TavernHostService.current(f.host())==serving[0],
                    "pending return retains the exact separate stocked food and bottle under the same live host session");
                h.succeed(); return;
            }
            var frame=seat[0].motionFrame(1);
            if(seat[0].isTransitioning()) {
                var guest=(SettlerEntity)seat[0].getFirstPassenger();
                h.assertTrue(!com.hearthstead.settlement.work.TavernHostService.validGuest(f.host(),guest,seat[0].site()),
                    "neither stepping phase is eligible for host handoff");
                var visit=new com.hearthstead.entity.ai.TavernVisitGoal(guest);
                h.assertTrue(visit.canUse(),"the real visit goal rebinds its current passenger");
                int beforeMeal=guest.mealRemainingTicks(); visit.tick();
                h.assertTrue(guest.mealRemainingTicks()==beforeMeal,"the actual visit goal pauses meal progression while stepping");
                double floor=seat[0].site().chair().getY();
                h.assertTrue(frame!=null && Math.min(frame.rightFoot().y,frame.leftFoot().y)<=floor+.00001
                    && frame.rightFoot().y>=floor-.00001 && frame.leftFoot().y>=floor-.00001,
                    "one actual foot stays supported while the other moves, without floor penetration");
            }
            CompoundTag saved=new CompoundTag(); seat[0].save(saved);
            if(!restored[0] && saved.getCompound("Motion").getInt("Tick")==10) {
                long deadline=saved.getLong("Expires"); Vec3 before=seat[0].getFirstPassenger().position();
                var passengerPos=saved.getList("Passengers",10).getCompound(0).getList("Pos",6);
                h.assertTrue(passengerPos.size()==3
                    && passengerPos.getDouble(0)==seat[0].getX() && passengerPos.getDouble(2)==seat[0].getZ()
                    && before.distanceToSqr(seat[0].position())>.01,
                    "real vanilla save uses fixed vehicle X/Z while the owned passenger is still moving");
                seat[0].getFirstPassenger().discard(); seat[0].discard(); loading[0]=true;
                h.runAfterDelay(2,() -> {
                    var loaded=EntityType.loadEntityRecursive(saved,h.getLevel(),e -> e);
                    h.assertTrue(loaded instanceof TavernSeatEntity,"actual saved vehicle decodes");
                    h.getLevel().addFreshEntityWithPassengers(loaded); seat[0]=(TavernSeatEntity)loaded;
                    CompoundTag again=new CompoundTag(); seat[0].save(again);
                    h.assertTrue(seat[0].getUUID().equals(seatId) && seat[0].getPassengers().size()==1
                        && seat[0].getFirstPassenger().getUUID().equals(guestId),
                        "reload preserves exact vehicle and sole passenger identities");
                    h.assertTrue(again.getLong("Expires")==deadline,
                        "reload preserves original deadline without renewing the visit");
                    h.assertTrue(again.getCompound("Motion").getInt("Phase")==1
                        && again.getCompound("Motion").getInt("Tick")==10,
                        "recursive attachment must not advance the saved entry clock");
                    h.assertTrue(seat[0].getFirstPassenger().position().distanceToSqr(before)<.000001,
                        "reload restores the exact moving rider position before any world tick: expected="+before
                            +" actual="+seat[0].getFirstPassenger().position());
                    restored[0]=true; loading[0]=false;
                });
            } else if(restored[0] && seat[0].isSettled() && !exiting[0]) {
                var guest=(SettlerEntity)seat[0].getFirstPassenger();
                if(exitRetryAt[0]<0) {
                guest.setHunger(50);
                serving[0]=com.hearthstead.settlement.work.TavernHostService.pickup(f.host(),guest,f.tavern(),source);
                h.assertTrue(serving[0]!=null && stock.isEmpty(),"a fully seated diner allows real two-item source pickup");
                guest.bag.setItem(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD));
                h.assertTrue(guest.beginMeal(guest.bag.getItem(0)),"one owned item starts a real meal before departure");
                ownedMealTicks[0]=guest.mealRemainingTicks();
                BlockPos obstacle=seat[0].site().aisle().above();
                h.getLevel().setBlockAndUpdate(obstacle,Blocks.STONE_BRICKS.defaultBlockState());
                h.assertTrue(!seat[0].requestNormalExit(),"real exit obstruction refuses the full route");
                h.getLevel().setBlockAndUpdate(obstacle,Blocks.AIR.defaultBlockState());
                exitRetryAt[0]=h.getLevel().getGameTime()+20;
                }
                if(h.getLevel().getGameTime()<exitRetryAt[0]) {
                    h.assertTrue(!seat[0].requestNormalExit() && seat[0].isSettled() && guest.getVehicle()==seat[0]
                        && guest.mealRemainingTicks()==ownedMealTicks[0],
                        "cleared exit still observes the real twenty-tick retry gate without losing passenger or meal");
                    return;
                }
                h.assertTrue(seat[0].requestNormalExit(),"same checked corridor supports an ordinary stand-up at the retry boundary");
                serving[0].serviceTick(f.host());
                h.assertTrue(serving[0].hasCargo() && serving[0].phase()==com.hearthstead.entity.TavernServingEntity.Phase.RETURNING,
                    "actual unfinished carrying session returns its cargo when the diner starts leaving, without handoff");
                exiting[0]=true;
            }
        });
    }
    @GameTest(batch = "tavern_seat_motion", template = "empty16", timeoutTicks = 60)
    public void blockedEntryRefusesAndAlarmCancelsActualMotionWithoutFoodMutation(GameTestHelper h) {
        Fixture f=fixture(h); f.guest().setNoAi(true); TavernSeatEntity seat=reserve(h,f);
        Vec3 staging=com.hearthstead.entity.TavernSeatMotion.staging(seat.site());
        f.guest().setPos(staging); f.guest().setYBodyRot(seat.site().dinerFacing().toYRot());
        f.guest().setYRot(f.guest().yBodyRot);
        BlockPos obstruction=seat.site().aisle().above();
        h.getLevel().setBlockAndUpdate(obstruction,Blocks.STONE_BRICKS.defaultBlockState());
        h.assertTrue(!seat.beginEntry(f.guest()) && !f.guest().isPassenger() && f.guest().position().equals(staging),
            "real obstruction rejects entry before passenger or motion mutation");
        h.getLevel().setBlockAndUpdate(obstruction,Blocks.AIR.defaultBlockState());
        var observedFrame=com.hearthstead.entity.TavernSeatMotion.sample(seat.site(),7);
        h.assertTrue(com.hearthstead.entity.TavernSeatMotion.clear(f.guest(),seat.site(),observedFrame),
            "actual tick7 rotated mesh must clear the real stair, including its separate solid components");
        BlockPos occupiedBody=BlockPos.containing(observedFrame.origin().add(0,.8,0));
        var priorBodyBlock=h.getLevel().getBlockState(occupiedBody);
        h.getLevel().setBlockAndUpdate(occupiedBody,Blocks.STONE_BRICKS.defaultBlockState());
        h.assertTrue(!com.hearthstead.entity.TavernSeatMotion.clear(f.guest(),seat.site(),observedFrame),
            "the same unchanged mesh must reject real solid material inside its actual torso volume");
        h.getLevel().setBlockAndUpdate(occupiedBody,priorBodyBlock);
        h.runAfterDelay(21,() -> {
            float facing=seat.site().dinerFacing().toYRot();
            f.guest().setYRot(facing); f.guest().setYBodyRot(facing); f.guest().setYHeadRot(facing);
            h.assertTrue(seat.atEntry(f.guest()),"same unmoved fixture resident reestablishes actual facing before retry");
            boolean entered=seat.beginEntry(f.guest());
            h.assertTrue(entered,"unobstructed actual path can begin"+(entered?"":entryFailure(h,f.guest(),seat)));
            f.guest().setHunger(50);
            f.guest().bag.setItem(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD));
            h.assertTrue(f.guest().beginMeal(f.guest().bag.getItem(0)),"one physical bread enters existing durable meal authority");
            int remaining=f.guest().mealRemainingTicks();
            h.runAfterDelay(5,() -> {
                h.assertTrue(seat.isTransitioning() && !seat.isSettled(),"alarm interrupts an actual in-progress transition");
                f.village().alertUntilGameTime=h.getLevel().getGameTime()+100;
                seat.tick();
                h.assertTrue(seat.isRemoved() && !f.guest().isPassenger() && h.getLevel().noCollision(f.guest())
                    && f.guest().hasMeal() && f.guest().mealRemainingTicks()==remaining && f.guest().bag.isEmpty(),
                    "alarm retains immediate local safety and exact pending food ownership");
                h.succeed();
            });
        });
    }

    private record MealChoice(Fixture actors, HearthBlockEntity hearth, Container stock,
                              BlockPos source, AABB bounds) {}

    /** All stock and initial positions are fixed before the first simulation tick. */
    @GameTest(batch = "tavern_schedule_boundaries", template = "empty16", timeoutTicks = 10)
    public void eveningAdmissionPreservesDaytimeHostPreparation(GameTestHelper h) {
        Fixture f = fixture(h);
        for (long tick : new long[]{5500, 6000, 6999, 10999, 12700}) {
            h.getLevel().setDayTime(tick);
            h.assertTrue(!TavernSeating.mayVisit(f.guest()), "resident cannot visit outside evening at " + tick);
        }
        h.getLevel().setDayTime(2000);
        h.assertTrue(TavernHostService.authorizedHost(f.host(), f.tavern().id),
            "guest opening hours do not stop an Innkeeper preparing service in working hours");
        h.getLevel().setDayTime(11000);
        h.assertTrue(TavernSeating.mayVisit(f.guest()), "resident admission opens at evening start");
        h.getLevel().setDayTime(12699);
        h.assertTrue(TavernSeating.mayVisit(f.guest()), "last evening tick remains open");
        h.getLevel().setDayTime(11500);
        h.succeed();
    }
    @GameTest(batch = "tavern_schedule_work", template = "empty16", timeoutTicks = 120)
    public void closedWorkingHoursStillPermitUrgentHearthMeal(GameTestHelper h) {
        observeClosedHoursUrgentMeal(h, 2000);
    }
    @GameTest(batch = "tavern_schedule_night", template = "empty16", timeoutTicks = 120)
    public void closedNightHoursStillPermitUrgentHearthMeal(GameTestHelper h) {
        observeClosedHoursUrgentMeal(h, 18000);
    }
    @GameTest(batch = "tavern_schedule_lunch", template = "empty16", timeoutTicks = 120)
    public void lunchStillPermitsActualHearthMealWithoutTavernAdmission(GameTestHelper h) {
        observeClosedHoursUrgentMeal(h, 6000);
    }
    private static void observeClosedHoursUrgentMeal(GameTestHelper h, long dayTime) {
        MealChoice f = mealChoice(h, 39, true);
        h.getLevel().setDayTime(dayTime);
        SettlerEntity guest = f.actors().guest();
        h.assertTrue(!TavernSeating.mayVisit(guest), "closed clock prevents resident Tavern admission");
        boolean[] sawOwnedMeal = {false};
        h.onEachTick(() -> {
            h.assertTrue(!guest.hasTavernSeat(), "urgent hunger must not wait in the closed Tavern");
            if (guest.hasMeal()) {
                sawOwnedMeal[0] = true;
                h.assertTrue(guest.mealDisplayCopy().is(Items.COOKED_BEEF), "actual Hearth meal owns the food");
            }
            boolean completed = sawOwnedMeal[0] && !guest.hasMeal();
            h.assertTrue(mealChoiceItems(h, f, Items.COOKED_BEEF) == (completed ? 1 : 2),
                "one real Hearth food is consumed only on meal completion");
            h.assertTrue(mealChoiceItems(h, f, Items.BREAD) == 2
                && mealChoiceItems(h, f, Items.GLASS_BOTTLE) == 1,
                "closed Tavern retains its stocked bread and reusable glass");
            if (completed) {
                h.assertTrue(guest.getHunger() > 65 && guest.bag.isEmpty(),
                    "urgent meal restores nutrition outside visit windows with no duplicate cargo");
                h.succeed();
            }
        });
    }
    private static MealChoice mealChoice(GameTestHelper h, float hunger, boolean tavernFood) {
        Fixture f = fixture(h);
        BlockPos hearthPos = new BlockPos(1, 1, 1);
        h.setBlock(hearthPos, ModBlocks.HEARTH.get());
        HearthBlockEntity hearth = (HearthBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(hearthPos));
        hearth.bindSettlement(f.village().id);
        hearth.getInventory().setStackInSlot(0, new ItemStack(Items.COOKED_BEEF, 2));
        BlockPos source = h.absolutePos(new BlockPos(3, 1, 5));
        h.getLevel().setBlockAndUpdate(source, Blocks.BARREL.defaultBlockState());
        Container stock = (Container) h.getLevel().getBlockEntity(source);
        if (tavernFood) stock.setItem(0, new ItemStack(Items.BREAD, 2));
        stock.setItem(1, new ItemStack(Items.GLASS_BOTTLE));
        Vec3 start = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(3, 1, 1)));
        f.guest().setPos(start);
        f.guest().move(net.minecraft.world.entity.MoverType.SELF, new Vec3(0, -.05, 0));
        f.guest().setHunger(hunger);
        h.assertTrue(f.guest().onGround() && f.guest().position().equals(start)
            && h.getLevel().noCollision(f.guest()) && !f.guest().isNoAi() && !f.host().isNoAi(),
            "ordinary guest and host start grounded, with real AI enabled");
        h.assertTrue(f.host().onGround() && h.getLevel().noCollision(f.host()),
            "ordinary host must also contact its supported clear floor");
        // A manual collision move sets onGround but leaves spawn velocity at zero.
        // Seed a small downward velocity so the first ordinary travel rechecks the
        // real floor instead of clearing contact before the later guest searches.
        f.host().setDeltaMovement(0.0, -0.05, 0.0);
        f.guest().setDeltaMovement(0.0, -0.05, 0.0);
        h.assertTrue(f.guest().blockPosition().distSqr(h.absolutePos(hearthPos)) <= 6.25,
            "guest begins inside the real Hearth contact range, not an artificial Tavern distance advantage");
        var hit = h.getLevel().clip(new net.minecraft.world.level.ClipContext(f.guest().getEyePosition(),
            Vec3.atCenterOf(h.absolutePos(hearthPos)), net.minecraft.world.level.ClipContext.Block.COLLIDER,
            net.minecraft.world.level.ClipContext.Fluid.NONE, f.guest()));
        h.assertTrue(hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS
            || hit.getBlockPos().equals(h.absolutePos(hearthPos)), "real Hearth food is visible and reachable at setup");
        h.assertTrue(TavernSeating.staffed(h.getLevel(), f.village(), f.tavern()), "fixture has an actually employed live host");
        return new MealChoice(f, hearth, stock, source, new AABB(Vec3.atLowerCornerOf(h.absolutePos(new BlockPos(0, 0, 0))),
            Vec3.atLowerCornerOf(h.absolutePos(new BlockPos(16, 6, 16)))));
    }
    private static int matching(ItemStack stack, Item item) {
        return stack.is(item) ? stack.getCount() : 0;
    }
    /** Real store, resident, serving and dropped-item owners; no display item counts as a second owner. */
    private static int mealChoiceItems(GameTestHelper h, MealChoice f, Item item) {
        int count = 0;
        for (int i = 0; i < f.hearth().getInventory().getSlots(); i++)
            count += matching(f.hearth().getInventory().getStackInSlot(i), item);
        for (int i = 0; i < f.stock().getContainerSize(); i++) count += matching(f.stock().getItem(i), item);
        for (SettlerEntity actor : h.getLevel().getEntitiesOfClass(SettlerEntity.class, f.bounds(),
                e -> f.actors().village().id.equals(e.getSettlementId()))) {
            for (int i = 0; i < actor.bag.getContainerSize(); i++) count += matching(actor.bag.getItem(i), item);
            count += matching(actor.getMainHandItem(), item) + matching(actor.getOffhandItem(), item);
            count += matching(actor.mealDisplayCopy(), item);
        }
        for (TavernServingEntity serving : h.getLevel().getEntitiesOfClass(TavernServingEntity.class, f.bounds(),
                e -> f.actors().village().id.equals(e.settlementId())))
            count += matching(serving.displayFood(), item) + matching(serving.displayGlass(), item);
        for (ItemEntity dropped : h.getLevel().getEntitiesOfClass(ItemEntity.class, f.bounds()))
            count += matching(dropped.getItem(), item);
        return count;
    }
    private static void assertStoresUntouched(GameTestHelper h, MealChoice f, int bread) {
        h.assertTrue(matching(f.hearth().getInventory().getStackInSlot(0), Items.COOKED_BEEF) == 2,
            "unselected Hearth must retain both exact food items");
        h.assertTrue(matching(f.stock().getItem(0), Items.BREAD) == bread
            && matching(f.stock().getItem(1), Items.GLASS_BOTTLE) == 1,
            "unselected Tavern must retain its real food and reusable glass");
    }
    private static void observeHearthFallback(GameTestHelper h, MealChoice f, int tavernBread,
                                              BooleanSupplier released) {
        boolean[] sawMeal = {false}, completed = {false};
        int[] activeTicks = {0};
        h.onEachTick(() -> {
            boolean active = released.getAsBoolean();
            SettlerEntity guest = f.actors().guest();
            if (active) {
                activeTicks[0]++;
                h.assertTrue(!guest.hasTavernSeat(), "unavailable service must not hold the hungry resident in a seat");
                if (guest.hasMeal()) {
                    h.assertTrue(guest.mealDisplayCopy().is(Items.COOKED_BEEF)
                        && guest.mealDisplayCopy().getCount() == 1, "ordinary Hearth goal owns the one actual source meal");
                    sawMeal[0] = true;
                } else if (sawMeal[0]) completed[0] = true;
            }
            h.assertTrue(mealChoiceItems(h, f, Items.COOKED_BEEF) == (completed[0] ? 1 : 2),
                "exact Hearth food remains conserved until the one real meal completes");
            h.assertTrue(matching(f.stock().getItem(0), Items.BREAD) == tavernBread
                && matching(f.stock().getItem(1), Items.GLASS_BOTTLE) == 1
                && mealChoiceItems(h, f, Items.BREAD) == tavernBread
                && mealChoiceItems(h, f, Items.GLASS_BOTTLE) == 1,
                "fallback never withdraws or duplicates the other store's food or glass");
            if (active) h.assertTrue(activeTicks[0] <= 100, "available nearby Hearth fallback must not wait out a Tavern visit");
            if (completed[0]) {
                h.assertTrue(guest.getHunger() > 90 && !guest.hasMeal() && guest.bag.isEmpty()
                    && matching(f.hearth().getInventory().getStackInSlot(0), Items.COOKED_BEEF) == 1,
                    "ordinary eating completes one real Hearth meal with nutrition and no loose second item");
                h.succeed();
            }
        });
    }
    @GameTest(batch = "tavern_meal_choice", template = "empty16", timeoutTicks = 900)
    public void stockedHearthYieldsToOrdinaryStaffedTavernMeal(GameTestHelper h) {
        MealChoice f = mealChoice(h, 65, true);
        Vec3 start = f.actors().guest().position();
        boolean[] sat = {false}, sawServing = {false}, sawMeal = {false}, completed = {false};
        h.onEachTick(() -> {
            SettlerEntity guest = f.actors().guest();
            h.assertTrue(matching(f.hearth().getInventory().getStackInSlot(0), Items.COOKED_BEEF) == 2
                && mealChoiceItems(h, f, Items.COOKED_BEEF) == 2,
                "staffed stocked reachable Tavern must win before the higher-priority Hearth goal withdraws food");
            if (guest.hasTavernSeat()) {
                sat[0] = true;
                h.assertTrue(TavernSeating.currentTavern(guest) == f.actors().tavern()
                    && guest.position().distanceTo(start) > 2, "ordinary AI walked to the actual fixture Tavern");
            }
            TavernServingEntity serving = TavernHostService.current(f.actors().host());
            if (serving != null) {
                sawServing[0] = true;
                h.assertTrue(serving.hostId().equals(f.actors().host().getUUID())
                    && serving.guestId().equals(guest.getUUID()) && serving.source().equals(f.source()),
                    "live host session preserves actual source and diner identity");
            }
            if (guest.hasMeal()) {
                h.assertTrue(sat[0] && sawServing[0] && guest.hasTavernSeat()
                    && ((TavernSeatEntity)guest.getVehicle()).isSettled()
                    && guest.mealDisplayCopy().is(Items.BREAD) && guest.mealDisplayCopy().getCount() == 1,
                    "only physical service to the settled diner supplies this real Tavern bread");
                sawMeal[0] = true;
            } else if (sawMeal[0]) completed[0] = true;
            h.assertTrue(mealChoiceItems(h, f, Items.BREAD) == (completed[0] ? 1 : 2)
                && mealChoiceItems(h, f, Items.GLASS_BOTTLE) == 1,
                "food and reusable glass remain conserved through real host/table/resident ownership");
            if (completed[0] && !TavernHostService.hasSession(f.actors().host())) {
                h.assertTrue(guest.getHunger() > 90 && guest.bag.isEmpty()
                    && matching(f.stock().getItem(0), Items.BREAD) == 1
                    && matching(f.stock().getItem(1), Items.GLASS_BOTTLE) == 1,
                    "one ordinary served meal completes and its real glass returns without charging the Hearth");
                h.succeed();
            }
        });
    }
    @GameTest(batch = "tavern_meal_choice", template = "empty16", timeoutTicks = 120)
    public void noRegisteredTavernFallsBackToActualHearthMeal(GameTestHelper h) {
        MealChoice f = mealChoice(h, 65, true);
        h.assertTrue(f.actors().village().buildings.remove(f.actors().tavern()), "fixture removes the sole Tavern before AI starts");
        observeHearthFallback(h, f, 2, () -> true);
    }
    @GameTest(batch = "tavern_meal_choice", template = "empty16", timeoutTicks = 120)
    public void emptyStaffedTavernFallsBackToActualHearthMeal(GameTestHelper h) {
        MealChoice f = mealChoice(h, 65, false);
        observeHearthFallback(h, f, 0, () -> true);
    }
    @GameTest(batch = "tavern_meal_choice", template = "empty16", timeoutTicks = 120)
    public void physicallySealedTavernFallsBackToActualHearthMeal(GameTestHelper h) {
        MealChoice f = mealChoice(h, 65, true);
        // Valid furniture and clear inside stands, but no entrance through this four-block-high enclosure.
        for (int x = 4; x <= 8; x++) for (int z = 4; z <= 9; z++)
            if (x == 4 || x == 8 || z == 4 || z == 9)
                for (int y = 1; y <= 4; y++) h.setBlock(new BlockPos(x, y, z), Blocks.STONE_BRICKS);
        BlockPos chair = h.absolutePos(new BlockPos(6, 1, 7));
        for (Direction side : new Direction[]{Direction.EAST, Direction.WEST}) {
            BlockPos aisle = chair.relative(side);
            var site = TavernSeating.site(h.getLevel(), f.actors().tavern(), chair, aisle);
            h.assertTrue(site != null && TavernSeating.clearStand(h.getLevel(), f.actors().guest(), aisle),
                "sealed test retains real chair/table and standable internal aisles");
            var path = f.actors().guest().getNavigation().createPath(aisle, 0);
            h.assertTrue(path == null || !path.canReach(), "ordinary navigation cannot enter the sealed Tavern");
        }
        observeHearthFallback(h, f, 2, () -> true);
    }
    @GameTest(batch = "tavern_meal_choice", template = "empty16", timeoutTicks = 420)
    public void naturallyOccupiedTavernFallsBackWithoutStealingSeat(GameTestHelper h) {
        MealChoice f = mealChoice(h, 65, true);
        f.actors().guest().setNoAi(true); // Release only after another ordinary resident physically occupies the one seat.
        SettlerEntity occupant = resident(h, f.actors().village(), new BlockPos(10, 1, 10));
        boolean[] released = {false};
        observeHearthFallback(h, f, 2, () -> {
            if (!released[0]) {
                if (!(occupant.getVehicle() instanceof TavernSeatEntity seat) || !seat.isSettled()) return false;
                h.assertTrue(seat.owns(occupant) && TavernSeating.currentTavern(occupant) == f.actors().tavern(),
                    "the competing diner reaches the real chair through its registered ordinary visit goal");
                f.actors().guest().setNoAi(false);
                released[0] = true;
            }
            h.assertTrue(occupant.hasTavernSeat(), "Hearth fallback must leave the existing diner's real seat intact");
            return true;
        });
    }
    @GameTest(batch = "tavern_meal_choice", template = "empty16", timeoutTicks = 120)
    public void criticalHungerUsesHearthWithoutWaitingForStockedTavern(GameTestHelper h) {
        MealChoice f = mealChoice(h, 35, true);
        observeHearthFallback(h, f, 2, () -> true);
    }
    @GameTest(batch = "tavern_meal_choice", template = "empty16", timeoutTicks = 100)
    public void existingDurableMealFinishesBeforeEitherNewFoodSource(GameTestHelper h) {
        MealChoice f = mealChoice(h, 65, true);
        f.actors().guest().bag.setItem(0, new ItemStack(Items.APPLE));
        h.assertTrue(f.actors().guest().beginMeal(f.actors().guest().bag.getItem(0))
            && f.actors().guest().bag.isEmpty(), "setup transfers one actual carried apple into the durable meal before AI starts");
        h.onEachTick(() -> {
            SettlerEntity guest = f.actors().guest();
            assertStoresUntouched(h, f, 2);
            h.assertTrue(mealChoiceItems(h, f, Items.COOKED_BEEF) == 2
                && mealChoiceItems(h, f, Items.BREAD) == 2 && mealChoiceItems(h, f, Items.GLASS_BOTTLE) == 1
                && !guest.hasTavernSeat(), "owned meal bypasses fresh Tavern preference and neither store is charged");
            if (guest.hasMeal()) {
                h.assertTrue(guest.mealDisplayCopy().is(Items.APPLE) && mealChoiceItems(h, f, Items.APPLE) == 1,
                    "same resident retains the original durable apple while normal eating progresses");
            } else {
                h.assertTrue(guest.getHunger() > 90 && mealChoiceItems(h, f, Items.APPLE) == 0 && guest.bag.isEmpty(),
                    "the existing real meal finishes once through ordinary AI without new food withdrawal");
                h.succeed();
            }
        });
    }
}
