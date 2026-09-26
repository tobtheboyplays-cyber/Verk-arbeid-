package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.GuardOrderGoal;
import com.hearthstead.entity.ai.HearthApproach;
import com.hearthstead.entity.path.RoadNavigation;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.BuildingManager;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.guard.GuardAssignmentService;
import com.hearthstead.settlement.raid.RaidPlan;
import com.hearthstead.settlement.raid.RaidThreatBoard;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.state.RaidLifecycle;
import com.hearthstead.settlement.state.GuardOrder;
import com.hearthstead.settlement.state.GuardOrderBook;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.pathfinder.Path;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.EnumSet;
import java.util.UUID;

/** Physical equipment and explicit-order regressions for the guard loop. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class GuardControlGameTests {

    private static Settlement arena(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y < 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        Settlement settlement = new Settlement(UUID.randomUUID(), "Orderholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 7;
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        return settlement;
    }

    private static SettlerEntity settler(GameTestHelper helper,
                                         Settlement settlement,
                                         BlockPos pos) {
        SettlerEntity guard = helper.spawn(ModEntities.SETTLER.get(), pos);
        guard.setSettlerName("Ward");
        guard.bindTo(settlement.id, settlement.center);
        settlement.putRecord(guard.getUUID(), guard.getSettlerName(),
            Profession.NONE);
        return guard;
    }

    @GameTest(template = "empty16", batch = "guard_control",
        timeoutTicks = 500)
    public void rallyOrderMovesAnArmedGuardAndHoldsTheExactArea(
            GameTestHelper helper) {
        Settlement settlement = arena(helper);
        Building barracks = GameTestFixtures.register(helper, settlement,
            BuildingType.BARRACKS, 2, 2);
        SettlerEntity guard = settler(helper, settlement,
            new BlockPos(2, 1, 2));
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement,
            barracks, guard).ok(), "fixture: barracks hire");
        // QA-ready health isolates this authored order/path; natural preparation has its own food test.
        guard.setHealth(guard.getMaxHealth());
        guard.setItemSlot(EquipmentSlot.MAINHAND,
            new ItemStack(Items.IRON_SWORD));
        BlockPos rally = helper.absolutePos(new BlockPos(12, 1, 12));
        helper.assertTrue(order(helper, settlement, guard).issueStand(rally,
                Direction.NORTH, GuardOrder.DEFAULT_LEASH_RADIUS,
                UUID.randomUUID(), barracks.id, helper.getLevel().getGameTime()),
            "fixture: rally order must be accepted");

        helper.succeedWhen(() -> helper.assertTrue(
            guard.blockPosition().distSqr(rally) <= 6.25D,
            "an active rally order must be consumed by guard AI; guard at "
                + guard.blockPosition() + ", rally=" + rally));
    }

    @GameTest(template = "empty16", batch = "guard_control_route_order",
        timeoutTicks = 900)
    public void patrolRouteVisitsEveryPointInTheNumberedOrder(
            GameTestHelper helper) {
        Settlement settlement = arena(helper);
        Building barracks = GameTestFixtures.register(helper, settlement,
            BuildingType.BARRACKS, 2, 2);
        SettlerEntity guard = settler(helper, settlement,
            new BlockPos(2, 1, 2));
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement,
            barracks, guard).ok(), "fixture: barracks hire");
        // QA-ready health isolates this authored order/path; natural preparation has its own food test.
        guard.setHealth(guard.getMaxHealth());
        guard.setItemSlot(EquipmentSlot.MAINHAND,
            new ItemStack(Items.IRON_SWORD));
        // Both authored points must be legal settlement targets.  The old
        // (4,2)/(13,13) pair sat just outside this fixture's radius-seven
        // authority and therefore tested rejection/fallback rather than the
        // numbered patrol journey it claimed to exercise.
        BlockPos first = helper.absolutePos(new BlockPos(5, 1, 3));
        BlockPos second = helper.absolutePos(new BlockPos(12, 1, 12));
        GuardOrder order = order(helper, settlement, guard);
        UUID issuer = UUID.randomUUID();
        helper.assertTrue(order.appendPatrolPoint(first, issuer, barracks.id,
                    helper.getLevel().getGameTime())
                && order.appendPatrolPoint(second, issuer, barracks.id,
                    helper.getLevel().getGameTime())
                && order.issuePatrol(GuardOrder.Traversal.LOOP, issuer,
                    barracks.id, helper.getLevel().getGameTime()),
            "fixture: two unique points must form an active patrol route");

        boolean[] reachedFirst = {false};
        boolean[] reachedSecond = {false};
        boolean[] wrongOrder = {false};
        helper.succeedWhen(() -> {
            boolean atFirst = guard.blockPosition().distSqr(first) <= 2.25D;
            boolean atSecond = guard.blockPosition().distSqr(second) <= 2.25D;
            if (!reachedFirst[0] && atSecond) {
                wrongOrder[0] = true;
            }
            if (atFirst) {
                reachedFirst[0] = true;
            }
            if (reachedFirst[0] && atSecond) {
                reachedSecond[0] = true;
            }
            helper.assertTrue(!wrongOrder[0] && reachedFirst[0]
                    && reachedSecond[0],
                "the guard must physically visit point 1 before point 2; at="
                    + guard.blockPosition() + " first=" + reachedFirst[0]
                    + " second=" + reachedSecond[0] + " routeFailure="
                    + guard.routeFailureNote());
        });
    }

    @GameTest(template = "empty16", batch = "guard_control_unreachable_route",
        timeoutTicks = 700)
    public void unreachablePatrolPointReportsFailureWithoutFakeArrival(
            GameTestHelper helper) {
        Settlement settlement = arena(helper);
        Building barracks = GameTestFixtures.register(helper, settlement,
            BuildingType.BARRACKS, 2, 2);
        SettlerEntity guard = settler(helper, settlement,
            new BlockPos(2, 1, 2));
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement,
            barracks, guard).ok(), "fixture: barracks hire");
        // QA-ready health isolates this authored order/path; natural preparation has its own food test.
        guard.setHealth(guard.getMaxHealth());
        guard.setItemSlot(EquipmentSlot.MAINHAND,
            new ItemStack(Items.IRON_SWORD));
        // Keep both points inside the settlement's authority; only the stone
        // enclosure below is allowed to make the second point unreachable.
        BlockPos first = helper.absolutePos(new BlockPos(5, 1, 3));
        BlockPos sealed = helper.absolutePos(new BlockPos(12, 1, 12));
        for (int y = 1; y <= 3; y++) {
            for (int x = 11; x <= 13; x++) {
                helper.setBlock(new BlockPos(x, y, 11), Blocks.STONE_BRICKS);
                helper.setBlock(new BlockPos(x, y, 13), Blocks.STONE_BRICKS);
            }
            for (int z = 11; z <= 13; z++) {
                helper.setBlock(new BlockPos(11, y, z), Blocks.STONE_BRICKS);
                helper.setBlock(new BlockPos(13, y, z), Blocks.STONE_BRICKS);
            }
        }
        GuardOrder order = order(helper, settlement, guard);
        UUID issuer = UUID.randomUUID();
        helper.assertTrue(order.appendPatrolPoint(first, issuer, barracks.id,
                    helper.getLevel().getGameTime())
                && order.appendPatrolPoint(sealed, issuer, barracks.id,
                    helper.getLevel().getGameTime())
                && order.issuePatrol(GuardOrder.Traversal.LOOP, issuer,
                    barracks.id, helper.getLevel().getGameTime()),
            "fixture: the route state may name a point that later proves unreachable");

        helper.succeedWhen(() -> helper.assertTrue(
            guard.routeFailureNote().startsWith("guard_order:no_path@")
                && guard.blockPosition().distSqr(sealed) > 2.25D
                && order.mode() == GuardOrder.Mode.PATROL_ROUTE,
            "an unreachable point must report no_path, never count as arrival "
                + "or erase the player's order; guard=" + guard.blockPosition()
                + " trace=" + guard.routeFailureNote()));
    }

    @GameTest(template = "empty16", batch = "guard_control_unarmed_order",
        timeoutTicks = 300)
    public void unarmedGuardKeepsOrderButCannotPretendToExecuteIt(
            GameTestHelper helper) {
        Settlement settlement = arena(helper);
        Building barracks = GameTestFixtures.register(helper, settlement,
            BuildingType.BARRACKS, 2, 2);
        SettlerEntity guard = settler(helper, settlement,
            new BlockPos(3, 1, 3));
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement,
                barracks, guard).ok(),
            "fixture: the valid Barracks must hire the guard");
        EquipmentRequests.refreshFor(helper.getLevel(), guard);
        BlockPos hold = helper.absolutePos(new BlockPos(12, 1, 12));
        GuardOrder order = order(helper, settlement, guard);
        helper.assertTrue(order.issueStand(hold, Direction.NORTH,
                GuardOrder.DEFAULT_LEASH_RADIUS, UUID.randomUUID(), barracks.id,
                helper.getLevel().getGameTime()),
            "the player may prepare an order while equipment is still requested");
        helper.assertTrue(!new GuardOrderGoal(guard).canUse(),
            "missing physical equipment must make explicit-order AI ineligible");

        BlockPos start = guard.blockPosition();
        helper.runAfterDelay(80, () -> {
            helper.assertTrue(guard.getMainHandItem().isEmpty()
                    && guard.blockPosition().distSqr(hold) > 6.25D
                    && order.mode() == GuardOrder.Mode.STAND_POST,
                "the unarmed guard must not fake execution or discard the "
                    + "waiting order; start=" + start + " now="
                    + guard.blockPosition());
            helper.succeed();
        });
    }

    @GameTest(template = "empty16", batch = "guard_control",
        timeoutTicks = 900)
    public void unarmedGuardFetchesTheRealBarracksSwordBeforeTargeting(
            GameTestHelper helper) {
        Settlement settlement = arena(helper);
        Building barracks = GameTestFixtures.register(helper, settlement,
            BuildingType.BARRACKS, 2, 2);
        BlockPos rackRel = new BlockPos(3, 1, 3);
        helper.setBlock(rackRel, Blocks.CHEST);
        Container rack = (Container) helper.getLevel().getBlockEntity(
            helper.absolutePos(rackRel));
        rack.setItem(0, new ItemStack(Items.IRON_SWORD));

        SettlerEntity guard = settler(helper, settlement,
            new BlockPos(12, 1, 12));
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement,
                barracks, guard).ok(),
            "fixture: barracks must hire the guard");
        // QA-ready health; the real distant sword still must be fetched physically.
        guard.setHealth(guard.getMaxHealth());
        helper.assertTrue(!EquipmentRequests.equipFromWorkplace(
                helper.getLevel(), settlement, guard),
            "a sword may not teleport from a distant rack into the hand");
        helper.assertTrue(rack.getItem(0).is(Items.IRON_SWORD)
                && guard.getMainHandItem().isEmpty(),
            "failed distant transfer must conserve the physical sword");

        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(),
            new BlockPos(6, 1, 6));
        raider.assign(UUID.randomUUID(), settlement.id, RaidObjective.BLOD,
            1.0F, false);
        raider.setNoAi(true);

        GameTestTicks.at(helper, 40L, () -> helper.assertTrue(
            guard.getTarget() == null,
            "an unarmed guard must not acquire a target and deadlock equipment pickup"));
        helper.succeedWhen(() -> {
            helper.assertTrue(guard.getMainHandItem().is(Items.IRON_SWORD),
                "guard must walk to the barracks rack and take its real sword");
            helper.assertTrue(rack.getItem(0).isEmpty(),
                "the sword must exist in exactly one place after pickup");
            helper.assertTrue(guard.getTarget() == raider,
                "only the now-armed guard may acquire the waiting raider");
        });
    }

    @GameTest(template = "empty16", batch = "guard_control",
        timeoutTicks = 500)
    public void wornSwordSwapsBackIntoTheRackWithoutDuplication(
            GameTestHelper helper) {
        Settlement settlement = arena(helper);
        Building barracks = GameTestFixtures.register(helper, settlement,
            BuildingType.BARRACKS, 2, 2);
        BlockPos rackRel = new BlockPos(3, 1, 3);
        helper.setBlock(rackRel, Blocks.CHEST);
        Container rack = (Container) helper.getLevel().getBlockEntity(
            helper.absolutePos(rackRel));
        rack.setItem(0, new ItemStack(Items.IRON_SWORD));

        SettlerEntity guard = settler(helper, settlement,
            new BlockPos(4, 1, 4));
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement,
                barracks, guard).ok(),
            "fixture: barracks must hire the guard");
        // QA-ready health isolates physical replacement and exact rack conservation.
        guard.setHealth(guard.getMaxHealth());
        ItemStack worn = new ItemStack(Items.IRON_SWORD);
        worn.setDamageValue(worn.getMaxDamage() - 1);
        guard.setItemSlot(EquipmentSlot.MAINHAND, worn);
        EquipmentRequests.refreshFor(helper.getLevel(), guard);

        helper.succeedWhen(() -> {
            ItemStack held = guard.getMainHandItem();
            ItemStack returned = rack.getItem(0);
            helper.assertTrue(held.is(Items.IRON_SWORD)
                    && held.getDamageValue() == 0,
                "the serviceable replacement must reach the guard's hand");
            helper.assertTrue(returned.is(Items.IRON_SWORD)
                    && returned.getDamageValue() == worn.getDamageValue(),
                "the worn sword must occupy the exact rack slot it replaced");
            helper.assertTrue(held.getCount() + returned.getCount() == 2,
                "the swap must conserve exactly two swords");
        });
    }

    @GameTest(template = "empty16",
        batch = "guard_readiness_read_only_equipment_state",
        timeoutTicks = 200)
    public void readinessQueryNeverRefreshesOrMutatesEquipmentRequests(
            GameTestHelper helper) {
        Settlement settlement = arena(helper);
        Building barracks = GameTestFixtures.register(helper, settlement,
            BuildingType.BARRACKS, 2, 2);
        SettlerEntity guard = settler(helper, settlement,
            new BlockPos(5, 1, 5));
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement,
                barracks, guard).ok(),
            "fixture: barracks must hire the exact guard");
        GuardOrder order = order(helper, settlement, guard);
        helper.assertTrue(order.issueStand(guard.blockPosition(), Direction.NORTH,
                GuardOrder.DEFAULT_LEASH_RADIUS, UUID.randomUUID(), barracks.id,
                helper.getLevel().getGameTime()),
            "fixture: exact Stand Post must commit");
        guard.setItemSlot(EquipmentSlot.MAINHAND,
            new ItemStack(Items.IRON_SWORD));

        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        CompoundTag queueBeforeArmed = settlement.equipmentRequestQueue
            .writeNbt().copy();
        int rowsBeforeArmed = barracks.equipmentRequests.size();
        data.setDirty(false);
        helper.assertTrue(GuardAssignmentService.hasValidReadyOrder(
                helper.getLevel(), settlement, guard),
            "an exact armed Guard with its own Stand Post must be ready");
        helper.assertTrue(!data.isDirty()
                && queueBeforeArmed.equals(
                    settlement.equipmentRequestQueue.writeNbt())
                && rowsBeforeArmed == barracks.equipmentRequests.size(),
            "armed readiness reads must not cancel rows, revise the queue, or dirty data");

        guard.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        CompoundTag queueBeforeUnarmed = settlement.equipmentRequestQueue
            .writeNbt().copy();
        int rowsBeforeUnarmed = barracks.equipmentRequests.size();
        data.setDirty(false);
        helper.assertTrue(!GuardAssignmentService.hasValidReadyOrder(
                helper.getLevel(), settlement, guard),
            "the same exact order must fail read-only readiness when unarmed");
        helper.assertTrue(!data.isDirty()
                && queueBeforeUnarmed.equals(
                    settlement.equipmentRequestQueue.writeNbt())
                && rowsBeforeUnarmed == barracks.equipmentRequests.size(),
            "unarmed readiness reads must not create rows, revise the queue, or dirty data");
        helper.succeed();
    }

    @GameTest(template = "empty16",
        batch = "guard_readiness_duplicate_record_fail_closed",
        timeoutTicks = 200)
    public void duplicateSettlerRecordCannotBecomeGuardAuthority(
            GameTestHelper helper) {
        Settlement settlement = arena(helper);
        Building barracks = GameTestFixtures.register(helper, settlement,
            BuildingType.BARRACKS, 2, 2);
        SettlerEntity guard = settler(helper, settlement,
            new BlockPos(5, 1, 5));
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement,
                barracks, guard).ok(), "fixture: barracks hire");
        GuardOrder order = order(helper, settlement, guard);
        helper.assertTrue(order.issueStand(guard.blockPosition(), Direction.NORTH,
            8, UUID.randomUUID(), barracks.id,
            helper.getLevel().getGameTime()), "fixture: stand order");
        guard.setItemSlot(EquipmentSlot.MAINHAND,
            new ItemStack(Items.IRON_SWORD));
        settlement.settlers.add(new Settlement.SettlerRecord(guard.getUUID(),
            "Forged duplicate", Profession.GUARD));
        int revision = order.revision();
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.setDirty(false);

        helper.assertTrue(!GuardAssignmentService.hasValidReadyOrder(
                helper.getLevel(), settlement, guard)
                && order.revision() == revision && !data.isDirty(),
            "duplicate persisted Guard identities must fail closed without mutation");
        helper.succeed();
    }

    @GameTest(template = "empty16",
        batch = "guard_readiness_null_record_fail_closed",
        timeoutTicks = 200)
    public void nullSettlerRecordCannotBecomeGuardAuthority(
            GameTestHelper helper) {
        ReadyFixture fixture = readyFixture(helper);
        fixture.settlement().settlers.add(null);
        int revision = fixture.order().revision();
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.setDirty(false);

        GuardAssignmentService.Validation validation =
            GuardAssignmentService.validate(helper.getLevel(),
                fixture.settlement(), fixture.guard(), false);
        fixture.settlement().settlers.remove(null);
        helper.assertTrue(
            validation.reason()
                == GuardAssignmentService.InvalidReason.ROSTER_MISMATCH
                && fixture.order().revision() == revision && !data.isDirty(),
            "a null persisted settler row must fail closed without mutation");
        helper.succeed();
    }

    @GameTest(template = "empty16",
        batch = "guard_readiness_null_building_fail_closed",
        timeoutTicks = 200)
    public void nullBuildingCannotBecomeGuardAuthority(
            GameTestHelper helper) {
        ReadyFixture fixture = readyFixture(helper);
        int revision = fixture.order().revision();
        // SettlementSavedData.get() performs the real persistence-boundary
        // sanitation that removes malformed null building rows. Resolve that
        // mutating boundary before injecting the in-memory corruption so the
        // assertion exercises GuardAssignmentService's read-only fail-closed
        // validation rather than sanitizing its own fixture away.
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        fixture.settlement().buildings.add(null);
        data.setDirty(false);

        GuardAssignmentService.Validation validation =
            GuardAssignmentService.validate(helper.getLevel(),
                fixture.settlement(), fixture.guard(), false);
        helper.assertTrue(
            validation.reason()
                == GuardAssignmentService.InvalidReason.ROSTER_MISMATCH
                && fixture.order().revision() == revision && !data.isDirty(),
            "a null persisted building row must fail closed without mutation"
                + " (reason=" + validation.reason() + ", revision="
                + fixture.order().revision() + ", dirty=" + data.isDirty()
                + ")");
        fixture.settlement().buildings.remove(null);
        helper.succeed();
    }

    @GameTest(template = "empty16",
        batch = "guard_readiness_null_worker_fail_closed",
        timeoutTicks = 200)
    public void nullWorkerEntryCannotBecomeGuardAuthority(
            GameTestHelper helper) {
        ReadyFixture fixture = readyFixture(helper);
        fixture.employer().workers.add(null);
        int revision = fixture.order().revision();
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.setDirty(false);

        GuardAssignmentService.Validation validation =
            GuardAssignmentService.validate(helper.getLevel(),
                fixture.settlement(), fixture.guard(), false);
        fixture.employer().workers.remove(null);
        helper.assertTrue(
            validation.reason()
                == GuardAssignmentService.InvalidReason.ROSTER_MISMATCH
                && fixture.order().revision() == revision && !data.isDirty(),
            "a null persisted worker id must fail closed without mutation");
        helper.succeed();
    }

    @GameTest(template = "empty16",
        batch = "guard_readiness_exact_root_and_hearth",
        timeoutTicks = 200)
    public void looseSettlementAndWrongHearthBindingFailClosed(
            GameTestHelper helper) {
        ReadyFixture fixture = readyFixture(helper);
        int revision = fixture.order().revision();
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.setDirty(false);
        Settlement loose = new Settlement(fixture.settlement().id,
            "Forged authority", fixture.settlement().center);

        GuardAssignmentService.Validation looseValidation =
            GuardAssignmentService.validate(helper.getLevel(), loose,
                fixture.guard(), false);
        fixture.guard().bindTo(fixture.settlement().id,
            fixture.settlement().center.offset(1, 0, 0));
        GuardAssignmentService.Validation hearthValidation =
            GuardAssignmentService.validate(helper.getLevel(),
                fixture.settlement(), fixture.guard(), false);
        fixture.guard().bindTo(fixture.settlement().id,
            fixture.settlement().center);

        helper.assertTrue(looseValidation.reason()
                == GuardAssignmentService.InvalidReason.SETTLEMENT_MISMATCH
                && hearthValidation.reason()
                    == GuardAssignmentService.InvalidReason.SETTLEMENT_MISMATCH
                && fixture.order().revision() == revision && !data.isDirty(),
            "authority requires the exact registered Settlement object and exact Hearth binding");
        helper.succeed();
    }

    @GameTest(template = "empty16",
        batch = "guard_legacy_null_record_quarantine",
        timeoutTicks = 200)
    public void legacyMigrationQuarantinesNullPersistedRecord(
            GameTestHelper helper) {
        ReadyFixture fixture = readyFixture(helper);
        GuardOrder legacy = new GuardOrder();
        helper.assertTrue(legacy.issue(GuardOrder.Mode.RALLY_HERE,
                fixture.guard().blockPosition(), Long.MAX_VALUE),
            "fixture: bounded v7 global order");
        fixture.settlement().guardOrders = GuardOrderBook.pendingLegacy(legacy);
        fixture.settlement().settlers.add(null);
        int legacyRevision = legacy.revision();

        GuardOrderBook.ReconcileResult result =
            GuardAssignmentService.reconcileLegacy(helper.getLevel(),
                fixture.settlement());
        fixture.settlement().settlers.remove(null);
        helper.assertTrue(
            result == GuardOrderBook.ReconcileResult.QUARANTINED_INVALID
                && fixture.settlement().guardOrders.quarantined()
                && fixture.settlement().guardOrders.size() == 0
                && legacy.revision() == legacyRevision,
            "v7 migration must quarantine a null persisted record without inventing Guard authority");
        helper.succeed();
    }

    @GameTest(template = "empty16",
        batch = "guard_v7_exact_runtime_migration",
        timeoutTicks = 200)
    public void v7GlobalOrderMigratesOnlyToTheExactLivePersistedGuard(
            GameTestHelper helper) {
        ReadyFixture fixture = readyFixture(helper);
        GuardOrder legacy = new GuardOrder();
        BlockPos legacyPost = helper.absolutePos(new BlockPos(7, 1, 7));
        helper.assertTrue(legacy.issue(GuardOrder.Mode.RALLY_HERE,
                legacyPost, Long.MAX_VALUE),
            "fixture: v7 global order must be valid");
        CompoundTag v7 = fixture.settlement().writeNbt();
        v7.remove("GuardOrders");
        v7.put("GuardOrder", legacy.writeNbt());
        Settlement loaded = Settlement.readNbt(v7, 7);
        helper.assertTrue(loaded.guardOrders.legacyStatus()
                == GuardOrderBook.LegacyStatus.PENDING
                && loaded.guardOrders.size() == 0,
            "v7 load must remain pending until server-level live reconciliation");

        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(loaded.id, loaded);
        data.setDirty(false);
        SettlementSavedData.get(helper.getLevel());
        GuardOrder migrated = loaded.guardOrders.order(
            fixture.guard().getUUID()).orElseThrow();
        helper.assertTrue(data.isDirty()
                && loaded.guardOrders.legacyStatus()
                    == GuardOrderBook.LegacyStatus.RESOLVED
                && loaded.guardOrders.size() == 1
                && migrated.ownedBy(loaded.id, fixture.guard().getUUID(),
                    helper.getLevel().dimension().location())
                && migrated.migratedLegacy()
                && migrated.issuerId().isEmpty()
                && migrated.linkedBuildingId().orElseThrow()
                    .equals(fixture.employer().id)
                && migrated.pos().orElseThrow().equals(legacyPost),
            "exactly one persisted and live Guard may receive the v7 order, preserving absent issuer truth");
        helper.succeed();
    }

    @GameTest(template = "empty16",
        batch = "guard_v7_ambiguous_runtime_quarantine",
        timeoutTicks = 200)
    public void v7GlobalOrderQuarantinesTwoEligibleLiveGuards(
            GameTestHelper helper) {
        ReadyFixture fixture = readyFixture(helper);
        SettlerEntity second = settler(helper, fixture.settlement(),
            new BlockPos(6, 1, 5));
        helper.assertTrue(Employment.hire(helper.getLevel(),
                fixture.settlement(), fixture.employer(), second).ok(),
            "fixture: a second exact Guard must share the persisted Barracks");
        GuardOrder legacy = new GuardOrder();
        helper.assertTrue(legacy.issue(GuardOrder.Mode.DEFEND_HEARTH,
                fixture.settlement().center, Long.MAX_VALUE),
            "fixture: v7 global order must be active");
        CompoundTag v7 = fixture.settlement().writeNbt();
        v7.remove("GuardOrders");
        v7.put("GuardOrder", legacy.writeNbt());
        Settlement loaded = Settlement.readNbt(v7, 7);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(loaded.id, loaded);
        data.setDirty(false);

        SettlementSavedData.get(helper.getLevel());
        helper.assertTrue(data.isDirty() && loaded.guardOrders.quarantined()
                && loaded.guardOrders.legacyStatus()
                    == GuardOrderBook.LegacyStatus.QUARANTINED
                && loaded.guardOrders.size() == 0,
            "two eligible live Guards are ambiguous; migration must invent no winner");
        helper.succeed();
    }

    @GameTest(template = "empty16",
        batch = "guard_v7_malformed_state_quarantine",
        timeoutTicks = 200)
    public void malformedV7GlobalOrderLoadsQuarantined(
            GameTestHelper helper) {
        ReadyFixture fixture = readyFixture(helper);
        CompoundTag v7 = fixture.settlement().writeNbt();
        v7.remove("GuardOrders");
        v7.putString("GuardOrder", "not-a-compound");

        Settlement loaded = Settlement.readNbt(v7, 7);
        helper.assertTrue(loaded.guardOrders.quarantined()
                && loaded.guardOrders.size() == 0
                && "malformed_legacy_guard_order".equals(
                    loaded.guardOrders.quarantineReason()),
            "missing, wrongly typed or malformed v7 authority must quarantine rather than reset fresh");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "guard_orders_no_command_bleed",
        timeoutTicks = 900)
    public void twoGuardsWalkOnlyTheirOwnPersistedOrders(
            GameTestHelper helper) {
        Settlement settlement = arena(helper);
        Building barracks = GameTestFixtures.register(helper, settlement,
            BuildingType.BARRACKS, 2, 2);
        SettlerEntity westGuard = settler(helper, settlement,
            new BlockPos(8, 1, 8));
        westGuard.setSettlerName("West Ward");
        SettlerEntity eastGuard = settler(helper, settlement,
            new BlockPos(8, 1, 9));
        eastGuard.setSettlerName("East Ward");
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement,
                barracks, westGuard).ok()
                && Employment.hire(helper.getLevel(), settlement,
                    barracks, eastGuard).ok(),
            "fixture: one barracks must own both exact guards");
        // QA-ready Guards: this fixture isolates independent orders, not paid preparation.
        westGuard.setHealth(westGuard.getMaxHealth());
        eastGuard.setHealth(eastGuard.getMaxHealth());
        westGuard.setItemSlot(EquipmentSlot.MAINHAND,
            new ItemStack(Items.IRON_SWORD));
        eastGuard.setItemSlot(EquipmentSlot.MAINHAND,
            new ItemStack(Items.IRON_SWORD));
        BlockPos westPost = helper.absolutePos(new BlockPos(3, 1, 8));
        BlockPos eastPost = helper.absolutePos(new BlockPos(13, 1, 8));
        helper.assertTrue(order(helper, settlement, westGuard).issueStand(
                westPost, Direction.WEST, 8, UUID.randomUUID(), barracks.id,
                helper.getLevel().getGameTime())
                && order(helper, settlement, eastGuard).issueStand(eastPost,
                    Direction.EAST, 8, UUID.randomUUID(), barracks.id,
                    helper.getLevel().getGameTime()),
            "fixture: each Guard must receive a different exact order");

        helper.succeedWhen(() -> helper.assertTrue(
            westGuard.blockPosition().distSqr(westPost) <= 2.25D
                && eastGuard.blockPosition().distSqr(eastPost) <= 2.25D
                && westGuard.blockPosition().distSqr(eastPost) > 25.0D
                && eastGuard.blockPosition().distSqr(westPost) > 25.0D,
            "Guard commands must not bleed across UUIDs; west="
                + westGuard.blockPosition() + " east="
                + eastGuard.blockPosition()));
    }

    /**
     * A player-issued post is durable authority, not a starvation or
     * exhaustion order. Both martial roles take a physical Hearth meal in
     * peacetime, then the exact same persisted posts resume.
     */
    @GameTest(template = "empty16", batch = "guard_duty_meals",
        timeoutTicks = 500)
    public void hungryOrderedGuardAndArcherEatPhysicalHearthMealsThenResumeOrders(
            GameTestHelper helper) {
        Settlement settlement = arena(helper);
        BlockPos hearthRel = new BlockPos(8, 1, 8);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getBlockEntity(hearthRel);
        helper.assertTrue(hearth != null, "fixture: shared Hearth exists");
        hearth.bindSettlement(settlement.id);
        hearth.getInventory().setStackInSlot(0, new ItemStack(Items.BREAD, 2));
        // Exercise meals beside walls, a closed gate and lower adjacent ground.
        // Require legal meal-contact routes, not a route into the Hearth's
        // collision cell. Physical consumption and resumed orders remain proof.
        helper.setBlock(new BlockPos(9, 1, 8), Blocks.COBBLESTONE_WALL);
        helper.setBlock(new BlockPos(8, 1, 7), Blocks.COBBLESTONE_WALL);
        helper.setBlock(new BlockPos(8, 1, 9), Blocks.OAK_FENCE_GATE);
        helper.setBlock(new BlockPos(7, 0, 8), Blocks.AIR);
        helper.setBlock(new BlockPos(8, 0, 7), Blocks.AIR);

        Building barracks = GameTestFixtures.register(helper, settlement,
            BuildingType.BARRACKS, 2, 2);
        Building tower = GameTestFixtures.register(helper, settlement,
            BuildingType.WATCHTOWER, 10, 2);
        SettlerEntity guard = settler(helper, settlement, new BlockPos(12, 1, 10));
        SettlerEntity archer = settler(helper, settlement, new BlockPos(12, 1, 12));
        archer.setSettlerName("Arrow Ward");
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement,
                barracks, guard).ok()
                && Employment.hire(helper.getLevel(), settlement, tower, archer).ok(),
            "fixture: valid Barracks and Watchtower hire both martial settlers");
        // Isolate hunger-driven duty pauses from the separately tested injury
        // recovery: hiring raises Guard capacity without granting health.
        guard.setHealth(guard.getMaxHealth());
        archer.setHealth(archer.getMaxHealth());
        guard.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        archer.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        BlockPos guardPost = helper.absolutePos(new BlockPos(13, 1, 10));
        BlockPos archerPost = helper.absolutePos(new BlockPos(13, 1, 12));
        GuardOrder guardOrder = order(helper, settlement, guard);
        GuardOrder archerOrder = order(helper, settlement, archer);
        UUID issuer = UUID.randomUUID();
        long now = helper.getLevel().getGameTime();
        helper.assertTrue(guardOrder.issueStand(guardPost, Direction.EAST, 8,
                issuer, barracks.id, now)
                && archerOrder.issueStand(archerPost, Direction.WEST, 8,
                    issuer, tower.id, now),
            "fixture: both roles need an authored persistent post");
        int guardRevision = guardOrder.revision();
        int archerRevision = archerOrder.revision();

        // Navigation must first see a settled physical body. PathGameTests
        // pins vanilla's real constraint: querying a just-spawned, falling
        // mob returns null even on a clear floor. Keep the first two ticks
        // inert so this test measures the need policy, not that fixture race.
        guard.setEnergy(100.0F);
        archer.setEnergy(100.0F);
        guard.setHunger(100.0F);
        archer.setHunger(100.0F);
        hearth.getInventory().setStackInSlot(0, ItemStack.EMPTY);
        boolean[] gatesChecked = {false};
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(guard.onGround() && archer.onGround(),
                "need policy begins only after both live settlers have grounded");
            guard.setHunger(10.0F);
            archer.setHunger(10.0F);
            helper.assertTrue(new GuardOrderGoal(guard).canUse()
                    && new GuardOrderGoal(archer).canUse(),
                "hungry defenders keep their real orders when no physical Hearth meal exists");
            hearth.getInventory().setStackInSlot(0, new ItemStack(Items.BREAD, 2));
            BlockPos hearthAbs = helper.absolutePos(hearthRel);
            var guardContact = HearthApproach.findReachableContactPath(
                guard, helper.getLevel(), hearthAbs);
            var archerContact = HearthApproach.findReachableContactPath(
                archer, helper.getLevel(), hearthAbs);
            helper.assertTrue(guardContact != null && archerContact != null,
                "fixture: both defenders have a full legal Hearth meal-contact route");
            helper.assertTrue(!new GuardOrderGoal(guard).canUse(),
                "a reachable Hearth must suspend the saved order for a real meal");

            // Critical exhaustion uses the real rest predicate. It may yield a
            // safe order, while an alert must keep both post orders eligible.
            guard.setHunger(100.0F);
            guard.setEnergy(11.0F);
            helper.assertTrue(!new GuardOrderGoal(guard).canUse(),
                "safe critical exhaustion must let existing RestAtNightGoal start");
            guard.setEnergy(100.0F);
            guard.setHunger(10.0F);
            settlement.alertUntilGameTime = helper.getLevel().getGameTime() + 20;
            helper.assertTrue(new GuardOrderGoal(guard).canUse()
                    && new GuardOrderGoal(archer).canUse(),
                "an active alert keeps both persisted defensive orders eligible");
            settlement.alertUntilGameTime = 0;
            gatesChecked[0] = true;
        });

        boolean[] guardOwnedMeal = {false};
        boolean[] archerOwnedMeal = {false};
        helper.succeedWhen(() -> {
            helper.assertTrue(gatesChecked[0],
                "waiting for grounded defenders and checked need gates");
            guardOwnedMeal[0] |= guard.hasMeal();
            archerOwnedMeal[0] |= archer.hasMeal();
            int owned = (guard.hasMeal() ? 1 : 0) + (archer.hasMeal() ? 1 : 0);
            int completed = 2 - hearth.countFoodUnits() - owned;
            helper.assertTrue(completed >= 0 && completed <= 2,
                "each of two bread stays in storage, visibly owned, or completes once");
            helper.assertTrue(guardOrder.mode() == GuardOrder.Mode.STAND_POST
                    && archerOrder.mode() == GuardOrder.Mode.STAND_POST
                    && guardOrder.revision() == guardRevision
                    && archerOrder.revision() == archerRevision,
                "a meal must not clear, rewrite, or re-author either defensive order");
            boolean guardResumed = guard.goalSelector.getAvailableGoals().stream()
                .anyMatch(wrapped -> wrapped.getGoal() instanceof GuardOrderGoal
                    && wrapped.isRunning());
            boolean archerResumed = archer.goalSelector.getAvailableGoals().stream()
                .anyMatch(wrapped -> wrapped.getGoal() instanceof GuardOrderGoal
                    && wrapped.isRunning());
            helper.assertTrue(guardOwnedMeal[0] && archerOwnedMeal[0]
                    && hearth.countFoodUnits() == 0
                    && !guard.hasMeal() && !archer.hasMeal()
                    && guard.getHunger() > 10.0F && archer.getHunger() > 10.0F
                    && guardResumed && archerResumed
                    && guard.blockPosition().distSqr(guardPost) <= 6.25D
                    && archer.blockPosition().distSqr(archerPost) <= 6.25D,
                "each hungry ordered defender must physically consume one Hearth bread, then resume its own saved post");
        });
    }

    @GameTest(template = "empty16",
        batch = "guard_control_sealed_raid_occlusion", timeoutTicks = 360)
    public void sealedRaidParticipantBehindClosedDoorRoutesBeforePhysicalHit(
            GameTestHelper helper) {
        Settlement settlement = arena(helper);
        Building barracks = GameTestFixtures.register(helper, settlement,
            BuildingType.BARRACKS, 2, 2);
        Building tower = GameTestFixtures.register(helper, settlement,
            BuildingType.WATCHTOWER, 0, 0);
        SettlerEntity guard = settler(helper, settlement,
            new BlockPos(3, 1, 8));
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement,
                barracks, guard).ok(), "fixture: barracks hire");
        guard.setHealth(guard.getMaxHealth());
        guard.setItemSlot(EquipmentSlot.MAINHAND,
            new ItemStack(Items.IRON_SWORD));
        GuardOrder order = order(helper, settlement, guard);
        helper.assertTrue(order.issueStand(guard.blockPosition(), Direction.EAST,
                GuardOrder.DEFAULT_LEASH_RADIUS, UUID.randomUUID(), barracks.id,
                helper.getLevel().getGameTime()),
            "fixture: Guard must hold one persisted eight-block post");
        SettlerEntity archer = settler(helper, settlement,
            new BlockPos(3, 1, 5));
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement, tower,
                archer).ok(), "fixture: Watchtower hire");
        archer.setHealth(archer.getMaxHealth());
        archer.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        GuardOrder archerOrder = order(helper, settlement, archer);
        helper.assertTrue(archerOrder.issueTower(archer.blockPosition(),
                Direction.EAST, GuardOrder.DEFAULT_FACING_ARC,
                UUID.randomUUID(), tower.id, helper.getLevel().getGameTime()),
            "fixture: Archer must hold one persisted tower post");

        // A three-block wall makes the closed oak door the only route. The
        // target begins six blocks from the post, so this proves occlusion,
        // not a range or leash exception.
        for (int z = 0; z < 16; z++) {
            if (z == 8) continue;
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(6, y, z), Blocks.STONE_BRICKS);
            }
        }
        BlockPos door = new BlockPos(6, 1, 8);
        BlockPos doorWorld = helper.absolutePos(door);
        helper.setBlock(door, Blocks.OAK_DOOR.defaultBlockState()
            .setValue(DoorBlock.FACING, Direction.EAST)
            .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
            .setValue(DoorBlock.OPEN, false));
        helper.setBlock(door.above(), Blocks.OAK_DOOR.defaultBlockState()
            .setValue(DoorBlock.FACING, Direction.EAST)
            .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER)
            .setValue(DoorBlock.OPEN, false));

        RaiderEntity sealed = helper.spawn(ModEntities.RAIDER.get(),
            new BlockPos(9, 1, 8));
        sealActiveParticipant(helper, settlement, sealed);
        sealed.setNoAi(true);
        RaiderEntity untracked = helper.spawn(ModEntities.RAIDER.get(),
            new BlockPos(9, 1, 5));
        untracked.assign(UUID.randomUUID(), settlement.id, RaidObjective.BLOD,
            1.0F, false);
        untracked.setNoAi(true);
        RaiderEntity goblin = helper.spawn(ModEntities.RAIDER.get(),
            new BlockPos(9, 1, 11));
        goblin.assign(UUID.randomUUID(), settlement.id, RaidObjective.BLOD,
            1.0F, false);
        goblin.setGoblinThiefPresentation(true, 0);
        goblin.setNoAi(true);

        float sealedHealth = sealed.getHealth();
        boolean[] acquiredBehindDoor = {false};
        boolean[] crossedDoor = {false};
        boolean[] openedDoor = {false};
        helper.onEachTick(() -> {
            boolean closed = !helper.getLevel().getBlockState(doorWorld)
                .getValue(DoorBlock.OPEN);
            if (!openedDoor[0] && closed && guard.getTarget() == sealed) {
                acquiredBehindDoor[0] = true;
                helper.assertTrue(sealed.getHealth() == sealedHealth
                        && guard.committedMeleeContacts() == 0L,
                    "a sealed raid target may be acquired through cover but "
                        + "must take no damage before physical door contact");
                helper.assertTrue(!guard.hasLineOfSight(sealed),
                    "the closed door must still occlude the initial target");
                helper.assertTrue(RaidThreatBoard.assignedTarget(
                        helper.getLevel(), settlement, guard) == sealed,
                    "only the sealed active participant may receive the "
                        + "through-cover assignment");
                helper.assertTrue(!RaidThreatBoard
                        .mayAcquireSealedRaidThreatThroughCover(
                            helper.getLevel(), settlement, archer, sealed)
                        && archer.getTarget() == null,
                    "the through-cover exception is Guard-only; an Archer "
                        + "must wait for actual line of sight");
            }
            if (!closed) openedDoor[0] = true;
            if (guard.getX() > doorWorld.getX() + 0.5D) crossedDoor[0] = true;
            helper.assertTrue(guard.getTarget() != untracked
                    && guard.getTarget() != goblin,
                "untracked raiders and Goblin thieves must never use the "
                    + "sealed-raid occlusion exception");
            if (acquiredBehindDoor[0] && openedDoor[0] && crossedDoor[0]
                    && sealed.getHealth() < sealedHealth
                    && guard.committedMeleeContacts() > 0L) {
                sealed.discard();
                untracked.discard();
                goblin.discard();
                archer.discard();
                SettlementSavedData.get(helper.getLevel()).settlements
                    .remove(settlement.id);
                helper.succeed();
            }
        });
    }

    /**
     * A safe posted archer must not starve merely because RoadNavigation's
     * bounded follow range first returns a legal prefix rather than the full
     * Hearth contact route.  The prefix only yields MOVE; food still leaves
     * the live Hearth after close physical contact, and the exact order
     * resumes unchanged.
     */
    @GameTest(template = "empty32", batch = "guard_duty_meal_staged_route",
        timeoutTicks = 900)
    public void hungryOrderedArcherStagesCloserHearthPrefixThenResumesPost(
            GameTestHelper helper) {
        BlockPos hearthRel = new BlockPos(2, 1, 2);
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 3; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getBlockEntity(hearthRel);
        helper.assertTrue(hearth != null, "fixture: staged-route Hearth exists");

        BlockPos hearthPos = helper.absolutePos(hearthRel);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(), "PostRoute", hearthPos);
        settlement.radius = 48;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        hearth.bindSettlement(settlement.id);
        hearth.getInventory().setStackInSlot(0, new ItemStack(Items.BREAD, 1));

        Building tower = GameTestFixtures.register(helper, settlement,
            BuildingType.WATCHTOWER, 25, 25);
        SettlerEntity archer = settler(helper, settlement, new BlockPos(29, 1, 29));
        archer.setSettlerName("Staged Arrow Ward");
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement, tower, archer).ok(),
            "fixture: Watchtower must hire the staged-route archer");
        archer.setHealth(archer.getMaxHealth());
        archer.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        BlockPos post = helper.absolutePos(new BlockPos(29, 1, 28));
        GuardOrder order = order(helper, settlement, archer);
        helper.assertTrue(order.issueStand(post, Direction.NORTH, 8,
                UUID.randomUUID(), tower.id, helper.getLevel().getGameTime()),
            "fixture: staged-route archer needs a persisted post");
        int revision = order.revision();

        boolean[] routeWitnessed = {false};
        boolean[] contactBeforeClaim = {false};
        boolean[] ownedMeal = {false};
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(archer.onGround(),
                "fixture: staged-route archer must be grounded before probing");
            archer.setHunger(10.0F);
            Path originalBudget = new IndoorRouteGameTests.OrdinaryNavigation(archer)
                .createPath(hearthPos, 1);
            Path strict = HearthApproach.findReachableContactPath(archer,
                helper.getLevel(), hearthPos);
            Path progressing = HearthApproach.findProgressingContactPath(archer,
                helper.getLevel(), hearthPos);
            BlockPos start = archer.blockPosition();
            BlockPos end = originalBudget == null || originalBudget.getNodeCount() == 0 ? null
                : originalBudget.getNodePos(originalBudget.getNodeCount() - 1);
            helper.assertTrue(originalBudget != null && !originalBudget.canReach() && end != null
                    && !end.equals(start) && end.distSqr(hearthPos) < start.distSqr(hearthPos),
                "original navigation must retain a strictly nearer partial prefix: "
                    + pathEvidence(originalBudget));
            helper.assertTrue(strict != null && strict.canReach(),
                "current navigation must complete the former range-limited Hearth contact: "
                    + pathEvidence(strict));
            helper.assertTrue(progressing != null && progressing.canReach(),
                "safe essential meal must retain complete physical contact: "
                    + pathEvidence(progressing));
            helper.assertTrue(!new GuardOrderGoal(archer).canUse(),
                "a stocked safe Hearth prefix must temporarily yield the persisted post");
            helper.assertTrue(hearth.countFoodUnits() == 1 && !archer.hasMeal(),
                "route arbitration must not withdraw food before physical contact");
            routeWitnessed[0] = true;
        });

        helper.succeedWhen(() -> {
            helper.assertTrue(routeWitnessed[0], "waiting for staged-route witness");
            if (!ownedMeal[0] && archer.blockPosition().distSqr(hearthPos) <= 6.25D) {
                contactBeforeClaim[0] = true;
            }
            ownedMeal[0] |= archer.hasMeal();
            helper.assertTrue(order.mode() == GuardOrder.Mode.STAND_POST
                    && order.revision() == revision,
                "a staged meal must not rewrite the persisted archer order");
            boolean orderResumed = archer.goalSelector.getAvailableGoals().stream()
                .anyMatch(wrapped -> wrapped.getGoal() instanceof GuardOrderGoal
                    && wrapped.isRunning());
            helper.assertTrue(!ownedMeal[0] || contactBeforeClaim[0],
                "food may leave the Hearth only after the archer reached close contact");
            helper.assertTrue(ownedMeal[0] && hearth.countFoodUnits() == 0
                    && !archer.hasMeal() && archer.getHunger() > 10.0F
                    && orderResumed && archer.blockPosition().distSqr(post) <= 6.25D,
                "the staged archer must eat one physical bread and resume its exact post"
                    + " [pos=" + archer.position() + ", block=" + archer.blockPosition()
                    + ", hunger=" + archer.getHunger() + ", food="
                    + hearth.countFoodUnits() + ", ownedSeen=" + ownedMeal[0]
                    + ", hasMeal=" + archer.hasMeal() + ", contactSeen="
                    + contactBeforeClaim[0] + ", activity=" + archer.getActivity()
                    + ", orderRunning=" + orderResumed + ", nav="
                    + pathEvidence(archer.getNavigation().getPath()) + ", orderMode="
                    + order.mode() + ", orderRevision=" + order.revision()
                    + ", expectedRevision=" + revision + "]");
        });
    }

    /**
     * A safe, exhausted day guard with no claim must not retain a saved post
     * merely because RoadNavigation stops short of the low Hearth. The route
     * witness is required before normal goal arbitration may claim a real
     * Barracks bed, sleep, recover, and resume that unchanged post.
     */
    @GameTest(template = "empty32", batch = "guard_critical_rest_hearth_fallback",
        timeoutTicks = 1200)
    public void exhaustedUnclaimedDayGuardUsesFullHearthContactThenRecoversAtBarracks(
            GameTestHelper helper) {
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 3; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        // A DAY guard is normally on duty during MORNING_WORK; critical energy
        // rather than curfew must be what makes the persisted post yield.
        helper.getLevel().setDayTime(2000);
        BlockPos hearthRel = new BlockPos(4, 1, 4);
        BlockPos hearthPos = helper.absolutePos(hearthRel);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getBlockEntity(hearthRel);
        helper.assertTrue(hearth != null, "fixture: real low Hearth exists");

        Settlement settlement = new Settlement(UUID.randomUUID(), "Restroute",
            hearthPos);
        settlement.radius = 31;
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        hearth.bindSettlement(settlement.id);

        Building barracks = GameTestFixtures.register(helper, settlement,
            BuildingType.BARRACKS, 18, 16);
        BlockPos bedFootRel = new BlockPos(19, 1, 20);
        BlockPos bedHeadRel = bedFootRel.north();
        helper.setBlock(bedFootRel, Blocks.RED_BED.defaultBlockState()
            .setValue(BedBlock.FACING, Direction.NORTH)
            .setValue(BedBlock.PART, BedPart.FOOT));
        helper.setBlock(bedHeadRel, Blocks.RED_BED.defaultBlockState()
            .setValue(BedBlock.FACING, Direction.NORTH)
            .setValue(BedBlock.PART, BedPart.HEAD));
        BlockPos bedHead = helper.absolutePos(bedHeadRel);
        barracks.beds.add(bedHead);

        SettlerEntity guard = settler(helper, settlement, new BlockPos(29, 1, 20));
        guard.setSettlerName("Exhausted Day Ward");
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement, barracks, guard).ok(),
            "fixture: valid Barracks must own the day guard");
        guard.setHealth(guard.getMaxHealth());
        guard.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        guard.setHunger(100.0F);
        guard.setEnergy(0.0F);
        BlockPos post = helper.absolutePos(new BlockPos(29, 1, 20));
        GuardOrder savedOrder = order(helper, settlement, guard);
        helper.assertTrue(savedOrder.issueStand(post, Direction.NORTH, 8, UUID.randomUUID(),
                barracks.id, helper.getLevel().getGameTime()),
            "fixture: safe guard needs one persistent post");
        int savedRevision = savedOrder.revision();
        HoldMoveGoal hold = new HoldMoveGoal();
        guard.goalSelector.addGoal(0, hold);

        boolean[] witnessed = {false};
        boolean[] claimed = {false};
        boolean[] slept = {false};
        boolean[] energyRoseSleeping = {false};
        boolean[] recoveredThreshold = {false};
        float[] sleepEnergy = {-1.0F};
        float[] peakEnergy = {0.0F};
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(guard.onGround(),
                "route witness requires the real guard body to have grounded");
            helper.assertTrue(Employment.watchOf(settlement, guard) == Employment.Watch.DAY
                    && guard.dayPhase().work() && guard.getClaimedBed() == null
                    && settlement.alertUntilGameTime == 0,
                "fixture: exhausted safe DAY guard must have no claimed bed");
            // Preserve the original failure witness with the original budget.
            // Actual goal arbitration below still uses the new live navigator.
            Path rawRoad = new IndoorRouteGameTests.OrdinaryNavigation(guard).createPath(hearthPos, 1);
            RoadNavigation.resetExpandedBudgetForTests(helper.getLevel()); // parallel tests share the per-tick budget
            Path currentRoad = new RoadNavigation(guard, helper.getLevel()).createPath(hearthPos, 1);
            helper.assertTrue(endsBesideHearth(currentRoad, hearthPos),
                "current navigation must recover the previously partial Hearth route");
            Path strictContact = HearthApproach.findReachableContactPath(guard,
                helper.getLevel(), hearthPos);
            Hearthstead.LOGGER.info("HSQA_GUARD_REST_ROUTE_AB tick={} pos={} rawRoad={} strictContact={}",
                helper.getTick(), guard.position(), pathEvidence(rawRoad), pathEvidence(strictContact));
            helper.assertTrue(rawRoad == null || !rawRoad.canReach(),
                "fixture must witness the raw Hearth Road miss: " + pathEvidence(rawRoad));
            helper.assertTrue(strictContact != null && strictContact.canReach(),
                "strict HearthApproach must retain a full legal contact route: "
                    + pathEvidence(strictContact));
            helper.assertTrue(savedOrder.mode() == GuardOrder.Mode.STAND_POST
                    && savedOrder.revision() == savedRevision,
                "route probes must not rewrite the persisted guard order");
            guard.goalSelector.removeGoal(hold);
            witnessed[0] = true;
        });

        helper.runAtTickTime(/* absolute: terminal step at the deadline */ 1190, () -> {
            boolean orderRunning = guard.goalSelector.getAvailableGoals().stream()
                .anyMatch(wrapped -> wrapped.getGoal() instanceof GuardOrderGoal && wrapped.isRunning());
            Hearthstead.LOGGER.info("HSQA_GUARD_REST_TIMEOUT tick={} pos={} blockPos={} onGround={} energy={} hunger={} activity={} claimedBed={} expectedBed={} sleeping={} orderRunning={} orderMode={} revision={} witnessed={} claimed={} slept={} energyRoseSleeping={} recoveredThreshold={} peakEnergy={} navPath={} target={} runningGoals={}",
                helper.getTick(), guard.position(), guard.blockPosition(), guard.onGround(),
                guard.getEnergy(), guard.getHunger(), guard.getActivity(), guard.getClaimedBed(),
                bedHead, guard.isSleeping(), orderRunning, savedOrder.mode(), savedOrder.revision(),
                witnessed[0], claimed[0], slept[0], energyRoseSleeping[0], recoveredThreshold[0],
                peakEnergy[0], pathEvidence(guard.getNavigation().getPath()), guard.getTarget(),
                runningGoalEvidence(guard));
        });

        helper.onEachTick(() -> {
            if (!witnessed[0]) return;
            helper.assertTrue(savedOrder.mode() == GuardOrder.Mode.STAND_POST
                    && savedOrder.revision() == savedRevision,
                "critical rest must never clear or revise the persisted post");
            if (bedHead.equals(guard.getClaimedBed())) claimed[0] = true;
            if (guard.isSleeping()) {
                slept[0] = true;
                if (sleepEnergy[0] < 0.0F) sleepEnergy[0] = guard.getEnergy();
                else if (guard.getEnergy() > sleepEnergy[0] + 1.0F) energyRoseSleeping[0] = true;
                peakEnergy[0] = Math.max(peakEnergy[0], guard.getEnergy());
                if (guard.getEnergy() >= 60.0F) recoveredThreshold[0] = true;
            }
            boolean orderResumed = guard.goalSelector.getAvailableGoals().stream()
                .anyMatch(wrapped -> wrapped.getGoal() instanceof GuardOrderGoal && wrapped.isRunning());
            if (claimed[0] && slept[0] && energyRoseSleeping[0] && recoveredThreshold[0]
                    && !guard.isSleeping() && orderResumed
                    && guard.blockPosition().distSqr(post) <= 6.25D) {
                Hearthstead.LOGGER.info("HSQA_GUARD_REST_PHYSICAL_SUCCESS tick={} pos={} energy={} peakEnergy={} recoveredThreshold={} bed={} revision={}",
                    helper.getTick(), guard.position(), guard.getEnergy(), peakEnergy[0],
                    recoveredThreshold[0], guard.getClaimedBed(), savedOrder.revision());
                // This fixture writes a real SavedData settlement; remove only
                // its own record after every physical-success assertion so it
                // cannot affect a later GameTest's independent settlement.
                guard.discard();
                data.settlements.remove(settlement.id);
                data.setDirty();
                helper.setBlock(hearthRel, Blocks.AIR);
                helper.succeed();
            }
        });
    }

    /**
     * A safe exhausted defender without a claim must be allowed to begin the
     * existing bed-claim flow when a nearby free bed is fully reachable, even
     * when its remote Hearth has no full contact route within navigation's
     * bounded search. RestAtNightGoal claims the bed only after arbitration;
     * keeping the post in control until strict Hearth contact would otherwise
     * prevent that real claim forever.
     */
    @GameTest(template = "empty32", batch = "guard_critical_rest_free_bed_preclaim",
        timeoutTicks = 1600)
    public void exhaustedUnclaimedDayGuardClaimsReachableBedWhenFullHearthContactIsRemote(
            GameTestHelper helper) {
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 3; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        // Critical energy, rather than curfew, must yield the saved DAY post.
        helper.getLevel().setDayTime(2000);
        BlockPos hearthRel = new BlockPos(2, 1, 2);
        BlockPos hearthPos = helper.absolutePos(hearthRel);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getBlockEntity(hearthRel);
        helper.assertTrue(hearth != null, "fixture: real remote Hearth exists");

        Settlement settlement = new Settlement(UUID.randomUUID(), "Freebedroute",
            hearthPos);
        settlement.radius = 48;
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        hearth.bindSettlement(settlement.id);

        Building barracks = GameTestFixtures.register(helper, settlement,
            BuildingType.BARRACKS, 18, 16);
        BlockPos bedFootRel = new BlockPos(19, 1, 20);
        BlockPos bedHeadRel = bedFootRel.north();
        helper.setBlock(bedFootRel, Blocks.RED_BED.defaultBlockState()
            .setValue(BedBlock.FACING, Direction.NORTH)
            .setValue(BedBlock.PART, BedPart.FOOT));
        helper.setBlock(bedHeadRel, Blocks.RED_BED.defaultBlockState()
            .setValue(BedBlock.FACING, Direction.NORTH)
            .setValue(BedBlock.PART, BedPart.HEAD));
        BlockPos bedHead = helper.absolutePos(bedHeadRel);
        barracks.beds.add(bedHead);

        SettlerEntity guard = settler(helper, settlement, new BlockPos(29, 1, 29));
        guard.setSettlerName("Remote Hearth Ward");
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement, barracks, guard).ok(),
            "fixture: valid Barracks must own the exhausted day guard");
        guard.setHealth(guard.getMaxHealth());
        guard.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        guard.setHunger(100.0F);
        guard.setEnergy(0.0F);
        BlockPos post = helper.absolutePos(new BlockPos(29, 1, 29));
        GuardOrder savedOrder = order(helper, settlement, guard);
        helper.assertTrue(savedOrder.issueStand(post, Direction.NORTH, 8, UUID.randomUUID(),
                barracks.id, helper.getLevel().getGameTime()),
            "fixture: safe guard needs one persistent remote post");
        int savedRevision = savedOrder.revision();
        HoldMoveGoal hold = new HoldMoveGoal();
        guard.goalSelector.addGoal(0, hold);

        boolean[] witnessed = {false};
        boolean[] claimed = {false};
        boolean[] slept = {false};
        boolean[] energyRoseSleeping = {false};
        boolean[] recoveredThreshold = {false};
        float[] sleepEnergy = {-1.0F};
        float[] peakEnergy = {0.0F};
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(guard.onGround(),
                "remote pre-claim witness requires the real guard body to be grounded");
            helper.assertTrue(Employment.watchOf(settlement, guard) == Employment.Watch.DAY
                    && guard.dayPhase().work() && guard.getClaimedBed() == null
                    && settlement.alertUntilGameTime == 0,
                "fixture: exhausted safe DAY guard must have no claim or alert");
            // Preserve the old range-limited premise separately. The live
            // navigator now intentionally completes this same civilian route.
            Path rawRoad = new IndoorRouteGameTests.OrdinaryNavigation(guard).createPath(hearthPos, 1);
            RoadNavigation.resetExpandedBudgetForTests(helper.getLevel()); // parallel tests share the per-tick budget
            Path recoveredRoad = new RoadNavigation(guard, helper.getLevel()).createPath(hearthPos, 1);
            Path strictContact = HearthApproach.findReachableContactPath(guard,
                helper.getLevel(), hearthPos);
            RoadNavigation.resetExpandedBudgetForTests(helper.getLevel()); // parallel tests share the per-tick budget
            Path bedRoute = new RoadNavigation(guard, helper.getLevel()).createPath(bedHead, 1);
            BlockPos freeBed = BuildingManager.findFreeBed(helper.getLevel(), settlement);
            Hearthstead.LOGGER.info("HSQA_GUARD_FREE_BED_REST_ROUTE tick={} pos={} rawHearth={} strictHearth={} freeBed={} expectedBed={} bedRoute={}",
                helper.getTick(), guard.position(), pathEvidence(rawRoad), pathEvidence(strictContact),
                freeBed, bedHead, pathEvidence(bedRoute));
            helper.assertTrue(rawRoad == null || !rawRoad.canReach(),
                "fixture must witness the remote raw Hearth Road miss: " + pathEvidence(rawRoad));
            helper.assertTrue(endsBesideHearth(recoveredRoad, hearthPos),
                "current navigation must recover the former range-limited Hearth route: "
                    + pathEvidence(recoveredRoad));
            helper.assertTrue(strictContact != null && strictContact.canReach(),
                "expanded civilian navigation must also recover the former remote Hearth contact: "
                    + pathEvidence(strictContact));
            helper.assertTrue(bedHead.equals(freeBed) && bedRoute != null && bedRoute.canReach(),
                "fixture must retain one exact reachable free bed: freeBed=" + freeBed
                    + ", bedRoute=" + pathEvidence(bedRoute));
            helper.assertTrue(savedOrder.mode() == GuardOrder.Mode.STAND_POST
                    && savedOrder.revision() == savedRevision,
                "route probes must not rewrite the persisted guard order");
            guard.goalSelector.removeGoal(hold);
            witnessed[0] = true;
        });

        helper.runAtTickTime(/* absolute: terminal step at the deadline */ 1590, () -> {
            boolean orderRunning = guard.goalSelector.getAvailableGoals().stream()
                .anyMatch(wrapped -> wrapped.getGoal() instanceof GuardOrderGoal && wrapped.isRunning());
            Hearthstead.LOGGER.info("HSQA_GUARD_FREE_BED_REST_TIMEOUT tick={} pos={} blockPos={} onGround={} energy={} hunger={} activity={} claimedBed={} expectedBed={} sleeping={} orderRunning={} orderMode={} revision={} witnessed={} claimed={} slept={} energyRoseSleeping={} recoveredThreshold={} peakEnergy={} navPath={} target={} runningGoals={}",
                helper.getTick(), guard.position(), guard.blockPosition(), guard.onGround(),
                guard.getEnergy(), guard.getHunger(), guard.getActivity(), guard.getClaimedBed(),
                bedHead, guard.isSleeping(), orderRunning, savedOrder.mode(), savedOrder.revision(),
                witnessed[0], claimed[0], slept[0], energyRoseSleeping[0], recoveredThreshold[0],
                peakEnergy[0], pathEvidence(guard.getNavigation().getPath()), guard.getTarget(),
                runningGoalEvidence(guard));
        });

        helper.onEachTick(() -> {
            if (!witnessed[0]) return;
            helper.assertTrue(savedOrder.mode() == GuardOrder.Mode.STAND_POST
                    && savedOrder.revision() == savedRevision,
                "free-bed recovery must never clear or revise the persisted post");
            if (bedHead.equals(guard.getClaimedBed())) claimed[0] = true;
            if (guard.isSleeping()) {
                slept[0] = true;
                if (sleepEnergy[0] < 0.0F) sleepEnergy[0] = guard.getEnergy();
                else if (guard.getEnergy() > sleepEnergy[0] + 1.0F) energyRoseSleeping[0] = true;
                peakEnergy[0] = Math.max(peakEnergy[0], guard.getEnergy());
                if (guard.getEnergy() >= 60.0F) recoveredThreshold[0] = true;
            }
            boolean orderResumed = guard.goalSelector.getAvailableGoals().stream()
                .anyMatch(wrapped -> wrapped.getGoal() instanceof GuardOrderGoal && wrapped.isRunning());
            if (claimed[0] && slept[0] && energyRoseSleeping[0] && recoveredThreshold[0]
                    && !guard.isSleeping() && orderResumed
                    && guard.blockPosition().distSqr(post) <= 6.25D) {
                Hearthstead.LOGGER.info("HSQA_GUARD_FREE_BED_REST_SUCCESS tick={} pos={} energy={} peakEnergy={} recoveredThreshold={} bed={} revision={}",
                    helper.getTick(), guard.position(), guard.getEnergy(), peakEnergy[0],
                    recoveredThreshold[0], guard.getClaimedBed(), savedOrder.revision());
                guard.discard();
                data.settlements.remove(settlement.id);
                data.setDirty();
                helper.setBlock(hearthRel, Blocks.AIR);
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty16",
        batch = "settlement_registry_existing_is_pure",
        timeoutTicks = 200)
    public void readOnlyRegistryLookupDoesNotRunLegacyGuardReconciliation(
            GameTestHelper helper) {
        Settlement settlement = arena(helper);
        Building barracks = GameTestFixtures.register(helper, settlement,
            BuildingType.BARRACKS, 2, 2);
        SettlerEntity guard = settler(helper, settlement,
            new BlockPos(5, 1, 5));
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement,
                barracks, guard).ok(), "fixture: barracks hire");
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        GuardOrder legacy = new GuardOrder();
        helper.assertTrue(legacy.issue(GuardOrder.Mode.RALLY_HERE,
                guard.blockPosition(), Long.MAX_VALUE),
            "fixture: v7 global order");
        settlement.guardOrders = GuardOrderBook.pendingLegacy(legacy);
        // get() above ran before pending state was installed; the next call is
        // deliberately the pure lookup under test.
        data.setDirty(false);
        int legacyRevision = legacy.revision();

        SettlementSavedData observed = SettlementSavedData.existing(
            helper.getLevel());
        helper.assertTrue(observed == data && !data.isDirty()
                && settlement.guardOrders.legacyStatus()
                    == GuardOrderBook.LegacyStatus.PENDING
                && settlement.guardOrders.size() == 0
                && legacy.revision() == legacyRevision,
            "read-only registry lookup must not reconcile, revise, or dirty v7 Guard state");
        helper.succeed();
    }

    private static GuardOrder order(GameTestHelper helper,
                                    Settlement settlement,
                                    SettlerEntity guard) {
        return settlement.guardOrders.orderForMutation(settlement.id,
            guard.getUUID(), helper.getLevel().dimension().location())
            .orElseThrow();
    }

    /**
     * Minimal real authored-raid lifecycle: the raider has an exact
     * settlement identity and becomes a sealed, tracked first-raid
     * participant. It deliberately does not use a generic assigned Raider,
     * which must remain ineligible behind cover.
     */
    private static void sealActiveParticipant(GameTestHelper helper,
                                              Settlement settlement,
                                              RaiderEntity raider) {
        RaidLifecycle lifecycle = settlement.raidLifecycle;
        RaidPlan plan = new RaidPlan(UUID.randomUUID(), RaidObjective.BLOD,
            0.0F, 4L);
        helper.assertTrue(lifecycle.initializeAtFounding(0L, 4, 1)
                && lifecycle.queueFirstPlan(plan)
                && lifecycle.beginFirstRaid(plan),
            "fixture: one exact plan must enter ACTIVE before capture");
        raider.assign(plan.captainId(), settlement.id, plan.objective(),
            1.0F, false);
        helper.assertTrue(lifecycle.recordParticipant(raider.getUUID())
                && lifecycle.sealParticipants()
                && lifecycle.isAuthoredFirstRaidActive()
                && lifecycle.participantsTracked()
                && lifecycle.isParticipant(raider.getUUID())
                && !lifecycle.terminalParticipants().contains(raider.getUUID()),
            "fixture: the only through-cover candidate must be one sealed "
                + "nonterminal participant of this exact ACTIVE raid");
    }

    private static ReadyFixture readyFixture(GameTestHelper helper) {
        Settlement settlement = arena(helper);
        Building barracks = GameTestFixtures.register(helper, settlement,
            BuildingType.BARRACKS, 2, 2);
        SettlerEntity guard = settler(helper, settlement,
            new BlockPos(5, 1, 5));
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement,
                barracks, guard).ok(),
            "fixture: exact Barracks must hire the Guard");
        GuardOrder order = order(helper, settlement, guard);
        helper.assertTrue(order.issueStand(guard.blockPosition(),
                Direction.NORTH, GuardOrder.DEFAULT_LEASH_RADIUS,
                UUID.randomUUID(), barracks.id,
                helper.getLevel().getGameTime()),
            "fixture: exact Stand Post must commit");
        guard.setItemSlot(EquipmentSlot.MAINHAND,
            new ItemStack(Items.IRON_SWORD));
        return new ReadyFixture(settlement, barracks, guard, order);
    }

    private static String runningGoalEvidence(SettlerEntity settler) {
        return settler.goalSelector.getAvailableGoals().stream()
            .filter(wrapped -> wrapped.isRunning())
            .map(wrapped -> wrapped.getGoal().getClass().getSimpleName())
            .sorted()
            .toList().toString();
    }

    /**
     * The route really gets to the Hearth: its last node stands next to it.
     * Not canReach(): vanilla GroundPathNavigation retargets a solid block
     * to the cell ABOVE it, so on flat ground every Hearth-side cell is two
     * Manhattan steps from that target and canReach(accuracy 1) is false for
     * a route that does arrive (W3b/W6: end one cell beside and one below).
     * The raw, range-limited miss still ends far away and still fails this.
     */
    private static boolean endsBesideHearth(Path path, BlockPos hearth) {
        if (path == null || path.getNodeCount() == 0) return false;
        BlockPos end = path.getNodePos(path.getNodeCount() - 1);
        return Math.abs(end.getX() - hearth.getX()) <= 1 && Math.abs(end.getZ() - hearth.getZ()) <= 1
            && Math.abs(end.getY() - hearth.getY()) <= 1;
    }

    private static String pathEvidence(Path path) {
        if (path == null) return "null";
        int count = path.getNodeCount();
        BlockPos end = count == 0 ? null : path.getNodePos(count - 1);
        return "target=" + path.getTarget() + ",canReach=" + path.canReach()
            + ",count=" + count + ",end=" + end;
    }

    private static final class HoldMoveGoal extends Goal {
        private HoldMoveGoal() { setFlags(EnumSet.of(Flag.MOVE)); }
        @Override public boolean canUse() { return true; }
        @Override public boolean canContinueToUse() { return true; }
    }

    private record ReadyFixture(Settlement settlement, Building employer,
                                SettlerEntity guard, GuardOrder order) {
    }
}
