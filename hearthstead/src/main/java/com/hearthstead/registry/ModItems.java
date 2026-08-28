package com.hearthstead.registry;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Profession;
import com.hearthstead.item.HandbookItem;
import com.hearthstead.item.HearthBlockItem;
import com.hearthstead.item.ProfessionWritItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(ForgeRegistries.ITEMS, Hearthstead.MODID);

    public static final RegistryObject<Item> HEARTH = ITEMS.register("hearth",
        () -> new HearthBlockItem(ModBlocks.HEARTH.get(), new Item.Properties()));

    public static final RegistryObject<Item> WRIT_FARMER = ITEMS.register("writ_farmer",
        () -> new ProfessionWritItem(Profession.FARMER, new Item.Properties()));

    public static final RegistryObject<Item> WRIT_LUMBERER = ITEMS.register("writ_lumberer",
        () -> new ProfessionWritItem(Profession.LUMBERER, new Item.Properties()));

    public static final RegistryObject<Item> WRIT_GUARD = ITEMS.register("writ_guard",
        () -> new ProfessionWritItem(Profession.GUARD, new Item.Properties()));

    public static final RegistryObject<Item> HANDBOOK = ITEMS.register("handbook",
        () -> new HandbookItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<Item> SETTLER_SPAWN_EGG = ITEMS.register("settler_spawn_egg",
        () -> new ForgeSpawnEggItem(ModEntities.SETTLER, 0x6B4F35, 0xC9B28A, new Item.Properties()));

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }

    private ModItems() {
    }
}
