package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.block.PlaqueBlock;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.block.PlaqueItemData;
import com.hearthstead.building.BuildingType;
import com.hearthstead.building.PlaqueState;
import com.hearthstead.entity.ai.GroundCollectionSession;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.item.JobEmblemItem;
import com.hearthstead.menu.HearthMenu;
import com.hearthstead.menu.SettlerInventoryMenu;
import com.hearthstead.network.HearthMayorAction;
import com.hearthstead.network.HearthMayorSnapshot;
import com.hearthstead.network.HearthNetwork;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.FoundingJourneyProgress;
import com.hearthstead.settlement.PendingPlayerDeliveryLedger;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.RecruitmentTransaction;
import com.hearthstead.settlement.development.Development;
import com.hearthstead.settlement.development.DevelopmentNode;
import com.hearthstead.settlement.development.DevelopmentQuests;
import com.hearthstead.settlement.development.JobEmblemCatalog;
import com.hearthstead.settlement.equipment.EquipmentRequirement;
import com.hearthstead.settlement.equipment.EquipmentRequest;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.equipment.WorkplaceStorage;
import com.hearthstead.settlement.guard.GuardAssignmentService;
import com.hearthstead.settlement.journey.JourneyIds;
import com.hearthstead.settlement.journey.JourneyOutcome;
import com.hearthstead.settlement.journey.JourneyServerHooks;
import com.hearthstead.settlement.journey.JourneyState;
import com.hearthstead.settlement.raid.FirstRaidReadiness;
import com.hearthstead.settlement.raid.FirstRaidReadinessService;
import com.hearthstead.settlement.raid.RaidDirector;
import com.hearthstead.settlement.raid.RaidLogEntry;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import com.hearthstead.settlement.request.RequestLedgerService;
import com.hearthstead.settlement.request.RequestPriority;
import com.hearthstead.settlement.request.RequestRecord;
import com.hearthstead.settlement.state.FirstRaidState;
import com.hearthstead.settlement.state.FoundingJourney;
import com.hearthstead.settlement.state.GuardOrder;
import com.hearthstead.settlement.state.RaidLifecycle;
import com.hearthstead.settlement.state.RaidParticipantRecord;
import com.hearthstead.settlement.work.FarmWorkApproach;
import com.hearthstead.settlement.work.WorkerProvenanceService;
import com.hearthstead.settlement.work.WorkerStackProvenance;
import com.hearthstead.settlement.workzone.WorkZone;
import com.hearthstead.settlement.workzone.WorkZoneService;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Black-box release gate from founding through definitive first-raid close. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class FirstRaidReadinessGameTests {
    // Keep the founding authority central in its 16x16 arena. GameTests share
    // one SavedData root and intentionally bypass the production founding
    // distance, so an edge Hearth lets a retained neighbouring test
    // settlement become nearer to this fixture's z=0 plaques.
    private static final BlockPos HEARTH = new BlockPos(7, 1, 7);
    private static final BlockPos HOUSE_ORIGIN = new BlockPos(1, 0, 1);
    private static final BlockPos SECOND_HOUSE_ORIGIN = new BlockPos(1, 0, 8);
    private static final BlockPos CAMP_ORIGIN = new BlockPos(9, 0, 1);
    private static final BlockPos FARM_ORIGIN = new BlockPos(0, 5, 1);
    private static final BlockPos WAREHOUSE_ORIGIN = new BlockPos(8, 5, 1);
    // Keep the nearest lawful outside origin on this arena's own side of
    // its isolation wall, so a one-step admission fixture has a real route.
    private static final BlockPos TAVERN_ORIGIN = new BlockPos(15, 0, 12);
    private static final BlockPos BARRACKS_ORIGIN = new BlockPos(8, 10, 1);
    private static final BlockPos WATCHTOWER_ORIGIN = new BlockPos(8, 10, 10);
    private static final BlockPos SECOND_WATCHTOWER_ORIGIN = new BlockPos(1, 10, 10);
    private static final BlockPos LUMBER_TREE_ROOT = new BlockPos(6, 1, 6);
    private static final BlockPos FARM_FIELD = new BlockPos(6, 1, 10);

    @GameTest(template = "empty64", timeoutTicks = 120,
        batch = "first_raid_paid_provenance_recovery")
    public void reassignedFounderNeedsNewPaidHireAndNewPhysicalProduction(GameTestHelper h) {
        Fixture f = foundedKnowledgeBuildingsAndGuildmaster(h, "Renewedwatch");
        for (SettlerEntity member : SettlementManager.loadedMembers(h.getLevel(), f.settlement)) member.setNoAi(true);
        purchaseAndHireDirectly(h, f); viewLumberInventory(h, f);
        commitLumberZoneForJourney(h, f); equipPhysical(h, f.camp, f.worker, Items.IRON_AXE);
        ItemStack first = performLumberWork(h, f);
        h.assertTrue(FoundingJourneyProgress.noteLogStored(h.getLevel(), f.settlement,
            f.camp, f.worker, first, first.getCount()), "first real paid production seals the original proof");
        var proof = f.settlement.firstRaidReadiness;
        UUID oldWorker = proof.workerId(); int oldRevision = proof.revision();
        var data = com.hearthstead.settlement.work.WorkerProvenanceSavedData.get(h.getLevel());
        var oldAction = data.actionsForSettlement(f.settlement.id).stream()
            .filter(a -> a.workerId().equals(oldWorker) && a.workTerminal()).findFirst().orElseThrow();
        var oldReceipt = data.receiptsFor(oldAction.id()).getFirst();
        h.assertTrue(!proof.noteConsumedLumbererEmblemHire(h.getLevel(), f.settlement, f.camp, f.worker)
            && proof.revision() == oldRevision, "still-valid original employment cannot reset its stored proof");

        Building otherJob = GameTestFixtures.registerWithBounds(h, f.settlement, BuildingType.FARMHOUSE,
            new BlockPos(2, 6, 2), new BlockPos(2, 6, 3),
            net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(
                h.absolutePos(new BlockPos(0, 5, 0)), h.absolutePos(new BlockPos(5, 8, 5))));
        h.assertTrue(Employment.hire(h.getLevel(), f.settlement, otherJob, f.worker).ok()
            && f.worker.getProfession() == Profession.FARMER, "real roster reassignment invalidates the old Lumberer");
        // Keep the original physical stock in the original worker's bag: no deletion/refund.
        Container chest = storage(h, f.storageRelative);
        int oldLogs = countIn(chest, Items.OAK_LOG);
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            if (chest.getItem(slot).is(Items.OAK_LOG)) {
                ItemStack moved = chest.removeItem(slot, chest.getItem(slot).getCount());
                h.assertTrue(f.worker.bag.addItem(moved).isEmpty(), "old stored logs remain actual owned stock");
            }
        }
        Fixture replacement = new Fixture(f.settlement, f.hearth, f.house, f.secondHouse,
            f.camp, f.plaque, f.spare, f.farmer, f.worker, f.courier, f.builder, f.storageRelative);
        h.assertTrue(Employment.hire(h.getLevel(), f.settlement, f.camp, replacement.worker).ok(),
            "free hire fixture must establish real employment without paid authority");
        h.assertTrue(!proof.noteConsumedLumbererEmblemHire(h.getLevel(), f.settlement, f.camp, replacement.worker)
            && proof.workerId().equals(oldWorker) && proof.revision() == oldRevision,
            "free replacement cannot rewrite the old proof");
        Employment.dismiss(h.getLevel(), f.settlement, replacement.worker);
        standBeside(replacement.worker, f.plaque.getBlockPos());
        purchaseAndHireDirectly(h, replacement);
        h.assertTrue(proof.stage() == FirstRaidReadiness.Stage.EMBLEM_HIRED
            && proof.workerId().equals(replacement.worker.getUUID()) && proof.storedLogCount() == 0
            && proof.revision() == oldRevision + 1,
            "new paid hire must reopen only live proof, not carry old production into readiness");
        h.assertTrue(!proof.noteStoredProduction(h.getLevel(), f.settlement, f.camp,
                replacement.worker, first, first.getCount())
            && !proof.noteRecoveredStoredProduction(h.getLevel(), f.settlement, f.camp, replacement.worker, oldReceipt),
            "plain inventory assertions and old receipts cannot satisfy replacement production");
        Settlement alien = new Settlement(UUID.randomUUID(), "Unregistered", f.settlement.center);
        h.assertTrue(!proof.noteConsumedLumbererEmblemHire(h.getLevel(), alien, f.camp, replacement.worker)
            && !proof.noteRecoveredStoredProduction(h.getLevel(), alien, f.camp, replacement.worker, oldReceipt),
            "cross-settlement attempts cannot change pending ownership");
        var pending = proof.writeNbt();
        var malformed = pending.copy();
        malformed.putString("RecoveryHiredAt", "not-a-tick");
        h.assertTrue(FirstRaidReadiness.readNbt(malformed).stage() == FirstRaidReadiness.Stage.QUARANTINED,
            "malformed recovery authority must fail closed rather than become legacy founding proof");
        f.settlement.firstRaidReadiness = FirstRaidReadiness.readNbt(pending);
        h.assertTrue(f.settlement.firstRaidReadiness.writeNbt().equals(pending),
            "pending paid recovery gate survives exact save/load");
        h.runAfterDelay(2, () -> {
            equipPhysical(h, replacement.camp, replacement.worker, Items.IRON_AXE);
            ItemStack fresh = performLumberWork(h, replacement);
            var recovered = f.settlement.firstRaidReadiness;
            h.assertTrue(recovered.stage() == FirstRaidReadiness.Stage.PRODUCTION_STORED
                && recovered.workerId().equals(replacement.worker.getUUID())
                && recovered.revision() == oldRevision + 2
                && recovered.storedAtGameTime() == h.getLevel().getGameTime()
                && f.settlement.foundingJourney.phase() == FoundingJourney.Phase.COMPLETE,
                "new real deposit renews exact raid proof without replaying the completed founding Journey");
            h.assertTrue(countIn(chest, Items.OAK_LOG) == fresh.getCount()
                && countIn(f.worker.bag, Items.OAK_LOG) == oldLogs
                && replacement.worker.bag.isEmpty(), "old plus new logs remain conserved under distinct owners");
            h.assertTrue(!recovered.noteRecoveredStoredProduction(h.getLevel(), f.settlement, f.camp,
                replacement.worker, oldReceipt) && recovered.revision() == oldRevision + 2,
                "stale replay cannot renew or increment completed recovery");
            h.assertTrue(!FirstRaidReadinessService.assessDomain(h.getLevel(), f.settlement).ready(),
                "repairing Lumber proof does not bypass the remaining whole-roster/readiness requirements");
            h.succeed();
        });
    }

    @GameTest(template = "empty64", timeoutTicks = 500,
        batch = "first_raid_readiness_real_journey")
    public void realAuthoritativeJourneyUnlocksOneExactFirstRaid(
            GameTestHelper helper) {
        Fixture f = foundedKnowledgeBuildingsAndGuildmaster(helper, "Oakwatch");
        long rolledNotBefore = f.settlement.raidLifecycle.rolledNotBeforeNight();
        FirstRaidReadinessService.Report zeroDefenders =
            FirstRaidReadinessService.assessDomain(helper.getLevel(), f.settlement);
        helper.assertTrue(zeroDefenders.blockedBy(
                FirstRaidReadinessService.Blocker.GUARD_INVALID)
                && zeroDefenders.blockedBy(
                    FirstRaidReadinessService.Blocker.ARCHER_INVALID)
                && zeroDefenders.blockedBy(FirstRaidReadinessService.Blocker
                    .SETTLER_ROSTER_INSUFFICIENT),
            "zero defenders and four unassigned founders must fail the live first-raid gate");

        helper.assertTrue(!RaidDirector.queueFirstWarningIfDue(helper.getLevel(),
                f.settlement, rolledNotBefore)
                && f.settlement.raidLifecycle.queuedPlan().isEmpty(),
            "knowledge, buildings and a Guildmaster must not bypass emblem-backed production");
        helper.assertTrue(f.settlement.raidLifecycle.firstState()
                    == FirstRaidState.PREPARING
                && f.settlement.raidLifecycle.rolledNotBeforeNight()
                    == rolledNotBefore
                && f.settlement.raidLifecycle.firstWarningNight()
                    == RaidLifecycle.UNSET_NIGHT
                && f.settlement.raidLifecycle.firstAttackNight()
                    == RaidLifecycle.UNSET_NIGHT,
            "blocked readiness must preserve the founding roll without arming dates");

        Hire hire = purchaseAndHireDirectly(helper, f);
        helper.assertTrue(f.settlement.firstRaidReadiness.stage()
                == FirstRaidReadiness.Stage.EMBLEM_HIRED,
            "the charged player path must persist the exact emblem-backed worker");
        helper.assertTrue(hire.player.getMainHandItem().isEmpty(),
            "the successful first job must consume exactly one held Lumberer emblem");
        viewLumberInventory(helper, f);

        int evidenceRevision = f.settlement.firstRaidReadiness.revision();
        hire.player.setItemInHand(InteractionHand.MAIN_HAND,
            new ItemStack(ModItems.LUMBERER_EMBLEM.get()));
        ItemStack replay = hire.player.getMainHandItem();
        ((JobEmblemItem) replay.getItem()).interactLivingEntity(replay,
            hire.player, f.worker, InteractionHand.MAIN_HAND);
        helper.assertTrue(f.settlement.firstRaidReadiness.revision() == evidenceRevision
                && count(f.camp.workers, f.worker.getUUID()) == 1
                && hire.player.getMainHandItem().getCount() == 1,
            "repeating the direct emblem interaction must not consume, duplicate "
                + "or rewrite readiness evidence");

        commitLumberZoneForJourney(helper, f);

        equipPhysical(helper, f.camp, f.worker, Items.IRON_AXE);
        ItemStack offered = performLumberWork(helper, f);
        helper.assertTrue(!f.settlement.firstRaidReadiness.noteStoredProduction(
                helper.getLevel(), f.settlement, null, f.worker, offered, 1)
                && !f.settlement.firstRaidReadiness.noteStoredProduction(
                    helper.getLevel(), f.settlement, f.camp, f.spare, offered, 1)
                && f.settlement.firstRaidReadiness.revision() == evidenceRevision,
            "the wrong camp or worker must not author stored-production proof");

        helper.assertTrue(FoundingJourneyProgress.noteLogStored(helper.getLevel(),
                f.settlement, f.camp, f.worker, offered, offered.getCount())
                && DevelopmentQuests.noteLumberLogsStored(helper.getLevel(),
                    f.settlement, f.camp, f.worker, offered, offered.getCount()),
            "the same emblem-backed worker must advance through a real camp-storage log");
        helper.assertTrue(f.settlement.foundingJourney.phase()
                == FoundingJourney.Phase.COMPLETE
                && f.settlement.firstRaidReadiness.assess(helper.getLevel(), f.settlement)
                    == FirstRaidReadiness.Assessment.JOURNEY_INCOMPLETE,
            "the old House + Lumber Camp + one-log slice must remain fail-closed");

        JourneyV2 v2 = completeJourneyV2(helper, f);
        FirstRaidReadiness.Assessment readiness = f.settlement
            .firstRaidReadiness.assess(helper.getLevel(), f.settlement);
        FirstRaidReadinessService.Report readinessReport =
            FirstRaidReadinessService.assessDomain(helper.getLevel(), f.settlement);
        helper.assertTrue(readiness == FirstRaidReadiness.Assessment.READY
                && readinessReport.ready(),
            "only five housed settlers plus one ordered Guard and one ordered, "
                + "physically supplied Archer may become raid-ready; legacy="
                + readiness + "; blockers=" + readinessReport.blockers()
                + "; metrics=" + readinessReport.metrics() + "; journey="
                + f.settlement.journeyState.currentStep()
                    .map(step -> step.id().toString()).orElse("none"));

        // Patrol remains a valid everyday Guard order, but the authored first
        // raid promises one local melee bodyguard. Only a Stand Post carries
        // the exact issuing-player/leash authority that escort can honor.
        GuardOrder firstRaidGuardOrder = f.settlement.guardOrders
            .order(f.guard.getUUID()).orElseThrow();
        UUID guardIssuer = f.builder.getUUID();
        long guardOrderTick = helper.getLevel().getGameTime();
        BlockPos patrolA = f.guard.blockPosition();
        BlockPos patrolB = patrolA.east(2);
        helper.assertTrue(firstRaidGuardOrder.clear(guardIssuer,
                v2.barracks.id, guardOrderTick)
            && firstRaidGuardOrder.appendPatrolPoint(patrolA, guardIssuer,
                v2.barracks.id, guardOrderTick)
            && firstRaidGuardOrder.appendPatrolPoint(patrolB, guardIssuer,
                v2.barracks.id, guardOrderTick)
            && firstRaidGuardOrder.issuePatrol(GuardOrder.Traversal.LOOP,
                guardIssuer, v2.barracks.id, guardOrderTick),
            "fixture: the armed Guard must accept an ordinary Patrol order");
        FirstRaidReadinessService.Report patrolOnly =
            FirstRaidReadinessService.assessDomain(helper.getLevel(),
                f.settlement);
        helper.assertTrue(patrolOnly.blockedBy(
                FirstRaidReadinessService.Blocker.GUARD_ORDER_INVALID),
            "a Patrol-only Guard cannot promise the first raid's local bodyguard");
        helper.assertTrue(firstRaidGuardOrder.clear(guardIssuer,
                v2.barracks.id, guardOrderTick)
            && firstRaidGuardOrder.issueStand(f.guard.blockPosition(),
                Direction.NORTH, GuardOrder.DEFAULT_LEASH_RADIUS, guardIssuer,
                v2.barracks.id, guardOrderTick)
            && JourneyServerHooks.noteGuardAssignmentCommitted(f.builder,
                f.settlement, f.guard, firstRaidGuardOrder,
                firstRaidGuardOrder.revision())
            && FirstRaidReadinessService.assessDomain(helper.getLevel(),
                f.settlement).ready(),
            "restoring the Stand Post must restore first-raid readiness");

        var secondHouseBounds = f.secondHouse.bounds;
        List<BlockPos> secondHouseBeds = List.copyOf(f.secondHouse.beds);
        f.secondHouse.bounds = f.house.bounds;
        f.secondHouse.beds.clear();
        f.secondHouse.beds.addAll(f.house.beds);
        FirstRaidReadinessService.Report overlappingBeds =
            FirstRaidReadinessService.assessDomain(helper.getLevel(),
                f.settlement);
        helper.assertTrue(overlappingBeds.metrics().housingCapacity() == 4
                && overlappingBeds.blockedBy(
                    FirstRaidReadinessService.Blocker.HOUSING_INSUFFICIENT)
                && overlappingBeds.blockedBy(FirstRaidReadinessService.Blocker
                    .BUILDING_REGISTRY_CORRUPT),
            "two housing plaques must not double-credit the same four physical "
                + "bed heads; the duplicate authority must fail closed");
        Settlement overlappingReload = Settlement.readNbt(
            f.settlement.writeNbt(), SettlementSavedData.CURRENT_DATA_VERSION);
        f.secondHouse.bounds = secondHouseBounds;
        f.secondHouse.beds.clear();
        f.secondHouse.beds.addAll(secondHouseBeds);
        SettlementSavedData readinessData = SettlementSavedData.get(
            helper.getLevel());
        readinessData.settlements.put(overlappingReload.id,
            overlappingReload);
        FirstRaidReadinessService.Report overlappingBedsAfterRestart =
            FirstRaidReadinessService.assessDomain(helper.getLevel(),
                overlappingReload);
        helper.assertTrue(overlappingBedsAfterRestart.metrics().housingCapacity()
                    == 4
                && overlappingBedsAfterRestart.blockedBy(
                    FirstRaidReadinessService.Blocker.HOUSING_INSUFFICIENT)
                && overlappingBedsAfterRestart.blockedBy(
                    FirstRaidReadinessService.Blocker.BUILDING_REGISTRY_CORRUPT),
            "restart must preserve the four-head metric and may never turn "
                + "overlapping housing authority green");
        readinessData.settlements.put(f.settlement.id, f.settlement);
        readinessData.setDirty();

        f.secondHouse.valid = false;
        helper.assertTrue(f.settlement.firstRaidReadiness.assess(
                helper.getLevel(), f.settlement)
                == FirstRaidReadiness.Assessment.HOUSING_INSUFFICIENT,
            "current bed capacity must still cover the live population");
        f.secondHouse.valid = true;
        v2.farmhouse.valid = false;
        helper.assertTrue(f.settlement.firstRaidReadiness.assess(
                helper.getLevel(), f.settlement)
                == FirstRaidReadiness.Assessment.FARMHOUSE_INVALID,
            "losing the physical Farmhouse must close the raid gate");
        v2.farmhouse.valid = true;
        v2.warehouse.valid = false;
        helper.assertTrue(f.settlement.firstRaidReadiness.assess(
                helper.getLevel(), f.settlement)
                == FirstRaidReadiness.Assessment.WAREHOUSE_INVALID,
            "losing the physical Warehouse must close the raid gate");
        v2.warehouse.valid = true;
        v2.tavern.valid = false;
        helper.assertTrue(f.settlement.firstRaidReadiness.assess(
                helper.getLevel(), f.settlement)
                == FirstRaidReadiness.Assessment.TAVERN_INVALID,
            "Hospitality knowledge without a live Tavern must close the raid gate");
        v2.tavern.valid = true;
        v2.barracks.valid = false;
        helper.assertTrue(f.settlement.firstRaidReadiness.assess(
                helper.getLevel(), f.settlement)
                == FirstRaidReadiness.Assessment.BARRACKS_INVALID,
            "First Watch knowledge without a live Barracks must close the raid gate");
        v2.barracks.valid = true;
        v2.watchtower.valid = false;
        helper.assertTrue(f.settlement.firstRaidReadiness.assess(
                helper.getLevel(), f.settlement)
                == FirstRaidReadiness.Assessment.WATCHTOWER_INVALID,
            "an Archer cannot defend from an invalidated Watchtower");
        v2.watchtower.valid = true;

        ItemStack archerBow = f.archer.getMainHandItem().copy();
        f.archer.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        helper.assertTrue(f.settlement.firstRaidReadiness.assess(
                helper.getLevel(), f.settlement)
                == FirstRaidReadiness.Assessment.ARCHER_UNARMED,
            "an Archer profession without the physical serviceable bow must fail closed");
        f.archer.setItemSlot(EquipmentSlot.MAINHAND, archerBow);

        GuardOrder archerOrder = f.settlement.guardOrders
            .order(f.archer.getUUID()).orElseThrow();
        helper.assertTrue(archerOrder.clear(f.builder.getUUID(),
                v2.watchtower.id, helper.getLevel().getGameTime())
                && f.settlement.firstRaidReadiness.assess(
                    helper.getLevel(), f.settlement)
                    == FirstRaidReadiness.Assessment.DEFENDER_ORDER_INVALID,
            "a bow-and-arrow Archer without an active Tower Post must fail closed");
        BlockPos restoredArcherPost = physicalTowerPost(helper, v2.watchtower);
        helper.assertTrue(restoredArcherPost != null
                && archerOrder.issueTower(restoredArcherPost,
                Direction.NORTH, GuardOrder.DEFAULT_FACING_ARC,
                f.builder.getUUID(), v2.watchtower.id,
                helper.getLevel().getGameTime())
                && JourneyServerHooks.noteGuardAssignmentCommitted(f.builder,
                    f.settlement, f.archer, archerOrder,
                    archerOrder.revision()),
            "restoring the exact persisted Tower Post must restore order authority");

        f.archer.assignProfession(Profession.GUARD);
        // assignProfession is a broad fixture helper: it also projects the
        // change into Settlement.SettlerRecord through
        // SettlementManager.noteProfessionChange.  This negative case is
        // deliberately narrower.  Restore the persisted, emblem-backed
        // Archer row so only the live entity projection is corrupted; that
        // is the mismatch the readiness authority must reject here.
        f.settlement.putRecord(f.archer.getUUID(),
            f.archer.getSettlerName(), Profession.ARCHER);
        FirstRaidReadiness.Assessment liveProfessionMismatch = f.settlement
            .firstRaidReadiness.assess(helper.getLevel(), f.settlement);
        FirstRaidReadinessService.Report liveProfessionMismatchReport =
            FirstRaidReadinessService.assessDomain(helper.getLevel(),
                f.settlement);
        helper.assertTrue(liveProfessionMismatch
                    == FirstRaidReadiness.Assessment.WATCHTOWER_INVALID
                && !liveProfessionMismatchReport.ready()
                && liveProfessionMismatchReport.blockedBy(
                    FirstRaidReadinessService.Blocker.ARCHER_INVALID),
            "a hand-mutated live profession must preserve the legacy tower-first "
                + "facade while the domain report identifies the Archer mismatch; "
                + "legacy=" + liveProfessionMismatch + "; blockers="
                + liveProfessionMismatchReport.blockers() + "; live="
                + f.archer.getProfession() + "; record="
                + f.settlement.record(f.archer.getUUID()).profession);
        f.archer.assignProfession(Profession.ARCHER);

        helper.assertTrue(v2.watchtower.workers.remove(f.archer.getUUID())
                && f.settlement.firstRaidReadiness.assess(
                    helper.getLevel(), f.settlement)
                    == FirstRaidReadiness.Assessment.ROSTER_CORRUPT,
            "an Archer record without its exact persisted employer must fail closed");
        v2.watchtower.workers.add(f.archer.getUUID());
        helper.assertTrue(f.settlement.firstRaidReadiness.assess(
                helper.getLevel(), f.settlement)
                == FirstRaidReadiness.Assessment.READY,
            "restoring the exact employer must expose the same evidence again");

        PlaqueAndBuilding unrelatedTower = buildWatchtower(helper,
            f.settlement, f.builder, SECOND_WATCHTOWER_ORIGIN);
        Container employerStorage = storage(helper, v2.watchtowerStorageRelative);
        Container unrelatedStorage = storage(helper,
            unrelatedTower.storageRelative);
        ItemStack employerArrows = removeAll(employerStorage, Items.ARROW);
        helper.assertTrue(employerArrows.getCount()
                == FirstRaidReadinessService.MIN_FIRST_RAID_ARROWS,
            "fixture: first Watch must start with the exact eight-arrow floor");
        helper.assertTrue(insert(employerStorage, new ItemStack(Items.ARROW,
                    FirstRaidReadinessService.MIN_FIRST_RAID_ARROWS - 1))
                    .isEmpty()
                && f.settlement.firstRaidReadiness.assess(
                    helper.getLevel(), f.settlement)
                    == FirstRaidReadiness.Assessment.ARCHER_NO_ARROWS,
            "seven physical arrows are one short of a credible opening exchange");
        helper.assertTrue(insert(employerStorage,
                    new ItemStack(Items.ARROW, 1)).isEmpty()
                && f.settlement.firstRaidReadiness.assess(
                    helper.getLevel(), f.settlement)
                    == FirstRaidReadiness.Assessment.READY,
            "the eighth physical arrow must cross the exact readiness boundary");

        employerArrows = removeAll(employerStorage, Items.ARROW);
        int quiverShare = 3;
        employerArrows.shrink(quiverShare);
        helper.assertTrue(employerArrows.getCount()
                == FirstRaidReadinessService.MIN_FIRST_RAID_ARROWS
                    - quiverShare
                && insert(employerStorage, employerArrows.copy()).isEmpty()
                && f.archer.storeArcherQuiverArrows(v2.watchtower.id,
                    quiverShare) == quiverShare
                && f.settlement.firstRaidReadiness.assess(
                    helper.getLevel(), f.settlement)
                    == FirstRaidReadiness.Assessment.READY,
            "five rack arrows plus three exact persisted quiver arrows must "
                + "satisfy the same eight-arrow floor");
        int restoredShare = f.archer.takeArcherQuiverArrows(quiverShare);
        helper.assertTrue(restoredShare == quiverShare
                && insert(employerStorage,
                    new ItemStack(Items.ARROW, restoredShare)).isEmpty(),
            "boundary fixture must return the three borrowed arrows exactly");

        employerArrows = removeAll(employerStorage, Items.ARROW);
        helper.assertTrue(employerArrows.getCount()
                == FirstRaidReadinessService.MIN_FIRST_RAID_ARROWS
                && insert(unrelatedStorage, employerArrows.copy()).isEmpty()
                && f.settlement.firstRaidReadiness.assess(
                    helper.getLevel(), f.settlement)
                    == FirstRaidReadiness.Assessment.ARCHER_NO_ARROWS,
            "arrows in Tower B must never satisfy the Archer employed at empty Tower A");
        removeAll(unrelatedStorage, Items.ARROW);
        helper.assertTrue(insert(employerStorage, employerArrows).isEmpty()
                && f.settlement.firstRaidReadiness.assess(
                    helper.getLevel(), f.settlement)
                    == FirstRaidReadiness.Assessment.READY,
            "returning the same physical arrows to the exact employer must restore readiness");

        ItemStack borrowedArrows = removeAll(employerStorage, Items.ARROW);
        int borrowedCount = borrowedArrows.getCount();
        helper.assertTrue(borrowedCount > 0
                && f.archer.storeArcherQuiverArrows(v2.watchtower.id,
                    borrowedCount)
                    == borrowedCount
                && f.settlement.firstRaidReadiness.assess(
                    helper.getLevel(), f.settlement)
                    == FirstRaidReadiness.Assessment.READY,
            "a real rack withdrawal into the same Archer's persisted bounded "
                + "quiver must stay ready without duplicating arrows");
        var archerSave = new net.minecraft.nbt.CompoundTag();
        f.archer.addAdditionalSaveData(archerSave);
        helper.assertTrue(f.archer.takeArcherQuiverArrows(
                SettlerEntity.ARCHER_QUIVER_CAPACITY) == borrowedCount
                && f.settlement.firstRaidReadiness.assess(
                    helper.getLevel(), f.settlement)
                    == FirstRaidReadiness.Assessment.ARCHER_NO_ARROWS,
            "an empty rack and empty persisted quiver must fail closed");
        f.archer.readAdditionalSaveData(archerSave);
        helper.assertTrue(f.archer.archerQuiverCount() == borrowedCount
                && f.archer.archerQuiverOwnedBy(v2.watchtower.id)
                && !f.archer.archerQuiverOwnedBy(unrelatedTower.building.id)
                && countIn(employerStorage, Items.ARROW) == 0
                && f.settlement.firstRaidReadiness.assess(
                    helper.getLevel(), f.settlement)
                    == FirstRaidReadiness.Assessment.READY,
            "entity reload must preserve exact Archer-owned ammunition and readiness");
        int returnedFromQuiver = f.archer.takeArcherQuiverArrows(
            SettlerEntity.ARCHER_QUIVER_CAPACITY);
        helper.assertTrue(returnedFromQuiver == borrowedCount
                && insert(employerStorage,
                    new ItemStack(Items.ARROW, returnedFromQuiver)).isEmpty()
                && countIn(employerStorage, Items.ARROW)
                    + f.archer.archerQuiverCount() == borrowedCount,
            "rack + persisted quiver must conserve the original physical arrows");

        Settlement.SettlerRecord duplicate = new Settlement.SettlerRecord(
            f.worker.getUUID(), "Forged duplicate", Profession.LUMBERER);
        f.settlement.settlers.add(duplicate);
        helper.assertTrue(f.settlement.firstRaidReadiness.assess(
                helper.getLevel(), f.settlement)
                == FirstRaidReadiness.Assessment.ROSTER_CORRUPT,
            "a duplicated persisted member row must fail closed before any warning");
        f.settlement.settlers.remove(duplicate);
        helper.assertTrue(f.settlement.firstRaidReadiness.assess(
                helper.getLevel(), f.settlement) == FirstRaidReadiness.Assessment.READY,
            "repairing the corrupt row must expose the same durable evidence again");

        f.settlement.firstRaidReadiness = FirstRaidReadiness.readNbt(
            f.settlement.firstRaidReadiness.writeNbt());
        helper.assertTrue(f.settlement.firstRaidReadiness.assess(
                helper.getLevel(), f.settlement) == FirstRaidReadiness.Assessment.READY,
            "emblem and stored-production evidence must remain authoritative after reload");

        long readinessNight = Math.max(0L, Math.floorDiv(
            helper.getLevel().getDayTime(), RaidLifecycle.DAY_LENGTH));
        helper.assertTrue(RaidDirector.commitFirstRaidReadiness(helper.getLevel(),
                f.settlement),
            "the explicit readiness transaction must arm one persisted calendar");
        long warningNight = f.settlement.raidLifecycle.firstWarningNight();
        long attackNight = f.settlement.raidLifecycle.firstAttackNight();
        long expectedAttack = Math.max(rolledNotBefore,
            readinessNight + f.settlement.raidLifecycle.warningLead());
        helper.assertTrue(attackNight == expectedAttack
                && warningNight == expectedAttack
                    - f.settlement.raidLifecycle.warningLead()
                && warningNight >= readinessNight
                && !RaidDirector.commitFirstRaidReadiness(helper.getLevel(),
                    f.settlement),
            "readiness must preserve the founding floor/full lead and cannot re-arm dates");

        var journeyThroughFj560 = f.settlement.journeyState.writeNbt();
        helper.assertTrue(f.settlement.journeyState.completedThrough(
                JourneyIds.FJ_560_DECLARE_RAID_READY)
                && !f.settlement.journeyState.isCompleted(
                    JourneyIds.FJ_600_RECEIVE_FIRST_WARNING),
            "fixture: the guided Journey must stop exactly before FJ-600");
        helper.assertTrue(RaidDirector.queueFirstWarningIfDue(helper.getLevel(),
                f.settlement, warningNight),
            "the original warning may queue once all server facts are ready");
        RaidPlan warned = f.settlement.raidLifecycle.queuedPlan().orElseThrow();
        helper.assertTrue(warned.night() == attackNight
                && warned.objective() == RaidObjective.KORN
                && f.settlement.raidLifecycle.firstWarningNight() == warningNight
                && f.settlement.raidLifecycle.firstAttackNight() == attackNight,
            "the first warning must preserve its dates and teach the clear "
                + "Warehouse-defense objective");

        // Model the exact crash window: the plan/lifecycle save landed, but
        // the append-only FJ-600 receipt did not. Direct start must refuse;
        // recovery re-presents the same warning and authors it once.
        f.settlement.journeyState = JourneyState.readNbt(journeyThroughFj560,
            f.settlement.id);
        f.settlement.raidLifecycle = RaidLifecycle.readNbt(
            f.settlement.raidLifecycle.writeNbt());
        helper.assertTrue(!RaidDirector.firstWarningReceiptReady(f.settlement)
                && RaidDirector.startQueuedFirstRaid(helper.getLevel(),
                    f.settlement, attackNight).isEmpty()
                && f.settlement.raidLifecycle.queuedPlan().orElseThrow()
                    .equals(warned),
            "a queued plan without FJ-600 must survive restart but cannot attack");
        JourneyState recoverableJourney = f.settlement.journeyState;
        f.settlement.journeyState = JourneyState.quarantined(f.settlement.id,
            "fixture_missing_warning_receipt");
        helper.assertTrue(!RaidDirector.recoverFirstRaidWarningReceipt(
                helper.getLevel(), f.settlement),
            "a quarantined Journey must never mint a replacement warning receipt");
        f.settlement.journeyState = recoverableJourney;
        helper.assertTrue(RaidDirector.recoverFirstRaidWarningReceipt(
                helper.getLevel(), f.settlement)
                && RaidDirector.firstWarningReceiptReady(f.settlement)
                && f.settlement.journeyState.isCompleted(
                    JourneyIds.FJ_600_RECEIVE_FIRST_WARNING)
                && f.settlement.raidLifecycle.queuedPlan().orElseThrow()
                    .equals(warned),
            "restart recovery must re-present and receipt the exact persisted warning");
        int warningReceiptRevision = f.settlement.journeyState.revision();
        helper.assertTrue(RaidDirector.recoverFirstRaidWarningReceipt(
                helper.getLevel(), f.settlement)
                && f.settlement.journeyState.revision()
                    == warningReceiptRevision,
            "warning recovery replay must not present or author FJ-600 twice");
        helper.assertTrue(!RaidDirector.queueFirstWarningIfDue(helper.getLevel(),
                f.settlement, warningNight + 1L)
                && f.settlement.raidLifecycle.queuedPlan().orElseThrow().equals(warned),
            "warning retries must be silent and retain record-equality with the first plan");

        f.camp.valid = false;
        helper.assertTrue(RaidDirector.startQueuedFirstRaid(helper.getLevel(),
                f.settlement, attackNight).isEmpty()
                && f.settlement.raidLifecycle.queuedPlan().orElseThrow().equals(warned),
            "live readiness loss after warning must keep the exact plan queued, not spawn");
        f.camp.valid = true;

        // Force a real partial-spawn failure without touching terrain outside
        // this 16x16 fixture. The captain must first pass the level's normal
        // addFreshEntity path; then NeoForge rejects exactly one follower at
        // the engine boundary. That leaves four accepted actors for the
        // authored five-slot encounter, so production must remove every
        // tentative actor before it begins or seals the lifecycle. Removing
        // the listener then retries the exact same warned plan normally.
        AtomicBoolean captainJoinObserved = new AtomicBoolean();
        AtomicBoolean followerRejected = new AtomicBoolean();
        Consumer<EntityJoinLevelEvent> rejectOneFollower = event -> {
            if (event.getLevel() != helper.getLevel()
                || !(event.getEntity() instanceof RaiderEntity raider)
                || !f.settlement.id.equals(raider.settlementId())
                || !warned.captainId().equals(raider.captainId())) {
                return;
            }
            if (raider.isCaptain()) {
                captainJoinObserved.set(true);
                return;
            }
            if (captainJoinObserved.get()
                && followerRejected.compareAndSet(false, true)) {
                event.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST,
            EntityJoinLevelEvent.class, rejectOneFollower);
        List<RaiderEntity> partial;
        try {
            partial = RaidDirector.startQueuedFirstRaid(helper.getLevel(),
                f.settlement, attackNight);
        } finally {
            NeoForge.EVENT_BUS.unregister(rejectOneFollower);
        }
        helper.assertTrue(captainJoinObserved.get() && followerRejected.get()
                && partial.isEmpty()
                && f.settlement.raidLifecycle.firstState()
                    == FirstRaidState.SCHEDULED
                && f.settlement.raidLifecycle.queuedPlan().orElseThrow()
                    .equals(warned)
                && f.settlement.raidLifecycle.participants().isEmpty()
                && f.settlement.pendingRaid == null
                && RaidDirector.livingRaidersOf(helper.getLevel(),
                    f.settlement).isEmpty(),
            "one engine-rejected follower after captain acceptance must leave no "
                + "entity, activation or sealed UUID and retain the exact "
                + "five-actor plan for retry");

        List<RaiderEntity> band = RaidDirector.startQueuedFirstRaid(helper.getLevel(),
            f.settlement, attackNight);
        String committedLeaderName = RaidDirector.leaderNameOf(f.settlement,
            warned.captainId()).orElseThrow();
        int captains = 0;
        int banditFollowers = 0;
        for (RaiderEntity raider : band) {
            if (raider.isCaptain()) {
                captains++;
                helper.assertTrue(raider.isCustomNameVisible()
                        && raider.getCustomName() != null
                        && committedLeaderName.equals(
                            raider.getCustomName().getString()),
                    "the neutral captain slot must carry the exact warned name");
            } else if (raider.variant() == RaiderEntity.Variant.BANDIT) {
                banditFollowers++;
            }
        }
        helper.assertTrue(band.size() == RaidDirector.FIRST_RAID_BAND_SIZE
                && band.get(0).isCaptain()
                && captains == 1 && banditFollowers == RaidDirector.FIRST_RAID_BAND_SIZE - 1
                && band.stream().allMatch(raider -> warned.captainId()
                    .equals(raider.captainId())
                    && warned.objective() == raider.objective())
                && f.settlement.raidLifecycle.firstState() == FirstRaidState.ACTIVE
                && f.settlement.raidLifecycle.activePlan().orElseThrow().equals(warned),
            "restoring follower footing must retry the same warned plan and seal "
                + "one named bandit captain and three bandit followers (26 Sep escalation curve)");
        helper.assertTrue(f.settlement.raidLifecycle.participantsTracked()
                && f.settlement.raidLifecycle.participantRosterTracked()
                && f.settlement.raidLifecycle.participants().size()
                    == RaidDirector.FIRST_RAID_BAND_SIZE
                && f.settlement.raidLifecycle.participantRoster().size()
                    == RaidDirector.FIRST_RAID_BAND_SIZE
                && f.settlement.raidLifecycle.participantRoster().stream()
                    .filter(RaidParticipantRecord::captain).count() == 1L
                // 26 Sep escalation curve: every first-raid slot is a bandit
                // (RaidDirector.firstRaidVariantFor), the captain included.
                && f.settlement.raidLifecycle.participantRoster().stream()
                    .filter(record -> !record.captain()
                        && record.build() == RaidParticipantRecord.Build.BANDIT)
                    .count() == RaidDirector.FIRST_RAID_BAND_SIZE - 1L
                && f.settlement.raidLifecycle.participantRoster().stream()
                    .allMatch(record -> record.build() == RaidParticipantRecord.Build.BANDIT)
                && band.stream().allMatch(raider -> f.settlement.raidLifecycle
                    .participants().contains(raider.getUUID())),
            "the sealed lifecycle and role ledgers must match all "
                + RaidDirector.FIRST_RAID_BAND_SIZE + " accepted actors exactly");
        for (RaiderEntity raider : band) {
            helper.assertTrue(f.settlement.raidLifecycle
                    .recordTerminalParticipant(raider.getUUID()),
                "fixture: every sealed raider must receive one definitive terminal fact");
            raider.discard();
        }
        RaidLogEntry terminalReport = new RaidLogEntry(warned.night(),
            RaidDirector.leaderNameOf(f.settlement, warned.captainId())
                .orElseThrow(), warned.objective().id(), true, 0, 0,
            f.settlement.raidPressure.stage().id());
        helper.assertTrue(f.settlement.raidLifecycle.completeFirstRaid(
                JourneyOutcome.HELD, terminalReport),
            "fixture: terminal participant ledger must close under one exact outcome/report");
        f.settlement.raidLog.add(terminalReport);
        f.settlement.pendingRaid = null;

        // Persist the second narrow crash window: lifecycle + exact report
        // committed, while FJ-610 and its reward have not yet landed.
        var terminalGapTag = f.settlement.writeNbt();
        Settlement mismatched = Settlement.readNbt(terminalGapTag,
            SettlementSavedData.CURRENT_DATA_VERSION);
        mismatched.raidLog.set(mismatched.raidLog.size() - 1,
            new RaidLogEntry(terminalReport.night(), "Forged Captain",
                terminalReport.objectiveId(), terminalReport.held(),
                terminalReport.itemsStolen(), terminalReport.settlersHurt(),
                terminalReport.stageAfterId()));
        SettlementSavedData root = SettlementSavedData.get(helper.getLevel());
        root.settlements.put(mismatched.id, mismatched);
        helper.assertTrue(!RaidDirector.reconcileCompletedFirstRaid(
                helper.getLevel(), mismatched)
                && !mismatched.journeyState.isCompleted(
                    JourneyIds.FJ_610_FIRST_RAID_RESOLVED)
                && mismatched.blessingState.earned() == 0
                && mismatched.raidLog.size() == 1,
            "mismatched lifecycle/report evidence must not mint FJ-610, reward or log");

        Settlement quarantined = Settlement.readNbt(terminalGapTag,
            SettlementSavedData.CURRENT_DATA_VERSION);
        quarantined.journeyState = JourneyState.quarantined(quarantined.id,
            "fixture_terminal_receipt_gap");
        root.settlements.put(quarantined.id, quarantined);
        helper.assertTrue(!RaidDirector.reconcileCompletedFirstRaid(
                helper.getLevel(), quarantined)
                && quarantined.blessingState.earned() == 0,
            "quarantined Journey authority must stop terminal recovery and reward");

        Settlement reloaded = Settlement.readNbt(terminalGapTag,
            SettlementSavedData.CURRENT_DATA_VERSION);
        // Recovery must use the persisted HELD fact, never today's roster.
        reloaded.settlers.clear();
        root.settlements.put(reloaded.id, reloaded);
        root.setDirty();
        helper.assertTrue(reloaded.population() == 0
                && RaidDirector.reconcileCompletedFirstRaid(
                    helper.getLevel(), reloaded)
                && reloaded.journeyState.isCompleted(
                    JourneyIds.FJ_610_FIRST_RAID_RESOLVED)
                && reloaded.journeyState.outcome() == JourneyOutcome.HELD
                && reloaded.blessingState.earned() == 1
                && !reloaded.raidLifecycle.mayGrantReward()
                && reloaded.raidLog.size() == 1,
            "restart must recover persisted HELD, FJ-610 and one reward without population inference");
        int resolutionRevision = reloaded.journeyState.revision();
        int rewardRevision = reloaded.blessingState.revision();
        helper.assertTrue(RaidDirector.reconcileCompletedFirstRaid(
                helper.getLevel(), reloaded)
                && reloaded.journeyState.revision() == resolutionRevision
                && reloaded.blessingState.revision() == rewardRevision
                && reloaded.blessingState.earned() == 1
                && reloaded.raidLog.size() == 1,
            "terminal recovery replay must not duplicate receipt, reward or Aftermath");

        f.builder.setPos(reloaded.center.getX() + 0.5D,
            reloaded.center.getY() + 0.5D, reloaded.center.getZ() + 0.5D);
        f.builder.openMenu(f.hearth, buf -> {
            buf.writeBlockPos(reloaded.center);
            buf.writeUUID(reloaded.id);
            buf.writeUtf(reloaded.name);
        });
        helper.assertTrue(f.builder.containerMenu instanceof HearthMenu
                && JourneyServerHooks.noteJourneyViewOpened(f.builder, reloaded)
                && reloaded.journeyState.isCompleted(
                    JourneyIds.FJ_620_REVIEW_AFTERMATH),
            "the recovered exact report must remain reviewable through the real Hearth menu");
        helper.succeed();
    }

    @GameTest(template = "empty64", timeoutTicks = 500,
        batch = "first_raid_readiness_restart_half_commit_recovery")
    public void persistedScheduledCalendarRecoversMissingJourneyReceipt(
            GameTestHelper helper) {
        Fixture f = foundedKnowledgeBuildingsAndGuildmaster(helper, "Ashenwatch");
        purchaseAndHireDirectly(helper, f);
        viewLumberInventory(helper, f);
        commitLumberZoneForJourney(helper, f);
        equipPhysical(helper, f.camp, f.worker, Items.IRON_AXE);

        ItemStack logs = performLumberWork(helper, f);
        helper.assertTrue(FoundingJourneyProgress.noteLogStored(
                helper.getLevel(), f.settlement, f.camp, f.worker,
                logs, logs.getCount())
                && DevelopmentQuests.noteLumberLogsStored(helper.getLevel(),
                    f.settlement, f.camp, f.worker, logs, logs.getCount()),
            "fixture: the real camp output must close the founding lumber rung");
        JourneyV2 v2 = completeJourneyV2(helper, f, true);
        FirstRaidReadinessService.Report declaration =
            FirstRaidReadinessService.assessDomain(helper.getLevel(), f.settlement);
        helper.assertTrue(declaration.ready(),
            "fixture: every live declaration fact must be ready before scheduling; "
                + "blockers=" + declaration.blockers() + "; metrics="
                + declaration.metrics() + "; journey="
                + f.settlement.journeyState.currentStep()
                    .map(step -> step.id().toString()).orElse("none"));

        long currentNight = Math.max(0L, Math.floorDiv(
            helper.getLevel().getDayTime(), RaidLifecycle.DAY_LENGTH));
        helper.assertTrue(f.settlement.raidLifecycle
                .scheduleAfterReadiness(currentNight)
                && FirstRaidReadinessService.assessScheduledCommitBridge(
                    helper.getLevel(), f.settlement).ready()
                && !FirstRaidReadinessService.assessExecution(
                    helper.getLevel(), f.settlement).ready(),
            "fixture: a persisted calendar without FJ-560 must be the exact recoverable half-commit");
        long warningNight = f.settlement.raidLifecycle.firstWarningNight();
        long attackNight = f.settlement.raidLifecycle.firstAttackNight();

        Settlement reloaded = Settlement.readNbt(f.settlement.writeNbt(),
            SettlementSavedData.CURRENT_DATA_VERSION);
        SettlementSavedData root = SettlementSavedData.get(helper.getLevel());
        root.settlements.put(reloaded.id, reloaded);
        root.setDirty();
        FirstRaidReadinessService.Report recoveredBridge =
            FirstRaidReadinessService.assessScheduledCommitBridge(
                helper.getLevel(), reloaded);
        helper.assertTrue(reloaded.raidLifecycle.firstState()
                == FirstRaidState.SCHEDULED
                && !reloaded.journeyState.isCompleted(
                    JourneyIds.FJ_560_DECLARE_RAID_READY)
                && recoveredBridge.ready(),
            "save/reload must preserve the narrow half-commit without inventing "
                + "its receipt; blockers=" + recoveredBridge.blockers()
                + "; metrics=" + recoveredBridge.metrics() + "; current="
                + reloaded.journeyState.currentStep()
                    .map(step -> step.id().toString()).orElse("none"));

        helper.assertTrue(RaidDirector.recoverFirstRaidReadinessCommit(
                helper.getLevel(), reloaded)
                && reloaded.journeyState.isCompleted(
                    JourneyIds.FJ_560_DECLARE_RAID_READY)
                && FirstRaidReadinessService.assessExecution(
                    helper.getLevel(), reloaded).ready()
                && reloaded.raidLifecycle.firstWarningNight() == warningNight
                && reloaded.raidLifecycle.firstAttackNight() == attackNight,
            "recovery must author only FJ-560 and preserve both persisted raid dates");
        int journeyRevision = reloaded.journeyState.revision();
        helper.assertTrue(RaidDirector.recoverFirstRaidReadinessCommit(
                helper.getLevel(), reloaded)
                && reloaded.journeyState.revision() == journeyRevision
                && reloaded.raidLifecycle.firstWarningNight() == warningNight
                && reloaded.raidLifecycle.firstAttackNight() == attackNight,
            "recovery replay must be idempotent and never re-arm or reroll the raid");
        helper.succeed();
    }

    @GameTest(template = "empty64", timeoutTicks = 350,
        batch = "first_raid_readiness_raw_hire_cannot_bypass")
    public void rawAdminHireAndRealStorageCannotForgeReadiness(
            GameTestHelper helper) {
        Fixture f = foundedKnowledgeBuildingsAndGuildmaster(helper, "Rawford");
        helper.assertTrue(Employment.hire(helper.getLevel(), f.settlement,
                f.camp, f.worker).ok(),
            "fixture: the permission-level-2 seam should still construct employment");
        helper.assertTrue(f.settlement.firstRaidReadiness.stage()
                == FirstRaidReadiness.Stage.PENDING,
            "the raw admin/GameTest seam must never record a consumed emblem");

        ItemStack offered = new ItemStack(Items.OAK_LOG);
        helper.assertTrue(WorkplaceStorage.insert(helper.getLevel(), f.camp,
                offered.copy()).isEmpty(),
            "fixture: the raw hire must still have one physical camp log to test");
        helper.assertTrue(!FoundingJourneyProgress.noteLogStored(helper.getLevel(),
                f.settlement, f.camp, f.worker, offered, 1)
                && f.settlement.foundingJourney.phase()
                    == FoundingJourney.Phase.HIRE_LUMBERER,
            "raw employment must not advance the emblem-gated Journey");
        helper.assertTrue(f.settlement.firstRaidReadiness.stage()
                == FirstRaidReadiness.Stage.PENDING
                && !RaidDirector.queueFirstWarningIfDue(helper.getLevel(), f.settlement,
                    f.settlement.raidLifecycle.firstWarningNight()),
            "an emblem-gated Journey plus raw employment must fail closed without proof");
        helper.succeed();
    }

    @GameTest(template = "empty64", timeoutTicks = 350,
        batch = "first_raid_readiness_hearth_log_cannot_bypass")
    public void legacyHearthLogCannotForgeStoredProduction(
            GameTestHelper helper) {
        Fixture f = foundedKnowledgeBuildingsAndGuildmaster(helper, "Oldroute");
        purchaseAndHireDirectly(helper, f);
        commitLumberZoneForJourney(helper, f);
        ItemStack offered = new ItemStack(Items.OAK_LOG);
        helper.assertTrue(f.hearth.insertGoods(offered.copy()).isEmpty(),
            "fixture: a real log must enter the old communal Hearth route");
        helper.assertTrue(FoundingJourneyProgress.noteLogDelivered(helper.getLevel(),
                f.settlement, f.hearth, f.worker, offered, 1)
                && f.settlement.foundingJourney.complete(),
            "the compatibility hook may finish its legacy Journey presentation");
        helper.assertTrue(f.settlement.firstRaidReadiness.stage()
                == FirstRaidReadiness.Stage.EMBLEM_HIRED
                && f.settlement.firstRaidReadiness.assess(helper.getLevel(), f.settlement)
                    == FirstRaidReadiness.Assessment.EVIDENCE_MISSING
                && !RaidDirector.queueFirstWarningIfDue(helper.getLevel(), f.settlement,
                    f.settlement.raidLifecycle.firstWarningNight()),
            "a Hearth delivery is not workplace production and must never unlock a raid");
        helper.succeed();
    }

    @GameTest(template = "empty64", timeoutTicks = 100,
        batch = "first_raid_readiness_legacy_and_corruption_fail_closed")
    public void legacyAndMissingCurrentLedgerFailClosed(GameTestHelper helper) {
        Settlement original = new Settlement(UUID.randomUUID(), "Legacy",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        var tag = original.writeNbt();
        Settlement legacy = Settlement.readNbt(tag, 3);
        helper.assertTrue(legacy.foundingJourney.phase() == FoundingJourney.Phase.SKIPPED
                && legacy.firstRaidReadiness.stage()
                    == FirstRaidReadiness.Stage.PENDING,
            "a legitimate legacy save must migrate to empty evidence, never invented proof");

        Settlement scheduledOriginal = new Settlement(UUID.randomUUID(),
            "Legacy Scheduled", helper.absolutePos(new BlockPos(9, 1, 9)));
        helper.assertTrue(scheduledOriginal.raidLifecycle.initializeAtFounding(20L, 4, 2),
            "fixture: legacy first schedule must initialize");
        long oldAttackNight = scheduledOriginal.raidLifecycle.firstAttackNight();
        Settlement scheduledLegacy = Settlement.readNbt(
            scheduledOriginal.writeNbt(), 3);
        helper.assertTrue(scheduledLegacy.foundingJourney.phase()
                == FoundingJourney.Phase.BUILD_LUMBER_CAMP
                && scheduledLegacy.firstRaidReadiness.stage()
                    == FirstRaidReadiness.Stage.PENDING
                && scheduledLegacy.raidLifecycle.firstAttackNight() == oldAttackNight,
            "a legacy scheduled raid must keep its roll but receive an honest qualification path");

        tag.remove("FirstRaidReadiness");
        Settlement damagedCurrent = Settlement.readNbt(tag,
            SettlementSavedData.CURRENT_DATA_VERSION);
        helper.assertTrue(damagedCurrent.firstRaidReadiness.stage()
                == FirstRaidReadiness.Stage.QUARANTINED
                && Settlement.readNbt(damagedCurrent.writeNbt(),
                    SettlementSavedData.CURRENT_DATA_VERSION)
                    .firstRaidReadiness.stage()
                    == FirstRaidReadiness.Stage.QUARANTINED,
            "missing current readiness authority must remain fail-closed after rewrite");
        helper.succeed();
    }

    private static Fixture foundedKnowledgeBuildingsAndGuildmaster(GameTestHelper helper,
                                                              String name) {
        floor(helper);
        helper.setBlock(HEARTH, ModBlocks.HEARTH.get());
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getLevel()
            .getBlockEntity(helper.absolutePos(HEARTH));
        helper.assertTrue(hearth != null, "fixture: physical Hearth must exist");

        boolean previousDistanceOverride = SettlementManager.ignoreFoundingDistance;
        Settlement settlement;
        try {
            SettlementManager.ignoreFoundingDistance = true;
            settlement = SettlementManager.tryFound(helper.getLevel(),
                helper.absolutePos(HEARTH));
        } finally {
            SettlementManager.ignoreFoundingDistance = previousDistanceOverride;
        }
        helper.assertTrue(settlement != null,
            "fixture: real founding must atomically create four ordinary founders");
        settlement.name = name;
        settlement.radius = 20;
        hearth.bindSettlement(settlement.id);
        helper.assertTrue(settlement.foundingJourney.phase()
                == FoundingJourney.Phase.BUILD_LUMBER_CAMP
                && settlement.firstRaidReadiness.stage()
                    == FirstRaidReadiness.Stage.PENDING,
            "real founding must start both authorities fresh, never skipped/quarantined");

        // Initialize Development before any room exists so grandfathering
        // cannot turn this release test into a free-knowledge fixture.
        // Houses are now honest HOME knowledge and are therefore built only
        // after the Farmer proof later in completeJourneyV2.
        Development.revisionOf(helper.getLevel(), settlement);
        ServerPlayer builder = helper.makeMockServerPlayerInLevel();
        NetworkRegistry.configureMockConnection(builder.connection.getConnection());
        // GameTest mock players default to creative instabuild. This fixture
        // exercises the ordinary player path, where fitting a learned plan is
        // a physical one-item cost, so pin the mock to survival semantics.
        builder.getAbilities().instabuild = false;
        builder.onUpdateAbilities();
        List<SettlerEntity> members = SettlementManager.loadedMembers(
            helper.getLevel(), settlement);
        helper.assertTrue(members.size() == 4 && settlement.mayorId == null
                && members.stream().allMatch(member -> member.getProfession() == Profession.NONE),
            "real founding must expose four unassigned founders and no Mayor seat");
        SettlerEntity worker = members.get(0);
        SettlerEntity farmer = members.get(1);
        SettlerEntity courier = members.get(2);
        SettlerEntity spare = members.get(3);
        helper.assertTrue(settlement.population() == 4
                && settlement.validBedCount() == 0,
            "fixture: four founders start together; "
                + "Home knowledge must author all later physical beds");

        // Emblems are traded by the Guildmaster beside the bound Banner (the
        // Mayor office is retired); seat him before the journey opens.
        helper.assertTrue(com.hearthstead.settlement.guildmaster.GuildmasterService
                .ensure(helper.getLevel(), settlement) != null,
            "fixture: the Guildmaster must take his seat beside the bound Banner");

        // First Fire is measured from the live Hearth, live Guildmaster and
        // four founded residents. Housing is deliberately authored later by
        // HOME; no client-reported quest progress is involved.
        openJourney(helper, builder, hearth, settlement);
        helper.assertTrue(settlement.mayorId == null
                && settlement.journeyState.isCompleted(JourneyIds.FJ_020_OPEN_JOURNEY)
                && settlement.journeyState.isCompleted(JourneyIds.FJ_030_APPOINT_MAYOR),
            "the real open session must observe the live Guildmaster");

        unlock(helper, hearth, settlement, DevelopmentNode.SHELTER, builder);
        unlock(helper, hearth, settlement, DevelopmentNode.TIMBER_RIGHTS, builder);
        PlaqueAndBuilding camp = buildCamp(helper, settlement, builder);
        helper.assertTrue(settlement.foundingJourney.phase()
                == FoundingJourney.Phase.HIRE_LUMBERER,
            "the real valid Lumber Camp plaque must advance the Journey once");
        return new Fixture(settlement, hearth, null,
            null, camp.building, camp.plaque, spare, worker,
            farmer, courier, builder, camp.storageRelative);
    }

    /** Completes every server-authored pre-raid rung after the founding log. */
    private static JourneyV2 completeJourneyV2(GameTestHelper helper, Fixture f) {
        return completeJourneyV2(helper, f, false);
    }

    private static JourneyV2 completeJourneyV2(GameTestHelper helper, Fixture f,
                                                boolean earlyWatchTraveler) {
        unlock(helper, f.hearth, f.settlement,
            DevelopmentNode.STORES_AND_ROADS, f.builder);

        PlaqueAndBuilding warehouse = buildWarehouse(helper, f.settlement, f.builder);
        purchaseAndHire(helper, f, Profession.COURIER, f.courier, warehouse.building);
        viewRequestLedger(helper, f);
        int logsMoved = routeOutput(helper, f, f.camp,
            helper.absolutePos(f.storageRelative), warehouse.building,
            helper.absolutePos(warehouse.storageRelative), Items.OAK_LOG, 1);
        helper.assertTrue(logsMoved == 1
                && DevelopmentQuests.noteCourierDelivery(helper.getLevel(),
                    f.settlement, f.courier, f.camp, warehouse.building,
                    logsMoved),
            "fixture: one physical Camp-to-Warehouse route must author Cultivated Ground");
        unlock(helper, f.hearth, f.settlement,
            DevelopmentNode.CULTIVATED_GROUND, f.builder);

        PlaqueAndBuilding farm = buildFarmhouse(helper, f.settlement, f.builder);
        purchaseAndHire(helper, f, Profession.FARMER, f.farmer, farm.building);
        equipPhysical(helper, farm.building, f.farmer, Items.IRON_HOE);
        commitFarmZoneForJourney(helper, f, farm.building);

        ItemStack crops = performFarmWork(helper, f, farm.building,
            helper.absolutePos(farm.storageRelative));
        helper.assertTrue(DevelopmentQuests.noteFarmCropsStored(
                helper.getLevel(), f.settlement, farm.building, f.farmer,
                crops, crops.getCount()),
            "fixture: the physical Farmhouse receipt must author Home progress");
        int cropsMoved = routeOutput(helper, f, farm.building,
            helper.absolutePos(farm.storageRelative), warehouse.building,
            helper.absolutePos(warehouse.storageRelative), Items.WHEAT,
            crops.getCount());
        helper.assertTrue(cropsMoved == crops.getCount(),
            "fixture: the exact Farmhouse crop must reach Warehouse authority");
        unlock(helper, f.hearth, f.settlement, DevelopmentNode.HOME, f.builder);

        f.house = buildHouse(helper, f.settlement, f.builder,
            HOUSE_ORIGIN, 4).building;
        f.secondHouse = buildHouse(helper, f.settlement, f.builder,
            SECOND_HOUSE_ORIGIN, 2).building;
        helper.assertTrue(f.settlement.validBedCount() == 6,
            "fixture: learned Home plans must survey exactly six physical bed heads");
        unlock(helper, f.hearth, f.settlement,
            DevelopmentNode.HOSPITALITY, f.builder);
        PlaqueAndBuilding tavern = buildTavern(helper, f.settlement, f.builder);

        fundTwoNaturalAdmissions(f);
        f.guard = recruitNaturally(helper, f, tavern.building, 5);
        unlock(helper, f.hearth, f.settlement,
            DevelopmentNode.FIRST_WATCH, f.builder);

        PlaqueAndBuilding barracks = buildBarracks(helper, f.settlement, f.builder);
        purchaseAndHire(helper, f, Profession.GUARD, f.guard, barracks.building);
        helper.assertTrue(f.settlement.firstRaidReadiness.assess(
                helper.getLevel(), f.settlement)
                == FirstRaidReadiness.Assessment.JOURNEY_INCOMPLETE,
            "First Watch must grant the Guard loop without bypassing its Courier proof "
                + "or the later Archer/Journey receipts");
        serveMissingToolRequest(helper, f, f.guard, warehouse.building,
            helper.absolutePos(warehouse.storageRelative), barracks.building,
            helper.absolutePos(barracks.storageRelative), Items.IRON_SWORD);
        var guardRequirement = EquipmentRequests.requirementFor(Profession.GUARD);
        FirstRaidReadinessService.Report deliveredButNotEquipped =
            FirstRaidReadinessService.assessDomain(helper.getLevel(), f.settlement);
        helper.assertTrue(guardRequirement != null
                && f.guard.getMainHandItem().isEmpty()
                && WorkplaceStorage.hasMatching(helper.getLevel(),
                    barracks.building, guardRequirement)
                && deliveredButNotEquipped.blockedBy(
                    FirstRaidReadinessService.Blocker.GUARD_UNARMED),
            "a delivered sword must remain physical Barracks storage and the "
                + "live readiness domain must still report the Guard unarmed");
        equipPhysicalFromStorage(helper, barracks.building, f.guard);
        issueDefenderOrder(helper, f, f.guard, barracks.building, false);

        if (earlyWatchTraveler) {
            f.archer = recruitNaturally(helper, f, tavern.building, 6, () -> {
                RecruitmentTransaction waiting = f.settlement.recruitment;
                helper.assertTrue(!f.settlement.journeyState.isCompleted(
                        JourneyIds.FJ_551_UNLOCK_ARM_THE_WATCH)
                        && waiting.evidenceAcknowledged(
                            RecruitmentTransaction.EVIDENCE_QUALIFICATION)
                        && waiting.evidenceAcknowledged(
                            RecruitmentTransaction.EVIDENCE_ARRIVAL),
                    "early guest must already have ordinary recruitment acknowledgements");
                unlock(helper, f.hearth, f.settlement,
                    DevelopmentNode.ARM_THE_WATCH, f.builder);
                helper.assertTrue(f.settlement.journeyState.isCompleted(
                        JourneyIds.FJ_553_SECOND_RECRUITMENT_WINDOW)
                        && f.settlement.journeyState.isCompleted(
                            JourneyIds.FJ_554_SECOND_TRAVELER_ARRIVES)
                        && !f.settlement.journeyState.isCompleted(
                            JourneyIds.FJ_555_ADMIT_FIFTH_SETTLER)
                        && waiting.transactionId().equals(
                            f.settlement.recruitment.transactionId())
                        && waiting.travelerId().equals(
                            f.settlement.recruitment.travelerId())
                        && f.settlement.population() == 5,
                    "unlock must adopt the exact earlier waiting guest before admission, "
                        + "without a Journey reopen, new guest or invented admission");
                f.settlement.journeyState = JourneyState.readNbt(
                    f.settlement.journeyState.writeNbt(), f.settlement.id);
                f.settlement.applyRecruitment(RecruitmentTransaction.readOrQuarantine(
                    f.settlement.recruitment.writeNbt(), f.settlement.id));
                helper.assertTrue(f.settlement.journeyState.isCompleted(
                        JourneyIds.FJ_554_SECOND_TRAVELER_ARRIVES),
                    "adopted arrival must survive a saved-state round trip");
            });
            helper.assertTrue(f.settlement.journeyState.isCompleted(
                    JourneyIds.FJ_555_ADMIT_FIFTH_SETTLER),
                "ordinary paid admission must close the adopted Watch slot");
        } else {
            // A duplicate building UUID is corrupt settlement authority, even if
            // both list entries happen to be the same Java object. Arm the Watch
            // may persist, but its derived five-bed receipt must stay fail-closed
            // until a later bounded re-observation sees one exact registration.
            int buildingsBeforeDuplicate = f.settlement.buildings.size();
            f.settlement.buildings.add(f.house);
            unlock(helper, f.hearth, f.settlement,
                DevelopmentNode.ARM_THE_WATCH, f.builder);
            helper.assertTrue(f.settlement.journeyState.isCompleted(
                    JourneyIds.FJ_551_UNLOCK_ARM_THE_WATCH)
                    && !f.settlement.journeyState.isCompleted(
                        JourneyIds.FJ_552_ADD_FIFTH_BED),
                "duplicate building UUID authority must block the physical-bed receipt");
            helper.assertTrue(f.settlement.buildings.remove(
                    f.settlement.buildings.size() - 1) == f.house
                    && f.settlement.buildings.size() == buildingsBeforeDuplicate,
                "fixture: removing the injected duplicate must restore one exact home");
            openJourney(helper, f.builder, f.hearth, f.settlement);
            helper.assertTrue(f.settlement.journeyState.isCompleted(
                    JourneyIds.FJ_552_ADD_FIFTH_BED),
                "Arm the Watch must re-observe five loaded physical bed heads before "
                    + "the second recruitment cycle begins");
            FirstRaidReadinessService.Report guardOnly =
                FirstRaidReadinessService.assessDomain(helper.getLevel(), f.settlement);
            helper.assertTrue(!guardOnly.blockedBy(
                        FirstRaidReadinessService.Blocker.GUARD_INVALID)
                    && !guardOnly.blockedBy(
                        FirstRaidReadinessService.Blocker.GUARD_UNARMED)
                    && !guardOnly.blockedBy(
                        FirstRaidReadinessService.Blocker.GUARD_ORDER_INVALID)
                    && guardOnly.blockedBy(
                        FirstRaidReadinessService.Blocker.ARCHER_INVALID)
                    && !guardOnly.blockedBy(FirstRaidReadinessService.Blocker
                        .SETTLER_ROSTER_INSUFFICIENT),
                "population now meets the roster minimum, but a distinct second recruit "
                    + "becomes a fully equipped Watchtower Archer");

            f.archer = recruitNaturally(helper, f, tavern.building, 6);
        }
        PlaqueAndBuilding watchtower = buildWatchtower(helper, f.settlement,
            f.builder, WATCHTOWER_ORIGIN);
        purchaseAndHire(helper, f, Profession.ARCHER, f.archer,
            watchtower.building);
        serveMissingToolRequest(helper, f, f.archer, warehouse.building,
            helper.absolutePos(warehouse.storageRelative), watchtower.building,
            helper.absolutePos(watchtower.storageRelative), Items.BOW);
        equipPhysicalFromStorage(helper, watchtower.building, f.archer);
        helper.assertTrue(WorkplaceStorage.insert(helper.getLevel(),
                watchtower.building, new ItemStack(Items.ARROW, 8)).isEmpty(),
            "the exact Archer employer must physically receive ammunition");
        issueDefenderOrder(helper, f, f.archer, watchtower.building, true);

        helper.assertTrue(f.settlement.population() == 6
                && f.settlement.validBedCount() == 6
                && !f.guard.getUUID().equals(f.archer.getUUID())
                && f.settlement.record(f.guard.getUUID()) != null
                && f.settlement.record(f.archer.getUUID()) != null,
            "the pre-raid roster must contain six unique members and two "
                + "distinct, naturally admitted defenders in six physical beds");
        return new JourneyV2(farm.building, warehouse.building,
            tavern.building, barracks.building, watchtower.building,
            watchtower.storageRelative);
    }

    /**
     * Uses the real survival-authored Tavern transaction. Only the multi-day
     * qualification clock is accelerated; spawn, arrival, payment, admission,
     * roster mutation and Journey receipts all use their production paths.
     */
    private static SettlerEntity recruitNaturally(GameTestHelper helper,
                                                   Fixture f,
                                                   Building tavern,
                                                   int expectedPopulation) {
        return recruitNaturally(helper, f, tavern, expectedPopulation, () -> {});
    }

    private static SettlerEntity recruitNaturally(GameTestHelper helper,
                                                   Fixture f, Building tavern,
                                                   int expectedPopulation,
                                                   Runnable beforeAdmission) {
        int populationBefore = f.settlement.population();
        int cycleBefore = f.settlement.recruitment.cycle();
        int breadBefore = count(f.hearth, Items.BREAD);
        int planksBefore = count(f.hearth, Items.OAK_PLANKS);
        helper.assertTrue(expectedPopulation == populationBefore + 1
                && f.settlement.recruitment.status()
                    == RecruitmentTransaction.Status.ATTRACTING,
            "fixture: each resident must begin from one fresh Tavern cycle");

        SettlementManager.tickRecruitment(helper.getLevel(), f.settlement);
        RecruitmentTransaction qualifying = f.settlement.recruitment;
        helper.assertTrue(qualifying.status()
                    == RecruitmentTransaction.Status.QUALIFYING
                && qualifying.survivalAuthored()
                && qualifying.cycle() == cycleBefore
                && tavern.id.equals(qualifying.tavernBuildingId()),
            "the eligible settlement must lock the exact linked Tavern naturally");

        f.settlement.applyRecruitment(qualifying.readyForSpawnForTest());
        advanceToNextNaturalVisitorWindow(helper, f.settlement);
        SettlementManager.tickRecruitment(helper.getLevel(), f.settlement);
        UUID travelerId = f.settlement.recruitment.travelerId();
        var candidate = travelerId == null ? null
            : helper.getLevel().getEntity(travelerId);
        helper.assertTrue(candidate instanceof SettlerEntity
                && f.settlement.recruitment.status()
                    == RecruitmentTransaction.Status.TRAVELING,
            "the completed natural clock must publish one physical traveler");
        SettlerEntity traveler = (SettlerEntity) candidate;
        BlockPos tavernAnchor = f.settlement.recruitment.tavernAnchor();
        traveler.moveTo(tavernAnchor.getX() + 0.5D, tavernAnchor.getY(),
            tavernAnchor.getZ() + 0.5D, 0.0F, 0.0F);
        SettlementManager.tickRecruitment(helper.getLevel(), f.settlement);
        helper.assertTrue(f.settlement.recruitment.status()
                    == RecruitmentTransaction.Status.WAITING_ADMISSION
                && traveler.isTraveler()
                && f.settlement.record(travelerId) == null,
            "only physical arrival at the locked Tavern may open admission");

        beforeAdmission.run();
        var quote = f.settlement.recruitment.quote();
        helper.assertTrue(quote != null && !quote.legacyPending(), "actual guest must own its frozen price");
        helper.assertTrue(quote.version() == 2, "actual current guest must carry a coin quote");
        int coinBeforeFunding = count(f.hearth, com.hearthstead.registry.ModItems.GOLD_COIN.get());
        if (coinBeforeFunding < quote.coins()) put(f.hearth, com.hearthstead.registry.ModItems.GOLD_COIN.get(), quote.coins() - coinBeforeFunding);
        int coinsBeforeAdmission = count(f.hearth, com.hearthstead.registry.ModItems.GOLD_COIN.get());
        int admissionRevision = f.settlement.recruitment.revision();
        helper.assertTrue(SettlementManager.admitWaitingTraveler(f.builder,
                f.settlement, travelerId, admissionRevision)
                == SettlementManager.AdmissionResult.COMMITTED,
            "the player must explicitly pay and admit the exact waiting traveler");
        RecruitmentTransaction admitted = f.settlement.recruitment;
        helper.assertTrue(admitted.status()
                    == RecruitmentTransaction.Status.ADMITTED
                && admitted.admissionReceipt() != null
                && admitted.admissionReceipt().removedItemCount() == quote.coins()
                && count(f.hearth, com.hearthstead.registry.ModItems.GOLD_COIN.get()) == coinsBeforeAdmission - quote.coins()
                && f.settlement.population() == expectedPopulation
                && f.settlement.record(travelerId) != null
                && !traveler.isTraveler()
                && count(f.hearth, Items.BREAD) == breadBefore - quote.bread()
                && count(f.hearth, Items.OAK_PLANKS) == planksBefore - quote.planks(),
            "one admission must consume the exact frozen quote and add the same "
                + "traveler UUID exactly once");
        helper.assertTrue(SettlementManager.admitWaitingTraveler(f.builder,
                f.settlement, travelerId, admissionRevision)
                != SettlementManager.AdmissionResult.COMMITTED
                && f.settlement.population() == expectedPopulation,
            "a stale replay must not double-pay or duplicate the admitted UUID");

        SettlementManager.tickRecruitment(helper.getLevel(), f.settlement);
        helper.assertTrue(f.settlement.recruitment.status()
                    == RecruitmentTransaction.Status.ATTRACTING
                && f.settlement.recruitment.cycle() == cycleBefore + 1,
            "acknowledged natural admission must open exactly one next cycle");
        advanceToNextWorkMorning(helper);
        return traveler;
    }

    /**
     * The qualification clock is the only accelerated part of this helper.
     * A survival-authored READY_TO_SPAWN transaction still uses one real
     * evening batch, and a second admission must use a later daily batch.
     */
    private static void advanceToNextNaturalVisitorWindow(GameTestHelper helper,
                                                           Settlement settlement) {
        long dayTime = helper.getLevel().getDayTime();
        long day = Math.floorDiv(dayTime, 24_000L);
        if (Math.floorMod(dayTime, 24_000L)
                > SettlementManager.TAVERN_VISITOR_ARRIVAL_TIME) {
            day++;
        }
        day = Math.max(day, settlement.lastTavernVisitorDay + 1L);
        helper.getLevel().setDayTime(day * 24_000L
            + SettlementManager.TAVERN_VISITOR_ARRIVAL_TIME);
        helper.assertTrue(SettlementManager.tavernVisitorArrivalDue(
                helper.getLevel(), settlement),
            "fixture must enter one unused natural evening visitor window");
    }

    /**
     * GameTest time is fixture-controlled. Once production has committed the
     * admission and opened the next cycle, resume a later working morning so
     * courier and defender steps do not remain frozen in the visitor evening.
     */
    private static void advanceToNextWorkMorning(GameTestHelper helper) {
        long now = helper.getLevel().getDayTime();
        long nextMorning = (Math.floorDiv(now, 24_000L) + 1L) * 24_000L
            + 2_000L;
        helper.getLevel().setDayTime(nextMorning);
        helper.assertTrue(nextMorning > now,
            "fixture work time may only advance after committed admission");
    }

    private static void fundTwoNaturalAdmissions(Fixture f) {
        put(f.hearth, Items.BREAD, 64);
        put(f.hearth, Items.OAK_PLANKS, 32);
    }

    private static void issueDefenderOrder(GameTestHelper helper, Fixture f,
                                           SettlerEntity defender,
                                           Building employer,
                                           boolean towerPost) {
        GuardOrder order = f.settlement.guardOrders.orderForMutation(
            f.settlement.id, defender.getUUID(),
            helper.getLevel().dimension().location()).orElseThrow();
        long now = helper.getLevel().getGameTime();
        BlockPos post = towerPost ? physicalTowerPost(helper, employer)
            : defender.blockPosition();
        helper.assertTrue(post != null,
            "fixture: the Watchtower must expose one loaded, walkable interior post");
        boolean authored = towerPost
            ? order.issueTower(post, Direction.NORTH,
                GuardOrder.DEFAULT_FACING_ARC, f.builder.getUUID(),
                employer.id, now)
            : order.issueStand(post, Direction.NORTH,
                GuardOrder.DEFAULT_LEASH_RADIUS, f.builder.getUUID(),
                employer.id, now);
        helper.assertTrue(authored
                && GuardAssignmentService.validate(helper.getLevel(),
                    f.settlement, defender, false).valid()
                && JourneyServerHooks.noteGuardAssignmentCommitted(f.builder,
                    f.settlement, defender, order, order.revision()),
            "the exact equipped " + defender.getProfession().key()
                + " must receive one persisted profession-valid order");
        SettlementSavedData.get(helper.getLevel()).setDirty();
    }

    /** Finds a real two-block-high floor cell inside the surveyed tower. */
    private static BlockPos physicalTowerPost(GameTestHelper helper,
                                              Building tower) {
        if (tower == null || tower.bounds == null) {
            return null;
        }
        var level = helper.getLevel();
        for (int y = tower.bounds.minY(); y < tower.bounds.maxY(); y++) {
            for (int x = tower.bounds.minX(); x <= tower.bounds.maxX(); x++) {
                for (int z = tower.bounds.minZ(); z <= tower.bounds.maxZ(); z++) {
                    BlockPos candidate = new BlockPos(x, y, z);
                    if (tower.contains(candidate) && level.isLoaded(candidate)
                        && level.getBlockState(candidate).isAir()
                        && level.getBlockState(candidate.above()).isAir()
                        && !level.getBlockState(candidate.below())
                            .getCollisionShape(level, candidate.below()).isEmpty()) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private static void purchaseAndHire(GameTestHelper helper, Fixture f,
                                        Profession profession,
                                        SettlerEntity settler,
                                        Building expectedWorkplace) {
        JobEmblemCatalog.Entry entry = JobEmblemCatalog.forProfession(profession);
        helper.assertTrue(entry != null,
            "fixture: " + profession.key() + " must have an emblem catalog entry");
        for (DevelopmentNode.Cost cost : entry.costs()) {
            put(f.hearth, cost.item(), cost.count());
        }
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        NetworkRegistry.configureMockConnection(player.connection.getConnection());
        player.getAbilities().instabuild = false;
        player.onUpdateAbilities();
        player.setPos(settler.getX(), settler.getY(), settler.getZ());
        selectEmptyMainHand(helper, player);
        Development.EmblemPurchase purchase = Development.purchaseEmblem(
            helper.getLevel(), f.settlement, f.hearth, profession,
            Development.revisionOf(helper.getLevel(), f.settlement), player);
        helper.assertTrue(purchase.applied(),
            "fixture: exact Hearth cost must issue the " + profession.key() + " emblem");
        PendingPlayerDeliveryLedger.DeliveryResult delivery =
            Development.deliverPending(helper.getLevel(), f.settlement, player,
                purchase.deliveryId());
        helper.assertTrue(delivery.outcome()
                == PendingPlayerDeliveryLedger.Outcome.MAIN_HAND,
            "fixture: paid emblem must transfer from its durable outbox into the empty hand; "
                + "outcome=" + delivery.outcome() + "; deliveryId="
                + purchase.deliveryId() + "; main=" + player.getMainHandItem()
                + "; offhand=" + player.getOffhandItem());
        ItemStack emblem = player.getMainHandItem();
        ((JobEmblemItem) emblem.getItem()).interactLivingEntity(emblem,
            player, settler, InteractionHand.MAIN_HAND);
        helper.assertTrue(expectedWorkplace.workers.contains(settler.getUUID())
                && settler.getProfession() == profession
                && player.getMainHandItem().isEmpty(),
            "giving the physical " + profession.key()
                + " emblem must consume it and select the compatible workplace");
    }

    private static void equipPhysical(GameTestHelper helper, Building workplace,
                                      SettlerEntity worker, Item tool) {
        ItemStack offered = new ItemStack(tool);
        helper.assertTrue(WorkplaceStorage.insert(helper.getLevel(), workplace,
                offered.copy()).isEmpty(),
            "fixture: physical tool must enter the worker's workplace first");
        equipPhysicalFromStorage(helper, workplace, worker);
    }

    private static void equipPhysicalFromStorage(GameTestHelper helper,
                                                 Building workplace,
                                                 SettlerEntity worker) {
        var requirement = EquipmentRequests.requirementFor(worker.getProfession());
        helper.assertTrue(requirement != null,
            "fixture: " + worker.getProfession().key() + " must require equipment");
        BlockPos source = WorkplaceStorage.nearestMatchingContainer(
            helper.getLevel(), workplace, requirement, worker.blockPosition());
        EquipmentRequest request = EquipmentRequests.requestFor(workplace,
            worker.getUUID());
        helper.assertTrue(source != null && request != null
                && helper.getLevel().getBlockEntity(source) instanceof Container,
            "fixture: the live request and its exact physical workplace tool "
                + "must both exist before contact");
        Container sourceContainer = (Container) helper.getLevel()
            .getBlockEntity(source);
        int sourceSlot = firstServiceableSlot(sourceContainer, requirement);
        ItemStack sourceBefore = sourceSlot < 0 ? ItemStack.EMPTY
            : sourceContainer.getItem(sourceSlot).copy();
        ItemStack heldBefore = worker.getMainHandItem().copy();
        helper.assertTrue(sourceSlot >= 0 && sourceBefore.getCount() == 1
                && !requirement.serviceable(heldBefore),
            "fixture: equipment contact must begin with one exact serviceable "
                + "source item and no already-serviceable held item");
        standBeside(worker, source);
        boolean equipped = EquipmentRequests.equipFromWorkplaceAt(
            helper.getLevel(), fSettlement(worker), worker, source);
        ItemStack heldAfter = worker.getMainHandItem().copy();
        ItemStack sourceAfter = sourceContainer.getItem(sourceSlot).copy();
        helper.assertTrue(equipped
                && requirement.serviceable(worker.getMainHandItem())
                && ItemStack.matches(sourceBefore, heldAfter)
                && ItemStack.matches(heldBefore, sourceAfter)
                && EquipmentRequests.requestFor(workplace,
                    worker.getUUID()) == null,
            "fixture: chest contact must swap the exact source item into the "
                + "worker's hand without loss or duplication and close its request; "
                + "sourceBefore=" + sourceBefore + "; heldBefore=" + heldBefore
                + "; sourceAfter=" + sourceAfter + "; heldAfter=" + heldAfter);
    }

    private static Settlement fSettlement(SettlerEntity worker) {
        Settlement settlement = worker.settlement();
        if (settlement == null) {
            throw new IllegalStateException("fixture worker lost settlement");
        }
        return settlement;
    }

    private static void serveMissingToolRequest(GameTestHelper helper, Fixture f,
                                                SettlerEntity worker,
                                                Building warehouse,
                                                BlockPos sourcePos,
                                                Building workplace,
                                                BlockPos targetPos,
                                                Item supplied) {
        helper.assertTrue(worker.getMainHandItem().isEmpty(),
            "fixture: the newly appointed " + worker.getProfession().key()
                + " must begin without its required weapon");
        EquipmentRequest request = EquipmentRequests.requestFor(workplace,
            worker.getUUID());
        helper.assertTrue(request != null
                && request.reason() == EquipmentRequest.Reason.MISSING
                && request.profession() == worker.getProfession()
                && EquipmentRequests.claim(helper.getLevel(), f.settlement,
                    request.id(), f.courier.getUUID()),
            "fixture: the live Courier must claim the exact defender request");
        var sourceEntity = helper.getLevel().getBlockEntity(sourcePos);
        var targetEntity = helper.getLevel().getBlockEntity(targetPos);
        helper.assertTrue(sourceEntity instanceof Container
                && targetEntity instanceof Container,
            "fixture: strict equipment route requires loaded source and target containers");
        Container source = (Container) sourceEntity;
        Container target = (Container) targetEntity;
        int sourceSlot = firstEmptySlot(source);
        int targetSlot = firstEmptySlot(target);
        int bagSlot = firstEmptySlot(f.courier.bag);
        helper.assertTrue(sourceSlot >= 0 && targetSlot >= 0 && bagSlot >= 0,
            "fixture: source, Courier bag and defender workplace must each have room");
        ItemStack exact = new ItemStack(supplied);
        source.setItem(sourceSlot, exact.copy());
        source.setChanged();
        helper.assertTrue(EquipmentRequests.bindRoute(helper.getLevel(),
                f.settlement, request.id(), f.courier, warehouse, sourcePos,
                sourceSlot, workplace, targetPos, exact),
            "fixture: request must bind the exact Warehouse slot and Barracks target");

        ItemStack inTransit = source.removeItem(sourceSlot, 1);
        source.setChanged();
        f.courier.bag.setItem(bagSlot, inTransit);
        helper.assertTrue(!inTransit.isEmpty()
                && EquipmentRequests.markPickedUp(helper.getLevel(),
                    f.settlement, request.id(), f.courier),
            "fixture: SOURCE -> COURIER_BAG must follow the physical withdrawal");

        ItemStack delivered = f.courier.bag.removeItem(bagSlot, 1);
        target.setItem(targetSlot, delivered);
        target.setChanged();
        helper.assertTrue(!delivered.isEmpty()
                && EquipmentRequests.markDelivered(helper.getLevel(),
                    f.settlement, request.id(), f.courier, targetPos),
            "fixture: defender equipment credit must follow exact BAG -> TARGET proof");
    }

    private static Hire purchaseAndHireDirectly(GameTestHelper helper, Fixture f) {
        JobEmblemCatalog.Entry entry = JobEmblemCatalog.forProfession(Profession.LUMBERER);
        helper.assertTrue(entry != null, "fixture: Lumberer must be in the Guildmaster catalog");
        for (DevelopmentNode.Cost cost : entry.costs()) {
            put(f.hearth, cost.item(), cost.count());
        }
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        NetworkRegistry.configureMockConnection(player.connection.getConnection());
        player.getAbilities().instabuild = false;
        player.onUpdateAbilities();
        player.setPos(f.plaque.getBlockPos().getX() + 0.5D,
            f.plaque.getBlockPos().getY() + 0.5D,
            f.plaque.getBlockPos().getZ() + 0.5D);
        selectEmptyMainHand(helper, player);
        Development.EmblemPurchase purchase = Development.purchaseEmblem(helper.getLevel(),
            f.settlement, f.hearth, Profession.LUMBERER,
            Development.revisionOf(helper.getLevel(), f.settlement), player);
        helper.assertTrue(purchase.applied(),
            "fixture: live Guildmaster, knowledge and exact Hearth price must issue one emblem");
        PendingPlayerDeliveryLedger.DeliveryResult delivery =
            Development.deliverPending(helper.getLevel(), f.settlement, player,
                purchase.deliveryId());
        helper.assertTrue(delivery.outcome()
                == PendingPlayerDeliveryLedger.Outcome.MAIN_HAND,
            "fixture: paid emblem must transfer from its durable outbox into the empty hand; "
                + "outcome=" + delivery.outcome() + "; deliveryId="
                + purchase.deliveryId() + "; main=" + player.getMainHandItem()
                + "; offhand=" + player.getOffhandItem());
        ItemStack emblem = player.getMainHandItem();
        ((JobEmblemItem) emblem.getItem()).interactLivingEntity(emblem,
            player, f.worker, InteractionHand.MAIN_HAND);
        helper.assertTrue(f.camp.workers.contains(f.worker.getUUID())
                && f.worker.getProfession() == Profession.LUMBERER,
            "giving the physical emblem to the settler must auto-select the camp");
        return new Hire(player);
    }

    /**
     * Login may have placed the physical Starter Handbook in selected hotbar
     * slot zero. Select a different, genuinely empty hotbar slot so this
     * fixture exercises the production delivery ladder's MAIN_HAND branch
     * without deleting or duplicating that independently durable item.
     */
    private static void selectEmptyMainHand(GameTestHelper helper,
                                            ServerPlayer player) {
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) {
                player.getInventory().selected = slot;
                helper.assertTrue(player.getMainHandItem().isEmpty(),
                    "fixture: selected empty hotbar slot must expose an empty main hand");
                return;
            }
        }
        helper.fail("fixture: paid emblem MAIN_HAND scenario requires one empty hotbar slot");
    }

    private static void commitLumberZoneForJourney(GameTestHelper helper,
                                                   Fixture fixture) {
        prepareNaturalLumberFixture(helper);
        WorkZone zone = WorkZone.between(fixture.settlement.id,
            fixture.camp.id, WorkZone.Type.LUMBER,
            helper.getLevel().dimension().location(),
            helper.absolutePos(LUMBER_TREE_ROOT.offset(-4, -1, -4)),
            helper.absolutePos(LUMBER_TREE_ROOT.offset(4, 6, 4)),
            fixture.camp.workZoneRevision() + 1);
        helper.assertTrue(WorkZoneService.validateCandidate(helper.getLevel(),
                fixture.settlement, fixture.camp, zone)
                == WorkZoneService.Result.APPLIED
                && fixture.camp.commitWorkZone(
                fixture.camp.workZoneRevision(), zone)
                && FoundingJourneyProgress.noteWorkZoneCommitted(
                    helper.getLevel(), fixture.settlement, fixture.camp, zone)
                && fixture.settlement.foundingJourney.phase()
                    == FoundingJourney.Phase.DELIVER_FIRST_LOG,
            "fixture: an exact committed Lumber zone must open first-log delivery");
    }

    private static void unlock(GameTestHelper helper, HearthBlockEntity hearth,
                               Settlement settlement, DevelopmentNode node,
                               ServerPlayer actor) {
        if (Development.of(helper.getLevel(), settlement).unlocked(node)) {
            return;
        }
        for (DevelopmentNode.Cost cost : node.costs()) {
            put(hearth, cost.item(), cost.count());
        }
        Development.Result result = Development.purchaseNode(helper.getLevel(), settlement,
            hearth, node, Development.revisionOf(helper.getLevel(), settlement),
            actor);
        helper.assertTrue(result == Development.Result.APPLIED,
            "server-authoritative " + node.id() + " unlock failed: " + result);
    }

    private static PlaqueAndBuilding buildHouse(GameTestHelper helper,
                                                Settlement settlement,
                                                ServerPlayer builder,
                                                BlockPos origin, int beds) {
        buildRoom(helper, origin, 5);
        placeBeds(helper, origin, beds);
        helper.setBlock(origin.offset(3, 2, 3), Blocks.TORCH);
        return fitPlan(helper, settlement, builder, origin,
            BuildingType.HOUSE, null);
    }

    private static void placeBeds(GameTestHelper helper, BlockPos origin,
                                  int count) {
        BlockPos[][] positions = {
            {origin.offset(1, 1, 1), origin.offset(1, 1, 2)},
            {origin.offset(2, 1, 1), origin.offset(2, 1, 2)},
            {origin.offset(3, 1, 1), origin.offset(3, 1, 2)},
            {origin.offset(1, 1, 3), origin.offset(2, 1, 3)}
        };
        Direction[] directions = {
            Direction.SOUTH, Direction.SOUTH, Direction.SOUTH, Direction.EAST
        };
        for (int i = 0; i < Math.min(count, positions.length); i++) {
            helper.setBlock(positions[i][0], Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, directions[i])
                .setValue(BedBlock.PART, BedPart.FOOT));
            helper.setBlock(positions[i][1], Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, directions[i])
                .setValue(BedBlock.PART, BedPart.HEAD));
        }
    }

    private static PlaqueAndBuilding buildCamp(GameTestHelper helper,
                                               Settlement settlement,
                                               ServerPlayer builder) {
        buildRoom(helper, CAMP_ORIGIN, 6);
        helper.setBlock(CAMP_ORIGIN.offset(1, 1, 2), Blocks.CRAFTING_TABLE);
        BlockPos storage = CAMP_ORIGIN.offset(3, 1, 3);
        helper.setBlock(storage, Blocks.CHEST);
        helper.setBlock(CAMP_ORIGIN.offset(1, 2, 1), Blocks.TORCH);
        return fitPlan(helper, settlement, builder, CAMP_ORIGIN,
            BuildingType.LUMBER_CAMP, storage);
    }

    private static PlaqueAndBuilding buildFarmhouse(GameTestHelper helper,
                                                    Settlement settlement,
                                                    ServerPlayer builder) {
        buildRoom(helper, FARM_ORIGIN, 6);
        helper.setBlock(FARM_ORIGIN.offset(1, 1, 2), Blocks.COMPOSTER);
        BlockPos storage = FARM_ORIGIN.offset(3, 1, 3);
        helper.setBlock(storage, Blocks.CHEST);
        helper.setBlock(FARM_ORIGIN.offset(1, 2, 1), Blocks.TORCH);
        return fitPlan(helper, settlement, builder, FARM_ORIGIN,
            BuildingType.FARMHOUSE, storage);
    }

    private static PlaqueAndBuilding buildWarehouse(GameTestHelper helper,
                                                    Settlement settlement,
                                                    ServerPlayer builder) {
        buildRoom(helper, WAREHOUSE_ORIGIN, 7);
        BlockPos storage = WAREHOUSE_ORIGIN.offset(1, 1, 1);
        helper.setBlock(storage, Blocks.CHEST);
        helper.setBlock(WAREHOUSE_ORIGIN.offset(3, 1, 1), Blocks.CHEST);
        helper.setBlock(WAREHOUSE_ORIGIN.offset(1, 1, 3), Blocks.BARREL);
        helper.setBlock(WAREHOUSE_ORIGIN.offset(3, 1, 3), Blocks.BARREL);
        helper.setBlock(WAREHOUSE_ORIGIN.offset(1, 2, 4), Blocks.TORCH);
        helper.setBlock(WAREHOUSE_ORIGIN.offset(4, 2, 4), Blocks.TORCH);
        return fitPlan(helper, settlement, builder, WAREHOUSE_ORIGIN,
            BuildingType.WAREHOUSE, storage);
    }

    private static PlaqueAndBuilding buildTavern(GameTestHelper helper,
                                                 Settlement settlement,
                                                 ServerPlayer builder) {
        buildRoom(helper, TAVERN_ORIGIN, 8);
        helper.setBlock(TAVERN_ORIGIN.offset(2, 1, 2), Blocks.BELL);
        BlockPos storage = TAVERN_ORIGIN.offset(4, 1, 2);
        helper.setBlock(storage, Blocks.BARREL);
        helper.setBlock(TAVERN_ORIGIN.offset(4, 1, 4), Blocks.BARREL);
        helper.setBlock(TAVERN_ORIGIN.offset(5, 1, 4), ModBlocks.ALE_TAP.get().defaultBlockState()
            .setValue(com.hearthstead.block.AleTapBlock.FACING, Direction.EAST));
        helper.setBlock(TAVERN_ORIGIN.offset(1, 2, 1), Blocks.TORCH);
        helper.setBlock(TAVERN_ORIGIN.offset(3, 2, 5), Blocks.TORCH);
        helper.setBlock(TAVERN_ORIGIN.offset(5, 2, 3), Blocks.TORCH);
        return fitPlan(helper, settlement, builder, TAVERN_ORIGIN,
            BuildingType.TAVERN, storage);
    }

    private static PlaqueAndBuilding buildBarracks(GameTestHelper helper,
                                                   Settlement settlement,
                                                   ServerPlayer builder) {
        buildRoom(helper, BARRACKS_ORIGIN, 8);
        placeBeds(helper, BARRACKS_ORIGIN, 4);
        BlockPos storage = BARRACKS_ORIGIN.offset(4, 1, 2);
        helper.setBlock(storage, Blocks.BARREL);
        helper.setBlock(BARRACKS_ORIGIN.offset(4, 1, 4), Blocks.BARREL);
        helper.setBlock(BARRACKS_ORIGIN.offset(1, 2, 4), Blocks.TORCH);
        helper.setBlock(BARRACKS_ORIGIN.offset(5, 2, 4), Blocks.TORCH);
        return fitPlan(helper, settlement, builder, BARRACKS_ORIGIN,
            BuildingType.BARRACKS, storage);
    }

    private static PlaqueAndBuilding buildWatchtower(GameTestHelper helper,
                                                     Settlement settlement,
                                                     ServerPlayer builder,
                                                     BlockPos origin) {
        buildRoom(helper, origin, 6);
        for (int y = 1; y <= 3; y++) {
            helper.setBlock(origin.offset(1, y, 2),
                Blocks.LADDER.defaultBlockState()
                    .setValue(LadderBlock.FACING, Direction.EAST));
        }
        helper.setBlock(origin.offset(4, 1, 2),
            Blocks.LADDER.defaultBlockState()
                .setValue(LadderBlock.FACING, Direction.WEST));
        BlockPos storage = origin.offset(3, 1, 3);
        helper.setBlock(storage, Blocks.BARREL);
        helper.setBlock(origin.offset(1, 2, 1), Blocks.TORCH);
        helper.setBlock(origin.offset(4, 2, 1), Blocks.TORCH);
        helper.setBlock(origin.offset(1, 2, 4), Blocks.TORCH);
        helper.setBlock(origin.offset(4, 2, 4), Blocks.TORCH);
        return fitPlan(helper, settlement, builder, origin,
            BuildingType.WATCHTOWER, storage);
    }

    private static PlaqueAndBuilding fitPlan(GameTestHelper helper,
                                             Settlement settlement,
                                             ServerPlayer builder,
                                             BlockPos origin,
                                             BuildingType type,
                                             BlockPos storageRelative) {
        BlockPos plaqueRelative = origin.offset(1, 2, -1);
        helper.setBlock(plaqueRelative, ModBlocks.PLAQUE.get().defaultBlockState()
            .setValue(PlaqueBlock.FACING, Direction.NORTH));
        PlaqueBlockEntity plaque = (PlaqueBlockEntity) helper.getLevel()
            .getBlockEntity(helper.absolutePos(plaqueRelative));
        helper.assertTrue(plaque != null, "fixture: " + type.id() + " plaque must exist");
        Settlement nearest = plaque.settlementFor(helper.getLevel());
        helper.assertTrue(nearest == settlement,
            "fixture: " + type.id() + " plaque must resolve the local founding "
                + "authority before plan use; expected=" + settlement.id + "@"
                + settlement.center + "; actual="
                + (nearest == null ? "none" : nearest.id + "@" + nearest.center)
                + "; plaque=" + helper.absolutePos(plaqueRelative));
        ItemStack plan = PlaqueItemData.stamped(new ItemStack(ModItems.BUILD_PLAN.get()), type);
        builder.setItemInHand(InteractionHand.MAIN_HAND, plan);
        BlockPos plaqueAbsolute = helper.absolutePos(plaqueRelative);
        builder.setPos(plaqueAbsolute.getX() + 0.5D, plaqueAbsolute.getY() + 0.5D,
            plaqueAbsolute.getZ() + 0.5D);
        helper.getLevel().getBlockState(plaqueAbsolute).useItemOn(
            builder.getMainHandItem(), helper.getLevel(), builder,
            InteractionHand.MAIN_HAND, new BlockHitResult(
                new Vec3(plaqueAbsolute.getX() + 0.5D,
                    plaqueAbsolute.getY() + 0.5D,
                    plaqueAbsolute.getZ() + 0.5D),
                Direction.NORTH, plaqueAbsolute, false));
        helper.assertTrue(builder.getMainHandItem().isEmpty()
                && plaque.state() == PlaqueState.LINKED_VALID,
            "fixture: player use of learned " + type.id()
                + " plan must consume it and survey a valid physical room");
        Settlement linkedOwner = plaque.settlementFor(helper.getLevel());
        Building building = plaque.building(helper.getLevel());
        helper.assertTrue(building != null && building.valid
                && settlement.buildings.contains(building),
            "fixture: " + type.id() + " plaque must resolve its exact saved "
                + "building; expectedSettlement=" + settlement.id
                + "; linkedOwner="
                + (linkedOwner == null ? "none" : linkedOwner.id)
                + "; plaqueBuilding=" + plaque.buildingId()
                + "; resolvedBuilding="
                + (building == null ? "none" : building.id)
                + "; expectedContains="
                + (building != null && settlement.buildings.contains(building)));
        return new PlaqueAndBuilding(plaque, building, storageRelative);
    }

    private static void buildRoom(GameTestHelper helper, BlockPos origin, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                boolean wall = x == 0 || z == 0 || x == size - 1 || z == size - 1;
                for (int y = 1; y <= 3; y++) {
                    helper.setBlock(origin.offset(x, y, z),
                        wall ? Blocks.STONE_BRICKS : Blocks.AIR);
                }
                helper.setBlock(origin.offset(x, 0, z), Blocks.STONE_BRICKS);
                helper.setBlock(origin.offset(x, 4, z), Blocks.STONE_BRICKS);
            }
        }
        int doorX = size / 2;
        helper.setBlock(origin.offset(doorX, 1, 0),
            Blocks.OAK_DOOR.defaultBlockState());
        helper.setBlock(origin.offset(doorX, 2, 0),
            Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    private static void openJourney(GameTestHelper helper,
                                    ServerPlayer player,
                                    HearthBlockEntity hearth,
                                    Settlement settlement) {
        player.setPos(settlement.center.getX() + 0.5D,
            settlement.center.getY() + 0.5D,
            settlement.center.getZ() + 0.5D);
        player.openMenu(hearth, buffer -> {
            buffer.writeBlockPos(settlement.center);
            buffer.writeUUID(settlement.id);
            buffer.writeUtf(settlement.name);
        });
        helper.assertTrue(player.containerMenu instanceof HearthMenu
                && JourneyServerHooks.noteJourneyViewOpened(player, settlement),
            "fixture: a real nearby Hearth menu must author Journey opening");
        player.closeContainer();
    }

    private static void viewLumberInventory(GameTestHelper helper, Fixture f) {
        f.builder.closeContainer();
        f.builder.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        f.builder.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        f.builder.setPos(f.worker.getX(), f.worker.getY(), f.worker.getZ());
        f.builder.setShiftKeyDown(true);
        var result = f.worker.interact(f.builder, InteractionHand.MAIN_HAND);
        f.builder.setShiftKeyDown(false);
        helper.assertTrue(result.consumesAction()
                && f.builder.containerMenu instanceof SettlerInventoryMenu
                && f.settlement.journeyState.isCompleted(
                    JourneyIds.FJ_130_OPEN_LUMBERER_INVENTORY),
            "fixture: empty-hand sneak interaction must open the exact "
                + "Lumberer inventory and author its Journey receipt");
        f.builder.closeContainer();
    }

    /** Executes one complete, provenance-backed natural-tree output cycle. */
    private static ItemStack performLumberWork(GameTestHelper helper,
                                               Fixture f) {
        prepareNaturalLumberFixture(helper);
        BlockPos root = helper.absolutePos(LUMBER_TREE_ROOT);
        BlockPos top = root.above();
        List<BlockPos> leafPositions = List.of(top.north(), top.south(),
            top.east(), top.west());
        WorkZone zone = f.camp.workZone().orElseThrow();
        List<BlockPos> logs = List.of(root, top);
        standAtWorkTarget(helper, f.worker, root, root.getY(), "tree root");
        UUID action = WorkerProvenanceService.beginLumberTree(
            helper.getLevel(), f.settlement, f.camp, f.worker, zone, root,
            logs);
        helper.assertTrue(action != null,
            "fixture: the loaded natural tree must begin one exact Lumber action");

        GroundCollectionSession collection = new GroundCollectionSession(
            f.worker, stack -> stack.is(ItemTags.LOGS), logs.size());
        int carriedLogs = 0;
        for (BlockPos log : List.of(top, root)) {
            standAtWorkTarget(helper, f.worker, log, root.getY(), "tree log");
            ItemStack output = new ItemStack(Items.OAK_LOG);
            helper.assertTrue(WorkerProvenanceService.prepareLumberLog(
                    helper.getLevel(), f.settlement, f.camp, f.worker,
                    action, log)
                    && WorkerProvenanceService.stampOutput(helper.getLevel(),
                        f.settlement, f.camp, f.worker, action, output, log),
                "fixture: each physical log must receive one tool-use and "
                    + "transit receipt before its block is removed");
            UUID transfer = collection.queuePhysical(helper.getLevel(), log,
                output);
            helper.assertTrue(transfer != null,
                "fixture: stamped Lumber output must enter durable transfer authority");
            helper.getLevel().setBlockAndUpdate(log,
                Blocks.AIR.defaultBlockState());
            helper.assertTrue(collection.materializeQueued(helper.getLevel(),
                    log, transfer),
                "fixture: source-cleared Lumber output must materialize exactly once");
            ItemEntity physical = helper.getLevel().getEntity(transfer)
                instanceof ItemEntity item ? item : null;
            helper.assertTrue(physical != null && physical.isAlive()
                    && physical.getItem().is(Items.OAK_LOG)
                    && WorkerStackProvenance.readTransit(
                        physical.getItem()).filter(transit ->
                            transit.actionId().equals(action)).isPresent(),
                "fixture: the exact action-stamped log must exist in world authority");
            helper.assertTrue(WorkerProvenanceService.completeLumberLog(
                    helper.getLevel(), f.settlement, f.camp, f.worker,
                    action, log, physical.getItem()),
                "fixture: each removed log must close its exact operation");
            standAtClearedPickupContact(helper, f.worker, log, "tree log");
            collection.select(physical);
            helper.assertTrue(collection.hasClearPickupLine(helper.getLevel(),
                    physical)
                    && collection.takeOneToOffhand(helper.getLevel(), 4.0D)
                        == GroundCollectionSession.PickupResult.PICKED
                    && !physical.isAlive()
                    && f.worker.getOffhandItem().is(Items.OAK_LOG),
                "fixture: physical pickup contact must move the world log into offhand");
            helper.assertTrue(collection.stowOne()
                    == GroundCollectionSession.StowResult.STOWED
                    && f.worker.getOffhandItem().isEmpty(),
                "fixture: the carried offhand log must enter the persistent worker bag");
            carriedLogs++;
            helper.assertTrue(countIn(f.worker.bag, Items.OAK_LOG) == carriedLogs,
                "fixture: every resolved log must remain physical in the carried bag");
        }
        for (BlockPos leaf : leafPositions) {
            helper.getLevel().setBlockAndUpdate(leaf,
                Blocks.AIR.defaultBlockState());
        }
        helper.assertTrue(WorkerProvenanceService.commitLumberTree(
                helper.getLevel(), f.settlement, f.camp, f.worker, action),
            "fixture: the resolved whole tree must author FJ-170 once");

        BlockPos campStorage = helper.absolutePos(f.storageRelative);
        standBeside(f.worker, campStorage);
        int depositedLogs = 0;
        while (depositedLogs < carriedLogs) {
            int slot = firstTaggedSlot(f.worker.bag, Items.OAK_LOG, action);
            helper.assertTrue(slot >= 0,
                "fixture: each deposit must originate in an action-tagged bag slot");
            ItemStack carried = f.worker.bag.getItem(slot);
            WorkerProvenanceService.DepositResult deposited =
                WorkerProvenanceService.depositOutput(helper.getLevel(),
                    f.settlement, f.camp, f.worker, campStorage, carried);
            f.worker.bag.setItem(slot, deposited.remainder());
            helper.assertTrue(deposited.remainder().isEmpty()
                    && deposited.receipt() != null,
                "fixture: each stamped log must enter the exact Camp chest "
                    + "through a durable output receipt");
            depositedLogs++;
        }
        Container storage = storage(helper, f.storageRelative);
        helper.assertTrue(countIn(storage, Items.OAK_LOG) == carriedLogs
                && countIn(f.worker.bag, Items.OAK_LOG) == 0,
            "fixture: every produced log must be physical Camp stock");
        return new ItemStack(Items.OAK_LOG, carriedLogs);
    }

    /** One natural, loaded tree shared by Work Zone validation and work. */
    private static void prepareNaturalLumberFixture(GameTestHelper helper) {
        BlockPos root = helper.absolutePos(LUMBER_TREE_ROOT);
        BlockPos top = root.above();
        helper.setBlock(LUMBER_TREE_ROOT.below(), Blocks.DIRT);
        helper.setBlock(LUMBER_TREE_ROOT, Blocks.OAK_LOG);
        helper.setBlock(LUMBER_TREE_ROOT.above(), Blocks.OAK_LOG);
        for (BlockPos leaf : List.of(top.north(), top.south(), top.east(),
                top.west())) {
            helper.getLevel().setBlockAndUpdate(leaf,
                Blocks.OAK_LEAVES.defaultBlockState());
        }
    }

    private static void viewRequestLedger(GameTestHelper helper, Fixture f) {
        f.builder.setPos(f.settlement.center.getX() + 0.5D,
            f.settlement.center.getY() + 0.5D,
            f.settlement.center.getZ() + 0.5D);
        f.builder.openMenu(f.hearth, buffer -> {
            buffer.writeBlockPos(f.settlement.center);
            buffer.writeUUID(f.settlement.id);
            buffer.writeUtf(f.settlement.name);
        });
        helper.assertTrue(f.builder.containerMenu instanceof HearthMenu
                && !f.settlement.journeyState.isCompleted(
                    JourneyIds.FJ_230_OPEN_REQUEST_LEDGER),
            "fixture: opening the Hearth alone must not claim a delivered request ledger");
        HearthMenu menu = (HearthMenu) f.builder.containerMenu;
        EmbeddedChannel channel = (EmbeddedChannel) f.builder.connection
            .getConnection().channel();
        drainOutbound(channel);
        HearthMayorSnapshot[] delivered = new HearthMayorSnapshot[1];
        int[] deliveredSnapshots = new int[1];
        int[] matchingLedgerPayloads = new int[1];
        boolean[] journeyIncompleteAtMatchingPayloadWrite = new boolean[1];
        List<String> observedOutbound = new java.util.ArrayList<>();
        String captureName = "hearthstead_request_ledger_capture_"
            + menu.containerId;
        channel.pipeline().addLast(captureName,
            new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext context, Object message,
                                  ChannelPromise promise) throws Exception {
                    if (observedOutbound.size() < 16) {
                        observedOutbound.add(message instanceof
                                ClientboundCustomPayloadPacket packet
                            ? message.getClass().getSimpleName() + ":"
                                + packet.payload().type().id()
                            : message.getClass().getName());
                    }
                    if (message instanceof ClientboundCustomPayloadPacket packet
                        && packet.payload()
                            instanceof HearthMayorSnapshot snapshot) {
                        delivered[0] = snapshot;
                        deliveredSnapshots[0]++;
                        if (snapshot.requests().matches(f.settlement.id,
                                menu.containerId)) {
                            matchingLedgerPayloads[0]++;
                            journeyIncompleteAtMatchingPayloadWrite[0] =
                                !f.settlement.journeyState.isCompleted(
                                    JourneyIds.FJ_230_OPEN_REQUEST_LEDGER);
                        }
                    }
                    super.write(context, message, promise);
                }
            });
        try {
            HearthNetwork.handle(f.builder, new HearthMayorAction(
                f.settlement.center, f.settlement.id, menu.containerId,
                HearthMayorAction.Kind.OPEN_REQUEST_LEDGER,
                HearthMayorAction.NO_ID, 0));
            channel.runPendingTasks();
            drainOutbound(channel);
            helper.assertTrue(deliveredSnapshots[0] == 1
                    && matchingLedgerPayloads[0] == 1
                    && journeyIncompleteAtMatchingPayloadWrite[0]
                    && delivered[0] != null
                    && delivered[0].requests().matches(f.settlement.id,
                        menu.containerId)
                    && f.settlement.journeyState.isCompleted(
                        JourneyIds.FJ_230_OPEN_REQUEST_LEDGER),
                "the production Hearth handler must hand one bounded ledger payload "
                    + "to the exact connection before authoring its Journey receipt; "
                    + "observed=" + observedOutbound + "; pipeline="
                    + channel.pipeline().names() + "; deliveredCount="
                    + deliveredSnapshots[0] + "; matchingPayloads="
                    + matchingLedgerPayloads[0] + "; incompleteAtWrite="
                    + journeyIncompleteAtMatchingPayloadWrite[0] + "; request="
                    + (delivered[0] == null ? "none"
                        : delivered[0].requests().open() + "/"
                            + delivered[0].requests().settlementId() + "/"
                            + delivered[0].requests().containerId())
                    + "; expected=" + f.settlement.id + "/"
                    + menu.containerId + "; journey="
                    + f.settlement.journeyState.isCompleted(
                        JourneyIds.FJ_230_OPEN_REQUEST_LEDGER)
                    + "; mode=" + f.settlement.journeyState.mode()
                    + "; quarantine="
                    + f.settlement.journeyState.quarantineReason()
                    + "; current=" + f.settlement.journeyState.currentStep()
                        .map(step -> step.id().toString()).orElse("none")
                    + "; completed="
                    + f.settlement.journeyState.completedCount()
                    + "; fj220=" + f.settlement.journeyState.isCompleted(
                        JourneyIds.FJ_220_STAFF_WAREHOUSE));
        } finally {
            if (channel.pipeline().context(captureName) != null) {
                channel.pipeline().remove(captureName);
            }
        }
        f.builder.closeContainer();
    }

    private static void drainOutbound(EmbeddedChannel channel) {
        channel.runPendingTasks();
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            ReferenceCountUtil.release(outbound);
        }
    }

    /** Routes one exact physical source stack through Courier bag authority. */
    private static int routeOutput(GameTestHelper helper, Fixture f,
                                   Building source, BlockPos sourceContainer,
                                   Building target, BlockPos targetContainer,
                                   Item item, int requested) {
        Container sourceInventory = container(helper, sourceContainer);
        int sourceSlot = firstSlot(sourceInventory, item, requested);
        helper.assertTrue(sourceSlot >= 0,
            "fixture: output route source must hold the requested stack");
        RequestLedgerService.Decision opened =
            RequestLedgerService.openOutputPickup(helper.getLevel(),
                f.settlement, source, sourceContainer, sourceSlot, target,
                targetContainer, requested, RequestPriority.NORMAL);
        RequestRecord request = opened.request();
        helper.assertTrue(opened.outcome()
                == RequestLedgerService.Outcome.COMMITTED && request != null,
            "fixture: exact source and Warehouse target must open one typed request");
        RequestLedgerService.Decision reserved = RequestLedgerService.reserve(
            helper.getLevel(), f.settlement, request.id(), f.courier);
        helper.assertTrue(reserved.outcome()
                == RequestLedgerService.Outcome.COMMITTED,
            "fixture: the live Warehouse Courier must reserve the exact request");

        standBeside(f.courier, sourceContainer);
        RequestLedgerService.Decision pickedUp = RequestLedgerService.pickup(
            helper.getLevel(), f.settlement, request.id(), f.courier);
        helper.assertTrue(pickedUp.outcome()
                == RequestLedgerService.Outcome.COMMITTED,
            "fixture: exact source stock must move into the Courier bag");
        standBeside(f.courier, targetContainer);
        RequestLedgerService.Decision delivered = RequestLedgerService.deliver(
            helper.getLevel(), f.settlement, request.id(), f.courier);
        helper.assertTrue(delivered.outcome()
                == RequestLedgerService.Outcome.SATISFIED
                && f.courier.bag.isEmpty(),
            "fixture: exact Courier bag stock must reach Warehouse and close once");
        return request.deliveredCount();
    }

    private static void commitFarmZoneForJourney(GameTestHelper helper,
                                                 Fixture f,
                                                 Building farmhouse) {
        WorkZone zone = WorkZone.between(f.settlement.id, farmhouse.id,
            WorkZone.Type.FARM, helper.getLevel().dimension().location(),
            helper.absolutePos(new BlockPos(6, 0, 8)),
            helper.absolutePos(new BlockPos(7, 3, 12)),
            farmhouse.workZoneRevision() + 1);
        helper.assertTrue(farmhouse.commitWorkZone(
                farmhouse.workZoneRevision(), zone)
                && JourneyServerHooks.noteWorkZoneCommitted(helper.getLevel(),
                    f.settlement, farmhouse, zone),
            "fixture: exact Farmhouse authority must commit a bounded field zone");
    }

    /** Plants, harvests and deposits one crop through physical provenance. */
    private static ItemStack performFarmWork(GameTestHelper helper, Fixture f,
                                             Building farmhouse,
                                             BlockPos farmStorage) {
        helper.setBlock(FARM_FIELD.below(), Blocks.FARMLAND);
        helper.setBlock(FARM_FIELD, Blocks.AIR);
        helper.assertTrue(WorkplaceStorage.insert(helper.getLevel(), farmhouse,
                new ItemStack(Items.WHEAT_SEEDS)).isEmpty(),
            "fixture: one physical seed must enter Farmhouse storage");
        standBeside(f.farmer, farmStorage);
        BlockPos crop = helper.absolutePos(FARM_FIELD);
        UUID plant = WorkerProvenanceService.supplyOneSeedAt(
            helper.getLevel(), f.settlement, farmhouse, f.farmer,
            farmStorage, stack -> stack.is(Items.WHEAT_SEEDS), crop);
        helper.assertTrue(plant != null,
            "fixture: the Farmer must withdraw one exact tagged seed");
        ItemStack consumedSeed = removeAll(f.farmer.bag, Items.WHEAT_SEEDS);
        standAtFarmContact(helper, f.farmer, crop, crop);
        // Diagnose the physical prerequisite without moving the established
        // fixture or weakening the seed/tool assertion below.
        Vec3 farmEyes = f.farmer.getEyePosition();
        Vec3 farmPoint = Vec3.atBottomCenterOf(crop).add(0, 0.2D, 0);
        StringBuilder farmCells = new StringBuilder();
        boolean farmRayLoaded = true;
        for (BlockPos cell : BlockPos.betweenClosed(BlockPos.containing(farmEyes),
                BlockPos.containing(farmPoint))) {
            boolean loaded = WorkZoneService.livePositionAvailable(helper.getLevel(), cell);
            farmRayLoaded &= loaded;
            farmCells.append(cell).append(" loaded=").append(loaded);
            if (loaded) farmCells.append(" state=").append(helper.getLevel().getBlockState(cell));
            farmCells.append(';');
        }
        var farmHit = farmRayLoaded ? helper.getLevel().clip(
            new net.minecraft.world.level.ClipContext(farmEyes, farmPoint,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, f.farmer)) : null;
        String farmContactDetails = "; feet=" + f.farmer.position()
            + "; block=" + f.farmer.blockPosition() + "; eye=" + farmEyes
            + "; target=" + crop + "; ray="
            + (farmHit == null ? "not clipped: unloaded cells"
                : farmHit.getType() + "@" + farmHit.getBlockPos())
            + "; cells=" + farmCells + "; consumedSeed=" + consumedSeed.getCount()
            + "; heldTool=" + f.farmer.getMainHandItem()
            + "; toolDamage=" + f.farmer.getMainHandItem().getDamageValue();
        helper.assertTrue(FarmWorkApproach.canContact(helper.getLevel(), f.farmer,
                crop, FarmWorkApproach.Contact.PLANT),
            "fixture: prepared farm work must have actual contact" + farmContactDetails);
        helper.assertTrue(consumedSeed.getCount() == 1
                && WorkerProvenanceService.prepareFarmPlant(helper.getLevel(),
                    f.settlement, farmhouse, f.farmer, plant, crop),
            "fixture: one carried seed and one hoe use must fund planting" + farmContactDetails);
        helper.setBlock(FARM_FIELD, Blocks.WHEAT.defaultBlockState());
        helper.assertTrue(WorkerProvenanceService.commitFarmPlant(
                helper.getLevel(), f.settlement, farmhouse, f.farmer,
                plant, crop, BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT)),
            "fixture: the consumed seed must become one physical crop block");

        helper.setBlock(FARM_FIELD, Blocks.WHEAT.defaultBlockState()
            .setValue(CropBlock.AGE, CropBlock.MAX_AGE));
        standAtFarmContact(helper, f.farmer, crop, crop);
        UUID harvest = WorkerProvenanceService.beginFarmHarvest(
            helper.getLevel(), f.settlement, farmhouse, f.farmer, crop);
        ItemStack output = new ItemStack(Items.WHEAT);
        helper.assertTrue(harvest != null
                && WorkerProvenanceService.stampOutput(helper.getLevel(),
                    f.settlement, farmhouse, f.farmer, harvest, output, crop),
            "fixture: the mature crop must begin one receipt-backed harvest");
        GroundCollectionSession collection = new GroundCollectionSession(
            f.farmer, stack -> stack.is(Items.WHEAT), 1);
        UUID transfer = collection.queuePhysical(helper.getLevel(), crop,
            output);
        helper.assertTrue(transfer != null,
            "fixture: stamped harvest must enter durable transfer authority");
        helper.setBlock(FARM_FIELD, Blocks.AIR);
        helper.assertTrue(collection.materializeQueued(helper.getLevel(), crop,
                transfer),
            "fixture: source-cleared harvest must materialize exactly once");
        ItemEntity physical = helper.getLevel().getEntity(transfer)
            instanceof ItemEntity item ? item : null;
        helper.assertTrue(physical != null && physical.isAlive()
                && physical.getItem().is(Items.WHEAT)
                && WorkerStackProvenance.readTransit(physical.getItem())
                    .filter(transit -> transit.actionId().equals(harvest))
                    .isPresent(),
            "fixture: exact harvest output must exist in world authority");
        helper.assertTrue(WorkerProvenanceService.commitFarmHarvest(
                helper.getLevel(), f.settlement, farmhouse, f.farmer,
                harvest, crop, List.of(physical.getItem())),
            "fixture: the removed mature crop must close one harvest action");
        // Plant contact permits 6.5 squared blocks; pickup deliberately requires 4.
        // Stage this direct-service fixture at its now-cleared physical source.
        standAtClearedPickupContact(helper, f.farmer, crop, "harvest output");
        helper.assertTrue(helper.getLevel().noCollision(f.farmer),
            "harvest pickup stand must be physically clear");
        collection.select(physical);
        helper.assertTrue(collection.takeOneToOffhand(helper.getLevel(),
                4.0D) == GroundCollectionSession.PickupResult.PICKED
                && !physical.isAlive()
                && f.farmer.getOffhandItem().is(Items.WHEAT),
            "fixture: harvest pickup contact must move wheat into offhand");
        helper.assertTrue(collection.stowOne()
                == GroundCollectionSession.StowResult.STOWED
                && f.farmer.getOffhandItem().isEmpty()
                && countIn(f.farmer.bag, Items.WHEAT) == 1,
            "fixture: the carried wheat must enter the persistent Farmer bag");
        standBeside(f.farmer, farmStorage);
        int outputSlot = firstTaggedSlot(f.farmer.bag, Items.WHEAT, harvest);
        helper.assertTrue(outputSlot >= 0,
            "fixture: Farmhouse deposit must originate in the tagged Farmer bag");
        ItemStack carriedOutput = f.farmer.bag.getItem(outputSlot);
        WorkerProvenanceService.DepositResult deposited =
            WorkerProvenanceService.depositOutput(helper.getLevel(),
                f.settlement, farmhouse, f.farmer, farmStorage, carriedOutput);
        f.farmer.bag.setItem(outputSlot, deposited.remainder());
        helper.assertTrue(deposited.remainder().isEmpty()
                && deposited.receipt() != null
                && countIn(f.farmer.bag, Items.WHEAT) == 0,
            "fixture: harvested wheat must enter Farmhouse storage by receipt");
        return new ItemStack(Items.WHEAT);
    }

    private static Container container(GameTestHelper helper,
                                       BlockPos absolute) {
        if (!(helper.getLevel().getBlockEntity(absolute)
                instanceof Container container)) {
            throw new IllegalStateException("fixture container is not loaded");
        }
        return container;
    }

    private static int firstSlot(Container container, Item item, int count) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(item)
                && container.getItem(slot).getCount() >= count) {
                return slot;
            }
        }
        return -1;
    }

    private static int firstServiceableSlot(Container container,
                                            EquipmentRequirement requirement) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (requirement.serviceable(container.getItem(slot))) {
                return slot;
            }
        }
        return -1;
    }

    private static int firstTaggedSlot(Container container, Item item,
                                       UUID actionId) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item) && WorkerStackProvenance.readTransit(stack)
                    .filter(transit -> transit.actionId().equals(actionId))
                    .isPresent()) {
                return slot;
            }
        }
        return -1;
    }

    /** Direct-service fixture positioning; never changes terrain or work authority. */
    private static void standAtFarmContact(GameTestHelper helper,
                                            SettlerEntity worker, BlockPos crop,
                                            BlockPos alsoVisible) {
        var level = helper.getLevel();
        for (int dy : new int[] {1, 0, 2, -1, -2}) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos feet = crop.offset(dx, dy, dz);
                    if (feet.distSqr(crop) > 6.5D || feet.distSqr(alsoVisible) > 6.5D
                        || !com.hearthstead.settlement.workzone.WorkZoneService.livePositionAvailable(level, feet)
                        || !com.hearthstead.settlement.workzone.WorkZoneService.livePositionAvailable(level, feet.above())
                        || !com.hearthstead.settlement.workzone.WorkZoneService.livePositionAvailable(level, feet.below())
                        || !level.getBlockState(feet.below()).isFaceSturdy(level,
                            feet.below(), net.minecraft.core.Direction.UP)) {
                        continue;
                    }
                    var position = net.minecraft.world.phys.Vec3.atBottomCenterOf(feet);
                    if (!level.noCollision(worker, worker.getBoundingBox().move(
                            position.subtract(worker.position())))) continue;
                    worker.moveTo(position.x, position.y, position.z,
                        worker.getYRot(), worker.getXRot());
                    worker.getNavigation().stop();
                    if (FarmWorkApproach.canContact(level, worker, crop,
                            FarmWorkApproach.Contact.PLANT)
                        && FarmWorkApproach.canContact(level, worker, alsoVisible,
                            FarmWorkApproach.Contact.PLANT)) {
                        helper.assertTrue(worker.isAlive() && worker.level() == level
                                && level.noCollision(worker),
                            "fixture must occupy a clear supported live field stand");
                        return;
                    }
                }
            }
        }
        helper.fail("fixture has no loaded, supported, collision-free farm contact for "
            + crop + " and " + alsoVisible + "; lastFeet=" + worker.position());
    }

    /** Places the real worker at hand range before any field mutation seam. */
    private static void standAtWorkTarget(GameTestHelper helper,
                                          SettlerEntity worker,
                                          BlockPos target,
                                          int standingY,
                                          String label) {
        worker.moveTo(target.getX() + 1.5D, standingY,
            target.getZ() + 0.5D, worker.getYRot(), worker.getXRot());
        worker.getNavigation().stop();
        helper.assertTrue(worker.level() == helper.getLevel()
                && worker.isAlive()
                && worker.distanceToSqr(Vec3.atCenterOf(target)) <= 4.0D,
            "fixture: worker must have physical hand range at " + label);
    }

    /**
     * Uses the now-cleared source cell as the worker's real pickup contact
     * position. A living tree's surrounding leaves block the former fixed east
     * work-side ray, whereas this position is adjacent to the actual drop and
     * remains subject to the ordinary range and line-of-sight transaction.
     */
    private static void standAtClearedPickupContact(GameTestHelper helper,
                                                    SettlerEntity worker,
                                                    BlockPos clearedSource,
                                                    String label) {
        worker.moveTo(clearedSource.getX() + 0.5D, clearedSource.getY(),
            clearedSource.getZ() + 0.5D, worker.getYRot(), worker.getXRot());
        worker.getNavigation().stop();
        helper.assertTrue(helper.getLevel().getBlockState(clearedSource).isAir()
                && worker.level() == helper.getLevel() && worker.isAlive()
                && worker.distanceToSqr(Vec3.atCenterOf(clearedSource)) <= 4.0D,
            "fixture: worker must have an unobstructed physical pickup contact at "
                + label);
    }

    private static void standBeside(SettlerEntity settler,
                                    BlockPos container) {
        settler.setPos(container.getX() + 1.5D, container.getY(),
            container.getZ() + 0.5D);
        settler.getNavigation().stop();
    }

    private static int countIn(Container container, Item item) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(item)) {
                total += container.getItem(slot).getCount();
            }
        }
        return total;
    }

    private static Container storage(GameTestHelper helper,
                                     BlockPos relative) {
        var blockEntity = helper.getLevel().getBlockEntity(
            helper.absolutePos(relative));
        if (!(blockEntity instanceof Container container)) {
            throw new IllegalStateException("fixture storage is not loaded");
        }
        return container;
    }

    private static ItemStack removeAll(Container container, Item item) {
        ItemStack removed = ItemStack.EMPTY;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack found = container.getItem(slot);
            if (!found.is(item)) {
                continue;
            }
            if (removed.isEmpty()) {
                removed = found.copy();
            } else {
                removed.grow(found.getCount());
            }
            container.setItem(slot, ItemStack.EMPTY);
        }
        container.setChanged();
        return removed;
    }

    private static ItemStack insert(Container container, ItemStack offered) {
        ItemStack remaining = offered.copy();
        for (int slot = 0; slot < container.getContainerSize()
                && !remaining.isEmpty(); slot++) {
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty()) {
                container.setItem(slot, remaining.copy());
                remaining = ItemStack.EMPTY;
                break;
            }
            if (ItemStack.isSameItemSameComponents(existing, remaining)) {
                int moved = Math.min(remaining.getCount(),
                    existing.getMaxStackSize() - existing.getCount());
                if (moved > 0) {
                    existing.grow(moved);
                    remaining.shrink(moved);
                    container.setItem(slot, existing);
                }
            }
        }
        container.setChanged();
        return remaining;
    }

    private static int firstEmptySlot(Container container) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 64; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                helper.setBlock(new BlockPos(x, 1, z), Blocks.AIR);
                helper.setBlock(new BlockPos(x, 2, z), Blocks.AIR);
            }
        }
        // Arrival paths must stay inside this test's owned ground, never use a neighbor's arena.
        for (int edge = 0; edge < 64; edge++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(edge, y, 0), Blocks.STONE_BRICKS);
                helper.setBlock(new BlockPos(edge, y, 63), Blocks.STONE_BRICKS);
                helper.setBlock(new BlockPos(0, y, edge), Blocks.STONE_BRICKS);
                helper.setBlock(new BlockPos(63, y, edge), Blocks.STONE_BRICKS);
            }
        }
    }

    private static void put(HearthBlockEntity hearth, Item item, int amount) {
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            ItemStack existing = hearth.getInventory().getStackInSlot(slot);
            if (existing.isEmpty()) {
                hearth.getInventory().setStackInSlot(slot, new ItemStack(item, amount));
                return;
            }
            if (existing.is(item)
                && existing.getCount() + amount <= existing.getMaxStackSize()) {
                existing.grow(amount);
                hearth.getInventory().setStackInSlot(slot, existing);
                return;
            }
        }
        throw new IllegalStateException("fixture Hearth inventory full");
    }

    private static int count(HearthBlockEntity hearth, Item item) {
        int count = 0;
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            ItemStack stack = hearth.getInventory().getStackInSlot(slot);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static int count(List<UUID> ids, UUID target) {
        int count = 0;
        for (UUID id : ids) {
            if (target.equals(id)) {
                count++;
            }
        }
        return count;
    }

    private record PlaqueAndBuilding(PlaqueBlockEntity plaque, Building building,
                                     BlockPos storageRelative) {
    }

    private static final class Fixture {
        final Settlement settlement;
        final HearthBlockEntity hearth;
        Building house;
        Building secondHouse;
        final Building camp;
        final PlaqueBlockEntity plaque;
        final SettlerEntity spare;
        final SettlerEntity worker;
        final SettlerEntity farmer;
        final SettlerEntity courier;
        final ServerPlayer builder;
        final BlockPos storageRelative;
        SettlerEntity guard;
        SettlerEntity archer;

        Fixture(Settlement settlement, HearthBlockEntity hearth,
                Building house, Building secondHouse, Building camp,
                PlaqueBlockEntity plaque, SettlerEntity spare,
                SettlerEntity worker, SettlerEntity farmer,
                SettlerEntity courier, ServerPlayer builder,
                BlockPos storageRelative) {
            this.settlement = settlement;
            this.hearth = hearth;
            this.house = house;
            this.secondHouse = secondHouse;
            this.camp = camp;
            this.plaque = plaque;
            this.spare = spare;
            this.worker = worker;
            this.farmer = farmer;
            this.courier = courier;
            this.builder = builder;
            this.storageRelative = storageRelative;
        }
    }

    private record JourneyV2(Building farmhouse, Building warehouse,
                             Building tavern, Building barracks,
                             Building watchtower,
                             BlockPos watchtowerStorageRelative) {
    }

    private record Hire(ServerPlayer player) {
    }
}
