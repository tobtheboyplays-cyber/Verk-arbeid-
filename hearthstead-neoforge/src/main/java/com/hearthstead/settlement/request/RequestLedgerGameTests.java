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
        ItemStack existing = sourceStack.copyWithCount(2);
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
        helper.assertTrue(exactCount(courier.bag, sourceStack) == 4,
            "Courier bag must own all four exact component-bearing logs");
        helper.assertTrue(exactCount(fixture.target, sourceStack) == 2,
            "Warehouse must be unchanged before delivery");

        BlockPos target = fixture.targetPos;
        courier.teleportTo(target.getX() + 1.5D, target.getY(),
            target.getZ() + 0.5D);
        RequestLedgerService.Decision delivered = RequestLedgerService.deliver(
            helper.getLevel(), fixture.settlement, requestId, courier);
        helper.assertTrue(delivered.outcome()
                == RequestLedgerService.Outcome.SATISFIED,
            "bag-to-Warehouse transaction must reach SATISFIED");
        helper.assertTrue(exactCount(courier.bag, sourceStack) == 0,
            "satisfied cargo cannot remain copied in the bag");
        helper.assertTrue(exactCount(fixture.target, sourceStack) == 6,
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
