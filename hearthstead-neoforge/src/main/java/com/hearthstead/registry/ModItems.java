package com.hearthstead.registry;

import com.hearthstead.Hearthstead;
import com.hearthstead.item.BuildPlanItem;
import com.hearthstead.item.HandbookItem;
import com.hearthstead.item.HearthBlockItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(Registries.ITEM, Hearthstead.MODID);

    public static final DeferredHolder<Item, HearthBlockItem> HEARTH = ITEMS.register("hearth",
        () -> new HearthBlockItem(ModBlocks.HEARTH.get(), new Item.Properties()));





    public static final DeferredHolder<Item, HandbookItem> HANDBOOK = ITEMS.register("handbook",
        () -> new HandbookItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, DeferredSpawnEggItem> SETTLER_SPAWN_EGG =
        ITEMS.register("settler_spawn_egg",
            () -> new DeferredSpawnEggItem(ModEntities.SETTLER, 0x6B4F35, 0xC9B28A,
                new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem> PLAQUE = ITEMS.register("plaque",
        () -> new BlockItem(ModBlocks.PLAQUE.get(), new Item.Properties()));

    /** D-006: the type lives on the plan, not on the plaque item above. */
    public static final DeferredHolder<Item, BuildPlanItem> BUILD_PLAN =
        ITEMS.register("build_plan", () -> new BuildPlanItem(new Item.Properties()));

    // ---------------------------------------------------------- SLICE CHAINS ---
    //
    // Six intermediate goods, chosen and bound by the FLOWS.md constitution
    // (docs/project/FLOWS.md) so every refining building gets a rough path
    // (works alone) and a fed path (a neighbour's product makes it better,
    // never required -- D-007). See Production.java for the recipes and
    // docs/project/PLAN_CHAINS.md for the full reasoning and the acyclicity
    // proof.
    //
    // FLOUR, IRON_BLOOM, TIMBER_BEAM, CURED_HIDE and WOOL_BOLT are plain
    // crafting/refining goods -- no food value, matching leather, ingots and
    // planks. MALT is the same. ALE is the one addition beyond FLOWS' six: the
    // brewery's fed path (malt -> ale) has to end SOMEWHERE, and vanilla has
    // no ale-equivalent item the way it already has bread for flour or an
    // ingot for iron_bloom -- so a seventh, terminal item is unavoidable to
    // give the malt chain anywhere to arrive. It carries no new mechanic
    // (plain Item, like the others); a future slice can make it drinkable.

    public static final DeferredHolder<Item, Item> FLOUR =
        ITEMS.register("flour", () -> new Item(new Item.Properties()));

    public static final DeferredHolder<Item, Item> MALT =
        ITEMS.register("malt", () -> new Item(new Item.Properties()));

    public static final DeferredHolder<Item, Item> ALE =
        ITEMS.register("ale", () -> new Item(new Item.Properties()));

    public static final DeferredHolder<Item, Item> IRON_BLOOM =
        ITEMS.register("iron_bloom", () -> new Item(new Item.Properties()));

    public static final DeferredHolder<Item, Item> TIMBER_BEAM =
        ITEMS.register("timber_beam", () -> new Item(new Item.Properties()));

    public static final DeferredHolder<Item, Item> CURED_HIDE =
        ITEMS.register("cured_hide", () -> new Item(new Item.Properties()));

    public static final DeferredHolder<Item, Item> WOOL_BOLT =
        ITEMS.register("wool_bolt", () -> new Item(new Item.Properties()));

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }

    private ModItems() {
    }
}
