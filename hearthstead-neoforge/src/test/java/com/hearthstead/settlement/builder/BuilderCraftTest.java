package com.hearthstead.settlement.builder;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** "Builder lager det selv": vanilla ratios, exact and conserved. */
class BuilderCraftTest {

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static SimpleContainer hut(ItemStack... stacks) {
        SimpleContainer c = new SimpleContainer(27);
        for (ItemStack s : stacks) {
            c.addItem(s);
        }
        return c;
    }

    @Test
    void stairsFromPlanksUseTheVanillaRatio() {
        SimpleContainer hut = hut(new ItemStack(Items.OAK_PLANKS, 12));
        List<Container> all = List.of(hut);
        int made = BuilderCraft.craft(all, all, Items.OAK_STAIRS, 8, 8);
        assertEquals(8, made);
        assertEquals(8, BuilderStock.count(all, Items.OAK_STAIRS));
        assertEquals(0, BuilderStock.count(all, Items.OAK_PLANKS));
    }

    @Test
    void fencesMakeTheirOwnSticksAndKeepTheSpare() {
        SimpleContainer hut = hut(new ItemStack(Items.SPRUCE_PLANKS, 6));
        List<Container> all = List.of(hut);
        int made = BuilderCraft.craft(all, all, Items.SPRUCE_FENCE, 3, 8);
        assertEquals(3, made);
        assertEquals(0, BuilderStock.count(all, Items.SPRUCE_PLANKS), "2 planks for sticks + 4 for the fence");
        assertEquals(2, BuilderStock.count(all, Items.STICK), "4 sticks made, 2 used, 2 kept");
    }

    @Test
    void doorsAndPlanksFromLogs() {
        SimpleContainer hut = hut(new ItemStack(Items.OAK_LOG, 2));
        List<Container> all = List.of(hut);
        assertEquals(3, BuilderCraft.craft(all, all, Items.OAK_DOOR, 3, 8));
        assertEquals(0, BuilderStock.count(all, Items.OAK_LOG), "8 planks from 2 logs, 6 used");
        assertEquals(2, BuilderStock.count(all, Items.OAK_PLANKS));
    }

    @Test
    void glassIsSmeltedSlowlyWithVillageFuel() {
        SimpleContainer hut = hut(new ItemStack(Items.SAND, 10), new ItemStack(Items.COAL, 1));
        List<Container> all = List.of(hut);
        assertEquals(8, BuilderCraft.craft(all, all, Items.GLASS, 10, 8), "one furnace load: 8");
        assertEquals(2, BuilderStock.count(all, Items.SAND));
        assertEquals(0, BuilderStock.count(all, Items.COAL));
        assertEquals(Items.CHARCOAL, BuilderCraft.missingInput(all, Items.GLASS), "then fuel is what is missing");
    }

    @Test
    void withoutSandTheMissingInputIsSand() {
        List<Container> all = List.of(hut(new ItemStack(Items.COAL, 4)));
        assertEquals(Items.SAND, BuilderCraft.missingInput(all, Items.GLASS));
        assertEquals(0, BuilderCraft.craft(all, all, Items.GLASS, 4, 8));
    }

    @Test
    void stoneShapesAreNotAWoodworkersJob() {
        assertNull(BuilderCraft.recipeFor(Items.COBBLESTONE_STAIRS));
        assertNull(BuilderCraft.recipeFor(Items.IRON_DOOR));
    }
}
