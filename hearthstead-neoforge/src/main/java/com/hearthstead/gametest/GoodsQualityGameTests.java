package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.registry.ModComponents;
import com.hearthstead.settlement.work.GoodsQuality;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.List;

@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class GoodsQualityGameTests {
    @GameTest(template = "empty16", timeoutTicks = 40)
    public void vanillaCraftingCannotLaunderQualityAndSavedStacksRetainIt(GameTestHelper helper) {
        ItemStack basic = new ItemStack(Items.OAK_LOG, 2);
        helper.assertTrue(GoodsQuality.of(basic) == GoodsQuality.BASIC,
            "ordinary gathered goods must remain Basic");
        // Explicit quality fixture; real worker creation is exercised separately
        // by the existing Lumberer/Farmer provenance transaction tests.
        ItemStack fine = new ItemStack(Items.OAK_LOG, 2);
        fine.set(ModComponents.GOODS_QUALITY.get(), GoodsQuality.FINE);
        ItemStack split = fine.split(1);
        helper.assertTrue(fine.getCount() == 1 && split.getCount() == 1
                && GoodsQuality.of(split) == GoodsQuality.FINE
                && !ItemStack.isSameItemSameComponents(basic, split),
            "splitting preserves both units and quality; Basic cannot merge into Fine");
        var saved = split.saveOptional(helper.getLevel().registryAccess());
        helper.assertTrue(saved instanceof net.minecraft.nbt.CompoundTag,
            "a nonempty physical stack must encode as a compound");
        ItemStack loaded = ItemStack.parseOptional(helper.getLevel().registryAccess(),
            (net.minecraft.nbt.CompoundTag) saved);
        helper.assertTrue(loaded.getCount() == 1 && GoodsQuality.of(loaded) == GoodsQuality.FINE,
            "actual stack codec must preserve quality across a clean save");
        CraftingInput input = CraftingInput.of(1, 1, List.of(loaded));
        var recipe = helper.getLevel().getRecipeManager().getRecipeFor(RecipeType.CRAFTING,
            input, helper.getLevel());
        helper.assertTrue(recipe.isPresent(), "vanilla oak log crafting must be available");
        ItemStack output = recipe.orElseThrow().value().assemble(input, helper.getLevel().registryAccess());
        helper.assertTrue(output.is(Items.OAK_PLANKS) && output.getCount() == 4
                && GoodsQuality.of(output) == GoodsQuality.BASIC && loaded.getCount() == 1,
            "vanilla recipe preview is exactly four Basic planks and does not mutate the input");
        loaded.set(ModComponents.GOODS_QUALITY.get(), 99);
        helper.assertTrue(!GoodsQuality.meets(loaded, GoodsQuality.FINE),
            "a malformed in-memory component cannot satisfy a quality order");
        helper.succeed();
    }
}
