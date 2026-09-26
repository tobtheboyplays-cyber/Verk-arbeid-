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
import com.hearthstead.settlement.Costs;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Mayor;
import com.hearthstead.settlement.RecruitmentTransaction;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * COSTS-1 — proves {@code docs/project/COSTS.md}'s pricing constitution is
 * real code, not fiction: {@link Costs}'s recruit price, its named discount
 * hooks (an employed innkeeper, a valid dining hall), the additive -50% cap,
 * the never-below-1 floor, and the tag-aware planks line all still hold
 * after the price moved out of {@code SettlementManager} and into the one
 * table {@link Costs} is.
 *
 * <p>Same shape {@code RecruitGameTests} uses throughout: the manager layer
 * ({@link SettlementManager#tickRecruitment}) is deterministic and callable
 * directly, so these tests never wait out a guest's real, multi-day
 * patience — and the pure discount MATH ({@link Costs#afterDiscounts}) is
 * tested directly where a real building isn't needed to prove the point.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class CostsGameTests {

    private static void floor(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
        // Keep every accepted exterior route inside this owned test arena.
        // The separate long-route fixture is tracked at y+40, above this wall.
        for (int edge = 0; edge < size; edge++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(edge, y, 0), Blocks.STONE_BRICKS);
                helper.setBlock(new BlockPos(edge, y, size - 1), Blocks.STONE_BRICKS);
                helper.setBlock(new BlockPos(0, y, edge), Blocks.STONE_BRICKS);
                helper.setBlock(new BlockPos(size - 1, y, edge), Blocks.STONE_BRICKS);
            }
        }
    }

    /** A settlement bound to a real hearth block, the same shape RecruitGameTests uses —
     *  COSTS-1's price is paid out of that block's own inventory. */
    private static Settlement settlement(GameTestHelper helper, BlockPos centerRel) {
        helper.setBlock(centerRel, ModBlocks.HEARTH.get());
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.values().removeIf(old -> helper.getBounds().contains(
            old.center.getX() + 0.5D, old.center.getY() + 0.5D,
            old.center.getZ() + 0.5D));
        Settlement s = new Settlement(UUID.randomUUID(), "Prisgranskning",
            helper.absolutePos(centerRel));
        // Small on purpose (EmploymentGameTests' settlement() explains why):
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

    /** Controlled payment stock derived from the real already-frozen guest quote. */
    private static void stockQuotedAdmission(GameTestHelper helper, Settlement settlement,
                                               HearthBlockEntity hearth, Item planks) {
        admissionBed(helper, settlement);
        var quote = settlement.recruitment.quote();
        helper.assertTrue(quote != null && !quote.legacyPending(),
            "the real spawned guest must own a frozen quote before stock is prepared");
        if (quote.version() == 2) {
            int reserve = com.hearthstead.settlement.RecruitmentPolicy.requiredReserve(settlement.population() + 1);
            int missingMeals = Math.max(0, reserve - countInHearth(hearth, Items.BREAD));
            if (missingMeals > 0) helper.assertTrue(hearth.insertGoods(new ItemStack(Items.BREAD, missingMeals)).isEmpty(), "seed missing reserve meals");
            int missingCoins = quote.coins() - countInHearth(hearth, com.hearthstead.registry.ModItems.GOLD_COIN.get());
            helper.assertTrue(missingCoins >= 0, "controlled fixture must not contain excess payment coins");
            if (missingCoins > 0) helper.assertTrue(hearth.insertGoods(new ItemStack(com.hearthstead.registry.ModItems.GOLD_COIN.get(), missingCoins)).isEmpty(), "stock actual frozen coins");
            helper.assertTrue(countInHearth(hearth, com.hearthstead.registry.ModItems.GOLD_COIN.get()) == quote.coins(), "exact v2 coins are funded; unrelated goods are retained");
            return;
        }
        int breadTarget = quote.bread()
            + com.hearthstead.settlement.RecruitmentPolicy.requiredReserve(settlement.population() + 1);
        int breadMissing = breadTarget - countInHearth(hearth, Items.BREAD);
        int planksMissing = quote.planks() - countInHearth(hearth, planks);
        // Fixture setup only: qualification uses the base price before the
        // guest exists; a frozen discount can leave excess seeded payment goods.
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            ItemStack existing = hearth.getInventory().getStackInSlot(slot);
            if (breadMissing < 0 && existing.is(Items.BREAD)) {
                breadMissing += hearth.getInventory().extractItem(slot, -breadMissing, false).getCount();
            } else if (planksMissing < 0 && existing.is(planks)) {
                planksMissing += hearth.getInventory().extractItem(slot, -planksMissing, false).getCount();
            }
        }
        if (breadMissing > 0) hearth.insertGoods(new ItemStack(Items.BREAD, breadMissing));
        if (planksMissing > 0) hearth.insertGoods(new ItemStack(planks, planksMissing));
        helper.assertTrue(countInHearth(hearth, Items.BREAD) == breadTarget
                && countInHearth(hearth, planks) == quote.planks(),
            "fixture must hold exactly the frozen price plus the unchanged meal reserve");
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
     * No discount buildings, no discount: {@link SettlementManager#recruitPrice}
     * must equal {@link Costs#recruit()} exactly, and a guest joining pays
     * the full amount with exact conservation of everything else in the
     * hearth -- the same base case {@code RecruitGameTests} already covers,
     * re-proven here through {@link Costs} so the refactor is verified, not
     * assumed.
     */
    @GameTest(batch = "costs", template = "empty32", timeoutTicks = 200)
    public void noDiscountBuildingsMeansFullPriceCharged(GameTestHelper helper) {
        floor(helper, 32);
        ServerLevel level = helper.getLevel();
        BlockPos hearthRel = new BlockPos(6, 1, 6);
        Settlement s = settlement(helper, hearthRel);
        building(helper, s, BuildingType.TAVERN, 10, 10);

        List<Costs.Discount> discounts = SettlementManager.recruitDiscounts(level, s);
        helper.assertTrue(discounts.isEmpty(),
            "an undecorated settlement must earn no discount, found " + discounts.size());

        Costs.Price price = SettlementManager.recruitPrice(level, s);
        helper.assertTrue(price.lines().size() == 1 && price.lines().get(0).exact() == com.hearthstead.registry.ModItems.GOLD_COIN.get()
                && price.lines().get(0).count() == 4, "new guest forecast must be exactly four Coins");

        HearthBlockEntity hearth = (HearthBlockEntity) level
            .getBlockEntity(helper.absolutePos(hearthRel));
        hearth.insertGoods(new ItemStack(Items.BREAD, 12));
        hearth.insertGoods(new ItemStack(Items.OAK_PLANKS, 8));
        hearth.insertGoods(new ItemStack(Items.IRON_INGOT, 5));

        SettlerEntity guest = waitingTraveler(helper, s, "Gjest");
        stockQuotedAdmission(helper, s, hearth, Items.OAK_PLANKS);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        helper.assertTrue(SettlementManager.admitWaitingTraveler(player, s,
                guest.getUUID(), s.recruitment.revision())
                == SettlementManager.AdmissionResult.COMMITTED,
            "the full price must commit through explicit admission");

        helper.assertFalse(guest.isTraveler(), "the full price must be payable and admit them");
        helper.assertTrue(countInHearth(hearth, Items.BREAD) == 12,
            "coin payment must retain twelve seeded meals, found "
                + countInHearth(hearth, Items.BREAD));
        helper.assertTrue(countInHearth(hearth, Items.OAK_PLANKS) == 8,
            "coin payment must retain unrelated planks, found " + countInHearth(hearth, Items.OAK_PLANKS));
        helper.assertTrue(countInHearth(hearth, Items.IRON_INGOT) == 5,
            "an unrelated good must be untouched, found " + countInHearth(hearth, Items.IRON_INGOT));
        helper.assertTrue(countInHearth(hearth, com.hearthstead.registry.ModItems.GOLD_COIN.get()) == 0
                && s.recruitment.admissionReceipt().removedItemCount() == s.recruitment.quote().coins(), "exact frozen coins consumed once");
        helper.succeed();
    }

    // ------------------------------------------------------------ (b) ---

    /**
     * An employed innkeeper earns the NAMED "hearthstead.discount.innkeeper"
     * hook, and the actual frozen aptitude-adjusted Coin amount leaves the
     * Hearth exactly once, including after dismissal of the discount source.
     */
    @GameTest(batch = "costs", template = "empty32", timeoutTicks = 200)
    public void employedInnkeeperAppliesTheNamedDiscount(GameTestHelper helper) {
        floor(helper, 32);
        ServerLevel level = helper.getLevel();
        BlockPos hearthRel = new BlockPos(6, 1, 6);
        BlockPos tavernRel = new BlockPos(10, 1, 10);
        Settlement s = settlement(helper, hearthRel);
        Building tavern = building(helper, s, BuildingType.TAVERN, tavernRel.getX(), tavernRel.getZ());
        SettlerEntity keeper = settler(helper, s, "Kroverten", tavernRel.getX() + 1, tavernRel.getZ());
        helper.assertTrue(Employment.hire(level, s, tavern, keeper).ok(),
            "the tavern must take an innkeeper");

        List<Costs.Discount> discounts = SettlementManager.recruitDiscounts(level, s);
        helper.assertTrue(discounts.size() == 1,
            "exactly one hook must apply (innkeeper only), found " + discounts.size());
        helper.assertTrue(discounts.get(0).translationKey().equals("hearthstead.discount.innkeeper")
                && discounts.get(0).percent() == 25,
            "the hook must be the named innkeeper discount at 25%, found " + discounts.get(0));

        HearthBlockEntity hearth = (HearthBlockEntity) level
            .getBlockEntity(helper.absolutePos(hearthRel));
        // Base qualification stock plus sixteen meals for both residents.
        // After the real guest spawns, stock the exact frozen quote below.
        hearth.insertGoods(new ItemStack(Items.BREAD, 20));
        hearth.insertGoods(new ItemStack(Items.OAK_PLANKS, 8));

        SettlerEntity guest = waitingTraveler(helper, s, "Gjest");
        stockQuotedAdmission(helper, s, hearth, Items.OAK_PLANKS);
        var frozenQuote = s.recruitment.quote();
        helper.assertTrue(frozenQuote.discountPercent() == 25
                && frozenQuote.coins() == Costs.discounted(4 + 2 * frozenQuote.premium(), 25),
            "the actual aptitude-adjusted offer must freeze the named 25 percent discount");
        int residentsBeforeDismissal = s.population();
        helper.assertTrue(Employment.dismiss(level, s, keeper) == tavern
                && s.population() == residentsBeforeDismissal
                && Employment.employerOf(s, keeper.getUUID()) == null,
            "remove only the discount source while preserving the same resident reserve");
        var assessment = com.hearthstead.settlement.RecruitmentPolicy.assess(level, s,
            com.hearthstead.settlement.RecruitmentPolicy.Stage.WAITING_ADMISSION);
        helper.assertTrue(s.recruitment.quote().equals(frozenQuote)
                && SettlementManager.recruitPrice(level, s).lines().get(0).count() == frozenQuote.coins()
                && assessment.price().lines().get(0).count() == frozenQuote.coins(),
            "shared reserve and payment assessment must retain the frozen quote after dismissal");
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        helper.assertTrue(SettlementManager.admitWaitingTraveler(player, s,
                guest.getUUID(), s.recruitment.revision())
                == SettlementManager.AdmissionResult.COMMITTED,
            "the named discounted price must commit through explicit admission");

        helper.assertFalse(guest.isTraveler(),
            "the discounted price alone must be enough to admit them");
        helper.assertTrue(countInHearth(hearth, Items.BREAD) == 20
                && countInHearth(hearth, Items.OAK_PLANKS) == 8,
            "only the discounted Coins are spent; meals and planks remain unchanged, found "
                + countInHearth(hearth, Items.BREAD) + " bread, "
                + countInHearth(hearth, Items.OAK_PLANKS) + " planks left");
        helper.assertTrue(countInHearth(hearth, com.hearthstead.registry.ModItems.GOLD_COIN.get()) == 0
                && s.recruitment.admissionReceipt().removedItemCount() == frozenQuote.coins(), "frozen discounted coins charged exactly once");
        helper.succeed();
    }

    // ------------------------------------------------------------ (c) ---

    /**
     * Innkeeper (25%) and dining hall (25%) are the two hooks COSTS.md names
     * for recruiting, and together they land EXACTLY on the -50% floor
     * ("Floor at -50%: 2 bread + 4 planks") -- COSTS.md's own worked
     * example, reproduced against the real buildings that earn it.
     *
     * <p>A third 25% hook is then stacked on top of the same, real two-hook
     * list to prove the cap actually CLIPS rather than merely landing on
     * -50% by coincidence: 25+25+25=75% would leave 1 bread + 2 planks if
     * uncapped, but COSTS.md's law never lets a settlement go past -50% no
     * matter how many hooks it stacks. (No real building grants a third
     * recruiting discount today -- COSTS.md's Recruiting section names only
     * the two above -- so the third hook here is added directly to prove
     * the engine's own ceiling, not to claim a third real-world source.)
     */
    @GameTest(batch = "costs", template = "empty32", timeoutTicks = 200)
    public void stackingCapsAtFiftyPercentNeverDeeper(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Settlement s = new Settlement(UUID.randomUUID(), "Fullstappet",
            helper.absolutePos(new BlockPos(2, 1, 2)));
        Building tavern = building(helper, s, BuildingType.TAVERN, 4, 2);
        SettlerEntity keeper = settler(helper, s, "Kroverten", 5, 2);
        helper.assertTrue(Employment.hire(level, s, tavern, keeper).ok(), "hire must succeed");
        building(helper, s, BuildingType.DINING_HALL, 4, 6);

        List<Costs.Discount> real = SettlementManager.recruitDiscounts(level, s);
        helper.assertTrue(real.size() == 2,
            "innkeeper + dining hall must both apply, found " + real.size());

        Costs.Price twoHooks = Costs.afterDiscounts(Costs.recruit(), real);
        helper.assertTrue(twoHooks.lines().get(0).count() == 2
                && twoHooks.lines().size() == 1,
            "innkeeper + dining hall alone must land exactly on COSTS.md's floor "
                + "(2 Coins), found " + twoHooks.lines());

        List<Costs.Discount> three = new ArrayList<>(real);
        three.add(new Costs.Discount("hearthstead.discount.library", 25,
            "test-only third hook, proving the cap clips rather than coincides"));
        Costs.Price threeHooks = Costs.afterDiscounts(Costs.recruit(), three);
        helper.assertTrue(threeHooks.lines().get(0).count() == 2
                && threeHooks.lines().size() == 1,
            "a third stacked hook must NOT push past -50% (uncapped would be "
                + "1 Coin), found " + threeHooks.lines());
        helper.succeed();
    }

    // ------------------------------------------------------------ (d) ---

    /**
     * COSTS.md: "never below 1 of any line". A line small enough that its
     * own discount would zero it out must instead floor at 1 -- proven
     * directly against {@link Costs#afterDiscounts}, independent of
     * recruiting's own (always comfortably above 1) numbers.
     */
    @GameTest(batch = "costs", template = "empty32", timeoutTicks = 200)
    public void aDiscountNeverTakesALineBelowOne(GameTestHelper helper) {
        Costs.Price oneBread = Costs.of(Costs.PriceKey.RECRUIT, Costs.Line.of(Items.BREAD, 1));
        List<Costs.Discount> half = List.of(
            new Costs.Discount("hearthstead.discount.innkeeper", 25, "test hook"),
            new Costs.Discount("hearthstead.discount.dining_hall", 25, "test hook"));

        Costs.Price discounted = Costs.afterDiscounts(oneBread, half);
        helper.assertTrue(discounted.lines().get(0).count() == 1,
            "a 1-count line at 50% off must floor at 1, not 0, found "
                + discounted.lines().get(0).count());
        helper.succeed();
    }

    // ------------------------------------------------------------ (e) ---

    /**
     * The tag-aware planks line (Byggherre-dom #1, krav 8) survived moving
     * the price into {@link Costs}: a settlement founded where only birch
     * grows must still be able to pay the planks line with birch planks,
     * not just oak.
     */
    @GameTest(batch = "costs", template = "empty32", timeoutTicks = 200)
    public void recruitPriceStillAcceptsAnyPlanks(GameTestHelper helper) {
        floor(helper, 32);
        ServerLevel level = helper.getLevel();
        BlockPos hearthRel = new BlockPos(6, 1, 6);
        Settlement s = settlement(helper, hearthRel);
        building(helper, s, BuildingType.TAVERN, 10, 10);

        HearthBlockEntity hearth = (HearthBlockEntity) level
            .getBlockEntity(helper.absolutePos(hearthRel));
        hearth.insertGoods(new ItemStack(Items.BREAD, 12));
        // Birch, not oak -- the exact-item mistake this line must not repeat.
        hearth.insertGoods(new ItemStack(Items.BIRCH_PLANKS, 8));

        SettlerEntity guest = waitingTraveler(helper, s, "Gjest");
        // Author an actual old-format saved transaction: schema1 had no Quote.
        var old = s.recruitment.writeNbt();
        old.putInt("SchemaVersion", 1); old.remove("Quote");
        // Use an exact timing target that existed in schema v1; new ordinary ranges did not.
        old.remove("TimingProfileWireId");
        old.putInt("LockedTarget", com.hearthstead.settlement.RecruitmentPolicy.callToArmsTargetFor(s.id, old.getInt("Cycle")));
        s.applyRecruitment(RecruitmentTransaction.readOrQuarantine(old, s.id));
        SettlementManager.recruitPrice(level, s); // Freeze the historical base barter once.
        helper.assertTrue(s.recruitment.quote().version() == 0, "loaded schema1 guest retains legacy barter");
        stockQuotedAdmission(helper, s, hearth, Items.BIRCH_PLANKS);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        helper.assertTrue(SettlementManager.admitWaitingTraveler(player, s,
                guest.getUUID(), s.recruitment.revision())
                == SettlementManager.AdmissionResult.COMMITTED,
            "the tag-aware birch-plank price must commit through explicit admission");

        helper.assertFalse(guest.isTraveler(),
            "birch planks must pay the planks line just like oak would");
        helper.assertTrue(countInHearth(hearth, Items.BIRCH_PLANKS) == 0,
            "the birch planks price must be gone, found "
                + countInHearth(hearth, Items.BIRCH_PLANKS));
        helper.succeed();
    }

    // ---------------------------------------------- COSTS-2: the feast ---

    /**
     * The FIRST appointment to an empty seat is free (COSTS.md "Mayor swap:
     * the feast"), and a SECOND appointment while one already sits charges
     * the full, undiscounted 8-bread feast -- exactly that amount leaves the
     * hearth, chest-true.
     */
    @GameTest(batch = "costs", template = "empty32", timeoutTicks = 200)
    public void appointingASecondMayorChargesTheFullFeastFromTheHearth(GameTestHelper helper) {
        floor(helper, 32);
        ServerLevel level = helper.getLevel();
        BlockPos hearthRel = new BlockPos(6, 1, 6);
        Settlement s = settlement(helper, hearthRel);
        HearthBlockEntity hearth = (HearthBlockEntity) level
            .getBlockEntity(helper.absolutePos(hearthRel));

        SettlerEntity first = settler(helper, s, "Forste", 8, 8);
        helper.assertTrue(Mayor.appoint(level, s, first) == null,
            "the first appointment to an empty seat must be free");
        helper.assertTrue(countInHearth(hearth, Items.BREAD) == 0,
            "an empty seat's appointment must not touch the hearth at all");

        hearth.insertGoods(new ItemStack(Items.BREAD, 8));
        SettlerEntity second = settler(helper, s, "Andre", 9, 8);
        Component refusal = Mayor.appoint(level, s, second);

        helper.assertTrue(refusal == null, "the full feast is payable and the swap must succeed");
        helper.assertTrue(s.mayorId.equals(second.getUUID()), "the new mayor must hold the seat");
        helper.assertTrue(countInHearth(hearth, Items.BREAD) == 0,
            "the full 8-bread feast must be entirely gone, found "
                + countInHearth(hearth, Items.BREAD));
        helper.succeed();
    }

    /**
     * A mayor who is not currently loaded must still be charged for -- the
     * KF-025 shape, now with a price attached (2026-08-26 raid-night audit).
     * {@code Mayor.find} resolves the incumbent through {@code
     * level.getEntity}, a LOADING fact, so appointing a successor while the
     * incumbent's chunk happened to be unloaded used to skip the whole swap
     * branch: no feast charged, no stand-down morale hit, yet the seat still
     * changed hands -- a swap that silently succeeded free. Simulated here
     * as an incumbent id with no entity in the level at all: {@code
     * level.getEntity} returns null either way, so it is the exact
     * observable state {@code Mayor.find} sees for a genuinely unloaded
     * mayor. {@code settlement.mayorId} alone -- a SETTLEMENT fact -- now
     * decides whether this is a swap, so the feast is charged regardless.
     */
    @GameTest(batch = "costs", template = "empty32", timeoutTicks = 200)
    public void anUnloadedIncumbentMayorStillChargesTheFeast(GameTestHelper helper) {
        floor(helper, 32);
        ServerLevel level = helper.getLevel();
        BlockPos hearthRel = new BlockPos(6, 1, 6);
        Settlement s = settlement(helper, hearthRel);
        HearthBlockEntity hearth = (HearthBlockEntity) level
            .getBlockEntity(helper.absolutePos(hearthRel));

        // On record as mayor, but no entity by that id exists in this level
        // at all -- Mayor.find returns null for this exactly the way it
        // would for a mayor asleep in an unloaded chunk.
        s.mayorId = UUID.randomUUID();
        s.mayorSince = level.getGameTime();

        hearth.insertGoods(new ItemStack(Items.BREAD, 8));
        SettlerEntity successor = settler(helper, s, "Etterfolger", 9, 8);
        Component refusal = Mayor.appoint(level, s, successor);

        helper.assertTrue(refusal == null,
            "an unloaded incumbent must not silently block a payable swap");
        helper.assertTrue(s.mayorId.equals(successor.getUUID()),
            "the successor must hold the seat");
        helper.assertTrue(countInHearth(hearth, Items.BREAD) == 0,
            "the full feast must still be charged for an unloaded incumbent, "
                + "found " + countInHearth(hearth, Items.BREAD));
        helper.succeed();
    }

    /**
     * A village that cannot afford the feast does not get a new mayor: the
     * swap is refused with a reason, the old mayor keeps the seat, and the
     * hearth is left exactly as it was -- a swap that silently succeeded
     * without the goods is exactly the value mint FLOWS.md forbids.
     */
    @GameTest(batch = "costs", template = "empty32", timeoutTicks = 200)
    public void aVillageThatCannotAffordTheFeastKeepsItsMayor(GameTestHelper helper) {
        floor(helper, 32);
        ServerLevel level = helper.getLevel();
        BlockPos hearthRel = new BlockPos(6, 1, 6);
        Settlement s = settlement(helper, hearthRel);
        HearthBlockEntity hearth = (HearthBlockEntity) level
            .getBlockEntity(helper.absolutePos(hearthRel));

        SettlerEntity first = settler(helper, s, "Forste", 8, 8);
        helper.assertTrue(Mayor.appoint(level, s, first) == null,
            "the first appointment to an empty seat must be free");

        hearth.insertGoods(new ItemStack(Items.BREAD, 3)); // short of the 8-bread feast
        SettlerEntity second = settler(helper, s, "Andre", 9, 8);
        Component refusal = Mayor.appoint(level, s, second);

        helper.assertTrue(refusal != null,
            "a village that cannot pay the feast must be refused, with a reason");
        helper.assertTrue(s.mayorId.equals(first.getUUID()),
            "the old mayor must keep the seat -- a refused swap changes nothing");
        helper.assertTrue(countInHearth(hearth, Items.BREAD) == 3,
            "a refused feast must not touch the hearth at all, found "
                + countInHearth(hearth, Items.BREAD));
        helper.succeed();
    }

    /**
     * A registered dining hall earns the NAMED
     * "hearthstead.discount.mayor_feast_dining_hall" hook at -50% (COSTS.md:
     * "the feast is cheaper where feasts are normal"), and exactly the
     * discounted amount (4 bread, half of 8) leaves the hearth for the swap
     * to succeed -- not the full price.
     */
    @GameTest(batch = "costs", template = "empty32", timeoutTicks = 200)
    public void diningHallHalvesTheFeastAndOnlyTheDiscountedAmountLeavesTheChest(
            GameTestHelper helper) {
        floor(helper, 32);
        ServerLevel level = helper.getLevel();
        BlockPos hearthRel = new BlockPos(6, 1, 6);
        BlockPos hallRel = new BlockPos(10, 1, 10);
        Settlement s = settlement(helper, hearthRel);
        building(helper, s, BuildingType.DINING_HALL, hallRel.getX(), hallRel.getZ());
        HearthBlockEntity hearth = (HearthBlockEntity) level
            .getBlockEntity(helper.absolutePos(hearthRel));

        SettlerEntity first = settler(helper, s, "Forste", 8, 8);
        helper.assertTrue(Mayor.appoint(level, s, first) == null,
            "the first appointment to an empty seat must be free");

        List<Costs.Discount> discounts =
            Costs.discountsFor(level, s, Costs.PriceKey.MAYOR_FEAST);
        helper.assertTrue(discounts.size() == 1
                && discounts.get(0).translationKey()
                    .equals("hearthstead.discount.mayor_feast_dining_hall")
                && discounts.get(0).percent() == 50,
            "a dining hall must earn exactly the named -50% feast hook, found " + discounts);

        hearth.insertGoods(new ItemStack(Items.BREAD, 4)); // exactly the discounted price
        SettlerEntity second = settler(helper, s, "Andre", 9, 8);
        Component refusal = Mayor.appoint(level, s, second);

        helper.assertTrue(refusal == null,
            "the discounted feast alone must be enough for the swap to succeed");
        helper.assertTrue(s.mayorId.equals(second.getUUID()), "the new mayor must hold the seat");
        helper.assertTrue(countInHearth(hearth, Items.BREAD) == 0,
            "exactly the discounted 4-bread feast must be spent, found "
                + countInHearth(hearth, Items.BREAD));
        helper.succeed();
    }

    /** Payment tests still require a real, free bed; abstract founder capacity is insufficient. */
    private static void admissionBed(GameTestHelper helper, Settlement settlement) {
        if (com.hearthstead.settlement.BuildingManager.findFreeBed(helper.getLevel(), settlement) != null) return;
        Building home = GameTestFixtures.register(helper, settlement, BuildingType.HOUSE, 2, 7);
        BlockPos foot = new BlockPos(3, 1, 8);
        BlockPos head = foot.south();
        var state = Blocks.RED_BED.defaultBlockState()
            .setValue(net.minecraft.world.level.block.BedBlock.FACING, net.minecraft.core.Direction.SOUTH);
        helper.setBlock(foot, state.setValue(net.minecraft.world.level.block.BedBlock.PART,
            net.minecraft.world.level.block.state.properties.BedPart.FOOT));
        helper.setBlock(head, state.setValue(net.minecraft.world.level.block.BedBlock.PART,
            net.minecraft.world.level.block.state.properties.BedPart.HEAD));
        home.beds.add(helper.absolutePos(head));
        helper.assertTrue(com.hearthstead.settlement.BuildingManager.findFreeBed(helper.getLevel(), settlement)
            .equals(helper.absolutePos(head)), "payment fixture must expose the real unclaimed bed head");
    }
}
