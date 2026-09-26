package com.hearthstead.registry;

import com.hearthstead.Hearthstead;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Display-only trade tools for the settlers' work animations (anim lane, owner 2026-09-26).
 * They exist only so a clip's {@code hearthstead_props} can draw a real-looking hammer, mallet,
 * plane, cleaver ... in a settler's hand (client.motion.MotionProp / MotionPropLayer). They are in
 * no creative tab, have no recipe and no loot, and nothing ever puts one into an inventory: the
 * prop hook reads them by id and renders them, so there is no dupe or loss path.
 */
public final class PropItems {
    public static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(Registries.ITEM, Hearthstead.MODID);

    public static final DeferredHolder<Item, Item> SMITH_HAMMER = prop("prop_smith_hammer");
    public static final DeferredHolder<Item, Item> HAMMER = prop("prop_hammer");
    public static final DeferredHolder<Item, Item> MALLET = prop("prop_mallet");
    public static final DeferredHolder<Item, Item> CHISEL = prop("prop_chisel");
    public static final DeferredHolder<Item, Item> PLANE = prop("prop_plane");
    public static final DeferredHolder<Item, Item> CLEAVER = prop("prop_cleaver");
    public static final DeferredHolder<Item, Item> SCRAPER = prop("prop_scraper");
    public static final DeferredHolder<Item, Item> PEEL = prop("prop_peel");
    public static final DeferredHolder<Item, Item> LADLE = prop("prop_ladle");
    public static final DeferredHolder<Item, Item> SHUTTLE = prop("prop_shuttle");
    public static final DeferredHolder<Item, Item> MASH_PADDLE = prop("prop_mash_paddle");
    public static final DeferredHolder<Item, Item> SACK_SCOOP = prop("prop_sack_scoop");

    private PropItems() {
    }

    private static DeferredHolder<Item, Item> prop(String id) {
        return ITEMS.register(id, () -> new Item(new Item.Properties().stacksTo(1)));
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}
