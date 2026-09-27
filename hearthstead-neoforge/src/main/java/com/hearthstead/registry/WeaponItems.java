package com.hearthstead.registry;

import com.hearthstead.Hearthstead;
import com.hearthstead.item.role.RoleWeaponItem;
import com.hearthstead.item.weapon.CaptainWeaponItem;
import com.hearthstead.item.weapon.WeaponType;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.Tiers;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Captain weapons in every material tier (weapons lane, 26 Sep; plan/WEAPONS.md): short sword,
 * double axe, halberd and warhammer x wooden / stone / iron / golden / diamond / netherite, plus
 * the four longsword tiers the battle-roles lane did not have yet (iron and diamond longswords
 * stay in {@link RoleItems}). Own register so no shared registry file is rewritten; registered
 * from {@code Hearthstead}. Art and models: tools/weapons/gen_weapons.py.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class WeaponItems {
    public static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(Registries.ITEM, Hearthstead.MODID);

    /** Material tiers in the vanilla naming ({@code golden_}, {@code wooden_}). */
    public enum Material {
        WOODEN("wooden", Tiers.WOOD),
        STONE("stone", Tiers.STONE),
        IRON("iron", Tiers.IRON),
        GOLDEN("golden", Tiers.GOLD),
        DIAMOND("diamond", Tiers.DIAMOND),
        NETHERITE("netherite", Tiers.NETHERITE);

        public final String prefix;
        public final Tier tier;

        Material(String prefix, Tier tier) {
            this.prefix = prefix;
            this.tier = tier;
        }
    }

    private static final Map<WeaponType, Map<Material, DeferredHolder<Item, CaptainWeaponItem>>> BY_TYPE =
        new EnumMap<>(WeaponType.class);
    private static final Map<Material, DeferredHolder<Item, RoleWeaponItem>> EXTRA_LONGSWORDS =
        new EnumMap<>(Material.class);

    static {
        for (WeaponType type : WeaponType.values()) {
            Map<Material, DeferredHolder<Item, CaptainWeaponItem>> tiers = new EnumMap<>(Material.class);
            for (Material m : Material.values()) {
                String id = m.prefix + "_" + type.id();
                tiers.put(m, ITEMS.register(id, () -> new CaptainWeaponItem(type, m.tier, props(m))));
            }
            BY_TYPE.put(type, tiers);
        }
        for (Material m : new Material[] {Material.WOODEN, Material.STONE, Material.GOLDEN, Material.NETHERITE}) {
            EXTRA_LONGSWORDS.put(m, ITEMS.register(m.prefix + "_longsword",
                () -> new RoleWeaponItem(RoleWeaponItem.Kind.LONGSWORD, m.tier, props(m))));
        }
    }

    // ------------------------------------------------------------- tags
    public static final TagKey<Item> CAPTAIN_DUAL_SWORDS = tag("captain/dual_swords");
    public static final TagKey<Item> CAPTAIN_GREAT_AXES = tag("captain/great_axes");
    public static final TagKey<Item> CAPTAIN_HALBERDS = tag("captain/halberds");
    public static final TagKey<Item> CAPTAIN_WARHAMMERS = tag("captain/warhammers");
    public static final TagKey<Item> CAPTAIN_BOWS = tag("captain/bows");
    /** Every heavy captain weapon (double axe, halberd, warhammer): sharp-weapon enchantments. */
    public static final TagKey<Item> HEAVY_WEAPONS = tag("heavy_weapons");

    private WeaponItems() {
    }

    private static Item.Properties props(Material m) {
        Item.Properties p = new Item.Properties();
        return m == Material.NETHERITE ? p.fireResistant() : p;
    }

    private static TagKey<Item> tag(String path) {
        return TagKey.create(Registries.ITEM, Hearthstead.id(path));
    }

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }

    public static CaptainWeaponItem get(WeaponType type, Material material) {
        return BY_TYPE.get(type).get(material).get();
    }

    /** Registry ids of every item this register owns, in creative-tab order. */
    public static List<String> ids() {
        List<String> out = new ArrayList<>();
        for (DeferredHolder<Item, ? extends Item> h : ITEMS.getEntries()) {
            out.add(h.getId().getPath());
        }
        return Collections.unmodifiableList(out);
    }

    /** type -> material -> item, for tests and the handbook tier tables. */
    public static Map<WeaponType, Map<Material, DeferredHolder<Item, CaptainWeaponItem>>> byType() {
        return Collections.unmodifiableMap(BY_TYPE);
    }

    public static Map<Material, DeferredHolder<Item, RoleWeaponItem>> extraLongswords() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(EXTRA_LONGSWORDS));
    }

    @SubscribeEvent
    public static void creativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() != ModCreativeTabs.MAIN.getKey()) {
            return;
        }
        for (DeferredHolder<Item, ? extends Item> holder : ITEMS.getEntries()) {
            if (holder.isBound()) {
                event.accept(holder.get());
            }
        }
    }
}
