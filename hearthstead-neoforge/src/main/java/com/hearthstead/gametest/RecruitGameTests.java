package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * SLICE RECRUIT-1 — the A2 recruiting chain: a tavern draws travelers in,
 * they wait as guests rather than joining on the spot, and joining costs the
 * settlement a real price paid out of the hearth's own stores.
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
        return GameTestFixtures.register(helper, s, type, x, z);
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

    /** A traveler already on record as waiting, spawned at {@code rel}. */
    private static SettlerEntity waitingTraveler(GameTestHelper helper, Settlement s,
                                                 String name, BlockPos rel) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), rel);
        settler.setSettlerName(name);
        settler.markTraveler(s.id, s.center);
        s.travelerId = settler.getUUID();
        s.travelerSinceGameTime = helper.getLevel().getGameTime();
        return settler;
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
        // Exactly the price, plus an unrelated good that must survive
        // untouched -- proof this charges the price and nothing else.
        hearth.insertGoods(new ItemStack(Items.BREAD, 4));
        hearth.insertGoods(new ItemStack(Items.OAK_PLANKS, 8));
        hearth.insertGoods(new ItemStack(Items.IRON_INGOT, 5));

        // Spawned right at the tavern's anchor: this test is about payment,
        // not pathing -- TravelerJoinGoal (untested here) owns getting them
        // there for real.
        SettlerEntity guest = waitingTraveler(helper, s, "Gjest", tavernRel);
        helper.assertTrue(guest.isTraveler(), "sanity: starts as a traveler, not a settler");

        SettlementManager.tickRecruitment(level, s);

        helper.assertFalse(guest.isTraveler(),
            "a guest the settlement can pay for must join");
        helper.assertTrue(guest.isBound(), "...and become a bound settler");
        helper.assertTrue(s.record(guest.getUUID()) != null,
            "the settlement roster must gain them");
        helper.assertTrue(s.travelerId == null, "no guest is left waiting after they join");

        helper.assertTrue(countInHearth(hearth, Items.BREAD) == 0,
            "the bread price must be gone, found " + countInHearth(hearth, Items.BREAD));
        helper.assertTrue(countInHearth(hearth, Items.OAK_PLANKS) == 0,
            "the planks price must be gone, found " + countInHearth(hearth, Items.OAK_PLANKS));
        helper.assertTrue(countInHearth(hearth, Items.IRON_INGOT) == 5,
            "only the price may be spent -- the unrelated good must be untouched, found "
                + countInHearth(hearth, Items.IRON_INGOT));
        helper.succeed();
    }

    // ------------------------------------------------------------ (b) ---

    /**
     * A settlement that cannot pay does not get a free settler -- the guest
     * waits out their patience and walks away instead. No tavern this time:
     * the hearth itself is the fallback waiting spot the design calls for.
     */
    @GameTest(batch = "recruit", template = "empty16", timeoutTicks = 200)
    public void anUnpayableGuestWalksAwayInsteadOfJoining(GameTestHelper helper) {
        floor(helper, 16);
        ServerLevel level = helper.getLevel();
        BlockPos hearthRel = new BlockPos(8, 1, 8);
        Settlement s = settlement(helper, hearthRel);
        HearthBlockEntity hearth = (HearthBlockEntity) level
            .getBlockEntity(helper.absolutePos(hearthRel));
        // An unrelated good only, and none of the price -- an empty larder
        // would be too easy a case to get right by accident.
        hearth.insertGoods(new ItemStack(Items.IRON_INGOT, 5));

        // Beside the hearth rather than inside its block space -- still well
        // within the arrival radius the hearth fallback checks against.
        SettlerEntity guest = waitingTraveler(helper, s, "Uheldig",
            hearthRel.offset(1, 0, 0));
        // Patience is measured in game days; simulate it having already run
        // out rather than ticking a GameTest through 2.5 of them.
        s.travelerSinceGameTime = level.getGameTime() - 100_000L;

        SettlementManager.tickRecruitment(level, s);

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
     * The tavern matters, and an innkeeper on shift matters more: both raise
     * the SAME recruit gauge rather than adding a second, hidden one.
     */
    @GameTest(batch = "recruit", template = "empty16", timeoutTicks = 200)
    public void aTavernAndItsInnkeeperAccelerateTheRecruitGauge(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // No physical settlements share this template's small footprint, so
        // every one of these three stays deliberately unregistered in
        // SettlementSavedData -- this test only ever calls tickRecruitment
        // directly on the object it holds, and skipping registration is one
        // less way an ad-hoc test settlement could answer for a real one
        // (see settlement()'s own radius comment for the failure this avoids).

        Settlement bare = new Settlement(UUID.randomUUID(), "Uten Vertshus",
            helper.absolutePos(new BlockPos(2, 1, 2)));
        bare.foodCache = 10;
        bare.moraleCache = 60;
        SettlementManager.tickRecruitment(level, bare);

        Settlement withTavern = new Settlement(UUID.randomUUID(), "Med Vertshus",
            helper.absolutePos(new BlockPos(2, 1, 6)));
        withTavern.foodCache = 10;
        withTavern.moraleCache = 60;
        building(helper, withTavern, BuildingType.TAVERN, 4, 6);
        SettlementManager.tickRecruitment(level, withTavern);

        helper.assertTrue(withTavern.recruitProgress > bare.recruitProgress,
            "a tavern must speed up recruiting: no tavern got "
                + bare.recruitProgress + ", with a tavern got " + withTavern.recruitProgress);

        Settlement withInnkeeper = new Settlement(UUID.randomUUID(), "Med Vert",
            helper.absolutePos(new BlockPos(2, 1, 10)));
        withInnkeeper.foodCache = 10;
        withInnkeeper.moraleCache = 60;
        Building innTavern = building(helper, withInnkeeper, BuildingType.TAVERN, 4, 10);
        SettlerEntity keeper = settler(helper, withInnkeeper, "Kroverten", 6, 10);
        helper.assertTrue(Employment.hire(level, withInnkeeper, innTavern, keeper).ok(),
            "a tavern must be able to take an innkeeper");
        helper.assertTrue(keeper.getProfession() == Profession.INNKEEPER,
            "hired into a tavern, they keep it");
        SettlementManager.tickRecruitment(level, withInnkeeper);

        helper.assertTrue(withInnkeeper.recruitProgress > withTavern.recruitProgress,
            "hospitality must raise the bonus further still: tavern alone got "
                + withTavern.recruitProgress + ", with an innkeeper got "
                + withInnkeeper.recruitProgress);
        helper.succeed();
    }
}
