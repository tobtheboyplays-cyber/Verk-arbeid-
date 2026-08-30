package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.GuardOrderGoal;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.guard.GuardAssignmentService;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.state.GuardOrder;
import com.hearthstead.settlement.state.GuardOrderBook;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

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

        helper.runAtTickTime(40L, () -> helper.assertTrue(
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

    private record ReadyFixture(Settlement settlement, Building employer,
                                SettlerEntity guard, GuardOrder order) {
    }
}
