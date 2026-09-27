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
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Adversarial server-authority tests for the typed physical request gate. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class RequestLedgerGameTests {

    @GameTest(template = "empty16", timeoutTicks = 40,
        batch = "request_ledger_authority")
    public void canonicalExpiredReservationMarkerMigratesWithoutLosingActiveIntent(
            GameTestHelper helper) {
        UUID settlementId = UUID.randomUUID();
        UUID courierId = UUID.randomUUID();
        RequestItemFingerprint fingerprint = RequestItemFingerprint.capture(
            helper.getLevel().registryAccess(), new ItemStack(Items.OAK_LOG, 2), 2);
        RequestLedger source = new RequestLedger(settlementId);
        UUID[] ids = new UUID[2];
        for (int index = 0; index < ids.length; index++) {
            RequestRecord row = RequestRecord.openOutput(UUID.randomUUID(), settlementId,
                RequestPriority.NORMAL, helper.getLevel().dimension().location(),
                UUID.randomUUID(), helper.absolutePos(new BlockPos(2 + index, 1, 2)), 0,
                UUID.randomUUID(), helper.absolutePos(new BlockPos(10, 1, 2)),
                fingerprint, 2, 0, 1L);
            ids[index] = row.id();
            helper.assertTrue(source.open(row).result() == RequestLedger.OpenResult.CREATED
                    && row.reserve(courierId, 2L, 100L)
                    && row.block(RequestBlocker.FINGERPRINT_MISMATCH, 3L),
                "fixture requires canonical blocked reservations with zero physical cargo");
            if (index == 1) {
                helper.assertTrue(row.expire(4L) && source.mutationCommitted(row),
                    "historical receipt must finish BLOCKED to EXPIRED");
            }
        }
        CompoundTag saved = source.writeNbt();
        CompoundTag raw = saved.getList("Terminal", Tag.TAG_COMPOUND).getCompound(0);
        raw.putInt("BlockedFrom", RequestState.RESERVED.wireId());
        helper.assertTrue(RequestItemFingerprint.readNbt(raw.getCompound("Fingerprint"),
                helper.getLevel().registryAccess()) != null
                && RequestRecord.readNbt(raw, helper.getLevel().registryAccess(), settlementId) == null,
            "the real failure has a valid canonical checksum and only a stale terminal marker");
        RequestLedger restored = RequestLedger.readNbt(saved,
            helper.getLevel().registryAccess(), settlementId);
        helper.assertTrue(!restored.quarantined() && restored.active().size() == 1
                && restored.terminalHistory().size() == 1
                && restored.any(ids[0]).writeNbt().equals(
                    saved.getList("Active", Tag.TAG_COMPOUND).getCompound(0)),
            "migration must preserve canonical active intent byte-for-tag, without expiring or reviving it");
        CompoundTag expected = raw.copy();
        expected.remove("BlockedFrom");
        helper.assertTrue(restored.any(ids[1]).writeNbt().equals(expected)
                && raw.contains("BlockedFrom", Tag.TAG_INT),
            "only the stale terminal marker may change and the supplied snapshot must stay untouched");
        RequestLedger reloaded = RequestLedger.readNbt(restored.writeNbt(),
            helper.getLevel().registryAccess(), settlementId);
        helper.assertTrue(!reloaded.quarantined() && reloaded.active().size() == 1
                && reloaded.terminalHistory().size() == 1,
            "normalized history must round-trip through strict loading");
        CompoundTag forgedCount = saved.copy();
        forgedCount.getList("Terminal", Tag.TAG_COMPOUND).getCompound(0).putInt("Moved", 1);
        CompoundTag forgedCourier = saved.copy();
        forgedCourier.getList("Terminal", Tag.TAG_COMPOUND).getCompound(0)
            .putUUID("Courier", UUID.randomUUID());
        CompoundTag forgedTrace = saved.copy();
        ListTag transitions = forgedTrace.getList("Terminal", Tag.TAG_COMPOUND)
            .getCompound(0).getList("Transitions", Tag.TAG_COMPOUND);
        transitions.getCompound(transitions.size() - 1)
            .putInt("From", RequestState.IN_TRANSIT.wireId());
        helper.assertTrue(RequestLedger.readNbt(forgedCount,
                    helper.getLevel().registryAccess(), settlementId).quarantined()
                && RequestLedger.readNbt(forgedCourier,
                    helper.getLevel().registryAccess(), settlementId).quarantined()
                && RequestLedger.readNbt(forgedTrace,
                    helper.getLevel().registryAccess(), settlementId).quarantined(),
            "count, courier and transition forgery must still quarantine the whole ledger");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 40,
        batch = "request_ledger_authority")
    public void linkedOutlyingContainersOpenAnExactBoundedRequest(
            GameTestHelper helper) {
        Fixture fixture = fixture(helper, 4);
        fixture.settlement.radius = 1;
        helper.assertTrue(!fixture.settlement.inside(fixture.sourcePos)
                && !fixture.settlement.inside(fixture.targetPos),
            "fixture: both exact registered containers must be outside the tiny city radius");
        RequestLedgerService.Decision opened = RequestLedgerService.openOutputPickup(
            helper.getLevel(), fixture.settlement, fixture.sourceBuilding,
            fixture.sourcePos, 0, fixture.targetBuilding, fixture.targetPos,
            4, RequestPriority.HIGH);
        helper.assertTrue(opened.accepted() && opened.request() != null,
            "registered source/target buildings and loaded exact containers must authorize the "
                + "outlying bounded request without a center-radius gate");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 40,
        batch = "request_ledger_authority")
    public void legacyZeroCargoDigestExpiresButNeverRestoresAuthority(
            GameTestHelper helper) {
        UUID settlementId = UUID.randomUUID();
        UUID courierId = UUID.randomUUID();
        RequestItemFingerprint fingerprint = RequestItemFingerprint.capture(
            helper.getLevel().registryAccess(), new ItemStack(Items.OAK_LOG, 4), 4);
        RequestLedger source = new RequestLedger(settlementId);
        RequestRecord active = RequestRecord.openOutput(UUID.randomUUID(), settlementId,
            RequestPriority.NORMAL, helper.getLevel().dimension().location(),
            UUID.randomUUID(), helper.absolutePos(new BlockPos(2, 1, 2)), 0,
            UUID.randomUUID(), helper.absolutePos(new BlockPos(10, 1, 2)),
            fingerprint, 4, 0, 1L);
        helper.assertTrue(source.open(active).result() == RequestLedger.OpenResult.CREATED
                && active.reserve(courierId, 2L, 100L)
                && active.block(RequestBlocker.FINGERPRINT_MISMATCH, 3L),
            "fixture needs Wilmot's historic blocked-from-reserved zero-cargo active shape");

        // Reproduce the real 2026-09-12 ledger shape: 25 completed output
        // receipts plus one older BLOCKED -> EXPIRED receipt. They are history,
        // never fresh source authority.
        UUID[] completedIds = new UUID[25];
        for (int index = 0; index < completedIds.length; index++) {
            RequestRecord completed = RequestRecord.openOutput(UUID.randomUUID(),
                settlementId, RequestPriority.NORMAL,
                helper.getLevel().dimension().location(), UUID.randomUUID(),
                helper.absolutePos(new BlockPos(2 + index, 2, 2)), 0,
                UUID.randomUUID(), helper.absolutePos(new BlockPos(2 + index, 2, 10)),
                fingerprint, 4, index, 10L + index * 10L);
            completedIds[index] = completed.id();
            long tick = 11L + index * 10L;
            helper.assertTrue(source.open(completed).result()
                    == RequestLedger.OpenResult.CREATED
                    && completed.reserve(courierId, tick, 100L)
                    && completed.markPickup(courierId, tick + 1L)
                    && completed.markInTransit(courierId, 4, tick + 2L)
                    && completed.noteDelivered(courierId, 4, tick + 3L)
                    && completed.markSatisfied(courierId, tick + 4L)
                    && source.mutationCommitted(completed),
                "fixture completed receipt " + index + " needs one full physical trace");
        }
        RequestRecord historicalExpired = RequestRecord.openOutput(UUID.randomUUID(),
            settlementId, RequestPriority.NORMAL,
            helper.getLevel().dimension().location(), UUID.randomUUID(),
            helper.absolutePos(new BlockPos(3, 3, 2)), 0, UUID.randomUUID(),
            helper.absolutePos(new BlockPos(3, 3, 10)), fingerprint, 4, 0, 300L);
        helper.assertTrue(source.open(historicalExpired).result()
                == RequestLedger.OpenResult.CREATED
                && historicalExpired.reserve(courierId, 301L, 100L)
                && historicalExpired.block(RequestBlocker.FINGERPRINT_MISMATCH, 302L)
                && historicalExpired.expire(303L)
                && source.mutationCommitted(historicalExpired),
            "fixture needs the old zero-cargo BLOCKED -> EXPIRED trace");

        CompoundTag saved = source.writeNbt();
        CompoundTag rawActive = saved.getList("Active", Tag.TAG_COMPOUND).getCompound(0);
        putLegacyDigest(rawActive);
        ListTag rawTerminal = saved.getList("Terminal", Tag.TAG_COMPOUND);
        for (int index = 0; index < rawTerminal.size(); index++) {
            putLegacyDigest(rawTerminal.getCompound(index));
        }
        CompoundTag rawExpired = rawTerminal.getCompound(rawTerminal.size() - 1);
        rawExpired.putInt("BlockedFrom", RequestState.RESERVED.wireId());
        helper.assertTrue(rawTerminal.size() == 26
                && rawExpired.getInt("State") == RequestState.EXPIRED.wireId()
                && rawExpired.getInt("Moved") == 0
                && rawExpired.getInt("Delivered") == 0,
            "fixture must retain the exact 25 SATISFIED plus one historic EXPIRED snapshot shape");
        helper.assertTrue(RequestRecord.readNbt(rawActive,
                helper.getLevel().registryAccess(), settlementId) == null
                && RequestRecord.readNbt(rawTerminal.getCompound(0),
                    helper.getLevel().registryAccess(), settlementId) == null,
            "ordinary record decoding must reject the obsolete digest without exposing authority");

        RequestLedger restored = RequestLedger.readNbt(saved,
            helper.getLevel().registryAccess(), settlementId);
        RequestRecord expiredActive = restored.any(active.id());
        RequestRecord expiredHistory = restored.any(historicalExpired.id());
        boolean allCompleted = true;
        for (UUID id : completedIds) {
            RequestRecord row = restored.any(id);
            allCompleted &= row != null && row.state() == RequestState.SATISFIED
                && row.hasFullTransportTrace();
        }
        helper.assertTrue(!restored.quarantined() && restored.active().isEmpty()
                && restored.terminalHistory().size() == 27
                && expiredActive != null && expiredActive.state() == RequestState.EXPIRED
                && expiredActive.movedCount() == 0 && expiredActive.deliveredCount() == 0
                && expiredHistory != null && expiredHistory.state() == RequestState.EXPIRED
                && !expiredHistory.writeNbt().contains("BlockedFrom", Tag.TAG_INT)
                && allCompleted,
            "the whole historic snapshot must load as terminal history without reviving source authority");

        CompoundTag rewritten = restored.writeNbt();
        RequestLedger reloaded = RequestLedger.readNbt(rewritten,
            helper.getLevel().registryAccess(), settlementId);
        RequestRecord reloadedActive = reloaded.any(active.id());
        RequestRecord reloadedHistory = reloaded.any(historicalExpired.id());
        helper.assertTrue(!reloaded.quarantined() && reloaded.active().isEmpty()
                && reloaded.terminalHistory().size() == 27
                && reloadedActive != null && reloadedActive.state() == RequestState.EXPIRED
                && reloadedHistory != null && reloadedHistory.state() == RequestState.EXPIRED,
            "the repaired terminal history must round-trip through strict ordinary decoding");

        CompoundTag moved = saved.copy();
        moved.getList("Active", Tag.TAG_COMPOUND).getCompound(0).putInt("Moved", 1);
        CompoundTag transit = saved.copy();
        CompoundTag transitRow = transit.getList("Active", Tag.TAG_COMPOUND).getCompound(0);
        transitRow.putInt("State", RequestState.IN_TRANSIT.wireId());
        transitRow.putInt("Blocker", RequestBlocker.NONE.wireId());
        transitRow.remove("BlockedFrom");
        CompoundTag foreign = saved.copy();
        foreign.getList("Active", Tag.TAG_COMPOUND).getCompound(0)
            .putUUID("Settlement", UUID.randomUUID());
        CompoundTag forgedTerminal = saved.copy();
        CompoundTag forgedTerminalRow = forgedTerminal.getList("Terminal",
            Tag.TAG_COMPOUND).getCompound(0);
        forgedTerminalRow.putInt("Moved", 3);
        helper.assertTrue(RequestLedger.readNbt(moved,
                    helper.getLevel().registryAccess(), settlementId).quarantined()
                && RequestLedger.readNbt(transit,
                    helper.getLevel().registryAccess(), settlementId).quarantined()
                && RequestLedger.readNbt(foreign,
                    helper.getLevel().registryAccess(), settlementId).quarantined()
                && RequestLedger.readNbt(forgedTerminal,
                    helper.getLevel().registryAccess(), settlementId).quarantined(),
            "moved, in-transit, foreign, or forged terminal rows must fail closed");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 40,
        batch = "request_ledger_authority")
    public void twoCouriersYieldOneReservationWinner(GameTestHelper helper) {
        Fixture fixture = fixture(helper, 4);
        SettlerEntity first = courier(helper, fixture, new BlockPos(4, 1, 4),
            "First");
        SettlerEntity second = courier(helper, fixture, new BlockPos(5, 1, 4),
            "Second");
        RequestLedgerService.Decision opened = RequestLedgerService
            .openOutputPickup(helper.getLevel(), fixture.settlement,
                fixture.sourceBuilding, fixture.sourcePos, 0,
                fixture.targetBuilding, fixture.targetPos, 4,
                RequestPriority.HIGH);
        helper.assertTrue(opened.outcome() == RequestLedgerService.Outcome.COMMITTED
                && opened.request() != null,
            "fixture must open one exact output row");

        RequestLedgerService.Decision winner = RequestLedgerService.reserve(
            helper.getLevel(), fixture.settlement, opened.request().id(), first);
        RequestLedgerService.Decision loser = RequestLedgerService.reserve(
            helper.getLevel(), fixture.settlement, opened.request().id(), second);
        helper.assertTrue(winner.accepted(), "first same-thread claim must win");
        helper.assertTrue(!loser.accepted()
                && loser.blocker() == RequestBlocker.RESERVED_BY_OTHER,
            "second Courier must observe the unique persisted owner");
        helper.assertTrue(first.getUUID().equals(opened.request().courierId()),
            "request owner must remain the exact winning Courier");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 40,
        batch = "request_ledger_authority")
    public void campToBagToWarehouseConservesCountAndComponents(
            GameTestHelper helper) {
        // Logs weigh four budget units each. Four is one exact legal Courier
        // load (4 x 4 == 16); asking for five correctly fails BAG_FULL before
        // source mutation and would test rejection rather than conservation.
        Fixture fixture = fixture(helper, 4);
        ItemStack sourceStack = fixture.source.getItem(0);
        sourceStack.set(DataComponents.CUSTOM_NAME,
            Component.literal("Gate 6 provenance log"));
        fixture.source.setItem(0, sourceStack);
        // Container.removeItem physically splits and empties the source slot;
        // retain an immutable expectation before that transfer mutates the
        // fixture's slot-owned stack object.
        ItemStack expectedStack = sourceStack.copy();
        ItemStack existing = expectedStack.copyWithCount(2);
        fixture.target.setItem(0, existing);
        fixture.target.setChanged();
        SettlerEntity courier = courier(helper, fixture,
            new BlockPos(4, 1, 4), "Carrier");

        RequestLedgerService.Decision opened = RequestLedgerService
            .openOutputPickup(helper.getLevel(), fixture.settlement,
                fixture.sourceBuilding, fixture.sourcePos, 0,
                fixture.targetBuilding, fixture.targetPos, 4,
                RequestPriority.URGENT);
        helper.assertTrue(opened.accepted() && opened.request() != null,
            "component-bearing source must create one request");
        UUID requestId = opened.request().id();
        helper.assertTrue(RequestLedgerService.reserve(helper.getLevel(),
            fixture.settlement, requestId, courier).accepted(),
            "Courier must reserve before touching stock");
        RequestLedgerService.Decision pickup = RequestLedgerService.pickup(
            helper.getLevel(), fixture.settlement, requestId, courier);
        helper.assertTrue(pickup.accepted(), "physical source-to-bag move failed");
        helper.assertTrue(fixture.source.getItem(0).isEmpty(),
            "source slot must lose the exact four logs");
        helper.assertTrue(exactCount(courier.bag, expectedStack) == 4,
            "Courier bag must own all four exact component-bearing logs");
        helper.assertTrue(exactCount(fixture.target, expectedStack) == 2,
            "Warehouse must be unchanged before delivery");

        BlockPos target = fixture.targetPos;
        courier.teleportTo(target.getX() + 1.5D, target.getY(),
            target.getZ() + 0.5D);
        RequestLedgerService.Decision delivered = RequestLedgerService.deliver(
            helper.getLevel(), fixture.settlement, requestId, courier);
        helper.assertTrue(delivered.outcome()
                == RequestLedgerService.Outcome.SATISFIED,
            "bag-to-Warehouse transaction must reach SATISFIED");
        helper.assertTrue(exactCount(courier.bag, expectedStack) == 0,
            "satisfied cargo cannot remain copied in the bag");
        helper.assertTrue(exactCount(fixture.target, expectedStack) == 6,
            "two existing plus four delivered exact stacks must remain six");
        RequestLedger ledger = RequestLedgerSavedData.get(helper.getLevel())
            .existing(fixture.settlement.id);
        RequestRecord terminal = ledger == null ? null : ledger.any(requestId);
        helper.assertTrue(terminal != null && terminal.hasFullTransportTrace()
                && ledger.terminalHistory().contains(terminal),
            "bounded terminal history must retain the full physical trace");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 40,
        batch = "request_ledger_authority")
    public void everyStateRoundTripsAndForgedTraceQuarantines(
            GameTestHelper helper) {
        UUID settlementId = UUID.randomUUID();
        UUID courier = UUID.randomUUID();
        ItemStack stack = new ItemStack(Items.OAK_LOG, 4);
        stack.set(DataComponents.CUSTOM_NAME,
            Component.literal("Restart exact components"));
        RequestItemFingerprint fingerprint = RequestItemFingerprint.capture(
            helper.getLevel().registryAccess(), stack, 4);
        RequestRecord row = RequestRecord.openOutput(UUID.randomUUID(),
            settlementId, RequestPriority.NORMAL,
            helper.getLevel().dimension().location(), UUID.randomUUID(),
            helper.absolutePos(new BlockPos(2, 1, 2)), 0, UUID.randomUUID(),
            helper.absolutePos(new BlockPos(10, 1, 2)), fingerprint,
            4, 0, 1L);

        row = roundTrip(helper, row, settlementId, RequestState.OPEN);
        helper.assertTrue(row.reserve(courier, 2L, 100L), "reserve edge");
        row = roundTrip(helper, row, settlementId, RequestState.RESERVED);
        helper.assertTrue(row.markPickup(courier, 3L), "pickup edge");
        row = roundTrip(helper, row, settlementId, RequestState.PICKUP);
        helper.assertTrue(row.markInTransit(courier, 4, 4L), "transit edge");
        row = roundTrip(helper, row, settlementId, RequestState.IN_TRANSIT);
        helper.assertTrue(row.noteDelivered(courier, 2, 5L),
            "partial delivery edge");
        helper.assertTrue(row.block(RequestBlocker.TARGET_FULL, 5L),
            "partial target failure must persist a concrete blocker");
        row = roundTrip(helper, row, settlementId, RequestState.BLOCKED);
        helper.assertTrue(row.resume(6L), "blocked transit must resume once");
        helper.assertTrue(row.noteDelivered(courier, 2, 6L),
            "remaining delivery edge");
        row = roundTrip(helper, row, settlementId, RequestState.DELIVERED);
        helper.assertTrue(row.markSatisfied(courier, 7L), "satisfied edge");
        row = roundTrip(helper, row, settlementId, RequestState.SATISFIED);
        helper.assertTrue(row.hasFullTransportTrace(),
            "restart must preserve the complete monotone state trace");

        RequestRecord cancelled = RequestRecord.openOutput(UUID.randomUUID(),
            settlementId, RequestPriority.NORMAL,
            helper.getLevel().dimension().location(), UUID.randomUUID(),
            helper.absolutePos(new BlockPos(3, 1, 2)), 0, UUID.randomUUID(),
            helper.absolutePos(new BlockPos(11, 1, 2)), fingerprint,
            4, 0, 1L);
        helper.assertTrue(cancelled.reserve(courier, 2L, 100L)
                && cancelled.markPickup(courier, 3L)
                && cancelled.markInTransit(courier, 4, 4L)
                && cancelled.cancelAfterPhysicalReturn(courier, 5L),
            "returned physical cargo must reach CANCELLED");
        roundTrip(helper, cancelled, settlementId, RequestState.CANCELLED);

        RequestRecord expired = RequestRecord.openOutput(UUID.randomUUID(),
            settlementId, RequestPriority.NORMAL,
            helper.getLevel().dimension().location(), UUID.randomUUID(),
            helper.absolutePos(new BlockPos(4, 1, 2)), 0, UUID.randomUUID(),
            helper.absolutePos(new BlockPos(12, 1, 2)), fingerprint,
            4, 0, 1L);
        helper.assertTrue(expired.expire(2L), "unclaimed row must expire");
        roundTrip(helper, expired, settlementId, RequestState.EXPIRED);

        CompoundTag forged = row.writeNbt();
        ListTag trace = forged.getList("Transitions", Tag.TAG_COMPOUND);
        for (int i = 0; i < trace.size(); i++) {
            CompoundTag edge = trace.getCompound(i);
            if (edge.getInt("To") == RequestState.IN_TRANSIT.wireId()) {
                edge.putInt("Moved", 3);
                break;
            }
        }
        helper.assertTrue(RequestRecord.readNbt(forged,
                helper.getLevel().registryAccess(), settlementId) == null,
            "forged non-exact moved count must fail closed");
        helper.assertTrue(RequestRecord.readNbt(row.writeNbt(),
                helper.getLevel().registryAccess(), UUID.randomUUID()) == null,
            "cross-settlement decode must fail closed");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 40, batch = "request_ledger_authority")
    public void foodIntentSurvivesEverySavedState(GameTestHelper helper) {
        UUID settlement = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        UUID courier = UUID.randomUUID();
        RequestRecord food = RequestRecord.openFood(settlement,
            helper.getLevel().dimension().location(), source, new BlockPos(1, 1, 1), 0,
            new BlockPos(3, 1, 1), RequestItemFingerprint.capture(helper.getLevel().registryAccess(),
                new ItemStack(Items.BREAD, 4), 4), 12, 0, 0L);
        food = roundTrip(helper, food, settlement, RequestState.OPEN);
        helper.assertTrue(food.reserve(courier, 1L, 1200L), "FOOD reservation should be valid");
        food = roundTrip(helper, food, settlement, RequestState.RESERVED);
        helper.assertTrue(food.markPickup(courier, 2L), "FOOD pickup boundary should persist");
        food = roundTrip(helper, food, settlement, RequestState.PICKUP);
        helper.assertTrue(food.markInTransit(courier, 4, 3L), "FOOD bag ownership should persist");
        food = roundTrip(helper, food, settlement, RequestState.IN_TRANSIT);
        helper.assertTrue(food.block(RequestBlocker.TARGET_INVALID, 4L), "FOOD interruption should persist");
        food = roundTrip(helper, food, settlement, RequestState.BLOCKED);
        helper.assertTrue(food.resume(5L) && food.noteDelivered(courier, 4, 6L), "FOOD delivery should resume once");
        food = roundTrip(helper, food, settlement, RequestState.DELIVERED);
        helper.assertTrue(food.markSatisfied(courier, 7L), "FOOD terminal receipt should persist");
        food = roundTrip(helper, food, settlement, RequestState.SATISFIED);
        helper.assertTrue(food.type() == RequestType.FOOD && food.hasFullTransportTrace(),
            "FOOD must retain type and full trace under existing v1 schema");
        CompoundTag wrongTarget = food.writeNbt();
        wrongTarget.putUUID("TargetBuilding", UUID.randomUUID());
        helper.assertTrue(RequestRecord.readNbt(wrongTarget, helper.getLevel().registryAccess(), settlement) == null,
            "FOOD must reject a destination sentinel from another settlement");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 40,
        batch = "request_ledger_authority")
    public void unitPickupReloadRejectsReplayAndChangedSourceWithoutLosingCargo(
            GameTestHelper helper) {
        Fixture f = fixture(helper, 4);
        ItemStack expected = f.source.getItem(0).copy();
        expected.set(DataComponents.CUSTOM_NAME, Component.literal("Unit custody"));
        f.source.setItem(0, expected.copy());
        SettlerEntity c = courier(helper, f, new BlockPos(4, 1, 4), "Unit carrier");
        var opened = RequestLedgerService.openOutputPickup(helper.getLevel(),
            f.settlement, f.sourceBuilding, f.sourcePos, 0,
            f.targetBuilding, f.targetPos, 4, RequestPriority.HIGH);
        helper.assertTrue(opened.accepted(), "real exact source must open");
        UUID id = opened.request().id();
        helper.assertTrue(RequestLedgerService.reserve(helper.getLevel(),
            f.settlement, id, c).accepted(), "real Courier must reserve");
        // Genuine old PICKUP remains readable; old format may not claim a partial commit.
        RequestRecord legacy = RequestRecord.readNbt(opened.request().writeNbt(),
            helper.getLevel().registryAccess(), f.settlement.id);
        helper.assertTrue(legacy != null && legacy.markPickup(c.getUUID(),
            helper.getLevel().getGameTime()), "legacy pickup boundary");
        CompoundTag old = legacy.writeNbt();
        old.putInt("DataVersion", 1);
        helper.assertTrue(RequestRecord.readNbt(old,
            helper.getLevel().registryAccess(), f.settlement.id) != null,
            "genuine version1 pickup must remain readable");
        helper.assertTrue(RequestLedgerService.pickupOne(helper.getLevel(),
            f.settlement, id, c, 0).accepted(), "first actual unit");
        helper.assertTrue(exactCount(f.source, expected) == 3
            && exactCount(c.bag, expected) == 1, "one source debit, one bag credit");
        var saved = RequestLedgerSavedData.get(helper.getLevel());
        var restored = RequestLedgerSavedData.load(saved.save(new CompoundTag(),
            helper.getLevel().registryAccess()), helper.getLevel().registryAccess());
        helper.getLevel().getDataStorage().set("hearthstead_request_ledger", restored);
        CompoundTag actor = new CompoundTag();
        c.saveWithoutId(actor);
        c.load(actor);
        RequestRecord loaded = restored.existing(f.settlement.id).active(id);
        helper.assertTrue(loaded.movedCount() == 1 && loaded.fingerprint().count() == 4
            && loaded.sourceCountBefore() == 4, "partial reload keeps immutable source intent");
        CompoundTag forgedLegacy = loaded.writeNbt();
        forgedLegacy.putInt("DataVersion", 1);
        helper.assertTrue(RequestRecord.readNbt(forgedLegacy,
            helper.getLevel().registryAccess(), f.settlement.id) == null,
            "partial evidence cannot masquerade as version1");
        helper.assertTrue(RequestLedgerService.routeForCourier(helper.getLevel(),
            f.settlement, c).phase() == RequestLedgerService.RoutePhase.TO_SOURCE,
            "partial reload must return to same source");
        c.bag.addItem(expected.copyWithCount(1));
        helper.assertTrue(!RequestLedgerService.pickupOne(helper.getLevel(),
            f.settlement, id, c, 1).accepted(), "unreceipted matching bag unit must refuse");
        helper.assertTrue(exactCount(f.source, expected) == 3
            && exactCount(c.bag, expected) == 2, "extra bag cargo is not silently adopted/deleted");
        c.bag.removeItem(0, 1);
        helper.assertTrue(RequestLedgerService.routeForCourier(helper.getLevel(),
            f.settlement, c).phase() == RequestLedgerService.RoutePhase.TO_SOURCE,
            "removing test-injected extra unit restores original receipt");
        helper.assertTrue(!RequestLedgerService.pickupOne(helper.getLevel(),
            f.settlement, id, c, 0).accepted(), "replayed contact must not debit again");
        f.source.setItem(0, new ItemStack(Items.OAK_LOG, 3));
        helper.assertTrue(!RequestLedgerService.pickupOne(helper.getLevel(),
            f.settlement, id, c, 1).accepted(), "changed source components must refuse");
        helper.assertTrue(f.source.getItem(0).getCount() == 3
            && exactCount(c.bag, expected) == 1, "refusal preserves physical owners");
        f.source.setItem(0, expected.copyWithCount(3));
        helper.assertTrue(RequestLedgerService.routeForCourier(helper.getLevel(),
            f.settlement, c).phase() == RequestLedgerService.RoutePhase.TO_SOURCE,
            "restored exact source resumes existing request");
        for (int n = 1; n < 4; n++) {
            helper.assertTrue(RequestLedgerService.pickupOne(helper.getLevel(),
                f.settlement, id, c, n).accepted(), "remaining contact " + n);
            helper.assertTrue(exactCount(f.source, expected) == 3 - n
                && exactCount(c.bag, expected) == n + 1
                && exactCount(f.target, expected) == 0, "conservation at every contact");
        }
        c.teleportTo(f.targetPos.getX() + 1.5D, f.targetPos.getY(),
            f.targetPos.getZ() + 0.5D);
        for (int n = 0; n < 4; n++) {
            helper.assertTrue(RequestLedgerService.deliver(helper.getLevel(),
                f.settlement, id, c, 1).accepted(), "real unit delivery " + n);
            helper.assertTrue(exactCount(c.bag, expected) + exactCount(f.target, expected) == 4,
                "delivery must conserve all exact components");
        }
        helper.assertTrue(restored.existing(f.settlement.id).any(id).hasFullTransportTrace()
            && exactCount(f.target, expected) == 4, "one complete custody trace");
        helper.assertTrue(!RequestLedgerService.pickupOne(helper.getLevel(),
            f.settlement, id, c, 0).accepted(), "terminal receipt cannot reopen pickup");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 40,
        batch = "request_ledger_authority")
    public void staleZeroCargoOutputToReplacedWarehouseExpiresForFreshClaim(
            GameTestHelper helper) {
        Fixture f = fixture(helper, 2);
        ItemStack fineOak = f.source.getItem(0).copy();
        fineOak.set(DataComponents.CUSTOM_NAME, Component.literal("Fine oak"));
        f.source.setItem(0, fineOak);
        SettlerEntity courier = courier(helper, f, new BlockPos(4, 1, 4),
            "Stale route courier");
        RequestLedgerService.Decision opened = RequestLedgerService.openOutputPickup(
            helper.getLevel(), f.settlement, f.sourceBuilding, f.sourcePos, 0,
            f.targetBuilding, f.targetPos, 2, RequestPriority.NORMAL);
        helper.assertTrue(opened.accepted() && opened.request() != null
                && RequestLedgerService.reserve(helper.getLevel(), f.settlement,
                    opened.request().id(), courier).accepted(),
            "fixture must own one exact pre-pickup output reservation");
        UUID staleId = opened.request().id();

        // Same chest position, but a new Warehouse registration: this mirrors a
        // re-surveyed player building instead of treating the old UUID as live.
        helper.assertTrue(f.settlement.buildings.remove(f.targetBuilding),
            "fixture must retire the request's original Warehouse registration");
        Building replacement = GameTestFixtures.register(helper, f.settlement,
            BuildingType.WAREHOUSE, 9, 2);
        helper.assertTrue(!replacement.id.equals(f.targetBuilding.id),
            "replacement Warehouse must receive fresh authority");
        helper.assertTrue(Employment.hire(helper.getLevel(), f.settlement,
                replacement, courier).ok(),
            "fixture Courier must be re-employed at the replacement Warehouse");
        f.source.setItem(0, ItemStack.EMPTY);
        f.source.setChanged();

        RequestLedgerService.Route recovered = RequestLedgerService.routeForCourier(
            helper.getLevel(), f.settlement, courier);
        RequestLedger ledger = RequestLedgerSavedData.get(helper.getLevel())
            .existing(f.settlement.id);
        RequestRecord retired = ledger == null ? null : ledger.any(staleId);
        helper.assertTrue(recovered.outcome() == RequestLedgerService.Outcome.NOT_FOUND
                && recovered.blocker() == RequestBlocker.NONE
                && retired != null && retired.state() == RequestState.EXPIRED
                && ledger.active(staleId) == null && courier.bag.isEmpty(),
            "a zero-cargo route to a removed Warehouse must retire without a false full-space blocker");

        f.source.setItem(0, fineOak.copy());
        f.source.setChanged();
        RequestLedgerService.Decision fresh = RequestLedgerService.openOutputPickup(
            helper.getLevel(), f.settlement, f.sourceBuilding, f.sourcePos, 0,
            replacement, f.targetPos, 2, RequestPriority.NORMAL);
        helper.assertTrue(fresh.accepted() && fresh.request() != null
                && RequestLedgerService.reserve(helper.getLevel(), f.settlement,
                    fresh.request().id(), courier).accepted(),
            "retiring the stale row must let the real current Warehouse claim the restored output");
        helper.succeed();
    }

    /**
     * A saved source-pickup preview is not cargo. When its exact zero-cargo
     * OUTPUT row is blocked by a changed source, the ledger may retire that
     * row and Courier selection must immediately claim the newly visible
     * output instead of preserving a dead presentation forever.
     */
    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "request_ledger_authority")
    public void staleZeroCargoSourcePreviewExpiresAndClaimsFreshOutput(
            GameTestHelper helper) {
        prepareCourierWorkTime(helper);
        prepareContactPatch(helper, 3, 4);
        Fixture f = fixture(helper, 4);
        SettlerEntity courier = courier(helper, f, new BlockPos(3, 1, 4),
            "Zero cargo preview");
        courier.setPos(net.minecraft.world.phys.Vec3.atBottomCenterOf(
            helper.absolutePos(new BlockPos(3, 1, 4))));
        ItemStack original = f.source.getItem(0).copy();
        RequestLedgerService.Decision opened = RequestLedgerService.openOutputPickup(
            helper.getLevel(), f.settlement, f.sourceBuilding, f.sourcePos, 0,
            f.targetBuilding, f.targetPos, 4, RequestPriority.NORMAL);
        helper.assertTrue(opened.accepted() && opened.request() != null
                && RequestLedgerService.reserve(helper.getLevel(), f.settlement,
                    opened.request().id(), courier).accepted(),
            "fixture must create one exact zero-cargo OUTPUT reservation");
        UUID staleId = opened.request().id();
        var preview = new com.hearthstead.entity.ai.CourierSourceBagSession(courier);
        preview.begin(helper.getLevel(), opened.request());
        helper.assertTrue(preview.active(),
            "fixture must persist the pre-lift source preview at clock zero");

        ItemStack renewed = original.copy();
        renewed.set(DataComponents.CUSTOM_NAME, Component.literal("Renewed camp logs"));
        f.source.setItem(0, renewed);
        f.source.setChanged();
        helper.assertTrue(preview.tick(helper.getLevel())
                == com.hearthstead.entity.ai.CourierSourceBagSession.Result.BLOCKED,
            "changed source must block the old preview before any debit");
        RequestLedger ledger = RequestLedgerSavedData.get(helper.getLevel())
            .existing(f.settlement.id);
        helper.assertTrue(ledger != null && ledger.any(staleId) != null
                && ledger.any(staleId).state() == RequestState.BLOCKED
                && courier.bag.isEmpty(),
            "old changed-source row must remain pre-pickup with no physical cargo");

        var goal = new com.hearthstead.entity.ai.CourierWorkGoal(courier);
        helper.assertTrue(goal.canUse(),
            "expired zero-cargo preview must fall through to a fresh output claim");
        RequestRecord retired = ledger.any(staleId);
        var fresh = ledger.activeForCourier(courier.getUUID());
        helper.assertTrue(!preview.active() && retired != null
                && retired.state() == RequestState.EXPIRED
                && fresh.size() == 1 && !fresh.getFirst().id().equals(staleId)
                && fresh.getFirst().type() == RequestType.OUTPUT_PICKUP
                && fresh.getFirst().movedCount() == 0
                && fresh.getFirst().deliveredCount() == 0
                && fresh.getFirst().fingerprint().matches(helper.getLevel().registryAccess(), renewed)
                && courier.bag.isEmpty(),
            "only the expired no-cargo receipt may clear; fresh changed goods must be claimed"
                + " [old=" + retired + " fresh=" + fresh + "]");
        helper.succeed();
    }

    /** Cargo or an in-flight receipt cannot use the stale-preview escape hatch. */
    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "request_ledger_authority")
    public void sourcePreviewWithCargoOrPendingReceiptIsRetained(
            GameTestHelper helper) {
        prepareCourierWorkTime(helper);
        prepareContactPatch(helper, 3, 4);
        Fixture f = fixture(helper, 4);
        SettlerEntity courier = courier(helper, f, new BlockPos(3, 1, 4),
            "Protected preview");
        courier.setPos(net.minecraft.world.phys.Vec3.atBottomCenterOf(
            helper.absolutePos(new BlockPos(3, 1, 4))));
        RequestLedgerService.Decision opened = RequestLedgerService.openOutputPickup(
            helper.getLevel(), f.settlement, f.sourceBuilding, f.sourcePos, 0,
            f.targetBuilding, f.targetPos, 4, RequestPriority.NORMAL);
        helper.assertTrue(opened.accepted() && opened.request() != null
                && RequestLedgerService.reserve(helper.getLevel(), f.settlement,
                    opened.request().id(), courier).accepted(),
            "fixture must create one protected OUTPUT reservation");
        UUID id = opened.request().id();
        var preview = new com.hearthstead.entity.ai.CourierSourceBagSession(courier);
        preview.begin(helper.getLevel(), opened.request());
        // Make the row genuinely expiry-eligible, so each protection below
        // must reject on its own receipt/cargo guard, not a still-valid source.
        f.source.setItem(0, new ItemStack(Items.OAK_LOG, 5));
        f.source.setChanged();
        RequestLedgerService.block(helper.getLevel(), f.settlement, id, courier,
            RequestBlocker.FINGERPRINT_MISMATCH);
        courier.bag.setItem(0, new ItemStack(Items.OAK_LOG));
        helper.assertTrue(!preview.retireExpiredZeroCargoOutput(helper.getLevel())
                && preview.active(),
            "any real bag cargo must retain the saved source receipt");
        courier.bag.setItem(0, ItemStack.EMPTY);
        CompoundTag receipt = courier.getPersistentData()
            .getCompound("HearthsteadCourierSourceBag");
        receipt.putBoolean("Pending", true);
        courier.getPersistentData().put("HearthsteadCourierSourceBag", receipt);
        helper.assertTrue(!preview.retireExpiredZeroCargoOutput(helper.getLevel())
                && preview.active(),
            "an unacknowledged receipt must retain the saved source presentation");
        receipt.putBoolean("Pending", false);
        receipt.putBoolean("Ack", true);
        helper.assertTrue(!preview.retireExpiredZeroCargoOutput(helper.getLevel())
                && preview.active(),
            "an acknowledged contact cannot be discarded even if the bag is unexpectedly empty");
        receipt.putBoolean("Ack", false);
        receipt.putInt("Clock", 48);
        helper.assertTrue(!preview.retireExpiredZeroCargoOutput(helper.getLevel())
                && preview.active(),
            "contact48 without a resolved receipt must retain the otherwise-expirable request");
        receipt.putInt("Clock", 0);
        receipt.putUUID("Settlement", UUID.randomUUID());
        helper.assertTrue(!preview.retireExpiredZeroCargoOutput(helper.getLevel())
                && preview.active(),
            "a foreign-settlement receipt must never be reconciled using this worker's ledger");
        RequestLedger ledger = RequestLedgerSavedData.get(helper.getLevel())
            .existing(f.settlement.id);
        helper.assertTrue(ledger != null && ledger.active(id) != null
                && ledger.any(id).state() == RequestState.BLOCKED
                && courier.bag.isEmpty() && f.source.getItem(0).getCount() == 5
                && f.target.isEmpty(),
            "protected receipt must not expire or fabricate/drop an item");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 600,
        batch = "request_ledger_authority")
    public void courierSourceSessionKeepsFloorBagAndOneUnitContactsAcrossReload(
            GameTestHelper helper) {
        prepareCourierWorkTime(helper);
        prepareContactPatch(helper, 3, 3);
        Fixture f = fixture(helper, 4);
        SettlerEntity c = courier(helper, f, new BlockPos(3, 1, 4), "Source session");
        c.setPos(net.minecraft.world.phys.Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 1, 4))));
        c.setNoAi(true); // Tick the actual Courier goal below without a second selector owner.
        ItemStack logs = f.source.getItem(0).copy();
        var opened = RequestLedgerService.openOutputPickup(helper.getLevel(), f.settlement,
            f.sourceBuilding, f.sourcePos, 0, f.targetBuilding, f.targetPos, 4, RequestPriority.HIGH);
        helper.assertTrue(opened.accepted() && RequestLedgerService.reserve(helper.getLevel(),
            f.settlement, opened.request().id(), c).accepted(), "real source authority must reserve");
        UUID id = opened.request().id();
        var session = new com.hearthstead.entity.ai.CourierSourceBagSession(c);
        session.begin(helper.getLevel(), opened.request());
        helper.assertTrue(session.active(), "adjacent source session must begin [actor=" + c.position()
            + " source=" + f.sourcePos + " below=" + helper.getLevel().getBlockState(c.blockPosition().below())
            + " fluid=" + helper.getLevel().getFluidState(c.blockPosition())
            + " bodyClear=" + helper.getLevel().noCollision(c, c.getBoundingBox()) + "]");
        final BlockPos anchor = c.blockPosition();
        var goal = new com.hearthstead.entity.ai.CourierWorkGoal[]{
            new com.hearthstead.entity.ai.CourierWorkGoal(c)};
        helper.assertTrue(goal[0].canUse(), "saved source session must select before anonymous cargo");
        goal[0].start();
        final int[] moved = {0};
        final boolean[] reloaded = {false}, tampered = {false}, bagFull = {false};
        helper.onEachTick(() -> {
            var ledger = RequestLedgerSavedData.get(helper.getLevel()).existing(f.settlement.id);
            var row = ledger.active(id);
            var view = c.bagTransferPresentation();
            // Real source change before first contact: no phantom debit, preview removed.
            if (!tampered[0] && view.active() && view.clock() == 47) {
                f.source.setItem(0, new ItemStack(Items.COBBLESTONE, 4));
                goal[0].tick();
                helper.assertTrue(row.movedCount() == 0 && c.bag.isEmpty()
                    && !c.bagTransferPresentation().active(), "changed source must suppress unit preview");
                f.source.setItem(0, logs.copy()); tampered[0] = true;
                goal[0].stop(); goal[0] = new com.hearthstead.entity.ai.CourierWorkGoal(c);
                helper.assertTrue(goal[0].canUse(), "same request after source restoration"); goal[0].start();
                return;
            }
            if (row.movedCount() == 1 && !bagFull[0] && view.active()
                    && view.clock() == 47 && !view.committed()) {
                for (int slot = 1; slot < c.bag.getContainerSize(); slot++)
                    c.bag.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
                goal[0].tick();
                helper.assertTrue(row.movedCount() == 1 && exactCount(f.source, logs) == 3,
                    "full/mixed bag cannot cause another debit");
                for (int slot = 1; slot < c.bag.getContainerSize(); slot++) {
                    helper.assertTrue(c.bag.getItem(slot).is(Items.COBBLESTONE)
                        && c.bag.getItem(slot).getCount() == 64, "foreign bag cargo retained");
                    c.bag.setItem(slot, ItemStack.EMPTY);
                }
                bagFull[0] = true;
                goal[0].stop(); goal[0] = new com.hearthstead.entity.ai.CourierWorkGoal(c);
                helper.assertTrue(goal[0].canUse(), "same source session after bag restoration"); goal[0].start();
                return;
            }
            int before = row.movedCount();
            goal[0].tick();
            int after = row.movedCount();
            helper.assertTrue(after - before == 0 || after - before == 1, "at most one real unit per tick");
            helper.assertTrue(exactCount(f.source, logs) + exactCount(c.bag, logs) == 4
                && exactCount(f.target, logs) == 0, "source and bag alone retain all four units");
            if (after > moved[0]) {
                var contact = c.bagTransferPresentation();
                helper.assertTrue(contact.active() && contact.sourcePickup() && contact.clock() == 48
                    && contact.committed() && anchor.equals(c.placedWorkContainerPos()),
                    "unit debit shares exact stow48 and unchanged grounded bag");
                moved[0] = after;
            }
            if (after == 2 && !reloaded[0]) {
                goal[0].stop();
                CompoundTag actor = new CompoundTag(); c.saveWithoutId(actor); c.load(actor);
                var saved = RequestLedgerSavedData.get(helper.getLevel());
                var restored = RequestLedgerSavedData.load(saved.save(new CompoundTag(),
                    helper.getLevel().registryAccess()), helper.getLevel().registryAccess());
                helper.getLevel().getDataStorage().set("hearthstead_request_ledger", restored);
                helper.assertTrue(anchor.equals(c.placedWorkContainerPos()), "reload must retain floor anchor");
                goal[0] = new com.hearthstead.entity.ai.CourierWorkGoal(c);
                helper.assertTrue(goal[0].canUse(), "partial source route must recover before destination");
                goal[0].start(); reloaded[0] = true;
            }
            if (!session.active()) {
                helper.assertTrue(after == 4 && moved[0] == 4 && reloaded[0] && tampered[0] && bagFull[0]
                    && row.state() == RequestState.IN_TRANSIT && c.placedWorkContainerPos() == null,
                    "only final lift hands the complete real load to travel");
                goal[0].stop(); helper.succeed();
            }
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 500,
        batch = "request_ledger_authority")
    public void legacyWarehouseUnitMatchesPreviewAfterPhysicalBagSlotSwap(GameTestHelper helper) {
        prepareCourierWorkTime(helper);
        prepareContactPatch(helper, 10, 3);
        Fixture f = fixture(helper, 0);
        SettlerEntity c = courier(helper, f, new BlockPos(10, 1, 4), "Mixed cargo");
        c.setPos(net.minecraft.world.phys.Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(10, 1, 4))));
        c.setNoAi(true);
        ItemStack logs = new ItemStack(Items.OAK_LOG, 3);
        logs.set(DataComponents.CUSTOM_NAME, Component.literal("Projected physical logs"));
        ItemStack cobble = new ItemStack(Items.COBBLESTONE, 1);
        c.bag.setItem(0, logs.copy()); c.bag.setItem(1, cobble.copy());
        var goal = new com.hearthstead.entity.ai.CourierWorkGoal(c);
        helper.assertTrue(goal.canUse(), "real mixed cargo must select the registered Warehouse");
        goal.start();
        boolean[] swapped = {false}; int[] commits = {0}; BlockPos[] anchor = {null};
        helper.onEachTick(() -> {
            var beforeView = c.bagTransferPresentation();
            if (!swapped[0] && beforeView.active() && beforeView.clock() == 47) {
                helper.assertTrue(ItemStack.isSameItemSameComponents(beforeView.item(), logs)
                    && beforeView.item().getCount() == 1, "first projection must identify exactly one named log");
                // Move existing stacks, preserving both components and the complete actual load.
                ItemStack first = c.bag.getItem(0), second = c.bag.getItem(1);
                c.bag.setItem(0, second); c.bag.setItem(1, first); swapped[0] = true;
            }
            int beforeLogs = exactCount(f.target, logs);
            int beforeCobble = exactCount(f.target, cobble);
            goal.tick();
            int targetLogs = exactCount(f.target, logs);
            int targetCobble = exactCount(f.target, cobble);
            int delta = targetLogs + targetCobble - beforeLogs - beforeCobble;
            helper.assertTrue(delta == 0 || delta == 1, "one visible contact cannot insert a stack");
            helper.assertTrue(targetLogs + exactCount(c.bag, logs) == 3
                && targetCobble + exactCount(c.bag, cobble) == 1,
                "each original component-bearing item must retain one physical owner");
            var view = c.bagTransferPresentation();
            if (view.active()) {
                if (anchor[0] == null) anchor[0] = view.bagAnchor();
                helper.assertTrue(anchor[0].equals(view.bagAnchor()), "all units retain one planted bag anchor");
            }
            if (delta == 1) {
                helper.assertTrue(view.active() && view.clock() == 48 && view.committed(),
                    "one real insert must share acknowledged contact48");
                if (commits[0] == 0) helper.assertTrue(swapped[0] && targetLogs == 1 && targetCobble == 0,
                    "slot order change must not replace the visible log with the first cobblestone");
                commits[0]++;
            }
            if (targetLogs == 3 && targetCobble == 1 && !view.active()) {
                helper.assertTrue(commits[0] == 4 && c.bag.isEmpty()
                    && c.placedWorkContainerPos() == null, "four contacts then final physical bag lift");
                goal.stop(); helper.succeed();
            }
        });
    }

    /**
     * A matching producer may grow the reserved source stack before the Courier's
     * first physical debit. The existing route owns only its captured unit.
     */
    @GameTest(template = "empty16", timeoutTicks = 600,
        batch = "request_ledger_authority")
    public void growingSourceBeforeFirstDebitKeepsOwnedUnitAndSurplus(
            GameTestHelper helper) {
        prepareCourierWorkTime(helper);
        for (int x = 2; x <= 11; x++) for (int z = 2; z <= 5; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            helper.setBlock(new BlockPos(x, 1, z), Blocks.AIR);
            helper.setBlock(new BlockPos(x, 2, z), Blocks.AIR);
        }
        Fixture f = fixture(helper, 1);
        SettlerEntity courier = courier(helper, f, new BlockPos(3, 1, 4),
            "Growing source courier");
        courier.setPos(net.minecraft.world.phys.Vec3.atBottomCenterOf(
            helper.absolutePos(new BlockPos(3, 1, 4))));
        courier.setNoAi(true);
        ItemStack oak = f.source.getItem(0).copyWithCount(1);
        var opened = RequestLedgerService.openOutputPickup(helper.getLevel(), f.settlement,
            f.sourceBuilding, f.sourcePos, 0, f.targetBuilding, f.targetPos,
            1, RequestPriority.HIGH);
        helper.assertTrue(opened.accepted() && opened.request() != null
                && RequestLedgerService.reserve(helper.getLevel(), f.settlement,
                    opened.request().id(), courier).accepted(),
            "one real oak must be reserved by its employed Courier");
        UUID requestId = opened.request().id();
        UUID courierId = courier.getUUID();
        var preview = new com.hearthstead.entity.ai.CourierSourceBagSession(courier);
        preview.begin(helper.getLevel(), opened.request());
        var goal = new com.hearthstead.entity.ai.CourierWorkGoal[]{
            new com.hearthstead.entity.ai.CourierWorkGoal(courier)};
        helper.assertTrue(preview.active() && goal[0].canUse(),
            "saved source preview must select from the physical contact cell");
        goal[0].start();
        boolean[] grew = {false}, released = {false};
        int[] lastTarget = {0};
        helper.onEachTick(() -> {
            RequestLedger ledger = RequestLedgerSavedData.get(helper.getLevel())
                .existing(f.settlement.id);
            RequestRecord row = ledger.any(requestId);
            var view = courier.bagTransferPresentation();
            if (!grew[0] && view.active() && view.sourcePickup()
                    && view.clock() == 32 && !view.committed()) {
                f.source.setItem(0, oak.copyWithCount(2));
                f.source.setChanged();
                goal[0].tick();
                helper.assertTrue(row.state() != RequestState.BLOCKED
                        && row.movedCount() == 0 && row.deliveredCount() == 0
                        && courier.bag.isEmpty() && exactCount(f.source, oak) == 2,
                    "matching source growth must retain the original reservation without cargo fabrication");
                grew[0] = true;
                return;
            }
            if (!released[0]) goal[0].tick();
            int source = exactCount(f.source, oak);
            int bag = exactCount(courier.bag, oak);
            int target = exactCount(f.target, oak);
            int expectedTotal = grew[0] ? 2 : 1;
            helper.assertTrue(source + bag + target == expectedTotal,
                "oak must retain one physical owner in each growth phase"
                    + " [grew=" + grew[0] + " source=" + source + " bag=" + bag + " target=" + target + "]");
            helper.assertTrue(target - lastTarget[0] == 0 || target - lastTarget[0] == 1,
                "one physical contact may deliver at most one oak");
            lastTarget[0] = target;
            if (!released[0] && !preview.active()) {
                helper.assertTrue(row.state() == RequestState.IN_TRANSIT
                        && row.courierId().equals(courierId) && row.movedCount() == 1
                        && source == 1 && bag == 1 && target == 0,
                    "the reserved one moves to its owner and new output remains at source");
                goal[0].stop();
                courier.setNoAi(false);
                released[0] = true;
            }
            if (released[0] && target == 1 && bag == 0
                    && !courier.bagTransferPresentation().active()) {
                RequestRecord terminal = RequestLedgerSavedData.get(helper.getLevel())
                    .existing(f.settlement.id).any(requestId);
                helper.assertTrue(terminal != null && terminal.state() == RequestState.SATISFIED
                        && terminal.courierId().equals(courierId)
                        && terminal.movedCount() == 1 && terminal.deliveredCount() == 1
                        && exactCount(f.source, oak) == 1 && exactCount(f.target, oak) == 1,
                    "same Courier must deliver exactly the owned unit and leave surplus physical");
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "request_ledger_authority")
    public void shrinkingSourceBeforeFirstDebitStillBlocksWithoutCargo(
            GameTestHelper helper) {
        prepareContactPatch(helper, 3, 4);
        Fixture f = fixture(helper, 2);
        SettlerEntity courier = courier(helper, f, new BlockPos(3, 1, 4),
            "Shrinking source courier");
        courier.setPos(net.minecraft.world.phys.Vec3.atBottomCenterOf(
            helper.absolutePos(new BlockPos(3, 1, 4))));
        helper.assertTrue(com.hearthstead.settlement.work.ContainerApproach
                .inspect(helper.getLevel(), courier, f.sourcePos).canInteract(),
            "fixture must begin at a real physical source contact");
        var opened = RequestLedgerService.openOutputPickup(helper.getLevel(), f.settlement,
            f.sourceBuilding, f.sourcePos, 0, f.targetBuilding, f.targetPos,
            2, RequestPriority.HIGH);
        helper.assertTrue(opened.accepted() && opened.request() != null
                && RequestLedgerService.reserve(helper.getLevel(), f.settlement,
                    opened.request().id(), courier).accepted(),
            "two physical oak must be reserved before shrink");
        f.source.setItem(0, new ItemStack(Items.OAK_LOG, 1));
        f.source.setChanged();
        helper.assertTrue(!RequestLedgerService.pickupOne(helper.getLevel(), f.settlement,
                opened.request().id(), courier, 0).accepted(),
            "source shrink below its snapshot must reject the pickup as no stock");
        RequestRecord row = RequestLedgerSavedData.get(helper.getLevel())
            .existing(f.settlement.id).any(opened.request().id());
        helper.assertTrue(row != null && row.state() == RequestState.BLOCKED
                && row.blocker() == RequestBlocker.NO_STOCK
                && exactCount(f.source, new ItemStack(Items.OAK_LOG)) == 1
                && courier.bag.isEmpty() && f.target.isEmpty(),
            "shrink refusal keeps the surviving source and creates no bag or target cargo");
        helper.succeed();
    }
    /** The template floor loads at helper-relative Y1; own the actual contact cells. */
    private static void prepareContactPatch(GameTestHelper helper, int centreX, int centreZ) {
        for (int x = centreX - 1; x <= centreX + 1; x++)
            for (int z = centreZ - 1; z <= centreZ + 1; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                helper.setBlock(new BlockPos(x, 1, z), Blocks.AIR);
                helper.setBlock(new BlockPos(x, 2, z), Blocks.AIR);
            }
    }

    private static void prepareCourierWorkTime(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000L);
    }

    private static Fixture fixture(GameTestHelper helper, int logs) {
        var data = SettlementManager.data(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(),
            "Ledgerholm", helper.absolutePos(new BlockPos(7, 1, 7)));
        settlement.radius = 24;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        Building sourceBuilding = GameTestFixtures.register(helper, settlement,
            BuildingType.LUMBER_CAMP, 2, 2);
        Building targetBuilding = GameTestFixtures.register(helper, settlement,
            BuildingType.WAREHOUSE, 9, 2);
        BlockPos sourceRel = new BlockPos(3, 1, 3);
        BlockPos targetRel = new BlockPos(10, 1, 3);
        helper.setBlock(sourceRel, Blocks.CHEST);
        helper.setBlock(targetRel, Blocks.CHEST);
        Container source = container(helper, helper.absolutePos(sourceRel));
        Container target = container(helper, helper.absolutePos(targetRel));
        helper.assertTrue(source != null && target != null,
            "fixture needs two real chest inventories");
        source.setItem(0, new ItemStack(Items.OAK_LOG, logs));
        source.setChanged();
        return new Fixture(settlement, sourceBuilding, targetBuilding,
            helper.absolutePos(sourceRel), helper.absolutePos(targetRel),
            source, target);
    }

    private static SettlerEntity courier(GameTestHelper helper, Fixture fixture,
                                          BlockPos relative, String name) {
        SettlerEntity courier = helper.spawn(ModEntities.SETTLER.get(), relative);
        courier.setSettlerName(name);
        courier.bindTo(fixture.settlement.id, fixture.settlement.center);
        fixture.settlement.putRecord(courier.getUUID(), name, Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), fixture.settlement,
                fixture.targetBuilding, courier).ok(),
            "fixture Warehouse must hire the Courier through production authority");
        return courier;
    }

    private static void putLegacyDigest(CompoundTag row) {
        row.getCompound("Fingerprint").putString("Digest",
            "67f3c9e52d7cf4343cdaf999d366139d0423a148a62223ad2cea955b56ff5f49");
    }

    private static RequestRecord roundTrip(GameTestHelper helper,
                                           RequestRecord row,
                                           UUID settlement,
                                           RequestState expected) {
        RequestRecord restored = RequestRecord.readNbt(row.writeNbt(),
            helper.getLevel().registryAccess(), settlement);
        helper.assertTrue(restored != null && restored.state() == expected,
            "restart must preserve " + expected + " exactly");
        return restored;
    }

    private static Container container(GameTestHelper helper, BlockPos absolute) {
        BlockEntity blockEntity = helper.getLevel().getBlockEntity(absolute);
        return blockEntity instanceof Container value ? value : null;
    }

    private static int exactCount(Container container, ItemStack expected) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (ItemStack.isSameItemSameComponents(stack, expected)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private record Fixture(Settlement settlement, Building sourceBuilding,
                           Building targetBuilding, BlockPos sourcePos,
                           BlockPos targetPos, Container source,
                           Container target) {
    }
}
