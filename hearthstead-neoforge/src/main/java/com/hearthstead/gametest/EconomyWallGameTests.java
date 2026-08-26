package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Costs;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * PLAN_TAVERN_GATE.md, byggherrens krav 2 og 7: the owner's "ikke emeralds"
 * order and the bootstrap-safe bell both become guarded facts the suite
 * enforces, not notes someone could quietly drift away from.
 *
 * <p><b>(g) noBuildPlanOrPriceUsesEmeralds</b> is a RATCHET in
 * {@code BuildPlanRecipeGameTests#everyBuildingTypeHasACraftablePlanRecipe}'s
 * own shape: it reads the recipe manager's own book -- every
 * {@link CraftingRecipe} whose result is a {@code hearthstead:build_plan} --
 * and {@link Costs}' own price table -- every public, no-arg,
 * {@code Costs.Price}-returning factory, found by reflection so a future
 * price nobody remembers to add here still gets checked, never silently
 * skipped -- rather than a hand-maintained list of "the recipes/prices I
 * know about today".
 *
 * <p><b>(h) theBellIsCraftable</b> proves PLAN_TAVERN_GATE.md's one
 * exception to the slice's frozen recipe surface --
 * {@code data/hearthstead/recipe/bell.json} -- actually resolves through
 * the real {@link RecipeManager} (the exact path a survival crafting table
 * uses), the same discipline {@code SurvivalAuditWallGameTests}'s own
 * recipe tests hold to. Vanilla ships NO bell recipe at all -- a bell is
 * otherwise village-loot or silk-touch only -- which is exactly the
 * soft-lock this recipe exists to close: without it, a village-less world
 * can build a hearth, a tavern PLAN and the room around it, but never the
 * bell the room's own requirement demands, forever.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class EconomyWallGameTests {

    // ------------------------------------------------------------ (g) ---

    /**
     * Every public, no-arg, {@link Costs.Price}-returning factory on
     * {@link Costs}, found by reflection rather than named by hand -- so a
     * future price this test's author forgot to list still gets swept in,
     * exactly the ratchet spirit {@code BuildPlanRecipeGameTests.EXEMPT}'s
     * own "never a silent skip" documents for its own list.
     */
    private static List<Costs.Price> allPrices() {
        List<Costs.Price> prices = new ArrayList<>();
        for (Method m : Costs.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers())
                && m.getParameterCount() == 0 && m.getReturnType() == Costs.Price.class) {
                try {
                    prices.add((Costs.Price) m.invoke(null));
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException("EconomyWallGameTests: could not invoke Costs."
                        + m.getName() + "() while collecting every price", e);
                }
            }
        }
        return prices;
    }

    @GameTest(batch = "economy_wall", template = "empty5", timeoutTicks = 100)
    public void noBuildPlanOrPriceUsesEmeralds(GameTestHelper helper) {
        ItemStack emerald = new ItemStack(Items.EMERALD);

        RecipeManager recipes = helper.getLevel().getRecipeManager();
        List<String> recipeOffenders = new ArrayList<>();
        for (RecipeHolder<?> holder : recipes.getRecipes()) {
            if (!(holder.value() instanceof CraftingRecipe recipe)) {
                continue;
            }
            ItemStack result = recipe.getResultItem(helper.getLevel().registryAccess());
            if (!result.is(ModItems.BUILD_PLAN.get())) {
                continue;
            }
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient.test(emerald)) {
                    recipeOffenders.add(holder.id().toString());
                    break;
                }
            }
        }
        helper.assertTrue(recipeOffenders.isEmpty(),
            "build plan recipe(s) accept an emerald ingredient -- forbidden by "
                + "the owner's tavern-gate order (\"ikke emeralds\"): " + recipeOffenders);

        List<String> priceOffenders = new ArrayList<>();
        for (Costs.Price price : allPrices()) {
            for (Costs.Line line : price.lines()) {
                boolean matchesEmerald = line.exact() != null
                    ? emerald.is(line.exact())
                    : line.tag() != null && emerald.is(line.tag());
                if (matchesEmerald) {
                    priceOffenders.add(price.key() + " (" + line + ")");
                }
            }
        }
        helper.assertTrue(priceOffenders.isEmpty(),
            "Costs price line(s) charge an emerald -- forbidden by the same order: "
                + priceOffenders);
        helper.succeed();
    }

    // ------------------------------------------------------------ (h) ---

    /**
     * {@code data/hearthstead/recipe/bell.json}: 3 gold ingots, 2 sticks, 1
     * iron ingot, laid out in the exact shape the JSON's pattern declares
     * ("GGG" / "S S" / " I "), assembles into a real {@code minecraft:bell}
     * through the actual recipe manager -- the same
     * {@code RecipeManager#getRecipeFor(RecipeType.CRAFTING, ...)} path a
     * survival crafting table uses, mirroring
     * {@code SurvivalAuditWallGameTests}'s own recipe-through-the-manager
     * idiom.
     */
    @GameTest(batch = "economy_wall", template = "empty5", timeoutTicks = 100)
    public void theBellIsCraftable(GameTestHelper helper) {
        List<ItemStack> slots = new ArrayList<>(Arrays.asList(
            new ItemStack(Items.GOLD_INGOT), new ItemStack(Items.GOLD_INGOT),
            new ItemStack(Items.GOLD_INGOT),
            new ItemStack(Items.STICK), ItemStack.EMPTY, new ItemStack(Items.STICK),
            ItemStack.EMPTY, new ItemStack(Items.IRON_INGOT), ItemStack.EMPTY));
        CraftingInput input = CraftingInput.of(3, 3, slots);

        RecipeManager recipes = helper.getLevel().getRecipeManager();
        Optional<RecipeHolder<CraftingRecipe>> match =
            recipes.getRecipeFor(RecipeType.CRAFTING, input, helper.getLevel());
        helper.assertTrue(match.isPresent(),
            "bell: no crafting recipe matched 3 gold ingots + 2 sticks + 1 iron "
                + "ingot through the recipe manager -- the survival table would "
                + "show nothing, and a village-less world could never build a "
                + "tavern");

        ItemStack result = match.get().value().assemble(input, helper.getLevel().registryAccess());
        helper.assertTrue(result.is(Items.BELL),
            "bell: matched recipe did not assemble a bell, got " + result);
        helper.succeed();
    }
}
