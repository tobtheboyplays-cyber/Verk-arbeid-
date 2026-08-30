package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.block.PlaqueBlock;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.block.PlaqueItemData;
import com.hearthstead.building.BuildingType;
import com.hearthstead.building.PlaqueState;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.item.JobEmblemItem;
import com.hearthstead.menu.HearthMenu;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.FoundingJourneyProgress;
import com.hearthstead.settlement.Mayor;
import com.hearthstead.settlement.PendingPlayerDeliveryLedger;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.RecruitmentTransaction;
import com.hearthstead.settlement.development.Development;
import com.hearthstead.settlement.development.DevelopmentNode;
import com.hearthstead.settlement.development.DevelopmentQuests;
import com.hearthstead.settlement.development.JobEmblemCatalog;
import com.hearthstead.settlement.equipment.EquipmentRequest;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.equipment.WorkplaceStorage;
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
import com.hearthstead.settlement.state.FirstRaidState;
import com.hearthstead.settlement.state.FoundingJourney;
import com.hearthstead.settlement.state.GuardOrder;
import com.hearthstead.settlement.state.RaidLifecycle;
import com.hearthstead.settlement.workzone.WorkZone;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.List;
import java.util.UUID;

/** Black-box release gate from founding through definitive first-raid close. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class FirstRaidReadinessGameTests {
    private static final BlockPos HEARTH = new BlockPos(7, 1, 13);
    private static final BlockPos HOUSE_ORIGIN = new BlockPos(1, 0, 1);
    private static final BlockPos SECOND_HOUSE_ORIGIN = new BlockPos(1, 0, 8);
    private static final BlockPos CAMP_ORIGIN = new BlockPos(9, 0, 1);
    private static final BlockPos FARM_ORIGIN = new BlockPos(0, 5, 1);
    private static final BlockPos WAREHOUSE_ORIGIN = new BlockPos(8, 5, 1);
    private static final BlockPos TAVERN_ORIGIN = new BlockPos(0, 10, 1);
    private static final BlockPos BARRACKS_ORIGIN = new BlockPos(8, 10, 1);
    private static final BlockPos WATCHTOWER_ORIGIN = new BlockPos(8, 10, 10);
    private static final BlockPos SECOND_WATCHTOWER_ORIGIN = new BlockPos(1, 10, 10);

    @GameTest(template = "empty16", timeoutTicks = 500,
        batch = "first_raid_readiness_real_journey")
    public void realAuthoritativeJourneyUnlocksOneExactFirstRaid(
            GameTestHelper helper) {
        Fixture f = foundedKnowledgeBuildingsAndMayor(helper, "Oakwatch");
        long rolledNotBefore = f.settlement.raidLifecycle.rolledNotBeforeNight();
        FirstRaidReadinessService.Report zeroDefenders =
            FirstRaidReadinessService.assessDomain(helper.getLevel(), f.settlement);
        helper.assertTrue(zeroDefenders.blockedBy(
                FirstRaidReadinessService.Blocker.GUARD_INVALID)
                && zeroDefenders.blockedBy(
                    FirstRaidReadinessService.Blocker.ARCHER_INVALID)
                && zeroDefenders.blockedBy(FirstRaidReadinessService.Blocker
                    .SETTLER_ROSTER_INSUFFICIENT),
            "zero defenders and three founders must fail the live first-raid gate");

        helper.assertTrue(!RaidDirector.queueFirstWarningIfDue(helper.getLevel(),
                f.settlement, rolledNotBefore)
                && f.settlement.raidLifecycle.queuedPlan().isEmpty(),
            "knowledge, buildings and a Mayor must not bypass emblem-backed production");
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
        ItemStack offered = new ItemStack(Items.OAK_LOG, 16);
        helper.assertTrue(!f.settlement.firstRaidReadiness.noteStoredProduction(
                helper.getLevel(), f.settlement, null, f.worker, offered, 1)
                && !f.settlement.firstRaidReadiness.noteStoredProduction(
                    helper.getLevel(), f.settlement, f.camp, f.mayor, offered, 1)
                && f.settlement.firstRaidReadiness.revision() == evidenceRevision,
            "the wrong camp or worker must not author stored-production proof");

        storePhysicalLog(helper, f, offered);
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
        helper.assertTrue(f.settlement.firstRaidReadiness.assess(helper.getLevel(), f.settlement)
                == FirstRaidReadiness.Assessment.READY,
            "only five housed settlers plus one ordered Guard and one ordered, "
                + "physically supplied Archer may become raid-ready");

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
        helper.assertTrue(archerOrder.issueTower(v2.watchtower.anchor,
                Direction.NORTH, GuardOrder.DEFAULT_FACING_ARC,
                f.builder.getUUID(), v2.watchtower.id,
                helper.getLevel().getGameTime())
                && JourneyServerHooks.noteGuardAssignmentCommitted(f.builder,
                    f.settlement, f.archer, archerOrder,
                    archerOrder.revision()),
            "restoring the exact persisted Tower Post must restore order authority");

        f.archer.assignProfession(Profession.GUARD);
        helper.assertTrue(f.settlement.firstRaidReadiness.assess(
                helper.getLevel(), f.settlement)
                == FirstRaidReadiness.Assessment.ARCHER_INVALID,
            "a hand-mutated live profession cannot borrow the Archer emblem proof");
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

        // Force the precise partial-spawn failure: the captain keeps a valid
        // direct/swept/fallback footing, while every possible random distance
        // on every follower bearing is made void for the first attempt. The
        // director must roll the captain back before it begins or seals the
        // lifecycle, then retry this exact warned plan after terrain repair.
        var plannedCaptain = RaidDirector.captainOf(f.settlement,
            warned.captainId());
        helper.assertTrue(plannedCaptain != null,
            "the warned plan must still resolve its exact captain");
        int plannedSize = RaidDirector.FIRST_RAID_BAND_SIZE;
        java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState>
            removedFollowerColumn = new java.util.LinkedHashMap<>();
        int lowY = Math.max(helper.getLevel().getMinBuildHeight(),
            f.settlement.center.getY() - RaidDirector.SPAWN_VERTICAL_SEARCH - 1);
        helper.assertTrue(RaidDirector.standableNear(helper.getLevel(),
                f.settlement.center) != null,
            "fixture must retain captain fallback footing at the live Hearth");
        for (int i = 1; i < plannedSize; i++) {
            float spread = (i / (float) (plannedSize - 1) - 0.5F)
                * 2.0F * RaidDirector.SPAWN_ARC;
            for (int distance = RaidDirector.SPAWN_MIN_DISTANCE;
                 distance <= RaidDirector.SPAWN_MAX_DISTANCE; distance++) {
                BlockPos follower = RaidDirector.formUpAt(f.settlement.center,
                    warned.approachDegrees() + spread, distance);
                for (int y = lowY; y <= f.settlement.center.getY(); y++) {
                    BlockPos cleared = new BlockPos(follower.getX(), y,
                        follower.getZ());
                    removedFollowerColumn.putIfAbsent(cleared,
                        helper.getLevel().getBlockState(cleared));
                    helper.getLevel().setBlock(cleared,
                        Blocks.AIR.defaultBlockState(), 3);
                }
                helper.assertTrue(RaidDirector.standableNear(helper.getLevel(),
                        follower) == null,
                    "fixture follower column unexpectedly retained footing at "
                        + follower);
            }
        }

        List<RaiderEntity> partial;
        try {
            partial = RaidDirector.startQueuedFirstRaid(helper.getLevel(),
                f.settlement, attackNight);
        } finally {
            for (var restored : removedFollowerColumn.entrySet()) {
                helper.getLevel().setBlock(restored.getKey(),
                    restored.getValue(), 3);
            }
        }
        helper.assertTrue(partial.isEmpty()
                && f.settlement.raidLifecycle.firstState()
                    == FirstRaidState.SCHEDULED
                && f.settlement.raidLifecycle.queuedPlan().orElseThrow()
                    .equals(warned)
                && f.settlement.raidLifecycle.participants().isEmpty()
                && f.settlement.pendingRaid == null
                && RaidDirector.livingRaidersOf(helper.getLevel(),
                    f.settlement).isEmpty(),
            "captain-only footing must leave no entity, activation or sealed UUID "
                + "and retain the exact five-actor plan for retry");

        List<RaiderEntity> band = RaidDirector.startQueuedFirstRaid(helper.getLevel(),
            f.settlement, attackNight);
        String committedLeaderName = RaidDirector.leaderNameOf(f.settlement,
            warned.captainId()).orElseThrow();
        int captains = 0;
        int bruteFollowers = 0;
        int skirmisherFollowers = 0;
        for (RaiderEntity raider : band) {
            if (raider.isCaptain()) {
                captains++;
                helper.assertTrue(raider.isCustomNameVisible()
                        && raider.getCustomName() != null
                        && committedLeaderName.equals(
                            raider.getCustomName().getString()),
                    "the neutral captain slot must carry the exact warned name");
            } else if (raider.variant() == RaiderEntity.Variant.BRUTE) {
                bruteFollowers++;
            } else if (raider.variant()
                    == RaiderEntity.Variant.SKIRMISHER) {
                skirmisherFollowers++;
            }
        }
        helper.assertTrue(band.size() == RaidDirector.FIRST_RAID_BAND_SIZE
                && band.get(0).isCaptain()
                && captains == 1 && bruteFollowers == 1
                && skirmisherFollowers == 3
                && band.stream().allMatch(raider -> warned.captainId()
                    .equals(raider.captainId())
                    && warned.objective() == raider.objective())
                && f.settlement.raidLifecycle.firstState() == FirstRaidState.ACTIVE
                && f.settlement.raidLifecycle.activePlan().orElseThrow().equals(warned),
            "restoring follower footing must retry the same warned plan and seal "
                + "one named captain, one BRUTE and three SKIRMISHER followers");
        helper.assertTrue(f.settlement.raidLifecycle.participantsTracked()
                && f.settlement.raidLifecycle.participants().size()
                    == RaidDirector.FIRST_RAID_BAND_SIZE
                && band.stream().allMatch(raider -> f.settlement.raidLifecycle
                    .participants().contains(raider.getUUID())),
            "the sealed lifecycle ledger must match all five accepted actors exactly");
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

    @GameTest(template = "empty16", timeoutTicks = 500,
        batch = "first_raid_readiness_restart_half_commit_recovery")
    public void persistedScheduledCalendarRecoversMissingJourneyReceipt(
            GameTestHelper helper) {
        Fixture f = foundedKnowledgeBuildingsAndMayor(helper, "Ashenwatch");
        purchaseAndHireDirectly(helper, f);
        commitLumberZoneForJourney(helper, f);
        equipPhysical(helper, f.camp, f.worker, Items.IRON_AXE);

        ItemStack logs = new ItemStack(Items.OAK_LOG, 16);
        storePhysicalLog(helper, f, logs);
        helper.assertTrue(FoundingJourneyProgress.noteLogStored(
                helper.getLevel(), f.settlement, f.camp, f.worker,
                logs, logs.getCount())
                && DevelopmentQuests.noteLumberLogsStored(helper.getLevel(),
                    f.settlement, f.camp, f.worker, logs, logs.getCount()),
            "fixture: the real camp output must close the founding lumber rung");
        JourneyV2 v2 = completeJourneyV2(helper, f);
        helper.assertTrue(FirstRaidReadinessService.assessDomain(
                helper.getLevel(), f.settlement).ready(),
            "fixture: every live declaration fact must be ready before scheduling");

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
        helper.assertTrue(reloaded.raidLifecycle.firstState()
                == FirstRaidState.SCHEDULED
                && !reloaded.journeyState.isCompleted(
                    JourneyIds.FJ_560_DECLARE_RAID_READY)
                && FirstRaidReadinessService.assessScheduledCommitBridge(
                    helper.getLevel(), reloaded).ready(),
            "save/reload must preserve the narrow half-commit without inventing its receipt");

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

    @GameTest(template = "empty16", timeoutTicks = 350,
        batch = "first_raid_readiness_raw_hire_cannot_bypass")
    public void rawAdminHireAndRealStorageCannotForgeReadiness(
            GameTestHelper helper) {
        Fixture f = foundedKnowledgeBuildingsAndMayor(helper, "Rawford");
        helper.assertTrue(Employment.hire(helper.getLevel(), f.settlement,
                f.camp, f.worker).ok(),
            "fixture: the permission-level-2 seam should still construct employment");
        helper.assertTrue(f.settlement.firstRaidReadiness.stage()
                == FirstRaidReadiness.Stage.PENDING,
            "the raw admin/GameTest seam must never record a consumed emblem");

        ItemStack offered = new ItemStack(Items.OAK_LOG);
        storePhysicalLog(helper, f, offered);
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

    @GameTest(template = "empty16", timeoutTicks = 350,
        batch = "first_raid_readiness_hearth_log_cannot_bypass")
    public void legacyHearthLogCannotForgeStoredProduction(
            GameTestHelper helper) {
        Fixture f = foundedKnowledgeBuildingsAndMayor(helper, "Oldroute");
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

    @GameTest(template = "empty16", timeoutTicks = 100,
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

    private static Fixture foundedKnowledgeBuildingsAndMayor(GameTestHelper helper,
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
            "fixture: real founding must atomically create three settlers");
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
        helper.assertTrue(members.size() == 3,
            "real founding must expose exactly three live founder members");
        SettlerEntity mayor = members.get(0);
        SettlerEntity worker = members.get(1);
        SettlerEntity farmer = members.get(2);
        helper.assertTrue(Mayor.appoint(helper.getLevel(), settlement, mayor) == null
                && settlement.mayorId.equals(mayor.getUUID()),
            "the first real Mayor appointment must fill the live seat");

        SettlerEntity courier = mayor;
        helper.assertTrue(settlement.population() == 3
                && settlement.validBedCount() == 0,
            "fixture: three founders start on the Hearth's base capacity; "
                + "Home knowledge must author all later physical beds");

        // First Fire is measured from the live Hearth, bound Mayor and three
        // founded residents. Housing is deliberately authored later by HOME;
        // no client-reported quest progress is involved.
        unlock(helper, hearth, settlement, DevelopmentNode.SHELTER);
        unlock(helper, hearth, settlement, DevelopmentNode.TIMBER_RIGHTS);
        PlaqueAndBuilding camp = buildCamp(helper, settlement, builder);
        helper.assertTrue(settlement.foundingJourney.phase()
                == FoundingJourney.Phase.HIRE_LUMBERER,
            "the real valid Lumber Camp plaque must advance the Journey once");
        return new Fixture(settlement, hearth, null,
            null, camp.building, camp.plaque, mayor, worker,
            farmer, courier, builder, camp.storageRelative);
    }

    /** Completes every server-authored pre-raid rung after the founding log. */
    private static JourneyV2 completeJourneyV2(GameTestHelper helper, Fixture f) {
        unlock(helper, f.hearth, f.settlement, DevelopmentNode.STORES_AND_ROADS);

        PlaqueAndBuilding warehouse = buildWarehouse(helper, f.settlement, f.builder);
        purchaseAndHire(helper, f, Profession.COURIER, f.courier, warehouse.building);
        ItemStack delivery = new ItemStack(Items.OAK_LOG);
        helper.assertTrue(WorkplaceStorage.insert(helper.getLevel(), warehouse.building,
                delivery.copy()).isEmpty()
                && DevelopmentQuests.noteCourierDelivery(helper.getLevel(),
                    f.settlement, f.courier, f.camp, warehouse.building, 1),
            "fixture: one physical Camp-to-Warehouse route must author Cultivated Ground");
        unlock(helper, f.hearth, f.settlement, DevelopmentNode.CULTIVATED_GROUND);

        PlaqueAndBuilding farm = buildFarmhouse(helper, f.settlement, f.builder);
        purchaseAndHire(helper, f, Profession.FARMER, f.farmer, farm.building);
        equipPhysical(helper, farm.building, f.farmer, Items.IRON_HOE);

        ItemStack crops = new ItemStack(Items.WHEAT, 24);
        helper.assertTrue(WorkplaceStorage.insert(helper.getLevel(), farm.building,
                crops.copy()).isEmpty()
                && DevelopmentQuests.noteFarmCropsStored(helper.getLevel(),
                    f.settlement, farm.building, f.farmer, crops, crops.getCount()),
            "fixture: physical Farmer crops must author Home progress");
        unlock(helper, f.hearth, f.settlement, DevelopmentNode.HOME);

        f.house = buildHouse(helper, f.settlement, f.builder,
            HOUSE_ORIGIN, 4).building;
        f.secondHouse = buildHouse(helper, f.settlement, f.builder,
            SECOND_HOUSE_ORIGIN, 1).building;
        helper.assertTrue(f.settlement.validBedCount() == 5,
            "fixture: learned Home plans must survey exactly five physical bed heads");
        unlock(helper, f.hearth, f.settlement, DevelopmentNode.HOSPITALITY);
        PlaqueAndBuilding tavern = buildTavern(helper, f.settlement, f.builder);

        fundTwoNaturalAdmissions(f);
        f.guard = recruitNaturally(helper, f, tavern.building, 4);
        unlock(helper, f.hearth, f.settlement, DevelopmentNode.FIRST_WATCH);

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
        helper.assertTrue(f.settlement.firstRaidReadiness.assess(
                helper.getLevel(), f.settlement)
                == FirstRaidReadiness.Assessment.GUARD_UNARMED,
            "a delivered sword must remain storage, not equipped readiness");
        equipPhysicalFromStorage(helper, barracks.building, f.guard);
        issueDefenderOrder(helper, f, f.guard, barracks.building, false);
        unlock(helper, f.hearth, f.settlement, DevelopmentNode.ARM_THE_WATCH);
        helper.assertTrue(JourneyServerHooks.noteBuildingLinked(helper.getLevel(),
                f.settlement, f.secondHouse)
                && f.settlement.journeyState.isCompleted(
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
                && guardOnly.blockedBy(FirstRaidReadinessService.Blocker
                    .SETTLER_ROSTER_INSUFFICIENT),
            "one valid Guard is still blocked until a distinct fifth resident "
                + "becomes a fully equipped Watchtower Archer");

        f.archer = recruitNaturally(helper, f, tavern.building, 5);
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

        helper.assertTrue(f.settlement.population() == 5
                && f.settlement.validBedCount() == 5
                && !f.guard.getUUID().equals(f.archer.getUUID())
                && f.settlement.record(f.guard.getUUID()) != null
                && f.settlement.record(f.archer.getUUID()) != null,
            "the pre-raid roster must contain five unique members and two "
                + "distinct, naturally admitted defenders in five physical beds");
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

        int admissionRevision = f.settlement.recruitment.revision();
        helper.assertTrue(SettlementManager.admitWaitingTraveler(f.builder,
                f.settlement, travelerId, admissionRevision)
                == SettlementManager.AdmissionResult.COMMITTED,
            "the player must explicitly pay and admit the exact waiting traveler");
        RecruitmentTransaction admitted = f.settlement.recruitment;
        helper.assertTrue(admitted.status()
                    == RecruitmentTransaction.Status.ADMITTED
                && admitted.admissionReceipt() != null
                && admitted.admissionReceipt().removedItemCount() == 12
                && f.settlement.population() == expectedPopulation
                && f.settlement.record(travelerId) != null
                && !traveler.isTraveler()
                && count(f.hearth, Items.BREAD) == breadBefore - 4
                && count(f.hearth, Items.OAK_PLANKS) == planksBefore - 8,
            "one admission must consume 4 bread + 8 planks and add the same "
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
        return traveler;
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
        boolean authored = towerPost
            ? order.issueTower(employer.anchor, Direction.NORTH,
                GuardOrder.DEFAULT_FACING_ARC, f.builder.getUUID(),
                employer.id, now)
            : order.issueStand(defender.blockPosition(), Direction.NORTH,
                GuardOrder.DEFAULT_LEASH_RADIUS, f.builder.getUUID(),
                employer.id, now);
        helper.assertTrue(authored
                && JourneyServerHooks.noteGuardAssignmentCommitted(f.builder,
                    f.settlement, defender, order, order.revision()),
            "the exact equipped " + defender.getProfession().key()
                + " must receive one persisted profession-valid order");
        SettlementSavedData.get(helper.getLevel()).setDirty();
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
            "fixture: paid emblem must transfer from its durable outbox into the empty hand");
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
        ItemStack supplied = WorkplaceStorage.extractOne(helper.getLevel(), workplace,
            requirement);
        helper.assertTrue(!supplied.isEmpty(),
            "fixture: the same physical tool must leave workplace storage");
        ItemStack displaced = worker.getMainHandItem();
        if (!displaced.isEmpty()) {
            helper.assertTrue(worker.bag.addItem(displaced.copy()).isEmpty(),
                "fixture: displaced worn tool must remain conserved in the bag");
        }
        worker.setItemSlot(EquipmentSlot.MAINHAND, supplied);
        EquipmentRequests.refreshFor(helper.getLevel(), fSettlement(worker),
            workplace, worker);
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
        helper.assertTrue(entry != null, "fixture: Lumberer must be in the Mayor catalog");
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
        Development.EmblemPurchase purchase = Development.purchaseEmblem(helper.getLevel(),
            f.settlement, f.hearth, Profession.LUMBERER,
            Development.revisionOf(helper.getLevel(), f.settlement), player);
        helper.assertTrue(purchase.applied(),
            "fixture: live Mayor, knowledge and exact Hearth price must issue one emblem");
        PendingPlayerDeliveryLedger.DeliveryResult delivery =
            Development.deliverPending(helper.getLevel(), f.settlement, player,
                purchase.deliveryId());
        helper.assertTrue(delivery.outcome()
                == PendingPlayerDeliveryLedger.Outcome.MAIN_HAND,
            "fixture: paid emblem must transfer from its durable outbox into the empty hand");
        ItemStack emblem = player.getMainHandItem();
        ((JobEmblemItem) emblem.getItem()).interactLivingEntity(emblem,
            player, f.worker, InteractionHand.MAIN_HAND);
        helper.assertTrue(f.camp.workers.contains(f.worker.getUUID())
                && f.worker.getProfession() == Profession.LUMBERER,
            "giving the physical emblem to the settler must auto-select the camp");
        return new Hire(player);
    }

    private static void commitLumberZoneForJourney(GameTestHelper helper,
                                                   Fixture fixture) {
        WorkZone zone = WorkZone.between(fixture.settlement.id,
            fixture.camp.id, WorkZone.Type.LUMBER,
            helper.getLevel().dimension().location(),
            helper.absolutePos(new BlockPos(0, 0, 0)),
            helper.absolutePos(new BlockPos(15, 15, 15)),
            fixture.camp.workZoneRevision() + 1);
        helper.assertTrue(fixture.camp.commitWorkZone(
                fixture.camp.workZoneRevision(), zone)
                && FoundingJourneyProgress.noteWorkZoneCommitted(
                    helper.getLevel(), fixture.settlement, fixture.camp, zone)
                && fixture.settlement.foundingJourney.phase()
                    == FoundingJourney.Phase.DELIVER_FIRST_LOG,
            "fixture: an exact committed Lumber zone must open first-log delivery");
    }

    private static void unlock(GameTestHelper helper, HearthBlockEntity hearth,
                               Settlement settlement, DevelopmentNode node) {
        if (Development.of(helper.getLevel(), settlement).unlocked(node)) {
            return;
        }
        for (DevelopmentNode.Cost cost : node.costs()) {
            put(hearth, cost.item(), cost.count());
        }
        Development.Result result = Development.purchaseNode(helper.getLevel(), settlement,
            hearth, node, Development.revisionOf(helper.getLevel(), settlement));
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
        Building building = plaque.building(helper.getLevel());
        helper.assertTrue(building != null && building.valid
                && settlement.buildings.contains(building),
            "fixture: " + type.id() + " plaque must resolve its exact saved building");
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

    private static void storePhysicalLog(GameTestHelper helper, Fixture f,
                                         ItemStack offered) {
        helper.assertTrue(helper.getLevel().getBlockEntity(
                helper.absolutePos(f.storageRelative)) instanceof Container,
            "fixture: Lumber Camp storage must be a live physical container");
        ItemStack remainder = WorkplaceStorage.insert(helper.getLevel(), f.camp,
            offered.copy());
        helper.assertTrue(remainder.isEmpty(),
            "fixture: the real worker storage route must insert the offered log");
        Container storage = (Container) helper.getLevel().getBlockEntity(
            helper.absolutePos(f.storageRelative));
        helper.assertTrue(countIn(storage, Items.OAK_LOG) == offered.getCount(),
            "fixture: log must exist in workplace storage before recording the event");
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
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
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
        final SettlerEntity mayor;
        final SettlerEntity worker;
        final SettlerEntity farmer;
        final SettlerEntity courier;
        final ServerPlayer builder;
        final BlockPos storageRelative;
        SettlerEntity guard;
        SettlerEntity archer;

        Fixture(Settlement settlement, HearthBlockEntity hearth,
                Building house, Building secondHouse, Building camp,
                PlaqueBlockEntity plaque, SettlerEntity mayor,
                SettlerEntity worker, SettlerEntity farmer,
                SettlerEntity courier, ServerPlayer builder,
                BlockPos storageRelative) {
            this.settlement = settlement;
            this.hearth = hearth;
            this.house = house;
            this.secondHouse = secondHouse;
            this.camp = camp;
            this.plaque = plaque;
            this.mayor = mayor;
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
