package com.hearthstead.client.ui2.handbook;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Reads a page's crafting grids from the client's live RecipeManager (the
 * recipes the server actually sent), so the handbook can never show a stale
 * recipe: change the JSON and the book follows.
 */
public final class HandbookRecipes {
    private HandbookRecipes() {
    }

    /**
     * Nine cells (row-major, each with its ingredient options), the output, and
     * the Tech Tree node that gates it ({@code gateNode}, null when free) plus
     * whether this player's settlement still lacks it ({@code locked}).
     */
    public record Grid(List<List<ItemStack>> cells, ItemStack output, boolean missing, String gateNode,
                       boolean locked) {
        static Grid absent() {
            return new Grid(List.of(), ItemStack.EMPTY, true, null, false);
        }
    }

    static List<ItemStack> commonFirst(List<ItemStack> options) {
        List<ItemStack> out = new ArrayList<>(options);
        out.sort(java.util.Comparator.comparingInt(stack -> HandbookSearch.commonRank(
            net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())));
        return List.copyOf(out);
    }

    public static Grid grid(String recipeId) {
        Minecraft mc = Minecraft.getInstance();
        ResourceLocation id = ResourceLocation.tryParse(recipeId);
        if (id == null || mc.level == null) return Grid.absent();
        Optional<RecipeHolder<?>> holder = mc.level.getRecipeManager().byKey(id);
        if (holder.isEmpty()) return Grid.absent();
        Recipe<?> recipe = holder.get().value();
        List<Ingredient> ingredients = recipe.getIngredients();
        int width = 3;
        if (recipe instanceof ShapedRecipe shaped) width = shaped.getWidth();
        List<List<ItemStack>> cells = new ArrayList<>();
        for (int i = 0; i < 9; i++) cells.add(List.of());
        for (int i = 0; i < ingredients.size() && i < 9; i++) {
            int cell = recipe instanceof ShapedRecipe ? (i / width) * 3 + (i % width) : i;
            if (cell < 9) cells.set(cell, commonFirst(Arrays.asList(ingredients.get(i).getItems())));
        }
        ItemStack output = recipe.getResultItem(mc.level.registryAccess()).copy();
        String gate = com.hearthstead.settlement.techtree.TechRecipeGates.nodeFor(id, output.getItem()).orElse(null);
        boolean locked = com.hearthstead.client.techtree.TechKnowledgeClient.locked(id, output.getItem()).isPresent();
        return new Grid(List.copyOf(cells), output, output.isEmpty(), gate, locked);
    }
}
