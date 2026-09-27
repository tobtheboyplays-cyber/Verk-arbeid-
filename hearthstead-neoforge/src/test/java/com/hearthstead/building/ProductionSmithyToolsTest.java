package com.hearthstead.building;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Owner decision 26 Sep ("Steinhakke hos smeden"): the smithy forges a stone
 * pickaxe from cobblestone (fired with its wood fuel) so a town with no iron
 * can still arm its first Miner, and forges the Herder's shears from iron.
 * Pins the table shape; the order-driven flow is in
 * {@code SmithyStonePickaxeGameTests}.
 */
class ProductionSmithyToolsTest {

    private static Production.Recipe byId(String id) {
        for (Production.Recipe recipe : Production.of(BuildingType.SMITHY)) {
            if (recipe.id().equals(id)) {
                return recipe;
            }
        }
        return null;
    }

    @Test
    void stonePickaxeIsForgedFromCobblestoneAlone() {
        Production.Recipe stone = byId(Production.STONE_PICKAXE_RECIPE);
        assertNotNull(stone, "the smithy must know the stone pickaxe");
        assertEquals(Items.STONE_PICKAXE, stone.output());
        assertEquals(1, stone.outputCount());
        assertEquals(3, stone.inputCount());
        assertTrue(stone.input().test(new ItemStack(Items.COBBLESTONE)));
        assertFalse(stone.input().test(new ItemStack(Items.IRON_INGOT)), "no iron in a stone pick");
        assertTrue(Fuel.burns(BuildingType.SMITHY), "the wood half: the forge burns fuel per batch");
    }

    @Test
    void stonePickaxeIsOrderOnlyAndNothingElseIs() {
        for (BuildingType type : BuildingType.values()) {
            for (Production.Recipe recipe : Production.of(type)) {
                // Spec correction (supply-chain audit, owner 26 Sep): the
                // Builder-supply recipes (prefix bp_) are order-only too.
                boolean expected = recipe.id().equals(Production.STONE_PICKAXE_RECIPE) && type == BuildingType.SMITHY
                    || recipe.id().startsWith(Production.BUILD_SUPPLY_PREFIX);
                assertEquals(expected, Production.orderOnly(recipe), type + "/" + recipe.id());
            }
        }
    }

    @Test
    void shearsAreForgedFromIron() {
        Production.Recipe shears = byId("shears");
        assertNotNull(shears, "the Herder's shears must have a maker");
        assertEquals(Items.SHEARS, shears.output());
        assertTrue(shears.input().test(new ItemStack(Items.IRON_INGOT)));
        assertEquals(2, shears.inputCount());
    }

    @Test
    void swordStaysLastSoTheIdleSmithyStillAsksForIron() {
        // The idle ask reads the last NON-order-only recipe
        // (CraftingOrderService.materialAndFuelNeeds); order-only Builder
        // supply recipes may follow it.
        List<Production.Recipe> smithy = new java.util.ArrayList<>();
        for (Production.Recipe recipe : Production.of(BuildingType.SMITHY)) {
            if (!Production.orderOnly(recipe)) smithy.add(recipe);
        }
        Production.Recipe last = smithy.get(smithy.size() - 1);
        assertEquals("sword", last.id());
        assertTrue(last.input().test(new ItemStack(Items.IRON_INGOT)));
    }
}
