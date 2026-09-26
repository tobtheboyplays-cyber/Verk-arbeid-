package com.hearthstead.registry;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.combat.role.HealLedger;
import com.hearthstead.item.JobEmblemItem;
import com.hearthstead.item.role.BandageItem;
import com.hearthstead.item.role.RoleWeaponItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.Tiers;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Items and sounds of the four battle roles (plan/BATTLE-ROLES.md). Kept in
 * its own register so the battle-roles lane never has to rewrite the shared
 * {@link ModItems} / {@link ModSounds}; registered from {@code Hearthstead}.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class RoleItems {
    public static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(Registries.ITEM, Hearthstead.MODID);
    public static final DeferredRegister<SoundEvent> SOUNDS =
        DeferredRegister.create(Registries.SOUND_EVENT, Hearthstead.MODID);

    // ---------------------------------------------------------- weapons ---
    public static final DeferredHolder<Item, RoleWeaponItem> WOODEN_SPEAR =
        ITEMS.register("wooden_spear", () -> new RoleWeaponItem(RoleWeaponItem.Kind.SPEAR,
            Tiers.WOOD, new Item.Properties()));
    public static final DeferredHolder<Item, RoleWeaponItem> IRON_SPEAR =
        ITEMS.register("iron_spear", () -> new RoleWeaponItem(RoleWeaponItem.Kind.SPEAR,
            Tiers.IRON, new Item.Properties()));
    public static final DeferredHolder<Item, RoleWeaponItem> DIAMOND_SPEAR =
        ITEMS.register("diamond_spear", () -> new RoleWeaponItem(RoleWeaponItem.Kind.SPEAR,
            Tiers.DIAMOND, new Item.Properties()));
    public static final DeferredHolder<Item, RoleWeaponItem> IRON_LONGSWORD =
        ITEMS.register("iron_longsword", () -> new RoleWeaponItem(RoleWeaponItem.Kind.LONGSWORD,
            Tiers.IRON, new Item.Properties()));
    public static final DeferredHolder<Item, RoleWeaponItem> DIAMOND_LONGSWORD =
        ITEMS.register("diamond_longsword", () -> new RoleWeaponItem(RoleWeaponItem.Kind.LONGSWORD,
            Tiers.DIAMOND, new Item.Properties()));

    // --------------------------------------------------------- supplies ---
    public static final DeferredHolder<Item, BandageItem> BANDAGE =
        ITEMS.register("bandage", () -> new BandageItem(HealLedger.BANDAGE_TOTAL,
            new Item.Properties().stacksTo(16)));
    public static final DeferredHolder<Item, Item> RUNE_STONE =
        ITEMS.register("rune_stone", () -> new Item(new Item.Properties().stacksTo(16)
            .rarity(Rarity.UNCOMMON)) {
            @Override
            public boolean isFoil(ItemStack stack) {
                return true;
            }
        });

    // ---------------------------------------------------------- emblems ---
    public static final DeferredHolder<Item, JobEmblemItem> SPEARMAN_EMBLEM =
        emblem("spearman_emblem", Profession.SPEARMAN);
    public static final DeferredHolder<Item, JobEmblemItem> LONGSWORDSMAN_EMBLEM =
        emblem("longswordsman_emblem", Profession.LONGSWORDSMAN);
    public static final DeferredHolder<Item, JobEmblemItem> HEALER_EMBLEM =
        emblem("healer_emblem", Profession.HEALER);
    public static final DeferredHolder<Item, JobEmblemItem> RUNE_MAGE_EMBLEM =
        emblem("rune_mage_emblem", Profession.RUNE_MAGE);

    // ------------------------------------------------------------- tags ---
    public static final TagKey<Item> SPEARS = TagKey.create(Registries.ITEM, Hearthstead.id("spears"));
    public static final TagKey<Item> LONGSWORDS = TagKey.create(Registries.ITEM, Hearthstead.id("longswords"));
    public static final TagKey<Item> HEALING_HERBS = TagKey.create(Registries.ITEM, Hearthstead.id("healing_herbs"));
    public static final TagKey<Item> MEDIC_SUPPLIES = TagKey.create(Registries.ITEM, Hearthstead.id("medic_supplies"));

    // ----------------------------------------------------------- sounds ---
    public static final DeferredHolder<SoundEvent, SoundEvent> SPEAR_THRUST_SOUND = sound("role.spear_thrust");
    public static final DeferredHolder<SoundEvent, SoundEvent> LONGSWORD_CLEAVE_SOUND = sound("role.longsword_cleave");
    public static final DeferredHolder<SoundEvent, SoundEvent> RUNE_CAST_SOUND = sound("role.rune_cast");
    public static final DeferredHolder<SoundEvent, SoundEvent> FIREBOLT_SOUND = sound("role.firebolt_impact");
    public static final DeferredHolder<SoundEvent, SoundEvent> WARD_SOUND = sound("role.ward_up");
    public static final DeferredHolder<SoundEvent, SoundEvent> FROST_SOUND = sound("role.frost_rune");
    public static final DeferredHolder<SoundEvent, SoundEvent> BANDAGE_SOUND = sound("role.bandage");
    // Hero Captain hooks (plan/CAPTAIN.md); the sound lane swaps in bespoke files.
    public static final DeferredHolder<SoundEvent, SoundEvent> CAPTAIN_WINDUP_SOUND = sound("captain.windup");
    public static final DeferredHolder<SoundEvent, SoundEvent> CAPTAIN_IMPACT_SOUND = sound("captain.impact");
    public static final DeferredHolder<SoundEvent, SoundEvent> CAPTAIN_RALLY_SOUND = sound("captain.rally");
    public static final DeferredHolder<SoundEvent, SoundEvent> CAPTAIN_PROMOTED_SOUND = sound("captain.promoted");

    private RoleItems() {
    }

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
        SOUNDS.register(bus);
    }

    private static DeferredHolder<Item, JobEmblemItem> emblem(String id, Profession profession) {
        return ITEMS.register(id, () -> new JobEmblemItem(profession,
            new Item.Properties().stacksTo(16).rarity(Rarity.UNCOMMON)));
    }

    private static DeferredHolder<SoundEvent, SoundEvent> sound(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(Hearthstead.id(name)));
    }

    public static boolean isSpear(ItemStack stack) {
        return !stack.isEmpty() && (stack.getItem() instanceof RoleWeaponItem w && w.isSpear()
            || stack.is(SPEARS));
    }

    public static boolean isLongsword(ItemStack stack) {
        return !stack.isEmpty() && (stack.getItem() instanceof RoleWeaponItem w && w.isLongsword()
            || stack.is(LONGSWORDS));
    }

    @SubscribeEvent
    public static void creativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() != ModCreativeTabs.MAIN.getKey()) {
            return;
        }
        for (DeferredHolder<Item, ? extends Item> holder : ITEMS.getEntries().stream()
                .map(h -> (DeferredHolder<Item, ? extends Item>) h).toList()) {
            event.accept(holder.get());
        }
    }
}
