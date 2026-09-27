package com.hearthstead.registry;

import com.hearthstead.Hearthstead;
import com.hearthstead.item.BlessingSealItem;
import com.hearthstead.item.BuildPlanItem;
import com.hearthstead.item.HandbookItem;
import com.hearthstead.item.HearthBlockItem;
import com.hearthstead.item.JobEmblemItem;
import com.hearthstead.item.PoopStickItem;
import com.hearthstead.item.TrollToenailItem;
import com.hearthstead.item.WorkScepterItem;
import com.hearthstead.entity.Profession;
import com.hearthstead.settlement.state.BlessingId;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.food.FoodProperties;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(Registries.ITEM, Hearthstead.MODID);

    public static final DeferredHolder<Item, net.minecraft.world.item.FishingRodItem> FISHERS_ROD = ITEMS.register("fishers_rod",
        () -> new net.minecraft.world.item.FishingRodItem(new Item.Properties().durability(192)));

    public static final DeferredHolder<Item, Item> FISH_PORTION = ITEMS.register("fish_portion",
        () -> new Item(new Item.Properties().food(new FoodProperties.Builder().nutrition(5).saturationModifier(.6F).build())));

    public static final DeferredHolder<Item, Item> RIVER_PERCH = ITEMS.register("river_perch",
        () -> new Item(new Item.Properties().food(new FoodProperties.Builder().nutrition(2).saturationModifier(.1F).build())));
    public static final DeferredHolder<Item, Item> BROWN_TROUT = ITEMS.register("brown_trout",
        () -> new Item(new Item.Properties().food(new FoodProperties.Builder().nutrition(3).saturationModifier(.1F).build())));
    public static final DeferredHolder<Item, Item> SILVER_PIKE = ITEMS.register("silver_pike",
        () -> new Item(new Item.Properties().rarity(Rarity.UNCOMMON).food(new FoodProperties.Builder().nutrition(3).saturationModifier(.1F).build())));
    public static final DeferredHolder<Item, Item> GOLDEN_CHAR = ITEMS.register("golden_char",
        () -> new Item(new Item.Properties().rarity(Rarity.RARE).food(new FoodProperties.Builder().nutrition(3).saturationModifier(.1F).build())));

    public static final DeferredHolder<Item, Item> GOLD_COIN = ITEMS.register("gold_coin",
        () -> new Item(new Item.Properties().stacksTo(64)));

    /** Rare death-only Goblin trophies; neither has a crafting recipe. */
    public static final DeferredHolder<Item, PoopStickItem> POOP_STICK =
        ITEMS.register("poop_stick", () -> new PoopStickItem(
            new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));

    public static final DeferredHolder<Item, TrollToenailItem> TROLL_TOENAIL =
        ITEMS.register("troll_toenail", () -> new TrollToenailItem(
            new Item.Properties().stacksTo(16).rarity(Rarity.UNCOMMON).food(
                new FoodProperties.Builder().nutrition(1).saturationModifier(0.1F)
                    .alwaysEdible().build())));

    public static final DeferredHolder<Item, HearthBlockItem> HEARTH = ITEMS.register("hearth",
        () -> new HearthBlockItem(ModBlocks.HEARTH.get(), new Item.Properties()));





    public static final DeferredHolder<Item, HandbookItem> HANDBOOK = ITEMS.register("handbook",
        () -> new HandbookItem(new Item.Properties().stacksTo(1)));

    /** Timber Rights survey tool; server use remains settlement-tech gated. */
    public static final DeferredHolder<Item, WorkScepterItem> WORK_SCEPTER =
        ITEMS.register("work_scepter", () -> new WorkScepterItem(
            new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));

    /** Patrol routes (PATROL ROUTES lane): mark waypoints, pick the squad; server-authoritative. */
    public static final DeferredHolder<Item, com.hearthstead.item.PatrolMapItem> PATROL_MAP =
        ITEMS.register("patrol_map", () -> new com.hearthstead.item.PatrolMapItem(
            new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, DeferredSpawnEggItem> SETTLER_SPAWN_EGG =
        ITEMS.register("settler_spawn_egg",
            () -> new DeferredSpawnEggItem(ModEntities.SETTLER, 0x6B4F35, 0xC9B28A,
                new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem> PLAQUE = ITEMS.register("plaque",
        () -> new BlockItem(ModBlocks.PLAQUE.get(), new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem> FISH_RACK = ITEMS.register("fish_rack",
        () -> new BlockItem(ModBlocks.FISH_RACK.get(), new Item.Properties()));
    public static final DeferredHolder<Item, BlockItem> FISHERS_CHAIR = ITEMS.register("fishers_chair",
        () -> new BlockItem(ModBlocks.FISHERS_CHAIR.get(), new Item.Properties()));

    /** Hunter rework: one hunted body, carried home and butchered at the Lodge. */
    public static final DeferredHolder<Item, com.hearthstead.item.CarcassItem> CARCASS = ITEMS.register("carcass",
        () -> new com.hearthstead.item.CarcassItem(new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<Item, BlockItem> BUTCHERING_TABLE = ITEMS.register("butchering_table",
        () -> new BlockItem(ModBlocks.BUTCHERING_TABLE.get(), new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem> ALE_TAP = ITEMS.register("ale_tap",
        () -> new BlockItem(ModBlocks.ALE_TAP.get(), new Item.Properties()));

    /** D-006: the type lives on the plan, not on the plaque item above. */
    public static final DeferredHolder<Item, BuildPlanItem> BUILD_PLAN =
        ITEMS.register("build_plan", () -> new BuildPlanItem(new Item.Properties()));

    /** A crafted order for the Builder: pick a style, place it (building-plan lane, 26 Sep). */
    public static final DeferredHolder<Item, com.hearthstead.item.BuildingPlanItem> BUILDING_PLAN =
        ITEMS.register("building_plan", () -> new com.hearthstead.item.BuildingPlanItem(
            new Item.Properties().stacksTo(16)));

    // Mayor-issued physical job authorizations. Employment consumes exactly
    // one only after its own settler/workplace/revision checks have passed.
    public static final DeferredHolder<Item, JobEmblemItem> LUMBERER_EMBLEM =
        jobEmblem("lumberer_emblem", Profession.LUMBERER);
    public static final DeferredHolder<Item, JobEmblemItem> FARMER_EMBLEM =
        jobEmblem("farmer_emblem", Profession.FARMER);
    public static final DeferredHolder<Item, JobEmblemItem> FISHER_EMBLEM =
        jobEmblem("fisher_emblem", Profession.FISHER);
    public static final DeferredHolder<Item, JobEmblemItem> COURIER_EMBLEM =
        jobEmblem("courier_emblem", Profession.COURIER);
    public static final DeferredHolder<Item, JobEmblemItem> TRADER_EMBLEM =
        jobEmblem("trader_emblem", Profession.TRADER);
    public static final DeferredHolder<Item, JobEmblemItem> INNKEEPER_EMBLEM =
        jobEmblem("innkeeper_emblem", Profession.INNKEEPER);
    public static final DeferredHolder<Item, JobEmblemItem> GUARD_EMBLEM =
        jobEmblem("guard_emblem", Profession.GUARD);
    public static final DeferredHolder<Item, JobEmblemItem> ARCHER_EMBLEM =
        jobEmblem("archer_emblem", Profession.ARCHER);
    public static final DeferredHolder<Item, JobEmblemItem> HUNTER_EMBLEM =
        jobEmblem("hunter_emblem", Profession.HUNTER);
    public static final DeferredHolder<Item, JobEmblemItem> SAWYER_EMBLEM =
        jobEmblem("sawyer_emblem", Profession.SAWYER);
    public static final DeferredHolder<Item, JobEmblemItem> SCHOLAR_EMBLEM =
        jobEmblem("scholar_emblem", Profession.SCHOLAR);
    // BUILDER lane (plan/BUILDER.md).
    public static final DeferredHolder<Item, JobEmblemItem> BUILDER_EMBLEM =
        jobEmblem("builder_emblem", Profession.BUILDER);
    /**
     * The Builder's Plan: opens the blueprint catalog, the defense line
     * tool, Upgrade Orders and the site list. Not the plaque's build_plan
     * slip, which stays exactly what it was.
     */
    /** Resource Scroll: linked to a Builder's Hut, shows its site's needs from anywhere. */
    public static final DeferredHolder<Item, com.hearthstead.item.ResourceScrollItem> RESOURCE_SCROLL =
        ITEMS.register("resource_scroll", () -> new com.hearthstead.item.ResourceScrollItem(
            new Item.Properties().stacksTo(1)));
    /** Survey Rod: saves a hand-built structure as a Builder design ("Our Designs"). */
    public static final DeferredHolder<Item, com.hearthstead.item.SurveyRodItem> SURVEY_ROD =
        ITEMS.register("survey_rod", () -> new com.hearthstead.item.SurveyRodItem(
            new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<Item, com.hearthstead.item.BuildersPlanItem> BUILDERS_PLAN =
        ITEMS.register("builders_plan", () -> new com.hearthstead.item.BuildersPlanItem(
            new Item.Properties().stacksTo(1)));

    // Physical raid rewards. They deliberately have no recipes: the Hearth
    // is the only production source, and the target interaction is the only
    // consumer. Identical seals stack so recurring victories do not punish
    // the player's inventory, while 16 keeps each stack feeling precious.
    public static final DeferredHolder<Item, BlessingSealItem> WARDEN_OATH_SEAL =
        ITEMS.register("warden_oath_seal", () -> new BlessingSealItem(
            BlessingId.WARDEN_OATH,
            new Item.Properties().stacksTo(16).rarity(Rarity.EPIC)));

    public static final DeferredHolder<Item, BlessingSealItem> HEARTHWARD_SEAL =
        ITEMS.register("hearthward_seal", () -> new BlessingSealItem(
            BlessingId.HEARTHWARD,
            new Item.Properties().stacksTo(16).rarity(Rarity.EPIC)));

    public static final DeferredHolder<Item, BlessingSealItem> THORNED_ROADS_SEAL =
        ITEMS.register("thorned_roads_seal", () -> new BlessingSealItem(
            BlessingId.THORNED_ROADS,
            new Item.Properties().stacksTo(16).rarity(Rarity.EPIC)));

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

    private static DeferredHolder<Item, JobEmblemItem> jobEmblem(
            String id, Profession profession) {
        return ITEMS.register(id, () -> new JobEmblemItem(profession,
            new Item.Properties().stacksTo(16).rarity(Rarity.UNCOMMON)));
    }

    private ModItems() {
    }
}
