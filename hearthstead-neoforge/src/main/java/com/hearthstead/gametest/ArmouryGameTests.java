package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.building.Production;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.GuardRank;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Owner-critic verdict #1, krav 4, severity 1, second half: "vaktrustning
 * trylles frem" — {@link GuardRank#applyEquipment} used to build a fresh
 * {@code ItemStack} out of nothing the instant a guard's Strength crossed a
 * threshold. These prove the replacement is chest-true both directions: a
 * kit actually sitting in the armoury is what gets worn, and the chest is
 * the one that loses it ({@code aPromotedSpearmanWithdrawsHerChestplate...});
 * with nothing in any store the rank still rises but the guard stays bare,
 * and nothing appears anywhere in the world to fill the gap
 * ({@code promotionWithEmptyStoresLeaves...}) — until the smith actually
 * delivers, at which point the very same settler is dressed on the next
 * refresh, never a fresh recruit standing in for them
 * ({@code ...ThenPicksUpStockOnNextRefresh}); and a promotion that outgrows
 * a tier returns the old pieces to the stores rather than the void, with the
 * settlement's total item count across armoury, warehouse and hearth
 * provably unmoved by the swap
 * ({@code promotionPastATierReturnsOutgrownPiecesConservingTheStores}).
 *
 * <p>Arena idiom is {@code RepairGameTests}/{@code GuardRankGameTests}: the
 * test builds its own floor, registers the settlement through
 * {@link SettlementSavedData}, and keeps the arena modest so a neighbour's
 * concurrently running one is never answered for.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class ArmouryGameTests {

    private static void buildArena(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }

    private static Settlement makeSettlement(GameTestHelper helper, BlockPos centerRel) {
        ServerLevel level = helper.getLevel();
        var arena = helper.getBounds();
        SettlementSavedData data = SettlementSavedData.get(level);
        data.settlements.values().removeIf(old ->
            arena.contains(old.center.getX() + 0.5, old.center.getY() + 0.5,
                old.center.getZ() + 0.5));
        Settlement s = new Settlement(UUID.randomUUID(), "Rustkammer",
            helper.absolutePos(centerRel));
        s.radius = 12;
        data.settlements.put(s.id, s);
        data.setDirty();
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

    /** Repeated small {@code train} calls rather than one huge one, so the
     *  result lands close to {@code target} instead of overshooting into the
     *  next tier by an unpredictable margin near the growth curve's cap
     *  (same idiom as {@code GuardRankGameTests}). */
    private static void trainStrengthTo(SettlerEntity settler, int target) {
        int guard = 0;
        while (settler.attribute(Attribute.STRENGTH) < target && guard++ < 20000) {
            settler.attributes().train(Attribute.STRENGTH, 5.0F, 1.0F);
        }
    }

    private static Container containerAt(GameTestHelper helper, BlockPos rel) {
        BlockEntity be = helper.getLevel().getBlockEntity(helper.absolutePos(rel));
        return be instanceof Container c ? c : null;
    }

    private static int countOf(Container container, Item item) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** Every item in every slot, whatever it is — the raw count a
     *  conservation check sums across stores. */
    private static int totalItemsIn(Container container) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            total += container.getItem(slot).getCount();
        }
        return total;
    }

    private static int totalItemsInHearth(HearthBlockEntity hearth) {
        int total = 0;
        ItemStackHandler inventory = hearth.getInventory();
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            total += inventory.getStackInSlot(slot).getCount();
        }
        return total;
    }

    private static HearthBlockEntity hearth(GameTestHelper helper, Settlement s, BlockPos rel) {
        helper.setBlock(rel, ModBlocks.HEARTH.get());
        BlockEntity be = helper.getLevel().getBlockEntity(helper.absolutePos(rel));
        helper.assertTrue(be instanceof HearthBlockEntity, "the arena hearth should have its block entity");
        HearthBlockEntity hearthEntity = (HearthBlockEntity) be;
        hearthEntity.bindSettlement(s.id);
        return hearthEntity;
    }

    // ---------------------------------------------------- (a) withdrawal ---

    /**
     * (a) The kit is bought, not conjured: with a leather chestplate already
     * sitting in the armoury's own chest, training a Recruit up to
     * {@link GuardRank#SPEARMAN} dresses them in exactly that piece — and the
     * chest is the one that loses it. No mutation happens inside the wait:
     * training completes synchronously before the first tick, so this proves
     * {@code SettlerEntity}'s equipment-refresh hook genuinely withdraws from
     * the world rather than the old thin-air behaviour.
     */
    @GameTest(batch = "armoury", template = "empty16", timeoutTicks = 400)
    public void aPromotedSpearmanWithdrawsHerChestplateFromTheArmoury(GameTestHelper helper) {
        buildArena(helper, 14);
        Settlement s = makeSettlement(helper, new BlockPos(7, 1, 7));
        building(helper, s, BuildingType.ARMOURY, 3, 3);
        helper.setBlock(new BlockPos(4, 1, 3), Blocks.CHEST);
        Container armouryChest = containerAt(helper, new BlockPos(4, 1, 3));
        helper.assertTrue(armouryChest != null, "the armoury chest should be a container");
        armouryChest.setItem(0, new ItemStack(Items.LEATHER_CHESTPLATE, 1));

        SettlerEntity guard = settler(helper, s, "Rekrutt", 4, 4);
        guard.assignProfession(Profession.GUARD);
        helper.assertTrue(GuardRank.of(guard) == GuardRank.RECRUIT,
            "fixture sanity: a fresh guard starts a Recruit");
        trainStrengthTo(guard, GuardRank.SPEARMAN.threshold());

        helper.succeedWhen(() -> {
            helper.assertTrue(GuardRank.of(guard) == GuardRank.SPEARMAN,
                "training must have earned Spearman, Strength="
                    + guard.attribute(Attribute.STRENGTH));
            helper.assertTrue(guard.getItemBySlot(EquipmentSlot.CHEST).is(Items.LEATHER_CHESTPLATE),
                "the Spearman must be wearing the withdrawn chestplate, found "
                    + guard.getItemBySlot(EquipmentSlot.CHEST));
            helper.assertTrue(countOf(armouryChest, Items.LEATHER_CHESTPLATE) == 0,
                "the armoury must lose exactly the piece it gave up, left "
                    + countOf(armouryChest, Items.LEATHER_CHESTPLATE));
        });
    }

    // ---------------------------------------------- (b) + (c) empty/refresh ---

    /**
     * (b) Rank is earned by fighting, not bought at the armoury: with every
     * store empty, training a Recruit past {@link GuardRank#SPEARMAN}'s
     * threshold still earns the rank — the ability checks in
     * {@code GuardMeleeGoal}/{@code GuardLeapGoal} read Strength directly —
     * but the guard stays bare, and no leather chestplate has appeared
     * anywhere in the world (the armoury chest, deliberately left empty,
     * proves it). (c) The moment the smith's delivery lands in that same
     * chest, the very same settler — never a fresh one — picks it up. The
     * pickup here calls {@link GuardRank#applyEquipment} directly rather
     * than waiting on a further rank change: {@code SettlerEntity}'s hook
     * only re-enters this rank once the observed rank itself moves (see
     * {@link GuardRank#isFullyEquipped}'s javadoc), so this is the same
     * entry point that hook would call on the refresh cycle a follow-up
     * SettlerEntity change is needed to actually trigger without a further
     * promotion.
     */
    @GameTest(batch = "armoury", template = "empty16", timeoutTicks = 400)
    public void promotionWithEmptyStoresLeavesUnarmouredThenPicksUpStockOnNextRefresh(
        GameTestHelper helper) {
        buildArena(helper, 14);
        Settlement s = makeSettlement(helper, new BlockPos(7, 1, 7));
        building(helper, s, BuildingType.ARMOURY, 3, 3);
        helper.setBlock(new BlockPos(4, 1, 3), Blocks.CHEST);
        Container armouryChest = containerAt(helper, new BlockPos(4, 1, 3));
        helper.assertTrue(armouryChest != null, "the armoury chest should be a container");
        // Deliberately left empty: the smith has not delivered anything yet.

        SettlerEntity guard = settler(helper, s, "Rekrutt", 4, 4);
        guard.assignProfession(Profession.GUARD);
        trainStrengthTo(guard, GuardRank.SPEARMAN.threshold());

        helper.runAtTickTime(60, () -> {
            // (b) Promoted, but with nothing anywhere to wear.
            helper.assertTrue(GuardRank.of(guard) == GuardRank.SPEARMAN,
                "training must still earn the rank even with empty stores, Strength="
                    + guard.attribute(Attribute.STRENGTH));
            helper.assertTrue(guard.getItemBySlot(EquipmentSlot.CHEST).isEmpty(),
                "with no chestplate in stock anywhere the Spearman must stay "
                    + "bare-chested, found " + guard.getItemBySlot(EquipmentSlot.CHEST));
            helper.assertTrue(countOf(armouryChest, Items.LEATHER_CHESTPLATE) == 0,
                "nothing may be conjured into the armoury either while it waits");

            // The smith delivers, at last.
            armouryChest.setItem(0, new ItemStack(Items.LEATHER_CHESTPLATE, 1));

            // (c) The SAME settler, on the next refresh, picks it up.
            GuardRank.applyEquipment(guard);

            helper.assertTrue(guard.getItemBySlot(EquipmentSlot.CHEST).is(Items.LEATHER_CHESTPLATE),
                "the same Spearman must wear the chestplate the moment it "
                    + "exists, found " + guard.getItemBySlot(EquipmentSlot.CHEST));
            helper.assertTrue(countOf(armouryChest, Items.LEATHER_CHESTPLATE) == 0,
                "the delivered piece must actually leave the chest once worn, left "
                    + countOf(armouryChest, Items.LEATHER_CHESTPLATE));
            helper.succeed();
        });
    }

    // -------------------------------------------------- (d) supersession ---

    /**
     * (d) A promotion that outgrows a tier returns the old pieces to the
     * stores rather than the void. The guard starts already dressed as a
     * full-leather Veteran (a fixture, not a withdrawal — this test is about
     * what happens on the WAY OUT of that kit); Strength is trained straight
     * to {@link GuardRank#SERGEANT}'s threshold, whose kit keeps the leather
     * helmet and boots untouched but swaps chest and legs for iron. The
     * iron is spread across both stores on purpose (armoury holds the
     * leggings, warehouse holds the chestplate) to prove the withdrawal
     * order reaches both, and the returned leather is counted back against
     * the SAME total — armoury + warehouse + hearth — to prove the swap is
     * exactly conserving: two pieces out, two pieces back, net zero.
     */
    @GameTest(batch = "armoury", template = "empty16", timeoutTicks = 400)
    public void promotionPastATierReturnsOutgrownPiecesConservingTheStores(GameTestHelper helper) {
        buildArena(helper, 14);
        BlockPos hearthRel = new BlockPos(11, 1, 11);
        Settlement s = makeSettlement(helper, hearthRel);
        HearthBlockEntity hearthEntity = hearth(helper, s, hearthRel);

        building(helper, s, BuildingType.ARMOURY, 3, 3);
        helper.setBlock(new BlockPos(4, 1, 3), Blocks.CHEST);
        Container armouryChest = containerAt(helper, new BlockPos(4, 1, 3));
        helper.assertTrue(armouryChest != null, "the armoury chest should be a container");
        armouryChest.setItem(0, new ItemStack(Items.IRON_LEGGINGS, 1));

        building(helper, s, BuildingType.WAREHOUSE, 8, 3);
        helper.setBlock(new BlockPos(9, 1, 3), Blocks.CHEST);
        Container warehouseChest = containerAt(helper, new BlockPos(9, 1, 3));
        helper.assertTrue(warehouseChest != null, "the warehouse chest should be a container");
        warehouseChest.setItem(0, new ItemStack(Items.IRON_CHESTPLATE, 1));

        SettlerEntity guard = settler(helper, s, "Veteran", 4, 4);
        guard.assignProfession(Profession.GUARD);
        // Fixture: already a fully-kitted Veteran, as if an earlier
        // promotion (outside this test) already withdrew this leather.
        guard.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
        guard.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
        guard.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.LEATHER_LEGGINGS));
        guard.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.LEATHER_BOOTS));

        int totalBefore = totalItemsIn(armouryChest) + totalItemsIn(warehouseChest)
            + totalItemsInHearth(hearthEntity);
        helper.assertTrue(totalBefore == 2,
            "fixture sanity: one iron piece in each store, got " + totalBefore);

        trainStrengthTo(guard, GuardRank.SERGEANT.threshold());

        helper.succeedWhen(() -> {
            helper.assertTrue(GuardRank.of(guard) == GuardRank.SERGEANT,
                "training must have earned Sergeant, Strength="
                    + guard.attribute(Attribute.STRENGTH));
            helper.assertTrue(guard.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE),
                "Sergeant's chest is iron, found " + guard.getItemBySlot(EquipmentSlot.CHEST));
            helper.assertTrue(guard.getItemBySlot(EquipmentSlot.LEGS).is(Items.IRON_LEGGINGS),
                "Sergeant's legs are iron, found " + guard.getItemBySlot(EquipmentSlot.LEGS));
            helper.assertTrue(guard.getItemBySlot(EquipmentSlot.HEAD).is(Items.LEATHER_HELMET),
                "Sergeant's helmet stays leather, untouched by the swap, found "
                    + guard.getItemBySlot(EquipmentSlot.HEAD));
            helper.assertTrue(guard.getItemBySlot(EquipmentSlot.FEET).is(Items.LEATHER_BOOTS),
                "Sergeant's boots stay leather, untouched by the swap, found "
                    + guard.getItemBySlot(EquipmentSlot.FEET));

            helper.assertTrue(countOf(armouryChest, Items.IRON_LEGGINGS) == 0,
                "the armoury's iron leggings must actually leave, left "
                    + countOf(armouryChest, Items.IRON_LEGGINGS));
            helper.assertTrue(countOf(warehouseChest, Items.IRON_CHESTPLATE) == 0,
                "the warehouse's iron chestplate must actually leave, left "
                    + countOf(warehouseChest, Items.IRON_CHESTPLATE));
            helper.assertTrue(countOf(armouryChest, Items.LEATHER_CHESTPLATE) == 1,
                "the outgrown chestplate must come back to a store (armoury "
                    + "first), found " + countOf(armouryChest, Items.LEATHER_CHESTPLATE));
            helper.assertTrue(countOf(armouryChest, Items.LEATHER_LEGGINGS) == 1,
                "the outgrown leggings must come back to a store (armoury "
                    + "first), found " + countOf(armouryChest, Items.LEATHER_LEGGINGS));

            int totalAfter = totalItemsIn(armouryChest) + totalItemsIn(warehouseChest)
                + totalItemsInHearth(hearthEntity);
            helper.assertTrue(totalAfter == totalBefore,
                "the settlement's total item count across armoury, warehouse "
                    + "and hearth must change by exactly the net kit "
                    + "difference (two iron pieces out, two leather pieces "
                    + "back — net zero here): before=" + totalBefore
                    + " after=" + totalAfter);
        });
    }

    // ---------------------------------------------- (e) the armoury MAKES ---
    //
    // BALANCE_AUDIT.md finding 1 (BROKEN) / PLAN_CIRCULATION.md F3: (a)-(d)
    // above proved GuardRank withdraws real stock and conjures nothing --
    // but nothing ever PUT stock there. Production.of(ARMOURY) was empty:
    // the smithy made only axe/pickaxe/hoe/sword, so every armour piece a
    // guard would ever wear had to be placed by the player's own hand. These
    // prove the maker half exists and is chest-true, in the same direct
    // Production.ready()/Production.run() idiom ChainsGameTests uses for
    // every other recipe-table proof in this repository: no settler, no
    // CrafterWorkGoal, no ticks to wait out (run() is one synchronous
    // transaction) -- purely "materials in this building's own chest in,
    // real armour out, exactly the items consumed leave", which is what
    // would go red the instant these eight recipes were removed from the
    // table (Production.ready() would return null forever with the chest
    // still full, exactly like a village that never got a smithy).

    private static Set<String> ranRecipeIds(GameTestHelper helper, Building armoury) {
        Set<String> ran = new HashSet<>();
        Production.Recipe recipe;
        int guard = 0;
        while ((recipe = Production.ready(helper.getLevel(), armoury)) != null && guard++ < 32) {
            helper.assertTrue(Production.run(helper.getLevel(), armoury, recipe),
                "ready() offered " + recipe.id() + " but run() refused it");
            ran.add(recipe.id());
        }
        return ran;
    }

    /**
     * (e) Leather armour, forged from leather alone — no tannery, no
     * smelter, no smithy anywhere in the world (D-007: a building works
     * alone). Twenty-four leather is exactly enough for one of each of the
     * four leather pieces (5 helmet + 8 chestplate + 7 leggings + 4 boots);
     * running the armoury down to idle must turn every last one of them into
     * real armour sitting in the real chest, and leave no leather behind.
     */
    @GameTest(batch = "armoury", template = "empty16", timeoutTicks = 100)
    public void theArmouryForgesEachLeatherPieceFromLeatherAloneChestTrue(GameTestHelper helper) {
        buildArena(helper, 14);
        Settlement s = makeSettlement(helper, new BlockPos(7, 1, 7));
        Building armoury = building(helper, s, BuildingType.ARMOURY, 3, 3);
        helper.setBlock(new BlockPos(4, 1, 3), Blocks.CHEST);
        Container chest = containerAt(helper, new BlockPos(4, 1, 3));
        helper.assertTrue(chest != null, "the armoury chest should be a container");
        chest.setItem(0, new ItemStack(Items.LEATHER, 24));

        Set<String> ran = ranRecipeIds(helper, armoury);

        helper.assertTrue(ran.equals(Set.of(
                "leather_helmet", "leather_chestplate", "leather_leggings", "leather_boots")),
            "expected exactly the four leather recipes to run once each, ran " + ran);
        helper.assertTrue(countOf(chest, Items.LEATHER) == 0,
            "all 24 leather must be spent, left " + countOf(chest, Items.LEATHER));
        helper.assertTrue(countOf(chest, Items.LEATHER_HELMET) == 1
                && countOf(chest, Items.LEATHER_CHESTPLATE) == 1
                && countOf(chest, Items.LEATHER_LEGGINGS) == 1
                && countOf(chest, Items.LEATHER_BOOTS) == 1,
            "one of each leather piece must be sitting in the chest: helmet="
                + countOf(chest, Items.LEATHER_HELMET) + " chestplate="
                + countOf(chest, Items.LEATHER_CHESTPLATE) + " leggings="
                + countOf(chest, Items.LEATHER_LEGGINGS) + " boots="
                + countOf(chest, Items.LEATHER_BOOTS));
        helper.succeed();
    }

    /**
     * (f) Iron armour, forged from ingots alone — the mirror of (e), no mine
     * and no smelter anywhere in the world either. Twenty-four iron ingots
     * is exactly enough for one of each of the four iron pieces, same 5/8/7/4
     * count as the leather line (the vanilla exchange rate both tiers
     * anchor to, see Production's ARMOURY comment) — proving the SAME table
     * drives both materials rather than a bespoke iron path.
     */
    @GameTest(batch = "armoury", template = "empty16", timeoutTicks = 100)
    public void theArmouryForgesEachIronPieceFromIngotsAloneChestTrue(GameTestHelper helper) {
        buildArena(helper, 14);
        Settlement s = makeSettlement(helper, new BlockPos(7, 1, 7));
        Building armoury = building(helper, s, BuildingType.ARMOURY, 3, 3);
        helper.setBlock(new BlockPos(4, 1, 3), Blocks.CHEST);
        Container chest = containerAt(helper, new BlockPos(4, 1, 3));
        helper.assertTrue(chest != null, "the armoury chest should be a container");
        chest.setItem(0, new ItemStack(Items.IRON_INGOT, 24));

        Set<String> ran = ranRecipeIds(helper, armoury);

        helper.assertTrue(ran.equals(Set.of(
                "iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots")),
            "expected exactly the four iron recipes to run once each, ran " + ran);
        helper.assertTrue(countOf(chest, Items.IRON_INGOT) == 0,
            "all 24 iron ingots must be spent, left " + countOf(chest, Items.IRON_INGOT));
        helper.assertTrue(countOf(chest, Items.IRON_HELMET) == 1
                && countOf(chest, Items.IRON_CHESTPLATE) == 1
                && countOf(chest, Items.IRON_LEGGINGS) == 1
                && countOf(chest, Items.IRON_BOOTS) == 1,
            "one of each iron piece must be sitting in the chest: helmet="
                + countOf(chest, Items.IRON_HELMET) + " chestplate="
                + countOf(chest, Items.IRON_CHESTPLATE) + " leggings="
                + countOf(chest, Items.IRON_LEGGINGS) + " boots="
                + countOf(chest, Items.IRON_BOOTS));
        helper.succeed();
    }

    /**
     * (g) The ladder holds, read off the live table rather than asserted by
     * comment: every iron piece costs strictly more ticks than its leather
     * counterpart, and a full iron kit costs strictly more than a full
     * leather kit — "leather is what a spearman wears and iron plate is
     * what a captain wears" only means something if the captain's kit is
     * actually the more expensive one to forge. Also pins the input counts
     * (5/8/7/4, vanilla's own helmet/chestplate/leggings/boots exchange
     * rate) against a silent retune breaking the vanilla-familiar anchor.
     */
    @GameTest(batch = "armoury", template = "empty16", timeoutTicks = 20)
    public void theArmouryPricesIronMeaningfullyAboveLeatherPerPieceAndPerKit(GameTestHelper helper) {
        java.util.List<Production.Recipe> recipes = Production.of(BuildingType.ARMOURY);
        helper.assertTrue(recipes.size() == 8,
            "the armoury should know exactly eight recipes, found " + recipes.size());

        int[] counts = {5, 8, 7, 4}; // helmet, chestplate, leggings, boots
        String[] slots = {"helmet", "chestplate", "leggings", "boots"};
        int leatherTicks = 0;
        int ironTicks = 0;
        for (int i = 0; i < slots.length; i++) {
            Production.Recipe leather = recipeById(recipes, "leather_" + slots[i]);
            Production.Recipe iron = recipeById(recipes, "iron_" + slots[i]);
            helper.assertTrue(leather != null && iron != null,
                "missing a " + slots[i] + " recipe on one of the two tiers");
            helper.assertTrue(leather.inputCount() == counts[i] && iron.inputCount() == counts[i],
                slots[i] + " should cost " + counts[i] + " material on both tiers (vanilla's own "
                    + "count), got leather=" + leather.inputCount() + " iron=" + iron.inputCount());
            helper.assertTrue(iron.ticks() > leather.ticks(),
                "iron " + slots[i] + " (" + iron.ticks() + "t) must cost strictly more than "
                    + "leather " + slots[i] + " (" + leather.ticks() + "t) — the ladder");
            leatherTicks += leather.ticks();
            ironTicks += iron.ticks();
        }
        helper.assertTrue(ironTicks > leatherTicks,
            "a full iron kit (" + ironTicks + "t) must cost strictly more than a full "
                + "leather kit (" + leatherTicks + "t) to forge — a village that can field "
                + "a captain as easily as a spearman has no ladder");
        helper.succeed();
    }

    private static Production.Recipe recipeById(java.util.List<Production.Recipe> recipes, String id) {
        for (Production.Recipe recipe : recipes) {
            if (recipe.id().equals(id)) {
                return recipe;
            }
        }
        return null;
    }
}
