package com.hearthstead.building;

import com.hearthstead.registry.ModItems;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [economy] owner decision 26 Sep: the weaver gets a basic recipe that uses
 * the wool a herder shears (any colour), because nothing in the village makes
 * string. Pins the recipe lookup: it exists, takes every colour, makes wool
 * bolts at a worse ratio than white wool's own recipe, and comes after it.
 */
class ProductionWeaverRecipeTest {

    private static Production.Recipe byId(List<Production.Recipe> recipes, String id) {
        for (Production.Recipe recipe : recipes) {
            if (recipe.id().equals(id)) {
                return recipe;
            }
        }
        return null;
    }

    @Test
    void weaverHasAnAnyWoolBoltRecipe() {
        List<Production.Recipe> weaver = Production.of(BuildingType.WEAVER);
        Production.Recipe any = byId(weaver, "wool_bolt_any");
        assertNotNull(any, "the weaver must have a recipe that takes any wool");
        assertEquals(ModItems.WOOL_BOLT.get(), any.output());
        assertEquals(4, any.inputCount());
        assertEquals(2, any.outputCount());
    }

    @Test
    void anyWoolAcceptsEveryColourAndNothingElse() {
        Production.Recipe any = byId(Production.of(BuildingType.WEAVER), "wool_bolt_any");
        assertNotNull(any);
        for (var wool : List.of(Items.WHITE_WOOL, Items.ORANGE_WOOL, Items.MAGENTA_WOOL,
                Items.LIGHT_BLUE_WOOL, Items.YELLOW_WOOL, Items.LIME_WOOL, Items.PINK_WOOL,
                Items.GRAY_WOOL, Items.LIGHT_GRAY_WOOL, Items.CYAN_WOOL, Items.PURPLE_WOOL,
                Items.BLUE_WOOL, Items.BROWN_WOOL, Items.GREEN_WOOL, Items.RED_WOOL,
                Items.BLACK_WOOL)) {
            assertTrue(any.input().test(new ItemStack(wool)), wool + " must be accepted");
        }
        assertFalse(any.input().test(new ItemStack(Items.STRING)), "string is not wool");
        assertFalse(any.input().test(new ItemStack(Items.WHITE_CARPET)), "carpet is not wool");
    }

    @Test
    void whiteWoolKeepsTheBetterRecipeFirst() {
        List<Production.Recipe> weaver = Production.of(BuildingType.WEAVER);
        Production.Recipe white = byId(weaver, "wool_bolt");
        Production.Recipe any = byId(weaver, "wool_bolt_any");
        assertNotNull(white);
        assertNotNull(any);
        assertTrue(weaver.indexOf(white) < weaver.indexOf(any),
            "white wool's 3 -> 2 recipe must be listed before the any-wool one");
        double whitePerBolt = (double) white.inputCount() / white.outputCount();
        double anyPerBolt = (double) any.inputCount() / any.outputCount();
        assertTrue(anyPerBolt > whitePerBolt, "any-wool must cost more wool per bolt than white");
        // No value-minting loop: nothing the weaver makes is itself wool it
        // could feed back into the any-wool recipe except the string->wool
        // recipe's WHITE_WOOL, which comes from string, not bolts.
        assertFalse(any.input().test(new ItemStack(ModItems.WOOL_BOLT.get())));
    }
}
