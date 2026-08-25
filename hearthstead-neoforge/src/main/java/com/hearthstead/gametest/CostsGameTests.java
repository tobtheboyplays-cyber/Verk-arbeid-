package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Costs;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
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
    }

    /** A settlement bound to a real hearth block, the same shape RecruitGameTests uses —
     *  COSTS-1's price is paid out of that block's own inventory. */
    private static Settlement settlement(GameTestHelper helper, BlockPos centerRel) {
        helper.setBlock(centerRel, ModBlocks.HEARTH.get());
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
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
        BlockPos anchor = helper.absolutePos(new BlockPos(x, 1, z));
        Building building = new Building(UUID.randomUUID(), type,
            helper.absolutePos(new BlockPos(x, 2, z)), anchor,
            BoundingBox.fromCorners(anchor, anchor.offset(3, 2, 3)));
        building.valid = true;
        s.buildings.add(building);
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
     * No discount buildings, no discount: {@link SettlementManager#recruitPrice}
     * must equal {@link Costs#recruit()} exactly, and a guest joining pays
     * the full amount with exact conservation of everything else in the
     * hearth -- the same base case {@code RecruitGameTests} already covers,
     * re-proven here through {@link Costs} so the refactor is verified, not
     * assumed.
     */
    @GameTest(template = "empty16", timeoutTicks = 200)
    public void noDiscountBuildingsMeansFullPriceCharged(GameTestHelper helper) {
        floor(helper, 16);
        ServerLevel level = helper.getLevel();
        BlockPos hearthRel = new BlockPos(6, 1, 6);
        Settlement s = settlement(helper, hearthRel);

        List<Costs.Discount> discounts = SettlementManager.recruitDiscounts(level, s);
        helper.assertTrue(discounts.isEmpty(),
            "an undecorated settlement must earn no discount, found " + discounts.size());

        Costs.Price price = SettlementManager.recruitPrice(level, s);
        helper.assertTrue(price.lines().get(0).exact() == Items.BREAD
                && price.lines().get(0).count() == 4,
            "the full price's bread line must be exactly 4, found " + price.lines().get(0));
        helper.assertTrue(price.lines().get(1).tag() == ItemTags.PLANKS
                && price.lines().get(1).count() == 8,
            "the full price's planks line must be exactly 8, found " + price.lines().get(1));

        HearthBlockEntity hearth = (HearthBlockEntity) level
            .getBlockEntity(helper.absolutePos(hearthRel));
        hearth.insertGoods(new ItemStack(Items.BREAD, 4));
        hearth.insertGoods(new ItemStack(Items.OAK_PLANKS, 8));
        hearth.insertGoods(new ItemStack(Items.IRON_INGOT, 5));

        SettlerEntity guest = waitingTraveler(helper, s, "Gjest", hearthRel.offset(1, 0, 0));
        SettlementManager.tickRecruitment(level, s);

        helper.assertFalse(guest.isTraveler(), "the full price must be payable and admit them");
        helper.assertTrue(countInHearth(hearth, Items.BREAD) == 0,
            "the full bread price must be gone, found " + countInHearth(hearth, Items.BREAD));
        helper.assertTrue(countInHearth(hearth, Items.OAK_PLANKS) == 0,
            "the full planks price must be gone, found " + countInHearth(hearth, Items.OAK_PLANKS));
        helper.assertTrue(countInHearth(hearth, Items.IRON_INGOT) == 5,
            "an unrelated good must be untouched, found " + countInHearth(hearth, Items.IRON_INGOT));
        helper.succeed();
    }

    // ------------------------------------------------------------ (b) ---

    /**
     * An employed innkeeper earns the NAMED "hearthstead.discount.innkeeper"
     * hook, and exactly the discounted amount (3 bread + 6 planks, COSTS.md's
     * -25% on 4 bread + 8 planks) leaves the hearth -- not the full price,
     * and not some other number.
     */
    @GameTest(template = "empty16", timeoutTicks = 200)
    public void employedInnkeeperAppliesTheNamedDiscount(GameTestHelper helper) {
        floor(helper, 16);
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
        // Exactly the DISCOUNTED price -- not the full 4 bread + 8 planks.
        // If the discount were fiction (still charging full price), this
        // guest could never join.
        hearth.insertGoods(new ItemStack(Items.BREAD, 3));
        hearth.insertGoods(new ItemStack(Items.OAK_PLANKS, 6));

        SettlerEntity guest = waitingTraveler(helper, s, "Gjest", tavernRel);
        SettlementManager.tickRecruitment(level, s);

        helper.assertFalse(guest.isTraveler(),
            "the discounted price alone must be enough to admit them");
        helper.assertTrue(countInHearth(hearth, Items.BREAD) == 0
                && countInHearth(hearth, Items.OAK_PLANKS) == 0,
            "exactly the discounted price must be spent, found "
                + countInHearth(hearth, Items.BREAD) + " bread, "
                + countInHearth(hearth, Items.OAK_PLANKS) + " planks left");
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
    @GameTest(template = "empty16", timeoutTicks = 200)
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
                && twoHooks.lines().get(1).count() == 4,
            "innkeeper + dining hall alone must land exactly on COSTS.md's floor "
                + "(2 bread + 4 planks), found " + twoHooks.lines());

        List<Costs.Discount> three = new ArrayList<>(real);
        three.add(new Costs.Discount("hearthstead.discount.library", 25,
            "test-only third hook, proving the cap clips rather than coincides"));
        Costs.Price threeHooks = Costs.afterDiscounts(Costs.recruit(), three);
        helper.assertTrue(threeHooks.lines().get(0).count() == 2
                && threeHooks.lines().get(1).count() == 4,
            "a third stacked hook must NOT push past -50% (uncapped would be "
                + "1 bread + 2 planks), found " + threeHooks.lines());
        helper.succeed();
    }

    // ------------------------------------------------------------ (d) ---

    /**
     * COSTS.md: "never below 1 of any line". A line small enough that its
     * own discount would zero it out must instead floor at 1 -- proven
     * directly against {@link Costs#afterDiscounts}, independent of
     * recruiting's own (always comfortably above 1) numbers.
     */
    @GameTest(template = "empty16", timeoutTicks = 200)
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
    @GameTest(template = "empty16", timeoutTicks = 200)
    public void recruitPriceStillAcceptsAnyPlanks(GameTestHelper helper) {
        floor(helper, 16);
        ServerLevel level = helper.getLevel();
        BlockPos hearthRel = new BlockPos(6, 1, 6);
        Settlement s = settlement(helper, hearthRel);

        HearthBlockEntity hearth = (HearthBlockEntity) level
            .getBlockEntity(helper.absolutePos(hearthRel));
        hearth.insertGoods(new ItemStack(Items.BREAD, 4));
        // Birch, not oak -- the exact-item mistake this line must not repeat.
        hearth.insertGoods(new ItemStack(Items.BIRCH_PLANKS, 8));

        SettlerEntity guest = waitingTraveler(helper, s, "Gjest", hearthRel.offset(1, 0, 0));
        SettlementManager.tickRecruitment(level, s);

        helper.assertFalse(guest.isTraveler(),
            "birch planks must pay the planks line just like oak would");
        helper.assertTrue(countInHearth(hearth, Items.BIRCH_PLANKS) == 0,
            "the birch planks price must be gone, found "
                + countInHearth(hearth, Items.BIRCH_PLANKS));
        helper.succeed();
    }
}
