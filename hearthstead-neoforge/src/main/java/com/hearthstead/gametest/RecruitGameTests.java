package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.storage.ServerLevelData;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

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

    private static void floor(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
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

    // ------------------------------------------------------------ (a) ---

    /**
     * The whole payable loop, end to end: a guest waiting at the tavern, a
     * hearth that can afford them, and exactly the price gone afterwards —
     * chest truth (INV-3), never a silent extra charge and never a discount.
     */
    @GameTest(batch = "recruit", template = "empty16", timeoutTicks = 200)
    public void aPayableGuestJoinsAndThePriceIsExact(GameTestHelper helper) {
        floor(helper, 16);
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

        helper.assertTrue(countInHearth(hearth, Items.BREAD) == 8,
            "the bread price must leave exactly eight reserve meals, found "
                + countInHearth(hearth, Items.BREAD));
        helper.assertTrue(countInHearth(hearth, Items.OAK_PLANKS) == 0,
            "the planks price must be gone, found " + countInHearth(hearth, Items.OAK_PLANKS));
        helper.assertTrue(countInHearth(hearth, Items.IRON_INGOT) == 5,
            "only the price may be spent -- the unrelated good must be untouched, found "
                + countInHearth(hearth, Items.IRON_INGOT));
        helper.succeed();
    }

    // ------------------------------------------------------------ (b) ---

    /**
     * A settlement that becomes unable to pay after a real Tavern arrival
     * does not get a free settler. Explicit admission is refused, the guest
     * waits out the persisted patience window, and the physical candidate
     * leaves without mutating membership or unrelated Hearth goods.
     */
    @GameTest(batch = "recruit", template = "empty16", timeoutTicks = 200)
    public void anUnpayableGuestWalksAwayInsteadOfJoining(GameTestHelper helper) {
        floor(helper, 16);
        ServerLevel level = helper.getLevel();
        BlockPos hearthRel = new BlockPos(8, 1, 8);
        BlockPos tavernRel = new BlockPos(10, 1, 10);
        Settlement s = settlement(helper, hearthRel);
        building(helper, s, BuildingType.TAVERN, tavernRel.getX(), tavernRel.getZ());
        HearthBlockEntity hearth = (HearthBlockEntity) level
            .getBlockEntity(helper.absolutePos(hearthRel));
        // Fund the real arrival first, then remove the price while the exact
        // candidate is waiting. Attraction itself correctly refuses an
        // unfunded settlement.
        hearth.insertGoods(new ItemStack(Items.BREAD, 12));
        hearth.insertGoods(new ItemStack(Items.OAK_PLANKS, 8));
        hearth.insertGoods(new ItemStack(Items.IRON_INGOT, 5));

        SettlerEntity guest = waitingTraveler(helper, s, "Uheldig");
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            ItemStack stack = hearth.getInventory().getStackInSlot(slot);
            if (stack.is(Items.BREAD) || stack.is(Items.OAK_PLANKS)) {
                hearth.getInventory().setStackInSlot(slot, ItemStack.EMPTY);
            }
        }
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        helper.assertTrue(SettlementManager.admitWaitingTraveler(player, s,
                guest.getUUID(), s.recruitment.revision())
                == SettlementManager.AdmissionResult.BLOCKED_POLICY,
            "an unpayable waiting guest must never receive a free admission");

        long originalGameTime = level.getGameTime();
        long expiredGameTime = SettlementManager.candidatePatienceUntil(level, s) + 1L;
        ServerLevelData clock = (ServerLevelData) level.getLevelData();
        try {
            clock.setGameTime(expiredGameTime);
            SettlementManager.tickRecruitment(level, s);
        } finally {
            clock.setGameTime(originalGameTime);
        }
        SettlementManager.tickRecruitment(level, s); // LEFT -> next cycle

        helper.assertFalse(guest.isAlive(),
            "an unpayable guest must eventually walk away, not linger forever");
        helper.assertTrue(s.travelerId == null, "the settlement stops waiting on them");
        helper.assertTrue(s.record(guest.getUUID()) == null,
            "they must never be added to the roster without paying");
        helper.assertTrue(countInHearth(hearth, Items.IRON_INGOT) == 5,
            "an unpaid joining must not touch the hearth at all, found "
                + countInHearth(hearth, Items.IRON_INGOT));
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
    @GameTest(batch = "recruit", template = "empty16", timeoutTicks = 200)
    public void anInnkeeperDiscountDoesNotCompressTheRecruitClock(GameTestHelper helper) {
        floor(helper, 16);
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
    @GameTest(batch = "recruit", template = "empty16", timeoutTicks = 200)
    public void noTavernMeansTheGaugeNeverFills(GameTestHelper helper) {
        floor(helper, 16);
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
    @GameTest(batch = "recruit", template = "empty16", timeoutTicks = 200)
    public void aValidTavernReopensTheGate(GameTestHelper helper) {
        floor(helper, 16);
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
    @GameTest(batch = "recruit", template = "empty16", timeoutTicks = 200)
    public void aWaitingGuestSurvivesTavernInvalidation(GameTestHelper helper) {
        floor(helper, 16);
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
        helper.assertTrue(countInHearth(hearth, Items.BREAD) == 8,
            "the price must leave the required reserve, found "
                + countInHearth(hearth, Items.BREAD));
        helper.assertTrue(countInHearth(hearth, Items.OAK_PLANKS) == 0,
            "the exact planks price must be gone, found " + countInHearth(hearth, Items.OAK_PLANKS));
        helper.succeed();
    }
}
