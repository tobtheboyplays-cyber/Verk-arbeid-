package com.hearthstead.registry;

import com.hearthstead.Hearthstead;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
        DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Hearthstead.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN =
        TABS.register("hearthstead", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.hearthstead"))
            .icon(() -> new ItemStack(ModItems.HEARTH.get()))
            .displayItems((params, output) -> {
                // EVERYTHING the mod registers, in play order -- found live
                // (2026-08-26, the owner's first demo session): this used to
                // list three items, so the plaque, the build plans and every
                // chain good were simply absent from creative, and the only
                // way to test a building was to craft its plan in survival.
                // The tab is the mod's own index; an item missing here reads
                // as an item that does not exist.
                output.accept(ModItems.HEARTH.get());
                output.accept(ModItems.GOLD_COIN.get());
                output.accept(ModItems.POOP_STICK.get());
                output.accept(ModItems.TROLL_TOENAIL.get());
                output.accept(ModItems.PLAQUE.get());
                output.accept(ModItems.ALE_TAP.get());
                output.accept(ModItems.FISH_RACK.get());
                output.accept(ModItems.FISHERS_CHAIR.get());
                output.accept(ModItems.BUTCHERING_TABLE.get());
                output.accept(ModItems.FISHERS_ROD.get());
                output.accept(ModItems.FISH_PORTION.get());
                output.accept(ModItems.FISHER_EMBLEM.get());
                output.accept(ModItems.RIVER_PERCH.get());
                output.accept(ModItems.BROWN_TROUT.get());
                output.accept(ModItems.SILVER_PIKE.get());
                output.accept(ModItems.GOLDEN_CHAR.get());
                // A sample hunted carcass (a bare one carries no yield and is inert).
                output.accept(com.hearthstead.item.CarcassItem.create(com.hearthstead.item.CarcassData.of(
                    net.minecraft.world.entity.EntityType.COW, java.util.List.of(
                        new ItemStack(net.minecraft.world.item.Items.BEEF, 3),
                        new ItemStack(net.minecraft.world.item.Items.LEATHER, 1)))));
                output.accept(ModItems.BUILD_PLAN.get());
                output.accept(ModItems.HANDBOOK.get());
                output.accept(ModItems.WORK_SCEPTER.get());
                output.accept(ModItems.PATROL_MAP.get());
                output.accept(ModItems.SETTLER_SPAWN_EGG.get());
                output.accept(ModItems.LUMBERER_EMBLEM.get());
                output.accept(ModItems.FARMER_EMBLEM.get());
                output.accept(ModItems.COURIER_EMBLEM.get());
                output.accept(ModItems.INNKEEPER_EMBLEM.get());
                output.accept(ModItems.TRADER_EMBLEM.get());
                output.accept(ModItems.GUARD_EMBLEM.get());
                output.accept(ModItems.ARCHER_EMBLEM.get());
                output.accept(ModItems.HUNTER_EMBLEM.get());
                output.accept(ModItems.SAWYER_EMBLEM.get());
                output.accept(ModItems.SCHOLAR_EMBLEM.get());
                output.accept(ModItems.BUILDER_EMBLEM.get());
                // The 15 extended-trade emblems (TradeEmblemItems, own register).
                for (com.hearthstead.entity.Profession profession
                        : com.hearthstead.entity.Profession.values()) {
                    ItemStack emblem = TradeEmblemItems.stackFor(profession);
                    if (!emblem.isEmpty()) output.accept(emblem);
                }
                output.accept(ModItems.BUILDERS_PLAN.get());
                output.accept(ModItems.SURVEY_ROD.get());
                output.accept(ModItems.RESOURCE_SCROLL.get());
                output.accept(ModItems.WARDEN_OATH_SEAL.get());
                output.accept(ModItems.HEARTHWARD_SEAL.get());
                output.accept(ModItems.THORNED_ROADS_SEAL.get());
                // The chain goods, in FLOWS order.
                output.accept(ModItems.FLOUR.get());
                output.accept(ModItems.MALT.get());
                output.accept(ModItems.ALE.get());
                output.accept(ModItems.IRON_BLOOM.get());
                output.accept(ModItems.TIMBER_BEAM.get());
                output.accept(ModItems.CURED_HIDE.get());
                output.accept(ModItems.WOOL_BOLT.get());
                // One pre-stamped Build Plan per building type, so any
                // building can be tested from creative without crafting its
                // plan first -- the fastest route from "I want to see the
                // sawmill" to a green plaque.
                for (com.hearthstead.building.BuildingType type
                        : com.hearthstead.building.BuildingType.values()) {
                    output.accept(com.hearthstead.block.PlaqueItemData.stamped(
                        new ItemStack(ModItems.BUILD_PLAN.get()), type));
                }
                for (com.hearthstead.building.BuildingType type : com.hearthstead.item.BuildingPlans.types()) {
                    output.accept(com.hearthstead.item.BuildingPlanItem.of(type));
                }
            })
            .build());

    public static void register(IEventBus bus) {
        TABS.register(bus);
    }

    private ModCreativeTabs() {
    }
}
