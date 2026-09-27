package com.hearthstead.settlement.request;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.CourierSourceBagSession;
import com.hearthstead.entity.ai.CourierWorkGoal;
import com.hearthstead.gametest.GameTestFixtures;
import com.hearthstead.logistics.Weight;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Focused saved MATERIAL_INPUT preview regression; not a native Courier replay. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class CourierStaleSourceSessionGameTests {
    private static final String KEY = "HearthsteadCourierSourceBag";
    private record Fixture(Settlement settlement, Building warehouse, Building hut,
                           BlockPos sourcePos, BlockPos targetPos, Container source, Container target,
                           SettlerEntity courier, RequestRecord request, CourierSourceBagSession session) {}

    @GameTest(template = "empty16", timeoutTicks = 80, batch = "courier_stale_source_session")
    public void staleMaterialPreviewExpiresAndSelectsNewWork(GameTestHelper h) {
        Fixture f = fixture(h);
        f.source.setItem(1, new ItemStack(Items.COBBLESTONE, 4));
        int requested = 2;
        h.assertTrue(requested <= Weight.perLoad(new ItemStack(Items.COBBLESTONE), f.courier.getCarryCapacity())
            && Weight.of(new ItemStack(Items.COBBLESTONE), requested)
                <= Weight.budgetFor(f.courier.getCarryCapacity()), "the fresh parcel fits the real carry and weight limits");
        var fresh = RequestLedgerService.openBuilderMaterial(h.getLevel(), f.settlement,
            f.warehouse, f.sourcePos, 1, f.hut, f.targetPos, new ItemStack(Items.COBBLESTONE), requested);
        h.assertTrue(fresh.accepted() && fresh.request() != null
            && fresh.request().fingerprint().count() == requested, "a second valid two-item material row is queued");
        stale(h, f);
        // Recreate the volatile wrappers; the source preview is recovered from entity NBT.
        CourierSourceBagSession restored = new CourierSourceBagSession(f.courier);
        h.assertTrue(restored.active(), "saved preview survives wrapper recreation");
        boolean selected = new CourierWorkGoal(f.courier).canUse();
        if (!selected) {
            Hearthstead.LOGGER.error("COURIER_STALE_SESSION selection failed phase={} capacity={} session={} old={} fresh={}",
                f.courier.dayPhase(), f.courier.getCarryCapacity(), f.courier.getPersistentData().getCompound(KEY),
                ledger(h, f).any(f.request.id()).writeNbt(), ledger(h, f).any(fresh.request().id()).writeNbt());
        }
        h.assertTrue(selected, "real goal selection finds current work; see COURIER_STALE_SESSION log");
        RequestLedger ledger = ledger(h, f);
        var owned = ledger.activeForCourier(f.courier.getUUID());
        h.assertTrue(!restored.active() && !f.courier.getPersistentData().contains(KEY)
            && ledger.active(f.request.id()) == null
            && ledger.any(f.request.id()).state() == RequestState.EXPIRED,
            "only authoritative expiry removes the stale material preview");
        h.assertTrue(owned.size() == 1 && owned.getFirst().id().equals(fresh.request().id())
            && owned.getFirst().state() == RequestState.RESERVED,
            "the newer actual row is selected instead of renewing the obsolete one");
        h.assertTrue(f.courier.bag.isEmpty() && f.target.isEmpty()
            && f.source.getItem(0).getCount() == 3 && f.source.getItem(1).getCount() == 4
            && f.request.movedCount() == 0 && f.request.deliveredCount() == 0,
            "retirement and selection move, refund and fabricate no material");
        h.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 80, batch = "courier_stale_source_session")
    public void pendingAndAcknowledgedMaterialReceiptsStayProtected(GameTestHelper h) {
        Fixture f = fixture(h);
        stale(h, f);
        CompoundTag receipt = f.courier.getPersistentData().getCompound(KEY);
        receipt.putBoolean("Pending", true);
        assertProtected(h, f, "unresolved pending contact");
        receipt.putBoolean("Pending", false);
        receipt.putBoolean("Ack", true);
        assertProtected(h, f, "acknowledged contact with an unexpectedly empty bag");
        receipt.putBoolean("Ack", false);
        receipt.putInt("Clock", 48);
        assertProtected(h, f, "contact boundary without a resolved receipt");
        receipt.putInt("Clock", 24);
        h.assertTrue(f.session.retireExpiredZeroCargoOutput(h.getLevel()) && !f.session.active()
            && ledger(h, f).any(f.request.id()).state() == RequestState.EXPIRED,
            "removing only the protection makes this same stale material row eligible");
        h.assertTrue(f.courier.bag.isEmpty() && f.target.isEmpty()
            && f.source.getItem(0).getCount() == 3, "receipt checks never mutate inventory");
        h.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 80, batch = "courier_stale_source_session")
    public void partiallyLiftedMaterialKeepsItsCustody(GameTestHelper h) {
        Fixture f = fixture(h);
        h.assertTrue(RequestLedgerService.pickupOne(h.getLevel(), f.settlement,
            f.request.id(), f.courier, 0).accepted(), "one real unit crosses source into Courier custody");
        h.assertTrue(f.request.movedCount() == 1 && f.source.getItem(0).getCount() == 3
            && f.courier.bag.countItem(Items.DIRT) == 1, "partial parcel is physically conserved");
        f.source.setItem(0, new ItemStack(Items.COBBLESTONE, 3));
        f.source.setChanged();
        RequestLedgerService.block(h.getLevel(), f.settlement, f.request.id(), f.courier,
            RequestBlocker.FINGERPRINT_MISMATCH);
        // Deliberately leave the preview unacknowledged, as after a crash between receipt writes.
        assertProtected(h, f, "actual partially lifted parcel");
        h.assertTrue(f.request.effectiveState() == RequestState.PICKUP && f.request.movedCount() == 1
            && f.request.deliveredCount() == 0 && f.courier.bag.countItem(Items.DIRT) == 1
            && f.source.getItem(0).is(Items.COBBLESTONE) && f.source.getItem(0).getCount() == 3
            && f.target.isEmpty(), "the saved parcel and its real cargo remain untouched");
        h.succeed();
    }

    private static void assertProtected(GameTestHelper h, Fixture f, String reason) {
        CompoundTag before = f.courier.getPersistentData().getCompound(KEY).copy();
        long revision = f.request.revision();
        h.assertTrue(!f.session.retireExpiredZeroCargoOutput(h.getLevel()) && f.session.active()
            && before.equals(f.courier.getPersistentData().getCompound(KEY))
            && ledger(h, f).active(f.request.id()) != null && f.request.revision() == revision,
            reason + " must retain both session receipt and ledger authority");
    }

    private static void stale(GameTestHelper h, Fixture f) {
        f.source.setItem(0, new ItemStack(Items.DIRT, 3));
        f.source.setChanged();
        h.assertTrue(f.session.tick(h.getLevel()) == CourierSourceBagSession.Result.BLOCKED
            && f.request.state() == RequestState.BLOCKED
            && f.request.blockedFrom() == RequestState.RESERVED
            && f.request.blocker() == RequestBlocker.FINGERPRINT_MISMATCH
            && f.courier.bag.isEmpty(), "real preview detects source shrink before any pickup");
    }

    private static RequestLedger ledger(GameTestHelper h, Fixture f) {
        return RequestLedgerSavedData.get(h.getLevel()).existing(f.settlement.id);
    }

    private static Fixture fixture(GameTestHelper h) {
        h.getLevel().setDayTime(2000L);
        for (int x = 2; x <= 4; x++) for (int z = 3; z <= 5; z++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            h.setBlock(new BlockPos(x, 1, z), Blocks.AIR);
            h.setBlock(new BlockPos(x, 2, z), Blocks.AIR);
        }
        Settlement s = new Settlement(UUID.randomUUID(), "Stale parcel", h.absolutePos(new BlockPos(7, 1, 7)));
        s.radius = 24;
        var towns = SettlementManager.data(h.getLevel());
        towns.settlements.put(s.id, s);
        towns.setDirty();
        h.setBlock(new BlockPos(7, 1, 7), ModBlocks.HEARTH.get());
        ((HearthBlockEntity) h.getLevel().getBlockEntity(s.center)).bindSettlement(s.id);
        Building warehouse = GameTestFixtures.register(h, s, BuildingType.WAREHOUSE, 2, 2);
        Building hut = GameTestFixtures.register(h, s, BuildingType.BUILDERS_HUT, 9, 2);
        BlockPos sourcePos = h.absolutePos(new BlockPos(3, 1, 3));
        BlockPos targetPos = h.absolutePos(new BlockPos(10, 1, 3));
        h.setBlock(new BlockPos(3, 1, 3), Blocks.CHEST);
        h.setBlock(new BlockPos(10, 1, 3), Blocks.CHEST);
        Container source = (Container) h.getLevel().getBlockEntity(sourcePos);
        Container target = (Container) h.getLevel().getBlockEntity(targetPos);
        source.setItem(0, new ItemStack(Items.DIRT, 4));
        source.setChanged();
        SettlerEntity courier = h.spawn(ModEntities.SETTLER.get(), new BlockPos(3, 1, 4));
        courier.setNoAi(true);
        courier.setPos(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(3, 1, 4))));
        courier.bindTo(s.id, s.center);
        s.putRecord(courier.getUUID(), "Parcel Courier", Profession.NONE);
        h.assertTrue(Employment.hire(h.getLevel(), s, warehouse, courier).ok(), "real Warehouse hires Courier");
        // Material endpoints require a live Builder employed by this exact hut.
        SettlerEntity builder = h.spawn(ModEntities.SETTLER.get(), new BlockPos(11, 1, 4));
        builder.setNoAi(true); // Endpoint authority only; it must not consume the test parcel.
        builder.bindTo(s.id, s.center);
        s.putRecord(builder.getUUID(), "Parcel Builder", Profession.NONE);
        h.assertTrue(Employment.hire(h.getLevel(), s, hut, builder).ok()
            && builder.getProfession() == Profession.BUILDER
            && Employment.employerOf(s, builder.getUUID()) == hut,
            "target hut has its own actual employed Builder");
        var opened = RequestLedgerService.openBuilderMaterial(h.getLevel(), s, warehouse, sourcePos,
            0, hut, targetPos, new ItemStack(Items.DIRT), 4);
        h.assertTrue(opened.accepted() && opened.request() != null
            && RequestLedgerService.reserve(h.getLevel(), s, opened.request().id(), courier).accepted(),
            "real material input is reserved");
        CourierSourceBagSession session = new CourierSourceBagSession(courier);
        session.begin(h.getLevel(), opened.request());
        h.assertTrue(session.active() && courier.bag.isEmpty(), "real zero-cargo source preview starts");
        return new Fixture(s, warehouse, hut, sourcePos, targetPos, source, target, courier, opened.request(), session);
    }
}
