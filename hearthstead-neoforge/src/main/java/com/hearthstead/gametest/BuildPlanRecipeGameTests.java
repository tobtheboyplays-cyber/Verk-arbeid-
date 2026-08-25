package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.registry.ModComponents;
import com.hearthstead.registry.ModItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Owner-critic verdict #1, krav 5, severity 1: a fresh survival world must be
 * able to reach every claimed building, the smithy included, without a
 * single command. Before this pass only 6 of 33 {@link BuildingType}s had a
 * survival crafting recipe for their build plan; the rest existed only via
 * {@code /give}.
 *
 * <p>Test (a) is a RATCHET, deliberately derived from
 * {@code BuildingType.values()} rather than a hand-written list: a future
 * 34th building type fails this test the moment it is added, until someone
 * gives it a {@code data/hearthstead/recipe/build_plan_<id>.json} (or adds it
 * to {@link #EXEMPT} with a documented reason — never a silent skip). It
 * reads the recipe manager's own book, not the filesystem: any
 * {@link CraftingRecipe} whose result is a {@code hearthstead:build_plan}
 * stamped with a building's id counts, regardless of what ingredients it
 * asks for or how many recipes lead to the same stamp.
 *
 * <p>Test (b) spot-checks three recipes spanning the cheap/mid/expensive
 * spread end to end THROUGH the recipe manager (the exact same
 * {@code getRecipeFor}/{@code assemble} path a real crafting table uses),
 * proving inputs really do turn into a plan stamped with the right type —
 * not just that a JSON file with the right shape exists on disk.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class BuildPlanRecipeGameTests {

    /**
     * BuildingTypes deliberately excluded from the "every building is
     * player-craftable" ratchet, each with its reason recorded here so an
     * exclusion can never happen silently.
     *
     * <p>Empty: RECIPES-1 judged all 33 currently-registered types fit for a
     * player-craftable plan (see the price table in the delivering report) —
     * none of them are command-only, debug-only, or otherwise meant to be
     * unreachable in survival.
     */
    private static final Set<BuildingType> EXEMPT = EnumSet.noneOf(BuildingType.class);

    // ------------------------------------------------------------------ (a) ---

    /**
     * The ratchet. Walks every loaded {@link CraftingRecipe}, collects the
     * {@code building_type} stamp off every recipe whose result is a
     * {@code hearthstead:build_plan}, and fails by NAME for every
     * non-exempt {@link BuildingType} that stamp set does not cover.
     */
    @GameTest(template = "empty5", timeoutTicks = 100)
    public void everyBuildingTypeHasACraftablePlanRecipe(GameTestHelper helper) {
        RecipeManager recipes = helper.getLevel().getRecipeManager();
        Set<String> stampedTypes = new HashSet<>();

        for (RecipeHolder<?> holder : recipes.getRecipes()) {
            if (!(holder.value() instanceof CraftingRecipe recipe)) {
                continue;
            }
            ItemStack result = recipe.getResultItem(helper.getLevel().registryAccess());
            if (!result.is(ModItems.BUILD_PLAN.get())) {
                continue;
            }
            String stamped = result.get(ModComponents.BUILDING_TYPE.get());
            if (stamped != null) {
                stampedTypes.add(stamped);
            }
        }

        List<String> missing = new ArrayList<>();
        for (BuildingType type : BuildingType.values()) {
            if (EXEMPT.contains(type)) {
                continue;
            }
            if (!stampedTypes.contains(type.id())) {
                missing.add(type.id());
            }
        }

        helper.assertTrue(missing.isEmpty(),
            "BuildingType(s) with no survival crafting recipe for their build "
                + "plan -- a fresh survival world cannot reach these without a "
                + "command: " + missing + ". Add "
                + "data/hearthstead/recipe/build_plan_<id>.json for each, or "
                + "add the type to BuildPlanRecipeGameTests.EXEMPT with a "
                + "documented reason.");
        helper.succeed();
    }

    // ------------------------------------------------------------------ (b) ---

    /** A 3x3 crafting grid input built from a flat ingredient list, matching
     *  how a survival player would actually fill a crafting table -- order
     *  and position do not matter to a shapeless recipe. */
    private static CraftingInput grid(ItemStack... stacks) {
        helperCheckSize(stacks.length);
        List<ItemStack> slots = new ArrayList<>(Arrays.asList(stacks));
        while (slots.size() < 9) {
            slots.add(ItemStack.EMPTY);
        }
        return CraftingInput.of(3, 3, slots);
    }

    private static void helperCheckSize(int count) {
        if (count > 9) {
            throw new IllegalArgumentException("a crafting table only has 9 slots, got " + count);
        }
    }

    /** Looks the input up through the SAME path a real crafting table uses
     *  ({@code RecipeManager#getRecipeFor(RecipeType.CRAFTING, ...)}), then
     *  asserts the assembled result is a build plan stamped for
     *  {@code expected}. */
    private static void assertCraftsInto(GameTestHelper helper, String label,
                                         CraftingInput input, BuildingType expected) {
        RecipeManager recipes = helper.getLevel().getRecipeManager();
        Optional<RecipeHolder<CraftingRecipe>> match =
            recipes.getRecipeFor(RecipeType.CRAFTING, input, helper.getLevel());
        helper.assertTrue(match.isPresent(),
            label + ": no crafting recipe matched these ingredients through "
                + "the recipe manager -- the survival table would show nothing");

        ItemStack result = match.get().value().assemble(input, helper.getLevel().registryAccess());
        helper.assertTrue(result.is(ModItems.BUILD_PLAN.get()),
            label + ": matched recipe did not assemble a build plan, got "
                + result);

        String stamped = result.get(ModComponents.BUILDING_TYPE.get());
        helper.assertTrue(expected.id().equals(stamped),
            label + ": expected a plan stamped '" + expected.id() + "', got '"
                + stamped + "'");
    }

    /**
     * WELL (hearthside, the cheapest tier): 1 paper, 1 feather, 1 stick.
     */
    @GameTest(template = "empty5", timeoutTicks = 100)
    public void wellPlanCraftsFromPaperFeatherAndAStick(GameTestHelper helper) {
        CraftingInput input = grid(
            new ItemStack(Items.PAPER),
            new ItemStack(Items.FEATHER),
            new ItemStack(Items.STICK));
        assertCraftsInto(helper, "well", input, BuildingType.WELL);
        helper.succeed();
    }

    /**
     * SMITHY (skilled tier, iron-priced per the owner-critic's own hint): 1
     * paper, 1 feather, 2 iron ingots.
     */
    @GameTest(template = "empty5", timeoutTicks = 100)
    public void smithyPlanCraftsFromPaperFeatherAndTwoIronIngots(GameTestHelper helper) {
        CraftingInput input = grid(
            new ItemStack(Items.PAPER),
            new ItemStack(Items.FEATHER),
            new ItemStack(Items.IRON_INGOT),
            new ItemStack(Items.IRON_INGOT));
        assertCraftsInto(helper, "smithy", input, BuildingType.SMITHY);
        helper.succeed();
    }

    /**
     * LIBRARY (civic tier, the most expensive recipe of the pass): 2 paper,
     * 1 feather, 3 books.
     */
    @GameTest(template = "empty5", timeoutTicks = 100)
    public void libraryPlanCraftsFromPaperFeatherAndThreeBooks(GameTestHelper helper) {
        CraftingInput input = grid(
            new ItemStack(Items.PAPER),
            new ItemStack(Items.PAPER),
            new ItemStack(Items.FEATHER),
            new ItemStack(Items.BOOK),
            new ItemStack(Items.BOOK),
            new ItemStack(Items.BOOK));
        assertCraftsInto(helper, "library", input, BuildingType.LIBRARY);
        helper.succeed();
    }

}
