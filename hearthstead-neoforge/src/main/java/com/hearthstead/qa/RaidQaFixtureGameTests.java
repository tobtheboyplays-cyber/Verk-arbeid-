package com.hearthstead.qa;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.OwnedProjectileLedger;
import com.hearthstead.entity.ai.ArcherAttackGoal;
import com.hearthstead.building.PlaqueState;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.RecruitmentTransaction;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.development.Development;
import com.hearthstead.settlement.guard.GuardAssignmentService;
import com.hearthstead.settlement.request.RequestLedgerSavedData;
import com.hearthstead.settlement.raid.RaidDirector;
import com.hearthstead.settlement.raid.RaidThreatBoard;
import com.hearthstead.settlement.state.FirstRaidState;
import com.hearthstead.settlement.state.GuardOrder;
import com.hearthstead.settlement.work.ContainerApproach;
import com.hearthstead.settlement.work.WorkerStorageAuthority;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Integration proof for the permission-two physical raid fixture trunk. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class RaidQaFixtureGameTests {
    public RaidQaFixtureGameTests() {
    }

    @GameTest(template = "empty16", timeoutTicks = 8_000,
        batch = "raidqa_fixture_warehouse_and_partial_replay")
    public void freshWarehouseIsExactAndCropWaitReplayConservesItems(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer actor = helper.makeMockServerPlayerInLevel();
        NetworkRegistry.configureMockConnection(
            actor.connection.getConnection());
        actor.getAbilities().instabuild = false;
        actor.onUpdateAbilities();

        // Keep this 32-block fixture vertically isolated from the ordinary
        // 16x16 test-template structures that share the server world.
        BlockPos origin = helper.absolutePos(new BlockPos(0, 40, 0));
        // GameTest normally tickets only the template's nearby chunks. The
        // showcase deliberately validates a wider physical raid apron before
        // changing a single block. Give that exact footprint a temporary test
        // ticket, but do not clear or replace anything: production's empty-site
        // validation must still reject collisions. Keep owned tickets until
        // test termination: loaded-only outer chunks do not tick travelers.
        Set<Long> fixtureChunks = new java.util.LinkedHashSet<>();
        Set<Long> inheritedChunkTickets = new java.util.LinkedHashSet<>();
        Set<Long> addedChunkTickets = new java.util.LinkedHashSet<>();
        boolean[] releasedChunkTickets = {false};
        Runnable releaseOwnedChunks = () -> {
            // Release explicitly before success, while this batch still owns
            // its tickets. Failure cleanup belongs to the GameTest runner.
            if (releasedChunkTickets[0]) return;
            releasedChunkTickets[0] = true;
            for (long chunkKey : addedChunkTickets) {
                ChunkPos chunk = new ChunkPos(chunkKey);
                level.setChunkForced(chunk.x, chunk.z, false);
            }
        };
        // Do not add a late terminal listener: the runner may already have
        // started the next batch and reacquired overlapping chunk tickets.
        for (int chunkX = (origin.getX() - RaidQaFixtureService.STAGING_RADIUS) >> 4;
                chunkX <= (origin.getX() + RaidQaFixtureService.STAGING_RADIUS) >> 4; chunkX++) {
            for (int chunkZ = (origin.getZ() - RaidQaFixtureService.STAGING_RADIUS) >> 4;
                    chunkZ <= (origin.getZ() + RaidQaFixtureService.STAGING_RADIUS) >> 4; chunkZ++) {
                long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
                fixtureChunks.add(chunkKey);
                if (level.getForcedChunks().contains(chunkKey)) {
                    // The GameTest framework owns the template chunk ticket.
                    // Preserve it; this fixture may release only tickets it
                    // acquired itself.
                    inheritedChunkTickets.add(chunkKey);
                }
            }
        }
        RaidQaFixtureService.Result first;
        boolean previousDistanceOverride =
            SettlementManager.ignoreFoundingDistance;
        try {
            for (long chunkKey : fixtureChunks) {
                ChunkPos chunk = new ChunkPos(chunkKey);
                if (!inheritedChunkTickets.contains(chunkKey)) {
                    level.setChunkForced(chunk.x, chunk.z, true);
                    addedChunkTickets.add(chunkKey);
                }
                level.getChunk(chunk.x, chunk.z);
                helper.assertTrue(level.isLoaded(new BlockPos(chunk.getMinBlockX(),
                        origin.getY(), chunk.getMinBlockZ())),
                    "temporary fixture chunk ticket must be observed before "
                        + "production site validation: " + chunk);
            }
            // GameTests share one SavedData root, so unrelated retained test
            // settlements must not prevent this test's real founding call.
            SettlementManager.ignoreFoundingDistance = true;
            first = RaidQaFixtureService.prepare(level, actor, origin);
        } finally {
            SettlementManager.ignoreFoundingDistance =
                previousDistanceOverride;
        }
        for (long chunkKey : addedChunkTickets) {
            helper.assertTrue(level.getForcedChunks().contains(chunkKey),
                "fixture must retain entity-ticking tickets during physical travel: "
                    + new ChunkPos(chunkKey));
        }
        for (long chunkKey : inheritedChunkTickets) {
            helper.assertTrue(level.getForcedChunks().contains(chunkKey),
                "fixture setup must preserve the GameTest framework's chunk "
                    + "ticket: " + new ChunkPos(chunkKey));
        }
        helper.assertTrue(first.stage()
                == RaidQaFixtureService.Stage.WAITING_NATURAL_CROP,
            "fresh fixture must stop only at natural crop growth; stage="
                + first.stage() + "; blocker=" + first.blocker()
                + "; detail=" + first.detail());

        // Barracks registration clears this exact doorway later. It must not
        // remove a crop-field border and release the irrigation water.
        BlockPos futureBarracksDoorstep = origin.offset(4, 1, 22);
        helper.assertTrue(!level.getBlockState(futureBarracksDoorstep).is(Blocks.FARMLAND)
                && level.getFluidState(futureBarracksDoorstep).isEmpty(),
            "the future Barracks doorstep must not overlap or drain the crop field");

        Settlement settlement = first.settlement();
        helper.assertTrue(settlement != null,
            "fresh fixture must retain its exact founded settlement");
        helper.assertTrue(openPoweredIronDoor(level, origin.offset(14, 1, 19), Direction.SOUTH)
                && openOakDoor(level, origin.offset(15, 1, 19), Direction.SOUTH)
                && openPoweredIronDoor(level, origin.offset(14, 1, 20), Direction.NORTH)
                && level.getBlockState(origin.offset(14, 3, 19)).is(Blocks.REDSTONE_BLOCK)
                && level.getBlockState(origin.offset(14, 3, 20)).is(Blocks.REDSTONE_BLOCK),
            "the authored Warehouse and tower firing corridor must initially be open, "
                + "before ordinary worker navigation may close them");
        RaidQaFixtureService.StartResult blockedBeforeReadiness =
            RaidQaFixtureService.start(level, actor);
        helper.assertTrue(blockedBeforeReadiness.outcome()
                    == RaidQaFixtureService.StartOutcome.BLOCKED
                && blockedBeforeReadiness.spawned() == 0
                && settlement.raidLifecycle.participants().isEmpty()
                && settlement.pendingRaid == null,
            "an incomplete fixture must remain blocked without fabricating "
                + "a plan, pending raid or participants: "
                + blockedBeforeReadiness);
        Building warehouse = exactBuilding(settlement,
            BuildingType.WAREHOUSE);
        Building camp = exactBuilding(settlement,
            BuildingType.LUMBER_CAMP);
        Building farmhouse = exactBuilding(settlement,
            BuildingType.FARMHOUSE);
        BlockPos warehousePlaque = origin.offset(12, 0, 13)
            .offset(1, 2, -1);
        PlaqueBlockEntity plaque = level.getBlockEntity(warehousePlaque)
            instanceof PlaqueBlockEntity found ? found : null;
        helper.assertTrue(warehouse != null && camp != null
                && farmhouse != null && warehouse.valid
                && warehouse.type == BuildingType.WAREHOUSE
                && warehouse.plaquePos.equals(warehousePlaque)
                && plaque != null && plaque.state() == PlaqueState.LINKED_VALID
                && plaque.settlementFor(level) == settlement
                && plaque.building(level) == warehouse
                && plaque.buildingId().equals(warehouse.id),
            "normal learned-plan Plaque use must resolve one exact valid "
                + "Warehouse owned by the fixture settlement");

        SettlerEntity courier = exactWorker(level, settlement, warehouse,
            Profession.COURIER);
        helper.assertTrue(courier != null,
            "Warehouse must retain its exact emblem-backed Courier");
        int logsAfterDelivery = countInBuilding(level, camp, Items.OAK_LOG)
            + countInBuilding(level, warehouse, Items.OAK_LOG)
            + count(courier.bag, Items.OAK_LOG);
        helper.assertTrue(logsAfterDelivery == 2
                && countInBuilding(level, camp, Items.OAK_LOG) == 0
                && countInBuilding(level, warehouse, Items.OAK_LOG) == 2
                && courier.bag.isEmpty(),
            "the typed Camp-to-Warehouse route must conserve exactly two "
                + "physical provenance-backed logs");

        int buildingCount = settlement.buildings.size();
        int memberCount = settlement.population();
        int journeyRevision = settlement.foundingJourney.revision();
        int developmentRevision = Development.revisionOf(level, settlement);
        long warehouseZoneRevision = warehouse.workZoneRevision();
        long farmZoneRevision = farmhouse.workZoneRevision();

        // The immature physical crop is an intentional persisted partial
        // state. A later prepare must resume it, never remint inputs or bind
        // a caller-supplied replacement origin.
        RaidQaFixtureService.Result replay = RaidQaFixtureService.prepare(
            level, actor, origin.offset(100, 0, 0));
        int replayLogs = countInBuilding(level, camp, Items.OAK_LOG)
            + countInBuilding(level, warehouse, Items.OAK_LOG)
            + count(courier.bag, Items.OAK_LOG);
        helper.assertTrue(replay.stage()
                == RaidQaFixtureService.Stage.WAITING_NATURAL_CROP
                && replay.origin().equals(origin)
                && replay.settlement() == settlement
                && settlement.buildings.size() == buildingCount
                && settlement.population() == memberCount
                && settlement.foundingJourney.revision() == journeyRevision
                && Development.revisionOf(level, settlement)
                    == developmentRevision
                && warehouse.workZoneRevision() == warehouseZoneRevision
                && farmhouse.workZoneRevision() == farmZoneRevision
                && replayLogs == logsAfterDelivery,
            "repeated prepare must resume the partial crop state without "
                + "new buildings, people, logs, zones, Journey or knowledge; "
                + "stage=" + replay.stage() + "; blocker=" + replay.blocker()
                + "; detail=" + replay.detail());

        Set<UUID> founders = SettlementManager.loadedMembers(level,
                settlement).stream().map(SettlerEntity::getUUID)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        UUID fixtureMayorId = settlement.mayorId;
        helper.assertTrue(fixtureMayorId != null
                && level.getEntity(fixtureMayorId) instanceof SettlerEntity mayor
                && mayor.isAlive(),
            "the fixture Mayor must be a live physical founder before the "
                + "natural wait");
        BlockPos cropPos = origin.offset(5, 2, 21);
        helper.assertTrue(level.getBlockState(cropPos).is(Blocks.WHEAT)
                && level.getRawBrightness(cropPos, 0) >= 9,
            "the physical fixture crop must begin planted with vanilla-valid "
                + "growth light; block=" + level.getBlockState(cropPos)
                + "; light=" + level.getRawBrightness(cropPos, 0));
        int[] maximumObservedCropAge = {0};
        long startedAt = level.getGameTime();
        RaidQaFixtureService.Result[] lastObserved = {replay};
        Set<Integer> acceleratedRecruitmentCycles =
            new java.util.LinkedHashSet<>();
        Set<Integer> admittedRecruitmentCycles =
            new java.util.LinkedHashSet<>();
        java.util.Map<Integer, Double> travelerSpawnDistances =
            new java.util.HashMap<>();
        Set<Integer> travelerRoutesProven = new java.util.LinkedHashSet<>();
        java.util.Map<Integer, RecruitmentTransaction> travelerSpawnIdentities =
            new java.util.HashMap<>();
        Set<UUID> visibleTravelers = new java.util.LinkedHashSet<>();
        boolean[] raidRouteVerificationScheduled = {false};
        Runnable[] observeRaid = {() -> {}};

        // Dedicated GameTest worlds do not naturally random-tick the isolated
        // y+40 section, even when RULE_RANDOMTICKING reads back a large value.
        // Accelerate only this test through vanilla's physical BoneMealItem
        // interaction instead: every growth attempt consumes the actor's
        // real stack, and CropBlock still owns every age transition.
        int selectedBeforeBoneMeal = actor.getInventory().selected;
        int boneMealSlot = firstEmptyHotbar(actor);
        helper.assertTrue(boneMealSlot >= 0,
            "fixture test needs one real hotbar slot for physical bone meal");
        actor.getInventory().selected = boneMealSlot;
        actor.setItemInHand(InteractionHand.MAIN_HAND,
            new net.minecraft.world.item.ItemStack(Items.BONE_MEAL, 64));
        actor.setPos(cropPos.getX() + 0.5D, cropPos.getY() + 0.5D,
            cropPos.getZ() + 0.5D);
        int boneMealBefore = actor.getMainHandItem().getCount();
        int boneMealAttempts = 0;
        while (level.getBlockState(cropPos).getBlock() instanceof CropBlock crop
                && !crop.isMaxAge(level.getBlockState(cropPos))
                && boneMealAttempts++ < boneMealBefore) {
            InteractionResult applied = actor.getMainHandItem().useOn(
                new UseOnContext(actor, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(cropPos), Direction.UP,
                        cropPos, false)));
            helper.assertTrue(applied.consumesAction(),
                "vanilla bone meal must accept the physical age-zero wheat");
            maximumObservedCropAge[0] = Math.max(maximumObservedCropAge[0],
                crop.getAge(level.getBlockState(cropPos)));
        }
        int boneMealAfter = actor.getMainHandItem().getCount();
        helper.assertTrue(level.getBlockState(cropPos).getBlock()
                    instanceof CropBlock mature
                && mature.isMaxAge(level.getBlockState(cropPos))
                && boneMealAfter < boneMealBefore
                && boneMealBefore - boneMealAfter == boneMealAttempts,
            "bounded vanilla growth must mature wheat and conserve the exact "
                + "consumed bone meal; before=" + boneMealBefore + "; after="
                + boneMealAfter + "; attempts=" + boneMealAttempts
                + "; crop=" + level.getBlockState(cropPos));
        actor.getInventory().selected = selectedBeforeBoneMeal;

        // The fixture contains two real edge-spawned travelers.  A fixed
        // deadline only slightly below the GameTest ceiling made a valid
        // first traveler appear near the end of a busy server run, then
        // falsely condemned its ordinary walk before it had a fair chance to
        // reach the Tavern.  The per-traveler timeout below is the actual
        // liveness assertion; this outer deadline is only a whole-fixture
        // circuit breaker.
        helper.runAfterDelay(7_900, () -> {
            BlockState cropState = level.getBlockState(cropPos);
            int cropAge = cropState.getBlock() instanceof CropBlock crop
                ? crop.getAge(cropState) : -1;
            RecruitmentTransaction recruitment = settlement.recruitment;
            helper.fail("fixture progress deadline; stage="
                + lastObserved[0].stage() + "; blocker="
                + lastObserved[0].blocker() + "; detail="
                + lastObserved[0].detail() + "; crop=" + cropState
                + "; cropAge=" + cropAge + "; maxCropAge="
                + maximumObservedCropAge[0] + "; light="
                + level.getRawBrightness(cropPos, 0) + "; boneMealConsumed="
                + (boneMealBefore - boneMealAfter) + "; population="
                + settlement.population() + "; recruitment="
                + (recruitment == null ? "null"
                    : recruitment.status() + "/cycle="
                        + recruitment.cycle()));
        });

        // Recruitment uses the established test-only qualification-clock
        // seam, while production still owns spawn, physical travel/arrival,
        // exact payment and admission.
        helper.onEachTick(() -> {
            if (raidRouteVerificationScheduled[0]) {
                observeRaid[0].run();
                return;
            }
            if (!(level.getEntity(fixtureMayorId) instanceof SettlerEntity mayor)
                    || !mayor.isAlive()) {
                helper.fail("the fixture's physical perimeter must keep its "
                    + "original Mayor alive throughout natural crop and "
                    + "traveler waits");
                return;
            }
            if (level.getBlockState(cropPos).getBlock()
                    instanceof CropBlock crop) {
                maximumObservedCropAge[0] = Math.max(
                    maximumObservedCropAge[0],
                    crop.getAge(level.getBlockState(cropPos)));
            }
            // prepare() deliberately stages its exact physical workers at
            // containers, crop plots and defense posts. Restore each existing
            // worker after that synchronous boundary, but retain a newly hired
            // defender at the service-issued post: resetting that one position
            // would erase the actual defensive formation before raid start.
            // A defender hired on an earlier tick (its bow/sword waited for a
            // busy Courier) gets its post on a later prepare: that service
            // staging is recognised by the order revision advancing.
            record FixturePosition(UUID id, SettlerEntity entity, Vec3 position,
                                   Profession profession, boolean founder,
                                   int orderRevision) {}
            List<FixturePosition> fixturePositions = new java.util.ArrayList<>();
            for (SettlerEntity member : SettlementManager.loadedMembers(level,
                    settlement)) {
                boolean founder = founders.contains(member.getUUID());
                Profession profession = member.getProfession();
                boolean allowedRole = founder
                    ? (profession == Profession.MAYOR && member.getUUID().equals(settlement.mayorId))
                        || profession == Profession.LUMBERER
                        || profession == Profession.FARMER
                        || profession == Profession.COURIER
                    : profession == Profession.NONE
                        || profession == Profession.GUARD
                        || profession == Profession.ARCHER;
                helper.assertTrue(member.isAlive() && !member.isTraveler()
                        && settlement.id.equals(member.getSettlementId())
                        && allowedRole,
                    "only the exact Mayor, founder workers and admitted candidates may be inspected");
                fixturePositions.add(new FixturePosition(member.getUUID(),
                    member, member.position(), profession, founder,
                    settlement.guardOrders.order(member.getUUID())
                        .map(GuardOrder::revision).orElse(-1)));
            }
            helper.assertTrue(fixturePositions.size() <= founders.size() + 2,
                "the fixture may stage only its founders and two admitted travelers");
            RaidQaFixtureService.Result observed;
            try {
                observed = RaidQaFixtureService.prepare(level, actor,
                    origin.offset(100, 0, 0));
            } finally {
                for (FixturePosition captured : fixturePositions) {
                    SettlerEntity member = captured.entity();
                    Profession afterRole = member.getProfession();
                    boolean allowedHire = !captured.founder()
                        && captured.profession() == Profession.NONE
                        && (afterRole == Profession.GUARD || afterRole == Profession.ARCHER);
                    helper.assertTrue(level.getEntity(captured.id()) == member
                            && captured.id().equals(member.getUUID())
                            && member.isAlive() && !member.isTraveler()
                            && settlement.id.equals(member.getSettlementId())
                            && (afterRole == captured.profession() || allowedHire),
                        "staging must preserve the exact worker and only allow defender hiring");
                    Vec3 position = captured.position();
                    if (captured.profession() == Profession.MAYOR) {
                        helper.assertTrue(member.position().equals(position),
                            "the dedicated Mayor must not receive worker staging");
                    }
                    boolean postReissued = !captured.founder()
                        && (afterRole == Profession.GUARD || afterRole == Profession.ARCHER)
                        && afterRole == captured.profession()
                        && settlement.guardOrders.order(member.getUUID())
                            .map(GuardOrder::revision).orElse(-1)
                            != captured.orderRevision();
                    boolean newlyStagedDefender = (allowedHire || postReissued)
                        && !member.position().equals(position);
                    if (!member.position().equals(position)
                            && !newlyStagedDefender) {
                        helper.assertTrue(afterRole != Profession.NONE,
                            "an unassigned guest must never receive fixture work staging");
                        member.setPos(position.x, position.y, position.z);
                    }
                    if (newlyStagedDefender) {
                        GuardOrder stagedOrder = settlement.guardOrders.order(
                            member.getUUID()).orElse(null);
                        helper.assertTrue(stagedOrder != null
                                && stagedOrder.pos().isPresent()
                                && member.blockPosition().distSqr(
                                    stagedOrder.pos().orElseThrow()) <= 2.25D,
                            "a newly hired fixture defender must retain the "
                                + "service-issued physical post for the real raid route");
                    } else {
                        helper.assertTrue(member.position().equals(position),
                            "synchronous fixture staging must restore its exact worker position");
                    }
                }
            }
            lastObserved[0] = observed;
            RecruitmentTransaction recruitment = settlement.recruitment;
            if (recruitment != null
                && recruitment.status() == RecruitmentTransaction.Status.ADMITTED
                && admittedRecruitmentCycles.add(recruitment.cycle())) {
                // The service has just committed the real admission. GameTest
                // time does not naturally leave the arrival evening, so resume
                // the following work morning before any courier/defender step.
                advanceToNextWorkMorning(level);
            }
            if (!"none".equals(observed.blocker())) {
                helper.fail("natural fixture replay must not hit a structured "
                    + "blocker; stage=" + observed.stage() + "; blocker="
                    + observed.blocker() + "; detail=" + observed.detail()
                    + "; recruitmentStatus=" + (settlement.recruitment == null ? null : settlement.recruitment.status())
                    + "; guest=" + travelerSnapshot(level, settlement));
                return;
            }
            if (recruitment != null
                && recruitment.status()
                    == RecruitmentTransaction.Status.TRAVELING
                && recruitment.spawnedTick() >= 0L) {
                helper.assertTrue(recruitment.transactionId() != null
                        && recruitment.travelerId() != null,
                    "a spawned fixture traveler must retain exact transaction and entity UUIDs");
                RecruitmentTransaction spawned = travelerSpawnIdentities
                    .computeIfAbsent(recruitment.cycle(), ignored -> recruitment);
                helper.assertTrue(spawned.transactionId().equals(recruitment.transactionId())
                        && spawned.travelerId().equals(recruitment.travelerId())
                        && spawned.spawnedTick() == recruitment.spawnedTick(),
                    "startup waiting must never replace a fixture transaction, traveler, or spawn clock");
                long elapsed = level.getGameTime() - spawned.spawnedTick();
                helper.assertTrue(elapsed >= 0L,
                    "a fixture traveler's original spawn clock must not move backward");
                var traveler = level.getEntity(spawned.travelerId());
                if (traveler == null) {
                    // addFreshEntity can accept an entity into a HIDDEN section
                    // before ServerLevel's visible UUID lookup exposes it. The
                    // already bounded startup allowance begins at the original
                    // spawn, not the first visible lookup or a replacement UUID.
                    helper.assertTrue(!visibleTravelers.contains(spawned.travelerId())
                            && elapsed <= 1_200L,
                        "a fixture guest must become visible within its original startup budget "
                            + "and remain loaded after first observation; cycle="
                            + recruitment.cycle() + "; transaction=" + spawned.transactionId()
                            + "; traveler=" + spawned.travelerId() + "; elapsed=" + elapsed);
                    for (long chunkKey : fixtureChunks) {
                        helper.assertTrue(level.getForcedChunks().contains(chunkKey),
                            "initial traveler visibility must retain the exact fixture chunk ticket: "
                                + new ChunkPos(chunkKey));
                    }
                    return;
                }
                helper.assertTrue(visibleTravelers.contains(spawned.travelerId())
                        || elapsed <= 1_200L,
                    "first traveler visibility must occur within the original startup budget");
                visibleTravelers.add(spawned.travelerId());
                if (!(traveler instanceof SettlerEntity)) {
                    helper.fail("a traveling fixture guest must retain its "
                        + "Settler identity; cycle=" + recruitment.cycle()
                        + "; entity=" + traveler);
                    return;
                }
                // The accelerated GameTest server can advance its world clock
                // while a newly loaded chunk is still admitting entities to
                // its ticking list. Zero entity ticks cannot prove a broken
                // navigation goal. Bound that startup separately, then measure
                // the real worker's physical opportunities to make progress.
                if (traveler.tickCount == 0) {
                    helper.assertTrue(elapsed <= 1_200L,
                        "the fixture must start ticking its real traveler within "
                            + "1200 world ticks; elapsed=" + elapsed
                            + "; chunk=" + traveler.chunkPosition()
                            + "; forced=" + level.getForcedChunks().contains(
                                traveler.chunkPosition().toLong()));
                    return;
                }
                long travelTicks = traveler.tickCount;
                // Observe the route that TravelerJoinGoal actually installed
                // after the goal scheduler ran. Recomputing a direct path here
                // is a false test: vanilla may currently be following a partial
                // route to the doorstep through the normal visitor doors.
                if (travelTicks >= 5L
                    && !travelerRoutesProven.contains(recruitment.cycle())) {
                    BlockPos approach = SettlementManager.travelerTavernApproach(
                        level, (SettlerEntity) traveler);
                    BlockPos navigationTarget = ((SettlerEntity) traveler)
                        .getNavigation().getTargetPos();
                    BlockPos raidGate = origin.offset(0, 1, 1);
                    List<BlockPos> visitorDoors = List.of(
                        origin.offset(0, 1, 2), origin.offset(23, 1, 0),
                        origin.offset(31, 1, 11), origin.offset(23, 1, 31));
                    if (approach != null && navigationTarget != null
                        && ((SettlerEntity) traveler).getActivity()
                            == com.hearthstead.entity.SettlerActivity.TRAVELING) {
                        travelerRoutesProven.add(recruitment.cycle());
                    } else if (travelTicks > 80L) {
                        helper.fail("each production edge spawn must receive "
                            + "an ordinary TravelerJoinGoal route after its "
                            + "scheduler starts; cycle=" + recruitment.cycle()
                            + "; spawn=" + traveler.blockPosition()
                            + "; approach=" + approach + "; navTarget="
                            + navigationTarget + "; activity="
                            + ((SettlerEntity) traveler).getActivity()
                            + "; entityTicks=" + traveler.tickCount
                            + "; onGround=" + traveler.onGround()
                            + "; raidGate=" + level.getBlockState(raidGate)
                            + "; visitorDoors=" + visitorDoors.stream()
                                .map(door -> door + "="
                                    + level.getBlockState(door)).toList());
                        return;
                    }
                    helper.assertTrue(level.getBlockState(raidGate)
                            .is(Blocks.OAK_FENCE_GATE)
                            && !level.getBlockState(raidGate)
                                .getValue(FenceGateBlock.OPEN)
                            && visitorDoors.stream().allMatch(door ->
                                level.getBlockState(door).getBlock()
                                    instanceof DoorBlock),
                        "the route proof must keep the controlled raid gate "
                            + "sealed and use its separate visitor door");
                }
                double distance = Math.sqrt(traveler.distanceToSqr(
                    recruitment.tavernAnchor().getX() + 0.5D,
                    recruitment.tavernAnchor().getY(),
                    recruitment.tavernAnchor().getZ() + 0.5D));
                double startingDistance = travelerSpawnDistances.computeIfAbsent(
                    recruitment.cycle(), ignored -> distance);
                // The traveler starts about 56 blocks from the Hearth
                // and the Tavern entry is no more than 31 blocks beyond it.
                // 1,200 ticks is deliberately generous for ordinary 0.3-speed
                // navigation, turns and door handling. An earlier two-block
                // progress check still catches a broken physical route instead
                // of disguising it as a longer timeout.
                if (travelTicks > 200L && distance >= startingDistance - 2.0D) {
                    helper.fail("a real traveler must make physical progress "
                        + "toward the locked Tavern; cycle="
                        + recruitment.cycle() + "; elapsed=" + elapsed
                        + "; startDistance=" + startingDistance
                        + "; distance=" + distance + "; traveler="
                        + traveler + "; target=" + recruitment.tavernAnchor());
                    return;
                }
                if (travelTicks > 1_200L) {
                    helper.fail("a real traveler must reach the locked Tavern "
                        + "within the bounded physical travel window; cycle="
                        + recruitment.cycle() + "; elapsed=" + elapsed
                        + "; traveler=" + traveler + "; target="
                        + recruitment.tavernAnchor());
                    return;
                }
            }
            if (recruitment != null
                && recruitment.status()
                    == RecruitmentTransaction.Status.QUALIFYING
                && acceleratedRecruitmentCycles.add(recruitment.cycle())) {
                // This approved GameTest seam accelerates only the long
                // multi-day qualification clock. The next normal Hearth tick
                // still performs the real production spawn transaction in an
                // unused evening batch; it may not publish immediately or
                // reuse a same-day visitor slot.
                settlement.applyRecruitment(
                    recruitment.readyForSpawnForTest());
                advanceToNextNaturalVisitorWindow(level, settlement);
            }
            if (observed.stage()
                    != RaidQaFixtureService.Stage.READY_BEFORE_FIRST_RAID) {
                return;
            }
            Set<UUID> members = SettlementManager.loadedMembers(level,
                    settlement).stream().filter(SettlerEntity::isAlive)
                .map(SettlerEntity::getUUID)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
            Set<UUID> admitted = new java.util.LinkedHashSet<>(members);
            admitted.removeAll(founders);
            Building barracks = exactBuilding(settlement,
                BuildingType.BARRACKS);
            helper.assertTrue(level.getFluidState(futureBarracksDoorstep).isEmpty(),
                "registered Barracks doorstep must remain dry after physical irrigation ticks");
            Building watchtower = exactBuilding(settlement,
                BuildingType.WATCHTOWER);
            SettlerEntity guard = exactWorker(level, settlement, barracks,
                Profession.GUARD);
            SettlerEntity archer = exactWorker(level, settlement, watchtower,
                Profession.ARCHER);
            int terminalHistoryBefore = RequestLedgerSavedData.existing(level)
                .existing(settlement.id).terminalHistory().size();
            GuardOrder guardOrder = settlement.guardOrders.order(
                guard.getUUID()).orElseThrow();
            GuardOrder archerOrder = settlement.guardOrders.order(
                archer.getUUID()).orElseThrow();
            int guardOrderRevision = guardOrder.revision();
            int archerOrderRevision = archerOrder.revision();
            int terminalBuildings = settlement.buildings.size();
            int terminalDevelopment = Development.revisionOf(level, settlement);
            int terminalWarehouseCrop = countInBuilding(level, warehouse,
                Items.WHEAT);
            BlockPos warehouseSouthLeft = origin.offset(14, 1, 19);
            BlockPos warehouseSouthRight = origin.offset(15, 1, 19);
            BlockPos warehouseSouthLeftPower = origin.offset(14, 3, 19);
            BlockPos warehouseNorthShutterLeft = origin.offset(14, 1, 13);
            BlockPos warehouseNorthShutterRight = origin.offset(15, 1, 13);
            BlockPos warehouseNorthShutterPowerLeft = origin.offset(14, 3, 13);
            BlockPos warehouseNorthShutterPowerRight = origin.offset(15, 3, 13);
            BlockPos towerNorthDoor = origin.offset(14, 1, 20);
            BlockPos towerNorthDoorPower = origin.offset(14, 3, 20);
            BlockPos expectedGroundFloorPost = origin.offset(14, 1, 22);
            // Warehouse min (-575, 70, -474) is origin+(12, 0, 13). Its west
            // approach is the first open, supported alley cell: minX-2,
            // minY+1, minZ+2. minX-3 is the adjacent Farmhouse wall.
            BlockPos expectedWarehouseApproachPost = origin.offset(10, 1, 15);
            // Live workers may use the remaining wooden door during normal
            // travel; the firing corridor itself stays physically open.
            helper.assertTrue(openPoweredIronDoor(level, warehouseSouthLeft, Direction.SOUTH)
                    && intactOakDoor(level, warehouseSouthRight, Direction.SOUTH)
                    && level.getBlockState(warehouseSouthLeftPower)
                        .is(Blocks.REDSTONE_BLOCK)
                    && openPoweredIronDoor(level, warehouseNorthShutterLeft, Direction.NORTH)
                    && openPoweredIronDoor(level, warehouseNorthShutterRight, Direction.NORTH)
                    && level.getBlockState(warehouseNorthShutterPowerLeft)
                        .is(Blocks.REDSTONE_BLOCK)
                    && level.getBlockState(warehouseNorthShutterPowerRight)
                        .is(Blocks.REDSTONE_BLOCK)
                    && openPoweredIronDoor(level, towerNorthDoor, Direction.NORTH)
                    && level.getBlockState(towerNorthDoorPower)
                        .is(Blocks.REDSTONE_BLOCK)
                    && warehouse.contains(warehouseSouthLeft)
                    && warehouse.contains(warehouseSouthRight)
                    && warehouse.contains(warehouseNorthShutterLeft)
                    && warehouse.contains(warehouseNorthShutterRight)
                    && watchtower.contains(towerNorthDoor)
                    && archerOrder.pos().orElseThrow().equals(expectedGroundFloorPost)
                    && guardOrder.mode() == GuardOrder.Mode.STAND_POST
                    && guardOrder.pos().orElseThrow().equals(expectedWarehouseApproachPost)
                    && level.getBlockState(expectedWarehouseApproachPost).isAir()
                    && level.getBlockState(expectedWarehouseApproachPost.above()).isAir()
                    && level.getBlockState(expectedWarehouseApproachPost.below())
                        .isFaceSturdy(level, expectedWarehouseApproachPost.below(), Direction.UP),
                "the ground-floor Tower Post, Warehouse approach post, original doorways and powered "
                    + "firing corridor must remain inside their surveyed "
                    + "buildings; warehouseDoors="
                    + level.getBlockState(warehouseSouthLeft) + ","
                    + level.getBlockState(warehouseSouthRight) + "; southPower="
                    + level.getBlockState(warehouseSouthLeftPower) + "; shutter="
                    + level.getBlockState(warehouseNorthShutterLeft) + ","
                    + level.getBlockState(warehouseNorthShutterRight) + "; shutterPower="
                    + level.getBlockState(warehouseNorthShutterPowerLeft) + ","
                    + level.getBlockState(warehouseNorthShutterPowerRight) + "; towerDoor="
                    + level.getBlockState(towerNorthDoor) + "; towerPower="
                    + level.getBlockState(towerNorthDoorPower) + "; archerPost="
                    + archerOrder.pos().orElseThrow());
            helper.assertTrue(level.getGameTime() > startedAt
                    && maximumObservedCropAge[0] > 0
                    && settlement.population() == 6
                    && settlement.validBedCount() == 6
                    && admitted.size() == 2
                    // Nightly Tavern visits no longer stop at full housing
                    // (bed/reserve checks are admission-only), so a third
                    // visitor may already be waiting unadmitted. What this
                    // proof needs is that both ADMITTED cycles were real,
                    // accelerated and physically routed.
                    && admittedRecruitmentCycles.size() == 2
                    && acceleratedRecruitmentCycles.containsAll(admittedRecruitmentCycles)
                    && travelerRoutesProven.containsAll(admittedRecruitmentCycles)
                    && exactBuilding(settlement, BuildingType.TAVERN) != null
                    && exactBuilding(settlement, BuildingType.TAVERN).valid
                    && barracks != null && barracks.valid
                    && watchtower != null && watchtower.valid
                    && guard != null && guard.getMainHandItem().is(Items.IRON_SWORD)
                    && archer != null && archer.getMainHandItem().is(Items.BOW)
                    && countInBuilding(level, barracks,
                        Items.LEATHER_CHESTPLATE) == 1
                    // Couriers now keep the Watchtower topped up from the
                    // Warehouse (up to one quiver), so the fixture's 8 is a floor.
                    && countInBuilding(level, watchtower, Items.ARROW) >= 8
                    && countInBuilding(level, watchtower, Items.ARROW)
                        <= SettlerEntity.ARCHER_QUIVER_CAPACITY
                    && terminalWarehouseCrop >= 1
                    && courier.bag.isEmpty()
                    && GuardAssignmentService.validate(level, settlement,
                        guard, false).valid()
                    && GuardAssignmentService.validate(level, settlement,
                        archer, false).valid()
                    && archerOrder.pos().isPresent()
                    && archer.blockPosition().distSqr(
                        archerOrder.pos().orElseThrow()) <= 2.25D
                    && settlement.raidLifecycle.firstState()
                        == FirstRaidState.SCHEDULED
                    && settlement.raidLifecycle.queuedPlan().isPresent()
                    && observed.stage()
                        == RaidQaFixtureService.Stage.READY_BEFORE_FIRST_RAID
                    && observed.ready() && observed.execution().ready(),
                "vanilla physical growth, recruitment and defender logistics "
                    + "must reach one exact warned-but-unstarted raid; "
                    + "maxCropAge=" + maximumObservedCropAge[0]
                    + "; population=" + settlement.population()
                    + "; beds=" + settlement.validBedCount()
                    + "; admitted=" + admitted.size()
                    + "; accelerated=" + acceleratedRecruitmentCycles
                    + "; routesProven=" + travelerRoutesProven
                    + "; admittedCycles=" + admittedRecruitmentCycles
                    + "; recruitment=" + settlement.recruitment
                    + "; tavern=" + (exactBuilding(settlement, BuildingType.TAVERN) != null
                        && exactBuilding(settlement, BuildingType.TAVERN).valid)
                    + "; barracks=" + (barracks != null && barracks.valid)
                    + "; watchtower=" + (watchtower != null && watchtower.valid)
                    + "; towerArrows=" + (watchtower == null ? -1 : countInBuilding(level, watchtower, Items.ARROW))
                    + "; courierBag=" + (courier.bag.isEmpty() ? "empty" : "busy")
                    + "; state=" + settlement.raidLifecycle.firstState()
                    + "; stage=" + observed.stage() + "; ready=" + observed.ready()
                    + "; exec=" + observed.execution().blockers()
                    + "; archerPostDist=" + (archer == null || archerOrder.pos().isEmpty() ? -1
                        : archer.blockPosition().distSqr(archerOrder.pos().orElseThrow()))
                    + "; archerBow=" + (archer != null && archer.getMainHandItem().is(Items.BOW))
                    + "; guardSword=" + (guard != null && guard.getMainHandItem().is(Items.IRON_SWORD))
                    + "; chest=" + (barracks == null ? -1 : countInBuilding(level, barracks, Items.LEATHER_CHESTPLATE))
                    + "; crop=" + terminalWarehouseCrop
                    + "; guardValid=" + (guard != null && GuardAssignmentService.validate(level, settlement, guard, false).valid())
                    + "; archerValid=" + (archer != null && GuardAssignmentService.validate(level, settlement, archer, false).valid())
                    + "; plan=" + settlement.raidLifecycle.queuedPlan().isPresent());

            assertFixtureAccess(helper, settlement);
            // A completed armour receipt is target-local: replay may not turn
            // another real Courier-owned item into a failed armour delivery.
            courier.bag.setItem(0, new net.minecraft.world.item.ItemStack(
                Items.STICK));
            RaidQaFixtureService.Result terminalReplay =
                RaidQaFixtureService.prepare(level, actor,
                    origin.offset(100, 0, 0));
            int terminalHistoryAfter = RequestLedgerSavedData.existing(level)
                .existing(settlement.id).terminalHistory().size();
            helper.assertTrue(terminalReplay.ready()
                    && settlement.buildings.size() == terminalBuildings
                    && settlement.population() == 6
                    && Development.revisionOf(level, settlement)
                        == terminalDevelopment
                    && terminalHistoryAfter == terminalHistoryBefore
                    && settlement.guardOrders.order(guard.getUUID())
                        .orElseThrow().revision() == guardOrderRevision
                    && settlement.guardOrders.order(archer.getUUID())
                        .orElseThrow().revision() == archerOrderRevision
                    && countInBuilding(level, barracks,
                        Items.LEATHER_CHESTPLATE) == 1
                    && countInBuilding(level, watchtower, Items.ARROW) == 8
                    && countInBuilding(level, warehouse, Items.WHEAT)
                        == terminalWarehouseCrop
                    && courier.bag.getItem(0).is(Items.STICK)
                    && courier.bag.getItem(0).getCount() == 1
                    && actor.getInventory().countItem(Items.BONE_MEAL)
                        == boneMealAfter,
                "terminal prepare replay must conserve buildings, residents, "
                    + "orders, requests, loadouts and unused physical bone meal");

            RaidQaFixtureService.Result startEligible =
                RaidQaFixtureService.status(level, actor);
            helper.assertTrue(startEligible.stage()
                    == RaidQaFixtureService.Stage.READY_BEFORE_FIRST_RAID
                    && startEligible.ready()
                    && startEligible.domain().blockedBy(
                        com.hearthstead.settlement.raid.FirstRaidReadinessService
                            .Blocker.RAID_STATE_NOT_PREPARING)
                    && startEligible.execution().ready()
                    && settlement.raidLifecycle.firstState()
                        == FirstRaidState.SCHEDULED
                    && settlement.raidLifecycle.queuedPlan().isPresent(),
                "a prepared real scheduled raid must report start eligible; "
                    + "status=" + startEligible);
            RaidQaFixtureService.StartResult started =
                RaidQaFixtureService.start(level, actor);
            int participants = settlement.raidLifecycle.participants().size();
            RaidQaFixtureService.StartResult startReplay =
                RaidQaFixtureService.start(level, actor);
            helper.assertTrue(started.outcome()
                    == RaidQaFixtureService.StartOutcome.STARTED
                    && started.spawned() == RaidDirector.FIRST_RAID_BAND_SIZE
                    && settlement.raidLifecycle.firstState()
                        == FirstRaidState.ACTIVE
                    && participants == RaidDirector.FIRST_RAID_BAND_SIZE
                    && startReplay.outcome()
                        == RaidQaFixtureService.StartOutcome.ALREADY_STARTED
                    && settlement.raidLifecycle.participants().size()
                        == participants,
                "one marker-bound start must enter real RaidDirector exactly "
                    + "once; first=" + started + "; replay=" + startReplay);
            List<com.hearthstead.entity.RaiderEntity> raiders =
                level.getEntitiesOfClass(com.hearthstead.entity.RaiderEntity.class,
                    // The band forms up outside the claim (radius + 8..24,
                    // captain sweep up to +16 more), not at the old 26..38.
                    new net.minecraft.world.phys.AABB(origin).inflate(
                        RaidDirector.spawnMaxDistance(settlement.radius)
                            + RaidDirector.CAPTAIN_EXTRA_REACH + 2.0D),
                    raider -> settlement.id.equals(raider.settlementId()));
            // Prove physical entry by an original raid participant using its
            // ordinary movement or breach behavior. This is not a full-fight
            // oracle and does not assume which entrance or defender it chooses.
            raidRouteVerificationScheduled[0] = true;
            long raidStartedAt = level.getGameTime();
            UUID observedArcherId = archer.getUUID();
            boolean[] entryLatched = {false};
            long[] entryElapsed = {-1L};
            long[] postEntryWindowTicks = {0L};
            boolean[] postEntryObservationClipped = {false};
            boolean[] archerShotLatched = {false};
            final long maximumPostEntryObservationTicks = 700L;
            // A just-created mob has not had a physics tick, so ground
            // navigation correctly refuses a route in this same spawn tick.
            // Keep the entire physical footprint ticking and inspect the
            // exact sealed roster only after ordinary movement has started.
            observeRaid[0] = () -> {
                long elapsed = level.getGameTime() - raidStartedAt;
                if (elapsed < 20L) return;
                if (elapsed % 20L == 0L) {
                    logArcherObservation(level, settlement, watchtower,
                        observedArcherId, raiders, origin, elapsed,
                        entryLatched[0] ? "post_entry" : "approach",
                        elapsed == 20L);
                }
                int shots = archerShotsFired(level, observedArcherId);
                if (shots > 0 && !archerShotLatched[0]) {
                    archerShotLatched[0] = true;
                    logArcherObservation(level, settlement, watchtower,
                        observedArcherId, raiders, origin, elapsed,
                        "archer_fired:" + shots, true);
                }
                if (elapsed == 20L) helper.assertTrue(raiders.size() == RaidDirector.FIRST_RAID_BAND_SIZE
                        && raiders.stream().allMatch(raider -> raider.isAlive()
                            && level.getEntity(raider.getUUID()) == raider
                            && raider.tickCount >= 5)
                        && settlement.raidLifecycle.participants().size() == RaidDirector.FIRST_RAID_BAND_SIZE,
                    "all " + RaidDirector.FIRST_RAID_BAND_SIZE + " exact raid participants must survive and tick "
                        + "before the route oracle; roster=" + raiders.stream()
                            .map(raider -> raider.getUUID() + ":alive="
                                + raider.isAlive() + ":ticks=" + raider.tickCount)
                            .toList());
                BlockPos raidGate = origin.offset(0, 1, 1);
                helper.assertTrue(level.getBlockState(raidGate)
                        .is(Blocks.OAK_FENCE_GATE)
                        && level.getBlockState(raidGate)
                            .getValue(FenceGateBlock.OPEN),
                    "only the explicit raid start must open the controlled west gate");
                // Observe production movement/breaching, not a fresh full path
                // beyond vanilla's search range. Entry is an actual grounded
                // original band member inside the physical settlement perimeter.
                boolean entered = raiders.stream().anyMatch(raider -> {
                    BlockPos pos = raider.blockPosition();
                    return raider.isAlive() && raider.onGround()
                        && pos.getY() == origin.getY() + 1
                        && pos.getX() > origin.getX() && pos.getX() < origin.getX() + 31
                        && pos.getZ() > origin.getZ() && pos.getZ() < origin.getZ() + 31;
                });
                if (entered && !entryLatched[0]) {
                    entryLatched[0] = true;
                    entryElapsed[0] = elapsed;
                    // The original fixture's 7,900-tick circuit breaker is
                    // authoritative. Observation is optional evidence only,
                    // so a valid late entry must clean up before that bound.
                    postEntryWindowTicks[0] = Math.min(
                        maximumPostEntryObservationTicks,
                        Math.max(0L, 7_899L - helper.getTick()));
                    postEntryObservationClipped[0] = postEntryWindowTicks[0]
                        < maximumPostEntryObservationTicks;
                    logArcherObservation(level, settlement, watchtower,
                        observedArcherId, raiders, origin, elapsed, "entry", true);
                }
                if (!entryLatched[0] && elapsed < 1_200L) return;
                helper.assertTrue(entryLatched[0],
                    "the ordinary band must physically enter the settlement within 1200 ticks; "
                        + raiders.stream().map(raider -> raider.getUUID() + ":pos="
                            + raider.blockPosition() + ":ground=" + raider.onGround()
                            + ":alive=" + raider.isAlive() + ":objective=" + raider.objectivePos()
                            + ":target=" + raider.getNavigation().getTargetPos()
                            + ":done=" + raider.getNavigation().isDone()).toList());
                if (elapsed - entryElapsed[0] < postEntryWindowTicks[0]) return;
                logArcherObservation(level, settlement, watchtower,
                    observedArcherId, raiders, origin, elapsed,
                    postEntryObservationClipped[0]
                        ? "post_entry_window_clipped" : "post_entry_window_complete",
                    true);
                // Preserve the original late-entry contract: the added combat
                // observation cannot turn a valid entry into a timeout. A full
                // window, however, must witness the ordinary Archer goal loose
                // a real volley from the existing Watchtower arrows.
                if (!postEntryObservationClipped[0]) {
                    helper.assertTrue(archerShotLatched[0],
                        "the ordinary first-raid KORN route must reach one "
                            + "eligible Watchtower shot without a forced target, "
                            + "teleport, leash change or combat cleanup victory");
                } else {
                    Hearthstead.LOGGER.info("HSQA_RAID_ARCHER event=post_entry_combat_observation_clipped elapsed={} archer={} shots={}",
                        elapsed, observedArcherId, shots);
                }
                // This wider fixture lives above the runner's template box.
                // End its exact actors before releasing tickets, otherwise
                // later batches can resume their old paths and inherit their
                // trace history. All physical raid assertions precede cleanup.
                for (UUID memberId : members) {
                    var entity = level.getEntity(memberId);
                    if (entity == null) continue;
                    helper.assertTrue(entity instanceof SettlerEntity,
                        "fixture member UUID must still identify its settler");
                    SettlerEntity member = (SettlerEntity) entity;
                    helper.assertTrue(settlement.id.equals(
                            member.boundOrTargetSettlementId()),
                        "cleanup must not remove another settlement's citizen");
                    member.getNavigation().stop();
                    member.discard();
                    helper.assertTrue(member.isRemoved(),
                        "completed fixture citizen must be removed before ticket release");
                }
                for (var raider : raiders) {
                    helper.assertTrue(settlement.id.equals(raider.settlementId()),
                        "cleanup must retain the original raid's settlement identity");
                    raider.getNavigation().stop();
                    raider.discard();
                    helper.assertTrue(raider.isRemoved(),
                        "completed fixture raider must be removed before ticket release");
                }
                releaseOwnedChunks.run();
                for (long chunkKey : addedChunkTickets) {
                    helper.assertTrue(!level.getForcedChunks().contains(chunkKey),
                        "completed fixture must release only its own chunk ticket: "
                            + new ChunkPos(chunkKey));
                }
                for (long chunkKey : inheritedChunkTickets) {
                    helper.assertTrue(level.getForcedChunks().contains(chunkKey),
                        "completed fixture must preserve framework-owned tickets: "
                            + new ChunkPos(chunkKey));
                }
                helper.succeed();
            };
        });
    }

    private static boolean openOakDoor(ServerLevel level, BlockPos lower,
            Direction facing) {
        return intactOakDoor(level, lower, facing)
            && level.getBlockState(lower).getValue(DoorBlock.OPEN);
    }

    private static boolean openPoweredIronDoor(ServerLevel level, BlockPos lower,
            Direction facing) {
        BlockState lowerState = level.getBlockState(lower);
        BlockState upperState = level.getBlockState(lower.above());
        return lowerState.is(Blocks.IRON_DOOR) && upperState.is(Blocks.IRON_DOOR)
            && lowerState.getValue(DoorBlock.FACING) == facing
            && upperState.getValue(DoorBlock.FACING) == facing
            && lowerState.getValue(DoorBlock.HALF)
                == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER
            && upperState.getValue(DoorBlock.HALF)
                == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER
            && lowerState.getValue(DoorBlock.HINGE) == upperState.getValue(DoorBlock.HINGE)
            && lowerState.getValue(DoorBlock.OPEN)
            && upperState.getValue(DoorBlock.OPEN)
            && lowerState.getValue(DoorBlock.POWERED)
            && upperState.getValue(DoorBlock.POWERED);
    }

    private static boolean intactOakDoor(ServerLevel level, BlockPos lower,
            Direction facing) {
        BlockState lowerState = level.getBlockState(lower);
        BlockState upperState = level.getBlockState(lower.above());
        return lowerState.is(Blocks.OAK_DOOR) && upperState.is(Blocks.OAK_DOOR)
            && lowerState.getValue(DoorBlock.FACING) == facing
            && upperState.getValue(DoorBlock.FACING) == facing
            && lowerState.getValue(DoorBlock.HALF)
                == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER
            && upperState.getValue(DoorBlock.HALF)
                == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER
            && lowerState.getValue(DoorBlock.HINGE) == upperState.getValue(DoorBlock.HINGE)
            && lowerState.getValue(DoorBlock.OPEN) == upperState.getValue(DoorBlock.OPEN);
    }

    /** Uses the existing Archer test seam only to observe an ordinary volley. */
    private static int archerShotsFired(ServerLevel level, UUID archerId) {
        if (!(level.getEntity(archerId) instanceof SettlerEntity archer)) {
            return -1;
        }
        return archer.goalSelector.getAvailableGoals().stream()
            .map(wrapped -> wrapped.getGoal())
            .filter(ArcherAttackGoal.class::isInstance)
            .map(ArcherAttackGoal.class::cast)
            .mapToInt(ArcherAttackGoal::shotsFired)
            .findFirst().orElse(-1);
    }

    private static String travelerSnapshot(ServerLevel level, Settlement settlement) {
        RecruitmentTransaction transaction = settlement.recruitment;
        if (transaction == null || transaction.travelerId() == null) return "no identity";
        var entity = level.getEntity(transaction.travelerId());
        if (!(entity instanceof SettlerEntity guest)) return "unloaded or wrong type:" + entity;
        BlockPos approach = SettlementManager.travelerTavernApproach(level, guest);
        return "uuid=" + guest.getUUID() + ";alive=" + guest.isAlive()
            + ";traveler=" + guest.isTraveler() + ";target=" + guest.getTargetSettlementId()
            + ";position=" + guest.blockPosition() + ";anchorDistanceSq="
            + guest.blockPosition().distSqr(transaction.tavernAnchor())
            + ";approach=" + approach + ";approachDistanceSq="
            + (approach == null ? "none" : guest.blockPosition().distSqr(approach))
            + ";record=" + settlement.record(guest.getUUID());
    }

    /** Read-only fixture evidence; it neither assigns a target nor advances combat. */
    private static void logArcherObservation(ServerLevel level,
                                             Settlement settlement,
                                             Building watchtower,
                                             UUID archerId,
                                             List<com.hearthstead.entity.RaiderEntity> raiders,
                                             BlockPos origin, long elapsed,
                                             String event, boolean detailedCandidates) {
        var raw = level.getEntity(archerId);
        SettlerEntity archer = raw instanceof SettlerEntity candidate ? candidate : null;
        GuardOrder order = settlement.guardOrders.order(archerId).orElse(null);
        LivingEntity target = archer == null ? null : archer.getTarget();
        BlockPos post = order == null ? null : order.pos().orElse(null);
        double archerPostDistanceSq = archer == null || post == null ? -1.0D
            : archer.blockPosition().distSqr(post);
        double targetPostDistanceSq = target == null || order == null ? -1.0D
            : nearestOrderAnchorDistanceSq(order, target.blockPosition());
        boolean urgent = target instanceof com.hearthstead.entity.RaiderEntity raider
            && urgentFor(settlement, archer, raider);
        boolean targetOrderActive = order != null && order.activeAt(level.getGameTime());
        boolean targetWithinLeash = !targetOrderActive || targetPostDistanceSq >= 0.0D
            && RaidThreatBoard.leashAllows(targetPostDistanceSq,
                order.leashRadius(), urgent);
        com.hearthstead.entity.RaiderEntity rayTarget =
            target instanceof com.hearthstead.entity.RaiderEntity raider
                && raider.isAlive() ? raider : nearestLiveEligibleRaider(
                    settlement, archer, order, targetOrderActive, raiders);
        String lineOfSightRay = rayTarget == null || archer == null
            ? "none" : lineOfSightRay(level, archer, rayTarget);
        List<String> ownedArrows = level.getEntitiesOfClass(AbstractArrow.class,
                new net.minecraft.world.phys.AABB(origin).inflate(96.0D),
                arrow -> arrow.isAlive())
            .stream().map(arrow -> OwnedProjectileLedger.inspect(arrow))
            .filter(java.util.Objects::nonNull)
            .filter(inspection -> archerId.equals(inspection.ownerId()))
            .map(inspection -> inspection.projectileId() + ":contacts="
                + inspection.committedContacts() + ":owner=" + inspection.ownerId())
            .toList();
        String roster = raiders.stream().map(raider -> raider.getUUID()
            + ":alive=" + raider.isAlive() + ":ticks=" + raider.tickCount)
            .collect(java.util.stream.Collectors.joining(","));
        List<String> candidates = detailedCandidates ? raiders.stream()
            .map(raider -> candidateSnapshot(level, settlement, archer, order,
                raider))
            .toList() : List.of();
        Hearthstead.LOGGER.info("HSQA_RAID_ARCHER event={} elapsed={} archer={} live={} pos={} "
                + "activity={} orderMode={} post={} leash={} archerPostDistanceSq={} target={} "
                + "targetType={} targetAlive={} los={} targetDistanceSq={} targetPostDistanceSq={} "
                + "targetUrgent={} targetWithinLeash={} quiver={} quiverSource={} towerArrowStock={} "
                + "ownedArrowsWithin96={} sealedRoster={} detailedCandidates={} lineOfSightRay={}",
            event, elapsed, archerId, archer != null && archer.isAlive(),
            archer == null ? null : archer.position(),
            archer == null ? null : archer.getActivity(),
            order == null ? null : order.mode(), post,
            order == null ? -1 : order.leashRadius(), archerPostDistanceSq,
            target == null ? null : target.getUUID(),
            target == null ? null : target.getClass().getSimpleName(),
            target != null && target.isAlive(),
            archer != null && target != null && archer.hasLineOfSight(target),
            archer == null || target == null ? -1.0D : archer.distanceToSqr(target),
            targetPostDistanceSq, urgent, targetWithinLeash,
            archer == null ? -1 : archer.archerQuiverCount(),
            archer == null ? null : archer.archerQuiverSourceBuildingId(),
            countInBuilding(level, watchtower, Items.ARROW), ownedArrows, roster,
            candidates, lineOfSightRay);
    }

    private static com.hearthstead.entity.RaiderEntity nearestLiveEligibleRaider(
            Settlement settlement, SettlerEntity archer, GuardOrder order,
            boolean orderActive,
            List<com.hearthstead.entity.RaiderEntity> raiders) {
        if (archer == null) return null;
        return raiders.stream()
            .filter(com.hearthstead.entity.RaiderEntity::isAlive)
            .filter(archer::canAttack)
            .filter(raider -> !orderActive || order != null
                && RaidThreatBoard.leashAllows(nearestOrderAnchorDistanceSq(order,
                    raider.blockPosition()), order.leashRadius(),
                    urgentFor(settlement, archer, raider)))
            .min(java.util.Comparator.comparingDouble(archer::distanceToSqr))
            .orElse(null);
    }

    /** Read-only mirror of LivingEntity's LOS clip; emits the first physical
     * blocker for the live target or nearest order-eligible raider. */
    private static String lineOfSightRay(ServerLevel level, SettlerEntity archer,
                                         com.hearthstead.entity.RaiderEntity target) {
        Vec3 from = archer.getEyePosition();
        Vec3 to = target.getEyePosition();
        BlockHitResult hit = level.clip(new ClipContext(from, to,
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, archer));
        return "target=" + target.getUUID() + ":targetPos=" + target.blockPosition()
            + ":from=" + from + ":to=" + to + ":type=" + hit.getType()
            + ":block=" + hit.getBlockPos() + ":state="
            + level.getBlockState(hit.getBlockPos()) + ":face="
            + hit.getDirection() + ":location=" + hit.getLocation();
    }

    private static String candidateSnapshot(ServerLevel level,
                                            Settlement settlement,
                                            SettlerEntity archer,
                                            GuardOrder order,
                                            com.hearthstead.entity.RaiderEntity raider) {
        LivingEntity victim = raider.getTarget();
        double archerDistanceSq = archer == null ? -1.0D
            : archer.distanceToSqr(raider);
        double postDistanceSq = order == null ? -1.0D
            : nearestOrderAnchorDistanceSq(order, raider.blockPosition());
        boolean orderActive = order != null && order.activeAt(level.getGameTime());
        boolean urgent = urgentFor(settlement, archer, raider);
        boolean leashEligible = !orderActive || postDistanceSq >= 0.0D
            && RaidThreatBoard.leashAllows(postDistanceSq,
                order.leashRadius(), urgent);
        boolean canAttack = archer != null && archer.canAttack(raider);
        boolean hasLos = archer != null && archer.hasLineOfSight(raider);
        double followRange = archer == null ? -1.0D : archer.getAttributeValue(
            net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE);
        return raider.getUUID() + ":alive=" + raider.isAlive()
            + ":pos=" + raider.blockPosition() + ":target="
            + (victim == null ? null : victim.getUUID()) + ":targetType="
            + (victim == null ? null : victim.getClass().getSimpleName())
            + ":canAttack=" + canAttack + ":los=" + hasLos
            + ":archerDistanceSq=" + archerDistanceSq + ":followRange="
            + followRange + ":postDistanceSq=" + postDistanceSq
            + ":urgent=" + urgent + ":leashEligible=" + leashEligible;
    }

    private static boolean urgentFor(Settlement settlement, SettlerEntity defender,
                                     com.hearthstead.entity.RaiderEntity raider) {
        LivingEntity victim = raider.getTarget();
        return defender != null && victim == defender
            || victim instanceof net.minecraft.world.entity.player.Player
            || victim instanceof SettlerEntity settler
                && settler.settlement() != null
                && settlement.id.equals(settler.settlement().id);
    }

    private static double nearestOrderAnchorDistanceSq(GuardOrder order,
                                                        BlockPos candidate) {
        if (order == null || candidate == null) return -1.0D;
        List<BlockPos> anchors = order.mode() == GuardOrder.Mode.PATROL_ROUTE
            ? order.patrolPoints() : order.pos().map(List::of).orElse(List.of());
        return anchors.stream().mapToDouble(candidate::distSqr).min()
            .orElse(-1.0D);
    }

    /** Test clock setup for the production daily visitor gate, never a spawn seam. */
    private static void advanceToNextNaturalVisitorWindow(ServerLevel level,
                                                           Settlement settlement) {
        long dayTime = level.getDayTime();
        long day = Math.floorDiv(dayTime, 24_000L);
        if (Math.floorMod(dayTime, 24_000L)
                > SettlementManager.TAVERN_VISITOR_ARRIVAL_TIME) {
            day++;
        }
        day = Math.max(day, settlement.lastTavernVisitorDay + 1L);
        level.setDayTime(day * 24_000L
            + SettlementManager.TAVERN_VISITOR_ARRIVAL_TIME);
    }

    /** Restore a work period only after the same real admission was committed. */
    private static void advanceToNextWorkMorning(ServerLevel level) {
        long now = level.getDayTime();
        long nextMorning = (Math.floorDiv(now, 24_000L) + 1L) * 24_000L
            + 2_000L;
        level.setDayTime(nextMorning);
    }

    private static void assertFixtureAccess(GameTestHelper helper,
            Settlement settlement) {
        ServerLevel level = helper.getLevel();
        for (Building building : settlement.buildings) {
            PlaqueBlockEntity plaque = level.getBlockEntity(building.plaquePos)
                    instanceof PlaqueBlockEntity found ? found : null;
            helper.assertTrue(building.valid && plaque != null
                    && plaque.state() == PlaqueState.LINKED_VALID
                    && plaque.settlementFor(level) == settlement
                    && plaque.building(level) == building,
                "every showcase silhouette must retain its exact valid Plaque: "
                    + building.type);
            for (BlockPos storage : WorkerStorageAuthority.loadedContainers(
                    level, building)) {
                SettlerEntity probe = SettlementManager.loadedMembers(level,
                    settlement).getFirst();
                Vec3 originalPosition = probe.position();
                boolean contact = false;
                // This synchronous contact inspection must not relocate a live
                // resident before the next AI tick or contaminate its trace.
                try {
                    for (Direction direction : Direction.Plane.HORIZONTAL) {
                        BlockPos feet = storage.relative(direction);
                        if (level.getBlockState(feet).isAir()
                            && level.getBlockState(feet.above()).isAir()
                            && level.getBlockState(feet.below()).isFaceSturdy(
                                level, feet.below(), Direction.UP)) {
                            probe.setPos(feet.getX() + 0.5D, feet.getY(),
                                feet.getZ() + 0.5D);
                            contact |= ContainerApproach.inspect(level, probe,
                                storage).canInteract();
                        }
                    }
                } finally {
                    probe.setPos(originalPosition.x, originalPosition.y,
                        originalPosition.z);
                }
                helper.assertTrue(probe.position().equals(originalPosition),
                    "contact inspection must preserve the live resident's position");
                helper.assertTrue(contact,
                    "each physical storage block needs an explicit standable "
                        + "approach: " + building.type + "@" + storage);
            }
        }
    }

    private static int firstEmptyHotbar(ServerPlayer actor) {
        for (int slot = 0; slot < 9; slot++) {
            if (actor.getInventory().getItem(slot).isEmpty()) return slot;
        }
        return -1;
    }

    private static Building exactBuilding(Settlement settlement,
                                          BuildingType type) {
        List<Building> matches = settlement.buildings.stream()
            .filter(building -> building != null && building.type == type)
            .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static SettlerEntity exactWorker(ServerLevel level,
                                             Settlement settlement,
                                             Building building,
                                             Profession profession) {
        List<SettlerEntity> matches = SettlementManager.loadedMembers(level,
                settlement).stream()
            .filter(member -> member.isAlive()
                && member.getProfession() == profession
                && building.workers.contains(member.getUUID()))
            .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static int countInBuilding(ServerLevel level, Building building,
                                       Item item) {
        return WorkerStorageAuthority.loadedContainers(level, building).stream()
            .map(level::getBlockEntity)
            .filter(Container.class::isInstance)
            .map(Container.class::cast)
            .mapToInt(container -> count(container, item))
            .sum();
    }

    private static int count(Container container, Item item) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(item)) {
                count += container.getItem(slot).getCount();
            }
        }
        return count;
    }
}
