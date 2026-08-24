package com.hearthstead.registry;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Profession;
import com.hearthstead.item.BuildPlanItem;
import com.hearthstead.item.HandbookItem;
import com.hearthstead.item.HearthBlockItem;
import com.hearthstead.item.ProfessionWritItem;
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

    public static final DeferredHolder<Item, ProfessionWritItem> WRIT_FARMER =
        ITEMS.register("writ_farmer",
            () -> new ProfessionWritItem(Profession.FARMER, new Item.Properties()));

    public static final DeferredHolder<Item, ProfessionWritItem> WRIT_LUMBERER =
        ITEMS.register("writ_lumberer",
            () -> new ProfessionWritItem(Profession.LUMBERER, new Item.Properties()));

    public static final DeferredHolder<Item, ProfessionWritItem> WRIT_GUARD =
        ITEMS.register("writ_guard",
            () -> new ProfessionWritItem(Profession.GUARD, new Item.Properties()));

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

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }

    private ModItems() {
    }
}
